package com.example.workoutlog_androidstudio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.workoutlog_androidstudio.api.ExerciseResponse
import com.example.workoutlog_androidstudio.api.NetworkClient
import com.example.workoutlog_androidstudio.api.NewWorkoutRequest
import com.example.workoutlog_androidstudio.api.WorkoutTemplateSummaryResponse
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The three bottom-nav destinations. Kept as an enum rather than a Boolean
 *  so tabs can keep being added later without renaming anything. */
private enum class MainTab { HOME, WORKOUTS, PROFILE }

/** Every screen reached via a chevron rather than the bottom tab bar - kept
 *  on their own back stack (see [MainActivity]'s `screenStack`) instead of
 *  each having its own independent on/off flag, so "back" always returns to
 *  whichever screen actually opened it (e.g. All Workouts -> a workout's
 *  detail -> back lands on All Workouts, not Home) instead of to one
 *  hardcoded screen. */
private sealed class Screen {
    object Calendar : Screen()
    object Targets : Screen()
    object AllWorkouts : Screen()
    data class WorkoutDetail(val workout: WorkoutSummary) : Screen()
    data class TemplateDetail(val template: WorkoutTemplateSummaryResponse) : Screen()
    data class ExerciseHistory(val exerciseId: Int, val exerciseName: String) : Screen()
}

