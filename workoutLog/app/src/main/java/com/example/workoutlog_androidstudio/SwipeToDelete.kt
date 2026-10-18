package com.example.workoutlog_androidstudio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** How far (share of the card's width) a swipe has to go before letting go
 *  asks to delete. */
private const val DELETE_THRESHOLD = 0.4f

/**
 * Swipe a card left to delete it - built to not fire while scrolling.
 *
 * - The swipe only starts when the finger moves clearly sideways (at least
 *   twice as far across as up/down). Any real vertical movement first hands
 *   the gesture to the list, so scrolling never moves the card.
 * - A quick flick doesn't count: only letting go past [DELETE_THRESHOLD] of
 *   the width does. A small buzz marks the point where it will.
 * - The card always springs back; [onRequestDelete] just opens the
 *   confirmation, so nothing is deleted without saying yes.
 */
@Composable
fun SwipeToDeleteBox(
    onRequestDelete: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val offset = remember { Animatable(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    val requestDelete by rememberUpdatedState(onRequestDelete)

    val pastThreshold = widthPx > 0 && -offset.value >= widthPx * DELETE_THRESHOLD

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f
                    var dy = 0f

                    // 1. Decide: sideways swipe, or leave it to the list.
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (!change.pressed || change.isConsumed) return@awaitEachGesture
                        val delta = change.positionChange()
                        dx += delta.x
                        dy += delta.y
                        // Moving up/down -> it's a scroll; never compete with it.
                        if (abs(dy) > slop) return@awaitEachGesture
                        if (abs(dx) > slop * 2) {
                            // Left only, and clearly more across than down.
                            if (dx < 0 && abs(dx) > abs(dy) * 2) {
                                change.consume()
                                break
                            }
                            return@awaitEachGesture
                        }
                    }

                    // 2. Follow the finger (left only, up to the full width).
                    var buzzed = false
                    horizontalDrag(down.id) { change ->
                        val next = (offset.value + change.positionChange().x)
                            .coerceIn(-widthPx.toFloat(), 0f)
                        scope.launch { offset.snapTo(next) }
                        change.consume()
                        val past = -next >= widthPx * DELETE_THRESHOLD
                        if (past && !buzzed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        buzzed = past
                    }

                    // 3. Let go: spring back, and ask to delete if it went far enough.
                    val confirmed = -offset.value >= widthPx * DELETE_THRESHOLD
                    scope.launch { offset.animateTo(0f, spring(stiffness = 500f)) }
                    if (confirmed) requestDelete()
                }
            }
    ) {
        // The red layer only exists while the card is moved, so no red edge
        // peeks out around the rounded corners at rest.
        if (offset.value < 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(if (pastThreshold) AppDanger else AppDanger.copy(alpha = 0.55f))
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = contentDescription,
                    tint = Color.White,
                    modifier = Modifier.scale(if (pastThreshold) 1.2f else 1f)
                )
            }
        }
        Box(modifier = Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) {
            content()
        }
    }
}
