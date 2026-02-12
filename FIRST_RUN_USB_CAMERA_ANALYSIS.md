# First-Run USB Permission → Camera Not Starting — Root Cause Analysis

**Scope:** OralVis app after manifest USB auto-launch removal.  
**Reference:** AndroidUSBCamera-3.3.3 demo.  
**No fixes in this document — analysis only.**

---

## 1. Full First-Run Event Order (OralVis)

### Step-by-step sequence

| Step | Where | What happens |
|------|--------|--------------|
| 1 | `onCreate()` | `previewSurfaceManager.attachTo(binding.cameraTextureView)` — registers `SurfaceTextureListener`. Surface may not be ready yet. |
| 2 | `onCreate()` | `ensureUsbMonitorRegistered()` — creates `mCameraClient`, registers `USBMonitor`. |
| 3 | `onCreate()` | `requestPermissionForAlreadyAttachedDevices()` — if camera already attached: add to `mCameraMap`, set `usbPermissionPending = true`, set status "USB Camera detected - Requesting permission...", call `client.requestPermission(device)`. System shows USB permission dialog. |
| 4 | (async) | User sees "Allow" / "Deny". **Main thread is blocked on user choice.** |
| 5 | (async) | **Possible race:** `TextureView` may get laid out; `onSurfaceTextureAvailable` can fire and set `isSurfaceTextureReady = true`, then call `onInitializeCameraIfReady()` → `cameraStartupCoordinator.onSurfaceReady()` → `initializeCameraIfReady()`. At this time **permission not yet granted**, so `mPendingCameraOpen` is **null**. So we run `initializeCameraIfReady()` with nothing to open; it returns after `ensureUsbMonitorRegistered()` and checking null `mPendingCameraOpen`. |
| 6 | User taps "Allow" | System sends `ACTION_USB_PERMISSION`; `USBMonitor` receives it, calls `processConnect(device)` → `OnDeviceConnectListener.onConnect()` → `MultiCameraClient` posts to main handler → `onConnectDev(device, ctrlBlock)` runs. |
| 7 | `onConnectDev()` **start** | `usbPermissionPending = false`. |
| 8 | `onConnectDev()` | **Immediately** calls `tryDeferredPermissionChecks()`. |
| 9 | `tryDeferredPermissionChecks()` | Because `deferredRuntimePermissionCheck == true` (set in step 10 below), calls `checkPermissions()`. |
| 10 | `checkPermissions()` | Now `usbPermissionPending` is false. Gets required permissions; if any missing, may show runtime permission dialog or request; **and** at end of `checkPermissions()` always calls `cameraStartupCoordinator.onSurfaceReady()` ("FORCE camera initialization on cold launch"). So `initializeCameraIfReady()` runs **again**. |
| 11 | **Critical** | At this moment we are **still inside `onConnectDev()`** and have **not yet** run the code that gets `mCameraMap[device.deviceId]`, sets `mCurrentCamera`, builds `CameraRequest`, or sets `mPendingCameraOpen`. So `initializeCameraIfReady()` runs with **`mPendingCameraOpen == null`** again — nothing to open. |
| 12 | `onConnectDev()` **continued** | After `tryDeferredPermissionChecks()` returns: set status "Camera connected - Opening camera...", get camera from map, set control block, set state callback, build `cameraRequest`, then **either** `camera.openCamera(...)` if `isSurfaceTextureReady` **or** `mPendingCameraOpen = Pair(camera, cameraRequest)`. |
| 13 | **Bug** | If surface was **not** ready at step 12: we set `mPendingCameraOpen`. But **nothing calls `onSurfaceReady()` / `initializeCameraIfReady()` again** after this. The only triggers for `initializeCameraIfReady()` are (a) `onSurfaceTextureAvailable` (already fired in step 5 with null pending) and (b) `checkPermissions()` / other permission paths (we already ran from step 9 with null pending). So **`mPendingCameraOpen` is never consumed** → camera never opens. |
| 14 | UI | Status stays "Camera connected - Opening camera..." or user still sees "Camera detected - Requesting permission..." if that text was not updated; preview never starts. |

### Confirmation

- **Is `onConnectDev()` called on first permission grant?** Yes.
- **Is `usbPermissionPending` set to false?** Yes, at start of `onConnectDev()`.
- **Is `openCamera()` called inside `onConnectDev()`?** Only when `cameraStateStore.isSurfaceTextureReady` is true at the moment we reach that branch (step 12). On first run, surface is often not ready yet, so we set `mPendingCameraOpen` instead.
- **Is `mPendingCameraOpen` set?** Yes, when surface is not ready (step 12).
- **Is `initializeCameraIfReady()` triggered after we set `mPendingCameraOpen`?** **No.** It is triggered **before** we set it (inside `tryDeferredPermissionChecks()` at the start of `onConnectDev()`), so it sees null and does nothing. It is never called again after we set `mPendingCameraOpen`.

