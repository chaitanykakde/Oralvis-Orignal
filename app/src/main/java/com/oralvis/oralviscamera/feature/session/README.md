# feature.session

## Owned Responsibilities

- **Session lifecycle**: Start, save, finalize, cleanup (`SessionFlowCoordinator`, `SessionController`)
- **Patient selection**: Patient picker dialogs, validation (`SessionFlowCoordinator`)
- **Session media tracking**: Add, remove, update session media list (`SessionFlowCoordinator`)
- **Session-patient binding**: Ensuring session belongs to correct patient

## Forbidden Responsibilities

- ❌ Camera operations (belongs to `feature.camera`)
- ❌ Guided capture orchestration (belongs to `feature.guided`)
- ❌ Gallery presentation (belongs to `feature.gallery`)
- ❌ Cloud sync (belongs to sync layer)
- ❌ Direct camera capture (use capture handlers)

## Public API Surface

### Coordinators
- `SessionFlowCoordinator` - Session and patient lifecycle coordinator
- `SessionController` - Session state management

### Key Methods
- `onStartSessionClicked()` - Initiate session start with patient validation
- `checkAndPromptForPatientSelection()` - Ensure patient is selected
- `proceedWithSessionStart()` - Begin guided capture session
- `saveCurrentSession()` - Finalize and persist session
- `clearSessionState()` - Reset session context
- `addSessionMedia()` / `removeSessionMedia()` - Manage session media list

## Architecture Rules

1. Sessions MUST be tied to a patient
2. Session start MUST validate patient selection first
3. Session media MUST include metadata (arch, sequence, guided session ID)
4. Empty sessions MUST be cleaned up on exit
5. Activities MUST NOT directly touch `SessionManager` - use `SessionFlowCoordinator`
