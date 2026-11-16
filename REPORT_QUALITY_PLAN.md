# Improve workout report quality

## Problem and confirmed direction

The generated `reports/hiit-trainer-report.txt` is not trustworthy enough as a session report. It reports only two high knees and two squats for a long HIIT video, uses generic standing/floor periods instead of describing the workout, and makes unsupported claims about the workout's goal, muscle groups, balance, and conclusion.

The user chose to improve **the analysis and the report together**, rather than just rewrite the current output. Preserve the deterministic, offline, factual-only scope: report what the system observed, show uncertainty and tracking limits, and do not add coaching, exercise advice, or invented activities. Retain the current Croatian trainer-report language unless the user asks for a language change.

## Evidence from `HANDOFF.md` and the sample report

- The pipeline follows one ML Kit skeleton at a time; the HIIT clip has camera cuts, multiple people, and 49 tracked-subject switches. Movement counts can be substantially incomplete.
- The coarse posture timeline says “standing” and “floor” and does not reliably distinguish exercise segments.
- The report template infers a training goal, muscle groups, and balanced loading from counts. Those are not measured by the telemetry.
- Exercise counts vary between runs and include likely false positives; the report must call them detected estimates, not ground truth.
- The report currently includes all posture blocks in the sample output, which is noisy and lengthy.

## Proposed implementation

1. **Define report evidence rules first.** Keep measured facts (duration, detected repetitions by exercise and side, recorded intervals, tracking/pose coverage) distinct from detector-based labels. Remove unsupported “goal,” muscle-group, balance, and broad conclusion claims. Say “not detected” rather than implying an exercise was absent. Keep Croatian and no-coaching behavior.
2. **Improve activity segmentation from existing pose signals.** Extend the per-frame feature and session aggregation path to summarize short fixed windows (initially 10–20 seconds) using evidence already available: posture, arm elevation/movement, knee lift, stance width, and torso orientation. Emit conservative candidate activity phases only when their features support them; preserve an unknown/uncertain label instead of guessing. Merge adjacent matching phases without bridging long gaps.
3. **Address the most important exercise confusions.** Add a distinct lunge detector to avoid reporting lunge-like movements as squats, and tighten high-knee and squat confirmation using the signals called out in `HANDOFF.md`. Require repeated evidence for exercise labels where single-frame poses are ambiguous. Do not claim multi-person tracking; the current pose model only yields one pose.
4. **Expose tracking limitations in telemetry and report.** Reuse available accepted/rejected-frame and subject-switch diagnostics, plus pose/posture coverage, to make uncertainty specific and understandable. Do not invent a numeric confidence score unless it is calibrated against labeled examples; prefer transparent coverage counts/rates and explicit single-person/camera-cut limitations.
5. **Rewrite the deterministic report around useful observations.** Provide a concise session overview, chronological candidate activity phases with uncertainty where needed, a sorted detected-repetition table with left/right splits where meaningful, and a short limitations/coverage section. Remove generic anatomy claims and evaluative conclusions. Keep analysis offline and do not send video or imagery to Firebase.
6. **Test against annotated evidence before regenerating reports.** Add focused unit tests for segmentation boundaries, ambiguous/unknown windows, lunge-versus-squat and high-knee cases, and wording that prohibits unsupported claims. Use representative frames and known intervals from `HANDOFF.md`; create a small labeled set for the HIIT clip before asserting exact labels. Retain the existing session fixtures and report determinism test.
7. **Validate in stages and regenerate artifacts.** Run targeted unit tests, then the full unit suite and build. Run short emulator windows on representative exercise transitions before another expensive full-video run. If a full run is needed to assess the HIIT report, compare its outputs to the labeled intervals and document which misses remain. Regenerate `reports/hiit-trainer-report.txt` and related artifacts only after tests pass.

## Scope and safeguards

- This plan does not authorize implementation yet; it is for user review.
- After approval, publish this approved plan as `REPORT_QUALITY_PLAN.md` in the repository root before implementation. The coding session should read that file as its source brief so the plan is available to future agents.
- No AI-generated narrative, coaching, tips, cloud history, or Firebase changes.
- Keep the sample video and private/local Firebase configuration out of Git.
- Do not label an exercise as absent solely because the detector did not count it.
- If existing pose features cannot reliably distinguish activities, report a broader/uncertain phase rather than adding unsupported specificity.

## Todos and dependencies

- Publish the approved implementation brief as `REPORT_QUALITY_PLAN.md` in the repository for future coding agents.
- Define evidence-backed report contract and tests.
- Implement short-window activity feature aggregation and conservative phase labels.
- Add/tune lunge, high-knee, and squat detection with focused regression tests.
- Add tracking coverage diagnostics and factual limitations to telemetry/report.
- Revise the report template and regenerate validated report artifacts.
- Run staged tests/build and review report output against the labeled video intervals.

The approved plan file must be published before implementation. The report contract and annotated intervals must be defined before segmentation/detector changes. Detector and segmentation changes must be tested before report artifact regeneration. Final validation depends on all implementation work.
