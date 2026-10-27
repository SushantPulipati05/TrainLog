package com.example.workoutlog_androidstudio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.workoutlog_androidstudio.api.FeedbackRequest
import com.example.workoutlog_androidstudio.api.NetworkClient
import com.example.workoutlog_androidstudio.api.UpdateProfileRequest
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The Profile screen's lists under Core Biometrics (Training, App, Account,
 * About) and the Account details page they lead to.
 */

/** Website pages also linked from the sign-in screen. */
const val PRIVACY_URL = "https://trainlog.sushnt.dev/privacy"
const val TERMS_URL = "https://trainlog.sushnt.dev/terms"

@Composable
fun ProfileSettingsSections(
    onOpenTrainingPlan: () -> Unit,
    onOpenTargets: () -> Unit,
    onOpenAccountDetails: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { WorkoutNamer.init(context) }
    var showDelete by remember { mutableStateOf(false) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showFeedback by remember { mutableStateOf(false) }

    // ---- Training ----
    SettingsLabel("TRAINING")
    SettingsGroup {
        val plan = TrainingPlanStore.plan
        SettingsRow(
            glyph = SettingsGlyphKind.CALENDAR,
            title = "Training plan",
            subtitle = plan?.let { "${it.split.label} · ${formatGymTime(context, it.hour, it.minute)}" } ?: "Not set up yet",
            onClick = onOpenTrainingPlan
        )
        SettingsDivider()
        val days = AppDataCache.profile?.weeklyWorkoutTarget
        SettingsRow(
            glyph = SettingsGlyphKind.TARGET,
            title = "Weekly target",
            subtitle = days?.let { "$it ${if (it == 1) "day" else "days"} a week" },
            onClick = onOpenTargets
        )
    }

    // ---- App ----
    SettingsLabel("APP")
    SettingsGroup {
        SettingsRow(
            glyph = SettingsGlyphKind.BELL,
            title = "Notifications",
            subtitle = "Gym reminders, workout in progress",
            onClick = { openNotificationSettings(context) }
        )
        SettingsDivider()
        SettingsRow(
            glyph = SettingsGlyphKind.SPARK,
            title = "Fun workout names",
            subtitle = if (WorkoutNamer.enabled) "Suggest a name when a workout ends" else "Workouts keep the name you give them",
            onClick = { WorkoutNamer.setEnabled(context, !WorkoutNamer.enabled) },
            trailing = {
                Switch(
                    checked = WorkoutNamer.enabled,
                    onCheckedChange = { WorkoutNamer.setEnabled(context, it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AppOnAccent,
                        checkedTrackColor = AppAccent,
                        uncheckedThumbColor = AppTextMuted,
                        uncheckedTrackColor = AppSurfaceVariant,
                        uncheckedBorderColor = AppBorder
                    )
                )
            }
        )
    }

    // ---- Account ----
    SettingsLabel("ACCOUNT")
    SettingsGroup {
        SettingsRow(
            glyph = SettingsGlyphKind.PERSON,
            title = "Account details",
            subtitle = "Email, sign-in, sex",
            onClick = onOpenAccountDetails
        )
        // Only accounts with a password have one to change.
        if (AuthManager.usesPassword) {
            SettingsDivider()
            SettingsRow(glyph = SettingsGlyphKind.LOCK, title = "Change password", onClick = { showChangePassword = true })
        }
        SettingsDivider()
        SettingsRow(glyph = SettingsGlyphKind.LOG_OUT, title = "Log out", onClick = { AuthManager.signOut() }, showChevron = false)
        SettingsDivider()
        SettingsRow(
            glyph = SettingsGlyphKind.BIN,
            title = "Delete account",
            subtitle = "Removes every workout you've logged",
            onClick = { showDelete = true },
            danger = true,
            showChevron = false
        )
    }

    // ---- About ----
    SettingsLabel("ABOUT")
    SettingsGroup {
        SettingsRow(glyph = SettingsGlyphKind.CHAT, title = "Send feedback", onClick = { showFeedback = true })
        SettingsDivider()
        SettingsRow(glyph = SettingsGlyphKind.DOC, title = "Privacy policy", onClick = { openUrl(context, PRIVACY_URL) })
        SettingsDivider()
        SettingsRow(glyph = SettingsGlyphKind.DOC, title = "Terms", onClick = { openUrl(context, TERMS_URL) })
    }

    Spacer(modifier = Modifier.height(18.dp))
    Text(
        text = "TrainLog ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
            (if (BuildConfig.DEBUG) installedAtLabel(context)?.let { " · installed $it" }.orEmpty() else ""),
        style = MaterialTheme.typography.labelSmall,
        color = AppTextMuted,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )

    DeleteAccountSheet(visible = showDelete, onDismiss = { showDelete = false })
    ChangePasswordSheet(visible = showChangePassword, onDismiss = { showChangePassword = false })
    FeedbackSheet(visible = showFeedback, onDismiss = { showFeedback = false })
}

/**
 * Profile -> Send feedback: Bug / Idea / Other and a message, sent to the
 * server (saved there and forwarded to the owner's phone). The app version
 * and phone model go with it unless the box is unticked.
 */
@Composable
private fun FeedbackSheet(visible: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var kind by remember(visible) { mutableStateOf<String?>(null) }
    var message by remember(visible) { mutableStateOf("") }
    var includeInfo by remember(visible) { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    var sent by remember(visible) { mutableStateOf(false) }
    var error by remember(visible) { mutableStateOf<String?>(null) }
    val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val device = remember {
        val maker = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = android.os.Build.MODEL
        val name = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
        "$name · Android ${android.os.Build.VERSION.RELEASE}"
    }

    AppBottomSheet(visible = visible, onDismissRequest = { if (!isSending) onDismiss() }) {
        if (sent) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier.size(58.dp).background(AppSurfaceVariant, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) { SettingsGlyph(SettingsGlyphKind.CHAT, Modifier.size(28.dp)) }
                Spacer(modifier = Modifier.height(14.dp))
                Text("Thanks, got it", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Your feedback was sent. If you reported a bug, I'll look into it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                ) { Text("Done", fontWeight = FontWeight.SemiBold) }
                Spacer(modifier = Modifier.height(4.dp))
            }
            return@AppBottomSheet
        }

        Text("Send feedback", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppTextPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Found a bug or want something added? It goes straight to the person building TrainLog.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("BUG" to "Bug", "IDEA" to "Idea", "OTHER" to "Other").forEach { (value, label) ->
                val on = kind == value
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) AppOnAccent else AppTextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) AppAccent else AppSurfaceVariant)
                        .clickable { kind = value }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        OutlinedTextField(
            value = message,
            onValueChange = { message = it.take(2000) },
            placeholder = { Text("What happened, or what would you like?", color = AppTextMuted) },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
            colors = appTextFieldColors()
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { includeInfo = !includeInfo }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(if (includeInfo) AppAccent else Color.Transparent, RoundedCornerShape(5.dp))
                    .then(if (includeInfo) Modifier else Modifier.border(1.5.dp, AppBorder, RoundedCornerShape(5.dp))),
                contentAlignment = Alignment.Center
            ) {
                if (includeInfo) SettingsGlyph(SettingsGlyphKind.TICK, Modifier.size(12.dp), color = AppOnAccent)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text("Include app version and phone model", style = MaterialTheme.typography.bodySmall, color = AppTextSecondary)
                Text("TrainLog $appVersion · $device", style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
            }
        }
        error?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = AppDanger)
        }
        Spacer(modifier = Modifier.height(16.dp))
        val ready = kind != null && message.isNotBlank()
        Button(
            onClick = {
                val chosen = kind ?: return@Button
                scope.launch {
                    isSending = true
                    error = null
                    try {
                        NetworkClient.workoutApi.sendFeedback(
                            FeedbackRequest(
                                kind = chosen,
                                message = message.trim(),
                                appVersion = if (includeInfo) appVersion else null,
                                device = if (includeInfo) device else null
                            )
                        )
                        sent = true
                    } catch (e: Exception) {
                        // The message stays in the box to try again.
                        error = serverRefusal(e) ?: "Couldn't send. Check your connection and try again."
                    } finally {
                        isSending = false
                    }
                }
            },
            enabled = ready && !isSending,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color.Black,
                disabledContainerColor = Color.White.copy(alpha = 0.35f),
                disabledContentColor = Color.Black
            )
        ) {
            if (isSending) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Send", fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

/** Change password = a reset link to the account's own email (Firebase's
 *  own page sets the new one), so the app never handles the new password. */
@Composable
private fun ChangePasswordSheet(visible: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var isSending by remember { mutableStateOf(false) }
    var sent by remember(visible) { mutableStateOf(false) }
    var error by remember(visible) { mutableStateOf<String?>(null) }
    val email = AuthManager.currentEmail.orEmpty()
    AppBottomSheet(visible = visible, onDismissRequest = { if (!isSending) onDismiss() }) {
        Text("Change password", style = MaterialTheme.typography.titleLarge, color = AppTextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (sent) "Sent. Open the link in the email to $email and choose a new password. It can take a minute to arrive, so check spam too."
            else "We'll email a link to $email. Open it to choose a new password.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary
        )
        error?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = AppDanger)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = {
                if (sent) { onDismiss(); return@Button }
                scope.launch {
                    isSending = true
                    error = null
                    try {
                        AuthManager.sendPasswordReset(email)
                        sent = true
                    } catch (e: Exception) {
                        error = friendlyAuthError(e)
                    } finally {
                        isSending = false
                    }
                }
            },
            enabled = !isSending && email.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) {
            if (isSending) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(if (sent) "Done" else "Send link", fontWeight = FontWeight.SemiBold)
        }
        if (!sent) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onDismiss, enabled = !isSending, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", color = AppTextSecondary)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

// ---------------------------------------------------------------------------
// Account details page
// ---------------------------------------------------------------------------

@Composable
fun AccountDetailsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val user = FirebaseAuth.getInstance().currentUser
    val profile = AppDataCache.profile
    val usesGoogle = user?.providerData?.any { it.providerId == "google.com" } == true
    val signInMethod = when {
        usesGoogle && AuthManager.usesPassword -> "Google and email"
        usesGoogle -> "Google"
        else -> "Email and password"
    }
    var showRename by remember { mutableStateOf(false) }
    var showSex by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackChevronRow(onBack = onBack, label = "Profile")
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 40.dp)
        ) {
            Text(
                text = "Account details",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary
            )

            SettingsLabel("SIGN-IN")
            SettingsGroup {
                DetailRow(label = "EMAIL", value = user?.email ?: "–")
                SettingsDivider()
                DetailRow(label = "SIGNED IN WITH", value = signInMethod)
                SettingsDivider()
                DetailRow(
                    label = "EMAIL STATUS",
                    value = if (user?.isEmailVerified == true) "Verified" else "Not verified",
                    leading = if (user?.isEmailVerified == true) {
                        { SettingsGlyph(SettingsGlyphKind.TICK, Modifier.size(14.dp)) }
                    } else null
                )
            }

            SettingsLabel("YOU")
            SettingsGroup {
                DetailRow(label = "NAME", value = profile?.name ?: "–", onClick = { showRename = true })
                SettingsDivider()
                DetailRow(
                    label = "SEX",
                    value = when (profile?.sex?.uppercase()) {
                        "MALE" -> "Male"
                        "FEMALE" -> "Female"
                        "UNSPECIFIED" -> "Prefer not to say"
                        else -> "Not set"
                    },
                    onClick = { showSex = true }
                )
            }
            DetailNote("Sex sets your body map figure.")

            SettingsLabel("ACCOUNT")
            SettingsGroup {
                DetailRow(label = "MEMBER SINCE", value = profile?.memberSince?.let { formatLongDate(it) } ?: "–")
            }
            if (usesGoogle && !AuthManager.usesPassword) {
                DetailNote("Signed in with Google, so your password is managed in your Google account.")
            }
        }
    }

    RenameSheet(visible = showRename, current = profile?.name.orEmpty(), onDismiss = { showRename = false })
    AppBottomSheet(visible = showSex, onDismissRequest = { showSex = false }) {
        // The same picker the Profile used to show.
        SexSettingRow()
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = { showSex = false }, modifier = Modifier.fillMaxWidth()) {
            Text("Done", color = AppTextSecondary)
        }
    }
}

