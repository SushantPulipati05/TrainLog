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
 * called... Quadfather"). No AI: hand-written name parts plus rules about the
 * session - its type (from the muscles trained), a PR, its length, the time
 * of day, a comeback - and the user's real numbers. Works offline, instantly.
 */

/** Everything about the finished session the name can react to. */
data class SessionFacts(
    val kind: WorkoutKind,
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

object WorkoutNamer {
    private const val PREFS = "workout_namer"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_RECENT = "recent"
    private const val RECENT_LIMIT = 20

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

    private val CORE: Map<WorkoutKind, List<String>> = mapOf(
        WorkoutKind.PUSH to listOf(
            "Push It Real Good", "Chest Day, Best Day", "Bench Press Conference", "Pec Deck Diaries",
            "Shoulder Season", "Pushin' P", "Tricep Treaty", "The Pressing Matters", "Bench Warmers",
            "Chest Fest", "Push Notifications", "Delts & Dragons", "Press Release", "Pushover", "Chesty La Rue"
        ),
        WorkoutKind.PULL to listOf(
            "Pull Up & Shut Up", "Back That Thing Up", "Row Row Row Your Bod", "Lat Night Show",
            "Back to the Future", "Deadlift Society", "Bicep Bistro", "Pulling Strings", "Row-mantic Evening",
            "The Lat Pack", "Back in Business", "Curl Power", "Pull Request", "Got Your Back", "Rows Before Bros"
        ),
        WorkoutKind.LEGS to listOf(
            "Quadfather", "Squat Happens", "Never Skip", "Leg Day (Survived)", "Thigh Society",
            "Hamstring Theory", "Squat Goals", "Calf Raise Your Hand", "Glute Expectations",
            "Knees Weak, Arms Spaghetti", "Wheel Day", "Leg-endary", "Lunge Lizard", "The Stairs Are Lava", "Booty Camp"
        ),
        WorkoutKind.UPPER to listOf(
            "Upper Management", "Top Shelf", "Upper Echelon", "Shirt Stretcher", "Sleeve Season",
            "Above the Belt", "Upper Cut", "Torso Torment", "The Top Half", "Upper Class"
        ),
        WorkoutKind.LOWER to listOf(
            "Lower Expectations", "Lower Than Low", "Down Under", "Ground Floor", "The Bottom Line",
            "Lower Your Standards", "Base Camp", "Below Deck", "Floor It", "Lower Level"
        ),
        WorkoutKind.FULL to listOf(
            "Full Send", "The Whole Enchilada", "Head to Toe", "All of the Above", "Full Body Panic",
            "Everything Everywhere All at Once", "The Works", "Total Package", "No Muscle Left Behind", "Grand Tour"
        ),
        WorkoutKind.MIXED to listOf(
            "Choose Your Own Adventure", "A Bit of Everything", "Chaos Theory", "Gym Buffet", "Freestyle Session",
            "Improv Night", "Mystery Box", "The Sampler Platter", "Off Script", "Wildcard"
        )
    )

    private val OPENERS = listOf(
        "Operation", "Certified", "The Return of", "Attack of", "Midweek", "Ultimate", "Deluxe", "Classic", "Unhinged", "Legendary"
    )
    private val ENDINGS = listOf(
        "Part II", "Director's Cut", "(Survived)", "Final Boss Edition", "Remastered", "Deluxe Edition",
        "Electric Boogaloo", "The Sequel", "Unplugged", "Live in Concert"
    )

    // {s} = type ("Push"), {c} = a core name, {w} = a weight, {x} = an exercise,
    // {n} = session number, {day} = weekday.
    private val PR = listOf(
        "{s}: PR Edition", "New Record: {c}", "{c}: Record Breaker", "The {w} kg Glow-Up",
        "Personal Best of {s}", "{s} Hall of Fame", "Plot Twist: PR", "Fresh PR, Who Dis"
    )
    private val BIGGEST = listOf(
        "{c}: Final Boss", "The Heaviest {s} Ever", "{s}: Maximum Overdrive", "Volume Turned to 11",
        "Biggest {s} Yet", "{c}: Extended Universe"
    )
    private val COMEBACK = listOf(
        "The Return of the {s}", "{s}: The Comeback", "Guess Who's Back ({s})", "{c} Reunion Tour",
        "{s} Strikes Back", "Back From the Couch"
    )
    private val SHORT = listOf(
        "{s} Speedrun", "{s} Express", "Quick {s}, No Lunch", "{c} (Short Version)", "{s} in a Hurry",
        "The {s} Sprint", "TL;DR {s}"
    )
    private val LONG = listOf(
        "{c}: Extended Cut", "The Never-Ending {s}", "{s}: The Long Haul", "{c} Marathon", "Overtime {s}",
        "{s} Till Closing"
    )
    private val LATE = listOf(
        "Midnight {s}", "{s} After Dark", "Late Night {c}", "Night Shift {s}", "{s} by Moonlight",
        "The {day} Night {s} Show"
    )
    private val EARLY = listOf(
        "Sunrise {s}", "{s} Before Breakfast", "Early Bird {s}", "Dawn of the {s}", "{s} & Coffee", "Rise and {s}"
    )
    private val NUMBERED = listOf(
        "The {w} kg Incident", "{day} {s} Special", "{s} Day No. {n}", "{x} Appreciation Society",
        "{x} & Friends", "{w} kg of Pure {s}", "Episode {n}: {c}"
    )

    // ---------------------------------------------------------------- rules

    /**
     * A name for the session. Special moments win, in this order: a PR, the
     * biggest session of its kind, a comeback, short / long, late / early.
     * Otherwise about a third of names use the session's real numbers and the
     * rest mix the type's names with an opener or ending. Names used recently
     * are skipped.
     */
    fun suggest(facts: SessionFacts, random: Random = Random.Default): SuggestedName {
        val used = recent().map { it.lowercase() }.toSet()
        val s = facts.kind.label
        val core = CORE.getValue(facts.kind)
        val hour = facts.startedAt.hour
        val weekday = facts.startedAt.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

        val special: Pair<List<String>, String>? = when {
            facts.prExercise != null ->
                PR to "🏆 New PR: ${facts.prExercise}" + (facts.prWeightKg?.let { " ${formatDecimal(it)} kg" } ?: "")
            facts.biggestEver -> BIGGEST to "Your biggest ${s.lowercase()} session yet - ${facts.totalSets} sets"
            facts.comebackDays != null -> COMEBACK to "First session in ${facts.comebackDays} days - welcome back"
            facts.durationMinutes in 1..34 -> SHORT to "In and out in ${facts.durationMinutes} min"
            facts.durationMinutes >= 90 -> LONG to "${durationLabel(facts.durationMinutes)} - that's dedication"
            hour >= 22 || hour < 4 -> LATE to "Started at ${timeLabel(facts.startedAt)}"
            hour in 4..6 -> EARLY to "Started at ${timeLabel(facts.startedAt)}"
            else -> null
        }

        val hasWeight = facts.topWeightKg != null && facts.topWeightKg > 0.0 && facts.topExercise != null
        fun fill(template: String): String? {
            if (!hasWeight && ("{w}" in template || "{x}" in template)) return null
            val weight = (if (special?.first === PR) facts.prWeightKg else null) ?: facts.topWeightKg
            return template
                .replace("{s}", s)
                .replace("{c}", core.random(random))
                .replace("{w}", weight?.let { formatDecimal(it) } ?: "")
                .replace("{x}", facts.topExercise.orEmpty())
                .replace("{n}", facts.sessionNumber.toString())
                .replace("{day}", weekday)
        }

        repeat(40) {
            val name: String?
            val reason: String
            if (special != null && random.nextFloat() < 0.85f) {
                name = fill(special.first.random(random))
                reason = special.second
            } else {
                val roll = random.nextFloat()
                name = when {
                    roll < 0.33f -> fill(NUMBERED.random(random))
                    roll < 0.55f -> "${OPENERS.random(random)} ${core.random(random)}"
                    roll < 0.75f -> "${core.random(random)}: ${ENDINGS.random(random)}"
                    else -> core.random(random)
                }
                reason = "${s} session #${facts.sessionNumber}"
            }
            if (name != null && name.length <= 60 && name.lowercase() !in used) return SuggestedName(name, reason)
        }
        return SuggestedName(core.random(random), "${s} session #${facts.sessionNumber}")
    }

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
        val muscleSets = doneSets.groupingBy { it.first.muscleGroup.uppercase() }.eachCount()
        val kind = workoutKind(muscleSets, plan)

        val top = doneSets
            .filter { !it.first.isBodyweight }
            .maxByOrNull { it.second.kg.toDoubleOrNull() ?: 0.0 }
            ?.takeIf { (it.second.kg.toDoubleOrNull() ?: 0.0) > 0.0 }

        val previous = history.filter { it.id != workoutId }
        val sameKind = previous.filter { it.kind(plan) == kind }
        val biggest = sameKind.size >= 3 && doneSets.size > (sameKind.maxOfOrNull { it.totalSets } ?: Int.MAX_VALUE)

        val today = now.toLocalDate()
        val lastDate = previous.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
            .filter { it.isBefore(today) }
            .maxOrNull()
        val gap = lastDate?.let { ChronoUnit.DAYS.between(it, today).toInt() }

        val startedAt = now.minusSeconds(elapsedSeconds.toLong())
        return SessionFacts(
            kind = kind,
            prExercise = prExercise,
            prWeightKg = prWeightKg,
            biggestEver = biggest,
            comebackDays = gap?.takeIf { it >= 10 },
            durationMinutes = elapsedSeconds / 60,
            startedAt = startedAt,
            topExercise = top?.first?.name,
            topWeightKg = top?.second?.kg?.toDoubleOrNull(),
            sessionNumber = sameKind.size + 1,
            totalSets = doneSets.size
        )
    }
}
