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
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLEncoder

/**
 * WebDAV 客户端 — 通过 HTTP 操作 WebDAV 存储。
 *
 * 端口自 C# WebDavAdapter.cs / WebDavBase.cs。
 * 与 SyncClipboard 服务端/桌面端使用的路径一致：
 * - 剪贴板配置文件: SyncClipboard.json
 * - 文件目录: file/
 */
class WebDAVClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String
) : SyncClipboardApi {

    companion object {
        private const val TAG = "WebDAVClient"
        private const val CLIPBOARD_FILE = "SyncClipboard.json"
        private const val DATA_DIR = "file"
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
            val response = client.get("$baseUrl/$CLIPBOARD_FILE") {
                header(HttpHeaders.Authorization, buildAuthHeader())
            }
            // 404 = 文件尚未创建（首次使用），返回 null
            if (response.status.value == 404) {
                Logger.info(TAG, "Clipboard file not found (404), returning null")
                return null
            }
            if (!response.status.isSuccess()) {
                Logger.warn(TAG, "getClipboard: server returned ${response.status.value}")
                return null
            }
            val responseText = response.bodyAsText()
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

        // URLEncoder 将空格编码为 +，WebDAV 路径需要 %20（与 C# Uri.EscapeDataString 一致）
        val encodedName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        val bytes = client.get("$baseUrl/$DATA_DIR/$encodedName") {
            header(HttpHeaders.Authorization, buildAuthHeader())
        }.body<ByteArray>()

        destFile.writeBytes(bytes)
        Logger.info(TAG, "File downloaded: $fileName -> $destinationPath")
        return destinationPath
    }

    override suspend fun putFile(fileName: String, filePath: String, onProgress: ((Float) -> Unit)?) {
        val file = File(filePath)
        if (!file.exists()) throw IllegalStateException("File not found: $filePath")

        // 确保远程目录存在（MKCOL，已存在则忽略 405）
        ensureDataDirectoryExists()

        val encodedName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        client.put("$baseUrl/$DATA_DIR/$encodedName") {
            header(HttpHeaders.Authorization, buildAuthHeader())
            setBody(file.readBytes())
        }
    }

    /**
     * 创建远程文件目录（MKCOL）。
     * 目录已存在时服务器返回 405，忽略此错误。
     */
    private suspend fun ensureDataDirectoryExists() {
        try {
            val response = client.request("$baseUrl/$DATA_DIR/") {
                method = HttpMethod.parse("MKCOL")
                header(HttpHeaders.Authorization, buildAuthHeader())
            }
            if (response.status.value == 201) {
                Logger.info(TAG, "Data directory created: $DATA_DIR/")
            }
            // 405 = 目录已存在，正常情况
        } catch (e: Exception) {
            Logger.warn(TAG, "ensureDataDirectoryExists: ${e.message}")
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
            hash = HashUtils.computeContentHash(content),
            text = content.text,
            hasData = content.hasData,
            dataName = content.fileName,
            size = content.fileSize
        )
        putClipboard(profile)
    }

    override suspend fun testConnection() {
        // 使用 PROPFIND + Depth: 1 测试连接，与服务端 WebDav.Test() 一致
        val response = client.request(baseUrl) {
            method = HttpMethod.parse("PROPFIND")
            header(HttpHeaders.Authorization, buildAuthHeader())
            header("Depth", "1")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("WebDAV test connection failed: ${response.status.value}")
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
