# OralVis Camera App - Project Analysis

## Overview
OralVis is an Android application for dental clinics to capture, manage, and sync oral examination media (images/videos) using USB cameras. The app uses a clinic-based registration system rather than traditional user login/registration.

---

## 1. Authentication & Registration System

### 1.1 Registration Flow (Clinic-Based, Not User-Based)

**Key Concept**: The app uses **Clinic Registration** instead of traditional user login/registration. Each device is associated with a clinic, not individual users.

#### Registration Process:
1. **Entry Point**: `SplashActivity` (launcher activity)
   - Checks if clinic is already registered
   - If no clinic ID exists → redirects to `ClinicRegistrationActivity`
   - If clinic ID exists → redirects to `MainActivity`

2. **Clinic Registration** (`ClinicRegistrationActivity.kt`):
   - User enters **clinic name** (only required field)
   - Makes API call to AWS API Gateway endpoint:
     ```
     POST https://d3x0w8vpui.execute-api.ap-south-1.amazonaws.com/default/OralVis_ClinicRegistration
     Body: { "clinicName": "..." }
     ```
   - Receives `clinicId` from backend
   - Stores clinic ID and name locally via `ClinicManager`
   - **Persistent Storage**: Uses SAF (Storage Access Framework) to persist clinic ID in encrypted file (`clinic.dat`) for app reinstall survival

3. **Identity Persistence** (`ClinicIdentityManager.kt`):
   - **Primary Storage**: Encrypted file in SAF-accessible folder (`Documents/oralvis/clinic.dat`)
   - **Encryption**: AES-128-GCM encryption with hardcoded key
   - **Fallback**: If SAF file missing, derives clinic ID from `ANDROID_ID` + salt
   - **Format**: Clinic ID format is `CLN_{10-char-hash}`

4. **Local Storage** (`ClinicManager.kt`):
   - Uses SharedPreferences for runtime clinic ID/name storage
   - Key: `clinic_id`, `clinic_name`
   - Cleared on logout

### 1.2 "Login" Flow (Auto-Restore)

**No Traditional Login**: The app doesn't have username/password login. Instead:

1. **App Launch** → `SplashActivity`
2. **Check Local Storage**:
   - First checks `ClinicManager` (SharedPreferences)
   - If missing, tries to restore from SAF file via `ClinicIdentityManager.tryLoadClinicId()`
3. **Navigation**:
   - If clinic ID found → `MainActivity` (camera screen)
   - If not found → `ClinicRegistrationActivity`

### 1.3 Logout Functionality

**Logout** is available in:
- `HomeActivity` (header logout button)
- `PatientDashboardActivity` (logout button)

**Logout Process**:
```kotlin
clinicManager.clearClinicId()  // Clears SharedPreferences
// Redirects to ClinicRegistrationActivity
```

**Note**: Logout clears local preferences but **does NOT** delete the SAF-encrypted file. On next launch, the clinic ID can be restored from SAF if the folder permission still exists.

---

## 2. Overall App Flow

### 2.1 Application Entry Flow

```
App Launch
    ↓
SplashActivity
    ↓
    ├─→ Has Clinic ID? ──YES──→ MainActivity (Camera)
    │                              OR
    │                              HomeActivity (Dashboard)
    │
    └─→ No Clinic ID? ──NO──→ ClinicRegistrationActivity
                                    ↓
                              Register Clinic
                                    ↓
                              MainActivity
```

### 2.2 Main Navigation Flow

```
MainActivity (Camera)
    ↕
HomeActivity (Dashboard)
    ├─→ Patient List
    ├─→ Add Patient
    ├─→ Patient Sessions
    └─→ Settings/Theme Toggle
        ↓
PatientDashboardActivity
    ├─→ Create New Patient
    ├─→ Search Existing Patients
    └─→ Select Patient → MainActivity (with patient context)
        ↓
MainActivity (with patient selected)
    ├─→ Capture Media
    ├─→ View Gallery
    └─→ Manage Sessions
        ↓
SessionDetailActivity
    └─→ View Session Media
        ↓
MediaViewerActivity
    └─→ View Individual Media
```

