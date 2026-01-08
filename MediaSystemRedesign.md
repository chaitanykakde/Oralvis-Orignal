# 🎯 PRODUCTION-GRADE MEDIA SYSTEM REDESIGN

## 📐 A. NEW MEDIA ARCHITECTURE (DOCUMENTED)

### 1. SINGLE SOURCE OF TRUTH RULES

**Primary Authority:** Local Room database is the single source of truth for media state and existence.

**Core Invariants:**
1. **DB Authority:** If a media record exists in DB → media logically exists
2. **Filesystem Cache:** Files are recreatable cache, not authority
3. **Cloud Replica:** Cloud is a backup/replica, not the source
4. **UI Consistency:** Gallery renders from DB state only, never filesystem inspection
5. **State Determinism:** Media state is explicit and queryable, not inferred

**Consequences:**
- Missing files show deterministic "file missing" state, not random placeholders
- Sync reconciles to match DB state, not cloud state
- UI never hides or infers data - shows actual DB state

### 2. CANONICAL MEDIA ID SYSTEM

**MediaId Definition:**
- Type: UUID (String)
- Generation: `UUID.randomUUID().toString()` on first DB insert
- Immutability: Never changes for the lifetime of the media record
- Universality: Used across local DB, cloud sync, and all operations

**Why MediaId vs Other Identifiers:**
- ❌ `id` (Room auto-gen): Resets on reinstall, not stable across devices
- ❌ `filePath`: Changes with directory restructuring, not stable
- ❌ `fileName`: Not globally unique, collisions possible
- ❌ `cloudFileName`: Not present for local-only media, not available during capture
- ✅ `mediaId`: Stable UUID generated once, survives all operations

**MediaId Lifecycle:**
- Generated during initial DB insert (in MediaRepository.createMediaRecord())
- Passed to cloud during upload (becomes primary identifier)
- Used for deduplication on download (prevents duplicates)
- Survives reinstall (stored in DB, re-synced from cloud)

### 3. MEDIA STATE MACHINE

**State Definitions:**

```kotlin
enum class MediaState {
    CAPTURED,           // Initial capture triggered, file/DB not committed
    FILE_READY,         // File written to temp location
    DB_COMMITTED,       // DB record exists, media logically exists
    UPLOADING,          // Currently uploading to cloud
    SYNCED,             // Successfully uploaded to cloud
    DOWNLOADED,         // Downloaded from cloud
    FILE_MISSING,       // DB record exists but file missing/unreadable
    CORRUPT             // File exists but corrupted/unreadable
}
```

**State Transition Rules:**

```
CAPTURED → FILE_READY (file write succeeds)
FILE_READY → DB_COMMITTED (atomic commit succeeds)
DB_COMMITTED → UPLOADING (sync starts)
UPLOADING → SYNCED (upload succeeds)
UPLOADING → DB_COMMITTED (upload fails - rollback)
DB_COMMITTED → DOWNLOADED (downloaded from cloud - merge state)
Any state → FILE_MISSING (file becomes unreadable)
Any state → CORRUPT (file corruption detected)
```

**State Query Methods:**
- `isVisibleInGallery()`: DB_COMMITTED, SYNCED, DOWNLOADED, FILE_MISSING
- `canBeUploaded()`: DB_COMMITTED (not SYNCED, not UPLOADING)
- `needsFileRecovery()`: FILE_MISSING (can re-download from cloud)

### 4. OWNERSHIP RULES

**Patient Owns Media:**
- Media belongs to patient, not session
- `patientId` is required and immutable
- Media survives session changes/deletions

**Session Provides Context:**
- Session groups media temporally
- Session provides sequence information for guided captures
- Session is optional context, not ownership
- Media can exist without session (cloud downloads)

**Cloud Provides Replication:**
- Cloud mirrors local DB state
- Cloud is not authoritative
- Local DB takes precedence in conflicts

