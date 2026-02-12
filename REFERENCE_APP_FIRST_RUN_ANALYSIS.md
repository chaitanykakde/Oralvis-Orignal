# Why the reference app (AndroidUSBCamera) opens camera on first run

## Reference app flow (DemoFragment + CameraFragment)

1. **SplashActivity** (800 ms) → **MainActivity**.
2. **MainActivity.onCreate**: `replaceDemoFragment(DemoFragment())`.
3. **replaceDemoFragment** first requests **runtime** permissions (CAMERA, WRITE_EXTERNAL_STORAGE, RECORD_AUDIO). If not granted, it calls `requestPermissions` and **returns without adding the fragment**. So the camera fragment is **not** created until the user has granted runtime permissions.
4. After user grants runtime permissions, **DemoFragment** is added. Its view is created and it has a **TextureView** for the camera.
5. **Critical:** In **CameraFragment** (base class), USB is **not** registered in onCreate. It is registered **only** when the TextureView’s surface is available:
   - `handleTextureView(textureView)` sets a `SurfaceTextureListener`.
   - In **`onSurfaceTextureAvailable`** it calls **`registerMultiCamera()`**.
   - `registerMultiCamera()` creates `MultiCameraClient`, registers the USB monitor, and in **onAttachDev** it calls **`requestPermission(device)`** for the attached device.
6. So the order is: **Surface available → register USB → request USB permission**. The USB permission dialog is shown **only after** the preview surface exists.
7. When the user taps **Allow** on the USB dialog, **onConnectDev** runs. The code does **`openCamera(mCameraView)`** immediately. The **TextureView** and its surface are already there, so the camera opens on first run.

## Our app (before this fix)

1. **SplashActivity** → **MainActivity** (after auth).
2. **MainActivity.onCreate**: We call **ensureUsbMonitorRegistered()** and then **post** `requestPermissionForAlreadyAttachedDevices()`. So we request USB permission **as soon as the first layout runs**, which can be **before** the TextureView has reported **onSurfaceTextureAvailable**.
3. When the USB dialog is shown, our activity may go to **stopped**. The grant can be missed or reported as cancel (e.g. device=null). So we might never get **onConnectDev** on first run.
4. Even when we did get **onConnectDev**, we sometimes set **mPendingCameraOpen** because **isSurfaceTextureReady** was still false, and the follow-up path to open could be racy or not run.

## Fix: match reference app order

**Request USB permission only after the preview surface is available.**

- We **removed** the `binding.root.post { requestPermissionForAlreadyAttachedDevices() }` from onCreate.
- We **call** `requestPermissionForAlreadyAttachedDevices()` from **`onSurfaceTextureAvailable`** via a new callback **`onSurfaceReadyForUsb`** in **PreviewSurfaceManager**.
- So the sequence is: **Surface available → request USB permission for already-attached devices**. When the user grants, **onConnectDev** runs and **isSurfaceTextureReady** is already true, so we call **openCamera** immediately and the camera opens on first run.

We still **register** the USB monitor in onCreate (so we don’t miss attach events), but we **request** permission only after the surface is ready. We also keep the **onResume** recovery (if we were REQUESTING_UVC and the device now has permission, trigger connect) as a fallback for devices where the grant was lost when the activity was stopped.

## Summary

| Aspect | Reference app | Our app (after fix) |
|--------|----------------|----------------------|
| When is USB permission requested? | Only after **onSurfaceTextureAvailable** (inside **registerMultiCamera()**) | Only after **onSurfaceTextureAvailable** (via **onSurfaceReadyForUsb**) |
| When user grants USB | Surface already exists → **openCamera(view)** runs and works | Surface already exists → **openCamera** runs and works |
| Result | Camera opens on first run | Camera should open on first run |
