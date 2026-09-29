package com.example.workoutlog_androidstudio

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.example.workoutlog_androidstudio.api.NetworkClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

private const val DEFAULT_WEEKLY_TARGET_DAYS = 6

private fun isInCurrentWeek(isoDate: String): Boolean {
    val date = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return false
    val today = LocalDate.now()
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val sunday = monday.plusDays(6)
    return !date.isBefore(monday) && !date.isAfter(sunday)
}

@Composable
fun HomeScreen(
    onStartWorkout: (workoutName: String) -> Unit,
    onOpenWorkout: (WorkoutSummary) -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenAllWorkouts: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showNewWorkoutDialog by remember { mutableStateOf(false) }

    var previousWorkouts by remember { mutableStateOf(AppDataCache.workoutSummaries ?: emptyList()) }
    var isLoadingPrevious by remember { mutableStateOf(AppDataCache.workoutSummaries == null) }
    var previousWorkoutsError by remember { mutableStateOf<String?>(null) }

    var weeklyWorkoutTarget by remember { mutableStateOf(AppDataCache.profile?.weeklyWorkoutTarget ?: DEFAULT_WEEKLY_TARGET_DAYS) }

    var workoutPendingDelete by remember { mutableStateOf<WorkoutSummary?>(null) }
    val coroutineScope = rememberCoroutineScope()

    var deletedWorkoutTitle by remember { mutableStateOf<String?>(null) }
    var toastVisible by remember { mutableStateOf(false) }
    LaunchedEffect(toastVisible) {
        if (toastVisible) {
            delay(2500)
            toastVisible = false
        }
    }

    LaunchedEffect(Unit) {
        try {
            previousWorkouts = AppDataCache.loadWorkoutSummaries()
        } catch (e: Exception) {
            previousWorkoutsError = "Couldn't load previous workouts. Check your connection."
        } finally {
            isLoadingPrevious = false
        }
    }

    LaunchedEffect(Unit) {
        try {
            weeklyWorkoutTarget = AppDataCache.loadProfile().weeklyWorkoutTarget
        } catch (e: Exception) {

        }
    }

    val thisWeekWorkouts = remember(previousWorkouts) {
        previousWorkouts.filter { isInCurrentWeek(it.date) }
    }

    val completedDaysThisWeek = remember(thisWeekWorkouts, weeklyWorkoutTarget) {
        thisWeekWorkouts.map { it.date }.distinct().size.coerceAtMost(weeklyWorkoutTarget)
    }

    var dateLabel by remember { mutableStateOf("") }
    var timeLabel by remember { mutableStateOf("") }
    var weekOfYear by remember { mutableStateOf(0) }
    var dayOfWeekNumber by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            val calendar = Calendar.getInstance()
            dateLabel = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(calendar.time).uppercase()
            timeLabel = SimpleDateFormat("h:mm a", Locale.getDefault()).format(calendar.time)
            weekOfYear = calendar.get(Calendar.WEEK_OF_YEAR)

            val day = calendar.get(Calendar.DAY_OF_WEEK)
            dayOfWeekNumber = if (day == Calendar.SUNDAY) 7 else day - 1
            delay(1000)
        }
    }

    NewWorkoutDialog(
        visible = showNewWorkoutDialog,
        onConfirm = { name ->
            showNewWorkoutDialog = false
            onStartWorkout(name)
        },
        onDismiss = { showNewWorkoutDialog = false }
    )

    Box(modifier = modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
            .padding(horizontal = 20.dp),

        contentPadding = PaddingValues(top = 24.dp, bottom = 110.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = AppAccent
                )
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = AppAccent
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "WEEK $weekOfYear/52 // DAY $dayOfWeekNumber",
                style = MaterialTheme.typography.labelSmall,
                color = AppAccent
            )
            Spacer(modifier = Modifier.height(20.dp))
            WeeklyTargetWidget(completedDays = completedDaysThisWeek, targetDays = weeklyWorkoutTarget)
            Spacer(modifier = Modifier.height(20.dp))
            QuickStartCard(onStartEmptyWorkout = { showNewWorkoutDialog = true })
            Spacer(modifier = Modifier.height(28.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAllWorkouts),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Previous Workouts",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppTextPrimary
                )
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = "View all workouts",
                    tint = AppTextSecondary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        when {
            isLoadingPrevious -> {
                items(2) {
                    SkeletonWorkoutCard()
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
            previousWorkoutsError != null -> {
                item {
                    Text(
                        text = previousWorkoutsError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary
                    )
                }
            }
            thisWeekWorkouts.isEmpty() -> {
                item {
                    Text(
                        text = "No workouts logged this week yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary
                    )
                }
            }
            else -> {
                items(thisWeekWorkouts, key = { it.id }) { workout ->
                    SwipeToDeleteWorkoutCard(
                        workout = workout,
                        onClick = { onOpenWorkout(workout) },
                        onRequestDelete = { workoutPendingDelete = workout }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(28.dp))
            ConsistencyHeatmap(workouts = previousWorkouts, onOpenCalendar = onOpenCalendar)
        }
    }

    workoutPendingDelete?.let { workout ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { workoutPendingDelete = null }
        )
        DeleteConfirmationPopup(
            title = "Delete workout?",
            message = "\"${workout.title}\" will be permanently deleted. This can't be undone.",
            onCancel = { workoutPendingDelete = null },
            onConfirm = {
                workoutPendingDelete = null

                previousWorkouts = previousWorkouts.filterNot { it.id == workout.id }
                AppDataCache.removeWorkout(workout.id)
                deletedWorkoutTitle = workout.title
                toastVisible = true
                coroutineScope.launch {
                    try {
                        NetworkClient.workoutApi.deleteWorkout(workout.id)
                    } catch (e: Exception) {
                        previousWorkouts = (previousWorkouts + workout)
                            .sortedByDescending { it.id }
                        AppDataCache.addWorkout(workout)
                        toastVisible = false
                    }
                }
            }
        )
    }

    DeletedToast(visible = toastVisible, message = "\"${deletedWorkoutTitle.orEmpty()}\" deleted")
    }
}

