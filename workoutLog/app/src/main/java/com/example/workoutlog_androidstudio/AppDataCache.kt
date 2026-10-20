package com.example.workoutlog_androidstudio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.workoutlog_androidstudio.api.ExerciseHistoryResponse
import com.example.workoutlog_androidstudio.api.ExerciseResponse
import com.example.workoutlog_androidstudio.api.NetworkClient
import com.example.workoutlog_androidstudio.api.ProfileResponse
import com.example.workoutlog_androidstudio.api.WorkoutDetailResponse
import com.example.workoutlog_androidstudio.api.WorkoutSummaryResponse
import com.example.workoutlog_androidstudio.api.WorkoutTemplateDetailResponse
import com.example.workoutlog_androidstudio.api.WorkoutTemplateSummaryResponse
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * A tiny in-memory cache shared by every screen, so navigating between them
 * doesn't re-fetch (and re-show a skeleton/spinner for) the same data every
 * single time.
 *
 * The three bottom-nav tabs (Home/Workouts/Profile) already stay composed
 * forever, so they only ever fetch once on their own - but every other
 * screen (Calendar, All Workouts, Targets, a workout's detail, a template's
 * detail) gets torn down and rebuilt each time it's opened, which used to
 * mean starting from an empty state and re-fetching from the network again.
 * Each screen now asks this cache for its data instead of calling
 * NetworkClient directly: the first call anywhere in the app does the real
 * fetch, and every call after that (from any screen) returns the value
 * already sitting here - only the very first load anyone triggers ever
 * shows a skeleton/spinner. A `force = true` call re-fetches anyway (used
 * right after an edit that needs to be reflected everywhere).
 *
 * This is intentionally just a plain object rather than a ViewModel - it
 * lives for as long as the process does, which is exactly the "survive
 * navigation" behavior wanted here. Screens that change data on the server
 * (delete, edit, log a set...) call the matching `setX`/`removeX` helper
 * right after so every other screen's copy of the same data updates
 * immediately, without needing a fresh fetch at all.
 *
 * Offline: everything loaded is also saved on the phone (DiskCache), and
 * read back at start-up - so the app opens with your data even with no
 * signal. Each list is still re-fetched once per app run (the first time
 * something asks for it); if that fails, the saved copy is used. Workouts
 * waiting to upload (PendingWorkoutStore) are merged into the workout list.
 */
object AppDataCache {
    var workoutSummaries by mutableStateOf<List<WorkoutSummary>?>(null)
        private set
    var exercises by mutableStateOf<List<ExerciseResponse>?>(null)
        private set
    var profile by mutableStateOf<ProfileResponse?>(null)
        private set
    var templates by mutableStateOf<List<WorkoutTemplateSummaryResponse>?>(null)
        private set

    // Per-id detail caches - these are fetched one at a time as screens ask
    // for them, rather than all up front.
    var workoutDetails by mutableStateOf<Map<Int, WorkoutDetailResponse>>(emptyMap())
        private set
    var templateDetails by mutableStateOf<Map<Int, WorkoutTemplateDetailResponse>>(emptyMap())
        private set
    var exerciseHistories by mutableStateOf<Map<Int, ExerciseHistoryResponse>>(emptyMap())
        private set

    /** The workout list exactly as the server sent it (what's saved to disk);
     *  [workoutSummaries] is this plus any workouts waiting to upload. */
    private var serverSummaries: List<WorkoutSummaryResponse>? = null

    /** What has been fetched from the server during this app run - anything
     *  else in memory came from the disk copy and is refreshed on first use. */
    private val fresh: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private const val MAX_SAVED_DETAILS = 60

    private val summariesSerializer = ListSerializer(WorkoutSummaryResponse.serializer())
    private val exercisesSerializer = ListSerializer(ExerciseResponse.serializer())
    private val templatesSerializer = ListSerializer(WorkoutTemplateSummaryResponse.serializer())
    private val detailsSerializer = MapSerializer(Int.serializer(), WorkoutDetailResponse.serializer())
    private val templateDetailsSerializer = MapSerializer(Int.serializer(), WorkoutTemplateDetailResponse.serializer())
    private val historiesSerializer = MapSerializer(Int.serializer(), ExerciseHistoryResponse.serializer())

    private var initialized = false

