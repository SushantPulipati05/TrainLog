package com.example.workoutlog_androidstudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a single ongoing notification showing the workout currently being
 * logged - its name, live timer, and in-progress/paused status, with a
 * pause/resume action - for as long as [ActiveWorkoutState.workout] is
 * non-null. It never runs its own timer; it just re-renders the
 * notification every time that shared state changes, so it always matches
 * whatever ActiveWorkoutScreen (or the floating mini-player) is showing.
 *
 * Running as a foreground service is also what keeps the app's process -
 * and so the workout's timer, which lives entirely in ActiveWorkoutScreen's
 * composition - alive while the app is backgrounded or the screen is off.
 */
class ActiveWorkoutNotificationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob())
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> {
                ActiveWorkoutState.isPaused = !ActiveWorkoutState.isPaused
            }
            else -> {
                // A crash anywhere in here would take the whole app process
                // down with it (this runs on the main thread as part of
                // starting the service) - degrade to "no ongoing
                // notification" instead of that, on any failure.
                try {
                    startForeground(NOTIFICATION_ID, buildNotification())
                    observeWorkoutState()
                } catch (e: Exception) {
                    Log.e("ActiveWorkoutNotif", "Couldn't start the workout notification", e)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    /** Re-renders the notification on every change to the shared workout
     *  state, and tears the service down itself the moment the workout
     *  ends - so MainActivity calling stop() is a backstop, not the only
     *  path, in case the process was ever recreated with this service
     *  still running but nothing left driving it. */
    private fun observeWorkoutState() {
        if (observeJob != null) return
        observeJob = serviceScope.launch {
            snapshotFlow {
                Triple(ActiveWorkoutState.workout, ActiveWorkoutState.elapsedSeconds, ActiveWorkoutState.isPaused)
            }.collect { (workout, _, _) ->
                if (workout == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    try {
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.notify(NOTIFICATION_ID, buildNotification())
                    } catch (e: Exception) {
                        Log.e("ActiveWorkoutNotif", "Couldn't update the workout notification", e)
                    }
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        val workout = ActiveWorkoutState.workout
        val statusText = if (ActiveWorkoutState.isPaused) {
            "Paused • ${formatElapsed(ActiveWorkoutState.elapsedSeconds)}"
        } else {
            "In progress • ${formatElapsed(ActiveWorkoutState.elapsedSeconds)}"
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_RESTORE
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ActiveWorkoutNotificationService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_workout)
            // The actual app icon (background + barbell, full color,
            // cropped in tighter - see launcherBitmap) as the large icon on
            // the right, and setColorized below to fill the whole card with
            // the same near-black the app's own screens use as a
            // background, instead of the system's flat default gray/white.
            .setLargeIcon(launcherBitmap)
            .setColor(AppBackground.toArgb())
            .setColorized(true)
            .setContentTitle(workout?.workoutName ?: "Workout")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(contentIntent)
            .addAction(
                if (ActiveWorkoutState.isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (ActiveWorkoutState.isPaused) "Resume" else "Pause",
                toggleIntent
            )
            .build()
    }

    /** The composited launcher icon (background + barbell foreground, in
     *  full color) - rendered once and reused, rather than on every
     *  notification update. Null only if something about the icon resource
     *  itself is missing/broken, in which case the notification is just
     *  built without a large icon rather than crashing the service.
     *
     *  R.mipmap.ic_launcher resolves to an *adaptive-icon* XML on this
     *  device (mipmap-anydpi-v26/ic_launcher.xml), not a plain bitmap, so
     *  BitmapFactory.decodeResource can't read it (it silently returns
     *  null rather than throwing). ContextCompat.getDrawable + drawing it
     *  onto a canvas is the resource-agnostic way to rasterize it, whether
     *  it turns out to be an adaptive icon, a vector, or a plain bitmap. */
    private val launcherBitmap: Bitmap? by lazy {
        try {
            val drawable = ContextCompat.getDrawable(this, R.mipmap.ic_launcher) ?: return@lazy null
            val fullSize = 192
            val full = Bitmap.createBitmap(fullSize, fullSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(full)
            drawable.setBounds(0, 0, fullSize, fullSize)
            drawable.draw(canvas)

            // The stock icon has generous padding baked in around the mark
            // itself (Android's adaptive-icon "safe zone"), so used as-is
            // it reads small inside the notification's fixed-size
            // large-icon slot; center-cropping into just the logo makes it
            // fill noticeably more of that same slot instead.
            val side = (fullSize * 0.6f).toInt()
            val offset = (fullSize - side) / 2
            Bitmap.createBitmap(full, offset, offset, side, side)
        } catch (e: Exception) {
            Log.e("ActiveWorkoutNotif", "Couldn't render the launcher icon for the notification", e)
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active workout",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "The ongoing notification for the workout currently being logged"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "active_workout"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE_PAUSE = "com.example.workoutlog_androidstudio.ACTION_TOGGLE_PAUSE"
        const val ACTION_RESTORE = "com.example.workoutlog_androidstudio.ACTION_RESTORE"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ActiveWorkoutNotificationService::class.java))
        }

        /** Usually a no-op in practice - [observeWorkoutState] already stops
         *  the service itself the moment the workout ends - but kept as a
         *  backstop MainActivity can always call unconditionally. */
        fun stop(context: Context) {
            context.stopService(Intent(context, ActiveWorkoutNotificationService::class.java))
        }
    }
}
