package io.github.erenche.syncclipboard.app.net

import android.util.Base64
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.model.ProfileDto
import io.github.erenche.syncclipboard.common.model.ServerConfig
import io.github.erenche.syncclipboard.common.util.HashUtils
import io.github.erenche.syncclipboard.common.util.Logger
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * App 端轻量 HTTP 客户端 — 使用 HttpURLConnection（无额外依赖）。
 * 用于"同步"页面直接查询/下载服务器内容。
 */
class ServerApi(private val server: ServerConfig) {

    private companion object {
        const val TAG = "ServerApi"

        private const val ALGORITHM = "AWS4-HMAC-SHA256"
        private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private fun authHeader(): String? {
        val u = server.username ?: return null
        val p = server.password ?: ""
        return "Basic " + Base64.encodeToString(
            "$u:$p".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
    }

    private fun connect(path: String): HttpURLConnection {
        val url = URL(server.url.trimEnd('/') + path)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        authHeader()?.let { conn.setRequestProperty("Authorization", it) }
        return conn
    }

    /** 获取服务器最新剪贴板 profile */
    fun getClipboard(): ProfileDto? {
        return try {
            val conn = connect("/SyncClipboard.json")
            conn.requestMethod = "GET"
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                json.decodeFromString(ProfileDto.serializer(), body)
            } else null
        } catch (e: Exception) {
            Logger.error(TAG, "getClipboard failed: ${e.message}", e)
            null
        }
    }

    /** 下载文件到指定路径，返回文件对象（syncclipboard / webdav：GET /file/{name} + Basic 认证） */
    fun downloadFile(fileName: String, destFile: File): File? {
        return try {
            // URLEncoder 把空格编成 '+', 而 ASP.NET 路由不解码 '+', 需转成 %20
            val encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
            val conn = connect("/file/$encoded")
            conn.requestMethod = "GET"
            if (conn.responseCode in 200..299) {
                destFile.parentFile?.mkdirs()
                conn.inputStream.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                destFile
            } else {
                Logger.error(TAG, "downloadFile failed: HTTP ${conn.responseCode} for ${conn.url}")
                null
            }
        } catch (e: Exception) {
            Logger.error(TAG, "downloadFile failed: ${e.message}", e)
            null
        }
    }

    /**
     * 下载 S3 文件到指定路径（SigV4 签名，与引擎 S3Client 逻辑一致）。
     * 对象 key 为 {objectPrefix}/file/{fileName}；配置了 URL 即视为自定义端点 -> path style。
     */
    fun downloadFileS3(fileName: String, destFile: File): File? {
        return try {
            val region = server.region ?: "us-east-1"
            val bucket = server.bucketName ?: return null
            val accessKey = server.username ?: return null
            val secretKey = server.password ?: return null

            val baseUrl = server.url.trimEnd('/')
            // URL 为空时使用 AWS 默认 endpoint + virtual-hosted 寻址（与引擎 S3Client 一致）
            val isCustomEndpoint = baseUrl.isNotBlank()
            val endpoint = URI(baseUrl.ifBlank { "https://s3.$region.amazonaws.com" })
            val scheme = endpoint.scheme
            val host = endpoint.host
            val port = endpoint.port
            val portSuffix = if (port > 0) ":$port" else ""

            // 与引擎一致：配置了 URL 即视为自定义端点 -> path style 寻址
            val pathStyle = server.forcePathStyle || isCustomEndpoint

            val prefix = (server.objectPrefix ?: "").replace('\\', '/').trim('/')
            val relative = "file/$fileName"
            val fullKey = if (prefix.isEmpty()) relative else "$prefix/$relative"
            val encodedKey = fullKey.split("/").joinToString("/") { seg ->
                URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
            }

            val canonicalPath = if (pathStyle) "/$bucket/$encodedKey" else "/$encodedKey"
            val hostHeader = if (pathStyle) "$host$portSuffix" else "$bucket.$host$portSuffix"
            val url = if (pathStyle) {
                "$scheme://$host$portSuffix/$bucket/$encodedKey"
            } else {
                "$scheme://$bucket.$host$portSuffix/$encodedKey"
            }

            // ─── SigV4 签名（GET、UNSIGNED-PAYLOAD） ───
            val now = Date()
            val amzDate = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(now)
            val dateStamp = amzDate.substring(0, 8)

            val headers = linkedMapOf(
                "host" to hostHeader,
                "x-amz-content-sha256" to UNSIGNED_PAYLOAD,
                "x-amz-date" to amzDate
            )
            val canonicalHeaders = headers.entries.sortedBy { it.key }
                .joinToString("") { "${it.key}:${it.value.trim()}\n" }
            val signedHeaders = headers.keys.sorted().joinToString(";")

            val canonicalRequest = buildString {
                append("GET").append('\n')
                append(canonicalPath).append('\n')
                append('\n')
                append(canonicalHeaders).append('\n')
                append(signedHeaders).append('\n')
                append(UNSIGNED_PAYLOAD)
            }

            val credentialScope = "$dateStamp/$region/s3/aws4_request"
            val stringToSign = buildString {
                append(ALGORITHM).append('\n')
                append(amzDate).append('\n')
                append(credentialScope).append('\n')
                append(sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)))
            }
            val signature = hmacHex(signingKey(secretKey, dateStamp, region), stringToSign)
            val authorization = "$ALGORITHM " +
                "Credential=$accessKey/$credentialScope, " +
                "SignedHeaders=$signedHeaders, " +
                "Signature=$signature"

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("x-amz-date", amzDate)
            conn.setRequestProperty("x-amz-content-sha256", UNSIGNED_PAYLOAD)
            conn.setRequestProperty("Authorization", authorization)

            if (conn.responseCode in 200..299) {
                destFile.parentFile?.mkdirs()
                conn.inputStream.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                destFile
            } else {
                Logger.error(TAG, "downloadFileS3 failed: HTTP ${conn.responseCode} for key=$fullKey")
                null
            }
        } catch (e: Exception) {
            Logger.error(TAG, "downloadFileS3 failed: ${e.message}", e)
            null
        }
    }

    // ─── SigV4 签名工具 ──────────────────────────────────────

    private fun hmacBytes(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacHex(key: ByteArray, data: String): String {
        return hmacBytes(key, data).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun signingKey(secretKey: String, dateStamp: String, region: String): ByteArray {
        val kSecret = ("AWS4" + secretKey).toByteArray(Charsets.UTF_8)
        val kDate = hmacBytes(kSecret, dateStamp)
        val kRegion = hmacBytes(kDate, region)
        val kService = hmacBytes(kRegion, "s3")
        return hmacBytes(kService, "aws4_request")
    }

    /**
     * 下载历史记录的数据文件到指定路径（GET /api/history/{profileId}/data）。
     * profileId 格式为 "Type-Hash"（如 File-6C12C7AC...、Image-0BDA056B...）。
     * 用于历史记录中文件/图片的下载与预览。
     */
    fun downloadHistoryData(type: ClipboardContentType, hash: String, destFile: File): File? {
        return try {
            // profileId 格式："{Type}-{rawHash}"，rawHash 必须剥离类型前缀
            // 服务器存的 hash 可能是 "text-xxx"（带前缀）或 "xxx"（无前缀），需统一剥离
            val profileId = "${type.name}-$hash"
            val encoded = URLEncoder.encode(profileId, "UTF-8")
            val conn = connect("/api/history/$encoded/data")
            conn.requestMethod = "GET"
            if (conn.responseCode in 200..299) {
                destFile.parentFile?.mkdirs()
                conn.inputStream.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                destFile
            } else {
                Logger.error(TAG, "downloadHistoryData failed: HTTP ${conn.responseCode} for profileId=$profileId")
                null
            }
        } catch (e: Exception) {
            Logger.error(TAG, "downloadHistoryData failed: ${e.message}", e)
            null
        }
    }
}
