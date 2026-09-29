package com.example.workoutlog_androidstudio

/**
 * Hardcoded placeholder data so the frontend has something to render.
 * Once the backend is wired up, these will be replaced by real API calls.
 */
object SampleData {

    val previousWorkouts = listOf(
        WorkoutSummary(
            id = 1,
            dateLabel = "YESTERDAY",
            date = "2026-09-22",
            title = "Upper Body Strength",
            duration = "54m",
            totalSets = 14,
            exerciseCount = 5,
            volumeLabel = "4,820 KG",
            tags = listOf("Bench Press 100kg", "Incline DB 38kg", "Lateral Raises")
        ),
        WorkoutSummary(
            id = 2,
            dateLabel = "OCT 22",
            date = "2025-10-22",
            title = "Leg Day & Core",
            duration = "1h 08m",
            totalSets = 18,
            exerciseCount = 6,
            volumeLabel = "6,120 KG",
            tags = listOf("Back Squat 140kg", "RDL 110kg", "Hanging Raises")
        ),
        WorkoutSummary(
            id = 3,
            dateLabel = "OCT 20",
            date = "2025-10-20",
            title = "Pull & Biceps",
            duration = "48m",
            totalSets = 12,
            exerciseCount = 3,
            volumeLabel = "3,950 KG",
            tags = listOf("Barbell Row 85kg", "Weighted Pullups", "Hammer Curls")
        )
    )

    fun startingExercises(): List<ActiveExercise> = listOf(
        ActiveExercise(
            exerciseId = 0,
            name = "Barbell Bench Press",
            muscleGroup = "CHEST",
            restLabel = "90S",
            isBodyweight = false,
            sets = listOf(
                ExerciseSet(setNumber = 1, previousLabel = "100 x 8", kg = "100", reps = "8"),
                ExerciseSet(setNumber = 2, previousLabel = "100 x 8", kg = "100", reps = "8"),
                ExerciseSet(setNumber = 3, previousLabel = "102.5 x 6", kg = "105", reps = "6")
            )
        ),
        ActiveExercise(
            exerciseId = 0,
            name = "Incline Dumbbell Press",
            muscleGroup = "CHEST",
            restLabel = "90S",
            isBodyweight = false,
            sets = listOf(
                ExerciseSet(setNumber = 1, previousLabel = "36 x 10", kg = "36", reps = "10"),
                ExerciseSet(setNumber = 2, previousLabel = "38 x 8", kg = "38", reps = "8"),
                ExerciseSet(setNumber = 3, previousLabel = "40 x 6", kg = "40", reps = "8")
            )
        )
    )
}
