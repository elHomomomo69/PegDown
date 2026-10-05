# Offline experiment: gyro integration with GNSS-compensated gravity

## Scope

CompensatedImuLeanExperiment lives only in src/test. It is not wired to the
ride display, SensorProcessor, recording service or GPX export. It replaces neither
the running Android fusion nor its calibration. The GPS-only experiment remains
as a separate negative example: a straight trajectory does not establish zero bank.

## Physical model

1. Integrate all three body-axis gyroscope rates into a normalized quaternion.
2. Derive longitudinal acceleration from GNSS speed changes and lateral acceleration
   from average speed multiplied by course rate, including course wraparound.
3. Transform those accelerations into the mounted phone frame and subtract them
   from measured acceleration before using the remaining vector as a gravity reference.
4. Apply a limited tilt correction to the saved orientation at the GNSS interval
   midpoint. Propagate that correction through subsequent gyro states to the present.

The correction uses a six-second history. GNSS fixes are delivered in receipt order;
future fixes cannot influence earlier outputs. Poor speed/course accuracy, stale
fixes, network-provider locations, abrupt roll/yaw transitions and implausible
inferred gravity inhibit correction. Missing gyro intervals are reported rather
than extrapolated. This does not solve heading observability or estimate gyro bias.

## Mounting assumption

Synthetic tests use a phone whose screen faces rearward, tilted ten degrees from
vertical. The actual vehicle-forward axis is supplied to the estimator.

Real replays assume that negative screen-Z, projected onto the upright calibration
plane, points forward. Calibration determines upright tilt but cannot establish
mounting yaw relative to the motorcycle. An off-centre mounting direction violates
this assumption. The recorded comparison uses the app's existing XY lean projection;
the synthetic truth comparison uses bank about the supplied vehicle-forward axis.
The test-only MountLeanGeometry helper now exposes both calculations; replay
compares them with identical attitude input, timestamps and display smoothing.

## Known-truth tests

Sixteen prototype unit tests cover full three-axis integration, a long banked turn with gyro
bias and periodic acceleration vibration, a true eight-degree bank along a straight
trajectory, delayed GNSS at a turn exit, simultaneous acceleration and turning,
unusable course accuracy, gyro gaps, and replays of both recordings. Additional
cases diagnose invalid fixes, compare raw and averaged old data, and demonstrate
that discarding periodic gyro samples can alias vibration into constant false roll.
Further cases trace acceleration decomposition, perturb initial attitude, simulate
internally lagged course values, and check an optional course/gyro consistency gate.

The synthetic motion supplies known orientation and internally consistent GNSS and
accelerometer signals. Those cases prove the tested mathematics under those
assumptions. They do not establish accuracy with noisy GNSS derivatives, changing
mount alignment, vertical road motion, sensor temperature drift or real vibration.
The vibration test does not add vibration-induced gyro bias or saturation.

## Recorded inputs and time alignment

October 5 input: Desktop/PegDown/PegDown.zip, diagnostic CSV
PegDown_sensor_diagnostics_20261005_141356.csv. Native monotonic sensor and location
fix times are aligned using the session anchor 1791209637000 ms wall time and
247227441009158 ns elapsed realtime. Receipt time is approximated from logged wall
time with millisecond precision. GNSS UTC differs from phone wall time by about
0.8 seconds, so UTC string matching is not used for sensor/fix alignment.

Fixtures imu_gps_20261005_*.csv.gz.b64 contain Base64-encoded gzip text, split into
eight-second chunks. Gyro groups of three and accelerometer groups of six raw
samples are averaged causally, retaining the last sample timestamp and receipt.
Partial groups at chunk boundaries are dropped. Baseline displayed lean is retained
from one in six rotation callbacks. No accelerometer, gyro or location coordinates
are invented; coordinates are omitted. Averaging and timestamp assignment introduce
an approximation, and cannot preserve every high-frequency motion component.

| Excerpt | Phone wall-clock start UTC | Duration |
| --- | --- | ---: |
| left_exit | 14:18:31 | 24 s |
| right_exit | 14:20:50 | 24 s |
| following_left_exit | 14:21:04 | 24 s |
| fast_left_exit | 14:31:28 | 32 s |

October 4 input: restored Desktop/PegDown_sensor_diagnostics_20261004_144016.zip.
The four existing post_turn_left_imu_*.csv excerpts retain sparsely selected
IMU samples without averaging, approximately 15 Hz per stream. Their origin is
163368899839012 ns elapsed realtime. The new location fixture supplies matching
original fix times and approximate receipt times. Old IMU receipt times were not
retained and are approximated by sample times. This older replay has greater
sampling and vibration-aliasing uncertainty than the newer excerpts.

Each excerpt starts from its first recorded Android rotation quaternion, so it
inherits any existing initial attitude error. Short replays do not demonstrate
whole-ride drift performance. Neither recording contains independent chassis-bank
truth: smaller angles after a turn are not by themselves proof of higher accuracy.

## Measured results

Validation on 2026-10-05: twenty-nine prototype/replay tests, four mounting geometry,
eight GPS-only and four course-timing tests succeed with the original newer ZIP
configured (45 total). The default run without that archive reports 44 passed,
one explicitly skipped external replay, zero failed. :app:assembleDebug succeeds.
No device tests were run.

Passing assertions establish the following synthetic bounds:

- Ideal full-axis integration: maximum bank error below 0.000001 degrees.
- Long turn with bias and vibration: mean error below 1.5 degrees in the final
  15 seconds. The uncompensated negative control exceeds five degrees.
- A true eight-degree bank on a straight trajectory remains intact.
- Delayed turn exit: aligned peak below 1.5 degrees; the current-time negative
  control exceeds three degrees.
- Combined longitudinal acceleration and turning: final-ten-second mean error
  below one degree.

Captured synthetic metrics: long-turn mean bank error 0.219 degrees versus
13.906 degrees for the uncompensated control. Delayed turn-exit peak is below
1e-9 degrees with historical alignment versus 7.829 degrees for the current-time
control. These are model outcomes, not measured road accuracy.

Recorded excerpt results below are mean absolute displayed angles in selected
exit sections. They are output magnitude, not independently verified angle error.
Correction counts cover the entire excerpt; values are rounded.

| Section | Excerpt seconds | Recorded baseline | Prototype | Accepted corrections |
| --- | --- | ---: | ---: | ---: |
| Oct 4 left exit | 2-10 | 4.89 deg | 14.47 deg | 0 |
| Oct 5 left exit | 12-20 | 5.19 deg | 1.48 deg | 11 |
| Oct 5 right exit | 10-15 | 5.86 deg | 1.83 deg | 15 |
| Oct 5 following left exit | 10-19 | 4.15 deg | 1.38 deg | 15 |
| Oct 5 faster exit | 17-25 | 9.83 deg | 9.43 deg | 22 |

All five replays produce finite angles and no detected gyro gaps. The older
excerpt rejects ten attempted corrections and remains gyro-only, with substantially
larger displayed angles. The newer faster excerpt shows little reduction despite
accepted corrections elsewhere in its window. Those are unresolved failures.
No thresholds were adjusted to make those recorded cases appear successful.

## Full-rate follow-up and rejection diagnostics

The new imu_gps_20261004_left_exit_raw_0..11.csv.gz.b64 fixtures preserve all
1,404 gyroscope, 2,809 accelerometer and 1,404 rotation samples in the original
24-second interval, plus 15 location callbacks. The twelve approximately two-second
chunks are storage boundaries only: replay averaging crosses their boundaries.
The final chunk includes the subsequent GNSS fixes through second 20.12.
Coordinates are omitted.

Native event timestamps retain nanosecond precision relative to the same origin.
Receipt times are reconstructed from the original session anchor:
1791124816975 ms wall time and 162405110740861 ns elapsed realtime. This is the
original wall-clock receipt approximation, not a new monotonic receipt measurement.
The seed quaternion is identical to the older replay's first rotation sample.
Every gyro and accelerometer value is retained without pre-averaging.

Causal averaging is a separate controlled replay variant: groups of three gyro
samples and six acceleration samples are averaged, retaining the final sample's
event and receipt timestamps. Incomplete trailing groups are discarded.

| Oct 4 replay, seconds 2-10 | Mean absolute output | Accepted | Rejected |
| --- | ---: | ---: | ---: |
| Original sparse selection | 14.47 deg | 0 | 10 |
| Same sparse selection, original event/receipt times | 14.55 deg | 0 | 10 |
| All original samples | 5.61 deg | 7 | 3 |
| Causal averages | 5.67 deg | 6 | 4 |
| All original samples, gyro only | 7.28 deg | 0 | 0 |

The recorded app baseline remains 4.89 degrees for the original sparse observation
timestamps. Full-rate observations use a denser grid, so these are not identical
sample-by-sample comparisons. None of these magnitudes is known angle error.

The original sparse replay rejects nine intervals for yaw-rate range above
0.10 rad/s and one for roll rate above 0.25 rad/s. Full-rate replay rejects only
two yaw-changing intervals and one rolling interval, all before the excerpt's
zero-time marker. Seven later corrections are accepted. The averaged variant
adds one marginal yaw rejection at midpoint 0.98 seconds (range 0.1028 rad/s).

