package eu.kanade.tachiyomi.animeextension.en.anikura

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

/**
 * Verified against the /browse URL params emitted by the site's shared
 * `78632` module: sort, status, q. Status ids come from the site's own
 * `13067` module.
 *
 * Year filtering is deliberately omitted — `AnimeFilter.Text` is abstract in
 * this SDK and cannot be instantiated directly.
 */
object Filters {

    val FILTER_LIST get() = AnimeFilterList(
        SortFilter(),
        StatusFilter(),
    )

    class SortFilter :
        AnimeFilter.Select<String>(
            "Sort by",
            arrayOf("Popular", "Top rated", "Newest"),
        ) {
        val selected get() = state
    }

    class StatusFilter :
        AnimeFilter.Select<String>(
            "Status",
            arrayOf("Any", "Releasing", "Finished", "Not yet aired"),
        ) {
        val value: String?
            get() = when (state) {
                1 -> "releasing"
                2 -> "finished"
                3 -> "not_yet_aired"
                else -> null
            }
    }
}
