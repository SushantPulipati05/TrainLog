package com.example.workoutlog_androidstudio

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.workoutlog_androidstudio.api.CompleteWorkoutRequest
import com.example.workoutlog_androidstudio.api.ImportWorkoutRequest
import com.example.workoutlog_androidstudio.api.NewWorkoutRequest
import com.example.workoutlog_androidstudio.api.SetEntryResponse
import com.example.workoutlog_androidstudio.api.WorkoutDetailResponse
import com.example.workoutlog_androidstudio.api.WorkoutResponse
import com.example.workoutlog_androidstudio.api.NetworkClient
import com.example.workoutlog_androidstudio.api.NewSetRequest
import com.example.workoutlog_androidstudio.api.UpdateWorkoutRequest
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/*
 * Offline, phase 1.
 *
 *  - The outbox (PendingWorkoutStore): End Workout first saves the finished
 *    workout here, on the phone, then tries to upload it. If there's no
 *    signal it stays queued and OutboxSyncWorker uploads it when the phone
 *    is back online - the workout screen never gets stuck.
 *  - The disk cache (DiskCache): AppDataCache saves what it loads, so the
 *    app opens with your exercises, history and plan even with no signal.
 */

private val offlineJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun currentUid(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

/** Writes [text] to [file] all-or-nothing (a temp file, then a rename), so a
 *  crash mid-write never leaves a half-written file. */
private fun writeAtomically(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
    }
}

// ---------------------------------------------------------------------------
// The outbox
// ---------------------------------------------------------------------------

@Serializable
data class PendingSet(
    val exerciseId: Int,
    val setNumber: Int,
    val reps: Int,
    val weightKg: Double?,
    val loggedAtSeconds: Int
)

/** One exercise of a queued workout - enough to show it in history and on
 *  the heatmap before it's uploaded. */
@Serializable
data class PendingExercise(
    val exerciseId: Int,
    val name: String,
    val muscleGroup: String,
    val sets: Int,
    val volumeKg: Double
)

/** A finished workout waiting to be uploaded. */
@Serializable
data class PendingWorkout(
    val workoutId: Int,
    val name: String,
    /** ISO date it was done on (the phone's). */
    val date: String,
    val endedAtMillis: Long,
    val elapsedSeconds: Int,
    val notes: String?,
    val sets: List<PendingSet>,
    val exercises: List<PendingExercise>,
    /** The account it belongs to - never uploaded under someone else's login. */
    val ownerUid: String? = null,
    /** Only for a past workout: when it really started (the server moves
     *  the workout to that time). Null for a normal, live workout. */
    val startedAtMillis: Long? = null
)

