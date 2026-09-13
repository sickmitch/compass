# Android 0.28.8 — endpoint order and supplied puck assets live gate

Status: awaiting operator live validation.

## Changes under test

- Origin and destination now expose location methods in the same order: current location, favourite
  places, search, map selection.
- The supplied Compass puck artwork is packaged as density-aware Android PNG resources at a logical
  size of 56 dp. The light artwork is used by the light map theme and the dark artwork by the dark
  map theme. No runtime SVG dependency was added.

## Device checks

1. Open `Crea viaggio`. Confirm both endpoint grids use identical row and column ordering, dimensions
   and selected-state treatment.
2. Inspect route-free follow and active navigation in both system themes. Confirm the light SVG
   artwork appears in the light theme, the dark SVG artwork in the dark theme, with a transparent
   background, correct bearing rotation and existing zoom scaling.

These visual checks require a physical device and are not implied by the local build.

