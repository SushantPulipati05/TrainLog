package com.example.workoutlog_androidstudio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Seconds "Resend email" stays locked after sending. */
private const val RESEND_COOLDOWN_SECONDS = 60

/**
 * Shown instead of the app to someone signed in with an email address that
 * hasn't been confirmed yet. "I've verified" re-checks with Firebase; the
 * screen also re-checks every few seconds by itself, so coming back from the
 * email app is usually enough. "Sign out" goes back to Log in, e.g. to fix a
 * mistyped address.
 */
@Composable
fun VerifyEmailScreen() {
    val scope = rememberCoroutineScope()
    var isChecking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var cooldown by remember { mutableIntStateOf(0) }

    // Quietly checks in the background, so tapping the link and coming back
    // is enough on its own. Stops when the screen goes away.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            runCatching { AuthManager.checkEmailVerified() }
        }
    }
    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1_000)
            cooldown -= 1
        }
    }

    fun checkNow() {
        scope.launch {
            isChecking = true
            message = null
            try {
                if (!AuthManager.checkEmailVerified()) {
                    message = "Not verified yet. Open the email and tap the link, then try again."
                }
            } catch (e: Exception) {
                message = friendlyAuthError(e)
            } finally {
                isChecking = false
            }
        }
    }

    fun resend() {
        if (cooldown > 0) return
        scope.launch {
            message = null
            try {
                AuthManager.resendVerificationEmail()
                message = "Sent again. It can take a minute to arrive."
                cooldown = RESEND_COOLDOWN_SECONDS
            } catch (e: Exception) {
                message = friendlyAuthError(e)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        // Wordmark, placed like on the Log in screen.
        Row(modifier = Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "TRAINLOG", style = MaterialTheme.typography.labelMedium, color = AppAccent)
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(AppAccent, RoundedCornerShape(50))
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(AppSurface, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            EnvelopeGlyph(modifier = Modifier.size(30.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "ONE LAST STEP", style = MaterialTheme.typography.labelMedium, color = AppAccent)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Check your\ninbox.",
            fontSize = 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
            color = AppTextPrimary
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = buildAnnotatedString {
                append("We sent a link to ")
                withStyle(SpanStyle(color = AppTextPrimary, fontWeight = FontWeight.SemiBold)) {
                    append(AuthManager.currentEmail ?: "your email")
                }
                append(". Tap it to confirm this email is yours, then come back here.")
            },
            color = AppTextSecondary,
            fontSize = 14.sp,
            lineHeight = 21.sp
        )

        if (message != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppSurface)
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .heightIn(min = 44.dp)
                        .background(AppAccent)
                )
                Text(
                    text = message.orEmpty(),
                    color = AppTextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Can't find it? Check spam or promotions.", color = AppTextMuted, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = { checkNow() },
            enabled = !isChecking,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AppAccent,
                contentColor = AppOnAccent,
                disabledContainerColor = AppAccent.copy(alpha = 0.6f),
                disabledContentColor = AppOnAccent
            )
        ) {
            if (isChecking) {
                CircularProgressIndicator(color = AppOnAccent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(text = "I've verified", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AppSurface)
                .border(1.dp, AppBorder, RoundedCornerShape(14.dp))
                .clickable(enabled = cooldown == 0) { resend() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (cooldown > 0) "Resend in %d:%02d".format(cooldown / 60, cooldown % 60) else "Resend email",
                color = if (cooldown > 0) AppTextMuted else AppTextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Wrong email?", color = AppTextSecondary, fontSize = 14.sp)
            Text(
                text = "Sign out",
                color = AppAccent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { AuthManager.signOut() }
                    .padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }
    }
}

/** A drawn envelope: rounded rectangle with the flap's V. */
@Composable
private fun EnvelopeGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val u = size.width / 24f
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = AppAccent,
            topLeft = Offset(3f * u, 5.5f * u),
            size = Size(18f * u, 13f * u),
            cornerRadius = CornerRadius(2.5f * u),
            style = stroke
        )
        val flap = Path().apply {
            moveTo(3.8f * u, 7f * u)
            lineTo(12f * u, 13f * u)
            lineTo(20.2f * u, 7f * u)
        }
        drawPath(flap, color = AppAccent, style = stroke)
    }
}
