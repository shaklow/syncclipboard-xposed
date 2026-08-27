package io.github.erenche.syncclipboard.xposed.api

import android.util.Base64
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
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import java.io.File

/**
 * WebDAV 客户端 — 通过 HTTP 操作 WebDAV 存储。
 *
 * 端口自 TypeScript WebDAVClient.ts。
 */
class WebDAVClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String
) : SyncClipboardApi {

    companion object {
        private const val TAG = "WebDAVClient"
        private const val CLIPBOARD_FILE = "clipboard.json"
        private const val DATA_DIR = "data"
    }

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

    private fun buildAuthHeader(): String {
        val credentials = "$username:$password"
        return "Basic " + Base64.encodeToString(
            credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
    }

    override suspend fun getClipboard(): ProfileDto? {
        return try {
            val responseText = client.get("$baseUrl/$CLIPBOARD_FILE") {
                header(HttpHeaders.Authorization, buildAuthHeader())
            }.bodyAsText()

            Json.decodeFromString<ProfileDto>(responseText)
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to get clipboard from WebDAV", e)
            null
        }
    }

    override suspend fun putClipboard(profile: ProfileDto) {
        client.put("$baseUrl/$CLIPBOARD_FILE") {
            header(HttpHeaders.Authorization, buildAuthHeader())
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

        val bytes = client.get("$baseUrl/$DATA_DIR/$fileName") {
            header(HttpHeaders.Authorization, buildAuthHeader())
        }.body<ByteArray>()

        destFile.writeBytes(bytes)
        Logger.info(TAG, "File downloaded: $fileName -> $destinationPath")
        return destinationPath
    }

    override suspend fun putFile(fileName: String, filePath: String, onProgress: ((Long, Long) -> Unit)?) {
        val file = File(filePath)
        if (!file.exists()) throw IllegalStateException("File not found: $filePath")

        // 流式上传并上报真实字节进度
        val total = file.length()
        onProgress?.invoke(0L, total)
        val body = object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
            override val contentLength = total
            override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        channel.writeFully(buf, 0, n)
                        sent += n
                        onProgress?.invoke(sent, total)
                    }
                }
            }
        }
        client.put("$baseUrl/$DATA_DIR/$fileName") {
            header(HttpHeaders.Authorization, buildAuthHeader())
            setBody(body)
        }
        onProgress?.invoke(total, total)
        Logger.info(TAG, "File uploaded to WebDAV: $fileName")
    }

    override suspend fun putContent(content: ClipboardContent, onProgress: ((Long, Long) -> Unit)?) {
        if (content.hasData && content.fileUri != null && content.fileName != null) {
            val name = content.fileName!!
            val uri = content.fileUri!!
            putFile(name, uri, onProgress)
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

    override suspend fun testConnection() {
        // Test by attempting to list directory
        client.get(baseUrl) {
            header(HttpHeaders.Authorization, buildAuthHeader())
        }
        Logger.info(TAG, "Connection test successful")
    }

    // WebDAV 不支持原项目的 /api/history API
    override suspend fun queryHistoryRecords(page: Int, modifiedAfter: String?, types: Int): List<HistoryRecordDto> = emptyList()
    override suspend fun uploadHistoryRecord(record: HistoryRecordDto, filePath: String?): HistoryRecordDto? = null
    override suspend fun updateHistoryRecord(type: ClipboardContentType, hash: String, update: HistoryRecordUpdateDto): HistoryRecordDto? = null
    override suspend fun getHistoryRecord(profileId: String): HistoryRecordDto? = null
    override suspend fun getHistoryStatistics(): HistoryStatisticsDto? = null
    override suspend fun downloadHistoryData(hash: String, destinationPath: String): String? = null
    override suspend fun getServerTime(): Long? = null
}
