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
    val tags: List<String>,
    /** Sets logged per muscle group (e.g. CHEST -> 9), cardio excluded -
     *  how Up next tells what a workout was. Empty from an older server. */
    val muscleSets: Map<String, Int> = emptyMap(),
    /** Sets per exercise (cardio excluded) - which parts of each muscle it
     *  worked decides whether the workout was complete. Empty from an older server. */
    val exerciseSets: List<ExerciseSetCount> = emptyList()
)

/** One exercise's sets inside a finished workout, with the body-map muscles
 *  it trains (BodyRegion names - empty when unknown, then they're guessed
 *  from the name). */
data class ExerciseSetCount(
    val name: String,
    val muscleGroup: String?,
    val sets: Int,
    val primaryMuscles: List<String> = emptyList(),
    val secondaryMuscles: List<String> = emptyList()
)

/** A typed weight as a number: "62.5" and "62,5" (comma keyboards) both
 *  give 62.5. null when empty or not a number. */
fun parseKg(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

/** What a weight box accepts while typing: digits and one decimal point (a
 *  comma becomes a point), at most 6 characters - so "62,5" can't silently
 *  turn into "no weight" when the workout is saved. */
fun cleanKgInput(raw: String): String {
    val normalized = raw.replace(',', '.').filter { it.isDigit() || it == '.' }
    val firstDot = normalized.indexOf('.')
    val oneDot = if (firstDot < 0) normalized
        else normalized.substring(0, firstDot + 1) + normalized.substring(firstDot + 1).replace(".", "")
    return oneDot.take(6)
}

/** What a reps box accepts: digits only, at most 4. */
fun cleanRepsInput(raw: String): String = raw.filter { it.isDigit() }.take(4)

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
    val loggedAtSeconds: Int = 0,
    // The user typed in this row or tapped its set number. A touched row's
    // empty box takes last time's number (shown in white) when it's saved;
    // an untouched row is never saved, grey numbers or not.
    val touched: Boolean = false,
    // Workout timer when this row was last typed in - the real "last set"
    // time, used to spot a workout that was left running.
    val editedAtSeconds: Int? = null,
    // A drop set: a lighter weight done straight after the set above it.
    // Shown as "D" under that set; it counts for volume but not as a set.
    val isDrop: Boolean = false
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

/** What each row's set box shows: working sets are numbered 1, 2, 3 and
 *  drop sets show "D" without taking a number. */
fun setLabels(isDrop: List<Boolean>): List<String> {
    var n = 0
    return isDrop.map { drop -> if (drop) "D" else (++n).toString() }
}

/** Rows renumbered in order after a change. The first row can't be a drop -
 *  there's no set above it to drop from. */
fun List<ExerciseSet>.renumbered(): List<ExerciseSet> =
    mapIndexed { i, s -> s.copy(setNumber = i + 1, isDrop = s.isDrop && i > 0) }