@Composable
private fun NewWorkoutDialog(
    visible: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }

    AppBottomSheet(visible = visible, onDismissRequest = onDismiss) {
        Text(
            text = "Name your workout",
            style = MaterialTheme.typography.titleLarge,
            color = AppTextPrimary
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(text = "e.g. Push Day", color = AppTextMuted) },
            colors = appTextFieldColors()
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
            enabled = name.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black
            )
        ) {
            Text(text = "Start", fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Cancel", color = AppTextSecondary)
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun WeeklyTargetWidget(completedDays: Int, targetDays: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurface, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "WEEKLY TARGET",
                style = MaterialTheme.typography.labelSmall,
                color = AppAccent,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$completedDays of $targetDays Completed",
                style = MaterialTheme.typography.titleMedium,
                color = AppTextPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            repeat(targetDays) { index ->
                Box(
                    modifier = Modifier
                        .width(7.dp)
                        .height(30.dp)
                        .background(
                            if (index < completedDays) AppTextPrimary else AppSurfaceVariant,
                            RoundedCornerShape(3.dp)
                        )
                )
            }
        }
    }
}

@Composable
private fun QuickStartCard(onStartEmptyWorkout: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurface, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Quick Start",
                style = MaterialTheme.typography.headlineSmall,
                color = AppTextPrimary
            )
            IconButton(onClick = { }) {
                Icon(
                    imageVector = Icons.Filled.OpenInFull,
                    contentDescription = null,
                    tint = AppTextSecondary
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Empty slate. Direct logging of load, target sets, micro-plates & instantaneous rest intervals.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = onStartEmptyWorkout,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black
            )
        ) {
            Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Start Empty Workout", fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteWorkoutCard(
    workout: WorkoutSummary,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { targetValue ->
            if (targetValue == SwipeToDismissBoxValue.EndToStart) {
                onRequestDelete()
            }

            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(AppDanger)
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete workout",
                    tint = Color.White
                )
            }
        }
    ) {
        WorkoutCard(workout = workout, onClick = onClick)
    }
}

@Composable
private fun WorkoutCard(workout: WorkoutSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(AppSurface, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(AppAccent, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = workout.dateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppAccent
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = workout.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppTextPrimary
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = AppTextSecondary
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatColumn(label = "DURATION", value = workout.duration, modifier = Modifier.weight(1f))
            StatColumn(label = "TOTAL SETS", value = workout.totalSets.toString(), modifier = Modifier.weight(1f))
            StatColumn(label = "VOLUME", value = workout.volumeLabel, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(14.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            workout.tags.forEach { tag -> TagChip(text = tag) }
        }
    }
}

@Composable
private fun rememberShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = -400f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )
    return Brush.linearGradient(
        colors = listOf(
            AppSurfaceVariant.copy(alpha = 0.55f),
            AppSurfaceVariant.copy(alpha = 0.15f),
            AppSurfaceVariant.copy(alpha = 0.55f)
        ),
        start = Offset(translateAnim, 0f),
        end = Offset(translateAnim + 300f, 300f)
    )
}

@Composable
private fun ShimmerBlock(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, brush: Brush) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(brush, RoundedCornerShape(4.dp))
    )
}

