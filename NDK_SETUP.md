# NDK Environment Variable Setup Guide

## NDK Path for Your System

Based on your Android SDK location, your NDK path should be:
```
C:/Users/Chaitany Kakde/AppData/Local/Android/Sdk/ndk/29.0.14206865
```

**Note:** Your installed NDK version is `29.0.14206865` (not 26.3.11579264)

**Note:** If this exact version doesn't exist, check what NDK versions are installed in:
```
C:/Users/Chaitany Kakde/AppData/Local/Android/Sdk/ndk/
```

## Method 1: Set Environment Variable (Recommended)

### Steps for Windows:

1. **Open System Properties:**
   - Press `Win + X` and select "System"
   - OR Right-click "This PC" → Properties
   - OR Press `Win + R`, type `sysdm.cpl`, press Enter

2. **Access Environment Variables:**
   - Click "Advanced system settings" (left sidebar)
   - Click "Environment Variables..." button (bottom right)

3. **Add NDK_HOME Variable:**
   - Under "User variables" (top section), click "New..."
   - Variable name: `NDK_HOME`
   - Variable value: `C:/Users/Chaitany Kakde/AppData/Local/Android/Sdk/ndk/29.0.14206865`
   - Click "OK"

4. **Add to PATH (Optional but Recommended):**
   - In "User variables", find and select "Path"
   - Click "Edit..."
   - Click "New"
   - Add: `C:/Users/Chaitany Kakde/AppData/Local/Android/Sdk/ndk/29.0.14206865`
   - Click "OK" on all dialogs

5. **Restart Your IDE:**
   - Close Android Studio/Cursor completely
   - Reopen it to pick up the new environment variables

## Method 2: Set in local.properties (Already Done)

The `local.properties` file has been updated with the NDK path. This method works for Gradle builds but may not be picked up by all tools.

## Verify Installation

To verify your NDK is accessible:

1. **Check if NDK exists:**
   ```powershell
   Test-Path "C:/Users/Chaitany Kakde/AppData/Local/Android/Sdk/ndk/29.0.14206865"
   ```

2. **Check environment variable (after restart):**
   ```powershell
   $env:NDK_HOME
   ```

3. **List available NDK versions:**
   ```powershell
   Get-ChildItem "C:/Users/Chaitany Kakde/AppData/Local/Android/Sdk/ndk"
   ```

## Troubleshooting

### If NDK version 29.0.14206865 doesn't exist:

1. Check what versions are installed:
   ```
   C:/Users/Chaitany Kakde/AppData/Local/Android/Sdk/ndk/
   ```

2. Update the path in:
   - Environment variable `NDK_HOME`
   - `local.properties` file (ndk.dir property)
   - `build.gradle` (ndkVersion property) - **Already updated to 29.0.14206865**

### If build still fails:

1. Make sure Android Studio SDK Manager has NDK installed:
   - Tools → SDK Manager → SDK Tools tab
   - Check "Show Package Details"
   - Find "NDK (Side by side)" and ensure version 29.0.14206865 is checked

2. Sync Gradle files:
   - File → Sync Project with Gradle Files

3. Clean and rebuild:
   ```powershell
   ./gradlew clean
   ./gradlew build
   ```