A second control identifies all 1,056 samples retained in the old fixture by
matching their original sensor values (or recorded displayed lean for rotation
rows). It substitutes their original monotonic event times and reconstructed
receipt times. The maximum difference from the old rounded time axis is 0.6604 ms.
Its mean output is 14.55 degrees with the same ten rejections. Correcting this
time approximation therefore does not resolve the old replay failure.

This establishes strong sensitivity to sparse sample selection and shows that
the earlier 14.47-degree result was not a valid assessment of full-rate fusion.
The vibration-aliasing unit test provides a known-motion example of this failure
mechanism. It does not establish the actual frequency or physical origin of the
recorded vibration. The restored sparse-selection, full-rate and averaged variants
now share the same event/receipt timing source.

The remaining 5.6-degree output is unresolved and is not an improvement over the
app's recorded baseline. The seven corrections help relative to the full-rate
gyro-only control, but that is not proof of correct chassis angle.

Decision logging records every received fix as accepted, rejected, ignored or
waiting for usable course, including rejection-specific roll/yaw limits, gravity
magnitude and tilt innovation where available. It changes no correction threshold.
Existing accepted/rejected counters retain their previous meaning.

In the Oct 5 faster window, corrections are accepted at midpoints 17.30, 18.30,
19.30 and 20.30 seconds. Roll gates reject midpoints 21.30 and 23.30 seconds
(0.497 and 0.695 rad/s). Corrections at 22.30 and 24.30 seconds are accepted,
with full tilt innovations 7.61 and 13.14 degrees. Therefore absent GPS correction
alone cannot explain the weak result in this window. These innovations combine
pitch and roll and must not be interpreted as bank error.

## Mounting-axis follow-up

MountLeanGeometry compares the existing device-XY angle with vehicle-axis bank.
For normalized calibration up u, supplied vehicle-forward axis f perpendicular to
u, and current estimated up g:

- r = normalize(f cross u)
- vehicle-axis bank = atan2(-g dot r, g dot u)
- estimated pitch = asin(normalize(g) dot f)
- screen tilt at calibration = asin(u.z)

This is yaw-pitch-roll geometry about the vehicle axes; it is not the angle of
a single-axis quaternion twist. The forward axis must actually match the vehicle.
A centered rear-facing screen permits the projection of negative device-Z onto
the calibration horizontal plane as an assumption, not as a measured mount heading.

Four geometry tests cover 3,600 combinations of screen tilt, portrait/landscape
orientation, supplied mounting yaw, bank, vehicle pitch and world heading.
With the actual forward axis supplied, bank and pitch agree with known truth.
For a centered screen tilted 21.5 degrees, a true 30-degree bank appears as
31.82 degrees in the old XY projection. Pure pitch with zero bank does not create
a bank offset in either method when the mount heading is centered.

The calibration gravity vectors yield screen-normal elevations of 21.4607 degrees
(Oct 4) and 21.9473 degrees (Oct 5), indicating screen tilt to the vertical plane
at calibration. This is not a measurement of mounting yaw, and relies on the
recorded orientation estimate. It need not equal the rider's approximate physical
mounting-angle description.

### Same Android attitude, different angle extraction

Both original ZIP CSVs were streamed at their full rotation-vector callback rate.
The normalized quaternion supplies g = inverse(q) rotated world-Z. Two parallel
display filters use the app's 15-percent EMA, 0.20-degree deadband and 65-degree
clamp; their only difference is projected XY versus assumed vehicle-axis extraction.
They start at the excerpt's first sample; evaluation starts at least eight seconds
later, after this filter initialization has settled.

The reconstructed XY filter agrees with recorded diagnostic lean to mean absolute
differences below 0.0000003 degrees in every evaluation window. This establishes
that the native replay reproduces the recorded angle pipeline for these sections.

| Native Android output, selected section | XY magnitude | Assumed-axis bank magnitude | Mean absolute extraction difference |
| --- | ---: | ---: | ---: |
| Oct 4 left exit, seconds 2-10 | 4.890 deg | 4.659 deg | 0.231 deg |
| Oct 5 left exit, seconds 12-20 | 5.151 deg | 5.103 deg | 0.051 deg |
| Oct 5 right exit, seconds 10-15 | 5.873 deg | 5.709 deg | 0.163 deg |
| Oct 5 following left exit, seconds 10-19 | 4.155 deg | 3.824 deg | 0.331 deg |
| Oct 5 faster exit, seconds 17-25 | 9.842 deg | 9.337 deg | 0.505 deg |

These native comparisons use all original callbacks; earlier fixture tables use
retained observation timestamps and therefore differ slightly in their means.
The full-rate native faster window has a maximum extraction difference of
1.5605 degrees. These are estimated angle magnitudes, not error against chassis truth.

### Same custom fused attitude, different angle extraction

The Kotlin prototype replay also keeps separate, equally smoothed XY and axis
outputs without changing its fusion, correction limits or input data.

| Prototype section | XY magnitude | Assumed-axis bank magnitude |
| --- | ---: | ---: |
| Oct 4 full-rate left exit | 5.613 deg | 5.635 deg |
| Oct 4 causally averaged left exit | 5.673 deg | 5.581 deg |
| Oct 5 left exit | 1.480 deg | 1.383 deg |
| Oct 5 right exit | 1.835 deg | 1.761 deg |
| Oct 5 following left exit | 1.384 deg | 1.278 deg |
| Oct 5 faster exit | 9.433 deg | 8.999 deg |

Correcting this centered mounting geometry does not explain the remaining
five-to-six-degree output in the complete old replay, or most of the faster-window
output. It changes angular gain but does not remove the residual attitude offset.

### Unresolved mounting yaw

A separate known-truth counterexample mounts the phone 20 degrees off the vehicle
heading and pitches an actually zero-bank vehicle by 10 degrees. Its upright
calibration up is identical to that of a centered mount. The screen-derived
forward assumption then reports -3.45 degrees of bank (old XY -3.48), while using
the known actual forward axis reports zero. This demonstrates an unobservable
mount-heading degree of freedom in upright calibration alone. It does not show
that the user's mounting has this yaw error; existing calibration vectors cannot
prove or exclude it.

## Acceleration decomposition and initial-state follow-up

Each attempted gravity correction now records measured acceleration, subtracted
vehicle motion, inferred gravity, GNSS and mean gyro course rates, historical
predicted/inferred bank and pitch, intended gain, and current bank before/after
accepted correction. Values remain test-only and introduce no production logging
or live angle change. Bank inferred from raw acceleration is an apparent-force
angle, not an independent chassis measurement.

### A concrete conflicting correction

In the newer excerpt at midpoint 24.3039 seconds:

- GNSS-derived clockwise yaw rate: +0.1260 rad/s.
- Gyro-derived yaw rate over the same interval: approximately -0.0008 rad/s.
- GNSS lateral acceleration to subtract: +1.8148 m/s².
- Bank inferred from uncompensated acceleration: -0.340 degrees.
- Bank after subtracting estimated motion: +9.934 degrees.
- Historical predicted bank: -0.959 degrees.
- Current bank changes from -1.017 to +1.954 degrees when that correction arrives.

This demonstrates how an inconsistent motion estimate shifts the prototype's
gravity reference. Internal GNSS course filtering/lag is a possible explanation;
this recording alone does not identify the physical cause of the discrepancy.
Correctly aligning fix timestamps and applying correction to historical attitude
cannot account for an unknown lag already embedded in the course value.

The production app still uses Android GAME_ROTATION_VECTOR. It does not execute
this custom GNSS compensation, so this trace is not proof that the same GPS
correction causes the production app's residual.

### Known-truth delayed-course test and optional gate

A synthetic 20-degree turn returns to zero bank over 0.4 seconds. Fix timestamps
and speed stay current while bearing is deliberately taken from two seconds
earlier. Historical alignment alone then produces a false exit peak of 5.659
degrees. An optional course/gyro consistency gate reduces it to 1.151 degrees.

The test asserts a substantial relative reduction, not perfect recovery. The
initial proposed sub-one-degree expectation failed: smaller mismatches still fit
the gate's error allowance. That residual is documented rather than hidden by
claiming zero-error correction.

The gate is disabled by default (checkYawConsistency=false), exists only in
test sources, and rejects correction when:

abs(courseRate - meanGyroYawRate) > 3 * courseRateErrorScale + 0.02 rad/s

The course error scale combines the two declared bearing accuracy values divided
by their time interval. The multiplier and model allowance are engineering
assumptions, not a calibrated probability bound or thresholds fitted to the ride.
A positive-control test preserves a true steady 25-degree bank with consistent
inputs; the rule never sets bank to zero based on straight GPS course.

In the newer excerpt this gate rejects midpoints 22.3039 and 24.3039 seconds.
The first lies inside a banked turn. Blocking both updates changes later attitude;
preventing one suspicious correction is not sufficient evidence of overall benefit.

### Correcting interpretation of the earlier faster-window average

The earlier 17-25-second comparison includes the next banked turn. Native recorded
lean rises to approximately +30 degrees around seconds 22-23 and falls through
zero near second 24. Another negative-bank turn begins around second 27.5.
Consequently the earlier approximately nine-degree average cannot be interpreted
as a straight-riding offset, and the earlier statement of little overall benefit
in this excerpt was too broad.

