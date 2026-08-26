package io.github.erenche.syncclipboard.xposed.api

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import java.io.File

/**
 * 分块流式上传文件内容：按 [CHUNK_SIZE] 读取文件写入请求体，每写出一个块即回调进度。
 *
 * 背景：直接 `setBody(file.readBytes())` 时，Ktor 的 onUpload（BodyProgress）对 OkHttp
 * 引擎只会在整块内容写入完成时回调一次，导致进度条 0 → 100 直接跳变。
 * 这里在数据源（RawSource）层分块计数，字节被引擎实际拉取时逐块回调，
 * 配合引擎侧的字节步进节流，UI 进度条能真实分段推进。
 */
internal class CountingFileContent(
    private val file: File,
    private val mediaType: ContentType?,
    private val onProgress: (Long) -> Unit
) : OutgoingContent.ReadChannelContent() {

    override val contentLength: Long get() = file.length()

    override val contentType: ContentType? get() = mediaType

    override fun readFrom(): ByteReadChannel {
        val source = object : RawSource {
            private val input = file.inputStream().buffered()
            private val buffer = ByteArray(CHUNK_SIZE)
            private var sent = 0L
            private var eof = false

            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                if (eof) return -1L
                if (byteCount <= 0L) return 0L
                val toRead = minOf(byteCount, CHUNK_SIZE.toLong()).toInt()
                val n = input.read(buffer, 0, toRead)
                if (n <= 0) {
                    eof = true
                    return -1L
                }
                sink.write(buffer, startIndex = 0, endIndex = n)
                sent += n
                onProgress(sent)
                return n.toLong()
            }

            override fun close() {
                eof = true
                input.close()
            }
        }
        return ByteReadChannel(source.buffered())
    }

    private companion object {
        const val CHUNK_SIZE = 64 * 1024
    }
}
