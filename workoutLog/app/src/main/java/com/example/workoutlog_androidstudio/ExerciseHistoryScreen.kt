package com.example.workoutlog_androidstudio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FitnessCenter
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.workoutlog_androidstudio.api.ExerciseHistoryPointResponse
import com.example.workoutlog_androidstudio.api.ExerciseHistoryResponse
import com.example.workoutlog_androidstudio.api.SetEntryResponse
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** The exercise detail / growth-chart screen, opened by tapping an exercise
 *  card on a finished workout's detail screen. Shows an all-time best card,
 *  a switchable metric chart (heaviest set / total volume)
 *  with a timeframe selector, and every finished session that logged this
 *  exercise with its full set-by-set breakdown - all backed by the single
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

/** How far back the chart/session list below the selector looks. */
private enum class HistoryTimeRange(val label: String) {
    ONE_WEEK("1W"),
    ONE_MONTH("1M"),
    THREE_MONTHS("3M"),
    SIX_MONTHS("6M"),
    ONE_YEAR("1Y")
}

/** Which per-workout number the chart (and the "latest" summary above it)
 *  plots. Everything here comes straight from real logged data - no RPE or
 *  effort rating, since the app doesn't collect one when a set is logged. */
private enum class HistoryMetric(val label: String) {
    HEAVIEST_SET("HEAVIEST SET"),
    TOTAL_VOLUME("TOTAL VOLUME")
}

private fun metricValue(point: ExerciseHistoryPointResponse, metric: HistoryMetric, isBodyweight: Boolean): Float =
    when (metric) {
        HistoryMetric.HEAVIEST_SET -> if (isBodyweight) point.maxReps.toFloat() else (point.maxWeightKg ?: 0.0).toFloat()
        HistoryMetric.TOTAL_VOLUME -> point.totalVolumeKg.toFloat()
    }

private fun metricUnit(metric: HistoryMetric, isBodyweight: Boolean): String = when (metric) {
    HistoryMetric.HEAVIEST_SET -> if (isBodyweight) "REPS" else "KG"
    HistoryMetric.TOTAL_VOLUME -> "KG"
}

private fun metricAxisBaseStep(metric: HistoryMetric, isBodyweight: Boolean): Float = when (metric) {
    HistoryMetric.HEAVIEST_SET -> if (isBodyweight) 1f else 2.5f
    HistoryMetric.TOTAL_VOLUME -> 25f
}

private fun formatMetricValue(value: Float, metric: HistoryMetric): String = formatDecimal(value.toDouble())

