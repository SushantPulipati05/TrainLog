package com.example.workoutlog_androidstudio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.workoutlog_androidstudio.api.ExerciseHistoryPointResponse
import com.example.workoutlog_androidstudio.api.ExerciseHistoryResponse
import com.example.workoutlog_androidstudio.api.SetEntryResponse
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** The exercise analytics screen, opened by tapping an exercise card on a
 *  finished workout's detail screen. Shows a personal-records grid, a bar
 *  per session for the selected metric (heaviest set / total volume) with a
 *  timeframe selector, and every finished session that logged this exercise
 *  with its set-by-set breakdown - all backed by the single
 *  /exercises/{id}/history call (see AppDataCache.loadExerciseHistory), so
 *  this never re-fetches every workout individually. */
@Composable
fun ExerciseHistoryScreen(
    exerciseId: Int,
    exerciseName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var history by remember(exerciseId) { mutableStateOf(AppDataCache.exerciseHistories[exerciseId]) }
    var isLoading by remember(exerciseId) { mutableStateOf(AppDataCache.exerciseHistories[exerciseId] == null) }
    var errorMessage by remember(exerciseId) { mutableStateOf<String?>(null) }

    suspend fun reload(force: Boolean = false) {
        isLoading = history == null
        errorMessage = null
        try {
            history = AppDataCache.loadExerciseHistory(exerciseId, force = force)
        } catch (e: Exception) {
            errorMessage = "Couldn't load this exercise's history. Check your connection."
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(exerciseId) { reload() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackChevronRow(onBack = onBack, label = "EXERCISE ANALYTICS")
        }

        LoadingOrError(isLoading = isLoading, errorMessage = errorMessage) {
            val current = history
            if (current == null || current.history.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No history yet for $exerciseName - log a set to start tracking it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary
                    )
                }
            } else {
                ExerciseHistoryContent(exerciseName = exerciseName, history = current)
            }
        }
    }
}

/** How far back the chart/session list looks. */
private enum class HistoryTimeRange(val label: String) {
    ONE_WEEK("1W"),
    ONE_MONTH("1M"),
    THREE_MONTHS("3M"),
    SIX_MONTHS("6M"),
    ONE_YEAR("1Y"),
    ALL("ALL")
}

/** Which per-workout number the bar chart plots. Everything here comes
 *  straight from real logged data. */
private enum class HistoryMetric(val label: String) {
    HEAVIEST_SET("Heaviest set"),
    TOTAL_VOLUME("Volume")
}

private fun metricValue(point: ExerciseHistoryPointResponse, metric: HistoryMetric, isBodyweight: Boolean): Float =
    when (metric) {
        HistoryMetric.HEAVIEST_SET -> if (isBodyweight) point.maxReps.toFloat() else (point.maxWeightKg ?: 0.0).toFloat()
        HistoryMetric.TOTAL_VOLUME -> point.totalVolumeKg.toFloat()
    }

private fun oneDecimal(value: Double): String = formatDecimal((value * 10).roundToInt() / 10.0)

/** The best set of a session: heaviest weight, then most reps at that weight. */
private fun topSetOf(sets: List<SetEntryResponse>): SetEntryResponse? =
    sets.maxWithOrNull(compareBy<SetEntryResponse>({ it.weightKg ?: 0.0 }, { it.reps }))

/** One tile in the "Personal records" grid. */
private data class RecordTile(
    val label: String,
    val value: String,
    val unit: String,
    val detail: String,
    val highlight: Boolean = false
)

