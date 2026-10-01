@file:OptIn(ExperimentalMaterial3Api::class)

package ru.vvsu.schedule

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private var preferences: android.content.SharedPreferences? = null

    var selectedGroup by mutableStateOf("")
    var selectedTeacher by mutableStateOf("")

    var mode by mutableStateOf("group")
    var date by mutableStateOf(LocalDate.now())
    
    var darkTheme by mutableStateOf(false)

    var autoRefresh by mutableStateOf(true)

    var notificationsEnabled by mutableStateOf(true)

    var themeColor by mutableStateOf("blue")

    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var lessons by mutableStateOf<List<Lesson>>(emptyList())
    private var allLessons by mutableStateOf<List<Lesson>>(emptyList())
    var suggestions by mutableStateOf<List<String>>(emptyList())
    var favoriteGroups by mutableStateOf<Set<String>>(emptySet())
    var cacheNotice by mutableStateOf<String?>(null)

    private var suggestionsJob: Job? = null
    private val repository = VvsuRepository()

    fun init(context: Context) {

        if (preferences != null) return

        preferences = context.getSharedPreferences(
            "timetable_settings",
            Context.MODE_PRIVATE
        )

        val p = preferences ?: return

        selectedGroup =
            p.getString("selected_group", "") ?: ""

        selectedTeacher =
            p.getString("selected_teacher", "") ?: ""

        mode =
            p.getString("mode", "group") ?: "group"

        darkTheme =
            p.getBoolean("dark_theme", false)

        autoRefresh =
            p.getBoolean("auto_refresh", true)

        notificationsEnabled =
            p.getBoolean("notifications", true)

        themeColor =
            p.getString("theme_color", "blue") ?: "blue"

        favoriteGroups =
            p.getStringSet("favorite_groups", emptySet())
                ?.toSet()
                ?: emptySet()

        val cached = loadScheduleFromCache()
        if (cached != null) {
            allLessons = cached
            lessons = cached
                .filter { it.date == date }
                .sortedBy { it.time }
            cacheNotice = "Показано сохранённое расписание. Оно хранится 14 дней."
        }

        if (
            (mode == "group" && selectedGroup.isNotBlank()) ||
            (mode == "teacher" && selectedTeacher.isNotBlank())
        ) {
            loadSchedule()
        }
    }

    private fun saveSettings() {

        preferences
            ?.edit()
            ?.putString("selected_group", selectedGroup)
            ?.putString("selected_teacher", selectedTeacher)
            ?.putString("mode", mode)
            ?.putBoolean("dark_theme", darkTheme)
            ?.putBoolean("auto_refresh", autoRefresh)
            ?.putBoolean(
                "notifications",
                notificationsEnabled
            )
            ?.putString("theme_color", themeColor)
            ?.putStringSet("favorite_groups", favoriteGroups)
            ?.apply()
    }

    fun updateDarkTheme(value: Boolean) {
    darkTheme = value
    saveSettings()
    }

    fun updateThemeColor(value: String) {
    themeColor = value
    saveSettings()
    }

    fun updateAutoRefresh(value: Boolean) {
    autoRefresh = value
    saveSettings()
    }

    fun setNotifications(value: Boolean) {
    notificationsEnabled = value
    saveSettings()
    }

    fun setGroup(value: String) {
        selectedGroup = value.trim()
        saveSettings()
    }

    fun setTeacher(value: String) {
        selectedTeacher = value.trim()
        saveSettings()
    }

    fun isFavoriteGroup(group: String): Boolean {
        return favoriteGroups.contains(group.trim())
    }

    fun toggleFavoriteGroup(group: String) {
        val cleanGroup = group.trim()
        if (cleanGroup.isBlank()) return

        favoriteGroups =
            if (favoriteGroups.contains(cleanGroup)) {
                favoriteGroups - cleanGroup
            } else {
                favoriteGroups + cleanGroup
            }

        saveSettings()
    }

    fun loadSuggestions(query: String) {
        suggestionsJob?.cancel()

        if (query.trim().length < 2) {
            suggestions = emptyList()
            return
        }

        suggestionsJob = viewModelScope.launch {
            delay(250)

            suggestions = try {
                if (mode == "group") {
                    repository.searchGroups(query)
                } else {
                    repository.searchTeachers(query)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun clearSuggestions() {
        suggestionsJob?.cancel()
        suggestions = emptyList()
    }

    private val cacheMaxAgeMs = 14L * 24L * 60L * 60L * 1000L

    private fun currentCachePrefix(): String {
        val source = if (mode == "group") selectedGroup else selectedTeacher
        return "schedule_cache_\${mode}_\${source}"
    }

    private fun saveScheduleToCache(result: List<Lesson>) {
        val p = preferences ?: return
        if (result.isEmpty()) return

        val array = JSONArray()

        result.forEach { lesson ->
            array.put(
                JSONObject().apply {
                    put("date", lesson.date.toString())
                    put("time", lesson.time)
                    put("subject", lesson.subject)
                    put("teacher", lesson.teacher)
                    put("type", lesson.type)
                    put("room", lesson.room)
                    put("group", lesson.group)
                }
            )
        }

        val prefix = currentCachePrefix()

        p.edit()
            .putString("\${prefix}_data", array.toString())
            .putLong("\${prefix}_time", System.currentTimeMillis())
            .apply()
    }

    private fun loadScheduleFromCache(): List<Lesson>? {
        val p = preferences ?: return null
        val prefix = currentCachePrefix()
        val savedAt = p.getLong("\${prefix}_time", 0L)

        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > cacheMaxAgeMs) {
            return null
        }

        val raw = p.getString("\${prefix}_data", null) ?: return null

        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        Lesson(
                            date = LocalDate.parse(item.getString("date")),
                            time = item.optString("time"),
                            subject = item.optString("subject"),
                            teacher = item.optString("teacher"),
                            type = item.optString("type"),
                            room = item.optString("room"),
                            group = item.optString("group")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun loadSchedule() {

        if (
            mode == "group" &&
            selectedGroup.isBlank()
        ) {
            lessons = emptyList()
            return
        }

        if (
            mode == "teacher" &&
            selectedTeacher.isBlank()
        ) {
            lessons = emptyList()
            return
        }

        loading = true
        error = null
        cacheNotice = null

        viewModelScope.launch {

            try {

                val result =
                    if (mode == "group") {
                        repository.loadGroup(
                            selectedGroup
                        )
                    } else {
                        repository.loadTeacher(
                            selectedTeacher
                        )
                    }

                if (result.isNotEmpty()) {
                    saveScheduleToCache(result)
                }

                allLessons = result
                lessons = result
                    .filter { it.date == date }
                    .sortedBy { it.time }

                loading = false

                if (result.isEmpty()) {

                    error =
                        if (mode == "group") {
                            "Расписание группы не найдено."
                        } else {
                            "Расписание преподавателя не найдено."
                        }
                }

            } catch (e: Exception) {

                val cached = loadScheduleFromCache()

                loading = false

                if (cached != null) {
                    allLessons = cached
                    lessons = cached
                        .filter { it.date == date }
                        .sortedBy { it.time }

                    cacheNotice = "Показано сохранённое расписание. Оно хранится 14 дней."
                    error = null
                } else {
                    lessons = emptyList()

                    error = when {

                        e.message?.contains(
                            "Unable to resolve host",
                            true
                        ) == true ->
                            "Нет соединения с интернетом."

                        else ->
                            e.message
                                ?: "Не удалось загрузить расписание ВВГУ."
                    }
                }
            }
        }
    }

    fun changeDate(newDate: LocalDate) {
        date = newDate
        error = null
        lessons = allLessons
            .filter { it.date == newDate }
            .sortedBy { it.time }
    }

    fun selectMode(newMode: String) {

        mode = newMode
        saveSettings()

        allLessons = emptyList()
        lessons = emptyList()
        error = null
        cacheNotice = null

        val cached = loadScheduleFromCache()
        if (cached != null) {
            allLessons = cached
            lessons = cached
                .filter { it.date == date }
                .sortedBy { it.time }
            cacheNotice = "Показано сохранённое расписание. Оно хранится 14 дней."
        }

        if (
            (newMode == "group" &&
                    selectedGroup.isNotBlank()) ||
            (newMode == "teacher" &&
                    selectedTeacher.isNotBlank())
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

        "red" ->
            Color(0xFFD32F2F)

        "orange" ->
            Color(0xFFEF6C00)

        "green" ->
            Color(0xFF388E3C)

        "purple" ->
            Color(0xFF7B1FA2)

        "pink" ->
            Color(0xFFC2185B)

        "teal" ->
            Color(0xFF00796B)

        else ->
            Color(0xFF1565C0)
    }

    val scheme =
        if (darkTheme) {
            darkColorScheme(
                primary = primary
            )
        } else {
            lightColorScheme(
                primary = primary
            )
        }

    MaterialTheme(
        colorScheme = scheme,
        content = content
    )
}

@Composable
fun App(
    vm: MainViewModel = viewModel()
) {

    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.init(context)
    }

    var tab by remember {
        mutableIntStateOf(0)
    }

    var showSelector by remember {
        mutableStateOf(false)
    }

    var backPressedOnce by remember {
        mutableStateOf(false)
    }

    val snackbarHostState =
        remember {
            SnackbarHostState()
        }

    LaunchedEffect(backPressedOnce) {

        if (backPressedOnce) {

            snackbarHostState.showSnackbar(
                "Нажмите ещё раз, чтобы выйти из приложения"
            )

            kotlinx.coroutines.delay(2000)

            backPressedOnce = false
        }
    }

    BackHandler {

        when {

            showSelector -> {
                showSelector = false
            }

            tab != 0 -> {
                tab = 0
            }

            !backPressedOnce -> {
                backPressedOnce = true
            }

            else -> {
                (context as? ComponentActivity)
                    ?.moveTaskToBack(true)
            }
        }
    }

    TimetableTheme(
        darkTheme = vm.darkTheme,
        colorName = vm.themeColor
    ) {

        Scaffold(
            snackbarHost = {
                SnackbarHost(
                    snackbarHostState
                )
            },

            topBar = {

                TopAppBar(

                    title = {

                        Text(
                            when {

                                showSelector ->
                                    if (vm.mode == "group")
                                        "Выбор группы"
                                    else
                                        "Выбор преподавателя"

                                tab == 0 ->
                                    "Timetable"

                                else ->
                                    "Настройки"
                            }
                        )
                    },

                    navigationIcon = {

                        if (
                            showSelector
                        ) {

                            IconButton(
                                onClick = {

                                    showSelector = false
                                }
                            ) {

                                Icon(
                                    Icons.Default.ArrowBack,
                                    contentDescription = "Назад"
                                )
                            }
                        }
                    },

                    actions = {

                        if (
                            tab == 0 &&
                            !showSelector
                        ) {

                            IconButton(
                                onClick = {
                                    vm.loadSchedule()
                                }
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

                if (
                    !showSelector
                ) {

                    NavigationBar {

                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = {
                                tab = 0
                            },
                            icon = {
                                Icon(
                                    Icons.Default.DateRange,
                                    null
                                )
                            },
                            label = {
                                Text("Расписание")
                            }
                        )

                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = {
                                tab = 1
                            },
                            icon = {
                                Icon(
                                    Icons.Default.Settings,
                                    null
                                )
                            },
                            label = {
                                Text("Настройки")
                            }
                        )
                    }
                }
            }

        ) { padding ->

            when {

                showSelector -> {

                    SelectorScreen(
                        vm = vm,
                        modifier = Modifier.padding(padding),
                        onBack = {
                            showSelector = false
                        },                    )
                }

                tab == 0 -> {

                    ScheduleScreen(
                        vm = vm,
                        modifier = Modifier.padding(padding),
                        onShowSelector = {
                            showSelector = true
                        }
                    )
                }

                else -> {

                    SettingsScreen(
                        vm = vm,
                        modifier = Modifier.padding(padding),
                        onSelectGroup = {
                            vm.selectMode("group")
                            showSelector = true
                        },
                        onSelectTeacher = {
                            vm.selectMode("teacher")
                            showSelector = true
                        },                    )
                }
            }
        }
    }
}

@Composable
fun ScheduleScreen(
    vm: MainViewModel,
    modifier: Modifier,
    onShowSelector: () -> Unit
) {
    PullToRefreshBox(
        isRefreshing = vm.loading,
        onRefresh = {
            vm.loadSchedule()
        },
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            Modifier
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

            Spacer(
                Modifier.height(10.dp)
            )

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
                        null
                    )
                }
            }

            Spacer(
                Modifier.height(14.dp)
            )

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

            if (vm.cacheNotice != null) {
                Text(
                    vm.cacheNotice ?: "",
                    modifier = Modifier.padding(
                        top = 8.dp,
                        bottom = 4.dp
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            when {
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

                vm.loading && vm.lessons.isEmpty() -> {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
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
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        items(vm.lessons) {
                            LessonCard(it)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LessonCard(
    lesson: Lesson
) {
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

            Spacer(
                Modifier.height(5.dp)
            )

            Text(
                lesson.subject,
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(
                Modifier.height(8.dp)
            )

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
    modifier: Modifier,
    onBack: () -> Unit
) {
    var text by remember {
        mutableStateOf(
            if (vm.mode == "group")
                vm.selectedGroup
            else
                vm.selectedTeacher
        )
    }

    var expanded by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(text, vm.mode) {
        vm.loadSuggestions(text)
        expanded = text.trim().length >= 2
    }

    DisposableEffect(Unit) {
        onDispose {
            vm.clearSuggestions()
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState()
            )
            .padding(16.dp)
    ) {

        ExposedDropdownMenuBox(
            expanded = expanded && vm.suggestions.isNotEmpty(),
            onExpandedChange = {
                if (vm.suggestions.isNotEmpty()) {
                    expanded = it
                }
            }
        ) {

            OutlinedTextField(
                value = text,

                onValueChange = {
                    text = it
                    expanded = it.trim().length >= 2
                },

                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),

                label = {
                    Text(
                        if (vm.mode == "group")
                            "Название группы"
                        else
                            "ФИО преподавателя"
                    )
                },

                placeholder = {
                    Text(
                        if (vm.mode == "group")
                            "Например: БИС-24-1"
                        else
                            "Например: Иванов Иван Иванович"
                    )
                },

                singleLine = true,

                trailingIcon = {
                    if (vm.suggestions.isNotEmpty()) {
                        ExposedDropdownMenuDefaults.TrailingIcon(
                            expanded = expanded
                        )
                    }
                }
            )

            ExposedDropdownMenu(
                expanded = expanded && vm.suggestions.isNotEmpty(),
                onDismissRequest = {
                    expanded = false
                }
            ) {

                vm.suggestions.forEach { suggestion ->

                    DropdownMenuItem(
                        text = {
                            Text(suggestion)
                        },

                        trailingIcon = {
                            if (vm.mode == "group") {
                                IconButton(
                                    onClick = {
                                        vm.toggleFavoriteGroup(suggestion)
                                    }
                                ) {
                                    Icon(
                                        if (vm.isFavoriteGroup(suggestion))
                                            Icons.Default.Star
                                        else
                                            Icons.Default.StarBorder,
                                        contentDescription =
                                            if (vm.isFavoriteGroup(suggestion))
                                                "Убрать из избранного"
                                            else
                                                "Добавить в избранное"
                                    )
                                }
                            }
                        },

                        onClick = {
                            text = suggestion
                            expanded = false
                            vm.clearSuggestions()
                        }
                    )
                }
            }
        }

        Spacer(
            Modifier.height(12.dp)
        )

        Button(
            onClick = {

                if (vm.mode == "group") {
                    vm.setGroup(text)
                } else {
                    vm.setTeacher(text)
                }

                vm.clearSuggestions()
                vm.loadSchedule()

                onBack()
            },

            modifier =
                Modifier.fillMaxWidth()
        ) {

            Text("Выбрать")
        }

        if (vm.mode == "group" && vm.favoriteGroups.isNotEmpty()) {
            Spacer(
                Modifier.height(24.dp)
            )

            Text(
                "Избранные группы",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(
                Modifier.height(8.dp)
            )

            vm.favoriteGroups
                .sorted()
                .forEach { group ->
                    ListItem(
                        headlineContent = {
                            Text(group)
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = "Избранная группа"
                            )
                        },
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    vm.toggleFavoriteGroup(group)
                                }
                            ) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = "Убрать из избранного"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                text = group
                                vm.setGroup(group)
                                vm.clearSuggestions()
                                vm.loadSchedule()
                                onBack()
                            }
                    )
                }
        }

        Spacer(
            Modifier.height(24.dp)
        )

        if (vm.mode == "teacher") {

            Text(
                "Приложение ищет расписание преподавателя на официальном портале ВВГУ.",
                style =
                    MaterialTheme.typography.bodySmall
            )
        }

        Spacer(
            Modifier.height(16.dp)
        )


    }
}

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    modifier: Modifier,
    onSelectGroup: () -> Unit,
    onSelectTeacher: () -> Unit
) {
    val context = LocalContext.current
    
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState()
            )
            .padding(16.dp)
    ) {

        Text(
            "Оформление",
            style =
                MaterialTheme.typography.titleMedium,
            fontWeight =
                FontWeight.Bold
        )

        Spacer(
            Modifier.height(8.dp)
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically,
            horizontalArrangement =
                Arrangement.SpaceBetween
        ) {

            Column(
                Modifier.weight(1f)
            ) {

                Text("Тема")

                Text(
                    if (vm.darkTheme)
                        "Тёмная"
                    else
                        "Светлая",

                    style =
                        MaterialTheme.typography.bodySmall
                )
            }

            Switch(
                checked =
                    vm.darkTheme,

                onCheckedChange = {
                    vm.updateDarkTheme(it)
                }
            )
        }

        Spacer(
            Modifier.height(8.dp)
        )

        Text(
            "Цвет",
            style =
                MaterialTheme.typography.labelLarge
        )

        val colors = listOf(
            "blue" to "Синий",
            "red" to "Красный",
            "orange" to "Оранжевый",
            "green" to "Зелёный",
            "purple" to "Фиолетовый",
            "pink" to "Розовый",
            "teal" to "Бирюзовый"
        )

        var expanded by remember {
            mutableStateOf(false)
        }

        Box {

            OutlinedButton(
                onClick = {
                    expanded = true
                }
            ) {

                Text(
                    colors.firstOrNull {
                        it.first == vm.themeColor
                    }?.second ?: "Синий"
                )

                Spacer(
                    Modifier.width(8.dp)
                )

                Icon(
                    Icons.Default.ArrowDropDown,
                    null
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                }
            ) {

                colors.forEach { (value, title) ->

                    DropdownMenuItem(

                        text = {
                            Text(title)
                        },

                        onClick = {

                            vm.updateThemeColor(value)
                            expanded = false
                        }
                    )
                }
            }
        }

        HorizontalDivider(
            Modifier.padding(
                vertical = 16.dp
            )
        )

        Text(
            "Расписание",
            style =
                MaterialTheme.typography.titleMedium,
            fontWeight =
                FontWeight.Bold
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

            trailingContent = {
                Icon(
                    Icons.Default.ChevronRight,
                    null
                )
            },

            modifier = Modifier.fillMaxWidth()
        )

        Spacer(
            Modifier.height(2.dp)
        )

        Button(
            onClick = onSelectGroup,
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text("Изменить группу")
        }

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

            trailingContent = {
                Icon(
                    Icons.Default.ChevronRight,
                    null
                )
            },

            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = onSelectTeacher,
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text("Изменить преподавателя")
        }

        Spacer(
            Modifier.height(12.dp)
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Icon(
                Icons.Default.Sync,
                null
            )

            Spacer(
                Modifier.width(16.dp)
            )

            Column(
                Modifier.weight(1f)
            ) {

                Text(
                    "Автообновление",
                    fontWeight =
                        FontWeight.Medium
                )

                Text(
                    "Обновлять расписание автоматически"
                )
            }

            Switch(
                checked =
                    vm.autoRefresh,

                onCheckedChange = {
                    vm.updateAutoRefresh(it)
                }
            )
        }

        Spacer(
            Modifier.height(12.dp)
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Icon(
                Icons.Default.Notifications,
                null
            )

            Spacer(
                Modifier.width(16.dp)
            )

            Column(
                Modifier.weight(1f)
            ) {

                Text(
                    "Уведомления",
                    fontWeight =
                        FontWeight.Medium
                )

                Text(
                    "Напоминания о занятиях"
                )
            }

            Switch(
                checked =
                    vm.notificationsEnabled,

                onCheckedChange = {
                    vm.setNotifications(it)
                }
            )
        }

        Spacer(
            Modifier.height(16.dp)
        )



        Spacer(
            Modifier.height(8.dp)
        )

        Button(
            onClick = {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.vvsu.ru/")
                )
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.Language,
                null
            )

            Spacer(
                Modifier.width(8.dp)
            )

            Text("Официальный сайт ВВГУ")
        }

        Spacer(
            Modifier.height(16.dp)
        )
    }
}

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContent {
            App()
        }

        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {

        if (
            Build.VERSION.SDK_INT >= 33 &&
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
