package com.example.workoutlog_androidstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.workoutlog_androidstudio.api.NetworkClient
import kotlinx.coroutines.launch

/** The bottom of the Profile screen: log out, and permanently delete the
 *  account (which Google Play requires an in-app way to do). */
@Composable
fun AccountSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showDelete by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(AppSurface, RoundedCornerShape(16.dp))
                .clickable { AuthManager.signOut() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "LOG OUT", style = MaterialTheme.typography.labelMedium, color = AppTextPrimary)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(AppSurface, RoundedCornerShape(16.dp))
                .clickable {
                    password = ""
                    deleteError = null
                    showDelete = true
                }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "DELETE ACCOUNT", style = MaterialTheme.typography.labelMedium, color = AppDanger)
        }
    }

    AppBottomSheet(visible = showDelete, onDismissRequest = { if (!isDeleting) showDelete = false }) {
        Text(text = "Delete your account?", style = MaterialTheme.typography.titleLarge, color = AppTextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "This permanently deletes your account and every workout, template and exercise you've logged. This can't be undone.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary
        )

        // Deleting an account is a sensitive action, so it needs a fresh
        // sign-in: a password for email accounts, the Google sheet otherwise.
        if (AuthManager.usesPassword) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                placeholder = { Text(text = "Confirm your password", color = AppTextMuted) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                colors = appTextFieldColors()
            )
        } else {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "You'll be asked to confirm with Google first.",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextMuted
            )
        }

        if (deleteError != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = deleteError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = AppDanger)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                scope.launch {
                    isDeleting = true
                    deleteError = null
                    try {
                        if (AuthManager.usesPassword) {
                            AuthManager.reauthenticateWithPassword(password)
                        } else {
                            AuthManager.reauthenticateWithGoogle(context)
                        }
                        // Server data first, then the login itself: if this
                        // fails halfway, there's never a login left pointing
                        // at nothing, or data left with no way to delete it.
                        NetworkClient.workoutApi.deleteAccount()
                        AuthManager.deleteFirebaseAccount()
                        // Deleting the Firebase user signs out, which swaps
                        // the whole app for the login screen.
                    } catch (e: Exception) {
                        deleteError = friendlyAuthError(e)
                    } finally {
                        isDeleting = false
                    }
                }
            },
            enabled = !isDeleting && (!AuthManager.usesPassword || password.isNotEmpty()),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppDanger, contentColor = Color.White)
        ) {
            if (isDeleting) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(text = "Delete everything", fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = { showDelete = false },
            enabled = !isDeleting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Cancel", color = AppTextSecondary)
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}
