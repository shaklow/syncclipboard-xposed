package io.github.erenche.syncclipboard.xposed.api

import io.github.erenche.syncclipboard.common.model.ClipboardContent
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.model.HistoryRecordDto
import io.github.erenche.syncclipboard.common.model.HistoryRecordUpdateDto
import io.github.erenche.syncclipboard.common.model.HistoryStatisticsDto
import io.github.erenche.syncclipboard.common.model.ProfileDto
import io.github.erenche.syncclipboard.common.util.HashUtils
import io.github.erenche.syncclipboard.common.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * S3 兼容存储客户端 — 通过 S3 REST API 操作对象。
 *
 * 端口自 C# S3Adapter.cs，使用 AWS Signature Version 4 对请求签名。
 * 与 SyncClipboard 服务端/桌面端使用的路径一致：
 * - 剪贴板配置文件: SyncClipboard.json
 * - 文件目录: file/
 */
class S3Client(
    private val serviceUrl: String? = null,
    private val region: String,
    private val bucketName: String,
    private val objectPrefix: String? = null,
    private val forcePathStyle: Boolean = false,
    private val accessKeyId: String,
    private val secretAccessKey: String
) : SyncClipboardApi {

    companion object {
        private const val TAG = "S3Client"
        private const val CLIPBOARD_KEY = "SyncClipboard.json"
        private const val FILE_FOLDER = "file"
        private const val SERVICE = "s3"
        private const val ALGORITHM = "AWS4-HMAC-SHA256"
        /** 使用未签名 payload，避免大文件双重读取，与 C# DisablePayloadSigning 一致 */
        private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
    }

    /** 解析 endpoint 为 scheme://host[:port] */
    private val baseEndpoint: URI = URI(
        (serviceUrl?.trimEnd('/').takeIf { !it.isNullOrBlank() }
            ?: "https://s3.$region.amazonaws.com")
    )

    /** 是否为自定义端点（非 AWS 默认 S3） */
    private val isCustomEndpoint: Boolean = !serviceUrl.isNullOrBlank()

    private val client = HttpClient(OkHttp) {
        install(Logging) {
            level = LogLevel.INFO
        }
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ─── SigV4 签名工具 ──────────────────────────────────────

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun signingKey(dateStamp: String): ByteArray {
        val kSecret = ("AWS4" + secretAccessKey).toByteArray(Charsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, SERVICE)
        return hmacSha256(kService, "aws4_request")
    }

    /**
     * 构建 S3 SigV4 签名并发送请求。
     */
    private suspend fun sendSignedRequest(
        method: HttpMethod,
        objectKey: String,
        queryString: String = "",
        body: ByteArray? = null,
        contentType: String? = null
    ): HttpResponse {
        // 构建完整对象 key（含 prefix）
        val fullKey = buildObjectKey(objectKey)
        // 编码路径（逐段编码，保留 /）
        val encodedKey = encodeS3Key(fullKey)

        // 构建请求 URL 和签名用的 host/path
        val scheme = baseEndpoint.scheme
        val host = baseEndpoint.host
        val port = baseEndpoint.port
        val portSuffix = if (port > 0) ":$port" else ""

        val pathStyle = forcePathStyle || isCustomEndpoint

        // canonicalPath: 签名用的路径（path style 含 bucketName，virtual hosted 不含）
        val canonicalPath = if (pathStyle) {
            "/$bucketName/$encodedKey"
        } else {
            "/$encodedKey"
        }

        // host header: virtual hosted style 包含 bucketName
        val hostHeader = if (pathStyle) {
            "$host$portSuffix"
        } else {
            "$bucketName.$host$portSuffix"
        }

        // 请求 URL
        val url = if (pathStyle) {
            "$scheme://$host$portSuffix/$bucketName/$encodedKey"
        } else {
            "$scheme://$bucketName.$host$portSuffix/$encodedKey"
        } + if (queryString.isNotBlank()) "?$queryString" else ""

        // 时间戳
        val now = Date()
        val amzDate = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(now)
        val dateStamp = amzDate.substring(0, 8)

        // 构建头部（小写键名）
        val headers = mutableMapOf(
            "host" to hostHeader,
            "x-amz-content-sha256" to UNSIGNED_PAYLOAD,
            "x-amz-date" to amzDate
        )
        contentType?.let { headers["content-type"] = it }

        // 规范化头部
        val canonicalHeaders = headers.entries.sortedBy { it.key }
            .joinToString("") { "${it.key}:${it.value.trim()}\n" }
        val signedHeaders = headers.keys.sorted().joinToString(";")

        // 规范化查询字符串（按参数名排序）
        val canonicalQueryString = if (queryString.isBlank()) "" else {
            queryString.split("&").filter { it.isNotBlank() }.sorted().joinToString("&")
        }

        // Canonical Request
        val canonicalRequest = buildString {
            append(method.value).append('\n')
            append(canonicalPath).append('\n')
            append(canonicalQueryString).append('\n')
            append(canonicalHeaders).append('\n')
            append(signedHeaders).append('\n')
            append(UNSIGNED_PAYLOAD)
        }

        // String to Sign
        val credentialScope = "$dateStamp/$region/$SERVICE/aws4_request"
        val stringToSign = buildString {
            append(ALGORITHM).append('\n')
            append(amzDate).append('\n')
            append(credentialScope).append('\n')
            append(sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
        }

        // 计算签名
        val signingKey = signingKey(dateStamp)
        val signature = hmacSha256(signingKey, stringToSign)
            .joinToString("") { "%02x".format(it) }

        // Authorization header
        val authorization = "$ALGORITHM " +
            "Credential=$accessKeyId/$credentialScope, " +
            "SignedHeaders=$signedHeaders, " +
            "Signature=$signature"

        Logger.debug(TAG, "$method $url")

        return client.request(url) {
            this.method = method
            header("x-amz-date", amzDate)
            header("x-amz-content-sha256", UNSIGNED_PAYLOAD)
            header("Authorization", authorization)
            contentType?.let { header("Content-Type", it) }
            if (body != null) {
                setBody(body)
            }
        }
    }

    /**
     * S3 对象 key 的 URL 编码：逐段编码，保留 /。
     * 与 AWS SDK 的 UriEncode 一致。
     */
    private fun encodeS3Key(key: String): String {
        if (key.isEmpty()) return ""
        return key.split("/").joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
    }

    /**
     * 构建完整对象 key（含 prefix）。
     * 与 C# S3Adapter.BuildObjectKey 一致：prefix/relativePath
     */
    private fun buildObjectKey(relativePath: String): String {
        val normalizedPath = relativePath.replace('\\', '/').trim('/')
        val normalizedPrefix = (objectPrefix ?: "").replace('\\', '/').trim('/')
        return when {
            normalizedPrefix.isEmpty() -> normalizedPath
            normalizedPath.isEmpty() -> normalizedPrefix
            else -> "$normalizedPrefix/$normalizedPath"
        }
    }

    // ─── SyncClipboardApi 实现 ───────────────────────────────

    override suspend fun getClipboard(): ProfileDto? {
        return try {
            val response = sendSignedRequest(HttpMethod.Get, CLIPBOARD_KEY)
            if (response.status.value == 404) {
                Logger.info(TAG, "Clipboard object not found (404), returning null")
                return null
            }
            if (!response.status.isSuccess()) {
                Logger.warn(TAG, "getClipboard: S3 returned ${response.status.value}")
                return null
            }
            val body = response.bodyAsText()
            json.decodeFromString(ProfileDto.serializer(), body)
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to get clipboard from S3", e)
            null
        }
    }

    override suspend fun putClipboard(profile: ProfileDto) {
        val body = json.encodeToString(ProfileDto.serializer(), profile)
            .toByteArray(Charsets.UTF_8)
        val response = sendSignedRequest(
            method = HttpMethod.Put,
            objectKey = CLIPBOARD_KEY,
            body = body,
            contentType = "application/json; charset=utf-8"
        )
        if (!response.status.isSuccess()) {
            val errBody = try { response.bodyAsText() } catch (_: Exception) { "" }
            throw IllegalStateException("S3 putClipboard failed: ${response.status.value} $errBody")
        }
    }

    override suspend fun downloadFile(
        fileName: String,
        destinationPath: String,
        onProgress: ((Float) -> Unit)?
    ): String {
        val destFile = File(destinationPath)
        destFile.parentFile?.mkdirs()

        val response = sendSignedRequest(HttpMethod.Get, "$FILE_FOLDER/$fileName")
        if (!response.status.isSuccess()) {
            throw IllegalStateException("S3 downloadFile failed: ${response.status.value}")
        }
        val bytes = response.readRawBytes()
        destFile.writeBytes(bytes)
        Logger.info(TAG, "File downloaded from S3: $fileName -> $destinationPath")
        return destinationPath
    }

    override suspend fun putFile(fileName: String, filePath: String, onProgress: ((Float) -> Unit)?) {
        val file = File(filePath)
        if (!file.exists()) throw IllegalStateException("File not found: $filePath")

        val response = sendSignedRequest(
            method = HttpMethod.Put,
            objectKey = "$FILE_FOLDER/$fileName",
            body = file.readBytes()
        )
        if (!response.status.isSuccess()) {
            val errBody = try { response.bodyAsText() } catch (_: Exception) { "" }
            throw IllegalStateException("S3 putFile failed: ${response.status.value} $errBody")
        }
        Logger.info(TAG, "File uploaded to S3: $fileName")
    }

    override suspend fun putContent(content: ClipboardContent) {
        if (content.hasData && content.fileUri != null && content.fileName != null) {
            putFile(content.fileName!!, content.fileUri!!)
        }

        val profile = ProfileDto(
            type = content.type,
            hash = HashUtils.computeContentHash(content),
            text = content.text,
            hasData = content.hasData,
            dataName = content.fileName,
            size = content.fileSize
        )
        putClipboard(profile)
    }

    /**
     * 测试连接 — 通过 ListObjectsV2 列出 bucket（最多 1 个 key）。
     * 与 C# S3Adapter.TestConnectionAsync 一致。
     */
    override suspend fun testConnection() {
        val prefix = buildObjectKey("") + "/"
        val encodedPrefix = java.net.URLEncoder.encode(prefix, "UTF-8").replace("+", "%20")
        val queryString = "list-type=2&prefix=$encodedPrefix&max-keys=1"
        val response = sendSignedRequest(
            method = HttpMethod.Get,
            objectKey = "",
            queryString = queryString
        )
        if (!response.status.isSuccess()) {
            val errBody = try { response.bodyAsText() } catch (_: Exception) { "" }
            throw IllegalStateException("S3 test connection failed: ${response.status.value} $errBody")
        }
        Logger.info(TAG, "S3 connection test successful")
    }

    // S3 不支持原项目的 /api/history API
    override suspend fun queryHistoryRecords(page: Int, modifiedAfter: String?, types: Int): List<HistoryRecordDto> = emptyList()
    override suspend fun uploadHistoryRecord(record: HistoryRecordDto, filePath: String?): HistoryRecordDto? = null
    override suspend fun updateHistoryRecord(type: ClipboardContentType, hash: String, update: HistoryRecordUpdateDto): HistoryRecordDto? = null
    override suspend fun getHistoryRecord(profileId: String): HistoryRecordDto? = null
    override suspend fun getHistoryStatistics(): HistoryStatisticsDto? = null
    override suspend fun downloadHistoryData(hash: String, destinationPath: String): String? = null
    override suspend fun getServerTime(): Long? = null
}
