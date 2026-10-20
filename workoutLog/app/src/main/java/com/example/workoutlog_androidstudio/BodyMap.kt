package com.example.workoutlog_androidstudio

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/*
 * The body map: an anatomical front + back figure, split into the parts the
 * app tracks (three chest bands, three delt heads, three triceps heads ...),
 * shaded in the accent colour. Used on Workout detail (shaded by how much
 * each part was worked), the Progress tab's Recovery view (shaded by how
 * recently trained, tap a muscle to filter) and the Up next widgets.
 *
 * There's a male and a female figure (Profile -> Body map, asked once in
 * onboarding). Both have exactly the same parts; the female one is the
 * same shapes re-proportioned. It only changes the drawing.
 *
 * Shapes are drawn on a 100 x 220 grid; left-side shapes are mirrored to
 * make the right side.
 */

/** The shapes on the figure. */
enum class BodyRegion {
    HEAD, NECK,
    UPPER_CHEST, MID_CHEST, LOWER_CHEST,
    FRONT_DELT, SIDE_DELT, REAR_DELT,
    BICEPS_LONG, BICEPS_SHORT, BRACHIALIS, FOREARMS,
    TRICEPS_LONG, TRICEPS_LATERAL, TRICEPS_MEDIAL,
    ABS, OBLIQUES,
    UPPER_TRAPS, MID_TRAPS, UPPER_BACK, LATS, LOWER_BACK,
    QUAD_OUTER, QUAD_FRONT, QUAD_INNER, ADDUCTORS, SHIN,
    GLUTE_MED, GLUTES, HAM_OUTER, HAM_INNER, CALF_OUTER, CALF_INNER, SOLEUS
}

enum class BodyView { FRONT, BACK }

/** Which figure is drawn. */
enum class BodyType { MALE, FEMALE }

/** Never shaded - just part of the silhouette. */
private val NEUTRAL_REGIONS = setOf(BodyRegion.HEAD, BodyRegion.NECK, BodyRegion.OBLIQUES)

private val CHEST_PARTS = setOf(BodyRegion.UPPER_CHEST, BodyRegion.MID_CHEST, BodyRegion.LOWER_CHEST)
private val DELT_PARTS = setOf(BodyRegion.FRONT_DELT, BodyRegion.SIDE_DELT, BodyRegion.REAR_DELT)
private val TRICEPS_PARTS = setOf(BodyRegion.TRICEPS_LONG, BodyRegion.TRICEPS_LATERAL, BodyRegion.TRICEPS_MEDIAL)
private val BICEPS_PARTS = setOf(BodyRegion.BICEPS_LONG, BodyRegion.BICEPS_SHORT, BodyRegion.BRACHIALIS)
private val QUAD_PARTS = setOf(BodyRegion.QUAD_OUTER, BodyRegion.QUAD_FRONT, BodyRegion.QUAD_INNER)
private val HAM_PARTS = setOf(BodyRegion.HAM_OUTER, BodyRegion.HAM_INNER)
private val CALF_PARTS = setOf(BodyRegion.CALF_OUTER, BodyRegion.CALF_INNER, BodyRegion.SOLEUS, BodyRegion.SHIN)
private val GLUTE_PARTS = setOf(BodyRegion.GLUTES, BodyRegion.GLUTE_MED)

/** The exercise list's muscle names -> the shapes that show them (every part
 *  of that muscle - used where only the muscle is known, like recovery). */
