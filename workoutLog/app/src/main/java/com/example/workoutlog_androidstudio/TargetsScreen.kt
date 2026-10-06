package com.example.workoutlog_androidstudio

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.workoutlog_androidstudio.api.NetworkClient
import com.example.workoutlog_androidstudio.api.UpdateProfileRequest
import kotlinx.coroutines.launch

private const val MIN_WEEKLY_TARGET = 1
private const val MAX_WEEKLY_TARGET = 7

@Composable
fun TargetsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {

    var profile by remember { mutableStateOf(AppDataCache.profile) }
    var isLoading by remember { mutableStateOf(AppDataCache.profile == null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var targetWeightText by remember {
        mutableStateOf(AppDataCache.profile?.targetWeightKg?.let { formatDecimal(it) } ?: "")
    }
    var weeklyTarget by remember { mutableStateOf(AppDataCache.profile?.weeklyWorkoutTarget ?: 6) }

    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var savedMessageVisible by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    suspend fun reload(force: Boolean = false) {
        isLoading = profile == null
        errorMessage = null
        try {
            val loaded = AppDataCache.loadProfile(force = force)
            profile = loaded
            targetWeightText = loaded.targetWeightKg?.let { formatDecimal(it) } ?: ""
            weeklyTarget = loaded.weeklyWorkoutTarget
        } catch (e: Exception) {
            errorMessage = "Couldn't load your targets. Check your connection."
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun save() {
        if (isSaving) return
        isSaving = true
        saveError = null
        savedMessageVisible = false
        coroutineScope.launch {
            try {

                val parsedTarget = targetWeightText.trim().toDoubleOrNull()
                val updated = NetworkClient.workoutApi.updateProfile(
                    UpdateProfileRequest(
                        targetWeightKg = parsedTarget,
                        weeklyWorkoutTarget = weeklyTarget
                    )
                )
                profile = updated
                AppDataCache.replaceProfile(updated)
                savedMessageVisible = true
            } catch (e: Exception) {
                saveError = "Couldn't save your targets. Check your connection and try again."
            } finally {
                isSaving = false
            }
        }
    }

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
            BackChevronRow(onBack = onBack, label = "Profile")
        }

        LoadingOrError(isLoading = isLoading, errorMessage = errorMessage) {
                val current = profile
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = 40.dp)
                ) {
                    Text(
                        text = "Targets",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppTextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Set the goals shown across the app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppSurface, RoundedCornerShape(20.dp))
                            .padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "WEIGHT TARGET", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
                            if (current != null) {
                                Text(
                                    text = "Current: ${formatDecimal(current.weightKg)} KG",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppTextMuted
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = targetWeightText,
                            onValueChange = { targetWeightText = it },
                            singleLine = true,
                            label = { Text(text = "Target weight (kg)", color = AppTextMuted) },
                            placeholder = { Text(text = "e.g. 80", color = AppTextMuted) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                            colors = appTextFieldColors()
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppSurface, RoundedCornerShape(20.dp))
                            .padding(18.dp)
                    ) {
                        Text(text = "WEEKLY WORKOUT TARGET", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "How many days a week you want to log at least one workout.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextSecondary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StepperButton(
                                icon = Icons.Filled.Remove,
                                enabled = weeklyTarget > MIN_WEEKLY_TARGET,
                                onClick = { weeklyTarget = (weeklyTarget - 1).coerceAtLeast(MIN_WEEKLY_TARGET) }
                            )
                            Text(
                                text = "$weeklyTarget ${if (weeklyTarget == 1) "day" else "days"}/week",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = AppTextPrimary
                            )
                            StepperButton(
                                icon = Icons.Filled.Add,
                                enabled = weeklyTarget < MAX_WEEKLY_TARGET,
                                onClick = { weeklyTarget = (weeklyTarget + 1).coerceAtMost(MAX_WEEKLY_TARGET) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    if (saveError != null) {
                        Text(text = saveError.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = AppTextSecondary)
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Button(
                        onClick = { save() },
                        enabled = !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(text = "Save Targets", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (savedMessageVisible) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Filled.Check, contentDescription = null, tint = AppAccent, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Targets saved", style = MaterialTheme.typography.bodyMedium, color = AppTextSecondary)
                        }
                    }
                }
        }
    }
}

@Composable
private fun StepperButton(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (enabled) AppSurfaceVariant else AppSurfaceVariant.copy(alpha = 0.4f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) AppTextPrimary else AppTextMuted
        )
    }
}
