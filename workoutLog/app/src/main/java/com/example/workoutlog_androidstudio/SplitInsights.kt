package com.example.workoutlog_androidstudio

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields

/*
 * Works out what each past workout was from the muscles it hit (not its
 * name), what's up next given what's still recovering, and what order the
 * user really trains their split days in.
 *
 * Muscle names are the exercise list's, upper-cased: CHEST, SHOULDERS,
 * TRICEPS, LATS, MIDDLE BACK, LOWER BACK, TRAPS, BICEPS, FOREARMS,
 * QUADRICEPS, HAMSTRINGS, GLUTES, CALVES, ADDUCTORS, ABDUCTORS (+ ABDOMINALS
 * and NECK, which are ignored - they turn up in any kind of workout).
 */

private val PUSH_MUSCLES = setOf("CHEST", "SHOULDERS", "TRICEPS")
private val BACK_MUSCLES = setOf("LATS", "MIDDLE BACK", "LOWER BACK", "TRAPS")
private val ARM_MUSCLES = setOf("BICEPS", "TRICEPS", "FOREARMS")
private val PULL_MUSCLES = BACK_MUSCLES + setOf("BICEPS", "FOREARMS")
private val LEG_MUSCLES = setOf("QUADRICEPS", "HAMSTRINGS", "GLUTES", "CALVES", "ADDUCTORS", "ABDUCTORS")
private val ALL_MUSCLES = PUSH_MUSCLES + PULL_MUSCLES + LEG_MUSCLES
private val IGNORED_MUSCLES = setOf("ABDOMINALS", "NECK")

/** Words in a split day's name -> the muscles that day trains. */
private val MUSCLES_BY_WORD: Map<String, Set<String>> = mapOf(
    "push" to PUSH_MUSCLES,
    "pull" to PULL_MUSCLES,
    "leg" to LEG_MUSCLES, "legs" to LEG_MUSCLES, "lower" to LEG_MUSCLES,
    "upper" to PUSH_MUSCLES + PULL_MUSCLES,
    "full" to ALL_MUSCLES, "body" to ALL_MUSCLES,
    "chest" to setOf("CHEST"), "pecs" to setOf("CHEST"),
    "back" to BACK_MUSCLES,
    "shoulder" to setOf("SHOULDERS"), "shoulders" to setOf("SHOULDERS"), "delts" to setOf("SHOULDERS"),
    "arm" to ARM_MUSCLES, "arms" to ARM_MUSCLES,
    "biceps" to setOf("BICEPS"), "bis" to setOf("BICEPS"),
    "triceps" to setOf("TRICEPS"), "tris" to setOf("TRICEPS"),
    "quads" to setOf("QUADRICEPS"), "hamstrings" to setOf("HAMSTRINGS"), "hams" to setOf("HAMSTRINGS"),
    "glutes" to setOf("GLUTES"), "calves" to setOf("CALVES"), "traps" to setOf("TRAPS")
)

/** A muscle needs this many days before it's trained hard again (2 = the
 *  common ~48 h guideline: trained Monday -> ready Wednesday). */
private const val RECOVERY_DAYS = 2L

/** A muscle only counts as "trained" in a workout if it got at least this
 *  share of the sets - a few face pulls on pull day don't make the next
 *  push day "too soon". */
private const val TRAINED_SHARE = 0.25

/** How much of a workout has to land on a split day's muscles for the
 *  workout to count as that day. */
private const val MATCH_SHARE = 0.55

/**
 * Sets per muscle for a workout. Uses the server's per-muscle set counts;
 * on an older server (no counts yet) each muscle tag counts as one set.
 */
fun WorkoutSummary.muscleWork(): Map<String, Int> {
    val raw = muscleSets.ifEmpty { tags.associateWith { 1 } }
    return raw
        .mapKeys { it.key.trim().uppercase() }
        .filterKeys { it.isNotEmpty() && it !in IGNORED_MUSCLES }
        .filterValues { it > 0 }
}