A separate interval, seconds 25-27, lies between those two detected attitude
transitions. It is a diagnostic observation window, not a zero-bank ground-truth
label. The recorded app's mean absolute displayed angle there is 4.970 degrees.

| Prototype variant, seconds 25-27 | Mean absolute bank | Maximum absolute bank |
| --- | ---: | ---: |
| Complete compensation, original gate settings | 0.723 deg | 1.477 deg |
| No longitudinal subtraction | 0.677 deg | 1.327 deg |
| No lateral subtraction | 4.441 deg | 6.397 deg |
| Initial bank plus 5 degrees | 0.726 deg | 1.483 deg |
| Initial bank minus 5 degrees | 0.721 deg | 1.472 deg |
| Initial pitch plus 5 degrees | 0.723 deg | 1.477 deg |
| Optional course/gyro gate | 1.485 deg | 3.037 deg |

The optional gate worsens this output-magnitude comparison and remains disabled.
Removing longitudinal compensation has little effect in these selected sections;
removing lateral compensation substantially changes the newer transition.
Neither observation establishes actual chassis-angle accuracy.

### Older recording and inherited starting orientation

In the complete older replay, seconds 2-10 yield mean absolute vehicle-axis bank:

| Variant | Mean absolute bank |
| --- | ---: |
| Complete compensation | 5.635 deg |
| No longitudinal subtraction | 5.619 deg |
| No lateral subtraction | 5.147 deg |
| Initial bank plus 5 degrees | 6.911 deg |
| Initial bank minus 5 degrees | 4.382 deg |
| Initial pitch plus 5 degrees | 4.677 deg |
| Optional course/gyro gate | 5.635 deg |

These deliberately perturbed seeds are sensitivity controls, not revised
calibrations or estimates of the true starting angle. They show that the short
older replay retains some dependence on its inherited Android orientation.
At midpoint 2.98 seconds it predicts 16.32 degrees of pitch while corrected
acceleration suggests -0.41 degrees; the true pitch is unknown.

A known-truth ideal-motion test, initialized with five degrees of bank error,
converges to mean error 0.000034 degrees over the final ten seconds while the
vehicle remains banked at 25 degrees. The model can remove initial error under
consistent signals, but this does not resolve the recorded data disagreement.

## Continuous-history follow-up

The restored October 4 ZIP now supplies an 84-second continuous sensor replay,
including 60 seconds before the previously tested 24-second excerpt. Its monotonic
origin is 163302899839012 ns, exactly 66 seconds before the former origin.
Original event nanoseconds and the same session-derived receipt approximation are
preserved. GPS callbacks extend through second 86.12.

The eleven imu_gps_20261004_continuous_0..10.csv.gz.b64 fixtures contain:
1,637 three-sample gyro means, 1,637 six-sample accelerometer means,
819 retained rotation observations and 47 location callbacks. No coordinates
are included. Rotation observations additionally retain their source quaternion
for the controlled late restart. The seed is the first rotation vector at
second 0.013452343.

Averaging is causal and continuous over the entire source interval. Storage
boundaries do not restart averaging groups; incomplete final groups are omitted.
Gyro/accelerometer means retain the final source sample's timestamp. Values are
stored with round-trip double precision. This is an averaged-input experiment,
not a new full-rate replay. Data-integrity tests check decompression, consistent
metadata, exact row counts, unique event timestamps and sensor intervals below
65 ms across all fixture boundaries.

### Matched later observations, different preceding history

The continuous variant starts once at the beginning. A control restarts at
second 60.010 using that exact observation's Android rotation quaternion.
It receives only subsequent events and starts with empty filter histories.
Both variants then evaluate exactly the same retained observation timestamps
at seconds 68-76, corresponding to the older excerpt's seconds 2-10.

This comparison changes inherited orientation, stored sensor history and prior
GPS processing together. It does not isolate a single mathematical cause or
establish true motorcycle bank.

| Variant, matched seconds 68-76 | Mean absolute bank | Signed mean bank | Maximum absolute bank |
| --- | ---: | ---: | ---: |
| Continuous fusion | 2.314 deg | +2.194 deg | 4.357 deg |
| Restart at second 60 | 5.393 deg | +5.393 deg | 7.190 deg |
| Continuous seed bank plus 5 degrees | 2.314 deg | +2.194 deg | 4.357 deg |
| Continuous seed bank minus 5 degrees | 2.314 deg | +2.194 deg | 4.357 deg |
| Continuous seed pitch plus 5 degrees | 2.314 deg | +2.194 deg | 4.357 deg |
| Continuous gyro only | 5.566 deg | -5.566 deg | 9.076 deg |

The recorded app's mean absolute displayed angle on these same observations is
4.893 degrees. The matched mean absolute output difference between continuous
fusion and late restart is 3.199 degrees. The newly matched restart magnitude
differs from the earlier 5.581-degree averaged result because global averaging
groups and retained observation phases differ; use this paired comparison
rather than subtracting independently sampled results.

Continuous fusion accepts 30 and rejects nine corrections over the full source
interval; the late restart accepts six and rejects three in its shorter interval.
Both report zero gyro gaps. The continuous variant also rejects a changing-yaw
interval at midpoint 66.980 seconds; its changed orientation can change subsequent
gating as well as the angle itself.

The deliberate initial bank/pitch perturbations change the late continuous
mean magnitude by less than 0.000002 degrees. Thus those tested initial
perturbations have practically settled by this later window. This does not mean
all state errors are gone: gyro bias, model errors, calibration and mounting
assumptions can still produce residual error.

The residual 2.314-degree magnitude is smaller, but is not measured angle error.
At accepted midpoint 68.980 seconds predicted pitch is +10.186 degrees while
inferred acceleration-based pitch is -1.912 degrees. Later inferred pitch ranges
from +8.899 to -7.418 degrees. Actual pitch is unknown; the two reference paths
still disagree and should not be promoted as a validated live solution.

## Matched GNSS interval and acceleration-reference follow-up

A new test-only matchGpsInterval option compares the existing 300 ms midpoint
accelerometer reference with the complete interval between the two GNSS fixes.
It is disabled by default. Correction strength, quality weighting and transient
rejection thresholds remain unchanged.

### Reference calculation

Each acceleration sample is first transported from its recorded device pose
into the estimated midpoint device frame:

a_mid(t) = inverse(q_mid) rotated [q(t) rotated a_device(t)]

Only then is its piecewise-linear time integral divided by the GNSS interval
duration. Integration interpolates bracketed interval boundaries, weights actual
sample spacing and refuses extrapolation, gaps above 150 ms, reversed timestamps
or missing pose/acceleration coverage. All inputs must already be present at the
fix callback; this does not request future sensor samples.

An arithmetic average of rotating device-frame values would mix different axes.
Transport also depends on the existing attitude estimate, so it is not an
independent truth reference.

For interval matching, motion subtraction uses the endpoint velocity difference
in the endpoint courses' bisector frame rather than v * courseRate alone.
With signed clockwise course change delta and duration dt:

- longitudinal = (v_end - v_start) * cos(delta / 2) / dt
- lateral = (v_end + v_start) * sin(delta / 2) / dt

The existing midpoint estimate supplies the corresponding horizontal vehicle
directions. The endpoint course bisector can differ from actual course at the
temporal midpoint during nonlinear motion; GNSS course lag, heading error,
mount-heading assumptions and missing vertical motion still remain limitations.
Standing fixes retain the previous short-window correction.

### Known-truth tests

An ideal 40-second model varies true bank as 15 + 6 * sin(pi * t / 2) degrees
with GNSS fixes every two seconds. Motion remains within the existing gates.
Both variants accept 19 corrections; no threshold is relaxed.

Over seconds 10-40 the mean absolute bank error is 0.676093 degrees with the
midpoint reference and 0.000001709 degrees with the interval reference. The
complete-interval calculation removes this particular modelled temporal mismatch.
The near-zero result is ideal simulation accuracy, not achievable road accuracy.

A steady known 25-degree turn also remains correct while world heading rotates.
Additional integration tests cover irregular sample spacing, clipped endpoints
and refusal of missing coverage, excessive gaps and reversed times.

### Recorded comparisons

These are output magnitudes on exactly the same observation grids, not angle
errors against independent chassis truth. The newer excerpts still start from
their local Android orientation; only the older case uses the longer history.

| Section | Midpoint mean magnitude | Interval mean magnitude | Midpoint peak | Interval peak |
| --- | ---: | ---: | ---: | ---: |
| Oct 4 continuous, seconds 68-76 | 2.314 deg | 2.614 deg | 4.357 deg | 4.554 deg |
| Oct 5 left, seconds 12-20 | 1.383 deg | 0.894 deg | 3.815 deg | 4.616 deg |
| Oct 5 right, seconds 10-15 | 1.761 deg | 1.347 deg | 4.417 deg | 3.313 deg |
| Oct 5 following left, seconds 10-19 | 1.278 deg | 1.349 deg | 2.622 deg | 2.551 deg |
| Oct 5 between turns, seconds 25-27 | 0.723 deg | 0.694 deg | 1.477 deg | 1.366 deg |

