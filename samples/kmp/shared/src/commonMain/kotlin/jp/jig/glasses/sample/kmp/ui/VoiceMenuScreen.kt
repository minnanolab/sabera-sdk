package jp.jig.glasses.sample.kmp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.jigglass.glass.CommandManager
import app.jigglass.glass.GestureType
import app.jigglass.glass.GlassClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "VoiceMenuScreen"

/** メニュー項目。1=血糖値グラフ、2=株価、3=今日の予定、4=現在地の天気、5=家族メモ */
private val MENU_ITEMS = listOf("1.血糖値", "2.株価", "3.今日の予定", "4.天気", "5.家族メモ")

/**
 * スマホ側の音声認識を始める。聞き取った全文が onText に届く。
 * 開始用の関数を返す。画面を離れると自動で止まる。
 */
@Composable
expect fun rememberVoiceCommands(
    onText: (String) -> Unit,
    onStatus: (String) -> Unit,
): () -> Unit

/**
 * 音声で操作するメニュー。
 * グラスには空画面ページにメニューを出し、選んでいる項目に ▶ を付ける。
 * 「上」「下」で移動、「決定」で確定。グラスのシングルタップでも決定できる。
 * スマホのボタンでも同じ操作ができる（音声が使えないときの予備）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceMenuScreen(client: GlassClient, onBack: () -> Unit) {
    val commandManager = remember(client) { client.createCommandManager() }
    val scope = rememberCoroutineScope()

    var selected by remember { mutableStateOf(0) }
    var decided by remember { mutableStateOf<String?>(null) }
    var lastHeard by remember { mutableStateOf("") }
    var voiceStatus by remember { mutableStateOf("音声はまだ開始していません") }
    var glucoseNote by remember { mutableStateOf("") }
    var fetchingGlucose by remember { mutableStateOf(false) }
    var showingGlucose by remember { mutableStateOf(false) }
    var stocksNote by remember { mutableStateOf("") }
    var fetchingStocks by remember { mutableStateOf(false) }
    var showingStocks by remember { mutableStateOf(false) }
    var scheduleNote by remember { mutableStateOf("") }
    var fetchingSchedule by remember { mutableStateOf(false) }
    var showingSchedule by remember { mutableStateOf(false) }
    var weatherNote by remember { mutableStateOf("") }
    var fetchingWeather by remember { mutableStateOf(false) }
    var showingWeather by remember { mutableStateOf(false) }
    var memosNote by remember { mutableStateOf("") }
    var fetchingMemos by remember { mutableStateOf(false) }
    var showingMemos by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun menuText(): String = buildString {
        append("メニュー")
        MENU_ITEMS.forEachIndexed { index, item ->
            append("\n")
            append(if (index == selected) "▶ " else "　 ")
            append(item)
        }
    }

    fun sendToGlasses(text: String) {
        try {
            commandManager.sendEmptyScreenContent(text)
        } catch (e: Throwable) {
            SampleLog.e(TAG, "sendEmptyScreenContent FAILED", e)
            error = e.message
        }
    }

    /**
     * タイトル＋本文行のパネルをグラスに出す。
     * キャンバスのテキストは1回の送信で合計190バイト程度までしか送れない
     * （日本語は1文字3バイト）ため、タイトルと各行を1要素ずつ分けて送る。
     * 要素は id 0..7 の8個までなので、本文は最大7行。
     */
    fun displayPanel(title: String, lines: List<String>, onShown: () -> Unit) {
        try {
            commandManager.sendCanvas(
                listOf(
                    CommandManager.CanvasElement(
                        id = 0, x = 16, y = 8, width = 560, height = 44,
                        text = title,
                    ),
                ),
            )
            lines.take(7).forEachIndexed { index, line ->
                commandManager.sendCanvasElements(
                    listOf(
                        CommandManager.CanvasElement(
                            id = index + 1, x = 16, y = 60 + index * 40,
                            width = 560, height = 38,
                            text = line,
                        ),
                    ),
                )
            }
            onShown()
        } catch (e: Throwable) {
            SampleLog.e(TAG, "displayPanel FAILED", e)
            error = e.message
        }
    }

    fun move(delta: Int) {
        decided = null
        if (showingGlucose || showingStocks || showingSchedule ||
            showingWeather || showingMemos
        ) {
            // パネル表示中なら、まずメニュー表示に戻す
            showingGlucose = false
            showingStocks = false
            showingSchedule = false
            showingWeather = false
            showingMemos = false
            try {
                commandManager.closeCanvas()
                commandManager.enterEmptyScreenPage()
            } catch (e: Throwable) {
                SampleLog.e(TAG, "backToMenu FAILED", e)
            }
        }
        selected = (selected + delta + MENU_ITEMS.size) % MENU_ITEMS.size
        sendToGlasses(menuText())
    }

    fun showGlucose() {
        if (fetchingGlucose) return
        fetchingGlucose = true
        glucoseNote = "血糖値を読み込んでいます…"
        fetchGlucoseData { result ->
            fetchingGlucose = false
            result.onSuccess { data ->
                glucoseNote = "最新 ${data.latest}（${data.latestTime}）" +
                    " 最高 ${data.max} / 最低 ${data.min} / 平均 ${data.avg}"
                try {
                    val image = renderGlucoseGraph(data)
                    commandManager.sendCanvas(
                        listOf(
                            CommandManager.CanvasElement(
                                id = 0, x = 16, y = 8, width = 560, height = 44,
                                text = "血糖値（24時間）",
                            ),
                            CommandManager.CanvasElement(
                                id = 1, x = 232, y = 60, width = 340, height = 84,
                                text = "最新 ${data.latest}",
                            ),
                            CommandManager.CanvasElement(
                                id = 2, x = 232, y = 152, width = 340, height = 110,
                                text = "最高 ${data.max}\n最低 ${data.min}\n平均 ${data.avg}",
                            ),
                            CommandManager.CanvasElement(
                                id = 3, x = 16, y = 272, width = 560, height = 40,
                                text = "最新は ${data.latestTime} の値です",
                            ),
                        ),
                    )
                    commandManager.sendCanvasImage(
                        id = 0, x = 16, y = 60,
                        width = image.width, height = image.height,
                        grayscale = image.pixels,
                    )
                    showingGlucose = true
                } catch (e: Throwable) {
                    SampleLog.e(TAG, "showGlucose FAILED", e)
                    error = e.message
                }
            }.onFailure { e ->
                SampleLog.e(TAG, "fetchGlucoseData FAILED", e)
                glucoseNote = "血糖値を読み込めませんでした"
                sendToGlasses("血糖値を\n読み込めませんでした")
            }
        }
    }

    fun showStocks() {
        if (fetchingStocks) return
        fetchingStocks = true
        stocksNote = "株価を読み込んでいます…"
        fetchStocksData { result ->
            fetchingStocks = false
            result.onSuccess { data ->
                stocksNote = data.title
                displayPanel(data.title, data.lines) { showingStocks = true }
            }.onFailure { e ->
                SampleLog.e(TAG, "fetchStocksData FAILED", e)
                stocksNote = "株価を読み込めませんでした"
                sendToGlasses("株価を\n読み込めませんでした")
            }
        }
    }

    fun showSchedule() {
        if (fetchingSchedule) return
        fetchingSchedule = true
        scheduleNote = "予定を読み込んでいます…"
        fetchScheduleData { result ->
            fetchingSchedule = false
            result.onSuccess { data ->
                scheduleNote = data.title
                displayPanel(data.title, data.lines) { showingSchedule = true }
            }.onFailure { e ->
                SampleLog.e(TAG, "fetchScheduleData FAILED", e)
                scheduleNote = "予定を読み込めませんでした"
                sendToGlasses("予定を\n読み込めませんでした")
            }
        }
    }

    fun showWeather(fetch: (onResult: (Result<WeatherData>) -> Unit) -> Unit) {
        if (fetchingWeather) return
        fetchingWeather = true
        weatherNote = "天気を読み込んでいます…"
        fetch { result ->
            fetchingWeather = false
            result.onSuccess { data ->
                weatherNote = data.title
                displayPanel(data.title, data.lines) { showingWeather = true }
            }.onFailure { e ->
                SampleLog.e(TAG, "fetchWeather FAILED", e)
                weatherNote = "天気を読み込めませんでした（${e.message ?: "不明なエラー"}）"
                sendToGlasses("天気を\n読み込めませんでした")
            }
        }
    }

    fun showMemos() {
        if (fetchingMemos) return
        fetchingMemos = true
        memosNote = "メモを読み込んでいます…"
        fetchMemosData { result ->
            fetchingMemos = false
            result.onSuccess { data ->
                memosNote = data.title
                displayPanel(data.title, data.lines) { showingMemos = true }
            }.onFailure { e ->
                SampleLog.e(TAG, "fetchMemosData FAILED", e)
                memosNote = "メモを読み込めませんでした"
                sendToGlasses("メモを\n読み込めませんでした")
            }
        }
    }

    fun decide(fetchWeatherFn: (onResult: (Result<WeatherData>) -> Unit) -> Unit) {
        decided = MENU_ITEMS[selected]
        when (selected) {
            0 -> showGlucose()
            1 -> showStocks()
            2 -> showSchedule()
            3 -> showWeather(fetchWeatherFn)
            4 -> showMemos()
        }
    }

    val fetchWeather = rememberWeatherFetcher()

    fun handleHeard(text: String) {
        lastHeard = text
        when {
            text.contains("決定") || text.contains("けってい") ||
                text.contains("決めて") || text.contains("きめて") -> decide(fetchWeather)

            text.contains("上") || text.contains("うえ") -> move(-1)
            text.contains("下") || text.contains("した") -> move(1)
        }
    }

    val startVoice = rememberVoiceCommands(
        onText = { handleHeard(it) },
        onStatus = { voiceStatus = it },
    )

    DisposableEffect(commandManager) {
        try {
            commandManager.enterEmptyScreenPage()
            commandManager.sendEmptyScreenContent(menuText())
        } catch (e: Throwable) {
            SampleLog.e(TAG, "enterEmptyScreenPage FAILED", e)
            error = e.message
        }
        val job: Job = scope.launch {
            commandManager.gestureEvents.collect { gesture ->
                if (gesture == GestureType.SINGLE_TAP) decide(fetchWeather)
            }
        }
        onDispose {
            job.cancel()
            try {
                commandManager.closeCanvas()
            } catch (e: Throwable) {
                SampleLog.e(TAG, "closeCanvas FAILED", e)
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("音声メニュー") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "「上」「下」「決定」と声で操作するメニュー。グラスにも同じメニューが出る。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))

            Text("いま選んでいるもの", style = MaterialTheme.typography.titleMedium)
            Text(
                decided?.let { "「$it」を選びました" } ?: MENU_ITEMS[selected],
                style = MaterialTheme.typography.headlineLarge,
            )

            Spacer(Modifier.height(16.dp))
            Text(voiceStatus, style = MaterialTheme.typography.bodyMedium)
            if (lastHeard.isNotBlank()) {
                Text("聞き取った言葉: $lastHeard", style = MaterialTheme.typography.bodyMedium)
            }
            if (glucoseNote.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(glucoseNote, style = MaterialTheme.typography.bodyLarge)
            }
            if (stocksNote.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(stocksNote, style = MaterialTheme.typography.bodyLarge)
            }
            if (scheduleNote.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(scheduleNote, style = MaterialTheme.typography.bodyLarge)
            }
            if (weatherNote.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(weatherNote, style = MaterialTheme.typography.bodyLarge)
            }
            if (memosNote.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(memosNote, style = MaterialTheme.typography.bodyLarge)
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { startVoice() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("音声を開始")
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { move(-1) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("上")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { move(1) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("下")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { decide(fetchWeather) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("決定")
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("戻る")
            }

            error?.let { message ->
                Spacer(Modifier.height(16.dp))
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { error = null }) {
                    Text("エラーを消す")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
