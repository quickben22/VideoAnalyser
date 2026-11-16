# FitPulse AI — analysis fix: progress & next steps

_Last updated: 2026-11-16 (after adding the deterministic trainer report + HIIT video run)_

## Goal

Make the analysis and text summary of the provided 24-minute multi-person circuit-workout video
correct. The video shows **five people in a gym all doing the same exercise**. The regression test
must produce a text document summarising the session. All processing stays on-device; Firebase/AI
recap is optional. Work test-first: write the test, watch it fail, fix the app, re-run.

## Ground truth about the video (verified by dumping frames)

Video: `Sanja Zuljevic trenira s vama - Kruzni trening za cijelo tijelo_720p.mp4`, pushed to the
emulator as app-internal `files/workout.mp4` (23:41, 1280x720). It is a produced class with camera
cuts and close-ups, not a fixed camera.

| Time | What is actually happening |
| --- | --- |
| 0:00–1:00 | Warm-up / instruction; **boxing-style punches** around 0:30–1:05 |
| 0:45–1:40 | Jumping jacks |
| 3:30–6:00 | Marching / high knees / lateral step-touch with arm swings |
| 7:03–7:45 | Push-ups (everyone on the floor) |
| 8:11–8:40 | Reverse lunges holding dumbbells |
| 9:15 onward | High knees holding dumbbells at the chest |
| 12:00–13:00 | Overhead dumbbell squats / presses, feet wide |
| 15:00–16:30, 18:34–20:24, 21:05–21:27 | Floor mobility: prone holds, child's pose, cat-cow |
| 22:00–23:30 | Cool-down / floor stretching |

**There are no side kicks anywhere in the video.** The old pipeline's 227 punches / 237 side kicks
were false positives from jumping jacks, arm swings, leg lifts and lunge back-legs. Frame dumps
used to establish this are in `reports/frames/` (`f-*`, `g-*`, `j-*`, `k-*`, `p-*`).

## Current state — everything below is DONE

- **49 unit tests pass** (`gradlew.bat :app:testDebugUnitTest`) — 45 original + 4 new
  `TrainerReportTest` tests.
- Both APKs build cleanly (`:app:assembleDebug`, `:app:assembleDebugAndroidTest`) and are installed
  on `Pixel_5` (rebuilt and reinstalled during the trainer-report work).
- Original full run (`workout.mp4`): `OK (1 test)` at 12.5 fps, ~500 s. **105 detections**:
  Punch 58, High knee 20, Jumping jack 11, Push-up 9, Squat 7, **Side kick 0**.
  Decoded 35 979 / analysed 17 990 / posed 17 228 / calibrated 13 839 frames;
  gate accepted 14 984, rejected 3 111, 105 subject switches.
- Second full run after the trainer-report change (`workout.mp4`): `OK (1 test)`, 536 s,
  **104 detections** (Punch 55, High knee 19, Jack 14, Squat 11, Push-up 5). Counts vary a little
  between runs because detection is heuristic. Report: `reports/trainer-report-device.txt`.
- HIIT video full run (`hiit.mp4`, root-file `BESPLATNI HIIT TRENING SA NAŠIM CURAMA…mp4`,
  839 MB): `OK (1 test)`, 1,265 s (~21 min), **106 detections** over 44:26
  (Punch 59, Jack 34, Push-up 9, High knee 2, Squat 2). Decoded 79 986 / analysed 26 662 /
  posed 26 031 / calibrated 25 114; gate accepted 25 123, rejected 1 588, 49 subject switches.
  Artifacts: `reports/hiit-{session-summary.txt,session-telemetry.json,trainer-report.txt}`.
- Artifacts regenerated in `reports/`: `session-summary.txt`, `session-telemetry.json`,
  `probe-full.csv` (3.3 MB), `trainer-report-from-app.txt` (from the saved telemetry fixture),
  `trainer-report-device.txt` (fresh device run), and the `hiit-*` set above.
  `session-window.txt` / `probe-window.csv` are from the last 400–540 s validation window and can
  be ignored.
- `HANDOFF.md` (this file) and `README.md` are up to date.

### How the current code works

