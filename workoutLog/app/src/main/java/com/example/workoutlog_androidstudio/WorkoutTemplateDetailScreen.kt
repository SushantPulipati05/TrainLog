package com.example.workoutlog_androidstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.workoutlog_androidstudio.api.WorkoutTemplateDetailResponse
import com.example.workoutlog_androidstudio.api.WorkoutTemplateSummaryResponse

@Composable
fun WorkoutTemplateDetailScreen(
    templateSummary: WorkoutTemplateSummaryResponse,
    onBack: () -> Unit,
    onStartWorkout: (WorkoutTemplateDetailResponse) -> Unit,
    modifier: Modifier = Modifier
) {

    var detail by remember { mutableStateOf(AppDataCache.templateDetails[templateSummary.id]) }
    var isLoading by remember { mutableStateOf(AppDataCache.templateDetails[templateSummary.id] == null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isStarting by remember { mutableStateOf(false) }

    LaunchedEffect(templateSummary.id) {
        isLoading = detail == null
        errorMessage = null
        try {
            detail = AppDataCache.loadTemplateDetail(templateSummary.id)
        } catch (e: Exception) {
            errorMessage = "Couldn't load this workout. Check your connection."
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.Filled.ChevronLeft, contentDescription = "Back", tint = AppTextPrimary)
            }
        }

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppAccent)
                }
            }
            errorMessage != null || detail == null -> {
                Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = errorMessage ?: "Workout not found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary
                    )
                }
            }
            else -> {
                val template = detail!!
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
                ) {
                    item {
                        Text(
                            text = "WORKOUT DETAILS",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTextMuted
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = template.name,
                            style = MaterialTheme.typography.displaySmall,
                            color = AppTextPrimary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CategoryBadge(text = template.category)
                            CategoryBadge(text = template.level)
                        }
                        if (template.muscleGroups.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = template.muscleGroups.joinToString("   "),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppTextMuted
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        StatGrid(template = template)
                        Spacer(modifier = Modifier.height(20.dp))
                        StartWorkoutButton(
                            isStarting = isStarting,
                            onClick = {
                                if (isStarting) return@StartWorkoutButton
                                isStarting = true
                                onStartWorkout(template)
                            }
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Sequence Plan",
                                style = MaterialTheme.typography.headlineSmall,
                                color = AppTextPrimary
                            )
                            Text(
                                text = lastLoggedLabel(template.lastLoggedDaysAgo),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppTextMuted
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    itemsIndexed(template.exercises) { index, exercise ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AppSurface, RoundedCornerShape(16.dp))
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(AppSurfaceVariant, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = (index + 1).toString().padStart(2, '0'),
                                    color = AppTextPrimary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = exercise.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = AppTextPrimary
                                )
                                val subtitle = listOfNotNull(exercise.muscleGroup, exercise.equipment).joinToString(" • ")
                                if (subtitle.isNotBlank()) {
                                    Text(
                                        text = subtitle.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppTextMuted
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    if (!template.notes.isNullOrBlank()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            CoachNoteCard(text = template.notes)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatGrid(template: WorkoutTemplateDetailResponse) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                icon = Icons.Filled.Timer,
                label = "EST. DURATION",
                value = "${template.estimatedMinutes} MIN",
                modifier = Modifier.weight(1f)
            )
            StatTile(
                icon = Icons.Filled.FitnessCenter,
                label = "MOVEMENTS",
                value = "${template.exercises.size}",
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                icon = Icons.Filled.Speed,
                label = "LEVEL",
                value = template.level.uppercase(),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                icon = Icons.Filled.History,
                label = "TYPE",
                value = if (template.isCustom) "CUSTOM" else "BUILT-IN",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatTile(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(AppSurface, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = AppAccent, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = AppTextPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StartWorkoutButton(isStarting: Boolean, onClick: () -> Unit) {
    // Same guard as HomeScreen's Quick Start button - starting a second
    // workout while ActiveWorkoutState already has one running would orphan
    // whichever one loses the race.
    val workoutAlreadyActive = ActiveWorkoutState.workout != null
    Button(
        onClick = onClick,
        enabled = !isStarting && !workoutAlreadyActive,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
    ) {
        if (isStarting) {
            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp))
        } else {
            Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (workoutAlreadyActive) "Workout In Progress" else "Start Workout",
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun CoachNoteCard(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurface, RoundedCornerShape(14.dp))
            .border(1.dp, AppAccent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "COACH NOTE",
            style = MaterialTheme.typography.labelSmall,
            color = AppAccent,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary
        )
    }
}
