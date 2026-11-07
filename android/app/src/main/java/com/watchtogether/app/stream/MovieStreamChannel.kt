package com.watchtogether.app.stream

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class MovieStreamChannel(private val context: Context) {
    private var moviePfd: ParcelFileDescriptor? = null
    private var movieChannel: FileChannel? = null

    @Synchronized
    fun getOrOpenChannel(uri: Uri): FileChannel {
        return movieChannel ?: run {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Cannot open movie file")
            moviePfd = pfd
            FileInputStream(pfd.fileDescriptor).channel.also { movieChannel = it }
        }
    }

    fun readChunk(ws: WebSocket, request: JSONObject, source: Uri, sourceSize: Long) {
        val id = request.optString("id")
        try {
            val offset = request.getLong("offset")
            val length = request.getInt("length")
            require(offset >= 0 && length in 1..262144)
            val channel = getOrOpenChannel(source)
            val chSize = try { channel.size() } catch (_: Exception) { sourceSize }
            if (chSize > 0 && offset >= chSize) {
                ws.send(JSONObject().put("type", "chunk").put("id", id).put("error", true).toString())
                return
            }
            val idBytes = id.toByteArray(Charsets.US_ASCII)
            val payload = ByteArray(idBytes.size + length)
            System.arraycopy(idBytes, 0, payload, 0, idBytes.size)
            val buffer = ByteBuffer.wrap(payload, idBytes.size, length)
            var read = 0
            while (read < length) {
                val n = channel.read(buffer, offset + read)
                if (n <= 0) break
                read += n
            }
            val sent = ws.send(payload.toByteString(0, payload.size))
            if (!sent) {
                ws.send(JSONObject().put("type", "chunk").put("id", id).put("error", true).toString())
            }
        } catch (_: Exception) {
            try {
                ws.send(JSONObject().put("type", "chunk").put("id", id).put("error", true).toString())
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun close() {
        try { movieChannel?.close() } catch (_: Exception) {}
        try { moviePfd?.close() } catch (_: Exception) {}
        movieChannel = null
        moviePfd = null
    }
}

