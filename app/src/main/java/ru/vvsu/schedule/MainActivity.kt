package ru.vvsu.schedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Lesson(
    val time: String,
    val subject: String,
    val teacher: String,
    val type: String,
    val room: String,
    val group: String = ""
)

class MainViewModel : androidx.lifecycle.ViewModel() {
    var selectedGroup by mutableStateOf("")
    var selectedTeacher by mutableStateOf("")
    var selectedTeacherUrl by mutableStateOf("")
    var mode by mutableStateOf("group")
    var date by mutableStateOf(LocalDate.now())
    var darkTheme by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var lessons by mutableStateOf<List<Lesson>>(emptyList())

    fun loadSchedule() {
        if (mode == "group" && selectedGroup.isBlank()) return
        if (mode == "teacher" && selectedTeacherUrl.isBlank()) return
        loading = true
        error = null
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val repo = VvsuTimetableRepository()
                val result = if (mode == "group")
                    repo.loadGroup(selectedGroup)
                else
                    repo.loadTeacher(selectedTeacherUrl, selectedTeacher)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    lessons = result
                    loading = false
                    if (result.isEmpty()) error = "В расписании не найдено занятий для этой группы."
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    loading = false
                    error = e.message ?: "Не удалось загрузить расписание ВВГУ."
                    lessons = emptyList()
                }
            }
        }
    }
}


private class VvsuTimetableRepository {
    private val base = "https://www.vvsu.ru/timetable/"

    suspend fun loadGroup(group: String): List<Lesson> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val doc = org.jsoup.Jsoup.connect(base)
                .userAgent("Mozilla/5.0 (Android) VVSU-Schedule/1.0")
                .timeout(20_000).get()
            parseScheduleTables(doc, groupFilter = group)
        }

    suspend fun loadTeacher(url: String, teacherName: String): List<Lesson> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val doc = org.jsoup.Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Android) VVSU-Schedule/1.0")
                .timeout(20_000).get()
            parseTeacherTables(doc, teacherName)
        }

    private fun parseScheduleTables(
        doc: org.jsoup.nodes.Document,
        groupFilter: String
    ): List<Lesson> {
        val result = mutableListOf<Lesson>()
        for (table in doc.select("table")) {
            val rows = table.select("tr")
            if (rows.isEmpty()) continue
            for (row in rows) {
                val cells = row.select("th,td").map { it.text().trim() }
                if (cells.size < 3) continue
                val time = cells.firstOrNull { Regex("""\d{1,2}:\d{2}\s*-\s*\d{1,2}:\d{2}""").containsMatchIn(it) } ?: continue
                val subject = cells.getOrNull(cells.indexOf(time) + 1).orEmpty()
                if (subject.isBlank()) continue
                val type = cells.firstOrNull { isLessonType(it) }.orEmpty()
                val room = cells.firstOrNull { it.matches(Regex("""\d+[а-яА-Яa-zA-Z]?,.*""")) }.orEmpty()
                result += Lesson(time, subject, "", type, room, groupFilter)
            }
        }
        return result
    }

    private fun parseTeacherTables(
        doc: org.jsoup.nodes.Document,
        teacherName: String
    ): List<Lesson> {
        val result = mutableListOf<Lesson>()
        for (table in doc.select("table")) {
            val rows = table.select("tr")
            for (row in rows.drop(1)) {
                val cells = row.select("th,td").map { it.text().trim() }
                if (cells.size < 4) continue
                val timeIndex = cells.indexOfFirst {
                    Regex("""\d{1,2}:\d{2}\s*-\s*\d{1,2}:\d{2}""").containsMatchIn(it)
                }
                if (timeIndex < 0) continue
                val time = cells[timeIndex]
                val subject = cells.getOrNull(timeIndex + 1).orEmpty()
                if (subject.isBlank()) continue
                val type = cells.firstOrNull { isLessonType(it) }.orEmpty()
                val room = cells.firstOrNull {
                    it.matches(Regex("""\d+[а-яА-Яa-zA-Z]?,.*"""))
                }.orEmpty()
                val group = cells.drop(timeIndex + 1)
                    .firstOrNull { it.contains("-") && !isLessonType(it) && it != subject && it != room }
                    .orEmpty()
                result += Lesson(time, subject, teacherName, type, room, group)
            }
        }
        return result
    }

    private fun isLessonType(value: String): Boolean {
        val v = value.trim().lowercase()
        return v == "лекция" || v == "практика" || v == "семинар" ||
            v == "лабораторная" || v == "экзамен" || v == "мероприятие"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel = viewModel()) {
    MaterialTheme(
        colorScheme = if (vm.darkTheme) darkColorScheme() else lightColorScheme()
    ) {
        var tab by remember { mutableIntStateOf(0) }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (tab == 0) "Расписание" else "Настройки") },
                    actions = {
                        if (tab == 0) {
                            IconButton(onClick = { vm.loadSchedule() }) {
                                Icon(Icons.Default.Refresh, "Обновить")
                            }
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.DateRange, null) },
                        label = { Text("Расписание") }
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.Settings, null) },
                        label = { Text("Настройки") }
                    )
                }
            }
        ) { padding ->
            if (tab == 0) ScheduleScreen(vm, Modifier.padding(padding))
            else SettingsScreen(vm, Modifier.padding(padding))
        }
    }
}