    /** Loads the saved copies (once per process). Called from MainActivity,
     *  the widgets and the sync worker. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        DiskCache.init(context)
        PendingWorkoutStore.init(context)
        serverSummaries = DiskCache.read("summaries", summariesSerializer)
        exercises = exercises ?: DiskCache.read("exercises", exercisesSerializer)
        profile = profile ?: DiskCache.read("profile", ProfileResponse.serializer())
        templates = templates ?: DiskCache.read("templates", templatesSerializer)
        workoutDetails = DiskCache.read("workout_details", detailsSerializer).orEmpty() + workoutDetails
        templateDetails = DiskCache.read("template_details", templateDetailsSerializer).orEmpty() + templateDetails
        exerciseHistories = DiskCache.read("exercise_histories", historiesSerializer).orEmpty() + exerciseHistories
        publishSummaries()
    }

    /** Forgets everything. Called on logout so the next person to sign in on
     *  this phone never sees the previous account's workouts or profile. */
    fun clear() {
        workoutSummaries = null
        serverSummaries = null
        exercises = null
        profile = null
        templates = null
        workoutDetails = emptyMap()
        templateDetails = emptyMap()
        exerciseHistories = emptyMap()
        fresh.clear()
        DiskCache.clearAll()
    }

    private fun toSummary(response: WorkoutSummaryResponse) = WorkoutSummary(
        id = response.id,
        // Worked out here, not taken from the server, so a list saved
        // yesterday doesn't still say TODAY.
        dateLabel = dayLabel(response.date),
        date = response.date,
        title = response.title,
        duration = response.duration,
        totalSets = response.totalSets,
        exerciseCount = response.exerciseCount,
        volumeLabel = response.volumeLabel,
        tags = response.tags,
        muscleSets = response.muscleSets,
        exerciseSets = response.exerciseSets.map { ExerciseSetCount(it.name, it.muscleGroup, it.sets) }
    )

    /** Server list + this account's workouts still waiting to upload. */
    private fun publishSummaries() {
        val server = serverSummaries?.map { toSummary(it) }
        val pending = PendingWorkoutStore.mine.filter { p -> server?.none { it.id == p.workoutId } ?: true }
        workoutSummaries = if (server == null && pending.isEmpty()) null
        else (pending.map { it.toSummary() } + server.orEmpty()).sortedWith(compareByDescending<WorkoutSummary> { it.date }.thenByDescending { it.id })
    }

    /** The upload queue changed (a workout was queued or uploaded). */
    fun onPendingChanged() = publishSummaries()

    /**
     * The one loading rule: what's in memory if it was fetched this run;
     * else fetch (and save); if that fails, what's in memory/on disk; else
     * the error.
     */
    private suspend fun <T : Any> load(key: String, force: Boolean, current: T?, fetch: suspend () -> T, keep: (T) -> Unit): T {
        if (!force && current != null && key in fresh) return current
        return try {
            val fetched = fetch()
            fresh += key
            keep(fetched)
            fetched
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            current ?: throw e
        }
    }

    private fun <V> trimmed(map: Map<Int, V>): Map<Int, V> =
        if (map.size <= MAX_SAVED_DETAILS) map else map.entries.toList().takeLast(MAX_SAVED_DETAILS).associate { it.key to it.value }

    suspend fun loadWorkoutSummaries(force: Boolean = false): List<WorkoutSummary> {
        val current = workoutSummaries
        if (!force && current != null && "summaries" in fresh) return current
        return try {
            publishFetched(NetworkClient.workoutApi.getWorkoutSummaries())
            fresh += "summaries"
            workoutSummaries.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline: the saved list (plus anything waiting to upload).
            current ?: throw e
        }
    }

    private fun publishFetched(fetched: List<WorkoutSummaryResponse>) {
        serverSummaries = fetched
        DiskCache.write("summaries", summariesSerializer, fetched)
        publishSummaries()
    }

    suspend fun loadExercises(force: Boolean = false): List<ExerciseResponse> =
        load("exercises", force, exercises, { NetworkClient.workoutApi.getExercises() }) {
            exercises = it
            DiskCache.write("exercises", exercisesSerializer, it)
        }

    suspend fun loadProfile(force: Boolean = false): ProfileResponse =
        load("profile", force, profile, { NetworkClient.workoutApi.getProfile() }) {
            profile = it
            DiskCache.write("profile", ProfileResponse.serializer(), it)
        }

    suspend fun loadTemplates(force: Boolean = false): List<WorkoutTemplateSummaryResponse> =
        load("templates", force, templates, { NetworkClient.workoutApi.getWorkoutTemplates() }) {
            templates = it
            DiskCache.write("templates", templatesSerializer, it)
        }

    suspend fun loadWorkoutDetail(id: Int, force: Boolean = false): WorkoutDetailResponse =
        load("workout:$id", force, workoutDetails[id], { NetworkClient.workoutApi.getWorkoutDetail(id) }) {
            workoutDetails = trimmed(workoutDetails - id + (id to it))
            DiskCache.write("workout_details", detailsSerializer, workoutDetails)
        }

