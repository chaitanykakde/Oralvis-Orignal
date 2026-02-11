# Multi-USB Device Startup Pipeline Analysis (UVC + CDC)

**Scope:** Staged behavior across app launches (first = UVC permission, second = CDC permission, third = full readiness).  
**Devices:** UVC camera (e.g. e-CAM514_USB) + CDC serial controller (CH55xduino, 0x1209/0xC550).  
**No fixes in this document — analysis only.**

---

## 1. Device Detection Timeline (Cold Start)

### Where devices are detected

- **Single source for “already attached” devices:** `requestPermissionForAlreadyAttachedDevices()` in `MainActivity` (runs once per activity lifecycle, guarded by `hasRunInitialUsbDeviceScan`).
- **Device list:** `client.getDeviceList()` from `MultiCameraClient` / `USBMonitor` — returns all USB devices visible to the app (order is implementation-dependent).
- **No separate CDC “detector” in the path:** `UsbDeviceDetector` exists in the codebase but is **not** instantiated or registered anywhere in MainActivity. CDC is only considered in the **second loop** of `requestPermissionForAlreadyAttachedDevices()` (explicit VID/PID 0x1209/0xC550).

### Exact order on cold start (both devices already plugged in)

| Step | Where | What happens |
|------|--------|--------------|
| 1 | `onCreate()` | `ensureUsbMonitorRegistered()` → create `mCameraClient`, register `USBMonitor`. |
| 2 | `onCreate()` | `requestPermissionForAlreadyAttachedDevices()` runs (first time: `hasRunInitialUsbDeviceScan == false`). |
| 3 | Inside `requestPermissionForAlreadyAttachedDevices()` | `devices = client.getDeviceList()` — both UVC and CDC are in the list (order undefined). |
| 4 | **First loop** `for (device in devices)` | Skip until `isUsbCamera(device) \|\| isFilterDevice(this, device)`. CDC (0x1209/0xC550) is **not** in `default_device_filter.xml` (libausbc), so **not** `isFilterDevice`; CDC is not video class, so **not** `isUsbCamera`. So the **first device that matches is the UVC camera**. Add it to `mCameraMap`, set status, call `client.requestPermission(device)` for **UVC**, then **`break`**. |
| 5 | **Second loop** `for (device in devices)` | Find first device with `vendorId == 0x1209 && productId == 0xC550` (CDC). If `!usbManager.hasPermission(device)` call `client.requestPermission(device)` for **CDC**, then **`break`**. |
| 6 | **Critical** | **Two** `requestPermission(device)` calls are made in the same run: first **UVC**, then **CDC**. Android documents that **only one USB permission request can be outstanding at a time**. The second call can replace or supersede the first. So the **system shows at most one dialog** — for whichever request is “current” (typically the **last** one, i.e. **CDC**). |
| 7 | User sees one dialog | Either “e-CAM514_USB” (UVC) or “CH55xduino” (CDC) — depends on which request the system keeps. |
| 8 | User taps Allow | Single `ACTION_USB_PERMISSION` broadcast → **one** `onConnectDev(device, ctrlBlock)` for **one** device. |
| 9a | If `onConnectDev` is for **UVC** | We have UVC in `mCameraMap`. We set control block, set callback, build request, open or set `mPendingCameraOpen`, then `tryDeferredPermissionChecks()`. Camera can open (with first-run ordering fix). **CDC was never granted in this session.** |
| 9b | If `onConnectDev` is for **CDC** | We look up `mCameraMap[device.deviceId]`. **We never put CDC in `mCameraMap`** (first loop only adds UVC). So `mCameraMap[device.deviceId]?.apply { ... }?.also { camera -> ... }` is **null**. We do **nothing**: no camera set, no open, no pending. **UVC was never granted in this session.** |

So in **one** cold start we only ever **grant one device**; the other is never requested in a way that shows a second dialog, and we never “open” CDC in `onConnectDev` (we only open the UVC camera when `onConnectDev` is for UVC).

