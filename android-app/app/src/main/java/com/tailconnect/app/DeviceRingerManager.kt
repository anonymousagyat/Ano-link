package com.tailconnect.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object DeviceRingerManager {
    private const val TAG = "DeviceRingerManager"

    @Volatile
    var isRinging = false
        private set

    private var mediaPlayer: MediaPlayer? = null
    private var originalAlarmVolume: Int = -1
    private var originalRingerMode: Int = -1
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoStopRunnable: Runnable? = null

    fun startRinging(context: Context, onStarted: (() -> Unit)? = null) {
        if (isRinging) {
            Log.d(TAG, "Device is already ringing.")
            onStarted?.invoke()
            return
        }

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 1. Save original volume & ringer state
            originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            originalRingerMode = audioManager.ringerMode

            // 2. Maximize STREAM_ALARM volume (Overrides Silent and DND modes)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

            // 3. Resolve Alarm Ringtone URI
            var alarmUri: Uri? = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            }
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            }
            val soundUri: Uri = alarmUri ?: android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI

            // 4. Initialize MediaPlayer with USAGE_ALARM attributes
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, soundUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }

            // 5. Trigger intense vibration pattern
            startVibration(context)

            isRinging = true
            Log.i(TAG, "Emergency siren started at 100% volume (STREAM_ALARM).")
            onStarted?.invoke()

            // 6. Safety timeout: automatically stop after 60 seconds to prevent battery drain
            autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
            autoStopRunnable = Runnable {
                Log.i(TAG, "Emergency siren auto-stopped after 60s.")
                stopRinging(context)
            }
            mainHandler.postDelayed(autoStopRunnable!!, 60000L)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start emergency siren: ${e.message}", e)
            isRinging = false
            cleanupMedia(context)
        }
    }

    fun stopRinging(context: Context, onStopped: (() -> Unit)? = null) {
        if (!isRinging && mediaPlayer == null) {
            onStopped?.invoke()
            return
        }

        autoStopRunnable?.let {
            mainHandler.removeCallbacks(it)
            autoStopRunnable = null
        }

        cleanupMedia(context)
        stopVibration(context)

        // Restore original volume
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (originalAlarmVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume, 0)
                originalAlarmVolume = -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore original alarm volume: ${e.message}")
        }

        isRinging = false
        Log.i(TAG, "Emergency siren halted and audio restored.")
        onStopped?.invoke()
    }

    private fun cleanupMedia(context: Context) {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (_: Exception) {}
        finally {
            mediaPlayer = null
        }
    }

    private fun startVibration(context: Context) {
        try {
            val pattern = longArrayOf(0, 500, 200, 500, 200, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vm?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to trigger vibration: ${e.message}")
        }
    }

    private fun stopVibration(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.cancel()
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.cancel()
            }
        } catch (_: Exception) {}
    }
}
