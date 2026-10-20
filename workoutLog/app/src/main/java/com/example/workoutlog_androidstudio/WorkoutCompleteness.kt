package com.example.workoutlog_androidstudio

/*
 * Was a workout complete? Judged by whether each muscle of that day was hit
 * in most of its parts - e.g. a push day wants 2 of the 3 chest parts
 * (upper / middle / lower), 2 of the 3 delt heads and 2 of the 3 triceps
 * heads, each with about 2+ sets. Drives the heatmap's shading.
 *
 * The part an exercise works comes from its name ("Incline Dumbbell Press"
 * -> upper chest); a name that says nothing falls back to the muscle's most
 * common part (a "Chest Machine" counts as middle chest).
 */

/** A part of a muscle that exercises can favour. */
enum class MusclePart {
    UPPER_CHEST, MID_CHEST, LOWER_CHEST,
    FRONT_DELT, SIDE_DELT, REAR_DELT,
    TRI_LONG, TRI_LATERAL, TRI_MEDIAL,
    LATS, UPPER_BACK, LOWER_BACK,
    BI_LONG, BI_SHORT, BRACHIALIS,
    SQUAT, KNEE_EXTENSION,
    HINGE, LEG_CURL,
    GLUTES, CALVES
}

/** A muscle a day trains: its parts, and how many of them make it "done". */
enum class MuscleTarget(val parts: List<MusclePart>, val needed: Int) {
    CHEST(listOf(MusclePart.UPPER_CHEST, MusclePart.MID_CHEST, MusclePart.LOWER_CHEST), 2),
    SHOULDERS(listOf(MusclePart.FRONT_DELT, MusclePart.SIDE_DELT, MusclePart.REAR_DELT), 2),
    TRICEPS(listOf(MusclePart.TRI_LONG, MusclePart.TRI_LATERAL, MusclePart.TRI_MEDIAL), 2),
    BACK(listOf(MusclePart.LATS, MusclePart.UPPER_BACK, MusclePart.LOWER_BACK), 2),
    REAR_DELTS(listOf(MusclePart.REAR_DELT), 1),
    BICEPS(listOf(MusclePart.BI_LONG, MusclePart.BI_SHORT, MusclePart.BRACHIALIS), 2),
    QUADS(listOf(MusclePart.SQUAT, MusclePart.KNEE_EXTENSION), 1),
    HAMSTRINGS(listOf(MusclePart.HINGE, MusclePart.LEG_CURL), 1),
    GLUTES(listOf(MusclePart.GLUTES), 1),
    CALVES(listOf(MusclePart.CALVES), 1)
}

/** Sets on a part to count it as fully hit. */
private const val SETS_FOR_PART = 2.0

/** Muscles that only help in an exercise (front delts on bench) get this
 *  much of a set per set... */
private const val ASSIST_SHARE = 0.5

/** ...but never more than this many sets in total, so helping alone can't
 *  make a part "done" (10 sets of bench don't finish your triceps). */
private const val ASSIST_CAP = 1.0

/** What an exercise works: [main] parts get full credit, [assist] half. */
data class ExerciseParts(val main: Set<MusclePart>, val assist: Set<MusclePart> = emptySet())

private fun parts(vararg main: MusclePart, assist: Set<MusclePart> = emptySet()) = ExerciseParts(main.toSet(), assist)

private val PRESS_ASSIST = setOf(MusclePart.FRONT_DELT, MusclePart.TRI_LATERAL, MusclePart.TRI_MEDIAL)
private val TRICEPS_PUSH = setOf(MusclePart.TRI_LATERAL, MusclePart.TRI_MEDIAL)
private val CURL_ASSIST = setOf(MusclePart.BI_LONG, MusclePart.BI_SHORT)

/**
 * The parts an exercise works, from its name (lower-cased words) and its
 * muscle group from the exercise list (CHEST, TRICEPS ...). Checked most
 * specific first - "close-grip bench" before "bench", "upright row" before
 * "row", "romanian deadlift" before "deadlift".
 */
