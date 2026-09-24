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

    try {

        installTemporaryTrustAllCertificates()

        /*
         * ШАГ 1.
         * Получаем ID группы через официальный фильтр ВВГУ.
         */
        val filterUrl = "https://www.vvsu.ru/local/controllers/getFilterValues.php"

        val filterResponse = Jsoup.connect(filterUrl)
            .userAgent(
                "Mozilla/5.0 (Linux; Android 13) " +
                    "AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            )
            .header(
                "Accept-Language",
                "ru-RU,ru;q=0.9"
            )
            .timeout(30_000)
            .ignoreContentType(true)
            .data("substr", cleanGroup)
            .data("hlBlockId", "46")
            .data("hlBlockFieldName", "UF_GROUP_NAME")
            .data("hlBlockFieldId", "UF_GROUP_ID")
            .get()

        val json = org.json.JSONObject(
            filterResponse.text()
        )

        val data = json.optJSONArray("data")
            ?: return@withContext emptyList()

        var groupId: String? = null
        var groupName: String? = null

        for (i in 0 until data.length()) {

            val item = data.optJSONObject(i)
                ?: continue

            val value = item.optString("value")

            if (
                value.equals(
                    cleanGroup,
                    ignoreCase = true
                )
            ) {
                groupId = item.optString("id")
                groupName = value
                break
            }
        }

        if (groupId == null) {
            android.util.Log.e(
                "VVSU_TEST",
                "Группа не найдена: $cleanGroup"
            )

            return@withContext emptyList()
        }

        android.util.Log.d(
            "VVSU_TEST",
            "Найдена группа: $groupName, ID: $groupId"
        )

        /*
         * ШАГ 2.
         * Повторяем POST-запрос официального сайта.
         */
        val filterQueryParams = """
            [
                {
                    "valueId":"$groupId",
                    "valueData":"$groupName",
                    "hlBlockId":46,
                    "hlBlockFieldName":"UF_GROUP_NAME",
                    "hlBlockFieldId":"UF_GROUP_ID"
                }
            ]
        """.trimIndent()

        val requestBody = """
            {
                "filterQueryParams": $filterQueryParams
            }
        """.trimIndent()

        val scheduleResponse = Jsoup.connect(
            "https://www.vvsu.ru/timetable/index.php"
        )
            .userAgent(
                "Mozilla/5.0 (Linux; Android 13) " +
                    "AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            )
            .header(
                "Accept",
                "text/html, */*; q=0.01"
            )
            .header(
                "Accept-Language",
                "ru-RU,ru;q=0.9"
            )
            .header(
                "Content-Type",
                "application/json"
            )
            .header(
                "X-Requested-With",
                "XMLHttpRequest"
            )
            .requestBody(requestBody)
            .timeout(30_000)
            .ignoreContentType(true)
            .followRedirects(true)
            .method(org.jsoup.Connection.Method.POST)
            .execute()

        val html = scheduleResponse.body()

        android.util.Log.d(
            "VVSU_TEST",
            "Расписание получено. HTML: ${html.length} символов"
        )

        val doc = Jsoup.parse(
            html,
            "https://www.vvsu.ru/timetable/"
        )

        parseGroupPage(
            doc = doc,
            group = cleanGroup
        )

    } catch (e: Exception) {

        android.util.Log.e(
            "VVSU_TEST",
            "Ошибка загрузки расписания группы",
            e
        )

        emptyList()
    }
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
                .select("td[data-th]")
                .map {
                    val label = it.attr("data-th")
                        .replace(Regex("\\s+"), " ")
                        .trim()

                    val value = it.text()
                        .replace(Regex("\\s+"), " ")
                        .trim()

                    label to value
                }

            if (cells.isEmpty()) {
                continue
            }

            /*
             * Получаем значения по названиям колонок.
             */
            val dateText = cells
                .firstOrNull {
                    it.first.equals(
                        "Дата",
                        ignoreCase = true
                    )
                }
                ?.second
                .orEmpty()

            val timeText = cells
                .firstOrNull {
                    it.first.equals(
                        "Время",
                        ignoreCase = true
                    )
                }
                ?.second
                .orEmpty()

            val subject = cells
                .firstOrNull {
                    it.first.equals(
                        "Дисциплина",
                        ignoreCase = true
                    )
                }
                ?.second
                .orEmpty()

            val type = cells
                .firstOrNull {
                    it.first.equals(
                        "Занятие",
                        ignoreCase = true
                    )
                }
                ?.second
                .orEmpty()

            val room = cells
                .firstOrNull {
                    it.first.equals(
                        "Аудитория",
                        ignoreCase = true
                    )
                }
                ?.second
                .orEmpty()

            val teacher = cells
                .firstOrNull {
                    it.first.equals(
                        "Преподаватель",
                        ignoreCase = true
                    )
                }
                ?.second
                .orEmpty()

            /*
             * Дата есть только у первой строки каждого дня.
             * Для остальных строк используем предыдущую дату.
             */
            val dateMatch = dateRegex.find(dateText)

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

                val date = currentDate
                    ?: continue

                /*
                 * Без времени это не занятие.
                 */
                if (!timeRegex.containsMatchIn(timeText)) {
                    continue
                }

                if (subject.isBlank()) {
                    continue
                }

                val time = timeRegex
                    .find(timeText)
                    ?.value
                    ?: continue

                result += Lesson(
                    date = date,
                    time = time,
                    subject = subject,
                    teacher = teacher,
                    type = type,
                    room = room,
                    group = group
                )

            } else {

                /*
                 * Продолжение того же дня.
                 */
                val date = currentDate
                    ?: continue

                if (!timeRegex.containsMatchIn(timeText)) {
                    continue
                }

                if (subject.isBlank()) {
                    continue
                }

                val time = timeRegex
                    .find(timeText)
                    ?.value
                    ?: continue

                result += Lesson(
                    date = date,
                    time = time,
                    subject = subject,
                    teacher = teacher,
                    type = type,
                    room = room,
                    group = group
                )
            }
        }
    }

    android.util.Log.d(
        "VVSU_TEST",
        "Найдено занятий группы $group: ${result.size}"
    )

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
