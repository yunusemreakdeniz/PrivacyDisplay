# Privacy Display

Android app that darkens the screen in milliseconds when someone looks from the side or over your shoulder, while staying out of the way when only you are looking. Built for low battery use.

## How it works

A four-level, event-driven state machine:

| Level | State | What runs |
|---|---|---|
| 0 | `IDLE` | Nothing. Screen off, or not a protected app. |
| 1 | `SENSOR_WATCH` | Accelerometer and light sensor at a low rate. Camera off. |
| 2 | `ACTIVE_CHECK` | Front camera at 320x240 (CameraX, `STRATEGY_KEEP_ONLY_LATEST`) and ML Kit face detection in fast mode, head yaw only. |
| 3 | `SHIELD_ACTIVE` | Curtain down until the view is safe again. |

- **Owner first.** The largest face is the owner. A second face in two frames in a row drops the curtain. The owner's own head angle only counts when it stays past 42° for four frames, so a glance aside never darkens the screen. It lifts after three frames of the owner alone below 32°. "No face" darkens only if enabled, after 2 s.
- **Asymmetric animation.** Darkening is instant. Lifting fades over 180 ms.
- **Continuous by default.** About 3 checks a second at 640x480, every frame while something looks wrong. Battery saver brings back the roadmap's sleeping camera (wakes on a move or every 2–30 s).
- **Dark rooms.** Below 3 lux the camera is not used. The device tilt decides.
- **Curtain.** Pure black (`#000000`) overlay, OLED friendly, no blur. Touches pass through. Android 12+ caps such overlays at about 80% opacity.
- **Only chosen apps.** Optional. Uses usage access to know which app is in front.
- **Quick Settings tile.** Turns protection off with one tap.

## Build

The GitHub Actions workflow builds a release APK on every push. A `v*` tag publishes it as a release.
