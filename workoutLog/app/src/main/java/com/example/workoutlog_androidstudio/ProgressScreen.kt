package com.example.workoutlog_androidstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Balance
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
import com.example.workoutlog_androidstudio.onboarding.GlyphKind
import com.example.workoutlog_androidstudio.onboarding.GuideStep
import com.example.workoutlog_androidstudio.onboarding.GuideStore
import com.example.workoutlog_androidstudio.onboarding.GuideTip
import com.example.workoutlog_androidstudio.onboarding.TeachingEmptyState
import com.example.workoutlog_androidstudio.onboarding.guideTarget
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
    val change3m: Double? = null,
    val change3mReps: Int? = null,
    val e1rmChange3m: Double? = null,
    // Body-map muscles (BodyRegion names) - empty for old custom exercises.
    val primaryMuscles: List<String> = emptyList(),
    val secondaryMuscles: List<String> = emptyList()
)

@Serializable
data class ProgressPrResponse(
    val exerciseId: Int,
    val name: String,
    val weightKg: Double? = null,
    val reps: Int,
    val date: String,
    /** "WEIGHT" = heavier than ever; "REP" = more reps than ever at a weight already lifted. */
    val kind: String = "WEIGHT",
    /** The record it beat: old best weight (WEIGHT) or old best reps at that weight (REP). */
    val previous: Double? = null
)

/** One insight card: "FORM" check-in, "NOT_DONE" lately, or a small "WIN". */
@Serializable
data class ProgressInsightResponse(
    val kind: String,
    val exerciseId: Int,
    val name: String,
    val message: String
)

@Serializable
data class ProgressResponse(
    val exercises: List<ProgressExerciseResponse> = emptyList(),
    val recentPrs: List<ProgressPrResponse> = emptyList(),
    val insights: List<ProgressInsightResponse> = emptyList()
)

/** Insight cards the user crossed off, until when (millis). A form check-in
 *  stays away 4 weeks, "not done lately" 3 weeks, a small win 2 months
 *  (a different win for the same exercise can still show). */
private object DismissedInsights {
    private const val PREFS = "progress_insights"

    private fun key(i: ProgressInsightResponse) = "${i.kind}:${i.exerciseId}:${if (i.kind == "WIN") i.message.hashCode() else 0}"

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    fun isHidden(context: android.content.Context, i: ProgressInsightResponse): Boolean =
        prefs(context).getLong(key(i), 0L) > System.currentTimeMillis()

    fun dismiss(context: android.content.Context, i: ProgressInsightResponse) {
        val days = when (i.kind) { "FORM" -> 28L; "NOT_DONE" -> 21L; else -> 60L }
        prefs(context).edit().putLong(key(i), System.currentTimeMillis() + days * 24 * 60 * 60 * 1000).apply()
    }
}

