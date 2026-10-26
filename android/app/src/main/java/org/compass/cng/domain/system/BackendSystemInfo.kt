package org.compass.cng.domain.system

data class BackendSystemInfo(
    val service: String,
    val version: String,
    val status: String,
)

interface BackendSystemInfoRepository {
    suspend fun load(): BackendSystemInfo
}

class InMemoryBackendSystemInfoRepository(
    private val value: BackendSystemInfo? = null,
) : BackendSystemInfoRepository {
    override suspend fun load(): BackendSystemInfo = value
        ?: error("backend system information is unavailable")
}
