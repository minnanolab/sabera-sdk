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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.jigglass.glass.GestureType
import app.jigglass.glass.GlassClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "VoiceMenuScreen"

/** メニュー項目。今は 1・2・3 の3つだけ */
private val MENU_ITEMS = listOf("1", "2", "3")

/** 決定の表示を出してからメニューに戻るまでの時間 */
private const val DECIDED_SHOW_MS = 2500L

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

    fun move(delta: Int) {
        decided = null
        selected = (selected + delta + MENU_ITEMS.size) % MENU_ITEMS.size
        sendToGlasses(menuText())
    }

    fun decide() {
        val item = MENU_ITEMS[selected]
        decided = item
        sendToGlasses("「$item」を選びました")
    }

    fun handleHeard(text: String) {
        lastHeard = text
        when {
            text.contains("決定") || text.contains("けってい") ||
                text.contains("決めて") || text.contains("きめて") -> decide()

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
                if (gesture == GestureType.SINGLE_TAP) decide()
            }
        }
        onDispose { job.cancel() }
    }

    // 決定の表示をしばらく出したらメニューに戻す
    LaunchedEffect(decided) {
        if (decided != null) {
            delay(DECIDED_SHOW_MS)
            decided = null
            sendToGlasses(menuText())
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
                onClick = { decide() },
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
