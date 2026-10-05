package com.example.workoutlog_androidstudio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.workoutlog_androidstudio.api.WorkoutResponse

/**
 * The single source of truth for "is a workout currently running" - shared
 * between MainActivity/ActiveWorkoutScreen (which read and drive it while
 * the app is in the foreground) and ActiveWorkoutNotificationService (which
 * only reads it, to keep the ongoing notification's timer and pause status
 * in sync with whatever the app itself is showing, without needing a
 * second copy of any of this).
 *
 * Plain object + mutableStateOf, exactly like AppDataCache - lives for as
 * long as the process does, which is exactly what a "still running while
 * the app is in the background" notification needs.
 */
object ActiveWorkoutState {
    var workout by mutableStateOf<WorkoutResponse?>(null)
        private set
    var isPaused by mutableStateOf(false)
    var elapsedSeconds by mutableStateOf(0)
    var isMinimized by mutableStateOf(false)

    fun start(workout: WorkoutResponse) {
        this.workout = workout
        isPaused = false
        elapsedSeconds = 0
        isMinimized = false
    }

    fun end() {
        workout = null
        isPaused = false
        elapsedSeconds = 0
        isMinimized = false
    }
}
