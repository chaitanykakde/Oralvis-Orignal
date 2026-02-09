# feature/sync

Responsibilities:
- Own orchestration of media synchronization flows (local→cloud, cloud→local) at the feature layer.
- Coordinate calls into upload/download services and repositories while remaining UI-agnostic.
- Provide simple entry points that screens like Gallery can invoke for patient-level sync.

Non-responsibilities:
- Direct HTTP client configuration or AWS SDK setup (belongs under `data`/network).
- UI progress messaging and toasts (belongs under `ui/gallery` or other UI modules).

