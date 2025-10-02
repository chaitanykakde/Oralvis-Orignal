# Video Resolution Changing Feature

## Overview
This feature allows users to change the video resolution of the USB camera through the settings panel. The implementation uses the libausbc library's built-in resolution changing capabilities.

## How to Use
1. Connect your USB camera
2. Open the camera app
3. Tap the **Settings** button
4. In the settings panel, you'll see a **Video Resolution** section
5. Use the dropdown spinner to select your desired resolution
6. The current resolution is displayed below the spinner
7. Resolution changes take effect immediately (camera will restart briefly)

## Technical Implementation

### Key Components
- **Resolution Detection**: Automatically detects all supported resolutions from the USB camera
- **UI Integration**: Added resolution spinner to the existing settings bottom sheet
- **Safe Resolution Change**: Prevents resolution changes during video recording
- **Current Resolution Display**: Shows the currently active resolution

### Supported Resolutions
The app automatically detects and displays all resolutions supported by your USB camera. Common resolutions include:
- 1920x1080 (Full HD)
- 1280x720 (HD)
- 640x480 (VGA)
- And others depending on your camera capabilities

### Code Changes
1. **MainActivity.kt**: Added resolution management methods and UI handling
2. **bottom_sheet_camera_settings.xml**: Added resolution selection UI components

### Safety Features
- **Recording Protection**: Cannot change resolution while recording video
- **Error Handling**: Graceful error handling if resolution change fails
- **Automatic Fallback**: Falls back to default resolution if selected resolution is not supported
- **Non-Destructive**: Does not affect existing camera functionality

### Logging
Resolution changes are logged with the tag "ResolutionManager" for debugging purposes.

## Troubleshooting
- If no resolutions appear in the dropdown, ensure your USB camera is properly connected
- If resolution change fails, try disconnecting and reconnecting the camera
- Some cameras may have limited resolution support
- Resolution changes require camera restart, which may take 1-2 seconds
- **Fixed Issue**: Multiple resolution changes now work properly without needing to restart the app
- **Fixed Issue**: "Camera not available" error after first resolution change has been resolved

## Future Enhancements
- Save preferred resolution in app preferences
- Display aspect ratio information
- Add resolution quality indicators (HD, Full HD, etc.)
- Support for different frame rates per resolution