Accepted/rejected correction totals are unchanged in each recorded pair and no
attempted correction lacks acceleration coverage. All variants report zero
gyro gaps. Smaller mean output can coexist with a larger peak: the newer left
case illustrates this, so its lower mean cannot be called a uniform improvement.

Interval matching fixes a demonstrable synthetic mismatch but does not explain
all recorded residuals or justify a production change. Both references remain
available as controls; the new option remains off.

## GPS course timing against gyroscope follow-up

CourseTimingExperiment compares GNSS interval course rates with time-integrated
gyro yaw rates, scanning shifts from -2.0 to +3.0 seconds in 0.1-second steps.
Positive lag means the GPS values match earlier gyro motion: GPS follows gyro.
This is a test-only timing analysis, not a fitted chassis-bank reference.

### New continuous input

Two longer sections of the original newer ZIP are streamed directly:

| Section | Phone wall-clock start UTC | Duration | Gyro bins | Location callbacks |
| --- | --- | ---: | ---: | ---: |
| turns | 14:17:31 | 300 s | 3,000 | 311 |
| faster | 14:30:28 | 240 s | 2,400 | 250 |

The nine course_timing_20261005_*.csv.gz.b64 fixture parts contain no coordinates.
Sensor and location times use original elapsed-realtime timestamps relative to
the same session anchor. No UTC-to-GNSS timestamp offset is fitted.

At each original gyro sample, the latest preceding GAME_ROTATION_VECTOR supplies
estimated world-up in device coordinates. Clockwise yaw rate is minus the dot
product of gyro and that up vector. Quaternion norm is accounted for; orientation
samples older than 50 ms are excluded. Rate and event time are then arithmetically
averaged in continuous 100 ms bins. These are reduced rate traces, not a full
sensor replay. Each part has 600 bins; binning never resets at storage boundaries.

A second trace projects gyro on fixed calibration-up instead. During bank it is
not true world-vertical yaw, but it checks whether the apparent lag depends
strongly on the changing Android orientation projection.

### Eligibility and independent temporal evaluation

Only GPS-provider fixes with speed/bearing present are retained. Endpoint quality
limits match the earlier course experiment: speed at least 8 m/s, bearing accuracy
at most 5 degrees, speed accuracy at most 1 m/s and position accuracy at most
15 m. Fix intervals must be 0.3-2.5 seconds. Excited course rates have magnitudes
between 0.025 and 0.8 rad/s.

Each lag is scored on exactly the same eligible GPS intervals. Shifted gyro
integrals need bracketed endpoints and reject gaps above 250 ms; any interval
missing coverage for one candidate is excluded for all candidates. At least
12 intervals and a GPS rate standard deviation of 0.015 rad/s are required.
These are analysis eligibility rules, not calibrated confidence bounds.

The lag minimizing rate RMSE is selected in the first temporal half and evaluated
unchanged in the later half. A three-second margin on each side of the half split
keeps their gyro support disjoint even for the extreme candidate shifts.
The all-section fit is descriptive; it is not the held-out validation.

Synthetic varying-rate data recovers deliberately inserted -0.7, 0.0, +1.2 and
+2.0-second delays with correlation above 0.99. Constant-rate data is correctly
reported as unable to identify delay. Missing gyro segments reduce the common
cohort equally for every candidate rather than changing candidate sample counts.

### Recorded results

RMSE values below are rate disagreement in rad/s, not bank-angle error.

| Section, Android-up projection | Best all-section lag | Unshifted RMSE | Best shifted RMSE | Correlation before | Correlation after |
| --- | ---: | ---: | ---: | ---: | ---: |
| turns, 124 eligible intervals | +0.6 s | 0.08411 | 0.04414 | 0.8313 | 0.9555 |
| faster, 136 eligible intervals | +0.7 s | 0.09647 | 0.05143 | 0.8551 | 0.9604 |

| Later temporal evaluation | Lag selected earlier | Later intervals | Unshifted RMSE | RMSE at earlier-selected lag | Correlation at earlier-selected lag |
| --- | ---: | ---: | ---: | ---: | ---: |
| turns | +0.6 s | 59 | 0.08555 | 0.04425 | 0.9575 |
| faster | +0.6 s | 71 | 0.11211 | 0.06009 | 0.9522 |

The later halves' own descriptive minima occur at +0.7 and +0.8 seconds.
They were not substituted into the earlier-selected held-out scores.
Fixed-calibration-up controls show all-section minima at +0.6 and +0.7 seconds
as well. Their later-half RMSE improves from 0.08380 to 0.03960 using the earlier
+0.6-second choice for turns, and from 0.10896 to 0.05779 using the earlier
+0.7-second choice for faster.

### Interpretation

These sections support an effective relative lag of approximately 0.6-0.8 seconds
between logged GNSS course changes and the gyro reference. The improvement persists
outside the fitting interval and under both projection choices.

This does not prove a fixed hardware delay or its physical cause. GPS course
filtering, phone orientation-reference error, vibration, mounting and differences
between vehicle heading and trajectory can all affect this comparison.
A 0.1-second scan step does not establish a 0.1-second confidence interval.
The remaining shifted rate disagreement is substantial.

No timestamps in recorded source data were rewritten. No fitted delay is applied
to the live app or the fusion prototype. The production GAME_ROTATION_VECTOR
pipeline does not use this custom GNSS subtraction, so this result is not by
itself an explanation of production angle drift.

## Fixed-lag motion-compensation follow-up

The next offline hypothesis fixes courseLagSeconds at 0.6 seconds before inspecting
bank magnitudes. This uses the earlier-selected rate-comparison lag; no alternative
delay is selected by searching for a smaller displayed bank. The default stays
zero and production code is unchanged.

### Causal historical alignment and speed assumption

Original fix timestamps, fix-age validity checks and callback arrival times remain
unchanged. Course values are assumed to describe an interval 0.6 seconds earlier.
The gyro gates, correction midpoint, pose history and acceleration-reference
window all refer to that shifted interval. The resulting past tilt correction
is propagated to later stored poses and the current estimate as before.

A course lag does not establish a speed lag. This experiment explicitly assumes
speed values are current at their native fix timestamps. It retains valid GPS
speed history and linearly interpolates speed at the shifted course endpoints;
lateral and longitudinal motion use those historical speeds. It refuses missing
brackets or speed gaps above 2.5 seconds instead of extrapolating. Gyro gaps and
invalid speed fixes clear the applicable history. Standing fixes keep their
original timing. This speed-timing assumption remains unverified for the real
phone; interpolation is also an approximation during nonlinear acceleration.

The option requires historical alignment, accepts only finite nonnegative lags
up to two seconds, and never reorders or rewrites source events.

### Known-truth controls

An ideal model varies true bank as 15 + 4 * sin(pi * t / 2) degrees with two-second
GPS fixes. Full-interval acceleration matching is enabled. All three controls
accept more than ten corrections under the unchanged motion gates.

| Synthetic condition, seconds 10-40 | Mean absolute bank error |
| --- | ---: |
| True 0.6-second bearing lag, ignored | 0.319549 deg |
| True 0.6-second bearing lag, compensated | 0.029586 deg |
| No true lag, incorrectly assuming 0.6 seconds | 0.281566 deg |

Thus correcting a real modelled lag helps, but introducing a lag where none exists
can harm the estimate. The residual in the aligned model is not presented as zero.

Initial attempts to demonstrate this on an abrupt exit failed their proposed
assertions: existing rolling gates already blocked most false updates.
A six-degree varying profile likewise gated out the wrongly shifted updates.
The final four-degree profile keeps motion within those same gates; assertions
also require accepted corrections in every control. No gate threshold or
error-reduction assertion was loosened. The abrupt-exit preservation case remains
as a separate passing regression.

A true 15-degree bank with speed increasing by 0.3 m/s each second, fresh speed
and delayed bearing yields mean bank error 0.001986 degrees under aligned timing.
Another test explicitly refuses missing historical speed. These are ideal-model
controls, not measured road-accuracy bounds.

### Recorded output comparisons

Values are mean absolute estimated vehicle-axis bank on the previously fixed
observation windows. Actual chassis bank is unknown. The October 4 application
is a transfer control: no 0.6-second lag was estimated for that recording.

| Section | Midpoint, no lag | Midpoint, 0.6 s | Full interval, no lag | Full interval, 0.6 s |
| --- | ---: | ---: | ---: | ---: |
| Oct 4 continuous, seconds 68-76 | 2.314 deg | 3.114 deg | 2.614 deg | 2.503 deg |
| Oct 5 left, seconds 12-20 | 1.383 deg | 1.350 deg | 0.894 deg | 0.932 deg |
| Oct 5 right, seconds 10-15 | 1.761 deg | 1.493 deg | 1.347 deg | 1.022 deg |
| Oct 5 following left, seconds 10-19 | 1.278 deg | 1.201 deg | 1.349 deg | 1.048 deg |
| Oct 5 between turns, seconds 25-27 | 0.723 deg | 1.746 deg | 0.694 deg | 1.188 deg |

