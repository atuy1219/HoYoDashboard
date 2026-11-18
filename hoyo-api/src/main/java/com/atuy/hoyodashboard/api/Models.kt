package com.atuy.hoyodashboard.api

enum class Game(val label: String, val resource: String) {
    GENSHIN("原神", "天然樹脂"),
    STAR_RAIL("崩壊：スターレイル", "開拓力"),
    ZZZ("ゼンレスゾーンゼロ", "バッテリー")
}

data class Role(
    val game: Game,
    val uid: String,
    val nickname: String,
    val server: String
)

data class Progress(val current: Int, val maximum: Int) {
    val fraction: Float get() = if (maximum > 0) (current.toFloat() / maximum).coerceIn(0f, 1f) else 0f
}

data class Detail(val name: String, val value: String)

data class DailyNote(
    val resource: Progress,
    val secondsUntilFull: Long?,
    val daily: Progress?,
    val dailyCompleted: Boolean?,
    val details: List<Detail> = emptyList(),
    val rawFields: List<Detail> = emptyList()
)

data class DashboardItem(val role: Role, val note: DailyNote?, val error: String?)

class HoYoApiException(message: String) : Exception(message)
