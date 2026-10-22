package ru.souz.backend.attachments

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bounded public HTTPS image download shared by receipt storage and legacy model input. */
internal suspend fun downloadToolImageBytes(url: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.userInfo == null && uri.host != null) { "Invalid tool-image origin." }
    require(InetAddress.getAllByName(uri.host).all {
        !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isSiteLocalAddress && !it.isLinkLocalAddress && !it.isMulticastAddress
    }) { "Tool image origin must be public." }
    val connection = uri.toURL().openConnection() as HttpURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        check(connection.responseCode == 200) { "Tool image download failed." }
        connection.inputStream.use { it.readAttachmentBytes(maxBytes) }
    } finally {
        connection.disconnect()
    }
}
