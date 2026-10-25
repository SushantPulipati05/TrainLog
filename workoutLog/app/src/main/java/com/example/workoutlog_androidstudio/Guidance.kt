package com.example.workoutlog_androidstudio

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Helping a new user find their way (see new-user-guidance mockup):
 *  1. three welcome cards right after onboarding,
 *  2. a "Getting started" checklist on Home that ticks itself off,
 *  3. one-time tips the first time someone reaches a screen.
 * Only for accounts that just went through onboarding on this phone -
 * people already using the app never see any of it.
 */

/** The checklist's steps, in order. */
enum class GuideStep(val title: String, val hint: String) {
    FIRST_WORKOUT("Log your first workout", "Start, add an exercise, log a set"),
    SEE_BODY_MAP("See what it hit", "Open the workout for its body map"),
    SET_SPLIT("Set your split", "So Up next fits how you train"),
    TAP_MUSCLE("Tap a muscle on Progress", "See only its exercises"),
    OPEN_CALENDAR("Open the calendar", "Every day you've trained")
}

/** The one-time tips. */
enum class GuideTip(val title: String, val body: String) {
    PROGRESS("Tap any muscle", "See only the exercises that train it - tap again for the whole group."),
    CALENDAR("Missed logging a day?", "Pick it here and add the workout afterwards."),
    EXERCISE_CARD("Grey numbers are last time", "Type over them. Beat your best and it tells you straight away."),
    HEATMAP("Each square is a day", "Brighter = a fuller workout. Tap Calendar for every day in detail.")
}

object GuideStore {
    private const val PREFS = "guide"
    private var prefs: android.content.SharedPreferences? = null

    /** True for an account that finished onboarding on this phone. */
    var isNewUser by mutableStateOf(false)
        private set
    var welcomePending by mutableStateOf(false)
        private set
    var checklistHidden by mutableStateOf(false)
        private set
    private var doneSteps by mutableStateOf<Set<String>>(emptySet())
    private var seenTips by mutableStateOf<Set<String>>(emptySet())

    /** Where each tip's target is on screen (root coordinates). */
    var targets by mutableStateOf<Map<GuideTip, Rect>>(emptyMap())
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        isNewUser = p.getBoolean("new_user", false)
        welcomePending = p.getBoolean("welcome_pending", false)
        checklistHidden = p.getBoolean("checklist_hidden", false)
        doneSteps = p.getStringSet("done", emptySet()).orEmpty().toSet()
        seenTips = p.getStringSet("tips", emptySet()).orEmpty().toSet()
    }

    /** Onboarding just finished: welcome cards next, then the checklist and tips. */
    fun startForNewUser() {
        isNewUser = true
        welcomePending = true
        checklistHidden = false
        doneSteps = emptySet()
        seenTips = emptySet()
        prefs?.edit()
            ?.putBoolean("new_user", true)
            ?.putBoolean("welcome_pending", true)
            ?.putBoolean("checklist_hidden", false)
            ?.putStringSet("done", emptySet())
            ?.putStringSet("tips", emptySet())
            ?.apply()
    }

    fun finishWelcome() {
        welcomePending = false
        prefs?.edit()?.putBoolean("welcome_pending", false)?.apply()
    }

    fun hideChecklist() {
        checklistHidden = true
        prefs?.edit()?.putBoolean("checklist_hidden", true)?.apply()
    }

    fun markDone(step: GuideStep) {
        if (!isNewUser || step.name in doneSteps) return
        doneSteps = doneSteps + step.name
        prefs?.edit()?.putStringSet("done", doneSteps)?.apply()
    }

    /** Done if it was done by hand, or the app's state already shows it. */
    fun isDone(step: GuideStep): Boolean = when (step) {
        GuideStep.FIRST_WORKOUT -> !AppDataCache.workoutSummaries.isNullOrEmpty() || step.name in doneSteps
        GuideStep.SET_SPLIT -> TrainingPlanStore.plan != null || step.name in doneSteps
        else -> step.name in doneSteps
    }

    val showChecklist: Boolean
        get() = isNewUser && !checklistHidden && !GuideStep.values().all { isDone(it) }

    fun shouldShowTip(tip: GuideTip): Boolean = isNewUser && !welcomePending && tip.name !in seenTips

    fun markTipSeen(tip: GuideTip) {
        if (tip.name in seenTips) return
        seenTips = seenTips + tip.name
        prefs?.edit()?.putStringSet("tips", seenTips)?.apply()
    }

    fun setTarget(tip: GuideTip, rect: Rect) {
        if (targets[tip] != rect) targets = targets + (tip to rect)
    }

    fun clearTarget(tip: GuideTip) {
        if (tip in targets) targets = targets - tip
    }
}

/** Marks a composable as what [tip] points at (only while it's unseen). */
fun Modifier.guideTarget(tip: GuideTip): Modifier = composed {
    // Forget the spot when the target leaves the screen.
    androidx.compose.runtime.DisposableEffect(tip) { onDispose { GuideStore.clearTarget(tip) } }
    if (!GuideStore.shouldShowTip(tip)) this
    else this.onGloballyPositioned { GuideStore.setTarget(tip, it.boundsInRoot()) }
}

