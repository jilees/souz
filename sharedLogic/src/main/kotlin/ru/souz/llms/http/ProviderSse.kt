package ru.souz.llms.http

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line

/** Bounded SSE decoding shared by provider bridges. Event headers and multiline data are preserved. */
suspend fun readProviderSse(
    channel: ByteReadChannel,
    maxFrameChars: Int = 8 * 1024 * 1024,
    maxResponseChars: Long = Long.MAX_VALUE,
    consume: suspend (event: String?, data: String) -> Boolean,
) {
    val data = StringBuilder()
    var event: String? = null
    var remaining = maxResponseChars
    while (true) {
        val line = channel.readUTF8Line(maxFrameChars)
        if (line == null || line.isEmpty()) {
            if (data.isNotEmpty()) {
                if (!consume(event, data.toString())) return
                data.clear()
            }
            event = null
            if (line == null) return
        } else {
            remaining -= line.length.toLong() + 1
            check(remaining >= 0) { "Provider response exceeds the size limit." }
            when {
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                    check(data.length <= maxFrameChars) { "Provider SSE frame exceeds the size limit." }
                }
            }
        }
    }
}