@Composable
private fun RenameSheet(visible: Boolean, current: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember(visible) { mutableStateOf(current) }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember(visible) { mutableStateOf<String?>(null) }
    AppBottomSheet(visible = visible, onDismissRequest = { if (!isSaving) onDismiss() }) {
        Text("Your name", style = MaterialTheme.typography.titleLarge, color = AppTextPrimary)
        Spacer(modifier = Modifier.height(14.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(100) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = appTextFieldColors()
        )
        error?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = AppDanger)
        }
        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = {
                val clean = name.trim()
                if (clean.isEmpty() || clean == current) { onDismiss(); return@Button }
                scope.launch {
                    isSaving = true
                    error = null
                    try {
                        val updated = NetworkClient.workoutApi.updateProfile(UpdateProfileRequest(name = clean))
                        AppDataCache.replaceProfile(updated)
                        onDismiss()
                    } catch (e: Exception) {
                        error = serverRefusal(e) ?: "Couldn't save. Check your connection and try again."
                    } finally {
                        isSaving = false
                    }
                }
            },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) {
            if (isSaving) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Save", fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
            Spacer(modifier = Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) {
                    leading()
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(value, style = MaterialTheme.typography.bodyLarge, color = AppTextPrimary)
            }
        }
        if (onClick != null) SettingsGlyph(SettingsGlyphKind.CHEVRON, Modifier.size(16.dp), color = AppTextMuted)
    }
}