### 2.3 Patient & Session Management Flow

1. **Patient Creation**:
   - User enters: Name, Age, Phone Number
   - Generates **Global Patient ID** (hash-based)
   - Syncs to backend via API: `POST /patients` (upsert)
   - Creates local database record

2. **Session Management**:
   - Each capture session is associated with a patient
   - Session ID format: `session_{timestamp}_{uuid}`
   - Managed by `SessionManager` (SharedPreferences)
   - Sessions stored in Room database

3. **Media Capture**:
   - Media files saved locally
   - Metadata stored in Room database (`MediaRecord`)
   - Can be synced to cloud (S3 + DynamoDB) via `CloudSyncService`

---

## 3. Key Functionalities

### 3.1 Camera Functionality

**MainActivity** - Primary camera interface:
- **USB Camera Support**: Uses `libausbc`, `libuvc` libraries for USB camera control
- **Multi-Camera Support**: Can switch between multiple connected USB cameras
- **Camera Modes**:
  - Normal (RGB)
  - Fluorescence
- **Capture Modes**:
  - Photo capture
  - Video recording
  - Guided capture (auto-sequence with voice instructions)

**Camera Controls**:
- Resolution selection
- Exposure, Contrast, Saturation, Gamma, Hue, Sharpness, Gain adjustments
- Zoom controls
- Recording timer display

### 3.2 Patient Management

**Patient Entity** (`Patient.kt`):
- Fields: code (Global ID), firstName, lastName, title, gender, age, phone, email, address, etc.
- Stored in Room database
- Can be synced to/from backend API

**Patient Operations**:
- Create new patient (local + cloud sync)
- Search patients (local + cloud search)
- View patient sessions
- Patient dashboard with session history

### 3.3 Session Management

**Session Entity** (`Session.kt`):
- Links patient to multiple media captures
- Tracks creation/completion times
- Media count tracking

**Session Operations**:
- Auto-create session on patient selection
- View all sessions for a patient
- Session detail view with media grid
- Session completion tracking

### 3.4 Media Management

**Media Storage**:
- **Local**: Files stored in app's internal/external storage
- **Database**: Room database stores metadata (`MediaRecord`)
- **Cloud**: AWS S3 for file storage, DynamoDB for metadata

**Media Operations**:
- Capture photos/videos
- View gallery (grid/list view)
- Media preview/viewer
- Cloud sync (upload to S3 + metadata to Lambda)
- Sync status tracking (synced/unsynced)

**Cloud Sync Process** (`CloudSyncService.kt`):
1. Upload file to S3: `s3://oralvis-media/{GlobalPatientId}/{ClinicId}/{FileName}`
2. Call Lambda API with metadata JSON
3. Update local database sync status

### 3.5 Cloud Integration

**AWS Services Used**:
- **S3**: Media file storage
- **API Gateway**: REST API endpoints
- **Lambda**: Backend processing (patient sync, media metadata sync)
- **DynamoDB**: (Inferred - likely stores patient/media metadata)

**API Endpoints**:
1. **Clinic Registration**:
   ```
   POST https://d3x0w8vpui.execute-api.ap-south-1.amazonaws.com/default/OralVis_ClinicRegistration
   ```

2. **Patient Sync**:
   ```
   POST https://te2fzjde7j.execute-api.ap-south-1.amazonaws.com/patients
   GET https://te2fzjde7j.execute-api.ap-south-1.amazonaws.com/patients?name={query}
   ```

3. **Media Metadata Sync**:
   ```
   POST https://ocki7ui6wa.execute-api.ap-south-1.amazonaws.com/default/SyncMediaMetadata
   ```

