package com.example.workoutlog_androidstudio

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.workoutlog_androidstudio.api.WorkoutTemplateSummaryResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The common training splits, each with the days it cycles through. */
enum class TrainingSplit(val label: String, val days: List<String>, val cycleLabel: String) {
    PPL("Push / Pull / Legs", listOf("Push", "Pull", "Legs"), "3-day cycle"),
    UPPER_LOWER("Upper / Lower", listOf("Upper", "Lower"), "2-day cycle"),
    BRO("Bro split", listOf("Chest", "Back", "Shoulders", "Arms", "Legs"), "5-day cycle"),
    ARNOLD("Arnold split", listOf("Chest + Back", "Shoulders + Arms", "Legs"), "3-day cycle"),
    FULL_BODY("Full body", listOf("Full body"), "every session"),
    CUSTOM("Custom", emptyList(), "name your own days"),
    NONE("No fixed split", emptyList(), "just remind me to train")
}

/**
 * The user's plan: which split they follow (the order their workouts go
 * in), which days they train, and when. Which workout is *next* isn't tied
 * to a weekday - it comes from what they actually did last (see
 * [computeUpNext] in SplitInsights.kt), so a skipped day never throws
 * the rotation off.
 *
 * Saved on this phone only - the reminders it drives are scheduled by the
 * phone itself, so there's nothing for the server to do.
 */
@Serializable
data class TrainingPlan(
    val split: TrainingSplit = TrainingSplit.PPL,
    val customDays: List<String> = emptyList(),
    /** ISO day numbers: 1 = Monday ... 7 = Sunday. */
    val trainingDays: Set<Int> = setOf(1, 2, 3, 4, 5, 6),
    val hour: Int = 18,
    val minute: Int = 30,
    /** How long before [hour]:[minute] the reminder comes. 0 = at that time. */
    val leadMinutes: Int = 30,
    val remindersEnabled: Boolean = true,
    /** Split day name (e.g. "Push") -> the template it starts from.
     *  [EMPTY_WORKOUT] means "start an empty workout"; days not in here are
     *  matched to a template by name (see [templateIdFor]). */
    val templateIds: Map<String, Int> = emptyMap(),
    /** The order the user really trains the split's days in, once they've
     *  confirmed it (see [detectUsualOrder]) - e.g. Legs, Pull, Push for a
     *  Push / Pull / Legs split. Empty = the split's own order. */
    val dayOrder: List<String> = emptyList()
) {
    fun splitDays(): List<String> = if (split == TrainingSplit.CUSTOM) customDays else split.days

    /** The split's days in the user's order. A saved order that no longer
     *  fits the split (it was changed since) is ignored. */
    fun orderedDays(): List<String> {
        val days = splitDays()
        return if (dayOrder.isNotEmpty() && dayOrder.sorted() == days.distinct().sorted()) dayOrder else days
    }

    /** Whether the user has set their own order for this split. */
    fun hasCustomOrder(): Boolean {
        val days = splitDays()
        return dayOrder.isNotEmpty() && dayOrder.sorted() == days.distinct().sorted() && dayOrder != days
    }

    fun isTrainingDay(day: DayOfWeek): Boolean = day.value in trainingDays

    /**
     * The template a split day starts from: the one the user picked, or else
     * one whose name matches the day ("Push" -> "Push" / "Push Day"). null =
     * start an empty workout.
     */
    fun templateIdFor(label: String, templates: List<WorkoutTemplateSummaryResponse>): Int? {
        if (label.isBlank()) return null
        templateIds[label]?.let { id ->
            return if (id == EMPTY_WORKOUT) null else id.takeIf { picked -> templates.any { it.id == picked } }
        }
        val wanted = normalizeWorkoutName(label)
        if (wanted.isEmpty()) return null
        return templates.firstOrNull { normalizeWorkoutName(it.name) == wanted }?.id
    }
}

/** Value stored in [TrainingPlan.templateIds] for "no template, start empty". */
const val EMPTY_WORKOUT = -1

/** "6:30 PM", or "18:30" when the phone uses 24-hour time. */
fun formatGymTime(context: Context, hour: Int, minute: Int): String {
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
}

fun leadLabel(minutes: Int): String = when (minutes) {
    0 -> "At gym time"
    60 -> "1 hr before"
    else -> "$minutes min before"
}

/** "Push day", "Chest + Back", "Full body" - how a planned workout is named
 *  in the notification and when it's started from there. */
