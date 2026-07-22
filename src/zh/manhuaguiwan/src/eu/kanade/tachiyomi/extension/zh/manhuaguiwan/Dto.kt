package eu.kanade.tachiyomi.extension.zh.manhuaguiwan

import kotlinx.serialization.Serializable

@Serializable
class Sl(
    val e: Int? = 0,
    val m: String? = "",
)

@Serializable
class Comic(
    val files: List<String>? = emptyList(),
    val path: String? = "",
    val sl: Sl? = Sl(),
)
