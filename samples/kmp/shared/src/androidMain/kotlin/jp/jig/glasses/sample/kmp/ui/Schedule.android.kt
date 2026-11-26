package jp.jig.glasses.sample.kmp.ui

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

private const val API_KEY = "AIzaSyAhtVecwPBQLjrzxNV7peSMaHw5NYWqDXY"
private const val PROJECT = "wakayama-family-memo"

actual fun fetchScheduleData(onResult: (Result<ScheduleData>) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    thread {
        val result = try {
            Result.success(loadScheduleData())
        } catch (e: Exception) {
            Result.failure(e)
        }
        main.post { onResult(result) }
    }
}

private fun loadScheduleData(): ScheduleData {
    val token = signInAnonymouslyForSchedule()
    val url = URL(
        "https://firestore.googleapis.com/v1/projects/$PROJECT" +
            "/databases/(default)/documents/schedule/main",
    )
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("Authorization", "Bearer $token")
    }
    val body = conn.inputStream.bufferedReader().use { it.readText() }
    val fields = JSONObject(body).getJSONObject("fields")
    val todayDate = fields.getJSONObject("todayDate").getString("stringValue")
    val parts = todayDate.split("-")
    val title = "今日の予定（${parts[1].toInt()}月${parts[2].toInt()}日）"
    val values = fields.getJSONObject("today").getJSONObject("arrayValue")
        .optJSONArray("values")
    val lines = mutableListOf<String>()
    if (values != null) {
        for (i in 0 until values.length()) {
            val map = values.getJSONObject(i).getJSONObject("mapValue")
                .getJSONObject("fields")
            val time = map.getJSONObject("time").getString("stringValue")
            val name = map.getJSONObject("title").getString("stringValue")
            lines.add("$time $name")
        }
    }
    if (lines.isEmpty()) lines.add("今日の予定はありません")
    return ScheduleData(title = title, lines = lines)
}

/** 匿名ログインして ID トークンを返す。schedule/main の読み取り用 */
private fun signInAnonymouslyForSchedule(): String {
    val url = URL("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY")
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15_000
        readTimeout = 15_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
    }
    conn.outputStream.use { it.write("""{"returnSecureToken":true}""".toByteArray()) }
    val body = conn.inputStream.bufferedReader().use { it.readText() }
    return JSONObject(body).getString("idToken")
}
