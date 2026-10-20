package com.example.workoutlog_androidstudio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/*
 * The body map (style B): an anatomical front + back figure whose muscles
 * are shaded in the accent colour. Used on Workout detail (shaded by sets)
 * and the Progress tab's Recovery view (shaded by how recently trained,
 * tap a muscle to filter).
 *
 * Shapes are drawn on a 100 x 220 grid; left-side shapes are mirrored to
 * make the right side.
 */

/** The shapes on the figure. */
enum class BodyRegion { HEAD, NECK, SHOULDERS, CHEST, BICEPS, TRICEPS, FOREARMS, ABS, OBLIQUES, TRAPS, UPPER_BACK, LATS, LOWER_BACK, GLUTES, QUADS, HAMSTRINGS, CALVES }

enum class BodyView { FRONT, BACK }

/** Never shaded - just part of the silhouette. */
private val NEUTRAL_REGIONS = setOf(BodyRegion.HEAD, BodyRegion.NECK, BodyRegion.OBLIQUES)

/** The exercise list's muscle names -> the shape that shows them. */
private val REGION_OF_MUSCLE: Map<String, BodyRegion> = mapOf(
    "CHEST" to BodyRegion.CHEST,
    "SHOULDERS" to BodyRegion.SHOULDERS,
    "TRICEPS" to BodyRegion.TRICEPS,
    "BICEPS" to BodyRegion.BICEPS,
    "FOREARMS" to BodyRegion.FOREARMS,
    "ABDOMINALS" to BodyRegion.ABS,
    "LATS" to BodyRegion.LATS,
    "MIDDLE BACK" to BodyRegion.UPPER_BACK,
    "LOWER BACK" to BodyRegion.LOWER_BACK,
    "TRAPS" to BodyRegion.TRAPS,
    "QUADRICEPS" to BodyRegion.QUADS,
    "ADDUCTORS" to BodyRegion.QUADS,
    "HAMSTRINGS" to BodyRegion.HAMSTRINGS,
    "GLUTES" to BodyRegion.GLUTES,
    "ABDUCTORS" to BodyRegion.GLUTES,
    "CALVES" to BodyRegion.CALVES
)

fun regionOfMuscle(muscle: String): BodyRegion? = REGION_OF_MUSCLE[muscle.trim().uppercase()]

/** The Progress tab's muscle-group chip a shape belongs to ("Legs" ...). */
fun progressGroupOf(region: BodyRegion): String? = when (region) {
    BodyRegion.CHEST -> "Chest"
    BodyRegion.SHOULDERS -> "Shoulders"
    BodyRegion.BICEPS, BodyRegion.TRICEPS, BodyRegion.FOREARMS -> "Arms"
    BodyRegion.ABS, BodyRegion.OBLIQUES -> "Abs"
    BodyRegion.TRAPS, BodyRegion.UPPER_BACK, BodyRegion.LATS, BodyRegion.LOWER_BACK -> "Back"
    BodyRegion.GLUTES, BodyRegion.QUADS, BodyRegion.HAMSTRINGS, BodyRegion.CALVES -> "Legs"
    BodyRegion.HEAD, BodyRegion.NECK -> null
}

/** All shapes in one of the Progress tab's groups - outlined when it's selected. */
fun regionsInGroup(group: String?): Set<BodyRegion> =
    if (group == null) emptySet() else BodyRegion.values().filter { progressGroupOf(it) == group && it !in NEUTRAL_REGIONS }.toSet()

/** "MIDDLE BACK" -> "Middle back" */
fun prettyMuscle(muscle: String): String =
    muscle.trim().lowercase(Locale.ENGLISH).replaceFirstChar { it.uppercase() }

// ---------------------------------------------------------------------------
// Shapes
// ---------------------------------------------------------------------------

