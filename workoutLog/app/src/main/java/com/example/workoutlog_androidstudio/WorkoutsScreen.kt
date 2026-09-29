package com.example.workoutlog_androidstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.workoutlog_androidstudio.api.ExerciseResponse
import com.example.workoutlog_androidstudio.api.NetworkClient
import com.example.workoutlog_androidstudio.api.NewWorkoutTemplateRequest
import com.example.workoutlog_androidstudio.api.WorkoutTemplateSummaryResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ALL_CATEGORIES = listOf("Strength Training", "Calisthenics", "Cardio", "Mobility/Flexibility")

@Composable
fun WorkoutsScreen(
    onOpenTemplate: (WorkoutTemplateSummaryResponse) -> Unit,
    modifier: Modifier = Modifier
) {

    var templates by remember { mutableStateOf(AppDataCache.templates ?: emptyList()) }
    var isLoading by remember { mutableStateOf(AppDataCache.templates == null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    var selectedCategory by remember { mutableStateOf<String?>(null) }

    var templatePendingDelete by remember { mutableStateOf<WorkoutTemplateSummaryResponse?>(null) }

    var deletedTemplateName by remember { mutableStateOf<String?>(null) }
    var toastVisible by remember { mutableStateOf(false) }
    LaunchedEffect(toastVisible) {
        if (toastVisible) {
            delay(2500)
            toastVisible = false
        }
    }

    val coroutineScope = rememberCoroutineScope()

    suspend fun reload(force: Boolean = false) {
        try {
            templates = AppDataCache.loadTemplates(force = force)
            errorMessage = null
        } catch (e: Exception) {
            errorMessage = "Couldn't load workouts. Check your connection."
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    val presentCategories = remember(templates) {
        ALL_CATEGORIES.filter { cat -> templates.any { it.category == cat } }
    }
    val filteredTemplates = remember(templates, selectedCategory) {
        if (selectedCategory == null) templates else templates.filter { it.category == selectedCategory }
    }
    val myWorkouts = remember(filteredTemplates) { filteredTemplates.filter { it.isCustom } }

    CreateTemplateDialog(
        visible = showCreateDialog,
        onDismiss = { showCreateDialog = false },
        onCreated = {
            showCreateDialog = false
            coroutineScope.launch { reload(force = true) }
        }
    )

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackground)
                .statusBarsPadding()
                .padding(horizontal = 20.dp),

            contentPadding = PaddingValues(top = 32.dp, bottom = 110.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Workouts Routine List",
                        style = MaterialTheme.typography.headlineSmall,
                        color = AppTextPrimary
                    )
                    Row(
                        modifier = Modifier
                            .background(AppAccent, RoundedCornerShape(50))
                            .clickable { showCreateDialog = true }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = null, tint = AppOnAccent, modifier = Modifier.size(18.dp))
                        //Spacer(modifier = Modifier.width(4.dp))
                        //Text(text = "New Routine", color = AppOnAccent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
//                Text(
//                    text = "${templates.size} ACTIVE ROUTINES",
//                    style = MaterialTheme.typography.labelSmall,
//                    color = AppTextMuted
//                )
                Spacer(modifier = Modifier.height(28.dp))

                CategoryFilterRow(
                    categories = presentCategories,
                    selectedCategory = selectedCategory,
                    onSelectCategory = { selectedCategory = it }
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            when {
                isLoading -> {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = AppAccent)
                        }
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
                else -> {
                    item {
                        Text(
                            text = "My Workouts",
                            style = MaterialTheme.typography.headlineSmall,
                            color = AppTextPrimary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    if (myWorkouts.isEmpty()) {
                        item {
                            Text(
                                text = "Nothing yet - tap New Routine to build one, or save a past workout as a template.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTextSecondary
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                    } else {
                        items(myWorkouts, key = { it.id }) { template ->
                            SwipeToDeleteTemplateCard(
                                template = template,
                                onClick = { onOpenTemplate(template) },
                                onRequestDelete = { templatePendingDelete = template }
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                    }
                }
            }
        }

        templatePendingDelete?.let { template ->

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { templatePendingDelete = null }
            )
            DeleteConfirmationPopup(
                title = "Delete this workout routine?",
                message = "\"${template.name}\" will be permanently deleted. This can't be undone.",
                onCancel = { templatePendingDelete = null },
                onConfirm = {
                    templatePendingDelete = null

                    templates = templates.filterNot { it.id == template.id }
                    AppDataCache.removeTemplate(template.id)
                    deletedTemplateName = template.name
                    toastVisible = true
                    coroutineScope.launch {
                        try {
                            NetworkClient.workoutApi.deleteWorkoutTemplate(template.id)
                        } catch (e: Exception) {
                            templates = (templates + template).sortedBy { it.id }
                            AppDataCache.replaceTemplates(templates)
                            toastVisible = false
                        }
                    }
                }
            )
        }

        DeletedToast(visible = toastVisible, message = "\"${deletedTemplateName.orEmpty()}\" deleted")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteTemplateCard(
    template: WorkoutTemplateSummaryResponse,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { targetValue ->
            if (targetValue == SwipeToDismissBoxValue.EndToStart) {
                onRequestDelete()
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
                    contentDescription = "Delete workout template",
                    tint = Color.White
                )
            }
        }
    ) {
        WorkoutTemplateCard(template = template, onClick = onClick)
    }
}

@Composable
private fun CategoryFilterRow(
    categories: List<String>,
    selectedCategory: String?,
    onSelectCategory: (String?) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(text = "All", selected = selectedCategory == null, onClick = { onSelectCategory(null) })
        }
        items(categories) { category ->
            FilterChip(
                text = category,
                selected = selectedCategory == category,
                onClick = { onSelectCategory(category) }
            )
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) AppAccent else AppSurface, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) AppOnAccent else AppTextSecondary
        )
    }
}

@Composable
private fun WorkoutTemplateCard(
    template: WorkoutTemplateSummaryResponse,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(AppSurface, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CategoryBadge(text = template.category)
                CategoryBadge(text = template.level)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Timer,
                    contentDescription = null,
                    tint = AppTextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${template.estimatedMinutes} MIN",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = template.name,
            style = MaterialTheme.typography.titleLarge,
            color = AppTextPrimary
        )
        if (template.muscleGroups.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = template.muscleGroups.joinToString("   "),
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    tint = AppTextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = lastLoggedLabel(template.lastLoggedDaysAgo, prefix = "Last Logged"),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = AppTextSecondary
            )
        }
    }
}

