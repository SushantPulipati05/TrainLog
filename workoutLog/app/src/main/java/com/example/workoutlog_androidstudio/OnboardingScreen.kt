package com.example.workoutlog_androidstudio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.workoutlog_androidstudio.api.NetworkClient
import com.example.workoutlog_androidstudio.api.ProfileResponse
import com.example.workoutlog_androidstudio.api.UpdateProfileRequest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** The questions, in order. SUMMARY is the review screen at the end. */
private enum class OnboardingStep { NAME, AGE, HEIGHT, WEIGHT, TARGET, BODY_FAT, WEEKLY, SUMMARY }

/** How many question screens the progress bar counts (everything but SUMMARY). */
private const val QUESTION_COUNT = 7

// Every question screen is built from the same fixed-height blocks - top
// bar, header, big value, chip, then the control - with the buttons pinned
// to the bottom in a slot that's always the same height (the Skip button's
// space is kept even on screens without one). That way the big number sits
// at exactly the same spot on every screen and doesn't jump between steps.
private val HEADER_HEIGHT = 176.dp
private val VALUE_HEIGHT = 112.dp
private val CHIP_HEIGHT = 40.dp

/**
 * First-run onboarding for a brand-new account: one question per screen
 * (name, age, height, weight, optional target weight, optional body fat,
 * weekly target), then a review screen that saves everything at once and
 * marks onboarding done. [initial] is the placeholder profile the server
 * created, used for the starting values.
 */
