package ru.vvsu.schedule

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class VvsuRepository {

    private val timetableUrl =
        "https://www.vvsu.ru/timetable/"

    private val portfolioUrl =
        "https://portfolio.vvsu.ru/"

    suspend fun loadGroup(
        group: String
    ): List<Lesson> = withContext(Dispatchers.IO) {

        val cleanGroup = group.trim()

        if (cleanGroup.isBlank()) {
            return@withContext emptyList()
        }

        val doc = Jsoup.connect(timetableUrl)
            .userAgent(
                "Mozilla/5.0 (Linux; Android 13) " +
                    "AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            )
            .header("Accept-Language", "ru-RU,ru;q=0.9")
            .timeout(30_000)
            .followRedirects(true)
            .get()

        parseGroupPage(
            doc = doc,
            group = cleanGroup
        )
    }

    suspend fun loadTeacher(
        teacher: String
    ): List<Lesson> = withContext(Dispatchers.IO) {

        val cleanTeacher = teacher.trim()

        if (cleanTeacher.isBlank()) {
            return@withContext emptyList()
        }

        val teacherUrl = findTeacherUrl(cleanTeacher)
            ?: return@withContext emptyList()

        val doc = Jsoup.connect(teacherUrl)
            .userAgent(
                "Mozilla/5.0 (Linux; Android 13) " +
                    "AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            )
            .header("Accept-Language", "ru-RU,ru;q=0.9")
            .timeout(30_000)
            .followRedirects(true)
            .get()

        parseTeacherPage(
            doc = doc,
            teacherName = cleanTeacher
        )
    }

    private fun parseGroupPage(
        doc: Document,
        group: String
    ): List<Lesson> {

        val result = mutableListOf<Lesson>()

        for (table in doc.select("table")) {

            for (row in table.select("tr")) {

                val cells = row
                    .select("th,td")
                    .map {
                        it.text()
                            .replace(Regex("\\s+"), " ")
                            .trim()
                    }

                if (cells.isEmpty()) continue

                val fullText = cells.joinToString(" ")

                if (!fullText.contains(group, ignoreCase = true)) {
                    continue
                }

                val timeIndex = cells.indexOfFirst {
                    Regex(
                        "\\d{1,2}:\\d{2}\\s*-\\s*\\d{1,2}:\\d{2}"
                    ).containsMatchIn(it)
                }

                if (timeIndex < 0) continue

                val time = cells[timeIndex]

                val subject = cells
                    .getOrNull(timeIndex + 1)
                    .orEmpty()

                if (subject.isBlank()) continue

                val type = cells
                    .firstOrNull { isLessonType(it) }
                    .orEmpty()

                val room = cells
                    .firstOrNull {
                        Regex(
                            "\\d+[А-Яа-яA-Za-z]?,\\s*.+"
                        ).matches(it)
                    }
                    .orEmpty()

                val teacher = cells
                    .firstOrNull {
                        it.contains(" ")
                            && !isLessonType(it)
                            && it != subject
                            && it != room
                            && !it.contains(group, true)
                    }
                    .orEmpty()

                result += Lesson(
                    date = LocalDate.now(),
                    time = time,
                    subject = subject,
                    teacher = teacher,
                    type = type,
                    room = room,
                    group = group
                )
            }
        }

        return result
    }

    private fun findTeacherUrl(
        teacher: String
    ): String? {

        val pages = listOf(
            "https://portfolio.vvsu.ru/",
            "https://portfolio.vvsu.ru/page/2/",
            "https://portfolio.vvsu.ru/page/3/"
        )

        for (page in pages) {

            try {

                val doc = Jsoup.connect(page)
                    .userAgent(
                        "Mozilla/5.0 (Linux; Android 13) " +
                            "AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
                    )
                    .timeout(20_000)
                    .get()

                val links = doc.select("a[href]")

                val exact = links.firstOrNull {
                    it.text()
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .equals(
                            teacher,
                            ignoreCase = true
                        )
                }

                if (exact != null) {
                    return exact.absUrl("href")
                }

                val partial = links.firstOrNull {
                    it.text()
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .contains(
                            teacher,
                            ignoreCase = true
                        )
                }

                if (partial != null) {
                    return partial.absUrl("href")
                }

            } catch (_: Exception) {
                continue
            }
        }

        return null
    }

    private fun parseTeacherPage(
        doc: Document,
        teacherName: String
    ): List<Lesson> {

        val result = mutableListOf<Lesson>()

        for (table in doc.select("table")) {

            val rows = table.select("tr")

            for (row in rows) {

                val cells = row
                    .select("th,td")
                    .map {
                        it.text()
                            .replace(Regex("\\s+"), " ")
                            .trim()
                    }

                if (cells.size < 4) continue

                val dateIndex = cells.indexOfFirst {
                    Regex(
                        "\\d{1,2}\\.\\d{1,2}\\.\\d{4}"
                    ).containsMatchIn(it)
                }

                val timeIndex = cells.indexOfFirst {
                    Regex(
                        "\\d{1,2}:\\d{2}\\s*-\\s*\\d{1,2}:\\d{2}"
                    ).containsMatchIn(it)
                }

                if (dateIndex < 0 || timeIndex < 0) continue

                val dateText = Regex(
                    "\\d{1,2}\\.\\d{1,2}\\.\\d{4}"
                )
                    .find(cells[dateIndex])
                    ?.value
                    ?: continue

                val date = try {
                    LocalDate.parse(
                        dateText,
                        DateTimeFormatter.ofPattern(
                            "d.M.yyyy",
                            Locale("ru")
                        )
                    )
                } catch (_: Exception) {
                    continue
                }

                val time = cells[timeIndex]

                val subject = cells
                    .getOrNull(timeIndex + 1)
                    .orEmpty()

                if (subject.isBlank()) continue

                val type = cells
                    .firstOrNull {
                        isLessonType(it)
                    }
                    .orEmpty()

                val room = cells
                    .firstOrNull {
                        Regex(
                            "\\d+[А-Яа-яA-Za-z]?,\\s*.+"
                        ).matches(it)
                    }
                    .orEmpty()

                val group = cells
                    .drop(timeIndex + 1)
                    .firstOrNull {
                        it.contains("-")
                            && !isLessonType(it)
                            && it != subject
                            && it != room
                    }
                    .orEmpty()

                result += Lesson(
                    date = date,
                    time = time,
                    subject = subject,
                    teacher = teacherName,
                    type = type,
                    room = room,
                    group = group
                )
            }
        }

        return result
    }

    private fun isLessonType(
        value: String
    ): Boolean {

        return when (
            value.trim().lowercase(Locale("ru"))
        ) {
            "лекция",
            "практика",
            "семинар",
            "лабораторная",
            "экзамен",
            "мероприятие" -> true

            else -> false
        }
    }
}