@Composable
fun ScheduleScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showSelector by remember { mutableStateOf(false) }

    if (showSelector) {
        SelectorScreen(vm, onBack = { showSelector = false })
        return
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = vm.mode == "group",
                onClick = { vm.mode = "group" },
                label = { Text("👥 Группа") }
            )
            FilterChip(
                selected = vm.mode == "teacher",
                onClick = { vm.mode = "teacher" },
                label = { Text("👨‍🏫 Преподаватель") }
            )
        }

        Spacer(Modifier.height(10.dp))

        OutlinedCard(
            onClick = { showSelector = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (vm.mode == "group") "Группа" else "Преподаватель",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        if (vm.mode == "group")
                            vm.selectedGroup.ifBlank { "Не выбрана" }
                        else
                            vm.selectedTeacher.ifBlank { "Не выбран" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Icon(Icons.Default.ChevronRight, null)
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = { vm.date = vm.date.minusDays(1) }) {
                Icon(Icons.Default.ChevronLeft, "Предыдущий день")
            }
            TextButton(onClick = { showDatePicker = true }) {
                Text(vm.date.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale("ru"))))
            }
            IconButton(onClick = { vm.date = vm.date.plusDays(1) }) {
                Icon(Icons.Default.ChevronRight, "Следующий день")
            }
        }

        HorizontalDivider()

        if (vm.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (vm.error != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(vm.error ?: "")
            }
        } else if (vm.lessons.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if ((vm.mode == "group" && vm.selectedGroup.isBlank()) ||
                        (vm.mode == "teacher" && vm.selectedTeacher.isBlank())
                    ) "Выберите группу или преподавателя"
                    else "На этот день занятий нет"
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(vm.lessons) { lesson ->
                    LessonCard(lesson)
                }
            }
        }

        if (showDatePicker) {
            val state = rememberDatePickerState(
                initialSelectedDateMillis = System.currentTimeMillis()
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let {
                            vm.date = java.time.Instant.ofEpochMilli(it)
                                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        }
                        showDatePicker = false
                    }) { Text("Выбрать") }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text("Отмена") }
                }
            ) { DatePicker(state) }
        }
    }
}

@Composable
fun LessonCard(lesson: Lesson) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(lesson.time, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(lesson.subject, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            if (lesson.teacher.isNotBlank()) Text("👤 ${lesson.teacher}")
            if (lesson.type.isNotBlank()) Text("🎓 ${lesson.type}")
            if (lesson.room.isNotBlank()) Text("🏫 ${lesson.room}")
            if (lesson.group.isNotBlank()) Text("👥 ${lesson.group}")
        }
    }
}

@Composable
fun SelectorScreen(vm: MainViewModel, onBack: () -> Unit) {
    var text by remember {
        mutableStateOf(if (vm.mode == "group") vm.selectedGroup else vm.selectedTeacher)
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
            Text(
                if (vm.mode == "group") "Выбор группы" else "Выбор преподавателя",
                style = MaterialTheme.typography.headlineSmall
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (vm.mode == "group") "Группа" else "ФИО преподавателя") },
            singleLine = true
        )
        if (vm.mode == "teacher") {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.selectedTeacherUrl,
                onValueChange = { vm.selectedTeacherUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ссылка на расписание ВВГУ") },
                supportingText = { Text("Публичная страница portfolio.vvsu.ru/timetable/...") },
                singleLine = true
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (vm.mode == "group") vm.selectedGroup = text.trim()
                else {
                    vm.selectedTeacher = text.trim()
                }
                vm.loadSchedule()
                onBack()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Выбрать") }

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = {
            // Future: open official VVSU personal cabinet in Custom Tab.
        }) {
            Text("Вход через ЛК ВВГУ")
        }
        Text(
            "Без входа можно использовать публичное расписание. Авторизация будет дополнительной функцией.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Внешний вид", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Тёмная тема")
            Switch(checked = vm.darkTheme, onCheckedChange = { vm.darkTheme = it })
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Text("Расписание", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ListItem(
            headlineContent = { Text("Группа") },
            supportingContent = { Text(vm.selectedGroup.ifBlank { "Не выбрана" }) },
            leadingContent = { Icon(Icons.Default.Group, null) }
        )
        ListItem(
            headlineContent = { Text("Преподаватель") },
            supportingContent = { Text(vm.selectedTeacher.ifBlank { "Не выбран" }) },
            leadingContent = { Icon(Icons.Default.Person, null) }
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Автообновление") },
            supportingContent = { Text("Обновлять расписание при запуске и вручную") },
            leadingContent = { Icon(Icons.Default.Sync, null) }
        )
        ListItem(
            headlineContent = { Text("Уведомления") },
            supportingContent = { Text("Напоминания о занятиях — следующий этап") },
            leadingContent = { Icon(Icons.Default.Notifications, null) }
        )
        ListItem(
            headlineContent = { Text("О приложении") },
            supportingContent = { Text("ВВГУ — Расписание • версия 1.0.0") },
            leadingContent = { Icon(Icons.Default.Info, null) }
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
