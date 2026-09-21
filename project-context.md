# Project Context: JTBS Classic / JTBS Live

## 1. Overview & System Purpose

**JTBS Classic** is the digital live broadcasting and channel management platform for **Jishu Television's Broadcasting Services (JTBS)**. The system delivers synchronized live TV streams, cultural programming, religious telecasts, and community events to web browsers and dedicated Android TV/mobile decoder applications.

Key operational highlights:
- **Zero-Cost Serverless Architecture**: Operates entirely on standard free/freemium cloud services (Firebase Hosting, Cloud Firestore, Cloud Functions).
- **Synchronized IPTV Playback Engine**: Synchronizes video playback across all connected web viewers and hardware Android decoders using stream start timestamps, media duration detection, and automatic elapsed time calculations.
- **Multi-Source Video Support**: Supports HLS (`.m3u8`), MPEG-DASH (`.mpd`), direct MP4 video loops, YouTube Live embeds, and Facebook Video embeds.
- **Remote Decoder Fleet Control**: Provides real-time status telemetry (`decoderStatus`) and remote playback management (`decoderControl`) for standalone Android TV decoder boxes.

---

## 2. Workspace & Repository Structure

The workspace contains two primary sub-directories:

```
jtbs-live (1)/
├── project-context.md              # Global project documentation (this file)
├── jtbs-classic-showcase/          # Public showcase & download documentation repo
│   ├── .github/
│   └── README.md                   # Channel details & Android APK download links
└── jtbs-live/                      # Main production codebase & application workspace
    ├── firebase.json               # Firebase Hosting, Firestore, and Functions configuration
    ├── .firebaserc                 # Active Firebase project mapping (default: "jtbs-classic")
    ├── firestore.rules             # Cloud Firestore security rules & RBAC access controls
    ├── firestore.indexes.json      # Firestore index definitions
    ├── SETUP.md                    # Setup, provisioning, and deployment guide
    ├── seed-admin.js               # Initial superadmin provisioning script (Firebase Admin SDK)
    ├── run-once-superadmin.js      # Alternative quick admin creation script
    │
    ├── functions/                  # Firebase Cloud Functions (Node.js 18 runtime)
    │   ├── package.json
    │   └── index.js                # Secure HTTPS callable functions (createAdminUser, setAdminClaim, deleteAdminUser)
    │
    ├── public/                     # Static Web Application (Firebase Hosting root)
    │   ├── index.html              # Main Live Viewer web application (HLS.js, Dash.js, IPTV Sync Engine)
    │   ├── admin.html              # Comprehensive Superadmin Control Dashboard
    │   ├── manifest.json           # PWA web app manifest
    │   └── sw.js                   # Service Worker script
    │
    ├── puppeteer/                  # End-to-end automated testing suite
    │   ├── test.js                 # Puppeteer integration test script
    │   ├── task.md                 # Test task checklist & verification logs
    │   └── walkthrough.md          # Verification walk-through and screenshot evidence
    │
    ├── jtbs-apk/                   # Native Android Applications source code & compiled APKs
    │   ├── JTBS-Classic.apk        # Compiled Android client app
    │   ├── JTBS-Decoder-*.apk      # Compiled hardware decoder APK versions
    │   ├── jtbs/                   # Jetpack Compose / WebView client Android project
    │   ├── jtbs-launcher/          # Primary ExoPlayer-based Android TV Decoder & Launcher app
    │   ├── jtbs-launcher-vlc/      # Hardware decoding fallback app powered by LibVLC
    │   └── logcat_*.txt            # Hardware diagnostic logs from Android TV test units
    │
    └── scratch/                    # Diagnostic scripts, log parsing tools, and recovery modules
        ├── extract.ps1
        └── edits_history.txt
```

---

## 3. Technology Stack

| Layer | Technology / Library | Purpose |
| :--- | :--- | :--- |
| **Web Frontend** | HTML5, Vanilla CSS3, JavaScript (ES6+) | Lightweight, high-performance web viewer & admin portal |
| **Media Playback (Web)** | HLS.js (`v1.4.12`), Dash.js (`v4.7.4`) | Native HTTP Live Streaming & DASH playback in browser |
| **Cloud Hosting** | Firebase Hosting | CDN edge distribution with custom SSL & URL rewrites |
| **Database** | Cloud Firestore | Real-time state synchronization (`streamState`, `chat`, `blessings`) |
| **Authentication** | Firebase Auth | Email/Password & Google Sign-In with Custom Claims |
| **Backend Logic** | Firebase Cloud Functions (`Node.js 18`) | Secure administrative user creation, role assignment, and deletion |
| **Android Apps** | Kotlin, Android SDK, Media3 / ExoPlayer, LibVLC | Dedicated Android TV Launcher, decoder application, and auto-boot service |
| **Automated Testing** | Puppeteer (Node.js) | End-to-end stream live testing, duration check verification, and sync tests |

---

## 4. Firestore Database Schema

The system relies on real-time listeners across specific Firestore documents:

### Core Operational Documents
- **`streamState/main`**:
  - `isLive` (*boolean*): Master toggle indicating whether the stream is active.
  - `streamUrl` (*string*): Active video source URL (HLS `.m3u8`, DASH `.mpd`, direct MP4, YouTube, or Facebook).
  - `playMode` (*string*): Playback behavior (`"loop"` or `"once"`).
  - `startTime` (*timestamp/ms*): Epoch timestamp when the stream went live (used for IPTV clock synchronization).
  - `duration` (*number*): Detected duration in seconds for static media files.
  - `offlineMessage` (*string*): Custom text displayed on the off-air screen.
  - `offlineImageUrl` (*string*): Background image for off-air screen.

