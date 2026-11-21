package org.compass.cng.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.RoutePreview

/** Combine regions only within the same build; never join independent graph IDs. */
internal suspend fun routeWithOfflinePackages(
    datasets: List<InstalledOfflineDataset>,
    points: List<Coordinate>,
    calculate: suspend (List<InstalledOfflineDataset>) -> List<RoutePreview>,
): List<RoutePreview> {
    require(points.size >= 2)
    val candidates = datasets.asReversed().groupBy {
        it.manifest.graphFamily ?: it.directory.name
    }.values.filter { group -> points.all { point -> group.any { it.manifest.contains(point) } } }
    check(candidates.isNotEmpty()) { "Nessun set di mappe compatibili copre l’intero percorso" }
    var failure: Exception? = null
    for (dataset in candidates) {
        currentCoroutineContext().ensureActive()
        try {
            val result = calculate(dataset)
            currentCoroutineContext().ensureActive()
            return result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure = error
        }
    }
    throw IllegalStateException("Percorso non disponibile nei pacchetti offline installati", failure)
}