private val NUMBER_PAIR = Regex("""(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")

/** The same shape flipped onto the other side of the body (x -> 100 - x). */
private fun mirror(d: String): String = NUMBER_PAIR.replace(d) { m ->
    val x = 100f - m.groupValues[1].toFloat()
    "${"%.1f".format(Locale.US, x)},${m.groupValues[2]}"
}

private fun both(d: String) = listOf(d, mirror(d))

private val HEAD = listOf("M50,4 C57,4 61,9 61,16 C61,23 57,28 50,28 C43,28 39,23 39,16 C39,9 43,4 50,4 Z")
private val NECK = listOf("M45,27 L55,27 L56,34 L44,34 Z")
private val SHOULDERS = both("M31,36 C24,36 19,41 19,49 C19,53 21,56 24,56 C27,50 31,45 36,42 Z")
private val UPPER_ARM = both("M20,57 C17,63 16,71 17,78 C20,80 24,79 26,76 C27,69 27,62 25,57 Z")
private val FOREARMS = both("M17,81 C14,90 12,100 12,110 C14,112 17,112 19,110 C22,101 24,91 25,82 Z")

private val FRONT_SHAPES: List<Pair<BodyRegion, List<String>>> = listOf(
    BodyRegion.HEAD to HEAD,
    BodyRegion.NECK to NECK,
    BodyRegion.SHOULDERS to SHOULDERS,
    BodyRegion.CHEST to both("M49,38 L37,40 C32,44 29,50 30,56 C34,61 43,62 49,59 Z"),
    BodyRegion.BICEPS to UPPER_ARM,
    BodyRegion.FOREARMS to FOREARMS,
    BodyRegion.ABS to listOf("M42,62 L58,62 L58,74 L42,74 Z", "M42,76 L58,76 L58,88 L42,88 Z", "M43,90 L57,90 L55,104 L45,104 Z"),
    BodyRegion.OBLIQUES to both("M32,62 C34,72 35,84 38,98 L41,98 L41,62 Z"),
    BodyRegion.QUADS to both("M37,108 C33,120 32,136 34,152 C37,156 43,156 46,152 C48,138 48,122 47,110 Z"),
    BodyRegion.CALVES to both("M35,162 C33,172 34,186 37,198 C39,200 42,200 43,197 C45,186 45,172 43,162 Z")
)

private val BACK_SHAPES: List<Pair<BodyRegion, List<String>>> = listOf(
    BodyRegion.HEAD to HEAD,
    BodyRegion.NECK to NECK,
    BodyRegion.TRAPS to listOf("M50,28 L60,34 L66,38 L56,46 L50,52 L44,46 L34,38 L40,34 Z"),
    BodyRegion.SHOULDERS to SHOULDERS,
    BodyRegion.UPPER_BACK to both("M49,52 L44,47 L37,42 C34,46 33,52 35,58 L49,62 Z"),
    BodyRegion.LATS to both("M34,58 C32,66 33,76 37,86 L44,82 L49,64 Z"),
    BodyRegion.TRICEPS to UPPER_ARM,
    BodyRegion.FOREARMS to FOREARMS,
    BodyRegion.LOWER_BACK to listOf("M44,84 L56,84 L57,100 L43,100 Z"),
    BodyRegion.GLUTES to both("M49,102 L38,102 C34,107 34,116 38,121 C42,124 47,123 49,120 Z"),
    BodyRegion.HAMSTRINGS to both("M37,124 C34,134 34,144 36,154 C39,157 44,157 46,154 C48,144 48,132 47,124 Z"),
    BodyRegion.CALVES to both("M35,162 C32,170 32,182 36,194 C39,198 42,198 44,194 C46,182 45,170 43,162 Z")
)

private class ParsedShape(val region: BodyRegion, val path: Path, val hit: android.graphics.Region)

/** How much finer than the 100 x 220 grid tap-testing works. */
private const val HIT_SCALE = 4f

private fun parseShapes(shapes: List<Pair<BodyRegion, List<String>>>): List<ParsedShape> =
    shapes.flatMap { (region, ds) ->
        ds.map { d ->
            val path = PathParser().parsePathString(d).toPath()
            val scaled = android.graphics.Path(path.asAndroidPath()).apply {
                transform(android.graphics.Matrix().apply { setScale(HIT_SCALE, HIT_SCALE) })
            }
            val clip = android.graphics.Region(0, 0, (100 * HIT_SCALE).toInt(), (220 * HIT_SCALE).toInt())
            ParsedShape(region, path, android.graphics.Region().apply { setPath(scaled, clip) })
        }
    }

private val parsedFront by lazy { parseShapes(FRONT_SHAPES) }
private val parsedBack by lazy { parseShapes(BACK_SHAPES) }

/** The shape under a point on the 100 x 220 grid, if any (a near miss
 *  still counts, so small shapes are easy to hit). */
private fun regionAt(shapes: List<ParsedShape>, x: Float, y: Float): BodyRegion? {
    val px = (x * HIT_SCALE).toInt()
    val py = (y * HIT_SCALE).toInt()
    shapes.firstOrNull { it.hit.contains(px, py) }?.let { return it.region }
    // Nothing exactly under the finger: the closest shape within ~4 units.
    val reach = (4 * HIT_SCALE).toInt()
    return shapes
        .filter { s ->
            val b = s.hit.bounds
            px in (b.left - reach)..(b.right + reach) && py in (b.top - reach)..(b.bottom + reach)
        }
        .minByOrNull { s ->
            val b = s.hit.bounds
            val dx = maxOf(b.left - px, 0, px - b.right)
            val dy = maxOf(b.top - py, 0, py - b.bottom)
            dx * dx + dy * dy
        }
        ?.region
}

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

/** Untrained / ready muscles - the theme's border grey. */
private val BodyIdle = AppBorder

/** Accent at [level] (0 = grey, 1 = full orange). */
private fun bodyFill(level: Float): Color =
    if (level <= 0f) BodyIdle else AppAccent.copy(alpha = level.coerceIn(0.15f, 1f))

/**
 * One figure. [level] gives each shape's shading (0..1); shapes in
 * [outlined] get a white outline; [onTap] (optional) makes it tappable.
 */
@Composable
fun BodyFigure(
    view: BodyView,
    level: (BodyRegion) -> Float,
    modifier: Modifier = Modifier,
    width: Dp = 100.dp,
    outlined: Set<BodyRegion> = emptySet(),
    onTap: ((BodyRegion) -> Unit)? = null
) {
    val shapes = if (view == BodyView.FRONT) parsedFront else parsedBack
    val tap by rememberUpdatedState(onTap)
    val tapModifier = if (onTap == null) Modifier else Modifier.pointerInput(view) {
        detectTapGestures { offset: Offset ->
            val k = 100f / size.width
            regionAt(shapes, offset.x * k, offset.y * k)?.let { region -> tap?.invoke(region) }
        }
    }
    Canvas(modifier = modifier.size(width = width, height = width * 2.2f).then(tapModifier)) {
        val k = size.width / 100f
        scale(scale = k, pivot = Offset.Zero) {
            shapes.forEach { s ->
                val lvl = if (s.region in NEUTRAL_REGIONS) 0f else level(s.region)
                drawPath(s.path, color = bodyFill(lvl))
            }
            shapes.filter { it.region in outlined }.forEach { s ->
                drawPath(s.path, color = AppTextPrimary, style = Stroke(width = 1.6f))
            }
        }
    }
}

/** Front and back side by side, labelled, centred on the screen. */
@Composable
fun BodyMapPair(
    level: (BodyRegion) -> Float,
    modifier: Modifier = Modifier,
    figureWidth: Dp = 100.dp,
    outlined: Set<BodyRegion> = emptySet(),
    onTap: ((BodyRegion) -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)
    ) {
        listOf(BodyView.FRONT to "FRONT", BodyView.BACK to "BACK").forEach { (view, label) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BodyFigure(view = view, level = level, width = figureWidth, outlined = outlined, onTap = onTap)
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = label, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
            }
        }
    }
}

/** "Fewer sets ▪ ▪ ▪ More" - a small key under the figure. */
@Composable
fun BodyMapLegend(start: String, end: String, levels: List<Float>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = start, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
        Spacer(modifier = Modifier.width(2.dp))
        levels.forEach { lvl ->
            Box(modifier = Modifier.size(width = 14.dp, height = 8.dp).background(bodyFill(lvl), RoundedCornerShape(3.dp)))
        }
        Spacer(modifier = Modifier.width(2.dp))
        Text(text = end, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
    }
}

// ---------------------------------------------------------------------------
// Shading rules
// ---------------------------------------------------------------------------

/** Sets per shape for a workout (muscles without a shape are left out). */
fun setsByRegion(muscleSets: Map<String, Int>): Map<BodyRegion, Int> {
    val out = HashMap<BodyRegion, Int>()
    muscleSets.forEach { (muscle, sets) ->
        val region = regionOfMuscle(muscle) ?: return@forEach
        if (sets > 0) out[region] = (out[region] ?: 0) + sets
    }
    return out
}

/** Workout view: the most-worked shape is full orange, the rest scaled by
 *  their share of its sets (never fainter than 0.3), untrained grey. */
fun workoutShading(byRegion: Map<BodyRegion, Int>): (BodyRegion) -> Float {
    val max = byRegion.values.maxOrNull()?.takeIf { it > 0 } ?: return { 0f }
    return { r -> byRegion[r]?.let { 0.3f + 0.7f * it / max } ?: 0f }
}

/** Recovery view: trained hard today = full orange, yesterday = faded,
 *  2+ days ago = grey (ready) - the same 48-hour rule as Up next.
 *  [daysSince] comes from [recentlyTrainedMuscles]. */
fun recoveryShading(daysSince: Map<String, Long>): (BodyRegion) -> Float {
    val byRegion = HashMap<BodyRegion, Long>()
    daysSince.forEach { (muscle, days) ->
        val region = regionOfMuscle(muscle) ?: return@forEach
        byRegion[region] = minOf(byRegion[region] ?: days, days)
    }
    return { r -> when (byRegion[r]) { 0L -> 1f; 1L -> RECOVERY_YESTERDAY_LEVEL; else -> 0f } }
}

const val RECOVERY_YESTERDAY_LEVEL = 0.5f

/** "Chest 9" - a muscle tag with its set count in the accent colour. */
@Composable
fun MuscleCountChip(muscle: String, sets: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(AppSurfaceVariant, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = prettyMuscle(muscle), style = MaterialTheme.typography.bodySmall, color = AppTextPrimary)
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = sets.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = AppAccent
        )
    }
}

// ---------------------------------------------------------------------------
// Bitmap version - for home-screen widgets, which can't run Compose drawing
// ---------------------------------------------------------------------------

private val FRONT_ONLY = setOf(BodyRegion.CHEST, BodyRegion.BICEPS, BodyRegion.ABS, BodyRegion.QUADS)
private val BACK_ONLY = setOf(
    BodyRegion.TRAPS, BodyRegion.UPPER_BACK, BodyRegion.LATS, BodyRegion.TRICEPS,
    BodyRegion.LOWER_BACK, BodyRegion.GLUTES, BodyRegion.HAMSTRINGS
)

/** Front or back - whichever shows more of [regions] (a pull day shows the back). */
fun bestViewFor(regions: Set<BodyRegion>): BodyView =
    if (regions.count { it in BACK_ONLY } > regions.count { it in FRONT_ONLY }) BodyView.BACK else BodyView.FRONT

/** One figure drawn into a bitmap [widthPx] wide (2.2x as tall). */
fun bodyBitmap(view: BodyView, level: (BodyRegion) -> Float, widthPx: Int): android.graphics.Bitmap {
    val w = widthPx.coerceAtLeast(8)
    val bitmap = android.graphics.Bitmap.createBitmap(w, (w * 2.2f).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.scale(w / 100f, w / 100f)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val shapes = if (view == BodyView.FRONT) parsedFront else parsedBack
    shapes.forEach { s ->
        val lvl = if (s.region in NEUTRAL_REGIONS) 0f else level(s.region)
        paint.color = bodyFill(lvl).toArgb()
        canvas.drawPath(s.path.asAndroidPath(), paint)
    }
    return bitmap
}