class MainActivity : ComponentActivity() {
    // Must be registered unconditionally before the activity reaches
    // STARTED - a no-op callback either way, since a denial just means the
    // ongoing notification silently never shows; nothing else in the app
    // depends on it.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        handleIntent(intent)
        setContent {
            WorkoutLogTheme {
                // Signing out - or a login that's expired or been revoked -
                // wipes everything tied to the previous account: cached data,
                // any workout in progress, and its ongoing notification.
                LaunchedEffect(AuthManager.isSignedIn) {
                    if (!AuthManager.isSignedIn) {
                        AppDataCache.clear()
                        ActiveWorkoutState.end()
                        ActiveWorkoutNotificationService.stop(this@MainActivity)
                    }
                }
                // Nothing else in the app is reachable until someone is
                // signed in; this also drops all the screens below (and
                // their remembered state) when they sign out.
                if (!AuthManager.isSignedIn) {
                    LoginScreen()
                    return@WorkoutLogTheme
                }

                // A brand-new account fills in its details (name, age,
                // height, weight...) once before the app opens. The server
                // says which: a new account's placeholder profile comes back
                // with onboardingComplete = false.
                var startupAttempt by remember { mutableStateOf(0) }
                var startupFailed by remember { mutableStateOf(false) }
                var startupProfile by remember { mutableStateOf(AppDataCache.profile) }
                var onboardingDone by remember { mutableStateOf(AppDataCache.profile?.onboardingComplete) }
                LaunchedEffect(startupAttempt) {
                    if (onboardingDone != null) return@LaunchedEffect
                    startupFailed = false
                    try {
                        val loaded = AppDataCache.loadProfile()
                        startupProfile = loaded
                        onboardingDone = loaded.onboardingComplete
                    } catch (e: Exception) {
                        startupFailed = true
                    }
                }
                if (onboardingDone != true) {
                    val profileForOnboarding = startupProfile
                    if (onboardingDone == false && profileForOnboarding != null) {
                        OnboardingScreen(initial = profileForOnboarding, onFinished = { onboardingDone = true })
                    } else {
                        StartupStatus(failed = startupFailed, onRetry = { startupAttempt++ })
                    }
                    return@WorkoutLogTheme
                }

                // Every exercise a just-started template workout should open
                // with - set right before ActiveWorkoutState.start(...),
                // cleared once consumed (or when a plain empty workout is
                // started instead).
                var pendingPrefillExercises by remember { mutableStateOf<List<ExerciseResponse>>(emptyList()) }
                fun endActiveWorkout() {
                    ActiveWorkoutState.end()
                    pendingPrefillExercises = emptyList()
                }
                // Starts/stops the ongoing notification (ActiveWorkoutNotificationService)
                // in lockstep with there being an active workout at all - so it survives
                // minimizing exactly like ActiveWorkoutScreen itself does, and only ever
                // goes away once the workout truly ends.
                LaunchedEffect(ActiveWorkoutState.workout != null) {
                    if (ActiveWorkoutState.workout != null) {
                        ActiveWorkoutNotificationService.start(this@MainActivity)
                    } else {
                        ActiveWorkoutNotificationService.stop(this@MainActivity)
                    }
                }
                // The back stack of chevron-reached screens currently open, e.g.
                // [AllWorkouts, WorkoutDetail(x)] once you've opened All Workouts
                // and then tapped into one of its rows - only the last (top)
                // entry is ever drawn, and popping it (via a screen's back
                // chevron) reveals whichever one was under it, exactly like a
                // real navigation stack. Empty means none of these are open, so
                // the tab view underneath shows through on its own.
                var screenStack by remember { mutableStateOf<List<Screen>>(emptyList()) }
                fun push(screen: Screen) { screenStack = screenStack + screen }
                fun pop() { screenStack = screenStack.dropLast(1) }
                // Which bottom-nav tab is showing. All three tab screens stay
                // composed at all times (see the Box below) - only this flag
                // changes - so switching tabs never tears down and re-fetches any
                // of their data the way flipping an if/else branch used to.
                var selectedTab by remember { mutableStateOf(MainTab.HOME) }
                val coroutineScope = rememberCoroutineScope()

                // System back button. Handlers registered later win, so the
                // order below is lowest to highest priority: a root tab other
                // than Home goes back to Home, an open chevron screen pops
                // the stack, and a full-screen active workout is minimized
                // (never discarded) before anything underneath it.
                BackHandler(enabled = selectedTab != MainTab.HOME) { selectedTab = MainTab.HOME }
                BackHandler(enabled = screenStack.isNotEmpty()) { pop() }
                BackHandler(enabled = ActiveWorkoutState.workout != null && !ActiveWorkoutState.isMinimized) {
                    ActiveWorkoutState.isMinimized = true
                }

                Box(modifier = Modifier.fillMaxSize().background(AppBackground)) {
                    // All three tabs are always in the composition, just
                    // shifted off to the side when not selected (via
                    // graphicsLayer, computed from the box's own measured
                    // size - no magic numbers). Because none of these
                    // composables is ever removed from the tree, their
                    // remembered state - including the LaunchedEffect(Unit)
                    // that fetches workouts/templates/profile once - survives
                    // switching tabs, so it's never re-fetched.
                    //
                    // Crucially, this whole Box (tabs + floating tab bar) sits
                    // OUTSIDE the `when` below over screenStack's top entry -
                    // whichever chevron-reached screen that is gets drawn as an
                    // overlay ON TOP of it instead of replacing it, so
                    // opening a workout's detail, a template's detail, the
                    // calendar, Targets, or All Workouts (all the screens
                    // reached via a chevron rather than the tab bar) never tears
                    // this Box down either. Before this, that if/else was the
                    // parent of this whole Box, so every one of those chevron
                    // screens disposed Home/Workouts/Profile entirely - that's
                    // exactly what made them look like they reloaded from
                    // scratch every time you came back from one.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = if (selectedTab == MainTab.HOME) 0f else size.width * 2
                            }
                    ) {
                        HomeScreen(
                            onStartWorkout = { workoutName ->
                                coroutineScope.launch {
                                    // Belt-and-suspenders guard behind the disabled
                                    // Quick Start button - a second workout can't
                                    // start while ActiveWorkoutState already holds one.
                                    if (ActiveWorkoutState.workout != null) return@launch
                                    try {
                                        val started = NetworkClient.workoutApi.startWorkout(
                                            NewWorkoutRequest(workoutName = workoutName)
                                        )
                                        Log.d("StartWorkout", "Created workout: $started")
                                        pendingPrefillExercises = emptyList()
                                        ActiveWorkoutState.start(started)
                                    } catch (e: Exception) {
                                        Log.e("StartWorkout", "Failed to start workout", e)
                                    }
                                }
                            },
                            onOpenWorkout = { tapped -> push(Screen.WorkoutDetail(tapped)) },
                            onOpenCalendar = { push(Screen.Calendar) },
                            onOpenAllWorkouts = { push(Screen.AllWorkouts) }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = if (selectedTab == MainTab.WORKOUTS) 0f else size.width * 2
                            }
                    ) {
                        WorkoutsScreen(
                            onOpenTemplate = { tapped -> push(Screen.TemplateDetail(tapped)) }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = if (selectedTab == MainTab.PROFILE) 0f else size.width * 2
                            }
                    ) {
                        ProfileScreen(
                            onOpenTargets = { push(Screen.Targets) }
                        )
                    }

                    FloatingTabBar(
                        selectedTab = selectedTab,
                        onSelectTab = { selectedTab = it },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 16.dp)
                    )

