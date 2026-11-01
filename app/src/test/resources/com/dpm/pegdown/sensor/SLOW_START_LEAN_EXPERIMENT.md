# Guarded slow-course correction — 2026-10-06

## Purpose

The rider reports a roughly -9 degree residual after riding over grass and a
curb and then turning left. Recorded native gyro/accelerometer data reproduce
the display. The previous estimator rejects moving course corrections below
8 m/s (28.8 km/h); consequently the residual is largely propagated during the
initial slow road section. This establishes a correction blind spot, not the
physical cause of the curb-induced attitude error.

## Change

Within the opt-in experimental live mode, moving GPS corrections can now start
at 3 m/s (10.8 km/h). Corrections involving either endpoint below 8 m/s require:

- Both endpoints at least 3 m/s.
- Both declared bearing errors at most 2 degrees.
- Differentiated lateral-acceleration uncertainty estimate at most 0.5 m/s².
- GPS heading change consistent with averaged gyro yaw: discrepancy at most
  three declared course-error scales plus 0.02 rad/s.

The existing position, speed, fix-age, interval, roll-rate, changing-yaw,
gravity-magnitude and innovation checks remain. Course error bounds are not
calibrated confidence intervals. Corrections still use the full interval's
motion-compensated acceleration. No straight-road zero forcing is introduced.
At walking pace or with poor course data, the estimator continues gyro
propagation. The stationary correction applies at <=0.4 m/s. Since November 1,
SensorProcessor additionally requires stable sensor history before using that
reference; see [STATIONARY_GRAVITY_GUARD.md](STATIONARY_GRAVITY_GUARD.md).

Above 8 m/s at both endpoints, the algorithm is identical to the control.
The shared estimator defaults this extension off; SensorProcessor explicitly
enables it in the user's opt-in experimental mode. Session metadata identifies
the extension and its limits. The normal Android lean mode is unaffected.

## Replay

Read-only external source: C:/Users/denis/Desktop/PegDown/PegDown.zip,
PegDown_sensor_diagnostics_20261006_100733.csv.

Both estimators process all native gyro, raw acceleration and location callbacks
in recorded file order, with original native fix times. Callback arrivals are
reconstructed from recorded wall-time differences anchored to the first sensor
sample. Calibration and initial orientation come from session metadata. The
display uses the existing 59 Hz-equivalent EMA and deadband. The control is also
checked against the recorded experimental display in the startup comparison
phase, 80–150 seconds, with maximum allowed difference 0.02 degrees.
Actual maximum startup difference is 0.0000007185 degrees.

Across the whole recording, the maximum control/recording difference is
0.7941 degrees at 1406.85 seconds. Exact live monotonic callback arrival is not
stored in the CSV; the replay reconstructs arrivals from wall times. The cause
of the isolated full-ride mismatch has not been established. The replay is
therefore not claimed to reproduce every later event exactly; the primary
evidence here is the reproduced startup section.

Preselected reported startup residual interval, 93–99 seconds:
- Control mean absolute displayed bank: 7.1794 degrees.
- Candidate mean absolute displayed bank: 1.3479 degrees.
- Nine low-speed corrections accepted in the 80–120 second startup window.
- Later mean absolute difference between candidate and control, 180–2500 seconds:
  0.0483 degrees.

At roughly 95 seconds the control shows -6.91 degrees and the candidate -1.18.
At roughly 98 seconds the control shows -7.07 degrees and the candidate +0.19.

These are displayed residuals on a rider-reported section, not measured absolute
bank errors. The lower residual does not establish true bank during the turn or
curb descent. A transient can still occur before trustworthy GPS data are
available. There is no independent riding bank reference and no guarantee for
other motorcycles, phones, road surfaces or GNSS conditions.

## Tests