@Composable
fun OnboardingScreen(initial: ProfileResponse, onFinished: () -> Unit) {
    val scope = rememberCoroutineScope()

    var stepIndex by rememberSaveable { mutableStateOf(0) }
    var editingFromSummary by rememberSaveable { mutableStateOf(false) }

    var name by rememberSaveable { mutableStateOf(initial.name) }
    var age by rememberSaveable { mutableStateOf(initial.age.coerceIn(13, 100)) }
    var heightCm by rememberSaveable { mutableStateOf(initial.heightCm.roundToInt().toFloat().coerceIn(100f, 250f)) }
    var weightKg by rememberSaveable { mutableStateOf(roundToHalf(initial.weightKg.toFloat()).coerceIn(30f, 250f)) }
    var targetKg by rememberSaveable { mutableStateOf(weightKg) }
    var hasTarget by rememberSaveable { mutableStateOf(false) }
    var bodyFat by rememberSaveable { mutableStateOf(20f) }
    var hasBodyFat by rememberSaveable { mutableStateOf(false) }
    var weeklyTarget by rememberSaveable { mutableStateOf(4) }

    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val step = OnboardingStep.entries[stepIndex]

    fun goTo(target: OnboardingStep) {
        stepIndex = target.ordinal
    }

    fun goNext() {
        if (editingFromSummary) {
            editingFromSummary = false
            goTo(OnboardingStep.SUMMARY)
        } else {
            stepIndex += 1
        }
    }

    fun goBack() {
        if (editingFromSummary) {
            editingFromSummary = false
            goTo(OnboardingStep.SUMMARY)
        } else if (stepIndex > 0) {
            stepIndex -= 1
        }
    }

    fun editFromSummary(target: OnboardingStep) {
        editingFromSummary = true
        goTo(target)
    }

    fun save() {
        isSaving = true
        saveError = null
        scope.launch {
            try {
                val updated = NetworkClient.workoutApi.updateProfile(
                    UpdateProfileRequest(
                        name = name.trim(),
                        age = age,
                        weightKg = weightKg.toDouble(),
                        targetWeightKg = if (hasTarget) targetKg.toDouble() else null,
                        heightCm = heightCm.toDouble(),
                        bodyFatPercent = if (hasBodyFat) bodyFat.toDouble() else null,
                        weeklyWorkoutTarget = weeklyTarget,
                        onboardingComplete = true
                    )
                )
                AppDataCache.replaceProfile(updated)
                onFinished()
            } catch (e: Exception) {
                saveError = "Couldn't save your details. Check your connection and try again."
            } finally {
                isSaving = false
            }
        }
    }

    BackHandler(enabled = stepIndex > 0 || editingFromSummary) { goBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)
    ) {
        OnboardingTopBar(
            stepIndex = stepIndex,
            showBack = stepIndex > 0 || editingFromSummary,
            showProgress = step != OnboardingStep.SUMMARY,
            onBack = { goBack() }
        )

        if (step == OnboardingStep.SUMMARY) {
            SummaryContent(
                name = name.trim(),
                rows = listOf(
                    SummaryRow("NAME", name.trim(), OnboardingStep.NAME),
                    SummaryRow("AGE", "$age years", OnboardingStep.AGE),
                    SummaryRow("HEIGHT", "${heightCm.roundToInt()} cm", OnboardingStep.HEIGHT),
                    SummaryRow("WEIGHT", "${formatKg(weightKg)} kg", OnboardingStep.WEIGHT),
                    SummaryRow("TARGET WEIGHT", if (hasTarget) "${formatKg(targetKg)} kg" else null, OnboardingStep.TARGET),
                    SummaryRow("BODY FAT", if (hasBodyFat) "${bodyFat.roundToInt()}%" else null, OnboardingStep.BODY_FAT),
                    SummaryRow("WEEKLY TARGET", "$weeklyTarget ${if (weeklyTarget == 1) "day" else "days"}", OnboardingStep.WEEKLY)
                ),
                onEdit = { editFromSummary(it) },
                saveError = saveError,
                isSaving = isSaving,
                onStart = { save() },
                modifier = Modifier.weight(1f)
            )
            return@Column
        }

        val isOptional = step == OnboardingStep.TARGET || step == OnboardingStep.BODY_FAT

        OnboardingHeader(
            label = when (step) {
                OnboardingStep.NAME, OnboardingStep.AGE -> "ABOUT YOU"
                OnboardingStep.TARGET, OnboardingStep.WEEKLY -> "YOUR GOALS"
                else -> "YOUR BODY"
            },
            optional = isOptional,
            title = when (step) {
                OnboardingStep.NAME -> "What should we call you?"
                OnboardingStep.AGE -> "How old are you?"
                OnboardingStep.HEIGHT -> "How tall are you?"
                OnboardingStep.WEIGHT -> "What do you weigh right now?"
                OnboardingStep.TARGET -> "Do you have a target weight?"
                OnboardingStep.BODY_FAT -> "Know your body fat percentage?"
                else -> "How many days a week will you train?"
            },
            subtitle = when (step) {
                OnboardingStep.NAME -> "This is the name on your profile."
                OnboardingStep.AGE -> "Shown on your profile next to your other stats."
                OnboardingStep.HEIGHT -> "Used with your weight to work out your BMI."
                OnboardingStep.WEIGHT -> "Bodyweight exercises count it toward your volume."
                OnboardingStep.TARGET -> "Your profile will show how far you are from it."
                OnboardingStep.BODY_FAT -> "Only if you've measured it. You can add it later."
                else -> "This becomes the weekly target on your home screen."
            }
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(VALUE_HEIGHT),
                contentAlignment = Alignment.Center
            ) {
                when (step) {
                    OnboardingStep.NAME -> OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 100) name = it },
                        singleLine = true,
                        placeholder = { Text(text = "Your name", color = AppTextMuted, fontSize = 22.sp) },
                        textStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, color = AppTextPrimary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = appTextFieldColors()
                    )
                    OnboardingStep.AGE -> BigValue(value = "$age", unit = "years")
                    OnboardingStep.HEIGHT -> BigValue(value = "${heightCm.roundToInt()}", unit = "cm")
                    OnboardingStep.WEIGHT -> BigValue(value = formatKg(weightKg), unit = "kg")
                    OnboardingStep.TARGET -> BigValue(value = formatKg(targetKg), unit = "kg")
                    OnboardingStep.BODY_FAT -> BigValue(value = "${bodyFat.roundToInt()}", unit = "%")
                    else -> BigValue(value = "$weeklyTarget", unit = if (weeklyTarget == 1) "day / week" else "days / week")
                }
            }

            Box(modifier = Modifier.height(CHIP_HEIGHT), contentAlignment = Alignment.Center) {
                when (step) {
                    OnboardingStep.WEIGHT -> {
                        val heightM = heightCm / 100f
                        val bmi = weightKg / (heightM * heightM)
                        ValueChip(text = "BMI ${String.format(Locale.US, "%.1f", bmi)} · ${bmiLabel(bmi)}", color = AppTextSecondary)
                    }
                    OnboardingStep.TARGET -> {
                        val delta = targetKg - weightKg
                        if (delta != 0f) {
                            val sign = if (delta > 0) "+" else "−"
                            ValueChip(text = "$sign${formatKg(kotlin.math.abs(delta))} KG FROM NOW", color = AppAccent)
                        }
                    }
                    else -> {}
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            when (step) {
                OnboardingStep.NAME -> {}
                OnboardingStep.AGE -> AgeStepper(
                    onDecrease = { age = (age - 1).coerceAtLeast(13) },
                    onIncrease = { age = (age + 1).coerceAtMost(100) }
                )
                OnboardingStep.HEIGHT -> RulerPicker(
                    value = heightCm,
                    onValueChange = { heightCm = it },
                    min = 100f,
                    max = 250f,
                    step = 1f,
                    majorEvery = 10,
                    midEvery = 5
                )
                OnboardingStep.WEIGHT -> RulerPicker(
                    value = weightKg,
                    onValueChange = { weightKg = it },
                    min = 30f,
                    max = 250f,
                    step = 0.5f,
                    majorEvery = 10,
                    midEvery = 2
                )
                OnboardingStep.TARGET -> RulerPicker(
                    value = targetKg,
                    onValueChange = { targetKg = it },
                    min = 30f,
                    max = 250f,
                    step = 0.5f,
                    majorEvery = 10,
                    midEvery = 2
                )
                OnboardingStep.BODY_FAT -> BodyFatSlider(value = bodyFat, onValueChange = { bodyFat = it })
                else -> WeeklyTargetPicker(selected = weeklyTarget, onSelect = { weeklyTarget = it })
            }
        }

        Button(
            onClick = {
                when (step) {
                    OnboardingStep.WEIGHT -> if (!hasTarget) targetKg = weightKg
                    OnboardingStep.TARGET -> hasTarget = true
                    OnboardingStep.BODY_FAT -> hasBodyFat = true
                    else -> {}
                }
                goNext()
            },
            enabled = step != OnboardingStep.NAME || name.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppAccent, contentColor = AppOnAccent)
        ) {
            Text(text = "Continue", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Always 48dp tall, even when there's nothing to skip, so the
        // content above never shifts between screens.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isOptional) {
                TextButton(
                    onClick = {
                        if (step == OnboardingStep.TARGET) hasTarget = false else hasBodyFat = false
                        goNext()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (step == OnboardingStep.TARGET) "Skip, no target for now" else "Skip, I don't know it",
                        color = AppTextSecondary,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingTopBar(stepIndex: Int, showBack: Boolean, showProgress: Boolean, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showBack) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(44.dp)
                    .background(AppSurface, RoundedCornerShape(12.dp))
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Back", tint = AppTextPrimary)
            }
        } else {
            Spacer(modifier = Modifier.width(44.dp))
        }
        if (showProgress) {
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(QUESTION_COUNT) { index ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(if (index <= stepIndex) AppAccent else AppBorder, RoundedCornerShape(2.dp))
                    )
                }
            }
            Text(
                text = "${stepIndex + 1}/$QUESTION_COUNT",
                style = MaterialTheme.typography.labelMedium,
                color = AppTextSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(44.dp)
            )
        }
    }
}

