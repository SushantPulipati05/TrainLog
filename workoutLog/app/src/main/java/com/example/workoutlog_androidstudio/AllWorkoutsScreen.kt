package com.example.workoutlog_androidstudio

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AllWorkoutsScreen(
    onBack: () -> Unit,
    onOpenWorkout: (WorkoutSummary) -> Unit,
    modifier: Modifier = Modifier
) {

    var workouts by remember { mutableStateOf(AppDataCache.workoutSummaries ?: emptyList()) }
    var isLoading by remember { mutableStateOf(AppDataCache.workoutSummaries == null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            workouts = AppDataCache.loadWorkoutSummaries()
            errorMessage = null
        } catch (e: Exception) {
            errorMessage = "Couldn't load your workouts. Check your connection."
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
                .padding(horizontal = 12.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackChevronRow(onBack = onBack)
            Text(
                text = "ALL WORKOUTS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppTextPrimary
            )
        }

        LoadingOrError(isLoading = isLoading, errorMessage = errorMessage) {
            if (workouts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text(text = "No workouts logged yet.", style = MaterialTheme.typography.bodyMedium, color = AppTextSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp)
                ) {
                    items(workouts, key = { it.id }) { workout ->
                        WorkoutRow(workout = workout, onClick = { onOpenWorkout(workout) })
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkoutRow(workout: WorkoutSummary, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = workout.title,
                style = MaterialTheme.typography.bodyLarge,
                color = AppTextPrimary
            )
            Text(
                text = workout.dateLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppBorder))
    }
}
