package jp.jig.glasses.sample.kmp.ui

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

private const val API_KEY = "AIzaSyAhtVecwPBQLjrzxNV7peSMaHw5NYWqDXY"
private const val PROJECT = "wakayama-family-memo"

actual fun fetchStocksData(onResult: (Result<StocksData>) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    thread {
        val result = try {
            Result.success(loadStocksData())
        } catch (e: Exception) {
            Result.failure(e)
        }
        main.post { onResult(result) }
    }
}

private fun loadStocksData(): StocksData {
    val token = signInAnonymouslyForStocks()
    val url = URL(
        "https://firestore.googleapis.com/v1/projects/$PROJECT" +
            "/databases/(default)/documents/stocks/main",
    )
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("Authorization", "Bearer $token")
    }
    val body = conn.inputStream.bufferedReader().use { it.readText() }
    val fields = JSONObject(body).getJSONObject("fields")
    val values = fields.getJSONObject("lines").getJSONObject("arrayValue")
        .getJSONArray("values")
    val lines = (0 until values.length()).map { i ->
        values.getJSONObject(i).getString("stringValue")
    }
    check(lines.isNotEmpty()) { "株価データがありません" }
    return StocksData(
        title = fields.getJSONObject("title").getString("stringValue"),
        lines = lines,
    )
}

/** 匿名ログインして ID トークンを返す。stocks/main の読み取り用 */
private fun signInAnonymouslyForStocks(): String {
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
