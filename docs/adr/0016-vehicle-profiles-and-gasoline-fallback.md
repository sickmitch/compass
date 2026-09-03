# ADR 0016: Local vehicle profiles and explicit gasoline fallback

Status: Accepted design; physical-device/live gate pending.

## Context

Range and reserve values vary by vehicle, while Compass has no CAN/OBD fuel telemetry. Re-entering
stable vehicle parameters is error-prone, but persisting a guessed current tank level would be less
safe. A dual-fuel vehicle can also finish some journeys that are not practicable with CNG alone.

## Decision

Android owns a versioned local profile document. Each profile has a stable ID, name, effective full
CNG range, CNG reserve, effective full gasoline range and gasoline reserve. The selected profile
pre-fills those four stable policy values across restarts. Current remaining CNG and gasoline ranges
are never stored as profile facts and remain explicit driver inputs.

The predictive request accepts optional remaining-gasoline and gasoline-reserve fields as a pair.
Compass first attempts its normal complete CNG itinerary. Only when that fails, it tests the direct
base-route distance against:

`usable CNG + usable gasoline = (remaining CNG - CNG reserve) + (remaining gasoline - gasoline reserve)`

If the direct route fits, the response is `gasoline_fallback` and exposes the estimated gasoline
required and margin at destination. It does not search for gasoline stations, infer fuel levels or
use gasoline merely to bridge toward a CNG station. A valid complete CNG itinerary always retains
priority.

## Consequences

- profiles stay private to the device and do not require accounts or backend schema changes;
- old callers can omit gasoline fields and retain existing CNG-only behavior;
- a gasoline fallback is explainable and bounded, but depends on conservative driver estimates;
- gasoline-station ranking and telemetry integration remain separate future work.