### 5. DATA FLOW DIAGRAM

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  MainActivity│    │MediaRepository│    │   Gallery   │
│             │    │             │    │             │
│ • capture() │───▶│ • create()  │◀───│ • observe() │
│ • file ops  │    │ • commit()  │    │ • render    │
└─────────────┘    └─────────────┘    └─────────────┘
       │                   │                   │
       ▼                   ▼                   ▼
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│ Filesystem  │    │Room Database│    │ Cloud APIs  │
│             │    │             │    │             │
│ • temp files│    │ • mediaId   │    │ • upload    │
│ • final loc │    │ • state     │    │ • download  │
│ • recovery  │    │ • patientId │    │ • reconcile │
└─────────────┘    └─────────────┘    └─────────────┘
```

**Key Flow: Capture → Repository → DB → UI**
1. Capture triggers MediaRepository.createMediaRecord()
2. Repository manages file operations and state transitions
3. DB stores canonical state with mediaId
4. Gallery observes DB state only
5. Sync uses mediaId for cloud reconciliation

---

## 🗃️ B. NEW DB SCHEMA

### Media Table (New Schema)

```sql
CREATE TABLE media_v2 (
    mediaId TEXT PRIMARY KEY NOT NULL,           -- Canonical UUID identifier
    patientId INTEGER NOT NULL,                  -- Patient owns media (required)
    sessionId TEXT,                              -- Session provides context (optional)
    state TEXT NOT NULL,                         -- MediaState enum value

    -- File metadata
    fileName TEXT NOT NULL,                      -- Original filename
    filePath TEXT,                               -- Current absolute path (can change)
    fileSizeBytes INTEGER,                       -- File size for validation
    checksum TEXT,                               -- File integrity check

    -- Media properties
    mediaType TEXT NOT NULL,                     -- "Image" or "Video"
    mode TEXT NOT NULL,                          -- "Normal" or "Fluorescence"
    captureTime INTEGER NOT NULL,                -- Timestamp (Date.getTime())

    -- Cloud sync metadata
    cloudFileName TEXT,                          -- Cloud UUID.ext (set after upload)
    s3Url TEXT,                                  -- S3 URL (set after upload)
    uploadedAt INTEGER,                          -- Upload timestamp

    -- Guided capture metadata
    dentalArch TEXT,                             -- "LOWER"/"UPPER"
    sequenceNumber INTEGER,                      -- 1,2,3,... per arch
    guidedSessionId TEXT,                        -- Guided session identifier

    -- Timestamps
    createdAt INTEGER NOT NULL,                  -- Creation timestamp
    updatedAt INTEGER NOT NULL                   -- Last update timestamp
);

-- Indexes for performance
CREATE INDEX idx_media_v2_patient ON media_v2(patientId);
CREATE INDEX idx_media_v2_session ON media_v2(sessionId);
CREATE INDEX idx_media_v2_state ON media_v2(state);
CREATE INDEX idx_media_v2_cloud_file ON media_v2(cloudFileName);

-- Constraints
ALTER TABLE media_v2 ADD CONSTRAINT ck_media_state
    CHECK (state IN ('CAPTURED', 'FILE_READY', 'DB_COMMITTED', 'UPLOADING', 'SYNCED', 'DOWNLOADED', 'FILE_MISSING', 'CORRUPT'));

ALTER TABLE media_v2 ADD CONSTRAINT ck_media_type
    CHECK (mediaType IN ('Image', 'Video'));

ALTER TABLE media_v2 ADD CONSTRAINT ck_media_mode
    CHECK (mode IN ('Normal', 'Fluorescence'));