@Composable
private fun DetailNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = AppTextMuted,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp)
    )
}

/** "24 Sep 2026" from an ISO date. */
private fun formatLongDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) }.getOrDefault(iso)

// ---------------------------------------------------------------------------
// Rows and groups
// ---------------------------------------------------------------------------

@Composable
private fun SettingsLabel(text: String) {
    Spacer(modifier = Modifier.height(22.dp))
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = AppTextMuted)
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AppSurface)
    ) { content() }
}

@Composable
private fun SettingsDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppBorder))
}

@Composable
private fun SettingsRow(
    glyph: SettingsGlyphKind,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    danger: Boolean = false,
    showChevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (subtitle != null) 12.dp else 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsGlyph(glyph, Modifier.size(20.dp), color = if (danger) AppDanger else AppAccent)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (danger) AppDanger else AppTextPrimary)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppTextMuted)
            }
        }
        when {
            trailing != null -> trailing()
            showChevron -> SettingsGlyph(SettingsGlyphKind.CHEVRON, Modifier.size(16.dp), color = AppTextMuted)
        }
    }
}

// ---------------------------------------------------------------------------
// Drawn icons (app theme, no icon library)
// ---------------------------------------------------------------------------

enum class SettingsGlyphKind { CALENDAR, TARGET, BELL, SPARK, PERSON, LOCK, LOG_OUT, BIN, CHAT, DOC, CHEVRON, TICK }

