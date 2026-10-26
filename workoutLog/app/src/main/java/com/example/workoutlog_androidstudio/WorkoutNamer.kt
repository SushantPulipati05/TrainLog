package com.example.workoutlog_androidstudio

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.random.Random

/*
 * Fun workout names, picked when a workout ends ("Today's workout is
 * called... Iron Chest"). No AI: the session is read first - which
 * muscles got how many sets, which exercises, how long, what time, any PR -
 * and then only names that are TRUE for it can be picked. A name about
 * shoulders needs real shoulder work; a name about bench press needs a bench
 * press. Works offline, instantly.
 */

/** Everything about the finished session the name can react to. */
data class SessionFacts(
    val kind: WorkoutKind,
    /** Sets per muscle group (upper-cased), abs included. */
    val muscleSets: Map<String, Int>,
    /** Names of the exercises done (with at least one logged set). */
    val exerciseNames: List<String>,
    val prExercise: String?,          // an exercise that got a new PR today
    val prWeightKg: Double?,
    val biggestEver: Boolean,         // most sets of any session of this kind
    val comebackDays: Int?,           // days since the previous workout, if 10+
    val durationMinutes: Int,
    val startedAt: LocalDateTime,
    val topExercise: String?,         // exercise of the heaviest set
    val topWeightKg: Double?,
    val sessionNumber: Int,           // this is the Nth session of this kind
    val totalSets: Int
)

/** A suggested name and the one-line reason shown under it. */
data class SuggestedName(val name: String, val reason: String)

/**
 * One name idea. It can only be picked when the session matches:
 * - [kinds]: empty = any kind of workout;
 * - [muscles]: empty = no muscle needed, otherwise at least one of these got
 *   a real share (25%+) of the sets;
 * - [exercise]: a word one of the exercises' names must contain ("bench").
 */
private data class NameIdea(
    val text: String,
    val kinds: Set<WorkoutKind> = emptySet(),
    val muscles: Set<String> = emptySet(),
    val exercise: String? = null
)

/** Muscle families a session can be "all about". */
private val FAMILIES: Map<String, Set<String>> = mapOf(
    "Chest" to setOf("CHEST"),
    "Shoulders" to setOf("SHOULDERS"),
    "Triceps" to setOf("TRICEPS"),
    "Biceps" to setOf("BICEPS"),
    "Forearms" to setOf("FOREARMS"),
    "Back" to setOf("LATS", "MIDDLE BACK", "LOWER BACK", "TRAPS"),
    "Legs" to setOf("QUADRICEPS", "HAMSTRINGS", "ADDUCTORS", "ABDUCTORS", "GLUTES", "CALVES"),
    "Glutes" to setOf("GLUTES"),
    "Calves" to setOf("CALVES"),
    "Abs" to setOf("ABDOMINALS")
)

object WorkoutNamer {
    private const val PREFS = "workout_namer"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_RECENT = "recent"
    private const val RECENT_LIMIT = 20

    /** A muscle counts as "trained" for a name if it got this share of the sets. */
    private const val REAL_SHARE = 0.25
    /** A session is "all about" one muscle family at this share. */
    private const val FOCUS_SHARE = 0.7

    private var prefs: SharedPreferences? = null

