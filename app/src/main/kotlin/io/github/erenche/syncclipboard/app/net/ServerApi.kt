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
import java.net.URL
import java.net.URLEncoder

/**
 * App 端轻量 HTTP 客户端 — 使用 HttpURLConnection（无额外依赖）。
 * 用于"同步"页面直接查询/下载服务器内容。
 */
class ServerApi(private val server: ServerConfig) {

    private companion object {
        const val TAG = "ServerApi"
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

    /** 下载文件到指定路径，返回文件对象 */
    fun downloadFile(fileName: String, destFile: File): File? {
        return try {
            val encoded = URLEncoder.encode(fileName, "UTF-8")
            val conn = connect("/file/$encoded")
            conn.requestMethod = "GET"
            if (conn.responseCode in 200..299) {
                destFile.parentFile?.mkdirs()
                conn.inputStream.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                destFile
            } else null
        } catch (e: Exception) {
            Logger.error(TAG, "downloadFile failed: ${e.message}", e)
            null
        }
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
            val rawHash = HashUtils.stripTypePrefix(hash)
            val profileId = "${type.name}-$rawHash"
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