fun exerciseParts(name: String, muscleGroup: String?): ExerciseParts {
    val n = " " + name.lowercase().replace('-', ' ').replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
    val muscle = muscleGroup?.trim()?.uppercase().orEmpty()
    fun has(vararg words: String) = words.any { n.contains(" $it") }
    val isPress = has("press", "bench", "push up", "pushup", "push ups", "pushups")

    return when {
        // ---- Legs / hips first (calf raise isn't a lateral raise) ----
        has("calf", "calves") -> parts(MusclePart.CALVES)
        has("leg extension", "quad extension") -> parts(MusclePart.KNEE_EXTENSION)
        has("leg curl", "hamstring curl", "nordic", "glute ham") -> parts(MusclePart.LEG_CURL)
        // Squats, lunges and hinges are real glute work, so they count for glutes too.
        has("romanian", "rdl", "stiff leg", "stiff legged", "good morning", "single leg deadlift") ->
            parts(MusclePart.HINGE, MusclePart.GLUTES, assist = setOf(MusclePart.LOWER_BACK))
        has("deadlift") -> parts(MusclePart.HINGE, MusclePart.LOWER_BACK, MusclePart.GLUTES, assist = setOf(MusclePart.UPPER_BACK))
        has("hip thrust", "glute bridge", "bridge", "abduct", "glute") ||
            (has("kickback") && muscle == "GLUTES") -> parts(MusclePart.GLUTES)
        has("squat", "leg press", "hack", "lunge", "step up", "split squat", "pistol") ->
            parts(MusclePart.SQUAT, MusclePart.GLUTES)
        has("back extension", "hyperextension", "superman", "reverse hyper") ->
            parts(MusclePart.LOWER_BACK, assist = setOf(MusclePart.HINGE, MusclePart.GLUTES))

        // ---- Shoulders (before chest/back, so "rear delt row" isn't a row) ----
        has("rear delt", "reverse fly", "reverse flye", "reverse pec", "face pull", "rear raise") ->
            parts(MusclePart.REAR_DELT, assist = setOf(MusclePart.UPPER_BACK))
        has("lateral raise", "side raise", "lateral", "y raise", "lu raise") -> parts(MusclePart.SIDE_DELT)
        has("upright row") -> parts(MusclePart.SIDE_DELT, assist = setOf(MusclePart.UPPER_BACK))
        has("front raise", "plate raise") -> parts(MusclePart.FRONT_DELT)
        has("arnold") -> parts(MusclePart.FRONT_DELT, assist = setOf(MusclePart.SIDE_DELT, MusclePart.TRI_LATERAL, MusclePart.TRI_MEDIAL))
        has("overhead press", "shoulder press", "military", "ohp", "push press", "landmine press", "seated press") ||
            (muscle == "SHOULDERS" && isPress) ->
            parts(MusclePart.FRONT_DELT, assist = setOf(MusclePart.SIDE_DELT, MusclePart.TRI_LATERAL, MusclePart.TRI_MEDIAL))

        // ---- Triceps ----
        has("close grip", "narrow grip", "jm press") && isPress -> ExerciseParts(TRICEPS_PUSH, setOf(MusclePart.MID_CHEST))
        has("skull", "lying extension", "lying triceps", "french press", "overhead extension", "overhead triceps",
            "overhead tricep", "tricep extension", "triceps extension", "ez bar extension") && !has("pushdown", "push down") ->
            parts(MusclePart.TRI_LONG, assist = setOf(MusclePart.TRI_LATERAL))
        has("reverse grip pushdown", "reverse grip push down", "underhand pushdown") -> parts(MusclePart.TRI_MEDIAL)
        has("pushdown", "push down", "pressdown", "press down", "kickback") -> ExerciseParts(TRICEPS_PUSH)
        has("dip") && (muscle == "TRICEPS" || has("tricep", "bench dip")) -> ExerciseParts(TRICEPS_PUSH, setOf(MusclePart.LOWER_CHEST))
        has("dip") -> ExerciseParts(setOf(MusclePart.LOWER_CHEST), TRICEPS_PUSH)

        // ---- Chest ----
        has("incline") && (isPress || has("fly", "flye", "flyes", "flies")) ->
            if (isPress) ExerciseParts(setOf(MusclePart.UPPER_CHEST), PRESS_ASSIST) else parts(MusclePart.UPPER_CHEST)
        has("low to high", "low cable", "upward fly") -> parts(MusclePart.UPPER_CHEST)
        has("decline") -> if (isPress) ExerciseParts(setOf(MusclePart.LOWER_CHEST), TRICEPS_PUSH) else parts(MusclePart.LOWER_CHEST)
        has("high to low", "downward fly") -> parts(MusclePart.LOWER_CHEST)
        has("bench", "chest press", "push up", "pushup", "push ups", "pushups", "floor press") && muscle != "TRICEPS" ->
            ExerciseParts(setOf(MusclePart.MID_CHEST), PRESS_ASSIST)
        has("fly", "flye", "flyes", "flies", "pec deck", "crossover", "squeeze press", "svend") -> parts(MusclePart.MID_CHEST)

        // ---- Back ----
        has("pulldown", "pull down", "pull up", "pullup", "pull ups", "pullups", "chin", "pullover", "straight arm", "lat ") ->
            ExerciseParts(setOf(MusclePart.LATS), CURL_ASSIST)
        has("shrug") -> parts(MusclePart.UPPER_BACK)
        has("row", "rows") -> ExerciseParts(setOf(MusclePart.UPPER_BACK), setOf(MusclePart.LATS, MusclePart.BI_LONG, MusclePart.BRACHIALIS))

        // ---- Biceps ----
        has("hammer", "reverse curl", "cross body", "crossbody", "brachialis", "zottman") ->
            parts(MusclePart.BRACHIALIS, assist = setOf(MusclePart.BI_LONG))
        has("incline curl", "incline dumbbell curl", "drag curl", "bayesian", "behind the back") -> parts(MusclePart.BI_LONG)
        has("preacher", "spider", "concentration", "scott", "wide grip curl") -> parts(MusclePart.BI_SHORT)
        has("curl") && muscle != "HAMSTRINGS" -> parts(MusclePart.BI_LONG, MusclePart.BI_SHORT)

        // ---- Nothing in the name: the muscle's most common part ----
        else -> when (muscle) {
            "CHEST" -> parts(MusclePart.MID_CHEST)
            "SHOULDERS" -> parts(MusclePart.SIDE_DELT)
            "TRICEPS" -> ExerciseParts(TRICEPS_PUSH)
            "LATS" -> parts(MusclePart.LATS)
            "MIDDLE BACK", "TRAPS" -> parts(MusclePart.UPPER_BACK)
            "LOWER BACK" -> parts(MusclePart.LOWER_BACK)
            "BICEPS" -> parts(MusclePart.BI_LONG, MusclePart.BI_SHORT)
            "FOREARMS" -> parts(MusclePart.BRACHIALIS)
            "QUADRICEPS", "ADDUCTORS" -> parts(MusclePart.SQUAT)
            "HAMSTRINGS" -> parts(MusclePart.LEG_CURL)
            "GLUTES", "ABDUCTORS" -> parts(MusclePart.GLUTES)
            "CALVES" -> parts(MusclePart.CALVES)
            else -> ExerciseParts(emptySet())
        }
    }
}

