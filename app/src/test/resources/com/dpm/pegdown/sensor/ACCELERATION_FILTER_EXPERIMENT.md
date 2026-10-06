# Longitudinal acceleration update — 2026-10-06

## Recording

Read-only source: C:/Users/denis/Desktop/PegDown/PegDown.zip.
Entries: PegDown_sensor_diagnostics_20261006_100733.csv and 2026-10-06_12-51.gpx.
CSV: 899731 data rows; 896989 sensor samples; 2736 location callbacks;
duration approximately 2619 seconds. No dropped_samples row found.

The rider reports about 30 seconds stationary with engine off, then engine-on
vibration, followed by grass/curb and a left turn. Recording ends shortly after
parking; the final bank near -14 degrees is not an upright reference.

## Implemented change

ForwardAccelerationProcessor projects Android linear acceleration onto the
horizontal projection of screen -Z using upright calibration gravity.
The resulting axis is fixed in the mounted phone. It does not rotate back into
the initial heading via the current Android quaternion.

A signed EMA uses sensor timestamps and a 0.35-second time constant.
Samples are bounded at +/-2 g before filtering; the 0.05 g deadband is applied
after filtering. Only then are positive acceleration, negative braking and
existing peak tracking updated. Non-finite vectors, unsupported near-flat
mounts and out-of-order events are ignored. Gaps over 0.25 seconds reset the
filter; calibration, recording, tour reset and sensor startup also reset it.

This assumes screen -Z projected onto the calibration plane matches actual
motorcycle forward. Upright calibration cannot determine mounting yaw.
It still uses Android linear acceleration, which can have gravity-estimation
errors. Filter response has a delay: about 0.35 seconds to reach 63 percent of
a constant acceleration step. No GPS-speed-zero forcing is installed.

## Offline checks

Native event order and native monotonic times are used. Both old and new
algorithms start at zero at recording start. Old reconstruction uses recorded
Android quaternion relative to calibration, screen -Z and the former separate
positive/negative filters.

Stationary window 40–60 seconds after first sensor event:
- Old displayed signed RMS: 0.0612597577 g.
- New displayed signed RMS: 0.0039621062 g.
- New 95th percentile of absolute displayed value: 0 g, after deadband.
- These numbers do not cover every stationary moment or the start/curb shocks.

Dynamic comparison selects consecutive native GPS fixes:
- Fix interval 0.3–2.5 seconds.
- Both speeds at least 8 m/s.
- Absolute heading difference at most 2 degrees.
- Absolute speed-derived acceleration at least 0.08 g.
- IMU displayed values averaged over the fix interval shifted forward 0.35 s
  for the new causal filter's nominal lag. The old reconstruction is measured
  on this same window, although its filter has a different response.
- 319 intervals selected.
- New/old sign agreement: 74.92 percent / 45.77 percent.
- New/old mean absolute difference from GPS: 0.09824 g / 0.14233 g.

These comparisons are diagnostic, not independent acceleration ground truth.
The lag is the chosen filter time constant, not a measured timing offset.
Mounting-axis and filtering changes are combined; this comparison does not
isolate which change contributes how much. GPS noise, signal delay and Android
gravity estimation can explain some remaining differences. No filter parameter
sweep on this recording was performed.

## Validation and reproduction

Five portable unit tests cover tilted-mount vertical vibration rejection,
alternating longitudinal vibration, acceleration/braking/reversal magnitude,
sample-rate independence, invalid inputs and timestamp/gap/reset handling.
A sixth test optionally streams the original external archive and checks the
stationary reduction plus reports the dynamic comparison.

PowerShell:

```powershell
$env:PEGDOWN_ACCEL_DIAGNOSTICS_ZIP = 'C:/Users/denis/Desktop/PegDown/PegDown.zip'
.\gradlew.bat :app:testDebugUnitTest --tests com.dpm.pegdown.sensor.ForwardAccelerationProcessorTest --rerun --info --console=plain
```

Without the variable, the external replay is explicitly skipped.
All app unit tests with the new replay and :app:assembleDebug succeeded.
The older large-archive replay uses its separate existing environment variable
and was not pointed at the new archive. No device tests were run.

## Remaining work

Verify the new live acceleration display in a later ride. Improve the slow-start
bank transient separately: the lean estimator currently rejects moving course
corrections below 8 m/s. Do not simply lower that threshold without evaluating
course accuracy, curb shocks and gyro error on recorded data. The lean algorithm
and its thresholds were not changed in this acceleration update.
