package org.example

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * Catalog of exercises a user can pick from (seeded from your spreadsheet
 * import, or added ad hoc later). `name` is unique so re-importing the same
 * CSV twice won't create duplicate rows.
 */
object Exercises : Table("exercises") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 100).uniqueIndex()
    val muscleGroup = varchar("muscle_group", 50).nullable()
    val equipment = varchar("equipment", 50).nullable()
    // "Cardio", "Strength", "Calisthenics", or "Strength/Calisthenics" (for
    // calisthenics moves that were already in the dataset under a strength
    // entry, e.g. "Pull Up"). Nullable because rows seeded before this field
    // existed need a migration/backfill rather than failing to load (see
    // migrateExercisesCategory() in App.kt). 30 chars wide enough to fit
    // "Strength/Calisthenics".
    val category = varchar("category", 30).nullable()

    override val primaryKey = PrimaryKey(id)
}

/** One workout "session" - started when the user starts a workout, ended when they finish. */
object Workouts : Table("workouts") {
    val id = integer("id").autoIncrement()
    val workoutName = varchar("workout_name", 100)
    val startedAt = datetime("started_at")
    val endedAt = datetime("ended_at").nullable()
    val notes = varchar("notes", 500).nullable()

    override val primaryKey = PrimaryKey(id)
}

/** A single logged set: which workout, which exercise, set number, reps, and weight. */
object SetEntries : Table("set_entries") {
    val id = integer("id").autoIncrement()
    val workoutId = integer("workout_id").references(Workouts.id)
    val exerciseId = integer("exercise_id").references(Exercises.id)
    val setNumber = integer("set_number")
    val reps = integer("reps")
    // Nullable: a set can be logged with no weight at all (e.g. a bodyweight
    // exercise done with no added weight) - that's different from "0kg" and
    // should just display as blank, not skip the set entirely.
    val weightKg = double("weight_kg").nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

/** A reusable workout "template" the user can pick from a Workouts tab and
 *  start - e.g. "Push Day" or "Full Body Calisthenics". Distinct from
 *  [Workouts], which is an actual logged session; starting a template just
 *  creates a new [Workouts] row and pre-fills the exercise list client-side. */
object WorkoutTemplates : Table("workout_templates") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 100).uniqueIndex()
    // "Strength Training", "Calisthenics", "Cardio", "Mobility/Flexibility", etc.
    // - always resolved server-side from the categories of its exercises,
    // never chosen by the user directly.
    val category = varchar("category", 30)
    val estimatedMinutes = integer("estimated_minutes")
    // "Beginner", "Intermediate", or "Advanced" - resolved server-side from
    // estimatedMinutes (see resolveTemplateFields() in App.kt), same as
    // category never chosen by the user directly.
    val level = varchar("level", 20).default("Intermediate")
    val notes = varchar("notes", 500).nullable()
    // false for the six ready-made templates this app ships with; true for
    // one the user built themselves (from scratch, or saved from a past
    // workout) - lets the Workouts tab show "My Workouts" as its own section.
    val isCustom = bool("is_custom").default(false)

    override val primaryKey = PrimaryKey(id)
}

/** Join table: which exercises belong to a template, and in what order. */
object WorkoutTemplateExercises : Table("workout_template_exercises") {
    val id = integer("id").autoIncrement()
    val templateId = integer("template_id").references(WorkoutTemplates.id)
    val exerciseId = integer("exercise_id").references(Exercises.id)
    val position = integer("position")

    override val primaryKey = PrimaryKey(id)
}

/** Single-row table holding the one user's profile info - this app has no
 *  multi-user login yet, so there's always exactly one row, with id = 1.
 *  Stats like workouts logged and total volume aren't stored here at all;
 *  they're computed fresh from Workouts/SetEntries every time they're asked
 *  for, so they can never drift out of sync with the real workout history. */
object AthleteProfile : Table("athlete_profile") {
    val id = integer("id")
    val name = varchar("name", 100)
    val age = integer("age")
    val weightKg = double("weight_kg")
    // The weight just before the last edit, so the profile screen can show
    // a "-0.4 KG" style delta. Null until the weight has been edited once.
    val previousWeightKg = double("previous_weight_kg").nullable()
    val targetWeightKg = double("target_weight_kg").nullable()
    val heightCm = double("height_cm")
    val bodyFatPercent = double("body_fat_percent").nullable()
    val memberSince = varchar("member_since", 20) // ISO date, e.g. "2026-09-24"
    // How many distinct calendar days a week the user wants to log at least
    // one workout - shown as the home screen's "WEEKLY TARGET" widget.
    val weeklyWorkoutTarget = integer("weekly_workout_target").default(6)

    override val primaryKey = PrimaryKey(id)
}
