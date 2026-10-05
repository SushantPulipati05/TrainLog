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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.workoutlog_androidstudio.api.NetworkClient
import com.example.workoutlog_androidstudio.api.UpdateProfileRequest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

private fun formatStature(heightCm: Double): String {
    val totalInches = heightCm / 2.54
    val roundedHalfInches = (totalInches * 2).roundToInt() / 2.0
    val feet = (roundedHalfInches / 12).toInt()
    val inches = roundedHalfInches - feet * 12
    val inchesLabel = if (inches == inches.toLong().toDouble()) inches.toLong().toString() else inches.toString()
    return "$feet' $inchesLabel\" Stature"
}

private fun bmiCategory(bmi: Double): String = when {
    bmi < 18.5 -> "UNDERWEIGHT"
    bmi < 25.0 -> "OPTIMAL"
    bmi < 30.0 -> "OVERWEIGHT"
    else -> "ELEVATED"
}

private fun formatMemberSince(isoDate: String): String {
    val date = runCatching { LocalDate.parse(isoDate) }.getOrNull() ?: return isoDate
    return date.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)).uppercase()
}

private fun initialsFor(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifEmpty { "?" }

@Composable
fun ProfileScreen(
    onOpenTargets: () -> Unit,
    modifier: Modifier = Modifier
) {

    var profile by remember { mutableStateOf(AppDataCache.profile) }
    var isLoading by remember { mutableStateOf(AppDataCache.profile == null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var syncedAtLabel by remember { mutableStateOf("") }

    var isEditing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    var editedName by remember { mutableStateOf("") }
    var editedAge by remember { mutableStateOf("") }
    var editedWeight by remember { mutableStateOf("") }
    var editedTargetWeight by remember { mutableStateOf("") }
    var editedHeight by remember { mutableStateOf("") }
    var editedBodyFat by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()

    suspend fun reload(force: Boolean = false) {
        isLoading = profile == null
        errorMessage = null
        try {
            profile = AppDataCache.loadProfile(force = force)
            syncedAtLabel = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Calendar.getInstance().time)
        } catch (e: Exception) {
            errorMessage = "Couldn't load your profile. Check your connection."
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun startEditing() {
        val current = profile ?: return
        editedName = current.name
        editedAge = current.age.toString()
        editedWeight = formatDecimal(current.weightKg)
        editedTargetWeight = current.targetWeightKg?.let { formatDecimal(it) } ?: ""
        editedHeight = formatDecimal(current.heightCm)
        editedBodyFat = current.bodyFatPercent?.let { formatDecimal(it) } ?: ""
        saveError = null
        isEditing = true
    }

    fun cancelEditing() {
        isEditing = false
        saveError = null
    }

    fun saveEdits() {
        val current = profile ?: return
        isSaving = true
        saveError = null
        coroutineScope.launch {
            try {
                val nameTrimmed = editedName.trim()
                val ageValue = editedAge.toIntOrNull()
                val weightValue = editedWeight.toDoubleOrNull()
                val heightValue = editedHeight.toDoubleOrNull()
                val targetValue = editedTargetWeight.toDoubleOrNull()
                val bodyFatValue = editedBodyFat.toDoubleOrNull()

                val updated = NetworkClient.workoutApi.updateProfile(
                    UpdateProfileRequest(
                        name = nameTrimmed.takeIf { it.isNotBlank() && it != current.name },
                        age = ageValue?.takeIf { it != current.age },
                        weightKg = weightValue?.takeIf { it != current.weightKg },
                        targetWeightKg = targetValue?.takeIf { it != current.targetWeightKg },
                        heightCm = heightValue?.takeIf { it != current.heightCm },
                        bodyFatPercent = bodyFatValue?.takeIf { it != current.bodyFatPercent }
                    )
                )
                profile = updated
                AppDataCache.replaceProfile(updated)
                isEditing = false
            } catch (e: Exception) {
                saveError = "Couldn't save your changes. Check your connection and try again."
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
                .padding(start = 12.dp, end = 12.dp, top = 32.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ATHLETE RECORD",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isEditing) AppTextMuted else AppAccent
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(AppAccent, RoundedCornerShape(50))
                )
            }

            if (isEditing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { cancelEditing() }, enabled = !isSaving) {
                        Text(text = "Cancel", color = AppTextSecondary, style = MaterialTheme.typography.labelMedium)
                    }
                    Box(
                        modifier = Modifier
                            .background(AppAccent, RoundedCornerShape(50))
                            .clickable(enabled = !isSaving) { saveEdits() }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(color = AppOnAccent, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Text(text = "Save", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = AppOnAccent)
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { startEditing() }) {
                        Icon(imageVector = Icons.Filled.Edit, contentDescription = "Edit profile", tint = AppAccent)
                    }

                    IconButton(onClick = onOpenTargets) {
                        Icon(imageVector = Icons.Filled.Tune, contentDescription = "Targets", tint = AppTextMuted)
                    }
                }
            }
        }

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppAccent)
                }
            }
            errorMessage != null -> {
                Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text(text = errorMessage.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = AppTextSecondary)
                }
            }
            profile != null -> {
                val current = profile!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),

                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 110.dp)
                ) {
                    item {

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(AppSurfaceVariant, RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = initialsFor(if (isEditing) editedName else current.name),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTextPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            if (isEditing) {
                                OutlinedTextField(
                                    value = editedName,
                                    onValueChange = { editedName = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.weight(1f),
                                    colors = appTextFieldColors()
                                )
                            } else {
                                Text(
                                    text = current.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTextPrimary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "CORE BIOMETRICS", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
                            Text(text = "SYNCED $syncedAtLabel", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AppSurface, RoundedCornerShape(20.dp))
                                .padding(18.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                BiometricStat(
                                    label = "AGE",
                                    value = if (isEditing) null else "${current.age}",
                                    caption = "Active Peak",
                                    modifier = Modifier.weight(1f),
                                    editableField = if (isEditing) {
                                        { EditableStatField(value = editedAge, onValueChange = { editedAge = it }, keyboardType = KeyboardType.Number) }
                                    } else null
                                )
                                BiometricStat(
                                    label = "WEIGHT",
                                    value = if (isEditing) null else "${formatDecimal(current.weightKg)} KG",
                                    caption = current.targetWeightKg?.let { "Target: ${formatDecimal(it)} kg" } ?: "No target set",
                                    badge = current.weightDeltaKg?.let { delta ->
                                        val sign = if (delta > 0) "+" else ""
                                        "$sign${"%.1f".format(delta)} KG"
                                    },
                                    modifier = Modifier.weight(1f),
                                    editableField = if (isEditing) {
                                        { EditableStatField(value = editedWeight, onValueChange = { editedWeight = it }, keyboardType = KeyboardType.Decimal) }
                                    } else null
                                )
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                BiometricStat(
                                    label = "HEIGHT",
                                    value = if (isEditing) null else "${formatDecimal(current.heightCm)} CM",
                                    caption = formatStature(current.heightCm),
                                    modifier = Modifier.weight(1f),
                                    editableField = if (isEditing) {
                                        { EditableStatField(value = editedHeight, onValueChange = { editedHeight = it }, keyboardType = KeyboardType.Decimal) }
                                    } else null
                                )
                                BiometricStat(
                                    label = "BMI",
                                    value = "%.1f".format(current.bmi),
                                    caption = "18.5 - 24.9 Range",
                                    badge = bmiCategory(current.bmi),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AppSurface, RoundedCornerShape(20.dp))
                                .padding(18.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                BiometricStat(
                                    label = "BODY FAT",
                                    value = if (isEditing) null else (current.bodyFatPercent?.let { "${formatDecimal(it)}%" } ?: "--"),
                                    caption = "DEXA Calibration",
                                    modifier = Modifier.weight(1f),
                                    editableField = if (isEditing) {
                                        { EditableStatField(value = editedBodyFat, onValueChange = { editedBodyFat = it }, keyboardType = KeyboardType.Decimal) }
                                    } else null
                                )
                                BiometricStat(
                                    label = "WORKOUTS LOGGED",
                                    value = "${current.workoutsLogged}",
                                    caption = "Completed Sessions",
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                BiometricStat(
                                    label = "TOTAL VOLUME",
                                    value = "%,.0f KG".format(current.totalVolumeKg),
                                    caption = "Cumulative Gross",
                                    modifier = Modifier.weight(1f)
                                )
                                BiometricStat(
                                    label = "MEMBER SINCE",
                                    value = formatMemberSince(current.memberSince),
                                    caption = "${current.weeksActive} Weeks Active",
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (saveError != null) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(text = saveError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = AppTextSecondary)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        AccountSection()

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "FORGE OS v3.8.4 • ENCRYPTED ATHLETE STORAGE",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTextMuted,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BiometricStat(
    label: String,
    value: String?,
    caption: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    editableField: (@Composable () -> Unit)? = null
) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .background(AppSurfaceVariant, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(text = badge, style = MaterialTheme.typography.labelSmall, color = AppAccent)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        if (editableField != null) {
            editableField()
        } else {
            Text(text = value.orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = AppTextPrimary)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = caption, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
    }
}

@Composable
private fun EditableStatField(value: String, onValueChange: (String) -> Unit, keyboardType: KeyboardType) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        colors = appTextFieldColors()
    )
}
