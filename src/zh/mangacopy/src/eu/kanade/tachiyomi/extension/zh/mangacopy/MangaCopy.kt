package eu.kanade.tachiyomi.extension.zh.mangacopy

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.decodeHex
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Source
abstract class MangaCopy : HttpSource() {

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2)
        .addInterceptor { chain ->
            val request = chain.request()
            val response = try {
                chain.proceed(request)
            } catch (e: Exception) {
                log("REQUEST FAILED ${request.method} ${request.url} -> ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
            log("HTTP ${response.code} ${request.method} ${request.url}")
            response
        }
        .build()

    /** Traced to stdout so the lines show up in the Suwayomi server log. */
    private fun log(message: String) = println("[MangaCopy] $message")

    /**
     * Browsing and search go through the app API; it stays available when the
     * website endpoints are picky, but it requires the mobile app's headers.
     */
    private val apiUrl = "https://api.mangacopy.com"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    private fun apiHeaders(): Headers = Headers.Builder()
        .set("User-Agent", "Dart/2.16 (dart:io)")
        .set("source", "copyApp")
        .set("webp", "1")
        .set("version", APP_VERSION)
        .set("region", "1")
        .set("platform", "3")
        .set("referer", "com.copymanga.app-$APP_VERSION")
        .set("accept", "application/json")
        .build()

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = comicListRequest(page, "-popular")

    override fun popularMangaParse(response: Response): MangasPage = comicListParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = comicListRequest(page, "-datetime_updated")

    override fun latestUpdatesParse(response: Response): MangasPage = comicListParse(response)

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) return comicListRequest(page, "-popular")

        val url = "$apiUrl/api/v3/search/comic".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("platform", "3")
            .build()
        return GET(url, apiHeaders())
    }

    override fun searchMangaParse(response: Response): MangasPage = comicListParse(response)

    private fun comicListRequest(page: Int, ordering: String): Request {
        val url = "$apiUrl/api/v3/comics".toHttpUrl().newBuilder()
            .addQueryParameter("ordering", ordering)
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .addQueryParameter("platform", "3")
            .build()
        return GET(url, apiHeaders())
    }

    private fun comicListParse(response: Response): MangasPage {
        val results = response.parseAs<ApiResponse<ComicListDto>>().results
            ?: throw Exception(BLOCKED_MESSAGE)

        val entries = results.list.map { comic ->
            SManga.create().apply {
                url = "/comic/${comic.pathWord}"
                title = comic.name
                thumbnail_url = comic.cover
                author = comic.author.joinToString { it.name }
            }
        }
        // Thumbnails are fetched by the app itself, so log them here to make a
        // failing cover URL visible alongside the requests this client makes.
        log("list: ${entries.size} entries, first url='${entries.firstOrNull()?.url}' cover='${entries.firstOrNull()?.thumbnail_url}'")
        return MangasPage(entries, results.offset + results.limit < results.total)
    }

    // ============================== Details ===============================

    override fun mangaDetailsRequest(manga: SManga): Request {
        log("mangaDetails: manga.url='${manga.url}' -> $baseUrl${manga.url}")
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.selectFirst("h6")?.text().orEmpty()
            thumbnail_url = document.selectFirst(".comicParticulars-left-img img")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            author = document.select("a[href^=/author/]").joinToString { it.text() }
            genre = document.select(".comicParticulars-tag a").joinToString { it.text().removePrefix("#") }
            description = document.selectFirst("p.intro")?.text()?.trim()
            status = when (document.labelValue("狀態", "状态")) {
                "連載中", "连载中" -> SManga.ONGOING
                "已完結", "已完结" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    /** Reads the text of the `comicParticulars-right-txt` span that follows a label. */
    private fun Document.labelValue(vararg labels: String): String? = labels.firstNotNullOfOrNull { label ->
        selectFirst("span:contains($label) + span.comicParticulars-right-txt")?.text()?.trim()
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        val pathWord = manga.url.substringAfterLast('/')
        val url = "$baseUrl/comicdetail/$pathWord/chapters"
        log("chapterList: manga.url='${manga.url}' pathWord='$pathWord' -> $url")
        require(pathWord.isNotBlank()) { "無法從漫畫網址取得 path word：'${manga.url}'" }

        val chapterHeaders = headersBuilder()
            .set("Referer", "$baseUrl${manga.url}")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        return GET(url, chapterHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val pathWord = response.request.url.pathSegments.let { it[it.size - 2] }
        val payload = response.parseAs<ApiResponse<String>>().results
            ?: throw Exception(BLOCKED_MESSAGE)
        log("chapterList: encrypted payload ${payload.length} chars for '$pathWord'")

        val chapters = decrypt(payload, pathWord)
            .parseAs<ChapterListDto>()
            .groups.values
            .flatMap(GroupDto::chapters)
        log("chapterList: decrypted ${chapters.size} chapters")

        // A blocked or rate-limited request still decrypts cleanly, it just has
        // no chapters in it, so tell the user what actually happened.
        if (chapters.isEmpty()) throw Exception(BLOCKED_MESSAGE)

        return chapters.asReversed().mapIndexed { index, chapter ->
            SChapter.create().apply {
                url = "/comic/$pathWord/chapter/${chapter.id}"
                name = chapter.name
                chapter_number = (chapters.size - index).toFloat()
            }
        }
    }

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request {
        log("pageList: chapter.url='${chapter.url}' -> $baseUrl${chapter.url}")
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()

        val payload = CONTENT_KEY_REGEX.find(body)?.groupValues?.get(1)
        log("pageList: page ${body.length} chars, contentKey ${payload?.length ?: -1} chars")
        if (payload.isNullOrEmpty()) throw Exception(BLOCKED_MESSAGE)

        // The chapter page ships the key it was encrypted with.
        val key = PAGE_KEY_REGEX.find(body)?.groupValues?.get(1) ?: DEFAULT_KEY

        val pages = aesDecrypt(payload, key)
            .parseAs<List<PageDto>>()
            .mapIndexed { index, page -> Page(index, imageUrl = page.url) }
        log("pageList: ${pages.size} pages, first=${pages.firstOrNull()?.imageUrl}")
        return pages
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Decryption =============================

    /**
     * The website encrypts payloads with a key that it publishes in the page
     * itself and rotates from time to time, so fall back to re-reading it.
     */
    private fun decrypt(payload: String, pathWord: String): String {
        runCatching { return aesDecrypt(payload, cachedKey) }

        val document = client.newCall(GET("$baseUrl/comic/$pathWord", headers)).execute()
            .use { it.asJsoup() }
        val key = PAGE_KEY_REGEX.find(document.html())?.groupValues?.get(1)
            ?: throw Exception("無法取得解密金鑰")

        cachedKey = key
        return aesDecrypt(payload, key)
    }

    /** Payload is a 16 character IV followed by hex encoded ciphertext. */
    private fun aesDecrypt(payload: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.toByteArray(), "AES"),
                IvParameterSpec(payload.substring(0, 16).toByteArray()),
            )
        }
        return cipher.doFinal(payload.substring(16).decodeHex()).toString(Charsets.UTF_8)
    }

    companion object {
        private const val PAGE_SIZE = 21
        private const val APP_VERSION = "3.0.0"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val DEFAULT_KEY = "op0zzpvv.nmn.00p"

        @Volatile
        private var cachedKey: String = DEFAULT_KEY

        private val PAGE_KEY_REGEX = Regex("""var\s+cc[zt]\s*=\s*'([^']+)'""")
        private val CONTENT_KEY_REGEX = Regex("""var\s+contentKey\s*=\s*'([^']*)'""")

        private const val BLOCKED_MESSAGE =
            "無法取得內容，您的 IP 可能被網站封鎖，請嘗試使用 VPN 或更換鏡像網址"
    }
}
