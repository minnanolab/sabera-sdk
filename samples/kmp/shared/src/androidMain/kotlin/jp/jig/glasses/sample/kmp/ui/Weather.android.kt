package jp.jig.glasses.sample.kmp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

@Composable
actual fun rememberWeatherFetcher(): (onResult: (Result<WeatherData>) -> Unit) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    var pending by remember { mutableStateOf<((Result<WeatherData>) -> Unit)?>(null) }
    val currentPending by rememberUpdatedState(pending)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val callback = currentPending
        pending = null
        if (callback != null) {
            if (granted) {
                loadWeather(appContext, callback)
            } else {
                callback(Result.failure(Exception("位置情報の許可がありません")))
            }
        }
    }

    return remember(appContext, permissionLauncher) {
        { onResult: (Result<WeatherData>) -> Unit ->
            if (ContextCompat.checkSelfPermission(
                    appContext, Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                loadWeather(appContext, onResult)
            } else {
                pending = onResult
                permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
    }
}

private fun loadWeather(context: Context, onResult: (Result<WeatherData>) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    thread {
        val result = try {
            val location = obtainLocation(context)
                ?: throw Exception("現在地が分かりませんでした")
            Result.success(buildWeather(context, location))
        } catch (e: Exception) {
            Result.failure(e)
        }
        main.post { onResult(result) }
    }
}

/** 最後に分かっている位置を取り、なければ一度だけ取り直す（最大10秒） */
private fun obtainLocation(context: Context): Location? {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
    var best: Location? = null
    for (provider in providers) {
        try {
            val loc = manager.getLastKnownLocation(provider)
            if (loc != null && (best == null || loc.time > best.time)) best = loc
        } catch (e: SecurityException) {
            // 許可がない場合は下で失敗として扱う
        } catch (e: Exception) {
            // そのプロバイダが使えない場合は次へ
        }
    }
    // 30分以内の位置なら十分新しい
    if (best != null && System.currentTimeMillis() - best.time < 30 * 60 * 1000) return best

    val latch = java.util.concurrent.CountDownLatch(1)
    var fresh: Location? = null
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            fresh = location
            latch.countDown()
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    try {
        Handler(Looper.getMainLooper()).post {
            try {
                manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
            } catch (e: Exception) {
                latch.countDown()
            }
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
    } catch (e: Exception) {
        // タイムアウト時は最後の位置を使う
    }
    return fresh ?: best
}

private fun buildWeather(context: Context, location: Location): WeatherData {
    val place = placeName(context, location)
    val url = URL(
        "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}" +
            "&longitude=${location.longitude}" +
            "&current=temperature_2m,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=Asia%2FTokyo&forecast_days=1",
    )
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 15_000
    }
    val body = conn.inputStream.bufferedReader().use { it.readText() }
    val root = JSONObject(body)
    val current = root.getJSONObject("current")
    val daily = root.getJSONObject("daily")
    val nowText = weatherLabel(current.getInt("weather_code"))
    val nowTemp = current.getDouble("temperature_2m")
    val maxTemp = daily.getJSONArray("temperature_2m_max").getDouble(0)
    val minTemp = daily.getJSONArray("temperature_2m_min").getDouble(0)
    val rain = daily.getJSONArray("precipitation_probability_max").optInt(0, -1)
    val lines = mutableListOf(
        "現在 $nowText ${fmtTemp(nowTemp)}",
        "今日 最高${fmtTemp(maxTemp)} 最低${fmtTemp(minTemp)}",
    )
    if (rain >= 0) lines.add("降水確率 $rain%")
    return WeatherData(title = "天気（$place）", lines = lines)
}

private fun fmtTemp(v: Double): String =
    if (v == v.toLong().toDouble()) "${v.toLong()}℃" else "${v}℃"

/** 座標から地名（市区町村）を取る。取れなければ「現在地」 */
private fun placeName(context: Context, location: Location): String {
    return try {
        val geocoder = Geocoder(context, Locale.JAPAN)
        @Suppress("DEPRECATION")
        val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
        val address = addresses?.firstOrNull()
        val city = address?.locality.orEmpty()
        val ward = address?.subLocality.orEmpty()
        when {
            city.isNotEmpty() && ward.isNotEmpty() && ward != city -> "$city$ward"
            city.isNotEmpty() -> city
            else -> "現在地"
        }
    } catch (e: Exception) {
        "現在地"
    }
}

/** Open-Meteo（WMO）の天気コードを日本語にする */
private fun weatherLabel(code: Int): String = when (code) {
    0 -> "快晴"
    1 -> "晴れ"
    2 -> "晴れ時々曇り"
    3 -> "曇り"
    45, 48 -> "霧"
    51, 53, 55 -> "霧雨"
    56, 57 -> "霧雨（着氷）"
    61 -> "小雨"
    63 -> "雨"
    65 -> "大雨"
    66, 67 -> "雨（着氷）"
    71, 73, 75, 77 -> "雪"
    80 -> "にわか雨"
    81 -> "にわか雨"
    82 -> "激しいにわか雨"
    85, 86 -> "にわか雪"
    95 -> "雷雨"
    96, 99 -> "雷雨（ひょう）"
    else -> "不明"
}