    /** The Profile switch. On by default. Compose state, so the switch updates. */
    var enabled by mutableStateOf(true)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, on: Boolean) {
        init(context)
        enabled = on
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
    }

    private fun recent(): List<String> =
        prefs?.getString(KEY_RECENT, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    /** Remember a name that was used, so it isn't suggested again soon. */
    fun remember(context: Context, name: String) {
        init(context)
        val updated = (recent() + name.trim()).takeLast(RECENT_LIMIT)
        prefs?.edit()?.putString(KEY_RECENT, updated.joinToString("\n"))?.apply()
    }

    // ---------------------------------------------------------------- names

    private val P = setOf(WorkoutKind.PUSH)
    private val PU = setOf(WorkoutKind.PULL)
    private val L = setOf(WorkoutKind.LEGS, WorkoutKind.LOWER)
    private val BACK = FAMILIES.getValue("Back")
    private val LEGS = FAMILIES.getValue("Legs")

    // Short, strong names - titles, not jokes. Each one still only fits a
    // session that really trained what it's about.
    private val IDEAS: List<NameIdea> = listOf(
        // Push, general
        NameIdea("Iron Press", P), NameIdea("Front Line", P), NameIdea("Full Force", P),
        NameIdea("Pressure Point", P), NameIdea("Push Protocol", P),
        // Chest
        NameIdea("Iron Chest", muscles = setOf("CHEST")), NameIdea("Chest Plate", muscles = setOf("CHEST")),
        NameIdea("Armor Up", muscles = setOf("CHEST")), NameIdea("Breastplate", muscles = setOf("CHEST")),
        NameIdea("Wide Open", muscles = setOf("CHEST"), exercise = "fly"),
        NameIdea("Benchmark", exercise = "bench"), NameIdea("Flat Out", exercise = "bench"),
        NameIdea("Pressed Steel", exercise = "press"), NameIdea("Parallel Bars", exercise = "dip"),
        // Shoulders
        NameIdea("Atlas", muscles = setOf("SHOULDERS")), NameIdea("Cannonballs", muscles = setOf("SHOULDERS")),
        NameIdea("Capped", muscles = setOf("SHOULDERS")), NameIdea("Broad Shoulders", muscles = setOf("SHOULDERS")),
        NameIdea("Side Load", muscles = setOf("SHOULDERS"), exercise = "raise"),
        // Triceps
        NameIdea("Lockout", muscles = setOf("TRICEPS")), NameIdea("Horseshoe", muscles = setOf("TRICEPS")),
        NameIdea("Triple Threat", muscles = setOf("TRICEPS")), NameIdea("Downforce", exercise = "pushdown"),
        // Pull, general
        NameIdea("Pull Force", PU), NameIdea("Undertow", PU), NameIdea("Backbone", PU, BACK),
        NameIdea("Behind the Lines", muscles = BACK), NameIdea("Back Engine", muscles = BACK),
        NameIdea("Iron Pull", exercise = "pull"),
        // Back specifics
        NameIdea("Wingspan", muscles = setOf("LATS")), NameIdea("V-Taper", muscles = setOf("LATS")),
        NameIdea("Spread Wings", muscles = setOf("LATS")),
        NameIdea("Heavy Rows", exercise = "row"), NameIdea("Row Engine", exercise = "row"),
        NameIdea("Dead Weight", exercise = "deadlift"), NameIdea("Floor to Lockout", exercise = "deadlift"),
        NameIdea("The Yoke", muscles = setOf("TRAPS")),
        // Arms
        NameIdea("Peaks", muscles = setOf("BICEPS")), NameIdea("Iron Curls", exercise = "curl"),
        NameIdea("Arsenal", muscles = setOf("BICEPS", "TRICEPS")), NameIdea("Gunsmith", muscles = setOf("BICEPS", "TRICEPS")),
        NameIdea("Arms Race", muscles = setOf("BICEPS", "TRICEPS")),
        NameIdea("Iron Grip", muscles = setOf("FOREARMS")), NameIdea("Vise Grip", muscles = setOf("FOREARMS")),
        // Legs, general
        NameIdea("Foundation", L), NameIdea("Pillars", L), NameIdea("Ground Zero", L),
        NameIdea("Iron Legs", L), NameIdea("Heavy Legs", L),
        // Leg specifics
        NameIdea("Teardrop", muscles = setOf("QUADRICEPS")), NameIdea("Quad Fire", muscles = setOf("QUADRICEPS")),
        NameIdea("Below Parallel", exercise = "squat"), NameIdea("Under the Bar", exercise = "squat"),
        NameIdea("Posterior Chain", muscles = setOf("HAMSTRINGS", "GLUTES")), NameIdea("The Hinge", muscles = setOf("HAMSTRINGS")),
        NameIdea("Power Source", muscles = setOf("GLUTES")), NameIdea("Drive", muscles = setOf("GLUTES")),
        NameIdea("Diamonds", muscles = setOf("CALVES")), NameIdea("Diamond Cut", muscles = setOf("CALVES")),
        NameIdea("Forward March", exercise = "lunge"), NameIdea("The Sled", exercise = "leg press"),
        NameIdea("Inner Strength", muscles = setOf("ADDUCTORS", "ABDUCTORS")),
        // Abs
        NameIdea("Steel Core", muscles = setOf("ABDOMINALS")), NameIdea("Core Lock", muscles = setOf("ABDOMINALS")),
        NameIdea("Midsection", muscles = setOf("ABDOMINALS")), NameIdea("Hold the Line", exercise = "plank"),
        // Upper / Lower / Full / Mixed
        NameIdea("Upper Hand", setOf(WorkoutKind.UPPER)), NameIdea("Top Half", setOf(WorkoutKind.UPPER)),
        NameIdea("Upper Deck", setOf(WorkoutKind.UPPER)),
        NameIdea("Ground Force", setOf(WorkoutKind.LOWER)), NameIdea("Bedrock", setOf(WorkoutKind.LOWER)),
        NameIdea("Lower Deck", setOf(WorkoutKind.LOWER)),
        NameIdea("All In", setOf(WorkoutKind.FULL)), NameIdea("Total Body", setOf(WorkoutKind.FULL)),
        NameIdea("Head to Toe", setOf(WorkoutKind.FULL)), NameIdea("Full Spectrum", setOf(WorkoutKind.FULL)),
        NameIdea("Wildcard", setOf(WorkoutKind.MIXED)), NameIdea("Freestyle", setOf(WorkoutKind.MIXED)),
        NameIdea("Off Script", setOf(WorkoutKind.MIXED)), NameIdea("Open Floor", setOf(WorkoutKind.MIXED))
    )

    // {s} = what the session was ("Chest" for a chest-only day, else "Push"),
    // {c} = a name idea that fits, {w} = a weight, {x} = an exercise,
    // {n} = session number, {day} = weekday.
    private val PR = listOf(
        "Record Breaker", "New Ceiling", "Uncharted", "Peak {s}", "{x}: New Best", "The {w} kg Club", "Personal Record"
    )
    private val BIGGEST = listOf(
        "Workhorse", "Max Volume", "Heavy Duty", "Biggest {s} Yet", "{s} at Full Capacity"
    )
    private val COMEBACK = listOf(
        "The Return", "Back in Iron", "Reloaded", "Rust Off", "{s}: Reloaded"
    )
    private val SHORT = listOf(
        "Blitz", "Quick Strike", "{s} Blitz", "In and Out", "Short and Heavy"
    )
    private val LONG = listOf(
        "Long Haul", "The Grind", "{s} Marathon", "Overtime", "Endurance Run"
    )
    private val LATE = listOf(
        "Night Shift", "After Hours", "Midnight Iron", "Night {s}", "Lights Out"
    )
    private val EARLY = listOf(
        "First Light", "Dawn Patrol", "Early Iron", "Sunrise {s}", "Before the World Wakes"
    )
    private val NUMBERED = listOf(
        "{c} Vol. {n}", "{s} Vol. {n}", "{day} {s}", "The {w} kg Club", "{x} Day"
    )

    // ---------------------------------------------------------------- rules

    private fun shares(facts: SessionFacts): Map<String, Double> {
        val total = facts.muscleSets.values.sum().toDouble()
        if (total <= 0.0) return emptyMap()
        return facts.muscleSets.mapValues { it.value / total }
    }

    /** "Chest" when one muscle family is most of the session, else null. */
    private fun focusOf(share: Map<String, Double>): String? =
        FAMILIES.entries
            .map { (name, group) -> name to group.sumOf { share[it] ?: 0.0 } }
            .filter { it.second >= FOCUS_SHARE }
            .maxByOrNull { it.second }
            ?.first

    private fun fits(idea: NameIdea, facts: SessionFacts, share: Map<String, Double>): Boolean {
        if (idea.kinds.isNotEmpty() && facts.kind !in idea.kinds) return false
        if (idea.muscles.isNotEmpty() && idea.muscles.none { (share[it] ?: 0.0) >= REAL_SHARE }) return false
        if (idea.exercise != null && facts.exerciseNames.none { it.contains(idea.exercise, ignoreCase = true) }) return false
        return true
    }

    /**
     * A name for the session. Special moments win, in this order: a PR, the
     * biggest session of its kind, a comeback, short / long, late / early.
     * Every name - and every {c} inside one - is drawn only from ideas that fit
     * what was actually trained; ideas about the session's main muscle or its
     * exercises are favoured over general ones. Names used recently are skipped.
     */
    fun suggest(facts: SessionFacts, random: Random = Random.Default): SuggestedName {
        val used = recent().map { it.lowercase() }.toSet()
        val share = shares(facts)
        val focus = focusOf(share)
        val label = focus ?: facts.kind.label
        val weekday = facts.startedAt.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

        val fitting = IDEAS.filter { fits(it, facts, share) }
        // Specific ideas (about a muscle or an exercise that was trained) count
        // three times, so a chest day mostly gets chest names.
        val weighted = fitting.flatMap { idea ->
            val specific = idea.muscles.isNotEmpty() || idea.exercise != null
            List(if (specific) 3 else 1) { idea.text }
        }.ifEmpty { listOf("$label Session") }

        val special: Pair<List<String>, String>? = when {
            facts.prExercise != null ->
                PR to "New best on ${facts.prExercise}" + (facts.prWeightKg?.let { ": ${formatDecimal(it)} kg" } ?: "")
            facts.biggestEver -> BIGGEST to "Biggest ${label.lowercase()} session yet"
            facts.comebackDays != null -> COMEBACK to "First session in ${facts.comebackDays} days"
            facts.durationMinutes in 1..34 -> SHORT to "Done in ${facts.durationMinutes} min"
            facts.durationMinutes >= 90 -> LONG to "${durationLabel(facts.durationMinutes)} session"
            facts.startedAt.hour >= 22 || facts.startedAt.hour < 4 -> LATE to "Started ${timeLabel(facts.startedAt)}"
            facts.startedAt.hour in 4..6 -> EARLY to "Started ${timeLabel(facts.startedAt)}"
            else -> null
        }

        val hasWeight = (facts.topWeightKg ?: 0.0) > 0.0 && facts.topExercise != null
        fun fill(template: String, forPr: Boolean): String? {
            if (!hasWeight && ("{w}" in template || "{x}" in template) && !(forPr && "{x}" in template && facts.prExercise != null)) return null
            val weight = (if (forPr) facts.prWeightKg else null) ?: facts.topWeightKg
            val exercise = (if (forPr) facts.prExercise else null) ?: facts.topExercise
            if ("{w}" in template && (weight == null || weight <= 0.0)) return null
            return template
                .replace("{s}", label)
                .replace("{c}", weighted.random(random))
                .replace("{w}", weight?.let { formatDecimal(it) } ?: "")
                .replace("{x}", exercise.orEmpty())
                .replace("{n}", facts.sessionNumber.toString())
                .replace("{day}", weekday)
        }

        // The duration is already in the reason for short / long sessions.
        val summary = sessionSummary(facts, share, withDuration = special?.first !== SHORT && special?.first !== LONG)
        repeat(50) {
            val name: String?
            var reason = summary
            if (special != null && random.nextFloat() < 0.85f) {
                name = fill(special.first.random(random), forPr = special.first === PR)
                reason = listOf(special.second, summary).filter { it.isNotBlank() }.joinToString(" · ")
            } else {
                val roll = random.nextFloat()
                name = if (roll < 0.25f) fill(NUMBERED.random(random), forPr = false) else weighted.random(random)
            }
            if (name != null && name.length <= 60 && name.lowercase() !in used) return SuggestedName(name, reason)
        }
        return SuggestedName(weighted.random(random), summary)
    }

    /** "Chest & Triceps · 12 sets · 52 min" - the facts the name came from. */
    private fun sessionSummary(facts: SessionFacts, share: Map<String, Double>, withDuration: Boolean = true): String {
        val main = share.entries
            .filter { it.value >= REAL_SHARE }
            .sortedByDescending { it.value }
            .take(2)
            .map { muscleLabel(it.key) }
        return listOfNotNull(
            main.takeIf { it.isNotEmpty() }?.joinToString(" & "),
            "${facts.totalSets} sets",
            facts.durationMinutes.takeIf { it > 0 && withDuration }?.let { durationLabel(it) }
        ).joinToString(" · ")
    }

    private fun muscleLabel(raw: String) = raw.lowercase().replaceFirstChar { it.uppercase() }

    private fun durationLabel(minutes: Int) = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

    private fun timeLabel(at: LocalDateTime): String {
        val h = at.hour % 12
        return "${if (h == 0) 12 else h}:${at.minute.toString().padStart(2, '0')} ${if (at.hour < 12) "AM" else "PM"}"
    }

    /**
     * Works out [SessionFacts] for a workout that has just been saved.
     * [prExercise] / [prWeightKg] come from the caller (it checks the server's
     * PR flags); everything else is from this session and [history] (the
     * previous finished workouts).
     */
    fun factsFor(
        exercises: List<ActiveExercise>,
        elapsedSeconds: Int,
        history: List<WorkoutSummary>,
        workoutId: Int,
        plan: TrainingPlan?,
        prExercise: String?,
        prWeightKg: Double?,
        now: LocalDateTime = LocalDateTime.now()
    ): SessionFacts {
        val doneSets = exercises.flatMap { e -> e.sets.filter { it.reps.toIntOrNull() != null }.map { e to it } }
        val muscleSets = doneSets.groupingBy { it.first.muscleGroup.trim().uppercase() }.eachCount()
        val kind = workoutKind(muscleSets, plan)

        val top = doneSets
            .filter { !it.first.isBodyweight }
            .maxByOrNull { parseKg(it.second.kg) ?: 0.0 }
            ?.takeIf { (parseKg(it.second.kg) ?: 0.0) > 0.0 }

        val previous = history.filter { it.id != workoutId }
        val sameKind = previous.filter { it.kind(plan) == kind }
        val biggest = sameKind.size >= 3 && doneSets.size > (sameKind.maxOfOrNull { it.totalSets } ?: Int.MAX_VALUE)

        val today = now.toLocalDate()
        val lastDate = previous.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
            .filter { it.isBefore(today) }
            .maxOrNull()
        val gap = lastDate?.let { ChronoUnit.DAYS.between(it, today).toInt() }

        return SessionFacts(
            kind = kind,
            muscleSets = muscleSets,
            exerciseNames = doneSets.map { it.first.name }.distinct(),
            prExercise = prExercise,
            prWeightKg = prWeightKg,
            biggestEver = biggest,
            comebackDays = gap?.takeIf { it >= 10 },
            durationMinutes = elapsedSeconds / 60,
            startedAt = now.minusSeconds(elapsedSeconds.toLong()),
            topExercise = top?.first?.name,
            topWeightKg = top?.second?.kg?.let { parseKg(it) },
            sessionNumber = sameKind.size + 1,
            totalSets = doneSets.size
        )
    }
}
