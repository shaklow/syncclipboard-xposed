package io.github.erenche.syncclipboard.xposed.api

import io.github.erenche.syncclipboard.common.model.ServerConfig

/**
 * API 客户端工厂 — 根据 ServerConfig 创建对应的客户端实例。
 *
 * 端口自 TypeScript ClientFactory.ts
 */
object ClientFactory {

    /**
     * 根据服务器配置创建 API 客户端。
     *
     * @throws IllegalArgumentException 如果配置不完整
     */
    fun createClient(config: ServerConfig): SyncClipboardApi {
        return when (config.type) {
            io.github.erenche.syncclipboard.common.model.ServerType.syncclipboard -> {
                require(config.url.isNotBlank()) { "Server URL is required" }
                SyncClipboardHttpClient(
                    baseUrl = config.url.trimEnd('/'),
                    username = config.username,
                    password = config.password
                )
            }
            io.github.erenche.syncclipboard.common.model.ServerType.webdav -> {
                require(config.url.isNotBlank()) { "Server URL is required" }
                require(!config.username.isNullOrBlank()) { "Username is required for WebDAV" }
                require(!config.password.isNullOrBlank()) { "Password is required for WebDAV" }
                WebDAVClient(
                    baseUrl = config.url.trimEnd('/'),
                    username = config.username!!,
                    password = config.password!!
                )
            }
            io.github.erenche.syncclipboard.common.model.ServerType.s3 -> {
                require(!config.bucketName.isNullOrBlank()) { "Bucket name is required for S3" }
                require(!config.username.isNullOrBlank()) { "Access Key ID is required for S3" }
                require(!config.password.isNullOrBlank()) { "Secret Access Key is required for S3" }
                S3Client(
                    serviceUrl = config.url.ifBlank { null },
                    region = config.region ?: "us-east-1",
                    bucketName = config.bucketName!!,
                    objectPrefix = config.objectPrefix,
                    forcePathStyle = config.forcePathStyle,
                    accessKeyId = config.username!!,
                    secretAccessKey = config.password!!
                )
            }
        }
    }
}
