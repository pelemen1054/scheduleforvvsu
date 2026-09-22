package ru.vvsu.schedule

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class VvsuRepository {

    private val timetableUrl =
        "https://www.vvsu.ru/timetable/"

    private val portfolioUrl =
        "https://portfolio.vvsu.ru/"

    /*
     * ВРЕМЕННЫЙ ДИАГНОСТИЧЕСКИЙ РЕЖИМ.
     *
     * Он отключает проверку SSL-сертификатов, чтобы проверить,
     * может ли приложение вообще получить страницу ВВГУ.
     *
     * НЕ ОСТАВЛЯТЬ В ФИНАЛЬНОЙ ВЕРСИИ!
     */
    private fun installTemporaryTrustAllCertificates() {

        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {

                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    return emptyArray()
                }

                override fun checkClientTrusted(
                    chain: Array<X509Certificate>,
                    authType: String
                ) {
                }

                override fun checkServerTrusted(
                    chain: Array<X509Certificate>,
                    authType: String
                ) {
                }
            }
        )

        val sslContext = SSLContext.getInstance("TLS")

        sslContext.init(
            null,
            trustAllCerts,
            SecureRandom()
        )

        HttpsURLConnection.setDefaultSSLSocketFactory(
            sslContext.socketFactory
        )

        HttpsURLConnection.setDefaultHostnameVerifier(
            HostnameVerifier { _, _ ->
                true
            }
        )
    }

    suspend fun loadGroup(
        group: String
    ): List<Lesson> = withContext(Dispatchers.IO) {

        val cleanGroup = group.trim()

        if (cleanGroup.isBlank()) {
            return@withContext emptyList()
        }

        /*
         * ВРЕМЕННО:
         * отключаем SSL-проверку перед запросом ВВГУ.
         */
        installTemporaryTrustAllCertificates()

        val doc = Jsoup.connect(timetableUrl)
            .userAgent(
                "Mozilla/5.0 (Linux; Android 13) " +
                    "AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            )
            .header(
                "Accept-Language",
                "ru-RU,ru;q=0.9"
            )
            .timeout(30_000)
            .followRedirects(true)
            .get()

        /*
         * Диагностика:
         * если HTML действительно пришёл, приложение получит
         * страницу ВВГУ.
         */
        android.util.Log.d(
            "VVSU_TEST",
            "ВВГУ загружен. HTML: ${doc.html().length} символов"
        )

        android.util.Log.d(
            "VVSU_TEST",
            "Заголовок страницы: ${doc.title()}"
        )

        android.util.Log.d(
            "VVSU_TEST",
            "URL: ${doc.location()}"
        )

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

        /*
         * ВРЕМЕННО отключаем SSL-проверку.
         */
        installTemporaryTrustAllCertificates()

        val teacherUrl = findTeacherUrl(cleanTeacher)
            ?: return@withContext emptyList()

        android.util.Log.d(
            "VVSU_TEST",
            "Найдена страница преподавателя: $teacherUrl"
        )

        val doc = Jsoup.connect(teacherUrl)
            .userAgent(
                "Mozilla/5.0 (Linux; Android 13) " +
                    "AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            )
            .header(
                "Accept-Language",
                "ru-RU,ru;q=0.9"
            )
            .timeout(30_000)
            .followRedirects(true)
            .get()

        android.util.Log.d(
            "VVSU_TEST",
            "Страница преподавателя загружена. HTML: ${doc.html().length} символов"
        )

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

            } catch (e: Exception) {

                android.util.Log.e(
                    "VVSU_TEST",
                    "Ошибка поиска преподавателя: ${e.message}",
                    e
                )

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

    val dateRegex = Regex(
        "\\d{1,2}\\.\\d{1,2}\\.\\d{4}"
    )

    val timeRegex = Regex(
        "\\d{1,2}:\\d{2}\\s*-\\s*\\d{1,2}:\\d{2}"
    )

    var currentDate: LocalDate? = null

    for (table in doc.select("table")) {

        for (row in table.select("tr")) {

            val cells = row
                .select("th,td")
                .map {
                    it.text()
                        .replace(Regex("\\s+"), " ")
                        .trim()
                }
                .filter { it.isNotBlank() }

            if (cells.isEmpty()) continue

            val fullText = cells.joinToString(" ")

            // Пропускаем заголовки таблицы
            if (
                fullText.contains("День недели", ignoreCase = true) ||
                fullText.contains("Дисциплина", ignoreCase = true) ||
                fullText.contains("Время", ignoreCase = true)
            ) {
                continue
            }

            // Ищем дату в текущей строке.
            // На сайте ВВГУ дата есть только у первой пары данного дня.
            val dateMatch = dateRegex.find(fullText)

            if (dateMatch != null) {
                currentDate = try {
                    LocalDate.parse(
                        dateMatch.value,
                        DateTimeFormatter.ofPattern(
                            "d.M.yyyy",
                            Locale("ru")
                        )
                    )
                } catch (_: Exception) {
                    currentDate
                }
            }

            // Если дату ещё не нашли — строку пропускаем
            val date = currentDate ?: continue

            // Ищем время
            val timeIndex = cells.indexOfFirst {
                timeRegex.containsMatchIn(it)
            }

            if (timeIndex < 0) continue

            val time = timeRegex
                .find(cells[timeIndex])
                ?.value
                ?: continue

            /*
             * После времени на сайте идут:
             *
             * Время
             * Дисциплина
             * Форма занятия
             * Аудитория
             * Группа
             */

            val subjectIndex = timeIndex + 1

            if (subjectIndex >= cells.size) continue

            val subject = cells[subjectIndex]

            if (
                subject.isBlank() ||
                isLessonType(subject)
            ) {
                continue
            }

            // Тип занятия
            val typeIndex = cells.indexOfFirst {
                isLessonType(it)
            }

            val type = if (typeIndex >= 0) {
                cells[typeIndex]
            } else {
                ""
            }

            // Аудитория
            val room = cells.firstOrNull {
                Regex(
                    "\\d+[А-Яа-яA-Za-z]?\\s*,\\s*.+"
                ).matches(it)
            }.orEmpty()

            /*
             * Группа находится после аудитории.
             * Иногда групп несколько и сайт переносит их
             * на отдельные строки/ячейки.
             */
            val groups = cells
                .drop(subjectIndex + 1)
                .filter {
                    it != type &&
                    it != room &&
                    !timeRegex.containsMatchIn(it) &&
                    !dateRegex.containsMatchIn(it) &&
                    it.isNotBlank()
                }
                .filter {
                    // Похожие на обозначения учебных групп
                    it.contains("-") ||
                    it.contains("/")
                }

            val group = groups
                .distinct()
                .joinToString(", ")

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

    android.util.Log.d(
        "VVSU_TEST",
        "Найдено занятий преподавателя: ${result.size}"
    )

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