@Composable
fun SettingsGlyph(kind: SettingsGlyphKind, modifier: Modifier = Modifier, color: Color = AppAccent) {
    Canvas(modifier = modifier) {
        val u = size.width / 24f
        val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(vararg points: Pair<Float, Float>): Path = Path().apply {
            points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) }
        }
        fun line(vararg points: Pair<Float, Float>) = drawPath(p(*points), color, style = stroke)
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
            drawRoundRect(color, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u), style = stroke)
        fun circle(cx: Float, cy: Float, r: Float) = drawCircle(color, r * u, Offset(cx * u, cy * u), style = stroke)
        when (kind) {
            SettingsGlyphKind.CALENDAR -> { rect(4f, 5f, 16f, 15f, 3f); line(4f to 10f, 20f to 10f); line(9f to 3f, 9f to 7f); line(15f to 3f, 15f to 7f) }
            SettingsGlyphKind.TARGET -> { circle(12f, 12f, 8f); circle(12f, 12f, 3.5f) }
            SettingsGlyphKind.BELL -> {
                drawPath(Path().apply {
                    moveTo(6f * u, 16f * u); lineTo(6f * u, 11f * u)
                    cubicTo(6f * u, 7.5f * u, 8.7f * u, 5f * u, 12f * u, 5f * u)
                    cubicTo(15.3f * u, 5f * u, 18f * u, 7.5f * u, 18f * u, 11f * u)
                    lineTo(18f * u, 16f * u); lineTo(19.5f * u, 18f * u); lineTo(4.5f * u, 18f * u); close()
                }, color, style = stroke)
                line(10.5f to 21f, 13.5f to 21f)
            }
            SettingsGlyphKind.SPARK -> { line(12f to 4f, 12f to 8f); line(12f to 16f, 12f to 20f); line(4f to 12f, 8f to 12f); line(16f to 12f, 20f to 12f); circle(12f, 12f, 1.2f) }
            SettingsGlyphKind.PERSON -> {
                circle(12f, 8f, 3.5f)
                drawPath(Path().apply {
                    moveTo(5f * u, 20f * u)
                    cubicTo(6f * u, 16f * u, 9f * u, 14f * u, 12f * u, 14f * u)
                    cubicTo(15f * u, 14f * u, 18f * u, 16f * u, 19f * u, 20f * u)
                }, color, style = stroke)
            }
            SettingsGlyphKind.LOCK -> {
                rect(5f, 10f, 14f, 10f, 2.5f)
                drawPath(Path().apply {
                    moveTo(8f * u, 10f * u); lineTo(8f * u, 7.5f * u)
                    cubicTo(8f * u, 5f * u, 10f * u, 3.5f * u, 12f * u, 3.5f * u)
                    cubicTo(14f * u, 3.5f * u, 16f * u, 5f * u, 16f * u, 7.5f * u); lineTo(16f * u, 10f * u)
                }, color, style = stroke)
            }
            SettingsGlyphKind.LOG_OUT -> { line(14f to 5f, 19f to 5f, 19f to 19f, 14f to 19f); line(10f to 8f, 6f to 12f, 10f to 16f); line(6f to 12f, 15f to 12f) }
            SettingsGlyphKind.BIN -> { line(5f to 7f, 19f to 7f); line(10f to 7f, 10f to 4f, 14f to 4f, 14f to 7f); line(7f to 7f, 8f to 20f, 16f to 20f, 17f to 7f) }
            SettingsGlyphKind.CHAT -> { line(4f to 5f, 20f to 5f, 20f to 16f, 9f to 16f, 4f to 20f, 4f to 5f) }
            SettingsGlyphKind.DOC -> { line(7f to 3f, 14f to 3f, 18f to 7f, 18f to 21f, 7f to 21f, 7f to 3f); line(14f to 3f, 14f to 7f, 18f to 7f); line(10f to 12f, 15f to 12f); line(10f to 16f, 15f to 16f) }
            SettingsGlyphKind.CHEVRON -> line(9f to 6f, 15f to 12f, 9f to 18f)
            SettingsGlyphKind.TICK -> line(5f to 12.5f, 9.5f to 17f, 19f to 7f)
        }
    }
}

// ---------------------------------------------------------------------------
// Leaving the app
// ---------------------------------------------------------------------------

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        // Older phones: the app's own settings page instead.
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/**
 * When this copy of the app was last fully installed on the phone ("27 Oct
 * 14:32"). Shown on debug builds only, to tell at a glance whether the phone
 * has the latest code: Android Studio's Apply Changes / Live Edit patch the
 * running app without reinstalling, so they don't move this time - and once
 * Android closes the app, it starts again from this install.
 */
private fun installedAtLabel(context: Context): String? = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    java.text.SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH).format(java.util.Date(info.lastUpdateTime))
}.getOrNull()

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
