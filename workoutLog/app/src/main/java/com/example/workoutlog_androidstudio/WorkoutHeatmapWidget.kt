package com.example.workoutlog_androidstudio

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.unit.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.example.workoutlog_androidstudio.api.NetworkClient
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

private val CELL_SIZE = 9.dp
private val CELL_GAP = 2.dp

private sealed class HeatmapWidgetState {
    data class Loaded(val exerciseCountByDate: Map<LocalDate, Int>) : HeatmapWidgetState()
    object Error : HeatmapWidgetState()
}

class WorkoutHeatmapWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state: HeatmapWidgetState = try {
            withTimeoutOrNull(8_000) {
                val summaries = NetworkClient.workoutApi.getWorkoutSummaries()
                val exerciseCountByDate = summaries
                    .mapNotNull { summary ->
                        runCatching { LocalDate.parse(summary.date) }.getOrNull()?.let { it to summary.exerciseCount }
                    }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { (_, counts) -> counts.sum() }
                HeatmapWidgetState.Loaded(exerciseCountByDate)
            } ?: HeatmapWidgetState.Error
        } catch (e: Exception) {
            HeatmapWidgetState.Error
        }

        provideContent {
            WorkoutHeatmapWidgetContent(state)
        }
    }
}

class WorkoutHeatmapWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WorkoutHeatmapWidget()
}

@Composable
private fun WorkoutHeatmapWidgetContent(state: HeatmapWidgetState) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val nextMonthAnchor = today.plusMonths(1)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(AppBackground)
            .cornerRadius(20.dp)
            .padding(14.dp)
            .clickable(onClick = actionStartActivity(Intent(context, MainActivity::class.java)))
    ) {
        when (state) {
            is HeatmapWidgetState.Loaded -> {
                Column(modifier = GlanceModifier.fillMaxSize()) {
                    Text(
                        text = "CONSISTENCY",
                        style = TextStyle(
                            color = ColorProvider(AppTextMuted),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    Row(modifier = GlanceModifier.fillMaxSize()) {
                        WidgetMonthGrid(monthAnchor = today, exerciseCountByDate = state.exerciseCountByDate)
                        Spacer(modifier = GlanceModifier.width(16.dp))
                        WidgetMonthGrid(monthAnchor = nextMonthAnchor, exerciseCountByDate = state.exerciseCountByDate)
                    }
                }
            }
            HeatmapWidgetState.Error -> {
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Couldn't load. Tap to open WorkoutLog.",
                        style = TextStyle(
                            color = ColorProvider(AppTextSecondary),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetMonthGrid(monthAnchor: LocalDate, exerciseCountByDate: Map<LocalDate, Int>) {
    val weeks = weeksOfMonth(monthAnchor)

    Column {
        Text(
            text = monthAnchor.month.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault()).uppercase(),
            style = TextStyle(color = ColorProvider(AppTextMuted), fontSize = 9.sp)
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        weeks.forEach { week ->
            Row {
                week.forEach { date ->
                    val inMonth = date.month == monthAnchor.month && date.year == monthAnchor.year
                    WidgetHeatmapCell(inCurrentMonth = inMonth, exerciseCount = exerciseCountByDate[date] ?: 0)
                    Spacer(modifier = GlanceModifier.width(CELL_GAP))
                }
            }
            Spacer(modifier = GlanceModifier.height(CELL_GAP))
        }
    }
}

@Composable
private fun WidgetHeatmapCell(inCurrentMonth: Boolean, exerciseCount: Int) {
    val cellColor: Color = heatmapCellColor(inCurrentMonth, exerciseCount)

    Box(
        modifier = GlanceModifier
            .size(CELL_SIZE)
            .background(cellColor)
            .cornerRadius(2.dp)
    ) {}
}
