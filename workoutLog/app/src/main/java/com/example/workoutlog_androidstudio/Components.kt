package com.example.workoutlog_androidstudio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

@Composable
fun TagChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(AppSurfaceVariant, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = AppTextPrimary)
    }
}

@Composable
fun StatColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = AppTextPrimary)
    }
}

@Composable
fun AppBottomSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {

    var shouldRender by remember { mutableStateOf(visible) }
    val transitionState = remember { MutableTransitionState(false) }

    if (visible) shouldRender = true

    LaunchedEffect(visible) {
        transitionState.targetState = visible
        if (!visible) {
            delay(260)
            shouldRender = false
        }
    }

    if (!shouldRender) return

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visibleState = transitionState,
                enter = fadeIn(animationSpec = tween(260)),
                exit = fadeOut(animationSpec = tween(200)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismissRequest
                        )
                )
            }
            AnimatedVisibility(
                visibleState = transitionState,
                enter = slideInVertically(
                    initialOffsetY = { fullHeight -> fullHeight },
                    animationSpec = tween(340, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(220)),
                exit = slideOutVertically(
                    targetOffsetY = { fullHeight -> fullHeight },
                    animationSpec = tween(260, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(180)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppSurface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 6.dp, bottom = 14.dp)
                            .width(40.dp)
                            .height(4.dp)
                            .background(AppBorder, RoundedCornerShape(50))
                    )
                    content()
                }
            }
        }
    }
}

@Composable
fun appTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AppTextPrimary,
    unfocusedTextColor = AppTextPrimary,
    focusedBorderColor = AppAccent,
    unfocusedBorderColor = AppBorder,
    cursorColor = AppAccent
)

@Composable
fun LoadingOrError(
    isLoading: Boolean,
    errorMessage: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    when {
        isLoading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppAccent)
            }
        }
        errorMessage != null -> {
            Box(modifier = modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Text(text = errorMessage, style = MaterialTheme.typography.bodyMedium, color = AppTextSecondary)
            }
        }
        else -> content()
    }
}

@Composable
fun DeleteConfirmationPopup(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onCancel,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true)
    ) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .background(AppSurface.copy(alpha = 0.9f), RoundedCornerShape(20.dp))
                .padding(22.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, color = AppTextPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = AppTextSecondary)
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(text = "Cancel", color = AppTextSecondary)
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppDanger, contentColor = Color.White)
                ) {
                    Text(text = "Delete", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun BoxScope.DeletedToast(visible: Boolean, message: String, tint: Color = AppSuccess) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 20.dp, bottom = 32.dp),
        enter = slideInHorizontally(initialOffsetX = { fullWidth -> fullWidth }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { fullWidth -> -fullWidth }) + fadeOut()
    ) {
        Row(
            modifier = Modifier
                .shadow(elevation = 10.dp, shape = RoundedCornerShape(14.dp))
                .background(AppSurface, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.width(18.dp).height(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = AppTextPrimary)
        }
    }
}

@Composable
fun BackChevronRow(onBack: () -> Unit, tint: Color = AppAccent, label: String? = null) {
    Row(
        modifier = Modifier.clickable(onClick = onBack),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icons.Filled.ChevronLeft, contentDescription = "Back", tint = tint)
        if (label != null) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = tint)
        }
    }
}
