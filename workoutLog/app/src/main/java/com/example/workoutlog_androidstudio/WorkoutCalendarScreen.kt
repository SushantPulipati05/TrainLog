package com.example.workoutlog_androidstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.workoutlog_androidstudio.api.ExerciseResponse
import com.example.workoutlog_androidstudio.api.SetEntryResponse
import com.example.workoutlog_androidstudio.api.WorkoutDetailResponse
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun WorkoutCalendarScreen(
    onBack: () -> Unit,
    onOpenWorkout: (WorkoutSummary) -> Unit,
    modifier: Modifier = Modifier
) {

    var allWorkouts by remember { mutableStateOf(AppDataCache.workoutSummaries ?: emptyList()) }
    var isLoading by remember { mutableStateOf(AppDataCache.workoutSummaries == null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var exercises by remember { mutableStateOf(AppDataCache.exercises ?: emptyList()) }

    var workoutDetails by remember { mutableStateOf(AppDataCache.workoutDetails) }

    val today = remember { LocalDate.now() }
    var displayedMonth by remember { mutableStateOf(today.withDayOfMonth(1)) }
    var selectedDate by remember { mutableStateOf(today) }

    LaunchedEffect(Unit) {
        try {
            allWorkouts = AppDataCache.loadWorkoutSummaries()
            exercises = AppDataCache.loadExercises()
            errorMessage = null
        } catch (e: Exception) {
            errorMessage = "Couldn't load your workout history. Check your connection."
        } finally {
            isLoading = false
        }
    }

    val workoutsByDate = remember(allWorkouts) {
        allWorkouts.groupBy { it.date }
    }
    val workoutDatesInMonth = remember(allWorkouts) {
        allWorkouts.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.toSet()
    }

    val selectedDateIso = remember(selectedDate) { selectedDate.toString() }
    val sessionsForSelectedDay = remember(workoutsByDate, selectedDateIso) {
        workoutsByDate[selectedDateIso].orEmpty()
    }

    LaunchedEffect(selectedDateIso, allWorkouts) {
        val missing = sessionsForSelectedDay.map { it.id }.filterNot { workoutDetails.containsKey(it) }
        if (missing.isEmpty()) return@LaunchedEffect
        val fetched = mutableMapOf<Int, WorkoutDetailResponse>()
        for (id in missing) {
            try {
                fetched[id] = AppDataCache.loadWorkoutDetail(id)
            } catch (e: Exception) {

            }
        }
        workoutDetails = workoutDetails + fetched
    }

    val monthWorkouts = remember(allWorkouts, displayedMonth) {
        allWorkouts.filter { w ->
            val d = runCatching { LocalDate.parse(w.date) }.getOrNull() ?: return@filter false
            d.month == displayedMonth.month && d.year == displayedMonth.year
        }
    }
    val monthVolumeLabel = remember(monthWorkouts) {
        val total = monthWorkouts.sumOf { parseVolumeKg(it.volumeLabel) }
        String.format(Locale.US, "%,.0f", total) + " KG"
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
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackChevronRow(onBack = onBack)
            Text(
                text = "WORKOUT LOG & CALENDAR",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppTextPrimary
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            item {
                if (errorMessage != null) {
                    Text(text = errorMessage.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = AppTextSecondary)
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${displayedMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${displayedMonth.year}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = AppTextPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            imageVector = Icons.Filled.ChevronLeft,
                            contentDescription = "Previous month",
                            tint = AppTextSecondary,
                            modifier = Modifier
                                .clickable { displayedMonth = displayedMonth.minusMonths(1) }
                                .padding(6.dp)
                        )
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = "Next month",
                            tint = AppTextSecondary,
                            modifier = Modifier
                                .clickable { displayedMonth = displayedMonth.plusMonths(1) }
                                .padding(6.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "${monthWorkouts.size} SESSIONS",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextMuted
                    )
                    Text(text = monthVolumeLabel, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
                }
                Spacer(modifier = Modifier.height(20.dp))

                CalendarMonthGrid(
                    monthAnchor = displayedMonth,
                    workoutDates = workoutDatesInMonth,
                    selectedDate = selectedDate,
                    today = today,
                    onSelectDate = { selectedDate = it }
                )
                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = "${selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()).uppercase()}, " +
                        "${selectedDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()} ${selectedDate.dayOfMonth}",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppAccent
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (sessionsForSelectedDay.isEmpty()) "No Sessions Logged" else "${sessionsForSelectedDay.size} Session${if (sessionsForSelectedDay.size == 1) "" else "s"} Logged",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppTextPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AppAccent)
                    }
                }
            } else if (sessionsForSelectedDay.isEmpty()) {
                item {
                    Text(
                        text = "Nothing logged on this day.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary
                    )
                }
            } else {
                items(sessionsForSelectedDay, key = { it.id }) { session ->
                    DaySessionCard(
                        session = session,
                        detail = workoutDetails[session.id],
                        exercises = exercises,
                        onViewFullBreakdown = { onOpenWorkout(session) }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
    }
}

private fun parseVolumeKg(label: String): Double {
    val digits = label.filter { it.isDigit() || it == '.' }
    return digits.toDoubleOrNull() ?: 0.0
}

@Composable
private fun CalendarMonthGrid(
    monthAnchor: LocalDate,
    workoutDates: Set<LocalDate>,
    selectedDate: LocalDate,
    today: LocalDate,
    onSelectDate: (LocalDate) -> Unit
) {
    val weeks = remember(monthAnchor) { weeksOfMonth(monthAnchor) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { label ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(text = label, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    val inMonth = date.month == monthAnchor.month && date.year == monthAnchor.year
                    Box(modifier = Modifier.weight(1f).padding(2.dp)) {
                        CalendarDayCell(
                            date = date,
                            inCurrentMonth = inMonth,
                            isSelected = date == selectedDate,
                            isToday = date == today,
                            hasWorkout = workoutDates.contains(date),
                            onClick = { onSelectDate(date) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate,
    inCurrentMonth: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    hasWorkout: Boolean,
    onClick: () -> Unit
) {
    val textColor = when {
        isSelected -> AppOnAccent
        !inCurrentMonth -> AppTextMuted.copy(alpha = 0.4f)
        else -> AppTextPrimary
    }
    val dotColor = when {
        !hasWorkout -> Color.Transparent
        isSelected -> AppOnAccent
        else -> AppAccent
    }

    Column(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) AppAccent else Color.Transparent)
            .then(
                if (isToday && !isSelected) Modifier.border(1.dp, AppAccent, RoundedCornerShape(10.dp)) else Modifier
            )
            .clickable(enabled = inCurrentMonth, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = date.dayOfMonth.toString().padStart(2, '0'),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = textColor
        )
        Spacer(modifier = Modifier.height(3.dp))
        Box(modifier = Modifier.size(4.dp).background(dotColor, CircleShape))
    }
}

@Composable
private fun DaySessionCard(
    session: WorkoutSummary,
    detail: WorkoutDetailResponse?,
    exercises: List<ExerciseResponse>,
    onViewFullBreakdown: () -> Unit
) {
    val groupedSets: List<Pair<ExerciseResponse?, List<SetEntryResponse>>> = remember(detail, exercises) {
        detail?.sets
            ?.groupBy { it.exerciseId }
            ?.map { (exerciseId, sets) -> exercises.find { it.id == exerciseId } to sets }
            ?: emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurface, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.titleLarge,
                color = AppTextPrimary,
                modifier = Modifier.weight(1f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Filled.Timer, contentDescription = null, tint = AppTextMuted, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = session.duration, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
            }
        }
        if (session.tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                session.tags.forEach { tag -> TagChip(text = tag) }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        if (detail == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppAccent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            groupedSets.forEach { (exercise, sets) ->
                val bestSet = sets.maxWithOrNull(compareBy({ it.weightKg ?: 0.0 }, { it.reps }))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = exercise?.name ?: "Exercise #${sets.firstOrNull()?.exerciseId ?: "?"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = bestSet?.let { formatBestSetLabel(it, sets.size) } ?: "${sets.size} sets logged",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = AppAccent
                    )
                }
            }

            val notes = detail.notes
            if (!notes.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "\"$notes\"",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = AppTextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onViewFullBreakdown),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "View Full Breakdown",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppAccent
            )
            Icon(imageVector = Icons.Filled.ChevronRight, contentDescription = null, tint = AppAccent)
        }
    }
}

private fun formatBestSetLabel(bestSet: SetEntryResponse, setCount: Int): String {
    val weight = bestSet.weightKg
    return if (weight != null && weight > 0) {
        "${formatDecimal(weight)} KG x ${bestSet.reps}"
    } else {
        "$setCount sets logged"
    }
}
