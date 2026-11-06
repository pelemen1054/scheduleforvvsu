package ru.vvsu.schedule

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Exports favorite-group timetable cache to a folder selected by the user. */
object ScheduleArchiveExporter {
    fun export(context: Context, treeUri: Uri): Int {
        val cacheDirectory = File(context.filesDir, "schedule_cache")
        val cacheFiles = cacheDirectory.listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true)
        }.orEmpty()
        if (cacheFiles.isEmpty()) return 0

        val resolver = context.contentResolver
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val exportFolder = DocumentsContract.createDocument(
            resolver, rootDocument, DocumentsContract.Document.MIME_TYPE_DIR, "VVSU_Export_$stamp"
        ) ?: error("Не удалось создать папку экспорта")

        var exported = 0
        cacheFiles.forEachIndexed { index, file ->
            val jsonText = file.readText(Charsets.UTF_8)
            val rows = JSONArray(jsonText)
            val group = if (rows.length() > 0) rows.optJSONObject(0)?.optString("group").orEmpty() else ""
            val safeGroup = group.ifBlank { "group_${index + 1}" }
                .replace(Regex("[^A-Za-zА-Яа-яЁё0-9_-]+"), "_")
                .take(60)
            val baseName = "${safeGroup}_${stamp}_${index + 1}"
            writeDocument(resolver, exportFolder, "$baseName.json", "application/json", jsonText)
            writeDocument(resolver, exportFolder, "$baseName.html", "text/html", buildHtml(rows, group))
            exported++
        }
        return exported
    }

    private fun writeDocument(
        resolver: android.content.ContentResolver,
        parent: Uri,
        name: String,
        mimeType: String,
        content: String
    ) {
        val document = DocumentsContract.createDocument(resolver, parent, mimeType, name)
            ?: error("Не удалось создать файл $name")
        resolver.openOutputStream(document, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(content)
        } ?: error("Не удалось записать файл $name")
    }

    private fun buildHtml(rows: JSONArray, group: String): String {
        fun esc(value: String): String = value
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

        val body = buildString {
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                append("<tr>")
                listOf("date", "time", "subject", "teacher", "type", "room").forEach { key ->
                    append("<td>").append(esc(item.optString(key))).append("</td>")
                }
                append("</tr>")
            }
        }
        return """
            <!doctype html>
            <html lang="ru"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Расписание ${esc(group)}</title>
            <style>
            body{font-family:Arial,sans-serif;margin:20px;color:#222}
            h1{font-size:22px}.wrap{overflow-x:auto}
            table{border-collapse:collapse;width:100%;min-width:650px}
            th,td{border:1px solid #ccc;padding:9px;text-align:left}
            th{background:#eee}tr:nth-child(even){background:#f7f7f7}
            </style></head><body>
            <h1>Расписание: ${esc(group)}</h1>
            <div class="wrap"><table>
            <thead><tr><th>Дата</th><th>Время</th><th>Предмет</th><th>Преподаватель</th><th>Тип</th><th>Аудитория</th></tr></thead>
            <tbody>$body</tbody></table></div></body></html>
        """.trimIndent()
    }
}