private val REGIONS_OF_MUSCLE: Map<String, Set<BodyRegion>> = mapOf(
    "CHEST" to CHEST_PARTS,
    "SHOULDERS" to DELT_PARTS,
    "TRICEPS" to TRICEPS_PARTS,
    "BICEPS" to BICEPS_PARTS,
    "FOREARMS" to setOf(BodyRegion.FOREARMS),
    "ABDOMINALS" to setOf(BodyRegion.ABS),
    "LATS" to setOf(BodyRegion.LATS),
    "MIDDLE BACK" to setOf(BodyRegion.UPPER_BACK, BodyRegion.MID_TRAPS),
    "TRAPS" to setOf(BodyRegion.UPPER_TRAPS, BodyRegion.MID_TRAPS),
    "LOWER BACK" to setOf(BodyRegion.LOWER_BACK),
    "QUADRICEPS" to QUAD_PARTS,
    "ADDUCTORS" to setOf(BodyRegion.ADDUCTORS),
    "HAMSTRINGS" to HAM_PARTS,
    "GLUTES" to GLUTE_PARTS,
    "ABDUCTORS" to setOf(BodyRegion.GLUTE_MED),
    "CALVES" to CALF_PARTS
)

fun regionsOfMuscle(muscle: String): Set<BodyRegion> = REGIONS_OF_MUSCLE[muscle.trim().uppercase()].orEmpty()

/** Whether a muscle has a place on the figure at all. */
fun hasBodyRegion(muscle: String): Boolean = regionsOfMuscle(muscle).isNotEmpty()

/** The shapes that show one part an exercise works (see WorkoutCompleteness). */
fun regionsOfPart(part: MusclePart): Set<BodyRegion> = when (part) {
    MusclePart.UPPER_CHEST -> setOf(BodyRegion.UPPER_CHEST)
    MusclePart.MID_CHEST -> setOf(BodyRegion.MID_CHEST)
    MusclePart.LOWER_CHEST -> setOf(BodyRegion.LOWER_CHEST)
    MusclePart.FRONT_DELT -> setOf(BodyRegion.FRONT_DELT)
    MusclePart.SIDE_DELT -> setOf(BodyRegion.SIDE_DELT)
    MusclePart.REAR_DELT -> setOf(BodyRegion.REAR_DELT)
    MusclePart.TRI_LONG -> setOf(BodyRegion.TRICEPS_LONG)
    MusclePart.TRI_LATERAL -> setOf(BodyRegion.TRICEPS_LATERAL)
    MusclePart.TRI_MEDIAL -> setOf(BodyRegion.TRICEPS_MEDIAL)
    MusclePart.LATS -> setOf(BodyRegion.LATS)
    MusclePart.UPPER_BACK -> setOf(BodyRegion.UPPER_BACK, BodyRegion.MID_TRAPS, BodyRegion.UPPER_TRAPS)
    MusclePart.LOWER_BACK -> setOf(BodyRegion.LOWER_BACK)
    MusclePart.BI_LONG -> setOf(BodyRegion.BICEPS_LONG)
    MusclePart.BI_SHORT -> setOf(BodyRegion.BICEPS_SHORT)
    MusclePart.BRACHIALIS -> setOf(BodyRegion.BRACHIALIS)
    MusclePart.SQUAT, MusclePart.KNEE_EXTENSION -> QUAD_PARTS
    MusclePart.HINGE, MusclePart.LEG_CURL -> HAM_PARTS
    MusclePart.GLUTES -> GLUTE_PARTS
    MusclePart.CALVES -> CALF_PARTS
}

/** The Progress tab's muscle-group chip a shape belongs to ("Legs" ...). */
fun progressGroupOf(region: BodyRegion): String? = when (region) {
    in CHEST_PARTS -> "Chest"
    in DELT_PARTS -> "Shoulders"
    in TRICEPS_PARTS, in BICEPS_PARTS, BodyRegion.FOREARMS -> "Arms"
    BodyRegion.ABS, BodyRegion.OBLIQUES -> "Abs"
    BodyRegion.UPPER_TRAPS, BodyRegion.MID_TRAPS, BodyRegion.UPPER_BACK, BodyRegion.LATS, BodyRegion.LOWER_BACK -> "Back"
    BodyRegion.HEAD, BodyRegion.NECK -> null
    else -> "Legs"
}

