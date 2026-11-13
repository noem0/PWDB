package eu.kanade.tachiyomi.extension.en.pornhwareader

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class PornhwaReader : KeiSource() {

    override val supportsFilterFetching = true

    override suspend fun getPopularManga(page: Int): MangasPage =
        getHomeListing(page, listOf("popular", "trending"))

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        getHomeListing(page, listOf("latest", "recent", "new releases"))

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val schema = parseBrowseSchema(fetchFilterData())
        if (!schema.found) {
            throw IOException("PornhwaReader search form was not found on the browse page")
        }
        if (query.isNotBlank() && schema.queryName == null) {
            throw IOException("PornhwaReader search input was not present in the discovered form")
        }

        val parameters = mutableListOf<Pair<String, String>>()
        parameters += schema.hidden
        if (query.isNotBlank()) {
            parameters += schema.queryName!! to query
        }

        filters.forEach { filter ->
            when (filter) {
                is BrowseSelectFilter -> {
                    filter.optionValues.getOrNull(filter.state)?.let {
                        parameters += filter.parameter to it
                    }
                }

                is BrowseCheckGroup -> {
                    filter.state.forEachIndexed { index, option ->
                        if (option.state) {
                            filter.optionValues.getOrNull(index)?.let {
                                parameters += filter.parameter to it
                            }
                        }
                    }
                }
            }
        }

        if (page > 1 && schema.pageName != null) {
            parameters += schema.pageName to page.toString()
        }

        val document = if (schema.method == "POST") {
            val body = FormBody.Builder().apply {
                parameters.forEach { (name, value) -> add(name, value) }
            }.build()
            client.post(schema.action.toHttpUrl(), Fetch.DOCUMENT, body).asJsoup()
        } else {
            val builder = schema.action.toHttpUrl().newBuilder()
            parameters.forEach { (name, value) -> builder.addQueryParameter(name, value) }
            client.get(builder.build().toString(), Fetch.DOCUMENT).asJsoup()
        }

        return MangasPage(parseSeriesCards(document), findNextPageUrl(document) != null)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val match = SERIES_PATH.find(url.encodedPath) ?: return null
        return SManga.create().apply {
            this.url = match.value
            title = match.value.substringAfterLast('/').replace('-', ' ')
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val seriesPath = SERIES_PATH.find(manga.url.substringBefore('?'))?.value
            ?: throw IOException("Invalid PornhwaReader series URL: ${manga.url}")
        val seriesUrl = baseUrl + seriesPath
        val firstPage = client.get(seriesUrl, Fetch.DOCUMENT).asJsoup()
        val updatedManga = parseSeriesDetails(firstPage, manga, seriesPath)
        val chapterList = fetchAllChapters(seriesPath, firstPage)
        return SMangaUpdate(updatedManga, chapterList)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = baseUrl + chapter.url
        val document = client.get(chapterUrl, Fetch.DOCUMENT).asJsoup()
        val imageUrls = parseReaderPages(document.html())
        if (imageUrls.isEmpty()) {
            throw IOException("PornhwaReader returned an empty reader page list for ${chapter.url}")
        }
        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    override suspend fun fetchFilterData(): JsonElement = discoverBrowseSchema().toJson()

    override fun getFilterList(data: JsonElement?): FilterList {
        val root = data as? JsonObject ?: return FilterList()
        val controls = root["controls"] as? JsonArray ?: return FilterList()
        val filters = mutableListOf<Filter<*>>()

        controls.forEach { element ->
            val control = element as? JsonObject ?: return@forEach
            val parameter = control.string("name") ?: return@forEach
            val label = control.string("label") ?: parameter
            val kind = control.string("kind") ?: return@forEach
            val options = control["options"]?.jsonArray?.mapNotNull { optionElement ->
                val option = optionElement as? JsonObject ?: return@mapNotNull null
                val value = option.string("value") ?: return@mapNotNull null
                val optionLabel = option.string("label") ?: value
                BrowseOption(optionLabel, value)
            }.orEmpty()

            if (options.isEmpty()) return@forEach
            when (kind) {
                "select" -> filters += BrowseSelectFilter(parameter, label, options)
                "multi", "checkbox" -> filters += BrowseCheckGroup(parameter, label, options)
            }
        }

        return FilterList(*filters.toTypedArray())
    }

    private suspend fun getHomeListing(page: Int, sectionNames: List<String>): MangasPage {
        var document = client.get(baseUrl, Fetch.DOCUMENT).asJsoup()
        repeat((page - 1).coerceAtLeast(0)) {
            val section = findListingSection(document, sectionNames)
            val nextUrl = findNextPageUrl(section) ?: findNextPageUrl(document)
                ?: return MangasPage(emptyList(), false)
            document = client.get(nextUrl, Fetch.DOCUMENT).asJsoup()
        }

        val section = findListingSection(document, sectionNames)
        val mangas = parseSeriesCards(section)
        return MangasPage(mangas, findNextPageUrl(section) != null || findNextPageUrl(document) != null)
    }

    private fun findListingSection(document: Document, sectionNames: List<String>): Element {
        return document.select("section, article, div").firstOrNull { container ->
            val heading = container.selectFirst("h1, h2, h3, h4")
            heading != null &&
                sectionNames.any { heading.text().contains(it, ignoreCase = true) } &&
                container.select("a[href]").any { isSeriesPath(pathAndQuery(it.attr("href"))) }
        } ?: document
    }

    private fun parseSeriesCards(scope: Element): List<SManga> {
        val result = mutableListOf<SManga>()
        val seen = mutableSetOf<String>()

        scope.select("a[href]").forEach { anchor ->
            val path = pathAndQuery(anchor.attr("href"))
            val cleanPath = path.substringBefore('?').trimEnd('/')
            if (!SERIES_URL.matches(cleanPath) || !seen.add(cleanPath)) return@forEach

            val card = anchor.parent() ?: anchor
            val image = anchor.selectFirst("img") ?: card.selectFirst("img")
            val title = sequenceOf(
                anchor.selectFirst("h1, h2, h3, h4, [class*=title]")?.text(),
                card.selectFirst("h1, h2, h3, h4, [class*=title]")?.text(),
                image?.attr("alt"),
                anchor.text(),
            ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
            if (title.isBlank()) return@forEach

            val thumbnail = image?.let(::imageSource)?.let(::resolveUrl)
            result += SManga.create().apply {
                url = cleanPath
                this.title = sanitizeTitle(title)
                thumbnail_url = thumbnail
            }
        }

        return result
    }

    private fun parseSeriesDetails(document: Document, manga: SManga, seriesPath: String): SManga {
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf(String::isNotBlank)
            ?: document.selectFirst("main h1, h1")?.text()
            ?: manga.title

        val cover = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")
            ?.takeIf(String::isNotBlank)
            ?.let(::resolveUrl)

        val description = document.selectFirst("meta[property=og:description], meta[name=description]")
            ?.attr("content")
            ?.takeIf(String::isNotBlank)
            ?: document.selectFirst("main p")?.text().orEmpty()

        val genres = sectionLinks(document, setOf("genre", "genres"))
        val characters = sectionLinks(document, setOf("character", "characters"))
        val scenes = sectionLinks(document, setOf("scene", "scenes"))
        val author = labelledValue(document, "author")
        val artist = labelledValue(document, "artist")
        val rating = labelledValue(document, "rating")
        val statusText = labelledValue(document, "status").ifBlank { document.text() }

        manga.url = seriesPath
        manga.title = sanitizeTitle(title)
        manga.thumbnail_url = cover ?: manga.thumbnail_url
        manga.description = buildString {
            append(description)
            if (author.isNotBlank()) append("\n\nAuthor: ", author)
            if (artist.isNotBlank()) append("\nArtist: ", artist)
            if (rating.isNotBlank()) append("\nRating: ", rating)
            if (characters.isNotEmpty()) append("\nCharacters: ", characters.joinToString())
            if (scenes.isNotEmpty()) append("\nScenes: ", scenes.joinToString())
        }.ifBlank { null }
        manga.author = author.ifBlank { null }
        manga.artist = artist.ifBlank { null }
        manga.genre = genres.distinct().joinToString().ifBlank { null }
        manga.status = when {
            statusText.contains("completed", ignoreCase = true) -> SManga.COMPLETED
            statusText.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("hiatus", ignoreCase = true) -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        return manga
    }

    private suspend fun fetchAllChapters(seriesPath: String, firstPage: Document): List<SChapter> {
        val result = mutableListOf<SChapter>()
        val seen = mutableSetOf<String>()
        var page = 1
        var document = firstPage

        while (true) {
            val pageChapters = parseChapterRows(document, seriesPath, seen)
            if (pageChapters.isEmpty()) break
            result += pageChapters
            page += 1
            val pageUrl = baseUrl.toHttpUrl().newBuilder()
                .encodedPath(seriesPath)
                .setQueryParameter("page", page.toString())
                .build()
                .toString()
            document = client.get(pageUrl, Fetch.DOCUMENT).asJsoup()
        }

        return result
    }

    private fun parseChapterRows(
        document: Document,
        seriesPath: String,
        seen: MutableSet<String>,
    ): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        document.select("div.chapter-row > a[href]").forEach { anchor ->
            val resolved = baseUrl.toHttpUrl().resolve(anchor.attr("href")) ?: return@forEach
            if (resolved.host != baseUrl.toHttpUrl().host) return@forEach

            val path = resolved.encodedPath.trimEnd('/')
            if (!path.startsWith("$seriesPath/")) return@forEach
            val identifier = path.removePrefix("$seriesPath/")
            val numberMatch = CHAPTER_NUMBER.matchEntire(identifier)
            val uuidMatch = CHAPTER_UUID.matches(identifier)
            if (numberMatch == null && !uuidMatch) return@forEach

            val chapterUrl = buildString {
                append(resolved.encodedPath)
                resolved.encodedQuery?.let { append('?').append(it) }
            }
            if (!seen.add(chapterUrl)) return@forEach

            val rowText = anchor.parent()?.text().orEmpty()
            val edition = EDITION.find(rowText)?.value
            var name = anchor.selectFirst(".chapter-title")?.text().orEmpty()
            if (name.isBlank()) name = anchor.text().lineSequence().firstOrNull().orEmpty().trim()
            if (name.isBlank()) {
                name = numberMatch?.groupValues?.get(1)?.let { "Chapter $it" } ?: "Chapter"
            }
            if (edition != null && !name.contains(edition, ignoreCase = true)) {
                name += " [$edition]"
            }

            chapters += SChapter.create().apply {
                url = chapterUrl
                this.name = name
                chapter_number = numberMatch?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                date_upload = parseDate(anchor.selectFirst("time"))
            }
        }

        return chapters
    }

    private suspend fun discoverBrowseSchema(): BrowseSchema {
        val home = client.get(baseUrl, Fetch.DOCUMENT).asJsoup()
        val browseLink = home.select("a[href]").firstOrNull { anchor ->
            val label = listOf(anchor.text(), anchor.attr("aria-label"), anchor.attr("title"))
                .joinToString(" ")
            label.contains("browse", ignoreCase = true)
        }
        val browseDocument = browseLink?.let {
            val target = baseUrl.toHttpUrl().resolve(it.attr("href"))?.toString()
            target?.let { url -> client.get(url, Fetch.DOCUMENT).asJsoup() }
        } ?: home

        val form = findBrowseForm(browseDocument) ?: findBrowseForm(home)
            ?: return BrowseSchema.empty(browseDocument.location().ifBlank { baseUrl })

        val action = form.absUrl("action").ifBlank { browseDocument.location().ifBlank { baseUrl } }
        val method = form.attr("method").uppercase(Locale.ROOT).ifBlank { "GET" }
        val inputs = form.select("input[name]")
        val queryName = inputs.firstOrNull { input ->
            val type = input.attr("type").lowercase(Locale.ROOT)
            val name = input.attr("name").lowercase(Locale.ROOT)
            type == "search" || name == "q" || name.contains("search") || name.contains("query")
        }?.attr("name")

        val hidden = form.select("input[type=hidden][name]").mapNotNull { input ->
            val name = input.attr("name")
            val value = input.attr("value")
            if (name.isBlank() || name == queryName) null else name to value
        }

        val controls = mutableListOf<BrowseControl>()
        form.select("select[name]").forEach { select ->
            val name = select.attr("name")
            val options = select.select("option").mapNotNull { option ->
                val value = option.attr("value").ifBlank { option.text() }
                if (value.isBlank()) null else BrowseOption(option.text().ifBlank { value }, value)
            }
            if (name.isNotBlank() && options.isNotEmpty()) {
                controls += BrowseControl(
                    name,
                    controlLabel(select, name),
                    if (select.hasAttr("multiple")) "multi" else "select",
                    options,
                )
            }
        }

        form.select("input[type=checkbox][name]").groupBy { it.attr("name") }.forEach { (name, inputsForName) ->
            if (name.isBlank()) return@forEach
            val options = inputsForName.mapNotNull { input ->
                val value = input.attr("value")
                if (value.isBlank()) null else BrowseOption(controlLabel(input, value), value)
            }
            if (options.isNotEmpty()) {
                controls += BrowseControl(name, name.removeSuffix("[]").replace('_', ' ').replaceFirstChar(Char::uppercase), "checkbox", options)
            }
        }

        val nextUrl = findNextPageUrl(browseDocument)
        val pageName = form.selectFirst("input[type=hidden][name]")?.attr("name")
            ?.takeIf { it.contains("page", ignoreCase = true) }
            ?: nextUrl?.let { url ->
                runCatching { url.toHttpUrl() }.getOrNull()
                    ?.queryParameterNames
                    ?.firstOrNull { key -> url.toHttpUrl().queryParameter(key)?.toIntOrNull() != null }
            }

        return BrowseSchema(
            found = queryName != null || controls.isNotEmpty(),
            action = action,
            method = method,
            queryName = queryName,
            pageName = pageName,
            hidden = hidden,
            controls = controls,
        )
    }

    private fun findBrowseForm(document: Document): Element? {
        val forms = document.select("form")
        return forms.firstOrNull { form ->
            val searchInput = form.select("input").any { input ->
                val name = input.attr("name").lowercase(Locale.ROOT)
                input.attr("type").equals("search", true) ||
                    name == "q" || name.contains("search") || name.contains("query")
            }
            val filterInput = form.select("select[multiple], input[type=checkbox]").isNotEmpty()
            searchInput || filterInput
        }
    }

    private fun controlLabel(element: Element, fallback: String): String {
        val id = element.attr("id")
        val label = if (id.isNotBlank()) {
            element.ownerDocument()?.select("label[for]")?.firstOrNull { it.attr("for") == id }?.text()
        } else {
            null
        }
        return label?.takeIf(String::isNotBlank)
            ?: element.parent()?.takeIf { it.tagName() == "label" }?.text()?.takeIf(String::isNotBlank)
            ?: element.attr("aria-label").takeIf(String::isNotBlank)
            ?: fallback
    }

    private fun BrowseSchema.toJson(): JsonElement = buildJsonObject {
        put("found", found)
        put("action", action)
        put("method", method)
        put("queryName", queryName.orEmpty())
        put("pageName", pageName.orEmpty())
        put("hidden", buildJsonArray {
            hidden.forEach { (name, value) ->
                add(buildJsonObject {
                    put("name", name)
                    put("value", value)
                })
            }
        })
        put("controls", buildJsonArray {
            controls.forEach { control ->
                add(buildJsonObject {
                    put("name", control.name)
                    put("label", control.label)
                    put("kind", control.kind)
                    put("options", buildJsonArray {
                        control.options.forEach { option ->
                            add(buildJsonObject {
                                put("label", option.label)
                                put("value", option.value)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun parseBrowseSchema(data: JsonElement): BrowseSchema {
        val root = data as? JsonObject ?: return BrowseSchema.empty(baseUrl)
        val hidden = root["hidden"]?.jsonArray?.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item.string("name") ?: return@mapNotNull null
            val value = item.string("value") ?: ""
            name to value
        }.orEmpty()
        val controls = root["controls"]?.jsonArray?.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item.string("name") ?: return@mapNotNull null
            val label = item.string("label") ?: name
            val kind = item.string("kind") ?: return@mapNotNull null
            val options = item["options"]?.jsonArray?.mapNotNull { optionElement ->
                val option = optionElement as? JsonObject ?: return@mapNotNull null
                val value = option.string("value") ?: return@mapNotNull null
                BrowseOption(option.string("label") ?: value, value)
            }.orEmpty()
            BrowseControl(name, label, kind, options)
        }.orEmpty()

        return BrowseSchema(
            found = root["found"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            action = root.string("action") ?: baseUrl,
            method = root.string("method") ?: "GET",
            queryName = root.string("queryName")?.ifBlank { null },
            pageName = root.string("pageName")?.ifBlank { null },
            hidden = hidden,
            controls = controls,
        )
    }

    private fun sectionLinks(document: Document, labels: Set<String>): List<String> {
        val heading = document.select("h1, h2, h3, h4, dt, strong, b")
            .firstOrNull { it.text().trim().trimEnd(':').lowercase(Locale.ROOT) in labels }
            ?: return emptyList()
        val container = heading.parent() ?: return emptyList()
        return container.select("a[href]").map { it.text().trim() }.filter(String::isNotBlank).distinct()
    }

    private fun labelledValue(document: Document, label: String): String {
        val target = label.lowercase(Locale.ROOT)
        document.select("dt, th, strong, b, label, span").forEach { element ->
            val text = element.text().trim().trimEnd(':').lowercase(Locale.ROOT)
            if (text == target) {
                val sibling = element.nextElementSibling()?.text()?.trim().orEmpty()
                if (sibling.isNotBlank()) return sibling
                val parentText = element.parent()?.text().orEmpty()
                if (parentText.length > element.text().length) {
                    return parentText.removePrefix(element.text()).trim().trimStart(':').trim()
                }
            }
        }
        return ""
    }

    private fun findNextPageUrl(element: Element): String? {
        val next = element.select("a[rel=next], a[aria-label*=Next], a[aria-label*=next]")
            .firstOrNull()
            ?: element.select("a[href]").firstOrNull {
                it.text().trim().lowercase(Locale.ROOT) in setOf("next", "next »", "»", "›")
            }
            ?: return null
        return baseUrl.toHttpUrl().resolve(next.attr("href"))?.toString()
    }

    private fun pathAndQuery(href: String): String {
        val resolved = baseUrl.toHttpUrl().resolve(href) ?: return ""
        if (resolved.host != baseUrl.toHttpUrl().host) return ""
        return buildString {
            append(resolved.encodedPath)
            resolved.encodedQuery?.let { append('?').append(it) }
        }
    }

    private fun imageSource(image: Element): String {
        return sequenceOf("data-src", "data-lazy-src", "src")
            .map { image.attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:image", ignoreCase = true) }
            .orEmpty()
            .substringBefore(',')
            .trim()
    }

    private fun resolveUrl(value: String): String? {
        if (value.isBlank()) return null
        return baseUrl.toHttpUrl().resolve(value)?.toString()
    }

    private fun sanitizeTitle(value: String): String =
        value.replace(Regex("""\s*[|–-]\s*PornhwaReader\s*$""", RegexOption.IGNORE_CASE), "").trim()

    private fun parseDate(element: Element?): Long {
        val value = element?.attr("datetime")?.takeIf(String::isNotBlank)
            ?: element?.text()?.takeIf(String::isNotBlank)
            ?: return 0L

        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()?.let { return it }
        val patterns = listOf("MMM d, yyyy", "MMM dd, yyyy", "MMMM d, yyyy", "yyyy-MM-dd")
        patterns.forEach { pattern ->
            runCatching {
                LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()?.let { return it }
        }
        return 0L
    }

    private fun parseReaderPages(html: String): List<String> {
        val stream = nextFlightStream(html)
        val marker = stream.indexOf("\"pages\":[")
        if (marker < 0) throw IOException("PornhwaReader chapter RSC payload did not contain a reader pages array")

        val raw = extractBalancedJson(stream, marker + "\"pages\":".length)
        val array = runCatching { Json.parseToJsonElement(raw).jsonArray }
            .getOrElse { throw IOException("PornhwaReader reader pages array was invalid JSON", it) }
        val pages = array.map { element ->
            val page = element as? JsonObject
                ?: throw IOException("PornhwaReader reader page entry was not an object")
            val ordinal = page["ordinal"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: throw IOException("PornhwaReader reader page had no valid ordinal")
            val url = page["url"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("PornhwaReader reader page had no image URL")
            if (ordinal < 0 || !url.startsWith("https://")) {
                throw IOException("PornhwaReader returned an invalid reader page URL or ordinal")
            }
            ordinal to url
        }.sortedBy { it.first }

        if (pages.isEmpty()) throw IOException("PornhwaReader chapter contained no reader pages")
        if (pages.map { it.first }.distinct().size != pages.size) {
            throw IOException("PornhwaReader reader page ordinals were duplicated")
        }

        return pages.map { it.second }.distinct().also {
            if (it.isEmpty()) throw IOException("PornhwaReader chapter contained no usable image URLs")
        }
    }

    private fun nextFlightStream(html: String): String {
        val chunks = NEXT_FLIGHT_CHUNK.findAll(html).mapNotNull { match ->
            runCatching {
                Json.parseToJsonElement("\"${match.groupValues[1]}\"").jsonPrimitive.content
            }.getOrNull()
        }.toList()
        if (chunks.isEmpty()) throw IOException("PornhwaReader page did not contain a Next.js RSC payload")
        return chunks.joinToString("")
    }

    private fun extractBalancedJson(value: String, offset: Int): String {
        var start = offset
        while (start < value.length && value[start].isWhitespace()) start++
        if (start >= value.length || (value[start] != '[' && value[start] != '{')) {
            throw IOException("PornhwaReader RSC payload did not contain a JSON value after the pages key")
        }

        val stack = mutableListOf<Char>()
        var inString = false
        var escaped = false
        for (index in start until value.length) {
            val char = value[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '{' -> stack += '}'
                '[' -> stack += ']'
                '}', ']' -> {
                    if (stack.isEmpty() || stack.removeAt(stack.lastIndex) != char) {
                        throw IOException("PornhwaReader RSC reader pages JSON was unbalanced")
                    }
                    if (stack.isEmpty()) return value.substring(start, index + 1)
                }
            }
        }
        throw IOException("PornhwaReader RSC reader pages JSON was incomplete")
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private class BrowseOption(val label: String, val value: String)

    private class BrowseControl(
        val name: String,
        val label: String,
        val kind: String,
        val options: List<BrowseOption>,
    )

    private class BrowseSchema(
        val found: Boolean,
        val action: String,
        val method: String,
        val queryName: String?,
        val pageName: String?,
        val hidden: List<Pair<String, String>>,
        val controls: List<BrowseControl>,
    ) {
        companion object {
            fun empty(action: String) = BrowseSchema(false, action, "GET", null, null, emptyList(), emptyList())
        }
    }

    private class BrowseSelectFilter(
        val parameter: String,
        name: String,
        options: List<BrowseOption>,
    ) : Filter.Select<String>(name, options.map { it.label }.toTypedArray()) {
        val optionValues = options.map { it.value }
    }

    private class BrowseCheckGroup(
        val parameter: String,
        name: String,
        options: List<BrowseOption>,
    ) : Filter.Group<Filter.CheckBox>(
        name,
        options.map { option -> object : Filter.CheckBox(option.label, false) {} },
    ) {
        val optionValues = options.map { it.value }
    }

    companion object {
        private val SERIES_PATH = Regex("""^/series/\d+/[^/?#]+""")
        private val SERIES_URL = Regex("""^/series/\d+/[^/?#]+/?$""")
        private val CHAPTER_NUMBER = Regex("""chapter-(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val CHAPTER_UUID = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""", RegexOption.IGNORE_CASE)
        private val EDITION = Regex("""\b(?:standard|uncensored)\b""", RegexOption.IGNORE_CASE)
        private val NEXT_FLIGHT_CHUNK = Regex(
            """self\.__next_f\.push\(\[1,"((?:\\.|[^"\\])*)"\]\)""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    }
}
