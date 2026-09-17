@file:OptIn(ExperimentalMaterial3Api::class)

package ru.vvsu.schedule

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Lesson(
    val date: LocalDate,
    val time: String,
    val subject: String,
    val teacher: String,
    val type: String,
    val room: String,
    val group: String = ""
)

class MainViewModel : ViewModel() {

    var selectedGroup by mutableStateOf("")
    var selectedTeacher by mutableStateOf("")
    var selectedTeacherUrl by mutableStateOf("")

    var mode by mutableStateOf("group")
    var date by mutableStateOf(LocalDate.now())

    var darkTheme by mutableStateOf(false)
    var autoRefresh by mutableStateOf(true)
    var notificationsEnabled by mutableStateOf(true)
    var themeColor by mutableStateOf("blue")

    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var lessons by mutableStateOf<List<Lesson>>(emptyList())

    private val repository = VvsuRepository()

    fun loadSchedule() {
        if (mode == "group" && selectedGroup.isBlank()) return
        if (mode == "teacher" && selectedTeacher.isBlank()) return

        loading = true
        error = null

        viewModelScope.launch {
            try {
                val result = if (mode == "group") {
                    repository.loadGroup(selectedGroup)
                } else {
                    repository.loadTeacher(selectedTeacher)
                }

                lessons = result
                    .filter { it.date == date }
                    .sortedBy { it.time }

                loading = false

                if (result.isEmpty()) {
                    error = if (mode == "group") {
                        "Расписание группы не найдено."
                    } else {
                        "Расписание преподавателя не найдено."
                    }
                }
            } catch (e: Exception) {
                loading = false
                lessons = emptyList()

                error = when {
                    e.message?.contains("Trust anchor", true) == true ->
                        "Не удалось установить защищённое соединение с ВВГУ. " +
                            "Попробуйте обновить приложение или открыть сайт ВВГУ в браузере."

                    e.message?.contains("Unable to resolve host", true) == true ->
                        "Нет соединения с интернетом."

                    else ->
                        e.message ?: "Не удалось загрузить расписание ВВГУ."
                }
            }
        }
    }

    fun changeDate(newDate: LocalDate) {
        date = newDate
        loadSchedule()
    }

    fun selectMode(newMode: String) {
        mode = newMode
        lessons = emptyList()
        error = null

        if (
            (newMode == "group" && selectedGroup.isNotBlank()) ||
            (newMode == "teacher" && selectedTeacher.isNotBlank())
        ) {
            loadSchedule()
        }
    }
}

@Composable
fun TimetableTheme(
    darkTheme: Boolean,
    colorName: String,
    content: @Composable () -> Unit
) {
    val primary = when (colorName) {
        "red" -> Color(0xFFD32F2F)
        "orange" -> Color(0xFFEF6C00)
        "green" -> Color(0xFF388E3C)
        "purple" -> Color(0xFF7B1FA2)
        "pink" -> Color(0xFFC2185B)
        "teal" -> Color(0xFF00796B)
        "blue" -> Color(0xFF1565C0)
        else -> Color(0xFF1565C0)
    }

    val scheme = if (darkTheme) {
        darkColorScheme(primary = primary)
    } else {
        lightColorScheme(primary = primary)
    }

    MaterialTheme(
        colorScheme = scheme,
        content = content
    )
}

