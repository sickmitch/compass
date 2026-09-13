# Android 0.28.6 — puck and voice refinement live gate

Status: awaiting operator live validation.

## Acceptance checks

1. In route-free follow and active navigation, the puck has one uninterrupted dark-green centre
   stroke from the rear notch to the arrow tip. No circular background or outer ring is visible.
2. Start navigation with a maneuver farther than 500 m away. The voice must never say `prossima
   manovra`. The initial announcement states the rounded distance and instruction.
3. Continue through the maneuver countdown. Announcements occur once at 500 m, 250 m, 100 m,
   50 m and 10 m. The 50 m announcement says `A breve` instead of a numeric distance; the other
   announcements state their distance. Small GPS oscillations must not repeat a stage.

These checks require device observation and are not implied by a successful local build.

