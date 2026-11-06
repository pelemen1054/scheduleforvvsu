package ru.vvsu.schedule

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent diagnostic log. It stores technical events, not timetable text. */
object AppLogger {
    private const val PREFS_NAME = "developer_diagnostics"
    private const val LOG_KEY = "events"
    private const val MAX_ENTRIES = 100
    private val lock = Any()

    fun info(context: Context?, message: String) = write(context, "INFO", message)
    fun warning(context: Context?, message: String) = write(context, "WARN", message)

    fun error(context: Context?, message: String, throwable: Throwable? = null) {
        val details = if (throwable == null) "" else {
            val writer = StringWriter()
            throwable.printStackTrace(PrintWriter(writer))
            " | " + writer.toString().replace("\r", "").replace("\n", " <- ").take(5000)
        }
        write(context, "ERROR", message + details)
    }

    fun read(context: Context): List<String> = synchronized(lock) {
        val raw = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(LOG_KEY, "").orEmpty()
        raw.split("\n").filter { it.isNotBlank() }.takeLast(MAX_ENTRIES)
    }

    fun clear(context: Context) = synchronized(lock) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(LOG_KEY).apply()
    }

    private fun write(context: Context?, level: String, message: String) {
        if (context == null) return
        synchronized(lock) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
            val safeMessage = message.replace("\r", " ").replace("\n", " ")
            val entries = prefs.getString(LOG_KEY, "").orEmpty()
                .split("\n").filter { it.isNotBlank() }.toMutableList()
            entries.add("$timestamp [$level] $safeMessage")
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
            prefs.edit().putString(LOG_KEY, entries.joinToString("\n")).apply()
        }
    }
}