private fun WorkoutSummary.localDate(): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()

/** Oldest first; same day -> in the order they were logged. */
private fun chronological(workouts: List<WorkoutSummary>) =
    workouts.sortedWith(compareBy({ it.date }, { it.id }))

/**
 * The muscles each split day trains: from the words in its name ("Chest +
 * Back"), or - for a custom name it doesn't know ("Day A") - learned from
 * the muscles of past workouts that had that name.
 */
private fun musclesForDay(day: String, history: List<WorkoutSummary>): Set<String> {
    val words = normalizeWorkoutName(day).split(' ').filter { it.isNotEmpty() }
    val fromName = words.flatMap { MUSCLES_BY_WORD[it].orEmpty() }.toSet()
    if (fromName.isNotEmpty()) return fromName

    val key = normalizeWorkoutName(day)
    val learned = HashMap<String, Int>()
    history.filter { normalizeWorkoutName(it.title) == key }
        .forEach { w -> w.muscleWork().forEach { (m, n) -> learned[m] = (learned[m] ?: 0) + n } }
    val total = learned.values.sum().toDouble()
    if (total == 0.0) return emptySet()
    return learned.filterValues { it / total >= 0.15 }.keys
}

/** Does a workout's name say it was this day ("Pull Day" -> Pull)? */
private fun nameMatches(title: String, day: String): Boolean {
    val words = normalizeWorkoutName(title).split(' ').toSet()
    val dayWords = normalizeWorkoutName(day).split(' ').filter { it.isNotEmpty() }
    return dayWords.isNotEmpty() && words.containsAll(dayWords)
}

/**
 * Which split day a workout was, or null if it doesn't clearly fit one
 * (a mixed or full-body session, say). Muscles decide; the name only
 * breaks a close call, or decides when a workout has no muscle data.
 */
private fun classify(
    workout: WorkoutSummary,
    days: List<String>,
    dayMuscles: Map<String, Set<String>>
): String? {
    val work = workout.muscleWork()
    val total = work.values.sum().toDouble()
    if (total == 0.0) return days.firstOrNull { nameMatches(workout.title, it) }

    val scores = days.associateWith { day ->
        val muscles = dayMuscles[day].orEmpty()
        work.filterKeys { it in muscles }.values.sum() / total
    }
    // The name agrees and the muscles at least partly back it up.
    days.firstOrNull { nameMatches(workout.title, it) && (scores[it] ?: 0.0) >= 0.4 }?.let { return it }

    // Otherwise the clearest muscle match. A day whose muscles cover more
    // (Upper vs Push) wins a tie only if it's actually ahead.
    val ranked = scores.entries.sortedByDescending { it.value }
    val best = ranked.firstOrNull() ?: return null
    val runnerUp = ranked.getOrNull(1)?.value ?: 0.0
    return best.key.takeIf { best.value >= MATCH_SHARE && best.value - runnerUp >= 0.1 }
}

/** Each workout paired with the split day it was (null = didn't fit one). */
private fun classifyHistory(history: List<WorkoutSummary>, days: List<String>): List<Pair<WorkoutSummary, String?>> {
    val dayMuscles = days.associateWith { musclesForDay(it, history) }
    return history.map { it to classify(it, days, dayMuscles) }
}

/**
 * What to train next.
 * [display] is what the user sees ("Pull day"), [startLabel] is what
 * starting it uses to find a template ("Pull"), [basedOn] says why, and
 * [recovering] is true when every option was trained too recently - the
 * suggestion is the most-rested one, but a rest day is the better call.
 */
data class UpNext(
    val display: String,
    val startLabel: String,
    val basedOn: String,
    val recovering: Boolean = false
)

