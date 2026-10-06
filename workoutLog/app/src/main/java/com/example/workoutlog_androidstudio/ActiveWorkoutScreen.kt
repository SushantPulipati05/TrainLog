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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
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
import com.example.workoutlog_androidstudio.api.CompleteWorkoutRequest
import com.example.workoutlog_androidstudio.api.ExerciseResponse
import com.example.workoutlog_androidstudio.api.NetworkClient
import com.example.workoutlog_androidstudio.api.NewExerciseRequest
import com.example.workoutlog_androidstudio.api.NewSetRequest
import com.example.workoutlog_androidstudio.api.WorkoutResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    // Set when End Workout couldn't reach the server; the screen stays open
    // (nothing is lost) so the person can simply try again.
    var endError by remember { mutableStateOf<String?>(null) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

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
                    loggedAtSeconds = elapsedSeconds
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
        }
    )

    CustomExerciseDialog(
        visible = showCustomExerciseDialog,
        initialName = customExercisePrefillName,

        muscleGroups = availableExercises.mapNotNull { it.muscleGroup }.distinct().sorted(),
        isSubmitting = isCreatingCustomExercise,
        errorMessage = customExerciseError,
        onDismiss = { showCustomExerciseDialog = false },
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
            Text(
                text = workout.workoutName,
                style = MaterialTheme.typography.titleLarge,
                color = AppTextPrimary
            )
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
                    text = if (isPaused) "WORKOUT PAUSED" else "WORKOUT IN PROGRESS",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppAccent
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatElapsed(elapsedSeconds),
                style = MaterialTheme.typography.displayMedium,
                color = AppTextPrimary
            )
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

        Button(
            onClick = {
                if (isEndingWorkout) return@Button
                logWorkoutData(workout, exercises, notesText)
                isEndingWorkout = true
                endError = null
                coroutineScope.launch {
                    val saved = try {
                        syncWorkoutToServer(workout.id, exercises, notesText)
                        true
                    } catch (e: Exception) {
                        Log.e("EndWorkout", "Failed to sync workout ${workout.id}", e)
                        // Recovered from (the workout stays on the phone to
                        // retry), but worth knowing about if it keeps happening.
                        CrashReporting.record(e)
                        false
                    }
                    isEndingWorkout = false
                    if (saved) {
                        try {
                            WorkoutHeatmapWidget().updateAll(context)
                        } catch (e: Exception) {
                            Log.e("EndWorkout", "Couldn't refresh the home-screen widget", e)
                        }
                        // Only now does the screen close - a failed save
                        // keeps everything here to try again.
                        onEndWorkout(notesText)
                    } else {
                        endError = "Couldn't save your workout. Check your connection and tap End Workout again - your sets are safe on this phone."
                    }
                }
            },
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
                Text(text = "End Workout", fontWeight = FontWeight.SemiBold)
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

/** Sends the whole finished workout - every set with reps filled in, plus
 *  the notes - in ONE request that the server saves all-or-nothing. Safe to
 *  retry: sending it again replaces the sets rather than adding duplicates,
 *  so a failed attempt on bad gym signal can simply be tried again. */
private suspend fun syncWorkoutToServer(
    workoutId: Int,
    exercises: List<ActiveExercise>,
    notes: String
) {
    val sets = exercises.flatMap { exercise ->
        exercise.sets.mapNotNull { set ->
            val reps = set.reps.toIntOrNull() ?: return@mapNotNull null
            NewSetRequest(
                exerciseId = exercise.exerciseId,
                setNumber = set.setNumber,
                reps = reps,
                weightKg = set.kg.toDoubleOrNull()
            )
        }
    }

    NetworkClient.workoutApi.completeWorkout(
        workoutId = workoutId,
        request = CompleteWorkoutRequest(notes = notes.ifBlank { null }, sets = sets)
    )
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
            IconButton(onClick = { }) {
                Icon(imageVector = Icons.Filled.MoreVert, contentDescription = null, tint = AppTextSecondary)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("SET", style = MaterialTheme.typography.labelSmall, color = AppTextMuted, modifier = Modifier.weight(0.9f))
            Spacer(modifier = Modifier.weight(1.1f))
            Text(
                text = if (exercise.isBodyweight) "ADDED KG" else "KG",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Text("REPS", style = MaterialTheme.typography.labelSmall, color = AppTextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.width(32.dp))
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
                        loggedAtSeconds = elapsedSeconds
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteExerciseCard(
    exercise: ActiveExercise,
    elapsedSeconds: Int,
    onSetsChange: (List<ExerciseSet>) -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { targetValue ->
            if (targetValue == SwipeToDismissBoxValue.EndToStart) {
                // Ask first; returning false snaps the card back until the
                // user confirms in the popup.
                onDelete()
            }
            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(AppDanger)
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete exercise",
                    tint = Color.White
                )
            }
        }
    ) {
        ExerciseCard(exercise = exercise, elapsedSeconds = elapsedSeconds, onSetsChange = onSetsChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteSetRow(
    set: ExerciseSet,
    onKgChange: (String) -> Unit,
    onRepsChange: (String) -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { targetValue ->
            if (targetValue == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppDanger)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete set",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    ) {

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
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(0.9f)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(AppSurfaceVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = set.setNumber.toString(), color = AppTextPrimary, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(modifier = Modifier.height(2.dp))
            // The workout timer's value when this set was added - two
            // consecutive sets' values show how much rest was taken
            // between them.
            Text(
                text = formatElapsedShort(set.loggedAtSeconds),
                color = AppTextMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(modifier = Modifier.weight(1.1f))
        SetInputField(
            value = set.kg,
            onValueChange = onKgChange,
            backgroundColor = AppSurfaceVariant,
            textColor = AppTextPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        )
        SetInputField(
            value = set.reps,
            onValueChange = onRepsChange,
            backgroundColor = AppSurfaceVariant,
            textColor = AppTextPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
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
