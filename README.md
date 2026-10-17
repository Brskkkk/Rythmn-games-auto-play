# Rhythm Autoplayer

A native Kotlin Android prototype for observing a rhythm game and optionally submitting calibrated touch taps. The project targets Android 10 (API 29) and later. It is intended for local, personal play; it does not automate dash or other special mechanics.

**Important:** this is an experimental vision heuristic, not a game-specific or benchmarked note recognizer. Do not enable autoplay until observation mode has been checked against the target mod. No accuracy, timing, frame-rate, or multi-touch guarantee is made for the LG G7 ThinQ.

## Build and install

Requirements: Android Studio or Gradle 8.9+, JDK 17+, Android SDK Platform 35, and Android Build Tools installed through SDK Manager.

```sh
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The application has `minSdk 29`, `targetSdk 35`, and `compileSdk 35`. On first use, enable **Rhythm Autoplayer input** in Android Accessibility settings if input testing is needed. Grant screen capture in the system prompt. Grant display-over-other-apps only for the optional floating guides. Android 13+ may also ask for notification permission; the capture service still supplies its required ongoing foreground notification.

## Architecture

- `capture/CaptureService`: MediaProjection foreground service, full-display `ImageReader`, rotation resize, bounded `acquireLatestImage()` processing, frame timing, and lifecycle cleanup.
- `core/FullScreenNoteDetector`: scans the captured frame in memory using a coarse color/chroma mask, then assigns vertical blobs to configurable lane centers.
- `core/NoteTracker`: associates candidates between frames, estimates directional velocity, predicts arrival, and emits one trigger per moving track.
- `config/ProfileRepository`: stores named calibration profiles in private SharedPreferences. Only settings and calibration data are persisted.
- `input/AccessibilityInputService`: submits short touch gestures at configured lane/receptor coordinates. Simultaneous lanes are combined into one gesture when requested.
- `DetectionOverlay`, `CalibrationGuideView`, `MainActivity`: optional non-touchable floating guides, draggable lane/receptor guides, controls, profile CRUD, permissions, and diagnostics.

The capture service analyzes the full captured screen vertically; calibration guides do not crop capture. Frames are copied into reusable in-memory buffers, processed on a dedicated worker, and closed immediately. The app does not record or save frames, upload screen content, or send analytics.

## Calibration and operation

1. Open the app and begin with **Observe**. Accept the MediaProjection prompt while the game is visible.
2. Optionally grant overlay permission. The transparent overlay shows configured lane centers, the receptor line, candidate positions, and rough predicted times over the game. It does not intercept touches.
3. Drag guides in the in-app calibration panel; adjust lane count, scroll direction, sensitivity, confidence threshold, initial speed estimate, processing interval, and timing offset. Save a named profile.
4. Compare detections and predicted times with the actual mod in observation-only mode. Tune lane positions and settings before testing input.
5. Enable the accessibility service and test a single lane. Verify the game recognizes it. Autoplay is a separate explicit control; stop capture to release projection and overlay resources.

Profiles support 1-32 configurable lanes, including common 4K, 6K, 7K, 9K, 12K, and 21K layouts. Lane centers also define touch targets; no fixed key count is embedded in the detector.

## Android API constraints and known limitations

- MediaProjection requires a user-approved capture session and a foreground notification. Projection revocation and service stop release the virtual display, reader, overlay, and pending inputs. Rotation resizes the current display rather than creating another projection display.
- Accessibility `dispatchGesture` injects screen-touch paths, not arbitrary key events. The game must accept touch at those coordinates. Gesture completion is asynchronous; a second overlapping gesture is rejected while one is in flight. Android/game/device pointer limits have not been tested here. The API caps a gesture at 20 strokes, and that is not a promise that 20 concurrent game inputs work.
- Gesture duration is estimated from detected note-body height divided by measured screen-space velocity, then sent as a held touch (up to 30 seconds). This is useful only when a mod's drawn long-note body corresponds to hold duration. Release tails are not recognized, and held gestures may not match the mod's sustain semantics.
- Android/game/device pointer limits have not been tested here. Chord lanes are combined into one gesture, limited to 20 strokes by the adapter, and another gesture is rejected while one is in flight. That limit is not a promise that 20 simultaneous inputs work.
- The detector is deliberately lightweight and generic, but its current color/chroma blob heuristic can miss monochrome, dim, textured, animated, or unusual notes and can mistake effects/background art for notes. It requires observed directional movement before autoplay dispatch, which reduces static-receptor false triggers but may miss notes during occlusion or dropped frames. It does not classify note colors or learn a mod palette. Treat its confidence as a heuristic score, not a probability.
- Note speed is estimated per tracked note from frame-to-frame movement, so the engine does not use one fixed chart speed. The configurable fallback is used until movement is observed. This cannot mean reliable operation at literally every speed: notes that cross a judgement region between captured frames, projection stalls, and device/game input latency can still cause misses. The usable speed range must be measured on the target device.
- Timing uses frame timestamps, observed screen-space motion, and a configurable offset. It cannot compensate for game-specific judgement windows, touch sampling, compositor behavior, or device load without empirical calibration.
- CPU/GPU use, thermal behavior, capture rate, latency, input concurrency, and gameplay success have not been benchmarked on an LG G7 ThinQ. The current CPU image scan may need profiling on-device; no JNI/GPU path is included.

## Tests and staged validation

The local unit tests use synthetic pixel arrays to exercise colored-note lane assignment (including a 7-lane/up-scroll layout) and one-time moving-track triggering:

```sh
gradle :app:testDebugUnitTest
```

Before enabling autoplay, validate in this order:

1. Grant capture, confirm the ongoing notification, and verify that stopping/revoking capture releases resources. Confirm no screenshots or video files are created.
2. Run synthetic detector tests, then compare observation-mode boxes with user-provided screenshots and the live game. The app currently has no screenshot-import UI; screenshots should be used only by a future local test harness, not copied into persistent capture storage.
3. Tune lane centers, scroll direction, and sensitivity at several note speeds and lane counts. Check overlapping notes, chords, effects, and dropped-frame behavior.
4. Record prediction error and capture-to-detection timing during observation. Adjust the timing offset only after repeated measurements.
5. Test one tap, then a small chord, and verify gesture completion and game recognition. Test sustained notes of several lengths; compare the estimated body-based hold with the mod's actual release behavior.
6. Exercise dense patterns and device rotation, then repeat while monitoring FPS, processing time, capture age, heat, and battery on Android 10 hardware.
7. Only after those checks, enable autoplay. Disable it immediately if inputs drift, duplicate, or miss.

Build success and synthetic tests do not establish real-game accuracy or Android 10 device behavior. The LG G7 and Friday Night Dustin assets/gameplay are not available in this workspace, so those validation steps remain outstanding.

## GitHub Actions APK

`.github/workflows/android.yml` runs on pushes, pull requests, and manual dispatch. It installs JDK 17 and Android SDK Platform 35, runs unit tests, assembles the debug APK, and publishes `app-debug.apk` as the `rhythm-autoplayer-debug-apk` workflow artifact for 14 days. It does not publish a signed release APK.