| File | Current behaviour |
| --- | --- |
| `vision/PoseGate.kt` | Single-subject lock: torso-size bounds, 35% scale tolerance, cross-frame jump rejection, re-acquire after 15 consecutive rejects. Counters `accepted`, `rejectedByScale/ByJump`, `subjectSwitches`. Uses `lastFrameTime` updated on every call. |
| `vision/PoseFeatures.kt` | Torso-normalised angles/features plus `Posture`. `STANDING` upright ≤40°; `FLOOR` if 50–125° **or** hips level with ankles (|hipAboveAnkle| < 0.7) with upright >45°; `BENT_OVER` ≥140°; else `UNKNOWN`. The extra FLOOR rule catches foreshortened front-on push-ups where ankles are lost. |
| `domain/ExerciseDetector.kt` | Posture-gated `RepMachine` state machines (COOLDOWN → READY → ACTIVE, 350 ms gap). Punch: guard (folded fist near shoulder, not overhead) → straight arm at shoulder height with the other arm not extended/out sideways. Side kick: abduction >110° + extension >150° + foot raised **+ foot level with hip + foot out to the side**. High knee: knee at ~hip height, support ankle steady, **support knee straight (>140°)**. Squat: both knees bend together. Push-up: elbows flex/extend, runs in FLOOR **or when upright >140°**. Jumping jack: feet together/arms down → feet wide (>0.8 torso)/both wrists overhead (>0.2)/straight legs (>140°). |
| `domain/SessionRecorder.kt` | Every frame through the gate; rejected frames skip detection. Resets smoothing immediately on a bad frame but only replaces the detector after **50 consecutive** rejects; keeps `lastAccepted` frame for the overlay. Exposes gate counters and `posture` in `FrameOutcome`. |
| `domain/SessionAggregator.kt` | Whole-block compaction (first 20 + last 20, max 40), complete per-exercise totals in `WorkoutTelemetry.exercises` (survive compaction), `ActivityTracker` posture phases. |
| `domain/Models.kt` | `ExerciseType` = PUNCH_JAB_CROSS, HIGH_KNEE, SIDE_KICK, SQUAT, PUSH_UP, JUMPING_JACK, IDLE; `Side` = LEFT/RIGHT/BOTH; `Posture`, `ExerciseTotal`, `ActivityBlock`. |
| `data/SessionReport.kt` | Text summary: breakdown + detail, "What the camera saw" posture timeline, block list, asymmetries, diagnostics. |
| `data/TrainerReport.kt` | Deterministic, offline Croatian trainer-style report from telemetry only (no AI/network). `WorkoutTelemetry.toTrainerReport()`: duration, goal, compacted posture phases, exercise counts with L/R, muscle groups, asymmetry note, conclusion. `compactPhases()` merges noisy posture blocks (60 s gap, ≥60 s long, max 6) so the report stays short. |
| `data/FactualSummary.kt` | `FactualPayload` with `observed_postures`; totals from `telemetry.exercises`. `LocalSessionStorage` also stores the trainer report per session (`trainer_report_$id`). |
| `MainActivity.kt` | Summary screen via `factualPayload(...)` + shared `label()`. "Generate report" button when no report exists; shows the saved report in a card. Existing Firebase/Gemini recap kept untouched. |
| `ui/WorkoutViewModel.kt` | `WorkoutState.trainerReport`, `generateReport()`, restore on reopen via `LocalSessionStorage.trainerReport(id)`, reset in `newWorkout()`. |
| `data/SessionReport.kt` | `clock()` and `WorkoutTelemetry.exerciseTotals()` changed from `private` to `internal` so the trainer report reuses the same totals (including legacy sessions derived from the timeline). |

Tests: `vision/PoseGateTest.kt` (7), `vision/PoseFeaturesTest.kt` (3),
`domain/ExerciseDetectorTest.kt` (large), `domain/SessionRecorderTest.kt`,
`domain/SessionAggregatorTest.kt`, plus `data/SessionReportTest.kt`, `data/TrainerReportTest.kt`
(includes a report generated from the mp4 telemetry fixture) and `FactualSummaryTest.kt`.
Several detector tests were written red first (lunge≠high knee, inverted push-up, punch guard,
side-kick geometry, jumping jack, rejection policy).

## Trainer report feature (added this session)

**Request:** after finishing a recording, the user taps **"Generate report"** and gets a short
trainer-style report of the session (goal, what happened, muscle groups), like the one written by
hand into `reports/trainer-report-hr.txt`.

**Key decision (confirmed with user):** generation is **deterministic and offline** — no AI model,
no Firebase, no network. It is pure formatting of `WorkoutTelemetry` into a fixed Croatian template.
The existing Gemini recap (`gemini-3.8-flash`, `FactualSummary`) is a separate feature and was left
as-is. The user also confirmed "recording stops" = the user taps **Finish workout**; no in-app
video recording was added.

