package com.feedpilot.client.data.repository

import android.content.Context
import android.content.ContextWrapper
import com.feedpilot.client.data.remote.ApiService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

class BridgeModeSettingsTest {
    @Test fun bridgeChoicePersistsAndDoesNotChangeOtherSettings() = runBlocking {
        val directory = Files.createTempDirectory("feedpilot-bridge-settings").toFile()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) {
                _, _, _ -> error("This test must not call the backend")
        } as ApiService
        val settings = SettingsRepository(context, api)
        assertTrue(settings.settings.first().bridgeEnabled)
        settings.setNotifications(false)
        settings.setBridgeEnabled(false)
        val restored = SettingsRepository(context, api)
        assertFalse(restored.settings.first().bridgeEnabled)
        assertFalse(restored.settings.first().notificationsEnabled)
        restored.setBridgeEnabled(true)
        assertTrue(settings.settings.first().bridgeEnabled)
        assertFalse(settings.settings.first().notificationsEnabled)
    }
}
