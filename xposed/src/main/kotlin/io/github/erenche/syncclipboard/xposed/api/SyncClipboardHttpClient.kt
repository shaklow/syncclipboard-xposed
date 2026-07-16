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
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders as KtorHttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * SyncClipboard 服务器 HTTP 客户端。
 *
 * 端口自 TypeScript SyncClipboardClient.ts。
 * 使用 Ktor HttpClient + kotlinx.serialization。
 */
class SyncClipboardHttpClient(
    private val baseUrl: String,
    private val username: String? = null,
    private val password: String? = null
) : SyncClipboardApi {

    companion object {
        private const val TAG = "SyncClipboardHttp"
        private const val CLIPBOARD_ENDPOINT = "/SyncClipboard.json"
        private const val FILE_ENDPOINT = "/file/"
        private const val HISTORY_API = "/api/history"
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

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private fun buildAuthHeader(): String {
        val credentials = "$username:$password"
        return "Basic " + Base64.encodeToString(
            credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
    }

    override suspend fun getClipboard(): ProfileDto? {
        return try {
            val response = client.get("$baseUrl$CLIPBOARD_ENDPOINT") {
                if (username != null && password != null) {
                    header(HttpHeaders.Authorization, buildAuthHeader())
                }
            }
            Logger.debug(TAG, "GET $baseUrl$CLIPBOARD_ENDPOINT -> status=${response.status.value}")
            if (response.status.value !in 200..299) {
                val body = response.bodyAsText()
                Logger.warn(TAG, "Server returned ${response.status.value}: ${body.take(300)}")
                return null
            }
            response.body<ProfileDto>()
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to get clipboard: ${e.message}", e)
            null
        }
    }

    override suspend fun putClipboard(profile: ProfileDto) {
        client.put("$baseUrl$CLIPBOARD_ENDPOINT") {
            if (username != null && password != null) {
                header(HttpHeaders.Authorization, buildAuthHeader())
            }
            contentType(ContentType.Application.Json)
            setBody(profile)
        }
    }

    override suspend fun downloadFile(
        fileName: String,
        destinationPath: String,
        onProgress: ((Float) -> Unit)?
    ): String {
        val destFile = File(destinationPath)
        destFile.parentFile?.mkdirs()

        client.get("$baseUrl$FILE_ENDPOINT${java.net.URLEncoder.encode(fileName, "UTF-8")}") {
            if (username != null && password != null) {
                header(HttpHeaders.Authorization, buildAuthHeader())
            }
        }.bodyAsChannel().toInputStream().use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        Logger.info(TAG, "File downloaded: $fileName -> $destinationPath")
        return destinationPath
    }

    override suspend fun putFile(fileName: String, filePath: String, onProgress: ((Float) -> Unit)?) {
        val file = File(filePath)
        if (!file.exists()) throw IllegalStateException("File not found: $filePath")

        // Upload via multipart or raw bytes depending on server implementation
        client.put("$baseUrl$FILE_ENDPOINT${java.net.URLEncoder.encode(fileName, "UTF-8")}") {
            if (username != null && password != null) {
                header(HttpHeaders.Authorization, buildAuthHeader())
            }
            setBody(file.readBytes())
        }
    }

    override suspend fun putContent(content: ClipboardContent) {
        // 如果有文件数据，先上传文件
        if (content.hasData && content.fileUri != null && content.fileName != null) {
            val name = content.fileName!!
            val uri = content.fileUri!!
            putFile(name, uri)
        }

        // 计算 profile hash
        val hash = HashUtils.computeProfileHash(
            content.type.name.lowercase(),
            content.text
        )

        // 上传 profile
        val profile = ProfileDto(
            type = content.type,
            hash = hash,
            text = content.text,
            hasData = content.hasData,
            dataName = content.fileName,
            size = content.fileSize
        )
        putClipboard(profile)
    }

    override suspend fun testConnection() {
        val response = client.get("$baseUrl$CLIPBOARD_ENDPOINT") {
            if (username != null && password != null) {
                header(HttpHeaders.Authorization, buildAuthHeader())
            }
        }
        if (response.status.value !in 200..299) {
            val body = response.bodyAsText()
            throw IllegalStateException("Server returned ${response.status.value}: ${body.take(200)}")
        }
        Logger.info(TAG, "Connection test successful")
    }

    override suspend fun queryHistoryRecords(page: Int, modifiedAfter: String?, types: Int): List<HistoryRecordDto> {
        return try {
            // 服务器 API：POST /api/history/query（multipart/form-data）
            // types 是 ProfileTypeFilter 字符串枚举，将 RN 位掩码转换为枚举名
            // RN ProfileTypeFilter: Text=1, Image=2, File=4, Group=8, All=15
            val typesName = when (types) {
                0 -> "None"
                1 -> "Text"
                2 -> "Image"
                4 -> "File"
                8 -> "Group"
                15 -> "All"
                else -> "All"
            }
            Logger.info(TAG, "queryHistoryRecords: POST $baseUrl$HISTORY_API/query, page=$page, modifiedAfter=$modifiedAfter, types=$typesName")

            val response = client.submitFormWithBinaryData(
                url = "$baseUrl$HISTORY_API/query",
                formData = formData {
                    if (page > 0) append("page", page.toString())
                    modifiedAfter?.let { append("modifiedAfter", it) }
                    append("types", typesName)
                }
            ) {
                if (username != null && password != null) {
                    header(HttpHeaders.Authorization, buildAuthHeader())
                }
            }
            if (response.status.value !in 200..299) {
                val errorBody = try { response.bodyAsText() } catch (_: Exception) { "" }
                val msg = "Server returned ${response.status.value}: ${errorBody.take(300)}"
                Logger.warn(TAG, "queryHistoryRecords: $msg")
                throw IllegalStateException(msg)
            }
            val body = response.bodyAsText()
            Logger.info(TAG, "queryHistoryRecords: POST -> 200, body length=${body.length}, preview=${body.take(500)}")
            if (body.isBlank() || body == "[]") {
                Logger.info(TAG, "queryHistoryRecords: empty result (end of pages)")
                return emptyList()
            }
            val records = Json.decodeFromString(ListSerializer(HistoryRecordDto.serializer()), body)
            Logger.info(TAG, "queryHistoryRecords: parsed ${records.size} records, hashes=${records.map { it.hash.take(8) }}")
            records
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to query history records: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
    }

    override suspend fun uploadHistoryRecord(record: HistoryRecordDto, filePath: String?): HistoryRecordDto? {
        return try {
            // 原项目使用 POST /api/history（multipart/form-data）
            val fields = buildMap {
                put("hash", record.hash)
                put("type", record.type.name)
                record.text?.let { put("text", it) }
                record.createTime?.let { put("createTime", it) }
                record.lastModified?.let { put("lastModified", it) }
                record.lastAccessed?.let { put("lastAccessed", it) }
                record.starred?.let { put("starred", it.toString()) }
                record.pinned?.let { put("pinned", it.toString()) }
                record.size?.let { put("size", it.toString()) }
                record.hasData?.let { put("hasData", it.toString()) }
                record.isDeleted?.let { put("isDeleted", it.toString()) }
                record.version?.let { put("version", it.toString()) }
            }

            val response = client.submitFormWithBinaryData(
                url = "$baseUrl$HISTORY_API",
                formData = formData {
                    fields.forEach { (key, value) ->
                        append(key, value)
                    }
                    if (filePath != null) {
                        val file = File(filePath)
                        if (file.exists()) {
                            append("file", file.readBytes(), Headers.build {
                                set(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                            })
                        }
                    }
                }
            ) {
                if (username != null && password != null) {
                    header(HttpHeaders.Authorization, buildAuthHeader())
                }
            }
            if (response.status.value == 409) {
                // 冲突：服务器已有此记录，返回服务器现有记录（与 RN SyncConflictError 一致）
                Logger.info(TAG, "uploadHistoryRecord: conflict (409) for ${record.hash}")
                val conflictBody = response.bodyAsText()
                return try {
                    json.decodeFromString(HistoryRecordDto.serializer(), conflictBody)
                } catch (_: Exception) { null }
            }
            if (response.status.value !in 200..299) {
                Logger.warn(TAG, "uploadHistoryRecord: server returned ${response.status.value}")
                return null
            }
            // 与 RN 一致：上传成功后用 GET 获取服务器记录（POST 响应体可能为空）
            // profileId 格式："{Type}-{rawHash}"，rawHash 必须剥离类型前缀
            val rawHash = HashUtils.stripTypePrefix(record.hash)
            getHistoryRecord("${record.type.name}-$rawHash")
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to upload history record: ${e.message}")
            null
        }
    }

    override suspend fun updateHistoryRecord(
        type: ClipboardContentType,
        hash: String,
        update: HistoryRecordUpdateDto
    ): HistoryRecordDto? {
        return try {
            // PATCH /api/history/{type}/{hash}（JSON body）
            // 服务器查找 hash 时需要带类型前缀（如 text-xxx），否则 404
            val response = client.patch("$baseUrl$HISTORY_API/${type.name}/$hash") {
                if (username != null && password != null) {
                    header(HttpHeaders.Authorization, buildAuthHeader())
                }
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(HistoryRecordUpdateDto.serializer(), update))
            }
            if (response.status.value == 404) {
                Logger.info(TAG, "updateHistoryRecord: record not found on server (404): $hash")
                return null
            }
            if (response.status.value == 409) {
                // 冲突：服务器返回当前版本（仅更新字段，无 hash/type）
                Logger.info(TAG, "updateHistoryRecord: conflict (409) for $hash")
                val body = response.bodyAsText()
                return try {
                    json.decodeFromString(HistoryRecordDto.serializer(), body)
                } catch (_: Exception) {
                    // 服务器 409 返回的是 HistoryRecordUpdateDto（无 hash/type），
                    // 用请求参数补全构造完整 DTO
                    val update2 = json.decodeFromString(HistoryRecordUpdateDto.serializer(), body)
                    HistoryRecordDto(
                        hash = hash,
                        text = "",
                        type = type,
                        version = update2.version ?: update.version,
                        isDeleted = update2.isDelete,
                        starred = update2.starred,
                        pinned = update2.pinned,
                        lastModified = update2.lastModified
                    )
                }
            }
            if (response.status.value !in 200..299) {
                Logger.warn(TAG, "updateHistoryRecord: server returned ${response.status.value}")
                return null
            }
            // 服务器可能返回 200/204 无响应体（成功但无内容）
            val body = response.bodyAsText()
            if (body.isBlank()) {
                Logger.debug(TAG, "updateHistoryRecord: success (empty body) for $hash")
                // 返回 dummy DTO 表示成功（调用方用 applyServerUpdate 标记 Synced）
                // 服务器 PATCH 成功后 version 会递增，本地需同步递增，否则下次 PATCH 会 409
                return HistoryRecordDto(
                    hash = hash,
                    text = "",
                    type = type,
                    version = (update.version ?: 0) + 1,
                    isDeleted = update.isDelete,
                    starred = update.starred,
                    pinned = update.pinned,
                    lastModified = update.lastModified
                )
            }
            json.decodeFromString(HistoryRecordDto.serializer(), body)
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to update history record: ${e.message}")
            null
        }
    }

    override suspend fun downloadHistoryData(hash: String, destinationPath: String): String? {
        return try {
            // 原项目 GET /api/history/{id}/data
            val response = client.get("$baseUrl$HISTORY_API/$hash/data") {
                if (username != null && password != null) {
                    header(HttpHeaders.Authorization, buildAuthHeader())
                }
            }
            if (response.status.value !in 200..299) {
                Logger.warn(TAG, "downloadHistoryData: server returned ${response.status.value}")
                return null
            }
            val bytes = response.readBytes()
            File(destinationPath).parentFile?.mkdirs()
            File(destinationPath).writeBytes(bytes)
            Logger.info(TAG, "downloadHistoryData: saved ${bytes.size} bytes to $destinationPath")
            destinationPath
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to download history data: ${e.message}")
            null
        }
    }

    override suspend fun getHistoryRecord(profileId: String): HistoryRecordDto? {
        return try {
            // 原项目 GET /api/history/{id}
            val response = client.get("$baseUrl$HISTORY_API/${java.net.URLEncoder.encode(profileId, "UTF-8")}") {
                if (username != null && password != null) {
                    header(HttpHeaders.Authorization, buildAuthHeader())
                }
            }
            if (response.status.value == 404) {
                Logger.info(TAG, "getHistoryRecord: not found (404): $profileId")
                return null
            }
            if (response.status.value !in 200..299) {
                Logger.warn(TAG, "getHistoryRecord: server returned ${response.status.value}")
                return null
            }
            val body = response.bodyAsText()
            json.decodeFromString(HistoryRecordDto.serializer(), body)
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to get history record: ${e.message}")
            null
        }
    }

    override suspend fun getHistoryStatistics(): HistoryStatisticsDto? {
        return try {
            // 原项目 GET /api/history/statistics
            val response = client.get("$baseUrl$HISTORY_API/statistics") {
                if (username != null && password != null) {
                    header(HttpHeaders.Authorization, buildAuthHeader())
                }
            }
            if (response.status.value !in 200..299) {
                Logger.warn(TAG, "getHistoryStatistics: server returned ${response.status.value}")
                return null
            }
            val body = response.bodyAsText()
            json.decodeFromString(HistoryStatisticsDto.serializer(), body)
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to get history statistics: ${e.message}")
            null
        }
    }

    override suspend fun getServerTime(): Long? {
        return try {
            // 原项目 GET /api/time（返回 ISO 8601 字符串）
            val response = client.get("$baseUrl/api/time") {
                if (username != null && password != null) {
                    header(HttpHeaders.Authorization, buildAuthHeader())
                }
            }
            if (response.status.value !in 200..299) {
                return null
            }
            val body = response.bodyAsText().trim()
            // 尝试解析 ISO 8601
            try {
                java.time.Instant.parse(body).toEpochMilli()
            } catch (_: Exception) {
                try {
                    java.time.OffsetDateTime.parse(body).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to get server time: ${e.message}")
            null
        }
    }
}