fun plannedWorkoutName(label: String): String = when {
    label.isBlank() -> "Workout"
    label.contains(' ') || label.contains('+') -> label
    else -> "$label day"
}

/** "Pull Day", "pull", "PULL workout" -> "pull"; "Chest & Back" -> "chest back".
 *  Used to tell whether two workout names mean the same workout. */
fun normalizeWorkoutName(name: String): String =
    name.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .split(' ')
        .filter { it.isNotBlank() && it !in setOf("day", "workout", "session", "and") }
        .joinToString(" ")

/** The saved plan plus a couple of small flags, kept in SharedPreferences. */
object TrainingPlanStore {
    private const val PREFS_NAME = "training_plan"
    private const val KEY_PLAN = "plan"
    private const val KEY_LAST_TRAINED = "last_trained_date"
    private const val KEY_PROMPT_DISMISSED = "prompt_dismissed"
    private const val KEY_UP_NEXT_DISPLAY = "up_next_display"
    private const val KEY_UP_NEXT_START = "up_next_start"
    private const val KEY_UP_NEXT_RECOVERING = "up_next_recovering"
    private const val KEY_DISMISSED_ORDER = "dismissed_order"

    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: SharedPreferences? = null

    /** null until the user has set a plan up. Compose state, so screens update. */
    var plan by mutableStateOf<TrainingPlan?>(null)
        private set

    /** Whether the one-time "set up a plan" card on Home was dismissed. */
    var promptDismissed by mutableStateOf(false)
        private set

    /** Set when "Start workout" is tapped on a reminder: the planned day's
     *  name (e.g. "Push", or "" for an unnamed training day). MainActivity
     *  consumes it and starts that day's workout. */
    var pendingStartPlanLabel by mutableStateOf<String?>(null)

    /** A detected order the user said "Keep mine" to ("Legs → Pull → Push"),
     *  so that same suggestion isn't shown again. */
    var dismissedOrder by mutableStateOf<String?>(null)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        plan = p.getString(KEY_PLAN, null)?.let { raw ->
            runCatching { json.decodeFromString(TrainingPlan.serializer(), raw) }.getOrNull()
        }
        promptDismissed = p.getBoolean(KEY_PROMPT_DISMISSED, false)
        dismissedOrder = p.getString(KEY_DISMISSED_ORDER, null)
    }

    fun save(context: Context, newPlan: TrainingPlan) {
        init(context)
        plan = newPlan
        prefs?.edit()?.putString(KEY_PLAN, json.encodeToString(TrainingPlan.serializer(), newPlan))?.apply()
        GymReminderScheduler.reschedule(context)
    }

    /** Home works out "Up next" from the workout list; it's saved here so
     *  the reminder (which runs without the app open) can name it too. */
    fun saveUpNext(context: Context, upNext: UpNext?) {
        init(context)
        prefs?.edit()
            ?.putString(KEY_UP_NEXT_DISPLAY, upNext?.display)
            ?.putString(KEY_UP_NEXT_START, upNext?.startLabel)
            ?.putBoolean(KEY_UP_NEXT_RECOVERING, upNext?.recovering == true)
            ?.apply()
    }

    fun savedUpNext(context: Context): UpNext? {
        init(context)
        val display = prefs?.getString(KEY_UP_NEXT_DISPLAY, null) ?: return null
        val start = prefs?.getString(KEY_UP_NEXT_START, null) ?: display
        val recovering = prefs?.getBoolean(KEY_UP_NEXT_RECOVERING, false) == true
        return UpNext(display = display, startLabel = start, basedOn = "", recovering = recovering)
    }

    /** "Update order" on the Home suggestion. */
    fun acceptOrder(context: Context, order: List<String>) {
        val current = plan ?: return
        save(context, current.copy(dayOrder = order))
    }

    /** "Keep mine" on the Home suggestion - don't offer this order again. */
    fun dismissOrder(context: Context, order: List<String>) {
        init(context)
        val label = orderLabel(order)
        dismissedOrder = label
        prefs?.edit()?.putString(KEY_DISMISSED_ORDER, label)?.apply()
    }

    fun dismissPrompt(context: Context) {
        init(context)
        promptDismissed = true
        prefs?.edit()?.putBoolean(KEY_PROMPT_DISMISSED, true)?.apply()
    }

    /** Called when a workout ends, so today's reminder is skipped. */
    fun markTrainedToday(context: Context) {
        init(context)
        prefs?.edit()?.putString(KEY_LAST_TRAINED, LocalDate.now().toString())?.apply()
        GymReminderScheduler.reschedule(context)
    }

    fun trainedToday(context: Context): Boolean {
        init(context)
        return prefs?.getString(KEY_LAST_TRAINED, null) == LocalDate.now().toString()
    }

    /** Signing out: forget this account's plan and stop its reminders. */
    fun clear(context: Context) {
        init(context)
        plan = null
        promptDismissed = false
        dismissedOrder = null
        prefs?.edit()?.clear()?.apply()
        GymReminderScheduler.cancel(context)
    }
}

