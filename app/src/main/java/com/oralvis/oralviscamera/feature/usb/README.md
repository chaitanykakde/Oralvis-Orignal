# feature/usb

Responsibilities:
- Own USB / CDC integration logic independent of Activities.
- Coordinate device detection, permission handling, and serial command routing.
- Expose interfaces that Activities or UI controllers can implement (e.g., `CameraCommandReceiver`) without tight coupling.

Non-responsibilities:
- UI behavior or lifecycle decisions (belongs under `ui/*`).
- Camera business rules or session/media lifecycle (belongs under `feature/camera`, `feature/session`, or `feature/media`).