@Composable
private fun OnboardingHeader(label: String, optional: Boolean, title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .padding(top = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = AppAccent)
            if (optional) {
                Text(
                    text = "OPTIONAL",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextSecondary,
                    modifier = Modifier
                        .background(AppSurfaceVariant, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = title,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            color = AppTextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = subtitle,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = AppTextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The big centered number with its unit beside it. */
@Composable
private fun BigValue(value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = value,
            fontSize = 88.sp,
            lineHeight = 88.sp,
            fontWeight = FontWeight.Bold,
            color = AppTextPrimary,
            modifier = Modifier.alignByBaseline()
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = unit,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = AppTextSecondary,
            modifier = Modifier.alignByBaseline()
        )
    }
}

@Composable
private fun ValueChip(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier
            .background(AppSurface, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun AgeStepper(onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        RoundIconButton(icon = { Icon(Icons.Filled.Remove, contentDescription = "Decrease age", tint = AppTextPrimary) }, onClick = onDecrease)
        RoundIconButton(icon = { Icon(Icons.Filled.Add, contentDescription = "Increase age", tint = AppTextPrimary) }, onClick = onIncrease)
    }
}

@Composable
private fun RoundIconButton(icon: @Composable () -> Unit, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(64.dp)
            .background(AppSurface, CircleShape)
            .border(1.dp, AppBorder, CircleShape)
    ) {
        icon()
    }
}

/**
 * A horizontal ruler you drag sideways to pick a value. The orange line in
 * the middle marks the current value; the ruler moves smoothly under your
 * finger and the value snaps to [step]. [majorEvery] / [midEvery] are counted
 * in steps (e.g. step 0.5 with majorEvery 10 = a long, labelled tick every 5).
 */
@Composable
private fun RulerPicker(
    value: Float,
    onValueChange: (Float) -> Unit,
    min: Float,
    max: Float,
    step: Float,
    majorEvery: Int,
    midEvery: Int,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val spacingPx = with(LocalDensity.current) { 10.dp.toPx() }
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onValueChange)
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val labelStyle = TextStyle(color = AppTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .pointerInput(min, max, step) {
                detectHorizontalDragGestures(
                    onDragStart = { dragValue = latestValue },
                    onDragEnd = { dragValue = null },
                    onDragCancel = { dragValue = null }
                ) { change, dragAmount ->
                    change.consume()
                    val raw = ((dragValue ?: latestValue) - dragAmount / spacingPx * step).coerceIn(min, max)
                    dragValue = raw
                    val snapped = ((raw / step).roundToInt() * step).coerceIn(min, max)
                    if (snapped != latestValue) latestOnChange(snapped)
                }
            }
    ) {
        val shown = dragValue ?: value
        val centerX = size.width / 2f
        val tickCount = ((max - min) / step).roundToInt()
        val strokePx = 2.dp.toPx()

        for (i in 0..tickCount) {
            val tickValue = min + i * step
            val x = centerX + (tickValue - shown) / step * spacingPx
            if (x < -40f || x > size.width + 40f) continue

            val stepNumber = (tickValue / step).roundToInt()
            val isMajor = stepNumber % majorEvery == 0
            val isMid = stepNumber % midEvery == 0
            val tickHeight = when {
                isMajor -> 32.dp.toPx()
                isMid -> 22.dp.toPx()
                else -> 14.dp.toPx()
            }
            drawLine(
                color = if (isMajor) AppTextSecondary else Color(0xFF3A3A40),
                start = Offset(x, 0f),
                end = Offset(x, tickHeight),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
            if (isMajor) {
                val layout = textMeasurer.measure(tickValue.roundToInt().toString(), labelStyle)
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, 42.dp.toPx()))
            }
        }

        drawLine(
            color = AppAccent,
            start = Offset(centerX, 0f),
            end = Offset(centerX, 44.dp.toPx()),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun BodyFatSlider(value: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = value,
            onValueChange = { onValueChange(it.roundToInt().toFloat()) },
            valueRange = 3f..50f,
            colors = SliderDefaults.colors(
                thumbColor = AppTextPrimary,
                activeTrackColor = AppAccent,
                inactiveTrackColor = AppSurfaceVariant
            )
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "3%", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
            Text(text = "50%", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
        }
    }
}

@Composable
private fun WeeklyTargetPicker(selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (days in 1..7) {
                val isSelected = days == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .background(if (isSelected) AppAccent else AppSurface, RoundedCornerShape(14.dp))
                        .border(1.dp, if (isSelected) AppAccent else AppBorder, RoundedCornerShape(14.dp))
                        .clickable { onSelect(days) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$days",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) AppOnAccent else AppTextPrimary
                    )
                }
            }
        }
        // A preview of the home screen's weekly target widget.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (day in 1..7) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .background(if (day <= selected) AppAccent else AppSurfaceVariant, RoundedCornerShape(4.dp))
                )
            }
        }
    }
}

