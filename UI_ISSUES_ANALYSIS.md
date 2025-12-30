# OralVis Android Camera App - UI & Session Issues Analysis

**Task Type:** Read-Only Analysis & Documentation  
**Date:** 2025-12-27  
**Status:** Analysis Complete - No Code Changes Made

---

## Table of Contents

1. [Issue 1: Camera Resolution Handling](#issue-1-camera-resolution-handling)
2. [Issue 2: Guided Session Auto-Restart](#issue-2-guided-session-auto-restart)
3. [Issue 3: Lower/Upper Arch Icons Not Visible](#issue-3-lowerupper-arch-icons-not-visible)
4. [Issue 4: Guided Capture Instructions Text](#issue-4-guided-capture-instructions-text)
5. [Issue 5: Gallery View Layout](#issue-5-gallery-view-layout)
6. [Issue 6: Start Session Button Not Visible](#issue-6-start-session-button-not-visible)
7. [Issue 7: Recent Patient List - Unwanted Info](#issue-7-recent-patient-list---unwanted-info)

---

## Issue 1: Camera Resolution Handling

### Current Implementation Summary

**How Resolution is Selected:**
- Resolution selection is handled via a `Spinner` widget located in the settings bottom sheet (`bottom_sheet_camera_settings.xml`)
- The resolution selector is also visible in the top toolbar (`activity_main.xml` lines 300-329) as a clickable `LinearLayout` that shows current resolution
- When clicked, it opens the settings panel which contains the actual `Spinner` for selection

**Default Resolution:**
- **Current Default:** The app does NOT set a hardcoded default resolution on startup
- Resolution is determined dynamically from the camera's actual request:
  - In `loadAvailableResolutions()` (MainActivity.kt:1363-1371), the app reads `cameraRequest.previewWidth` and `previewHeight` from the camera
  - This actual resolution is then stored as `currentResolution`
- **Fallback Default:** If camera provides no resolutions, fallback defaults are set (line 1393-1398):
  ```kotlin
  availableResolutions.addAll(listOf(
      PreviewSize(1920, 1080),
      PreviewSize(1280, 720),
      PreviewSize(640, 480)
  ))
  currentResolution = PreviewSize(640, 480)  // Lowest resolution as fallback
  ```

**1920x1080 Availability:**
- 1920x1080 IS included in the fallback list (line 1394)
- However, the actual resolution list comes from `camera.getAllPreviewSizes()` (line 1354)
- Whether 1920x1080 exists depends on what the USB camera hardware reports
- The list is sorted by total pixels (descending) in line 1360, so if 1920x1080 exists, it should appear first

**Why 1920x1080 is NOT Applied on Start:**
1. **Camera Initialization:** When camera opens (MainActivity.kt:1886-1893), resolution is calculated based on screen aspect ratio:
   ```kotlin
   val baseWidth = 1920  // Used for calculation
   val recordingWidth = baseWidth
   val recordingHeight = (baseWidth / screenAspectRatio).toInt()
   ```
   This creates a resolution matching screen aspect ratio, NOT necessarily 1920x1080.

2. **Resolution Sync:** The app reads the camera's actual resolution after opening (line 1363-1371) and uses that as `currentResolution`. If the camera was initialized with a different aspect ratio, that becomes the "current" resolution.

3. **No Explicit 1920x1080 Request:** There is no code that explicitly requests 1920x1080 on startup. The camera request uses screen-based calculation.

**Resolution Selector UI Location:**
- **Top Toolbar:** Visible in main camera view (`activity_main.xml:300-329`) - shows current resolution as text with dropdown icon
- **Settings Panel:** Actual `Spinner` is in `bottom_sheet_camera_settings.xml:83-89`
- **Why Outside Settings:** The top toolbar selector is a convenience shortcut that opens the settings panel. The actual selection happens in the settings bottom sheet.

### Files & Classes Involved

- `MainActivity.kt`:
  - `loadAvailableResolutions()` (line 1346-1405)
  - `setupResolutionSpinner()` (line 1525-1609)
  - `changeResolution()` (line 1611-1680)
  - `currentResolution` variable (line 97)
  - Camera initialization (line 1870-1897)
- `activity_main.xml`: Resolution selector in toolbar (line 300-329)
- `bottom_sheet_camera_settings.xml`: Resolution spinner in settings (line 83-89)
- `libausbc/CameraClient.kt`: `getAllPreviewSizes()`, `updateResolution()`

### Exact Code Paths

1. **On App Start:**
   - Camera opens → `onConnectDec()` callback (line 1869)
   - Creates `CameraRequest` with screen-based resolution (line 1886-1893)
   - Camera initializes with calculated resolution
   - `loadAvailableResolutions()` called → reads actual camera resolution → sets `currentResolution`

2. **On Resolution Change:**
   - User clicks top toolbar selector → `showResolutionDropdown()` (line 825) → opens settings panel
   - User selects from spinner → `onItemSelected()` (line 1568) → `changeResolution()` (line 1611)
   - `updateResolution()` called on camera (line 1640) → camera restarts with new resolution

### Risk Notes

- Changing default resolution requires modifying camera initialization logic (line 1886-1893)
- Resolution list depends on camera hardware capabilities - may vary by device
- Camera restart on resolution change (line 1640) causes brief preview interruption

---

## Issue 2: Guided Session Auto-Restart

### Current Implementation Summary

**Session Lifecycle:**
- Guided capture uses `GuidedSessionController` which manages state machine transitions
- States: `READY_TO_SCAN_LOWER` → `SCANNING_LOWER` → `READY_TO_SCAN_UPPER` → `SCANNING_UPPER` → `COMPLETE`

**What Happens on Completion:**
- When guided session reaches `COMPLETE` state and user clicks main action button:
  - `onMainActionClicked()` in `GuidedSessionController.kt` (line 84-92) handles `COMPLETE` state
  - **Auto-Restart Logic:** The code explicitly resets to `READY_TO_SCAN_LOWER`:
    ```kotlin
    ScanningState.COMPLETE -> {
        // Full reset to beginning of lower scan for the SAME guidedSessionId.
        lowerSequence = 0
        upperSequence = 0
        state = ScanningState.READY_TO_SCAN_LOWER
        autoCaptureController.isProcessingActive = false
    }
    ```

**Session Completion Detection:**
- Completion is detected in `onMainActionClicked()` when state is `SCANNING_UPPER` and button is clicked (line 78-82)
- `sessionBridge.onGuidedSessionComplete(guidedSessionId)` is called (line 81)
- However, the state immediately transitions to `COMPLETE` (line 79), and clicking again triggers restart

**Why New Session Starts Automatically:**
- **Intentional Design:** The comment in code (line 85-87) states: "Full reset to beginning of lower scan for the SAME guidedSessionId. A brand new guidedSessionId will be allocated only when the user presses the global 'Start Session' button again."
- This is **intentional behavior** - not a bug. The same `guidedSessionId` is reused, allowing users to re-scan without creating a new session.
- The reset happens immediately when user clicks the main action button in `COMPLETE` state (line 84-92)

### Files & Classes Involved

- `GuidedSessionController.kt`:
  - `onMainActionClicked()` (line 61-95) - handles state transitions
  - `startGuidedSession()` (line 45-54) - initializes new session
  - `guidedSessionId` variable (line 37) - tracks current session
- `GuidedCaptureManager.kt`:
  - `enable()` (line 62-91) - starts guided capture
  - `guidedSessionController.startGuidedSession()` (line 89)
- `MainActivity.kt`:
  - `startSessionClickListener` (line 363-410) - triggers guided capture
  - `initializeGuidedCapture()` (line 244-336) - creates session bridge

### Exact Code Paths

1. **Session Start:**
   - User clicks "Start Session" → `startSessionClickListener` (line 363)
   - `guidedCaptureManager.enable()` (line 401)
   - `guidedSessionController.startGuidedSession()` (GuidedCaptureManager.kt:89)
   - New `guidedSessionId` generated (MainActivity.kt:252)

2. **Session Completion:**
   - User finishes upper scan → state becomes `COMPLETE` (GuidedSessionController.kt:79)
   - User clicks main action button → `onMainActionClicked()` (line 84)
   - State resets to `READY_TO_SCAN_LOWER` (line 90)
   - Same `guidedSessionId` is reused (line 85-87 comment)

3. **New Session (Different guidedSessionId):**
   - User clicks "Start Session" button again → new `guidedSessionId` generated (MainActivity.kt:252)

### Risk Notes

- Auto-restart is **intentional** - changing this would require modifying state machine logic
- The `guidedSessionId` persistence means recapture operations target the same session
- Removing auto-restart would require adding explicit "Start New Session" UI

---

## Issue 3: Lower/Upper Arch Icons Not Visible

### Current Implementation Summary

**Icon Definition:**
- Icons are defined as `Bitmap?` variables in `GuidanceOverlayView.kt`:
  - `lowerArchIcon: Bitmap?` (line 69)
  - `upperArchIcon: Bitmap?` (line 70)

**Icon Initialization:**
- In `GuidedCaptureManager.kt` (line 57-59):
  ```kotlin
  // Arch icons can be replaced with specific lower/upper assets later.
  overlayView.lowerArchIcon = null
  overlayView.upperArchIcon = null
  ```
- **Icons are explicitly set to `null`** - they are not loaded from resources

**Visibility Control:**
- Icons are drawn in `GuidanceOverlayView.drawControlPanel()` (line 163-170):
  ```kotlin
  val icon = when (scanningState) {
      ScanningState.READY_TO_SCAN_LOWER,
      ScanningState.SCANNING_LOWER -> lowerArchIcon
      ScanningState.READY_TO_SCAN_UPPER,
      ScanningState.SCANNING_UPPER,
      ScanningState.COMPLETE -> upperArchIcon
  }
  icon?.let { /* draw icon */ }
  ```
- The `icon?.let {}` block only executes if icon is not null (line 171)

**Why Icons Are Not Visible:**
- **Root Cause:** Icons are set to `null` in initialization (GuidedCaptureManager.kt:58-59)
- No code exists to load icon bitmaps from drawable resources
- The drawing code checks for null and skips drawing if null (line 171)

**Theme/Tint/Background:**
- Not applicable - icons are never drawn because they are null
- The drawing code uses `canvas.drawBitmap()` (line 178-185) but is never reached

### Files & Classes Involved

- `GuidanceOverlayView.kt`:
  - Icon variables (line 69-70)
  - Icon drawing logic (line 163-185)
- `GuidedCaptureManager.kt`:
  - Icon initialization (line 57-59)
- No drawable resource files found for arch icons

### Exact Code Paths

1. **Icon Initialization:**
   - `GuidedCaptureManager` created → `init` block (line 36-60)
   - Icons set to `null` (line 58-59)

2. **Icon Drawing:**
   - `GuidanceOverlayView.onDraw()` → `drawControlPanel()` (line 140)
   - Icon selected based on state (line 163-170)
   - `icon?.let {}` check fails because icon is null (line 171)
   - Icon drawing code never executes

### Risk Notes

- Icons need to be loaded from drawable resources or created programmatically
- Icon drawing logic is correct - just needs non-null bitmaps
- Icon size calculation (line 172-176) will work once bitmaps are provided

---

## Issue 4: Guided Capture Instructions Text

### Current Implementation Summary

**Instruction Strings:**
- Instructions are **hardcoded** in `GuidedSessionController.kt` in the `notifyUi()` method (line 159-190)
- Not stored in `strings.xml` - directly embedded in code

**Instruction Sequence:**
1. `READY_TO_SCAN_LOWER`: "Place scanner at LEFT of LOWER arch." / "Start Lower Scan" / "1/2"
2. `SCANNING_LOWER`: "Move along LOWER arch to the right." / "Finish Lower Scan" / "1/2"
3. `READY_TO_SCAN_UPPER`: "Place scanner at LEFT of UPPER arch." / "Start Upper Scan" / "2/2"
4. `SCANNING_UPPER`: "Move along UPPER arch to the right." / "Finish Upper Scan" / "2/2"
5. `COMPLETE`: "Scan complete. Click to restart." / "Restart Scan" / "2/2"

**How Instructions Advance:**
- Instructions are updated via `notifyUi()` which is called after every state transition (line 94, 122)
- State transitions happen in `onMainActionClicked()` (line 61-95)
- Each button click advances to next state and triggers `notifyUi()`

**Lower/Upper Arch Switch:**
- Handled automatically by state machine:
  - Lower states (`READY_TO_SCAN_LOWER`, `SCANNING_LOWER`) show lower instructions
  - Upper states (`READY_TO_SCAN_UPPER`, `SCANNING_UPPER`, `COMPLETE`) show upper instructions
  - State-based selection in `notifyUi()` (line 160-190)

### Files & Classes Involved

- `GuidedSessionController.kt`:
  - `notifyUi()` (line 159-190) - generates instruction text
  - `onMainActionClicked()` (line 61-95) - triggers state transitions
- `GuidedCaptureManager.kt`:
  - `onUiStateUpdated()` (line 142-156) - receives instructions and updates overlay
- `GuidanceOverlayView.kt`:
  - `mainText`, `buttonText`, `progressText` properties (line 38-54)
  - Text drawing methods (line 127-138)

### Exact Code Paths

1. **Instruction Update:**
   - State changes → `notifyUi()` called (GuidedSessionController.kt:94)
   - `notifyUi()` generates text based on state (line 160-190)
   - `listener.onUiStateUpdated()` called (line 191-195)
   - `GuidedCaptureManager.onUiStateUpdated()` receives text (line 142-156)
   - Overlay view properties updated (line 148-151)
   - `invalidate()` triggers redraw (GuidanceOverlayView.kt:35, 41, 47)

2. **Step Transitions:**
   - User clicks main button → `onMainActionClicked()` (line 61)
   - State transitions (line 63-92)
   - `notifyUi()` called (line 94)
   - UI updates with new instructions

### Risk Notes

- Instructions are hardcoded - moving to `strings.xml` would require refactoring
- Text is generated per-state - adding new states requires updating `notifyUi()`
- Instruction text is tightly coupled to state machine logic

---

## Issue 5: Gallery View Layout

### Current Implementation Summary

**Layout Implementation:**
- Gallery uses `RecyclerView` with `GridLayoutManager` in `GalleryActivity.kt` (line 70):
  ```kotlin
  binding.mediaRecyclerView.layoutManager = GridLayoutManager(this, 4)
  ```
- **Span Count: 4** - creates 4 columns in grid layout

**Adapter:**
- Uses `SessionMediaGridAdapter` (line 67-69, 166-169)
- Adapter is recreated when media list updates (line 166-169)

**Expected vs Actual:**
- Current implementation: 4-column grid
- If layout doesn't match expectations, possible causes:
  1. Item layout (`item_gallery.xml`) may have incorrect dimensions
  2. GridLayoutManager span count may need adjustment
  3. Item spacing/padding may affect visual appearance

**Layout Manager Configuration:**
- `GridLayoutManager` with 4 spans (line 70)
- No custom span size lookup or spacing configuration visible

### Files & Classes Involved

- `GalleryActivity.kt`:
  - `setupRecycler()` (line 66-72)
  - `loadMediaForCurrentPatient()` (line 154-186)
- `SessionMediaGridAdapter` (referenced but not found in search results)
- `item_gallery.xml` (not found - may be named differently)

### Exact Code Paths

1. **Gallery Setup:**
   - `onCreate()` → `setupRecycler()` (line 53)
   - `GridLayoutManager` created with 4 spans (line 70)
   - Adapter created and assigned (line 67-71)

2. **Media Loading:**
   - `loadMediaForCurrentPatient()` (line 154)
   - Media fetched from database (line 163)
   - Adapter recreated with new list (line 166-169)
   - RecyclerView updated (line 170)

### Risk Notes

- Span count is hardcoded to 4 - changing requires modifying line 70
- Adapter recreation on every update (line 166) may cause performance issues
- Item layout file not found - may need to locate actual layout XML

---

## Issue 6: Start Session Button Not Visible

### Current Implementation Summary

**Button Definition:**
- Two "Start Session" buttons exist:
  1. **Top Toolbar Button:** `btnStartGuidedSession` (activity_main.xml:253-263) - always visible
  2. **Bottom Button:** `btnStartSession` (activity_main.xml:512-535) - **visibility set to GONE**

**Visibility Logic:**
- In `MainActivity.setupNewUI()` (line 687):
  ```kotlin
  binding.btnStartSession.visibility = View.GONE
  ```
- Comment explains: "temporarily hidden; top-bar button is primary entry" (line 511)

**Conditions for Visibility:**
- Top button (`btnStartGuidedSession`) is always visible in toolbar
- Bottom button (`btnStartSession`) is explicitly hidden (line 687)
- Both buttons use the same click listener (line 411-412)

**Guided/Manual Mode:**
- Both buttons trigger guided capture (line 363-410)
- No separate manual mode button exists
- Button visibility is not affected by mode - it's hardcoded to GONE

### Files & Classes Involved

- `MainActivity.kt`:
  - `setupNewUI()` (line 687) - sets bottom button to GONE
  - `startSessionClickListener` (line 363-410) - handles clicks
  - Button click listeners (line 411-412)
- `activity_main.xml`:
  - Top button (line 253-263)
  - Bottom button (line 512-535)

### Exact Code Paths

1. **Button Initialization:**
   - `onCreate()` → `setupNewUI()` (line 436)
   - Bottom button visibility set to GONE (line 687)
   - Click listeners assigned (line 411-412)

2. **Button Click:**
   - User clicks either button → `startSessionClickListener` (line 363)
   - Guided capture enabled (line 401)
   - Overlay shown (line 66-81 in GuidedCaptureManager)

### Risk Notes

- Bottom button is intentionally hidden - making it visible requires changing line 687
- Both buttons share same functionality - no differentiation needed
- Top button is primary entry point per design

---

## Issue 7: Recent Patient List - Unwanted Info

### Current Implementation Summary

**Patient Card Layout:**
- Uses `item_patient_row.xml` layout
- Displayed via `PatientListAdapter` in home screen

**Fields Shown:**
1. **patientName** (line 34-40): Patient's display name - **WANTED**
2. **patientSubtitle** (line 42-48): Shows diagnosis + age (e.g., "Student · 22 years") - **WANTED**
3. **patientProblem** (line 58-63): Shows diagnosis or "Review" - **UNWANTED?**
4. **patientTime** (line 66-72): Shows appointment time or "--" - **UNWANTED?**
5. **patientStatus** (line 76-82): Shows check-in status or "IN" - **UNWANTED?**
6. **btnDetails** (line 84-91): Arrow button - **UNWANTED?**

**Data Source:**
- Data comes from `Patient` database entity via `PatientListAdapter.bind()` (line 27-41)
- Fields populated from database:
  - `patient.displayName` → patientName
  - `patient.diagnosis` + `patient.age` → patientSubtitle
  - `patient.diagnosis ?: "Review"` → patientProblem (line 36)
  - `patient.appointmentTime ?: "--"` → patientTime (line 37)
  - `patient.checkInStatus ?: "IN"` → patientStatus (line 38)

**Why Unwanted Fields Appear:**
- **Hardcoded Fallbacks:** Fields show default values when data is null:
  - `patientProblem` shows "Review" if diagnosis is null (line 36)
  - `patientTime` shows "--" if appointmentTime is null (line 37)
  - `patientStatus` shows "IN" if checkInStatus is null (line 38)
- These fields are always visible in layout (item_patient_row.xml) regardless of data availability

**Backend vs Hardcoded:**
- Data structure comes from database (`Patient` entity)
- Display logic is in adapter (PatientListAdapter.kt:27-41)
- Fallback values are hardcoded in adapter, not from backend

### Files & Classes Involved

- `PatientListAdapter.kt`:
  - `bind()` method (line 27-41) - populates card fields
- `item_patient_row.xml`:
  - Layout definition with all fields (line 1-95)
- `Patient.kt` (database entity):
  - Provides data fields (diagnosis, appointmentTime, checkInStatus)

### Exact Code Paths

1. **Card Population:**
   - Adapter `onBindViewHolder()` → `bind()` (PatientListAdapter.kt:50)
   - Fields populated from Patient entity (line 28-38)
   - Fallback values applied for null fields (line 36-38)

2. **Data Flow:**
   - Database → Patient entity → Adapter → View binding
   - All fields in layout are always rendered, even with null/empty data

### Risk Notes

- Removing fields requires modifying both layout XML and adapter code
- "Review", "--", "IN" are hardcoded fallbacks - safe to remove if fields are hidden
- Arrow button (btnDetails) is separate from data fields - can be removed independently
- Patient entity fields may be used elsewhere - verify before removing from database

---

## Summary of Findings

### Issue Severity Assessment

1. **Resolution Handling:** Medium - Functional but doesn't default to 1920x1080
2. **Guided Auto-Restart:** Low - Intentional design feature
3. **Arch Icons:** High - Icons are null, need resource loading
4. **Instructions Text:** Low - Hardcoded but functional
5. **Gallery Layout:** Medium - Depends on item layout file (not found)
6. **Start Session Button:** Low - Intentionally hidden, top button works
7. **Patient List Info:** Medium - Unwanted fields can be removed

### Common Patterns

- Many UI elements use hardcoded values instead of resources
- State management is centralized in controllers
- Visibility is often controlled programmatically, not in XML
- Fallback values are used extensively for null data

### Files Requiring Attention (For Future Fixes)

1. `GuidedCaptureManager.kt` - Icon loading (line 57-59)
2. `MainActivity.kt` - Resolution default (line 1886-1893)
3. `PatientListAdapter.kt` - Unwanted fields (line 27-41)
4. `item_patient_row.xml` - Layout cleanup
5. `GuidedSessionController.kt` - Instruction strings (line 159-190)

---

**END OF ANALYSIS**

