package org.compass.cng.voice

import org.compass.cng.domain.favorite.FavoritePlace
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class FavoriteVoiceResolution(
    val candidates: List<FavoritePlace> = emptyList(),
    val selected: FavoritePlace? = null,
    val explicitFavoriteRequest: Boolean = false,
)

/** Resolves against the user's actual catalog, never a fixed list of destination names. */
object FavoriteVoiceResolver {
    private val context = Regex("^(?:(?:il|lo|la|i|gli|le|un|una|mio|mia|miei|mie) +|l['’] *)")
    private val favoriteContext = Regex("^(?:(?:posto|luogo) +)?preferit[oaie](?: +|$)")

    fun resolve(spoken: String, places: List<FavoritePlace>): FavoriteVoiceResolution {
        val names = places.associateWith { place ->
            listOfNotNull(canonical(place.name), "parcheggio".takeIf { place.isParking })
        }
        // A favorite itself may start with a command word, e.g. a business called "Vai col vento".
        val raw = canonical(spoken)
        val rawExact = places.filter { raw.isNotEmpty() && raw in names.getValue(it) }
        if (rawExact.isNotEmpty()) return FavoriteVoiceResolution(rawExact, rawExact.singleOrNull())
        var remaining = ItalianVoiceQuery.key(ItalianVoiceQuery.normalize(spoken))
        val variants = linkedSetOf(canonical(remaining))
        var explicit = false
        repeat(5) {
            val favoritePrefix = favoriteContext.find(remaining)
            val next = if (favoritePrefix != null) {
                explicit = true
                remaining.removeRange(favoritePrefix.range)
            } else remaining.replaceFirst(context, "")
            if (next != remaining) { remaining = next.trim(); variants.add(canonical(remaining)) }
        }
        val nonempty = variants.filter { it.isNotEmpty() }
        fun result(matches: List<FavoritePlace>, certain: Boolean) = FavoriteVoiceResolution(
            candidates = matches, selected = matches.singleOrNull()?.takeIf { certain },
            explicitFavoriteRequest = explicit)

        // Prefer a complete saved name over contextual interpretations, then over spelling guesses.
        val literal = variants.first()
        val exact = places.filter { place -> literal.isNotEmpty() && literal in names.getValue(place) }
        if (exact.isNotEmpty()) return result(exact, certain = true)
        val compact = nonempty.map { it.replace(" ", "") }
        val spacing = places.filter { place -> names.getValue(place).any { name ->
            name.replace(" ", "") in compact
        } }
        if (spacing.isNotEmpty()) return result(spacing, certain = true)

        val partial = places.filter { place -> names.getValue(place).any { name -> nonempty.any { part ->
            part.length >= 3 && " $name ".contains(" $part ")
        } } }
        if (partial.isNotEmpty()) return result(partial, certain = false)

        val approximate = places.mapNotNull { place ->
            val distance = names.getValue(place).flatMap { name -> compact.mapNotNull { candidate ->
                val saved = name.replace(" ", "")
                val length = max(candidate.length, saved.length)
                val limit = if (min(candidate.length, saved.length) >= 8) 2 else 1
                if (min(candidate.length, saved.length) < 4 || abs(candidate.length - saved.length) > limit) null
                else editDistance(candidate, saved).takeIf { it <= limit && it * 4 <= length }
            } }.minOrNull()
            distance?.let { place to it }
        }.sortedWith(compareBy<Pair<FavoritePlace, Int>> { it.second }
            .thenBy { canonical(it.first.name) }.thenBy { it.first.id }).map { it.first }
        return result(approximate, certain = false)
    }

    private fun canonical(text: String): String = ItalianVoiceQuery.key(text)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(Regex(" +"))
        .joinToString(" ") { ItalianVoiceQuery.civic(it)?.toString() ?: it }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) current[j + 1] = minOf(
                current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = current
        }
        return previous[b.length]
    }
}
