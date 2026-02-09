# feature/guided

Responsibilities:
- Own guided capture orchestration logic that is independent of Activities.
- Coordinate guided session state machine, motion analysis, and auto-capture triggers.
- Provide APIs that can be driven by the `ui` layer (e.g., main camera screen) without referencing Activity types directly.

Non-responsibilities:
- Direct view manipulation or Android widget handling (belongs under `ui/*`).
- Persistence of media or sessions (belongs under `feature/media` or `feature/session`).