| Full-interval variant | Unshifted peak | Shifted peak |
| --- | ---: | ---: |
| Oct 4 continuous | 4.554 deg | 4.198 deg |
| Oct 5 left | 4.616 deg | 4.482 deg |
| Oct 5 right | 3.313 deg | 2.679 deg |
| Oct 5 following left | 2.551 deg | 2.403 deg |
| Oct 5 between turns | 1.366 deg | 2.610 deg |

Correction counts and rejections change because gyro gates now examine earlier
motion. Each lagged replay skips one initial attempt for missing speed history.
All report zero gyro gaps. The bank comparison therefore includes changed
eligibility as well as changed timing.

The right and following-left windows lie in the later portion of the earlier
five-minute rate comparison. The first-left and faster between-turn windows are
within their rate-fitting portions; they are not independent held-out ride data.
The newer bank replays still start from each short excerpt's Android quaternion,
whereas only the older replay includes its additional 60-second history.

### Decision

The 0.6-second shift improves the newer right and following-left outputs but
worsens the previously small between-turn output and its peak. Midpoint correction
also worsens the older transfer control substantially. A rate-alignment improvement
does not translate into uniformly better bank estimates.

Keep the lag option disabled by default and the complete fusion prototype offline.
Do not pick a different lag solely to minimize these bank magnitudes. A longer
continuous newer sensor replay is needed to compare warm filter history and
timing together; the yaw-only timing fixtures cannot supply accelerometer fusion
for that task. Independent bank truth remains absent.

## Continuous newer fusion and matched-restart follow-up

The original newer ZIP is now streamed directly by an opt-in unit replay.
This avoids transferring the large source recording into many copied fixtures.
Only the selected windows are retained in memory; the ZIP and source CSV are
read-only. The test uses the explicit PEGDOWN_DIAGNOSTICS_ZIP environment variable
and the known Oct05 CSV entry. An absent variable skips this one external-data
test; an explicitly provided missing or incompatible archive fails.

### Inputs and source integrity

| Window | Phone wall-clock start UTC | Duration | Averaged gyro rows | Averaged acceleration rows | Retained rotation observations | Location callbacks |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| turns | 14:17:31 | 300 s | 5,850 | 5,850 | 2,926 | 311 |
| faster | 14:30:28 | 92 s | 1,794 | 1,794 | 898 | 96 |

Every three source gyro samples and every six accelerometer samples are
causally averaged without resetting groups within a window. Means use double
precision directly from the source, attached to the group's last event and
receipt timestamp. Incomplete final groups are omitted. Rotation observations
are retained one in six and preserve their source quaternion for local restarts.
These are averaged-input replays, not full-rate integration.

Native elapsed-realtime event timestamps and original wall-clock receipt offsets
use the established shared anchor; no UTC alignment or new lag is estimated here.
Both candidate variants receive the same source data and observation timestamps.

The eleven imu_gps_20261005_continuous_turns_0..10.csv.gz.b64 files archive only
the first 88 seconds, not the complete five-minute window. Their portable test
checks 1,716 gyro rows, 1,715 accelerometer rows, 858 rotation observations and
92 location callbacks, unique sensor event times and no mean-sample gaps above
65 ms. Compact stored means use nine significant digits; timestamps retain
nanosecond precision and coordinates are omitted. Selected rotation quaternions
near earlier restart checkpoints are retained.

The external test independently matches that entire 88-second subset against
the original source by sample type and monotonic time, checks receipt times,
and compares stored vectors within 1e-7 per component. This verifies both coverage
and the compact mean representation. Source-derived row counts replace the
initial incorrect manually predicted equal gyro/acceleration counts.

### Continuous state and local restart controls

Continuous fusion starts once at each longer window's first recorded quaternion.
Local controls restart using the source rotation quaternion just after seconds
60, 199 or 213. They then receive the same later events, with empty initial sensor
and speed histories. Evaluation grids are asserted identical for each matched pair.

| Observed section | Continuous-window seconds | Local restart near |
| --- | --- | --- |
| left | 72-80 | 60 s |
| right | 209-214 | 199 s |
| following left | 223-232 | 213 s |
| faster between turns | 85-87 | 60 s |

No timing parameter is optimized on these bank outputs. The two acceleration
references and previously chosen zero/0.6-second course-lag controls remain fixed.
There is still no independent measured chassis bank.

### Continuous outputs

All numbers below are mean absolute displayed angles, not verified bank error.

| Section | Recorded app baseline | Midpoint, no lag | Midpoint, 0.6 s | Full interval, no lag | Full interval, 0.6 s |
| --- | ---: | ---: | ---: | ---: | ---: |
| left | 5.192 deg | 0.939 deg | 1.012 deg | 0.851 deg | 0.782 deg |
| right | 5.878 deg | 1.903 deg | 2.200 deg | 1.726 deg | 1.330 deg |
| following left | 4.140 deg | 1.277 deg | 2.098 deg | 1.371 deg | 1.565 deg |
| faster between turns | 4.970 deg | 0.597 deg | 1.291 deg | 0.687 deg | 1.199 deg |

| Continuous full-interval variant | Peak without lag | Peak with 0.6-second lag |
| --- | ---: | ---: |
| left | 3.614 deg | 4.021 deg |
| right | 4.293 deg | 3.178 deg |
| following left | 2.522 deg | 2.601 deg |
| faster between turns | 1.345 deg | 2.651 deg |

The left mean falls slightly with the lag hypothesis while its peak grows.
Right improves in both metrics. Following left and the between-turn section
worsen in both metrics. Lower recorded-output magnitude is not independent
evidence of higher angle accuracy.

### Same observations with restarted versus continuous history

| Full-interval section | Continuous, no lag | Restarted, no lag | Continuous, 0.6 s | Restarted, 0.6 s |
| --- | ---: | ---: | ---: | ---: |
| left | 0.851 deg | 0.956 deg | 0.782 deg | 1.089 deg |
| right | 1.726 deg | 1.468 deg | 1.330 deg | 1.245 deg |
| following left | 1.371 deg | 1.400 deg | 1.565 deg | 1.123 deg |
| faster between turns | 0.687 deg | 0.687 deg | 1.199 deg | 1.199 deg |

History changes some newer results, especially the lagged following-left section.
For the faster between-turn section, continuous and restarted full-interval
means differ by less than 0.0002 degrees. Startup history therefore does not
explain the lag variant's larger between-turn magnitude in this comparison.

Total corrections cover whole source windows, not only the observation intervals.
Over the 300-second window the continuous full-interval unshifted variant accepts
153 and rejects 86 corrections; the lagged variant accepts 152 and rejects 82.
Over the 92-second faster window their totals are 70/19 and 67/21 respectively.
All continuous and matched-restart runs report zero gyro gaps and finite angles.

### Validation and decision

With PEGDOWN_DIAGNOSTICS_ZIP set, all 45 selected tests succeed, including the
external whole-window replay and original-source integrity comparison.
Without the variable, the standard run reports 44 passed, one explicitly skipped,
zero failed. :app:assembleDebug succeeds. No device tests were run.

Longer state history does not make fixed course-lag compensation uniformly useful.
Keep the lag hypothesis disabled and the custom fusion offline. The unshifted
prototypes remain candidates, not validated replacements for live Android fusion.
The remaining disagreement should be investigated at individual accepted gravity
corrections, including pitch innovation and course-derived motion consistency;
do not infer correct bank by forcing straight-trajectory observations to zero.

## Individual correction attribution on continuous newer inputs

The replay now records bank immediately before and after every gyro and GPS
callback, using receipt time for application order. A correction's historical
reference time is logged separately from its GPS fix time and receipt time.
Every replay asserts that signed gyro changes plus signed correction changes
equal final minus initial unfiltered bank (tolerance 1e-8 degrees). Accepted
correction traces must also match those immediate before/after values.

These are changes in the prototype's estimated bank, not measurements of true
bank or independent additive sensor errors. The displayed EMA is not included
in the instantaneous balance. Different corrections change the orientation
subsequently used for gyro integration, so the two terms cannot be transferred
unchanged between variants.

Selection is fixed: all accepted corrections from five seconds before each
following-left/between-turn observation through its end are reported. No
correction is selected by the size of its output.

| Receipt-time interval | Full-interval variant | Initial bank | Final bank | Sum gyro changes | Sum correction changes |
| --- | --- | ---: | ---: | ---: | ---: |
| following left, 218-232 s | no lag | -29.467 | 0.971 | 33.016 | -2.578 |
| following left, 218-232 s | lag 0.6 s | -31.618 | 0.959 | 33.271 | -0.694 |
| faster, 80-87 s | no lag | 2.169 | 0.226 | -2.172 | 0.229 |
| faster, 80-87 s | lag 0.6 s | 2.114 | -0.598 | -2.463 | -0.250 |

The sums can hide large opposing updates. In the unshifted faster replay:

- Receipt 82.924 s: correction changes current bank by -1.853 degrees.
- Receipt 84.922 s: correction changes -2.082 to +1.261 degrees,
  a +3.343-degree jump. Its historical reference is 84.304 s.
- Receipt 86.935 s: correction changes current bank by -1.034 degrees.

