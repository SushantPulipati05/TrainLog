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

@Composable
fun AddExerciseDialog(
    visible: Boolean,
    exercises: List<ExerciseResponse>,
    isLoading: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSelect: (ExerciseResponse) -> Unit,
    onRequestCustomExercise: (searchQuery: String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) {
        exercises
    } else {
        exercises.filter { it.name.contains(query, ignoreCase = true) }
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
            filtered.isEmpty() -> {
                Column {
                    Text(
                        text = "No exercises found.",
                        color = AppTextMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onRequestCustomExercise(query.trim()) }
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
            }
            else -> {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(filtered) { exercise ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(exercise) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = exercise.name,
                                    color = AppTextPrimary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (exercise.muscleGroup != null) {
                                    Text(
                                        text = exercise.muscleGroup,
                                        color = AppTextMuted,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
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
    onSubmit: (name: String, muscleGroup: String?, isBodyweight: Boolean) -> Unit
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