/** Builds the queue entry for a workout that was just ended. */
fun pendingWorkoutFrom(
    workoutId: Int,
    name: String,
    exercises: List<ActiveExercise>,
    notes: String,
    elapsedSeconds: Int,
    startedAtMillis: Long,
    endedAtMillis: Long = System.currentTimeMillis(),
    isPast: Boolean = false
): PendingWorkout {
    // Same rule as the upload always had: only sets with reps filled in.
    val sets = exercises.flatMap { exercise ->
        exercise.sets.mapNotNull { set ->
            val reps = set.reps.toIntOrNull() ?: return@mapNotNull null
            PendingSet(exercise.exerciseId, set.setNumber, reps, parseKg(set.kg), set.loggedAtSeconds)
        }
    }
    val summary = exercises.mapNotNull { exercise ->
        val done = exercise.sets.filter { it.reps.toIntOrNull() != null }
        if (done.isEmpty()) return@mapNotNull null
        PendingExercise(
            exerciseId = exercise.exerciseId,
            name = exercise.name,
            muscleGroup = exercise.muscleGroup.trim().uppercase(),
            sets = done.size,
            volumeKg = done.sumOf { (parseKg(it.kg) ?: 0.0) * (it.reps.toIntOrNull() ?: 0) }
        )
    }
    val date = Instant.ofEpochMilli(startedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return PendingWorkout(
        workoutId = workoutId,
        name = name,
        date = date.toString(),
        endedAtMillis = endedAtMillis,
        elapsedSeconds = elapsedSeconds,
        notes = notes.ifBlank { null },
        sets = sets,
        exercises = summary,
        ownerUid = currentUid(),
        startedAtMillis = if (isPast) startedAtMillis else null
    )
}

private val SHORT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

/** "TODAY", "YESTERDAY" or "OCT 22" - worked out on the phone, so a list
 *  loaded from disk the next day doesn't still say TODAY. */
fun dayLabel(isoDate: String, today: LocalDate = LocalDate.now()): String {
    val date = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return isoDate
    return when (date) {
        today -> "TODAY"
        today.minusDays(1) -> "YESTERDAY"
        else -> date.format(SHORT_DAY).uppercase()
    }
}

/** How a queued workout shows in history, on the heatmap and in Up next. */
fun PendingWorkout.toSummary(): WorkoutSummary {
    val minutes = elapsedSeconds / 60
    return WorkoutSummary(
        id = workoutId,
        dateLabel = dayLabel(date) + " · WAITING TO SYNC",
        date = date,
        title = name,
        duration = if (minutes >= 60) "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}m" else "${minutes}m",
        totalSets = sets.size,
        exerciseCount = exercises.size,
        volumeLabel = "%,.0f KG".format(exercises.sumOf { it.volumeKg }),
        tags = exercises.map { it.muscleGroup }.distinct(),
        muscleSets = exercises.groupBy { it.muscleGroup }.mapValues { (_, list) -> list.sumOf { it.sets } },
        exerciseSets = exercises.map { e ->
            // The muscles come from the exercise list (not stored in the queue).
            val known = AppDataCache.exercises?.firstOrNull { it.id == e.exerciseId }
            ExerciseSetCount(e.name, e.muscleGroup, e.sets, known?.primaryMuscles.orEmpty(), known?.secondaryMuscles.orEmpty())
        }
    )
}

object PendingWorkoutStore {
    private const val FILE_NAME = "pending_workouts.json"
    private val serializer: KSerializer<List<PendingWorkout>> = ListSerializer(PendingWorkout.serializer())
    private var file: File? = null
    private val lock = Any()

    /** Everything waiting to upload (every account's). Compose state. */
    var all by mutableStateOf<List<PendingWorkout>>(emptyList())
        private set

    /** The signed-in account's queued workouts. */
    val mine: List<PendingWorkout>
        get() {
            val uid = currentUid()
            return all.filter { it.ownerUid == null || it.ownerUid == uid }
        }

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val f = File(context.applicationContext.filesDir, FILE_NAME)
            file = f
            all = runCatching { offlineJson.decodeFromString(serializer, f.readText()) }.getOrDefault(emptyList())
        }
    }

    fun has(workoutId: Int): Boolean = all.any { it.workoutId == workoutId }

    private fun save(updated: List<PendingWorkout>) {
        synchronized(lock) {
            all = updated
            file?.let { writeAtomically(it, offlineJson.encodeToString(serializer, updated)) }
        }
        AppDataCache.onPendingChanged()
    }

    /** Queues a finished workout (replacing an older entry for the same one). */
    suspend fun add(context: Context, workout: PendingWorkout) = withContext(Dispatchers.IO) {
        init(context)
        save(all.filterNot { it.workoutId == workout.workoutId } + workout)
    }

    /** The fun-name card (or a rename) picked a new name before it uploaded. */
    suspend fun rename(workoutId: Int, name: String) = withContext(Dispatchers.IO) {
        save(all.map { if (it.workoutId == workoutId) it.copy(name = name) else it })
    }

    suspend fun remove(workoutId: Int) = withContext(Dispatchers.IO) {
        save(all.filterNot { it.workoutId == workoutId })
    }

    /** The user deleted a workout that hadn't uploaded yet - it's dropped
     *  from the queue straight away (the file is tiny). */
    fun discard(workoutId: Int) {
        if (has(workoutId)) save(all.filterNot { it.workoutId == workoutId })
    }
}

object OfflineSync {
    private const val WORK_NAME = "trainlog-outbox-sync"
    private val mutex = Mutex()