                    // Whichever chevron-reached screen is on top of the stack,
                    // drawn on top of the tab view above (each of these
                    // already fills the screen with its own opaque
                    // background) rather than replacing it - see the
                    // comment above. Its own onBack only ever pops the
                    // stack, so it reveals whatever was open before it
                    // rather than jumping to a single hardcoded screen.
                    when (val top = screenStack.lastOrNull()) {
                        is Screen.WorkoutDetail -> {
                            WorkoutDetailScreen(
                                workout = top.workout,
                                onBack = { pop() },
                                onOpenExerciseHistory = { exerciseId, exerciseName ->
                                    push(Screen.ExerciseHistory(exerciseId, exerciseName))
                                }
                            )
                        }
                        is Screen.ExerciseHistory -> {
                            ExerciseHistoryScreen(
                                exerciseId = top.exerciseId,
                                exerciseName = top.exerciseName,
                                onBack = { pop() }
                            )
                        }
                        is Screen.TemplateDetail -> {
                            WorkoutTemplateDetailScreen(
                                templateSummary = top.template,
                                onBack = { pop() },
                                onStartWorkout = { templateDetail ->
                                    coroutineScope.launch {
                                        // Same belt-and-suspenders guard as onStartWorkout
                                        // above, behind StartWorkoutButton's own disabled state.
                                        if (ActiveWorkoutState.workout != null) return@launch
                                        try {
                                            val started = NetworkClient.workoutApi.startWorkout(
                                                NewWorkoutRequest(workoutName = templateDetail.name)
                                            )
                                            Log.d("StartWorkout", "Created workout from template: $started")
                                            pendingPrefillExercises = templateDetail.exercises
                                            // Starting a workout leaves this whole chevron
                                            // stack behind, not just its top entry - when the
                                            // workout ends you land back on the tab view.
                                            screenStack = emptyList()
                                            ActiveWorkoutState.start(started)
                                        } catch (e: Exception) {
                                            Log.e("StartWorkout", "Failed to start workout from template", e)
                                        }
                                    }
                                }
                            )
                        }
                        Screen.Calendar -> {
                            WorkoutCalendarScreen(
                                onBack = { pop() },
                                onOpenWorkout = { tapped -> push(Screen.WorkoutDetail(tapped)) }
                            )
                        }
                        Screen.Targets -> {
                            TargetsScreen(onBack = { pop() })
                        }
                        Screen.AllWorkouts -> {
                            AllWorkoutsScreen(
                                onBack = { pop() },
                                onOpenWorkout = { tapped -> push(Screen.WorkoutDetail(tapped)) }
                            )
                        }
                        null -> Unit
                    }

