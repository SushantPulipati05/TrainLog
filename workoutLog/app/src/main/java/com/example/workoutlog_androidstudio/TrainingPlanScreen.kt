package com.example.workoutlog_androidstudio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val LEAD_OPTIONS = listOf(0, 15, 30, 60)

/** A sensible starting plan: PPL at 6:30 PM on the first N days of the week,
 *  N being the weekly target from the profile (6 if unknown). */
private fun defaultPlan(): TrainingPlan {
    val target = (AppDataCache.profile?.weeklyWorkoutTarget ?: 6).coerceIn(1, 7)
    return TrainingPlan(trainingDays = (1..target).toSet())
}

/**
 * Profile -> Training plan. What's up next and the week's Train/Rest days
 * (tap to toggle), the split / gym time, and the reminder settings. Every change is
 * saved straight away and re-schedules the reminder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingPlanScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val saved = TrainingPlanStore.plan
    val plan = saved ?: remember { defaultPlan() }
    fun update(newPlan: TrainingPlan) = TrainingPlanStore.save(context, newPlan)

    var showSplitDialog by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }
    var showTimeDialog by remember { mutableStateOf(false) }
    var splitDayBeingLinked by remember { mutableStateOf<String?>(null) }

    // Templates, for linking each split day to the one it starts from.
    LaunchedEffect(Unit) { runCatching { AppDataCache.loadTemplates() } }
    val templates = AppDataCache.templates ?: emptyList()

    // Android 13+ needs the user's OK before any notification can show.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val today = LocalDate.now().dayOfWeek
    val workouts = AppDataCache.workoutSummaries ?: emptyList()
    val upNext = remember(workouts, plan) { computeUpNext(workouts, plan) }
    val explanation = remember(workouts, plan) { explainUpNext(workouts, plan) }
    var showWhy by remember { mutableStateOf(false) }

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
            BackChevronRow(onBack = onBack, label = "PROFILE")
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Training plan",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Pick your split and gym time, and get a reminder before you go.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary
            )

            // ---- This week: up next + which days you train ----
            Spacer(modifier = Modifier.height(20.dp))
            PlanCard {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("THIS WEEK", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
                    Text(
                        text = when {
                            upNext != null -> "UP NEXT · ${upNext.display.uppercase()}"
                            plan.isTrainingDay(today) -> "TODAY · TRAINING"
                            else -> "TODAY · REST"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = AppAccent,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        val training = plan.isTrainingDay(day)
                        WeekDayCell(
                            day = day,
                            training = training,
                            isToday = day == today,
                            onClick = {
                                val days = if (training) plan.trainingDays - day.value else plan.trainingDays + day.value
                                update(plan.copy(trainingDays = days))
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap a day to switch it between Train and Rest. Up next is worked out from the muscles you trained: the most-rested day that's had 2+ days to recover.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted
                )
            }

            // ---- Why this pick: how each recent workout was read ----
            if (explanation != null) {
                Spacer(modifier = Modifier.height(12.dp))
                UpNextWhyCard(explanation = explanation, expanded = showWhy, onToggle = { showWhy = !showWhy })
            }

            // ---- Split, days, time ----
            Spacer(modifier = Modifier.height(12.dp))
            PlanCard(verticalPadding = 2.dp) {
                PlanRow(label = "Split", value = plan.split.label, onClick = { showSplitDialog = true })
                PlanDivider()
                PlanRow(
                    label = "Gym time",
                    value = formatGymTime(context, plan.hour, plan.minute),
                    onClick = { showTimeDialog = true }
                )
            }

            // ---- Workout for each split day ----
            val splitDays = plan.orderedDays().distinct()
            if (splitDays.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                PlanCard(verticalPadding = 2.dp) {
                    Text(
                        text = if (plan.hasCustomOrder()) "WORKOUT FOR EACH DAY · YOUR ORDER" else "WORKOUT FOR EACH DAY",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextMuted,
                        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)
                    )
                    splitDays.forEachIndexed { index, label ->
                        if (index > 0) PlanDivider()
                        val templateId = plan.templateIdFor(label, templates)
                        PlanRow(
                            label = label,
                            value = templates.firstOrNull { it.id == templateId }?.name ?: "Empty workout",
                            onClick = { splitDayBeingLinked = label }
                        )
                    }
                    if (plan.hasCustomOrder()) {
                        PlanDivider()
                        PlanRow(
                            label = "Use the split's order",
                            value = orderLabel(plan.splitDays().distinct()),
                            onClick = { update(plan.copy(dayOrder = emptyList())) }
                        )
                    }
                }
            }

            // ---- Reminder ----
            Spacer(modifier = Modifier.height(12.dp))
            PlanCard(verticalPadding = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.NotificationsActive,
                        contentDescription = null,
                        tint = AppAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Gym reminder",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = saved != null && plan.remindersEnabled,
                        onCheckedChange = { on ->
                            if (on) ensureNotificationPermission()
                            update(plan.copy(remindersEnabled = on))
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AppOnAccent,
                            checkedTrackColor = AppAccent,
                            uncheckedThumbColor = AppTextMuted,
                            uncheckedTrackColor = AppSurfaceVariant,
                            uncheckedBorderColor = AppBorder
                        )
                    )
                }
                PlanDivider()
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    Text("Remind me", style = MaterialTheme.typography.bodyLarge, color = AppTextPrimary)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LEAD_OPTIONS.forEach { minutes ->
                            LeadChip(
                                text = if (minutes == 0) "At time" else if (minutes == 60) "1 hr" else "$minutes min",
                                selected = plan.leadMinutes == minutes,
                                onClick = { update(plan.copy(leadMinutes = minutes)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = if (saved == null) {
                    "Turn on the gym reminder (or change anything above) to save your plan."
                } else {
                    "Reminders come from your phone, so they work offline. If you've already finished a workout that day, you won't get one."
                },
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // ---- Dialogs ----
    if (showSplitDialog) {
        ChoiceDialog(
            title = "Your split",
            onDismiss = { showSplitDialog = false }
        ) {
            TrainingSplit.entries.forEach { split ->
                ChoiceRow(
                    title = split.label,
                    subtitle = if (split.days.isNotEmpty()) split.days.joinToString(" · ") else split.cycleLabel,
                    selected = plan.split == split,
                    onClick = {
                        showSplitDialog = false
                        if (split == TrainingSplit.CUSTOM) {
                            showCustomDialog = true
                        } else {
                            update(plan.copy(split = split, dayOrder = emptyList()))
                        }
                    }
                )
            }
        }
    }

    if (showCustomDialog) {
        var text by remember { mutableStateOf(plan.customDays.joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            containerColor = AppSurface,
            title = { Text("Name your days", color = AppTextPrimary) },
            text = {
                Column {
                    Text(
                        text = "In the order you do them, separated by commas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("e.g. Chest, Back, Legs, Arms") },
                        colors = appTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                val names = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                TextButton(
                    enabled = names.isNotEmpty(),
                    onClick = {
                        showCustomDialog = false
                        update(plan.copy(split = TrainingSplit.CUSTOM, customDays = names, dayOrder = emptyList()))
                    }
                ) { Text("Save", color = if (names.isNotEmpty()) AppAccent else AppTextMuted) }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) { Text("Cancel", color = AppTextSecondary) }
            }
        )
    }

    if (showTimeDialog) {
        val state = rememberTimePickerState(
            initialHour = plan.hour,
            initialMinute = plan.minute,
            is24Hour = android.text.format.DateFormat.is24HourFormat(context)
        )
        AlertDialog(
            onDismissRequest = { showTimeDialog = false },
            containerColor = AppSurface,
            title = { Text("When do you head to the gym?", color = AppTextPrimary, style = MaterialTheme.typography.titleMedium) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    showTimeDialog = false
                    update(plan.copy(hour = state.hour, minute = state.minute))
                }) { Text("Save", color = AppAccent) }
            },
            dismissButton = {
                TextButton(onClick = { showTimeDialog = false }) { Text("Cancel", color = AppTextSecondary) }
            }
        )
    }

    splitDayBeingLinked?.let { label ->
        val currentId = plan.templateIdFor(label, templates)
        ChoiceDialog(title = "$label starts from", onDismiss = { splitDayBeingLinked = null }) {
            if (templates.isEmpty()) {
                Text(
                    text = "No templates yet - make one in the Workouts tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            templates.forEach { template ->
                ChoiceRow(
                    title = template.name,
                    subtitle = template.category,
                    selected = template.id == currentId,
                    onClick = {
                        splitDayBeingLinked = null
                        update(plan.copy(templateIds = plan.templateIds + (label to template.id)))
                    }
                )
            }
            ChoiceRow(
                title = "Empty workout",
                subtitle = "Start with nothing and add exercises as you go",
                selected = currentId == null,
                onClick = {
                    splitDayBeingLinked = null
                    update(plan.copy(templateIds = plan.templateIds + (label to EMPTY_WORKOUT)))
                }
            )
        }
    }
}

@Composable
private fun PlanCard(verticalPadding: androidx.compose.ui.unit.Dp = 14.dp, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurface, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = verticalPadding)
    ) { content() }
}

@Composable
private fun PlanDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppBorder)
    )
}

@Composable
private fun PlanRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = AppTextPrimary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = AppTextSecondary)
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AppTextMuted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun WeekDayCell(
    day: DayOfWeek,
    training: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (training) AppAccent.copy(alpha = 0.14f) else AppSurfaceVariant)
            .then(
                when {
                    isToday -> Modifier.border(1.5.dp, AppAccent, shape)
                    training -> Modifier.border(1.dp, AppAccent.copy(alpha = 0.35f), shape)
                    else -> Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isToday) AppAccent else AppTextMuted
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = if (training) "Train" else "Rest",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (training) FontWeight.SemiBold else FontWeight.Normal,
            color = if (training) AppAccent else AppTextMuted,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LeadChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(shape)
            .background(if (selected) AppAccent.copy(alpha = 0.14f) else AppSurfaceVariant)
            .then(if (selected) Modifier.border(1.dp, AppAccent, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) AppAccent else AppTextSecondary
        )
    }
}

@Composable
private fun ChoiceDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        title = { Text(title, color = AppTextPrimary) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) { content() }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = AppTextSecondary) }
        }
    )
}

@Composable
private fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AppAccent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) AppAccent else AppTextPrimary
            )
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
            }
        }
    }
}

/** Profile's entry point: what the plan is, or a nudge to set one up. */
@Composable
fun TrainingPlanEntryRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val plan = TrainingPlanStore.plan
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AppSurface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.NotificationsActive,
            contentDescription = null,
            tint = AppAccent,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("TRAINING PLAN", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (plan == null) {
                    "Set your split and get gym reminders"
                } else {
                    "${plan.split.label} · ${formatGymTime(context, plan.hour, plan.minute)}" +
                        if (plan.remindersEnabled) "" else " · reminders off"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextPrimary
            )
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AppTextMuted)
    }
}