    /** Phone-made workout id -> the id the server gave it on upload. */
    private val uploadedAs = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** The server's id for a workout (a phone-made one once it's uploaded). */
    fun serverIdFor(workoutId: Int): Int = uploadedAs[workoutId] ?: workoutId

    /**
     * Uploads every queued workout of the signed-in account. Safe to run any
     * number of times: the server replaces a workout's sets rather than adding
     * them again. Returns true when nothing of theirs is left waiting.
     */
    suspend fun syncAll(context: Context): Boolean = mutex.withLock {
        PendingWorkoutStore.init(context)
        AppDataCache.init(context)
        var uploadedAny = false
        for (pending in PendingWorkoutStore.mine) {
            try {
                if (isLocalWorkoutId(pending.workoutId)) {
                    // Started with no connection: the server has never heard of
                    // it, so it's created now - name, times, sets, all at once.
                    val created = NetworkClient.workoutApi.importWorkout(
                        ImportWorkoutRequest(
                            clientId = "phone-${-pending.workoutId}",
                            workoutName = pending.name,
                            startedAtMillis = pending.startedAtMillis
                                ?: (pending.endedAtMillis - pending.elapsedSeconds * 1000L),
                            endedAtMillis = pending.endedAtMillis,
                            notes = pending.notes,
                            sets = pending.sets.map {
                                NewSetRequest(
                                    exerciseId = it.exerciseId,
                                    setNumber = it.setNumber,
                                    reps = it.reps,
                                    weightKg = it.weightKg,
                                    loggedAtSeconds = it.loggedAtSeconds
                                )
                            }
                        )
                    )
                    uploadedAs[pending.workoutId] = created.id
                    uploadedAny = true
                    runCatching { AppDataCache.loadWorkoutSummaries(force = true) }
                    PendingWorkoutStore.remove(pending.workoutId)
                    continue
                }
                NetworkClient.workoutApi.completeWorkout(
                    workoutId = pending.workoutId,
                    request = CompleteWorkoutRequest(
                        notes = pending.notes,
                        sets = pending.sets.map {
                            NewSetRequest(
                                exerciseId = it.exerciseId,
                                setNumber = it.setNumber,
                                reps = it.reps,
                                weightKg = it.weightKg,
                                loggedAtSeconds = it.loggedAtSeconds
                            )
                        },
                        endedAtMillis = pending.endedAtMillis,
                        startedAtMillis = pending.startedAtMillis
                    )
                )
                // Its name (a rename during the workout or from the fun-name card).
                runCatching {
                    NetworkClient.workoutApi.updateWorkout(pending.workoutId, UpdateWorkoutRequest(workoutName = pending.name))
                }
                uploadedAny = true
                // Fetch the list first, then drop the queue entry - so the
                // workout never blinks out of history in between.
                runCatching { AppDataCache.loadWorkoutSummaries(force = true) }
                PendingWorkoutStore.remove(pending.workoutId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() == 404) {
                    // The workout was deleted on the server - nothing to save it into.
                    Log.w("OfflineSync", "Workout ${pending.workoutId} no longer exists; dropping it from the queue")
                    PendingWorkoutStore.remove(pending.workoutId)
                } else {
                    Log.e("OfflineSync", "Upload of workout ${pending.workoutId} failed (${e.code()})", e)
                    CrashReporting.record(e)
                }
            } catch (e: Exception) {
                // No signal, timeout ... - stays queued for next time.
                Log.w("OfflineSync", "Upload of workout ${pending.workoutId} failed, will retry", e)
            }
        }
        if (uploadedAny) {
            runCatching { AppDataCache.refreshAfterWorkoutEnded() }
            TrainLogWidgets.requestRefresh(context)
        }
        PendingWorkoutStore.mine.isEmpty()
    }

    /** Tries to upload [workoutId] right now, waiting at most [timeoutMillis]
     *  (patchy gym signal shouldn't hold up the screen). Anything still
     *  queued is handed to the background worker. True if it uploaded. */
    suspend fun syncNow(context: Context, workoutId: Int, timeoutMillis: Long = 8_000): Boolean {
        withTimeoutOrNull(timeoutMillis) { syncAll(context) }
        val uploaded = !PendingWorkoutStore.has(workoutId)
        if (PendingWorkoutStore.mine.isNotEmpty()) schedule(context)
        return uploaded
    }

    /** Has Android upload the queue as soon as there's a connection - even if
     *  the app is closed - retrying with backoff until it's through. */
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class OutboxSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (currentUid() == null) return Result.success() // signed out - waits for the next login
        return if (OfflineSync.syncAll(applicationContext)) Result.success() else Result.retry()
    }
}

