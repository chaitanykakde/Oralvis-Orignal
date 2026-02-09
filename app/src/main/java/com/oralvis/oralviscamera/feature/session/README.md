# feature/session

Responsibilities:
- Own session lifecycle orchestration independent of Activities.
- Coordinate creation, selection, and clearing of sessions using underlying persistence mechanisms.
- Provide a small API surface that UI layers call to work with current session and session identifiers.

Non-responsibilities:
- Direct database schema or DAO definitions (belongs under `data`).
- UI rendering concerns or Activity navigation (belongs under `ui/*`).

