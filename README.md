# GyroRecorder

Util app for capturing phone-motion datasets ("sensor Pantheon" runs): live ARCore
camera preview + time-synced ARCore poses and full-rate IMU, labelled by scenario
(Walking / Shooting / Two hands / One hand / Static / Pan-tilt sign check / Zoom / Custom).

## Build & run (release only)

The canonical artifact is the **release** APK. `./gradlew` with no task and
`./build-release.sh` both run `:app:assembleRelease`; do not ship debug builds.

```sh
./build-release.sh
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.matthew.gyrorecorder/.MainActivity
```

Release is signed with the local debug key (same convention as PerfOverlay's
`releaseLocal`) so it installs with no secrets; it is not a Play upload build.

Tests: `./gradlew :app:testReleaseUnitTest` (PoseMath/FOV unit tests) and, with
a phone attached, `./gradlew :app:connectedAndroidTest` (injected-pinch zoom
device test; on Xiaomi, allow the test-APK install prompt if it appears).

Sessions land in `Android/data/com.matthew.gyrorecorder/files/Pantheon/<time>_<scenario>/`
(also: **Share last** button zips a session). Pull with:

```sh
adb pull /sdcard/Android/data/com.matthew.gyrorecorder/files/Pantheon/
```

## Session files

All timestamps are nanoseconds in the boot-time domain: `SensorEvent.timestamp`
and `SystemClock.elapsedRealtimeNanos` are documented boot-time clocks
([SensorEvent](https://developer.android.com/reference/android/hardware/SensorEvent));
ARCore documents `Frame.timestamp` as capture time in ns
([Frame](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame)).
That they share a domain is treated as a *checkable assumption*, not gospel:
`frames.csv` records `host_elapsed_ns` next to every frame so the offset
(frame ts lagging host by roughly the exposure+pipeline age, ~0.1 s in our
test capture) can be audited per device before joining on `timestamp_ns`.
`t_rel_s` is seconds relative to record start.

| File | Contents |
|---|---|
| `session.json` | device, sensors, scenario, notes, `sensorOrientationDeg`, gyro mapper version, plus a `camera` block for scene replication (ARCore camera config: id/facing/CPU+GPU stream sizes/fps range; Camera2: focal length mm, sensor size mm, pixel array) and `cameraIntrinsicsFirstFrame` (image + texture intrinsics with computed h/v/diagonal FOV) |
| `frames.csv` | per ARCore frame: pose `tx,ty,tz,qx,qy,qz,qw` (world), intrinsics `fx,fy,cx,cy` + image size for that frame, tracking state, `host_elapsed_ns` (clock audit: frame ts lags host by exposure age) |
| `gyro.csv` | gyro rad/s, device frame (`wx..wz`) + camera frame (`cam_wx..cam_wz`, RawLens `GyroCameraFrameMapper` v1: stored-image X right / Y down / Z into scene) |
| `accel.csv` | accelerometer m/s², device frame, incl. gravity |
| `rotvec.csv` | rotation-vector quaternion `qx,qy,qz,qw` |
| `events.csv` | MARK taps (e.g. shutter moments while "Shooting") |
| `frame_motion.csv` | **gyro integrated between consecutive frames**: `drx,dry,drz` (rad, camera frame), rotation angle, and predicted image shift at the principal point `shift_x_px = fx·dry`, `shift_y_px = −fy·drx`, plus gyro sample count per interval |

## Why gyro carries the subpixel signal

Image-based alignment quantises to the pixel grid and the camera sensor gives
no direct subpixel motion readout. The gyro measures angular rate at ~400 Hz
(BMI260 on the test Xiaomi), so integrating it between two frame timestamps
yields the inter-frame rotation to well under a pixel. As zoom increases the
perspective (translational/parallax) warp shrinks and the inter-frame warp
tends to a pure rotational homography `H ≈ K·R·K⁻¹` — exactly what
`frame_motion.csv` precomputes per frame pair. Use `Static` runs for the gyro
noise floor/bias, and `Pan/tilt sign check` once per new device to validate
axis signs against the visible motion.

## References (online)

Every external API/claim above maps to a public source; URLs checked live 2026-10-20.

| What we use / claim | Source |
|---|---|
| `Session.update()` → `Frame`, camera pose, tracking state | [ARCore Frame reference](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame), [Camera reference](https://developers.google.com/ar/reference/java/com/google/ar/core/Camera), [Session reference](https://developers.google.com/ar/reference/java/com/google/ar/core/Session) |
| Per-frame `fx, fy, cx, cy`, image dimensions | [ARCore CameraIntrinsics reference](https://developers.google.com/ar/reference/java/com/google/ar/core/CameraIntrinsics) |
| Camera config (id, facing, stream sizes, fps range) in `session.json` | [ARCore CameraConfig reference](https://developers.google.com/ar/reference/java/com/google/ar/core/CameraConfig) |
| Physical focal length / sensor size / pixel array in `session.json` | [CameraCharacteristics reference](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics) |
| Pose translation + rotation quaternion `(x, y, z, w)` | [ARCore Pose reference](https://developers.google.com/ar/reference/java/com/google/ar/core/Pose) |
| ARCore supported-device / availability checks | [ARCore supported devices](https://developers.google.com/ar/devices) |
| Camera-background rendering (`setCameraTextureName`, `transformCoordinates2d`) | [google-ar/arcore-android-sdk Hello AR sample](https://github.com/google-ar/arcore-android-sdk/tree/master/samples/hello_ar_kotlin) |
| Gyro/accel/rotation-vector sensors, `SensorEvent.values` meanings | [Android motion sensors guide](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion), [SensorEvent reference](https://developer.android.com/reference/android/hardware/SensorEvent) |
| `samplingPeriodUs` is a hint; event timestamps | [SensorManager reference](https://developer.android.com/reference/android/hardware/SensorManager), [Sensors overview (rate limits, `HIGH_SAMPLING_RATE_SENSORS`)](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview) |
| Material 3 UI (cards, chips, buttons, theming) — same system as PerfOverlay | [Material Components for Android getting started](https://github.com/material-components/material-components-android/blob/master/docs/getting-started.md), [Material 3 design tokens](https://m3.material.io/foundations/design-tokens/overview) |
| Gyro readings → inter-frame rotation → homography `H = K·R·K⁻¹` ("gyro field") | [GyroFlow+: Gyroscope-Guided Unsupervised Deep Homography and Optical Flow Learning (arXiv:2301.10018)](https://arxiv.org/abs/2301.10018) |

Local, measured (not from a datasheet): on the test Xiaomi 25080RABDG,
`dumpsys sensorservice` lists `bmi260_gyro`/`bmi260_acc` at max 400 Hz, and a
recorded session confirms 404 Hz gyro/accel delivery. Re-check per device.

## Notes

- ARCore is declared `optional`: on devices without it the app still records
  IMU-only sessions (banner shown, preview dark).
- Intrinsics are recorded **per frame** — they change with zoom/crop, and the
  alignment math needs the values that belong to each frame.
- No video file is recorded; the pose + IMU streams are the dataset. Use
  Android screen-record if a visual reference is needed for a run.
- Gyro samples for `frame_motion.csv` are buffered in RAM (~400 Hz); keep
  sessions to tens of minutes, not hours.
- The gyro->camera-frame mapping uses the first back camera's
  `SENSOR_ORIENTATION`; on multi-back-camera devices where ARCore picks a
  different physical camera, validate with the Pan/tilt sign check first.
- Pinch zoom is a **simulated** digital zoom: ARCore owns the camera and
  exposes no optical-zoom control, so the preview centre-crops and
  `session.json` records `zoomFactor`. Native intrinsics stay in
  `frames.csv`; effective focal lengths are `fx * zoomFactor` etc.
