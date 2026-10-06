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
propagation. The existing stationary correction still applies at <=0.4 m/s.

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
succeeded. Earlier portable replay fixtures still run with the extension off.
No device tests or new live ride have been performed with this extension.

Next validation is a ride with the experimental mode enabled, upright
calibration and unchanged mount. Compare the early residual and later curve
exits, and retain the diagnostic ZIP and GPX.
