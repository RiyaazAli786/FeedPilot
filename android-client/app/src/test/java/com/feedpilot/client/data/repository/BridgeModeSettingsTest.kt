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

    @Test fun floatingBubbleChoicePersistsAndDoesNotAffectBridgeMode() = runBlocking {
        val directory = Files.createTempDirectory("feedpilot-bubble-settings").toFile()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) {
            _, _, _ -> error("This test must not call the backend")
        } as ApiService
        val settings = SettingsRepository(context, api)
        assertFalse(settings.settings.first().bridgeFloatingBubbleEnabled)
        settings.setBridgeFloatingBubbleEnabled(true)
        val restored = SettingsRepository(context, api)
        assertTrue(restored.settings.first().bridgeFloatingBubbleEnabled)
        assertTrue(restored.settings.first().bridgeEnabled)
        restored.setBridgeFloatingBubbleEnabled(false)
        assertFalse(settings.settings.first().bridgeFloatingBubbleEnabled)
    }

    @Test fun feedWarmupChoicePersistsAndDefaultsToFalse() = runBlocking {
        val directory = Files.createTempDirectory("feedpilot-warmup-settings").toFile()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) {
            _, _, _ -> error("This test must not call the backend")
        } as ApiService
        val settings = SettingsRepository(context, api)
        assertFalse(settings.settings.first().feedWarmupEnabled)
        assertEquals(1, settings.settings.first().warmupIntervalHours)
        assertTrue(settings.settings.first().warmupNightModeEnabled)
        assertEquals(10, settings.settings.first().warmupDailyLimit)
        assertTrue(settings.settings.first().warmupLikeEnabled)
        assertTrue(settings.settings.first().warmupSaveEnabled)
        assertTrue(settings.settings.first().warmupFollowEnabled)
        assertFalse(settings.settings.first().warmupRepostEnabled)
        assertTrue(settings.settings.first().warmupStoryViewEnabled)

        settings.setFeedWarmupEnabled(true)
        settings.setWarmupIntervalHours(3)
        settings.setWarmupNightMode(false)
        settings.setWarmupDailyLimit(15)
        settings.setWarmupRepostEnabled(true)

        val restored = SettingsRepository(context, api)
        assertTrue(restored.settings.first().feedWarmupEnabled)
        assertEquals(3, restored.settings.first().warmupIntervalHours)
        assertFalse(restored.settings.first().warmupNightModeEnabled)
        assertEquals(15, restored.settings.first().warmupDailyLimit)
        assertTrue(restored.settings.first().warmupRepostEnabled)

        restored.setFeedWarmupEnabled(false)
        assertFalse(settings.settings.first().feedWarmupEnabled)
    }
}
