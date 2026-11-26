package jp.jig.glasses.sample.kmp.ui

/** グラスにテキスト表示する株価データ（タイトル＋銘柄ごとの行） */
data class StocksData(
    val title: String,
    val lines: List<String>,
)

/**
 * 共有先（Firestore の stocks/main）から最新の株価テキストを取る。
 * 結果はメインスレッドで onResult に届く。
 */
expect fun fetchStocksData(onResult: (Result<StocksData>) -> Unit)
