# Stationary gravity correction guard — 2026-11-01

## Fault and scope

The November recording starts mid-session, already moving and with a nonzero
display. Its initial hill maneuver cannot be reconstructed completely.
A later recorded restart, however, permits a validated causal reconstruction.

At 55.868 s, GPS still reports zero speed. The existing stationary update
mistakes a dynamic acceleration vector for gravity: inferred pitch 20.094°
versus predicted pitch 1.874°, gravity norm 10.210 m/s². Its 5.259° quaternion
correction subsequently produces approximately 5.2° additional left bank
through the next turn. The 2.10-g raw peak would pass the previously considered
2.5-g shock veto.

Reconstruction from a documented stationary checkpoint agrees with 53 recorded
pre/post correction bank values within 0.000001853°. Omitting only that update
isolates its causal effect; it does not supply independent true bank.

## Live rule — version 2

SensorProcessor enables qualifyStationaryCorrections in the opt-in experimental
estimator. The shared estimator defaults it off for historical replay controls.
The standard Android lean mode is unaffected.

For the existing GPS speed <=0.4 m/s branch only, first qualify GPS history:

- Cover at least 750 ms with valid native GPS fixes; adjacent intervals must
  be 0.3–2.5 s, and reported speed errors must be <=0.3 m/s.
- Normally all covered speeds must be <=0.4 m/s. For each interval require
  (absolute speed difference + root-sum-square endpoint speed errors) / dt
  <=0.5 m/s².
- A coarse interval >=1.5 s ending at <=0.1 m/s may contain braking followed
  by a complete stop. In that case allow the IMU guard to decide rather than
  treating the whole interval's average braking as acceleration at its end.
  The GPS history/accuracy checks and all IMU checks still apply.
- Otherwise record STATIONARY_SPEED_UNCERTAIN without changing orientation.

Then apply the existing sensor checks:

1. Require 750 ms of bracketed accelerometer and gyro history, without sensor
   gaps beyond the existing 150 ms interpolation limit.
2. End at the latest acceleration sample actually covered by gyro orientation,
   capped at the fix timestamp plus 150 ms. This avoids an accidental missing
   history rejection caused by the relative phase of the sensors.
3. Transport acceleration samples into a common body frame using gyro attitude.
4. Calculate time-weighted means for three consecutive 250 ms bins.
5. Require every pair of these means, including the existing short gravity
   measurement, to differ by at most 1.0 m/s².
6. Require the norm of each bin's mean gyro vector to be at most 0.12 rad/s.

Limits are engineering quality thresholds, not calibrated confidence bounds.
The rotation average suppresses alternating vibration rather than rejecting on
individual acceleration peaks.

Compare measured vectors against one another, not against a presumed zero-bank
attitude. Stable real lean, side-stand lean and existing estimator drift remain
correctable. On failure, record STATIONARY_UNSTABLE and retain gyro propagation.
After fresh stable history, normal correction resumes automatically.

There is no stored permanent veto or manual recalibration requirement. GPS
history continues updating normally, so moving motion-compensated updates can
resume through their existing checks.

A low GPS speed and stable sensor means still cannot universally prove absence
of linear acceleration. This change addresses a reproduced failure mechanism;
it is not a general proof of absolute lean accuracy.

## November causal replay

At approximately 61.006 s, raw bank before display smoothing:

| Policy | Bank |
|---|---:|
| Previous stationary policy | -5.646° |
| Omit only the identified update | -0.431° |
| New stationary guard | -0.430° |

The guard rejects that update with complete history and mean gyro rate
0.14576 rad/s. The measured-vector spread is approximately 0.99653 m/s²;
the gyro criterion is therefore decisive. Adjacent stable updates at 53.868
and 54.868 s are accepted.

The portable synthetic suite covers startup acceleration with lagging GPS zero,
automatic resumption, high-frequency vibration, stable -15° side stand with
20° initial error, yaw rotation, missing history after gyro outage, sensor phase
offsets, limit inclusivity, and unchanged true 20° moving bank. Existing
SensorProcessor recovery tests also pass with the live guard enabled.

## Full native ride transfer checks — version 1 history

All runs use the same fixed thresholds and recorded input order.

| Source | Prior stationary acceptances | Guarded stationary acceptances | Guard rejections | Mean display difference after 20 s | Maximum display difference |
|---|---:|---:|---:|---:|---:|
| October 4 CSV | 153 | 147 | 7 | 0.0166° | 5.3693° |
| October 5 CSV | 23 | 14 | 9 | 0.0374° | 4.6286° |
| October 6 CSV in restored ZIP | 193 | 173 | 20 | 0.0793° | 7.2485° |
| November 1 CSV in ZIP | 197 | 160 | 37 | 0.1353° | 6.7194° |

Acceptance counts need not subtract exactly because earlier changed updates
affect later gravity/movement gates. No source-stream gyro gaps occur.
The October 6 recording has one rejection for incomplete initial history,
with no missing-history rejections after the first two seconds. The other
three runs have no missing-history rejections.
The longest consecutive block of stationary fixes is 1, 3, 6 and 9 respectively;
unquiet stops can therefore delay reference correction.

Differences are behavior changes, not independently measured angular errors.
In particular, the full November comparison initializes both algorithms from
session calibration for comparison; only the separate checkpoint reconstruction
reproduces the actual ongoing recorded state.

The restored C:/Users/denis/Desktop/PegDown/PegDown_old.zip contains the
October 5 and 6 native CSVs. Both were rerun with the existing guard thresholds.
The October 5 audit matches the earlier standalone CSV results exactly.

