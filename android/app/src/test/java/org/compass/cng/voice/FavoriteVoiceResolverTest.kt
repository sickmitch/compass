package org.compass.cng.voice

import org.compass.cng.domain.favorite.FavoritePlace
import org.compass.cng.domain.favorite.PARKING_PLACE_ID
import org.compass.cng.domain.model.Coordinate
import org.junit.Assert.*
import org.junit.Test

class FavoriteVoiceResolverTest {
    private fun place(id: String, name: String) = FavoritePlace(id, name, Coordinate(45.0, 9.0))
    private fun resolve(spoken: String, places: List<FavoritePlace>) =
        FavoriteVoiceResolver.resolve(spoken, places)

    @Test fun savedNamesThatLookLikeCommandsHavePrecedence() {
        val names = listOf(place("a", "Vai col vento"), place("b", "Vai"))
        assertEquals(names[0], resolve("Vai col vento", names).selected)
        assertEquals(names[1], resolve("Vai", names).selected)
    }

    @Test fun usesTheSavedCatalogForDifferentCommandsNamesAndArticles() {
        val catalog = listOf(place("a", "Casa mamma"), place("b", "Officina Rossi"),
            place("c", "Palestra"), place("d", "Studio 2"), place("e", "Caffè D’Angelo"))
        for ((spoken, expected) in listOf(
            "Porta mi a casa mamma" to catalog[0], "Vai da Officina Rossi" to catalog[1],
            "Accompagna mi alla mia palestra" to catalog[2], "Vorrei andare allo studio due" to catalog[3],
            "Guida mi al caffè d'angelo" to catalog[4], "Avvia la navigazione verso Officina Rossi" to catalog[1],
            "Raggiungi il preferito Casa mamma" to catalog[0], "Mostra mi la palestra" to catalog[2],
        )) assertEquals(spoken, expected, resolve(spoken, catalog).selected)
    }

    @Test fun splitOrJoinedWordsMatchAnyFavoriteNotJustKnownCommands() {
        val catalog = listOf(place("a", "Bar Roma"), place("b", "Officina"), place("c", "Casa mamma"))
        assertEquals(catalog[0], resolve("Portami al barroma", catalog).selected)
        assertEquals(catalog[1], resolve("Vai all'offi cina", catalog).selected)
        assertEquals(catalog[2], resolve("Portami a casamamma", catalog).selected)
    }

    @Test fun collisionsAndCompetingContextInterpretationsRequireSelection() {
        val names = listOf(place("a", "Bar Roma"), place("b", "BarRoma"))
        assertEquals(2, resolve("bar ro ma", names).candidates.size)
        assertNull(resolve("bar ro ma", names).selected)
        val interpretations = listOf(place("a", "Lavoro"), place("b", "Voro"))
        assertNull(resolve("la voro", interpretations).selected)
        assertEquals(2, resolve("la voro", interpretations).candidates.size)
        val numbers = listOf(place("a", "Studio due"), place("b", "Studio 2"))
        assertNull(resolve("studio due", numbers).selected)
    }

    @Test fun minorSpellingErrorsOfferCandidatesButNeverAutoSelect() {
        val names = listOf(place("a", "Palestra"), place("b", "Officina Rossi"), place("c", "Casa mamma"))
        for ((spoken, expected) in listOf("Palesra" to names[0], "Oficina Rossi" to names[1],
            "Casa mama" to names[2])) {
            val match = resolve(spoken, names)
            assertTrue(spoken, expected in match.candidates)
            assertNull(match.selected)
        }
    }

    @Test fun partialNamesAndShortTyposAreNotConfidentMatches() {
        val names = listOf(place("a", "Casa mamma"), place("b", "Casa mare"), place("c", "Bar"))
        assertEquals(2, resolve("casa", names).candidates.size)
        assertNull(resolve("casa", names).selected)
        assertTrue(resolve("car", names).candidates.isEmpty())
        assertTrue(resolve("farmacia Verona", names).candidates.isEmpty())
    }

    @Test fun unknownExplicitFavoriteRemainsAnExplicitLocalRequest() {
        val names = listOf(place("a", "Palestra"))
        val missing = resolve("Vai al mio posto preferito xyz", names)
        assertTrue(missing.explicitFavoriteRequest)
        assertTrue(missing.candidates.isEmpty())
        assertTrue(resolve("Mostrami i preferiti", names).explicitFavoriteRequest)
        assertFalse(resolve("farmacia", names).explicitFavoriteRequest)
    }

    @Test fun parkingAliasUsesExistingSavedParkingAndHandlesCollisions() {
        val parking = place(PARKING_PLACE_ID, "Via Roma 4")
        assertEquals(parking, resolve("Portami al mio parcheggio", listOf(parking)).selected)
        assertNull(resolve("parcheggio", listOf(parking, place("other", "Parcheggio"))).selected)
    }
}
