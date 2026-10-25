package com.example.workoutlog_androidstudio

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import com.example.workoutlog_androidstudio.api.ExerciseResponse
import com.example.workoutlog_androidstudio.api.NetworkClient
import com.example.workoutlog_androidstudio.api.NewExerciseRequest
import com.example.workoutlog_androidstudio.api.UpdateWorkoutRequest
import com.example.workoutlog_androidstudio.api.WorkoutResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async

@Composable
fun ActiveWorkoutScreen(
    workout: WorkoutResponse,
    // Hoisted up to MainActivity rather than kept as local `remember` state,
    // so the floating mini-player (a sibling composable shown once this
    // screen is minimized) can display the exact same running timer and
    // pause state instead of a frozen snapshot.
    elapsedSeconds: Int,
    onTick: () -> Unit,
    isPaused: Boolean,
    onTogglePause: () -> Unit,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onEndWorkout: (notes: String) -> Unit,

    prefillExercises: List<ExerciseResponse> = emptyList(),
    modifier: Modifier = Modifier
) {
    // The exercises, sets and notes live in ActiveWorkoutState rather than
    // here, because it saves them on the phone with every change - so the
    // workout survives the app being killed (or the screen being rotated).
    var exercises by ActiveWorkoutState::exercises
    var notesText by ActiveWorkoutState::notes

    var showAddExerciseDialog by remember { mutableStateOf(false) }
    // Index of the exercise the user swiped to delete; non-null while the
    // confirmation popup is showing.
    var pendingDeleteIndex by remember { mutableStateOf<Int?>(null) }
    var availableExercises by remember { mutableStateOf(AppDataCache.exercises ?: emptyList()) }
    var isLoadingExercises by remember { mutableStateOf(false) }
    var exerciseLoadError by remember { mutableStateOf<String?>(null) }
    var isEndingWorkout by remember { mutableStateOf(false) }
    // Fun workout names: the suggestion shown once the workout is saved.
    var nameSuggestion by remember { mutableStateOf<Pair<SuggestedName, SessionFacts>?>(null) }
    var isSavingName by remember { mutableStateOf(false) }
    // Set when End Workout couldn't reach the server; the screen stays open
    // (nothing is lost) so the person can simply try again.
    var endError by remember { mutableStateOf<String?>(null) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showSavePast by remember { mutableStateOf(false) }

    var showCustomExerciseDialog by remember { mutableStateOf(false) }
    var customExercisePrefillName by remember { mutableStateOf("") }
    var isCreatingCustomExercise by remember { mutableStateOf(false) }
    var customExerciseError by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffectTicker(isPaused) { onTick() }

    LaunchedEffect(showAddExerciseDialog) {
        if (showAddExerciseDialog && availableExercises.isEmpty() && !isLoadingExercises) {
            isLoadingExercises = true
            exerciseLoadError = null
            try {
                availableExercises = AppDataCache.loadExercises()
            } catch (e: Exception) {
                exerciseLoadError = "Couldn't load exercises. Check your connection."
            } finally {
                isLoadingExercises = false
            }
        }
    }

    // ---- "From your last Legs day" in Add Exercise ----
    // Which past workout to offer, in this order: the day chip the user
    // picked; else what they've logged so far (by muscles); else the
    // workout's own name (started from Up next, a template or a reminder).
    // Nothing is assumed for a plain "Workout" with no exercises yet - the
    // sheet shows the split's day chips instead.
    val plan = TrainingPlanStore.plan
    val dayChips = remember(plan) { plan?.orderedDays().orEmpty().distinct() }
    val upNextDay = remember { TrainingPlanStore.savedUpNext(context)?.startLabel?.takeIf { it in dayChips } }
    var chosenDay by remember { mutableStateOf<String?>(null) }
    val loggedMuscles = remember(exercises) {
        exercises.groupBy { it.muscleGroup.trim().uppercase() }.mapValues { (_, list) -> list.sumOf { it.sets.size.coerceAtLeast(1) } }
    }
    val lookupKey: String? = when {
        chosenDay != null -> "day:$chosenDay"
        exercises.isNotEmpty() -> "muscles:" + loggedMuscles.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" }
        workout.workoutName != DEFAULT_WORKOUT_NAME -> "name:${workout.workoutName}"
        else -> null
    }
    var lastLikeThis by remember { mutableStateOf<Pair<String, List<Int>>?>(null) }
    LaunchedEffect(showAddExerciseDialog, lookupKey) {
        if (!showAddExerciseDialog) return@LaunchedEffect
        if (lookupKey == null) { lastLikeThis = null; return@LaunchedEffect }
        val summaries = (AppDataCache.workoutSummaries
            ?: runCatching { AppDataCache.loadWorkoutSummaries() }.getOrNull()
            ?: return@LaunchedEffect).filter { it.id != workout.id }
        val found: Pair<String, WorkoutSummary>? = when {
            chosenDay != null -> findPreviousSimilarWorkout(chosenDay!!, summaries, plan)?.let { plannedWorkoutName(chosenDay!!) to it }
            exercises.isNotEmpty() -> findPreviousWorkoutForMuscles(loggedMuscles, summaries, plan)?.let { (day, w) -> plannedWorkoutName(day) to w }
            else -> findPreviousSimilarWorkout(workout.workoutName, summaries, plan)?.let { workout.workoutName to it }
        }
        if (found == null) { lastLikeThis = null; return@LaunchedEffect }
        val detail = runCatching { AppDataCache.loadWorkoutDetail(found.second.id) }.getOrNull() ?: return@LaunchedEffect
        lastLikeThis = "your last ${found.first}" to detail.sets.map { it.exerciseId }.distinct()
    }
    // Minus anything already added to this workout.
    val fromLastExercises = lastLikeThis?.second.orEmpty()
        .filter { id -> exercises.none { it.exerciseId == id } }
        .mapNotNull { id -> availableExercises.firstOrNull { it.id == id } }

    // Renaming from the title. A name the user typed means they've named it
    // themselves, so no fun-name card at the end.
    var showRename by remember { mutableStateOf(false) }
    var namedByUser by remember { mutableStateOf(false) }
    fun renameWorkout(name: String) {
        val clean = name.trim().take(60)
        if (clean.isEmpty() || clean == workout.workoutName) return
        ActiveWorkoutState.rename(clean)
        coroutineScope.launch {
            try {
                NetworkClient.workoutApi.updateWorkout(workout.id, UpdateWorkoutRequest(workoutName = clean))
            } catch (e: Exception) {
                Log.e("RenameWorkout", "Couldn't rename workout ${workout.id} (will retry at the end)", e)
            }
        }
    }

    fun addExerciseToWorkout(selected: ExerciseResponse) {
        val isBodyweight = selected.equipment.equals("Body Only", ignoreCase = true)
        exercises = exercises + ActiveExercise(
            exerciseId = selected.id,
            name = selected.name,
            muscleGroup = selected.muscleGroup?.uppercase() ?: "GENERAL",
            restLabel = "90S",
            isBodyweight = isBodyweight,

            sets = listOf(
                ExerciseSet(
                    setNumber = 1,
                    previousLabel = "-",
                    kg = if (isBodyweight) "0" else "",
                    reps = "",
                    // Read from the clock at the tap, not the value from the last redraw.
                    loggedAtSeconds = ActiveWorkoutState.elapsedSeconds
                )
            )
        )
    }

    LaunchedEffect(Unit) {
        // Only into an empty workout - a restored one already has its own.
        if (exercises.isEmpty()) {
            prefillExercises.forEach { addExerciseToWorkout(it) }
        }
    }

    nameSuggestion?.let { (suggestion, facts) ->
        WorkoutNameCard(
            suggestion = suggestion,
            durationLabel = facts.durationMinutes.let { if (it >= 60) "${it / 60}h ${(it % 60).toString().padStart(2, '0')}m" else "${it}m" },
            totalSets = facts.totalSets,
            topLift = facts.topWeightKg?.let { "${formatDecimal(it)} kg" },
            isSaving = isSavingName,
            onSave = { chosen ->
                isSavingName = true
                coroutineScope.launch {
                    if (chosen != workout.workoutName) {
                        if (PendingWorkoutStore.has(workout.id)) {
                            // Not uploaded yet: the name goes up with it.
                            PendingWorkoutStore.rename(workout.id, chosen)
                        } else {
                            try {
                                NetworkClient.workoutApi.updateWorkout(workout.id, UpdateWorkoutRequest(workoutName = chosen))
                            } catch (e: Exception) {
                                // The workout is saved either way - it just keeps its old name.
                                Log.e("EndWorkout", "Couldn't rename workout ${workout.id}", e)
                            }
                        }
                    }
                    WorkoutNamer.remember(context, chosen)
                    isSavingName = false
                    nameSuggestion = null
                    onEndWorkout(notesText)
                }
            }
        )
    }

    if (showDiscardConfirm) {
        DeleteConfirmationPopup(
            title = "Discard workout?",
            message = "Nothing from this workout will be saved. This can't be undone.",
            confirmLabel = "Discard",
            onConfirm = {
                showDiscardConfirm = false
                onClose()
            },
            onCancel = { showDiscardConfirm = false }
        )
    }

    pendingDeleteIndex?.let { index ->
        val target = exercises.getOrNull(index)
        if (target == null) {
            pendingDeleteIndex = null
        } else {
            DeleteConfirmationPopup(
                title = "Delete exercise?",
                message = "Remove ${target.name} and all its sets from this workout?",
                onConfirm = {
                    exercises = exercises.toMutableList().also { it.removeAt(index) }
                    pendingDeleteIndex = null
                },
                onCancel = { pendingDeleteIndex = null }
            )
        }
    }

    AddExerciseDialog(
        visible = showAddExerciseDialog,
        exercises = availableExercises,
        isLoading = isLoadingExercises,
        errorMessage = exerciseLoadError,
        onDismiss = { showAddExerciseDialog = false },
        onSelect = { selected ->
            addExerciseToWorkout(selected)
            showAddExerciseDialog = false
        },
        onRequestCustomExercise = { searchQuery ->
            customExercisePrefillName = searchQuery
            customExerciseError = null
            showAddExerciseDialog = false
            showCustomExerciseDialog = true
        },
        fromLastTitle = lastLikeThis?.first,
        fromLastExercises = fromLastExercises,
        onAddAll = { all ->
            all.forEach { addExerciseToWorkout(it) }
            showAddExerciseDialog = false
        },
        // Day chips only before anything is logged - after that the
        // exercises themselves say what day it is.
        dayChips = if (exercises.isEmpty()) dayChips else emptyList(),
        selectedDay = chosenDay,
        upNextDay = upNextDay,
        onSelectDay = { day ->
            chosenDay = if (chosenDay == day) null else day
            // A still-unnamed workout takes the day's name ("Legs day").
            if (chosenDay != null && !namedByUser) renameWorkout(plannedWorkoutName(day))
        }
    )

    if (showRename) {
        RenameWorkoutSheet(
            currentName = workout.workoutName,
            onDismiss = { showRename = false },
            onSave = { name ->
                showRename = false
                namedByUser = true
                renameWorkout(name)
            }
        )
    }

    CustomExerciseDialog(
        visible = showCustomExerciseDialog,
        initialName = customExercisePrefillName,

        muscleGroups = availableExercises.mapNotNull { it.muscleGroup }.distinct().sorted(),
        isSubmitting = isCreatingCustomExercise,
        errorMessage = customExerciseError,
        onDismiss = { showCustomExerciseDialog = false },
        existingExercises = availableExercises,
        onSelectExisting = { existing ->
            addExerciseToWorkout(existing)
            showCustomExerciseDialog = false
        },
        onSubmit = { name, muscleGroup, isBodyweight ->
            isCreatingCustomExercise = true
            customExerciseError = null
            coroutineScope.launch {
                try {
                    val created = NetworkClient.workoutApi.createExercise(
                        NewExerciseRequest(
                            name = name,
                            muscleGroup = muscleGroup,
                            equipment = if (isBodyweight) "Body Only" else "Equipment"
                        )
                    )
                    availableExercises = availableExercises + created
                    AppDataCache.addExercise(created)
                    addExerciseToWorkout(created)
                    showCustomExerciseDialog = false
                } catch (e: Exception) {
                    customExerciseError = "Couldn't add that exercise. Check your connection, or it may already exist."
                } finally {
                    isCreatingCustomExercise = false
                }
            }
        }
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Minimizes into the floating mini-player instead of ending
                // the workout - distinct from the X below, which discards
                // this screen outright.
                IconButton(onClick = onMinimize) {
                    Icon(imageVector = Icons.Filled.ArrowBack, contentDescription = "Minimize", tint = AppTextPrimary)
                }
                IconButton(onClick = { showDiscardConfirm = true }, enabled = !isEndingWorkout) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "Discard workout", tint = AppTextPrimary)
                }
            }
            // Tap the title to rename the workout.
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showRename = true }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = workout.workoutName,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppTextPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Rename workout",
                    tint = AppTextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
            // A past workout has no clock to pause.
            if (!ActiveWorkoutState.isPast) {
                TextButton(
                    onClick = onTogglePause,
                    colors = pausePillColors()
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = null,
                        tint = AppAccent
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isPaused) "RESUME" else "PAUSE",
                        color = AppAccent,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(AppAccent, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when {
                        ActiveWorkoutState.isPast -> "LOGGING A PAST WORKOUT"
                        isPaused -> "WORKOUT PAUSED"
                        else -> "WORKOUT IN PROGRESS"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AppAccent
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (ActiveWorkoutState.isPast) {
                // The day it's for instead of a clock.
                Text(
                    text = ActiveWorkoutState.pastDateLabel.orEmpty(),
                    style = MaterialTheme.typography.displaySmall,
                    color = AppTextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${ActiveWorkoutState.pastTimeLine.orEmpty()} · no timer, just enter what you did",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextMuted,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = formatElapsed(elapsedSeconds),
                    style = MaterialTheme.typography.displayMedium,
                    color = AppTextPrimary
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)
        ) {
            itemsIndexed(exercises) { index, exercise ->
                SwipeToDeleteExerciseCard(
                    exercise = exercise,
                    elapsedSeconds = elapsedSeconds,
                    onSetsChange = { updatedSets ->
                        exercises = exercises.toMutableList().also {
                            it[index] = exercise.copy(sets = updatedSets)
                        }
                    },
                    onDelete = { pendingDeleteIndex = index }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            item {
                AddExerciseButton(onClick = { showAddExerciseDialog = true })
            }
        }

        NotesField(
            value = notesText,
            onValueChange = { notesText = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        )

        if (endError != null) {
            Text(
                text = endError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = AppDanger,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            )
        }

        // Saves the workout: on the phone first, then uploaded (see OfflineSync).
        fun saveWorkout() {
            if (isEndingWorkout) return
            logWorkoutData(workout, exercises, notesText)
            isEndingWorkout = true
            endError = null
            coroutineScope.launch {
                // 1. Saved on the phone first - from here on the workout
                //    can't be lost, signal or not.
                val saved = try {
                    PendingWorkoutStore.add(
                        context,
                        pendingWorkoutFrom(
                            workoutId = workout.id,
                            name = ActiveWorkoutState.workout?.workoutName ?: workout.workoutName,
                            exercises = exercises,
                            notes = notesText,
                            elapsedSeconds = ActiveWorkoutState.elapsedSeconds,
                            startedAtMillis = ActiveWorkoutState.effectiveStartMillis,
                            // A past workout ends when its length says, on its own day.
                            endedAtMillis = ActiveWorkoutState.pastStartMillis
                                ?.let { it + ActiveWorkoutState.pastMinutes * 60_000L }
                                ?: System.currentTimeMillis(),
                            isPast = ActiveWorkoutState.isPast
                        )
                    )
                    true
                } catch (e: Exception) {
                    Log.e("EndWorkout", "Couldn't save workout ${workout.id} on the phone", e)
                    CrashReporting.record(e)
                    false
                }
                if (saved) {
                    // 2. Upload now if there's signal (a few seconds at most);
                    //    otherwise it's queued and goes up on its own later.
                    val uploaded = OfflineSync.syncNow(context, workout.id)
                    if (uploaded) {
                        try {
                            WorkoutHeatmapWidget().updateAll(context)
                        } catch (e: Exception) {
                            Log.e("EndWorkout", "Couldn't refresh the home-screen widget", e)
                        }
                    }
                    WorkoutNamer.init(context)
                    if (WorkoutNamer.enabled && !namedByUser && !ActiveWorkoutState.isPast) {
                        // Freeze the clock while the name card is up.
                        ActiveWorkoutState.isPaused = true
                        val facts = sessionFactsAfterSave(workout.id, exercises, ActiveWorkoutState.elapsedSeconds)
                        nameSuggestion = WorkoutNamer.suggest(facts) to facts
                        isEndingWorkout = false
                    } else {
                        // Make sure a name typed during the workout reached
                        // the server (its first try may have failed). A
                        // queued workout carries its name with it.
                        if ((namedByUser || chosenDay != null) && !PendingWorkoutStore.has(workout.id)) {
                            runCatching {
                                NetworkClient.workoutApi.updateWorkout(
                                    workout.id,
                                    UpdateWorkoutRequest(workoutName = ActiveWorkoutState.workout?.workoutName ?: workout.workoutName)
                                )
                            }
                        }
                        isEndingWorkout = false
                        // Only now does the screen close - a failed save
                        // keeps everything here to try again.
                        onEndWorkout(notesText)
                    }
                } else {
                    isEndingWorkout = false
                    endError = "Couldn't save your workout on this phone. Tap End Workout again - your sets are still here."
                }
            }
        }
        // A past workout gets one check of its day and numbers first.
        if (showSavePast) {
            SavePastWorkoutPopup(
                dateLabel = ActiveWorkoutState.pastDateLabel.orEmpty(),
                timeLabel = ActiveWorkoutState.pastTimeLine.orEmpty(),
                exerciseCount = exercises.count { e -> e.sets.any { it.reps.toIntOrNull() != null } },
                setCount = exercises.sumOf { e -> e.sets.count { it.reps.toIntOrNull() != null } },
                onSave = { showSavePast = false; saveWorkout() },
                onEdit = { showSavePast = false }
            )
        }
        Button(
            onClick = { if (ActiveWorkoutState.isPast) showSavePast = true else saveWorkout() },
            enabled = !isEndingWorkout,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) {
            if (isEndingWorkout) {
                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp))
            } else {
                Text(text = if (ActiveWorkoutState.isPast) "Save Workout" else "End Workout", fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(imageVector = Icons.Filled.ArrowForward, contentDescription = null)
            }
        }
    }
}

@Composable
private fun pausePillColors() = ButtonDefaults.textButtonColors(containerColor = AppSurfaceVariant)

@Composable
private fun LaunchedEffectTicker(paused: Boolean, onTick: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(paused) {
        while (!paused) {
            delay(1000)
            onTick()
        }
    }
}

private fun logWorkoutData(
    workout: WorkoutResponse,
    exercises: List<ActiveExercise>,
    notes: String
) {
    val report = buildString {
        appendLine("===== WORKOUT SUMMARY (id=${workout.id}) =====")
        appendLine("Name: ${workout.workoutName}")
        appendLine("Started at: ${workout.startedAt}")
        appendLine("Notes: ${if (notes.isBlank()) "(none)" else notes}")
        appendLine("Exercise count: ${exercises.size}")
        exercises.forEachIndexed { exerciseIndex, exercise ->
            appendLine("---- Exercise ${exerciseIndex + 1}: ${exercise.name} (${exercise.muscleGroup}, rest ${exercise.restLabel}) ----")
            if (exercise.sets.isEmpty()) {
                appendLine("    (no sets logged)")
            }
            exercise.sets.forEach { set ->
                appendLine("    Set ${set.setNumber}: ${set.kg}kg x ${set.reps} reps  (previous: ${set.previousLabel})")
            }
        }
        appendLine("===== END WORKOUT SUMMARY =====")
    }
    Log.d("WorkoutData", report)
}

@Composable
private fun ExerciseCard(
    exercise: ActiveExercise,
    elapsedSeconds: Int,
    onSetsChange: (List<ExerciseSet>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurface, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = exercise.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppTextPrimary
                )
                Text(
                    text = "${exercise.muscleGroup} • REST ${exercise.restLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        // Column widths here must match SetRow's: 40dp set box, 44dp time, then KG/REPS split the rest.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("SET", style = MaterialTheme.typography.labelSmall, color = AppTextMuted, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
            Text("TIME", style = MaterialTheme.typography.labelSmall, color = AppTextMuted, modifier = Modifier.width(44.dp), textAlign = TextAlign.Center)
            Text(
                text = if (exercise.isBodyweight) "ADDED KG" else "KG",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Text("REPS", style = MaterialTheme.typography.labelSmall, color = AppTextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
        Spacer(modifier = Modifier.height(10.dp))
        exercise.sets.forEachIndexed { rowIndex, set ->
            SwipeToDeleteSetRow(
                set = set,
                onKgChange = { newKg ->
                    val updated = exercise.sets.toMutableList()
                    updated[rowIndex] = set.copy(kg = newKg)
                    onSetsChange(updated)
                },
                onRepsChange = { newReps ->
                    val updated = exercise.sets.toMutableList()
                    updated[rowIndex] = set.copy(reps = newReps)
                    onSetsChange(updated)
                },
                onDelete = {
                    val updated = exercise.sets.toMutableList()
                    updated.removeAt(rowIndex)

                    onSetsChange(updated.mapIndexed { i, s -> s.copy(setNumber = i + 1) })
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        TextButton(
            onClick = {
                val nextNumber = exercise.sets.size + 1
                val defaultKg = if (exercise.isBodyweight) "0" else ""
                onSetsChange(
                    exercise.sets + ExerciseSet(
                        setNumber = nextNumber,
                        previousLabel = "-",
                        kg = defaultKg,
                        reps = "",
                        // Read from the clock at the tap, not the value from the last redraw.
                        loggedAtSeconds = ActiveWorkoutState.elapsedSeconds
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null, tint = AppTextSecondary)
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "ADD SET", color = AppTextSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** An exercise card that can be swiped left to delete - using the strict
 *  swipe from SwipeToDelete.kt, so scrolling never triggers it. Deleting
 *  still asks first (onDelete opens the confirmation). */
@Composable
private fun SwipeToDeleteExerciseCard(
    exercise: ActiveExercise,
    elapsedSeconds: Int,
    onSetsChange: (List<ExerciseSet>) -> Unit,
    onDelete: () -> Unit
) {
    SwipeToDeleteBox(onRequestDelete = onDelete, contentDescription = "Delete exercise") {
        ExerciseCard(exercise = exercise, elapsedSeconds = elapsedSeconds, onSetsChange = onSetsChange)
    }
}

/** A set row that can be swiped left to delete it. Same strict swipe: it
 *  only starts on a clearly sideways drag and only deletes when let go past
 *  40% of the width, so scrolling through sets can't remove one. The row's
 *  own swipe wins over the exercise card's. */
@Composable
private fun SwipeToDeleteSetRow(
    set: ExerciseSet,
    onKgChange: (String) -> Unit,
    onRepsChange: (String) -> Unit,
    onDelete: () -> Unit
) {
    SwipeToDeleteBox(onRequestDelete = onDelete, contentDescription = "Delete set", cornerRadius = 10.dp) {
        Box(modifier = Modifier.fillMaxWidth().background(AppSurface)) {
            SetRow(set = set, onKgChange = onKgChange, onRepsChange = onRepsChange)
        }
    }
}

@Composable
private fun SetRow(
    set: ExerciseSet,
    onKgChange: (String) -> Unit,
    onRepsChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Set number in a rounded square matching the KG/REPS boxes.
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(AppSurfaceVariant, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = set.setNumber.toString(),
                color = AppTextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        // The workout timer's value when this set was added - two
        // consecutive sets' values show how much rest was taken
        // between them.
        Text(
            // A past workout has no timer, so no time per set.
            text = if (ActiveWorkoutState.isPast) "–" else formatElapsedShort(set.loggedAtSeconds),
            color = AppTextMuted,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(44.dp)
        )
        SetInputField(
            value = set.kg,
            onValueChange = onKgChange,
            backgroundColor = AppSurfaceVariant,
            textColor = AppTextPrimary,
            modifier = Modifier.weight(1f)
        )
        SetInputField(
            value = set.reps,
            onValueChange = onRepsChange,
            backgroundColor = AppSurfaceVariant,
            textColor = AppTextPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SetInputField(
    value: String,
    onValueChange: (String) -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                innerTextField()
            }
        }
    )
}

@Composable
private fun NotesField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "NOTES",
            style = MaterialTheme.typography.labelSmall,
            color = AppTextMuted
        )
        Spacer(modifier = Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = AppTextPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .background(AppSurface, RoundedCornerShape(14.dp))
                .padding(14.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = "Add notes about this workout...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTextMuted
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

/**
 * Facts about a just-saved workout for its fun name. Checks the server's PR
 * flags for each exercise (in parallel, giving up after a few seconds -
 * without them the name just won't mention a PR) and compares with the
 * previous workouts.
 */
private suspend fun sessionFactsAfterSave(
    workoutId: Int,
    exercises: List<ActiveExercise>,
    elapsedSeconds: Int
): SessionFacts {
    val prs: List<Pair<String, Double?>> = kotlinx.coroutines.withTimeoutOrNull(4000) {
        kotlinx.coroutines.coroutineScope {
            exercises.distinctBy { it.exerciseId }.map { exercise ->
                async {
                    runCatching {
                        val history = AppDataCache.loadExerciseHistory(exercise.exerciseId, force = true)
                        history.history.firstOrNull { point -> point.isPr && point.sets.any { it.workoutId == workoutId } }
                            ?.let { exercise.name to it.maxWeightKg }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    } ?: emptyList()
    val bestPr = prs.maxByOrNull { it.second ?: 0.0 }
    val history = AppDataCache.workoutSummaries ?: runCatching { AppDataCache.loadWorkoutSummaries() }.getOrNull().orEmpty()
    return WorkoutNamer.factsFor(
        exercises = exercises,
        elapsedSeconds = elapsedSeconds,
        history = history,
        workoutId = workoutId,
        plan = TrainingPlanStore.plan,
        prExercise = bestPr?.first,
        prWeightKg = bestPr?.second
    )
}

/** The name a workout started from "Start Empty Workout" gets until the
 *  user (or the fun-name card at the end) names it. */
const val DEFAULT_WORKOUT_NAME = "Workout"

/** Rename the running workout - opened by tapping its title. */
@Composable
private fun RenameWorkoutSheet(currentName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(currentName, androidx.compose.ui.text.TextRange(0, currentName.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AppBottomSheet(visible = true, onDismissRequest = onDismiss) {
        Text(text = "Rename workout", style = MaterialTheme.typography.titleLarge, color = AppTextPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.text.length <= 60) name = it },
            singleLine = true,
            placeholder = { Text(text = "e.g. Leg Day", color = AppTextMuted) },
            keyboardOptions = KeyboardOptions(
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (name.text.isNotBlank()) onSave(name.text) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
            colors = appTextFieldColors()
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { onSave(name.text) },
            enabled = name.text.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) {
            Text(text = "Save", fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Cancel", color = AppTextSecondary)
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}