---

## 2. Permission Double-Request

- **`requestPermissionForAlreadyAttachedDevices()`:** Runs once per lifecycle (`hasRunInitialUsbDeviceScan`). Adds device to `mCameraMap`, sets status and `usbPermissionPending`, calls `client.requestPermission(device)` for first matching camera. One dialog for camera.
- **`onAttachDev()`:** Only runs when system sends `ACTION_USB_DEVICE_ATTACHED` (device plugged **while app running**). For **first launch with device already plugged**, attach broadcast often not re-sent, so `onAttachDev()` may never run. If it did run, we have `if (mCameraMap.containsKey(device.deviceId)) return`, so we would not call `requestPermission()` again for same device.
- **Conclusion:** Double permission request is **not** the primary cause of "camera not starting." At most one USB permission dialog for the camera on first run. The issue is **not** "second internal request" or double dialog for the same device.

---

## 3. `usbPermissionPending` Flag

- **Set true:** `onAttachDev()` (when device attaches at runtime); `requestPermissionForAlreadyAttachedDevices()` when `!usbManager.hasPermission(device)`.
- **Set false:** `onConnectDev()` (first line); `onCancelDev()`.
- **Effect:** When true, `checkPermissions()` returns early and sets `deferredRuntimePermissionCheck = true`, then calls `onSurfaceReady()`. When false, `checkPermissions()` proceeds to normal runtime permission logic and at the end calls `onSurfaceReady()`.
- **Does `onConnectDev()` always run after user grants?** Yes (via `USBMonitor` → `processConnect` → callback).
- **Does any path leave it stuck true?** No. Permission grant path clears it in `onConnectDev()`; deny path clears it in `onCancelDev()`.
- **Conclusion:** `usbPermissionPending` is **not** stuck. The problem is **not** that `tryDeferredPermissionChecks()` is skipped; the problem is that when it runs, it triggers the **consumer** of `mPendingCameraOpen` **before** `onConnectDev()` has set it.

---

## 4. Surface Readiness Timing and the Race

- **When does `isSurfaceTextureReady` become true?** When `TextureView.SurfaceTextureListener.onSurfaceTextureAvailable` runs (in `PreviewSurfaceManager`). That can be before or after the user taps "Allow" on the USB permission dialog.
- **Two orders:**

  **Order A — Surface ready before permission grant (common first-run failure):**  
  1) `onSurfaceTextureAvailable` → `isSurfaceTextureReady = true` → `onSurfaceReady()` → `initializeCameraIfReady()` with `mPendingCameraOpen == null` → no open.  
  2) User taps Allow → `onConnectDev()` → `tryDeferredPermissionChecks()` → `checkPermissions()` → `onSurfaceReady()` again → `initializeCameraIfReady()` still with `mPendingCameraOpen == null` (we haven’t set it yet).  
  3) Then `onConnectDev()` sets `mPendingCameraOpen`. No further call to `onSurfaceReady()` / `initializeCameraIfReady()` → **camera never opens.**

  **Order B — Permission grant before surface ready:**  
  1) User taps Allow → `onConnectDev()` → `tryDeferredPermissionChecks()` runs (consumer runs with null).  
  2) Then we set `mPendingCameraOpen`.  
  3) Later `onSurfaceTextureAvailable` → `onSurfaceReady()` → `initializeCameraIfReady()` sees non-null `mPendingCameraOpen` → openCamera. **Works.**

- **Refined cause:** The consumer (`initializeCameraIfReady()`) is triggered **inside** `onConnectDev()` via `tryDeferredPermissionChecks()` → `checkPermissions()` → `onSurfaceReady()`, and that happens **before** we set `mPendingCameraOpen` in the same `onConnectDev()`. So the consumer always sees null on first run when we would have deferred (surface not ready). So **mPendingCameraOpen is set but never consumed** — a combination of **ordering** and **one-shot** surface callback.

---

## 5. Comparison With Demo App (AndroidUSBCamera-3.3.3)

### Demo (CameraFragment) flow