/**
 * Works out the next workout from what was actually done, not the weekday.
 *
 * With a split: every split day whose muscles are recovered (none of them
 * trained hard in the last [RECOVERY_DAYS] days) is a candidate, and the
 * one done longest ago wins. Ties - e.g. two days never done yet - go to
 * whichever comes first after the last workout in the user's order. So
 * Legs, Pull, Push -> Legs (not Push again, and not back to the top of
 * "Push / Pull / Legs").
 *
 * Without a split: learn from history - find the previous time the latest
 * workout was done and suggest whatever came right after it then.
 */
fun computeUpNext(
    workouts: List<WorkoutSummary>,
    plan: TrainingPlan?,
    today: LocalDate = LocalDate.now()
): UpNext? {
    val days = plan?.orderedDays().orEmpty().distinct()
    if (days.isEmpty()) return upNextFromNames(chronological(workouts))
    if (days.size == 1) return UpNext(plannedWorkoutName(days[0]), days[0], "your split")
    return explainUpNext(workouts, plan, today)?.upNext
}

/** One past workout, as Up next read it - shown on the Training plan screen. */
data class ReadWorkout(
    val date: LocalDate?,
    val title: String,
    /** The split day it counted as, or null if it didn't clearly fit one. */
    val day: String?,
    /** Its muscles with set counts, most first (ABDOMINALS/NECK left out). */
    val muscles: List<Pair<String, Int>>,
    /** true when the server sent no set counts and tags were used instead. */
    val fromTagsOnly: Boolean
)

/** Where one split day stands. */
data class DayStatus(
    val day: String,
    /** Last workout that counted as this day. */
    val lastDone: LocalDate?,
    /** Last time any of its muscles was trained hard (maybe in another workout). */
    val musclesLastTrained: LocalDate?,
    val recovered: Boolean
)

/** Everything behind an Up next pick, so the user can see why. */
data class UpNextExplanation(
    val upNext: UpNext,
    /** The day the pick is for: today, or tomorrow if they trained today. */
    val sessionDate: LocalDate,
    val days: List<DayStatus>,
    /** Most recent first. */
    val workouts: List<ReadWorkout>
)

/**
 * The Up next pick for a split plan plus the reasoning behind it. null for
 * no split / a one-day split (nothing to choose between).
 */
fun explainUpNext(
    workouts: List<WorkoutSummary>,
    plan: TrainingPlan?,
    today: LocalDate = LocalDate.now()
): UpNextExplanation? {
    val history = chronological(workouts)
    val days = plan?.orderedDays().orEmpty().distinct()
    if (days.size < 2) return null

    // Already trained today -> plan for the next session (tomorrow).
    val sessionDate = if (history.any { it.localDate() == today }) today.plusDays(1) else today

    val classified = classifyHistory(history, days)
    val lastDone = HashMap<String, LocalDate>()
    classified.forEach { (w, day) -> if (day != null) w.localDate()?.let { lastDone[day] = it } }

    // When each muscle was last trained hard - from every workout, matched
    // to a split day or not.
    val lastTrained = HashMap<String, LocalDate>()
    history.forEach { w ->
        val date = w.localDate() ?: return@forEach
        val work = w.muscleWork()
        val total = work.values.sum().toDouble()
        work.forEach { (muscle, sets) ->
            if (total > 0 && sets / total >= TRAINED_SHARE) lastTrained[muscle] = date
        }
    }

    val dayMuscles = days.associateWith { musclesForDay(it, history) }
    fun musclesLast(day: String) = dayMuscles[day].orEmpty().mapNotNull { lastTrained[it] }.maxOrNull()
    // How long a day has rested: the later of "last done as this day" and
    // "its muscles last trained hard". So a push workout that didn't match
    // Push (odd mix of exercises) still counts as push work.
    fun lastWorked(day: String): LocalDate? = listOfNotNull(lastDone[day], musclesLast(day)).maxOrNull()
    fun isRecovered(day: String): Boolean {
        val recent = lastWorked(day) ?: return true
        return ChronoUnit.DAYS.between(recent, sessionDate) >= RECOVERY_DAYS
    }

    // Position in the order after the most recent matched workout - breaks ties.
    val lastDay = classified.lastOrNull { it.second != null }?.second
    val start = lastDay?.let { days.indexOf(it) + 1 } ?: 0
    fun orderRank(day: String) = Math.floorMod(days.indexOf(day) - start, days.size)

    val byRest = compareBy<String>({ lastWorked(it) ?: LocalDate.MIN }, { orderRank(it) })
    val ready = days.filter { isRecovered(it) }
    val pick = ready.minWithOrNull(byRest) ?: days.minWithOrNull(byRest) ?: days.first()

    val basedOn = when {
        ready.isEmpty() -> "everything was trained in the last couple of days"
        lastWorked(pick) == null -> "not done yet"
        else -> "last trained ${lastWorked(pick)}"
    }
    val upNext = UpNext(
        display = plannedWorkoutName(pick),
        startLabel = pick,
        basedOn = basedOn,
        recovering = ready.isEmpty()
    )
    return UpNextExplanation(
        upNext = upNext,
        sessionDate = sessionDate,
        days = days.map { DayStatus(it, lastDone[it], musclesLast(it), isRecovered(it)) },
        workouts = classified.asReversed().map { (w, day) ->
            ReadWorkout(
                date = w.localDate(),
                title = w.title,
                day = day,
                muscles = w.muscleWork().entries.sortedByDescending { it.value }.map { it.key to it.value },
                fromTagsOnly = w.muscleSets.isEmpty()
            )
        }
    )
}