- **`config/main`**:
  - `channelName` (*string*): Watermark and header display name.
  - `logoUrl` (*string*): Direct image URL for channel branding logo.

- **`announcement/current`**:
  - `text` (*string*): Active ticker/marquee message displayed on viewer screens.
  - `active` (*boolean*): Visibility toggle for announcement bar.

### User Interaction & Moderation
- **`chat/{id}`**: Viewer chat messages (`name`, `message`, `timestamp`).
- **`blessings/{id}`**: Prayer & blessing submissions (`name`, `message`, `timestamp`).
- **`blessCount/main`**: Global blessing counter (`count`).
- **`quotes/{id}`**: Daily quotes shown on viewer screens (`quote`, `author`, `active`).

### Fleet Control & Telemetry
- **`admins/{uid}`**: User profiles (`email`, `displayName`, `role`: `"admin"` | `"superadmin"`, `createdAt`).
- **`decoderStatus/{id}`**: Telemetry reported by Android hardware decoders (buffer status, playback state, frame drops, network speed, active URL).
- **`decoderControl/{id}`**: Remote commands issued from admin panel to decoders (reboot app, refresh stream, change target URL).
- **`activeViewers/{id}`**: Active viewer session ping records.

---

## 5. Security & Access Control (RBAC)

Firestore security rules (`firestore.rules`) and Firebase Cloud Functions enforce strict role-based access:

- **Public Read Access**: `streamState/main`, `config/main`, `announcement/current`, `blessCount/main`, `chat`, `blessings`, `quotes`.
- **Public Write Access**:
  - `blessCount/main` (increment counter).
  - `chat/{id}` & `blessings/{id}` (validated field length: name $\le 100$ chars, message $\le 300$ chars, timestamp matching `request.time`).
  - `decoderStatus/{id}` (decoders write telemetry).
- **Admin Write Access** (`role in ['admin', 'superadmin']`):
  - Modifying `streamState/main`, `config/main`, `announcement/current`, `quotes`, `decoderControl`.
  - Deleting chat messages or blessings.
- **Superadmin Only Access**:
  - Admin account management via Cloud Functions (`createAdminUser`, `setAdminClaim`, `deleteAdminUser`).
  - Modifying admin roles in `admins/{uid}` document.

---

## 6. Key Application Modules & Features

### A. Viewer Web App (`public/index.html`)
1. **Dynamic Video Format Router**: Automatically detects HLS, DASH, direct MP4, YouTube iframe, or Facebook iframe based on URL patterns.
2. **IPTV Synchronized Seeking Engine**: When playing looped video files or non-live streams, calculates `(currentTime - startTime) % duration` and seeks the player so all global viewers are perfectly synchronized to the exact same second of video.
3. **Interactive Overlays**: Live badge, channel watermark/logo, scrollable chat panel, blessing box, announcement ticker, and full-screen controls.

### B. Admin Control Dashboard (`public/admin.html`)
1. **Live Stream Control Panel**: Instant ON/OFF Air toggle, stream URL validator, media duration auto-fetcher, loop/once playMode selector.
2. **Live Telemetry & Decoder Monitor**: Real-time health view of connected Android decoder boxes.
3. **Content Moderation & Management**: Live chat message deletion, blessing approval/tally, marquee message manager, branding/logo updating.
4. **User Management**: Provisioning new admin/superadmin accounts via Firebase Cloud Functions calls.

### C. Native Android TV Decoder (`jtbs-apk/jtbs-launcher`)
1. **Auto-Boot on Device Startup**: `BootReceiver.kt` listens for `ACTION_BOOT_COMPLETED` to immediately start `MainActivity.kt` on TV box power-on.
2. **ExoPlayer Playback Engine**: Robust hardware-accelerated playback with custom error handling (`PlayerHealthMonitor`, `RecoveryManager`) to restart streams on network interruption.
3. **Firestore Sync & Control**: Continuously pulls `streamState/main` from Firestore and synchronizes playback. Sends heartbeats to `decoderStatus`.

---

## 7. Deployment & Operations Guide

### Deployment Instructions
From `jtbs-live/` directory:
```bash
# 1. Deploy Cloud Functions
cd functions && npm install && cd ..
firebase deploy --only functions

# 2. Deploy Firestore Rules
firebase deploy --only firestore:rules

# 3. Deploy Web Hosting
firebase deploy --only hosting

# Full Deployment
firebase deploy
```

### Initial Superadmin Setup
1. Download Service Account Key from Firebase Console to `jtbs-live/serviceAccountKey.json`.
2. Edit `seed-admin.js` with superadmin email, password, and display name.
3. Run `node seed-admin.js` to create the initial superadmin in Firebase Auth and Firestore.
4. Delete `serviceAccountKey.json` after setup.

---

## 8. Development & Maintenance Guidelines

- **No Unintended Code Changes**: Always preserve existing API contracts, Firestore field structures, and CSS styling tokens.
- **Service Account Safety**: Never commit `serviceAccountKey.json` or Firebase private keys to version control.
- **Cross-Platform Parity**: Any changes to stream synchronization math (`startTime`, `duration`, `playMode`) in `index.html` must be reflected in `admin.html` (duration auto-fetcher) and `jtbs-launcher/MainActivity.kt` (Android ExoPlayer decoder).