/**
 * Schedules exactly one alarm - the next upcoming reminder. When it fires,
 * [GymReminderReceiver] shows the notification and schedules the one after.
 * Uses an inexact (battery-friendly) alarm, so it may arrive a few minutes
 * late when the phone is idle - fine for a gym reminder, and it needs no
 * special "exact alarm" permission.
 */
object GymReminderScheduler {
    private const val REQUEST_CODE = 4100

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, GymReminderReceiver::class.java).setAction(GymReminderReceiver.ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent(context))
    }

    fun reschedule(context: Context) {
        cancel(context)
        TrainingPlanStore.init(context)
        val plan = TrainingPlanStore.plan ?: return
        if (!plan.remindersEnabled) return

        val now = LocalDateTime.now()
        val trainedToday = TrainingPlanStore.trainedToday(context)
        val next = (0..7).asSequence()
            .map { now.toLocalDate().plusDays(it.toLong()) }
            .filter { date -> plan.isTrainingDay(date.dayOfWeek) }
            .filter { date -> !(date == now.toLocalDate() && trainedToday) }
            .map { date -> date.atTime(plan.hour, plan.minute).minusMinutes(plan.leadMinutes.toLong()) }
            .firstOrNull { it.isAfter(now) }
            ?: return

        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context))
        Log.d("GymReminder", "Next reminder at $next")
    }
}

/** Shows the reminder, handles "Skip today", and re-arms the alarm after a
 *  reboot or a clock/time zone change (alarms don't survive those). */
class GymReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "com.example.workoutlog_androidstudio.GYM_REMINDER_FIRE"
        const val ACTION_SKIP = "com.example.workoutlog_androidstudio.GYM_REMINDER_SKIP"
        const val ACTION_START = "com.example.workoutlog_androidstudio.GYM_REMINDER_START"
        const val EXTRA_PLAN_LABEL = "plan_label"
        const val CHANNEL_ID = "gym_reminders"
        const val NOTIFICATION_ID = 4101
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> {
                showReminderIfDue(context)
                GymReminderScheduler.reschedule(context)
            }
            ACTION_SKIP -> NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            else -> GymReminderScheduler.reschedule(context) // boot, time set, time zone, app update
        }
    }

    private fun showReminderIfDue(context: Context) {
        TrainingPlanStore.init(context)
        val plan = TrainingPlanStore.plan ?: return
        if (!plan.remindersEnabled || TrainingPlanStore.trainedToday(context)) return
        if (!plan.isTrainingDay(LocalDate.now().dayOfWeek)) return
        // Up next, as last worked out by Home; before there's any history,
        // the split's first day (or a plain "Gym time" for no split).
        val upNext = TrainingPlanStore.savedUpNext(context)
            ?: plan.orderedDays().firstOrNull()?.let { UpNext(plannedWorkoutName(it), it, "") }
        val label = upNext?.startLabel.orEmpty()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        createChannel(context)
        val time = formatGymTime(context, plan.hour, plan.minute)
        val title = if (upNext == null) "Gym time at $time" else "${upNext.display} at $time"
        val body = when {
            upNext?.recovering == true -> "You've trained everything in the last couple of days - a rest day is fine too."
            plan.leadMinutes == 0 -> "Time to head to the gym."
            plan.leadMinutes == 60 -> "1 hour to go. Tap to start your workout."
            else -> "${plan.leadMinutes} minutes to go. Tap to start your workout."
        }

        val openApp = PendingIntent.getActivity(
            context, 4102,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val start = PendingIntent.getActivity(
            context, 4103,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PLAN_LABEL, label)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val skip = PendingIntent.getBroadcast(
            context, 4104,
            Intent(context, GymReminderReceiver::class.java).setAction(ACTION_SKIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_workout)
            .setColor(AppAccent.toArgb())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Start workout", start)
            .addAction(0, "Skip today", skip)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w("GymReminder", "Notification permission missing", e)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Gym reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A reminder before your planned gym time"
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