```

### Migration Strategy

**Migration Path: media → media_v2**

1. **Create new table** with new schema
2. **Migrate existing records:**
   ```sql
   INSERT INTO media_v2 (
       mediaId, patientId, sessionId, state, fileName, filePath,
       mediaType, mode, captureTime, cloudFileName, s3Url,
       dentalArch, sequenceNumber, guidedSessionId,
       createdAt, updatedAt
   )
   SELECT
       -- Generate new mediaId for each record
       LOWER(HEX(RANDOMBLOB(4))) || '-' || LOWER(HEX(RANDOMBLOB(2))) || '-4' ||
       SUBSTR(LOWER(HEX(RANDOMBLOB(2))), 2) || '-a' ||
       SUBSTR(LOWER(HEX(RANDOMBLOB(2))), 2) || '-' ||
       LOWER(HEX(RANDOMBLOB(6))) as mediaId,

       -- Map patientId (from session join or direct)
       COALESCE(s.patientId, m.patientId) as patientId,

       m.sessionId,
       CASE
           WHEN m.isSynced = 1 THEN 'SYNCED'
           WHEN m.isFromCloud = 1 THEN 'DOWNLOADED'
           ELSE 'DB_COMMITTED'
       END as state,

       m.fileName, m.filePath, m.mediaType, m.mode,
       strftime('%s', m.captureTime) * 1000,

       m.cloudFileName, m.s3Url,
       m.dentalArch, m.sequenceNumber, m.guidedSessionId,

       strftime('%s', 'now') * 1000,
       strftime('%s', 'now') * 1000

   FROM media m
   LEFT JOIN sessions s ON m.sessionId = s.sessionId;
   ```

3. **Validate migration** - ensure no data loss
4. **Update DAO references** to use media_v2
5. **Drop old table** after successful migration

### Session Relationship (Clean)

**Session remains context-only:**
- `sessionId` is optional foreign key to sessions table
- No cascading deletes (media survives session deletion)
- Session provides grouping, not ownership
- Media can exist with `sessionId = null` (cloud downloads)

---

## 🔁 C. SYNC FLOW (UNCHANGED APIs)

### Upload Flow (Local → Cloud)

1. **Select uploadable media:**
   ```sql
   SELECT * FROM media_v2
   WHERE state = 'DB_COMMITTED' AND patientId = ?
   ORDER BY captureTime ASC
   ```

2. **Upload process:**
   - Generate new `cloudFileName` (UUID.ext)
   - Upload file to S3: `{patientCode}/{clinicId}/{cloudFileName}`
   - Call Lambda API with `mediaId` as primary identifier
   - Update DB: `state = 'SYNCED'`, `cloudFileName`, `s3Url`, `uploadedAt`

3. **Deduplication:** Cloud uses `mediaId` to detect re-uploads

### Download Flow (Cloud → Local)

1. **Fetch cloud media list** (unchanged API)
2. **For each cloud media:**
   - Check if `mediaId` already exists in local DB
   - If exists: skip (already have this media)
   - If not: download file and insert new record

3. **Reconciliation:** Cloud `mediaId` becomes local canonical ID

### Idempotent Guarantees

- **Upload:** Multiple calls with same `mediaId` → cloud deduplicates
- **Download:** Local DB check prevents duplicate insertions
- **Partial failure:** Can resume without duplication
- **Re-sync:** Compares `mediaId` sets, only transfers differences

---

## 🧬 D. MIGRATION STRATEGY

### Zero Data Loss Migration

**Phase 1: Schema Preparation**
- Create `media_v2` table with new schema
- Keep `media` table intact during migration

**Phase 2: Data Migration**
- Generate stable `mediaId` for each existing record
- Map patient relationships (session join + direct)
- Infer initial state from existing flags
- Preserve all metadata fields

**Phase 3: Validation**
- Count records before/after migration
- Verify all relationships preserved
- Test file accessibility

**Phase 4: Cutover**
- Update all DAO references to `media_v2`
- Drop old `media` table
- Update indexes and constraints

### Cloud Media Mapping

**Existing cloud media:**
- Use existing `cloudFileName` to match cloud records
- Generate new `mediaId` for cloud media during download
- Store `mediaId` in cloud metadata for future sync

**Migration of downloaded cloud media:**
- Existing cloud downloads get new `mediaId`
- State set to `DOWNLOADED`
- No re-download needed

---

## 🧪 E. FAILURE HANDLING

### File Missing
- State: `FILE_MISSING`
- Gallery: Shows "file missing" indicator (deterministic)
- Recovery: Re-download from cloud if `SYNCED` or `DOWNLOADED`
- Prevention: File integrity checks on access

### Upload Fails
- State: Remains `DB_COMMITTED` (rollback from `UPLOADING`)
- Retry: Can retry upload safely (idempotent)
- User feedback: Clear error message
- Recovery: Manual retry or automatic on next sync

### Download Fails
- Skip failed download
- No DB record created
- Retry: Safe to retry (cloud API is idempotent)
- Partial: Successfully downloaded media remains available

### DB Mismatch
- File exists but DB corrupted: Rebuild DB from filesystem scan
- DB exists but file missing: Mark as `FILE_MISSING`
- Inconsistent state: State machine prevents invalid transitions

### App Killed Mid-Operation
- **File writing:** Temp files cleaned up on restart
- **DB committing:** Transactions ensure consistency
- **Upload:** State rollback to `DB_COMMITTED`
- **Recovery:** App restart detects and repairs inconsistent state

---

## ✅ F. GUARANTEES (EXPLICIT)

### Deterministic Behavior
- **Media visibility:** Gallery shows all `DB_COMMITTED+` media deterministically
- **State transitions:** All state changes follow explicit state machine
- **No placeholders:** Only "file missing" or "corrupt" states, never random icons
- **Consistent ordering:** Media ordered by `captureTime`, stable across restarts

### Data Integrity
- **No data loss:** Migration preserves all existing data
- **No duplicates:** `mediaId` prevents all duplication scenarios
- **Atomic operations:** File + DB operations coordinated
- **State consistency:** Invalid states impossible via constraints

### Sync Reliability
- **Idempotent operations:** Sync can run multiple times safely
- **Partial failure recovery:** Failed operations don't break subsequent runs
- **Conflict resolution:** Local DB takes precedence over cloud
- **Network resilience:** Works offline, syncs when connected

### Feature Preservation
- **Camera flow unchanged:** All capture triggers work identically
- **Session flow unchanged:** Session start/end behavior preserved
- **Auto capture unchanged:** Motion detection and capture logic untouched
- **UI/UX preserved:** All existing screens and interactions work
- **API contracts preserved:** All cloud API calls unchanged

### Performance & Scalability
- **Efficient queries:** Indexed fields for fast gallery loading
- **Lazy loading:** Thumbnails loaded on-demand
- **Background sync:** Upload/download don't block UI
- **Memory efficient:** State machine prevents memory leaks

---

## 🏁 IMPLEMENTATION ROADMAP

1. **Phase 1:** Create MediaRepository with state machine
2. **Phase 2:** Implement new DB schema and migration
3. **Phase 3:** Update MediaDao with new queries
4. **Phase 4:** Modify capture flow to use repository
5. **Phase 5:** Update gallery to use dumb rendering
6. **Phase 6:** Update sync services to use mediaId
7. **Phase 7:** Test all scenarios and guarantees
