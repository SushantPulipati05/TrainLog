package com.example.workoutlog_androidstudio

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * TrainLog's home-screen widgets (all Glance):
 *
 *  - Up next (2x2)       - the day Up next picked, a figure with its
 *                          muscles lit, when it was last done, and Start.
 *  - Up next bar (4x1)   - the same in one slim row.
 *  - Consistency (4x2)   - last month + this month, like Home's heatmap.
 *  - Streak (2x2)        - weeks on target in a row + the last weeks' grid.
 *
 * Figures and grids are drawn into bitmaps (widgets can't run Compose
 * drawing). Start opens the app with the same intent as a gym reminder's
 * "Start workout", so it starts the planned day's template exactly like that.
 */

// ---------------------------------------------------------------------------
// Refreshing
// ---------------------------------------------------------------------------

object TrainLogWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Redraw every TrainLog widget (fire and forget). Called when the app
     *  goes to the background, so whatever changed - a workout ended or was
     *  deleted, the plan was edited - shows on the home screen. */
    fun requestRefresh(context: Context) {
        val app = context.applicationContext
        scope.launch { refreshAll(app) }
    }

    suspend fun refreshAll(context: Context) {
        listOf(UpNextWidget(), UpNextBarWidget(), WorkoutHeatmapWidget(), StreakWidget()).forEach { widget ->
            runCatching { widget.updateAll(context) }
        }
    }
}

// ---------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------

private class WidgetData(val workouts: List<WorkoutSummary>, val plan: TrainingPlan?)

/** The workout list (cached in the app if it's running) and the plan. null =
 *  couldn't load (offline, or signed out). */
private suspend fun loadWidgetData(context: Context): WidgetData? {
    TrainingPlanStore.init(context)
    // The saved copy on the phone, so the widgets work offline too.
    AppDataCache.init(context)
    BodyFigureStore.init(context)
    val workouts = try {
        withTimeoutOrNull(8_000) { AppDataCache.loadWorkoutSummaries() }
    } catch (e: Exception) {
        null
    } ?: return null
    return WidgetData(workouts, TrainingPlanStore.plan)
}

private fun WorkoutSummary.day(): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()

private fun openAppAction(context: Context): Action =
    actionStartActivity(Intent(context, MainActivity::class.java))

/** Same intent as a gym reminder's "Start workout": MainActivity starts
 *  this split day's workout (or brings back one already going). */
private fun startAction(context: Context, label: String): Action =
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setAction(GymReminderReceiver.ACTION_START)
            .putExtra(GymReminderReceiver.EXTRA_PLAN_LABEL, label)
    )

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

private val WIDGET_PAD_H = 14.dp
private val WIDGET_PAD_V = 12.dp

private fun text(color: Color, size: Float, bold: Boolean = false, align: TextAlign = TextAlign.Start) = TextStyle(
    color = ColorProvider(color),
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    textAlign = align
)

@Composable
private fun WidgetCard(onClick: Action, content: @Composable () -> Unit) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(AppSurface)
            .cornerRadius(22.dp)
            .padding(horizontal = WIDGET_PAD_H, vertical = WIDGET_PAD_V)
            .clickable(onClick)
    ) { content() }
}

@Composable
private fun SmallLabel(label: String) {
    Text(text = label, style = text(AppTextMuted, 9.5f, bold = true), maxLines = 1)
}

/** Shown when the workouts couldn't be loaded. */
@Composable
private fun WidgetMessage(message: String) {
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, style = text(AppTextSecondary, 11.5f, align = TextAlign.Center))
    }
}

/** Orange "Start" pill with a play triangle. */
@Composable
private fun StartPill(action: Action) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .background(AppAccent)
            .cornerRadius(50.dp)
            .padding(start = 11.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
            .clickable(action),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(provider = ImageProvider(playIcon(context.px(10.dp))), contentDescription = null, modifier = GlanceModifier.size(10.dp))
        Spacer(modifier = GlanceModifier.width(5.dp))
        Text(text = "Start", style = text(AppOnAccent, 12.5f, bold = true))
    }
}