At the +3.343-degree update, GPS course rate is +0.1260 rad/s while mean gyro
yaw rate is -0.00114 rad/s. The model subtracts +1.814 m/s2 lateral acceleration.
Historical predicted bank is -2.021 degrees, inferred gravity bank +10.232
degrees, and inferred-minus-predicted pitch is -6.309 degrees. Gain is 0.274.
This exposes a model/input inconsistency at an accepted update. It does not
prove which source gives true chassis bank, nor explain the production Android
fusion causally.

The shifted faster replay has no accepted update at receipts 82.924 or 84.922 s;
its remaining updates in this interval are -0.027, +0.510 and -0.734 degrees.
Avoiding the unshifted jump still does not improve its previously reported
85-87-second displayed magnitude. Neither net correction sums nor individual
jumps justify a fixed timing change or a gyro-only replacement.

Following-left accepted updates also show pitch discrepancies, including
+10.215 degrees in the shifted variant at receipt 222.917 s. Possible vertical
motion, mounting assumptions and acceleration compensation remain unresolved.
A pitch discrepancy alone cannot identify a bad update.

Validation: all 45 selected tests, including the external ZIP replay, pass
with the new attribution assertions; :app:assembleDebug succeeds. No device
tests were run. No production code or filter parameters
were changed. The subsequent fixed consistency-rule evaluation is documented
below; no threshold was tuned to force real observation windows to zero.

## Fixed course/gyro consistency rule: follow-up

The existing optional rule is evaluated unchanged, before examining these new
outputs: reject moving corrections when absolute course-rate minus mean
gyro-yaw-rate exceeds 3 * declared course-rate error scale + 0.02 rad/s.
Whole-interval acceleration matching is used and course lag remains zero.
No threshold is fitted to recorded bank magnitude. This is an engineering
consistency check, not a calibrated probability of correctness.

### Known-angle tests

A straight synthetic trajectory retains a real 8-degree bank. Starting at
15 seconds, its reported GPS bearing falsely rotates at 0.12 rad/s while
gyroscope and physical acceleration remain consistent with the actual motion.

- Unchecked mean bank error after 25 seconds: 12.599 degrees.
- Checked mean bank error: 0.000 degrees; 24 mismatch rejections.
- At least ten earlier valid corrections remain accepted.
- Valid constant -25/+25-degree turns with a 3-degree initial bank error
  retain 44 accepted corrections each. Late mean errors are approximately
  0.000021/0.000012 degrees.

These tests cover this specified fault and ideal motion. They do not establish
real-world calibration, heading observability or immunity to gyro bias.

### Both recordings, continuous histories

Numbers are mean absolute displayed bank; true chassis bank is unknown.

| Observation | Rule off | Rule on | Peak off | Peak on |
| --- | ---: | ---: | ---: | ---: |
| Older left, 68-76 s | 2.614 | 2.614 | 4.554 | 4.554 |
| Newer left, 72-80 s | 0.851 | 0.851 | 3.614 | 3.614 |
| Newer right, 209-214 s | 1.726 | 1.535 | 4.293 | 3.751 |
| Newer following left, 223-232 s | 1.371 | 1.186 | 2.522 | 2.383 |
| Newer faster, 85-87 s | 0.687 | 1.441 | 1.345 | 3.035 |

The older full-interval run accepts 30 corrections with either variant and
rejects none for course/gyro mismatch. Newer 300-second accepts fall from 153
to 147; faster-window accepts fall from 70 to 68. All report zero gyro gaps.
Matched faster local restart still produces a mean of 1.440 degrees with the
rule, so preceding history does not explain its larger displayed magnitude.

The rule detects a known synthetic faulty input but does not select a uniformly
more accurate real estimate. The larger faster output cannot independently
label the rule wrong, just as a smaller output cannot validate it. Neither
source is a trusted bank reference, and accepting/rejecting corrections also
changes subsequent state evolution.

Keep the consistency option disabled by default and the fusion prototype
offline. Next use a controlled independent bank reference for validation.
Before another ride, static known-angle checks can constrain mounting geometry
and sign; dynamic reference footage or another independent measurement is needed
to label the turn exits. More thresholds fitted against zero in these same
recordings would not resolve the missing reference.

Validation: all 48 selected tests pass with the external ZIP replay enabled;
:app:assembleDebug succeeds. Three added regression tests plus existing
whole-window comparisons cover this follow-up. No device tests were run.
Production code and gate constants unchanged.

## Why the faster exit retains bank after mismatch rejection

A diagnostic ablation now omits selected orientation updates while still
processing every GPS fix, speed history and sensor sample. It does not drop GPS
messages or change subsequent interval endpoints. Holdouts are explicitly logged
as DIAGNOSTIC_HOLDOUT rather than ordinary confidence rejection.

The two previously investigated updates are selected by their receipt windows,
82-83 s and 84-85 s, without changing gains or thresholds. Their historical
reference times are 82.303854342 and 84.303865342 s. Four whole-window replays
apply both updates, omit the first, omit the second, or omit both.

| Variant | Mean absolute display, 85-87 s | Signed mean | Peak absolute |
| --- | ---: | ---: | ---: |
| Both applied | 0.687 | -0.088 | 1.345 |
| Omit 82.924 s update only | 1.019 | 1.001 | 2.004 |
| Omit 84.922 s update only | 2.899 | -2.899 | 4.733 |
| Omit both | 1.441 | -1.441 | 3.035 |
| Unchanged consistency gate | 1.441 | -1.441 | 3.035 |

The gate rejects exactly these two updates in the full 92-second replay.
Omitting both yields identical displayed bank to the gated replay at every
observation (asserted within 1e-9 degrees), not merely the same summary.
All variants have identical observations before 82.9 s and zero gyro gaps.

### Estimate evolution with both updates omitted

This table uses unfiltered bank and receipt-time bins, not the displayed EMA.

| Receipt seconds | Start bank | End bank | Gyro change | Correction change |
| --- | ---: | ---: | ---: | ---: |
| 82-83 | 29.179 | 29.540 | 0.361 | 0.000 |
| 83-84 | 29.540 | 1.190 | -28.350 | 0.000 |
| 84-85 | 1.190 | -0.985 | -2.176 | 0.000 |
| 85-86 | -0.985 | -1.556 | -1.377 | 0.807 |
| 86-87 | -1.556 | -0.672 | 1.578 | -0.695 |

Thus the negative estimate arises along gyro propagation even after the two
identified corrections are withheld. It is not introduced solely by a remaining
accepted correction in the 84-85-second bin. Later gravity updates and continued
gyro motion both change it.

The ungated replay applies a -1.853-degree update during the turn and a
+3.343-degree update at its exit. Its lower between-turn magnitude is partly
the result of opposing corrections and cannot establish accuracy. Omitting only
one update changes both subsequent state and later correction sizes.

This establishes the causal behavior of this offline estimator on these inputs.
It does not establish that the observed gyro change is false motion, quantify
true motorcycle bank, or identify the mechanism inside Android's live fusion.
Possible gyro bias, mounting geometry, initial orientation and motion-model
errors remain distinguishable hypotheses, not proven explanations.

The stationary-bias and mounting-axis checks are documented below. Neither
supports a fixed live correction yet. Do not force output to zero from straight
GPS trajectory or enable the consistency gate from these magnitudes.

Validation: all 48 selected tests pass with the external ZIP enabled, including
holdout identity and unchanged-prefix assertions; :app:assembleDebug succeeds.
No device tests were run. Production code remains unchanged.

## Stationary gyro candidate and mounting-axis sensitivity

These offline checks use the existing recording; no camera or new ride was
required. No compensation values were transferred into production.

### Gyro bias candidate

The original newer CSV is streamed before the first continuous turn window:
214 one-second receipt-time bins. Each bin requires at least 40 gyro and 80
accelerometer samples spanning at least 0.8 seconds, a GPS speed observation
at or below 0.4 m/s, mean gyro magnitude below 0.05 rad/s, gyro vector standard
deviation below 0.015 rad/s, mean acceleration magnitude within 0.25 m/s2 of
9.80665 and acceleration vector standard deviation below 0.2 m/s2.
Three consecutive qualifying bins would supply a sample-weighted candidate
bias, selected chronologically rather than fitted to lean outputs.

No bin qualifies. Exclusion counts overlap:

| Criterion | Excluded bins |
| --- | ---: |
| GPS speed above 0.4 m/s | 200 |
| No GPS observation in the bin | 14 |
| Gyro variation too high | 214 |
| Acceleration variation too high | 214 |
| Mean gyro magnitude too high | 131 |
| Acceleration magnitude outside allowance | 26 |

There is no supported bias estimate from this pre-window interval, so no bias
subtraction replay is performed. This does not establish that gyro bias is zero
or that the device never stopped: receipt-bin coverage, vibration and the chosen
conservative criteria limit this method. Criteria are not relaxed until a
desired angle is obtained. Even passing them would only identify a candidate;
slow actual rotation can look like a constant gyro bias.

A synthetic test confirms a known quiet gyro offset is recovered and that
moving GPS, gyro vibration, accelerometer vibration, missing GPS and empty
sensor coverage are excluded.

