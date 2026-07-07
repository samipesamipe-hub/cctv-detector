# CCTV Detector (Android)

Real-time, on-device detection of visible CCTV / security cameras using the
phone's camera — no location database required. Points the camera around,
draws bounding boxes over anything the model recognizes as a camera, and
shows a rough distance estimate.

## Stack
- Kotlin, single-activity
- CameraX (`Preview` + `ImageAnalysis`)
- TensorFlow Lite (Ultralytics YOLOv8n-style export)
- Custom `OverlayView` for bounding boxes (no extra AR/depth libraries needed)

## Project layout
```
app/src/main/java/com/example/cctvdetector/
  MainActivity.kt           CameraX pipeline, permission handling
  ObjectDetectorHelper.kt   Loads model.tflite, runs inference, NMS, distance estimate
  OverlayView.kt            Draws boxes/labels on top of the preview
  ImageUtils.kt             YUV_420_888 -> Bitmap conversion
  Detection.kt              Result data class
app/src/main/assets/
  labels.txt                Class names (edit to match your trained model)
  model.tflite               <-- YOU ADD THIS (see below, not included)
```

## 1. Build it

### Option A — Android Studio (local)
Open the folder in Android Studio (Koala+ recommended). It will generate the
Gradle wrapper on first sync — if it doesn't, run `gradle wrapper` from the
project root once you have Gradle installed locally. Minimum SDK 24.

Without a model file the app still builds and runs — it will show
"Model not loaded" instead of crashing, so you can wire up the UI first.

### Option B — GitHub Actions (no local Android Studio needed)
A workflow is already included at `.github/workflows/build-apk.yml`. It
installs JDK 17, the Android SDK, and Gradle on a GitHub-hosted runner, then
builds a debug APK — no local Gradle wrapper required.

1. Create a new repo on GitHub (public or private, doesn't matter).
2. Push this project to it:
   ```bash
   cd CCTVDetector
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/<your-username>/<your-repo>.git
   git push -u origin main
   ```
3. Go to the repo's **Actions** tab on GitHub — the "Build APK" workflow
   runs automatically on push (or click **Run workflow** to trigger it
   manually).
4. When it finishes (a few minutes), open the completed run and download
   the **cctv-detector-debug-apk** artifact from the Summary page — it's a
   zip containing `app-debug.apk`.
5. Unzip it, transfer `app-debug.apk` to your phone (email, cloud drive,
   USB, etc.), tap it to install. You'll need to allow "install unknown
   apps" for whichever app you use to open it (one-time Android prompt).

Note: without a `model.tflite` in `assets/` (see step 2 below) the APK will
install and run fine, just showing "Model not loaded" until you add one and
rebuild.

### What triggers a build
The workflow only fires on pushes that touch `app/**` (code, resources, or
the model/labels files) or the workflow file itself — so pushing just the
README won't waste a build. It also fires specifically when you overwrite
`app/src/main/assets/model.tflite`, which is the normal way you'll update
the app after training a better model. You can always trigger it manually
too from the Actions tab (**Run workflow** button).

### Signing the APK (so updates don't break)
Android requires every update to an app to be signed with the *same* key.
Without setup, the workflow signs with the auto-generated debug key, which
works fine for personal sideloading but isn't meant for real distribution
and isn't guaranteed stable across environments. To sign with a real,
persistent key:

1. Generate a keystore once, locally (keep this file and its passwords safe
   — if you lose them you can't push updates to an already-installed app
   without uninstalling first):
   ```bash
   keytool -genkeypair -v -keystore release-key.jks \
     -alias cctvdetector -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Base64-encode it:
   ```bash
   base64 -w 0 release-key.jks > release-key.b64   # Linux
   base64 -i release-key.jks -o release-key.b64     # macOS
   ```
3. In your GitHub repo, go to **Settings → Secrets and variables → Actions**
   and add these repository secrets:
   - `KEYSTORE_BASE64` — contents of `release-key.b64`
   - `KEYSTORE_PASSWORD` — the keystore password you set in step 1
   - `KEY_ALIAS` — `cctvdetector` (or whatever alias you used)
   - `KEY_PASSWORD` — the key password you set in step 1
4. Push again (or re-run the workflow). The build will now produce a
   properly signed APK, and the artifact/file name will end in `-signed`
   instead of `-debugsigned` so you can tell at a glance.

Each build's APK is renamed to something like
`CCTVDetector-v1.0-build7-a1b2c3d-signed.apk` (version, run number, commit,
signing status) so you can keep multiple downloads straight.

## 2. Get or train the detection model
There's no off-the-shelf "security camera" class in COCO, so you need a
model trained on that specific class. Two practical paths:

### Option A — use a public dataset (fastest)
Roboflow Universe has several community CCTV/security-camera datasets
(search "security camera detection" or "CCTV detection" there). Export one
in YOLOv8 format, then:

```bash
pip install ultralytics
yolo train model=yolov8n.pt data=path/to/data.yaml epochs=100 imgsz=640
yolo export model=runs/detect/train/weights/best.pt format=tflite imgsz=640
```

This produces a `best_float32.tflite` (or `int8` if you pass `int8=True`
with a representative dataset). Rename it `model.tflite` and drop it in
`app/src/main/assets/`.

### Option B — build your own dataset
Collect a few hundred photos of CCTV/dome/bullet cameras in varied angles,
lighting, and distances, label boxes with a tool like Roboflow or CVAT
(class names matching `labels.txt`), then run the same `yolo train` /
`yolo export` commands above. More data and angle variety = fewer missed
or false-positive detections, especially for small/angled cameras.

### Matching the output format
`ObjectDetectorHelper.decodeOutput()` assumes the default Ultralytics
tflite export shape `[1, 4 + numClasses, numBoxes]` with no built-in NMS.
If you export with `nms=True` or use a different architecture (e.g. a
TFLite Model Maker EfficientDet model), the output tensor shape will
differ — adjust `decodeOutput()` in `ObjectDetectorHelper.kt` to match
(the TFLite Task Vision library's `ObjectDetector` API can also replace
the manual parsing if you go that route).

Update `labels.txt` and the `knownWidthsMeters` map in
`ObjectDetectorHelper.kt` to match whatever classes you actually train.

## 3. Distance estimate — important caveat
Distance is a rough pinhole-camera estimate:
`distance ≈ (real_object_width × focal_length_px) / pixel_width_of_box`.

`assumedFocalLengthPx` in `ObjectDetectorHelper.kt` is a generic placeholder,
**not calibrated per device**. To calibrate: place a camera-sized object at
a known distance (e.g. 2m), note the detected box width in pixels, and
solve for `focal_length_px = (pixel_width × distance) / real_width`.
Treat the output as "near / medium / far", not a precise measurement.

## 4. Performance notes
- Detection runs on a throttled loop (one frame in flight at a time via
  `AtomicBoolean`) rather than every camera frame, so a slow model doesn't
  back up the pipeline.
- The GPU delegate is used automatically when the device supports it,
  falling back to 4-thread CPU otherwise.
- YOLOv8n at 640px should comfortably run in real time on mid-range 2023+
  hardware; drop to `imgsz=320` at export/train time for older devices at
  some accuracy cost.

## Known limitations (as noted in your spec)
- Small, angled, or partially hidden cameras may be missed.
- Decorative objects shaped like cameras (smoke detectors, some light
  fixtures) can trigger false positives — more varied training data reduces
  this but won't eliminate it entirely.
- Model quality is entirely dependent on the dataset you train on.
