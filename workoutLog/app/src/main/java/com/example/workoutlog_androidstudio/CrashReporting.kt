package com.example.workoutlog_androidstudio

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Thin wrapper around Firebase Crashlytics. Crashes are reported on their
 * own (the SDK catches them); this adds:
 *  - which account a report came from (the Firebase uid - an opaque id, no
 *    name or email), so one tester's reports can be told apart, and
 *  - "non-fatal" reports for errors the app recovers from but you'd still
 *    want to know about, like a workout that failed to save.
 * Never lets a reporting problem take the app down.
 */
object CrashReporting {
    fun setUser(uid: String?) {
        try {
            FirebaseCrashlytics.getInstance().setUserId(uid ?: "")
        } catch (e: Exception) {
        }
    }

    fun record(error: Throwable) {
        try {
            FirebaseCrashlytics.getInstance().recordException(error)
        } catch (e: Exception) {
        }
    }
}
