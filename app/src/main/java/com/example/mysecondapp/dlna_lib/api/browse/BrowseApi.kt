package com.example.mysecondapp.dlna_lib.api.browse

import com.example.mysecondapp.dlna_lib.api.device.DeviceId
import com.example.mysecondapp.dlna_lib.api.media.ContainerId

interface BrowseApi {

    /**
     * Browses a specific container (folder) on a remote device.
     * * @param deviceId The ID of the Media Server to browse.
     * @param containerId The ID of the folder (use "0" for root).
     * @param startIndex Pagination offset.
     * @param count Number of items to fetch.
     */
    suspend fun browse(
        deviceId: DeviceId,
        containerId: ContainerId,
        startIndex: Int,
        count: Int
    ): BrowseResult

    /**
     * Shortcut to browse the root directory of a device.
     */
    suspend fun browseRoot(deviceId: DeviceId): BrowseResult
}