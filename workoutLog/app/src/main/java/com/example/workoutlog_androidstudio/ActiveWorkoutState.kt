package com.example.workoutlog_androidstudio

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.workoutlog_androidstudio.api.WorkoutResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The single source of truth for the workout currently being logged - shared
 * between MainActivity/ActiveWorkoutScreen (which read and drive it) and
 * ActiveWorkoutNotificationService (which only reads it, to keep the ongoing
 * notification in sync).
 *
 * Everything about the running workout lives here - not just the timer but
 * the exercises, sets and notes too - and every change is saved to the phone
 * straight away. So if Android kills the app mid-workout (switching to
 * another app, low memory, a crash), reopening it picks up exactly where the
 * person left off, timer included. Nothing is sent to the server until the
 * workout is ended (see ActiveWorkoutScreen), so there's nothing half-saved
 * there either.
 *
 * The timer is worked out from the clock, not counted: it's the seconds
 * built up before the last pause plus the time since the last start/resume.
 * With the screen off the phone's CPU sleeps and a once-a-second counter
 * would simply miss seconds; a clock-based timer can't fall behind, and a
 * set added right after turning the screen back on gets the right time.
 */
object ActiveWorkoutState {
    private const val PREFS_NAME = "active_workout"
    private const val KEY_STATE = "state"

    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: SharedPreferences? = null

    var workout by mutableStateOf<WorkoutResponse?>(null)
        private set
    var isMinimized by mutableStateOf(false)

    private var pausedState by mutableStateOf(false)
    /** Seconds built up before the current run (i.e. up to the last pause). */
    private var accumulatedSeconds by mutableStateOf(0)
    /** Wall-clock time the timer last started or resumed; null while paused. */
    private var runningSinceMillis by mutableStateOf<Long?>(null)
    /** Bumped by [tick] once a second only so the screen redraws the time -
     *  the time itself never depends on it. */
    private var nowMillis by mutableLongStateOf(System.currentTimeMillis())
    private var exercisesState by mutableStateOf<List<ActiveExercise>>(emptyList())
    private var notesState by mutableStateOf("")

    var isPaused: Boolean
        get() = pausedState
        set(value) {
            if (value == pausedState) return
            val now = System.currentTimeMillis()
            if (value) {
                // Pausing: bank the time run so far.
                accumulatedSeconds = secondsAt(now)
                runningSinceMillis = null
            } else {
                runningSinceMillis = now
            }
            pausedState = value
            nowMillis = now
            persist()
        }

    /** The workout's elapsed time, worked out fresh from the clock on every
     *  read. Read from Compose, it updates whenever [tick] runs. */
    val elapsedSeconds: Int
        get() {
            nowMillis // read so Compose re-reads this every tick
            return secondsAt(System.currentTimeMillis())
        }

    /** Wall-clock time the workout would have started if it had never been
     *  paused - for the notification's self-running timer. */
    val effectiveStartMillis: Long
        get() = System.currentTimeMillis() - elapsedSeconds * 1000L

    private fun secondsAt(now: Long): Int {
        val since = runningSinceMillis ?: return accumulatedSeconds
        val running = ((now - since) / 1000L).toInt().coerceAtLeast(0)
        return accumulatedSeconds + running
    }

    /** Called about once a second by the Active Workout screen so the time
     *  on screen moves. Missing ticks (screen off) loses nothing. */
    fun tick() {
        nowMillis = System.currentTimeMillis()
    }

    var exercises: List<ActiveExercise>
        get() = exercisesState
        set(value) {
            exercisesState = value
            persist()
        }

    var notes: String
        get() = notesState
        set(value) {
            notesState = value
            persist()
        }

    /** Hooks up on-phone saving and restores a workout that was still running
     *  when the app was last killed. Safe to call more than once. */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        restore()
    }

    fun start(workout: WorkoutResponse) {
        this.workout = workout
        pausedState = false
        accumulatedSeconds = 0
        runningSinceMillis = System.currentTimeMillis()
        exercisesState = emptyList()
        notesState = ""
        isMinimized = false
        persist()
    }

    /** Renames the running workout on this phone (the caller tells the server). */
    fun rename(name: String) {
        val current = workout ?: return
        workout = current.copy(workoutName = name)
        persist()
    }

    fun end() {
        workout = null
        pausedState = false
        accumulatedSeconds = 0
        runningSinceMillis = null
        exercisesState = emptyList()
        notesState = ""
        isMinimized = false
        persist()
    }

    private fun restore() {
        val raw = prefs?.getString(KEY_STATE, null) ?: return
        try {
            val saved = json.decodeFromString(SavedActiveWorkout.serializer(), raw)
            val now = System.currentTimeMillis()
            workout = saved.workout
            pausedState = saved.isPaused
            if (saved.version >= 2) {
                // The start time was saved, so the clock simply carries on.
                accumulatedSeconds = saved.accumulatedSeconds
                runningSinceMillis = if (saved.isPaused) null else saved.runningSinceMillis ?: now
            } else {
                // Saved by the old counting timer: its count plus the time
                // since that save (unless paused), then run from now.
                val secondsAway = if (saved.isPaused) 0
                else ((now - saved.savedAtMillis) / 1000L).toInt().coerceAtLeast(0)
                accumulatedSeconds = saved.elapsedSeconds + secondsAway
                runningSinceMillis = if (saved.isPaused) null else now
            }
            nowMillis = now
            exercisesState = saved.exercises
            notesState = saved.notes
            isMinimized = false
        } catch (e: Exception) {
            // Unreadable (e.g. saved by an older version) - start clean
            // rather than crash on launch.
            prefs?.edit()?.remove(KEY_STATE)?.apply()
        }
    }

    private fun persist() {
        val store = prefs ?: return
        val current = workout
        if (current == null) {
            store.edit().remove(KEY_STATE).apply()
            return
        }
        val saved = SavedActiveWorkout(
            workout = current,
            exercises = exercisesState,
            notes = notesState,
            elapsedSeconds = secondsAt(System.currentTimeMillis()),
            isPaused = pausedState,
            savedAtMillis = System.currentTimeMillis(),
            accumulatedSeconds = accumulatedSeconds,
            runningSinceMillis = runningSinceMillis,
            version = 2
        )
        store.edit().putString(KEY_STATE, json.encodeToString(SavedActiveWorkout.serializer(), saved)).apply()
    }
}

/** What's written to the phone for a workout in progress. */
@Serializable
private data class SavedActiveWorkout(
    val workout: WorkoutResponse,
    val exercises: List<ActiveExercise>,
    val notes: String,
    /** Version 1 (the old counting timer) only had this and [savedAtMillis]. */
    val elapsedSeconds: Int,
    val isPaused: Boolean,
    val savedAtMillis: Long,
    val accumulatedSeconds: Int = 0,
    val runningSinceMillis: Long? = null,
    val version: Int = 1
)
