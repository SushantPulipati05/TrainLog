package com.example.workoutlog_androidstudio

import kotlinx.serialization.Serializable

/** A finished workout as shown in the "Previous Workouts" list on the home screen. */
data class WorkoutSummary(
    val id: Int,
    val dateLabel: String,      // e.g. "YESTERDAY", "OCT 22"
    val date: String,           // ISO date (yyyy-MM-dd) - used to group by week/month
    val title: String,          // e.g. "Upper Body Strength"
    val duration: String,       // e.g. "54m", "1h 08m"
    val totalSets: Int,
    val exerciseCount: Int,     // distinct exercises logged - drives the consistency heatmap's intensity
    val volumeLabel: String,    // e.g. "4,820 KG"
    val tags: List<String>
)

/** One row in an exercise's set table during an active workout. Serializable
 *  so a workout in progress can be saved on the phone (see ActiveWorkoutState). */
@Serializable
data class ExerciseSet(
    val setNumber: Int,
    val previousLabel: String,  // e.g. "100 x 8" - what was logged last time
    val kg: String,
    val reps: String,
    // The workout's own running timer (ActiveWorkoutState.elapsedSeconds),
    // stamped the moment this set was added - not a wall-clock timestamp,
    // so it's unaffected by time zone/clock changes and keeps counting
    // correctly through pauses/backgrounding exactly like the timer
    // itself does. Shown beside each set so two consecutive sets' values
    // reveal how much rest was taken between them.
    val loggedAtSeconds: Int = 0
)

/** One exercise card during an active workout, with all its logged sets. */
@Serializable
data class ActiveExercise(
    val exerciseId: Int,        // the backend's Exercises.id, needed to log sets
    val name: String,
    val muscleGroup: String,    // e.g. "CHEST"
    val restLabel: String,      // e.g. "90S"
    val isBodyweight: Boolean,  // true when the backend lists this exercise's equipment as "Body Only"
    val sets: List<ExerciseSet>
)
