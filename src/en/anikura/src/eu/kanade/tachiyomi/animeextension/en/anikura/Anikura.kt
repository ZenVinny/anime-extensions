package eu.kanade.tachiyomi.animeextension.en.anikura

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.get
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

/**
 * Anikura (anikura.club) source.
 *
 * Watch flow uses two endpoints:
 *
 *  - `/api/watch/streams?id=X&ep=N&lang=sub|dub` lists every registered
 *    provider for an episode. It is a catalog: a provider can appear here
 *    but have no actual stream for this episode.
 *  - `/api/watch/sources?id=X&ep=N&lang=sub|dub&provider=<id>` fetches a
 *    single provider's stream, returning `{"stream": null}` when none
 *    exists.
 *
 * Verified on anime 8384 ep 2: `sources?provider=megaplay:1` returns null
 * while `sources?provider=kaa:1` returns a real stream, so the catalog is
 * queried per provider before being offered to the player.
 *
 * Provider ids: the first `-`-delimited segment of the stream id plus the
 * `:1` suffix the site uses. `kaa-native-sub` → `kaa:1`;
 * `megaplay-native-sub-172352` → `megaplay:1`.
 *
 * The proxy `s=` signature on every URL expires in ~10 minutes, so videos
 * are marked `initialized = true` and [resolveVideo] re-fetches
 * `/api/watch/sources` right before playback, refreshing both the video URL
 * and the subtitle tracks.
 */