### When is CDC “detected”?

- CDC is **detected** in the same lifecycle as UVC — in the **same** call to `requestPermissionForAlreadyAttachedDevices()`, in the **second** loop.
- CDC is **not** gated behind camera open: we iterate `client.getDeviceList()` once; both devices are present.
- CDC permission is **requested** in that same run (second loop), but because we already called `requestPermission(UVC)` and then `requestPermission(CDC)`, only one permission dialog is shown.

### When is UVC detected?

- In the **first** loop of `requestPermissionForAlreadyAttachedDevices()` — first device in `devices` that is `isUsbCamera` or `isFilterDevice`.

### Summary: detection timeline

- **UVC:** Detected in first loop; permission requested; loop exits with `break`.
- **CDC:** Detected in second loop; permission requested if not already granted.
- **Same lifecycle:** Both are handled in the same `requestPermissionForAlreadyAttachedDevices()` run.
- **Only one dialog:** Two `requestPermission()` calls in one run → Android allows only one outstanding request → one grant per launch.

---

## 2. CDC Permission Request Timing

- **Where CDC permission is requested:** Only in `requestPermissionForAlreadyAttachedDevices()`, second loop (0x1209/0xC550).
- **When that runs:** Once per activity instance, at the start of `onCreate()`, when `hasRunInitialUsbDeviceScan` is false.
- **Not** triggered by camera ready: the second loop runs regardless of `cameraStateStore.isCameraReady`.
- **Not** triggered by `usbController.start()`: `UsbSerialManager.start()` only calls `usbManager.openDevice(device)` and checks `hasPermission(device)`; it does **not** call `requestPermission()`.

So:

- **Why CDC permission only appears on second launch:** On **first** launch we run both loops and call `requestPermission(UVC)` then `requestPermission(CDC)`. Only one dialog is shown. If the system keeps the **first** request (UVC), user grants UVC; we never get a separate CDC dialog in that session. On **second** launch we run `requestPermissionForAlreadyAttachedDevices()` again (new Activity → `hasRunInitialUsbDeviceScan` reset). First loop: UVC — if we already have permission, `requestPermission(UVC)` leads to immediate `processConnect(UVC)` (no dialog). Second loop: CDC — if we still don’t have permission, we call `requestPermission(CDC)` and **now** the CDC dialog appears. So the **second** launch is when the user can be asked for CDC, and only if they weren’t asked for CDC on the first launch.

---

## 3. CDC Initialization Dependency

### When `usbController.start()` is called

- **Path 1 (immediate):** In the camera **OPENED** callback inside `onConnectDev()` → `camera.setCameraStateCallBack(...)` → `onOpened = { ... usbSerialManager?.onCameraOpened(...); usbController?.start(); ... }`. So when the **UVC camera** reports OPENED, we store the connection in `usbSerialManager` and call `usbController?.start()`.
- **Path 2 (delayed):** `scheduleCdcStartAfterStability()` is invoked from `onFirstFrameReceived()` (first frame from camera) and schedules a **20-second** delayed runnable that calls `usbController?.start()` again.

So CDC start is **tied to camera OPENED** (and optionally again 20s after first frame). It is **not** tied to a runtime permission callback or to a separate “CDC attached” event in this code.

### When “Remote control ready” is shown

- `UsbSerialManager.openConnection(device)` → on success → `onConnectionStateChanged(true)` → `MainActivity.updateUsbConnectionStatus(true)` → Toast: **“OralVis hardware connected - Remote control ready!”**.

So the toast is shown only when the **CDC serial connection is successfully opened**, which requires:

1. Camera has opened (so `usbSerialManager.onCameraOpened()` was called and `isCameraReady == true`).
2. `usbController.start()` ran (from camera OPENED).
3. `getOralVisCdcDevice()` found the CDC device in `usbManager.deviceList`.
4. **`usbManager.hasPermission(cdcDevice)`** is true.
5. `openDevice(device)` and CDC interface claim succeed.

