# feature/media

Responsibilities:
- Own high-level media lifecycle orchestration (capture, visibility, selection) independent of Activities.
- Delegate persistence and state-machine transitions to the `data` layer (e.g., repositories).
- Expose operations the UI can invoke to work with media for the current patient/session.

Non-responsibilities:
- Low-level file I/O and Room entity/DAO definitions (belongs under `data`).
- Layout or visual grouping decisions for the gallery UI (belongs under `ui/gallery`).

