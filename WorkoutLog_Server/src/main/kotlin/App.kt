package org.example

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.interfaces.Payload
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.sql.DriverManager
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Reads an environment variable, falling back to [default] when it isn't set -
 *  so this still runs against your local Postgres with no env vars set at all,
 *  but picks up Railway's (or any host's) real values automatically once deployed. */
private fun env(key: String, default: String): String = System.getenv(key) ?: default

private fun databaseUrl(): String {
    val host = env("PGHOST", "localhost")
    val port = env("PGPORT", "5432")
    val database = env("PGDATABASE", "WorkoutLog")
    return "jdbc:postgresql://$host:$port/$database"
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081
    // host = 0.0.0.0 (not just "localhost") so devices on your network - the
    // Android emulator via 10.0.2.2, your phone on the same WiFi, or (once
    // deployed) anyone on the internet hitting your public URL - can reach this.
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

/** What one exercise looks like as JSON, sent back to the app. */
@Serializable
data class ExerciseResponse(
    val id: Int,
    val name: String,
    val muscleGroup: String?,
    val equipment: String?,
    // "Cardio", "Strength", "Calisthenics", "Strength/Calisthenics", or
    // "Mobility/Flexibility".
    val category: String?
)

/** What the app sends us when starting a new workout. */
@Serializable
data class NewWorkoutRequest(
    val workoutName: String
)

/** What the app sends us when logging a custom exercise that isn't in the
 *  seeded list - e.g. muscleGroup = "Chest", equipment = "Body Only" for a
 *  bodyweight movement, or any other string for an equipment-based one. */
@Serializable
data class NewExerciseRequest(
    val name: String,
    val muscleGroup: String?,
    val equipment: String?,
    // "Cardio", "Strength", "Calisthenics", "Strength/Calisthenics", or
    // "Mobility/Flexibility". Defaults to "Strength" server-side (below) if
    // the app doesn't send one, since the custom-exercise dialog doesn't
    // ask for this yet.
    val category: String? = null
)

/** What one workout looks like as JSON, sent back to the app. */
@Serializable
data class WorkoutResponse(
    val id: Int,
    val workoutName: String,
    val startedAt: String,
    val endedAt: String?,
    val notes: String?
)

/** What the app sends us when logging one set. weightKg is null when no
 *  weight was entered (e.g. a bodyweight set with nothing added). */
@Serializable
data class NewSetRequest(
    val exerciseId: Int,
    val setNumber: Int,
    val reps: Int,
    val weightKg: Double?
)

/** What one logged set looks like as JSON, sent back to the app. */
@Serializable
data class SetEntryResponse(
    val id: Int,
    val workoutId: Int,
    val exerciseId: Int,
    val setNumber: Int,
    val reps: Int,
    val weightKg: Double?
)

/** What the app sends us when ending a workout. Notes are optional. */
@Serializable
data class EndWorkoutRequest(
    val notes: String? = null
)

/** What the app sends to edit an already-finished workout's name and/or
 *  notes. Either field left null is left unchanged - this never touches
 *  startedAt/endedAt, so the workout's date, time and duration stay put. */
@Serializable
data class UpdateWorkoutRequest(
    val workoutName: String? = null,
    val notes: String? = null
)

/** Everything about a finished workout in one request: all its sets plus
 *  notes. See POST /workouts/{id}/complete. */
@Serializable
data class CompleteWorkoutRequest(
    val notes: String? = null,
    val sets: List<NewSetRequest>
)

/** What the app sends to edit one already-logged set's reps/weight. */
@Serializable
data class UpdateSetRequest(
    val reps: Int,
    val weightKg: Double?
)

/** A single workout together with every set logged against it. */
@Serializable
data class WorkoutDetailResponse(
    val id: Int,
    val workoutName: String,
    val startedAt: String,
    val endedAt: String?,
    val notes: String?,
    val sets: List<SetEntryResponse>
)

/** A finished workout summarized for the "Previous Workouts" list on the home screen. */
@Serializable
data class WorkoutSummaryResponse(
    val id: Int,
    val dateLabel: String,      // e.g. "TODAY", "YESTERDAY", "OCT 22"
    val date: String,          // ISO date (yyyy-MM-dd) the workout was started on - for grouping by day/week/month
    val title: String,
    val duration: String,      // e.g. "54m", "1h 08m"
    val totalSets: Int,
    val exerciseCount: Int,    // number of distinct exercises logged, used for the consistency heatmap's intensity
    val volumeLabel: String,   // e.g. "4,820 KG"
    val tags: List<String>
)

/** The athlete profile screen's full payload - stored fields (name, age,
 *  weight, height, ...) plus a handful of stats computed fresh from the
 *  workout history every time this is requested, so they can never go
 *  stale the way a stored, unmaintained counter could. */
@Serializable
data class ProfileResponse(
    val name: String,
    val age: Int,
    val weightKg: Double,
    val weightDeltaKg: Double?,   // change vs. the weight just before the last edit; null until it's been edited once
    val targetWeightKg: Double?,
    val heightCm: Double,
    val bmi: Double,
    val bodyFatPercent: Double?,
    val workoutsLogged: Int,
    val totalVolumeKg: Double,
    val memberSince: String,      // ISO date
    val weeksActive: Int,
    val weeklyWorkoutTarget: Int,  // distinct days/week the user wants to log a workout
    val onboardingComplete: Boolean // false = the app should show first-run onboarding
)

/** What the app sends to edit the profile. Any field left null keeps its
 *  current value - workoutsLogged/totalVolumeKg/bmi/weeksActive aren't here
 *  at all, since those are always computed, never stored. */
@Serializable
data class UpdateProfileRequest(
    val name: String? = null,
    val age: Int? = null,
    val weightKg: Double? = null,
    val targetWeightKg: Double? = null,
    val heightCm: Double? = null,
    val bodyFatPercent: Double? = null,
    val weeklyWorkoutTarget: Int? = null,
    // Sent as true once, by the last onboarding screen.
    val onboardingComplete: Boolean? = null
)

/** One finished workout's contribution to an exercise's progression history -
 *  used to draw the weight-over-time line chart and to flag PR workouts.
 *  Returned oldest-first so both the chart and the running PR comparison
 *  read naturally in chronological order. */
@Serializable
data class ExerciseHistoryPointResponse(
    val workoutId: Int,
    val workoutName: String,   // the real workout session name (e.g. "Push Day"), for the session history list
    val date: String,          // ISO date (yyyy-MM-dd) the workout was started on
    val startedAt: String,     // full ISO timestamp - lets the app show the time of day too, not just the date
    // Heaviest weight logged for this exercise in this workout. Null when
    // every set that workout had no weight entered at all (pure bodyweight,
    // nothing added) - that's different from 0kg.
    val maxWeightKg: Double?,
    val maxReps: Int,          // most reps logged in a single set for this exercise this workout - what bodyweight progress is judged on instead
    val totalVolumeKg: Double, // sum of (weight x reps) across every set of this exercise this workout - added weight only, bodyweight isn't folded in
    // True the first time this workout's maxWeightKg beats every strictly
    // earlier finished workout's maxWeightKg for this exercise - even by one
    // rep's worth of weight, for one rep. Always false when maxWeightKg is null.
    val isPr: Boolean,
    // Every set logged for this exercise in this workout, set-number order -
    // the session history list's set-by-set breakdown is built from this.
    val sets: List<SetEntryResponse>
)

/** One exercise's full progression history: its name/equipment (so the app
 *  knows whether to render a weight chart or just track reps) plus every
 *  finished workout that logged a set for it, oldest first. */
@Serializable
data class ExerciseHistoryResponse(
    val exerciseId: Int,
    val exerciseName: String,
    val equipment: String?,
    val muscleGroup: String?,
    val isBodyweight: Boolean,
    val history: List<ExerciseHistoryPointResponse>
)

/** A workout template summarized for the Workouts tab's list - just enough
 *  to render a card (name, category badge, exercise count, estimated time)
 *  without pulling every exercise's full details. */
@Serializable
data class WorkoutTemplateSummaryResponse(
    val id: Int,
    val name: String,
    val category: String,
    // "Beginner", "Intermediate", or "Advanced" - resolved server-side from
    // estimatedMinutes, same as category never chosen by the user.
    val level: String,
    val estimatedMinutes: Int,
    val exerciseCount: Int,
    // Up to 3 distinct muscle groups trained by this template's exercises,
    // in the order each first appears - e.g. ["CHEST", "SHOULDERS", "TRICEPS"].
    val muscleGroups: List<String>,
    // Days since the most recent *finished* workout with this exact name was
    // logged, or null if it's never been logged. 0 = today, 1 = yesterday, etc.
    val lastLoggedDaysAgo: Int?,
    // false for the six built-in templates, true for one the user made -
    // lets the app split the list into "My Workouts" vs. the built-in ones.
    val isCustom: Boolean
)

/** A workout template's full detail - its exercises in order, so the detail
 *  screen can show sets/equipment for each and "Do this workout" can pre-load
 *  every one of them into a freshly started workout. */
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

/** What the app sends to create a custom workout template from scratch -
 *  just a name, an optional note, and the exercises (in the order they
 *  should appear). Category and estimated duration are never sent by the
 *  app - they're always resolved server-side from these exercises, in
 *  resolveTemplateFields() below. */
@Serializable
data class NewWorkoutTemplateRequest(
    val name: String,
    val notes: String? = null,
    val exerciseIds: List<Int>
)

/** What the app sends to save an already-logged workout as a reusable
 *  template. name/notes default to the workout's own name and notes when
 *  left null, so "save as template" works with a single tap. */
@Serializable
data class SaveWorkoutAsTemplateRequest(
    val name: String? = null,
    val notes: String? = null
)

fun Application.module() {
    // Firebase Authentication does the actual signing in; this server only
    // verifies the ID token the app sends. The project id is what a genuine
    // token's issuer/audience must match, so there's no safe default - fail
    // loudly at startup instead of silently accepting nothing (or worse,
    // everything).
    val firebaseProjectId = System.getenv("FIREBASE_PROJECT_ID")?.takeIf { it.isNotBlank() }
        ?: error("FIREBASE_PROJECT_ID is not set - set it to your Firebase project's id (Project settings > General).")

    Database.connect(
        url = databaseUrl(),
        driver = "org.postgresql.Driver",
        user = env("PGUSER", "postgres"),
        password = env("PGPASSWORD", "postgres")
    )

    // On a fresh deploy, Postgres can still be booting (or restarting) at the
    // exact moment this app container starts - there's no guaranteed startup
    // order between two separate Railway services. Instead of crashing on the
    // very first failed connection attempt, retry for a while so a slow-to-start
    // database doesn't take the whole deploy down with it.
    waitForDatabase()

    transaction {
        // Creates any of these tables that don't already exist yet. Safe to run
        // on every startup (including every future deploy) - it never touches
        // a table that's already there.
        SchemaUtils.create(Users, Exercises, Workouts, SetEntries, AthleteProfile, WorkoutTemplates, WorkoutTemplateExercises)
    }

    migrateSetEntriesWeightNullable()
    migrateExercisesCategory()
    migrateWorkoutTemplatesCustomFields()
    migrateAthleteProfileWeeklyTarget()
    migrateMultiUser()
    migrateAthleteProfileOnboarding()

    seedExercisesIfNeeded()
    seedWorkoutTemplatesIfNeeded()

    install(ContentNegotiation) {
        json()
    }

    // Firebase signs its ID tokens with rotating keys published at this URL;
    // the provider caches them (and rate-limits re-fetches) so verifying a
    // token is a local signature check, not a network call per request.
    val jwkProvider = JwkProviderBuilder(
        URI("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com").toURL()
    )
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    install(Authentication) {
        jwt("firebase") {
            verifier(jwkProvider, "https://securetoken.google.com/$firebaseProjectId") {
                acceptLeeway(5)
            }
            validate { credential ->
                val payload = credential.payload
                if (payload.audience?.contains(firebaseProjectId) == true && !payload.subject.isNullOrBlank()) {
                    JWTPrincipal(payload)
                } else {
                    null
                }
            }
        }
    }

    routing {
        // A single test route to prove the server is alive, before we wire in the database.
        get("/health") {
            call.respondText("OK")
        }

        // Everything below needs a valid Firebase ID token (see the
        // Authentication setup in module()) - /health above stays public.
        authenticate("firebase") {
            // Returns every exercise this user can pick from - the built-in
            // library plus any custom ones they made - as a JSON array.
            get("/exercises") {
                val me = call.userId()
                val zone = call.userZone()
                val exercises = transaction {
                    Exercises.selectAll().where { visibleExercises(me) }.map { row ->
                        ExerciseResponse(
                            id = row[Exercises.id],
                            name = row[Exercises.name],
                            muscleGroup = row[Exercises.muscleGroup],
                            equipment = row[Exercises.equipment],
                            category = row[Exercises.category]
                        )
                    }
                }
                call.respond(exercises)
            }

            // Adds a custom exercise the user typed in themselves, for when the
            // seeded list doesn't have what they're looking for.
            post("/exercises") {
                val me = call.userId()
                val zone = call.userZone()
                val request = try {
                    call.receive<NewExerciseRequest>()
                } catch (e: Exception) {
                    call.respondText("invalid exercise body", status = HttpStatusCode.BadRequest)
                    return@post
                }

                val trimmedName = request.name.trim()
                if (trimmedName.isEmpty()) {
                    call.respondText("exercise name can't be blank", status = HttpStatusCode.BadRequest)
                    return@post
                }

                // Defaults to "Strength" when the app doesn't send a category -
                // the custom-exercise dialog only asks for equipment/bodyweight
                // right now, not cardio-vs-strength.
                val resolvedCategory = request.category ?: "Strength"

                val created = try {
                    transaction {
                        // Same name as a built-in exercise, or one of this user's
                        // own custom ones (ignoring case), counts as a duplicate.
                        val clash = Exercises.selectAll()
                            .where { (Exercises.name.lowerCase() eq trimmedName.lowercase()) and visibleExercises(me) }
                            .limit(1)
                            .any()
                        if (clash) return@transaction null

                        val newId = Exercises.insert {
                            it[name] = trimmedName
                            it[muscleGroup] = request.muscleGroup
                            it[equipment] = request.equipment
                            it[category] = resolvedCategory
                            it[Exercises.userId] = me
                        } get Exercises.id

                        ExerciseResponse(
                            id = newId,
                            name = trimmedName,
                            muscleGroup = request.muscleGroup,
                            equipment = request.equipment,
                            category = resolvedCategory
                        )
                    }
                } catch (e: Exception) {
                    // Most likely a duplicate name - Exercises.name has a unique index.
                    null
                }

                if (created == null) {
                    call.respondText("an exercise with that name already exists", status = HttpStatusCode.Conflict)
                } else {
                    call.respond(HttpStatusCode.Created, created)
                }
            }

            // Returns one exercise's full progression history for the exercise
            // detail/growth-chart screen: every finished workout that logged a
            // set for it, oldest first, with each workout's heaviest weight,
            // most reps, total volume, and whether it was a new all-time PR for
            // this exercise. Backed by a single query joining set_entries to
            // workouts (filtered to this exerciseId) - the grouping/PR math all
            // happens in-memory afterward over just this exercise's rows, so
            // this stays fast as the workout history grows, unlike re-fetching
            // every workout from the client and recomputing it there.
            get("/exercises/{id}/history") {
                val me = call.userId()
                val zone = call.userZone()
                val exerciseId = call.parameters["id"]?.toIntOrNull()
                if (exerciseId == null) {
                    call.respondText("exercise id must be a number", status = HttpStatusCode.BadRequest)
                    return@get
                }

                val result = transaction {
                    val exerciseRow = Exercises.selectAll()
                        .where { (Exercises.id eq exerciseId) and visibleExercises(me) }
                        .singleOrNull() ?: return@transaction null

                    // Only finished workouts count - same rule /workouts/summary
                    // uses - and ordering by startedAt here means every set for
                    // the same workout lands together, so grouping below keeps
                    // workouts in chronological order with no extra sort step.
                    val rows = (SetEntries innerJoin Workouts)
                        .selectAll()
                        .where { (SetEntries.exerciseId eq exerciseId) and (Workouts.endedAt.isNotNull()) and (Workouts.userId eq me) }
                        .orderBy(Workouts.startedAt, SortOrder.ASC)
                        .toList()

                    var runningMaxWeightKg = 0.0
                    val history = rows.groupBy { it[SetEntries.workoutId] }.map { (workoutId, setsForWorkout) ->
                        val maxWeightKg = setsForWorkout.mapNotNull { it[SetEntries.weightKg] }.maxOrNull()
                        val maxReps = setsForWorkout.maxOf { it[SetEntries.reps] }
                        val totalVolumeKg = setsForWorkout.sumOf { (it[SetEntries.weightKg] ?: 0.0) * it[SetEntries.reps] }
                        val startedAt = setsForWorkout.first()[Workouts.startedAt].toZone(zone)

                        // A set with no weight at all never counts as a PR -
                        // there's nothing to compare against the running max.
                        val isPr = maxWeightKg != null && maxWeightKg > runningMaxWeightKg
                        if (maxWeightKg != null && maxWeightKg > runningMaxWeightKg) {
                            runningMaxWeightKg = maxWeightKg
                        }

                        ExerciseHistoryPointResponse(
                            workoutId = workoutId,
                            workoutName = setsForWorkout.first()[Workouts.workoutName],
                            date = startedAt.toLocalDate().toString(),
                            startedAt = startedAt.toString(),
                            maxWeightKg = maxWeightKg,
                            maxReps = maxReps,
                            totalVolumeKg = totalVolumeKg,
                            isPr = isPr,
                            sets = setsForWorkout.sortedBy { it[SetEntries.setNumber] }.map { row ->
                                SetEntryResponse(
                                    id = row[SetEntries.id],
                                    workoutId = workoutId,
                                    exerciseId = exerciseId,
                                    setNumber = row[SetEntries.setNumber],
                                    reps = row[SetEntries.reps],
                                    weightKg = row[SetEntries.weightKg]
                                )
                            }
                        )
                    }

                    ExerciseHistoryResponse(
                        exerciseId = exerciseId,
                        exerciseName = exerciseRow[Exercises.name],
                        equipment = exerciseRow[Exercises.equipment],
                        muscleGroup = exerciseRow[Exercises.muscleGroup],
                        isBodyweight = exerciseRow[Exercises.equipment].equals("Body Only", ignoreCase = true),
                        history = history
                    )
                }

                if (result == null) {
                    call.respondText("no exercise with that id", status = HttpStatusCode.NotFound)
                } else {
                    call.respond(result)
                }
            }

            // Returns every workout template for the Workouts tab's list - both
            // the built-in ones and any the user has made.
            get("/workout-templates") {
                val me = call.userId()
                val zone = call.userZone()
                val templates = transaction {
                    WorkoutTemplates.selectAll().where { visibleTemplates(me) }.map { row ->
                        val templateId = row[WorkoutTemplates.id]
                        val exerciseIds = WorkoutTemplateExercises.selectAll()
                            .where { WorkoutTemplateExercises.templateId eq templateId }
                            .orderBy(WorkoutTemplateExercises.position)
                            .map { it[WorkoutTemplateExercises.exerciseId] }

                        WorkoutTemplateSummaryResponse(
                            id = templateId,
                            name = row[WorkoutTemplates.name],
                            category = row[WorkoutTemplates.category],
                            level = row[WorkoutTemplates.level],
                            estimatedMinutes = row[WorkoutTemplates.estimatedMinutes],
                            exerciseCount = exerciseIds.size,
                            muscleGroups = computeMuscleGroups(exerciseIds),
                            lastLoggedDaysAgo = computeLastLoggedDaysAgo(row[WorkoutTemplates.name], me, zone),
                            isCustom = row[WorkoutTemplates.isCustom]
                        )
                    }
                }
                call.respond(templates)
            }

            // Creates a custom workout template from scratch - a name, an
            // optional note, and a list of exercise ids. Category and estimated
            // duration are always resolved server-side (see
            // resolveTemplateFields) - the app never sends them.
            post("/workout-templates") {
                val me = call.userId()
                val zone = call.userZone()
                val request = try {
                    call.receive<NewWorkoutTemplateRequest>()
                } catch (e: Exception) {
                    call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
                    return@post
                }

                val trimmedName = request.name.trim()
                if (trimmedName.isEmpty()) {
                    call.respondText("template name can't be blank", status = HttpStatusCode.BadRequest)
                    return@post
                }
                if (request.exerciseIds.isEmpty()) {
                    call.respondText("a template needs at least one exercise", status = HttpStatusCode.BadRequest)
                    return@post
                }

                // Every exercise has to be one this user can actually see - a
                // built-in or one of their own - not someone else's custom one.
                val allVisible = transaction { request.exerciseIds.all { exerciseVisible(it, me) } }
                if (!allVisible) {
                    call.respondText("one or more of those exercises doesn't exist", status = HttpStatusCode.BadRequest)
                    return@post
                }

                val created = try {
                    transaction {
                        val clash = WorkoutTemplates.selectAll()
                            .where { (WorkoutTemplates.name.lowerCase() eq trimmedName.lowercase()) and visibleTemplates(me) }
                            .limit(1)
                            .any()
                        if (clash) return@transaction null

                        val resolved = resolveTemplateFields(request.exerciseIds)

                        val newTemplateId = WorkoutTemplates.insert {
                            it[name] = trimmedName
                            it[category] = resolved.category
                            it[level] = resolved.level
                            it[estimatedMinutes] = resolved.estimatedMinutes
                            it[notes] = request.notes
                            it[isCustom] = true
                            it[WorkoutTemplates.userId] = me
                        } get WorkoutTemplates.id

                        insertTemplateExercises(newTemplateId, request.exerciseIds)
                        loadTemplateDetail(newTemplateId, me, zone)
                    }
                } catch (e: Exception) {
                    // Most likely a duplicate name - WorkoutTemplates.name has a unique index.
                    null
                }

                if (created == null) {
                    call.respondText("a workout with that name already exists", status = HttpStatusCode.Conflict)
                } else {
                    call.respond(HttpStatusCode.Created, created)
                }
            }

            // Returns one workout template's full detail - name, category,
            // estimated duration, and its exercises in order - for the "tap a
            // workout" detail screen.
            get("/workout-templates/{id}") {
                val me = call.userId()
                val zone = call.userZone()
                val templateId = call.parameters["id"]?.toIntOrNull()
                if (templateId == null) {
                    call.respondText("template id must be a number", status = HttpStatusCode.BadRequest)
                    return@get
                }

                val detail = transaction { loadTemplateDetail(templateId, me, zone) }

                if (detail == null) {
                    call.respondText("no template with that id", status = HttpStatusCode.NotFound)
                } else {
                    call.respond(detail)
                }
            }

            // Deletes one of this user's own custom templates. A built-in
            // template (no owner) is shared by everyone, so it can never be
            // deleted here - it just reports "not found", same as another
            // user's template would.
            delete("/workout-templates/{id}") {
                val me = call.userId()
                val zone = call.userZone()
                val templateId = call.parameters["id"]?.toIntOrNull()
                if (templateId == null) {
                    call.respondText("template id must be a number", status = HttpStatusCode.BadRequest)
                    return@delete
                }

                val deletedCount = transaction {
                    val owned = WorkoutTemplates.selectAll()
                        .where { (WorkoutTemplates.id eq templateId) and (WorkoutTemplates.userId eq me) }
                        .limit(1)
                        .any()
                    if (!owned) return@transaction 0

                    WorkoutTemplateExercises.deleteWhere { WorkoutTemplateExercises.templateId eq templateId }
                    WorkoutTemplates.deleteWhere { (WorkoutTemplates.id eq templateId) and (WorkoutTemplates.userId eq me) }
                }

                if (deletedCount == 0) {
                    call.respondText("no template with that id", status = HttpStatusCode.NotFound)
                } else {
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            // Saves an already-logged workout as a reusable template - its
            // distinct exercises (in the order each first appears) become the
            // template's exercise list. name/notes default to the workout's own
            // if the app doesn't send them.
            post("/workouts/{id}/save-as-template") {
                val me = call.userId()
                val zone = call.userZone()
                val workoutId = call.parameters["id"]?.toIntOrNull()
                if (workoutId == null) {
                    call.respondText("workout id must be a number", status = HttpStatusCode.BadRequest)
                    return@post
                }

                val request = try {
                    call.receive<SaveWorkoutAsTemplateRequest>()
                } catch (e: Exception) {
                    SaveWorkoutAsTemplateRequest()
                }

                val result = try {
                    transaction {
                        val workoutRow = Workouts.selectAll()
                            .where { (Workouts.id eq workoutId) and (Workouts.userId eq me) }
                            .singleOrNull()
                            ?: return@transaction "not_found"

                        // Distinct exercise ids, in the order each first appears -
                        // same ordering approach as /workouts/summary's tag list.
                        val exerciseIds = SetEntries.selectAll()
                            .where { SetEntries.workoutId eq workoutId }
                            .orderBy(SetEntries.setNumber)
                            .map { it[SetEntries.exerciseId] }
                            .distinct()

                        if (exerciseIds.isEmpty()) return@transaction "empty"

                        val trimmedName = (request.name ?: workoutRow[Workouts.workoutName]).trim()
                        if (trimmedName.isEmpty()) return@transaction "empty"

                        val clash = WorkoutTemplates.selectAll()
                            .where { (WorkoutTemplates.name.lowerCase() eq trimmedName.lowercase()) and visibleTemplates(me) }
                            .limit(1)
                            .any()
                        if (clash) return@transaction "duplicate"

                        val resolved = resolveTemplateFields(exerciseIds)

                        val newTemplateId = WorkoutTemplates.insert {
                            it[name] = trimmedName
                            it[category] = resolved.category
                            it[level] = resolved.level
                            it[estimatedMinutes] = resolved.estimatedMinutes
                            it[notes] = request.notes ?: workoutRow[Workouts.notes]
                            it[isCustom] = true
                            it[WorkoutTemplates.userId] = me
                        } get WorkoutTemplates.id

                        insertTemplateExercises(newTemplateId, exerciseIds)
                        newTemplateId
                    }
                } catch (e: Exception) {
                    "duplicate"
                }

                when (result) {
                    "not_found" -> call.respondText("no workout with that id", status = HttpStatusCode.NotFound)
                    "empty" -> call.respondText("that workout has no exercises to save", status = HttpStatusCode.BadRequest)
                    "duplicate" -> call.respondText("a workout with that name already exists", status = HttpStatusCode.Conflict)
                    is Int -> {
                        val detail = transaction { loadTemplateDetail(result, me, zone) }
                        call.respond(HttpStatusCode.Created, detail!!)
                    }
                    else -> call.respondText("couldn't save this workout as a template", status = HttpStatusCode.InternalServerError)
                }
            }

            // Returns every workout, most recently started first.
            get("/workouts") {
                val me = call.userId()
                val zone = call.userZone()
                val workouts = transaction {
                    Workouts.selectAll()
                        .where { Workouts.userId eq me }
                        .orderBy(Workouts.startedAt, SortOrder.DESC)
                        .map { row ->
                            WorkoutResponse(
                                id = row[Workouts.id],
                                workoutName = row[Workouts.workoutName],
                                startedAt = row[Workouts.startedAt].toZone(zone).toString(),
                                endedAt = row[Workouts.endedAt]?.toZone(zone)?.toString(),
                                notes = row[Workouts.notes]
                            )
                        }
                }
                call.respond(workouts)
            }

            // Returns every *finished* workout, summarized for the home screen's
            // "Previous Workouts" list: duration, total sets, total volume lifted,
            // and the distinct muscle groups trained, most recent first.
            get("/workouts/summary") {
                val me = call.userId()
                val zone = call.userZone()
                val summaries = transaction {
                    // Now a real value from the athlete profile instead of a
                    // hardcoded guess - falls back to the old guess only if the
                    // profile row is somehow missing.
                    val userBodyweightKg = AthleteProfile.selectAll()
                        .where { AthleteProfile.userId eq me }
                        .singleOrNull()
                        ?.get(AthleteProfile.weightKg)
                        ?: 83.0

                    Workouts.selectAll()
                        .where { Workouts.userId eq me }
                        .orderBy(Workouts.startedAt, SortOrder.DESC)
                        .mapNotNull { workoutRow ->
                            // Skip workouts that were started but never ended - there's
                            // nothing meaningful to summarize yet for those.
                            val endedAt = workoutRow[Workouts.endedAt]?.toZone(zone) ?: return@mapNotNull null
                            val workoutId = workoutRow[Workouts.id]
                            val startedAt = workoutRow[Workouts.startedAt].toZone(zone)

                            val sets = SetEntries.selectAll()
                                .where { SetEntries.workoutId eq workoutId }
                                .toList()

                            val totalSets = sets.size

                            // Look up each distinct exercise used in this workout once -
                            // its muscle group feeds the tags below, and its equipment
                            // tells us whether it's a bodyweight movement, for the volume
                            // calc right after. A small handful of lookups per workout is
                            // fine at this app's scale.
                            val exerciseById = sets.map { it[SetEntries.exerciseId] }.distinct()
                                .mapNotNull { exerciseId ->
                                    Exercises.selectAll()
                                        .where { Exercises.id eq exerciseId }
                                        .singleOrNull()
                                        ?.let { row -> exerciseId to row }
                                }
                                .toMap()

                            // A bodyweight exercise (equipment == "Body Only") still counts
                            // as real training volume even with nothing added, so the
                            // athlete's actual bodyweight (fetched above) is folded in here
                            // rather than only counting added weight.
                            val totalVolume = sets.sumOf { set ->
                                val equipment = exerciseById[set[SetEntries.exerciseId]]?.get(Exercises.equipment)
                                val isBodyweight = equipment.equals("Body Only", ignoreCase = true)
                                val addedWeight = set[SetEntries.weightKg] ?: 0.0
                                val load = if (isBodyweight) userBodyweightKg + addedWeight else addedWeight
                                load * set[SetEntries.reps]
                            }

                            val tags = exerciseById.values
                                .mapNotNull { row -> row[Exercises.muscleGroup] }
                                .map { it.uppercase() }
                                .distinct()

                            val minutes = Duration.between(startedAt, endedAt).toMinutes()
                            val durationLabel = if (minutes >= 60) {
                                "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}m"
                            } else {
                                "${minutes}m"
                            }

                            val today = LocalDate.now(zone)
                            val startedDate = startedAt.toLocalDate()
                            val dateLabel = when (startedDate) {
                                today -> "TODAY"
                                today.minusDays(1) -> "YESTERDAY"
                                else -> startedAt.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)).uppercase()
                            }

                            val volumeLabel = "%,.0f KG".format(totalVolume)

                            WorkoutSummaryResponse(
                                id = workoutId,
                                dateLabel = dateLabel,
                                date = startedDate.toString(),
                                title = workoutRow[Workouts.workoutName],
                                duration = durationLabel,
                                totalSets = totalSets,
                                exerciseCount = exerciseById.size,
                                volumeLabel = volumeLabel,
                                tags = tags
                            )
                        }
                }
                call.respond(summaries)
            }

            // Returns one workout plus every set logged against it.
            get("/workouts/{id}") {
                val me = call.userId()
                val zone = call.userZone()
                val workoutId = call.parameters["id"]?.toIntOrNull()
                if (workoutId == null) {
                    call.respondText("workout id must be a number", status = HttpStatusCode.BadRequest)
                    return@get
                }

                val workoutDetail = transaction {
                    val workoutRow = Workouts.selectAll()
                        .where { (Workouts.id eq workoutId) and (Workouts.userId eq me) }
                        .singleOrNull()
                        ?: return@transaction null

                    val sets = SetEntries.selectAll()
                        .where { SetEntries.workoutId eq workoutId }
                        .orderBy(SetEntries.setNumber)
                        .map { row ->
                            SetEntryResponse(
                                id = row[SetEntries.id],
                                workoutId = row[SetEntries.workoutId],
                                exerciseId = row[SetEntries.exerciseId],
                                setNumber = row[SetEntries.setNumber],
                                reps = row[SetEntries.reps],
                                weightKg = row[SetEntries.weightKg]
                            )
                        }

                    WorkoutDetailResponse(
                        id = workoutRow[Workouts.id],
                        workoutName = workoutRow[Workouts.workoutName],
                        startedAt = workoutRow[Workouts.startedAt].toZone(zone).toString(),
                        endedAt = workoutRow[Workouts.endedAt]?.toZone(zone)?.toString(),
                        notes = workoutRow[Workouts.notes],
                        sets = sets
                    )
                }

                if (workoutDetail == null) {
                    call.respondText("no workout with that id", status = HttpStatusCode.NotFound)
                } else {
                    call.respond(workoutDetail)
                }
            }

            // Starts a new workout. Example body: {"workoutName":"Push Day"}
            post("/workouts") {
                val me = call.userId()
                val zone = call.userZone()
                val request = call.receive<NewWorkoutRequest>()
                val now = nowUtc()

                val newWorkout = transaction {
                    val newId = Workouts.insert {
                        it[workoutName] = request.workoutName
                        it[startedAt] = now
                        it[Workouts.userId] = me
                    } get Workouts.id

                    WorkoutResponse(
                        id = newId,
                        workoutName = request.workoutName,
                        startedAt = now.toZone(zone).toString(),
                        endedAt = null,
                        notes = null
                    )
                }

                call.respond(newWorkout)
            }

            // Logs one set against an existing workout.
            // Example: POST /workouts/1/sets  with body {"exerciseId":3,"setNumber":1,"reps":10,"weightKg":40.0}
            post("/workouts/{id}/sets") {
                val me = call.userId()
                val zone = call.userZone()
                val workoutId = call.parameters["id"]?.toIntOrNull()
                if (workoutId == null) {
                    call.respondText("workout id must be a number", status = HttpStatusCode.BadRequest)
                    return@post
                }

                val request = call.receive<NewSetRequest>()

                val newSet = transaction {
                    // The workout must be this user's, and the exercise one they
                    // can see - otherwise a guessed id could attach a set to
                    // someone else's workout.
                    if (!ownsWorkout(workoutId, me)) return@transaction null
                    if (!exerciseVisible(request.exerciseId, me)) return@transaction null

                    val newId = SetEntries.insert {
                        it[SetEntries.workoutId] = workoutId
                        it[exerciseId] = request.exerciseId
                        it[setNumber] = request.setNumber
                        it[reps] = request.reps
                        it[weightKg] = request.weightKg
                        it[createdAt] = nowUtc()
                    } get SetEntries.id

                    SetEntryResponse(
                        id = newId,
                        workoutId = workoutId,
                        exerciseId = request.exerciseId,
                        setNumber = request.setNumber,
                        reps = request.reps,
                        weightKg = request.weightKg
                    )
                }

                if (newSet == null) {
                    call.respondText("no workout or exercise with that id", status = HttpStatusCode.NotFound)
                    return@post
                }

                call.respond(newSet)
            }

            // Marks a workout as finished (sets endedAt, and optionally notes) and
            // returns the updated workout. The body is optional - ending a workout
            // with no notes is fine too.
            post("/workouts/{id}/end") {
                val me = call.userId()
                val zone = call.userZone()
                val workoutId = call.parameters["id"]?.toIntOrNull()
                if (workoutId == null) {
                    call.respondText("workout id must be a number", status = HttpStatusCode.BadRequest)
                    return@post
                }

                // The body is optional, so if there's none (or it doesn't parse),
                // just treat it as "no notes" instead of failing the request.
                val request = try {
                    call.receive<EndWorkoutRequest>()
                } catch (e: Exception) {
                    null
                }

                val updatedWorkout = transaction {
                    Workouts.update({ (Workouts.id eq workoutId) and (Workouts.userId eq me) }) {
                        it[endedAt] = nowUtc()
                        if (request?.notes != null) {
                            it[notes] = request.notes
                        }
                    }

                    Workouts.selectAll().where { (Workouts.id eq workoutId) and (Workouts.userId eq me) }.map { row ->
                        WorkoutResponse(
                            id = row[Workouts.id],
                            workoutName = row[Workouts.workoutName],
                            startedAt = row[Workouts.startedAt].toZone(zone).toString(),
                            endedAt = row[Workouts.endedAt]?.toZone(zone)?.toString(),
                            notes = row[Workouts.notes]
                        )
                    }.singleOrNull()
                }

                if (updatedWorkout == null) {
                    call.respondText("no workout with that id", status = HttpStatusCode.NotFound)
                } else {
                    call.respond(updatedWorkout)
                }
            }

            // Permanently deletes a workout and every set logged against it.
            // Set entries have to go first - they reference the workout via a
            // foreign key, so the workout row can't be removed while they still
            // point at it.
            delete("/workouts/{id}") {
                val me = call.userId()
                val zone = call.userZone()
                val workoutId = call.parameters["id"]?.toIntOrNull()
                if (workoutId == null) {
                    call.respondText("workout id must be a number", status = HttpStatusCode.BadRequest)
                    return@delete
                }

                val deletedCount = transaction {
                    if (!ownsWorkout(workoutId, me)) return@transaction 0

                    SetEntries.deleteWhere { SetEntries.workoutId eq workoutId }
                    Workouts.deleteWhere { (Workouts.id eq workoutId) and (Workouts.userId eq me) }
                }

                if (deletedCount == 0) {
                    call.respondText("no workout with that id", status = HttpStatusCode.NotFound)
                } else {
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            // Edits an already-finished workout's name and/or notes. Never
            // touches startedAt/endedAt - the date, time and duration a workout
            // was actually done in are not editable.
            patch("/workouts/{id}") {
                val me = call.userId()
                val zone = call.userZone()
                val workoutId = call.parameters["id"]?.toIntOrNull()
                if (workoutId == null) {
                    call.respondText("workout id must be a number", status = HttpStatusCode.BadRequest)
                    return@patch
                }

                val request = try {
                    call.receive<UpdateWorkoutRequest>()
                } catch (e: Exception) {
                    call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
                    return@patch
                }

                val updatedWorkout = transaction {
                    Workouts.update({ (Workouts.id eq workoutId) and (Workouts.userId eq me) }) {
                        if (request.workoutName != null) {
                            it[workoutName] = request.workoutName
                        }
                        if (request.notes != null) {
                            it[notes] = request.notes
                        }
                    }

                    Workouts.selectAll().where { (Workouts.id eq workoutId) and (Workouts.userId eq me) }.map { row ->
                        WorkoutResponse(
                            id = row[Workouts.id],
                            workoutName = row[Workouts.workoutName],
                            startedAt = row[Workouts.startedAt].toZone(zone).toString(),
                            endedAt = row[Workouts.endedAt]?.toZone(zone)?.toString(),
                            notes = row[Workouts.notes]
                        )
                    }.singleOrNull()
                }

                if (updatedWorkout == null) {
                    call.respondText("no workout with that id", status = HttpStatusCode.NotFound)
                } else {
                    call.respond(updatedWorkout)
                }
            }

            // Edits one already-logged set's reps/weight.
            put("/sets/{id}") {
                val me = call.userId()
                val zone = call.userZone()
                val setId = call.parameters["id"]?.toIntOrNull()
                if (setId == null) {
                    call.respondText("set id must be a number", status = HttpStatusCode.BadRequest)
                    return@put
                }

                val request = try {
                    call.receive<UpdateSetRequest>()
                } catch (e: Exception) {
                    call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
                    return@put
                }

                val updatedSet = transaction {
                    if (!ownsSet(setId, me)) return@transaction null

                    val updatedRows = SetEntries.update({ SetEntries.id eq setId }) {
                        it[reps] = request.reps
                        it[weightKg] = request.weightKg
                    }
                    if (updatedRows == 0) return@transaction null

                    SetEntries.selectAll().where { SetEntries.id eq setId }.map { row ->
                        SetEntryResponse(
                            id = row[SetEntries.id],
                            workoutId = row[SetEntries.workoutId],
                            exerciseId = row[SetEntries.exerciseId],
                            setNumber = row[SetEntries.setNumber],
                            reps = row[SetEntries.reps],
                            weightKg = row[SetEntries.weightKg]
                        )
                    }.singleOrNull()
                }

                if (updatedSet == null) {
                    call.respondText("no set with that id", status = HttpStatusCode.NotFound)
                } else {
                    call.respond(updatedSet)
                }
            }

            // Removes a single set entry, e.g. when the user deletes one row
            // while editing a past workout.
            delete("/sets/{id}") {
                val me = call.userId()
                val zone = call.userZone()
                val setId = call.parameters["id"]?.toIntOrNull()
                if (setId == null) {
                    call.respondText("set id must be a number", status = HttpStatusCode.BadRequest)
                    return@delete
                }

                val deletedCount = transaction {
                    if (!ownsSet(setId, me)) return@transaction 0

                    SetEntries.deleteWhere { SetEntries.id eq setId }
                }

                if (deletedCount == 0) {
                    call.respondText("no set with that id", status = HttpStatusCode.NotFound)
                } else {
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            // Removes an entire exercise (every set logged against it) from one
            // workout in a single call, so editing a past workout doesn't need
            // to delete each of that exercise's sets one at a time.
            delete("/workouts/{workoutId}/exercises/{exerciseId}") {
                val me = call.userId()
                val zone = call.userZone()
                val workoutId = call.parameters["workoutId"]?.toIntOrNull()
                val exerciseId = call.parameters["exerciseId"]?.toIntOrNull()
                if (workoutId == null || exerciseId == null) {
                    call.respondText("workout id and exercise id must both be numbers", status = HttpStatusCode.BadRequest)
                    return@delete
                }

                val deletedCount = transaction {
                    if (!ownsWorkout(workoutId, me)) return@transaction 0

                    SetEntries.deleteWhere { (SetEntries.workoutId eq workoutId) and (SetEntries.exerciseId eq exerciseId) }
                }

                if (deletedCount == 0) {
                    call.respondText("no matching sets for that workout/exercise", status = HttpStatusCode.NotFound)
                } else {
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            // Returns the athlete profile screen's data.
            get("/profile") {
                val me = call.userId()
                val zone = call.userZone()
                val response = loadProfileResponse(me, zone)
                if (response == null) {
                    call.respondText("no profile found", status = HttpStatusCode.NotFound)
                } else {
                    call.respond(response)
                }
            }

            // Edits the profile. Any field left null in the request keeps its
            // current value. Editing weight snapshots the old value into
            // previousWeightKg first, so the profile screen can show a delta
            // like "-0.4 KG" the next time it loads.
            patch("/profile") {
                val me = call.userId()
                val zone = call.userZone()
                val request = try {
                    call.receive<UpdateProfileRequest>()
                } catch (e: Exception) {
                    call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
                    return@patch
                }

                val updated = transaction {
                    val current = AthleteProfile.selectAll().where { AthleteProfile.userId eq me }.singleOrNull()
                        ?: return@transaction false

                    // Finishing onboarding replaces the placeholder values, so
                    // the weight it sets is a starting point, not a change -
                    // no "previous weight" delta should come out of it.
                    val finishingOnboarding = request.onboardingComplete == true && !current[AthleteProfile.onboardingComplete]

                    AthleteProfile.update({ AthleteProfile.userId eq me }) {
                        if (request.name != null) { it[name] = request.name }
                        if (request.age != null) { it[age] = request.age }
                        if (finishingOnboarding) {
                            if (request.weightKg != null) { it[weightKg] = request.weightKg }
                            it[previousWeightKg] = null
                            it[onboardingComplete] = true
                        } else if (request.weightKg != null && request.weightKg != current[AthleteProfile.weightKg]) {
                            it[previousWeightKg] = current[AthleteProfile.weightKg]
                            it[weightKg] = request.weightKg
                        }
                        if (request.targetWeightKg != null) { it[targetWeightKg] = request.targetWeightKg }
                        if (request.heightCm != null) { it[heightCm] = request.heightCm }
                        if (request.bodyFatPercent != null) { it[bodyFatPercent] = request.bodyFatPercent }
                        if (request.weeklyWorkoutTarget != null) { it[weeklyWorkoutTarget] = request.weeklyWorkoutTarget }
                    }
                    true
                }

                if (!updated) {
                    call.respondText("no profile found", status = HttpStatusCode.NotFound)
                    return@patch
                }

                call.respond(loadProfileResponse(me, zone)!!)
            }

            // Saves a finished workout in one go: replaces whatever sets the
            // workout has with the ones sent, stores the notes and marks it
            // ended - all in one transaction, so it either fully saves or
            // not at all. Sending the same request again (a retry after a
            // dropped connection) gives the same result rather than
            // duplicate sets, and keeps the original end time.
            post("/workouts/{id}/complete") {
                val me = call.userId()
                val zone = call.userZone()
                val workoutId = call.parameters["id"]?.toIntOrNull()
                if (workoutId == null) {
                    call.respondText("workout id must be a number", status = HttpStatusCode.BadRequest)
                    return@post
                }

                val request = try {
                    call.receive<CompleteWorkoutRequest>()
                } catch (e: Exception) {
                    call.respondText("invalid request body", status = HttpStatusCode.BadRequest)
                    return@post
                }

                val validSets = request.sets.size <= 1000 && request.sets.all { set ->
                    set.reps in 0..10_000 && set.setNumber in 1..1000 &&
                        (set.weightKg == null || set.weightKg in 0.0..2_000.0)
                }
                if (!validSets) {
                    call.respondText("one or more sets are invalid", status = HttpStatusCode.BadRequest)
                    return@post
                }

                val allVisible = transaction {
                    request.sets.map { it.exerciseId }.distinct().all { exerciseVisible(it, me) }
                }
                if (!allVisible) {
                    call.respondText("one or more of those exercises doesn't exist", status = HttpStatusCode.BadRequest)
                    return@post
                }

                val completed = transaction {
                    val workoutRow = Workouts.selectAll()
                        .where { (Workouts.id eq workoutId) and (Workouts.userId eq me) }
                        .singleOrNull()
                        ?: return@transaction null

                    val now = nowUtc()
                    SetEntries.deleteWhere { SetEntries.workoutId eq workoutId }
                    request.sets.forEach { set ->
                        SetEntries.insert {
                            it[SetEntries.workoutId] = workoutId
                            it[exerciseId] = set.exerciseId
                            it[setNumber] = set.setNumber
                            it[reps] = set.reps
                            it[weightKg] = set.weightKg
                            it[createdAt] = now
                        }
                    }

                    val endedAt = workoutRow[Workouts.endedAt] ?: now
                    val notes = request.notes?.trim()?.takeIf { it.isNotEmpty() }?.take(500)
                    Workouts.update({ (Workouts.id eq workoutId) and (Workouts.userId eq me) }) {
                        it[Workouts.endedAt] = endedAt
                        it[Workouts.notes] = notes
                    }

                    WorkoutResponse(
                        id = workoutId,
                        workoutName = workoutRow[Workouts.workoutName],
                        startedAt = workoutRow[Workouts.startedAt].toZone(zone).toString(),
                        endedAt = endedAt.toZone(zone).toString(),
                        notes = notes
                    )
                }

                if (completed == null) {
                    call.respondText("no workout with that id", status = HttpStatusCode.NotFound)
                } else {
                    call.respond(completed)
                }
            }

            // Permanently deletes this user's account and everything they own:
            // every workout (and its sets), their custom templates and
            // exercises, their profile, and the user row itself. Google Play
            // requires an in-app way to do this. The app should call this FIRST
            // and then delete the Firebase account (FirebaseUser.delete()), so a
            // failure here never leaves a login with no data behind it.
            delete("/account") {
                val me = call.userId()
                val zone = call.userZone()
                deleteAccount(me)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/**
 * One-time schema fix: set_entries.weight_kg was originally created NOT NULL,
 * back before a set with no weight logged (e.g. bodyweight, nothing added)
 * was a thing this app needed to support. SchemaUtils.create() only creates
 * tables that don't exist yet - it never loosens a constraint on a table
 * that's already there - so this runs a plain SQL ALTER TABLE instead.
 *
 * Safe to run on every startup: dropping a NOT NULL constraint that's already
 * gone (a fresh database, or a redeploy after this has already run once) is a
 * harmless no-op in Postgres, not an error.
 */
private fun migrateSetEntriesWeightNullable() {
    DriverManager.getConnection(databaseUrl(), env("PGUSER", "postgres"), env("PGPASSWORD", "postgres")).use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("ALTER TABLE set_entries ALTER COLUMN weight_kg DROP NOT NULL")
        }
    }
}

/**
 * One-time schema fix: adds the "category" (Cardio/Strength/Calisthenics)
 * column to a database whose exercises table predates this field - same
 * reasoning as migrateSetEntriesWeightNullable(), SchemaUtils.create()
 * never alters a table that already exists. Also backfills every row this
 * column left null: existing rows default to "Strength", except a handful
 * of cardio-machine/cardio-activity exercises that were already in the seed
 * data tagged with a muscle group (e.g. "Treadmill running" under
 * Quadriceps) - those get flipped to "Cardio" by name.
 *
 * Also widens the column to VARCHAR(30) - it started at 20 chars, which is
 * enough for "Strength/Calisthenics" once the calisthenics dataset merge
 * added that value.
 *
 * Safe to run on every startup: the ALTER is a no-op once the column
 * exists at the right width, and the backfill only ever touches rows that
 * are still null.
 */
private fun migrateExercisesCategory() {
    DriverManager.getConnection(databaseUrl(), env("PGUSER", "postgres"), env("PGPASSWORD", "postgres")).use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("ALTER TABLE exercises ADD COLUMN IF NOT EXISTS category VARCHAR(30)")
            statement.execute("ALTER TABLE exercises ALTER COLUMN category TYPE VARCHAR(30)")
            statement.execute("UPDATE exercises SET category = 'Strength' WHERE category IS NULL")
            statement.execute(
                "UPDATE exercises SET category = 'Cardio' WHERE name IN (" +
                    "'Bicycling', 'Elliptical trainer', 'Trail Running/Walking', " +
                    "'Treadmill running', 'Treadmill jogging')"
            )
        }
    }
}

/**
 * Tries to open a real connection to the database, retrying with a short delay
 * between attempts, instead of letting the very first failure crash the app.
 * Covers the case where Postgres (a separate Railway service) is still booting,
 * restarting, or briefly unreachable when this app container starts up.
 */
private fun waitForDatabase(maxAttempts: Int = 15, delayMillis: Long = 2000) {
    var lastError: Exception? = null

    for (attempt in 1..maxAttempts) {
        try {
            // A plain JDBC connection - proves Postgres is actually reachable,
            // not just that Database.connect() was called (that call alone
            // never touches the network, it only configures the JDBC settings).
            DriverManager.getConnection(
                databaseUrl(),
                env("PGUSER", "postgres"),
                env("PGPASSWORD", "postgres")
            ).close()
            println("Connected to the database on attempt $attempt.")
            return
        } catch (e: Exception) {
            lastError = e
            println("Database not ready yet (attempt $attempt/$maxAttempts): ${e.message}")
            Thread.sleep(delayMillis)
        }
    }

    throw IllegalStateException(
        "Could not connect to the database after $maxAttempts attempts.",
        lastError
    )
}

/** One row of exercises_seed.csv. */
private data class SeedExercise(val name: String, val muscleGroup: String?, val equipment: String?, val category: String)

/** Parses exercises_seed.csv (bundled in src/main/resources) - empty if the
 *  file isn't there. */
private fun readSeedExercises(): List<SeedExercise> {
    val inputStream = Thread.currentThread().contextClassLoader.getResourceAsStream("exercises_seed.csv")
        ?: return emptyList()

    val lines = BufferedReader(InputStreamReader(inputStream)).use { it.readLines() }
    // first line is the header: name,muscle_group,equipment,category
    return lines.drop(1).mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null

        val parts = line.split(",")
        val exerciseName = parts.getOrNull(0)?.trim().orEmpty()
        if (exerciseName.isEmpty()) return@mapNotNull null

        SeedExercise(
            name = exerciseName,
            muscleGroup = parts.getOrNull(1)?.trim().orEmpty().ifEmpty { null },
            equipment = parts.getOrNull(2)?.trim().orEmpty().ifEmpty { null },
            // Older copies of this CSV (before the Cardio/Strength field was
            // added) won't have a 4th column at all - default those to
            // Strength rather than leaving every seeded row uncategorized.
            category = parts.getOrNull(3)?.trim().orEmpty().ifEmpty { "Strength" }
        )
    }
}

/**
 * Loads exercises_seed.csv into the Exercises table as the built-in library
 * (no owner). Safe to call on every startup - it only inserts names that
 * aren't already a built-in, so re-deploying never creates duplicates. (This
 * used to lean on a global unique index on name; that's gone now that two
 * users may each have a custom exercise with the same name, so the check
 * happens here instead, backed by a partial unique index.)
 */
private fun seedExercisesIfNeeded() {
    val seeds = readSeedExercises()
    if (seeds.isEmpty()) return

    transaction {
        val existing = Exercises.selectAll()
            .where { Exercises.userId.isNull() }
            .map { it[Exercises.name] }
            .toHashSet()

        for (seed in seeds) {
            if (!existing.add(seed.name)) continue

            Exercises.insert {
                it[name] = seed.name
                it[muscleGroup] = seed.muscleGroup
                it[equipment] = seed.equipment
                it[category] = seed.category
            }
        }
    }
}

/** category/estimatedMinutes/level, all resolved purely from a template's
 *  exercises - see [resolveTemplateFields]. */
private data class ResolvedTemplateFields(val category: String, val estimatedMinutes: Int, val level: String)

/**
 * Figures out a template's category, estimated duration, and difficulty
 * level purely from the exercises it's made of - the app never sends any of
 * these directly.
 *
 * Category: each exercise's own category (Strength, Cardio, Calisthenics,
 * Strength/Calisthenics, Mobility/Flexibility) is bucketed into one of the
 * four labels the Workouts tab already shows, and whichever bucket has the
 * most exercises wins - ties break in a fixed order (Strength Training,
 * Calisthenics, Cardio, Mobility/Flexibility) so the result is deterministic.
 *
 * Estimated minutes: exercise count times a per-exercise minute figure for
 * the resolved category (roughly matching the built-in templates - e.g. Push
 * Day's 5 strength exercises at ~10 min each comes to 50).
 *
 * Level: thresholds on the resolved estimated minutes - 45+ is "Advanced",
 * 25+ is "Intermediate", anything shorter is "Beginner". A rough proxy for
 * how much a workout demands, without any per-exercise difficulty data to
 * draw on.
 *
 * Must be called from inside an existing transaction { } block.
 */
private fun resolveTemplateFields(exerciseIds: List<Int>): ResolvedTemplateFields {
    val rawCategories = exerciseIds.mapNotNull { exerciseId ->
        Exercises.selectAll().where { Exercises.id eq exerciseId }.singleOrNull()?.get(Exercises.category)
    }

    fun bucketFor(rawCategory: String?): String = when (rawCategory) {
        "Cardio" -> "Cardio"
        "Calisthenics", "Strength/Calisthenics" -> "Calisthenics"
        "Mobility/Flexibility" -> "Mobility/Flexibility"
        else -> "Strength Training" // "Strength", null, or anything unrecognized
    }

    val counts = rawCategories.map { bucketFor(it) }.groupingBy { it }.eachCount()
    val priorityOrder = listOf("Strength Training", "Calisthenics", "Cardio", "Mobility/Flexibility")
    val resolvedCategory = priorityOrder
        .filter { counts.containsKey(it) }
        .maxByOrNull { counts.getValue(it) }
        ?: "Strength Training" // no exercises resolved to a category at all (e.g. all ids were invalid)

    val minutesPerExercise = when (resolvedCategory) {
        "Strength Training" -> 10
        "Calisthenics" -> 8
        "Cardio" -> 6
        else -> 4 // Mobility/Flexibility
    }
    val estimatedMinutes = (exerciseIds.size * minutesPerExercise).coerceAtLeast(10)

    val resolvedLevel = when {
        estimatedMinutes >= 45 -> "Advanced"
        estimatedMinutes >= 25 -> "Intermediate"
        else -> "Beginner"
    }

    return ResolvedTemplateFields(resolvedCategory, estimatedMinutes, resolvedLevel)
}

/** Up to 3 distinct muscle groups (uppercased) trained by these exercises,
 *  in the order each first appears - e.g. ["CHEST", "SHOULDERS", "TRICEPS"].
 *  Must be called from inside an existing transaction { } block. */
private fun computeMuscleGroups(exerciseIds: List<Int>): List<String> {
    return exerciseIds
        .mapNotNull { exerciseId ->
            Exercises.selectAll().where { Exercises.id eq exerciseId }.singleOrNull()?.get(Exercises.muscleGroup)
        }
        .map { it.uppercase() }
        .distinct()
        .take(3)
}

/** Days since the most recent *finished* workout with this exact name was
 *  logged by this user (0 = today), or null if they never have. Matches purely by
 *  name, since workouts aren't otherwise linked back to the template they
 *  were started from. Must be called from inside an existing transaction { } block. */
private fun computeLastLoggedDaysAgo(templateName: String, me: Int, zone: ZoneId): Int? {
    val lastStartedAt = Workouts.selectAll()
        .where { (Workouts.workoutName eq templateName) and (Workouts.endedAt.isNotNull()) and (Workouts.userId eq me) }
        .orderBy(Workouts.startedAt, SortOrder.DESC)
        .limit(1)
        .singleOrNull()
        ?.get(Workouts.startedAt)
        ?: return null

    return ChronoUnit.DAYS.between(lastStartedAt.toZone(zone).toLocalDate(), LocalDate.now(zone)).toInt().coerceAtLeast(0)
}

/** Inserts one row per exercise into WorkoutTemplateExercises, in the given
 *  order. Must be called from inside an existing transaction { } block. */
private fun insertTemplateExercises(templateId: Int, exerciseIds: List<Int>) {
    exerciseIds.forEachIndexed { index, exerciseId ->
        WorkoutTemplateExercises.insert {
            it[WorkoutTemplateExercises.templateId] = templateId
            it[WorkoutTemplateExercises.exerciseId] = exerciseId
            it[position] = index
        }
    }
}

/** Loads one template's full detail - shared by GET /workout-templates/{id}
 *  and the two "create a template" routes, which both return the newly
 *  created template the same way it'd be fetched. Must be called from
 *  inside an existing transaction { } block. */
private fun loadTemplateDetail(templateId: Int, me: Int, zone: ZoneId): WorkoutTemplateDetailResponse? {
    val templateRow = WorkoutTemplates.selectAll()
        .where { (WorkoutTemplates.id eq templateId) and visibleTemplates(me) }
        .singleOrNull()
        ?: return null

    val exercises = (WorkoutTemplateExercises innerJoin Exercises)
        .selectAll()
        .where { WorkoutTemplateExercises.templateId eq templateId }
        .orderBy(WorkoutTemplateExercises.position)
        .map { row ->
            ExerciseResponse(
                id = row[Exercises.id],
                name = row[Exercises.name],
                muscleGroup = row[Exercises.muscleGroup],
                equipment = row[Exercises.equipment],
                category = row[Exercises.category]
            )
        }

    val exerciseIds = exercises.map { it.id }

    return WorkoutTemplateDetailResponse(
        id = templateRow[WorkoutTemplates.id],
        name = templateRow[WorkoutTemplates.name],
        category = templateRow[WorkoutTemplates.category],
        level = templateRow[WorkoutTemplates.level],
        estimatedMinutes = templateRow[WorkoutTemplates.estimatedMinutes],
        muscleGroups = computeMuscleGroups(exerciseIds),
        lastLoggedDaysAgo = computeLastLoggedDaysAgo(templateRow[WorkoutTemplates.name], me, zone),
        notes = templateRow[WorkoutTemplates.notes],
        isCustom = templateRow[WorkoutTemplates.isCustom],
        exercises = exercises
    )
}

/**
 * One-time schema fix: adds the "notes", "is_custom", and "level" columns to
 * a workout_templates table created before these fields existed - same
 * reasoning as migrateExercisesCategory(). Existing (built-in) rows backfill
 * is_custom to false (also the column's default going forward) and level
 * from their already-stored estimated_minutes, using the same thresholds as
 * resolveTemplateFields().
 *
 * Safe to run on every startup: every statement here is a no-op once already
 * applied.
 */
private fun migrateWorkoutTemplatesCustomFields() {
    DriverManager.getConnection(databaseUrl(), env("PGUSER", "postgres"), env("PGPASSWORD", "postgres")).use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("ALTER TABLE workout_templates ADD COLUMN IF NOT EXISTS notes VARCHAR(500)")
            statement.execute("ALTER TABLE workout_templates ADD COLUMN IF NOT EXISTS is_custom BOOLEAN DEFAULT FALSE")
            statement.execute("UPDATE workout_templates SET is_custom = FALSE WHERE is_custom IS NULL")
            statement.execute("ALTER TABLE workout_templates ADD COLUMN IF NOT EXISTS level VARCHAR(20) DEFAULT 'Intermediate'")
            statement.execute(
                "UPDATE workout_templates SET level = CASE " +
                    "WHEN estimated_minutes >= 45 THEN 'Advanced' " +
                    "WHEN estimated_minutes >= 25 THEN 'Intermediate' " +
                    "ELSE 'Beginner' END " +
                    "WHERE level IS NULL"
            )
        }
    }
}

/** Same reasoning as the other migrate*() functions - adds the profile's
 *  weekly workout target column for anyone whose database predates it,
 *  backfilling the existing single profile row to the same default (6) the
 *  table definition gives a brand-new one. */
private fun migrateAthleteProfileWeeklyTarget() {
    DriverManager.getConnection(databaseUrl(), env("PGUSER", "postgres"), env("PGPASSWORD", "postgres")).use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("ALTER TABLE athlete_profile ADD COLUMN IF NOT EXISTS weekly_workout_target INTEGER DEFAULT 6")
            statement.execute("UPDATE athlete_profile SET weekly_workout_target = 6 WHERE weekly_workout_target IS NULL")
        }
    }
}

/**
 * One-time seed for the Workouts tab: a handful of ready-made template
 * workouts (Push/Pull/Legs, Full Body Calisthenics, Cardio Conditioning, a
 * Mobility flow) spanning the categories the app already tags exercises
 * with. Every exercise name below is looked up against the already-seeded
 * Exercises table by exact name; a name that doesn't match anything (e.g. if
 * the CSV is ever edited) is just skipped rather than failing the whole seed.
 *
 * Safe to run on every startup - templates are matched by name (unique
 * index), so re-running this after the first deploy inserts nothing new.
 */
private fun seedWorkoutTemplatesIfNeeded() {
    data class TemplateSeed(val name: String, val category: String, val estimatedMinutes: Int, val exerciseNames: List<String>)

    val templates = listOf(
        TemplateSeed(
            name = "Push Day",
            category = "Strength Training",
            estimatedMinutes = 50,
            exerciseNames = listOf(
                "Barbell Bench Press - Medium Grip",
                "Military press",
                "Incline dumbbell bench press",
                "Triceps Pushdown",
                "Dip"
            )
        ),
        TemplateSeed(
            name = "Pull Day",
            category = "Strength Training",
            estimatedMinutes = 50,
            exerciseNames = listOf(
                "Barbell Deadlift",
                "Bent Over Barbell Row",
                "Seated Cable Rows",
                "Pull Up",
                "Hanging leg raise"
            )
        ),
        TemplateSeed(
            name = "Leg Day",
            category = "Strength Training",
            estimatedMinutes = 55,
            exerciseNames = listOf(
                "Barbell Squat",
                "Leg Press",
                "Leg Extensions",
                "Lying Leg Curls",
                "Standing Calf Raises"
            )
        ),
        TemplateSeed(
            name = "Full Body Calisthenics",
            category = "Calisthenics",
            estimatedMinutes = 40,
            exerciseNames = listOf(
                "Push Up",
                "Pull Up",
                "Dip",
                "Pike Push Up",
                "Mountain climber"
            )
        ),
        TemplateSeed(
            name = "Cardio Conditioning",
            category = "Cardio",
            estimatedMinutes = 30,
            exerciseNames = listOf(
                "Treadmill running",
                "Jump Rope",
                "Burpee",
                "Mountain climber",
                "Bicycling"
            )
        ),
        TemplateSeed(
            name = "Mobility & Flexibility Flow",
            category = "Mobility/Flexibility",
            estimatedMinutes = 20,
            exerciseNames = listOf(
                "Cat-Cow Stretch",
                "Downward Dog",
                "World's Greatest Stretch",
                "Kneeling Hip Flexor Stretch",
                "Arm Circles"
            )
        )
    )

    transaction {
        for (template in templates) {
            val alreadySeeded = WorkoutTemplates.selectAll()
                .where { (WorkoutTemplates.name eq template.name) and WorkoutTemplates.userId.isNull() }
                .limit(1)
                .any()
            if (alreadySeeded) continue

            val resolvedLevel = when {
                template.estimatedMinutes >= 45 -> "Advanced"
                template.estimatedMinutes >= 25 -> "Intermediate"
                else -> "Beginner"
            }

            val newTemplateId = WorkoutTemplates.insert {
                it[name] = template.name
                it[category] = template.category
                it[level] = resolvedLevel
                it[estimatedMinutes] = template.estimatedMinutes
            } get WorkoutTemplates.id

            template.exerciseNames.forEachIndexed { index, exerciseName ->
                val exerciseId = Exercises.selectAll()
                    .where { (Exercises.name eq exerciseName) and Exercises.userId.isNull() }
                    .singleOrNull()
                    ?.get(Exercises.id)
                    ?: return@forEachIndexed // name didn't match a seeded exercise - skip it

                WorkoutTemplateExercises.insert {
                    it[templateId] = newTemplateId
                    it[WorkoutTemplateExercises.exerciseId] = exerciseId
                    it[position] = index
                }
            }
        }
    }
}

/**
 * Builds the full /profile payload for one user - the stored fields plus a
 * handful of stats (workouts logged, total volume, BMI, weeks active)
 * computed fresh from their workout history every time this is called.
 * Returns null only if they somehow have no profile row (one is always
 * created the first time an account is seen - see provisionUser()).
 */
private fun loadProfileResponse(me: Int, zone: ZoneId): ProfileResponse? = transaction {
    val profile = AthleteProfile.selectAll().where { AthleteProfile.userId eq me }.singleOrNull()
        ?: return@transaction null

    val bodyweightKg = profile[AthleteProfile.weightKg]

    // Same per-workout volume calc as /workouts/summary, just totaled across
    // every finished workout instead of returned one at a time.
    var workoutsLogged = 0
    var totalVolumeKg = 0.0

    Workouts.selectAll().where { Workouts.userId eq me }.forEach { workoutRow ->
        if (workoutRow[Workouts.endedAt] == null) return@forEach
        workoutsLogged++

        val workoutId = workoutRow[Workouts.id]
        val sets = SetEntries.selectAll().where { SetEntries.workoutId eq workoutId }.toList()

        val exerciseById = sets.map { it[SetEntries.exerciseId] }.distinct()
            .mapNotNull { exerciseId ->
                Exercises.selectAll().where { Exercises.id eq exerciseId }.singleOrNull()
                    ?.let { row -> exerciseId to row }
            }
            .toMap()

        totalVolumeKg += sets.sumOf { set ->
            val equipment = exerciseById[set[SetEntries.exerciseId]]?.get(Exercises.equipment)
            val isBodyweight = equipment.equals("Body Only", ignoreCase = true)
            val addedWeight = set[SetEntries.weightKg] ?: 0.0
            val load = if (isBodyweight) bodyweightKg + addedWeight else addedWeight
            load * set[SetEntries.reps]
        }
    }

    val heightM = profile[AthleteProfile.heightCm] / 100.0
    val bmi = bodyweightKg / (heightM * heightM)

    val memberSinceDate = LocalDate.parse(profile[AthleteProfile.memberSince])
    val weeksActive = ChronoUnit.WEEKS.between(memberSinceDate, LocalDate.now(zone)).toInt().coerceAtLeast(0)

    val previousWeight = profile[AthleteProfile.previousWeightKg]
    val weightDelta = previousWeight?.let { bodyweightKg - it }

    ProfileResponse(
        name = profile[AthleteProfile.name],
        age = profile[AthleteProfile.age],
        weightKg = bodyweightKg,
        weightDeltaKg = weightDelta,
        targetWeightKg = profile[AthleteProfile.targetWeightKg],
        heightCm = profile[AthleteProfile.heightCm],
        bmi = bmi,
        bodyFatPercent = profile[AthleteProfile.bodyFatPercent],
        workoutsLogged = workoutsLogged,
        totalVolumeKg = totalVolumeKg,
        memberSince = profile[AthleteProfile.memberSince],
        weeksActive = weeksActive,
        weeklyWorkoutTarget = profile[AthleteProfile.weeklyWorkoutTarget],
        onboardingComplete = profile[AthleteProfile.onboardingComplete]
    )
}

// ---------------------------------------------------------------------------
// Accounts & per-user data
// ---------------------------------------------------------------------------

/** Firebase uid -> local Users.id, so a request after the first one costs no
 *  database lookup just to learn who's calling. */
private val userIdCache = ConcurrentHashMap<String, Int>()

/** Serializes first-time account setup within this server process. The app
 *  fires several requests in parallel right after login (profile, workouts,
 *  templates...), and all of them would otherwise try to create the same new
 *  user at once. insertIgnore + unique indexes cover the multi-instance case. */
private val provisionLock = Any()

/**
 * The local Users.id of whoever sent this (already authenticated) request.
 * The first time a Firebase account is seen, this creates its Users row and
 * default profile; after that it's served from [userIdCache].
 */
private fun ApplicationCall.userId(): Int {
    val payload = principal<JWTPrincipal>()?.payload
        ?: error("userId() was called on a route that isn't inside authenticate { }")
    val firebaseUid = payload.subject

    userIdCache[firebaseUid]?.let { return it }
    return synchronized(provisionLock) {
        userIdCache[firebaseUid] ?: provisionUser(payload, userZone()).also { userIdCache[firebaseUid] = it }
    }
}

/** Finds - or on first sight creates - the Users row for a verified token,
 *  makes sure it has a profile, and (for the app's owner only) hands over
 *  the data that predates accounts. */
private fun provisionUser(payload: Payload, zone: ZoneId): Int = transaction {
    val firebaseUid = payload.subject
    val email = payload.getClaim("email").asString()
    val emailVerified = payload.getClaim("email_verified").asBoolean() ?: false
    val displayName = payload.getClaim("name").asString()

    Users.insertIgnore {
        it[Users.firebaseUid] = firebaseUid
        it[Users.email] = email
        it[Users.displayName] = displayName
        it[Users.createdAt] = nowUtc()
    }
    val me = Users.selectAll().where { Users.firebaseUid eq firebaseUid }.single()[Users.id]

    // Everything that existed before accounts (one person's workouts,
    // templates, custom exercises and profile) is claimed by whoever signs in
    // with OWNER_EMAIL - and only with a *verified* address, so nobody can
    // take it by registering that email without owning it.
    val ownerEmail = System.getenv("OWNER_EMAIL")?.trim().orEmpty()
    val isOwner = ownerEmail.isNotEmpty() && emailVerified && email != null && email.equals(ownerEmail, ignoreCase = true)
    if (isOwner) {
        claimLegacyData(me)
    }

    val hasProfile = AthleteProfile.selectAll().where { AthleteProfile.userId eq me }.limit(1).any()
    if (!hasProfile) {
        // Placeholder values the person edits on their Profile screen -
        // the profile table needs a value in every one of these columns.
        AthleteProfile.insertIgnore {
            it[AthleteProfile.userId] = me
            it[AthleteProfile.name] = displayName?.takeIf { n -> n.isNotBlank() }
                ?: email?.substringBefore("@")?.takeIf { n -> n.isNotBlank() }
                ?: "Athlete"
            it[AthleteProfile.age] = 25
            it[AthleteProfile.weightKg] = 70.0
            it[AthleteProfile.previousWeightKg] = null
            it[AthleteProfile.targetWeightKg] = null
            it[AthleteProfile.heightCm] = 170.0
            it[AthleteProfile.bodyFatPercent] = null
            it[AthleteProfile.memberSince] = LocalDate.now(zone).toString()
            it[AthleteProfile.onboardingComplete] = false
        }
    }

    me
}

/** Assigns every row that has no owner yet (the pre-accounts data) to [me].
 *  Built-in exercises and templates stay ownerless on purpose - they're
 *  shared. Must be called from inside a transaction { } block. */
private fun claimLegacyData(me: Int) {
    Workouts.update({ Workouts.userId.isNull() }) { it[Workouts.userId] = me }
    WorkoutTemplates.update({ WorkoutTemplates.userId.isNull() and (WorkoutTemplates.isCustom eq true) }) {
        it[WorkoutTemplates.userId] = me
    }

    // The pre-accounts profile row (the owner's real name, weight, height...)
    // wins over whatever placeholder profile this account got if it signed in
    // once before OWNER_EMAIL was set.
    val legacyProfileExists = AthleteProfile.selectAll().where { AthleteProfile.userId.isNull() }.limit(1).any()
    if (legacyProfileExists) {
        AthleteProfile.deleteWhere { AthleteProfile.userId eq me }
        AthleteProfile.update({ AthleteProfile.userId.isNull() }) { it[AthleteProfile.userId] = me }
    }

    // A pre-accounts custom exercise looks just like a built-in one in the
    // table (no owner), so tell them apart by name: anything not in the seed
    // CSV was typed in by the owner.
    val seedNames = readSeedExercises().map { it.name }.toHashSet()
    if (seedNames.isNotEmpty()) {
        val customIds = Exercises.selectAll()
            .where { Exercises.userId.isNull() }
            .filter { it[Exercises.name] !in seedNames }
            .map { it[Exercises.id] }
        customIds.chunked(500).forEach { chunk ->
            Exercises.update({ Exercises.id inList chunk }) { it[Exercises.userId] = me }
        }
    }
}

/** Removes everything a user owns, then the user. Deletion order matters:
 *  sets reference workouts, template rows reference templates, and
 *  everything references the user. */
private fun deleteAccount(me: Int) {
    transaction {
        val workoutIds = Workouts.selectAll().where { Workouts.userId eq me }.map { it[Workouts.id] }
        workoutIds.chunked(1000).forEach { chunk ->
            SetEntries.deleteWhere { SetEntries.workoutId inList chunk }
        }
        Workouts.deleteWhere { Workouts.userId eq me }

        val templateIds = WorkoutTemplates.selectAll().where { WorkoutTemplates.userId eq me }.map { it[WorkoutTemplates.id] }
        templateIds.chunked(1000).forEach { chunk ->
            WorkoutTemplateExercises.deleteWhere { WorkoutTemplateExercises.templateId inList chunk }
        }
        WorkoutTemplates.deleteWhere { WorkoutTemplates.userId eq me }

        Exercises.deleteWhere { Exercises.userId eq me }
        AthleteProfile.deleteWhere { AthleteProfile.userId eq me }
        Users.deleteWhere { Users.id eq me }
    }
    userIdCache.entries.removeIf { it.value == me }
}

/** Built-in exercises (no owner) plus this user's own custom ones. */
private fun visibleExercises(me: Int): Op<Boolean> = Exercises.userId.isNull() or (Exercises.userId eq me)

/** Built-in templates (no owner) plus this user's own. */
private fun visibleTemplates(me: Int): Op<Boolean> = WorkoutTemplates.userId.isNull() or (WorkoutTemplates.userId eq me)

/** True if [workoutId] is one of this user's workouts. Must be called from
 *  inside a transaction { } block. */
private fun ownsWorkout(workoutId: Int, me: Int): Boolean =
    Workouts.selectAll().where { (Workouts.id eq workoutId) and (Workouts.userId eq me) }.limit(1).any()

/** True if [setId] belongs to a workout of this user's (sets have no owner
 *  of their own - they inherit it from their workout). Must be called from
 *  inside a transaction { } block. */
private fun ownsSet(setId: Int, me: Int): Boolean =
    (SetEntries innerJoin Workouts).selectAll()
        .where { (SetEntries.id eq setId) and (Workouts.userId eq me) }
        .limit(1)
        .any()

/** True if [exerciseId] is a built-in exercise or one of this user's own.
 *  Must be called from inside a transaction { } block. */
private fun exerciseVisible(exerciseId: Int, me: Int): Boolean =
    Exercises.selectAll().where { (Exercises.id eq exerciseId) and visibleExercises(me) }.limit(1).any()

/**
 * One-time schema changes for accounts - same reasoning as the other
 * migrate*() functions: SchemaUtils.create() never alters a table that
 * already exists, so a database from before accounts needs plain SQL.
 *  - adds user_id to the four tables that have an owner,
 *  - drops the old *global* unique index on exercise and template names and
 *    replaces it with per-owner ones (partial indexes: one for the shared
 *    built-ins, one per user for their own),
 *  - turns athlete_profile.id into a generated id (it used to be hardcoded
 *    to 1) and makes user_id unique there,
 *  - adds the indexes the per-user queries rely on.
 *
 * Safe to run on every startup: each statement is a no-op once applied.
 */
private fun migrateMultiUser() {
    DriverManager.getConnection(databaseUrl(), env("PGUSER", "postgres"), env("PGPASSWORD", "postgres")).use { connection ->
        connection.createStatement().use { statement ->
            for (table in listOf("workouts", "workout_templates", "exercises", "athlete_profile")) {
                statement.execute("ALTER TABLE $table ADD COLUMN IF NOT EXISTS user_id INTEGER REFERENCES users(id)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_${table}_user_id ON $table(user_id)")
            }
            statement.execute("CREATE INDEX IF NOT EXISTS idx_workouts_user_started ON workouts(user_id, started_at)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_set_entries_workout_id ON set_entries(workout_id)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_set_entries_exercise_id ON set_entries(exercise_id)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_template_exercises_template_id ON workout_template_exercises(template_id)")
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS athlete_profile_user_id_unique ON athlete_profile(user_id)")
        }

        dropSingleColumnUnique(connection, "exercises", "name")
        dropSingleColumnUnique(connection, "workout_templates", "name")

        connection.createStatement().use { statement ->
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS exercises_builtin_name_unique ON exercises(name) WHERE user_id IS NULL")
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS exercises_user_name_unique ON exercises(user_id, name) WHERE user_id IS NOT NULL")
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS workout_templates_builtin_name_unique ON workout_templates(name) WHERE user_id IS NULL")
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS workout_templates_user_name_unique ON workout_templates(user_id, name) WHERE user_id IS NOT NULL")
        }

        // athlete_profile.id used to be a plain integer the code set to 1 by
        // hand; give it a sequence so each new user's row gets its own id.
        // Only when the column has no default of its own yet (a database from
        // before accounts) - a freshly created one already generates ids, and
        // an identity column can't take a default at all.
        val needsGeneratedId = connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT 1 FROM information_schema.columns " +
                    "WHERE table_name = 'athlete_profile' AND column_name = 'id' " +
                    "AND column_default IS NULL AND is_identity = 'NO'"
            ).use { rows -> rows.next() }
        }
        if (needsGeneratedId) {
            connection.createStatement().use { statement ->
                statement.execute("CREATE SEQUENCE IF NOT EXISTS athlete_profile_id_seq")
                statement.execute(
                    "SELECT setval('athlete_profile_id_seq', GREATEST(" +
                        "(SELECT COALESCE(MAX(id), 0) FROM athlete_profile), 1))"
                )
                statement.execute("ALTER TABLE athlete_profile ALTER COLUMN id SET DEFAULT nextval('athlete_profile_id_seq')")
            }
        }
    }
}

