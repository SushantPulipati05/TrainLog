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
 * called... Pec Deck Diaries"). No AI: the session is read first - which
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

    private val IDEAS: List<NameIdea> = listOf(
        // Push, general
        NameIdea("Push It Real Good", P), NameIdea("Pushin' P", P), NameIdea("Push Notifications", P),
        NameIdea("Pushover", P), NameIdea("The Pressing Matters", P),
        // Chest
        NameIdea("Chest Day, Best Day", muscles = setOf("CHEST")), NameIdea("Pec Deck Diaries", muscles = setOf("CHEST")),
        NameIdea("Chest Fest", muscles = setOf("CHEST")), NameIdea("Chesty La Rue", muscles = setOf("CHEST")),
        NameIdea("Pec-tacular", muscles = setOf("CHEST")), NameIdea("Fly Me to the Moon", muscles = setOf("CHEST"), exercise = "fly"),
        NameIdea("Bench Warmers", exercise = "bench"), NameIdea("Bench Press Conference", exercise = "bench"),
        NameIdea("Press Release", exercise = "press"), NameIdea("Dip Dip Hooray", exercise = "dip"),
        // Shoulders
        NameIdea("Shoulder Season", muscles = setOf("SHOULDERS")), NameIdea("Delts & Dragons", muscles = setOf("SHOULDERS")),
        NameIdea("Boulder Shoulders", muscles = setOf("SHOULDERS")), NameIdea("Raise the Roof", muscles = setOf("SHOULDERS"), exercise = "raise"),
        // Triceps
        NameIdea("Tricep Treaty", muscles = setOf("TRICEPS")), NameIdea("Tri Harder", muscles = setOf("TRICEPS")),
        NameIdea("Horseshoe Club", muscles = setOf("TRICEPS")), NameIdea("Push(down) It to the Limit", exercise = "pushdown"),
        // Pull, general
        NameIdea("Pulling Strings", PU), NameIdea("Pull Request", PU), NameIdea("Got Your Back", PU, BACK),
        NameIdea("Back in Business", muscles = BACK), NameIdea("Back to the Future", muscles = BACK),
        NameIdea("Back That Thing Up", muscles = BACK), NameIdea("Pull Up & Shut Up", exercise = "pull"),
        // Back specifics
        NameIdea("Lat Night Show", muscles = setOf("LATS")), NameIdea("The Lat Pack", muscles = setOf("LATS")),
        NameIdea("Wingspan Season", muscles = setOf("LATS")),
        NameIdea("Row Row Row Your Bod", exercise = "row"), NameIdea("Row-mantic Evening", exercise = "row"),
        NameIdea("Rows Before Bros", exercise = "row"), NameIdea("Deadlift Society", exercise = "deadlift"),
        NameIdea("Shrug Life", muscles = setOf("TRAPS")),
        // Arms
        NameIdea("Bicep Bistro", muscles = setOf("BICEPS")), NameIdea("Curl Power", exercise = "curl"),
        NameIdea("Gun Show", muscles = setOf("BICEPS", "TRICEPS")), NameIdea("Sleeve Season", muscles = setOf("BICEPS", "TRICEPS")),
        NameIdea("Curls Just Wanna Have Fun", exercise = "curl"), NameIdea("Grip It & Rip It", muscles = setOf("FOREARMS")),
        // Legs, general
        NameIdea("Never Skip", L), NameIdea("Leg Day (Survived)", L), NameIdea("Wheel Day", L),
        NameIdea("Leg-endary", L), NameIdea("The Stairs Are Lava", L), NameIdea("Knees Weak, Arms Spaghetti", L),
        // Leg specifics
        NameIdea("Quadfather", muscles = setOf("QUADRICEPS")), NameIdea("Quad Squad", muscles = setOf("QUADRICEPS")),
        NameIdea("Squat Happens", exercise = "squat"), NameIdea("Squat Goals", exercise = "squat"),
        NameIdea("Hamstring Theory", muscles = setOf("HAMSTRINGS")), NameIdea("Glute Expectations", muscles = setOf("GLUTES")),
        NameIdea("Booty Camp", muscles = setOf("GLUTES")), NameIdea("Calf Raise Your Hand", muscles = setOf("CALVES")),
        NameIdea("Calf-way There", muscles = setOf("CALVES")), NameIdea("Lunge Lizard", exercise = "lunge"),
        NameIdea("Thigh Society", muscles = setOf("QUADRICEPS", "ADDUCTORS", "ABDUCTORS")),
        NameIdea("Leg Press Pass", exercise = "leg press"),
        // Abs
        NameIdea("Core Values", muscles = setOf("ABDOMINALS")), NameIdea("Abs-olutely", muscles = setOf("ABDOMINALS")),
        NameIdea("Crunch Time", muscles = setOf("ABDOMINALS")), NameIdea("Plank You Very Much", exercise = "plank"),
        // Upper / Lower / Full / Mixed
        NameIdea("Upper Management", setOf(WorkoutKind.UPPER)), NameIdea("Top Shelf", setOf(WorkoutKind.UPPER)),
        NameIdea("Upper Echelon", setOf(WorkoutKind.UPPER)), NameIdea("Shirt Stretcher", setOf(WorkoutKind.UPPER)),
        NameIdea("Above the Belt", setOf(WorkoutKind.UPPER)), NameIdea("Upper Class", setOf(WorkoutKind.UPPER)),
        NameIdea("Lower Expectations", setOf(WorkoutKind.LOWER)), NameIdea("Down Under", setOf(WorkoutKind.LOWER)),
        NameIdea("Ground Floor", setOf(WorkoutKind.LOWER)), NameIdea("The Bottom Line", setOf(WorkoutKind.LOWER)),
        NameIdea("Full Send", setOf(WorkoutKind.FULL)), NameIdea("The Whole Enchilada", setOf(WorkoutKind.FULL)),
        NameIdea("Head to Toe", setOf(WorkoutKind.FULL)), NameIdea("No Muscle Left Behind", setOf(WorkoutKind.FULL)),
        NameIdea("Everything Everywhere All at Once", setOf(WorkoutKind.FULL)),
        NameIdea("Choose Your Own Adventure", setOf(WorkoutKind.MIXED)), NameIdea("Chaos Theory", setOf(WorkoutKind.MIXED)),
        NameIdea("Gym Buffet", setOf(WorkoutKind.MIXED)), NameIdea("Off Script", setOf(WorkoutKind.MIXED)),
        NameIdea("The Sampler Platter", setOf(WorkoutKind.MIXED))
    )

    private val OPENERS = listOf("Operation", "Certified", "Attack of", "Ultimate", "Deluxe", "Classic", "Unhinged", "Legendary")
    private val ENDINGS = listOf(
        "Part II", "Director's Cut", "Final Boss Edition", "Remastered", "Deluxe Edition", "Electric Boogaloo", "The Sequel", "Unplugged"
    )

    // {s} = what the session was ("Chest" for a chest-only day, else "Push"),
    // {c} = a name idea that fits, {w} = a weight, {x} = an exercise,
    // {n} = session number, {day} = weekday.
    private val PR = listOf(
        "{s}: PR Edition", "New Record: {c}", "{c}: Record Breaker", "The {w} kg Glow-Up",
        "Personal Best of {s}", "{s} Hall of Fame", "Plot Twist: PR", "Fresh PR, Who Dis", "{x}: New High Score"
    )
    private val BIGGEST = listOf(
        "{c}: Final Boss", "The Biggest {s} Ever", "{s}: Maximum Overdrive", "Volume Turned to 11", "{c}: Extended Universe"
    )
    private val COMEBACK = listOf(
        "The Return of the {s}", "{s}: The Comeback", "Guess Who's Back", "{c}: Reunion Tour", "{s} Strikes Back", "Back From the Couch"
    )
    private val SHORT = listOf(
        "{s} Speedrun", "{s} Express", "Quick {s}, No Lunch", "{c} (Short Version)", "The {s} Sprint", "TL;DR {s}"
    )
    private val LONG = listOf(
        "{c}: Extended Cut", "The Never-Ending {s}", "{s}: The Long Haul", "{c} Marathon", "Overtime {s}", "{s} Till Closing"
    )
    private val LATE = listOf(
        "Midnight {s}", "{s} After Dark", "Late Night {c}", "Night Shift {s}", "{s} by Moonlight", "The {day} Night {s} Show"
    )
    private val EARLY = listOf(
        "Sunrise {s}", "{s} Before Breakfast", "Early Bird {s}", "Dawn of the {s}", "{s} & Coffee", "Rise and {s}"
    )
    private val NUMBERED = listOf(
        "The {w} kg Incident", "{day} {s} Special", "{s} Day No. {n}", "{x} Appreciation Society", "{x} & Friends", "{w} kg of Pure {s}"
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
                PR to "🏆 PR on ${facts.prExercise}" + (facts.prWeightKg?.let { " ${formatDecimal(it)} kg" } ?: "")
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
                name = when {
                    roll < 0.3f -> fill(NUMBERED.random(random), forPr = false)
                    roll < 0.45f -> "${OPENERS.random(random)} ${weighted.random(random)}"
                    roll < 0.6f -> "${weighted.random(random)}: ${ENDINGS.random(random)}"
                    else -> weighted.random(random)
                }
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