// ---------------------------------------------------------------------------
// 1. Welcome cards
// ---------------------------------------------------------------------------

private class WelcomePage(val art: @Composable () -> Unit, val lead: String, val highlight: String, val body: String)

/** Three swipeable cards over everything, right after onboarding. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WelcomeCards(onDone: () -> Unit) {
    val pages = remember {
        listOf(
            WelcomePage({ GuideGlyph(GlyphKind.DUMBBELL, Modifier.size(74.dp)) }, "Just start ", "lifting.",
                "Tap Start, add exercises as you go. Your last numbers show up as you type, and it saves even with no signal."),
            WelcomePage({ GuideGlyph(GlyphKind.UP_NEXT, Modifier.size(74.dp)) }, "It knows what's ", "next.",
                "Up next picks today's day from what you actually trained, and skips what's still recovering."),
            WelcomePage({ WelcomeBodyArt() }, "See every ", "muscle.",
                "Each workout lights up exactly what it hit - upper chest, side delts, the long head of your triceps.")
        )
    }
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Skip",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDone)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
                val page = pages[index]
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .background(AppSurface, RoundedCornerShape(22.dp)),
                        contentAlignment = Alignment.Center
                    ) { page.art() }
                    Spacer(modifier = Modifier.height(28.dp))
                    Text(
                        text = buildAnnotatedString {
                            append(page.lead)
                            withStyle(SpanStyle(color = AppAccent)) { append(page.highlight) }
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = AppTextPrimary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = page.body, style = MaterialTheme.typography.bodyLarge, color = AppTextSecondary)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                pages.indices.forEach { i ->
                    val on = i == pager.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = if (on) 18.dp else 6.dp, height = 6.dp)
                            .background(if (on) AppAccent else AppBorder, RoundedCornerShape(3.dp))
                    )
                }
            }
            val last = pager.currentPage == pages.lastIndex
            Button(
                onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) {
                Text(text = if (last) "Start training" else "Next", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** A push day on the body map, for the third card. */
@Composable
private fun WelcomeBodyArt() {
    val push = mapOf(
        BodyRegion.UPPER_CHEST to 1f, BodyRegion.MID_CHEST to 0.8f, BodyRegion.LOWER_CHEST to 0.4f,
        BodyRegion.FRONT_DELT to 0.9f, BodyRegion.SIDE_DELT to 1f,
        BodyRegion.TRICEPS_LATERAL to 0.8f, BodyRegion.TRICEPS_MEDIAL to 0.7f
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BodyFigure(view = BodyView.FRONT, level = { push[it] ?: 0f }, width = 76.dp, gapColor = AppSurface)
        BodyFigure(view = BodyView.BACK, level = { push[it] ?: 0f }, width = 76.dp, gapColor = AppSurface)
    }
}

// ---------------------------------------------------------------------------
// 2. Getting started checklist (Home)
// ---------------------------------------------------------------------------