/** No split: the workout that followed the latest one the last time it was done. */
private fun upNextFromNames(history: List<WorkoutSummary>): UpNext? {
    val named = history.filter { normalizeWorkoutName(it.title).isNotEmpty() }
    val latest = named.lastOrNull() ?: return null
    val latestKey = normalizeWorkoutName(latest.title)
    val previousSame = named.dropLast(1).indexOfLast { normalizeWorkoutName(it.title) == latestKey }
    if (previousSame < 0) return null
    val next = named[previousSame + 1]
    return UpNext(display = next.title, startLabel = next.title, basedOn = "after ${latest.title}")
}

/**
 * The order the user actually trains their split days in, if it's clearly
 * different from the plan's - e.g. the plan says Push / Pull / Legs but the
 * last few weeks went Legs -> Pull -> Push. null when there's not enough
 * history (two full rounds), no clear pattern, or it already matches.
 *
 * Looks at the last 6 weeks, counts which day follows which, and builds a
 * cycle from the most common follow-ups. Order is cyclic (Pull, Push, Legs
 * is the same as Legs, Pull, Push), so the result starts with the day the
 * user most often opens their week with.
 */
fun detectUsualOrder(
    workouts: List<WorkoutSummary>,
    plan: TrainingPlan?,
    today: LocalDate = LocalDate.now()
): List<String>? {
    val days = plan?.orderedDays().orEmpty().distinct()
    if (days.size < 3) return null // with 2 days, either way round is the same cycle

    val recent = chronological(workouts).filter { w ->
        val d = w.localDate() ?: return@filter false
        !d.isBefore(today.minusWeeks(6))
    }
    val sequence = classifyHistory(recent, days)
        .mapNotNull { (w, day) -> day?.let { w to it } }
        // A day done twice in a row (two Push sessions) isn't a transition.
        .fold(mutableListOf<Pair<WorkoutSummary, String>>()) { acc, item ->
            if (acc.lastOrNull()?.second != item.second) acc.add(item); acc
        }
    if (sequence.size < days.size * 2) return null

    val transitions = HashMap<Pair<String, String>, Int>()
    sequence.zipWithNext { a, b ->
        val key = a.second to b.second
        transitions[key] = (transitions[key] ?: 0) + 1
    }
    val total = transitions.values.sum()
    if (total == 0) return null

    // Follow the most common next day from each day, never revisiting one.
    val cycle = mutableListOf(days[0])
    var agreeing = 0
    while (cycle.size < days.size) {
        val from = cycle.last()
        val next = days.filter { it !in cycle }
            .maxByOrNull { transitions[from to it] ?: 0 } ?: return null
        val count = transitions[from to next] ?: 0
        val outgoing = transitions.filterKeys { it.first == from }.values.sum()
        if (count == 0 || count < outgoing * 0.6) return null
        agreeing += count
        cycle.add(next)
    }
    agreeing += transitions[cycle.last() to cycle.first()] ?: 0
    if (agreeing < total * 0.7) return null

    if (sameCycle(cycle, days)) return null

    // Start the suggestion with the day that usually opens the week.
    val weekOpeners = sequence
        .groupBy { (w, _) -> w.localDate()?.let { it.get(IsoFields.WEEK_BASED_YEAR) * 100 + it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) } }
        .values.map { it.first().second }
    val opener = weekOpeners.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: cycle.first()
    return rotateTo(cycle, opener)
}

