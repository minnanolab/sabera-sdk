package jp.jig.glasses.sample.kmp.ui

import androidx.compose.runtime.Composable

/** グラスにテキスト表示する現在地の天気（タイトル＋行） */
data class WeatherData(
    val title: String,
    val lines: List<String>,
)

/**
 * スマホの現在地を取り、その場所の天気を返す関数を返す。
 * 位置情報の許可がなければ、呼び出し時に許可ダイアログを出す。
 * 結果はメインスレッドで onResult に届く。
 */
@Composable
expect fun rememberWeatherFetcher(): (onResult: (Result<WeatherData>) -> Unit) -> Unit
