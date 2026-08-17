package com.feedpilot.client.security

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import com.feedpilot.client.data.repository.AppGuardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import kotlin.system.exitProcess

@HiltViewModel
class AppGuardViewModel @Inject constructor(
    private val appGuardRepository: AppGuardRepository
) : ViewModel() {

    val state: StateFlow<AppGuardState> = appGuardRepository.guardState

    fun exitApp(activity: Activity?) {
        try {
            activity?.finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(0)
        } catch (e: Exception) {
            Log.e("AppGuardViewModel", "Error terminating app process", e)
        }
    }

    fun joinTelegram(context: Context) {
        val tgUrl = state.value.tgUrl
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(tgUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("AppGuardViewModel", "Error opening Telegram link", e)
        }
    }
}