/** All shapes in one of the Progress tab's groups - outlined when it's selected. */
fun regionsInGroup(group: String?): Set<BodyRegion> =
    if (group == null) emptySet() else BodyRegion.values().filter { progressGroupOf(it) == group && it !in NEUTRAL_REGIONS }.toSet()

/** "MIDDLE BACK" -> "Middle back" */
fun prettyMuscle(muscle: String): String =
    muscle.trim().lowercase(Locale.ENGLISH).replaceFirstChar { it.uppercase() }

// ---------------------------------------------------------------------------
// Which figure (Profile -> Body map)
// ---------------------------------------------------------------------------

object BodyFigureStore {
    private const val PREFS = "body_map"
    private const val KEY_TYPE = "type"
    private var prefs: android.content.SharedPreferences? = null

    /** null until the user has picked one (shown as the male figure). */
    var chosen by mutableStateOf<BodyType?>(null)
        private set

    val type: BodyType get() = chosen ?: BodyType.MALE

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        chosen = p.getString(KEY_TYPE, null)?.let { runCatching { BodyType.valueOf(it) }.getOrNull() }
    }

    fun set(context: Context, type: BodyType) {
        init(context)
        chosen = type
        prefs?.edit()?.putString(KEY_TYPE, type.name)?.apply()
        // The widgets draw the figure too.
        TrainLogWidgets.requestRefresh(context)
    }
}

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
private val DELT_INNER = both("M31,36 L36,42 C32,45 27,50 24,56 C25,50 26,43 31,36 Z")
private val DELT_OUTER = both("M31,36 C24,36 19,41 19,49 C19,53 21,56 24,56 C25,50 26,43 31,36 Z")
private val FOREARM = both("M17,81 C14,90 12,100 12,110 C14,112 17,112 19,110 C22,101 24,91 25,82 Z")

private val FRONT_SHAPES: List<Pair<BodyRegion, List<String>>> = listOf(
    BodyRegion.HEAD to HEAD,
    BodyRegion.NECK to NECK,
    BodyRegion.UPPER_CHEST to both("M49,38 L37,40 C35,41.5 33,43.5 32,46 L49,45.5 Z"),
    BodyRegion.MID_CHEST to both("M49,45.5 L32,46 C30.7,48.5 30,51 30,53.5 L49,53 Z"),
    BodyRegion.LOWER_CHEST to both("M49,53 L30,53.5 C30,54.5 30,55.3 30,56 C34,61 43,62 49,59 Z"),
    BodyRegion.FRONT_DELT to DELT_INNER,
    BodyRegion.SIDE_DELT to DELT_OUTER,
    BodyRegion.BICEPS_LONG to both("M20,57 C17,63 16,70 17,75 L21.5,74 C21,68 21.5,62 22.5,57 Z"),
    BodyRegion.BICEPS_SHORT to both("M22.5,57 C21.5,62 21,68 21.5,74 L23,77 C24.5,77.5 25.5,77 26,76 C27,69 27,62 25,57 Z"),
    BodyRegion.BRACHIALIS to both("M17,75 C17,76 17,77 17,78 C19,79.5 21.5,79.5 23,77 L21.5,74 Z"),
    BodyRegion.FOREARMS to FOREARM,
    BodyRegion.ABS to both("M42,62 L49.3,62 L49.3,74 L42,74 Z") + both("M42,76 L49.3,76 L49.3,88 L42,88 Z") + both("M43,90 L49.3,90 L49.3,104 L45,104 Z"),
    BodyRegion.OBLIQUES to both("M32,62 C34,72 35,84 38,98 L41,98 L41,62 Z"),
    BodyRegion.QUAD_OUTER to both("M37,108 C33,120 32,136 34,152 C35.5,153.5 37,154 38.5,154 C37.5,140 37.5,124 39.5,110 Z"),
    BodyRegion.QUAD_FRONT to both("M39.5,110 C37.5,124 37.5,140 38.5,154 C40.5,154.5 42,154 43,152.5 C43.5,146 44.5,140 46.5,136 C44.2,132 44,124 44,111 Z"),
    BodyRegion.QUAD_INNER to both("M43,152.5 C44.5,154 45.5,153.5 46,152 C47.5,145 47.5,140 46.5,136 C44.5,140 43.5,146 43,152.5 Z"),
    BodyRegion.ADDUCTORS to both("M44,111 C44,124 44.2,132 46.5,136 C47.5,128 48,120 47,110 Z"),
    BodyRegion.SHIN to both("M35,162 C33,172 34,186 37,198 C39,200 42,200 43,197 C45,186 45,172 43,162 Z")
)