/** One-time card on Home, shown after the first workout until the user sets
 *  up a plan or dismisses it. */
@Composable
fun GymReminderPromptCard(onSetUp: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AppSurface)
            .border(1.dp, AppAccent.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .clickable(onClick = onSetUp)
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.NotificationsActive,
            contentDescription = null,
            tint = AppAccent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Get a reminder before the gym",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppTextPrimary
            )
            Text(
                text = "Set your split and gym time",
                style = MaterialTheme.typography.labelMedium,
                color = AppAccent
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = AppTextMuted, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Home card when the user's real order differs from the plan's: "You
 * usually train Legs → Pull → Push. Update your plan to match?" Nothing
 * changes until they tap Update.
 */
@Composable
fun SplitOrderPromptCard(
    order: List<String>,
    onUpdate: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AppSurface)
            .border(1.dp, AppAccent.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 4.dp)
    ) {
        Text(
            text = "YOUR USUAL ORDER",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = AppAccent
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "You usually train ${orderLabel(order)}. Update your plan to match?",
            style = MaterialTheme.typography.bodyLarge,
            color = AppTextPrimary
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onKeep) {
                Text("Keep mine", color = AppTextSecondary)
            }
            TextButton(onClick = onUpdate) {
                Text("Update order", color = AppAccent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d/M", Locale.ENGLISH)

/** "MIDDLE BACK" -> "Middle back" */
private fun muscleName(raw: String) = raw.lowercase().replaceFirstChar { it.uppercase() }

/**
 * "How Up next was picked": for the next session, where each split day
 * stands (when it was last trained, recovered or not), then the recent
 * workouts and what each one counted as, with the muscles behind it.
 * Collapsed by default.
 */
@Composable
private fun UpNextWhyCard(explanation: UpNextExplanation, expanded: Boolean, onToggle: () -> Unit) {
    PlanCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "HOW UP NEXT WAS PICKED",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = if (expanded) "Hide" else "Show",
                tint = AppTextMuted,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 90f else 0f)
            )
        }
        if (expanded) {

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "For ${explanation.sessionDate.format(SHORT_DATE)}: ${explanation.upNext.display}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppTextPrimary
            )
            Text(
                text = "The most-rested day whose muscles have had 2+ days to recover.",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted
            )

            Spacer(modifier = Modifier.height(10.dp))
            explanation.days.forEach { status ->
                val last = listOfNotNull(status.lastDone, status.musclesLastTrained).maxOrNull()
                Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = status.day,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (status.day == explanation.upNext.startLabel) FontWeight.Bold else FontWeight.Normal,
                        color = if (status.day == explanation.upNext.startLabel) AppAccent else AppTextPrimary,
                        modifier = Modifier.width(110.dp)
                    )
                    Text(
                        text = (last?.let { "trained ${it.format(SHORT_DATE)}" } ?: "not trained yet") +
                            if (status.recovered) " · ready" else " · recovering",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (status.recovered) AppTextSecondary else AppTextMuted
                    )
                }
            }

            val recent = explanation.workouts.take(8)
            if (recent.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("RECENT WORKOUTS", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
                recent.forEach { w ->
                    Column(modifier = Modifier.padding(vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${w.date?.format(SHORT_DATE) ?: "?"} · ${w.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTextPrimary,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = w.day?.let { "→ $it" } ?: "→ no clear match",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (w.day != null) AppAccent else AppTextMuted
                            )
                        }
                        Text(
                            text = if (w.muscles.isEmpty()) "No muscle data - matched by name only"
                            else w.muscles.joinToString(" · ") { (m, n) ->
                                if (w.fromTagsOnly) muscleName(m) else "${muscleName(m)} $n"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTextMuted
                        )
                    }
                }
                if (recent.any { it.fromTagsOnly && it.muscles.isNotEmpty() }) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Set counts per muscle aren't coming from the server yet, so each muscle counts once. Deploying the server update fixes this.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextMuted
                    )
                }
            }
        }
    }
}
