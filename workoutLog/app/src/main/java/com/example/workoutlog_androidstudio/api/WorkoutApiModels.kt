package com.example.workoutlog_androidstudio.api

import kotlinx.serialization.Serializable

@Serializable
data class NewWorkoutRequest(val workoutName: String)

@Serializable
data class WorkoutResponse(
    val id: Int,
    val workoutName: String,
    val startedAt: String,
    val endedAt: String?,
    val notes: String?
)

@Serializable
data class ExerciseResponse(
    val id: Int,
    val name: String,
    val muscleGroup: String?,
    val equipment: String?,
    // "Cardio", "Strength", "Calisthenics", "Strength/Calisthenics", or
    // "Mobility/Flexibility". Nullable/optional since older custom exercises
    // (created before this field existed) won't have one.
    val category: String? = null
)

@Serializable
data class WorkoutSummaryResponse(
    val id: Int,
    val dateLabel: String,
    val date: String,
    val title: String,
    val duration: String,
    val totalSets: Int,
    val exerciseCount: Int,
    val volumeLabel: String,
    val tags: List<String>
)

/** What we send the backend when logging one set during a workout. */
@Serializable
data class NewSetRequest(
    val exerciseId: Int,
    val setNumber: Int,
    val reps: Int,
    val weightKg: Double?
)

/** What one logged set looks like coming back from the backend. */
@Serializable
data class SetEntryResponse(
    val id: Int,
    val workoutId: Int,
    val exerciseId: Int,
    val setNumber: Int,
    val reps: Int,
    val weightKg: Double?
)

/** What we send the backend when ending a workout. Notes are optional. */
@Serializable
data class EndWorkoutRequest(
    val notes: String? = null
)

@Serializable
data class WorkoutDetailResponse(
    val id: Int,
    val workoutName: String,
    val startedAt: String,
    val endedAt: String?,
    val notes: String?,
    val sets: List<SetEntryResponse>
)

@Serializable
data class NewExerciseRequest(
    val name: String,
    val muscleGroup: String?,
    val equipment: String?,
    val category: String? = null
)

@Serializable
data class UpdateWorkoutRequest(
    val workoutName: String? = null,
    val notes: String? = null
)

@Serializable
data class UpdateSetRequest(
    val reps: Int,
    val weightKg: Double?
)

@Serializable
data class ProfileResponse(
    val name: String,
    val age: Int,
    val weightKg: Double,
    val weightDeltaKg: Double?,
    val targetWeightKg: Double?,
    val heightCm: Double,
    val bmi: Double,
    val bodyFatPercent: Double?,
    val workoutsLogged: Int,
    val totalVolumeKg: Double,
    val memberSince: String,
    val weeksActive: Int,
    // Distinct calendar days/week the user wants to log at least one
    // workout - set on the Targets screen, shown as Home's weekly widget.
    val weeklyWorkoutTarget: Int
)

@Serializable
data class UpdateProfileRequest(
    val name: String? = null,
    val age: Int? = null,
    val weightKg: Double? = null,
    val targetWeightKg: Double? = null,
    val heightCm: Double? = null,
    val bodyFatPercent: Double? = null,
    val weeklyWorkoutTarget: Int? = null
)

/** A workout template summarized for the Workouts tab's list. */
@Serializable
data class WorkoutTemplateSummaryResponse(
    val id: Int,
    val name: String,
    val category: String,
    // "Beginner", "Intermediate", or "Advanced" - resolved server-side.
    val level: String,
    val estimatedMinutes: Int,
    val exerciseCount: Int,
    // Up to 3 distinct muscle groups trained by this template's exercises,
    // e.g. ["CHEST", "SHOULDERS", "TRICEPS"].
    val muscleGroups: List<String>,
    // Days since the most recent finished workout with this exact name was
    // logged, or null if it's never been logged. 0 = today, 1 = yesterday.
    val lastLoggedDaysAgo: Int?,
    // false for the six built-in templates, true for one the user made.
    val isCustom: Boolean
)

/** A workout template's full detail - its exercises in order. */
@Serializable
data class WorkoutTemplateDetailResponse(
    val id: Int,
    val name: String,
    val category: String,
    val level: String,
    val estimatedMinutes: Int,
    val muscleGroups: List<String>,
    val lastLoggedDaysAgo: Int?,
    val notes: String?,
    val isCustom: Boolean,
    val exercises: List<ExerciseResponse>
)

/** What the app sends to create a custom workout template from scratch.
 *  Category, level, and estimated duration are never sent - the backend
 *  always resolves them from these exercises. */
@Serializable
data class NewWorkoutTemplateRequest(
    val name: String,
    val notes: String? = null,
    val exerciseIds: List<Int>
)

/** What the app sends to save an already-logged workout as a reusable
 *  template. name/notes default to the workout's own when left null. */
@Serializable
data class SaveWorkoutAsTemplateRequest(
    val name: String? = null,
    val notes: String? = null
)