/** All-time records across every session (not filtered by the range picker). */
private fun personalRecords(history: ExerciseHistoryResponse): List<RecordTile> {
    val points = history.history
    val allSets = points.flatMap { point -> point.sets.map { point.date to it } }
    if (allSets.isEmpty()) return emptyList()

    val weighted = allSets.filter { (it.second.weightKg ?: 0.0) > 0.0 }

    val heaviest = weighted.maxWithOrNull(compareBy({ it.second.weightKg ?: 0.0 }, { it.second.reps }))?.let { (date, set) ->
        RecordTile(
            label = if (history.isBodyweight) "HEAVIEST ADDED" else "HEAVIEST",
            value = formatDecimal(set.weightKg ?: 0.0),
            unit = "kg × ${set.reps}",
            detail = formatHistoryDateShort(date)
        )
    }

    val mostReps = allSets.maxWithOrNull(compareBy({ it.second.reps }, { it.second.weightKg ?: 0.0 }))?.let { (date, set) ->
        val weight = set.weightKg
        val at = if (weight == null || weight <= 0.0) {
            if (history.isBodyweight) "bodyweight" else ""
        } else {
            "@ ${formatDecimal(weight)} kg"
        }
        RecordTile(label = "MOST REPS", value = set.reps.toString(), unit = at, detail = formatHistoryDateShort(date))
    }

    val bestVolume = points.maxByOrNull { it.totalVolumeKg }?.takeIf { it.totalVolumeKg > 0.0 }?.let { point ->
        RecordTile(
            label = "BEST VOLUME",
            value = oneDecimal(point.totalVolumeKg),
            unit = "kg",
            detail = formatHistoryDateShort(point.date)
        )
    }

    // Estimated one-rep max (Epley: weight × (1 + reps / 30)). Only from
    // sets of 15 reps or fewer - the formula gets unreliable past that.
    val oneRepMax = if (history.isBodyweight) {
        null
    } else {
        weighted
            .filter { it.second.reps in 1..15 }
            .maxByOrNull { (it.second.weightKg ?: 0.0) * (1 + it.second.reps / 30.0) }
            ?.let { (_, set) ->
                val weight = set.weightKg ?: 0.0
                RecordTile(
                    label = "EST. 1RM",
                    value = oneDecimal(weight * (1 + set.reps / 30.0)),
                    unit = "kg",
                    detail = "from ${formatDecimal(weight)} × ${set.reps}"
                )
            }
    }

    val ordered = if (history.isBodyweight) {
        listOfNotNull(mostReps, bestVolume, heaviest)
    } else {
        listOfNotNull(heaviest, mostReps, bestVolume, oneRepMax)
    }
    return ordered.mapIndexed { index, tile -> if (index == 0) tile.copy(highlight = true) else tile }
}

