package com.example.workoutlog_androidstudio

import com.example.workoutlog_androidstudio.api.ExerciseResponse

/*
 * Exercise name matching: the search in "Add Exercise", the "did you mean"
 * check before creating a custom exercise, and spotting likely duplicates
 * to merge ("Incline Bicep Curl" vs "Incline Dumbbell Bicep Curl").
 */

/** Spellings and short forms that mean the same word. */
private val SYNONYMS = mapOf(
    "db" to "dumbbell", "dumbell" to "dumbbell", "dumbbells" to "dumbbell", "dumbells" to "dumbbell", "dbs" to "dumbbell",
    "bb" to "barbell", "barbells" to "barbell",
    "kb" to "kettlebell", "kettlebells" to "kettlebell",
    "bicep" to "biceps", "bis" to "biceps",
    "tricep" to "triceps", "tris" to "triceps",
    "alternating" to "alternate", "alt" to "alternate",
    "ohp" to "overhead", "rdl" to "romanian",
    "ups" to "up", "pushup" to "push up", "pushups" to "push up",
    "pullup" to "pull up", "pullups" to "pull up",
    "chinup" to "chin up", "chinups" to "chin up",
    "situp" to "sit up", "situps" to "sit up",
    "pulldown" to "pull down", "pulldowns" to "pull down",
    "flye" to "fly", "flyes" to "fly", "flys" to "fly", "flies" to "fly"
)

/** Words that don't change which exercise it is. */
private val FILLER = setOf("the", "a", "an", "with", "on", "of", "to", "and", "exercise")

/** "Incline DB Bicep Curls" -> [incline, dumbbell, biceps, curl] */
fun exerciseTokens(name: String): List<String> =
    name.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .split(' ')
        .filter { it.isNotBlank() }
        .flatMap { word -> (SYNONYMS[word] ?: word).split(' ') }
        .map { word ->
            // Plurals: "curls" -> "curl", "raises" -> "raise". Short words
            // and ones that end in "ss" ("press") are left alone; "biceps"
            // and "triceps" are names, not plurals.
            if (word.length > 3 && word.endsWith("s") && !word.endsWith("ss") && word != "biceps" && word != "triceps") {
                word.dropLast(1)
            } else word
        }
        .filter { it !in FILLER }

/** Everyday words for each muscle group (as the exercise list names them),
 *  already in [exerciseTokens] form ("delts" -> "delt"). The group's own
 *  words count too ("middle back" -> middle, back). */
private val MUSCLE_ALIASES: Map<String, List<String>> = mapOf(
    "CHEST" to listOf("pec", "pecs"),
    "SHOULDERS" to listOf("shoulder", "delt", "delts"),
    "TRICEPS" to listOf("arm", "tri"),
    "BICEPS" to listOf("arm", "bi"),
    "FOREARMS" to listOf("arm", "forearm", "grip"),
    "LATS" to listOf("lat", "back"),
    "MIDDLE BACK" to listOf("back", "upper"),
    "LOWER BACK" to listOf("back"),
    "TRAPS" to listOf("trap", "back"),
    "QUADRICEPS" to listOf("quad", "leg"),
    "HAMSTRINGS" to listOf("hamstring", "ham", "leg"),
    "GLUTES" to listOf("glute", "butt", "leg"),
    "CALVES" to listOf("calf", "calve", "leg"),
    "ADDUCTORS" to listOf("adductor", "inner", "thigh", "leg", "hip"),
    "ABDUCTORS" to listOf("abductor", "outer", "thigh", "leg", "hip"),
    "ABDOMINALS" to listOf("ab", "abs", "core", "abdominal", "oblique"),
    "NECK" to listOf("neck")
)

/** Words that find an exercise by its muscle: "triceps" / "tris" / "arms" all
 *  find a triceps exercise, "back" finds lats, middle and lower back. */
private fun muscleWords(muscleGroup: String?): List<String> {
    val group = muscleGroup?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    return exerciseTokens(group) + MUSCLE_ALIASES[group].orEmpty()
}

/**
 * Exercises matching what's typed, best first. Every typed word has to
 * start a word of the name *or* name the exercise's muscle, in any order:
 * "incline curl" finds "Alternate Incline Dumbbell Curl", "triceps" finds
 * every triceps exercise, "back row" finds rows for the back. Exercises the
 * user has logged come first - most-logged first - so their usual one is
 * always on top; then ones whose name matches before muscle-only matches.
 */
