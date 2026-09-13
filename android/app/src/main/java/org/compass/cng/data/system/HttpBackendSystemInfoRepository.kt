package org.compass.cng.data.system

import org.compass.cng.data.api.CompassApiClient
import org.compass.cng.domain.system.BackendSystemInfo
import org.compass.cng.domain.system.BackendSystemInfoRepository

class HttpBackendSystemInfoRepository(
    private val apiClient: CompassApiClient,
) : BackendSystemInfoRepository {
    override suspend fun load(): BackendSystemInfo {
        val info = apiClient.getBackendSystemInfo()
        return BackendSystemInfo(
            service = info.service,
            version = info.version,
            status = info.status,
            trafficEnabled = info.trafficEnabled,
            trafficProvider = info.trafficProvider,
            trafficStatus = info.trafficStatus,
            trafficAwareRouting = info.trafficAwareRouting,
            valhallaTilesetVersion = info.valhallaTilesetVersion,
            trafficMappingVersion = info.trafficMappingVersion,
        )
    }
}