// ---------------------------------------------------------------------------
// The disk cache
// ---------------------------------------------------------------------------

/** Copies of what AppDataCache loads, kept per account in the app's private
 *  files - so the app can open (and log) with no signal. */
object DiskCache {
    private var root: File? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (root == null) root = File(context.applicationContext.filesDir, "cache_v1")
    }

    private fun fileFor(name: String): File? {
        val uid = currentUid() ?: return null
        return root?.let { File(File(it, uid.filter { c -> c.isLetterOrDigit() }), "$name.json") }
    }

    fun <T> read(name: String, serializer: KSerializer<T>): T? {
        val f = fileFor(name) ?: return null
        if (!f.exists()) return null
        return runCatching { offlineJson.decodeFromString(serializer, f.readText()) }.getOrNull()
    }

    /** Saves in the background; the newest value wins. */
    fun <T> write(name: String, serializer: KSerializer<T>, value: T) {
        val f = fileFor(name) ?: return
        val text = runCatching { offlineJson.encodeToString(serializer, value) }.getOrNull() ?: return
        ioScope.launch {
            synchronized(this@DiskCache) { runCatching { writeAtomically(f, text) } }
        }
    }

    /** Forgets every account's saved copies (sign-out). */
    fun clearAll() {
        root?.let { dir -> ioScope.launch { runCatching { dir.deleteRecursively() } } }
    }
}


// ---------------------------------------------------------------------------
// Starting a workout with no connection
// ---------------------------------------------------------------------------

/** Workouts made on the phone (server down / no signal) have negative ids
 *  until they upload; the server's own ids are always positive. */
fun isLocalWorkoutId(id: Int): Boolean = id < 0

/**
 * Starts a workout on the server if it answers within a few seconds;
 * otherwise makes it on the phone (a negative id) so logging never waits on
 * the server. A phone-made workout is created on the server when it's
 * uploaded (see [OfflineSync.syncAll]).
 */
/**
 * Starts a workout instantly, on the phone. It used to ask the server first
 * and wait up to 5 seconds on bad gym signal (with nothing on screen, so a
 * second tap could start a second workout). Now every workout lives on the
 * phone until it's finished and then uploads in one go through the outbox
 * (POST /workouts/import, see [OfflineSync]) - the same path a no-signal
 * workout always took. Local ids are negative ([isLocalWorkoutId]).
 */
@Suppress("UNUSED_PARAMETER")
suspend fun startWorkoutAnywhere(name: String, timeoutMillis: Long = 0): WorkoutResponse {
    return WorkoutResponse(
        id = -(1 + kotlin.random.Random.nextInt(Int.MAX_VALUE - 1)),
        workoutName = name,
        startedAt = java.time.LocalDateTime.now().toString(),
        endedAt = null,
        notes = null
    )
}

/** A queued workout as a full workout (all its sets), for its detail screen
 *  and the calendar before it has uploaded. */
fun PendingWorkout.toDetail(): WorkoutDetailResponse {
    val zone = ZoneId.systemDefault()
    val start = startedAtMillis ?: (endedAtMillis - elapsedSeconds * 1000L)
    fun iso(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime().toString()
    return WorkoutDetailResponse(
        id = workoutId,
        workoutName = name,
        startedAt = iso(start),
        endedAt = iso(endedAtMillis),
        notes = notes,
        sets = sets.mapIndexed { i, set ->
            SetEntryResponse(
                id = -(i + 1),
                workoutId = workoutId,
                exerciseId = set.exerciseId,
                setNumber = set.setNumber,
                reps = set.reps,
                weightKg = set.weightKg,
                loggedAtSeconds = set.loggedAtSeconds
            )
        }
    )
}
