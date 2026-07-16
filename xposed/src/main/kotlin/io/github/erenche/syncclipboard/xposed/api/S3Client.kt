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
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.File

/**
 * S3 兼容存储客户端。
 *
 * 端口自 TypeScript S3Client.ts。
 * 使用 Ktor HttpClient，通过 S3 REST API 进行对象操作。
 *
 * 注意：此实现为简化版，完整的 AWS SigV4 签名需要在生产环境中正确实现。
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
        private const val CLIPBOARD_KEY = "clipboard.json"
        private const val DATA_PREFIX = "data/"
    }

    private val endpoint = serviceUrl ?: "https://s3.$region.amazonaws.com"

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = false
            })
        }
        install(Logging) {
            level = LogLevel.INFO
        }
    }

    private fun buildObjectKey(suffix: String): String {
        val prefix = objectPrefix?.trimEnd('/')?.let { "$it/" } ?: ""
        return "$prefix$suffix"
    }

    private fun buildObjectUrl(key: String): String {
        return if (forcePathStyle) {
            "$endpoint/$bucketName/$key"
        } else {
            "$endpoint/$key"
            // For virtual hosted style: https://bucket.s3.region.amazonaws.com/key
            // Simplified here — full SigV4 implementation needed for production
        }
    }

    override suspend fun getClipboard(): ProfileDto? {
        return try {
            val url = buildObjectUrl(buildObjectKey(CLIPBOARD_KEY))
            val responseText = client.get(url).bodyAsText()
            Json.decodeFromString<ProfileDto>(responseText)
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to get clipboard from S3", e)
            null
        }
    }

    override suspend fun putClipboard(profile: ProfileDto) {
        val url = buildObjectUrl(buildObjectKey(CLIPBOARD_KEY))
        client.put(url) {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(ProfileDto.serializer(), profile))
        }
    }

    override suspend fun downloadFile(
        fileName: String,
        destinationPath: String,
        onProgress: ((Float) -> Unit)?
    ): String {
        val destFile = File(destinationPath)
        destFile.parentFile?.mkdirs()

        val key = buildObjectKey("$DATA_PREFIX$fileName")
        val url = buildObjectUrl(key)
        val bytes = client.get(url).body<ByteArray>()

        destFile.writeBytes(bytes)
        Logger.info(TAG, "File downloaded from S3: $fileName -> $destinationPath")
        return destinationPath
    }

    override suspend fun putFile(fileName: String, filePath: String, onProgress: ((Float) -> Unit)?) {
        val file = File(filePath)
        if (!file.exists()) throw IllegalStateException("File not found: $filePath")

        val key = buildObjectKey("$DATA_PREFIX$fileName")
        val url = buildObjectUrl(key)
        client.put(url) {
            setBody(file.readBytes())
        }
    }

    override suspend fun putContent(content: ClipboardContent) {
        if (content.hasData && content.fileUri != null && content.fileName != null) {
            val name = content.fileName!!
            val uri = content.fileUri!!
            putFile(name, uri)
        }

        val profile = ProfileDto(
            type = content.type,
            hash = HashUtils.computeProfileHash(content.type.name.lowercase(), content.text),
            text = content.text,
            hasData = content.hasData,
            dataName = content.fileName,
            size = content.fileSize
        )
        putClipboard(profile)
    }

    override suspend fun testConnection() {
        // Test by trying to list bucket
        client.get(endpoint)
        Logger.info(TAG, "Connection test successful")
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