### Why remote control is only ready on third launch

- **First launch:** One permission dialog; user grants one device (UVC or CDC). If they grant **CDC**, we get `onConnectDev(CDC)` and do nothing (no camera in map). Camera never opens; `usbController.start()` is never called. If they grant **UVC**, we open camera and call `usbController.start()`, but we **don’t** have CDC permission yet → `openConnection(CDC)` fails at `hasPermission(device)` → no “Remote control ready.”
- **Second launch:** We request the **other** device (e.g. CDC dialog). When user grants CDC, we get `onConnectDev(CDC)` (again we do nothing with it in our callback). But we already had UVC from first launch, so when we run the **first** loop we get immediate `onConnectDev(UVC)` (no dialog). We open the camera. **Ordering race:** Camera OPENED and `usbController?.start()` may run **before** the user has dismissed the CDC dialog and granted CDC. So when `usbController.start()` runs, we often **still don’t have CDC permission** → `openConnection(CDC)` fails → no toast. So “Remote control ready” does not show on second launch.
- **Third launch:** We have **both** UVC and CDC permissions. No new dialogs. Camera opens (from immediate `onConnectDev(UVC)`), `usbController.start()` runs, `hasPermission(CDC)` is true → CDC opens → `onConnectionStateChanged(true)` → “Remote control ready.”

So CDC init depends on **camera OPENED**; “Remote control ready” depends on **CDC permission already granted** when `usbController.start()` runs. Because the second launch often grants CDC **after** the camera has already opened and started CDC (and failed), only the third launch has both permissions before the first CDC start attempt.

---

## 4. Multi-Device Permission Conflict

- **Does requesting UVC block CDC (or vice versa)?** Yes, in the sense that we call **two** `UsbManager.requestPermission(device, pendingIntent)` in one run (UVC then CDC). Android allows **only one outstanding permission request**. The second call effectively replaces or supersedes the first, so only **one** dialog is shown and we get **one** grant per session.
- **Are we requesting only one device at a time?** No. We request **UVC** (first loop, then `break`) and then **CDC** (second loop) in the same method run. So we issue two requests in quick succession; the system shows one dialog.
- **Is the CDC device ignored during the first scan?** No. CDC is explicitly requested in the second loop. It is not “ignored”; it is requested **after** UVC, so the dialog the user sees may be for CDC, and we may get `onConnectDev(CDC)` with no camera in map.
- **Does `requestPermissionForAlreadyAttachedDevices()` stop after first match?** Yes, **per loop.** First loop: we request the **first** UVC/filter device and **break** (we never request a second UVC in that run). Second loop: we request the **first** 0x1209/0xC550 device and **break**. So we request **at most one UVC and one CDC** per run, but the platform only shows one dialog.

So the conflict is: **two permission requests in one run, one outstanding dialog, one grant per launch** — and **only one** of the two devices is added to `mCameraMap` and used in `onConnectDev` (UVC). The other (CDC) is never “opened” in `onConnectDev`; CDC is only used later when `usbController.start()` runs and opens the CDC device via `UsbManager.openDevice()`.

---

## 5. First Launch Dead State — Precise Explanation

First launch can end with:

