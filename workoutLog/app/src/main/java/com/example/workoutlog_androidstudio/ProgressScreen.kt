package com.example.workoutlog_androidstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.workoutlog_androidstudio.api.NetworkClient
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------------------
// GET /progress
// ---------------------------------------------------------------------------

@Serializable
data class ProgressExerciseResponse(
    val exerciseId: Int,
    val name: String,
    val muscleGroup: String? = null,
    val isBodyweight: Boolean = false,
    val sessions: Int,
    val lastDate: String,
    val topWeightKg: Double? = null,
    val topReps: Int = 0,
    val change3m: Double? = null
)

@Serializable
data class ProgressPrResponse(
    val exerciseId: Int,
    val name: String,
    val weightKg: Double? = null,
    val reps: Int,
    val date: String
)

@Serializable
data class ProgressResponse(
    val exercises: List<ProgressExerciseResponse> = emptyList(),
    val recentPrs: List<ProgressPrResponse> = emptyList()
)

/** The muscle groups the Progress tab talks about, and the exercise-list
 *  muscle names that belong to each. */
private val PROGRESS_GROUPS: List<Pair<String, Set<String>>> = listOf(
    "Chest" to setOf("CHEST"),
    "Back" to setOf("LATS", "MIDDLE BACK", "LOWER BACK", "TRAPS"),
    "Shoulders" to setOf("SHOULDERS"),
    "Arms" to setOf("BICEPS", "TRICEPS", "FOREARMS"),
    "Legs" to setOf("QUADRICEPS", "HAMSTRINGS", "GLUTES", "CALVES", "ADDUCTORS", "ABDUCTORS"),
    "Abs" to setOf("ABDOMINALS")
)

private fun groupOf(muscleGroup: String?): String? {
    val m = muscleGroup?.trim()?.uppercase() ?: return null
    return PROGRESS_GROUPS.firstOrNull { m in it.second }?.first
}

/** One muscle group's sets this week against a usual week. */
private data class WeekBalance(val group: String, val thisWeek: Int, val usual: Int)

/**
 * Sets per muscle group this week (Monday to today) and in a usual week:
 * the average of the 4 full weeks before this one. Groups with nothing in
 * either are left out.
 */
private fun weekBalance(workouts: List<WorkoutSummary>, today: LocalDate = LocalDate.now()): List<WeekBalance> {
    val thisMonday = weekStartOf(today)
    val fromUsual = thisMonday.minusWeeks(4)
    val thisWeek = HashMap<String, Int>()
    val usualTotal = HashMap<String, Int>()
    workouts.forEach { w ->
        val date = runCatching { LocalDate.parse(w.date) }.getOrNull() ?: return@forEach
        val target = when {
            !date.isBefore(thisMonday) && !date.isAfter(today) -> thisWeek
            !date.isBefore(fromUsual) && date.isBefore(thisMonday) -> usualTotal
            else -> return@forEach
        }
        w.muscleWork().forEach { (muscle, sets) ->
            val group = groupOf(muscle) ?: return@forEach
            target[group] = (target[group] ?: 0) + sets
        }
    }
    return PROGRESS_GROUPS.map { (group, _) ->
        WeekBalance(group, thisWeek[group] ?: 0, Math.round((usualTotal[group] ?: 0) / 4.0).toInt())
    }.filter { it.thisWeek > 0 || it.usual > 0 }
}

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d/M", Locale.ENGLISH)

private fun shortDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(SHORT_DATE) }.getOrDefault(iso)

/** "32.5 kg × 8", or "12 reps" for bodyweight with nothing added. */
private fun topSetLabel(weightKg: Double?, reps: Int): String =
    if (weightKg != null && weightKg > 0.0) "${formatDecimal(weightKg)} kg × $reps" else "$reps reps"

/**
 * The Progress tab: recent PRs, this week's sets per muscle group against a
 * usual week, and every exercise the user has logged (most done first) -
 * each opening that exercise's analytics. Search and muscle chips filter
 * the exercise list.
 */