@Composable
private fun SkeletonWorkoutCard() {
    val brush = rememberShimmerBrush()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurface, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).background(brush, CircleShape))
                    Spacer(modifier = Modifier.width(6.dp))
                    ShimmerBlock(width = 64.dp, height = 12.dp, brush = brush)
                }
                Spacer(modifier = Modifier.height(6.dp))
                ShimmerBlock(width = 150.dp, height = 20.dp, brush = brush)
            }
            Box(modifier = Modifier.size(20.dp).background(brush, RoundedCornerShape(4.dp)))
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            repeat(3) { index ->
                Column(modifier = Modifier.weight(1f)) {
                    ShimmerBlock(width = 46.dp, height = 10.dp, brush = brush)
                    Spacer(modifier = Modifier.height(6.dp))
                    ShimmerBlock(width = 36.dp, height = 16.dp, brush = brush)
                }
                if (index < 2) Spacer(modifier = Modifier.width(8.dp))
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShimmerBlock(width = 92.dp, height = 26.dp, brush = brush)
            ShimmerBlock(width = 74.dp, height = 26.dp, brush = brush)
        }
    }
}

@Composable
private fun ConsistencyHeatmap(workouts: List<WorkoutSummary>, onOpenCalendar: () -> Unit) {
    val today = remember { LocalDate.now() }
    val nextMonthAnchor = remember(today) { today.plusMonths(1) }

    val exerciseCountByDate = remember(workouts) {
        workouts
            .mapNotNull { workout ->
                runCatching { LocalDate.parse(workout.date) }.getOrNull()?.let { it to workout.exerciseCount }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, counts) -> counts.sum() }
    }

    var tooltip by remember { mutableStateOf<HeatmapTooltipInfo?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenCalendar),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Consistency",
                style = MaterialTheme.typography.headlineSmall,
                color = AppTextPrimary
            )
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Open workout log & calendar",
                tint = AppTextSecondary
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MonthGrid(
                monthAnchor = today,
                exerciseCountByDate = exerciseCountByDate,
                onCellTap = { date, count, bounds -> tooltip = HeatmapTooltipInfo(date, count, bounds) },
                modifier = Modifier.weight(1f)
            )
            MonthGrid(
                monthAnchor = nextMonthAnchor,
                exerciseCountByDate = exerciseCountByDate,
                onCellTap = { date, count, bounds -> tooltip = HeatmapTooltipInfo(date, count, bounds) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.align(Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Less", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
            Spacer(modifier = Modifier.width(6.dp))
            listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f).forEach { alpha ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(10.dp)
                        .background(
                            if (alpha == 0f) AppSurfaceVariant else AppAccent.copy(alpha = alpha),
                            RoundedCornerShape(3.dp)
                        )
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "More", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
        }
    }

    tooltip?.let { info ->
        HeatmapTooltipPopup(info = info, onDismiss = { tooltip = null })
    }
}

private data class HeatmapTooltipInfo(val date: LocalDate, val exerciseCount: Int, val anchorBounds: Rect)

@Composable
private fun HeatmapTooltipPopup(info: HeatmapTooltipInfo, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val gapPx = with(density) { 6.dp.toPx() }
    val edgeMarginPx = with(density) { 8.dp.toPx() }

    Popup(
        popupPositionProvider = remember(info.anchorBounds) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize
                ): IntOffset {
                    val cellCenterX = (info.anchorBounds.left + info.anchorBounds.right) / 2f
                    val minX = edgeMarginPx
                    val maxX = (windowSize.width - popupContentSize.width - edgeMarginPx).coerceAtLeast(minX)
                    val x = (cellCenterX - popupContentSize.width / 2f).coerceIn(minX, maxX)

                    val yAbove = info.anchorBounds.top - popupContentSize.height - gapPx
                    val y = if (yAbove >= 0f) yAbove else info.anchorBounds.bottom + gapPx

                    return IntOffset(x.roundToInt(), y.roundToInt())
                }
            }
        },
        onDismissRequest = onDismiss
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .background(AppSurfaceVariant, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = heatmapTooltipLabel(info.date, info.exerciseCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTextPrimary
                )
            }
            Canvas(modifier = Modifier.size(width = 12.dp, height = 6.dp)) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
                drawPath(path, color = AppSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MonthGrid(
    monthAnchor: LocalDate,
    exerciseCountByDate: Map<LocalDate, Int>,
    onCellTap: (LocalDate, Int, Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val weeks = remember(monthAnchor) { weeksOfMonth(monthAnchor) }

    Column(modifier = modifier) {
        Text(
            text = monthAnchor.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AppTextMuted
        )
        Spacer(modifier = Modifier.height(8.dp))
        weeks.forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                week.forEach { date ->
                    val inMonth = date.month == monthAnchor.month && date.year == monthAnchor.year
                    val exerciseCount = exerciseCountByDate[date] ?: 0
                    HeatmapCell(
                        inCurrentMonth = inMonth,
                        exerciseCount = exerciseCount,
                        onTap = { bounds -> onCellTap(date, exerciseCount, bounds) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun HeatmapCell(inCurrentMonth: Boolean, exerciseCount: Int, onTap: (Rect) -> Unit, modifier: Modifier = Modifier) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .onGloballyPositioned { coordinates = it }
            .clickable { coordinates?.let { onTap(it.boundsInWindow()) } }
            .background(heatmapCellColor(inCurrentMonth, exerciseCount), RoundedCornerShape(4.dp))
    )
}
