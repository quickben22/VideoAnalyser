# FitPulse AI

An Android camera-based workout analyzer. CameraX sends frames directly to ML Kit pose detection in memory; the app does not bind video capture or write frames or videos to storage. It counts punches, high knees and side kicks using heuristic motion detection. These estimates are not medical or safety advice.

## Build

Open the folder in Android Studio with an Android 35 SDK and JDK 17 or newer, or run `gradlew.bat assembleDebug testDebugUnitTest`. Install on an Android device with a camera and grant camera permission. The bundled pose model works offline. Stand far enough away for both shoulders and ankles to be visible; the timer starts automatically. Return to a resting position after each movement to register a rep. The camera button switches between front and rear cameras.

## Firebase factual recap setup

Local workout analysis works without Firebase. To enable factual AI recaps:

1. In the existing Firebase project, select or register an Android app with package name `com.example.fitpulseai`. Download its `google-services.json` into `app\google-services.json` and rebuild. This file is ignored by Git. Do not use a service-account key.
2. In Firebase Console, open **AI Services > AI Logic**, complete setup for the Gemini Developer API, enable the required services, and verify that `gemini-3.8-flash` is available. Review project quota and billing requirements before making any billing changes.
3. For a debug build, run the app on a test device or emulator and find the generated App Check debug token in local Logcat. Register it under **App Check > Apps > Manage debug tokens** in the same project. Keep the token and debug builds private; never commit or share the token. If compromised, revoke it in the console.
4. For release builds, configure Play Integrity App Check, register the release signing SHA-256 certificate, and follow Firebase's distribution-specific setup. Keep AI Logic App Check enforcement enabled and verify attestation before release. Debug builds use the debug provider; release builds use Play Integrity.

Only compact JSON containing duration, detected counts, side totals and first/last recorded detection times is sent to Firebase AI Logic. Images and video stay on-device and are never uploaded or recorded. Gemini summarizes the local detector's estimates, not the original video; it cannot report exercises that the detector missed. Generated text can be inaccurate: use the displayed local counts and times as authoritative. No coaching, advice, account login, cloud history, or Firestore is used.

Telemetry and factual recaps are stored in app-private DataStore with Android backup disabled. The most recent saved summary is shown on reopening the app. Local results are immediate offline; pending AI requests wait for connectivity and transient failures retry with backoff. Configuration, access, invalid-response and persistent errors appear on the summary screen with a retry option. Without Firebase configuration the app remains local-only. Clear app data to remove stored sessions.

## Validation notes

Motion thresholds are starting points, not a validated fitness classifier. Real-device checks are required for camera alignment, movement accuracy, sustained frame rate, memory behavior, and network/offline Firebase integration. The app does not guarantee 30 FPS on every device.