class Anikura :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Anikura"
    override val baseUrl = "https://www.anikura.club"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val preferredServer: String
        get() = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

    private val excludedServers: Set<String>
        get() = preferences.getStringSet(PREF_SERVER_EXCLUDE_KEY, emptySet()) ?: emptySet()

    private val streamHeaders: Headers
        get() = headers.newBuilder()
            .add("x-anikura-player", "1")
            .add("Referer", "$baseUrl/")
            .add("Accept", "application/json")
            .build()

    private val browseHeaders: Headers
        get() = headers.newBuilder()
            .add("x-anikura-player", "1")
            .add("Referer", "$baseUrl/browse")
            .add("Accept", "application/json")
            .build()

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/api/browse/page".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, browseHeaders)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val body = response.parseAs<BrowsePageDto>()
        val items = body.items
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = items.isNotEmpty() && currentPage < MAX_PAGES
        return AnimesPage(items.map { it.toSAnime(baseUrl) }, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val rows = response.asJsoup().parseHomeRows()
        val row = rows.firstOrNull { it.episodes.isNotEmpty() }
            ?: return AnimesPage(emptyList(), false)
        return AnimesPage(row.episodes.map { it.toSAnime(baseUrl) }, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val maybeUrl = query.toHttpUrlOrNull()
        if (maybeUrl != null && maybeUrl.host == baseUrl.toHttpUrl().host) {
            return GET(maybeUrl, headers)
        }

        val url = "$baseUrl/api/browse/page".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) addQueryParameter("q", query)
            filters.forEach { filter ->
                when (filter) {
                    is Filters.SortFilter -> when (filter.selected) {
                        1 -> addQueryParameter("sort", "top")
                        2 -> addQueryParameter("sort", "newest")
                    }

                    is Filters.StatusFilter -> filter.value?.let { addQueryParameter("status", it) }

                    else -> {}
                }
            }
        }.build()
        return GET(url, browseHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.parseAs<BrowsePageDto>()
        val items = body.items
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = items.isNotEmpty() && currentPage < MAX_PAGES
        return AnimesPage(items.map { it.toSAnime(baseUrl) }, hasNextPage)
    }

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/anime/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = response.asJsoup().extractHeroAnime()
        return anime.toSAnime(baseUrl).apply { initialized = true }
    }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val anime = doc.extractHeroAnime()
        val payload = doc.extractEpisodeList()
        val sAnimeUrl = "${anime.id}/${anime.slug}"
        return payload.episodes
            .sortedByDescending { it.number }
            .map { ep ->
                val key = ep.number.toString()
                val preview = payload.thumbnails[key]
                    ?.let { if (it.startsWith("/")) "$baseUrl$it" else it }
                val summary = payload.descriptions[key]?.takeIf { it.isNotBlank() }
                ep.toSEpisode(sAnimeUrl, "sub", preview, summary)
            }
    }

    override fun getEpisodeUrl(episode: SEpisode): String = "$baseUrl${episode.url}"

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override fun hosterListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val info = parseEpisodeUrl(episode.url) ?: return emptyList()
        val referer = "$baseUrl${episode.url}"

        val streamsUrl = "$baseUrl/api/watch/streams".toHttpUrl().newBuilder()
            .addQueryParameter("id", info.animeId)
            .addQueryParameter("ep", info.episode.toString())
            .addQueryParameter("lang", info.lang)
            .build()

        val catalog = try {
            client.get(streamsUrl, streamHeaders).parseAs<StreamsResponseDto>().streams
                .filter { it.url.isNotBlank() && it.embedUrl.isNullOrBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "streams fetch failed", e)
            return emptyList()
        }

        if (catalog.isEmpty()) return emptyList()

        val excluded = excludedServers
        val videos = mutableListOf<Pair<String, Video>>()
        for (stream in catalog) {
            val provider = providerIdOf(stream) ?: continue
            val serverName = serverNameOf(stream) ?: continue
            if (serverName in excluded) continue
            val video = fetchSource(info, provider, referer) ?: continue
            videos.add(serverName to video)
        }

        if (videos.isEmpty()) return emptyList()

        val preferred = preferredServer
        val ordered = if (preferred.isBlank()) {
            videos.sortedBy { (_, video) -> if (video.videoUrl.contains(PREFERRED_HOST)) 0 else 1 }
        } else {
            videos.sortedByDescending { (serverName, _) -> serverName == preferred }
        }

        return listOf(
            Hoster(
                hosterUrl = "anikura",
                hosterName = "Anikura",
                videoList = ordered.map { it.second },
            ),
        )
    }

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = hoster.videoList ?: emptyList()

    override suspend fun resolveVideo(video: Video): Video? {
        val key = try {
            video.internalData.parseAs<ResolveKey>()
        } catch (e: Exception) {
            Log.w(TAG, "resolveVideo: bad internalData", e)
            return video
        }

        val info = EpisodeUrlInfo(key.animeId, key.episode, key.lang)
        val resolved = fetchSource(info, key.provider, key.referer) ?: return null
        return resolved.copy(
            videoTitle = video.videoTitle.ifBlank { resolved.videoTitle },
            internalData = video.internalData,
        )
    }

    private suspend fun fetchSource(
        info: EpisodeUrlInfo,
        provider: String,
        referer: String,
    ): Video? {
        val url = "$baseUrl/api/watch/sources".toHttpUrl().newBuilder()
            .addQueryParameter("id", info.animeId)
            .addQueryParameter("ep", info.episode.toString())
            .addQueryParameter("lang", info.lang)
            .addQueryParameter("provider", provider)
            .build()

        val stream = try {
            client.get(url, streamHeaders).parseAs<SourceResponseDto>().stream
        } catch (e: Exception) {
            Log.w(TAG, "sources fetch failed for $provider", e)
            return null
        }?.takeIf { it.url.isNotBlank() } ?: return null

        val videoHeaders = headers.newBuilder()
            .add("Referer", referer)
            .add(
                "Accept",
                "image/avif,image/webp,image/apng,image/svg+xml,image/*,video/*,*/*;q=0.8",
            )
            .build()

        val absoluteUrl = if (stream.url.startsWith("/")) "$baseUrl${stream.url}" else stream.url

        return Video(
            videoUrl = absoluteUrl,
            videoTitle = stream.label,
            headers = videoHeaders,
            subtitleTracks = stream.toSubtitleTracks(baseUrl),
            initialized = true,
            internalData = ResolveKey(
                animeId = info.animeId,
                episode = info.episode,
                lang = info.lang,
                provider = provider,
                referer = referer,
            ).toJsonString(),
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_SERVER_EXCLUDE_KEY
            title = PREF_SERVER_EXCLUDE_TITLE
            entries = PREF_SERVER_EXCLUDE_ENTRIES
            entryValues = PREF_SERVER_EXCLUDE_ENTRIES
            setDefaultValue(emptySet<String>())
            summary = PREF_SERVER_EXCLUDE_SUMMARY
        }.also(screen::addPreference)
    }

    private fun serverNameOf(stream: StreamDto): String? {
        when {
            stream.id.startsWith("megaplay") -> return "AniKoto"
            stream.id.startsWith("kaa") -> return "KAA"
        }
        return stream.label.substringBefore(" ·").trim().ifBlank { null }
    }

    private fun providerIdOf(stream: StreamDto): String? = stream.id.substringBefore("-").trim().ifBlank { null }?.let { "$it:1" }

    @Serializable
    private class ResolveKey(
        val animeId: String,
        val episode: Int,
        val lang: String,
        val provider: String,
        val referer: String,
    )

    private class EpisodeUrlInfo(
        val animeId: String,
        val episode: Int,
        val lang: String,
    )

    private fun parseEpisodeUrl(url: String): EpisodeUrlInfo? {
        val match = EPISODE_URL_REGEX.find(url) ?: return null
        return EpisodeUrlInfo(
            animeId = match.groupValues[1],
            episode = match.groupValues[3].toIntOrNull() ?: return null,
            lang = match.groupValues[4],
        )
    }

    companion object {
        private const val TAG = "Anikura"
        private const val MAX_PAGES = 50
        private const val PREFERRED_HOST = "krussdomi.com"

        private val EPISODE_URL_REGEX = Regex("""/watch/(\d+)/([^?]+)\?ep=(\d+)&lang=(sub|dub)""")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Preferred server"
        private const val PREF_SERVER_DEFAULT = ""
        private val PREF_SERVER_ENTRIES = arrayOf("Auto", "AniKoto", "KAA")
        private val PREF_SERVER_VALUES = arrayOf("", "AniKoto", "KAA")

        private const val PREF_SERVER_EXCLUDE_KEY = "excluded_servers"
        private const val PREF_SERVER_EXCLUDE_TITLE = "Exclude servers"
        private val PREF_SERVER_EXCLUDE_ENTRIES = arrayOf("AniKoto", "KAA")
        private const val PREF_SERVER_EXCLUDE_SUMMARY = "Hide videos from the selected servers."
    }
}