/** True when two orders are the same cycle (Pull, Legs, Push == Push, Pull, Legs). */
fun sameCycle(a: List<String>, b: List<String>): Boolean {
    if (a.size != b.size || a.toSet() != b.toSet()) return false
    if (a.isEmpty()) return true
    return rotateTo(a, b.first()) == b
}

private fun rotateTo(order: List<String>, first: String): List<String> {
    val i = order.indexOf(first)
    return if (i <= 0) order else order.drop(i) + order.take(i)
}

/** "Legs → Pull → Push" */
fun orderLabel(order: List<String>): String = order.joinToString(" → ")

/**
 * The most recent past workout "like" one named [name] - for "Add from your
 * last Pull day". Same name first ("Pull Day" = "pull"); otherwise, if
 * [name] names one of the plan's split days, the last workout whose muscles
 * made it that day (so a "Back & Bis" session still counts as Pull).
 */
fun findPreviousSimilarWorkout(name: String, workouts: List<WorkoutSummary>, plan: TrainingPlan?): WorkoutSummary? {
    val history = chronological(workouts)
    val key = normalizeWorkoutName(name)
    if (key.isNotEmpty()) history.lastOrNull { normalizeWorkoutName(it.title) == key }?.let { return it }
    val days = plan?.orderedDays().orEmpty().distinct()
    val day = days.firstOrNull { nameMatches(name, it) } ?: return null
    return classifyHistory(history, days).lastOrNull { it.second == day }?.first
}

// ---------------------------------------------------------------------------
// Heatmap: how full each day's training was, and which planned days were missed.
// ---------------------------------------------------------------------------

/** With no history to compare against yet, this many sets counts as a full session. */
private const val DEFAULT_FULL_SETS = 15

/** How far back "your usual" looks. */
private const val USUAL_WEEKS = 8L

private fun median(values: List<Int>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
}

private fun WorkoutSummary.setCount(): Int = if (totalSets > 0) totalSets else muscleSets.values.sum()

/**
 * How complete each day's training was, 0..1, for the heatmap's shading.
 *
 * Each workout's sets are compared with the user's usual for that kind of
 * workout (Push / Pull / Legs ..., read from its muscles like Up next): the
 * median sets of that type over the last [USUAL_WEEKS] weeks. With fewer
 * than 3 of that type it compares with their usual across all workouts, and
 * with fewer than 3 workouts at all, with [DEFAULT_FULL_SETS] sets.
 *
 * Steps: about 90% of usual or more = 1 (full), 60-90% = 0.75, 30-60% = 0.5,
 * anything less = 0.3. Two workouts on one day add up.
 */
