# feature/camera

Responsibilities:
- Own camera-related orchestration logic independent of Activities.
- Coordinate UVC camera client setup, open/close, and capture triggers.
- Expose methods that can be called from `ui` layer (e.g., `MainActivity`) without depending on Activity types.

Non-responsibilities:
- UI rendering or view binding (belongs in `ui/*`).
- Business rules for sessions, patients, or media lifecycle (belongs in `feature/session` or `feature/media`).

