package dev.pranav.applock.core.intruder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Alerts waiting to be emailed. Each is `<id>.json`, holding the email text, plus `<id>.jpg` and/or
 * `<id>.mp4` when something was captured. They live in noBackupFilesDir, which Android never backs
 * up, and an alert is deleted as soon as Resend accepts it, so nothing stays on the phone after that.
 */
class IntruderOutbox(context: Context) {

    private val dir = File(context.noBackupFilesDir, DIR_NAME)

    data class Alert(
        val id: String,
        val createdAt: Long,
        val text: String,
        val attachments: List<File>
    )

    fun newId(): String = UUID.randomUUID().toString()

    /** Where a capture for alert [id] is written, before [add] records the alert. */
    fun captureFile(id: String, extension: String): File {
        synchronized(LOCK) { dir.mkdirs() }
        return File(dir, "$id.$extension")
    }

    /** Records an alert as waiting. The JSON is renamed into place, so it is never half written. */
    fun add(id: String, createdAt: Long, text: String, attachments: List<File>) {
        synchronized(LOCK) {
            dir.mkdirs()
            val json = JSONObject()
                .put(FIELD_CREATED_AT, createdAt)
                .put(FIELD_TEXT, text)
            val usable = attachments.filter { it.exists() && it.length() > 0 }
            if (usable.isNotEmpty()) {
                json.put(FIELD_ATTACHMENTS, JSONArray(usable.map { it.name }))
            }

            val temp = File(dir, "$id.json.tmp")
            temp.writeText(json.toString())
            temp.renameTo(File(dir, "$id.json"))
            prune()
        }
    }

    /** Waiting alerts, oldest first. */
    fun pending(): List<Alert> {
        synchronized(LOCK) {
            prune()
            return jsonFiles().mapNotNull(::read).sortedBy { it.createdAt }
        }
    }

    fun isEmpty(): Boolean = synchronized(LOCK) { jsonFiles().isEmpty() }

    /** Deletes an alert and its captures. */
    fun remove(alert: Alert) {
        synchronized(LOCK) {
            alert.attachments.forEach { it.delete() }
            File(dir, "${alert.id}.json").delete()
        }
    }

    private fun read(file: File): Alert? {
        return try {
            val json = JSONObject(file.readText())
            val names = json.optJSONArray(FIELD_ATTACHMENTS)
            val attachments = buildList {
                for (index in 0 until (names?.length() ?: 0)) {
                    val attachment = File(dir, names!!.getString(index))
                    if (attachment.exists() && attachment.length() > 0) add(attachment)
                }
            }
            Alert(
                id = file.name.removeSuffix(".json"),
                createdAt = json.getLong(FIELD_CREATED_AT),
                text = json.getString(FIELD_TEXT),
                attachments = attachments
            )
        } catch (_: Exception) {
            // It could never be sent.
            file.delete()
            null
        }
    }

    /**
     * Drops alerts older than [MAX_AGE_MS] and all but the newest [MAX_ALERTS], so an address that
     * never works can't fill the phone. Also clears captures whose alert was never recorded, once
     * they are too old to belong to one still being made.
     */
    private fun prune() {
        val now = System.currentTimeMillis()
        jsonFiles().mapNotNull(::read)
            .sortedByDescending { it.createdAt }
            .forEachIndexed { index, alert ->
                if (index >= MAX_ALERTS || now - alert.createdAt > MAX_AGE_MS) remove(alert)
            }

        val ids = jsonFiles().map { it.name.removeSuffix(".json") }.toSet()
        dir.listFiles()?.forEach { file ->
            if (!file.name.endsWith(".json") &&
                file.name.substringBefore('.') !in ids &&
                now - file.lastModified() > ORPHAN_AGE_MS
            ) {
                file.delete()
            }
        }
    }

    private fun jsonFiles(): List<File> =
        dir.listFiles()?.filter { it.name.endsWith(".json") }.orEmpty()

    companion object {
        const val PHOTO_EXTENSION = "jpg"
        const val VIDEO_EXTENSION = "mp4"

        private const val DIR_NAME = "intruder_outbox"
        private const val FIELD_CREATED_AT = "created_at"
        private const val FIELD_TEXT = "text"
        private const val FIELD_ATTACHMENTS = "attachments"

        private const val MAX_ALERTS = 10
        private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
        private const val ORPHAN_AGE_MS = 60 * 60 * 1000L

        /** The alert, the sender and the job each make their own outbox, so they share one lock. */
        private val LOCK = Any()
    }
}
