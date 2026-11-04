package com.example.workoutlog_androidstudio

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

enum class WeightUnit { KG, LB }
enum class HeightUnit { CM, FT_IN }

/**
 * Which units this phone shows. Everything is still STORED and sent in kg and
 * cm - the server never sees pounds - so switching units only changes what is
 * typed and shown, and nothing already logged is ever changed.
 *
 * Weight typed into an active workout is held in the chosen unit, so changing
 * the unit converts what's in the workout (see [selectWeight]).
 */
object Units {
    private const val PREFS = "units"
    private const val KEY_WEIGHT = "weight"
    private const val KEY_HEIGHT = "height"
    private const val KG_PER_LB = 0.45359237
    private const val CM_PER_INCH = 2.54

    private var prefs: SharedPreferences? = null

    var weight by mutableStateOf(WeightUnit.KG)
        private set
    var height by mutableStateOf(HeightUnit.CM)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        weight = runCatching { WeightUnit.valueOf(p.getString(KEY_WEIGHT, null) ?: "KG") }.getOrDefault(WeightUnit.KG)
        height = runCatching { HeightUnit.valueOf(p.getString(KEY_HEIGHT, null) ?: "CM") }.getOrDefault(HeightUnit.CM)
    }

    fun selectWeight(unit: WeightUnit) {
        if (unit == weight) return
        val old = weight
        // Convert what's already typed into a running workout first, so
        // "100" typed in kg doesn't silently become 100 lb.
        ActiveWorkoutState.exercises = ActiveWorkoutState.exercises.map { exercise ->
            exercise.copy(sets = exercise.sets.map { set ->
                val typed = parseKg(set.kg)
                if (typed == null || set.kg.isBlank()) set
                else set.copy(kg = shownText(convert(typed, old, unit), unit))
            })
        }
        weight = unit
        prefs?.edit()?.putString(KEY_WEIGHT, unit.name)?.apply()
    }

    fun selectHeight(unit: HeightUnit) {
        if (unit == height) return
        height = unit
        prefs?.edit()?.putString(KEY_HEIGHT, unit.name)?.apply()
    }

    // ---- Weight ----

    /** "kg" or "lb". */
    val weightLabel: String get() = if (weight == WeightUnit.LB) "lb" else "kg"

    /** "KG" or "LB" - for column headers and big labels. */
    val weightLabelUpper: String get() = weightLabel.uppercase()

    private fun convert(value: Double, from: WeightUnit, to: WeightUnit): Double = when {
        from == to -> value
        from == WeightUnit.KG -> value / KG_PER_LB
        else -> value * KG_PER_LB
    }

    /** A weight stored in kg, as a number in the chosen unit. */
    fun fromKg(kg: Double): Double = convert(kg, WeightUnit.KG, weight)

    /** A weight typed or shown in the chosen unit, as kg. */
    fun toKg(shown: Double): Double = convert(shown, weight, WeightUnit.KG)

    private fun shownText(value: Double, unit: WeightUnit): String =
        // Pounds are rounded to one decimal so 135 lb comes back as "135", not
        // "135.00000000000003"; kg is left exactly as logged (2.25 stays 2.25).
        if (unit == WeightUnit.LB) formatDecimal((value * 10).roundToInt() / 10.0) else formatDecimal(value)

    /** "62.5" - a kg weight as text in the chosen unit, no unit label. */
    fun weightText(kg: Double): String = shownText(fromKg(kg), weight)

    /** "62.5 kg" / "137.8 lb". */
    fun weightWithUnit(kg: Double): String = "${weightText(kg)} $weightLabel"

    /** What's typed in a weight box, as kg - null when empty or not a number. */
    fun parseWeightKg(typed: String): Double? = parseKg(typed)?.let { toKg(it) }

    /**
     * Like [parseWeightKg], but a box left showing the value [original] had
     * comes back as exactly [original] - so opening an edit screen in pounds
     * and saving never nudges a stored kg value by a rounding hair.
     */
    fun kgFromInput(typed: String, original: Double?): Double? {
        val shown = parseKg(typed) ?: return null
        if (original != null && formatDecimal(shown) == weightText(original)) return original
        return toKg(shown)
    }

    /** "4,820 KG" - a total (volume) given in kg, in the chosen unit. */
    fun volumeText(kg: Double): String = String.format(java.util.Locale.US, "%,.0f", fromKg(kg)) + " " + weightLabelUpper

    /** The server's volume label ("4,820 KG") re-written in the chosen unit. */
    fun localizeVolumeLabel(label: String): String {
        if (weight == WeightUnit.KG) return label
        val kg = label.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return label
        return volumeText(kg)
    }

    /**
     * Sentences from the server that quote kg ("Estimated 1-rep max up 2.5 kg",
     * "60 → 62.5 → 65 kg") with each number turned into the chosen unit.
     */
    fun localizeKgText(text: String): String {
        if (weight == WeightUnit.KG) return text
        val chain = Regex("""(\d+(?:\.\d+)?(?:\s*→\s*\d+(?:\.\d+)?)*)\s*kg\b""")
        return chain.replace(text) { match ->
            val converted = Regex("""\d+(?:\.\d+)?""").replace(match.groupValues[1]) { n ->
                shownText(fromKg(n.value.toDouble()), weight)
            }
            "$converted lb"
        }
    }

    // ---- Height ----

    /** Whole feet and inches (inches rounded to the nearest, never 12). */
    fun cmToFeetInches(cm: Double): Pair<Int, Int> {
        val totalInches = (cm / CM_PER_INCH).roundToInt()
        return totalInches / 12 to totalInches % 12
    }

    fun feetInchesToCm(feet: Int, inches: Double): Double = (feet * 12 + inches) * CM_PER_INCH

    fun cmToInches(cm: Double): Double = cm / CM_PER_INCH

    fun inchesToCm(inches: Double): Double = inches * CM_PER_INCH

    /** "193 cm" or "6′ 4″". */
    fun heightText(cm: Double): String =
        if (height == HeightUnit.CM) "${formatDecimal(cm)} cm"
        else cmToFeetInches(cm).let { (ft, inch) -> "$ft′ $inch″" }

    /** The label that sits with a typed height in cm. */
    val heightLabelUpper: String get() = if (height == HeightUnit.CM) "CM" else "FT / IN"
}

/** "KG | LB"-style switch: the chosen option is filled with the accent colour. */
@Composable
fun UnitToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(AppSurfaceVariant, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) AppAccent else AppSurfaceVariant)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) AppOnAccent else AppTextSecondary
                )
            }
        }
    }
}