**Files created/changed:** new `data/TrainerReport.kt`, new `test/.../data/TrainerReportTest.kt`,
`app/src/test/resources/session-telemetry.json` (copy of the mp4 telemetry, used as a fixture),
plus edits to `FactualSummary.kt`, `WorkoutViewModel.kt`, `MainActivity.kt`, `SessionReport.kt`,
`VideoSessionTest.kt` (now writes/validates `files/trainer-report.txt`), `README.md`, `HANDOFF.md`.

**Honest limitation:** the "Što se dogodilo" section lists recorded posture phases with timestamps,
not invented class labels like "zagrijavanje" — the detector only knows posture, not named segments.
Counts remain heuristic estimates from a single tracked participant in group footage; the report
states this in "Napomena".

**To regenerate the fixture-based report locally (no emulator):**
```
.\gradlew.bat :app:testDebugUnitTest --console=plain -q
# -> app/build/reports/trainer-report-from-app.txt
```
`TrainerReportTest.report generated from the mp4 telemetry file` writes that file. To make a full
device run emit it too, the instrumented test writes `files/trainer-report.txt`; pull with
`cmd /c "adb exec-out run-as com.example.fitpulseai cat files/trainer-report.txt > reports\..."`
(PowerShell `-join`/`>` corrupts UTF-8 — see the pull snippet below).

### Tuning history (useful if numbers regress)

Raw runs at `intervalMs 80`: **501** detections (old pipeline, 227 punches/237 kicks) → **92**
(gated + posture, still 62 punches/8 kicks) → **4** (punch guard too strict) → **40/53** window →
**114** (Punch 55, HK 35, Squat 12, Jack 11, Push 1) → **105** final after lunge/push-up fixes.
Push-ups in the real 7:03–7:20 block went 0 → 5; high knees 35 → 20 after the support-knee rule.

## Test harness & how to reproduce

`app/src/androidTest/.../VideoSessionTest.kt` args: `intervalMs`, `startSeconds`, `maxSeconds`,
`probe`, `dumpFrames`, `frameTimes` (comma-separated seconds), `video`. Writes
`files/session-summary.txt`, `files/session-telemetry.json`, `files/trainer-report.txt`,
`files/probe.csv`.

Probe CSV columns: `pts_ms,landmarks,nose,torso_x,torso_y,torso_len,upright,kneeL,kneeR,elbowL,
elbowR,abdL,abdR,wristOverL,wristOverR,ankleSpread,wristSpread,hipAnkleL,hipAnkleR,posed,
calibrated,reps,exercise,posture`. **`reps` is cumulative — diff it to get rep events**;
`-1` means a null feature.

Window run (~2–3 min, use for quick checks):
```powershell
& $adb shell am instrument -w -e intervalMs 80 -e startSeconds 400 -e maxSeconds 140 -e probe true `
  com.example.fitpulseai.test/androidx.test.runner.AndroidJUnitRunner
```

Full run (~8.5 min):
```powershell
& $adb logcat -c
& $adb shell am instrument -w -e intervalMs 80 -e probe true `
  com.example.fitpulseai.test/androidx.test.runner.AndroidJUnitRunner
```

HIIT video (root `BESPLATNI HIIT TRENING SA NAŠIM CURAMA…mp4`, pushed as app-internal `files/hiit.mp4`;
44:26 timeline, full run ~21 min):
```powershell
& $adb shell am instrument -w -e video hiit.mp4 `
  com.example.fitpulseai.test/androidx.test.runner.AndroidJUnitRunner
```

After any main-source change rebuild **and install both APKs** (stale test APK gives
`ClassNotFoundException`):
```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain -q
$adb = "C:\Users\armin\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
```

Emulator `Pixel_5` (API 37, x86_64) dies between sessions; restart with:
```powershell
$emu = "C:\Users\armin\AppData\Local\Android\Sdk\emulator\emulator.exe"
Start-Process -FilePath $emu -ArgumentList "-avd","Pixel_5","-no-window","-no-audio","-no-boot-anim","-gpu","swiftshader_indirect","-no-snapshot-save" -WindowStyle Hidden
# poll: adb shell getprop sys.boot_completed -> "1"
```

Pull artifacts UTF-8/binary safe. **Use `cmd /c "..."` for raw bytes** — PowerShell `>` writes
UTF-16 and `(& $adb exec-out ...) -join` decodes with the wrong codepage, both corrupt Croatian text
(the trainer report was mangled once before being re-pulled correctly):
```powershell
cmd /c "$adb exec-out run-as com.example.fitpulseai cat files/trainer-report.txt > reports\trainer-report-device.txt"
cmd /c "$adb exec-out run-as com.example.fitpulseai cat files/session-summary.txt > reports\session-summary.txt"
# repeat for session-telemetry.json and probe.csv -> probe-full.csv
```

Dump specific frames for visual verification (`maxSeconds 2` keeps it quick; the trailing
assertion failure is expected):
```powershell
& $adb shell am instrument -w -e intervalMs 1000 -e maxSeconds 2 -e dumpFrames true `
  -e frameTimes "420,900,1200" com.example.fitpulseai.test/androidx.test.runner.AndroidJUnitRunner
```