fun heatmapLevels(
    workouts: List<WorkoutSummary>,
    plan: TrainingPlan?,
    today: LocalDate = LocalDate.now()
): Map<LocalDate, Float> {
    val history = chronological(workouts)
    // A plan's split if there is one; otherwise Push / Pull / Legs is just
    // used to group similar sessions, so a short Legs day is compared with
    // other Legs days.
    val days = plan?.orderedDays().orEmpty().distinct().ifEmpty { TrainingSplit.PPL.days }
    val classified = classifyHistory(history, days)

    val recentFrom = today.minusWeeks(USUAL_WEEKS)
    val recent = classified.filter { (w, _) -> w.localDate()?.let { !it.isBefore(recentFrom) } == true && w.setCount() > 0 }
    val overall = recent.map { it.first.setCount() }
    val usualOverall = if (overall.size >= 3) median(overall) else DEFAULT_FULL_SETS.toDouble()
    val usualByType = recent.filter { it.second != null }
        .groupBy({ it.second!! }, { it.first.setCount() })
        .filterValues { it.size >= 3 }
        .mapValues { median(it.value) }

    val ratioByDate = HashMap<LocalDate, Double>()
    classified.forEach { (w, type) ->
        val date = w.localDate() ?: return@forEach
        val sets = w.setCount()
        val usual = type?.let { usualByType[it] } ?: usualOverall
        val ratio = if (sets == 0) 0.1 else sets / usual.coerceAtLeast(1.0)
        ratioByDate[date] = (ratioByDate[date] ?: 0.0) + ratio
    }
    return ratioByDate.mapValues { (_, r) ->
        when {
            r >= 0.9 -> 1f
            r >= 0.6 -> 0.75f
            r >= 0.3 -> 0.5f
            else -> 0.3f
        }
    }
}

/**
 * Planned training days that were missed and not made up - outlined on the
 * heatmap and calendar.
 *
 * Only whole weeks from [TrainingPlan.trainingDaysSince] (a Monday) on are
 * judged, and never today (it isn't over). Counted per week: if the week so far has fewer workout days than
 * planned days, that many planned-but-empty days are marked, newest first.
 * So training on a rest day makes up for a skipped one.
 */
fun missedDays(
    workouts: List<WorkoutSummary>,
    plan: TrainingPlan?,
    today: LocalDate = LocalDate.now()
): Set<LocalDate> {
    plan ?: return emptySet()
    if (plan.trainingDays.isEmpty()) return emptySet()
    // Whole weeks: judging starts on the Monday of the chosen week.
    val chosen = plan.trainingDaysSince?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?.let { weekStartOf(it) } ?: return emptySet()
    // Never before the week after the plan was set up.
    val earliest = plan.earliestTrackedWeek()
    val since = if (earliest != null && earliest.isAfter(chosen)) earliest else chosen
    if (!since.isBefore(today)) return emptySet()

    val trainedDates = workouts.mapNotNull { it.localDate() }.toSet()
    val missed = HashSet<LocalDate>()
    var weekStart = since.minusDays((since.dayOfWeek.value - 1).toLong())
    while (weekStart.isBefore(today)) {
        val judged = (0L..6L).map { weekStart.plusDays(it) }
            .filter { !it.isBefore(since) && it.isBefore(today) }
        val planned = judged.filter { plan.isTrainingDay(it.dayOfWeek) }
        val done = judged.count { it in trainedDates }
        val short = planned.size - done
        if (short > 0) {
            planned.filter { it !in trainedDates }.takeLast(short).forEach { missed.add(it) }
        }
        weekStart = weekStart.plusWeeks(1)
    }
    return missed
}

/** What kind of session a set of muscles adds up to - for naming workouts. */
enum class WorkoutKind(val label: String) {
    PUSH("Push"), PULL("Pull"), LEGS("Legs"), UPPER("Upper"), LOWER("Lower"), FULL("Full Body"), MIXED("Mixed")
}

/**
 * Push / Pull / Legs / Upper / Lower / Full body / Mixed from sets per
 * muscle. Over 60% on one group names it after that group; mostly upper
 * body with little leg work is Upper; a real share of both is Full body.
 * Leg days are "Lower" for someone on an Upper / Lower split.
 */