Portable synthetic checks cover:
- Correcting an initial 10-degree bias at 5 m/s with good GPS course.
- Retaining a true 20-degree slow turn, rather than flattening it.
- Rejecting walking pace and poor bearing accuracy.
- Rejecting excessive differentiated course uncertainty.
- Rejecting precise but gyro-inconsistent false GPS turns.
- Identical high-speed results with the extension enabled or disabled.

The native external replay is optional:

```powershell
$env:PEGDOWN_SLOW_START_ZIP = 'C:/Users/denis/Desktop/PegDown/PegDown.zip'
.\gradlew.bat :app:testDebugUnitTest --tests com.dpm.pegdown.sensor.SlowStartLeanReplayTest --rerun --info --console=plain
```

All local app unit tests, both new October 6 ride replays and :app:assembleDebug
succeeded. No device tests or new live ride have been performed with this extension.

### Transfer check using October 4 and 5 fixtures

The new portable transfer test compares the extension enabled/disabled with
identical inputs, full-interval compensation and no parameter changes.
Archived compact fixtures contain averaged sensor samples, not the full native
October 6 input rate. Windows were selected in the earlier investigations.

| Window | Mean absolute control bank | Mean absolute candidate bank | Maximum output difference |
| --- | --- | --- | --- |
| Oct04 continuous, 68–76 s | 2.6143° | 2.6143° | 0° |
| Oct05 continuous, 72–80 s | 0.8507° | 0.9167° | 1.1261° |
| Oct05 left exit, 12–20 s | 0.8937° | 0.9510° | 0.5413° |
| Oct05 right exit, 10–15 s | 1.3473° | 1.3473° | 0° |
| Oct05 following left, 10–19 s | 1.3489° | 1.3489° | 0° |
| Oct05 faster exit, 17–25 s | 8.9273° | 8.9273° | 0° |

All variants have zero source-stream gyro gaps and matching output timestamps.
Four selected windows are unchanged; two are changed. Additional corrections
outside a selected window may still affect later results. Without independently
known bank, the increased magnitude in two windows cannot be called a true
accuracy regression or improvement. The existing faster-exit residual remains
unresolved; the slow-course extension does not fix that case.

The test reports these changes without asserting that lower real bank values
mean higher accuracy. This transfer check complements synthetic ground-truth
tests; it does not establish general ride accuracy. The extension remains
opt-in, with unchanged production thresholds.

## Startup attribution from native events

An additional replay separates the instantaneous change in the control's raw
bank from each gyro event and each GPS correction. No bank ground truth is
assumed. Callback arrivals in this archive are still reconstructed from wall
time, as above.

| Second after first sensor sample | Raw control bank at end of second |
| --- | --- |
| 78 | +0.9411° |
| 79 | -0.5499° |
| 82 | +4.1205° |
| 86 | -16.6896° |
| 90 | -2.3157° |
| 93 | -7.8090° |
| 96 | -7.3134° |

The first large acceleration shock at 78 s reaches 37.12 m/s² but is not
immediately followed by a persistent -9° bank. A later shock at 86 s reaches
47.36 m/s² with peak angular-rate magnitude 4.015 rad/s. GPS corrections in the
old control are absent from 82 through 99 s: moving fixes report
WAITING_FOR_COURSE because speed is below the old floor. A network fix is also
rejected as NON_GPS.

Consequently every control bank change in this interval comes from gyro
propagation, not an accepted accelerometer/GPS correction. The bank changes by
-9.55° during second 86, then +6.62° and +3.72° during seconds 87 and 88.
It later changes by -3.53° and -1.86° during seconds 91 and 92. These changes
include actual riding lean; they cannot individually be classified as errors.

This establishes the propagation path and correction blind spot. It does not
identify whether gyro vibration sensitivity, physical roll/pitch motion,
mount-axis assumptions or another effect creates the residual. Raw acceleration
shocks are contextual evidence, not proof of gyro error. Discarding gyro
rotations merely because acceleration is high could discard real motorcycle
motion, so no such shock suppression is added.

