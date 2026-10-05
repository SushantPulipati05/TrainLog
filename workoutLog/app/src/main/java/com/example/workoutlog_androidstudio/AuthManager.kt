package com.example.workoutlog_androidstudio

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Interceptor
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Everything to do with who's signed in. Login itself is Firebase
 * Authentication - this app never sees or stores a password of its own - and
 * the backend verifies the Firebase ID token this hands out on every request
 * (see [AuthInterceptor]).
 *
 * Two ways in: "Continue with Google" through Android's Credential Manager
 * (one tap, no password), or email + password.
 */
object AuthManager {
    private val firebaseAuth: FirebaseAuth get() = FirebaseAuth.getInstance()

    /** Observable by Compose: flips the moment someone signs in or out - including when
     *  a login is revoked or deleted elsewhere. */
    var isSignedIn by mutableStateOf(firebaseAuth.currentUser != null)
        private set

    init {
        firebaseAuth.addAuthStateListener { isSignedIn = it.currentUser != null }
    }

    /** True when this account signs in with an email + password (as opposed to Google only). */
    val usesPassword: Boolean
        get() = firebaseAuth.currentUser?.providerData?.any { it.providerId == EmailAuthProvider.PROVIDER_ID } == true

    // ---- signing in ----

    suspend fun signInWithGoogle(context: Context) {
        firebaseAuth.signInWithCredential(requestGoogleCredential(context)).await()
    }

    suspend fun signInWithEmail(email: String, password: String) {
        firebaseAuth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun createAccount(email: String, password: String) {
        val result = firebaseAuth.createUserWithEmailAndPassword(email.trim(), password).await()
        // Best effort - a failure to send the verification email shouldn't undo the sign-up.
        try {
            result.user?.sendEmailVerification()?.await()
        } catch (e: Exception) {
        }
    }

    suspend fun sendPasswordReset(email: String) {
        firebaseAuth.sendPasswordResetEmail(email.trim()).await()
    }

    /** Offers to save the password in the user's password manager, so next
     *  time Android can fill it in. Best effort - never blocks sign-in. */
    suspend fun offerToSavePassword(context: Context, email: String, password: String) {
        try {
            CredentialManager.create(context).createCredential(context, CreatePasswordRequest(email.trim(), password))
        } catch (e: Exception) {
        }
    }

    fun signOut() {
        firebaseAuth.signOut()
    }

    // ---- deleting the account (needs a fresh sign-in first) ----

    suspend fun reauthenticateWithGoogle(context: Context) {
        val user = firebaseAuth.currentUser ?: error("Not signed in")
        user.reauthenticate(requestGoogleCredential(context)).await()
    }

    suspend fun reauthenticateWithPassword(password: String) {
        val user = firebaseAuth.currentUser ?: error("Not signed in")
        val email = user.email ?: error("This account has no email address")
        user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
    }

    suspend fun deleteFirebaseAccount() {
        firebaseAuth.currentUser?.delete()?.await()
    }

    // ---- the token the backend checks ----

    /** A current Firebase ID token for the signed-in user (Firebase refreshes
     *  it by itself when it's close to expiring), or null if signed out.
     *  Blocking - only call from a background thread, like OkHttp's. */
    fun idTokenBlocking(forceRefresh: Boolean = false): String? {
        val user = firebaseAuth.currentUser ?: return null
        return try {
            Tasks.await(user.getIdToken(forceRefresh)).token
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun requestGoogleCredential(context: Context): AuthCredential {
        // default_web_client_id is generated from google-services.json by the
        // google-services Gradle plugin - it's the project's *web* OAuth client.
        val option = GetSignInWithGoogleOption.Builder(context.getString(R.string.default_web_client_id)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(context).getCredential(context, request).credential

        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            return GoogleAuthProvider.getCredential(idToken, null)
        }
        throw IllegalStateException("Unexpected credential type from Google sign-in")
    }
}

/** Attaches the signed-in user's Firebase ID token to every request. If the
 *  server still says 401, the token is force-refreshed and the request tried
 *  once more; if that fails too, the login is no longer valid (account
 *  deleted or disabled), so the user is signed out. */
class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = AuthManager.idTokenBlocking() ?: return chain.proceed(chain.request())

        val response = chain.proceed(chain.request().withBearer(token))
        if (response.code != 401) return response

        response.close()
        val fresh = AuthManager.idTokenBlocking(forceRefresh = true) ?: return chain.proceed(chain.request())
        val retried = chain.proceed(chain.request().withBearer(fresh))
        if (retried.code == 401) AuthManager.signOut()
        return retried
    }

    private fun okhttp3.Request.withBearer(token: String): okhttp3.Request =
        newBuilder().header("Authorization", "Bearer $token").build()
}

/** A message safe to show a person for whatever went wrong signing in. */
fun friendlyAuthError(e: Throwable): String = when (e) {
    is FirebaseAuthWeakPasswordException -> "That password is too weak. Use at least 8 characters."
    is FirebaseAuthUserCollisionException -> "An account with that email already exists. Try logging in instead."
    is FirebaseAuthInvalidCredentialsException ->
        if (e.errorCode == "ERROR_INVALID_EMAIL") "That email address doesn't look right." else "Wrong email or password."
    is FirebaseAuthInvalidUserException -> "Wrong email or password."
    is FirebaseAuthRecentLoginRequiredException -> "For security, please confirm it's you and try again."
    is FirebaseNetworkException -> "No connection. Check your internet and try again."
    is GetCredentialCancellationException -> "Sign-in was cancelled."
    is NoCredentialException -> "No Google account found on this phone. Add one in Settings, or use email instead."
    else -> "Something went wrong. Please try again."
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            continuation.resume(task.result)
        } else {
            continuation.resumeWithException(task.exception ?: RuntimeException("Task failed"))
        }
    }
}