@Composable
private fun Figure(view: BodyView, level: (BodyRegion) -> Float, width: Dp) {
    val context = LocalContext.current
    Image(
        provider = ImageProvider(bodyBitmap(view, level, context.px(width))),
        contentDescription = null,
        modifier = GlanceModifier.size(width, width * 2.2f)
    )
}

private fun Context.px(dp: Dp): Int = (dp.value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

// ---------------------------------------------------------------------------
// Up next (2x2) and Up next bar (4x1)
// ---------------------------------------------------------------------------

/** Everything both Up next widgets show. */
private class UpNextInfo(
    val title: String,
    /** The split day to start (blank = a plain workout). */
    val label: String,
    /** "Last: 6 days ago" / "Not done yet". */
    val detail: String,
    /** "Chest, shoulders rested". */
    val rested: String?,
    val resting: Boolean,
    val view: BodyView,
    val level: (BodyRegion) -> Float
)

private val MUSCLE_ORDER = listOf(
    "CHEST", "SHOULDERS", "TRICEPS", "LATS", "MIDDLE BACK", "TRAPS", "LOWER BACK", "BICEPS", "FOREARMS",
    "QUADRICEPS", "HAMSTRINGS", "GLUTES", "CALVES", "ADDUCTORS", "ABDUCTORS"
)

private fun daysAgoLabel(date: LocalDate?, today: LocalDate): String {
    date ?: return "Not done yet"
    return when (val days = ChronoUnit.DAYS.between(date, today)) {
        0L -> "Last: today"
        1L -> "Last: yesterday"
        else -> "Last: $days days ago"
    }
}

private fun upNextInfo(data: WidgetData, today: LocalDate = LocalDate.now()): UpNextInfo? {
    val upNext = computeUpNext(data.workouts, data.plan, today) ?: return null
    val label = upNext.startLabel
    if (upNext.recovering) {
        // Rest day: show what's still recovering instead.
        val recent = recentlyTrainedMuscles(data.workouts, today)
        val regions = recent.keys.flatMap { regionsOfMuscle(it) }.toSet()
        return UpNextInfo(
            title = "Rest",
            label = label,
            detail = "Everything was trained in the last 2 days. ${upNext.display} is next.",
            rested = null,
            resting = true,
            view = bestViewFor(regions),
            level = recoveryShading(recent)
        )
    }
    val muscles = musclesForSplitDay(label, data.workouts)
    val regions = muscles.flatMap { regionsOfMuscle(it) }.toSet()
    val lastDone = explainUpNext(data.workouts, data.plan, today)?.days?.firstOrNull { it.day == label }?.lastDone
    val rested = muscles.sortedBy { MUSCLE_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
        .take(2)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ") { prettyMuscle(it).lowercase() }
        ?.let { "$it rested" }
    return UpNextInfo(
        title = upNext.display,
        label = label,
        detail = if (data.plan?.orderedDays().orEmpty().size >= 2) daysAgoLabel(lastDone, today) else "Usually ${upNext.basedOn}",
        rested = rested,
        resting = false,
        view = bestViewFor(regions),
        level = { r -> if (r in regions) 1f else 0f }
    )
}

class UpNextWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        val info = data?.let { upNextInfo(it) }
        provideContent {
            val ctx = LocalContext.current
            WidgetCard(onClick = if (info != null && !info.resting) startAction(ctx, info.label) else openAppAction(ctx)) {
                when {
                    data == null -> WidgetMessage("Open TrainLog to load Up next.")
                    info == null -> NoUpNext(ctx)
                    else -> UpNextSquare(info)
                }
            }
        }
    }
}

class UpNextWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UpNextWidget()
}

/** No split and no history to learn from yet. */
@Composable
private fun NoUpNext(context: Context) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        SmallLabel("UP NEXT")
        Spacer(modifier = GlanceModifier.height(6.dp))
        Text(text = "Set up your split", style = text(AppTextPrimary, 15f, bold = true), maxLines = 2)
        Spacer(modifier = GlanceModifier.height(2.dp))
        Text(text = "Profile → Training plan, and this shows what to train next.", style = text(AppTextSecondary, 10.5f), maxLines = 3)
        Spacer(modifier = GlanceModifier.defaultWeight())
        StartPill(startAction(context, ""))
    }
}