fun workoutKind(muscleSets: Map<String, Int>, plan: TrainingPlan?): WorkoutKind {
    val work = muscleSets.mapKeys { it.key.trim().uppercase() }.filterKeys { it !in IGNORED_MUSCLES }
    val total = work.values.sum().toDouble()
    if (total <= 0.0) return WorkoutKind.MIXED
    fun share(group: Set<String>) = work.filterKeys { it in group }.values.sum() / total
    val push = share(PUSH_MUSCLES)
    val pull = share(PULL_MUSCLES)
    val legs = share(LEG_MUSCLES)
    return when {
        legs >= 0.6 -> if (plan?.split == TrainingSplit.UPPER_LOWER) WorkoutKind.LOWER else WorkoutKind.LEGS
        push >= 0.6 -> WorkoutKind.PUSH
        pull >= 0.6 -> WorkoutKind.PULL
        legs <= 0.2 && push + pull >= 0.75 -> WorkoutKind.UPPER
        legs >= 0.25 && push + pull >= 0.4 -> WorkoutKind.FULL
        else -> WorkoutKind.MIXED
    }
}

/** [workoutKind] for a past workout. */
fun WorkoutSummary.kind(plan: TrainingPlan?): WorkoutKind = workoutKind(muscleWork(), plan)

/**
 * The most recent past workout that was the same kind of day as what's being
 * logged now ([muscleSets] - sets per muscle so far), and that day's name -
 * for "From your last Legs day" once a few exercises are in. Days are the
 * plan's split days, or Push / Pull / Legs without a plan. null when the
 * exercises so far don't clearly add up to one day.
 */
fun findPreviousWorkoutForMuscles(
    muscleSets: Map<String, Int>,
    workouts: List<WorkoutSummary>,
    plan: TrainingPlan?
): Pair<String, WorkoutSummary>? {
    if (muscleSets.values.sum() <= 0) return null
    val days = plan?.orderedDays().orEmpty().distinct().ifEmpty { TrainingSplit.PPL.days }
    val current = WorkoutSummary(
        id = -1, dateLabel = "", date = LocalDate.now().toString(), title = "", duration = "",
        totalSets = muscleSets.values.sum(), exerciseCount = muscleSets.size, volumeLabel = "",
        tags = muscleSets.keys.toList(), muscleSets = muscleSets
    )
    val history = chronological(workouts)
    val dayMuscles = days.associateWith { musclesForDay(it, history) }
    val day = classify(current, days, dayMuscles) ?: return null
    val previous = classifyHistory(history, days).lastOrNull { it.second == day }?.first ?: return null
    return day to previous
}

/**
 * Muscles trained hard in the last [RECOVERY_DAYS] days -> days since
 * (0 = today, 1 = yesterday). Same "hard" rule as Up next (at least
 * [TRAINED_SHARE] of a workout's sets), so the Recovery body map agrees
 * with it. ABDOMINALS is kept here (it has a shape on the map) but, like
 * Up next, doesn't count towards a workout's total.
 */
fun recentlyTrainedMuscles(workouts: List<WorkoutSummary>, today: LocalDate = LocalDate.now()): Map<String, Long> {
    val result = HashMap<String, Long>()
    workouts.forEach { w ->
        val date = w.localDate() ?: return@forEach
        val days = ChronoUnit.DAYS.between(date, today)
        if (days < 0 || days >= RECOVERY_DAYS) return@forEach
        val total = w.muscleWork().values.sum().toDouble()
        if (total <= 0) return@forEach
        val all = w.muscleSets.ifEmpty { w.tags.associateWith { 1 } }
            .mapKeys { it.key.trim().uppercase() }
            .filterValues { it > 0 }
        all.forEach { (muscle, sets) ->
            if (sets / total >= TRAINED_SHARE) result[muscle] = minOf(result[muscle] ?: days, days)
        }
    }
    return result
}