private val BACK_SHAPES: List<Pair<BodyRegion, List<String>>> = listOf(
    BodyRegion.HEAD to HEAD,
    BodyRegion.NECK to NECK,
    BodyRegion.UPPER_TRAPS to listOf("M50,28 L60,34 L66,38 L58,40 L50,38 L42,40 L34,38 L40,34 Z"),
    BodyRegion.MID_TRAPS to listOf("M50,38 L58,40 L56,46 L50,52 L44,46 L42,40 Z"),
    BodyRegion.REAR_DELT to DELT_INNER,
    BodyRegion.SIDE_DELT to DELT_OUTER,
    BodyRegion.UPPER_BACK to both("M49,52 L44,47 L37,42 C34,46 33,52 35,58 L49,62 Z"),
    BodyRegion.LATS to both("M34,58 C32,66 33,76 37,86 L44,82 L49,64 Z"),
    BodyRegion.LOWER_BACK to both("M44,84 L49.3,84 L49.3,100 L43,100 Z"),
    BodyRegion.TRICEPS_LATERAL to both("M20,57 C17,63 16,70 17,76 L21,73 C21.5,67 22,62 23,57 Z"),
    BodyRegion.TRICEPS_LONG to both("M23,57 C22,62 21.5,67 21,73 L24.5,75 C25.3,75.3 25.8,75.6 26,76 C27,69 27,62 25,57 Z"),
    BodyRegion.TRICEPS_MEDIAL to both("M17,76 L17,78 C20,80 24,79 26,76 C25.8,75.6 25.3,75.3 24.5,75 L21,73 Z"),
    BodyRegion.FOREARMS to FOREARM,
    BodyRegion.GLUTE_MED to both("M37,98 L43,99.5 L43,102 L38,102 C36.5,103.5 35.5,105 35,107 C34.5,104 35,100.5 37,98 Z"),
    BodyRegion.GLUTES to both("M49,102 L38,102 C34,107 34,116 38,121 C42,124 47,123 49,120 Z"),
    BodyRegion.HAM_OUTER to both("M37,124 C34,134 34,144 36,154 C38,155.5 40,156 41,156 C40.5,145 41,134 42,124 Z"),
    BodyRegion.HAM_INNER to both("M42,124 C41,134 40.5,145 41,156 C43,156 44.5,155.5 46,154 C48,144 48,132 47,124 Z"),
    BodyRegion.CALF_OUTER to both("M35,162 C32,170 32,178 34,184 L39,180 L39,162 Z"),
    BodyRegion.CALF_INNER to both("M39,162 L39,180 L44,183 C45.5,176 45,169 43,162 Z"),
    BodyRegion.SOLEUS to both("M34,184 C34.5,188 35,191 36,194 C39,198 42,198 44,194 C44,190 44,186 44,183 L39,180 Z")
)

// ---- The female figure: the same shapes, re-proportioned ----

private val ARM_REGIONS = DELT_PARTS + TRICEPS_PARTS + BICEPS_PARTS + BodyRegion.FOREARMS

/** Body width at each height compared with the male figure: narrower
 *  shoulders and waist, wider hips and thighs. */
