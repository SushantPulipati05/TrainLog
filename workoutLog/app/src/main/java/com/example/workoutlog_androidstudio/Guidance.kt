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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.aspectRatio
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Helping a new user find their way (see new-user-guidance mockup):
 *  1. three welcome cards before the personal-details questions,
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

/**
 * The guide's progress, saved per account (SharedPreferences "guide_<uid>"),
 * so signing out and back in never restarts it, and another account on
 * the same phone has its own. Steps that are done stay done even while the
 * app is still loading after a sign-in, and once every step is done the
 * checklist is finished for good.
 */
object GuideStore {
    /** Before guide progress was saved per account. */
    private const val LEGACY_PREFS = "guide"
    private var appContext: Context? = null
    private var prefs: android.content.SharedPreferences? = null
    private var accountUid: String? = null

    /** True for an account that finished onboarding on this phone. */
    var isNewUser by mutableStateOf(false)
        private set
    var welcomePending by mutableStateOf(false)
        private set
    /** The welcome cards a brand-new account sees before the personal-
     *  details questions have been shown (so they don't replay if the app
     *  is closed halfway through onboarding). */
    var introSeen by mutableStateOf(false)
        private set
    var checklistHidden by mutableStateOf(false)
        private set
    private var doneSteps by mutableStateOf<Set<String>>(emptySet())
    private var seenTips by mutableStateOf<Set<String>>(emptySet())

    /** Where each tip's target is on screen (root coordinates). */
    var targets by mutableStateOf<Map<GuideTip, Rect>>(emptyMap())
        private set

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        useAccount(runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid }.getOrNull())
    }

    /** Loads [uid]'s guide progress (null = signed out: nothing shows). */
    fun useAccount(uid: String?) {
        val context = appContext ?: return
        if (uid == accountUid && (uid == null || prefs != null)) return
        accountUid = uid
        targets = emptyMap()
        if (uid == null) {
            prefs = null
            isNewUser = false
            welcomePending = false
            introSeen = false
            checklistHidden = false
            doneSteps = emptySet()
            seenTips = emptySet()
            return
        }
        val p = context.getSharedPreferences("guide_" + uid.filter { it.isLetterOrDigit() }, Context.MODE_PRIVATE)
        // One-time move: progress saved before it was per account belongs
        // to whoever is signed in when this version first runs.
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (p.all.isEmpty() && legacy.all.isNotEmpty()) {
            p.edit()
                .putBoolean("new_user", legacy.getBoolean("new_user", false))
                .putBoolean("welcome_pending", legacy.getBoolean("welcome_pending", false))
                .putBoolean("checklist_hidden", legacy.getBoolean("checklist_hidden", false))
                .putStringSet("done", legacy.getStringSet("done", emptySet()).orEmpty().toSet())
                .putStringSet("tips", legacy.getStringSet("tips", emptySet()).orEmpty().toSet())
                .apply()
            legacy.edit().clear().apply()
        }
        prefs = p
        isNewUser = p.getBoolean("new_user", false)
        welcomePending = p.getBoolean("welcome_pending", false)
        // Whether this account has seen the welcome cards (shown before
        // its personal-details questions).
        introSeen = p.getBoolean("intro_seen", false)
        checklistHidden = p.getBoolean("checklist_hidden", false)
        doneSteps = p.getStringSet("done", emptySet()).orEmpty().toSet()
        seenTips = p.getStringSet("tips", emptySet()).orEmpty().toSet()
    }

    /** The welcome cards were read (or skipped): on to the personal details. */
    fun finishIntro() {
        introSeen = true
        prefs?.edit()?.putBoolean("intro_seen", true)?.apply()
    }

    /** Onboarding just finished: the checklist and tips start. (The welcome
     *  cards already came before onboarding.) The intro flag is reset, so a
     *  different new account on this phone sees the cards too. */
    fun startForNewUser() {
        isNewUser = true
        welcomePending = false
        introSeen = false
        checklistHidden = false
        doneSteps = emptySet()
        seenTips = emptySet()
        prefs?.edit()
            ?.putBoolean("new_user", true)
            ?.putBoolean("welcome_pending", false)
            ?.putBoolean("intro_seen", false)
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
        finishIfAllDone()
    }

    /**
     * Saves steps the app's data shows are done (a workout exists, a split
     * is set), so they stay ticked after signing back in - when that data
     * is briefly missing until it reloads.
     */
    fun recordAutoSteps(hasWorkouts: Boolean, hasPlan: Boolean) {
        if (!isNewUser) return
        if (hasWorkouts) markDone(GuideStep.FIRST_WORKOUT)
        if (hasPlan) markDone(GuideStep.SET_SPLIT)
    }

    /** Every step done: the checklist is finished and never comes back. */
    private fun finishIfAllDone() {
        if (!checklistHidden && GuideStep.values().all { it.name in doneSteps }) hideChecklist()
    }

    /** Done if it was done by hand, or the app's state already shows it. */
    fun isDone(step: GuideStep): Boolean = when (step) {
        GuideStep.FIRST_WORKOUT -> !AppDataCache.workoutSummaries.isNullOrEmpty() || step.name in doneSteps
        GuideStep.SET_SPLIT -> TrainingPlanStore.plan != null || step.name in doneSteps
        else -> step.name in doneSteps
    }

    /** Waits for the workout list to load after a sign-in, so steps that
     *  are done never flash up as not done. */
    val showChecklist: Boolean
        get() = isNewUser && !checklistHidden && AppDataCache.workoutSummaries != null &&
            !GuideStep.values().all { isDone(it) }

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

