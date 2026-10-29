package com.feedpilot.client.data.repository

import com.feedpilot.client.common.Resource
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.dto.DeviceListItemDto
import com.feedpilot.client.data.remote.dto.PagedDevicesDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class DeviceRepositoryTest {

    @Test
    fun `getDevices returns success with paged devices`() = runBlocking {
        val expectedDevice = DeviceListItemDto(
            id = "test-id",
            appId = "com.feedpilot.client",
            deviceId = "device-123",
            installationId = "install-123",
            deviceModel = "Pixel 8",
            registeredAt = "2026-10-29T12:00:00Z",
            lastSeenAt = "2026-10-29T12:05:00Z",
            isOnline = true,
            bridgeEnabled = true
        )
        val api = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java)
        ) { _, method, _ ->
            if (method.name == "getDevices") {
                PagedDevicesDto(
                    items = listOf(expectedDevice),
                    total = 1,
                    page = 1,
                    pageSize = 20
                )
            } else {
                error("Unexpected call: ${method.name}")
            }
        } as ApiService

        val repository = DeviceRepository(api)
        val result = repository.getDevices(search = "Pixel", page = 1, pageSize = 20)

        assertTrue(result is Resource.Success)
        val data = (result as Resource.Success).data
        assertEquals(1, data.total)
        assertEquals(1, data.items.size)
        assertEquals("Pixel 8", data.items[0].deviceModel)
        assertTrue(data.items[0].isOnline)
        assertTrue(data.items[0].bridgeEnabled)
    }

    @Test
    fun `getDevices returns error when api throws exception`() = runBlocking {
        val api = Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java)
        ) { _, method, _ ->
            if (method.name == "getDevices") {
                throw RuntimeException("Network unreachable")
            } else {
                error("Unexpected call: ${method.name}")
            }
        } as ApiService

        val repository = DeviceRepository(api)
        val result = repository.getDevices()

        assertTrue(result is Resource.Error)
        assertEquals("Network unreachable", (result as Resource.Error).message)
    }
}
