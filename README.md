# Rhythm Autoplayer

Native Kotlin Android app (Android 10+, tested target: LG G7 ThinQ) that watches a **downscroll** rhythm game
through screen capture and taps your lanes with an Accessibility service. Personal use, fully offline:
frames are processed in memory and never saved or uploaded.

> Status: the code was rewritten and reviewed by hand, but it has **not** been compiled or run on a device
> by the author of this change. Build it, then follow the checks below before trusting it. No accuracy,
> latency, or multi-touch guarantee is made.

## How to use

1. Install the APK. Open the app.
2. **1. Overlay permission** (allow "Display over other apps").
3. **2. Accessibility** -> turn on *Rhythm Autoplayer input*.
4. Pick the number of keys (4K, 6K, 7K, 9K, 12K, 21K, or any 1-32 with the slider).
5. **3. Start** -> allow screen capture. The app goes to the background.
6. Open your game. The control bar has separate **Watch lanes** and **Tap targets** edit buttons.
   - **Watch lanes**: drag the green bands to cover the falling-note columns and move each green hit line
     to the point where notes should be judged.
   - **Tap targets**: drag the pink circles to the game's actual touch controls. These can be anywhere
     on screen and are independent from the watch lanes.
   - *Row: on* keeps the selected layer aligned vertically when dragging; *Even* spaces that layer evenly;
     **-** / **+** change the key count;
     **Move** drags the control bar (keep it away from the receptors).
   - **Watch**: circles lock and stop blocking touches. Yellow dots show detected notes (red = a tap was
     scheduled). Nothing is tapped. Use this to check detection first.
   - **Play**: autoplay on. Tap **Watch** to pause, **X** to stop and release everything.
   - **–** minimizes the bar into a round bubble (✎ edit, ◉ watch, ▶ play). Tap the bubble to expand, drag it to move, long-press it during Play to pause. Watch and Play minimize automatically.
   - **Test**: taps every receptor once so you can see whether the game reacts (and how many simultaneous
     touches it accepts).
7. Tune sensitivity, confidence, timing offset, and speed guess in the app. Save named profiles per game/key count.

The detector scans each green lane band from the top of the screen down to just above its hit line (the full falling area); the bands do not crop
capture. Tune *Button size* to fit a note. Pink input targets are independent and may be placed on game
buttons, a virtual keyboard, or other touch controls.

## What was wrong (crash on open)

`MainActivity.buildInterface()` set the lane-count SeekBar progress while building the screen. That fired its
listener, which called `syncLabels()` and touched `lateinit` labels that did not exist yet
(`UninitializedPropertyAccessException`). The new screen only writes settings on real user drags, so no
listener runs during construction. Also fixed: the capture service now calls `startForeground()` first on every
start path (a missed call crashes the app on Android 8+).

## Architecture

- `capture/CaptureService` - foreground MediaProjection service; capture at <=1280px long side, bounded
  `acquireLatestImage()` loop on a worker thread, rotation handling, schedules taps from predicted arrival.
- `capture/ReceptorOverlayView`, `capture/ControlPanel` - independent lime watch bands/hit lines and pink touch targets (touchable only in Edit; in Watch/Play
  the window is `FLAG_NOT_TOUCHABLE` so injected taps reach the game) and the floating control bar.
- `core/FullScreenNoteDetector` - scans lane bands; a pixel is "note" when it differs from that lane's own background (per-frame median), so white/grey/dim notes work too. Rejects weak-fill and colour-incoherent blobs.
- `core/NoteTracker` - (falling-only: a note must enter from the top and travel downward; long notes are followed by their bottom edge, then by their top edge once the head reaches the hit zone) - globally-optimal frame-to-frame matching; a blob only counts as a note after it travels downward a real distance at a steady speed, so static pop-ups (sick/perfect/miss, hit flashes) are ignored and cannot corrupt the shared scroll-speed estimate. Predicts arrival and triggers once per note.
- `input/AccessibilityInputService` - `dispatchGesture` taps and long-note holds; single in-flight gesture, per-lane de-duplicated queue,
  stale requests dropped, chords batched up to `GestureDescription.getMaxStrokeCount()`.
- `config/ProfileRepository` - named profiles in private SharedPreferences (settings only).

## Build

Android Studio (Hedgehog or newer) or Gradle 8.9+, JDK 17, Android SDK 35.

```sh
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`.github/workflows/android.yml` runs the unit tests and builds the debug APK on every push (cancelling older runs of the same branch). The APK is the `rhythm-autoplayer-debug-apk` artifact; if a test fails, the HTML test report is uploaded as `unit-test-reports`.

## Known limitations

- **Downscroll only.** Upscroll/sideways layouts were removed.
- **Long notes are held on a best-effort basis.** Tall blobs are treated as hold notes: the lane is pressed on the head and kept down with chained continuation strokes (`willContinue`) until the tail reaches the hit line. This depends on the game/phone accepting continued gestures, so verify with a real long note in Watch (cyan dots = hold) before trusting Play. Two notes stacked with almost no gap look like one long note.
- Accessibility gestures are one batch at a time and Android caps strokes per gesture (usually 10), so large
  chords on 12K/21K are split into consecutive gestures and may land a few milliseconds apart. Whether the
  game accepts that many simultaneous touches must be checked with **Test**.
- The control bar and, in Edit mode, the whole overlay intercept touches; only Watch/Play let taps through.
- Detection uses color saturation, fill, hue coherence, then repeated motion confirmation. Dim/grey notes may be
  missed; coherent moving shaders inside a watch band can still look like notes. Tune *Color sensitivity* /
  *Minimum confidence* and use Watch mode to verify before Play.
- Our own circles and dots appear in the captured frames; dots are drawn beside the lane column so they are not scanned.
- Positions are stored as screen fractions. Calibrate in the orientation the game runs in (landscape is typical).
- Capture timing uses frame arrival time; use *Tap earlier/later* to compensate for gesture latency
  (the control bar shows tap lag and scheduling lateness).
- The first frames of a note are needed to measure speed, so very fast scrolling or a low frame rate can miss notes.
  Raise capture rate by lowering *Frame interval* if the phone stays cool.

## Privacy

No network permission is declared. Frames are read from the capture buffer, then closed. Only profiles are stored.
