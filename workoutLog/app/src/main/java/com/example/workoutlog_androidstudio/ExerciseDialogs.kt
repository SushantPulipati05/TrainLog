package com.example.workoutlog_androidstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.workoutlog_androidstudio.api.ExerciseResponse

/**
 * The "Add Exercise" sheet.
 *
 * Before anything is typed: the exercises from the last workout like this
 * one ([fromLastTitle], e.g. "your last Pull day") with an "Add all", then
 * the user's own exercises (most recently used first), then everything else.
 * While typing: [searchExercises] - any word order, common short forms
 * (db, bb, bicep/biceps), and the user's own exercises ranked first.
 */
@Composable
fun AddExerciseDialog(
    visible: Boolean,
    exercises: List<ExerciseResponse>,
    isLoading: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSelect: (ExerciseResponse) -> Unit,
    onRequestCustomExercise: (searchQuery: String) -> Unit,
    fromLastTitle: String? = null,
    fromLastExercises: List<ExerciseResponse> = emptyList(),
    onAddAll: (List<ExerciseResponse>) -> Unit = {},
    /** The split's days as chips ("What are you training?") - shown before
     *  anything is logged. Picking one shows that day's last exercises. */
    dayChips: List<String> = emptyList(),
    selectedDay: String? = null,
    upNextDay: String? = null,
    onSelectDay: (String) -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    // A fresh search each time the sheet opens.
    LaunchedEffect(visible) { if (visible) query = "" }

    val searching = query.isNotBlank()
    val results = remember(query, exercises) { if (searching) searchExercises(query, exercises) else emptyList() }
    val recent = remember(exercises) { recentExercises(exercises) }
    val rest = remember(exercises, recent) {
        val recentIds = recent.map { it.id }.toSet()
        exercises.filter { it.id !in recentIds }.sortedBy { it.name.lowercase() }
    }

    AppBottomSheet(visible = visible, onDismissRequest = onDismiss) {
        Text(
            text = "Add Exercise",
            style = MaterialTheme.typography.titleLarge,
            color = AppTextPrimary
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(text = "Search exercises...", color = AppTextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = appTextFieldColors()
        )
        Spacer(modifier = Modifier.height(12.dp))
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AppAccent)
                }
            }
            errorMessage != null -> {
                Text(text = errorMessage, color = AppTextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            searching && results.isEmpty() -> {
                Column {
                    Text(
                        text = "No exercises found.",
                        color = AppTextMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    AddCustomRow(query = query, onClick = { onRequestCustomExercise(query.trim()) })
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    if (!searching && dayChips.isNotEmpty()) {
                        item(key = "dayChips") {
                            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                SheetSectionLabel("REPEAT A RECENT DAY")
                                Spacer(modifier = Modifier.height(8.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    dayChips.forEach { day ->
                                        SelectableChip(
                                            text = if (day == upNextDay) "$day · up next" else day,
                                            selected = day == selectedDay,
                                            onClick = { onSelectDay(day) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (searching) {
                        items(results, key = { "r${it.id}" }) { exercise ->
                            ExercisePickRow(exercise = exercise, onClick = { onSelect(exercise) })
                        }
                        item(key = "custom") {
                            Spacer(modifier = Modifier.height(8.dp))
                            AddCustomRow(query = query, onClick = { onRequestCustomExercise(query.trim()) })
                        }
                    } else {
                        if (fromLastTitle != null && fromLastExercises.isNotEmpty()) {
                            item(key = "lastHeader") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SheetSectionLabel(
                                        text = "FROM ${fromLastTitle.uppercase()}",
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { onAddAll(fromLastExercises) }) {
                                        Text(
                                            text = "Add all ${fromLastExercises.size}",
                                            color = AppAccent,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    }
                                }
                            }
                            items(fromLastExercises, key = { "l${it.id}" }) { exercise ->
                                ExercisePickRow(exercise = exercise, onClick = { onSelect(exercise) })
                            }
                        }
                        if (recent.isNotEmpty()) {
                            item(key = "recentHeader") { SheetSectionLabel("YOUR EXERCISES", Modifier.padding(top = 12.dp, bottom = 2.dp)) }
                            items(recent, key = { "m${it.id}" }) { exercise ->
                                ExercisePickRow(exercise = exercise, onClick = { onSelect(exercise) })
                            }
                        }
                        item(key = "allHeader") {
                            SheetSectionLabel(
                                if (recent.isEmpty()) "ALL EXERCISES" else "EVERYTHING ELSE",
                                Modifier.padding(top = 12.dp, bottom = 2.dp)
                            )
                        }
                        items(rest, key = { "a${it.id}" }) { exercise ->
                            ExercisePickRow(exercise = exercise, onClick = { onSelect(exercise) })
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
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
private fun SheetSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = AppTextMuted, modifier = modifier)
}

/** One exercise in the sheet: name, muscle group, and how often it's been logged. */
@Composable
private fun ExercisePickRow(exercise: ExerciseResponse, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = exercise.name,
                color = AppTextPrimary,
                style = MaterialTheme.typography.bodyMedium
            )
            val muscle = exercise.muscleGroup
            if (muscle != null) {
                Text(
                    text = muscle,
                    color = AppTextMuted,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        if (exercise.timesLogged > 0) {
            Text(
                text = "${exercise.timesLogged}×",
                color = AppAccent,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun AddCustomRow(query: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .border(1.dp, AppBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icons.Filled.Add, contentDescription = null, tint = AppAccent)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (query.isBlank()) "Add a custom exercise" else "Add \"${query.trim()}\" as a custom exercise",
            color = AppAccent,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun SelectableChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) AppAccent else AppSurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) AppOnAccent else AppTextPrimary
        )
    }
}

@Composable
fun CustomExerciseDialog(
    visible: Boolean,
    initialName: String,
    muscleGroups: List<String>,
    isSubmitting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (name: String, muscleGroup: String?, isBodyweight: Boolean) -> Unit,
    /** Everything already in the list - checked for near-duplicates of the typed name. */
    existingExercises: List<ExerciseResponse> = emptyList(),
    /** "Did you mean ...?" -> use that existing exercise instead. */
    onSelectExisting: (ExerciseResponse) -> Unit = {}
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var selectedMuscleGroup by remember(initialName) { mutableStateOf<String?>(null) }
    var isBodyweight by remember(initialName) { mutableStateOf(false) }

    AppBottomSheet(visible = visible, onDismissRequest = onDismiss) {
        Text(
            text = "Custom Exercise",
            style = MaterialTheme.typography.titleLarge,
            color = AppTextPrimary
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            placeholder = { Text(text = "e.g. Cable Pull-Through", color = AppTextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = appTextFieldColors()
        )
        // Before making a new one, check it isn't already there under a
        // slightly different name - duplicates split an exercise's history.
        val similar = remember(name, existingExercises) { similarExercises(name, existingExercises) }
        if (similar.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(text = "DID YOU MEAN", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
            similar.forEach { exercise ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectExisting(exercise) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = exercise.name, color = AppTextPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = listOfNotNull(
                                exercise.muscleGroup,
                                exercise.timesLogged.takeIf { it > 0 }?.let { "you've logged it $it×" }
                            ).joinToString(" · "),
                            color = AppTextMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(text = "Use this", color = AppAccent, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(text = "Muscle Group", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
        Spacer(modifier = Modifier.height(8.dp))
        if (muscleGroups.isEmpty()) {
            Text(
                text = "No muscle groups to pick from yet.",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextMuted
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                muscleGroups.forEach { group ->
                    SelectableChip(
                        text = group,
                        selected = selectedMuscleGroup == group,
                        onClick = { selectedMuscleGroup = group }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(text = "Type", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableChip(text = "Equipment", selected = !isBodyweight, onClick = { isBodyweight = false })
            SelectableChip(text = "Bodyweight", selected = isBodyweight, onClick = { isBodyweight = true })
        }
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = errorMessage, color = AppTextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { onSubmit(name.trim(), selectedMuscleGroup, isBodyweight) },
            enabled = name.isNotBlank() && selectedMuscleGroup != null && !isSubmitting,
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
                Text(text = "Add Exercise", fontWeight = FontWeight.SemiBold)
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

@Composable
fun AddExerciseButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .border(1.dp, AppBorder, RoundedCornerShape(14.dp))
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null, tint = AppTextSecondary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Add Exercise", color = AppTextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
