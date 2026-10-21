/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.slider

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.os.UserManager
import android.os.Vibrator
import android.util.Log
import android.widget.Toast

object Action {
    const val NONE = -1
    const val SILENT = 0 // AudioManager.RINGER_MODE_SILENT
    const val VIBRATE = 1 // AudioManager.RINGER_MODE_VIBRATE
    const val NORMAL = 2 // AudioManager.RINGER_MODE_NORMAL
    const val DND_PRIORITY = 3
    const val DND_TOTAL = 4
    const val DND_ALARMS = 5
    const val TORCH_ON = 6
    const val TORCH_OFF = 7
    const val BATTERY_SAVER_ON = 8
    const val BATTERY_SAVER_OFF = 9

    // Actions that happen once rather than selecting a state. TORCH_ON is
    // one, since a flashlight lighting itself at boot is not a held state.
    private val ONE_SHOT = setOf(TORCH_ON)

    fun isStateful(action: Int) = action !in ONE_SHOT

    // An action a restriction takes away from the user in front of the screen,
    // who is the user it would be carried out for.
    private fun restriction(action: Int): String? =
        when (action) {
            SILENT,
            VIBRATE,
            NORMAL -> UserManager.DISALLOW_ADJUST_VOLUME
            else -> null
        }

    fun isRestricted(context: Context, action: Int): Boolean {
        val restriction = restriction(action) ?: return false
        val manager = context.getSystemService(UserManager::class.java) ?: return false
        return manager.hasUserRestrictionForUser(
            restriction,
            UserHandle.of(ActivityManager.getCurrentUser()),
        )
    }

    private fun requiredFeature(action: Int): String? =
        when (action) {
            TORCH_ON,
            TORCH_OFF -> PackageManager.FEATURE_CAMERA_FLASH
            else -> null
        }

    fun isAvailable(context: Context, action: Int): Boolean {
        val feature = requiredFeature(action)
        if (feature != null && !context.packageManager.hasSystemFeature(feature)) {
            return false
        }
        if (isRestricted(context, action)) {
            return false
        }
        // A vibrator is not a system feature, and AudioService silences a
        // device that has none rather than refusing the mode.
        if (action == VIBRATE) {
            return context.getSystemService(Vibrator::class.java)?.hasVibrator() == true
        }
        return true
    }

    fun label(context: Context, action: Int): String? {
        val values = context.resources.getStringArray(R.array.action_values)
        val index = values.indexOf(action.toString())
        return if (index < 0) {
            null
        } else {
            context.resources.getStringArray(R.array.action_entries)[index]
        }
    }
}

class Actions(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)!!
    private val notificationManager = context.getSystemService(NotificationManager::class.java)!!
    private val powerManager = context.getSystemService(PowerManager::class.java)!!
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    fun apply(action: Int) {
        // A choice outlives the restriction that takes it away, so what the
        // screen stopped offering is also refused here.
        if (Action.isRestricted(context, action)) {
            Log.w(TAG, "action $action is not allowed for the current user")
            return
        }

        try {
            dispatch(action)
        } catch (e: Exception) {
            Log.e(TAG, "action $action failed", e)
            reportFailure(action)
        }
    }

    private fun dispatch(action: Int) {
        when (action) {
            Action.NONE -> return
            // The filter and the ringer mode are two writes, so a failure on
            // the second leaves the first applied.
            Action.SILENT,
            Action.VIBRATE,
            Action.NORMAL -> {
                notificationManager.setInterruptionFilter(
                    NotificationManager.INTERRUPTION_FILTER_ALL
                )
                audioManager.ringerModeInternal = action
            }
            Action.DND_PRIORITY,
            Action.DND_TOTAL,
            Action.DND_ALARMS -> {
                audioManager.ringerModeInternal = AudioManager.RINGER_MODE_NORMAL
                notificationManager.setInterruptionFilter(action - 1)
            }
            Action.TORCH_ON -> setTorch(true)
            Action.TORCH_OFF -> setTorch(false)
            Action.BATTERY_SAVER_ON -> powerManager.setPowerSaveModeEnabled(true)
            Action.BATTERY_SAVER_OFF -> powerManager.setPowerSaveModeEnabled(false)
        }
    }

    private fun reportFailure(action: Int) {
        val label = Action.label(context, action) ?: return
        handler.post {
            Toast.makeText(
                    context,
                    context.getString(R.string.action_failed, label),
                    Toast.LENGTH_SHORT,
                )
                .show()
        }
    }

    private fun setTorch(enabled: Boolean) {
        val manager = cameraManager ?: error("no camera on this device")
        val id = torchCameraId ?: error("no camera with a flash")
        manager.setTorchMode(id, enabled)
    }

    private val torchCameraId: String? by lazy {
        cameraManager?.cameraIdList?.firstOrNull {
            cameraManager
                .getCameraCharacteristics(it)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }

    companion object {
        private const val TAG = "LineageSlider"
    }
}