## Diagnostic timing and gyro availability

The next recording uses CSV schema version 2, with one appended column:
callback_elapsed_realtime_ns. Existing column positions are preserved.

- Location rows retain native fix time in elapsed_realtime_ns and separately
  capture monotonic arrival at entry to RecordingService.onLocationUpdate.
- The same captured arrival is passed into the estimator and recorder.
- lean_correction rows log every processed fix decision, including rejection,
  correction reference time, arrival, source fix time, bank before/after,
  innovation, gain and available motion-compensation details.
- Missing decision details are represented as null in metadata.
- Sensor rows leave the appended callback column empty; their native event
  timestamps are unchanged. Correction rows are additional events, not GPS fixes.
- The native replay reads the appended arrival when present and uses the legacy
  wall-time reconstruction only for older recordings.

A main-thread watchdog runs every 100 ms while sensor tracking is active.
An experimental estimate becomes invalid if gyro measurement age or accepted
gyro callback age exceeds 150 ms. This supplements the existing inter-sample
gap check and handles complete callback loss. Scheduling can delay the check;
this is not a hard real-time guarantee. Invalid estimates clear timestamp
history. After a gyro outage, automatic recovery keeps the original mounting
calibration and waits for fresh sensor data and confirmed references. Standard
Android lean mode is unaffected.

Portable tests cover complete callback loss, the timeout boundary, accelerometer
callbacks continuing without gyro, out-of-order gyro, timely gyro, recovery by
recalibration, stopping the watchdog, correction listener delivery, and separate
fix/arrival times in CSV rows. Delayed gyro delivery is tested separately from
measurement freshness. All local app unit tests, both October 6 native ride
replays, :app:assembleDebug and git diff --check passed after these changes.
No device tests were run.

### Automatic recovery after gyro loss

No manual recalibration is required solely because gyro data stopped. Recovery
preserves the original mounting reference; the current motorcycle pose is never
declared a new zero. Initial mounting calibration remains manual.

A fresh game rotation vector seeds a provisional estimator once gyro delivery
resumes. The provisional angle and its history stay unavailable. The estimator
continues collecting raw acceleration and applying the existing GPS quality,
motion compensation and innovation checks in the background.

Publishing resumes after at least three consecutive eligible accepted GPS
corrections spanning at least two seconds. Each must have orientation innovation
at most 3° and current bank change at most 1°. Stationary corrections additionally
require at least 0.5 s of gyro history with mean vector magnitude below 0.08 rad/s.
Moving corrections require declared course error at most 2° and speed error at
most 0.5 m/s, in addition to the estimator's existing interval checks.

Rejected GPS references reset confirmations; network fixes neither confirm nor
reset GPS progress. A renewed gyro outage discards all provisional state and
confirmations. An unsuccessful provisional seed is retried after ten seconds
of rejected confirmations using a fresh rotation vector. This does not publish
an unconfirmed Android angle. With insufficient reference quality, recovery
can remain pending indefinitely.

The gauge hides the current motorcycle indicator and shows a dash while the
angle is unavailable. Its status says automatic recovery is in progress.
Diagnostic correction metadata includes recovery state and confirmation count.

Synthetic tests cover automatic recovery at -15° on a side stand without
changing the mounting calibration; a coordinated 20° motorcycle turn at
15 m/s; waiting for GPS instead of network fixes; rejecting stationary yaw
motion; retrying a bad provisional seed; and restarting confirmation after a
second outage. These establish expected synthetic behavior, not real-ride
absolute accuracy. Validation of automatic recovery: 104 local unit tests passed,
three optional external-data tests were skipped, :app:assembleDebug succeeded,
and git diff --check passed. No device tests or live recovery trial were run.

Next validation is a ride with the experimental mode enabled, upright
calibration and unchanged mount. Compare the early residual and later curve
exits, and retain the diagnostic ZIP and GPX.