- **Surface first, USB second:** `onSurfaceTextureAvailable` → **`registerMultiCamera()`** (register `USBMonitor` and callbacks). So USB is only registered **after** surface is available.
- **Permission then open in one place:** When user grants, `onConnectDev(device, ctrlBlock)` runs. There the demo does:
  - `mCameraMap[device.deviceId]?.apply { setUsbControlBlock(ctrlBlock) }?.also { camera -> ... openCamera(mCameraView) }`.
  - So **`openCamera(mCameraView)` is called directly from `onConnectDev()`**. No deferred `mPendingCameraOpen`; no second trigger needed.
- **Why it’s safe:** By the time permission is granted, the view has already had its surface (we only registered USB in `onSurfaceTextureAvailable`), so `mCameraView` is valid and `openCamera(mCameraView)` works.

### OralVis flow

- **USB first, surface whenever:** `ensureUsbMonitorRegistered()` and `requestPermissionForAlreadyAttachedDevices()` in `onCreate()` — before we know if the surface is ready.
- **Deferred open:** In `onConnectDev()` we either call `camera.openCamera(...)` if `isSurfaceTextureReady`, or set `mPendingCameraOpen`. The deferred open is consumed only by `initializeCameraIfReady()`, which is driven by `onSurfaceReady()`.
- **Ordering bug:** We call `tryDeferredPermissionChecks()` at the **start** of `onConnectDev()`, which can call `onSurfaceReady()` → `initializeCameraIfReady()` **before** we set `mPendingCameraOpen` later in the same method. So the consumer always runs with null on first run when we defer.

### Structural difference

| Aspect | Demo | OralVis |
|--------|------|--------|
| When USB is registered | After surface available | In onCreate, before surface guaranteed |
| Where openCamera is called | Directly in `onConnectDev()` | In `onConnectDev()` only if surface ready; else deferred to `initializeCameraIfReady()` |
| Deferred open consumed by | N/A | `initializeCameraIfReady()` (only when `onSurfaceReady()` is called) |
| When consumer runs relative to setting pending | N/A | **Before** we set `mPendingCameraOpen` (inside same `onConnectDev()` via `tryDeferredPermissionChecks`) |

---

## 6. First-Run Special Case: Runtime CAMERA Permission

- **Does OralVis request runtime CAMERA (and others) on first install?** Yes, in `checkPermissions()` (required list includes CAMERA, RECORD_AUDIO, storage).
- **Interaction:** On first launch, `checkPermissions()` is called from `onCreate()`. If `usbPermissionPending` is true, we set `deferredRuntimePermissionCheck = true` and call `onSurfaceReady()` then return. So we don’t show runtime permission dialog yet. When user grants **USB** permission, `onConnectDev()` runs and calls `tryDeferredPermissionChecks()` → `checkPermissions()`. Now `usbPermissionPending` is false, so we proceed; if runtime permissions are missing we request them and still call `onSurfaceReady()` at the end. So we **do** trigger `initializeCameraIfReady()` from that path — but again **before** we set `mPendingCameraOpen` in `onConnectDev()`. So runtime permission logic is not what blocks the open; it’s the **order** of `tryDeferredPermissionChecks()` vs setting `mPendingCameraOpen` in `onConnectDev()`.
- **Does runtime permission callback re-trigger open?** `onRequestPermissionsResult` can call `cameraStartupCoordinator.onSurfaceReady()` when permissions are granted. That could consume `mPendingCameraOpen` **if** it was already set. But on first run the typical order is: USB grant → `onConnectDev()` → `tryDeferredPermissionChecks()` (consumer runs, null) → set `mPendingCameraOpen`. So when we set it, we don’t call `onSurfaceReady()` again. If a **runtime** permission dialog was shown from `tryDeferredPermissionChecks()` and user grants later, then `onRequestPermissionsResult` → `onSurfaceReady()` would run and **could** open — but only if we had set `mPendingCameraOpen` earlier in `onConnectDev()`. So in the “USB first, then runtime” flow, the open could sometimes happen from the runtime callback; in the “surface ready before USB grant” flow, the consumer has already run with null and we never run it again after setting `mPendingCameraOpen`, so camera stays closed.

---

## 7. Root Cause — Exact Answer

**Which of these is true?**

