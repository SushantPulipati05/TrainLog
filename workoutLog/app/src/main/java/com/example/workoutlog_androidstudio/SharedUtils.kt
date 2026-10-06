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

/** Number of exercises in a day at which a heatmap cell turns full colour. */
const val HEATMAP_FULL_AT_EXERCISES = 7

/**
 * Heatmap opacity for a day: 1 exercise = 0.2, each extra exercise adds 0.1
 * (6 exercises = 0.7), and 7 or more exercises shows the full colour.
 */
fun heatmapIntensity(exerciseCount: Int): Float = when {
    exerciseCount >= HEATMAP_FULL_AT_EXERCISES -> 1f
    exerciseCount >= 1 -> 0.1f + 0.1f * exerciseCount
    else -> 0f
}

fun heatmapCellColor(inCurrentMonth: Boolean, exerciseCount: Int): Color {
    if (!inCurrentMonth) return AppSurfaceVariant.copy(alpha = 0.35f)
    val intensity = heatmapIntensity(exerciseCount)
    return if (intensity == 0f) AppSurfaceVariant else AppAccent.copy(alpha = intensity)
}

/** e.g. "01:24:03" - the active-workout timer, shown both on the full
 *  screen and on the floating mini-player that replaces it while
 *  minimized, so both always read exactly the same elapsed time. */
fun formatElapsed(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

/** Short mm:ss form of an elapsed duration (h:mm:ss past an hour) - used
 *  beside each logged set, where the main timer's full hh:mm:ss would be
 *  too wide for the space. */
fun formatElapsedShort(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
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
