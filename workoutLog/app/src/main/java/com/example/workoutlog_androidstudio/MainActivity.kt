package com.example.workoutlog_androidstudio

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.workoutlog_androidstudio.api.ExerciseResponse
import com.example.workoutlog_androidstudio.api.NetworkClient
import com.example.workoutlog_androidstudio.api.NewWorkoutRequest
import com.example.workoutlog_androidstudio.api.WorkoutResponse
import com.example.workoutlog_androidstudio.api.WorkoutTemplateSummaryResponse
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
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WorkoutLogTheme {
                // null = no active workout (show the tabs); non-null = the real
                // workout returned by the server (show ActiveWorkoutScreen for it).
                var activeWorkout by remember { mutableStateOf<WorkoutResponse?>(null) }
                // Every exercise a just-started template workout should open
                // with - set right before activeWorkout, cleared once consumed
                // (or when a plain empty workout is started instead).
                var pendingPrefillExercises by remember { mutableStateOf<List<ExerciseResponse>>(emptyList()) }
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

                // Presents ActiveWorkoutScreen/WorkoutDetailScreen like an iOS modal -
                // it eases up from the bottom of the screen over the tabs, and eases
                // back down when closed, instead of just abruptly swapping in and out.
                AnimatedContent(
                    targetState = activeWorkout,
                    transitionSpec = {
                        if (targetState != null) {
                            (slideInVertically(
                                initialOffsetY = { fullHeight -> fullHeight },
                                animationSpec = tween(350, easing = FastOutSlowInEasing)
                            ) + fadeIn(animationSpec = tween(250))) togetherWith
                                fadeOut(animationSpec = tween(200))
                        } else {
                            fadeIn(animationSpec = tween(200)) togetherWith
                                (slideOutVertically(
                                    targetOffsetY = { fullHeight -> fullHeight },
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(200)))
                        }.using(SizeTransform(clip = false))
                    },
                    label = "HomeToActiveWorkout"
                ) { workout ->
                    if (workout != null) {
                        ActiveWorkoutScreen(
                            workout = workout,
                            prefillExercises = pendingPrefillExercises,
                            onClose = {
                                activeWorkout = null
                                pendingPrefillExercises = emptyList()
                            },
                            onEndWorkout = { notes ->
                                // ActiveWorkoutScreen has already synced the sets and
                                // notes to the backend by the time this fires - this
                                // just closes the screen.
                                Log.d("EndWorkout", "Ended workout ${workout.id}: $notes")
                                activeWorkout = null
                                pendingPrefillExercises = emptyList()
                            }
                        )
                    } else {
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
                                            try {
                                                val started = NetworkClient.workoutApi.startWorkout(
                                                    NewWorkoutRequest(workoutName = workoutName)
                                                )
                                                Log.d("StartWorkout", "Created workout: $started")
                                                pendingPrefillExercises = emptyList()
                                                activeWorkout = started
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
                                        onBack = { pop() }
                                    )
                                }
                                is Screen.TemplateDetail -> {
                                    WorkoutTemplateDetailScreen(
                                        templateSummary = top.template,
                                        onBack = { pop() },
                                        onStartWorkout = { templateDetail ->
                                            coroutineScope.launch {
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
                                                    activeWorkout = started
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
                        }
                    }
                }
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
