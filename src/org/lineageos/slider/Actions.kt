/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.slider

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.SearchManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.ColorDisplayManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.os.UserManager
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import com.android.internal.view.RotationPolicy

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
    const val VOICE_RECORD_START = 10
    const val VOICE_RECORD_STOP = 11
    const val AIRPLANE_ON = 12
    const val AIRPLANE_OFF = 13
    const val WIFI_ON = 14
    const val WIFI_OFF = 15
    const val BLUETOOTH_ON = 16
    const val BLUETOOTH_OFF = 17
    const val MOBILE_DATA_ON = 18
    const val MOBILE_DATA_OFF = 19
    const val AUTO_ROTATE_ON = 20
    const val AUTO_ROTATE_OFF = 21
    const val LOCATION_ON = 22
    const val LOCATION_OFF = 23
    const val LAUNCH_CAMERA = 24
    const val LAUNCH_ASSISTANT = 25
    const val MEDIA_PAUSE = 26
    const val MEDIA_PLAY = 27
    const val NIGHT_LIGHT_ON = 28
    const val NIGHT_LIGHT_OFF = 29

    // Actions that happen once rather than selecting a state. TORCH_ON is
    // one, since a flashlight lighting itself at boot is not a held state.
    private val ONE_SHOT =
        setOf(
            TORCH_ON,
            VOICE_RECORD_START,
            VOICE_RECORD_STOP,
            LAUNCH_CAMERA,
            LAUNCH_ASSISTANT,
            MEDIA_PAUSE,
            MEDIA_PLAY,
        )

    fun isStateful(action: Int) = action !in ONE_SHOT

    // An action a restriction takes away from the user in front of the screen,
    // who is the user it would be carried out for.
    private fun restriction(action: Int): String? =
        when (action) {
            SILENT,
            VIBRATE,
            NORMAL -> UserManager.DISALLOW_ADJUST_VOLUME
            AIRPLANE_ON,
            AIRPLANE_OFF -> UserManager.DISALLOW_AIRPLANE_MODE
            WIFI_ON,
            WIFI_OFF -> UserManager.DISALLOW_CHANGE_WIFI_STATE
            BLUETOOTH_ON,
            BLUETOOTH_OFF -> UserManager.DISALLOW_CONFIG_BLUETOOTH
            MOBILE_DATA_ON,
            MOBILE_DATA_OFF -> UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS
            LOCATION_ON,
            LOCATION_OFF -> UserManager.DISALLOW_CONFIG_LOCATION
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
            VOICE_RECORD_START,
            VOICE_RECORD_STOP -> PackageManager.FEATURE_MICROPHONE
            WIFI_ON,
            WIFI_OFF -> PackageManager.FEATURE_WIFI
            BLUETOOTH_ON,
            BLUETOOTH_OFF -> PackageManager.FEATURE_BLUETOOTH
            MOBILE_DATA_ON,
            MOBILE_DATA_OFF -> PackageManager.FEATURE_TELEPHONY_DATA
            LOCATION_ON,
            LOCATION_OFF -> PackageManager.FEATURE_LOCATION
            LAUNCH_CAMERA -> PackageManager.FEATURE_CAMERA_ANY
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
        if (action == VOICE_RECORD_START || action == VOICE_RECORD_STOP) {
            return VoiceRecorder.isAvailable(context)
        }
        if (action == LAUNCH_ASSISTANT) {
            return hasAssistant(context)
        }
        if (action == AUTO_ROTATE_ON || action == AUTO_ROTATE_OFF) {
            return RotationPolicy.isRotationSupported(context)
        }
        if (action == LAUNCH_CAMERA) {
            return handles(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        }
        if (action == NIGHT_LIGHT_ON || action == NIGHT_LIGHT_OFF) {
            return ColorDisplayManager.isNightDisplayAvailable(context)
        }
        return true
    }

    private fun handles(context: Context, intent: Intent) =
        context.packageManager.resolveActivityAsUser(intent, 0, ActivityManager.getCurrentUser()) !=
            null

    // An assistant is set as a VoiceInteractionService, or, for a legacy one,
    // as an activity answering ACTION_ASSIST.
    private fun hasAssistant(context: Context): Boolean {
        val configured =
            Settings.Secure.getStringForUser(
                context.contentResolver,
                Settings.Secure.ASSISTANT,
                UserHandle.USER_CURRENT,
            )
        if (!configured.isNullOrEmpty()) {
            return true
        }
        return handles(context, Intent(Intent.ACTION_ASSIST))
    }

    fun grantIntent(context: Context, action: Int): Intent? =
        when (action) {
            VOICE_RECORD_START,
            VOICE_RECORD_STOP -> VoiceRecorder.grantIntent(context)
            else -> null
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
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private val colorDisplayManager = context.getSystemService(ColorDisplayManager::class.java)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val voiceRecorder = VoiceRecorder(context)
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
            Action.VOICE_RECORD_START -> voiceRecorder.start()
            Action.VOICE_RECORD_STOP -> voiceRecorder.stop()
            Action.AIRPLANE_ON -> requireConnectivity().setAirplaneMode(true)
            Action.AIRPLANE_OFF -> requireConnectivity().setAirplaneMode(false)
            Action.WIFI_ON -> setWifi(true)
            Action.WIFI_OFF -> setWifi(false)
            Action.BLUETOOTH_ON -> setBluetooth(true)
            Action.BLUETOOTH_OFF -> setBluetooth(false)
            Action.MOBILE_DATA_ON -> requireTelephony().setDataEnabled(true)
            Action.MOBILE_DATA_OFF -> requireTelephony().setDataEnabled(false)
            Action.AUTO_ROTATE_ON -> setAutoRotate(true)
            Action.AUTO_ROTATE_OFF -> setAutoRotate(false)
            Action.LOCATION_ON -> setLocation(true)
            Action.LOCATION_OFF -> setLocation(false)
            Action.LAUNCH_CAMERA -> launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            Action.LAUNCH_ASSISTANT -> launchAssist()
            Action.MEDIA_PAUSE -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            Action.MEDIA_PLAY -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            Action.NIGHT_LIGHT_ON -> requireColorDisplay().setNightDisplayActivated(true)
            Action.NIGHT_LIGHT_OFF -> requireColorDisplay().setNightDisplayActivated(false)
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

    private fun requireWifi() = wifiManager ?: error("no Wi-Fi on this device")

    private fun requireBluetooth() = bluetoothAdapter ?: error("no Bluetooth on this device")

    private fun requireTelephony() = telephonyManager ?: error("no telephony on this device")

    private fun requireColorDisplay() = colorDisplayManager ?: error("no ColorDisplayManager")

    private fun requireConnectivity() = connectivityManager ?: error("no ConnectivityManager")

    private fun setTorch(enabled: Boolean) {
        val manager = cameraManager ?: error("no camera on this device")
        val id = torchCameraId ?: error("no camera with a flash")
        manager.setTorchMode(id, enabled)
    }

    // Wi-Fi refuses by return value rather than by throwing.
    private fun setWifi(enabled: Boolean) {
        if (!requireWifi().setWifiEnabled(enabled)) {
            error("Wi-Fi refused the change")
        }
    }

    private fun setBluetooth(enabled: Boolean) {
        val adapter = requireBluetooth()
        val changed = if (enabled) adapter.enable() else adapter.disable()
        if (!changed) {
            error("Bluetooth refused the change")
        }
    }

    private fun setAutoRotate(enabled: Boolean) {
        RotationPolicy.setRotationLock(context, !enabled, TAG)
    }

    private fun setLocation(enabled: Boolean) {
        val manager = locationManager ?: error("no location on this device")
        manager.setLocationEnabledForUser(enabled, UserHandle.of(ActivityManager.getCurrentUser()))
    }

    private fun sendMediaKey(keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    // An activity intent does not reach a VoiceInteractionService, and
    // SearchManagerService drops the user it is given, so none is passed.
    private fun launchAssist() {
        val manager =
            context.getSystemService(SearchManager::class.java) ?: error("no SearchManager")
        manager.launchAssist(null)
    }

    // A package installed for the user the app is started for is not one
    // installed for the user this process belongs to.
    private fun packagesFor(userId: Int) =
        context.createContextAsUser(UserHandle.of(userId), 0).packageManager

    // Every start the platform resolves gets MATCH_DEFAULT_ONLY, which drops a
    // launcher filter that does not name CATEGORY_DEFAULT, so naming the
    // component is what makes a category intent start at all. A start the
    // platform can resolve itself is left to it, chooser included.
    private fun launch(intent: Intent, userId: Int = ActivityManager.getCurrentUser()) {
        val resolved = intent.component ?: resolveComponent(intent, userId)
        if (resolved != null) {
            intent.component = resolved
        }

        context.startActivityAsUser(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            UserHandle.of(userId),
        )
    }

    // Nothing is named when the platform can resolve the start itself, and a
    // tie it cannot resolve is settled here rather than dropped.
    private fun resolveComponent(intent: Intent, userId: Int): ComponentName? {
        val packageManager = packagesFor(userId)
        if (handlers(packageManager, intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()) {
            return null
        }

        val handlers = handlers(packageManager, intent, 0)
        if (handlers.isEmpty()) {
            error("nothing handles ${intent.action}")
        }
        return handlers.first().activityInfo.let { ComponentName(it.packageName, it.name) }
    }

    // An activity no other app could start is not one to name, which the
    // system uid is otherwise allowed to do.
    private fun handlers(packageManager: PackageManager, intent: Intent, flags: Int) =
        packageManager.queryIntentActivities(intent, flags).filter { it.activityInfo.exported }

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