/** Same grey as last time's numbers on the real exercise card. */
private val WELCOME_GHOST = Color(0xFF55555B)

private class WelcomePage(
    val kicker: String,
    val lead: String,
    val highlight: String,
    val body: String,
    val art: @Composable () -> Unit
)

/**
 * Three swipeable cards a brand-new account sees first, before the
 * personal-details questions. Each one shows a small copy of the real
 * screen it talks about, so the app looks familiar once they get there.
 * [onDone] runs from "Get started" on the last card or from Skip.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WelcomeCards(onDone: () -> Unit) {
    val pages = remember {
        listOf(
            WelcomePage("LOG", "Just start ", "lifting.",
                "Last time's numbers wait in grey. Type over them. It saves even with no signal.") { WelcomeExerciseArt() },
            WelcomePage("PLAN", "It knows what's ", "next.",
                "Up next follows what you actually trained and skips what's still recovering.") { WelcomeUpNextArt() },
            WelcomePage("SEE", "See every ", "muscle.",
                "Each workout lights up exactly what it hit, down to the side delts.") { WelcomeBodyArt() }
        )
    }
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
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
            Row(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${pager.currentPage + 1} / ${pages.size}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppTextMuted,
                    modifier = Modifier.weight(1f)
                )
                if (!last) {
                    Text(
                        text = "Skip",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDone)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
                val page = pages[index]
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(AppSurface),
                        contentAlignment = Alignment.Center
                    ) { page.art() }
                    Spacer(modifier = Modifier.height(26.dp))
                    Text(
                        text = page.kicker,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppAccent
                    )
                    Spacer(modifier = Modifier.height(8.dp))
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
            Button(
                onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (last) AppAccent else Color.White,
                    contentColor = if (last) AppOnAccent else Color.Black
                )
            ) {
                Text(text = if (last) "Get started" else "Next", fontWeight = if (last) FontWeight.Bold else FontWeight.SemiBold)
            }
        }
    }
}

/** First card: a copy of an exercise card mid-workout - last time's numbers
 *  in grey, one box being typed over, saved with no signal. */
