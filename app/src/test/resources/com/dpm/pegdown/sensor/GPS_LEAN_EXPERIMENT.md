# GPS-assisted lean experiment

This candidate exists only in test sources. It is not connected to SensorProcessor,
the UI, or GPX export. It evaluates a limited correction on settled course sections.

## Recorded inputs

Source: PegDown.zip supplied on 2026-10-05, containing
PegDown_sensor_diagnostics_20261005_141356.csv and 2026-10-05_16-48.gpx.

Fixtures contain three 60-second excerpts of the diagnostic CSV:

| Fixture | Phone wall-clock start (UTC) |
| --- | --- |
| ride_20261005_left_exit | 14:18:16 |
| ride_20261005_right_and_left_exit | 14:20:48 |
| ride_20261005_fast_left_exit | 14:31:22 |

Each .csv.gz.b64 file is Base64-encoded gzip text. Decode with Base64 then gzip.
One in six rotation-vector callbacks is retained (approximately 10 Hz), together
with every location callback in the excerpt, including rejected network fixes.
The recorded displayed angle is used as input; no quaternion or accelerometer
fusion is recomputed at the reduced fixture rate. Coordinates are omitted.

event_s is the original monotonic event timestamp relative to the excerpt.
arrival_s approximates callback receipt from the diagnostic wall time using the
session's paired wall-clock / elapsed-realtime stamps. It has millisecond precision
and is not a newly measured monotonic receipt timestamp. Replay is ordered by
arrival, so later GNSS fixes cannot influence earlier outputs.

The phone clock bridge is:
session wall time 1791209637000 ms, elapsed realtime 247227441009158 ns.
GNSS UTC and phone wall time disagree by about 0.8 seconds in this recording;
matching sensor samples by UTC strings would therefore misalign this experiment.

## Model and constraints

The reference atan(speed * course_rate / gravity) describes equilibrium bank of
the vehicle's centre of mass. It does not independently measure chassis angle.

Only three consecutive good GNSS fixes, speed at least 8 m/s, stable course,
limited acceleration, fresh data and a settled attitude estimate permit correction.
The weight rises with elapsed time, is capped at 0.8, and disengages on invalid
or stale GNSS, rapid bank changes, or angles beyond 12 degrees. Correction is
bounded to 4.8 degrees. Tests cover heading wraparound, missing values, stationary
motion, fix loss and synthetic turn transitions.

## Replay results

Numbers below are mean absolute displayed angles in selected sections. They
measure changed output magnitude, not error against an independently known angle.

| Section | Recorded baseline | Experiment | Supported samples |
| --- | ---: | ---: | ---: |
| Left exit, fixture seconds 28-34 | 5.06 deg | 3.40 deg | 30/59 |
| Right exit, fixture seconds 14-17 | 5.22 deg | 2.78 deg | 29/29 |
| Following left exit, fixture seconds 26-34 | 4.32 deg | 1.37 deg | 78/78 |
| Separate faster exit, fixture seconds 24-31 | 10.87 deg | 10.59 deg | 18/68 |

On supported samples, disagreement with the GNSS equilibrium reference decreases.
The faster section shows little overall benefit because the eligibility gates
mostly refuse correction. Recorded large angles are unchanged by construction.

A synthetic counterexample uses a perfect attitude measurement of a real
8-degree straight-line bank, such as a crosswind-balanced ride. The same GPS
reference incorrectly suppresses that true angle by over 4 degrees. Therefore
these replay results do not justify enabling automatic GPS straightening in the
ride display. A further candidate needs additional physical constraints and
independent validation of chassis bank.

Run:
./gradlew :app:testDebugUnitTest --tests com.dpm.pegdown.sensor.GpsAidedLeanExperimentTest
