package org.compass.cng.enforcement

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.compass.cng.data.api.ApiClientException
import org.compass.cng.domain.model.Coordinate
import org.compass.cng.navigation.NavigationLocation
import org.compass.cng.navigation.distanceMeters

@Serializable
data class SavedEnforcementPackage(val owner: String, val savedAt: Long, val data: EnforcementPackage)

interface EnforcementCache {
    fun read(): SavedEnforcementPackage?
    fun write(value: SavedEnforcementPackage)
    fun clear()
}

data class EnforcementMapState(
    val data: EnforcementPackage? = null,
    val saved: Boolean = false,
    val unavailable: Boolean = false,
)

/** One shared consumer for both maps. No location subscription and no driving-alert authority. */
class EnforcementRepository(
    private val owner: () -> String,
    private val fetch: suspend (Coordinate) -> EnforcementPackage,
    private val cache: EnforcementCache,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(EnforcementMapState())
    val state = mutable.asStateFlow()
    private val lock = Mutex()
    private var currentOwner: String? = null
    private var stored: SavedEnforcementPackage? = null
    private var lastAttempt: Long? = null
    private var fromDiskOrFailed = false

    // Caller runs on IO; cache and HTTP work must never block the map/UI thread.
    suspend fun update(location: NavigationLocation?) = lock.withLock {
        val scope = owner()
        val time = now()
        if (scope != currentOwner) {
            currentOwner = scope
            lastAttempt = null
            stored = runCatching { cache.read() }.getOrNull()?.takeIf { it.owner == scope }
            fromDiskOrFailed = stored != null
            mutable.value = EnforcementMapState()
        }
        if (stored != null && time - stored!!.savedAt !in 0..CACHE_LIFETIME) {
            stored = null
            runCatching { cache.clear() }
        }
        if (location == null || time - location.timestampEpochMillis !in -10_000..90_000 ||
            !location.accuracyMeters.isFinite() || location.accuracyMeters !in 0.0..100.0) {
            mutable.value = EnforcementMapState()
            return@withLock
        }
        val position = location.coordinate
        fun visible() = stored?.takeIf {
            distanceMeters(position, Coordinate(it.data.latitude, it.data.longitude)) <=
                it.data.radiusMeters
        }?.data
        mutable.value = EnforcementMapState(visible(), fromDiskOrFailed, mutable.value.unavailable)
        val previous = stored
        val needsRefresh = previous == null || time - previous.savedAt >= REFRESH_INTERVAL ||
            distanceMeters(position, Coordinate(previous.data.latitude, previous.data.longitude)) >=
                REFRESH_DISTANCE
        val attemptAge = lastAttempt?.let { time - it }
        if (!needsRefresh || (attemptAge != null && attemptAge in 0 until RETRY_INTERVAL)) {
            return@withLock
        }
        lastAttempt = time
        try {
            val result = fetch(position).validated()
            // A profile change while the request was running must not publish another server's data.
            if (owner() != scope) {
                stored = null
                mutable.value = EnforcementMapState()
                return@withLock
            }
            require(distanceMeters(position, Coordinate(result.latitude, result.longitude)) < 10)
            stored = SavedEnforcementPackage(scope, now(), result)
            runCatching { cache.write(stored!!) }
            fromDiskOrFailed = false
            mutable.value = EnforcementMapState(result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (owner() != scope) {
                stored = null
                mutable.value = EnforcementMapState()
                return@withLock
            }
            if (error is ApiClientException.Http && error.statusCode in setOf(401, 403)) {
                stored = null
                runCatching { cache.clear() }
            }
            fromDiskOrFailed = true
            mutable.value = EnforcementMapState(visible(), saved = true, unavailable = true)
        }
    }

    companion object {
        const val CACHE_LIFETIME = 24 * 60 * 60 * 1000L
        const val REFRESH_INTERVAL = 15 * 60 * 1000L
        const val RETRY_INTERVAL = 60 * 1000L
        const val REFRESH_DISTANCE = 1000.0
        fun ownerKey(baseUrl: String, username: String): String = MessageDigest.getInstance("SHA-256")
            .digest("$baseUrl\n$username".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