@Composable
fun App(vm: MainViewModel = viewModel()) {

    var tab by remember { mutableIntStateOf(0) }
    var showSelector by remember { mutableStateOf(false) }

    TimetableTheme(
        darkTheme = vm.darkTheme,
        colorName = vm.themeColor
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (tab == 0) "Timetable"
                            else "Настройки"
                        )
                    },
                    actions = {
                        if (tab == 0) {
                            IconButton(
                                onClick = { vm.loadSchedule() }
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Обновить"
                                )
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
                        icon = {
                            Icon(
                                Icons.Default.DateRange,
                                contentDescription = null
                            )
                        },
                        label = {
                            Text("Расписание")
                        }
                    )

                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = null
                            )
                        },
                        label = {
                            Text("Настройки")
                        }
                    )
                }
            }
        ) { padding ->

            if (tab == 0) {
                ScheduleScreen(
                    vm = vm,
                    modifier = Modifier.padding(padding),
                    showSelector = showSelector,
                    onShowSelector = {
                        showSelector = true
                    },
                    onCloseSelector = {
                        showSelector = false
                    }
                )
            } else {
                SettingsScreen(
                    vm = vm,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
fun ScheduleScreen(
    vm: MainViewModel,
    modifier: Modifier,
    showSelector: Boolean,
    onShowSelector: () -> Unit,
    onCloseSelector: () -> Unit
) {

    if (showSelector) {
        SelectorScreen(
            vm = vm,
            onBack = onCloseSelector
        )
        return
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            FilterChip(
                selected = vm.mode == "group",
                onClick = {
                    vm.selectMode("group")
                },
                label = {
                    Text("👥 Группа")
                }
            )

            FilterChip(
                selected = vm.mode == "teacher",
                onClick = {
                    vm.selectMode("teacher")
                },
                label = {
                    Text("👨‍🏫 Преподаватель")
                }
            )
        }

        Spacer(Modifier.height(10.dp))

        OutlinedCard(
            onClick = onShowSelector,
            modifier = Modifier.fillMaxWidth()
        ) {

            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Column(
                    Modifier.weight(1f)
                ) {

                    Text(
                        if (vm.mode == "group")
                            "Группа"
                        else
                            "Преподаватель",
                        style = MaterialTheme.typography.labelMedium
                    )

                    Text(
                        if (vm.mode == "group") {
                            vm.selectedGroup.ifBlank {
                                "Не выбрана"
                            }
                        } else {
                            vm.selectedTeacher.ifBlank {
                                "Не выбран"
                            }
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {

            IconButton(
                onClick = {
                    vm.changeDate(vm.date.minusDays(1))
                }
            ) {
                Icon(
                    Icons.Default.ChevronLeft,
                    "Предыдущий день"
                )
            }

            TextButton(
                onClick = {
                    vm.changeDate(LocalDate.now())
                }
            ) {
                Text(
                    vm.date.format(
                        DateTimeFormatter.ofPattern(
                            "d MMMM, EEEE",
                            Locale("ru")
                        )
                    )
                )
            }

            IconButton(
                onClick = {
                    vm.changeDate(vm.date.plusDays(1))
                }
            ) {
                Icon(
                    Icons.Default.ChevronRight,
                    "Следующий день"
                )
            }
        }

        HorizontalDivider()

        when {
            vm.loading -> {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            vm.error != null -> {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        vm.error ?: "",
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }

            vm.lessons.isEmpty() -> {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (
                            (vm.mode == "group" &&
                                    vm.selectedGroup.isBlank()) ||
                            (vm.mode == "teacher" &&
                                    vm.selectedTeacher.isBlank())
                        ) {
                            "Выберите группу или преподавателя"
                        } else {
                            "На этот день занятий нет"
                        }
                    )
                }
            }

            else -> {
                LazyColumn(
                    verticalArrangement =
                        Arrangement.spacedBy(10.dp),
                    contentPadding =
                        PaddingValues(vertical = 12.dp)
                ) {

                    items(vm.lessons) { lesson ->
                        LessonCard(lesson)
                    }
                }
            }
        }
    }
}

@Composable
fun LessonCard(lesson: Lesson) {

    ElevatedCard(
        Modifier.fillMaxWidth()
    ) {

        Column(
            Modifier.padding(16.dp)
        ) {

            Text(
                lesson.time,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(5.dp))

            Text(
                lesson.subject,
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(Modifier.height(8.dp))

            if (lesson.teacher.isNotBlank()) {
                Text("👤 ${lesson.teacher}")
            }

            if (lesson.type.isNotBlank()) {
                Text("🎓 ${lesson.type}")
            }

            if (lesson.room.isNotBlank()) {
                Text("🏫 ${lesson.room}")
            }

            if (lesson.group.isNotBlank()) {
                Text("👥 ${lesson.group}")
            }
        }
    }
}

@Composable
fun SelectorScreen(
    vm: MainViewModel,
    onBack: () -> Unit
) {
    var text by remember {
        mutableStateOf(
            if (vm.mode == "group") {
                vm.selectedGroup
            } else {
                vm.selectedTeacher
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Назад"
                )
            }

            Text(
                if (vm.mode == "group") {
                    "Выбор группы"
                } else {
                    "Выбор преподавателя"
                },
                style = MaterialTheme.typography.headlineSmall
            )
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
            },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    if (vm.mode == "group") {
                        "Название группы"
                    } else {
                        "ФИО преподавателя"
                    }
                )
            },
            placeholder = {
                Text(
                    if (vm.mode == "group") {
                        "Например: БИС-24-1"
                    } else {
                        "Например: Иванов Иван Иванович"
                    }
                )
            },
            singleLine = true
        )

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                if (vm.mode == "group") {
                    vm.selectedGroup = text.trim()
                } else {
                    vm.selectedTeacher = text.trim()
                }

                vm.loadSchedule()
                onBack()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Выбрать")
        }

        Spacer(Modifier.height(24.dp))

        if (vm.mode == "teacher") {
            Text(
                "Приложение само ищет страницу преподавателя на официальном портале ВВГУ.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(16.dp))

        OpenUrlButton("https://fort.vvsu.ru/openid/") {
            Icon(
                Icons.Default.Login,
                contentDescription = null
            )

            Spacer(Modifier.width(8.dp))

            Text("Вход через ЛК ВВГУ")
        }
    }
}

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    modifier: Modifier
) {

    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        Text(
            "Внешний вид",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text("Тёмная тема")

            Switch(
                checked = vm.darkTheme,
                onCheckedChange = {
                    vm.darkTheme = it
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "Цвет приложения",
            style = MaterialTheme.typography.titleSmall
        )

        Spacer(Modifier.height(8.dp))

        val colors = listOf(
            "blue" to "Синий",
            "red" to "Красный",
            "orange" to "Оранжевый",
            "green" to "Зелёный",
            "purple" to "Фиолетовый",
            "pink" to "Розовый",
            "teal" to "Бирюзовый"
        )

        colors.forEach { (value, title) ->

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {

                RadioButton(
                    selected = vm.themeColor == value,
                    onClick = {
                        vm.themeColor = value
                    }
                )

                Text(title)
            }
        }

        HorizontalDivider(
            Modifier.padding(vertical = 16.dp)
        )

        Text(
            "Расписание",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        ListItem(
            headlineContent = {
                Text("Группа")
            },
            supportingContent = {
                Text(
                    vm.selectedGroup.ifBlank {
                        "Не выбрана"
                    }
                )
            },
            leadingContent = {
                Icon(
                    Icons.Default.Group,
                    null
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        ListItem(
            headlineContent = {
                Text("Преподаватель")
            },
            supportingContent = {
                Text(
                    vm.selectedTeacher.ifBlank {
                        "Не выбран"
                    }
                )
            },
            leadingContent = {
                Icon(
                    Icons.Default.Person,
                    null
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Icon(
                Icons.Default.Sync,
                null
            )

            Spacer(Modifier.width(16.dp))

            Column(
                Modifier.weight(1f)
            ) {

                Text(
                    "Автообновление",
                    fontWeight = FontWeight.Medium
                )

                Text(
                    "Обновлять расписание автоматически"
                )
            }

            Switch(
                checked = vm.autoRefresh,
                onCheckedChange = {
                    vm.autoRefresh = it
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Icon(
                Icons.Default.Notifications,
                null
            )

            Spacer(Modifier.width(16.dp))

            Column(
                Modifier.weight(1f)
            ) {

                Text(
                    "Уведомления",
                    fontWeight = FontWeight.Medium
                )

                Text(
                    "Напоминания о занятиях"
                )
            }

            Switch(
                checked = vm.notificationsEnabled,
                onCheckedChange = {
                    vm.notificationsEnabled = it
                }
            )
        }

        Spacer(Modifier.height(12.dp))

       OpenUrlButton("https://www.vvsu.ru/") {
    Icon(Icons.Default.Language, null)
    Spacer(Modifier.width(8.dp))
    Text("Официальный сайт ВВГУ")
}

        Spacer(Modifier.height(16.dp))

        Text(
            "Timetable • версия 1.0.0",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun OpenUrlButton(
    url: String,
    content: @Composable RowScope.() -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Button(
        onClick = {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
                )
            )
        },
        content = content
    )
}

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContent {
            App()
        }
    }

    private fun requestNotificationPermission() {

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }
}
