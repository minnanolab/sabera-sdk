package jp.jig.glasses.sample.kmp.ui

/** 血糖値1点ぶん。epochSec は秒単位のUNIX時刻 */
data class GlucosePoint(val epochSec: Long, val value: Int)

/** グラスにグラフを出すために必要な血糖値データ（直近24時間） */
data class GlucoseData(
    val points: List<GlucosePoint>,
    val latest: Int,
    val latestTime: String,
    val min: Int,
    val max: Int,
    val avg: Int,
)

/**
 * 共有先（Firestore の glucose/main）から最新の血糖値データを取る。
 * 結果はメインスレッドで onResult に届く。
 */
expect fun fetchGlucoseData(onResult: (Result<GlucoseData>) -> Unit)

/** 24時間グラフをグラス用グレースケール画像（196x196）に描く */
expect fun renderGlucoseGraph(data: GlucoseData): GrayscaleImage
