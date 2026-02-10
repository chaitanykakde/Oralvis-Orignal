# feature.guided

## Owned Responsibilities

- **Guided capture orchestration**: Auto-capture, motion analysis, guidance overlay (`GuidedController`)
- **Sequence management**: Upper/lower arch capture sequences, recapture logic
- **Motion detection**: Frame analysis for stability and readiness (`MotionAnalyzer` - internal)
- **Audio feedback**: Guidance audio cues (`GuidedAudioManager` - internal)
- **Session bridging**: Integration with session/media infrastructure (`SessionBridge`)

## Forbidden Responsibilities

- ❌ Patient selection (belongs to `feature.session`)
- ❌ Camera capture execution (use `PhotoCaptureHandler` via `SessionBridge`)
- ❌ Database writes (delegate via `SessionBridge`)
- ❌ Gallery presentation (belongs to `feature.gallery`)
- ❌ Direct camera control (coordinate via callbacks)

## Public API Surface

### Controller
- `GuidedController` - **SINGLE PUBLIC ENTRY POINT**
  - `initializeIfNeeded()` - Lazy initialization
  - `enable()` / `disable()` - Start/stop guided capture
  - `isGuidedActive()` - Check if guided mode is active
  - `handleManualCapture()` - Process manual shutter during guided session
  - `onFirstFrame()` - Handle first preview frame
  - `triggerCapture()` - Manually trigger capture
  - `getPreviewCallback()` - Get IPreviewDataCallBack for camera registration

### Interface
- `SessionBridge` - Activity-provided bridge to session/capture infrastructure
  - `ensureGuidedSessionId()` - Get or create guided session ID
  - `onGuidedCaptureRequested()` - Delegate capture to photo handler
  - `onGuidedSessionComplete()` - Notify session completion
  - `onRecaptureLower()` / `onRecaptureUpper()` - Delete arch media for recapture

### Constants
- `GuidedController.DENTAL_ARCH_LOWER`
- `GuidedController.DENTAL_ARCH_UPPER`

## Internal Components (do NOT import directly)

- `GuidedCaptureManager` - Internal orchestrator
- `GuidedSessionController` - Session state machine
- `MotionAnalyzer` - Frame motion detection
- `AutoCaptureController` - Auto-capture timing
- `GuidedAudioManager` - Audio feedback
- `GuidanceOverlayView` - UI overlay
- `CaptureFlashController` - Flash effect

## Architecture Rules

1. External code MUST ONLY import `GuidedController` and `SessionBridge`
2. `guidedcapture.*` package is INTERNAL - do not import directly
3. Guided capture MUST use `SessionBridge` for all session/capture operations
4. Activities MUST NOT access `GuidedCaptureManager` directly
5. Guided overlay MUST be attached to root container, not camera surface