In the previously selected October 6 residual interval 93–99 s (351 samples),
mean absolute display decreases from 1.347895° to 0.649417°. Mean absolute
difference between policies is 0.829927°. This is a display-residual comparison,
not a true-bank measurement.

The largest October 6 difference occurs at 2315.245 s: original display
+7.748497° versus guarded +0.499980°. The last relevant stationary decisions
at 2309.232 s and 2310.233 s are rejected by the guard:
- 2309.232 s: mean vector spread 2.3380 m/s², mean gyro 0.1026 rad/s.
- 2310.233 s: mean vector spread 1.3738 m/s², mean gyro 0.3488 rad/s.

They follow accepted stable updates and illustrate how the stationary decision
can affect a subsequent turn. Lower magnitude alone is not proof of correctness.

The largest October 5 difference is -2.130601° versus +2.498040° at 901.152 s.
The changed sign and slightly larger magnitude are reported explicitly rather
than treating every change as an improvement. No independent bank reference
exists for that event.

Existing compact fixture and synthetic tests also run in the regular unit suite.

## Version 2 regression and transfer checks

The neutral review exposed a uniform-braking counterexample: an upright device
braking at 2 m/s² from 2.3 m/s reaches a correctly reported 0.3 m/s after one
second. Stable IMU means alone accepted this as gravity and applied 3.26755°
of false tilt. A subsequent pure-yaw geometry check transferred the error into
3.26755° of bank. Version 2 rejects the reference; the same bank remains zero.
The pure-yaw step is a geometry test, not a full riding trajectory.

Other portable tests cover half-second GPS updates, low but changing speeds,
uncertain/missing/stale GPS history, reset after a bad fix, automatic resumption,
the coarse braking-then-stop case, and threshold boundaries. Existing vibration,
-15° side-stand, mount geometry and automatic gyro recovery tests remain active.

The first implementation required every previous GPS speed to be low even
for two-second sampling. That could discard a genuine complete stop and
produced a 24.894° difference later in the October 4 replay. The coarse-stop
rule above addresses this temporal ambiguity. With that rule, the difference
at that later maximum is no longer the replay maximum.

Final full-stream comparisons with the historical unguarded policy:

| Source | Original stationary accepts | V2 accepts | IMU guard rejections | GPS guard rejections | Mean display difference | Maximum display difference |
|---|---:|---:|---:|---:|---:|---:|
| October 4 | 153 | 135 | 5 | 14 | 0.033254° | 5.158455° |
| October 5 | 23 | 10 | 5 | 8 | 0.046857° | 7.348707° |
| October 6 | 193 | 162 | 18 | 13 | 0.087383° | 7.733153° |
| November 1 | 197 | 151 | 28 | 18 | 0.139954° | 6.719353° |

All streams have zero gyro gaps. These remain behavior comparisons, not true
angular error measurements. Acceptance counts need not subtract exactly because
changed orientation affects later gates.

### Bundled November fixture

november_20261101_stationary_reference.csv contains 217 original gyro and raw
acceleration samples from about 54.75 s through the callback for the 55.867764 s
reference, plus calibration direction, preceding speed fixes and recorded
pre-update bank/pitch. It contains no latitude/longitude or full ride archive.

NovemberRideStartReplayTest.bundledNovemberReferenceReproducesTheRecordedUpdateAndRejectsIt
always runs without environment variables. It anchors the documented
pre-update orientation, reconstructs the local gyro history, reproduces
recorded before/after bank and the 5.25899° original update within 0.00001°,
then requires STATIONARY_UNSTABLE with complete speed/sensor history and
exactly unchanged bank. Earlier GPS corrections are not replayed by this local
fixture. The optional full checkpoint replay supplies the wider causal check.
Neither test supplies independent true riding bank.

## Diagnostic identification

Session metadata includes experimental_stationary_guard=true, version 2,
all GPS/IMU limits, app_version_name, app_version_code and app_build_id.
The build ID is read from the same generated string resource as the visible
label; it remains stable for identical production sources/configuration.

Correction metadata includes sensor and speed history intervals/coverage,
maximum mean acceleration difference, maximum mean angular rate, maximum
speed/error, estimated acceleration allowance, coarse-stop decision and both
qualification results. The CSV column schema stays unchanged.

## Reproduce

Optional native tests require local diagnostic files; without environment
variables they are skipped.

Set PEGDOWN_NOVEMBER_DIAGNOSTICS_ZIP to the November ZIP and run
:app:testDebugUnitTest --tests com.dpm.pegdown.sensor.NovemberRideStartReplayTest.

Set PEGDOWN_STATIONARY_RIDE to a CSV or ZIP and run
:app:testDebugUnitTest --tests com.dpm.pegdown.sensor.StationaryRideReplayTest
--rerun --info --console=plain.
For ZIPs containing multiple CSVs, also set PEGDOWN_STATIONARY_ENTRY to the
exact entry name, e.g. PegDown_sensor_diagnostics_20261006_100733.csv.

Portable tests: StationaryCorrectionTest, SensorProcessorTest, and the bundled
NovemberRideStartReplayTest method above. Only the full November checkpoint
method requires the external ZIP.

Validation: complete :app:testDebugUnitTest and :app:assembleDebug passed with
both November native replays enabled. No device tests or post-change live ride
have been performed. Install the freshly built app before recording the next
ride; start diagnostics before riding away.