private val FEMALE_WIDTH = listOf(
    0f to 1f, 30f to 0.88f, 38f to 0.86f, 56f to 0.85f, 70f to 0.8f, 86f to 0.75f, 96f to 0.86f,
    106f to 1.1f, 116f to 1.15f, 128f to 1.1f, 148f to 1.0f, 160f to 0.93f, 220f to 0.92f
)

private fun femaleWidthAt(y: Float): Float {
    for (i in 1 until FEMALE_WIDTH.size) {
        val (y1, w1) = FEMALE_WIDTH[i]
        val (y0, w0) = FEMALE_WIDTH[i - 1]
        if (y <= y1) return w0 + (w1 - w0) * (y - y0) / (y1 - y0)
    }
    return FEMALE_WIDTH.last().second
}

private fun femaleShape(region: BodyRegion, d: String): String = NUMBER_PAIR.replace(d) { m ->
    var x = m.groupValues[1].toFloat()
    var y = m.groupValues[2].toFloat()
    when {
        region in ARM_REGIONS -> {
            // Slimmer arms, moved in with the narrower shoulders.
            val centre = if (x < 50f) 23f else 77f
            val inward = if (x < 50f) 4.2f else -4.2f
            val slim = if (region in DELT_PARTS) 0.76f else 0.8f
            x = centre + inward + (x - centre) * slim
            if (region in DELT_PARTS) y = 37f + (y - 37f) * 0.86f
        }
        region == BodyRegion.HEAD -> {
            x = 50f + (x - 50f) * 0.92f
            y = 16f + (y - 16f) * 0.94f
        }
        else -> {
            x = 50f + (x - 50f) * femaleWidthAt(y)
            if (region == BodyRegion.GLUTES) x = 50f + (x - 50f) * 1.04f
            if (region == BodyRegion.LOWER_CHEST && y > 55f) y += 2.4f
            if (region == BodyRegion.MID_CHEST && y > 50f) y += 0.6f
        }
    }
    "${"%.2f".format(Locale.US, x)},${"%.2f".format(Locale.US, y)}"
}

private class ParsedShape(val region: BodyRegion, val path: Path, val hit: android.graphics.Region)

/** How much finer than the 100 x 220 grid tap-testing works. */
private const val HIT_SCALE = 4f

private fun parseShapes(shapes: List<Pair<BodyRegion, List<String>>>, type: BodyType): List<ParsedShape> =
    shapes.flatMap { (region, ds) ->
        ds.map { raw ->
            val d = if (type == BodyType.FEMALE) femaleShape(region, raw) else raw
            val path = PathParser().parsePathString(d).toPath()
            val scaled = android.graphics.Path(path.asAndroidPath()).apply {
                transform(android.graphics.Matrix().apply { setScale(HIT_SCALE, HIT_SCALE) })
            }
            val clip = android.graphics.Region(0, 0, (100 * HIT_SCALE).toInt(), (220 * HIT_SCALE).toInt())
            ParsedShape(region, path, android.graphics.Region().apply { setPath(scaled, clip) })
        }
    }

private val parsedCache = java.util.concurrent.ConcurrentHashMap<Pair<BodyView, BodyType>, List<ParsedShape>>()

private fun shapesFor(view: BodyView, type: BodyType): List<ParsedShape> =
    parsedCache.getOrPut(view to type) { parseShapes(if (view == BodyView.FRONT) FRONT_SHAPES else BACK_SHAPES, type) }

/** The shape under a point on the 100 x 220 grid, if any (a near miss
 *  still counts, so small shapes are easy to hit). */
