# Conservative straight-ride bank reference

## Scope

SensorProcessor enables this extra reference only in the existing experimental
sensor/GPS lean mode. The shared estimator defaults it off for historical
controls. The standard Android mode is unchanged.

This is a near-upright hypothesis supported by GPS and accelerometer data, not
an independent bank measurement. Straight travel does not universally imply
zero bank: crosswind and other effects can require real lean.

## Qualification

The reference collects contiguous intervals only after the normal moving
GPS/accelerometer correction is accepted. It requires at least five seconds.
GPS intervals from 0.3 to 2.5 s are supported; three 2 s observations therefore
produce a 6 s qualifying window rather than assuming a particular update rate.

Every observation requires:

- Both endpoint speeds >=8 m/s, about 28.8 km/h.
- Both bearing accuracy estimates <=1.5°, speed accuracy <=0.5 m/s and
  position accuracy <=10 m.
- Current callback/fix age <=1 s.
- Absolute estimated longitudinal acceleration <=0.5 m/s².
- Absolute mean gyro yaw <=0.01 rad/s and mean roll <=0.04 rad/s.
- Motion-compensated acceleration implies bank within +/-1° and gravity
  magnitude within 0.5 m/s² of standard gravity.

Gyro means are evaluated over the GPS interval, rather than rejecting every
brief vibration spike. A separate latest-250-ms mean veto rejects current
yaw above 0.04 rad/s or roll above 0.12 rad/s.

Over the complete window:

- Unwrapped GPS course range <=2°; north crossing is handled.
- Absolute integrated gyro yaw <=2°.
- Inferred pitch range <=2°.
- The course endpoint change plus the reported endpoint accuracy scale,
  divided by elapsed time and combined with maximum speed, must imply an
  equilibrium-bank magnitude <=2°.

The last check is a heuristic uncertainty allowance, not a calibrated
confidence bound or a measurement of actual bank. It is evaluated only once
the complete five-second window exists; applying it to the first one-second
prefix would prevent otherwise valid windows from ever accumulating.

Bad GPS, turning, accelerating/braking, unstable gravity or rejected base
correction restarts qualification. Network fixes contribute no observations
and apply no extra correction. Gyro gaps clear it. Automatic recovery suppresses
and clears the reference until the experimental estimator is confirmed ready.

## Applied correction

The extra update runs on a qualifying GPS callback, not freely between fixes.

- If current calculated bank is already within +/-1°, no extra update is made.
  This is a deadband for this additional correction, not a display deadband.
- Otherwise the desired bank change is current bank times
  (1 - exp(-GPS interval / 30 s)), capped at 0.15° per second of GPS interval.
- A quaternion correction changes internal bank toward zero while retaining
  current pitch. No separate display offset or gyro-bias learning is added.
- The same world rotation updates the retained attitude history, preserving
  relative gyro motion used for alignment.

This is deliberately slow and bounded. It is not intended to erase a 20°
residual immediately. The existing motion-compensated corrections remain the
primary orientation reference.

A current estimate need not already be close to zero to qualify: the independent
condition here is the compensated acceleration's near-upright direction.
Conversely, a known true lean above 1° must not be flattened merely because
the GPS heading changes slowly.

## Tests

StraightRideReferenceTest covers:

- Full waiting duration, contiguous observations and north crossing.
- Long shallow turns with mean yaw below the instantaneous yaw limit but
  excessive cumulative heading change.
- Bad/old GPS, low speed, braking, gravity disagreement and pitch transitions.
- Synthetic constant roll bias: after 45 s, control bank 3.6690° versus
  3.2600° with the additional correction; 41 small updates, all rate limited.
- Retaining real 0.6° and 2° lean without extra updates.
- Actual steady motorcycle turns at 1.5° and 20°, including the shallow 1.5°
  turn whose course changes very little over five seconds.
- Gyro gaps and recovery explicitly discarding qualified history.

SensorProcessor tests cover live-mode configuration, correction metadata and
the existing automatic recovery behavior with the extension enabled.

## Full native ride comparisons

Baseline for these tests is the stationary guard already enabled.
Only the straight-ride extension differs. Thresholds are fixed across runs.
The original table used stationary guard version 1. After adding the version 2
speed-history gate, October 4 was rerun: still 11 qualifying fixes, three extra
updates and maximum display difference 0.166368°. Other rows below describe
the earlier version 1 comparison. The straight-reference rules themselves
were not retuned.

| Source | Qualified fixes | Extra updates | Maximum display difference |
|---|---:|---:|---:|
| October 4 | 11 | 3 | 0.166368° |
| October 5 | 0 | 0 | 0° |
| October 6 | 0 | 0 | 0° |
| November 1 | 0 | 0 | 0° |

October 4 extra bank changes:

| Relative GPS fix time | Control current bank | Corrected current bank | Applied delta |
|---|---:|---:|---:|
| 1087.899656 s | 1.702321° | 1.592532° | -0.109790° |
| 2985.928716 s | -1.244060° | -1.163826° | +0.080235° |
| 3075.930122 s | -1.746504° | -1.633865° | +0.112639° |

Mean absolute display difference across the October 4 stream after 20 s is
0.000449°. The eight other qualified fixes require no extra change because
the current estimate is already in the additional correction's deadband.

The other rides fail to maintain all conditions for a full five-second window.
They are not made to display zero just to demonstrate activity. Consequently
this extension has not demonstrated a reduction of the reported November
startup error. Without independent riding bank, the three October 4 changes
also do not prove absolute accuracy improvement.

## Diagnostics and reproduction

Session metadata identifies experimental_straight_reference=true, version 1,
window duration, minimum speed, correction-rate cap and bank deadband.

Correction metadata records straight_reason, straight_qualified,
straight_duration_seconds, straight_observation_count, straight_course_range_degrees,
straight_course_uncertainty_bank_degrees, straight_reference_bank_degrees and
straight_applied_bank_delta_degrees. These describe this extra update separately
from the normal gravity correction. Overall before/after bank includes both.

Optional native comparison:
- PEGDOWN_STRAIGHT_RIDE: path to a CSV or ZIP.
- PEGDOWN_STRAIGHT_ENTRY: exact CSV entry name for archives with multiple CSVs.
- Gradle task: :app:testDebugUnitTest
  --tests com.dpm.pegdown.sensor.StraightRideReplayTest --rerun --info --console=plain.

No device test or new live ride is included in this validation.
For a live attempt, after normal upright calibration record a safe, steady
straight stretch for several seconds, then inspect the recorded qualification
reason; the rider should not operate or watch the app while riding.