Videos are in app-internal `files/`: `workout.mp4` (original 23:41 clip) and `hiit.mp4`
(the 839 MB HIIT clip); both survive reinstall. The filename of the HIIT file on the host contains
diacritics — locate it via `Get-ChildItem ... | Where-Object { $_.Name -like 'BESPLATNI*' }`, not a
literal path. If a video is missing, push via `/data/local/tmp` then
`run-as com.example.fitpulseai cp` (chmod 666 the tmp copy). Emulator `Pixel_5` dies between
sessions — restart it (snippet above) before any new run.

## Known limitations (NOT solved)

1. **Single skeleton**: ML Kit's bundled pose detector returns exactly one pose per frame. With
   five people and camera cuts the app can only follow one at a time; many reps in long marching /
   lunge blocks are missed, and some lunges still register as squats. Cannot fix without a
   different pose model.
2. **Floor stretch false positives**: a few holds around 16:00 count as push-ups because elbows
   flex/extend there.
3. **Coarse posture timeline**: long "Standing work" spans hide changes between exercises, so
   "What the camera saw" is weak in the first ~10 minutes.
4. **Punch count 58** is plausible only because the video contains boxing blocks; scattered
   punches elsewhere may still be arm swings.
5. Camera-path behaviour still works; Firebase/AI recap is optional and was not exercised.
6. **HIIT video caveat**: in the 839 MB group HIIT clip only 2 high knees and 2 squats were found
   (vs 59 punches and 34 jumping jacks) — the single-skeleton gate follows one person through
   camera cuts, so the trainer report undercounts those exercises. The report's "Napomena" already
   says counts are approximate.

## Next steps (pick one; test-first as before)

1. **Richer activity timeline** (highest user-visible value): track per-frame motion signatures
   (arms overhead, knee lifts, wide stance, horizontal torso) in 10–20 s buckets and emit labelled
   phases like "boxing-style arm work", "knee lifts", "lunges/squats", "floor mobility" instead of
   just STANDING/FLOOR. Add `ActivityBlock` labels + tests, then a full run.
2. **LUNGE exercise type**: detect one knee deeply bent with the other leg trailing; prevents
   lunges being reported as squats and makes the 8:11–8:40 block explicit.
3. **Tighten late push-ups**: require a stable horizontal torso and both elbows cycling 2+ times
   within a short window to suppress hold/stretch frames around 16:00.
4. **Honest low confidence**: surface a per-exercise confidence/coverage indicator in the text
   report so users know counts are heuristic in group footage.
5. **Richer trainer report phases**: the deterministic report currently says "Rad u stojećem
   položaju / Rad na podu" with timestamps. Reusing the labelled `ActivityBlock` phases from step 1
   would make "Što se dogodilo" read more like a real trainer's narrative.

## Data cheat sheet (`reports/probe-full.csv`)

- Posture frame counts (latest run differ slightly): roughly STANDING 10.5k, FLOOR 3.7k,
  BENT_OVER 0.4–0.6k, UNKNOWN 3k of ~18k analysed.
- Floor phases by minute: 12, 15, 16, 19, 20, 21 min. Elbow medians 140–170° in holds/stretches,
  88–115° in the 15:00–16:30 block.
- Push-up window 415–450 s: elbow p10 ≈ 78–94°, median ≈ 160°, upright median ≈ 160°,
  hipAnkle wildly unreliable (-10 to +1) → never rely on ankles alone there.
- Lunge window 490–520 s: both knees ≈ 112–126°, elbows ≈ 50–80° (weights at chest);
  this is why the support-knee rule was needed for high knees.
- Jumping-jack-shaped cycles cluster at 0:56–1:03 and 11:47–13:12; the latter is the wide
  overhead squat, hence the "feet come back together" rest requirement.
- Frame dump files already pulled in `reports/frames/`; reuse them before running the emulator.
