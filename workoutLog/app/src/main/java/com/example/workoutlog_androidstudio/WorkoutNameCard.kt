package com.example.workoutlog_androidstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * "Today's workout is called... Quadfather" - shown when a workout ends (if
 * fun names are on in Profile). Keep it, or "Write your own" to type a name
 * instead. Closing the sheet keeps the suggestion.
 */
@Composable
fun WorkoutNameCard(
    suggestion: SuggestedName,
    durationLabel: String,
    totalSets: Int,
    topLift: String?,
    isSaving: Boolean,
    onSave: (name: String) -> Unit
) {
    var writingOwn by remember { mutableStateOf(false) }
    var ownName by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(writingOwn) { if (writingOwn) runCatching { focus.requestFocus() } }

    fun save() {
        if (isSaving) return
        val name = if (writingOwn) ownName.trim() else suggestion.name
        if (name.isNotEmpty()) onSave(name)
    }

    AppBottomSheet(visible = true, onDismissRequest = { if (!writingOwn) save() }) {
        Text(
            text = if (writingOwn) "NAME YOUR WORKOUT" else "TODAY'S WORKOUT IS CALLED…",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = AppAccent
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (writingOwn) {
            OutlinedTextField(
                value = ownName,
                onValueChange = { if (it.length <= 60) ownName = it },
                singleLine = true,
                placeholder = { Text(text = suggestion.name, color = AppTextMuted) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                colors = appTextFieldColors()
            )
        } else {
            Text(
                text = suggestion.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = AppTextPrimary
            )
            if (suggestion.reason.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = suggestion.reason, style = MaterialTheme.typography.bodySmall, color = AppTextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NameStat(value = durationLabel, label = "DURATION", modifier = Modifier.weight(1f))
            NameStat(value = totalSets.toString(), label = "SETS", modifier = Modifier.weight(1f))
            NameStat(value = topLift ?: "-", label = "TOP LIFT", modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = { save() },
            enabled = !isSaving && (!writingOwn || ownName.isNotBlank()),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) {
            if (isSaving) {
                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(text = if (writingOwn) "Save name" else "Keep it", fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        TextButton(
            onClick = { writingOwn = !writingOwn },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (writingOwn) "Use \"${suggestion.name}\" instead" else "Write your own",
                color = AppTextSecondary,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun NameStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(AppSurfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AppTextPrimary, maxLines = 1)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
    }
}

/** Profile row: turn fun workout names on or off. */
@Composable
fun FunNamesSettingRow(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { WorkoutNamer.init(context) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AppSurface, RoundedCornerShape(18.dp))
            .clickable { WorkoutNamer.setEnabled(context, !WorkoutNamer.enabled) }
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("FUN WORKOUT NAMES", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (WorkoutNamer.enabled) "Suggest a name when a workout ends" else "Off - workouts keep the name you give them",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextPrimary
            )
        }
        androidx.compose.material3.Switch(
            checked = WorkoutNamer.enabled,
            onCheckedChange = { WorkoutNamer.setEnabled(context, it) },
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = AppOnAccent,
                checkedTrackColor = AppAccent,
                uncheckedThumbColor = AppTextMuted,
                uncheckedTrackColor = AppSurfaceVariant,
                uncheckedBorderColor = AppBorder
            )
        )
    }
}
