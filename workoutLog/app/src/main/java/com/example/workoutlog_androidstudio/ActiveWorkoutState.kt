package com.example.workoutlog_androidstudio

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
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
    private var elapsedState by mutableStateOf(0)
    private var exercisesState by mutableStateOf<List<ActiveExercise>>(emptyList())
    private var notesState by mutableStateOf("")

    var isPaused: Boolean
        get() = pausedState
        set(value) {
            pausedState = value
            persist()
        }

    var elapsedSeconds: Int
        get() = elapsedState
        set(value) {
            elapsedState = value
            persist()
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
        elapsedState = 0
        exercisesState = emptyList()
        notesState = ""
        isMinimized = false
        persist()
    }

    fun end() {
        workout = null
        pausedState = false
        elapsedState = 0
        exercisesState = emptyList()
        notesState = ""
        isMinimized = false
        persist()
    }

    private fun restore() {
        val raw = prefs?.getString(KEY_STATE, null) ?: return
        try {
            val saved = json.decodeFromString(SavedActiveWorkout.serializer(), raw)
            // While the app was dead the clock kept running (unless paused),
            // so add the time that passed since the last save.
            val secondsAway = if (saved.isPaused) {
                0
            } else {
                ((System.currentTimeMillis() - saved.savedAtMillis) / 1000L).toInt().coerceAtLeast(0)
            }
            workout = saved.workout
            pausedState = saved.isPaused
            elapsedState = saved.elapsedSeconds + secondsAway
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
            elapsedSeconds = elapsedState,
            isPaused = pausedState,
            savedAtMillis = System.currentTimeMillis()
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
    val elapsedSeconds: Int,
    val isPaused: Boolean,
    val savedAtMillis: Long
)
