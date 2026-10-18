package com.example.workoutlog_androidstudio

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.workoutlog_androidstudio.api.ExerciseResponse
import com.example.workoutlog_androidstudio.api.NetworkClient
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** POST /exercises/merge - all of the user's sets of [fromExerciseId] become
 *  [intoExerciseId], and [fromExerciseId] leaves their exercise list. */
@Serializable
data class MergeExercisesRequest(
    val fromExerciseId: Int,
    val intoExerciseId: Int
)

@Serializable
data class MergeExercisesResponse(
    val movedSets: Int
)

/** Merge suggestions the user said "not the same" to, so they don't come back. */
private object MergeSuggestionStore {
    private const val PREFS = "merge_suggestions"
    private const val KEY = "dismissed_pairs"

    private fun pairKey(a: Int, b: Int) = if (a < b) "$a-$b" else "$b-$a"

    fun isDismissed(context: Context, a: Int, b: Int): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()).orEmpty()
            .contains(pairKey(a, b))

    fun dismiss(context: Context, a: Int, b: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY, emptySet()).orEmpty()
        prefs.edit().putStringSet(KEY, current + pairKey(a, b)).apply()
    }
}

/**
 * Everything for merging the exercise on the Exercise Analytics screen with
 * another one:
 *
 * - a suggestion card when another logged exercise looks like the same one
 *   ([likelyDuplicates]) - "Merge" or "Not the same";
 * - the "Merge with..." picker ([showPicker], opened from the screen's header);
 * - the confirmation, where the user picks which name to keep.
 *
 * Nothing changes until they confirm. [onMerged] gets the id that was kept.
 */
@Composable
fun ExerciseMergeControls(
    exerciseId: Int,
    showPicker: Boolean,
    onDismissPicker: () -> Unit,
    onMerged: (keptExerciseId: Int, keptName: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { runCatching { AppDataCache.loadExercises() } }
    val all = AppDataCache.exercises.orEmpty()
    val current = all.firstOrNull { it.id == exerciseId } ?: return

    var dismissedTick by remember { mutableStateOf(0) }
    val suggestion = remember(all, current, dismissedTick) {
        likelyDuplicates(current, all).firstOrNull { !MergeSuggestionStore.isDismissed(context, current.id, it.id) }
    }

    var pending by remember { mutableStateOf<ExerciseResponse?>(null) }
    var isMerging by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    if (suggestion != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(AppSurface)
                .border(1.dp, AppAccent.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                .padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 4.dp)
        ) {
            Text(
                text = "LOOKS LIKE A DUPLICATE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = AppAccent
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "\"${suggestion.name}\" (logged ${suggestion.timesLogged}×) looks like the same exercise. " +
                    "Merge them so their history and records are in one place?",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextPrimary
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    MergeSuggestionStore.dismiss(context, current.id, suggestion.id)
                    dismissedTick++
                }) {
                    Text("Not the same", color = AppTextSecondary)
                }
                TextButton(onClick = { pending = suggestion }) {
                    Text("Merge", color = AppAccent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    message?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = AppTextSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )
    }

    // ---- "Merge with..." picker ----
    var query by remember { mutableStateOf("") }
    LaunchedEffect(showPicker) { if (showPicker) query = "" }
    AppBottomSheet(visible = showPicker, onDismissRequest = onDismissPicker) {
        Text(text = "Merge with…", style = MaterialTheme.typography.titleLarge, color = AppTextPrimary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Pick the exercise that's the same as ${current.name}. Your sets of both become one exercise.",
            style = MaterialTheme.typography.bodySmall,
            color = AppTextMuted
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(text = "Search exercises...", color = AppTextMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = appTextFieldColors()
        )
        Spacer(modifier = Modifier.height(8.dp))
        val candidates = remember(query, all, current) {
            val others = all.filter { it.id != current.id }
            if (query.isBlank()) {
                // Likely duplicates first, then everything else they've logged.
                val likely = likelyDuplicates(current, all)
                likely + recentExercises(others).filter { r -> likely.none { it.id == r.id } }
            } else {
                searchExercises(query, others)
            }
        }
        if (candidates.isEmpty()) {
            Text(
                text = if (query.isBlank()) "Exercises you've logged show up here." else "No exercises found.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextMuted,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                items(candidates, key = { it.id }) { other ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismissPicker()
                                pending = other
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = other.name, style = MaterialTheme.typography.bodyMedium, color = AppTextPrimary)
                            other.muscleGroup?.let {
                                Text(text = it, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
                            }
                        }
                        if (other.timesLogged > 0) {
                            Text(text = "${other.timesLogged}×", style = MaterialTheme.typography.labelMedium, color = AppAccent)
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onDismissPicker, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Cancel", color = AppTextSecondary)
        }
    }

    // ---- Confirm: which name to keep ----
    pending?.let { other ->
        fun merge(keep: ExerciseResponse, drop: ExerciseResponse) {
            if (ActiveWorkoutState.workout != null) {
                message = "Finish or discard your current workout first, then merge."
                pending = null
                return
            }
            isMerging = true
            scope.launch {
                try {
                    val result = NetworkClient.workoutApi.mergeExercises(
                        MergeExercisesRequest(fromExerciseId = drop.id, intoExerciseId = keep.id)
                    )
                    MergeSuggestionStore.dismiss(context, keep.id, drop.id)
                    AppDataCache.refreshAfterExercisesMerged()
                    message = "Merged - ${result.movedSets} sets moved into ${keep.name}."
                    pending = null
                    onMerged(keep.id, keep.name)
                } catch (e: Exception) {
                    message = "Couldn't merge. Check your connection and try again."
                    pending = null
                } finally {
                    isMerging = false
                }
            }
        }

        AlertDialog(
            onDismissRequest = { if (!isMerging) pending = null },
            containerColor = AppSurface,
            title = { Text("Merge exercises?", color = AppTextPrimary) },
            text = {
                Column {
                    Text(
                        text = "Your sets of \"${current.name}\" and \"${other.name}\" become one exercise, so the charts " +
                            "and records combine. Pick the name to keep - the other leaves your list. This can't be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    listOf(current to other, other to current).forEach { (keep, drop) ->
                        Button(
                            onClick = { merge(keep, drop) },
                            enabled = !isMerging,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                        ) {
                            Text(text = "Keep \"${keep.name}\"", fontWeight = FontWeight.SemiBold, maxLines = 2)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pending = null }, enabled = !isMerging) {
                    Text("Cancel", color = AppTextSecondary)
                }
            }
        )
    }
}