@Composable
private fun ExerciseHistoryContent(exerciseName: String, history: ExerciseHistoryResponse) {
    val points = history.history

    var selectedRange by remember { mutableStateOf(HistoryTimeRange.THREE_MONTHS) }
    var selectedMetric by remember { mutableStateOf(HistoryMetric.HEAVIEST_SET) }

    val filteredPoints = remember(points, selectedRange) {
        val cutoff = when (selectedRange) {
            HistoryTimeRange.ONE_WEEK -> LocalDate.now().minusWeeks(1)
            HistoryTimeRange.ONE_MONTH -> LocalDate.now().minusMonths(1)
            HistoryTimeRange.THREE_MONTHS -> LocalDate.now().minusMonths(3)
            HistoryTimeRange.SIX_MONTHS -> LocalDate.now().minusMonths(6)
            HistoryTimeRange.ONE_YEAR -> LocalDate.now().minusYears(1)
        }
        points.filter { point ->
            try {
                !LocalDate.parse(point.date).isBefore(cutoff)
            } catch (e: Exception) {
                true
            }
        }
    }

    // The "latest" summary compares the two most recent sessions *within
    // the selected range* - same thing the chart itself is showing.
    val latestValue = filteredPoints.lastOrNull()?.let { metricValue(it, selectedMetric, history.isBodyweight) }
    val previousValue = if (filteredPoints.size >= 2) {
        metricValue(filteredPoints[filteredPoints.size - 2], selectedMetric, history.isBodyweight)
    } else {
        null
    }
    val delta = if (latestValue != null && previousValue != null) latestValue - previousValue else null
    val percentChange = if (delta != null && previousValue != null && previousValue != 0f) {
        (delta / previousValue) * 100f
    } else {
        null
    }

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
            Spacer(modifier = Modifier.height(16.dp))

            // End of section 1 (title + all-time best). A wider gap marks
            // the move into section 2 (the metric-to-timeframe group).
            AllTimeBestCard(history = history, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(32.dp))

            SegmentedControl(
                options = HistoryMetric.values().toList(),
                selected = selectedMetric,
                label = { it.label },
                onSelect = { selectedMetric = it },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (latestValue != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${formatMetricValue(latestValue, selectedMetric)} ${metricUnit(selectedMetric, history.isBodyweight)}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = AppAccent
                    )
                    // Only shown when the number actually moved - a flat
                    // "+0 KG (0%)" badge next to every unchanged session
                    // would just be noise.
                    if (delta != null && percentChange != null && kotlin.math.abs(delta) > 0.01f) {
                        Spacer(modifier = Modifier.width(10.dp))
                        val isUp = delta >= 0f
                        Text(
                            text = "${if (isUp) "+" else ""}${formatMetricValue(delta, selectedMetric)} " +
                                "${metricUnit(selectedMetric, history.isBodyweight)} (${if (isUp) "+" else ""}${percentChange.roundToInt()}%)",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isUp) AppSuccess else AppDanger,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "LATEST ${selectedMetric.label} · ${formatHistoryDate(filteredPoints.last().date)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            ExerciseProgressChart(
                points = filteredPoints,
                valueFor = { metricValue(it, selectedMetric, history.isBodyweight) },
                axisBaseStep = metricAxisBaseStep(selectedMetric, history.isBodyweight),
                formatAxisValue = { formatMetricValue(it, selectedMetric) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(AppSurface, RoundedCornerShape(18.dp))
                    .padding(16.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            SegmentedControl(
                options = HistoryTimeRange.values().toList(),
                selected = selectedRange,
                label = { it.label },
                onSelect = { selectedRange = it },
                modifier = Modifier.fillMaxWidth()
            )

            // End of section 2. Another wide gap before section 3
            // (session history).
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "SESSION HISTORY (${filteredPoints.size} LOGGED)",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (filteredPoints.isEmpty()) {
            item {
                Text(
                    text = "No sessions logged in this range.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextMuted
                )
            }
        } else {
            items(filteredPoints.reversed()) { point ->
                SessionHistoryCard(point = point)
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        item {
            // End of section 3. Wide gap before section 4 (form &
            // activation).
            Spacer(modifier = Modifier.height(32.dp))
            BiomechanicsCard(muscleGroup = history.muscleGroup, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** The "ALL-TIME BEST" hero card - heaviest weight ever logged (or most
 *  reps, for a bodyweight exercise) across the exercise's whole history,
 *  regardless of the timeframe selected below, plus how far that is above
 *  the first session ever logged for it. */
@Composable
private fun AllTimeBestCard(history: ExerciseHistoryResponse, modifier: Modifier = Modifier) {
    val points = history.history
    val unit = if (history.isBodyweight) "REPS" else "KG"
    val bestValue = if (history.isBodyweight) {
        points.maxOfOrNull { it.maxReps.toFloat() }
    } else {
        points.mapNotNull { it.maxWeightKg }.maxOrNull()?.toFloat()
    }
    val firstValue = if (history.isBodyweight) {
        points.firstOrNull()?.maxReps?.toFloat()
    } else {
        points.firstOrNull { it.maxWeightKg != null }?.maxWeightKg?.toFloat()
    }
    val firstDate = points.firstOrNull()?.date

    if (bestValue == null) return

    val baselinePercent = if (firstValue != null && firstValue > 0f) {
        ((bestValue - firstValue) / firstValue) * 100f
    } else {
        null
    }

    Column(
        modifier = modifier
            .background(AppSurface, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.EmojiEvents,
                contentDescription = null,
                tint = AppAccent,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "ALL-TIME BEST", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (history.isBodyweight) bestValue.toInt().toString() else formatDecimal(bestValue.toDouble()),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = unit,
                style = MaterialTheme.typography.titleMedium,
                color = AppTextMuted,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        if (baselinePercent != null && firstDate != null && kotlin.math.abs(baselinePercent) >= 1f) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${if (baselinePercent >= 0f) "+" else ""}${baselinePercent.roundToInt()}% since ${formatHistoryDateShort(firstDate)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (baselinePercent >= 0f) AppSuccess else AppDanger
            )
        }
    }
}

/** A generic pill-row segmented control, shared by the metric picker and
 *  the timeframe picker - tapping a segment calls [onSelect] with it. */
@Composable
private fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(AppSurface, RoundedCornerShape(14.dp))
            .padding(4.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) AppSurfaceVariant else Color.Transparent)
                    .clickable(onClick = { onSelect(option) })
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) AppTextPrimary else AppTextMuted
                )
            }
        }
    }
}

/** One finished session's card - just the date, this exercise's total
 *  volume that session, and every individual set logged, in order. */
@Composable
private fun SessionHistoryCard(point: ExerciseHistoryPointResponse) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatHistoryDate(point.date),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppTextPrimary
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "TOTAL VOL", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
                Text(
                    text = "${formatDecimal(point.totalVolumeKg)} KG",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextPrimary
                )
            }
        }
        if (point.sets.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "SET",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "LOAD",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "REPS",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            point.sets.sortedBy { it.setNumber }.forEach { set ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = set.setNumber.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatSetLoad(set.weightKg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = set.reps.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
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

@Composable
private fun BiomechanicsCard(muscleGroup: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(AppSurface, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.FitnessCenter,
                contentDescription = null,
                tint = AppAccent,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "FORM & ACTIVATION", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
        }
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

private fun formatHistoryDate(iso: String): String {
    return try {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    } catch (e: Exception) {
        iso
    }
}

private fun formatHistoryDateShort(iso: String): String {
    return try {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMM d"))
    } catch (e: Exception) {
        iso
    }
}


/** A hand-rolled area/line chart (no charting library yet), styled after a
 *  stock app's price chart - a gradient fill under a smooth line, PR
 *  workouts marked with a small ringed dot, a right-side Y axis gridded in
 *  fixed, round steps (no gridlines cluttering the plot itself), and an X
 *  axis with the first and last date in view underneath. [valueFor] and
 *  [axisBaseStep] are supplied by the caller so the same chart draws
 *  whichever metric (heaviest set / total volume) is
 *  currently selected. */
@Composable
private fun ExerciseProgressChart(
    points: List<ExerciseHistoryPointResponse>,
    valueFor: (ExerciseHistoryPointResponse) -> Float,
    axisBaseStep: Float,
    formatAxisValue: (Float) -> String,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(text = "No sessions in this range.", style = MaterialTheme.typography.bodySmall, color = AppTextMuted)
        }
        return
    }

    val values = points.map(valueFor)
    val maxValue = values.maxOrNull() ?: 0f
    val minValue = values.minOrNull() ?: 0f

    // The Y axis is gridded in fixed steps (2.5kg, 25kg or 1 rep depending
    // on the metric - see metricAxisBaseStep) rather than dividing whatever
    // range the data happens to span into thirds, so the labels are always
    // round, familiar numbers. If that would pack in more than 6 lines (a
    // wide range), the step is scaled up by a whole multiple of itself -
    // still a clean multiple of the base step, just a bigger one.
    val axisStep = run {
        var step = axisBaseStep
        while ((maxValue - minValue) / step > 6) step += axisBaseStep
        step
    }
    val axisMin = kotlin.math.floor(minValue / axisStep) * axisStep
    val axisMax = (kotlin.math.ceil(maxValue / axisStep) * axisStep)
        .let { if (it <= axisMin) axisMin + axisStep else it }
    val axisRange = axisMax - axisMin
    // Descending so the first label drawn is the top of the chart.
    val axisLabels = generateSequence(axisMax) { it - axisStep }
        .takeWhile { it >= axisMin - (axisStep / 2) }
        .toList()

    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                fun yFor(value: Float): Float {
                    val fraction = (value - axisMin) / axisRange
                    // Inverted - a higher value draws closer to the top of the canvas.
                    return size.height - (fraction * size.height)
                }

                if (values.size < 2) {
                    drawCircle(
                        color = if (points.first().isPr) AppAccent else AppTextSecondary,
                        radius = 5.dp.toPx(),
                        center = Offset(size.width / 2f, yFor(values.first()))
                    )
                    return@Canvas
                }

                val stepX = size.width / (values.size - 1)
                val linePath = Path()
                values.forEachIndexed { index, value ->
                    val x = stepX * index
                    val y = yFor(value)
                    if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                }

                // The filled area under the line: the same path, closed down
                // to the bottom edge and back - drawn first so the line sits
                // on top of it, same as a stock app's price chart.
                val fillPath = Path().apply {
                    addPath(linePath)
                    lineTo(stepX * (values.size - 1), size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(AppAccent.copy(alpha = 0.35f), AppAccent.copy(alpha = 0f))
                    )
                )

                drawPath(
                    path = linePath,
                    color = AppAccent,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Only PR workouts get their own marker - a plain line reads
                // cleaner for every other point.
                values.forEachIndexed { index, value ->
                    if (points[index].isPr) {
                        val center = Offset(stepX * index, yFor(value))
                        drawCircle(color = AppBackground, radius = 6.dp.toPx(), center = center)
                        drawCircle(color = AppAccent, radius = 4.5.dp.toPx(), center = center)
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Y axis on the right, like a stock chart - evenly spaced in
            // fixed steps, no gridlines drawn across the plot itself.
            Column(
                modifier = Modifier.fillMaxHeight().width(42.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.Start
            ) {
                axisLabels.forEach { value ->
                    Text(text = formatAxisValue(value), style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // X axis - the first and last date in view, indented so they line
        // up under the chart itself rather than the Y axis column beside it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 52.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatHistoryDateShort(points.first().date) + if (points.first().isPr) " (PR)" else "",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted
            )
            if (points.size > 1) {
                Text(
                    text = formatHistoryDateShort(points.last().date) + if (points.last().isPr) " (PR)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted
                )
            }
        }
    }
}