/** Drops any non-partial unique index (or unique constraint) that covers
 *  exactly [column] of [table] - without needing to know what name the old
 *  schema happened to give it. A no-op when there isn't one. */
private fun dropSingleColumnUnique(connection: java.sql.Connection, table: String, column: String) {
    val sql = """
        SELECT i.relname AS index_name, con.conname AS constraint_name
        FROM pg_class t
        JOIN pg_index ix ON ix.indrelid = t.oid
        JOIN pg_class i ON i.oid = ix.indexrelid
        JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ix.indkey[0]
        LEFT JOIN pg_constraint con ON con.conindid = ix.indexrelid AND con.conrelid = t.oid
        WHERE t.relname = ? AND a.attname = ?
          AND ix.indisunique AND NOT ix.indisprimary AND ix.indnatts = 1 AND ix.indpred IS NULL
    """.trimIndent()

    val found = mutableListOf<Pair<String, String?>>()
    connection.prepareStatement(sql).use { statement ->
        statement.setString(1, table)
        statement.setString(2, column)
        statement.executeQuery().use { rows ->
            while (rows.next()) {
                found += rows.getString("index_name") to rows.getString("constraint_name")
            }
        }
    }

    connection.createStatement().use { statement ->
        for ((indexName, constraintName) in found) {
            if (constraintName != null) {
                statement.execute("ALTER TABLE \"$table\" DROP CONSTRAINT \"$constraintName\"")
            } else {
                statement.execute("DROP INDEX IF EXISTS \"$indexName\"")
            }
        }
    }
}

