package com.tailconnect.app

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat

class ScreenCaptureActivity : Activity() {

    companion object {
        private const val TAG = "ScreenCaptureActivity"
        private const val REQUEST_CODE_CAPTURE = 1002
        private const val NOTIFICATION_ID_PROMPT = 8881
        private const val CHANNEL_ID_PROMPT = "screen_share_prompt_channel_v2"

        fun start(context: Context) {
            val intent = Intent(context, ScreenCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Direct startActivity failed: ${e.message}")
            }

            // Post high-priority full-screen intent notification so popup is never delayed or blocked
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(
                        CHANNEL_ID_PROMPT,
                        "Screen Share Authorization",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Displays the prompt to authorize screen sharing"
                        setSound(null, null)
                        enableVibration(true)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                    nm.createNotificationChannel(channel)
                }

                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notification = NotificationCompat.Builder(context, CHANNEL_ID_PROMPT)
                    .setSmallIcon(R.drawable.ic_ano_logo)
                    .setContentTitle("Screen Sharing Requested")
                    .setContentText("Tap to allow PC remote screen view")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setFullScreenIntent(pendingIntent, true)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .build()
                nm?.notify(NOTIFICATION_ID_PROMPT, notification)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to post screen capture prompt notification: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        promptMediaProjection()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        promptMediaProjection()
    }

    private fun promptMediaProjection() {
        if (ScreenCaptureService.isStreaming) {
            dismissPromptNotification()
            finish()
            return
        }
        try {
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE_CAPTURE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch screen capture intent: ${e.message}")
            dismissPromptNotification()
            finish()
        }
    }

    private fun dismissPromptNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID_PROMPT)
        } catch (_: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        dismissPromptNotification()
        if (requestCode == REQUEST_CODE_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.i(TAG, "Screen capture permission GRANTED by user!")
                TailConnectService.onScreenCaptureGranted(resultCode, data)

                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA, data)
                }
                startForegroundService(serviceIntent)
            } else {
                Log.w(TAG, "Screen capture permission DENIED by user")
                TailConnectService.onScreenCaptureDenied()
            }
            finish()
        }
    }
}