@Composable
fun GettingStartedCard(onOpen: (GuideStep) -> Unit, modifier: Modifier = Modifier) {
    val steps = GuideStep.values().toList()
    val done = steps.count { GuideStore.isDone(it) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppSurface, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Getting started",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppTextPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Hide",
                style = MaterialTheme.typography.labelMedium,
                color = AppTextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { GuideStore.hideChecklist() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().height(5.dp).background(AppSurfaceVariant, RoundedCornerShape(3.dp))) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(done / steps.size.toFloat())
                    .height(5.dp)
                    .background(AppAccent, RoundedCornerShape(3.dp))
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = "$done of ${steps.size} done", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        steps.forEachIndexed { i, step ->
            val isDone = GuideStore.isDone(step)
            if (i > 0) Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppBorder))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isDone) { onOpen(step) }
                    .padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(if (isDone) AppAccent else Color.Transparent, RoundedCornerShape(6.dp))
                        .border(1.5.dp, if (isDone) AppAccent else AppBorder, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isDone) GuideGlyph(GlyphKind.TICK, Modifier.size(12.dp), color = AppOnAccent)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isDone) AppTextMuted else AppTextPrimary,
                        textDecoration = if (isDone) TextDecoration.LineThrough else null
                    )
                    if (!isDone) {
                        Text(text = step.hint, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
                    }
                }
                if (!isDone) GuideGlyph(GlyphKind.CHEVRON, Modifier.size(14.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 3. One-time tips
// ---------------------------------------------------------------------------

/**
 * Draws [tip] over the whole screen: everything dimmed except its target
 * (outlined in orange) and a white tip next to it. Any tap dismisses it
 * for good. Nothing shows until the target has been laid out.
 */
@Composable
fun GuideTipOverlay(tip: GuideTip?) {
    val target = tip?.let { GuideStore.targets[it] }
    AnimatedVisibility(visible = tip != null && target != null, enter = fadeIn(), exit = fadeOut()) {
        if (tip == null || target == null) return@AnimatedVisibility
        val density = LocalDensity.current
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    GuideStore.markTipSeen(tip)
                    GuideStore.clearTarget(tip)
                }
        ) {
            val pad = with(density) { 6.dp.toPx() }
            val hole = Rect(target.left - pad, target.top - pad, target.right + pad, target.bottom + pad)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = CornerRadius(14.dp.toPx())
                val path = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addRoundRect(RoundRect(hole, radius))
                }
                drawPath(path, Color.Black.copy(alpha = 0.6f))
                drawRoundRect(
                    color = AppAccent,
                    topLeft = hole.topLeft,
                    size = Size(hole.width, hole.height),
                    cornerRadius = radius,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            // Below the target when there's room, otherwise above.
            val screenH = with(density) { maxHeight.toPx() }
            val screenW = with(density) { maxWidth.toPx() }
            val tipW = with(density) { 240.dp.toPx() }
            val gap = with(density) { 12.dp.toPx() }
            val below = hole.bottom + gap + with(density) { 130.dp.toPx() } < screenH
            val x = (hole.center.x - tipW / 2).coerceIn(with(density) { 16.dp.toPx() }, screenW - tipW - with(density) { 16.dp.toPx() })
            Column(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x.roundToInt(),
                            if (below) (hole.bottom + gap).roundToInt() else (hole.top - gap - with(density) { 120.dp.toPx() }).roundToInt()
                        )
                    }
                    .width(240.dp)
                    .background(AppTextPrimary, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(text = tip.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color(0xFF111114))
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = tip.body, style = MaterialTheme.typography.bodySmall, color = Color(0xFF2A2A2E))
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Got it",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = GUIDE_ACCENT_ON_LIGHT,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** The accent, darkened enough to read on the white tip. */
private val GUIDE_ACCENT_ON_LIGHT = Color(0xFFC24A22)

// ---------------------------------------------------------------------------
// 4. Empty screens that teach
// ---------------------------------------------------------------------------

/** A centred icon, title and line of text (and an optional button) for a
 *  screen with nothing on it yet. */
@Composable
fun TeachingEmptyState(
    glyph: GlyphKind,
    title: String,
    body: String,
    action: String? = null,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(64.dp).background(AppSurface, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) { GuideGlyph(glyph, Modifier.size(30.dp)) }
        Spacer(modifier = Modifier.height(14.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = AppTextPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        if (action != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) { Text(text = action, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ---------------------------------------------------------------------------
// The app's own small icons (drawn, no icon library)
// ---------------------------------------------------------------------------

enum class GlyphKind { DUMBBELL, UP_NEXT, CHART, LIST, TICK, CHEVRON }

@Composable
fun GuideGlyph(kind: GlyphKind, modifier: Modifier = Modifier, color: Color = AppAccent) {
    Canvas(modifier = modifier) {
        val u = size.width / 24f
        val w = (if (kind == GlyphKind.TICK || kind == GlyphKind.CHEVRON) 3f else 1.8f) * u
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(vararg pts: Pair<Float, Float>) {
            val path = Path().apply {
                moveTo(pts[0].first * u, pts[0].second * u)
                pts.drop(1).forEach { lineTo(it.first * u, it.second * u) }
            }
            drawPath(path, color, style = stroke)
        }
        fun rrect(x: Float, y: Float, w2: Float, h: Float, r: Float) =
            drawRoundRect(color, p(x, y), Size(w2 * u, h * u), CornerRadius(r * u), style = stroke)
        when (kind) {
            GlyphKind.DUMBBELL -> {
                rrect(3f, 9f, 3f, 6f, 1f); rrect(18f, 9f, 3f, 6f, 1f)
                rrect(6f, 7f, 2.5f, 10f, 1f); rrect(15.5f, 7f, 2.5f, 10f, 1f)
                line(8.5f to 12f, 15.5f to 12f)
            }
            GlyphKind.UP_NEXT -> {
                rrect(3.5f, 5f, 17f, 15.5f, 3f)
                line(3.5f to 10f, 20.5f to 10f); line(8f to 3f, 8f to 7f); line(16f to 3f, 16f to 7f)
                line(10f to 13.5f, 13f to 16f, 10f to 18.5f)
            }
            GlyphKind.CHART -> { line(4f to 18f, 9f to 12f, 13f to 15f, 20f to 6f); line(15f to 6f, 20f to 6f, 20f to 11f) }
            GlyphKind.LIST -> {
                line(9f to 7f, 20f to 7f); line(9f to 12f, 20f to 12f); line(9f to 17f, 20f to 17f)
                listOf(7f, 12f, 17f).forEach { y -> drawCircle(color, 1.2f * u, p(5f, y)) }
            }
            GlyphKind.TICK -> line(5f to 12.5f, 9.5f to 17f, 19f to 7.5f)
            GlyphKind.CHEVRON -> line(9f to 6f, 15f to 12f, 9f to 18f)
        }
    }
}
