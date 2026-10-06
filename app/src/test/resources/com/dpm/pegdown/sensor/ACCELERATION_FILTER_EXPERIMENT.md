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
- IMU displayed values averaged over the SAME native fix interval for both
  implementations. An earlier version assumed a +0.35 s alignment shift from
  the EMA time constant; that was not a measured GPS/IMU delay and has been
  removed from the primary comparison.
- 319 intervals selected.
- Direction categories use an interval-mean magnitude below 0.01 g as near zero:
  240 agree, 32 opposite, 47 near zero for the new implementation.
- Only 7 opposite intervals have IMU magnitude at least 0.05 g.
- With the same 0.01 g classification threshold for both implementations,
  new/old direction agreement is 75.24 percent / 28.53 percent.
- New/old mean absolute difference from GPS: 0.08172 g / 0.14352 g.
- Acceleration: 90 agree, 12 opposite, 5 near zero.
- Braking: 150 agree, 20 opposite, 42 near zero.
- Stricter declared-error subset: speed-difference error scale <= half the
  observed acceleration and both course-error scales <=2 degrees:
  308 intervals, 236 agree, 28 opposite, 44 near zero.

These comparisons are diagnostic, not independent acceleration ground truth.
Do not interpret every non-agreeing interval as reversed acceleration.

### Timing and sustained phases

Fixed diagnostic window shifts were examined without changing the app filter:

| IMU window shift relative to GPS | Direction agreement | Opposite >=0.05 g | Mean absolute difference |
| --- | --- | --- | --- |
| -0.70 s | 274/319 | 2 | 0.06721 g |
| -0.35 s | 272/319 | 3 | 0.06911 g |
| 0 s (primary) | 240/319 | 7 | 0.08172 g |
| +0.35 s | 209/319 | 19 | 0.09824 g |
| +0.70 s | 187/319 | 37 | 0.11348 g |

Earlier sensor windows agree more often with GPS speed changes in this ride.
This is consistent with relative signal timing, but does not identify a unique
GPS latency or justify fitting a live correction delay to one ride.

55 overlapping windows containing three consecutive selected GPS intervals
with the same acceleration sign were evaluated without time shift. Direction
agrees in 53; none has opposite sensor magnitude >=0.05 g. Mean absolute
difference is 0.06926 g. They are overlapping diagnostic windows, not 55
independent trials.

The seven larger remaining native-interval oppositions occur at approximately
886, 1503, 1844, 2092, 2235, 2245 and 2413 seconds. Several are near neighboring
GPS acceleration/braking reversals. Their maximum displayed bank is roughly
1.6–7.7 degrees, so a large sustained lean is not required for these mismatches.
Timing, vibration, Android filtering/gravity estimation and GPS uncertainty
cannot be isolated completely from this recording.

For inspection only, the test reports the longitudinal projection of raw
accelerometer minus Android linear-acceleration samples when their native
timestamps match. Different sensor processing may still be involved; this
difference is not asserted to be an independently measured gravity vector.

Mounting-axis and filtering changes are combined; this comparison does not
isolate which change contributes how much. No production filter parameters or
mounting yaw were fitted to these results.

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

Verify the new live acceleration display in a later ride. The separate guarded
slow-course update is documented in SLOW_START_LEAN_EXPERIMENT.md and already
permits experimental corrections from 3 m/s with stricter checks. The current
follow-up changes only replay analysis, tests and documentation; live
acceleration and lean parameters remain as in commit 950c4e5.