**Authentication**:
- Uses `ClinicId` header for authenticated requests
- AWS credentials stored in `BuildConfig` (from `local.properties`)

### 3.6 Database Architecture

**Room Database** (`MediaDatabase.kt`):
- **Patients Table**: Patient information
- **Sessions Table**: Session records
- **Media Table**: Media file metadata
- Uses Kotlin Coroutines Flow for reactive data observation

**Key DAOs**:
- `PatientDao`: Patient CRUD operations
- `SessionDao`: Session management
- `MediaDao`: Media record management

### 3.7 Theme Management

**ThemeManager**:
- Light/Dark theme support
- Theme preference persisted
- Dynamic color application across activities
- Theme toggle in navigation

### 3.8 Guided Capture

**GuidedCaptureManager**:
- Automated capture sequences
- Voice instructions (audio files: Ins1.wav - Ins5.wav)
- Dental arch selection (Upper/Lower)
- Sequence number tracking
- PDF guides (GuidedCapture.pdf, Gallery.pdf)

---

## 4. Technical Architecture

### 4.1 Technology Stack

- **Language**: Kotlin
- **UI Framework**: Android Views (not Jetpack Compose)
- **Architecture**: MVVM-like (ViewModels for some screens)
- **Database**: Room (SQLite)
- **Networking**: Retrofit + OkHttp
- **Cloud**: AWS SDK (S3, API Gateway)
- **Image Processing**: OpenCV 4.9.0
- **Camera**: Custom USB camera libraries (libausbc, libuvc)

### 4.2 Key Design Patterns

1. **Manager Pattern**: `ClinicManager`, `SessionManager`, `ThemeManager`
2. **Service Pattern**: `CloudSyncService` (object singleton)
3. **Repository Pattern**: DAOs for database access
4. **Observer Pattern**: Flow-based reactive data observation

### 4.3 Data Flow

```
User Input
    ↓
Activity/ViewModel
    ↓
Manager/Service
    ↓
API/Database
    ↓
Cloud Storage (S3/DynamoDB)
```

---

## 5. Security Considerations

### Current Implementation:
- ✅ Clinic ID encrypted in SAF file (AES-128-GCM)
- ✅ AWS credentials in BuildConfig (not in source code)
- ✅ HTTPS API endpoints

### Potential Concerns:
- ⚠️ Hardcoded encryption key in `ClinicIdentityManager` (not secure for production)
- ⚠️ No user authentication (anyone with device can access)
- ⚠️ AWS credentials in BuildConfig (should use IAM roles or secure storage)

---

## 6. Key Files Reference

### Authentication/Registration:
- `SplashActivity.kt` - App entry point, clinic check
- `ClinicRegistrationActivity.kt` - Clinic registration UI
- `ClinicManager.kt` - Local clinic ID storage
- `ClinicIdentityManager.kt` - Persistent clinic ID management

### Main Activities:
- `MainActivity.kt` - Camera interface
- `HomeActivity.kt` - Dashboard/home screen
- `PatientDashboardActivity.kt` - Patient selection/creation

### Data Management:
- `database/` - Room database entities and DAOs
- `api/` - Retrofit API service and models
- `cloud/CloudSyncService.kt` - Cloud synchronization

### Core Features:
- `session/SessionManager.kt` - Session management
- `ThemeManager.kt` - Theme management
- `guidedcapture/` - Guided capture functionality

---

## 7. Summary

**Authentication Model**: Clinic-based registration (no user login)
- One clinic per device
- Clinic ID persists via encrypted SAF file
- Auto-restore on app reinstall

**App Flow**: 
1. Register clinic → 2. Select/Create patient → 3. Capture media → 4. Sync to cloud

**Core Features**:
- USB camera capture (photos/videos)
- Patient & session management
- Cloud sync (S3 + API)
- Guided capture with voice instructions
- Theme support (light/dark)

**Architecture**: Android native app with Room database, Retrofit networking, AWS cloud integration.