For a future recording, a feasible additional reference is 20-30 seconds with
the mounted phone completely still, engine off, before calibration/ride start.
This requires neither a camera nor an external IMU. Repeat at the end before
removing the phone. It constrains gyro bias, not absolute dynamic chassis bank.

### Mount yaw sensitivity

Reference gravity and original seed orientation are retained. Only the assumed
vehicle-forward axis is rotated about calibration-up by predefined -10, -5,
0, +5 and +10 degrees. This tests unknown mounting yaw, not the already measured
screen tilt. It changes bank extraction, acceleration compensation and motion
gates together. The unchanged course/gyro check and full-interval matching are
used in every variant.

Mean absolute displayed bank, with unknown actual chassis bank:

| Observation | -10 deg yaw | -5 deg | 0 deg | +5 deg | +10 deg |
| --- | ---: | ---: | ---: | ---: | ---: |
| Older left, 68-76 s | 3.609 | 3.112 | 2.614 | 2.110 | 1.603 |
| Newer left, 72-80 s | 1.093 | 0.830 | 0.851 | 1.063 | 1.365 |
| Newer right, 209-214 s | 2.165 | 1.844 | 1.535 | 1.260 | 1.080 |
| Newer following left, 223-232 s | 1.059 | 1.266 | 1.186 | 1.351 | 1.506 |
| Newer faster, 85-87 s | 1.793 | 1.623 | 1.441 | 1.263 | 1.188 |

Axis assumptions matter, but no yaw setting consistently decreases all newer
outputs. Smaller output cannot identify the true mounting axis. The faster
residual also remains across the tested range. No mounting angle is selected
from these magnitudes; default yaw remains zero in the experiments.

Next investigate whether low-yaw acceleration and braking segments provide a
consistent vehicle-forward direction independently of minimizing bank output.
The completed follow-up below finds insufficient and inconsistent direction
evidence, including across chronological halves. The bias hypothesis remains
unresolved until a usable rest interval is available.

Validation: all 50 selected tests pass with the external ZIP replay enabled;
:app:assembleDebug succeeds. Added tests cover quiet-bin selection and older
mount-axis sensitivity. No device tests were run. Production measurement and
calibration remain unchanged.

## Forward-axis evidence from acceleration and braking

A test-only diagnostic regresses raw, time-averaged accelerometer vectors on
GPS speed change. A fitted intercept absorbs constant gravity/constant offsets;
the fitted slope is projected onto the plane perpendicular to calibration-up.
Yaw is reported relative to the assumed forward/right basis. The sign of this
yaw uses forward cross up as right, and is distinct from the earlier quaternion
mountYawDegrees sensitivity convention.

No Android orientation estimate or candidate bank output is used as the fit
target. Gravity is assumed sufficiently stable within selected intervals.
Pitch changes, suspension motion, slopes, vibration and GPS speed timing/error
can still confound the inferred slope. In particular, an acceleration-correlated
transverse force can give a perfect fit to a wrong axis.

### Predetermined selection

Adjacent GPS intervals must be 0.5-1.5 seconds with both endpoint speeds
8-80 m/s, speed accuracy at most 0.5 m/s, bearing accuracy at most 5 degrees,
position accuracy at most 15 metres and receipt age 0-2.5 seconds.
Absolute speed-derived acceleration must be 0.5-3 m/s2 and course rate at
most 0.02 rad/s. Gyro coverage requires at least four samples spanning 70%
of the interval, mean rate magnitude at most 0.08 rad/s and peak at most
0.3 rad/s. Accelerometer integration uses bracketed endpoints and the existing
150 ms maximum sample-gap rule.

A regression requires at least four samples, speed-derived acceleration variance
at least 0.05 (m/s2)^2 and a nontrivial planar slope. These are diagnostic
selection rules, not calibrated confidence probabilities. They were not relaxed
after inspecting the resulting axes.

### Known-input checks

New synthetic checks recover the same known direction independently from
acceleration and braking despite a constant additive acceleration offset.
Insufficient samples and no acceleration variation produce no fit.

A second check adds a transverse term equal to 0.3 times longitudinal acceleration.
The regression then reports a wrong axis by 16.699 degrees with R2 exactly 1.
Thus high correlation alone is not sufficient to validate a mounting axis.

### Recorded evidence

The original newer ZIP is now streamed through the end of its CSV for this
axis diagnostic. Existing bank replays remain restricted to their established
windows; no extra bank fitting is performed.

| Input | GPS intervals examined | Selected acceleration | Selected braking |
| --- | ---: | ---: | ---: |
| Complete newer recording | 2136 | 2 | 23 |
| Newer 300-second turns window | 310 | 0 | 3 |
| Newer 92-second faster window | 95 | 0 | 1 |
| Older portable 84-second window | 46 | 0 | 0 |

In the full newer recording, first-failing exclusions are 539 for fix/interval
quality, 1358 for motion selection and 214 for gyro selection. The older window
does not meet the stricter fix/interval selection; no claim is made that its
GPS fixes are generally invalid.

| Complete newer subset | Samples | Fitted yaw | Planar R2 |
| --- | ---: | ---: | ---: |
| All qualifying intervals | 25 | 3.971 deg | 0.207 |
| Acceleration only | 2 | no fit | no fit |
| Braking only | 23 | 23.505 deg | 0.031 |
| Earlier chronological half | 11 | 3.702 deg | 0.298 |
| Later chronological half | 14 | 18.765 deg | 0.112 |

The halves are divided at the temporal midpoint of the source recording,
not at a boundary chosen to improve the fit. Later-half evaluation is only a
diagnostic comparison; no inferred axis is installed or used to adjust bank.

These outputs do not constrain mounting yaw reliably: acceleration evidence is
insufficient, braking correlation is weak, and the chronological halves disagree
by approximately 15 degrees. The pooled apparent 3.971-degree yaw must not be
treated as a measured mounting correction.

### Decision and practical next recording

Leave mounting calibration and live fusion unchanged. Existing recordings do
not support either a fixed gyro-bias subtraction or automatic yaw calibration.

Without a camera or angle scale, the next useful recording can still add:

1. Mounted phone motionless for 20-30 seconds with engine off before calibration.
2. Several distinct gentle acceleration and braking phases on a straight
   section, interspersed with steady-speed phases; retain the same mounting.
3. Repeat those phases later to provide separate fitting and checking data.
4. End with another 20-30 seconds motionless before taking the phone off.

This is a protocol for bias and axis identifiability, not an absolute bank
reference. No intentionally larger lean angle or faster curve is required for
these checks. Keep the full diagnostics ZIP and its GPX.

Validation: all 53 selected tests pass with the external original ZIP enabled;
:app:assembleDebug succeeds. Three new tests cover signed direction recovery,
misleading correlated contamination and older-recording selection. No device
tests were run. Production code remains unchanged.

## Decision and next checks

Keep this prototype offline. The older 14-degree failure was strongly inflated by
the sparse replay; its residual with complete data and the newer faster section
do not support a reliable production solution. The earlier faster-window mean
must not be treated as a straight-riding offset; the separately evaluated newer
between-turn section is promising. Mount heading remains an assumption and the
older residual persists. The longer matched replay now shows a substantial effect
of preceding history, with tested initial perturbations settled by the observation
window. Matching GNSS and acceleration intervals removes a known synthetic
failure but produces mixed recorded results, including a larger newer-left peak.
The longer course-timing comparison now supports an effective 0.6-0.8-second
GPS-relative lag across fitting and later intervals. Testing the preselected
0.6-second timing hypothesis improves some bank outputs but worsens another
between-turn section. The completed continuous newer replay confirms that longer
history does not remove this mixed result; the faster between-turn comparison is
almost unchanged by a local restart. Individual accepted gravity corrections
are now attributed above. The unchanged consistency rule passes known-fault
synthetic checks but produces mixed real outputs on both rides. The faster
holdout replay now isolates the two gate rejections and subsequent gyro
propagation. The pre-window recording supplies no qualifying rest interval,
and mounting-axis sensitivity produces mixed outputs. Longitudinal regression
on the full newer recording also supplies inconsistent, weak axis evidence.
A recording with rest intervals and repeated straight acceleration/braking
phases is the next useful input for bias and mounting-axis identification.
Independent bank references remain needed for absolute accuracy;
do not fit real bank to zero.
Do not hard-code the fitted lag into production based on this recording.
Independent bank references remain necessary; straight GPS course cannot provide
those labels.

## Vergleichsprotokoll für die nächste Messung

Status: vorbereitet, noch nicht durchgeführt. Es gibt weiterhin keine
unabhängigen Winkelwerte zu den vorhandenen Fahrten. Für diese Vorbereitung
wurden ausschließlich Dokumentation und keine Messalgorithmen geändert.

### 1. Bekannte Winkel im Stand

Zunächst kann eine mechanische Vorrichtung mit Winkelskala und Lot genutzt
werden. Die Drehachse entspricht der Fahrzeug-Längsachse, nicht einfach einer
beliebigen Kante des Handys. Das Handy bleibt während der gesamten Serie in
derselben Halterung, einschließlich seiner vorhandenen Neigung zum Fahrer.
Diese Serie prüft Geometrie, Vorzeichen und Rückkehr zum Nullpunkt; sie
bildet die Beschleunigungen einer Motorradkurve nicht nach.

