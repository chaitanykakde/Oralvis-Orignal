# feature.camera

## Owned Responsibilities

- **Camera lifecycle**: Opening, closing, state management (`CameraLifecycleManager`, `CameraStateStore`)
- **Camera startup**: Device discovery, permission handling, initial configuration (`CameraStartupCoordinator`)
- **Camera controls**: Manual controls (focus, exposure, white balance), auto/manual toggles, throttling (`CameraControlCoordinator`)
- **Camera modes**: Photo/Video/Fluorescence mode switching (`CameraModeController`, `FluorescenceModeAdapter`)
- **Preview management**: Surface handling, callback routing (`PreviewSurfaceManager`, `PreviewCallbackRouter`)
- **Capture operations**: Photo capture, video recording, file I/O (`PhotoCaptureHandler`, `VideoCaptureHandler`)

## Forbidden Responsibilities

- ❌ Session management (belongs to `feature.session`)
- ❌ Patient selection (belongs to `feature.session`)
- ❌ Guided capture logic (belongs to `feature.guided`)
- ❌ Gallery/media organization (belongs to `feature.gallery`)
- ❌ Cloud sync orchestration (belongs to sync layer)
- ❌ Direct database writes (use `MediaRepository`)

## Public API Surface

### Coordinators
- `CameraStartupCoordinator` - Camera discovery and initialization
- `CameraControlCoordinator` - Manual controls and resolution management
- `CameraLifecycleManager` - Open/close coordination

### Handlers
- `PhotoCaptureHandler` - Still image capture
- `VideoCaptureHandler` - Video recording
- `CameraModeController` - Mode switching

### Interfaces
- `PhotoCaptureHost` - Activity interface for photo capture
- `VideoCaptureHost` - Activity interface for video capture
- `CameraModeUi` - UI callback interface for mode changes

### State
- `CameraStateStore` - Camera state container

## Architecture Rules

1. Activities MUST NOT call camera SDK directly
2. All camera operations MUST go through coordinators/handlers
3. Capture handlers MUST use host interfaces for UI updates
4. Mode switching MUST be atomic (no partial state)
5. Resolution changes MUST include safety timeouts
