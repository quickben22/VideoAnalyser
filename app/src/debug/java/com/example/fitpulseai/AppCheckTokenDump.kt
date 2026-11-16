package com.example.fitpulseai

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.appcheck.FirebaseAppCheck
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Debug-only helper: App Check prints its debug token to Logcat, which some OEM builds
 * (for example HONOR/MagicOS) do not expose over adb. Persist it app-privately instead:
 *
 *     adb shell run-as com.example.fitpulseai cat files/appcheck_debug_token.txt
 *
 * Fetching a token requires network, so the locally stored debug secret is used as a
 * fallback. That secret is what gets allow-listed in the Firebase console.
 */
internal fun dumpAppCheckToken(context: Context) {
    val appContext = context.applicationContext
    Thread {
        val text = try {
            Tasks.await(FirebaseAppCheck.getInstance().getAppCheckToken(false), 15, TimeUnit.SECONDS).token
        } catch (e: Exception) {
            debugSecret(appContext) ?: "failed: $e"
        }
        runCatching { File(appContext.filesDir, "appcheck_debug_token.txt").writeText(text) }
            .onFailure { Log.w("AppCheckDebug", "Could not store the App Check debug token", it) }
        Log.i("AppCheckDebug", "App Check debug token: $text")
    }.start()
}

private fun debugSecret(context: Context): String? {
    val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
    return prefsDir.listFiles()
        ?.filter { it.name.startsWith("com.google.firebase.appcheck.debug.store") }
        ?.firstNotNullOfOrNull { file ->
            runCatching {
                Regex("""name="com\.google\.firebase\.appcheck\.debug\.DEBUG_SECRET">([^<]+)<""")
                    .find(file.readText())?.groupValues?.get(1)
            }.getOrNull()
        }
}
