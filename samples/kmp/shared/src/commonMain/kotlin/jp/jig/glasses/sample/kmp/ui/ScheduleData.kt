package jp.jig.glasses.sample.kmp.ui

/** グラスにテキスト表示する今日の予定（タイトル＋予定ごとの行） */
data class ScheduleData(
    val title: String,
    val lines: List<String>,
)

/**
 * 共有先（Firestore の schedule/main）から今日の予定を取る。
 * 結果はメインスレッドで onResult に届く。
 */
expect fun fetchScheduleData(onResult: (Result<ScheduleData>) -> Unit)