private fun regionAt(shapes: List<ParsedShape>, x: Float, y: Float): BodyRegion? {
    val px = (x * HIT_SCALE).toInt()
    val py = (y * HIT_SCALE).toInt()
    shapes.firstOrNull { it.region !in NEUTRAL_REGIONS && it.hit.contains(px, py) }?.let { return it.region }
    // Nothing exactly under the finger: the closest shape within ~4 units.
    val reach = (4 * HIT_SCALE).toInt()
    return shapes
        .filter { s ->
            val b = s.hit.bounds
            s.region !in NEUTRAL_REGIONS &&
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

/** Width of the thin line drawn between parts (on the 100-wide grid). */
private const val PART_GAP = 0.7f

/** Accent at [level] (0 = grey, 1 = full orange). */
private fun bodyFill(level: Float): Color =
    if (level <= 0f) BodyIdle else AppAccent.copy(alpha = level.coerceIn(0.15f, 1f))

/**
 * One figure. [level] gives each shape's shading (0..1); shapes in
 * [outlined] get a white outline; [onTap] (optional) makes it tappable.
 * [gapColor] is whatever the figure sits on - it draws the lines between parts.
 */
@Composable
fun BodyFigure(
    view: BodyView,
    level: (BodyRegion) -> Float,
    modifier: Modifier = Modifier,
    width: Dp = 100.dp,
    outlined: Set<BodyRegion> = emptySet(),
    type: BodyType = BodyFigureStore.type,
    gapColor: Color = AppBackground,
    onTap: ((BodyRegion) -> Unit)? = null
) {
    val shapes = shapesFor(view, type)
    val tap by rememberUpdatedState(onTap)
    val tapModifier = if (onTap == null) Modifier else Modifier.pointerInput(view, type) {
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
                drawPath(s.path, color = gapColor, style = Stroke(width = PART_GAP))
            }
            shapes.filter { it.region in outlined }.forEach { s ->
                drawPath(s.path, color = AppTextPrimary, style = Stroke(width = 1.4f))
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
    val context = LocalContext.current
    LaunchedEffect(Unit) { BodyFigureStore.init(context) }
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
// Picking the figure - onboarding and Profile
// ---------------------------------------------------------------------------

/** Two cards side by side - the female and the male figure - to pick from. */
@Composable
fun BodyTypeChooser(selected: BodyType?, onSelect: (BodyType) -> Unit, modifier: Modifier = Modifier, figureWidth: Dp = 64.dp) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(BodyType.FEMALE to "Female figure", BodyType.MALE to "Male figure").forEach { (type, label) ->
            val on = selected == type
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) AppSurfaceVariant else AppSurface)
                    .border(width = 1.5.dp, color = if (on) AppAccent else AppBorder, shape = RoundedCornerShape(18.dp))
                    .clickable { onSelect(type) }
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BodyFigure(
                    view = BodyView.FRONT,
                    level = { 0f },
                    width = figureWidth,
                    type = type,
                    gapColor = if (on) AppSurfaceVariant else AppSurface
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) AppTextPrimary else AppTextSecondary
                )
            }
        }
    }
}

/** Profile -> Body map: which figure the app draws. */
@Composable
fun BodyFigureSettingRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { BodyFigureStore.init(context) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppSurface, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Text("BODY MAP", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "The figure that shows the muscles you trained. Only changes the drawing.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextPrimary
        )
        Spacer(modifier = Modifier.height(12.dp))
        BodyTypeChooser(
            selected = BodyFigureStore.type,
            onSelect = { BodyFigureStore.set(context, it) },
            figureWidth = 40.dp
        )
    }
}

// ---------------------------------------------------------------------------
// Shading rules
// ---------------------------------------------------------------------------

/** Sets per shape from per-muscle counts (every part of each muscle). */
fun setsByRegion(muscleSets: Map<String, Int>): Map<BodyRegion, Double> {
    val out = HashMap<BodyRegion, Double>()
    muscleSets.forEach { (muscle, sets) ->
        if (sets <= 0) return@forEach
        regionsOfMuscle(muscle).forEach { out[it] = (out[it] ?: 0.0) + sets }
    }
    return out
}

/** Muscles that have their own shape but no parts in WorkoutCompleteness. */
private val DIRECT_MUSCLES = setOf("FOREARMS", "ABDOMINALS", "ADDUCTORS", "ABDUCTORS")