- **Camera permission granted, but camera not opened:** This happens when the **one** dialog shown was for **CDC** (second `requestPermission` overwrote the first). User grants CDC. We get `onConnectDev(CDC)`. We look up `mCameraMap[device.deviceId]` for CDC; we never put CDC in `mCameraMap`, so the whole `?.apply { }?.also { }` block is skipped. We never set `mCurrentCamera`, never build `CameraRequest`, never call `openCamera()` or set `mPendingCameraOpen`. So **camera never opens** even though the user “allowed” something (they allowed CDC). UVC was never granted.
- **CDC not requested (as a separate dialog):** If the **first** request (UVC) is the one that stays “outstanding,” the user sees UVC and grants it. We get `onConnectDev(UVC)`, camera can open. We **did** call `requestPermission(CDC)` in the same run, but the system did not show a second dialog; CDC is still not granted. So “CDC not requested” in the sense that the user never saw a **second** dialog for CDC.
- **App stuck in mid state:** We have one device granted (UVC or CDC), the other not; camera opens only if UVC was the one granted; CDC only works when we have CDC permission and we call `usbController.start()` after camera is open. So we’re in a half-ready state until the next launch.

Gating flags:

- **`cameraStateStore.isCameraReady`:** Used inside the camera OPENED callback and in CDC start. It does not block the **request** of UVC/CDC; it blocks **CDC start** until the camera has opened.
- **`deferredRuntimePermissionCheck`:** Only affects runtime (e.g. CAMERA) permission and when we call `checkPermissions()` / `onSurfaceReady()`. It does not block USB permission requests or which device we request.
- **`usbPermissionPending`:** Set when we request USB permission (e.g. in first loop); cleared in `onConnectDev` / `onCancelDev`. It gates **patient/runtime** logic, not which USB device we request. It does not cause the “first launch dead state” by itself.
- **Early return in CDC logic:** There is no “early return” that skips CDC **request**. The issue is that we request **both** UVC and CDC in one run and only **one** dialog is shown, and that `onConnectDev(CDC)` does nothing because we don’t treat CDC as a camera in `mCameraMap`.

---

## 6. Full Boot Timeline (Launches 1–3)

### Launch 1

1. `onCreate` → `ensureUsbMonitorRegistered()` → `requestPermissionForAlreadyAttachedDevices()`.
2. First loop: first UVC device → add to `mCameraMap`, call `requestPermission(UVC)`.
3. Second loop: CDC device (0x1209/0xC550) → call `requestPermission(CDC)` (if no permission).
4. Only **one** permission dialog is shown (UVC or CDC; typically the last request wins → often **CDC**).
5. User taps Allow → **one** `onConnectDev`:
   - If **UVC:** We set up camera, open or set `mPendingCameraOpen`, `tryDeferredPermissionChecks()`. Camera can open. CDC permission was never granted.
   - If **CDC:** We do nothing (no entry in `mCameraMap` for CDC). Camera never opens. UVC permission was never granted.
6. **Observed:** “USB permission requested for e-CAM514_USB (UVC). After Allow → camera does NOT open.” → Suggests the dialog that stayed was for **UVC** but something else prevents open (e.g. ordering before the fix, or the dialog was actually CDC). If the dialog was **CDC**, then after Allow we get `onConnectDev(CDC)` and nothing → camera does not open; app is closed.
7. **Result:** One device granted; the other not. Camera opens only if UVC was granted; if CDC was granted, camera never opens. No “Remote control ready” (either no camera open or no CDC permission when CDC start runs).

### Launch 2

1. New Activity → `hasRunInitialUsbDeviceScan == false` again.
2. `requestPermissionForAlreadyAttachedDevices()` runs again.
3. First loop: UVC. If we **have** UVC permission (e.g. from launch 1), `requestPermission(UVC)` → library calls `processConnect(UVC)` immediately → **`onConnectDev(UVC)`** is posted (no dialog).
4. We **break**, then second loop: CDC. If we **don’t** have CDC permission, `requestPermission(CDC)` → **CDC permission dialog** is shown.
5. On the main thread we run **`onConnectDev(UVC)`**: set up camera, open or set pending, `tryDeferredPermissionChecks()`. Camera starts opening (async).
6. User sees **CDC** dialog and grants → **`onConnectDev(CDC)`** (we again do nothing with it).
7. **Race:** Camera OPENED callback and `usbController?.start()` often run **before** the user has granted CDC. So when `usbController.start()` runs, `hasPermission(CDC)` is still false → `openConnection(CDC)` fails → no “Remote control ready.”
8. **Result:** Camera preview opens (UVC). CDC permission is granted later in the same session; CDC start already ran and failed. “Remote control NOT ready.”

