package org.compass.cng.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.domain.model.RoutePreview

/** Independent builds stay separate. Prefer the last imported graph, then try overlapping areas. */
internal suspend fun routeWithOfflinePackages(
    datasets: List<InstalledOfflineDataset>,
    points: List<Coordinate>,
    calculate: suspend (InstalledOfflineDataset) -> List<RoutePreview>,
): List<RoutePreview> {
    require(points.size >= 2)
    val candidates = datasets.asReversed().filter { dataset -> points.all(dataset.manifest::contains) }
    check(candidates.isNotEmpty()) { "Nessun pacchetto offline copre l’intero percorso" }
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