@Composable
fun ProgressScreen(onOpenExercise: (exerciseId: Int, name: String) -> Unit, modifier: Modifier = Modifier) {
    var progress by remember { mutableStateOf<ProgressResponse?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }

    // Reload whenever the workout list changes (a workout ended or was
    // deleted) or the exercise list does (a merge).
    val summaries = AppDataCache.workoutSummaries
    LaunchedEffect(summaries, AppDataCache.exercises) {
        try {
            progress = NetworkClient.workoutApi.getProgress()
            errorMessage = null
        } catch (e: Exception) {
            if (progress == null) errorMessage = "Couldn't load your progress. Check your connection."
        }
    }
    LaunchedEffect(Unit) { if (summaries == null) runCatching { AppDataCache.loadWorkoutSummaries() } }

    val balance = remember(summaries) { weekBalance(summaries.orEmpty()) }
    val exercises = progress?.exercises.orEmpty()
    val shown = remember(exercises, query, group) {
        exercises.filter { e ->
            (group == null || groupOf(e.muscleGroup) == group) &&
                matchesExerciseQuery(query, e.name, e.muscleGroup)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 120.dp)
    ) {
        item {
            Text(
                text = "Progress",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = AppTextMuted) },
                placeholder = { Text(text = "Search exercises or muscles", color = AppTextMuted) },
                modifier = Modifier.fillMaxWidth(),
                colors = appTextFieldColors()
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SelectableChip(text = "All", selected = group == null, onClick = { group = null })
                PROGRESS_GROUPS.forEach { (name, _) ->
                    SelectableChip(text = name, selected = group == name, onClick = { group = if (group == name) null else name })
                }
            }
        }

        if (progress == null) {
            item {
                Spacer(modifier = Modifier.height(40.dp))
                Text(
                    text = errorMessage ?: "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary
                )
            }
            return@LazyColumn
        }

        if (exercises.isEmpty()) {
            item {
                Spacer(modifier = Modifier.height(40.dp))
                Text(
                    text = "Log a workout and your lifts, records and weekly sets will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary
                )
            }
            return@LazyColumn
        }

        // ---- Recent PRs (hidden while searching / filtering) ----
        val prs = progress?.recentPrs.orEmpty()
        if (prs.isNotEmpty() && query.isBlank() && group == null) {
            item {
                SectionLabel("RECENT PRS")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    prs.forEach { pr -> PrCard(pr = pr, onClick = { onOpenExercise(pr.exerciseId, pr.name) }) }
                }
            }
        }

        // ---- Sets per muscle this week ----
        if (balance.isNotEmpty() && query.isBlank() && group == null) {
            item {
                SectionLabel("SETS THIS WEEK")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppSurface, RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Each bar fills up as you reach your usual week (your average over the last 4 weeks).",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    balance.forEach { BalanceRow(it) }
                }
            }
        }

        // ---- Your exercises ----
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("YOUR EXERCISES · ${shown.size}", modifier = Modifier.weight(1f))
                if (query.isBlank()) SectionLabel("MOST USED")
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(text = "No exercises match.", style = MaterialTheme.typography.bodyMedium, color = AppTextMuted)
            }
        } else {
            items(shown, key = { it.exerciseId }) { exercise ->
                ExerciseProgressRow(exercise = exercise, onClick = { onOpenExercise(exercise.exerciseId, exercise.name) })
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = AppTextMuted,
        modifier = modifier.padding(top = 24.dp, bottom = 10.dp)
    )
}

@Composable
private fun PrCard(pr: ProgressPrResponse, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(shape)
            .background(AppSurface)
            .border(1.dp, AppAccent.copy(alpha = 0.4f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = pr.name,
            style = MaterialTheme.typography.labelMedium,
            color = AppTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = topSetLabel(pr.weightKg, pr.reps),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = AppTextPrimary,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = AppAccent, modifier = Modifier.size(12.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = shortDate(pr.date), style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
        }
    }
}

/** "Legs  [=====-----]  8 of 18 sets". A full bar = a usual week. Groups
 *  well behind (under 60%) get a fainter bar and an orange number. */
@Composable
private fun BalanceRow(b: WeekBalance) {
    val fraction = when {
        b.usual <= 0 -> if (b.thisWeek > 0) 1f else 0f
        else -> (b.thisWeek.toFloat() / b.usual).coerceIn(0f, 1f)
    }
    val behind = b.usual > 0 && b.thisWeek < b.usual * 0.6f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = b.group, style = MaterialTheme.typography.bodyMedium, color = AppTextPrimary, modifier = Modifier.width(78.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(AppSurfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(AppAccent.copy(alpha = if (behind) 0.45f else 1f), RoundedCornerShape(4.dp))
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = if (b.usual > 0) "${b.thisWeek} of ${b.usual} sets" else "${b.thisWeek} sets",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (behind) FontWeight.SemiBold else FontWeight.Normal,
            color = if (behind) AppAccent else AppTextSecondary,
            modifier = Modifier.width(84.dp),
            maxLines = 1
        )
    }
}

/** One exercise: name, muscle and sessions; latest top set and its change
 *  over 3 months; a chevron - the whole row opens its analytics. */
@Composable
private fun ExerciseProgressRow(exercise: ProgressExerciseResponse, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AppSurface)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = exercise.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    exercise.muscleGroup?.lowercase()?.replaceFirstChar { it.uppercase() },
                    "${exercise.sessions} ${if (exercise.sessions == 1) "session" else "sessions"}"
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = AppTextMuted
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = exercise.topWeightKg?.let { "${formatDecimal(it)} kg" } ?: "${exercise.topReps} reps",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppTextPrimary
            )
            exercise.change3m?.takeIf { kotlin.math.abs(it) >= 0.05 }?.let { change ->
                val unit = if (exercise.topWeightKg != null) "kg" else "reps"
                Text(
                    text = "${if (change > 0) "+" else "−"}${formatDecimal(Math.round(kotlin.math.abs(change) * 10) / 10.0)} $unit",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (change > 0) AppAccent else AppTextMuted
                )
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AppTextMuted, modifier = Modifier.padding(start = 4.dp))
    }
}
