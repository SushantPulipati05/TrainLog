package com.example.workoutlog_androidstudio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.workoutlog_androidstudio.api.ExerciseResponse
import com.example.workoutlog_androidstudio.api.NetworkClient
import com.example.workoutlog_androidstudio.api.NewExerciseRequest
import com.example.workoutlog_androidstudio.api.NewSetRequest
import com.example.workoutlog_androidstudio.api.SaveWorkoutAsTemplateRequest
import com.example.workoutlog_androidstudio.api.SetEntryResponse
import com.example.workoutlog_androidstudio.api.UpdateSetRequest
import com.example.workoutlog_androidstudio.api.UpdateWorkoutRequest
import com.example.workoutlog_androidstudio.api.WorkoutDetailResponse
import kotlinx.coroutines.launch

/**
 * View of one finished workout, opened by tapping a card in the "Previous
 * Workouts" list on the home screen. [workout] already carries the header
 * stats (title, date, duration, tags) computed server-side by
 * /workouts/summary - this screen fetches the per-set breakdown, and lets
 * the user edit everything about the workout except when it happened - the
 * date, time and duration stay fixed.
 *
 * NOTE: "previous" (per-set history) and PR badges aren't wired up yet -
 * that's a deliberate follow-up, not a bug.
 */
@Composable
fun WorkoutDetailScreen(
    workout: WorkoutSummary,
    onBack: () -> Unit,
    onOpenExerciseHistory: (exerciseId: Int, exerciseName: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    // Seeded straight from AppDataCache when this workout (or the exercise
    // list) has already been loaded elsewhere, so re-opening the same
    // workout never shows the spinner twice.
    var detail by remember { mutableStateOf(AppDataCache.workoutDetails[workout.id]) }
    var exercises by remember { mutableStateOf(AppDataCache.exercises ?: emptyList()) }
    var isLoading by remember { mutableStateOf(AppDataCache.workoutDetails[workout.id] == null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // The name shown at the top - starts as what the home screen passed in,
    // but reflects a saved rename once an edit has actually gone through.
    var displayedTitle by remember { mutableStateOf(workout.title) }

    // The database id (workout.id) never gets reused or renumbered when
    // older workouts are deleted, so it can jump way ahead of how many
    // workouts actually still exist (e.g. "ARCHIVE #17" with only 2 left).
    // Instead we show this workout's position among all workouts that still
    // exist, oldest first - so it always reads as a clean, gap-free count.
    var archiveNumber by remember { mutableStateOf<Int?>(null) }

    val coroutineScope = rememberCoroutineScope()

    suspend fun reload(force: Boolean = false) {
        isLoading = detail == null
        errorMessage = null
        try {
            detail = AppDataCache.loadWorkoutDetail(workout.id, force = force)
            exercises = AppDataCache.loadExercises(force = force)
            val allWorkouts = AppDataCache.loadWorkoutSummaries(force = force)
            archiveNumber = allWorkouts
                .sortedBy { it.id }
                .indexOfFirst { it.id == workout.id }
                .let { index -> if (index >= 0) index + 1 else null }
        } catch (e: Exception) {
            errorMessage = "Couldn't load this workout. Check your connection."
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(workout.id) {
        reload()
    }

    // Buckets the flat set list by exercise, in the order each exercise's
    // first set appears - groupBy preserves that order for us.
    val groupedSets: List<Pair<ExerciseResponse?, List<SetEntryResponse>>> = remember(detail, exercises) {
        detail?.sets
            ?.groupBy { it.exerciseId }
            ?.map { (exerciseId, sets) -> exercises.find { it.id == exerciseId } to sets }
            ?: emptyList()
    }

    // ---- Edit mode state ----
    var isEditing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var editedWorkoutName by remember { mutableStateOf("") }
    var editedNotes by remember { mutableStateOf("") }
    var editableExercises by remember { mutableStateOf<List<EditableExercise>>(emptyList()) }
    // Snapshot taken the moment editing starts - diffed against editableExercises
    // on save to work out exactly what changed (added, edited, or removed).
    var originalExercises by remember { mutableStateOf<List<EditableExercise>>(emptyList()) }
    var showAddExerciseDialog by remember { mutableStateOf(false) }
    var showCustomExerciseDialog by remember { mutableStateOf(false) }
    var customExercisePrefillName by remember { mutableStateOf("") }
    var isCreatingCustomExercise by remember { mutableStateOf(false) }
    var customExerciseError by remember { mutableStateOf<String?>(null) }

    // "Save as Template" - lets this already-logged workout become a
    // reusable template under "My Workouts".
    var showSaveAsTemplateDialog by remember { mutableStateOf(false) }
    var isSavingTemplate by remember { mutableStateOf(false) }
    var saveTemplateError by remember { mutableStateOf<String?>(null) }
    var templateSavedToastVisible by remember { mutableStateOf(false) }
    LaunchedEffect(templateSavedToastVisible) {
        if (templateSavedToastVisible) {
            kotlinx.coroutines.delay(2500)
            templateSavedToastVisible = false
        }
    }

    fun startEditing() {
        editedWorkoutName = displayedTitle
        editedNotes = detail?.notes.orEmpty()
        val built = groupedSets.map { (exercise, sets) ->
            EditableExercise(
                exerciseId = exercise?.id ?: sets.first().exerciseId,
                name = exercise?.name ?: "Exercise #${sets.first().exerciseId}",
                muscleGroup = exercise?.muscleGroup?.uppercase() ?: "GENERAL",
                isBodyweight = exercise?.equipment.equals("Body Only", ignoreCase = true),
                sets = sets.sortedBy { it.setNumber }.map { s ->
                    EditableSet(
                        id = s.id,
                        setNumber = s.setNumber,
                        kg = s.weightKg?.let { formatKgForInput(it) } ?: "",
                        reps = s.reps.toString()
                    )
                }
            )
        }
        editableExercises = built
        originalExercises = built
        saveError = null
        isEditing = true
    }

    fun cancelEditing() {
        isEditing = false
        saveError = null
    }

    fun saveEdits() {
        isSaving = true
        saveError = null
        coroutineScope.launch {
            try {
                // Workout name / notes - only send what actually changed.
                val nameChanged = editedWorkoutName.trim().isNotBlank() && editedWorkoutName.trim() != displayedTitle
                val notesChanged = editedNotes != detail?.notes.orEmpty()
                if (nameChanged || notesChanged) {
                    NetworkClient.workoutApi.updateWorkout(
                        workoutId = workout.id,
                        request = UpdateWorkoutRequest(
                            workoutName = if (nameChanged) editedWorkoutName.trim() else null,
                            notes = if (notesChanged) editedNotes else null
                        )
                    )
                }

                val originalById = originalExercises.associateBy { it.exerciseId }
                val editedById = editableExercises.associateBy { it.exerciseId }

                // Exercises removed entirely this session - one call cleans up
                // every set that exercise had logged.
                for (removedExerciseId in originalById.keys - editedById.keys) {
                    NetworkClient.workoutApi.deleteExerciseFromWorkout(workout.id, removedExerciseId)
                }

                // Exercises kept (edited or untouched) or newly added.
                for ((exerciseId, editedExercise) in editedById) {
                    val originalExercise = originalById[exerciseId]
                    val originalSetsById: Map<Int, EditableSet> = originalExercise?.sets.orEmpty()
                        .mapNotNull { s -> s.id?.let { id -> id to s } }
                        .toMap()
                    val editedSetIds = editedExercise.sets.mapNotNull { it.id }.toSet()

                    // Sets removed from this exercise.
                    for (removedSetId in originalSetsById.keys - editedSetIds) {
                        NetworkClient.workoutApi.deleteSet(removedSetId)
                    }

                    for (set in editedExercise.sets) {
                        val reps = set.reps.toIntOrNull() ?: continue
                        val weight = set.kg.toDoubleOrNull()

                        if (set.id == null) {
                            // A newly added row - either on a brand-new exercise,
                            // or an extra set added to one that already existed.
                            NetworkClient.workoutApi.logSet(
                                workoutId = workout.id,
                                request = NewSetRequest(
                                    exerciseId = exerciseId,
                                    setNumber = set.setNumber,
                                    reps = reps,
                                    weightKg = weight
                                )
                            )
                        } else {
                            val originalSet = originalSetsById[set.id]
                            if (originalSet != null && (originalSet.reps != set.reps || originalSet.kg != set.kg)) {
                                NetworkClient.workoutApi.updateSet(
                                    setId = set.id,
                                    request = UpdateSetRequest(reps = reps, weightKg = weight)
                                )
                            }
                        }
                    }
                }

                if (nameChanged) {
                    displayedTitle = editedWorkoutName.trim()
                }
                isEditing = false
                // Force a real re-fetch (rather than the cached copy) since
                // this workout's sets/exercises just changed on the server.
                reload(force = true)
            } catch (e: Exception) {
                saveError = "Couldn't save your changes. Check your connection and try again."
            } finally {
                isSaving = false
            }
        }
    }

    SaveAsTemplateDialog(
        visible = showSaveAsTemplateDialog,
        initialName = displayedTitle,
        initialNotes = detail?.notes.orEmpty(),
        isSaving = isSavingTemplate,
        errorMessage = saveTemplateError,
        onDismiss = { showSaveAsTemplateDialog = false },
        onSave = { name, notes ->
            isSavingTemplate = true
            saveTemplateError = null
            coroutineScope.launch {
                try {
                    NetworkClient.workoutApi.saveWorkoutAsTemplate(
                        workoutId = workout.id,
                        request = SaveWorkoutAsTemplateRequest(
                            name = name.ifBlank { null },
                            notes = notes.ifBlank { null }
                        )
                    )
                    showSaveAsTemplateDialog = false
                    templateSavedToastVisible = true
                } catch (e: Exception) {
                    saveTemplateError = "Couldn't save this as a template. It may already exist, or check your connection."
                } finally {
                    isSavingTemplate = false
                }
            }
        }
    )

    AddExerciseDialog(
        visible = showAddExerciseDialog,
        exercises = exercises,
        isLoading = false,
        errorMessage = null,
        onDismiss = { showAddExerciseDialog = false },
        onSelect = { selected ->
            editableExercises = editableExercises + selected.toNewEditableExercise()
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
        // Muscle groups pulled straight from the already-loaded exercise list,
        // so the options here always match what's actually in the dataset.
        muscleGroups = exercises.mapNotNull { it.muscleGroup }.distinct().sorted(),
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
                    exercises = exercises + created
                    AppDataCache.addExercise(created)
                    editableExercises = editableExercises + created.toNewEditableExercise()
                    showCustomExerciseDialog = false
                } catch (e: Exception) {
                    customExerciseError = "Couldn't add that exercise. Check your connection, or it may already exist."
                } finally {
                    isCreatingCustomExercise = false
                }
            }
        }
    )

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.clickable(enabled = !isEditing, onClick = onBack),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.ChevronLeft,
                    contentDescription = "Back",
                    tint = if (isEditing) AppTextMuted else AppAccent
                )
                Text(
                    text = "History",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isEditing) AppTextMuted else AppAccent
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(AppSurfaceVariant, RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "ARCHIVE #${archiveNumber ?: "-"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextMuted
                    )
                }
                if (isEditing) {
                    TextButton(onClick = { cancelEditing() }, enabled = !isSaving) {
                        Text(text = "Cancel", color = AppTextSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(AppAccent, RoundedCornerShape(50))
                            .clickable(enabled = !isSaving) { saveEdits() }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                color = AppOnAccent,
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = "Save",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = AppOnAccent
                            )
                        }
                    }
                } else {
                    IconButton(onClick = { showSaveAsTemplateDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.BookmarkAdd,
                            contentDescription = "Save as template",
                            tint = AppTextSecondary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(AppAccent, RoundedCornerShape(50))
                            .clickable { startEditing() }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Edit",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = AppOnAccent
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp)
        ) {
            item {
                if (isEditing) {
                    OutlinedTextField(
                        value = editedWorkoutName,
                        onValueChange = { editedWorkoutName = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AppTextPrimary,
                            unfocusedTextColor = AppTextPrimary,
                            focusedBorderColor = AppAccent,
                            unfocusedBorderColor = AppBorder,
                            cursorColor = AppAccent
                        )
                    )
                } else {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = displayedTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = AppTextPrimary,
                            modifier = Modifier.fillMaxWidth(0.72f)
                        )
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .background(AppSurfaceVariant, RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Schedule,
                                contentDescription = null,
                                tint = AppAccent,
                                modifier = Modifier.height(14.dp).width(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = workout.duration,
                                style = MaterialTheme.typography.labelSmall,
                                color = AppTextPrimary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = workout.dateLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppAccent
                )

                if (workout.tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        workout.tags.forEach { tag -> TagChip(text = tag) }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatCard(label = "VOLUME", value = workout.volumeLabel, modifier = Modifier.weight(1f))
                    StatCard(
                        label = "TOTAL SETS",
                        value = (if (isEditing) editableExercises.sumOf { it.sets.size } else (detail?.sets?.size ?: workout.totalSets)).toString(),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        label = "EXERCISES",
                        value = (if (isEditing) editableExercises.size else groupedSets.size).toString(),
                        modifier = Modifier.weight(1f)
                    )
                }

                if (isEditing) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppSurface, RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "SESSION NOTES",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTextMuted
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = editedNotes,
                            onValueChange = { editedNotes = it },
                            placeholder = { Text(text = "Add notes...", color = AppTextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = AppTextPrimary,
                                unfocusedTextColor = AppTextPrimary,
                                focusedBorderColor = AppAccent,
                                unfocusedBorderColor = AppBorder,
                                cursorColor = AppAccent
                            )
                        )
                    }
                } else {
                    val notes = detail?.notes
                    if (!notes.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AppSurface, RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "SESSION NOTES",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppTextMuted
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = notes,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTextPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "EXERCISES (${if (isEditing) editableExercises.size else groupedSets.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (saveError != null) {
                    Text(
                        text = saveError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            if (isEditing) {
                items(editableExercises, key = { it.exerciseId }) { exercise ->
                    EditableExerciseCard(
                        exercise = exercise,
                        onRemoveExercise = {
                            editableExercises = editableExercises.filterNot { it.exerciseId == exercise.exerciseId }
                        },
                        onAddSet = {
                            val nextNumber = (exercise.sets.maxOfOrNull { it.setNumber } ?: 0) + 1
                            val defaultKg = if (exercise.isBodyweight) "0" else ""
                            val updated = exercise.copy(
                                sets = exercise.sets + EditableSet(id = null, setNumber = nextNumber, kg = defaultKg, reps = "")
                            )
                            editableExercises = editableExercises.map { if (it.exerciseId == exercise.exerciseId) updated else it }
                        },
                        onRemoveSet = { setToRemove ->
                            val updated = exercise.copy(sets = exercise.sets.filterNot { it === setToRemove })
                            editableExercises = editableExercises.map { if (it.exerciseId == exercise.exerciseId) updated else it }
                        },
                        onSetChange = { setToChange, newKg, newReps ->
                            val updated = exercise.copy(
                                sets = exercise.sets.map { s ->
                                    if (s === setToChange) s.copy(kg = newKg, reps = newReps) else s
                                }
                            )
                            editableExercises = editableExercises.map { if (it.exerciseId == exercise.exerciseId) updated else it }
                        }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }
                item {
                    AddExerciseButton(onClick = { showAddExerciseDialog = true })
                }
            } else {
                when {
                    isLoading -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) { CircularProgressIndicator(color = AppAccent) }
                        }
                    }
                    errorMessage != null -> {
                        item {
                            Text(
                                text = errorMessage.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTextSecondary
                            )
                        }
                    }
                    groupedSets.isEmpty() -> {
                        item {
                            Text(
                                text = "No sets were logged for this workout.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTextSecondary
                            )
                        }
                    }
                    else -> {
                        items(groupedSets, key = { (exercise, sets) -> exercise?.id ?: sets.first().id }) { (exercise, sets) ->
                            ExerciseDetailCard(
                                exercise = exercise,
                                sets = sets,
                                onClick = {
                                    val exerciseId = exercise?.id ?: sets.first().exerciseId
                                    val exerciseName = exercise?.name ?: "Exercise #$exerciseId"
                                    onOpenExerciseHistory(exerciseId, exerciseName)
                                }
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                    }
                }
            }
        }
    }

    AnimatedVisibility(
        visible = templateSavedToastVisible,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 20.dp, bottom = 32.dp),
        enter = slideInHorizontally(initialOffsetX = { fullWidth -> fullWidth }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { fullWidth -> -fullWidth }) + fadeOut()
    ) {
        Row(
            modifier = Modifier
                .shadow(elevation = 10.dp, shape = RoundedCornerShape(14.dp))
                .background(AppSurface, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Filled.Check, contentDescription = null, tint = AppAccent, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = "Saved to My Workouts", style = MaterialTheme.typography.bodyMedium, color = AppTextPrimary)
        }
    }
    }
}

/** The form for saving an already-logged workout as a reusable template -
 *  name and notes default to the workout's own, so a single tap ("Save") is
 *  enough for the common case. */
@Composable
private fun SaveAsTemplateDialog(
    visible: Boolean,
    initialName: String,
    initialNotes: String,
    isSaving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, notes: String) -> Unit
) {
    var name by remember(initialName, visible) { mutableStateOf(initialName) }
    var notes by remember(initialNotes, visible) { mutableStateOf(initialNotes) }

    AppBottomSheet(visible = visible, onDismissRequest = onDismiss) {
        Text(
            text = "Save as Template",
            style = MaterialTheme.typography.titleLarge,
            color = AppTextPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "This workout's exercises will become a reusable template under My Workouts. Category and estimated time are figured out automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = AppTextMuted
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text(text = "Name", color = AppTextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = AppTextPrimary,
                unfocusedTextColor = AppTextPrimary,
                focusedBorderColor = AppAccent,
                unfocusedBorderColor = AppBorder,
                cursorColor = AppAccent
            )
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text(text = "Notes (optional)", color = AppTextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = AppTextPrimary,
                unfocusedTextColor = AppTextPrimary,
                focusedBorderColor = AppAccent,
                unfocusedBorderColor = AppBorder,
                cursorColor = AppAccent
            )
        )
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = errorMessage, color = AppTextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { if (name.isNotBlank() && !isSaving) onSave(name.trim(), notes.trim()) },
            enabled = name.isNotBlank() && !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black
            )
        ) {
            if (isSaving) {
                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(text = "Save Template", fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Cancel", color = AppTextSecondary)
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

/** One set row while editing a past workout - id is null for a row that
 *  doesn't exist on the backend yet (added this edit session); otherwise
 *  it's the existing SetEntries.id, which is how saving decides whether a
 *  row means create, update, or (if it's gone by save time) delete. */
private data class EditableSet(
    val id: Int?,
    val setNumber: Int,
    val kg: String,
    val reps: String
)

/** One exercise card's worth of state while editing a past workout. */
private data class EditableExercise(
    val exerciseId: Int,
    val name: String,
    val muscleGroup: String,
    val isBodyweight: Boolean,
    val sets: List<EditableSet>
)

/** A brand-new exercise added to a past workout during this edit session -
 *  starts with a single blank set, same convention as adding a fresh
 *  exercise during an active workout. */
private fun ExerciseResponse.toNewEditableExercise(): EditableExercise {
    val isBodyweight = equipment.equals("Body Only", ignoreCase = true)
    return EditableExercise(
        exerciseId = id,
        name = name,
        muscleGroup = muscleGroup?.uppercase() ?: "GENERAL",
        isBodyweight = isBodyweight,
        sets = listOf(EditableSet(id = null, setNumber = 1, kg = if (isBodyweight) "0" else "", reps = ""))
    )
}

/** Drops a trailing ".0" for a clean input box value (e.g. 100.0 -> "100"),
 *  but keeps real decimals (42.5 -> "42.5"). */
private fun formatKgForInput(weightKg: Double): String =
    if (weightKg == weightKg.toLong().toDouble()) weightKg.toLong().toString() else weightKg.toString()

/** One of the three stat tiles (volume / total sets / exercises) shown side by side. */
@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(AppSurface, RoundedCornerShape(16.dp))
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = AppTextMuted, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = AppTextPrimary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ExerciseDetailCard(
    exercise: ExerciseResponse?,
    sets: List<SetEntryResponse>,
    onClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AppSurface)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Text(
            text = exercise?.name ?: "Exercise #${sets.firstOrNull()?.exerciseId ?: "?"}",
            style = MaterialTheme.typography.titleMedium,
            color = AppTextPrimary
        )
        val muscleGroup = exercise?.muscleGroup
        if (!muscleGroup.isNullOrBlank()) {
            Text(
                text = muscleGroup.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("SET", style = MaterialTheme.typography.labelSmall, color = AppTextMuted, modifier = Modifier.weight(1f))
            Text("LOAD", style = MaterialTheme.typography.labelSmall, color = AppTextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("REPS", style = MaterialTheme.typography.labelSmall, color = AppTextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
        Spacer(modifier = Modifier.height(8.dp))

        sets.sortedBy { it.setNumber }.forEach { set ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = set.setNumber.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatLoad(set.weightKg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = set.reps.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** The editable counterpart of [ExerciseDetailCard] - each set's LOAD/REPS
 *  become input boxes, each row gets a remove button, and the card itself
 *  can be removed (taking every one of its sets with it) or grown with an
 *  extra set via the row at the bottom. */
@Composable
private fun EditableExerciseCard(
    exercise: EditableExercise,
    onRemoveExercise: () -> Unit,
    onAddSet: () -> Unit,
    onRemoveSet: (EditableSet) -> Unit,
    onSetChange: (set: EditableSet, newKg: String, newReps: String) -> Unit
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
                if (exercise.muscleGroup.isNotBlank()) {
                    Text(
                        text = exercise.muscleGroup,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextMuted
                    )
                }
            }
            IconButton(onClick = onRemoveExercise) {
                Icon(imageVector = Icons.Filled.Delete, contentDescription = "Remove exercise", tint = AppTextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("SET", style = MaterialTheme.typography.labelSmall, color = AppTextMuted, modifier = Modifier.weight(0.6f))
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
        Spacer(modifier = Modifier.height(8.dp))

        exercise.sets.forEach { set ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = set.setNumber.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary,
                    modifier = Modifier.weight(0.6f)
                )
                EditableCellField(
                    value = set.kg,
                    onValueChange = { onSetChange(set, it, set.reps) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                )
                EditableCellField(
                    value = set.reps,
                    onValueChange = { onSetChange(set, set.kg, it) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                )
                IconButton(onClick = { onRemoveSet(set) }, modifier = Modifier.size(32.dp)) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "Remove set", tint = AppTextMuted)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAddSet)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null, tint = AppAccent, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "Add Set", color = AppAccent, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun EditableCellField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = AppTextPrimary, textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
            .background(AppSurfaceVariant, RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                innerTextField()
            }
        }
    )
}

/**
 * Drops a trailing ".0" (e.g. 100.0 -> "100 KG") but keeps real decimals
 * (e.g. 42.5 -> "42.5 KG"). A null weight (nothing logged - e.g. a bodyweight
 * set with no added weight) shows as a blank dash rather than "0 KG".
 */
private fun formatLoad(weightKg: Double?): String {
    if (weightKg == null) return "-"
    val number = if (weightKg == weightKg.toLong().toDouble()) {
        weightKg.toLong().toString()
    } else {
        weightKg.toString()
    }
    return "$number KG"
}