fun searchExercises(query: String, exercises: List<ExerciseResponse>): List<ExerciseResponse> {
    val wanted = exerciseTokens(query)
    if (wanted.isEmpty()) return exercises
    val wantedPhrase = wanted.joinToString(" ")
    return exercises
        .mapNotNull { exercise ->
            val tokens = exerciseTokens(exercise.name)
            val muscles = muscleWords(exercise.muscleGroup)
            // Each word must match the name or the muscle; matching both
            // ("back" for a back exercise) ranks higher than either alone.
            var hits = 0
            var muscleHits = 0
            for (w in wanted) {
                val name = tokens.any { it.startsWith(w) }
                val muscle = muscles.any { it.startsWith(w) }
                if (!name && !muscle) return@mapNotNull null
                if (name) hits++
                if (muscle) { hits++; muscleHits++ }
            }
            val phrase = tokens.joinToString(" ")
            val closeness = when {
                phrase == wantedPhrase -> 3
                phrase.startsWith(wantedPhrase) -> 2
                phrase.contains(wantedPhrase) -> 1
                else -> 0
            }
            SearchHit(exercise, hits, muscleHits, closeness)
        }
        .sortedWith(
            compareByDescending<SearchHit> { it.exercise.timesLogged > 0 }
                .thenByDescending { it.exercise.timesLogged }
                .thenByDescending { it.hits }
                .thenByDescending { it.muscleHits }
                .thenByDescending { it.closeness }
                .thenBy { exerciseTokens(it.exercise.name).size }
                .thenBy { it.exercise.name }
        )
        .map { it.exercise }
}

private data class SearchHit(val exercise: ExerciseResponse, val hits: Int, val muscleHits: Int, val closeness: Int)

/** The user's own exercises (logged at least once), most recently used first. */
fun recentExercises(exercises: List<ExerciseResponse>): List<ExerciseResponse> =
    exercises.filter { it.timesLogged > 0 }
        .sortedWith(compareByDescending<ExerciseResponse> { it.lastLoggedAt.orEmpty() }.thenByDescending { it.timesLogged })

/** Shared words / words in the shorter name: 1.0 = every word of one name is in the other. */
private fun containment(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    return a.intersect(b).size.toDouble() / minOf(a.size, b.size)
}

/**
 * Existing exercises that look like [name] - for "Did you mean ...?" before
 * creating a custom exercise. Loose on purpose: it's only a hint.
 */
fun similarExercises(name: String, exercises: List<ExerciseResponse>, limit: Int = 3): List<ExerciseResponse> {
    val wanted = exerciseTokens(name).toSet()
    if (wanted.size < 2) return emptyList()
    return exercises
        .map { it to exerciseTokens(it.name).toSet() }
        .filter { (_, tokens) -> wanted.intersect(tokens).size >= 2 && containment(wanted, tokens) >= 0.66 }
        .sortedWith(
            compareByDescending<Pair<ExerciseResponse, Set<String>>> { containment(wanted, it.second) }
                .thenByDescending { it.first.timesLogged }
                .thenBy { it.second.size }
        )
        .take(limit)
        .map { it.first }
}

private fun sameEquipment(a: ExerciseResponse, b: ExerciseResponse): Boolean {
    // "Equipment" is the custom-exercise placeholder, so it fits anything
    // except bodyweight.
    fun norm(e: String?) = e?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "equipment" }
    val x = norm(a.equipment)
    val y = norm(b.equipment)
    if ((x == "body only") != (y == "body only")) return false
    return x == null || y == null || x == y
}

/**
 * Logged exercises that are probably the same as [exercise], to suggest a
 * merge. Strict, since it's a suggestion to combine history: same muscle
 * group, compatible equipment, and every word of the shorter name appears
 * in the longer one ("Incline Bicep Curl" ⊂ "Incline Dumbbell Bicep Curl",
 * but not "Incline Hammer Curl"). The user still confirms.
 */
fun likelyDuplicates(exercise: ExerciseResponse, exercises: List<ExerciseResponse>): List<ExerciseResponse> {
    val tokens = exerciseTokens(exercise.name).toSet()
    if (tokens.size < 2) return emptyList()
    return exercises
        .filter { other ->
            other.id != exercise.id &&
                other.timesLogged > 0 &&
                other.muscleGroup.equals(exercise.muscleGroup, ignoreCase = true) &&
                sameEquipment(other, exercise) &&
                exerciseTokens(other.name).toSet().let { o -> o.size >= 2 && containment(tokens, o) >= 1.0 }
        }
        .sortedByDescending { it.timesLogged }
}
