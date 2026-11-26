package jp.jig.glasses.sample.kmp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android 標準の音声認識（スマホのマイク）で聞き取り続ける。
 * 1回聞き取るたびに認識は終わるので、終わったら自動で聞き直す。
 */
@Composable
actual fun rememberVoiceCommands(
    onText: (String) -> Unit,
    onStatus: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val currentOnText by rememberUpdatedState(onText)
    val currentOnStatus by rememberUpdatedState(onStatus)

    val engine = remember(context) {
        VoiceCommandEngine(
            context = context.applicationContext,
            onText = { currentOnText(it) },
            onStatus = { currentOnStatus(it) },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            engine.start()
        } else {
            currentOnStatus("マイクの許可がないため、音声操作は使えません")
        }
    }

    DisposableEffect(engine) {
        onDispose { engine.destroy() }
    }

    return remember(engine, permissionLauncher) {
        {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                engine.start()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

private class VoiceCommandEngine(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) : RecognitionListener {

    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private val handler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable { if (active) startListeningInternal() }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus("このスマホでは音声認識が使えません")
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                it.setRecognitionListener(this)
            }
        }
        active = true
        onStatus("聞き取り中です。「上」「下」「決定」と言ってください")
        startListeningInternal()
    }

    fun destroy() {
        active = false
        handler.removeCallbacks(restartRunnable)
        recognizer?.destroy()
        recognizer = null
    }

    private fun startListeningInternal() {
        val target = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            target.startListening(intent)
        } catch (e: Exception) {
            scheduleRestart(600)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        handler.removeCallbacks(restartRunnable)
        if (active) handler.postDelayed(restartRunnable, delayMs)
    }

    override fun onResults(results: Bundle?) {
        val texts = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
        val text = texts.firstOrNull().orEmpty()
        if (text.isNotBlank()) onText(text)
        scheduleRestart(200)
    }

    override fun onError(error: Int) {
        // 聞き取れなかったときも、少し待ってから聞き直す
        scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 800 else 350)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
