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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import com.example.workoutlog_androidstudio.api.ExerciseResponse
import com.example.workoutlog_androidstudio.api.NetworkClient
import com.example.workoutlog_androidstudio.api.NewExerciseRequest
import com.example.workoutlog_androidstudio.api.SetEntryResponse
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
    var showEndConfirm by remember { mutableStateOf(false) }

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
        // A phone-made workout takes its name with it when it uploads.
        if (isLocalWorkoutId(workout.id)) return
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
        // As many rows as last time (when its history is already on the
        // phone), so last time's numbers show in grey on every set. If it
        // isn't loaded yet, the card adds the rows once it is.
        val lastCount = AppDataCache.exerciseHistories[selected.id]?.history?.lastOrNull()?.sets?.size ?: 1
        val now = ActiveWorkoutState.elapsedSeconds
        exercises = exercises + ActiveExercise(
            exerciseId = selected.id,
            name = selected.name,
            muscleGroup = selected.muscleGroup?.uppercase() ?: "GENERAL",
            restLabel = "90S",
            isBodyweight = isBodyweight,
            sets = blankSets(count = lastCount.coerceIn(1, 10), isBodyweight = isBodyweight, loggedAtSeconds = now)
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
                                NetworkClient.workoutApi.updateWorkout(OfflineSync.serverIdFor(workout.id), UpdateWorkoutRequest(workoutName = chosen))
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
        onSubmit = { name, muscleGroup, isBodyweight, primary, secondary ->
            isCreatingCustomExercise = true
            customExerciseError = null
            coroutineScope.launch {
                try {
                    val created = NetworkClient.workoutApi.createExercise(
                        NewExerciseRequest(
                            name = name,
                            muscleGroup = muscleGroup,
                            equipment = if (isBodyweight) "Body Only" else "Equipment",
                            primaryMuscles = primary,
                            secondaryMuscles = secondary
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

    // While the keyboard is up the screen shrinks to the space above it
    // (imePadding), and Notes and End Workout step aside so the set rows
    // keep the room - unless Notes is what's being typed in.
    @OptIn(ExperimentalLayoutApi::class)
    val keyboardOpen = WindowInsets.isImeVisible
    var notesFocused by remember { mutableStateOf(false) }
    val showBottom = !keyboardOpen || notesFocused
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
            .imePadding()
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

        if (showBottom) {
            NotesField(
                value = notesText,
                onValueChange = { notesText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .onFocusChanged { notesFocused = it.isFocused },
                // Typing notes: the keyboard's Done closes it again.
                maxLines = if (keyboardOpen) 3 else Int.MAX_VALUE
            )
        }

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
        // [finishedAtSeconds]: the workout timer value it really ended at
        // (from "Did you finish earlier?"), or null for now.
        fun saveWorkout(finishedAtSeconds: Int? = null) {
            if (isEndingWorkout) return
            // Touched rows take last time's number in any box left empty.
            val finalExercises = withLastTimeFilled(exercises)
            val durationSeconds = finishedAtSeconds ?: ActiveWorkoutState.elapsedSeconds
            logWorkoutData(workout, finalExercises, notesText)
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
                            exercises = finalExercises,
                            notes = notesText,
                            elapsedSeconds = durationSeconds,
                            startedAtMillis = ActiveWorkoutState.effectiveStartMillis,
                            // A past workout ends when its length says, on its own day;
                            // "Finished at 7:45 PM" ends it then, not now.
                            endedAtMillis = ActiveWorkoutState.pastStartMillis
                                ?.let { it + ActiveWorkoutState.pastMinutes * 60_000L }
                                ?: finishedAtSeconds?.let { ActiveWorkoutState.effectiveStartMillis + it * 1000L }
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
                        val facts = sessionFactsAfterSave(OfflineSync.serverIdFor(workout.id), finalExercises, durationSeconds)
                        nameSuggestion = WorkoutNamer.suggest(facts) to facts
                        isEndingWorkout = false
                    } else {
                        // Make sure a name typed during the workout reached
                        // the server (its first try may have failed). A
                        // queued workout carries its name with it.
                        if ((namedByUser || chosenDay != null) && !PendingWorkoutStore.has(workout.id)) {
                            runCatching {
                                NetworkClient.workoutApi.updateWorkout(
                                    OfflineSync.serverIdFor(workout.id),
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
                exerciseCount = withLastTimeFilled(exercises).count { e -> e.sets.any { it.reps.toIntOrNull() != null } },
                setCount = withLastTimeFilled(exercises).sumOf { e -> e.sets.count { it.reps.toIntOrNull() != null } },
                onSave = { showSavePast = false; saveWorkout() },
                onEdit = { showSavePast = false }
            )
        }
        // End Workout asks first (see EndWorkoutPopup).
        if (showEndConfirm) {
            val filled = withLastTimeFilled(exercises)
            val doneSets = filled.flatMap { e -> e.sets.filter { it.reps.toIntOrNull() != null } }
            val elapsed = ActiveWorkoutState.elapsedSeconds
            // When the last set was really typed (rows added in advance
            // don't count), to catch a workout that was left running.
            val lastActivity = doneSets.maxOfOrNull { it.editedAtSeconds ?: it.loggedAtSeconds } ?: 0
            EndWorkoutPopup(
                durationSeconds = elapsed,
                exerciseCount = filled.count { e -> e.sets.any { it.reps.toIntOrNull() != null } },
                setCount = doneSets.size,
                startMillis = ActiveWorkoutState.effectiveStartMillis,
                lastSetSeconds = lastActivity.takeIf { doneSets.isNotEmpty() && elapsed - it > FORGOT_AFTER_SECONDS },
                onKeepGoing = { showEndConfirm = false },
                onDiscard = { showEndConfirm = false; onClose() },
                onEnd = { finishedAt -> showEndConfirm = false; saveWorkout(finishedAt) }
            )
        }
        if (!keyboardOpen) Button(
            onClick = { if (ActiveWorkoutState.isPast) showSavePast = true else showEndConfirm = true },
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
    // ---- Your best ever and last time's sets (from its history; the copy
    // saved on the phone works offline). Nothing the first time.
    var history by remember(exercise.exerciseId) { mutableStateOf(AppDataCache.exerciseHistories[exercise.exerciseId]) }
    LaunchedEffect(exercise.exerciseId) {
        runCatching { AppDataCache.loadExerciseHistory(exercise.exerciseId) }.getOrNull()?.let { history = it }
    }
    val best = remember(history) { topSetOf(history?.history.orEmpty().flatMap { it.sets }) }
    val lastSets = remember(history) { history?.history?.lastOrNull()?.sets.orEmpty().sortedBy { it.setNumber } }
    val lastTime = remember(lastSets) { topSetOf(lastSets) }
    // Its history arrived after it was added: give it as many rows as last
    // time - only while it's still one untouched row, so nothing typed (or
    // deliberately removed) is ever changed.
    LaunchedEffect(lastSets.size) {
        val untouched = exercise.sets.size == 1 && exercise.sets.all { it.isBlank(exercise.isBodyweight) }
        if (untouched && lastSets.size > 1) {
            onSetsChange(
                blankSets(
                    count = lastSets.size.coerceAtMost(10),
                    isBodyweight = exercise.isBodyweight,
                    loggedAtSeconds = exercise.sets.first().loggedAtSeconds
                )
            )
        }
    }

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
                // Its exact muscles ("SIDE DELTS"), from the exercise list.
                val known = AppDataCache.exercises?.firstOrNull { it.id == exercise.exerciseId }
                Text(
                    text = exerciseMuscleLabel(known?.primaryMuscles.orEmpty(), exercise.muscleGroup).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted
                )
                // "Best 70 kg × 6 · last time 65 kg × 8" - straight under the
                // muscle, same spacing as the muscle under the name.
                if (best != null || lastTime != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = androidx.compose.ui.text.buildAnnotatedString {
                            best?.let {
                                append("Best ")
                                withStyle(androidx.compose.ui.text.SpanStyle(color = AppAccent, fontWeight = FontWeight.SemiBold)) { append(it.label()) }
                            }
                            if (best != null && lastTime != null) append(" · ")
                            lastTime?.let { append("last time ${it.label()}") }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = AppTextSecondary
                    )
                }
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
            // Empty boxes show last time's numbers for this set in grey.
            val ghost = lastSets.getOrNull(rowIndex)
            // New users: the tip about grey numbers points at the first such row.
            val tipHere = ghost != null && rowIndex == 0 &&
                ActiveWorkoutState.exercises.firstOrNull { e -> e.sets.isNotEmpty() }?.exerciseId == exercise.exerciseId
            Box(modifier = if (tipHere) Modifier.guideTarget(GuideTip.EXERCISE_CARD) else Modifier) {
                SwipeToDeleteSetRow(
                    set = set,
                    ghostKg = ghostKgOf(ghost),
                    ghostReps = ghostRepsOf(ghost),
                    // Tap the set number: last time's numbers, both boxes, one tap.
                    onFillFromLast = if (ghost == null) null else {
                        {
                            val updated = exercise.sets.toMutableList()
                            val kgEmpty = set.kg.isBlank() || (exercise.isBodyweight && set.kg == "0")
                            updated[rowIndex] = set.copy(
                                kg = if (kgEmpty) ghostKgOf(ghost) ?: set.kg else set.kg,
                                reps = set.reps.ifBlank { ghostRepsOf(ghost).orEmpty() },
                                touched = true,
                                editedAtSeconds = ActiveWorkoutState.elapsedSeconds
                            )
                            onSetsChange(updated)
                        }
                    },
                    onKgChange = { newKg ->
                        val updated = exercise.sets.toMutableList()
                        updated[rowIndex] = set.copy(kg = cleanKgInput(newKg), touched = true, editedAtSeconds = ActiveWorkoutState.elapsedSeconds)
                        onSetsChange(updated)
                    },
                    onRepsChange = { newReps ->
                        val updated = exercise.sets.toMutableList()
                        updated[rowIndex] = set.copy(reps = cleanRepsInput(newReps), touched = true, editedAtSeconds = ActiveWorkoutState.elapsedSeconds)
                        onSetsChange(updated)
                    },
                    onDelete = {
                        val updated = exercise.sets.toMutableList()
                        updated.removeAt(rowIndex)

                        onSetsChange(updated.mapIndexed { i, s -> s.copy(setNumber = i + 1) })
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        // Beat your best with a set typed in just now: say so straight away.
        val todayTop = topSetOf(withLastTimeFilled(listOf(exercise)).first().sets.mapNotNull { s ->
            val reps = s.reps.toIntOrNull() ?: return@mapNotNull null
            TopSet(parseKg(s.kg)?.takeIf { it > 0.0 }, reps)
        })
        if (best != null && todayTop != null && todayTop.beats(best)) {
            Text(
                text = "New best: ${todayTop.short()} beats ${best.short()}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppAccent
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        TextButton(
            onClick = {
                val nextNumber = exercise.sets.size + 1
                // Same weight as the set above (most sets repeat it); reps
                // stay empty - they usually change.
                val defaultKg = exercise.sets.lastOrNull()?.kg?.takeIf { it.isNotBlank() }
                    ?: if (exercise.isBodyweight) "0" else ""
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
    onDelete: () -> Unit,
    ghostKg: String? = null,
    ghostReps: String? = null,
    onFillFromLast: (() -> Unit)? = null
) {
    SwipeToDeleteBox(onRequestDelete = onDelete, contentDescription = "Delete set", cornerRadius = 10.dp) {
        Box(modifier = Modifier.fillMaxWidth().background(AppSurface)) {
            SetRow(
                set = set,
                onKgChange = onKgChange,
                onRepsChange = onRepsChange,
                ghostKg = ghostKg,
                ghostReps = ghostReps,
                onFillFromLast = onFillFromLast
            )
        }
    }
}

@Composable
private fun SetRow(
    set: ExerciseSet,
    onKgChange: (String) -> Unit,
    onRepsChange: (String) -> Unit,
    ghostKg: String? = null,
    ghostReps: String? = null,
    onFillFromLast: (() -> Unit)? = null
) {
    // Once the row is touched, last time's number in an empty box will be
    // saved with it - so it turns white instead of grey.
    val placeholderColor = if (set.touched) AppTextPrimary else GHOST_TEXT
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Set number in a rounded square matching the KG/REPS boxes.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AppSurfaceVariant)
                .then(
                    if (onFillFromLast != null) {
                        Modifier.clickable(onClickLabel = "Fill in last time's numbers", onClick = onFillFromLast)
                    } else Modifier
                ),
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
            placeholder = ghostKg,
            placeholderColor = placeholderColor,
            isWeight = true,
            modifier = Modifier.weight(1f)
        )
        SetInputField(
            value = set.reps,
            onValueChange = onRepsChange,
            backgroundColor = AppSurfaceVariant,
            textColor = AppTextPrimary,
            placeholder = ghostReps,
            placeholderColor = placeholderColor,
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
    modifier: Modifier = Modifier,
    /** Shown in grey while empty - last time's number for this set. */
    placeholder: String? = null,
    placeholderColor: Color = GHOST_TEXT,
    /** A weight box: the keypad has a decimal point (for 2.5 kg steps). */
    isWeight: Boolean = false
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = if (isWeight) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = placeholderColor,
                        textAlign = TextAlign.Center
                    )
                }
                innerTextField()
            }
        }
    )
}

@Composable
private fun NotesField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE
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
            maxLines = maxLines,
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


/** One set's weight (null = bodyweight, nothing added) and reps. */
private data class TopSet(val weightKg: Double?, val reps: Int) {
    /** "70 kg × 6", or "12 reps" for bodyweight. */
    fun label(): String = if (weightKg != null) "${formatDecimal(weightKg)} kg × $reps" else "$reps reps"
    /** "70 kg" / "12 reps" - for the "new best" line. */
    fun short(): String = if (weightKg != null) "${formatDecimal(weightKg)} kg" else "$reps reps"
    /** Heavier wins; at the same weight (or both bodyweight), more reps wins. */
    fun beats(other: TopSet): Boolean = when {
        weightKg != null && other.weightKg != null -> weightKg > other.weightKg || (weightKg == other.weightKg && reps > other.reps)
        weightKg != null -> true
        other.weightKg != null -> false
        else -> reps > other.reps
    }
}

/** The top set: the heaviest (most reps at that weight); with no weight
 *  anywhere, the most reps. */
private fun topSetOf(sets: List<TopSet>): TopSet? =
    sets.filter { it.weightKg != null }.maxWithOrNull(compareBy<TopSet>({ it.weightKg }, { it.reps }))
        ?: sets.maxByOrNull { it.reps }

@JvmName("topSetOfEntries")
private fun topSetOf(sets: List<com.example.workoutlog_androidstudio.api.SetEntryResponse>): TopSet? =
    topSetOf(sets.map { TopSet(it.weightKg?.takeIf { w -> w > 0.0 }, it.reps) })

/** Last time's numbers shown in empty set boxes - a dim grey. */
private val GHOST_TEXT = Color(0xFF55555B)

/** [count] empty set rows, numbered from 1. Bodyweight ones start at 0 kg added. */
private fun blankSets(count: Int, isBodyweight: Boolean, loggedAtSeconds: Int): List<ExerciseSet> =
    (1..count).map { n ->
        ExerciseSet(
            setNumber = n,
            previousLabel = "-",
            kg = if (isBodyweight) "0" else "",
            reps = "",
            loggedAtSeconds = loggedAtSeconds
        )
    }

/** Nothing typed into this row yet. */
private fun ExerciseSet.isBlank(isBodyweight: Boolean): Boolean =
    reps.isBlank() && (kg.isBlank() || (isBodyweight && kg == "0"))

/** A workout whose last set was typed more than an hour before End is
 *  probably one that was left running. */
private const val FORGOT_AFTER_SECONDS = 60 * 60

/** Rest assumed after the last set when ending "at the last set". */
private const val AFTER_LAST_SET_SECONDS = 3 * 60

/** Last session's sets for an exercise, in set order (from the history the
 *  exercise card loaded - the same numbers shown in grey). */
private fun lastTimeSets(exerciseId: Int): List<SetEntryResponse> =
    AppDataCache.exerciseHistories[exerciseId]?.history?.lastOrNull()?.sets.orEmpty().sortedBy { it.setNumber }

private fun ghostKgOf(last: SetEntryResponse?): String? = last?.weightKg?.takeIf { it > 0.0 }?.let { formatDecimal(it) }

private fun ghostRepsOf(last: SetEntryResponse?): String? = last?.reps?.toString()

/**
 * The exercises as they'll be saved: in every row the user touched, an
 * empty box takes last time's number for that set (the white placeholder
 * they saw). Untouched rows stay as they are - empty, so not saved.
 */
private fun withLastTimeFilled(exercises: List<ActiveExercise>): List<ActiveExercise> =
    exercises.map { exercise ->
        val last = lastTimeSets(exercise.exerciseId)
        exercise.copy(sets = exercise.sets.mapIndexed { i, set ->
            if (!set.touched) return@mapIndexed set
            val ghost = last.getOrNull(i) ?: return@mapIndexed set
            val kgEmpty = set.kg.isBlank() || (exercise.isBodyweight && set.kg == "0")
            set.copy(
                kg = if (kgEmpty) ghostKgOf(ghost) ?: set.kg else set.kg,
                reps = set.reps.ifBlank { ghostRepsOf(ghost).orEmpty() }
            )
        })
    }

/** "52m", "1h 05m". */
private fun durationLabel(seconds: Int): String {
    val minutes = seconds / 60
    return if (minutes >= 60) "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}m" else "${minutes}m"
}

/**
 * Asks before ending a workout - three versions (see logging-fixes mockup):
 * nothing logged (discard instead of saving an empty workout), last set
 * over an hour ago (end at the last set or now), or the usual summary.
 * [onEnd] gets the workout-timer second it ended at, or null for now.
 */
@Composable
private fun EndWorkoutPopup(
    durationSeconds: Int,
    exerciseCount: Int,
    setCount: Int,
    startMillis: Long,
    lastSetSeconds: Int?,
    onKeepGoing: () -> Unit,
    onDiscard: () -> Unit,
    onEnd: (Int?) -> Unit
) {
    val context = LocalContext.current
    fun clock(timerSeconds: Int): String {
        val time = java.time.Instant.ofEpochMilli(startMillis + timerSeconds * 1000L)
            .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        return formatGymTime(context, time.hour, time.minute)
    }
    androidx.compose.ui.window.Popup(
        alignment = Alignment.Center,
        onDismissRequest = onKeepGoing,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true, dismissOnClickOutside = true)
    ) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .background(AppSurface, RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            when {
                setCount == 0 -> {
                    Text("Nothing logged yet", style = MaterialTheme.typography.titleLarge, color = AppTextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "No sets have reps, so there's nothing to save.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    PopupButtons(secondary = "Keep going", onSecondary = onKeepGoing, primary = "Discard", onPrimary = onDiscard)
                }
                lastSetSeconds != null -> {
                    val finishedAt = lastSetSeconds + AFTER_LAST_SET_SECONDS
                    Text("Did you finish earlier?", style = MaterialTheme.typography.titleLarge, color = AppTextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = androidx.compose.ui.text.buildAnnotatedString {
                            append("Your last set was at ")
                            withStyle(androidx.compose.ui.text.SpanStyle(color = AppTextPrimary, fontWeight = FontWeight.SemiBold)) {
                                append(clock(lastSetSeconds))
                            }
                            append(", ${durationLabel(durationSeconds - lastSetSeconds)} ago.")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(
                        onClick = { onEnd(finishedAt) },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppAccent, contentColor = AppOnAccent)
                    ) { Text("Finished at ${clock(finishedAt)}", fontWeight = FontWeight.Bold) }
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { onEnd(null) },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppSurfaceVariant, contentColor = AppTextPrimary)
                    ) { Text("Finished just now", fontWeight = FontWeight.SemiBold) }
                    TextButton(onClick = onKeepGoing, modifier = Modifier.fillMaxWidth()) {
                        Text("Keep going", color = AppTextSecondary)
                    }
                }
                else -> {
                    Text("End workout?", style = MaterialTheme.typography.titleLarge, color = AppTextPrimary)
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PopupStat(durationLabel(durationSeconds), "TIME", Modifier.weight(1f))
                        PopupStat(exerciseCount.toString(), "EXERCISES", Modifier.weight(1f))
                        PopupStat(setCount.toString(), "SETS", Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    PopupButtons(secondary = "Keep going", onSecondary = onKeepGoing, primary = "End workout", onPrimary = { onEnd(null) })
                }
            }
        }
    }
}

@Composable
private fun PopupStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(AppSurfaceVariant, RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AppTextPrimary, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
    }
}

@Composable
private fun PopupButtons(secondary: String, onSecondary: () -> Unit, primary: String, onPrimary: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onSecondary,
            modifier = Modifier.weight(1f).height(46.dp),
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppSurfaceVariant, contentColor = AppTextPrimary)
        ) { Text(secondary, fontWeight = FontWeight.SemiBold) }
        Button(
            onClick = onPrimary,
            modifier = Modifier.weight(1f).height(46.dp),
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) { Text(primary, fontWeight = FontWeight.SemiBold) }
    }
}
