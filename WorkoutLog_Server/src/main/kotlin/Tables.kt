package org.example

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * One row per signed-in person. Login itself is handled by Firebase
 * Authentication - this app's server never sees or stores a password. The
 * [firebaseUid] (the verified token's "sub" claim) is how a request is tied
 * back to a row here, and every other table's user_id points at [id].
 */
object Users : Table("users") {
    val id = integer("id").autoIncrement()
    val firebaseUid = varchar("firebase_uid", 128).uniqueIndex()
    val email = varchar("email", 320).nullable()
    val displayName = varchar("display_name", 200).nullable()
    val createdAt = datetime("created_at")
    // The app's training plan (split, training days, gym time, reminder
    // settings), saved as the app's own JSON so the server never has to
    // change when the plan gains a field. null = no plan set up yet.
    // Kept per account so it comes back after signing out, a reinstall or
    // a new phone. See GET/PUT /training-plan and migrateUsersTrainingPlan().
    val trainingPlan = text("training_plan").nullable()
    // Whether the "set up a training plan" prompt on Home was dismissed.
    val planPromptDismissed = bool("plan_prompt_dismissed").default(false)

    override val primaryKey = PrimaryKey(id)
}

/**
 * Catalog of exercises a user can pick from. Built-in exercises (seeded from
 * the CSV) have a null [userId] and are visible to everyone; a custom
 * exercise someone typed in has their [userId] and is visible only to them.
 * Name uniqueness is enforced per owner by partial unique indexes created in
 * migrateMultiUser() (App.kt), not by a column-level unique index, since two
 * different users are allowed to each have their own "Cable Pull-Through".
 */
object Exercises : Table("exercises") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 100)
    val muscleGroup = varchar("muscle_group", 50).nullable()
    val equipment = varchar("equipment", 50).nullable()
    // "Cardio", "Strength", "Calisthenics", or "Strength/Calisthenics" (for
    // calisthenics moves that were already in the dataset under a strength
    // entry, e.g. "Pull Up"). Nullable because rows seeded before this field
    // existed need a migration/backfill rather than failing to load (see
    // migrateExercisesCategory() in App.kt). 30 chars wide enough to fit
    // "Strength/Calisthenics".
    val category = varchar("category", 30).nullable()
    // The body-map muscles (the app's BodyRegion names, ";"-separated) this
    // exercise trains: primary = what it's chosen for, secondary = muscles
    // that clearly assist. e.g. incline bench press -> primary UPPER_CHEST,
    // secondary MID_CHEST;FRONT_DELT;TRICEPS_LATERAL;TRICEPS_MEDIAL.
    // Built-ins come from exercises_seed.csv; null on old custom exercises
    // (the app then guesses from the name). See migrateExerciseMuscles().
    val primaryMuscles = text("primary_muscles").nullable()
    val secondaryMuscles = text("secondary_muscles").nullable()
    // null = built-in (shared by everyone); otherwise the Users.id who made it.
    val userId = integer("user_id").references(Users.id).nullable()

    override val primaryKey = PrimaryKey(id)
}

/** One workout "session" - started when the user starts a workout, ended when they finish. */
/**
 * Built-in exercises a user merged into another one (see POST
 * /exercises/merge), so they no longer show in that user's exercise list.
 * A merged *custom* exercise is deleted outright instead - only its owner
 * could ever see it.
 */
object HiddenExercises : Table("hidden_exercises") {
    val userId = integer("user_id").references(Users.id)
    val exerciseId = integer("exercise_id").references(Exercises.id)

    override val primaryKey = PrimaryKey(userId, exerciseId)
}

object Workouts : Table("workouts") {
    val id = integer("id").autoIncrement()
    val workoutName = varchar("workout_name", 100)
    val startedAt = datetime("started_at")
    val endedAt = datetime("ended_at").nullable()
    val notes = varchar("notes", 500).nullable()
    // Whose workout this is. Nullable only so rows from before accounts
    // existed can be added to a database that already has them; every new
    // row always sets it, and every query filters on it.
    val userId = integer("user_id").references(Users.id).nullable()
    // For a workout started on the phone with no connection: the phone's own
    // id for it, so uploading it twice (a retry after a lost reply) updates
    // the same workout instead of making a copy. Null for normal workouts.
    val clientId = varchar("client_id", 64).nullable()

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
    // The workout timer's value (seconds since the workout started) when the
    // set was added in the app. Null for sets logged before this existed.
    val loggedAtSeconds = integer("logged_at_seconds").nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

/** A reusable workout "template" the user can pick from a Workouts tab and
 *  start - e.g. "Push Day" or "Full Body Calisthenics". Distinct from
 *  [Workouts], which is an actual logged session; starting a template just
 *  creates a new [Workouts] row and pre-fills the exercise list client-side. */
object WorkoutTemplates : Table("workout_templates") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 100)
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
    // null = one of the built-in templates (shared by everyone); otherwise
    // the Users.id who built it. Name uniqueness is per owner - see
    // migrateMultiUser() in App.kt.
    val userId = integer("user_id").references(Users.id).nullable()

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

/** One profile row per user (see [userId]). Stats like workouts logged and total volume aren't stored here at all;
 *  they're computed fresh from Workouts/SetEntries every time they're asked
 *  for, so they can never drift out of sync with the real workout history. */
object AthleteProfile : Table("athlete_profile") {
    val id = integer("id").autoIncrement()
    // Unique per user (unique index created in migrateMultiUser()). Nullable
    // only for the one pre-accounts row, which the owner claims on first login.
    val userId = integer("user_id").references(Users.id).nullable()
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
    // false until the person finishes the first-run onboarding (name, age,
    // height, weight, ...). A brand-new account's profile starts false with
    // placeholder values; the app shows onboarding until it's true.
    val onboardingComplete = bool("onboarding_complete").default(true)
    // "MALE", "FEMALE" or "UNSPECIFIED" (prefer not to say); null for
    // profiles from before it was asked. Picks the body map figure, and is
    // there for anything that should differ by sex (strength standards ...).
    val sex = varchar("sex", 12).nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Feedback sent from the app (Profile -> Send feedback). Each one is also
 * forwarded to the owner's Discord and/or Telegram when those are set up
 * (see forwardFeedback() in App.kt).
 */
object Feedback : Table("feedback") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id)
    // "BUG", "IDEA" or "OTHER".
    val kind = varchar("kind", 10)
    val message = text("message")
    // e.g. "1.0 (1)" and "Pixel 7 · Android 15" - only when the user left
    // "Include app version and phone model" ticked.
    val appVersion = varchar("app_version", 40).nullable()
    val device = varchar("device", 100).nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}
