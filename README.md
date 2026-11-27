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

- **Hysteresis.** The curtain drops when `|yaw| > 42°`, or with more than one face, or with no face for two frames (optional). It lifts only at `|yaw| < 32°` with exactly one face.
- **Asymmetric animation.** Darkening is instant. Lifting fades over 180 ms.
- **Battery.** While the phone is still, the camera sleeps and wakes every few seconds (2–30 s, set by the user). A move wakes it at once. Once the view is clear and the phone has been still for 3 s, the camera turns off again.
- **Dark rooms.** Below 3 lux the camera is not used. The device tilt decides.
- **Curtain.** Pure black (`#000000`) overlay, OLED friendly, no blur. Touches pass through. Android 12+ caps such overlays at about 80% opacity.
- **Only chosen apps.** Optional. Uses usage access to know which app is in front.
- **Quick Settings tile.** Turns protection off with one tap.

## Build

The GitHub Actions workflow builds a release APK on every push. A `v*` tag publishes it as a release.
