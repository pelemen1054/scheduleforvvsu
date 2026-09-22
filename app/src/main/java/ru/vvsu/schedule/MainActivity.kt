@file:OptIn(ExperimentalMaterial3Api::class)

package ru.vvsu.schedule

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

    private var preferences: android.content.SharedPreferences? = null

    var selectedGroup by mutableStateOf("")
    var selectedTeacher by mutableStateOf("")

    var mode by mutableStateOf("group")
    var date by mutableStateOf(LocalDate.now())

    var darkTheme by mutableStateOf(false)
        private set

    var autoRefresh by mutableStateOf(true)
        private set

    var notificationsEnabled by mutableStateOf(true)
        private set

    var themeColor by mutableStateOf("blue")
        private set

    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var lessons by mutableStateOf<List<Lesson>>(emptyList())

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
            ?.apply()
    }

    fun setDarkTheme(value: Boolean) {
        darkTheme = value
        saveSettings()
    }

    fun setThemeColor(value: String) {
        themeColor = value
        saveSettings()
    }

    fun setAutoRefresh(value: Boolean) {
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

                lessons = result
                    .filter {
                        it.date == date
                    }
                    .sortedBy {
                        it.time
                    }

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

                loading = false
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

    fun changeDate(newDate: LocalDate) {
        date = newDate
        loadSchedule()
    }

    fun selectMode(newMode: String) {

        mode = newMode
        saveSettings()

        lessons = emptyList()
        error = null

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

    var showLogin by remember {
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

            showLogin -> {
                showLogin = false
            }

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

                                showLogin ->
                                    "Вход в ЛК ВВГУ"

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
                            showLogin ||
                            showSelector
                        ) {

                            IconButton(
                                onClick = {

                                    if (showLogin) {
                                        showLogin = false
                                    } else {
                                        showSelector = false
                                    }
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
                            !showSelector &&
                            !showLogin
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
                    !showSelector &&
                    !showLogin
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

                showLogin -> {

                    LoginScreen(
                        Modifier.padding(padding),
                        onLoginFinished = {
                            showLogin = false
                            vm.loadSchedule()
                        }
                    )
                }

                showSelector -> {

                    SelectorScreen(
                        vm = vm,
                        modifier = Modifier.padding(padding),
                        onBack = {
                            showSelector = false
                        },
                        onLogin = {
                            showLogin = true
                        }
                    )
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
                        },
                        onLogin = {
                            showLogin = true
                        }
                    )
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

    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {

            FilterChip(
                selected =
                    vm.mode == "group",

                onClick = {
                    vm.selectMode("group")
                },

                label = {
                    Text("👥 Группа")
                }
            )

            FilterChip(
                selected =
                    vm.mode == "teacher",

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
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Column(
                    Modifier.weight(1f)
                ) {

                    Text(
                        if (vm.mode == "group")
                            "Группа"
                        else
                            "Преподаватель",
                        style =
                            MaterialTheme.typography.labelMedium
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

                        style =
                            MaterialTheme.typography.titleMedium,

                        fontWeight =
                            FontWeight.SemiBold
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
            verticalAlignment =
                Alignment.CenterVertically,
            horizontalArrangement =
                Arrangement.SpaceBetween
        ) {

            IconButton(
                onClick = {
                    vm.changeDate(
                        vm.date.minusDays(1)
                    )
                }
            ) {

                Icon(
                    Icons.Default.ChevronLeft,
                    "Предыдущий день"
                )
            }

            TextButton(
                onClick = {
                    vm.changeDate(
                        LocalDate.now()
                    )
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
                    vm.changeDate(
                        vm.date.plusDays(1)
                    )
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
                    contentAlignment =
                        Alignment.Center
                ) {

                    CircularProgressIndicator()
                }
            }

            vm.error != null -> {

                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment =
                        Alignment.Center
                ) {

                    Text(
                        vm.error ?: "",
                        modifier =
                            Modifier.padding(24.dp)
                    )
                }
            }

            vm.lessons.isEmpty() -> {

                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment =
                        Alignment.Center
                ) {

                    Text(
                        if (
                            (
                                vm.mode == "group" &&
                                    vm.selectedGroup.isBlank()
                            ) ||
                            (
                                vm.mode == "teacher" &&
                                    vm.selectedTeacher.isBlank()
                            )
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
                        PaddingValues(
                            vertical = 12.dp
                        )
                ) {

                    items(vm.lessons) {
                        LessonCard(it)
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
                style =
                    MaterialTheme.typography.titleMedium,
                fontWeight =
                    FontWeight.Bold
            )

            Spacer(
                Modifier.height(5.dp)
            )

            Text(
                lesson.subject,
                style =
                    MaterialTheme.typography.titleLarge
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
    onBack: () -> Unit,
    onLogin: () -> Unit
) {
    val context = LocalContext.current
    
    var text by remember {

        mutableStateOf(
            if (vm.mode == "group")
                vm.selectedGroup
            else
                vm.selectedTeacher
        )
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState()
            )
            .padding(16.dp)
    ) {

        OutlinedTextField(
            value = text,

            onValueChange = {
                text = it
            },

            modifier =
                Modifier.fillMaxWidth(),

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

            singleLine = true
        )

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

                vm.loadSchedule()

                onBack()
            },

            modifier =
                Modifier.fillMaxWidth()
        ) {

            Text("Выбрать")
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

        Button(
            onClick = onLogin,
            modifier =
                Modifier.fillMaxWidth()
        ) {

            Icon(
                Icons.Default.Login,
                null
            )

            Spacer(
                Modifier.width(8.dp)
            )

            Text("Войти через ЛК ВВГУ")
        }
    }
}

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    modifier: Modifier,
    onSelectGroup: () -> Unit,
    onSelectTeacher: () -> Unit,
    onLogin: () -> Unit
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
                    vm.setDarkTheme(it)
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

                            vm.setThemeColor(value)
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
                    vm.setAutoRefresh(it)
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

        Button(
            onClick = onLogin,
            modifier =
                Modifier.fillMaxWidth()
        ) {

            Icon(
                Icons.Default.Login,
                null
            )

            Spacer(
                Modifier.width(8.dp)
            )

            Text("Войти в ЛК ВВГУ")
        }

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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    modifier: Modifier,
    onLoginFinished: () -> Unit
) {

    val context = LocalContext.current

    AndroidView(

        modifier = modifier.fillMaxSize(),

        factory = {

            WebView(context).apply {

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.loadsImagesAutomatically = true

                CookieManager
                    .getInstance()
                    .setAcceptCookie(true)

                CookieManager
                    .getInstance()
                    .setAcceptThirdPartyCookies(
                        this,
                        true
                    )

                webViewClient =
                    object : WebViewClient() {

                        override fun onPageFinished(
                            view: WebView?,
                            url: String?
                        ) {

                            super.onPageFinished(
                                view,
                                url
                            )

                            val currentUrl =
                                url ?: ""

                            if (
                                currentUrl.contains(
                                    "cabinet.vvsu.ru"
                                )
                            ) {

                                onLoginFinished()
                            }
                        }
                    }

                loadUrl(
                    "https://fort.vvsu.ru/openid/"
                )
            }
        }
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