1. App-Version beziehungsweise Commit, Handy-Ausrichtung und Montage
   fotografieren. Insbesondere eine seitliche Verdrehung zur Fahrtrichtung
   dokumentieren; der aufrechte Schwerkraftvektor bestimmt diese nicht.
2. Diagnoseaufzeichnung starten. Vorrichtung mechanisch aufrecht ausrichten
   und einmal kalibrieren. Nicht zwischen den Winkeln nachkalibrieren.
3. Jeden Zielwinkel zehn Sekunden halten, Übergänge ebenfalls aufzeichnen.
   Reihenfolge: 0, links 10, 0, rechts 10, 0, links 20, 0, rechts 20, 0 Grad.
   Zielwerte von der mechanischen Referenz ablesen, nicht aus der App.
4. Dieselbe Serie ein zweites Mal ohne neue Kalibrierung durchführen.
5. Für jeden Abschnitt Referenzwinkel, erreichbare Ablesegenauigkeit,
   Beginn/Ende und angezeigten Winkel notieren. Linke/rechte Seite explizit
   angeben, damit eine Vorzeichenkonvention nicht vorausgesetzt wird.

Die Halterung kann für diesen ersten Test separat in der Vorrichtung sitzen.
Damit ist die wirkliche Fahrzeugmontage noch nicht validiert. Eine Referenz
am Motorrad muss relativ zu einer eindeutig definierten Rahmenachse gemessen
werden; Seitenständer und Lenkerstellung liefern keinen bekannten Rollwinkel.

### 2. Dynamischer Vergleich

Benötigt wird eine unabhängig ausgewertete Referenz für die tatsächliche
Motorradlage. Eine weitere App auf demselben Handy, der Android-Rotationsvektor
oder die GPS-Kurvenform sind dafür keine unabhängige Referenz.

Eine ortsfeste Kamera kann einzelne Durchfahrtszeitpunkte erfassen:

- Im Bild eine Lotrichtung festhalten und Kameraneigung bestimmen.
- Am Motorrad eine zur definierten Fahrzeug-Hochachse parallele sichtbare
  Referenz verwenden. Fahreroberkörper oder Vorderrad sind dafür ungeeignet.
- Den Winkel nur bei annähernd frontaler oder rückwärtiger Sicht auswerten.
  Perspektive, Kameraposition und Bildauflösung als Unsicherheit dokumentieren.
- Für zeitliche Zuordnung Aufnahme und Diagnoseprotokoll vor und nach dem
  Versuch mit einem gemeinsamen erkennbaren Ereignis synchronisieren.
  Beispielsweise zeigt ein Video eine kurze markante Bewegung der montierten
  Halterung vor der anschließenden Kalibrierung und nochmals nach Fahrtende.
  Beide Ereignisse müssen im Sensorprotokoll identifizierbar sein.
- Links- und Rechtsdurchfahrten sowie aufgerichtete Durchfahrten nach der
  Kurve aufnehmen. Für den gemeldeten Nachlauf zusätzlich Referenzpunkte
  wenige Sekunden und ungefähr zehn Sekunden nach dem Aufrichten erfassen.

Eine einzelne Kamera mit ungeeignetem Blickwinkel liefert keinen
durchgehenden verlässlichen Rollwinkel über eine ganze Kurve. In diesem Fall
nur die brauchbaren Einzelzeitpunkte kennzeichnen; übrige Abschnitte bleiben
ohne Wahrheitslabel. Ein am Motorrad aufgenommenes Horizontvideo benötigt
ebenfalls eine bekannte Kameramontage und einen geeigneten Horizont und darf
nicht pauschal als exakte Referenz gelten.

### 3. Gemeinsame Datengrundlage

Zur Auswertung zusammen aufbewahren:

- vollständige Diagnose-ZIP und zugehörige GPX derselben Aufzeichnung;
- unverändertes Referenzvideo beziehungsweise Fotos der mechanischen Skala;
- App-Version/Commit und Fotos der Montage;
- Referenztabelle mit Zeit, Seite, Winkel und Unsicherheit;
- Zeitpunkte von Kalibrierung, Start, Stopp und Abnehmen des Handys.

Das Handy während der Messserie nicht umsetzen. Den Zeitpunkt des späteren
Abnehmens kennzeichnen, damit diese Bewegungen nicht als Fahrt bewertet werden.
Fehlt eine unabhängige Referenz, ist eine zusätzliche Fahrt weiterhin für
Störungen und zeitliche Zusammenhänge nützlich, aber nicht für die Bestimmung
des absoluten Winkelfehlers.

### 4. Vorab festgelegte Auswertung

Zuerst die unabhängige Referenztabelle erstellen, anschließend die Kandidaten
vergleichen. Dieselben Zeitpunkte und dieselbe Zeitsynchronisation für alle
Varianten nutzen.

- Im Stand: mittlere Abweichung in den letzten fünf Sekunden jedes Halts,
  Streuung und Unterschied zwischen erstem und zweitem Durchlauf.
- Dynamisch: geschätzter minus Referenzwinkel an gültigen Referenzzeitpunkten,
  getrennt für links, rechts und Kurvenausgänge.
- Referenzunsicherheit und Unsicherheit der Zeitsynchronisation mit angeben.
  Kleine Unterschiede unterhalb dieser Unsicherheit nicht als Verbesserung werten.
- Einen vollständigen Durchlauf für Entwicklung, den zweiten für unveränderte
  Gegenprüfung verwenden. Keine Schwellen an den Gegenprüfwerten nachstellen.

Erst diese Daten erlauben zu beurteilen, ob eine Korrektur den tatsächlichen
Winkelfehler verringert. Die Genauigkeit eines neuen Kandidaten ist damit weiterhin nicht bestätigt.

## Optionaler Live-Vergleich (2026-10-05)

Auf ausdrücklichen Wunsch steht der Kandidat jetzt als abschaltbarer Live-Modus
zur Verfügung. Das ist ein Feldversuch, keine bestätigte Genauigkeitsverbesserung.
Der Schalter „Experimentelle Schräglagenmessung“ ist standardmäßig ausgeschaltet.
Vor der Aufzeichnung einschalten und mit aufrechtem Motorrad neu kalibrieren.
Erst beim Status „Experiment: Sensor/GPS“ ist die experimentelle Anzeige bereit.
Ein Moduswechsel während der Aufzeichnung wird nicht übernommen.

Die App nutzt dieselbe Quaternion-/Montagegeometrie und denselben kompensierten
IMU-Schätzer wie die Replay-Tests. Die Beschleunigungskorrektur berücksichtigt das
vollständige GNSS-Intervall. Kursverzögerung bleibt bei null, die zusätzliche
Kurs/Gyro-Konsistenzprüfung ist aus. Weder Gyro-Bias noch Montage-Yaw werden aus
unzureichenden Fahrtdaten übernommen. Live werden die nativen Sensorereignisse
verarbeitet; das ist nicht identisch zur zeitlichen Abtastung alter Replays.

Die bisherige Android-Berechnung läuft parallel weiter. Im Diagnose-CSV werden
`lean_standard_callback_deg`, `lean_experimental_callback_deg`, `lean_mode` und
`experimental_status` an die bisherigen Spalten angehängt. Beide Winkel beziehen
sich auf den Callback-Zeitpunkt; sie sind keine unabhängige Winkelreferenz.
Kalibrierungen während einer Aufzeichnung erhalten eine eigene Metadatenzeile.
Anzeige, Peaks und GPX-Schräglage verwenden den gewählten Modus. Wenn das Experiment
noch nicht bereit ist, bleibt dessen Diagnosewinkel leer; der bestehende numerische
GPX-Winkelfallback ist null. Eine erkannte Gyro-Datenlücke verwirft die experimentelle
Lage und verlangt erneute Kalibrierung, statt automatisch auf Android zurückzufallen.

Validierung: Alle lokalen Unit-Tests einschließlich des externen Replays mit dem
Original-Fahrtarchiv sowie `:app:assembleDebug` erfolgreich. Keine Gerätetests und
noch keine Fahrt mit diesem Live-Modus. Geradeaus-Restwinkel und Verhalten nach
langen Kurven sollen bei der nächsten Fahrt verglichen werden; kleinere Werte
allein beweisen keinen kleineren tatsächlichen Winkelfehler.

## Reproduction

./gradlew :app:testDebugUnitTest --tests com.dpm.pegdown.sensor.CompensatedImuLeanExperimentTest

For mounting mathematics also run --tests com.dpm.pegdown.sensor.MountLeanGeometryTest.
For diagnostic metrics add --rerun --info --console=plain. Device tests are not required.

The complete newer replay is opt-in because it reads a large external archive.
Example in PowerShell before running the same Gradle command:

```powershell
$env:PEGDOWN_DIAGNOSTICS_ZIP = 'C:/Users/denis/Desktop/PegDown/PegDown.zip'
```

The archive is read-only. The variable must identify the original newer ZIP with
PegDown_sensor_diagnostics_20261005_141356.csv. With no variable, portable tests
still run and the external replay is explicitly skipped.
