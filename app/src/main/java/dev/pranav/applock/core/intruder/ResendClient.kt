package dev.pranav.applock.core.intruder

import android.util.Base64
import android.util.Base64OutputStream
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Sends one email through Resend's API (https://resend.com/docs/api-reference/emails/send-email),
 * using only what Android already has, so the feature adds no library.
 *
 * Attachments are streamed into the request as Base64 instead of being built up in memory, since a
 * video can be several megabytes.
 */
class ResendClient(private val apiKey: String) {

    data class Attachment(val file: File, val filename: String)

    sealed interface Result {
        data object Sent : Result

        /** Worth trying again later: no network, a timeout, a quota, rate limiting or a server error. */
        data class Retry(val reason: String) : Result

        /** Resend refused the request itself, so sending it again unchanged can't help. */
        data class Rejected(val reason: String) : Result
    }

    /** A request written out in order: JSON text, with each attachment's bytes Base64'd in place. */
    private sealed interface Part {
        class Literal(val bytes: ByteArray) : Part
        class Base64File(val file: File) : Part
    }

    fun send(
        from: String,
        to: String,
        subject: String,
        text: String,
        attachments: List<Attachment>,
        idempotencyKey: String
    ): Result {
        val parts = buildParts(from, to, subject, text, attachments)
        val contentLength = parts.sumOf { part ->
            when (part) {
                is Part.Literal -> part.bytes.size.toLong()
                is Part.Base64File -> base64Length(part.file.length())
            }
        }

        return try {
            val connection = URL(ENDPOINT).openConnection() as HttpsURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(contentLength)
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.setRequestProperty("Content-Type", "application/json")
                // Resend refuses requests without one.
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Idempotency-Key", idempotencyKey)

                connection.outputStream.use { out ->
                    parts.forEach { part ->
                        when (part) {
                            is Part.Literal -> out.write(part.bytes)
                            is Part.Base64File -> {
                                Base64OutputStream(out, Base64.NO_WRAP or Base64.NO_CLOSE)
                                    .use { base64 -> part.file.inputStream().use { it.copyTo(base64) } }
                            }
                        }
                    }
                }

                val code = connection.responseCode
                val reason = "HTTP $code ${errorMessage(connection)}".trim()
                when {
                    code in 200..299 -> Result.Sent
                    // 409 is another request with the same key still in progress.
                    code == 409 || code == 429 || code >= 500 -> Result.Retry(reason)
                    else -> Result.Rejected(reason)
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            Result.Retry("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun buildParts(
        from: String,
        to: String,
        subject: String,
        text: String,
        attachments: List<Attachment>
    ): List<Part> = buildList {
        val head = StringBuilder()
            .append("{\"from\":").append(JSONObject.quote(from))
            .append(",\"to\":[").append(JSONObject.quote(to)).append(']')
            .append(",\"subject\":").append(JSONObject.quote(subject))
            .append(",\"text\":").append(JSONObject.quote(text))

        if (attachments.isEmpty()) {
            add(literal(head.append('}').toString()))
            return@buildList
        }

        add(literal(head.append(",\"attachments\":[").toString()))
        attachments.forEachIndexed { index, attachment ->
            val prefix = StringBuilder()
            if (index > 0) prefix.append(',')
            prefix.append("{\"filename\":").append(JSONObject.quote(attachment.filename))
                .append(",\"content\":\"")
            add(literal(prefix.toString()))
            add(Part.Base64File(attachment.file))
            add(literal("\"}"))
        }
        add(literal("]}"))
    }

    private fun literal(text: String): Part = Part.Literal(text.toByteArray(Charsets.UTF_8))

    /** Resend's explanation from an error response, or nothing if it gave none. */
    private fun errorMessage(connection: HttpsURLConnection): String {
        return try {
            val body = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: return ""
            JSONObject(body).optString("message").take(MAX_MESSAGE_LENGTH)
        } catch (_: Exception) {
            ""
        }
    }

    /** Base64 with padding and no line breaks turns every 3 bytes, or part of them, into 4. */
    private fun base64Length(bytes: Long): Long = (bytes + 2) / 3 * 4

    companion object {
        private const val ENDPOINT = "https://api.resend.com/emails"
        private const val USER_AGENT = "system-services"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_MESSAGE_LENGTH = 300
    }
}
