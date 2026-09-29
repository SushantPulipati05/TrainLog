package com.example.workoutlog_androidstudio

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

fun formatDecimal(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

fun lastLoggedLabel(daysAgo: Int?, prefix: String = "Logged"): String = when (daysAgo) {
    null -> "Never logged"
    0 -> "$prefix Today"
    1 -> "$prefix Yesterday"
    else -> "$prefix ${daysAgo}d ago"
}

fun weeksOfMonth(monthAnchor: LocalDate): List<List<LocalDate>> {
    val firstOfMonth = monthAnchor.withDayOfMonth(1)
    val lastOfMonth = firstOfMonth.plusMonths(1).minusDays(1)
    val gridStart = firstOfMonth.minusDays((firstOfMonth.dayOfWeek.value - 1).toLong())
    val gridEnd = lastOfMonth.plusDays((7 - lastOfMonth.dayOfWeek.value).toLong())
    return generateSequence(gridStart) { it.plusDays(1) }
        .takeWhile { !it.isAfter(gridEnd) }
        .toList()
        .chunked(7)
}

fun heatmapIntensity(exerciseCount: Int): Float = when {
    exerciseCount >= 5 -> 1f
    exerciseCount == 4 -> 0.8f
    exerciseCount == 3 -> 0.6f
    exerciseCount == 2 -> 0.4f
    exerciseCount == 1 -> 0.2f
    else -> 0f
}

fun heatmapCellColor(inCurrentMonth: Boolean, exerciseCount: Int): Color {
    if (!inCurrentMonth) return AppSurfaceVariant.copy(alpha = 0.35f)
    val intensity = heatmapIntensity(exerciseCount)
    return if (intensity == 0f) AppSurfaceVariant else AppAccent.copy(alpha = intensity)
}

/** e.g. "7 exercises on 29th September" for a heatmap cell's tooltip. */
fun heatmapTooltipLabel(date: LocalDate, exerciseCount: Int): String {
    val day = date.dayOfMonth
    val suffix = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    val month = date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val exerciseWord = if (exerciseCount == 1) "exercise" else "exercises"
    return "$exerciseCount $exerciseWord on $day$suffix $month"
}