/** U2: label, figure + day + last done, Start. */
@Composable
private fun UpNextSquare(info: UpNextInfo) {
    val context = LocalContext.current
    val size = LocalSize.current
    // The figure takes all the height between the label and Start (and at
    // most ~40% of the width), so a tall widget isn't left half empty.
    val reserved = WIDGET_PAD_V.value * 2 + 12f + 6f + (if (info.resting) 0f else 8f + 32f)
    val byHeight = (size.height.value - reserved) / 2.2f
    val byWidth = (size.width.value - WIDGET_PAD_H.value * 2) * 0.4f
    val figureWidth = minOf(byHeight, byWidth).coerceIn(20f, 90f).dp
    val titleSize = if (figureWidth.value >= 45f) 21f else 18f
    Column(modifier = GlanceModifier.fillMaxSize()) {
        SmallLabel("UP NEXT")
        Spacer(modifier = GlanceModifier.height(6.dp))
        Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            Figure(info.view, info.level, figureWidth)
            Spacer(modifier = GlanceModifier.width(9.dp))
            Column {
                Text(text = info.title, style = text(AppTextPrimary, titleSize, bold = true), maxLines = 2)
                Spacer(modifier = GlanceModifier.height(3.dp))
                Text(text = info.detail, style = text(AppTextSecondary, 10.5f), maxLines = if (info.resting) 4 else 2)
            }
        }
        if (!info.resting) {
            Spacer(modifier = GlanceModifier.height(6.dp))
            StartPill(startAction(context, info.label))
        }
    }
}

class UpNextBarWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        val info = data?.let { upNextInfo(it) }
        provideContent {
            val ctx = LocalContext.current
            WidgetCard(onClick = if (info != null && !info.resting) startAction(ctx, info.label) else openAppAction(ctx)) {
                when {
                    data == null -> WidgetMessage("Open TrainLog to load Up next.")
                    else -> UpNextBar(info, ctx)
                }
            }
        }
    }
}

class UpNextBarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UpNextBarWidget()
}

/** U4: small figure, "UP NEXT / Push day · chest, shoulders rested", Start. */
@Composable
private fun UpNextBar(info: UpNextInfo?, context: Context) {
    val height = LocalSize.current.height
    val figureWidth = ((height.value - 2 * 8f) / 2.2f).coerceIn(14f, 40f).dp
    Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        if (info != null) {
            Figure(info.view, info.level, figureWidth)
            Spacer(modifier = GlanceModifier.width(10.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            SmallLabel("UP NEXT")
            val line = when {
                info == null -> "Set up your split"
                info.resting -> "Rest day"
                else -> info.title
            }
            val extra = when {
                info == null -> null
                info.resting -> info.detail.substringAfter(". ").removeSuffix(".").let { "· $it" }
                else -> info.rested?.let { "· $it" }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = line, style = text(AppTextPrimary, 15f, bold = true), maxLines = 1)
                if (extra != null) {
                    Spacer(modifier = GlanceModifier.width(5.dp))
                    Text(text = extra, style = text(AppTextSecondary, 11f), maxLines = 1)
                }
            }
        }
        if (info == null || !info.resting) {
            Spacer(modifier = GlanceModifier.width(8.dp))
            StartPill(startAction(context, info?.label.orEmpty()))
        }
    }
}

// ---------------------------------------------------------------------------
// Consistency (4x2) - same shading and missed outlines as Home's heatmap
// ---------------------------------------------------------------------------

class WorkoutHeatmapWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        val today = LocalDate.now()
        val levels = data?.let { heatmapLevels(it.workouts, it.plan, today) }.orEmpty()
        val missed = data?.let { missedDays(it.workouts, it.plan, today) }.orEmpty()
        val thisMonth = data?.workouts.orEmpty().mapNotNull { it.day() }
            .filter { YearMonth.from(it) == YearMonth.from(today) }.distinct().size
        provideContent {
            val ctx = LocalContext.current
            WidgetCard(onClick = openAppAction(ctx)) {
                if (data == null) {
                    WidgetMessage("Couldn't load. Tap to open TrainLog.")
                } else {
                    val size = LocalSize.current
                    val gridW = size.width - WIDGET_PAD_H * 2
                    val gridH = size.height - WIDGET_PAD_V * 2 - 20.dp
                    // Drawn to fit the space, then trimmed to what it used.
                    val grid = twoMonthBitmap(ctx, ctx.px(gridW), ctx.px(gridH), levels, missed, today)
                    val usedH = (grid.height / ctx.resources.displayMetrics.density).dp
                    Column(modifier = GlanceModifier.fillMaxSize()) {
                        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = GlanceModifier.defaultWeight()) { SmallLabel("CONSISTENCY") }
                            Text(text = "$thisMonth", style = text(AppTextPrimary, 11f, bold = true))
                            Text(text = " this month", style = text(AppTextSecondary, 11f))
                        }
                        // Any height left over goes evenly above and below the grid.
                        Box(
                            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                provider = ImageProvider(grid),
                                contentDescription = "Workouts last month and this month",
                                modifier = GlanceModifier.size(gridW, usedH)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Kept under its original name so widgets already on home screens keep working. */
class WorkoutHeatmapWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WorkoutHeatmapWidget()
}

// ---------------------------------------------------------------------------
// Streak (2x2) - weeks on target in a row
// ---------------------------------------------------------------------------

/** Weeks in a row with at least [target] workout days, counting back from
 *  this week (which only counts once it's reached - it isn't over yet). */
internal fun weeksOnTarget(trainedDates: Set<LocalDate>, target: Int, today: LocalDate): Int {
    val goal = target.coerceIn(1, 7)
    fun daysIn(weekStart: LocalDate) = (0L..6L).count { weekStart.plusDays(it) in trainedDates }
    var week = weekStartOf(today)
    var streak = if (daysIn(week) >= goal) 1 else 0
    week = week.minusWeeks(1)
    repeat(520) {
        if (daysIn(week) < goal) return streak
        streak++
        week = week.minusWeeks(1)
    }
    return streak
}

class StreakWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        val today = LocalDate.now()
        val trained = data?.workouts.orEmpty().mapNotNull { it.day() }.toSet()
        // The weekly target the user set (as on Home); else their plan's training days.
        val profile = AppDataCache.profile ?: runCatching { withTimeoutOrNull(5_000) { AppDataCache.loadProfile() } }.getOrNull()
        val target = profile?.weeklyWorkoutTarget
            ?: data?.plan?.trainingDays?.size?.takeIf { it > 0 }
            ?: 4
        val streak = weeksOnTarget(trained, target, today)
        val doneThisWeek = (0L..6L).count { weekStartOf(today).plusDays(it) in trained }
        val levels = data?.let { heatmapLevels(it.workouts, it.plan, today) }.orEmpty()
        val missed = data?.let { missedDays(it.workouts, it.plan, today) }.orEmpty()
        provideContent {
            val ctx = LocalContext.current
            WidgetCard(onClick = openAppAction(ctx)) {
                if (data == null) {
                    WidgetMessage("Couldn't load. Tap to open TrainLog.")
                } else {
                    val size = LocalSize.current
                    val stripW = size.width - WIDGET_PAD_H * 2
                    // The grid fills everything under the number: cell size
                    // from the height left, then as many weeks (columns) as
                    // fit the width (4-26), stretched to fill it exactly.
                    val gap = 2.5f
                    val spaceH = (size.height - WIDGET_PAD_V * 2 - 56.dp).value.coerceAtLeast(30f)
                    val cellByHeight = (spaceH - 6 * gap) / 7f
                    val weeks = ((stripW.value + gap) / (cellByHeight + gap)).toInt().coerceIn(4, 26)
                    val cell = minOf(cellByHeight, (stripW.value - (weeks - 1) * gap) / weeks)
                    val stripH = (cell * 7 + 6 * gap).dp
                    Column(modifier = GlanceModifier.fillMaxSize()) {
                        SmallLabel("ON TARGET")
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = if (streak > 0) "$streak" else "$doneThisWeek/$target",
                                style = text(AppAccent, if (streak > 0) 32f else 26f, bold = true)
                            )
                            Spacer(modifier = GlanceModifier.width(6.dp))
                            Text(
                                text = when {
                                    streak == 1 -> "week in a row"
                                    streak > 1 -> "weeks in a row"
                                    else -> "this week"
                                },
                                style = text(AppTextPrimary, 11f, bold = true),
                                modifier = GlanceModifier.padding(bottom = 6.dp)
                            )
                        }
                        Spacer(modifier = GlanceModifier.defaultWeight())
                        Image(
                            provider = ImageProvider(weekStripBitmap(ctx, weeks, ctx.px(stripW), ctx.px(stripH), levels, missed, today)),
                            contentDescription = "Workouts in the last $weeks weeks",
                            modifier = GlanceModifier.size(stripW, stripH)
                        )
                    }
                }
            }
        }
    }
}

class StreakWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StreakWidget()
}

// ---------------------------------------------------------------------------
// Bitmaps
// ---------------------------------------------------------------------------

private val EMPTY_CELL = AppSurfaceVariant
private val FUTURE_CELL = AppSurfaceVariant.copy(alpha = 0.45f)
private val MISSED_EDGE = AppAccent.copy(alpha = 0.6f)

/** One day's square: orange by level, outlined if missed, dim if it hasn't happened. */
private fun drawDay(
    canvas: Canvas, paint: Paint, rect: RectF, radius: Float, date: LocalDate, today: LocalDate,
    levels: Map<LocalDate, Float>, missed: Set<LocalDate>, density: Float
) {
    when {
        date.isAfter(today) -> {
            paint.style = Paint.Style.FILL; paint.color = FUTURE_CELL.toArgb()
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        date in missed -> {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = density; paint.color = MISSED_EDGE.toArgb()
            val inset = RectF(rect).apply { inset(density / 2, density / 2) }
            canvas.drawRoundRect(inset, radius, radius, paint)
        }
        else -> {
            val level = levels[date] ?: 0f
            paint.style = Paint.Style.FILL
            paint.color = (if (level > 0f) AppAccent.copy(alpha = level) else EMPTY_CELL).toArgb()
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}

/** Last month + this month side by side, with month names, weekday letters
 *  and a legend underneath - Home's design A in widget form. */
private fun twoMonthBitmap(
    context: Context, w: Int, h: Int, levels: Map<LocalDate, Float>, missed: Set<LocalDate>, today: LocalDate
): Bitmap {
    val d = context.resources.displayMetrics.density
    val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f * d }

    val monthGap = 14f * d
    val gap = 2.5f * d
    val nameH = 13f * d
    val lettersH = 11f * d
    val legendH = 14f * d
    val monthW = (w - monthGap) / 2f
    val months = listOf(YearMonth.from(today).minusMonths(1), YearMonth.from(today))
    // Only as many rows as the months need (5 or 6), so no empty row is reserved.
    val rows = months.maxOf { m -> (m.atDay(1).dayOfWeek.value - 1 + m.lengthOfMonth() + 6) / 7 }
    val legendGap = 8f * d
    val gridH = h - nameH - lettersH - legendGap - legendH
    val cell = minOf((monthW - 6 * gap) / 7f, (gridH - (rows - 1) * gap) / rows).coerceAtLeast(2f)
    val radius = cell * 0.22f
    val letters = listOf("M", "T", "W", "T", "F", "S", "S")

    // Last month from the left edge, this month to the right edge - like
    // the mockup's two columns - whatever the widget's width.
    val usedMonthW = 7 * cell + 6 * gap
    months.forEachIndexed { i, month ->
        val left = if (i == 0) 0f else w - usedMonthW
        textPaint.color = AppTextSecondary.toArgb()
        textPaint.isFakeBoldText = true
        textPaint.textSize = 9f * d
        canvas.drawText(month.month.getDisplayName(JavaTextStyle.FULL, Locale.ENGLISH).uppercase(), left, 9f * d, textPaint)
        textPaint.isFakeBoldText = false
        textPaint.color = AppTextMuted.toArgb()
        textPaint.textSize = 7.5f * d
        textPaint.textAlign = Paint.Align.CENTER
        letters.forEachIndexed { c, letter ->
            canvas.drawText(letter, left + c * (cell + gap) + cell / 2, nameH + 8f * d, textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT

        val top = nameH + lettersH
        val lead = month.atDay(1).dayOfWeek.value - 1
        for (day in 1..month.lengthOfMonth()) {
            val pos = lead + day - 1
            val x = left + (pos % 7) * (cell + gap)
            val y = top + (pos / 7) * (cell + gap)
            drawDay(canvas, paint, RectF(x, y, x + cell, y + cell), radius, month.atDay(day), today, levels, missed, d)
        }
    }

    // Legend right under the grid: Less [] [] [] [] [] More   [ ] Missed
    val gridBottom = nameH + lettersH + rows * cell + (rows - 1) * gap
    val ly = gridBottom + legendGap + legendH / 2f
    val sq = 8f * d
    var x = 0f
    textPaint.color = AppTextMuted.toArgb()
    textPaint.textSize = 8.5f * d
    fun word(s: String) { canvas.drawText(s, x, ly + 3f * d, textPaint); x += textPaint.measureText(s) + 4f * d }
    fun square(fill: Color?, outline: Boolean = false) {
        val r = RectF(x, ly - sq / 2, x + sq, ly + sq / 2)
        if (outline) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = d; paint.color = MISSED_EDGE.toArgb()
            r.inset(d / 2, d / 2)
        } else {
            paint.style = Paint.Style.FILL; paint.color = (fill ?: EMPTY_CELL).toArgb()
        }
        canvas.drawRoundRect(r, 2f * d, 2f * d, paint)
        x += sq + 3f * d
    }
    word("Less")
    square(null)
    listOf(0.3f, 0.5f, 0.75f, 1f).forEach { square(AppAccent.copy(alpha = it)) }
    x += 1f * d
    word("More")
    x += 8f * d
    square(null, outline = true)
    word("Missed")
    // Trim to what was drawn, so the widget doesn't reserve empty space.
    val usedH = (gridBottom + legendGap + legendH).toInt().coerceIn(1, bitmap.height)
    return if (usedH < bitmap.height) Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, usedH) else bitmap
}

/** The last [weeks] weeks as columns (Monday at the top), ending this week. */
private fun weekStripBitmap(
    context: Context, weeks: Int, w: Int, h: Int, levels: Map<LocalDate, Float>, missed: Set<LocalDate>, today: LocalDate
): Bitmap {
    val d = context.resources.displayMetrics.density
    val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val gap = 2.5f * d
    val cell = minOf((w - (weeks - 1) * gap) / weeks, (h - 6 * gap) / 7f)
    // Right-aligned so this week sits at the edge.
    val startX = w - (weeks * cell + (weeks - 1) * gap)
    val firstMonday = weekStartOf(today).minusWeeks((weeks - 1).toLong())
    for (col in 0 until weeks) for (row in 0 until 7) {
        val date = firstMonday.plusDays((col * 7 + row).toLong())
        val x = startX + col * (cell + gap)
        val y = row * (cell + gap)
        drawDay(canvas, paint, RectF(x, y, x + cell, y + cell), cell * 0.25f, date, today, levels, missed, d)
    }
    return bitmap
}

/** A small filled play triangle in the "on accent" colour. */
private fun playIcon(size: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AppOnAccent.toArgb() }
    val path = android.graphics.Path().apply {
        moveTo(size * 0.18f, size * 0.08f)
        lineTo(size * 0.92f, size * 0.5f)
        lineTo(size * 0.18f, size * 0.92f)
        close()
    }
    canvas.drawPath(path, paint)
    return bitmap
}