private data class SummaryRow(val label: String, val value: String?, val step: OnboardingStep)

@Composable
private fun SummaryContent(
    name: String,
    rows: List<SummaryRow>,
    onEdit: (OnboardingStep) -> Unit,
    saveError: String?,
    isSaving: Boolean,
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(AppAccent, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Filled.Check, contentDescription = null, tint = AppOnAccent, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "You're all set, ${name.substringBefore(" ")}",
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Check everything looks right. You can change any of this later from your profile.",
                fontSize = 15.sp,
                lineHeight = 21.sp,
                color = AppTextSecondary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppSurface, RoundedCornerShape(18.dp))
                    .padding(horizontal = 18.dp, vertical = 4.dp)
            ) {
                rows.forEachIndexed { index, row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = AppTextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = row.value ?: "Skipped",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (row.value == null) AppTextSecondary else AppTextPrimary
                        )
                        TextButton(onClick = { onEdit(row.step) }, enabled = !isSaving) {
                            Text(text = "Edit", color = AppAccent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (index < rows.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(AppBorder)
                        )
                    }
                }
            }
            if (saveError != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(text = saveError, style = MaterialTheme.typography.bodySmall, color = AppDanger)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = onStart,
            enabled = !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppAccent, contentColor = AppOnAccent)
        ) {
            if (isSaving) {
                CircularProgressIndicator(color = AppOnAccent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(text = "Start training", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

/** Shown right after sign-in while the profile loads (to decide whether
 *  onboarding is needed), or with a retry button if that load failed. */
@Composable
fun StartupStatus(failed: Boolean, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Couldn't load your account. Check your connection.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary,
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = onRetry) {
                    Text(text = "Try again", color = AppAccent, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = { AuthManager.signOut() }) {
                    Text(text = "Log out", color = AppTextSecondary)
                }
            }
        } else {
            CircularProgressIndicator(color = AppAccent)
        }
    }
}

private fun roundToHalf(value: Float): Float = (value * 2f).roundToInt() / 2f

private fun formatKg(value: Float): String = String.format(Locale.US, "%.1f", value)

private fun bmiLabel(bmi: Float): String = when {
    bmi < 18.5f -> "UNDERWEIGHT"
    bmi < 25f -> "HEALTHY RANGE"
    bmi < 30f -> "OVERWEIGHT"
    else -> "OBESE RANGE"
}
