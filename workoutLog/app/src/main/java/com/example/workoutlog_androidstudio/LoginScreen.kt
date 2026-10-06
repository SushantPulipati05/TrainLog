package com.example.workoutlog_androidstudio

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The only thing shown until someone is signed in: log in or sign up with
 * email + password, or one tap with Google. Signing in flips
 * [AuthManager.isSignedIn], which swaps this screen out for the app.
 *
 * Log in and Sign up are two separate pages (sliding between them like the
 * rest of the app's screens), built from the app's own pieces so they read
 * as TrainLog: the accent micro-labels and dot from the Profile header, a
 * strip of the consistency heatmap, the app's outlined fields and the orange
 * primary button from onboarding.
 */
@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isCreating by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }

    fun switchMode(toCreating: Boolean) {
        isCreating = toCreating
        password = ""
        confirmPassword = ""
        errorMessage = null
        infoMessage = null
    }

    fun launchAuth(block: suspend () -> Unit) {
        scope.launch {
            isBusy = true
            errorMessage = null
            infoMessage = null
            try {
                block()
            } catch (e: Exception) {
                errorMessage = friendlyAuthError(e)
            } finally {
                isBusy = false
            }
        }
    }

    fun submit() {
        when {
            email.isBlank() || password.isEmpty() -> errorMessage = "Enter your email and password."
            isCreating && password.length < 8 -> errorMessage = "Use at least 8 characters for your password."
            isCreating && password != confirmPassword -> errorMessage = "Those passwords don't match."
            else -> launchAuth {
                if (isCreating) AuthManager.createAccount(email, password) else AuthManager.signInWithEmail(email, password)
                AuthManager.offerToSavePassword(context, email, password)
            }
        }
    }

    // Phone back from Sign up returns to Log in instead of closing the app.
    BackHandler(enabled = isCreating && !isBusy) { switchMode(false) }

    AnimatedContent(
        targetState = isCreating,
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground),
        transitionSpec = {
            val spec = tween<IntOffset>(durationMillis = 320, easing = FastOutSlowInEasing)
            if (targetState) {
                slideInHorizontally(spec) { width -> width } togetherWith slideOutHorizontally(spec) { width -> -width / 4 }
            } else {
                slideInHorizontally(spec) { width -> -width / 4 } togetherWith slideOutHorizontally(spec) { width -> width }
            }
        },
        label = "authPage"
    ) { isCreating ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        // Sign up has a back button to Log in; Log in keeps the same space
        // empty so the wordmark sits in the same place on both pages.
        Box(modifier = Modifier.height(44.dp)) {
            if (isCreating) {
                IconButton(
                    onClick = { switchMode(false) },
                    enabled = !isBusy,
                    modifier = Modifier
                        .size(44.dp)
                        .background(AppSurface, RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Back to log in",
                        tint = AppTextPrimary
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Wordmark, in the same style as the Profile screen's header.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "TRAINLOG", style = MaterialTheme.typography.labelMedium, color = AppAccent)
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(AppAccent, RoundedCornerShape(50))
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
        HeatmapStrip()
        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = if (isCreating) "NEW ATHLETE" else "WELCOME BACK",
            style = MaterialTheme.typography.labelMedium,
            color = AppAccent
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = if (isCreating) "Start logging\nyour training." else "Pick up where\nyou left off.",
            fontSize = 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
            color = AppTextPrimary
        )

        Spacer(modifier = Modifier.height(28.dp))

        AuthField(
            value = email,
            onValueChange = { email = it },
            placeholder = "Email",
            icon = Icons.Outlined.Email,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next
        )
        Spacer(modifier = Modifier.height(12.dp))
        AuthField(
            value = password,
            onValueChange = { password = it },
            placeholder = if (isCreating) "Password (8+ characters)" else "Password",
            icon = Icons.Outlined.Lock,
            keyboardType = KeyboardType.Password,
            imeAction = if (isCreating) ImeAction.Next else ImeAction.Done,
            isPassword = true
        )
        if (isCreating) {
            Spacer(modifier = Modifier.height(12.dp))
            AuthField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                placeholder = "Confirm password",
                icon = Icons.Outlined.Lock,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                isPassword = true
            )
        } else {
            Text(
                text = "Forgot password?",
                style = MaterialTheme.typography.labelMedium,
                color = AppAccent,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !isBusy) {
                        if (email.isBlank()) {
                            errorMessage = "Enter your email above first, then tap Forgot password."
                        } else {
                            launchAuth {
                                AuthManager.sendPasswordReset(email)
                                infoMessage = "If an account exists for that email, a reset link is on its way."
                            }
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(text = errorMessage.orEmpty(), style = MaterialTheme.typography.bodySmall, color = AppDanger)
        }
        if (infoMessage != null) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(text = infoMessage.orEmpty(), style = MaterialTheme.typography.bodySmall, color = AppSuccess)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { submit() },
            enabled = !isBusy,
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
            if (isBusy) {
                CircularProgressIndicator(color = AppOnAccent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(text = if (isCreating) "Create account" else "Log in", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = AppBorder)
            Text(
                text = "  OR  ",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = AppBorder)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AppSurface)
                .border(1.dp, AppBorder, RoundedCornerShape(14.dp))
                .clickable(enabled = !isBusy) { launchAuth { AuthManager.signInWithGoogle(context) } },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_google_logo),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "Continue with Google", color = AppTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }

        Spacer(modifier = Modifier.height(28.dp))

        // The way to the other page.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isCreating) "Already have an account?" else "New to TrainLog?",
                color = AppTextSecondary,
                fontSize = 14.sp
            )
            Text(
                text = if (isCreating) "Log in" else "Create an account",
                color = AppAccent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !isBusy) { switchMode(!isCreating) }
                    .padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }
    }
    }
}

/** A purely decorative strip of the consistency heatmap - the app's
 *  signature visual - using the exact cell colours the real one uses. */
@Composable
private fun HeatmapStrip() {
    // Fixed pattern of "exercises logged" per cell, so it looks like a few
    // weeks of real training rather than random noise.
    val counts = listOf(
        listOf(0, 3, 0, 5, 2, 0, 1, 4, 0, 6, 3, 0, 2, 5),
        listOf(2, 0, 4, 1, 0, 6, 3, 0, 5, 2, 0, 4, 6, 1),
        listOf(0, 5, 2, 0, 3, 4, 0, 6, 1, 0, 5, 3, 0, 6)
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        counts.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { count ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .background(heatmapCellColor(inCurrentMonth = true, exerciseCount = count), RoundedCornerShape(4.dp))
                    )
                }
            }
        }
    }
}

/** One input in the app's standard outlined style; what it's for is in
 *  its placeholder. Password fields get a show/hide eye. */
@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    isPassword: Boolean = false
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text(text = placeholder, color = AppTextMuted) },
            leadingIcon = { Icon(imageVector = icon, contentDescription = null, tint = AppTextMuted, modifier = Modifier.size(20.dp)) },
            trailingIcon = if (isPassword) {
                {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (visible) "Hide password" else "Show password",
                            tint = AppTextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else {
                null
            },
            visualTransformation = if (isPassword && !visible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            shape = RoundedCornerShape(14.dp),
            colors = appTextFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
}
