package com.example.workoutlog_androidstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * "Log a past workout" - for a day you trained but couldn't log (no
 * signal, server down, forgot). Pick the day and roughly when, then the
 * normal workout screen opens in "past" mode: no timer, a banner saying
 * which day it's for, and Save puts it on that date (history, heatmap,
 * streak, Up next, PRs).
 */

/** How far back a past workout can go. */
private const val MAX_DAYS_BACK = 365L

/** Workout lengths to pick from, in minutes. */
private val LENGTHS = listOf(30, 45, 60, 75, 90, 120)

private val DAY_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val TIME_12: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/** "Wed 22 Oct" */
fun formatPastDate(date: LocalDate): String = date.format(SHORT_DATE)

/** "6:30 pm" */
fun pastTimeLabel(time: LocalTime): String = time.format(TIME_12).lowercase(Locale.ENGLISH)

/** "1h 00m" / "45m" */
fun pastLengthLabel(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}m" else "${minutes}m"

/**
 * The sheet that starts a past workout. [initialDate] is pre-picked (from
 * the calendar); otherwise yesterday. [onStart] gets the start time
 * (epoch millis), the length in minutes and the split day picked (null =
 * empty workout).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogPastWorkoutSheet(
    visible: Boolean,
    initialDate: LocalDate?,
    onDismiss: () -> Unit,
    onStart: (startMillis: Long, minutes: Int, day: String?) -> Unit
) {
    if (!visible) return
    val today = remember { LocalDate.now() }
    val plan = TrainingPlanStore.plan
    // Fresh picks every time the sheet opens.
    var date by remember(initialDate) { mutableStateOf(initialDate?.takeIf { it.isBefore(today) } ?: today.minusDays(1)) }
    var time by remember { mutableStateOf(LocalTime.of(plan?.hour ?: 18, plan?.minute ?: 30)) }
    var minutes by remember { mutableIntStateOf(60) }
    var day by remember { mutableStateOf<String?>(null) }
    var showCalendar by remember { mutableStateOf(false) }

    // Days that already have a workout are dimmed (you can still add another).
    val loggedDates = remember(AppDataCache.workoutSummaries) {
        AppDataCache.workoutSummaries.orEmpty().map { it.date }.toSet()
    }
    val lastWeek = remember(today) { (7L downTo 1L).map { today.minusDays(it) } }
    val splitDays = remember(plan) { plan?.orderedDays().orEmpty().distinct() }

    AppBottomSheet(visible = true, onDismissRequest = onDismiss) {
        Text(text = "Log a past workout", style = MaterialTheme.typography.titleLarge, color = AppTextPrimary)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = "When did you train?", style = MaterialTheme.typography.bodyMedium, color = AppTextSecondary)

        PastLabel("DAY")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            lastWeek.forEach { d ->
                val selected = d == date
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) AppAccent.copy(alpha = 0.14f) else AppSurfaceVariant)
                        .border(1.5.dp, if (selected) AppAccent else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { date = d }
                        .alpha(if (d.toString() in loggedDates && !selected) 0.45f else 1f)
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = d.format(DAY_NAME).uppercase(Locale.ENGLISH),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) AppAccent else AppTextSecondary
                    )
                    Text(
                        text = d.dayOfMonth.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) AppAccent else AppTextPrimary
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = if (date in lastWeek) "Earlier date…" else "${formatPastDate(date)} · change",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = AppAccent,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { showCalendar = true }
                .padding(vertical = 4.dp)
        )

        PastLabel("STARTED AROUND")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper(
                value = pastTimeLabel(time),
                caption = "start",
                onMinus = { time = time.minusMinutes(15) },
                onPlus = { time = time.plusMinutes(15) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LENGTHS.forEach { m -> SelectableChip(text = pastLengthLabel(m), selected = m == minutes, onClick = { minutes = m }) }
        }

        PastLabel("WHAT DID YOU DO")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            splitDays.forEach { d -> SelectableChip(text = d, selected = day == d, onClick = { day = if (day == d) null else d }) }
            SelectableChip(text = "Empty workout", selected = day == null, onClick = { day = null })
        }

        Spacer(modifier = Modifier.height(22.dp))
        val startMillis = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val inFuture = startMillis + minutes * 60_000L > System.currentTimeMillis()
        Button(
            onClick = { onStart(startMillis, minutes, day) },
            enabled = !inFuture,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) {
            Text(text = "Log sets for ${formatPastDate(date)}", fontWeight = FontWeight.SemiBold)
        }
        if (inFuture) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "That time hasn't happened yet - pick an earlier start.",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Cancel", color = AppTextSecondary)
        }
    }

    if (showCalendar) {
        val earliest = today.minusDays(MAX_DAYS_BACK)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return d.isBefore(today) && !d.isBefore(earliest)
                }
                override fun isSelectableYear(year: Int): Boolean = year in earliest.year..today.year
            }
        )
        val colors = DatePickerDefaults.colors(
            containerColor = AppSurface,
            titleContentColor = AppTextSecondary,
            headlineContentColor = AppTextPrimary,
            weekdayContentColor = AppTextMuted,
            subheadContentColor = AppTextSecondary,
            navigationContentColor = AppTextPrimary,
            yearContentColor = AppTextPrimary,
            currentYearContentColor = AppAccent,
            selectedYearContentColor = AppOnAccent,
            selectedYearContainerColor = AppAccent,
            dayContentColor = AppTextPrimary,
            disabledDayContentColor = AppTextMuted.copy(alpha = 0.4f),
            selectedDayContentColor = AppOnAccent,
            selectedDayContainerColor = AppAccent,
            todayContentColor = AppAccent,
            todayDateBorderColor = AppAccent,
            dividerColor = AppBorder
        )
        DatePickerDialog(
            onDismissRequest = { showCalendar = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showCalendar = false
                }) { Text(text = "Done", color = AppAccent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showCalendar = false }) { Text(text = "Cancel", color = AppTextSecondary) }
            },
            colors = colors
        ) {
            DatePicker(state = state, colors = colors, showModeToggle = false)
        }
    }
}

@Composable
private fun PastLabel(text: String) {
    Spacer(modifier = Modifier.height(18.dp))
    Text(text = text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AppTextMuted)
    Spacer(modifier = Modifier.height(8.dp))
}

/** "−  6:30 pm  +" in a rounded field. */
@Composable
private fun Stepper(value: String, caption: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AppSurfaceVariant)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepButton(text = "−", onClick = onMinus)
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = AppTextPrimary)
            Text(text = caption, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
        }
        StepButton(text = "+", onClick = onPlus)
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(vertical = 6.dp)
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = MaterialTheme.typography.titleLarge, color = AppAccent)
    }
}