/** How "Your exercises" is ordered. */
private enum class ExerciseSort(val label: String) { MOST_USED("Most used"), RECENT("Recently done"), BIGGEST_GAIN("Biggest gain") }

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
fun ProgressScreen(
    onOpenExercise: (exerciseId: Int, name: String) -> Unit,
    modifier: Modifier = Modifier,
    onStartWorkout: () -> Unit = {}
) {
    var progress by remember { mutableStateOf<ProgressResponse?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }
    // One muscle tapped on the body map ("Upper chest") - narrower than a group.
    var region by remember { mutableStateOf<BodyRegion?>(null) }
    var sort by remember { mutableStateOf(ExerciseSort.MOST_USED) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var dismissTick by remember { mutableStateOf(0) }

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

    val exercises = progress?.exercises.orEmpty()
    val shown = remember(exercises, query, group, region, sort) {
        exercises
            .filter { e ->
                (group == null || groupOf(e.muscleGroup) == group) &&
                    (region == null || exerciseTrains(region!!, e.primaryMuscles, e.name, e.muscleGroup)) &&
                    matchesExerciseQuery(query, e.name, e.muscleGroup)
            }
            .let { list ->
                when (sort) {
                    ExerciseSort.MOST_USED -> list.sortedWith(compareByDescending<ProgressExerciseResponse> { it.sessions }.thenByDescending { it.lastDate })
                    ExerciseSort.RECENT -> list.sortedByDescending { it.lastDate }
                    // Estimated 1RM change; exercises without one go last.
                    ExerciseSort.BIGGEST_GAIN -> list.sortedWith(compareByDescending<ProgressExerciseResponse> { it.e1rmChange3m ?: Double.NEGATIVE_INFINITY })
                }
            }
    }
    // Recovery body map: what's still recovering from the last two days,
    // and why Up next picked what it did.
    val recent = remember(summaries) { recentlyTrainedMuscles(summaries.orEmpty()) }
    val recoveryLevel = remember(summaries) { recoveryShadingFor(summaries.orEmpty()) }
    val upNext = remember(summaries, TrainingPlanStore.plan) {
        if ((TrainingPlanStore.plan?.orderedDays().orEmpty().distinct().size) >= 2)
            computeUpNext(summaries.orEmpty(), TrainingPlanStore.plan) else null
    }

    // At most 3, form check-ins first (the server sends them in that order).
    val insights = remember(progress, dismissTick) {
        progress?.insights.orEmpty().filterNot { DismissedInsights.isHidden(context, it) }.take(3)
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
                // Fully rounded and filled, like the mockup.
                shape = RoundedCornerShape(50),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AppSurface,
                    unfocusedContainerColor = AppSurface,
                    focusedBorderColor = AppBorder,
                    unfocusedBorderColor = AppBorder,
                    focusedTextColor = AppTextPrimary,
                    unfocusedTextColor = AppTextPrimary,
                    cursorColor = AppAccent
                )
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SelectableChip(text = "All", selected = group == null && region == null, onClick = { group = null; region = null })
                PROGRESS_GROUPS.forEach { (name, _) ->
                    SelectableChip(text = name, selected = group == name, onClick = { region = null; group = if (group == name) null else name })
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
                Spacer(modifier = Modifier.height(24.dp))
                TeachingEmptyState(
                    glyph = GlyphKind.CHART,
                    title = "Your progress lives here",
                    body = "After your first workout: your records, what's recovering on the body map, and how each lift is moving.",
                    action = "Start a workout",
                    onAction = onStartWorkout
                )
            }
            return@LazyColumn
        }

        // ---- Recovery: the body map, right on the screen (no card). Tap a
        // muscle to filter Your exercises to its group. Hidden while searching.
        if (query.isBlank() && summaries != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionLabel("RECOVERY", modifier = Modifier.weight(1f))
                    Text(
                        text = "tap a muscle to filter",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextMuted,
                        modifier = Modifier.padding(top = 24.dp, bottom = 10.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                BodyMapPair(
                    level = recoveryLevel,
                    outlined = region?.let { setOf(it) } ?: regionsInGroup(group),
                    fillWidth = true,
                    modifier = Modifier.guideTarget(GuideTip.PROGRESS),
                    // 1st tap: just that muscle. 2nd tap on it: its whole
                    // group. A tap inside the selected group: clear.
                    onTap = { tapped ->
                        GuideStore.markDone(GuideStep.TAP_MUSCLE)
                        when {
                            region == tapped -> { region = null; group = progressGroupOf(tapped) }
                            region == null && group != null && progressGroupOf(tapped) == group -> { group = null }
                            else -> { region = tapped; group = null }
                        }
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                BodyMapLegend(start = "Ready", end = "Recovering", levels = listOf(0f, RECOVERY_YESTERDAY_LEVEL, 1f))
                Spacer(modifier = Modifier.height(12.dp))
                RecoveryLine(recent = recent, upNext = upNext)
            }
        }

        // ---- Recent PRs (hidden while searching / filtering) ----
        val prs = progress?.recentPrs.orEmpty()
        if (prs.isNotEmpty() && query.isBlank() && group == null && region == null) {
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

        // ---- Insights: separate cards, each can be crossed off ----
        if (insights.isNotEmpty() && query.isBlank() && group == null && region == null) {
            item { SectionLabel("INSIGHTS") }
            items(insights, key = { "${it.kind}:${it.exerciseId}" }) { insight ->
                InsightCard(
                    insight = insight,
                    onOpen = { onOpenExercise(insight.exerciseId, insight.name) },
                    onDismiss = {
                        DismissedInsights.dismiss(context, insight)
                        dismissTick++
                    }
                )
            }
        }

        // ---- Your exercises ----
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("YOUR EXERCISES · ${shown.size}", modifier = Modifier.weight(1f))
                // The muscle or group picked by a chip or by tapping the body
                // map ("Upper chest", "Chest"); × clears it.
                (region?.let { regionLabel(it) } ?: group)?.let { g ->
                    Row(
                        modifier = Modifier
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(50))
                            .background(AppAccent)
                            .clickable { group = null; region = null }
                            .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = g, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = AppOnAccent)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Filled.Close, contentDescription = "Clear filter", tint = AppOnAccent, modifier = Modifier.size(14.dp))
                    }
                }
            }
            SortControl(selected = sort, onSelect = { sort = it })
            Spacer(modifier = Modifier.height(10.dp))
        }
        if (shown.isEmpty()) {
            item {
                Text(text = "No exercises match.", style = MaterialTheme.typography.bodyMedium, color = AppTextMuted)
            }
        } else {
            items(shown, key = { it.exerciseId }) { exercise ->
                ExerciseProgressRow(
                    exercise = exercise,
                    showE1rm = sort == ExerciseSort.BIGGEST_GAIN,
                    onClick = { onOpenExercise(exercise.exerciseId, exercise.name) }
                )
            }
        }
    }
}