/**
 * Adds athlete_profile.onboarding_complete to a database from before the
 * first-run onboarding existed. Every profile already there counts as
 * onboarded (the owner's real profile, and anything edited), EXCEPT ones
 * still holding the untouched placeholder values a new account was given -
 * those people never got to enter their details, so they get onboarding too.
 * Only runs the first time (when the column doesn't exist yet), so it never
 * second-guesses anyone afterwards.
 */
private fun migrateAthleteProfileOnboarding() {
    DriverManager.getConnection(databaseUrl(), env("PGUSER", "postgres"), env("PGPASSWORD", "postgres")).use { connection ->
        val columnExists = connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT 1 FROM information_schema.columns " +
                    "WHERE table_name = 'athlete_profile' AND column_name = 'onboarding_complete'"
            ).use { rows -> rows.next() }
        }
        if (columnExists) return

        connection.createStatement().use { statement ->
            statement.execute("ALTER TABLE athlete_profile ADD COLUMN onboarding_complete BOOLEAN NOT NULL DEFAULT TRUE")
            statement.execute(
                "UPDATE athlete_profile SET onboarding_complete = FALSE " +
                    "WHERE user_id IS NOT NULL AND age = 25 AND weight_kg = 70 AND height_cm = 170 " +
                    "AND previous_weight_kg IS NULL AND target_weight_kg IS NULL AND body_fat_percent IS NULL"
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Time zones
// ---------------------------------------------------------------------------
// Every timestamp is STORED in UTC, whatever clock the server runs on. Every
// timestamp and calendar date SENT BACK is in the person's own time zone,
// which the app sends on each request - so a workout at 11pm in India lands
// on that day, not the next one in UTC, and "TODAY"/"YESTERDAY", the heatmap
// and the weekly target all line up with the phone's own calendar.

/** The caller's time zone from the X-Time-Zone header (an IANA id such as
 *  "Asia/Kolkata"); UTC when it's missing or not a real zone. */
private fun ApplicationCall.userZone(): ZoneId =
    request.headers["X-Time-Zone"]?.let { id -> runCatching { ZoneId.of(id) }.getOrNull() } ?: ZoneOffset.UTC

/** The current time in UTC - how every timestamp is stored. */
private fun nowUtc(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

/** A stored (UTC) timestamp as wall-clock time in [zone]. */
private fun LocalDateTime.toZone(zone: ZoneId): LocalDateTime =
    atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime()
