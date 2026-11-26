package jp.jig.glasses.sample.kmp.ui

/** グラスにテキスト表示する家族メモ（タイトル＋メモごとの行） */
data class MemosData(
    val title: String,
    val lines: List<String>,
)

/**
 * 共有先（Firestore の glassesMemos/main）から家族メモを取る。
 * 結果はメインスレッドで onResult に届く。
 */
expect fun fetchMemosData(onResult: (Result<MemosData>) -> Unit)
