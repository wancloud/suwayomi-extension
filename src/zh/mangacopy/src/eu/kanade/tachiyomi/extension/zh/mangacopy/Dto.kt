package eu.kanade.tachiyomi.extension.zh.mangacopy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ApiResponse<T>(
    val code: Int = 0,
    val message: String = "",
    val results: T? = null,
)

@Serializable
class ComicListDto(
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    val list: List<ComicDto> = emptyList(),
)

@Serializable
class ComicDto(
    val name: String,
    @SerialName("path_word") val pathWord: String,
    val cover: String = "",
    val author: List<NamedDto> = emptyList(),
)

@Serializable
class NamedDto(
    val name: String,
)

/**
 * Decrypted payload of `/comicdetail/<pathWord>/chapters`.
 */
@Serializable
class ChapterListDto(
    val groups: Map<String, GroupDto> = emptyMap(),
)

@Serializable
class GroupDto(
    val name: String = "",
    val count: Int = 0,
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
class ChapterDto(
    val id: String,
    val name: String,
    val type: Int = 1,
)

/**
 * Decrypted payload of the `contentKey` variable on a chapter page.
 */
@Serializable
class PageDto(
    val url: String,
)