private class EpisodeListPayload(
    val episodes: List<EpisodeDto>,
    val thumbnails: Map<String, String>,
    val descriptions: Map<String, String>,
)

private fun org.jsoup.nodes.Document.parseHomeRows(): List<AnimeRowDto> {
    val rows = mutableListOf<AnimeRowDto>()

    try {
        val latest = extractNextJs<AnimeRowDto> { el ->
            val obj = el as? JsonObject ?: return@extractNextJs false
            val eps = obj["episodes"] as? JsonArray ?: return@extractNextJs false
            eps.isNotEmpty()
        }
        if (latest != null) rows += latest
    } catch (_: Exception) {
    }

    for (title in listOf("Popular Shows", "This Season")) {
        val row = try {
            extractNextJs<AnimeRowDto> { el ->
                val obj = el as? JsonObject ?: return@extractNextJs false
                (obj["title"] as? JsonPrimitive)?.content == title
            }
        } catch (_: Exception) {
            null
        }
        if (row != null) rows += row
    }

    return rows
}

private fun org.jsoup.nodes.Document.extractHeroAnime(): AnimeCoreDto {
    val hero = extractNextJs<AnimeHeroPropsDto> { el ->
        val obj = el as? JsonObject ?: return@extractNextJs false
        val inner = obj["anime"] as? JsonObject ?: return@extractNextJs false
        inner.containsKey("id") &&
            inner.containsKey("slug") &&
            inner.containsKey("title")
    } ?: throw IllegalStateException("Could not locate anime detail payload")

    return hero.anime
}

/**
 * The same top-level shape (`episodes` + `hasDub` + `episodeThumbnails`) is
 * emitted twice — once as the Suspense fallback (placeholders, no metadata),
 * once as the resolved list. The predicate rejects the fallback by requiring
 * real data: any auxiliary map populated, or a non-generic title.
 */
private fun org.jsoup.nodes.Document.extractEpisodeList(): EpisodeListPayload {
    val props = extractNextJs<EpisodeListPropsDto> { el ->
        val obj = el as? JsonObject ?: return@extractNextJs false

        if (!obj.containsKey("hasDub") || !obj.containsKey("episodeThumbnails")) {
            return@extractNextJs false
        }

        val eps = obj["episodes"] as? JsonArray ?: return@extractNextJs false
        if (eps.isEmpty()) return@extractNextJs false

        if (eps.size > 1) {
            val hasAuxData =
                (obj["episodeThumbnails"] as? JsonObject)?.isNotEmpty() == true ||
                    (obj["episodeDescriptions"] as? JsonObject)?.isNotEmpty() == true ||
                    (obj["episodeDurations"] as? JsonObject)?.isNotEmpty() == true

            val hasRealTitle = eps.any { ep ->
                val eo = ep as? JsonObject ?: return@any false
                val t = (eo["title"] as? JsonPrimitive)?.content ?: return@any false
                val n = (eo["number"] as? JsonPrimitive)?.content
                t.isNotBlank() && t != "Episode $n"
            }

            return@extractNextJs hasAuxData || hasRealTitle
        }

        val first = eps.first() as? JsonObject ?: return@extractNextJs false
        val title = (first["title"] as? JsonPrimitive)?.content ?: return@extractNextJs false
        val number = (first["number"] as? JsonPrimitive)?.content ?: return@extractNextJs false
        title != "Episode $number"
    }

    return EpisodeListPayload(
        episodes = props?.episodes ?: emptyList(),
        thumbnails = props?.episodeThumbnails ?: emptyMap(),
        descriptions = props?.episodeDescriptions ?: emptyMap(),
    )
}
