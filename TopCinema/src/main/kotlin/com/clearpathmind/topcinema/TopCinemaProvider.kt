package com.clearpathmind.topcinema

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class TopCinema : MainAPI() {
    override var mainUrl = "https://topcinemaa.co"
    override var name = "TopCinema"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    override var lang = "ar"
    override val hasMainPage = true

    // Placeholder image used by the theme before lazy-loading
    private val placeholder = "cover.jpg"

    private val arabicOrdinals = mapOf(
        "الاول" to 1, "الأول" to 1,
        "الثاني" to 2,
        "الثالث" to 3,
        "الرابع" to 4,
        "الخامس" to 5,
        "السادس" to 6,
        "السابع" to 7,
        "الثامن" to 8,
        "التاسع" to 9,
        "العاشر" to 10,
        "الحادي عشر" to 11,
        "الثاني عشر" to 12
    )

    // Strip the leading type word and trailing translation suffixes from titles
    private val titleNoise = listOf(
        "مترجم اون لاين", "مترجمة اون لاين", "اون لاين", "مترجم كامل", "مترجمة كاملة", "كامل",
        "مترجمة", "مترجم"
    )

    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        t = t.removePrefix("فيلم ").removePrefix("مسلسل ").removePrefix("انمي ").trim()
        for (noise in titleNoise) {
            if (t.endsWith(noise)) {
                t = t.removeSuffix(noise).trim()
                break
            }
        }
        return t.ifBlank { raw }
    }

    private fun posterOf(card: Element): String? {
        val img = card.selectFirst(".Poster img") ?: card.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifBlank { img.attr("src") }
        return src.takeIf { it.isNotBlank() && !it.contains(placeholder) }
    }

    private fun qualityOf(card: Element): String? =
        card.select("ul.liList li")
            .map { it.text() }
            .firstOrNull { it.contains(Regex("\\d{3,4}\\s*p", RegexOption.IGNORE_CASE)) }

    private fun searchQualityOf(card: Element): SearchQuality? {
        val q = qualityOf(card)?.lowercase() ?: return null
        return when {
            "bluray" in q || "blu-ray" in q -> SearchQuality.BlueRay
            "web-dl" in q || "webdl" in q || "web" in q -> SearchQuality.WebRip
            "hdcam" in q -> SearchQuality.HdCam
            "hdts" in q -> SearchQuality.HdCam
            "hdrip" in q -> SearchQuality.HD
            else -> null
        }
    }

    private fun guessTvType(title: String): TvType = when {
        title.contains("انمي") -> TvType.Anime
        title.contains("مسلسل") -> TvType.TvSeries
        else -> TvType.Movie
    }

    private fun parseSeasonNumber(text: String, fallback: Int): Int {
        Regex("الموسم\\s+(\\d+)").find(text)?.groupValues?.get(1)?.let { return it.toIntOrNull() ?: fallback }
        arabicOrdinals.forEach { (word, num) ->
            if (text.contains(word) && text.contains("الموسم")) return num
        }
        return fallback
    }

    private fun parseCards(doc: Document): List<SearchResponse> =
        doc.select("div.Small--Box").mapNotNull { card ->
            val a = card.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = card.selectFirst("h3.title")?.text()?.takeIf { it.isNotBlank() }
                ?: a.attr("title").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            newMovieSearchResponse(
                cleanTitle(title),
                href,
                guessTvType(title),
            ) {
                posterUrl = posterOf(card)
                quality = searchQualityOf(card)
            }
        }.distinctBy { it.url }

    private fun parseEpisodes(doc: Document, seasonNumber: Int): List<Episode> =
        doc.select(".allepcont .row a").mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = a.attr("title").takeIf { it.isNotBlank() }
                ?: a.selectFirst(".ep-info h2")?.text()
                ?: return@mapNotNull null
            val epNum = Regex("(\\d+)")
                .find(a.selectFirst(".epnum")?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(href) {
                this.name = cleanTitle(name)
                this.episode = epNum
                this.season = seasonNumber
                this.posterUrl = posterOf(a)
            }
        }

    private fun pagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if ("?" in base) {
            base.replace("?", "/page/$page/?")
        } else {
            base.trimEnd('/') + "/page/$page/"
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/recent/" to "المضاف حديثا",
        "$mainUrl/movies/" to "الافلام",
        "$mainUrl/netflix-movies/" to "افلام نتفليكس",
        "$mainUrl/top-rating-imdb/" to "الاعلي تقييما IMDB",
        "https://topcinemaa.co/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/?key=episodes" to "مسلسلات اجنبي - احدث الحلقات",
        "https://topcinemaa.co/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "مسلسلات اجنبي",
        "$mainUrl/netflix-series/" to "اجنبي نتفليكس",
        "$mainUrl/top-rating-imdb-series/" to "المسلسلات الاعلي تقييما IMDB",
        "https://topcinemaa.co/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/?key=episodes" to "اسيوي - احدث الحلقات",
        "https://topcinemaa.co/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/" to "مسلسلات اسيوية",
        "https://topcinemaa.co/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%86%d9%85%d9%8a/?key=episodes" to "انمي - احدث الحلقات",
        "https://topcinemaa.co/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%86%d9%85%d9%8a/" to "قائمة الانميات",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(pagedUrl(request.data, page)).document
        val items = parseCards(doc)
        val hasNext = doc.select("ul.page-numbers li a")
            .any { it.attr("href").contains("/page/${page + 1}/") }
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return parseCards(
            app.get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}").document
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val rawTitle = doc.selectFirst("h1.post-title")?.text()?.trim()
            ?: doc.selectFirst("h1.post-title a")?.text()?.trim()
            ?: doc.title()
        val title = cleanTitle(rawTitle)
        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val plot = doc.selectFirst(".story p")?.text()?.trim()
        val rating = doc.selectFirst(".imdbBox span")?.text()?.trim()?.toFloatOrNull()
        val tags = doc.select(".catssection li a").map { it.text() }
        val year = Regex("(19|20)\\d{2}").find(rawTitle)?.value?.toIntOrNull()
        val tvType = guessTvType(rawTitle)

        if (url.contains("/series/")) {
            val type = when (tvType) {
                TvType.Anime -> TvType.Anime
                else -> TvType.TvSeries
            }
            val seasonLinks = doc.select(".allseasonss .Small--Box.Season a")

            val episodes: List<Episode> = if (seasonLinks.isEmpty()) {
                // The url is already a single-season page listing its episodes
                parseEpisodes(doc, parseSeasonNumber(rawTitle, 1))
            } else {
                seasonLinks.amapIndexed { index, s ->
                    val seasonUrl = s.attr("href")
                    val seasonDoc = runCatching { app.get(seasonUrl).document }.getOrNull()
                    val sn = seasonDoc?.let { d ->
                        d.selectFirst("h1.post-title")?.text()?.let { parseSeasonNumber(it, index + 1) }
                    } ?: (index + 1)
                    seasonDoc?.let { parseEpisodes(it, sn) } ?: emptyList()
                }.flatten().sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
            }

            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = rating?.let { Score.from(it, 10) }
                this.tags = tags
            }
        }

        return newMovieLoadResponse(title, url, tvType, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = rating?.let { Score.from(it, 10) }
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = data.trimEnd('/') + "/watch/"
        val res = app.get(watchUrl)
        val doc = res.document

        val sources = LinkedHashSet<String>()
        doc.selectFirst(".player--iframe iframe")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { sources.add(it) }

        // Other servers are fetched through the theme's AJAX endpoint:
        // MyAjaxURL + Single/Server.php with the post id and server index
        val ajaxBase = Regex("MyAjaxURL\\s*=\\s*[\"']([^\"']+)[\"']")
            .find(res.text)?.groupValues?.get(1)

        if (ajaxBase != null) {
            doc.select("li.server--item").forEach { li ->
                val id = li.attr("data-id")
                val index = li.attr("data-server")
                if (id.isBlank() || index.isBlank()) return@forEach
                runCatching {
                    val frag = app.post(
                        ajaxBase.trimEnd('/') + "/Single/Server.php",
                        data = mapOf("id" to id, "i" to index),
                        headers = mapOf(
                            "Referer" to watchUrl,
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    ).text
                    Jsoup.parse(frag).selectFirst("iframe")?.attr("src")
                        ?.takeIf { it.isNotBlank() }
                }.getOrNull()?.let { sources.add(it) }
            }
        }

        sources.apmap { src ->
            runCatching {
                loadExtractor(src, watchUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