### Launch 3

1. New Activity; `requestPermissionForAlreadyAttachedDevices()` runs again.
2. First loop: UVC — we have permission → immediate `onConnectDev(UVC)` → camera opens.
3. Second loop: CDC — we have permission → no dialog, no second `onConnectDev` needed for CDC.
4. Camera OPENED → `usbController?.start()` → `getOralVisCdcDevice()` finds CDC, `hasPermission(CDC)` true → `openConnection(CDC)` succeeds → `onConnectionStateChanged(true)` → **“Remote control ready”** toast.
5. **Result:** No new permission dialogs; camera opens; CDC starts; full readiness.

---

## 7. Root Cause Summary

| Issue | Root cause |
|-------|------------|
| UVC and CDC not both requested in same session | We **do** request both in one run, but Android allows only **one** outstanding USB permission request. The second `requestPermission()` call does not produce a second dialog; we get one grant per launch. |
| CDC init “requires” third launch | CDC start runs when the camera opens (`onOpened`). On second launch we often open the camera and run `usbController.start()` **before** the user has granted the CDC dialog. So CDC open fails. On third launch we already have both permissions, so the first CDC start attempt succeeds. |
| Camera open still partially broken on first launch | If the single dialog shown was for **CDC**, we get `onConnectDev(CDC)` and do nothing (no camera in map), so camera never opens. So first launch can end with “permission granted” but no camera. |
| Device readiness staged across launches | **One** USB permission grant per launch; we need **two** (UVC and CDC). So launch 1 = one device, launch 2 = other device, launch 3 = both already granted so camera + CDC start both succeed. |
| Why third launch finally succeeds | By then we have both UVC and CDC permissions. No dialogs. Camera opens from immediate `onConnectDev(UVC)`; `usbController.start()` runs with CDC permission already set → CDC opens → “Remote control ready.” |

---

## 8. Gating Flags and Conditions

| Flag / condition | Effect |
|-------------------|--------|
| `hasRunInitialUsbDeviceScan` | Ensures we only run the two loops **once per Activity**. So we only **request** UVC+CDC once per launch; we don’t re-request the second device later in the same Activity. |
| “Only one outstanding permission request” (Android) | Ensures only **one** dialog per run of `requestPermissionForAlreadyAttachedDevices()`, so only one device granted per launch. |
| `mCameraMap` only holds UVC | `onConnectDev(CDC)` does nothing because we never add CDC to `mCameraMap`; we only add the first UVC in the first loop. |
| `UsbSerialManager.start()` requires `isCameraReady` and `hasPermission(CDC)` | CDC start is gated on camera having opened and on CDC permission. If we run start before CDC is granted, we never show “Remote control ready” until a later launch when we have both. |

---

## 9. Design Implication for “Single Unified Boot Controller”

To get **WAIT_FOR_ALL_DEVICES → REQUEST_ALL_PERMISSIONS → INIT_ALL → READY**:

- We must **request** UVC and CDC in a **sequential** way: request first device → wait for grant (or “already granted”) → then request second device → wait for grant. That implies **one** permission request at a time and not two back-to-back in the same run.
- We must not rely on a single run of `requestPermissionForAlreadyAttachedDevices()` to show two dialogs; we need a small state machine or callback chain: after the first permission result (or immediate `processConnect`), request the second device if needed.
- We may also want to **re-trigger** CDC start when CDC permission is **newly** granted (e.g. from a permission callback or a dedicated “CDC attached/granted” path), so that we don’t depend on a third launch for “Remote control ready.”

This analysis is sufficient to design that controller; no code changes were made in this document.

---

**End of analysis.**
