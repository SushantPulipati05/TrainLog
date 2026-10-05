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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The only thing shown until someone is signed in: a one-tap "Continue with
 *  Google", or email + password to log in / create an account. Signing in
 *  flips [AuthManager.isSignedIn], which swaps this screen out for the app. */
@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isCreating by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }

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
        if (email.isBlank() || password.isEmpty()) {
            errorMessage = "Enter your email and password."
            return
        }
        if (isCreating && password.length < 8) {
            errorMessage = "Use at least 8 characters for your password."
            return
        }
        launchAuth {
            if (isCreating) AuthManager.createAccount(email, password) else AuthManager.signInWithEmail(email, password)
            AuthManager.offerToSavePassword(context, email, password)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (isCreating) "Create your account" else "Welcome back",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = AppTextPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (isCreating) "Start logging your workouts." else "Log in to pick up where you left off.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(14.dp))
                .clickable(enabled = !isBusy) { launchAuth { AuthManager.signInWithGoogle(context) } }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Continue with Google",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.Black
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = AppBorder)
            Text(
                text = "  or use email  ",
                style = MaterialTheme.typography.labelSmall,
                color = AppTextMuted
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = AppBorder)
        }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            singleLine = true,
            placeholder = { Text(text = "Email", color = AppTextMuted) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
            colors = appTextFieldColors()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            singleLine = true,
            placeholder = { Text(text = "Password", color = AppTextMuted) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            colors = appTextFieldColors()
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = errorMessage.orEmpty(), style = MaterialTheme.typography.bodySmall, color = AppDanger)
        }
        if (infoMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = infoMessage.orEmpty(), style = MaterialTheme.typography.bodySmall, color = AppSuccess)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { submit() },
            enabled = !isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppAccent, contentColor = AppOnAccent)
        ) {
            if (isBusy) {
                CircularProgressIndicator(color = AppOnAccent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(text = if (isCreating) "Create account" else "Log in", fontWeight = FontWeight.SemiBold)
            }
        }

        if (!isCreating) {
            TextButton(
                onClick = {
                    if (email.isBlank()) {
                        errorMessage = "Enter your email above first, then tap Forgot password."
                    } else {
                        launchAuth {
                            AuthManager.sendPasswordReset(email)
                            infoMessage = "If an account exists for that email, a reset link is on its way."
                        }
                    }
                },
                enabled = !isBusy,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(text = "Forgot password?", color = AppTextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = {
                isCreating = !isCreating
                errorMessage = null
                infoMessage = null
            },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (isCreating) "Already have an account? Log in" else "New here? Create an account",
                color = AppAccent,
                textAlign = TextAlign.Center
            )
        }
    }
}
