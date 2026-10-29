package com.feedpilot.client.data.repository

import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.dto.PagedDevicesDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepository @Inject constructor(
    private val api: ApiService
) {
    suspend fun getDevices(
        search: String? = null,
        page: Int = 1,
        pageSize: Int = 20
    ): Resource<PagedDevicesDto> = withContext(Dispatchers.IO) {
        try {
            val result = api.getDevices(
                search = search?.takeIf { it.isNotBlank() },
                page = page,
                pageSize = pageSize
            )
            Resource.Success(result)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Failed to load registered devices")
        }
    }
}