- **A) onConnectDev not firing** — **No.** It fires when user grants USB permission.
- **B) usbPermissionPending stuck** — **No.** It is cleared in `onConnectDev()` and `onCancelDev()`.
- **C) mPendingCameraOpen never consumed** — **Yes.** It is set when surface is not ready, but the only consumer runs **before** it is set (inside `onConnectDev()` via `tryDeferredPermissionChecks()`), so it is never consumed.
- **D) Surface-ready race** — **Yes.** The consumer runs when surface is already ready (or when `checkPermissions()` runs) **before** we set `mPendingCameraOpen` in the same `onConnectDev()`, so the deferred open is never executed.
- **E) Double permission request** — **No.** Not the cause of camera not starting.
- **F) Runtime CAMERA permission interfering** — **Partially.** It doesn’t block the open by itself, but the way `tryDeferredPermissionChecks()` → `checkPermissions()` is called at the **start** of `onConnectDev()` causes the consumer to run too early.
- **G) openCamera not executed due to guard condition** — **Yes.** The “guard” is: the code path that would call `openCamera` (either directly or via consuming `mPendingCameraOpen`) runs **before** we set `mPendingCameraOpen` or decide to open. So the condition “surface ready OR pending set” is never true at the right time for the deferred path.

**Primary root cause:** **Ordering in `onConnectDev()`:** `tryDeferredPermissionChecks()` is called at the beginning and triggers `onSurfaceReady()` → `initializeCameraIfReady()` **before** the same method sets `mPendingCameraOpen` (or calls `openCamera`). So the deferred-open consumer runs with `mPendingCameraOpen == null` and never runs again after we set it. So **C**, **D**, and **G** together: **mPendingCameraOpen is set but never consumed** because **the consumer runs too early** (ordering), and **openCamera is not executed** for the deferred case.

---

## 8. Why Restart Fixes It

- On **second launch**, device already has USB permission. `requestPermissionForAlreadyAttachedDevices()` calls `client.requestPermission(device)`. In `USBMonitor.requestPermission(device)`, if `mUsbManager.hasPermission(device)` is true, it calls **`processConnect(device)`** immediately (no dialog). So **`onConnectDev()` runs very soon**, often before or around the same time as layout.
- If **surface is already ready** by then (e.g. from a previous layout), we take the branch `camera.openCamera(...)` and preview starts.
- If **surface is not ready** yet, we set `mPendingCameraOpen`. Then when `onSurfaceTextureAvailable` fires (or when `checkPermissions()` runs and calls `onSurfaceReady()` again), `initializeCameraIfReady()` runs **after** `mPendingCameraOpen` has been set, so it consumes it and opens. So on second launch the **order** is such that the consumer runs **after** we set the pending open, so the camera opens.

---

## 9. Summary Table

| Observation | Explanation |
|------------|-------------|
| "Camera detected – requesting permission..." stays | Status is set in `requestPermissionForAlreadyAttachedDevices()` and updated in `onConnectDev()` to "Camera connected - Opening camera...". If the latter runs, text can still show "Opening camera..." while preview never starts because open was never executed. |
| Preview does not start on first run | `initializeCameraIfReady()` runs (from `tryDeferredPermissionChecks()` at start of `onConnectDev()`) with `mPendingCameraOpen == null`; later we set `mPendingCameraOpen` but never call `initializeCameraIfReady()` again. |
| Full app reopen fixes it | Second launch: permission already granted → `onConnectDev()` runs quickly; we either open immediately (surface ready) or set `mPendingCameraOpen` and a later surface/permission path consumes it. |
| Sometimes second permission dialog | Can be runtime (CAMERA/storage) after USB, or rare double path; not the main cause of “camera not starting.” |
| Demo starts preview instantly | Demo calls `openCamera(mCameraView)` directly in `onConnectDev()` and only registers USB after surface is ready, so no deferred-open ordering issue. |

---

## 10. Why Reference App Opens Camera on First Run (and we needed a fix)

- **Reference (AndroidUSBCamera):** Single Activity, no Splash, no patient dialog. When the app requests USB permission, the **activity stays in foreground** until the user taps Allow/Deny. The `ACTION_USB_PERMISSION` broadcast is delivered to the same activity; `onConnectDev()` runs and the camera opens.
- **OralVis:** Splash → MainActivity, then we show the **patient selection dialog** and we **post** the initial USB request. When the **system** USB permission dialog appears, the **activity goes to stopped** (our window is no longer visible). On some devices or Android versions:
  - The grant broadcast can be **delivered while the activity is stopped** and the library may report **cancel (device=null)** e.g. when `Intent.getParcelableExtra(EXTRA_DEVICE)` returns null (API 33+).
  - Or the broadcast is **queued** and not processed until after we've already received a "cancel" path.
- **Fix applied:** In **onResume**, if we are still in `REQUESTING_UVC` and `usbPermissionPending`, we **re-check** whether the UVC device **now has permission** (user granted while we were stopped). If it does, we call `client.requestPermission(device)` again; the library sees `hasPermission(device)` and calls `processConnect(device)` → **onConnectDev()** → camera opens on first run without needing a second launch.

---

**End of analysis.**