/** The muscle groups the Recovery line names, with the muscles that make
 *  each one "still recovering". Abs left out - they're in everything. */
private val RECOVERY_GROUPS: List<Pair<String, Set<String>>> = PROGRESS_GROUPS.filter { it.first != "Abs" }

/** "Ready: Chest, Shoulders, Arms" and, with a split, why Up next picked
 *  its day - so the figure and Up next tell the same story. */
@Composable
private fun RecoveryLine(recent: Map<String, Long>, upNext: UpNext?) {
    val ready = RECOVERY_GROUPS.filter { (_, muscles) -> muscles.none { it in recent } }.map { it.first }
    val first = when {
        ready.size == RECOVERY_GROUPS.size -> "Everything's rested."
        ready.isEmpty() -> "Everything's still recovering."
        else -> "Ready: ${ready.joinToString(", ")}"
    }
    val second = when {
        upNext == null -> null
        upNext.recovering -> "A rest day is the better call."
        ready.isEmpty() -> null
        else -> "That's why Up next is ${upNext.display}."
    }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = first, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = AppTextPrimary)
        second?.let {
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = AppTextSecondary)
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
            .width(150.dp)
            .clip(shape)
            .background(AppSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        val isRep = pr.kind == "REP"
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (isRep) AppSurfaceVariant else AppAccent)
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(
                text = if (isRep) "REP PR" else "WEIGHT PR",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isRep) AppAccent else AppOnAccent
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
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
        Text(
            text = shortDate(pr.date) + (pr.previous?.let { prev ->
                when {
                    pr.kind == "REP" && pr.weightKg != null -> " · best at ${formatDecimal(pr.weightKg)} was × ${prev.toInt()}"
                    pr.kind == "REP" -> " · best was ${prev.toInt()} reps"
                    else -> " · was ${formatDecimal(prev)} kg"
                }
            } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = AppTextMuted,
            maxLines = 2
        )
    }
}

/** One exercise: name, muscle and sessions; latest top set ("60 kg × 12")
 *  and its change over 3 months - in kg, or in reps when the weight held,
 *  or in estimated 1RM when sorting by Biggest gain; a chevron. The whole
 *  row opens its analytics. */
@Composable
private fun ExerciseProgressRow(exercise: ProgressExerciseResponse, showE1rm: Boolean, onClick: () -> Unit) {
    fun oneDp(x: Double) = formatDecimal(Math.round(x * 10) / 10.0)
    val change: Pair<String, Boolean>? = when {
        showE1rm -> exercise.e1rmChange3m?.takeIf { kotlin.math.abs(it) >= 0.05 }?.let {
            "${if (it > 0) "+" else "−"}${oneDp(kotlin.math.abs(it))} kg (est. 1RM)" to (it > 0)
        }
        exercise.change3m != null && kotlin.math.abs(exercise.change3m) >= 0.05 ->
            "${if (exercise.change3m > 0) "+" else "−"}${oneDp(kotlin.math.abs(exercise.change3m))} kg" to (exercise.change3m > 0)
        exercise.change3mReps != null && exercise.change3mReps != 0 ->
            "${if (exercise.change3mReps > 0) "+" else "−"}${kotlin.math.abs(exercise.change3mReps)} reps" to (exercise.change3mReps > 0)
        else -> null
    }
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
                text = topSetLabel(exercise.topWeightKg, exercise.topReps),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppTextPrimary,
                maxLines = 1
            )
            change?.let { (text, up) ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (up) AppAccent else AppTextMuted,
                    maxLines = 1
                )
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AppTextMuted, modifier = Modifier.padding(start = 4.dp))
    }
}

/** Most used · Recently done · Biggest gain. */
@Composable
private fun SortControl(selected: ExerciseSort, onSelect: (ExerciseSort) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppSurface)
            .padding(3.dp)
    ) {
        ExerciseSort.values().forEach { option ->
            val on = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) AppSurfaceVariant else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) AppTextPrimary else AppTextSecondary
                )
            }
        }
    }
}

/** One insight on its own card: an icon for the kind, the exercise, the
 *  message, and an × to cross it off. Tapping the card opens the exercise. */
@Composable
private fun InsightCard(insight: ProgressInsightResponse, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val (icon, tint) = when (insight.kind) {
        "FORM" -> Icons.Filled.Balance to AppAccent
        "NOT_DONE" -> Icons.Filled.Schedule to AppTextSecondary
        else -> Icons.Filled.TrendingUp to AppAccent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AppSurface)
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AppSurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (insight.kind == "FORM") "${insight.name} · form check-in" else insight.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppTextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = insight.message, style = MaterialTheme.typography.bodySmall, color = AppTextSecondary)
        }
        androidx.compose.material3.IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = AppTextMuted, modifier = Modifier.size(18.dp))
        }
    }
}