                    // ActiveWorkoutScreen itself - presented like an iOS modal,
                    // easing up from the bottom over the tabs when a workout
                    // starts and back down when it truly ends. In between, while
                    // merely minimized rather than ended, it is NOT removed from
                    // composition (AnimatedVisibility only toggles on start/end,
                    // driven by activeWorkout itself) - it just gets slid off the
                    // bottom of the screen via graphicsLayer, exactly like the
                    // tabs above are slid off to the side. That's what lets its
                    // timer and pause state (now hoisted above) and its
                    // exercises/notes keep running untouched while minimized.
                    AnimatedVisibility(
                        visible = ActiveWorkoutState.workout != null,
                        enter = slideInVertically(
                            initialOffsetY = { fullHeight -> fullHeight },
                            animationSpec = tween(350, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(250)),
                        exit = fadeOut(animationSpec = tween(200)) + slideOutVertically(
                            targetOffsetY = { fullHeight -> fullHeight },
                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                        )
                    ) {
                        val workout = ActiveWorkoutState.workout
                        if (workout != null) {
                            val minimizeProgress by animateFloatAsState(
                                targetValue = if (ActiveWorkoutState.isMinimized) 1f else 0f,
                                animationSpec = tween(300, easing = FastOutSlowInEasing),
                                label = "workoutMinimizeProgress"
                            )
                            ActiveWorkoutScreen(
                                workout = workout,
                                elapsedSeconds = ActiveWorkoutState.elapsedSeconds,
                                onTick = { ActiveWorkoutState.elapsedSeconds++ },
                                isPaused = ActiveWorkoutState.isPaused,
                                onTogglePause = { ActiveWorkoutState.isPaused = !ActiveWorkoutState.isPaused },
                                prefillExercises = pendingPrefillExercises,
                                onMinimize = { ActiveWorkoutState.isMinimized = true },
                                onClose = { endActiveWorkout() },
                                onEndWorkout = { notes ->
                                    // ActiveWorkoutScreen has already synced the sets and
                                    // notes to the backend by the time this fires - this
                                    // just closes the screen.
                                    Log.d("EndWorkout", "Ended workout ${workout.id}: $notes")
                                    endActiveWorkout()
                                },
                                modifier = Modifier.graphicsLayer {
                                    translationY = size.height * minimizeProgress
                                }
                            )
                        }
                    }

                    // The draggable floating mini-player - only present once a
                    // workout is both active and minimized, drawn last so it
                    // floats above the tabs, any chevron screen, and everything
                    // else. Tapping it (anywhere but the pause button) restores
                    // the full ActiveWorkoutScreen.
                    val minimizedWorkout = ActiveWorkoutState.workout
                    if (minimizedWorkout != null && ActiveWorkoutState.isMinimized) {
                        FloatingActiveWorkoutBar(
                            workoutName = minimizedWorkout.workoutName,
                            elapsedSeconds = ActiveWorkoutState.elapsedSeconds,
                            isPaused = ActiveWorkoutState.isPaused,
                            onTogglePause = { ActiveWorkoutState.isPaused = !ActiveWorkoutState.isPaused },
                            onRestore = { ActiveWorkoutState.isMinimized = false }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** A notification tap arrives here as [ActiveWorkoutNotificationService.ACTION_RESTORE] -
     *  just un-minimizes, so ActiveWorkoutScreen (already alive - see the
     *  AnimatedVisibility above) comes back to the front. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ActiveWorkoutNotificationService.ACTION_RESTORE) {
            ActiveWorkoutState.isMinimized = false
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/** A single floating pill - not a full-width Material NavigationBar - holding
 *  the tab icons side by side. A soft rounded highlight slides behind
 *  whichever icon is selected.
 *
 *  Both the highlight and the icons are positioned from the same two
 *  numbers - [slotWidth] and [indicatorSize] - instead of the highlight's
 *  position being measured off the icon's real on-screen bounds; computing
 *  both from one shared layout guarantees they always land in the same
 *  place. The indicator (46dp) is taller than the icon row (24dp), so both
 *  are explicitly centered with the same [Alignment.CenterStart] - leaving
 *  the Row at its default top-aligned placement, while the taller indicator
 *  sits centered, was exactly what made them look misaligned before. */
@Composable
private fun FloatingTabBar(
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val slotWidth = 64.dp
    val indicatorSize = 46.dp

    val targetOffsetX = (slotWidth * selectedTab.ordinal) + (slotWidth - indicatorSize) / 2
    val animatedOffsetX by animateDpAsState(
        targetValue = targetOffsetX,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tabIndicatorX"
    )

    Box(
        modifier = modifier
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(36.dp),
                ambientColor = Color.Black.copy(alpha = 0.7f),
                spotColor = Color.Black.copy(alpha = 0.7f)
            )
            .clip(RoundedCornerShape(36.dp))
            .background(AppSurface)
            .border(1.dp, AppBorder, RoundedCornerShape(36.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        // The sliding highlight, drawn behind the Row of icons below it.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = animatedOffsetX)
                .size(indicatorSize)
                .background(AppSurfaceVariant, CircleShape)
        )

        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabBarItem(
                label = "Home",
                icon = Icons.Filled.Home,
                selected = selectedTab == MainTab.HOME,
                onClick = { onSelectTab(MainTab.HOME) },
                modifier = Modifier.width(slotWidth)
            )
            TabBarItem(
                label = "Workouts",
                icon = Icons.Filled.FitnessCenter,
                selected = selectedTab == MainTab.WORKOUTS,
                onClick = { onSelectTab(MainTab.WORKOUTS) },
                modifier = Modifier.width(slotWidth)
            )
            TabBarItem(
                label = "Profile",
                icon = Icons.Filled.Person,
                selected = selectedTab == MainTab.PROFILE,
                onClick = { onSelectTab(MainTab.PROFILE) },
                modifier = Modifier.width(slotWidth)
            )
        }
    }
}

@Composable
private fun TabBarItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = if (selected) AppAccent else AppTextMuted
    Box(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}

/** The floating mini-player shown once an active workout is minimized -
 *  the workout's name, its live timer, and a pause/resume button, all in
 *  a small pill that can be dragged anywhere on screen (the drag offset is
 *  plain unclamped pixels, so it's free to be parked wherever is out of the
 *  way) and hovers above every other screen in the app. Tapping it anywhere
 *  outside the pause button restores the full ActiveWorkoutScreen. */
@Composable
private fun FloatingActiveWorkoutBar(
    workoutName: String,
    elapsedSeconds: Int,
    isPaused: Boolean,
    onTogglePause: () -> Unit,
    onRestore: () -> Unit
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .statusBarsPadding()
            .padding(top = 16.dp, start = 16.dp, end = 16.dp)
            .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = Color.Black.copy(alpha = 0.7f),
                spotColor = Color.Black.copy(alpha = 0.7f)
            )
            .clip(RoundedCornerShape(28.dp))
            .background(AppSurface)
            .border(1.dp, AppBorder, RoundedCornerShape(28.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    dragOffset += dragAmount
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onRestore
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(AppAccent, CircleShape)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = workoutName,
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTextPrimary,
                    maxLines = 1
                )
                Text(
                    text = formatElapsed(elapsedSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            IconButton(
                onClick = onTogglePause,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (isPaused) "Resume" else "Pause",
                    tint = AppAccent
                )
            }
        }
    }
}
