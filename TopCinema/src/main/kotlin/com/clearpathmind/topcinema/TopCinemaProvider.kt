package com.clearpathmind.topcinema

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
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

    // Series pages have no og:image; their poster lives in the single header block
    private fun posterOf(doc: Document): String? {
        doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?.takeIf { it.isNotBlank() && !it.contains(placeholder) }
            ?.let { return it }
        val img = doc.selectFirst(".MainSingle .left .image img")
            ?: doc.selectFirst(".Poster img")
            ?: return null
        val src = img.attr("data-src").ifBlank { img.attr("src") }
        return src.takeIf { it.isNotBlank() && !it.contains(placeholder) }
    }

    // og:description is series-level but wrapped in promo boilerplate; clean it up
    private fun cleanOgDescription(doc: Document): String? {
        val raw = doc.selectFirst("meta[property=\"og:description\"]")?.attr("data-content")
            ?: doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")
            ?: return null
        var t = raw.replace(Regex("<[^>]*>"), " ")
        for (noise in listOf(
            "مشاهدة وتحميل", "مشاهدة حلقات", "بجودة HD اون لاين وتحميل مباشر",
            "تحميل مباشر", "اون لاين", "اونلاين", "مترجم كامل", "كامل"
        )) {
            t = t.replace(noise, " ")
        }
        return t.trim().ifBlank { null }
    }

    private fun qualityOf(card: Element): String? =
        card.select("ul.liList li")
            .map { it.text() }
            .firstOrNull { it.contains(Regex("\\d{3,4}\\s*p", RegexOption.IGNORE_CASE)) }

    // IMDb rating badge on cards, e.g. <li class="imdbRating"><i></i> 8.1</li>
    private fun ratingOf(card: Element): Float? =
        card.selectFirst("li.imdbRating")?.text()?.trim()?.toFloatOrNull()

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

    private fun parseCards(root: Element): List<SearchResponse> =
        root.select("div.Small--Box").mapNotNull { card ->
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
                score = ratingOf(card)?.let { Score.from(it, 10) }
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

    // Groups near-identical episode cards ("... الحلقة 61", "... الحلقة 62", ...)
    // under one result, preferring the series/movie main card when present.
    // If only episode cards exist, the representative keeps the clean base title
    // instead of e.g. "Show الموسم الثالث الحلقة 8".
    private fun groupCards(cards: List<SearchResponse>): List<SearchResponse> {
        val groups = LinkedHashMap<String, MutableList<Pair<SearchResponse, Boolean>>>()
        for (card in cards) {
            val isEpisode = card.name.contains("الحلقة")
            groups.getOrPut(baseTitleKey(card.name)) { mutableListOf() }.add(card to isEpisode)
        }
        return groups.values.mapNotNull { group ->
            val (rep, repIsEpisode) = group.firstOrNull { !it.second } ?: group.firstOrNull()
                ?: return@mapNotNull null
            if (!repIsEpisode) {
                rep
            } else {
                val cleanName = baseTitleKey(rep.name).ifBlank { rep.name }
                newMovieSearchResponse(cleanName, rep.url, rep.type ?: TvType.Movie) {
                    this.posterUrl = rep.posterUrl
                    this.quality = rep.quality
                }
            }
        }
    }

    private fun baseTitleKey(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("\\s+الحلقة\\s+\\d+[^\\s]*"), "")
        t = t.replace(Regex("\\s+والاخيرة"), "")
        t = t.replace(Regex("\\s+الموسم\\s+[^\\s]+"), "")
        return t.trim()
    }

    // Season pages render only ~51 episodes inline (getMoreByScroll lazy loading).
    // The theme's AJAX endpoint returns the complete list for a season.
    private suspend fun fetchAllEpisodes(
        seasonDoc: Document,
        inline: List<Episode>,
        seasonNumber: Int
    ): List<Episode> {
        val first = inline.firstOrNull() ?: return inline
        val watchText = runCatching {
            app.get(first.data.trimEnd('/') + "/watch/").text
        }.getOrNull() ?: return inline
        val watchDoc = Jsoup.parse(watchText)
        val togglerLink = watchDoc.selectFirst(".seasons--toggler a[data-id][data-season]")
            ?: return inline
        val postId = togglerLink.attr("data-id")
        val seasonId = togglerLink.attr("data-season")
        if (postId.isBlank() || seasonId.isBlank()) return inline

        val ajaxHtml = runCatching {
            app.post(
                "$mainUrl/wp-content/themes/movies2023/Ajaxat/Single/Episodes.php",
                data = mapOf("season" to seasonId, "post_id" to postId),
                headers = mapOf(
                    "Referer" to mainUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text
        }.getOrNull() ?: return inline

        val parsed = Jsoup.parse(ajaxHtml).select("a[href]").mapNotNull { a ->
            val num = a.selectFirst("em")?.text()?.toIntOrNull() ?: return@mapNotNull null
            val postUrl = a.attr("href").removeSuffix("/watch/").trimEnd('/')
            if (postUrl.isBlank()) null
            else newEpisode(postUrl) {
                this.season = seasonNumber
                this.episode = num
            }
        }
        return if (parsed.size > inline.size) parsed else inline
    }

    override val mainPage = mainPageOf(
        "$mainUrl/recent/" to "المضاف حديثا",
        "$mainUrl/movies/" to "الافلام",
        "$mainUrl/netflix-movies/" to "افلام نتفليكس",
        "$mainUrl/top-rating-imdb/" to "الاعلى تقييما IMDB",
        "https://topcinemaa.co/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/?key=episodes" to "مسلسلات اجنبية - احدث الحلقات",
        "https://topcinemaa.co/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a/" to "مسلسلات اجنبية",
        "$mainUrl/netflix-series/" to "اجنبي نتفليكس",
        "$mainUrl/top-rating-imdb-series/" to "المسلسلات الاعلى تقييما IMDB",
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
        return groupCards(
            parseCards(
                app.get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}").document
            )
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val rawTitle = doc.selectFirst("h1.post-title")?.text()?.trim()
            ?: doc.selectFirst("h1.post-title a")?.text()?.trim()
            ?: doc.title()

        // A single episode post was opened directly (e.g. from grouped search results).
        // Redirect to its parent series so the user sees the whole show.
        if (!url.contains("/series/") && rawTitle.contains("الحلقة")) {
            val seriesUrl = doc.select("#mpbreadcrumbs a[href*=\"/series/\"]")
                .map { it.attr("href") }
                .firstOrNull { it != url }
            if (seriesUrl != null) {
                return load(seriesUrl)
            }
        }

        val title = cleanTitle(rawTitle)
        val poster = posterOf(doc)
        val plot = doc.selectFirst(".story p")?.text()?.trim()
        val rating = doc.selectFirst(".imdbBox span")?.text()?.trim()?.toFloatOrNull()
        // Genres live in ul.RightTaxContent under the "نوع الفيلم/المسلسل" row
        val tags = doc.select("ul.RightTaxContent li")
            .firstOrNull { it.selectFirst("span")?.text()?.contains("نوع") == true }
            ?.select("a")?.map { it.text() }?.filter { it.isNotBlank() }
            ?: emptyList()
        val year = Regex("(19|20)\\d{2}").find(rawTitle)?.value?.toIntOrNull()
        val tvType = guessTvType(rawTitle)

        if (url.contains("/series/")) {
            val type = when (tvType) {
                TvType.Anime -> TvType.Anime
                else -> TvType.TvSeries
            }
            val seasonLinks = doc.select(".allseasonss .Small--Box.Season a")

            // The site puts the LATEST season's synopsis in the series page story,
            // so prefer the story from the lowest-numbered season page instead.
            var seriesPlot: String? = null

            val episodes: List<Episode> = if (seasonLinks.isEmpty()) {
                // The url is already a single-season page listing its episodes
                val sn = parseSeasonNumber(rawTitle, 1)
                val inline = parseEpisodes(doc, sn)
                val claimed = Regex("الحلقات\\s*\\[\\s*(\\d+)\\s*\\]")
                    .find(doc.text())?.groupValues?.get(1)?.toIntOrNull()
                if (claimed != null && inline.size < claimed) {
                    fetchAllEpisodes(doc, inline, sn)
                } else {
                    inline
                }
            } else {
                val perSeason = seasonLinks.amapIndexed { index, s ->
                    val seasonUrl = s.attr("href")
                    val seasonDoc = runCatching { app.get(seasonUrl).document }.getOrNull()
                        ?: return@amapIndexed null
                    val sn = seasonDoc.selectFirst("h1.post-title")?.text()
                        ?.let { parseSeasonNumber(it, index + 1) } ?: (index + 1)
                    val inline = parseEpisodes(seasonDoc, sn)
                    val claimed = Regex("الحلقات\\s*\\[\\s*(\\d+)\\s*\\]")
                        .find(seasonDoc.text())?.groupValues?.get(1)?.toIntOrNull()
                    val eps = if (claimed != null && inline.size < claimed) {
                        fetchAllEpisodes(seasonDoc, inline, sn)
                    } else {
                        inline
                    }
                    Triple(sn, eps, seasonDoc.selectFirst(".story p")?.text()?.trim())
                }
                perSeason.filterNotNull().minByOrNull { it.first }?.let { seriesPlot = it.third }
                perSeason.filterNotNull().flatMap { it.second }
                    .sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
            }

            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = (seriesPlot ?: plot)?.takeIf { it.isNotBlank() }
                    ?: cleanOgDescription(doc)
                this.year = year
                this.score = rating?.let { Score.from(it, 10) }
                this.tags = tags
                // "مسلسلات اخرى" tab on series pages
                val othersSection = doc.selectFirst("section.otherser")
                val otherSeries: List<SearchResponse>? =
                    if (othersSection != null) parseCards(othersSection) else null
                this.recommendations = if (otherSeries.isNullOrEmpty()) null else otherSeries
            }
        }

        return newMovieLoadResponse(title, url, tvType, url) {
            this.posterUrl = poster
            this.plot = plot?.takeIf { it.isNotBlank() } ?: cleanOgDescription(doc)
            this.year = year
            this.score = rating?.let { Score.from(it, 10) }
            this.tags = tags
            // "مشاهدة عروض اخري" section on movie pages
            val relatedSection = doc.selectFirst(".related--Posts")
            val relatedMovies: List<SearchResponse>? =
                if (relatedSection != null) parseCards(relatedSection) else null
            this.recommendations = if (relatedMovies.isNullOrEmpty()) null else relatedMovies
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

        sources.toList().amap { src ->
            runCatching {
                loadExtractor(src, watchUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