/** Sets landing on each part across a workout (assisting sets count half,
 *  up to [ASSIST_CAP]). */
fun partCredit(exercises: List<ExerciseSetCount>): Map<MusclePart, Double> {
    val main = HashMap<MusclePart, Double>()
    val assist = HashMap<MusclePart, Double>()
    exercises.forEach { e ->
        if (e.sets <= 0) return@forEach
        val p = exerciseParts(e.name, e.muscleGroup)
        p.main.forEach { main[it] = (main[it] ?: 0.0) + e.sets }
        (p.assist - p.main).forEach { assist[it] = (assist[it] ?: 0.0) + e.sets * ASSIST_SHARE }
    }
    return (main.keys + assist.keys).associateWith { part ->
        (main[part] ?: 0.0) + (assist[part] ?: 0.0).coerceAtMost(ASSIST_CAP)
    }
}

/** How done one muscle is, 0..1: its best [MuscleTarget.needed] parts, each
 *  counting fully at 2 sets (1 set = half). */
fun targetScore(target: MuscleTarget, credit: Map<MusclePart, Double>): Double {
    val best = target.parts
        .map { ((credit[it] ?: 0.0) / SETS_FOR_PART).coerceAtMost(1.0) }
        .sortedDescending()
        .take(target.needed)
    return best.sum() / target.needed
}

/**
 * The muscles a split day should hit, from the muscles it trains (exercise
 * list names, see SplitInsights) - plus rear delts on a day called "pull".
 */
fun targetsForDay(dayName: String, dayMuscles: Set<String>): List<MuscleTarget> {
    val targets = LinkedHashSet<MuscleTarget>()
    dayMuscles.forEach { m ->
        when (m.trim().uppercase()) {
            "CHEST" -> targets += MuscleTarget.CHEST
            "SHOULDERS" -> targets += MuscleTarget.SHOULDERS
            "TRICEPS" -> targets += MuscleTarget.TRICEPS
            "LATS", "MIDDLE BACK", "LOWER BACK", "TRAPS" -> targets += MuscleTarget.BACK
            "BICEPS" -> targets += MuscleTarget.BICEPS
            "QUADRICEPS" -> targets += MuscleTarget.QUADS
            "HAMSTRINGS" -> targets += MuscleTarget.HAMSTRINGS
            "GLUTES" -> targets += MuscleTarget.GLUTES
            "CALVES" -> targets += MuscleTarget.CALVES
        }
    }
    if (normalizeWorkoutName(dayName).split(' ').contains("pull") && MuscleTarget.SHOULDERS !in targets) {
        targets += MuscleTarget.REAR_DELTS
    }
    return targets.toList()
}

/** How complete a workout was for its day, 0..1 (average over the day's
 *  muscles). null when there's nothing to judge against. */
fun workoutCompleteness(exercises: List<ExerciseSetCount>, targets: List<MuscleTarget>): Double? {
    if (targets.isEmpty() || exercises.isEmpty()) return null
    val credit = partCredit(exercises)
    return targets.sumOf { targetScore(it, credit) } / targets.size
}

/** Heatmap shade for a completeness score: all muscles done = full; about
 *  one muscle short = 0.75; about half = 0.5; less = 0.3. */
fun completenessLevel(score: Double): Float = when {
    score >= 0.9 -> 1f
    score >= 0.62 -> 0.75f
    score >= 0.4 -> 0.5f
    else -> 0.3f
}
