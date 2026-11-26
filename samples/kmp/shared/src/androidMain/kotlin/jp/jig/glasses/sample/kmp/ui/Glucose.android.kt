package jp.jig.glasses.sample.kmp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

private const val API_KEY = "AIzaSyAhtVecwPBQLjrzxNV7peSMaHw5NYWqDXY"
private const val PROJECT = "wakayama-family-memo"

/** グラフの縦軸の範囲（mg/dL）。Libre の表示に合わせて固定 */
private const val GRAPH_MIN = 40
private const val GRAPH_MAX = 300

/** 目標範囲（mg/dL）。この帯を薄く塗る */
private const val TARGET_LOW = 70
private const val TARGET_HIGH = 180

actual fun fetchGlucoseData(onResult: (Result<GlucoseData>) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    thread {
        val result = try {
            Result.success(loadGlucoseData())
        } catch (e: Exception) {
            Result.failure(e)
        }
        main.post { onResult(result) }
    }
}

private fun loadGlucoseData(): GlucoseData {
    val token = signInAnonymously()
    val url = URL(
        "https://firestore.googleapis.com/v1/projects/$PROJECT" +
            "/databases/(default)/documents/glucose/main",
    )
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("Authorization", "Bearer $token")
    }
    val body = conn.inputStream.bufferedReader().use { it.readText() }
    val fields = JSONObject(body).getJSONObject("fields")

    val tsValues = fields.getJSONObject("ts").getJSONObject("arrayValue")
        .getJSONArray("values")
    val vsValues = fields.getJSONObject("vs").getJSONObject("arrayValue")
        .getJSONArray("values")
    val points = (0 until tsValues.length()).map { i ->
        GlucosePoint(
            epochSec = tsValues.getJSONObject(i).getString("integerValue").toLong(),
            value = vsValues.getJSONObject(i).getString("integerValue").toInt(),
        )
    }
    check(points.isNotEmpty()) { "血糖値データがありません" }
    return GlucoseData(
        points = points,
        latest = fields.getJSONObject("latest").getString("integerValue").toInt(),
        latestTime = fields.getJSONObject("latestTime").getString("stringValue"),
        min = fields.getJSONObject("min").getString("integerValue").toInt(),
        max = fields.getJSONObject("max").getString("integerValue").toInt(),
        avg = fields.getJSONObject("avg").getString("integerValue").toInt(),
    )
}

/** 匿名ログインして ID トークンを返す。glucose/main の読み取り用 */
private fun signInAnonymously(): String {
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

actual fun renderGlucoseGraph(data: GlucoseData): GrayscaleImage {
    val size = GLASS_IMAGE_MAX_SIZE
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val left = 6f
        val right = size - 6f
        val top = 6f
        val bottom = size - 6f

        fun yOf(value: Int): Float {
            val clamped = value.coerceIn(GRAPH_MIN, GRAPH_MAX)
            val ratio = (clamped - GRAPH_MIN).toFloat() / (GRAPH_MAX - GRAPH_MIN)
            return bottom - ratio * (bottom - top)
        }

        // 目標範囲の帯
        val band = Paint().apply { color = Color.rgb(200, 200, 200) }
        canvas.drawRect(left, yOf(TARGET_HIGH), right, yOf(TARGET_LOW), band)

        // 枠
        val border = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRect(left, top, right, bottom, border)

        // 折れ線
        val points = data.points
        if (points.size >= 2) {
            val first = points.first().epochSec
            val last = points.last().epochSec
            val span = (last - first).coerceAtLeast(1)
            val line = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 3f
                isAntiAlias = false
            }
            var prevX = 0f
            var prevY = 0f
            points.forEachIndexed { index, point ->
                val x = left + (point.epochSec - first).toFloat() / span * (right - left)
                val y = yOf(point.value)
                if (index > 0) canvas.drawLine(prevX, prevY, x, y, line)
                prevX = x
                prevY = y
            }
            // 最新の点に丸印
            val dot = Paint().apply { color = Color.BLACK }
            canvas.drawCircle(prevX, prevY, 5f, dot)
        }

        val colors = IntArray(size * size)
        bitmap.getPixels(colors, 0, size, 0, 0, size, size)
        val pixels = ByteArray(colors.size) { index ->
            val color = colors[index]
            val luminance = 0.299 * Color.red(color) +
                0.587 * Color.green(color) + 0.114 * Color.blue(color)
            luminance.toInt().coerceIn(0, 255).toByte()
        }
        return GrayscaleImage(size, size, pixels)
    } finally {
        bitmap.recycle()
    }
}
