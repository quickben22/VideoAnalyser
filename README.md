# FitPulse AI

An Android camera-based workout analyzer. CameraX sends frames directly to ML Kit pose detection in memory; the app does not bind video capture or write frames or videos to storage. It counts punches, high knees, side kicks, squats, push-ups and jumping jacks using heuristic motion detection, and reports which postures (standing, floor, bent over) the tracked person was in. These estimates are not medical or safety advice.

## Build

Open the folder in Android Studio with an Android 35 SDK and JDK 17 or newer, or run `gradlew.bat assembleDebug testDebugUnitTest`. Install on an Android device with a camera and grant camera permission. The bundled pose model works offline. Stand far enough away for both shoulders and ankles to be visible; the timer starts automatically. Return to a resting position after each movement to register a rep. The camera button switches between front and rear cameras.

## Firebase factual recap setup

Local workout analysis works without Firebase. To enable factual AI recaps:

1. In the existing Firebase project, select or register an Android app with package name `com.example.fitpulseai`. Download its `google-services.json` into `app\google-services.json` and rebuild. This file is ignored by Git. Do not use a service-account key.
2. In Firebase Console, open **AI Services > AI Logic**, complete setup for the Gemini Developer API, enable the required services, and verify that `gemini-3.8-flash` is available. Review project quota and billing requirements before making any billing changes.
3. For a debug build, run the app on a test device or emulator and find the generated App Check debug token in local Logcat. Register it under **App Check > Apps > Manage debug tokens** in the same project. Keep the token and debug builds private; never commit or share the token. If compromised, revoke it in the console.
4. For release builds, configure Play Integrity App Check, register the release signing SHA-256 certificate, and follow Firebase's distribution-specific setup. Keep AI Logic App Check enforcement enabled and verify attestation before release. Debug builds use the debug provider; release builds use Play Integrity.

Only compact JSON containing duration, detected counts, side totals, first/last recorded detection times and the observed posture phases is sent to Firebase AI Logic. Images and video stay on-device and are never uploaded or recorded. Gemini summarizes the local detector's estimates, not the original video; it cannot report exercises that the detector missed. Generated text can be inaccurate: use the displayed local counts and times as authoritative. No coaching, advice, account login, cloud history, or Firestore is used.

Telemetry and factual recaps are stored in app-private DataStore with Android backup disabled. The most recent saved summary is shown on reopening the app. Local results are immediate offline; pending AI requests wait for connectivity and transient failures retry with backoff. Configuration, access, invalid-response and persistent errors appear on the summary screen with a retry option. Without Firebase configuration the app remains local-only. Clear app data to remove stored sessions.

## Trainer report

After finishing a workout, tap **Generate report** on the summary screen. The app turns the recorded
session data into a short Croatian trainer-style report: duration, goal, the main posture phases,
exercise counts with left/right splits, muscle groups, an asymmetry note and a conclusion. The report
is saved in app-private DataStore and appears again when the app is reopened. Generation is fully
deterministic and offline - no AI model, network connection or video is involved, and the counts are
still heuristic estimates rather than validated measurements.

## Video regression test

`app/src/androidTest/.../VideoSessionTest.kt` decodes a workout video with `MediaCodec`, runs the bundled ML Kit pose model over sampled frames, and pushes the results through the same `SessionRecorder` the camera uses. It asserts that frames decode, that a pose is found in at least half of them, and that a session with a duration is produced, then writes a text summary, a trainer report and the raw telemetry payload.

```bash
adb push workout.mp4 /data/local/tmp/workout.mp4
adb shell run-as com.example.fitpulseai mkdir -p files
adb shell "run-as com.example.fitpulseai cp /data/local/tmp/workout.mp4 files/workout.mp4"
adb shell am instrument -w -e intervalMs 80 com.example.fitpulseai.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as com.example.fitpulseai cat files/session-summary.txt
```

`intervalMs` sets the analysis interval (80 is 12.5 fps), `maxSeconds` limits a run to the first N seconds, and `video` overrides the file name. Results land in app-private `files/session-summary.txt`, `files/trainer-report.txt` and `files/session-telemetry.json`. The video stays on-device.

### How a group video is analysed

The bundled ML Kit pose model returns exactly one skeleton per frame. In footage with several people it may follow a different person from one moment to the next, and occasionally reports a collapsed skeleton. `PoseGate` rejects skeletons that are too small for the frame, differ in scale from the locked subject, or jump across the frame, then re-acquires after a short run of rejections so a long session is never abandoned. `SessionRecorder` resets its smoothing and detector whenever the subject changes, so two people's limbs are never mixed into one movement.

`ExerciseDetector` first classifies the body posture (standing, floor, bent over) and only runs the detectors that make sense there. A punch needs a folded fist at the shoulder followed by a straight arm at shoulder height while the other arm stays in — an arm swing with both arms out is not a punch. A side kick needs the foot lifted to hip height and moved out to the side, so a lunge's raised heel is not a kick. A jumping jack needs the feet to come back together, so a wide overhead squat is not one. The summary lists per-exercise totals plus a coarse "what the camera saw" timeline, so stretches without counted reps are still described.

## Validation notes

Motion thresholds are starting points, not a validated fitness classifier. Real-device checks are required for camera alignment, movement accuracy, sustained frame rate, memory behavior, and network/offline Firebase integration. The app does not guarantee 30 FPS on every device.
