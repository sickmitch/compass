# Android 0.19.2 road-test acceptance record

Status: accepted on a physical Android device and real road route on 2026-09-06.

## Scope

This post-Navigation-UI-Phase-9 patch contains the two corrections prompted by the operator's first
road test:

- a structured green junction sign is centered and sizes to its content, and an identical maneuver
  road subtitle is suppressed rather than presenting the same road three times;
- upcoming guidance uses Valhalla's `begin_shape_index` transition semantics, so the instruction,
  distance, following maneuver, speech staging, approach phase and camera no longer remain one
  maneuver behind the matched position.

Android version is `0.19.2` (`versionCode=22`). No backend, API, database, traffic or route-document
contract changed.

## Automated validation

The targeted `NavigationDrivingUiModelTest`, `NavigationEngineTest`, `ManeuverControllerTest` and
`NavigationCameraControllerTest` suites passed. The complete repository-local Android gate then
passed:

```text
BUILD SUCCESSFUL in 1m 53s
53 actionable tasks: 17 executed, 36 up-to-date
```

That gate ran `testDebugUnitTest`, `lintDebug` and `assembleDebug`. `git diff --check` also passed.

The maneuver regression reproduces the transition from `Roverchiara Nord` to
`Via Cappafredda`: before the exit the exit instruction is current; once matching enters the exit's
outgoing segment, the Via Cappafredda turn becomes current and its distance remains aligned.

## Physical-device evidence

The operator generated the route while connected to the workstation, drove the real route on the
physical device, returned screenshots identifying both defects, installed the corrections and then
explicitly reported the `0.19.2` result as perfect.

The Android `0.19.2` road-test gate is complete. A later increment may begin only when explicitly
requested.
