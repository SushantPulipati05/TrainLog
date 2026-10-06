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

    /** Forgets everything. Called on logout so the next person to sign in on
     *  this phone never sees the previous account's workouts or profile. */
    fun clear() {
        workoutSummaries = null
        exercises = null
        profile = null
        templates = null
        workoutDetails = emptyMap()
        templateDetails = emptyMap()
        exerciseHistories = emptyMap()
    }

    private fun toSummary(response: WorkoutSummaryResponse) = WorkoutSummary(
        id = response.id,
        dateLabel = response.dateLabel,
        date = response.date,
        title = response.title,
        duration = response.duration,
        totalSets = response.totalSets,
        exerciseCount = response.exerciseCount,
        volumeLabel = response.volumeLabel,
        tags = response.tags
    )

    suspend fun loadWorkoutSummaries(force: Boolean = false): List<WorkoutSummary> {
        val cached = workoutSummaries
        if (!force && cached != null) return cached
        val fetched = NetworkClient.workoutApi.getWorkoutSummaries().map { toSummary(it) }
        workoutSummaries = fetched
        return fetched
    }

    suspend fun loadExercises(force: Boolean = false): List<ExerciseResponse> {
        val cached = exercises
        if (!force && cached != null) return cached
        val fetched = NetworkClient.workoutApi.getExercises()
        exercises = fetched
        return fetched
    }

    suspend fun loadProfile(force: Boolean = false): ProfileResponse {
        val cached = profile
        if (!force && cached != null) return cached
        val fetched = NetworkClient.workoutApi.getProfile()
        profile = fetched
        return fetched
    }

    suspend fun loadTemplates(force: Boolean = false): List<WorkoutTemplateSummaryResponse> {
        val cached = templates
        if (!force && cached != null) return cached
        val fetched = NetworkClient.workoutApi.getWorkoutTemplates()
        templates = fetched
        return fetched
    }

    suspend fun loadWorkoutDetail(id: Int, force: Boolean = false): WorkoutDetailResponse {
        val cached = workoutDetails[id]
        if (!force && cached != null) return cached
        val fetched = NetworkClient.workoutApi.getWorkoutDetail(id)
        workoutDetails = workoutDetails + (id to fetched)
        return fetched
    }

    suspend fun loadTemplateDetail(id: Int, force: Boolean = false): WorkoutTemplateDetailResponse {
        val cached = templateDetails[id]
        if (!force && cached != null) return cached
        val fetched = NetworkClient.workoutApi.getWorkoutTemplate(id)
        templateDetails = templateDetails + (id to fetched)
        return fetched
    }

    // Keyed by exerciseId - the weight-progression history used by the
    // exercise's growth-chart screen. Logging a new set invalidates the
    // relevant entry (see invalidateExerciseHistory below) so the next
    // visit to that chart picks up the fresh data instead of showing stale
    // history right after the workout that just added to it.
    suspend fun loadExerciseHistory(exerciseId: Int, force: Boolean = false): ExerciseHistoryResponse {
        val cached = exerciseHistories[exerciseId]
        if (!force && cached != null) return cached
        val fetched = NetworkClient.workoutApi.getExerciseHistory(exerciseId)
        exerciseHistories = exerciseHistories + (exerciseId to fetched)
        return fetched
    }

    // --- Local mutation helpers - screens call these right after a
    // successful server call so the shared cache (and every other screen
    // reading from it) updates immediately, without a fresh fetch. ---

    // Named replaceX/... rather than setX - a plain "setX" clashes at the
    // JVM signature level with the private setter Kotlin already generates
    // for the "by mutableStateOf(...)" property of the same name above.
    fun replaceProfile(updated: ProfileResponse) {
        profile = updated
    }

    fun removeWorkout(id: Int) {
        workoutSummaries = workoutSummaries?.filterNot { it.id == id }
        workoutDetails = workoutDetails - id
    }

    fun addWorkout(workout: WorkoutSummary) {
        workoutSummaries = (workoutSummaries.orEmpty() + workout).sortedByDescending { it.id }
    }

    /** Called right after a workout is ended. Re-fetches everything a
     *  finished workout changes - the previous-workouts list (and so the
     *  heatmap and weekly target), the profile's totals, and the templates'
     *  "last done" labels - and drops the cached exercise growth charts so
     *  they reload with the new sets. Failures are ignored: each screen
     *  still loads for itself the next time it opens. */
    suspend fun refreshAfterWorkoutEnded() {
        exerciseHistories = emptyMap()
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
    }

    fun replaceTemplates(updated: List<WorkoutTemplateSummaryResponse>) {
        templates = updated
    }

    fun removeTemplate(id: Int) {
        templates = templates?.filterNot { it.id == id }
        templateDetails = templateDetails - id
    }

    fun addExercise(exercise: ExerciseResponse) {
        exercises = (exercises.orEmpty() + exercise)
    }

    fun invalidateExerciseHistory(exerciseId: Int) {
        exerciseHistories = exerciseHistories - exerciseId
    }
}