@Composable
private fun ExerciseHistoryContent(exerciseName: String, history: ExerciseHistoryResponse) {
    val points = history.history

    var selectedRange by remember { mutableStateOf(HistoryTimeRange.THREE_MONTHS) }
    var selectedMetric by remember { mutableStateOf(HistoryMetric.HEAVIEST_SET) }

    val records = remember(history) { personalRecords(history) }

    // Which sessions set a new best for the selected metric, worked out over
    // the WHOLE history (so a bar is only orange if it beat everything before
    // it, even sessions outside the selected range).
    val newBestFlags: List<Boolean> = remember(points, selectedMetric) {
        var best = Float.NEGATIVE_INFINITY
        points.map { point ->
            val value = metricValue(point, selectedMetric, history.isBodyweight)
            val isNewBest = value > best && value > 0f
            if (value > best) best = value
            isNewBest
        }
    }

    // (point, isNewBest) pairs inside the selected range, oldest first.
    val today = remember { LocalDate.now() }
    val cutoff: LocalDate? = when (selectedRange) {
        HistoryTimeRange.ONE_WEEK -> today.minusWeeks(1)
        HistoryTimeRange.ONE_MONTH -> today.minusMonths(1)
        HistoryTimeRange.THREE_MONTHS -> today.minusMonths(3)
        HistoryTimeRange.SIX_MONTHS -> today.minusMonths(6)
        HistoryTimeRange.ONE_YEAR -> today.minusYears(1)
        HistoryTimeRange.ALL -> null
    }
    val filtered: List<Pair<ExerciseHistoryPointResponse, Boolean>> = remember(points, newBestFlags, cutoff) {
        points.zip(newBestFlags).filter { (point, _) ->
            cutoff == null || runCatching { !LocalDate.parse(point.date).isBefore(cutoff) }.getOrDefault(true)
        }
    }

    // Sessions the user has opened; the most recent one starts open.
    val expanded = remember(exerciseName) { mutableStateMapOf<String, Boolean>() }

    val firstDate = points.firstOrNull()?.date

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        item {
            Text(
                text = exerciseName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildString {
                    history.muscleGroup?.takeIf { it.isNotBlank() }?.let { append(it.uppercase()).append(" · ") }
                    append("${points.size} ${if (points.size == 1) "SESSION" else "SESSIONS"}")
                    if (firstDate != null) append(" SINCE ${formatHistoryDateShort(firstDate).uppercase()}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted
            )

            if (records.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                SectionLabel("PERSONAL RECORDS")
                Spacer(modifier = Modifier.height(8.dp))
                RecordsGrid(records)
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Change from the first to the last session in view, e.g. "+50%".
            val values = filtered.map { metricValue(it.first, selectedMetric, history.isBodyweight) }
            val change = if (values.size >= 2 && values.first() > 0f) {
                ((values.last() - values.first()) / values.first()) * 100f
            } else {
                null
            }
            // The window the range picker covers, e.g. "7/7 – 6/10 · 2 sessions" -
            // updates on every range tap so the change is always visible.
            val windowStart = cutoff ?: points.firstOrNull()?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val windowLabel = buildString {
                if (windowStart != null) append("${formatDayMonth(windowStart)} – ${formatDayMonth(today)} · ")
                append("${filtered.size} ${if (filtered.size == 1) "session" else "sessions"}")
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppSurface, RoundedCornerShape(18.dp))
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HistoryMetric.values().forEach { metric ->
                        ChartChip(
                            text = metric.label,
                            selected = metric == selectedMetric,
                            onClick = { selectedMetric = metric }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (change != null && kotlin.math.abs(change) >= 0.5f) {
                        Text(
                            text = "${if (change > 0f) "+" else ""}${change.roundToInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (change > 0f) AppSuccess else AppDanger
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                AnimatedContent(
                    targetState = windowLabel,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                    label = "rangeLabel"
                ) { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextMuted,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                // Cross-fades the whole chart when the range or metric changes.
                AnimatedContent(
                    targetState = selectedRange to selectedMetric,
                    transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                    label = "chart"
                ) { (_, metric) ->
                    SessionBarChart(
                        sessions = filtered,
                        valueFor = { metricValue(it, metric, history.isBodyweight) },
                        axisBaseStep = metricAxisBaseStep(metric, history.isBodyweight),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                RangeChips(selected = selectedRange, onSelect = { selectedRange = it })
            }

            Spacer(modifier = Modifier.height(28.dp))
            SectionLabel("SESSION HISTORY · ${filtered.size}")
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (filtered.isEmpty()) {
            item {
                Text(
                    text = "No sessions logged in this range.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextMuted
                )
            }
        } else {
            val newestFirst = filtered.reversed()
            items(newestFirst.size) { index ->
                val (point, _) = newestFirst[index]
                val key = "${point.date}|${point.totalVolumeKg}|${point.sets.size}"
                val isOpen = expanded[key] ?: (index == 0)
                SessionHistoryCard(
                    point = point,
                    isOpen = isOpen,
                    onToggle = { expanded[key] = !isOpen }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
            BiomechanicsCard(muscleGroup = history.muscleGroup, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
}

/** 2-column grid of record tiles; the first (headline) record gets an orange outline. */
@Composable
private fun RecordsGrid(records: List<RecordTile>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        records.chunked(2).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTiles.forEach { tile ->
                    RecordTileCard(tile = tile, modifier = Modifier.weight(1f))
                }
                if (rowTiles.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RecordTileCard(tile: RecordTile, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .background(AppSurface, shape)
            .then(if (tile.highlight) Modifier.border(1.dp, AppAccent, shape) else Modifier)
            .padding(14.dp)
    ) {
        Text(
            text = tile.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (tile.highlight) AppAccent else AppTextMuted
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = tile.value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary,
                maxLines = 1
            )
            if (tile.unit.isNotBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = tile.unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTextSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = tile.detail, style = MaterialTheme.typography.labelSmall, color = AppTextMuted, maxLines = 1)
    }
}

/** Metric toggle inside the chart card - the selected one gets a filled background. */
@Composable
private fun ChartChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AppSurfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) AppTextPrimary else AppTextSecondary
        )
    }
}

@Composable
private fun RangeChips(selected: HistoryTimeRange, onSelect: (HistoryTimeRange) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        HistoryTimeRange.values().forEach { range ->
            val isSelected = range == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) AppSurfaceVariant else Color.Transparent)
                    .clickable { onSelect(range) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = range.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) AppTextPrimary else AppTextMuted
                )
            }
        }
    }
}

private val BAR_AREA_HEIGHT = 140.dp
private val BAR_SLOT_MIN_WIDTH = 44.dp
private val Y_AXIS_WIDTH = 34.dp
private val BarGrey = Color(0xFF2E2E33)
private val GridLine = Color(0xFF2A2A2E)

/** Gridline spacing for each metric: 2.5 kg for weights, 1 rep for
 *  bodyweight reps, 25 kg for volume. Multiplied up (x2, x4, x10...) when the
 *  data would need more than 5 gaps, so a 100 kg lift doesn't get 40 lines. */
private fun metricAxisBaseStep(metric: HistoryMetric, isBodyweight: Boolean): Float = when (metric) {
    HistoryMetric.HEAVIEST_SET -> if (isBodyweight) 1f else 2.5f
    HistoryMetric.TOTAL_VOLUME -> 25f
}

private fun axisStepFor(maxValue: Float, baseStep: Float): Float {
    val multipliers = listOf(1f, 2f, 4f, 10f, 20f, 40f, 100f, 200f, 400f, 1000f)
    return multipliers.map { baseStep * it }.firstOrNull { maxValue / it <= 5f } ?: (baseStep * 1000f)
}

private fun formatAxisValue(value: Float): String = formatDecimal((value * 10).roundToInt() / 10.0)

/** One bar per session, oldest on the left, drawn over horizontal gridlines
 *  with their values on a left axis. Sessions that set a new best for the
 *  selected metric are orange, the rest grey. Bars share the width when they
 *  fit; past that the bars scroll sideways (the grid stays put) and open
 *  scrolled to the newest session. */
@Composable
private fun SessionBarChart(
    sessions: List<Pair<ExerciseHistoryPointResponse, Boolean>>,
    valueFor: (ExerciseHistoryPointResponse) -> Float,
    axisBaseStep: Float,
    modifier: Modifier = Modifier
) {
    if (sessions.isEmpty()) {
        Box(
            modifier = modifier.height(BAR_AREA_HEIGHT + 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "No sessions in this range.", style = MaterialTheme.typography.bodySmall, color = AppTextMuted)
        }
        return
    }

    val values = sessions.map { valueFor(it.first) }
    val maxValue = values.maxOrNull()?.takeIf { it > 0f } ?: axisBaseStep
    val step = axisStepFor(maxValue, axisBaseStep)
    val axisMax = (kotlin.math.ceil(maxValue / step) * step).let { if (it < maxValue + step * 0.15f) it + step else it }
    val gridValues = generateSequence(0f) { it + step }.takeWhile { it <= axisMax + step / 2 }.toList()

    Row(modifier = modifier) {
        // Y axis labels, each centred on its gridline.
        Box(modifier = Modifier.width(Y_AXIS_WIDTH).height(BAR_AREA_HEIGHT)) {
            gridValues.forEach { value ->
                val y = BAR_AREA_HEIGHT * (1f - value / axisMax)
                Text(
                    text = formatAxisValue(value),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted,
                    maxLines = 1,
                    modifier = Modifier.offset(y = y - 7.dp)
                )
            }
        }

        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            // Gridlines: the zero line is the solid x axis, the rest are fainter.
            Canvas(modifier = Modifier.fillMaxWidth().height(BAR_AREA_HEIGHT)) {
                gridValues.forEach { value ->
                    val y = size.height * (1f - value / axisMax)
                    drawLine(
                        color = if (value == 0f) AppTextMuted.copy(alpha = 0.6f) else GridLine,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }

            val fits = BAR_SLOT_MIN_WIDTH * sessions.size <= maxWidth
            val scrollState = rememberScrollState()
            LaunchedEffect(sessions.size, scrollState.maxValue) {
                scrollState.scrollTo(scrollState.maxValue)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (fits) Modifier else Modifier.horizontalScroll(scrollState))
            ) {
                sessions.forEachIndexed { index, (point, isNewBest) ->
                    SessionBar(
                        fraction = values[index] / axisMax,
                        isNewBest = isNewBest,
                        dateLabel = formatBarDate(point.date),
                        modifier = if (fits) Modifier.weight(1f) else Modifier.width(BAR_SLOT_MIN_WIDTH)
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionBar(
    fraction: Float,
    isNewBest: Boolean,
    dateLabel: String,
    modifier: Modifier = Modifier
) {
    val barHeight by animateDpAsState(
        targetValue = BAR_AREA_HEIGHT * fraction.coerceIn(0.03f, 1f),
        label = "barHeight"
    )
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.height(BAR_AREA_HEIGHT),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .width(26.dp)
                    .height(barHeight)
                    .background(
                        if (isNewBest) AppAccent else BarGrey,
                        RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                    )
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = dateLabel, style = MaterialTheme.typography.labelSmall, color = AppTextMuted, maxLines = 1)
    }
}

/** Day/month, e.g. "29/9". */
private fun formatBarDate(iso: String): String = try {
    LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d/M"))
} catch (e: Exception) {
    iso
}

private fun formatDayMonth(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d/M"))

/** One session: date, PR badge and total volume, tap to show/hide the full
 *  set table. Collapsed, it shows the set count and the session's top set. */
@Composable
private fun SessionHistoryCard(point: ExerciseHistoryPointResponse, isOpen: Boolean, onToggle: () -> Unit) {
    val sets = point.sets.sortedBy { it.setNumber }
    val topSet = topSetOf(sets)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppSurface)
            .clickable(onClick = onToggle)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatHistoryDate(point.date),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextPrimary
                )
                if (point.isPr) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PR",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = AppOnAccent,
                        modifier = Modifier
                            .background(AppAccent, RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                text = "${oneDecimal(point.totalVolumeKg)} kg",
                style = MaterialTheme.typography.labelMedium,
                color = AppTextSecondary
            )
        }

        if (!isOpen) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("${sets.size} ${if (sets.size == 1) "set" else "sets"}")
                    if (topSet != null) append(" · top ${formatSetLoad(topSet.weightKg).lowercase()} × ${topSet.reps}")
                    append("  ›")
                },
                style = MaterialTheme.typography.labelMedium,
                color = AppTextMuted
            )
        } else if (sets.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("SET", style = MaterialTheme.typography.labelSmall, color = AppTextMuted, modifier = Modifier.width(48.dp))
                Text("LOAD", style = MaterialTheme.typography.labelSmall, color = AppTextMuted, modifier = Modifier.weight(1f))
                Text("REPS", style = MaterialTheme.typography.labelSmall, color = AppTextMuted, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
            }
            Spacer(modifier = Modifier.height(4.dp))
            sets.forEach { set ->
                // The session's top set is picked out in orange.
                val isTop = set === topSet
                val color = if (isTop) AppAccent else AppTextPrimary
                val weight = if (isTop) FontWeight.SemiBold else FontWeight.Normal
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                ) {
                    Text(
                        text = set.setNumber.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isTop) AppAccent else AppTextMuted,
                        fontWeight = weight,
                        modifier = Modifier.width(48.dp)
                    )
                    Text(
                        text = formatSetLoad(set.weightKg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                        fontWeight = weight,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = set.reps.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                        fontWeight = weight,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(56.dp)
                    )
                }
            }
        }
    }
}

/** Mirrors WorkoutDetailScreen's formatLoad so the set table reads the
 *  same way here as it does on the workout detail screen. */
private fun formatSetLoad(weightKg: Double?): String {
    if (weightKg == null) return "-"
    val number = if (weightKg == weightKg.toLong().toDouble()) {
        weightKg.toLong().toString()
    } else {
        weightKg.toString()
    }
    return "$number KG"
}

/** Static, generic form cues keyed by muscle group - not personalized and
 *  not computed from any logged data, just a reference reminder. Falls back
 *  to a generic tempo/range-of-motion cue for anything not in the map. */
private object ExerciseCoachingTips {
    private val byMuscleGroup: Map<String, String> = mapOf(
        "chest" to "Keep your shoulder blades retracted and pressed into the bench, and control the eccentric (lowering) portion of each rep to keep tension on the chest rather than the shoulders.",
        "back" to "Drive the movement from your elbows rather than your hands, and pause briefly at full contraction to maximize lat and mid-back engagement.",
        "shoulders" to "Keep a slight bend in the elbows and avoid shrugging - lead with your elbows rather than your hands to isolate the deltoid.",
        "triceps" to "Keep your upper arm fixed and close to your body throughout the movement so the triceps, not the shoulder, does the work.",
        "biceps" to "Avoid swinging - keep your elbows pinned at your sides and control the lowering phase to maximize time under tension.",
        "legs" to "Keep your knees tracking in line with your toes and drive through your whole foot, not just your toes, to protect your knees.",
        "quads" to "Keep your knees tracking in line with your toes and drive through your whole foot, not just your toes, to protect your knees.",
        "hamstrings" to "Focus on a slow eccentric with a slight bend in the knees throughout to keep tension on the hamstrings rather than the lower back.",
        "glutes" to "Squeeze at the top of each rep and avoid hyperextending your lower back - let the glutes, not the spine, finish the movement.",
        "core" to "Keep your lower back neutral and exhale on the exertion to maintain intra-abdominal pressure.",
        "calves" to "Use a full range of motion - a deep stretch at the bottom and a full squeeze at the top - rather than small, fast reps.",
        "full body" to "Brace your core before initiating the movement and keep a neutral spine throughout to protect your lower back under load."
    )

    private const val fallback =
        "Focus on a controlled tempo and full range of motion, and stop a rep or two short of failure on your heaviest sets."

    fun tipsFor(muscleGroup: String?): String {
        val key = muscleGroup?.trim()?.lowercase() ?: return fallback
        return byMuscleGroup[key] ?: fallback
    }
}

/** Form tips, folded away by default so they don't push the data down. */
@Composable
private fun BiomechanicsCard(muscleGroup: String?, modifier: Modifier = Modifier) {
    var isOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AppSurface)
            .clickable { isOpen = !isOpen }
            .padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.FitnessCenter,
                contentDescription = null,
                tint = AppAccent,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "FORM & ACTIVATION",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (isOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (isOpen) "Hide form tips" else "Show form tips",
                tint = AppTextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
        AnimatedVisibility(visible = isOpen) {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = ExerciseCoachingTips.tipsFor(muscleGroup),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "General guidance only, not personalized to you - prioritize proper form and consult a professional for individual advice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted
                )
            }
        }
    }
}

private fun formatHistoryDate(iso: String): String {
    return try {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    } catch (e: Exception) {
        iso
    }
}

private fun formatHistoryDateShort(iso: String): String {
    return try {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM"))
    } catch (e: Exception) {
        iso
    }
}