@Composable
fun CategoryBadge(text: String) {
    Box(
        modifier = Modifier
            .background(AppSurfaceVariant, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AppAccent
        )
    }
}

@Composable
private fun CreateTemplateDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onCreated: () -> Unit
) {
    var name by remember(visible) { mutableStateOf("") }
    var notes by remember(visible) { mutableStateOf("") }
    var query by remember(visible) { mutableStateOf("") }
    var selectedExerciseIds by remember(visible) { mutableStateOf<Set<Int>>(emptySet()) }

    var availableExercises by remember { mutableStateOf(AppDataCache.exercises ?: emptyList()) }
    var isLoadingExercises by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(visible) {
        if (visible && availableExercises.isEmpty() && !isLoadingExercises) {
            isLoadingExercises = true
            try {
                availableExercises = AppDataCache.loadExercises()
            } catch (e: Exception) {
                errorMessage = "Couldn't load exercises. Check your connection."
            } finally {
                isLoadingExercises = false
            }
        }
    }

    val filtered = remember(query, availableExercises) {
        if (query.isBlank()) availableExercises
        else availableExercises.filter { it.name.contains(query, ignoreCase = true) }
    }

    AppBottomSheet(visible = visible, onDismissRequest = onDismiss) {
        Text(
            text = "New Workout Template",
            style = MaterialTheme.typography.titleLarge,
            color = AppTextPrimary
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text(text = "Name", color = AppTextMuted) },
            placeholder = { Text(text = "e.g. Arm Day", color = AppTextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = appTextFieldColors()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text(text = "Notes (optional)", color = AppTextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = appTextFieldColors()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Exercises${if (selectedExerciseIds.isNotEmpty()) " (${selectedExerciseIds.size} selected)" else ""}",
            style = MaterialTheme.typography.labelSmall,
            color = AppTextMuted
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(text = "Search exercises...", color = AppTextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = appTextFieldColors()
        )
        Spacer(modifier = Modifier.height(8.dp))
        when {
            isLoadingExercises -> {
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppAccent)
                }
            }
            filtered.isEmpty() -> {
                Text(text = "No exercises found.", color = AppTextMuted, style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(filtered, key = { it.id }) { exercise ->
                        val isSelected = selectedExerciseIds.contains(exercise.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedExerciseIds = if (isSelected) {
                                        selectedExerciseIds - exercise.id
                                    } else {
                                        selectedExerciseIds + exercise.id
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = exercise.name, color = AppTextPrimary, style = MaterialTheme.typography.bodyMedium)
                                if (exercise.muscleGroup != null) {
                                    Text(text = exercise.muscleGroup, color = AppTextMuted, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(if (isSelected) AppAccent else AppSurfaceVariant, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(imageVector = Icons.Filled.Check, contentDescription = null, tint = AppOnAccent, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = errorMessage.orEmpty(), color = AppTextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(20.dp))
        CreateTemplateSubmitButton(
            name = name,
            notes = notes,
            selectedExerciseIds = selectedExerciseIds,
            isSubmitting = isSubmitting,
            onSubmittingChange = { isSubmitting = it },
            onError = { errorMessage = it },
            onCreated = onCreated
        )
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

@Composable
private fun CreateTemplateSubmitButton(
    name: String,
    notes: String,
    selectedExerciseIds: Set<Int>,
    isSubmitting: Boolean,
    onSubmittingChange: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onCreated: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    Button(
        onClick = {
            if (isSubmitting) return@Button
            if (name.isBlank()) {
                onError("Give this template a name.")
                return@Button
            }
            if (selectedExerciseIds.isEmpty()) {
                onError("Pick at least one exercise.")
                return@Button
            }
            onSubmittingChange(true)
            onError(null)
            coroutineScope.launch {
                try {
                    NetworkClient.workoutApi.createWorkoutTemplate(
                        NewWorkoutTemplateRequest(
                            name = name.trim(),
                            notes = notes.trim().ifBlank { null },
                            exerciseIds = selectedExerciseIds.toList()
                        )
                    )
                    onCreated()
                } catch (e: Exception) {
                    onError("Couldn't create that template. It may already exist, or check your connection.")
                } finally {
                    onSubmittingChange(false)
                }
            }
        },
        enabled = !isSubmitting,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black
        )
    ) {
        if (isSubmitting) {
            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(text = "Create Template", fontWeight = FontWeight.SemiBold)
        }
    }
}