/** The check before a past workout is saved: day, time, exercises, sets. */
@Composable
fun SavePastWorkoutPopup(
    dateLabel: String,
    timeLabel: String,
    exerciseCount: Int,
    setCount: Int,
    onSave: () -> Unit,
    onEdit: () -> Unit
) {
    androidx.compose.ui.window.Popup(
        alignment = Alignment.Center,
        onDismissRequest = onEdit,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true, dismissOnClickOutside = true)
    ) {
        Column(
            modifier = Modifier
                .width(310.dp)
                .background(AppSurface, RoundedCornerShape(20.dp))
                .border(1.dp, AppBorder, RoundedCornerShape(20.dp))
                .padding(22.dp)
        ) {
            Text(text = "Save to $dateLabel?", style = MaterialTheme.typography.titleLarge, color = AppTextPrimary)
            Spacer(modifier = Modifier.height(14.dp))
            listOf("Day" to dateLabel, "Time" to timeLabel, "Exercises" to "$exerciseCount", "Sets" to "$setCount").forEach { (k, v) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(text = k, style = MaterialTheme.typography.bodyMedium, color = AppTextSecondary, modifier = Modifier.weight(1f))
                    Text(text = v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = AppTextPrimary)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "It goes on that day in your history, heatmap and streak.",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextMuted
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Text(text = "Edit sets", color = AppTextSecondary)
                }
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                ) {
                    Text(text = "Save", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
