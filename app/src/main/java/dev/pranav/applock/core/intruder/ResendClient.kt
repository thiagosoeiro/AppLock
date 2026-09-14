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
 * The attachment is streamed into the request as Base64 instead of being built up in memory, since
 * a video can be several megabytes.
 */
class ResendClient(private val apiKey: String) {

    sealed interface Result {
        data object Sent : Result

        /** Worth trying again later: no network, a timeout, a quota, rate limiting or a server error. */
        data class Retry(val reason: String) : Result

        /** Resend refused the request itself, so sending it again unchanged can't help. */
        data class Rejected(val reason: String) : Result
    }

    fun send(
        from: String,
        to: String,
        subject: String,
        text: String,
        attachment: File?,
        attachmentName: String?,
        idempotencyKey: String
    ): Result {
        val head = buildString {
            append("{\"from\":").append(JSONObject.quote(from))
            append(",\"to\":[").append(JSONObject.quote(to)).append(']')
            append(",\"subject\":").append(JSONObject.quote(subject))
            append(",\"text\":").append(JSONObject.quote(text))
            if (attachment != null) {
                append(",\"attachments\":[{\"filename\":")
                append(JSONObject.quote(attachmentName ?: attachment.name))
                append(",\"content\":\"")
            }
        }.toByteArray(Charsets.UTF_8)
        val tail = (if (attachment != null) "\"}]}" else "}").toByteArray(Charsets.UTF_8)
        val contentLength = head.size + base64Length(attachment?.length() ?: 0L) + tail.size

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
                    out.write(head)
                    if (attachment != null) {
                        Base64OutputStream(out, Base64.NO_WRAP or Base64.NO_CLOSE).use { base64 ->
                            attachment.inputStream().use { it.copyTo(base64) }
                        }
                    }
                    out.write(tail)
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
