# OpenCV Android SDK Setup

The motion analysis pipeline requires OpenCV Android SDK. Follow these steps to add it:

## Option 1: Download and Add as Module (Recommended)

1. **Download OpenCV Android SDK:**
   - Visit: https://opencv.org/releases/
   - Download OpenCV Android SDK (version 4.8.0 or later)
   - Extract the ZIP file

2. **Add OpenCV Module to Project:**
   - In Android Studio: `File` > `New` > `Import Module`
   - Navigate to the extracted OpenCV folder
   - Select: `OpenCV-android-sdk/sdk`
   - Module name: `opencv`
   - Click `Finish`

3. **Update settings.gradle:**
   Add this line:
   ```gradle
   include ':opencv'
   ```

4. **Update app/build.gradle:**
   Replace the commented OpenCV line with:
   ```gradle
   implementation project(':opencv')
   ```

5. **Sync and Build:**
   - Click `Sync Project with Gradle Files`
   - Build the project

## Option 2: Use Local AAR (Alternative)

If you have the OpenCV AAR file:

1. Create `libs` folder in `app/` directory
2. Copy `opencv-android-sdk.aar` to `app/libs/`
3. In `app/build.gradle`, add:
   ```gradle
   implementation files('libs/opencv-android-sdk.aar')
   ```

## Replacing Stub with Full Implementation

After adding the OpenCV module:

1. **Replace the stub file:**
   - The current `MotionAnalyzer.kt` is a stub that allows the app to build without OpenCV
   - A full implementation is saved as `MotionAnalyzer.kt.opencv`
   - Copy the contents of `MotionAnalyzer.kt.opencv` to replace `MotionAnalyzer.kt`

2. **Or manually:**
   - Uncomment the OpenCV imports in `MotionAnalyzer.kt`
   - Replace the `processFrameStub()` method with the full `processFrame()` implementation
   - Add OpenCV Mat object declarations and methods

## Verification

After setup, the MotionAnalyzer should initialize OpenCV successfully. Check logs for:
```
MotionAnalyzer: OpenCV initialized successfully
```

Instead of:
```
MotionAnalyzer: STUB MODE - OpenCV not available
```

