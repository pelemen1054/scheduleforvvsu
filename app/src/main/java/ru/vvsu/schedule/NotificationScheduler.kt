package ru.vvsu.schedule

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.random.Random

object NotificationScheduler {
    private const val CHANNEL_ID = "lesson_reminders"
    private const val PREFS = "notification_schedule"
    private const val IDS = "scheduled_ids"
    private const val GAP_THRESHOLD_MINUTES = 30L
    private const val CONTINUOUS_GAP_MINUTES = 15L

    fun schedule(
        context: Context,
        lessons: List<Lesson>,
        enabled: Boolean,
        leadMinutes: Int,
        notifyBeforeLesson: Boolean,
        notifyAfterLongWindow: Boolean
    ) {
        cancelAll(context)
        if (!enabled || lessons.isEmpty()) return
        createChannel(context)
        val now = LocalDateTime.now()
        val sorted = lessons.sortedWith(compareBy<Lesson>({ it.date }, { parseStart(it.time) }))

        sorted.forEachIndexed { index, lesson ->
            val start = parseDateTime(lesson)
            if (start == null || !start.isAfter(now)) return@forEachIndexed

            val previous = sorted.getOrNull(index - 1)
            val previousEnd = previous?.let { parseEndDateTime(it) }
            val gap = if (previousEnd != null && previous.date == lesson.date) {
                java.time.Duration.between(previousEnd, start).toMinutes()
            } else Long.MAX_VALUE

            val shouldNotify =
                (notifyBeforeLesson && (previousEnd == null || gap > CONTINUOUS_GAP_MINUTES)) ||
                (notifyAfterLongWindow && gap >= GAP_THRESHOLD_MINUTES)

            if (!shouldNotify) return@forEachIndexed

            val minutes = leadMinutes.coerceAtLeast(1)
            val notifyAt = start.minusMinutes(minutes.toLong())
            if (!notifyAt.isAfter(now)) return@forEachIndexed

            val requestCode = abs(("${lesson.date}|${lesson.time}|${lesson.subject}|$minutes").hashCode())
            scheduleOne(context, requestCode, notifyAt, lesson, minutes)
        }
    }

    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(IDS, emptySet()).orEmpty()
        ids.forEach { raw ->
            raw.toIntOrNull()?.let { requestCode ->
                val intent = Intent(context, LessonNotificationReceiver::class.java)
                val pending = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                if (pending != null) {
                    alarmManager.cancel(pending)
                    pending.cancel()
                }
            }
        }
        prefs.edit().remove(IDS).apply()
    }

    private fun scheduleOne(context: Context, requestCode: Int, notifyAt: LocalDateTime, lesson: Lesson, minutes: Int) {
        val intent = Intent(context, LessonNotificationReceiver::class.java).apply {
            putExtra("subject", lesson.subject)
            putExtra("time", lesson.time)
            putExtra("minutes", minutes)
        }
        val pending = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val trigger = notifyAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(IDS, emptySet()).orEmpty().toMutableSet()
        ids.add(requestCode.toString())
        prefs.edit().putStringSet(IDS, ids).apply()
    }

    private fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Напоминания о парах", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Напоминания о ближайших занятиях"
        })
    }

    private fun parseStart(time: String): Int = time.substringBefore("-").trim().replace(":", "").toIntOrNull() ?: Int.MAX_VALUE

    private fun parseDateTime(lesson: Lesson): LocalDateTime? = runCatching {
        LocalDateTime.of(lesson.date, java.time.LocalTime.parse(lesson.time.substringBefore("-").trim(), DateTimeFormatter.ofPattern("H:mm")))
    }.getOrNull()

    private fun parseEndDateTime(lesson: Lesson): LocalDateTime? = runCatching {
        LocalDateTime.of(lesson.date, java.time.LocalTime.parse(lesson.time.substringAfter("-", "").trim(), DateTimeFormatter.ofPattern("H:mm")))
    }.getOrNull()
}

class LessonNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val subject = intent.getStringExtra("subject") ?: "занятие"
        val time = intent.getStringExtra("time") ?: ""
        val minutes = intent.getIntExtra("minutes", 30)
        val variants = listOf(
            "Помни про время! Пара $subject начинается через $minutes минут.",
            "Скоро пара: $subject. До начала $minutes минут.",
            "Напоминание: $subject начинается через $minutes минут.",
            "Не забудь про пару $subject — она начнётся через $minutes минут."
        )
        val text = variants[Random.nextInt(variants.size)]
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(context, "lesson_reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Расписание ВВГУ • $time")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(abs(("${subject}|$time").hashCode()), notification)
    }
}