/** How much each shape was worked in a workout, from its exercises: the
 *  part each exercise works (incline press -> upper chest), helping
 *  muscles counted less (see [partCredit]). */
fun creditByRegion(exercises: List<ExerciseSetCount>): Map<BodyRegion, Double> {
    val out = HashMap<BodyRegion, Double>()
    partCredit(exercises).forEach { (part, credit) ->
        regionsOfPart(part).forEach { out[it] = (out[it] ?: 0.0) + credit }
    }
    exercises.forEach { e ->
        val muscle = e.muscleGroup?.trim()?.uppercase() ?: return@forEach
        if (muscle in DIRECT_MUSCLES && e.sets > 0) {
            regionsOfMuscle(muscle).forEach { out[it] = (out[it] ?: 0.0) + e.sets }
        }
    }
    return out
}

/** Workout view: the most-worked shape is full orange, the rest scaled by
 *  their share of it (never fainter than 0.25), untouched grey. */
fun workoutShading(byRegion: Map<BodyRegion, Double>): (BodyRegion) -> Float {
    val max = byRegion.values.maxOrNull()?.takeIf { it > 0.0 } ?: return { 0f }
    return { r -> byRegion[r]?.takeIf { it > 0.0 }?.let { (0.25 + 0.75 * it / max).toFloat() } ?: 0f }
}

/** Recovery view: trained hard today = full orange, yesterday = faded,
 *  2+ days ago = grey (ready) - the same 48-hour rule as Up next.
 *  [daysSince] comes from [recentlyTrainedMuscles]. */
fun recoveryShading(daysSince: Map<String, Long>): (BodyRegion) -> Float {
    val byRegion = HashMap<BodyRegion, Long>()
    daysSince.forEach { (muscle, days) ->
        regionsOfMuscle(muscle).forEach { region -> byRegion[region] = minOf(byRegion[region] ?: days, days) }
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
            fontWeight = FontWeight.SemiBold,
            color = AppAccent
        )
    }
}

// ---------------------------------------------------------------------------
// Bitmap version - for home-screen widgets, which can't run Compose drawing
// ---------------------------------------------------------------------------

private val FRONT_ONLY = CHEST_PARTS + BICEPS_PARTS + QUAD_PARTS + setOf(BodyRegion.ABS, BodyRegion.FRONT_DELT, BodyRegion.ADDUCTORS)
private val BACK_ONLY = TRICEPS_PARTS + HAM_PARTS + GLUTE_PARTS + setOf(
    BodyRegion.UPPER_TRAPS, BodyRegion.MID_TRAPS, BodyRegion.UPPER_BACK, BodyRegion.LATS,
    BodyRegion.LOWER_BACK, BodyRegion.REAR_DELT
)

/** Front or back - whichever shows more of [regions] (a pull day shows the back). */
fun bestViewFor(regions: Set<BodyRegion>): BodyView =
    if (regions.count { it in BACK_ONLY } > regions.count { it in FRONT_ONLY }) BodyView.BACK else BodyView.FRONT

/** One figure drawn into a bitmap [widthPx] wide (2.2x as tall), in the
 *  figure the user picked. [gapColor] is the widget's background. */
fun bodyBitmap(view: BodyView, level: (BodyRegion) -> Float, widthPx: Int, gapColor: Color = AppSurface): android.graphics.Bitmap {
    val w = widthPx.coerceAtLeast(8)
    val bitmap = android.graphics.Bitmap.createBitmap(w, (w * 2.2f).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.scale(w / 100f, w / 100f)
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val gap = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = PART_GAP
        color = gapColor.toArgb()
    }
    shapesFor(view, BodyFigureStore.type).forEach { s ->
        val lvl = if (s.region in NEUTRAL_REGIONS) 0f else level(s.region)
        fill.color = bodyFill(lvl).toArgb()
        val p = s.path.asAndroidPath()
        canvas.drawPath(p, fill)
        canvas.drawPath(p, gap)
    }
    return bitmap
}