@Composable
private fun WelcomeExerciseArt() {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Incline Bench Press", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                Text("UPPER CHEST", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AppTextMuted)
            }
            Text(
                text = "NO SIGNAL · SAVED",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = AppTextMuted,
                modifier = Modifier
                    .background(AppSurfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = buildAnnotatedString {
                append("Best ")
                withStyle(SpanStyle(color = AppAccent, fontWeight = FontWeight.SemiBold)) { append("70 kg × 6") }
                append(" · last time 65 kg × 8")
            },
            fontSize = 12.sp,
            color = AppTextSecondary
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("SET", "KG", "REPS").forEachIndexed { i, label ->
                Text(
                    text = label,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTextMuted,
                    textAlign = TextAlign.Center,
                    modifier = if (i == 0) Modifier.width(34.dp) else Modifier.weight(1f)
                )
            }
        }
        WelcomeSetRow("1", "65", "8")
        WelcomeSetRow("2", "67.5", "8", typing = true, repsGhost = true)
        WelcomeSetRow("3", "65", "7", kgGhost = true, repsGhost = true)
    }
}

@Composable
private fun WelcomeSetRow(
    number: String,
    kg: String,
    reps: String,
    typing: Boolean = false,
    kgGhost: Boolean = false,
    repsGhost: Boolean = false
) {
    Spacer(modifier = Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier.width(34.dp).height(34.dp).background(AppSurfaceVariant, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) { Text(number, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTextPrimary) }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(34.dp)
                .background(AppSurfaceVariant, RoundedCornerShape(9.dp))
                .then(if (typing) Modifier.border(1.5.dp, AppAccent, RoundedCornerShape(9.dp)) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kg, fontSize = 13.sp, color = if (kgGhost) WELCOME_GHOST else AppTextPrimary)
                // The text cursor, so it reads as being typed.
                if (typing) Box(modifier = Modifier.padding(start = 1.dp).width(1.5.dp).height(14.dp).background(AppAccent))
            }
        }
        Box(
            modifier = Modifier.weight(1f).height(34.dp).background(AppSurfaceVariant, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) { Text(reps, fontSize = 13.sp, color = if (repsGhost) WELCOME_GHOST else AppTextPrimary) }
    }
}

/** Second card: a copy of Up next - the split's days, this week so far,
 *  and why Pull is next. */
@Composable
private fun WelcomeUpNextArt() {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "UP NEXT · PULL DAY",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = AppAccent,
                modifier = Modifier.weight(1f)
            )
            Text("6:30 PM", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppTextMuted)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Push" to 0, "Pull" to 1, "Legs" to 2).forEach { (day, kind) ->
                Text(
                    text = day,
                    fontSize = 11.sp,
                    fontWeight = if (kind == 1) FontWeight.Bold else FontWeight.Normal,
                    color = when (kind) { 1 -> AppOnAccent; 0 -> AppTextMuted; else -> AppTextSecondary },
                    modifier = Modifier
                        .background(if (kind == 1) AppAccent else AppSurfaceVariant, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        // Mon..Sun this week: trained days lit, today outlined.
        val days = listOf("M", "T", "W", "T", "F", "S", "S")
        val labels = listOf("Push", "Legs", "Push", "", "Today", "", "")
        val fills = listOf(1f, 1f, 0.55f, 0f, 0f, 0f, 0f)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            days.forEachIndexed { i, d ->
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = AppTextMuted)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(
                                if (fills[i] > 0f) AppAccent.copy(alpha = fills[i]) else AppSurfaceVariant,
                                RoundedCornerShape(5.dp)
                            )
                            .then(if (i == 4) Modifier.border(1.5.dp, AppTextPrimary, RoundedCornerShape(5.dp)) else Modifier)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(labels[i], fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = AppTextSecondary, maxLines = 1)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = buildAnnotatedString {
                append("Chest is still recovering from Wednesday, so ")
                withStyle(SpanStyle(color = AppTextPrimary, fontWeight = FontWeight.SemiBold)) { append("Pull") }
                append(" is up next.")
            },
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = AppTextSecondary
        )
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
        BodyFigure(view = BodyView.FRONT, level = { push[it] ?: 0f }, width = 80.dp, gapColor = AppSurface)
        BodyFigure(view = BodyView.BACK, level = { push[it] ?: 0f }, width = 80.dp, gapColor = AppSurface)
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
