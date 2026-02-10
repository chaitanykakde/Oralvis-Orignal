# feature.gallery

## Owned Responsibilities

- **Media loading**: Patient/session-specific media queries (`GalleryFlowCoordinator`)
- **Sequence grouping**: RGB+UV pairing, arch filtering, sequence card generation (`GalleryFlowCoordinator`)
- **Media deletion**: Discard sequence pairs, file cleanup (`GalleryFlowCoordinator`)
- **Cloud sync**: Two-phase upload/download orchestration (`GalleryFlowCoordinator`)

## Forbidden Responsibilities

- ❌ Camera operations (belongs to `feature.camera`)
- ❌ Session start/management (belongs to `feature.session`)
- ❌ Guided capture (belongs to `feature.guided`)
- ❌ Patient creation (belongs to patient management)
- ❌ Direct database writes without repository

## Public API Surface

### Coordinator
- `GalleryFlowCoordinator` - Gallery domain logic coordinator
  - `observeMediaForCurrentPatient()` - Load patient media
  - `observeMediaForCurrentSession()` - Load session-specific media
  - `groupMediaIntoSequences()` - Create sequence cards with arch filtering
  - `discardSequencePair()` - Delete media pair
  - `performCloudSync()` - Trigger sync orchestration

## Architecture Rules

1. Activities MUST delegate ALL domain logic to `GalleryFlowCoordinator`
2. `GalleryActivity` MUST be UI-only (RecyclerView, tabs, progress indicators)
3. Sequence grouping MUST handle guided/unguided media correctly
4. Arch filtering MUST respect UPPER/LOWER/OTHER tab selection
5. Sync MUST use callbacks for progress updates (no direct UI access from coordinator)
6. Media deletion MUST clean up both database records AND files