    suspend fun loadTemplateDetail(id: Int, force: Boolean = false): WorkoutTemplateDetailResponse =
        load("template:$id", force, templateDetails[id], { NetworkClient.workoutApi.getWorkoutTemplate(id) }) {
            templateDetails = trimmed(templateDetails - id + (id to it))
            DiskCache.write("template_details", templateDetailsSerializer, templateDetails)
        }

    // Keyed by exerciseId - the weight-progression history used by the
    // exercise's growth-chart screen (and "last time" numbers in a workout).
    suspend fun loadExerciseHistory(exerciseId: Int, force: Boolean = false): ExerciseHistoryResponse =
        load("history:$exerciseId", force, exerciseHistories[exerciseId], { NetworkClient.workoutApi.getExerciseHistory(exerciseId) }) {
            exerciseHistories = trimmed(exerciseHistories - exerciseId + (exerciseId to it))
            DiskCache.write("exercise_histories", historiesSerializer, exerciseHistories)
        }

    // --- Local mutation helpers - screens call these right after a
    // successful server call so the shared cache (and every other screen
    // reading from it) updates immediately, without a fresh fetch. ---

    // Named replaceX/... rather than setX - a plain "setX" clashes at the
    // JVM signature level with the private setter Kotlin already generates
    // for the "by mutableStateOf(...)" property of the same name above.
    fun replaceProfile(updated: ProfileResponse) {
        profile = updated
        DiskCache.write("profile", ProfileResponse.serializer(), updated)
    }

    /** Hides a workout right away (the delete call follows). Its server copy
     *  is kept aside so [addWorkout] can put it back if the delete fails. */
    private val removedSummaries = HashMap<Int, WorkoutSummaryResponse>()

    fun removeWorkout(id: Int) {
        // Deleting one that never uploaded: it just leaves the queue.
        PendingWorkoutStore.discard(id)
        serverSummaries?.firstOrNull { it.id == id }?.let { removedSummaries[id] = it }
        serverSummaries = serverSummaries?.filterNot { it.id == id }
        serverSummaries?.let { DiskCache.write("summaries", summariesSerializer, it) }
        workoutDetails = workoutDetails - id
        publishSummaries()
        workoutSummaries = workoutSummaries?.filterNot { it.id == id }
    }

    fun addWorkout(workout: WorkoutSummary) {
        val response = removedSummaries.remove(workout.id)
        if (response != null && serverSummaries != null) {
            serverSummaries = serverSummaries.orEmpty() + response
            DiskCache.write("summaries", summariesSerializer, serverSummaries.orEmpty())
            publishSummaries()
        } else {
            workoutSummaries = (workoutSummaries.orEmpty() + workout).sortedByDescending { it.id }
        }
    }

    /** Called right after a workout is ended (and after a queued one
     *  uploads). Re-fetches everything a finished workout changes - the
     *  previous-workouts list (and so the heatmap and weekly target), the
     *  profile's totals, and the templates' "last done" labels - and marks
     *  the exercise list and growth charts for a reload. Failures are
     *  ignored: the saved copies stay in place. */
    suspend fun refreshAfterWorkoutEnded() {
        // Kept (they're what an offline workout needs) but re-fetched on next use.
        fresh.removeAll { it.startsWith("history:") || it == "exercises" }
        try {
            loadWorkoutSummaries(force = true)
        } catch (e: Exception) {
        }
        try {
            loadProfile(force = true)
        } catch (e: Exception) {
        }
        try {
            loadTemplates(force = true)
        } catch (e: Exception) {
        }
    }

    fun invalidateWorkoutDetail(id: Int) {
        workoutDetails = workoutDetails - id
        fresh.remove("workout:$id")
    }

    fun replaceTemplates(updated: List<WorkoutTemplateSummaryResponse>) {
        templates = updated
        DiskCache.write("templates", templatesSerializer, updated)
    }

    fun removeTemplate(id: Int) {
        templates = templates?.filterNot { it.id == id }
        templates?.let { DiskCache.write("templates", templatesSerializer, it) }
        templateDetails = templateDetails - id
    }

    fun addExercise(exercise: ExerciseResponse) {
        exercises = (exercises.orEmpty() + exercise)
        DiskCache.write("exercises", exercisesSerializer, exercises.orEmpty())
    }

    fun invalidateExerciseHistory(exerciseId: Int) {
        exerciseHistories = exerciseHistories - exerciseId
        fresh.remove("history:$exerciseId")
    }

    /** After two exercises were merged: sets moved between them, so drop
     *  everything that shows sets or exercise names and reload the list. */
    suspend fun refreshAfterExercisesMerged() {
        exerciseHistories = emptyMap()
        workoutDetails = emptyMap()
        templateDetails = emptyMap()
        fresh.removeAll { it.startsWith("history:") || it.startsWith("workout:") || it.startsWith("template:") }
        try {
            loadExercises(force = true)
        } catch (e: Exception) {
            exercises = null
        }
    }
}
