# Project Context: JTBS Classic / JTBS Live

## 1. Overview & System Purpose

**JTBS Classic** is the digital live broadcasting and channel management platform for **Jishu Television's Broadcasting Services (JTBS)**. The system delivers synchronized live TV streams, cultural programming, religious telecasts, and community events to web browsers, dedicated Android TV/mobile decoder applications, and standard IPTV players (VLC, TiviMate, OTT Navigator).

Key operational highlights:
- **Zero-Cost Serverless Architecture**: Operates entirely on standard free/freemium cloud services (Firebase Hosting, Cloud Firestore, Cloud Functions, Cloudflare Workers).
- **Synchronized IPTV Playback Engine**: Synchronizes video playback across all connected web viewers and hardware Android decoders using stream start timestamps, media duration detection, and automatic elapsed time calculations.
- **Multi-Source Video Support**: Supports HLS (`.m3u8`), MPEG-DASH (`.mpd`), direct MP4/WebM/MKV/AVI/MOV/TS video loops, YouTube Live embeds, YouTube Playlist sequential playback, and Facebook Video embeds.
- **Remote Decoder Fleet Control**: Provides real-time status telemetry (`decoderStatus`) and remote playback management (`decoderControl`) for standalone Android TV decoder boxes.
- **Cloudflare Worker IPTV Proxy**: An intelligent edge proxy that routes static IPTV endpoints (`/live.m3u8`, `/decoder.m3u8`, `/live1.m3u8`–`/live6.m3u8`) to live or offline sources by querying Firestore in real-time, ensuring seamless off-air transitions for IPTV players.
- **Multi-Channel Broadcasting**: Supports 8 independent channels — Main Public Stream, Decoder Stream, and 6 additional Feeds — each with independent live/offline state and offline fallback media.

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
    │   ├── admin.html              # Comprehensive Superadmin Control Dashboard (7 tabs)
    │   ├── playlist.m3u            # IPTV M3U Playlist file with all 8 channels for IPTV players
    │   ├── manifest.json           # PWA web app manifest
    │   └── sw.js                   # Service Worker script
    │
    ├── cloudflare-worker/          # Cloudflare Worker IPTV Reverse Proxy
    │   └── index.js                # Stream state router, HLS rewriter, off-air playlist generator
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
| **IPTV Edge Proxy** | Cloudflare Workers | Intelligent HLS reverse proxy with Firestore-driven stream routing |
| **Android Apps** | Kotlin, Android SDK, Media3 / ExoPlayer, LibVLC | Dedicated Android TV Launcher, decoder application, and auto-boot service |
| **Automated Testing** | Puppeteer (Node.js) | End-to-end stream live testing, duration check verification, and sync tests |

---

## 4. Firestore Database Schema

The system relies on real-time listeners across specific Firestore documents:

### Core Operational Documents

- **`streamState/main`**: Primary stream state for Public and Decoder streams.
  - `mode` (*string*): `"same"` (single stream for both) or `"different"` (independent Public & Decoder).
  - `isLive` (*boolean*): Master toggle — whether the public stream is active.
  - `streamUrl` (*string*): Active video source URL (HLS `.m3u8`, DASH `.mpd`, direct MP4, YouTube, Facebook).
  - `streamType` (*string*): Detected format — `"youtube"` | `"hls"` | `"dash"` | `"video"` | `"iframe"`.
  - `playMode` (*string*): Playback behavior — `"offair"` | `"repeat"` | `"loop"` | `"live"`.
  - `mp4PlayMode` (*string*): MP4/TS-specific playback — `"repeat"` | `"offair"`.
  - `duration` (*number*): Detected duration in seconds for static media files.
  - `selectedVideos` (*array*): Selected YouTube playlist video IDs.
  - `videoDurations` (*object*): Duration map for selected playlist videos.
  - `allVideos` (*array*): Full YouTube playlist video ID list.
  - `allDurations` (*object*): Duration map for full playlist.
  - `offlineImageUrl` (*string*): Background poster image (PNG/JPG/WebP) shown on web app, website, and decoder when off-air.
  - `offlineHlsUrl` (*string*): HLS (`.m3u8`) or TS/MP4 URL played in IPTV players (VLC, TiviMate) when off-air.
  - `offlineVideoUrl` (*string*): Legacy offline video URL field.
  - `offlineText` (*string*): Custom text displayed on the off-air screen.
  - `updatedAt` (*timestamp*): Last update timestamp.
  - `updatedBy` (*string*): Email of admin who made the last change.
  - **Decoder-specific fields** (when `mode === "different"`):
    - `decoderIsLive` (*boolean*): Independent decoder stream toggle.
    - `decoderStreamUrl` (*string*): Decoder-specific stream URL.
    - `decoderStreamType` (*string*): Detected format for decoder stream.
    - `decoderDuration` (*number*): Decoder stream media duration.
    - `decoderPlayMode` (*string*): Decoder playback behavior.
    - `decoderMp4PlayMode` (*string*): Decoder MP4/TS playback mode.
    - `decoderOfflineImageUrl` (*string*): Decoder offline poster image.
    - `decoderOfflineHlsUrl` (*string*): Decoder offline HLS/TS stream.
    - `decoderOfflineVideoUrl` (*string*): Decoder legacy offline video.
    - `decoderOfflineText` (*string*): Decoder offline message.

- **`streamState/feed1` through `streamState/feed6`**: Independent state for 6 secondary stream feeds.
  - `isLive` (*boolean*): Feed on/off toggle.
  - `streamUrl` (*string*): Feed stream URL.
  - `streamType` (*string*): Detected format.
  - `mode` / `playMode` (*string*): Playback behavior.
  - `mp4PlayMode` (*string*): MP4/TS playback mode.
  - `selectedVideos` (*array*): YouTube playlist selection.
  - `offlineImageUrl` (*string*): Per-feed offline poster image.
  - `offlineHlsUrl` (*string*): Per-feed offline HLS/TS URL for IPTV players.
  - `offlineVideoUrl` (*string*): Per-feed legacy offline video.
  - `offlineText` (*string*): Per-feed offline message.
  - `updatedAt` (*timestamp*): Last update time.

- **`config/main`**: Global channel configuration & branding.
  - `channelName` (*string*): Watermark and header display name.
  - `logoUrl` (*string*): Direct image URL for channel branding logo.
  - `offlineImageUrl` (*string*): Default fallback offline poster image.
  - `offlineVideoUrl` (*string*): Default fallback offline video URL.
  - `offlineVideoDuration` (*number*): Offline video duration.
  - `offlineText` (*string*): Default offline message.
  - `offlineHlsUrl` (*string*): Default offline HLS URL.
  - `showLogoOffAir` (*boolean*): Whether to show channel logo on off-air screen.
  - `watermarkText` (*string*): Text shown under logo watermark.
  - `liveBadgeText` (*string*): Live badge text (e.g., `"LIVE"`).
  - `appDownloadUrl` (*string*): APK download link for Android app popup.
  - `iptvDomain` (*string*): Cloudflare Tunnel domain (e.g., `jtbsclassic.dpdns.org`).
  - `logoSize` (*number*): Logo size in pixels (30–150).
  - `watermarkTextSize` (*number*): Watermark text font size (10–36px).
  - `badgeSize` (*number*): Live badge font size (8–24px).
  - `logoTop`, `logoLeft`, `badgeTop`, `badgeLeft` (*number*): Position offsets.
  - `decoderOfflineImageUrl`, `decoderOfflineHlsUrl`, `decoderOfflineVideoUrl`, `decoderOfflineText` (*string*): Decoder-specific offline defaults.

- **`announcement/current`**: Real-time push announcement banner.
  - `text` (*string*): Active ticker/marquee message.
  - `color` (*string*): Theme color — `"gold"` | `"red"` | `"blue"` | `"green"` | `"purple"`.
  - `active` (*boolean*): Visibility toggle for announcement bar.
  - `pushedAt` (*timestamp*): When the announcement was pushed.

### User Interaction & Moderation
- **`chat/{id}`**: Viewer chat messages (`name`, `message`, `timestamp`).
- **`blessings/{id}`**: Prayer & blessing submissions (`name`, `message`, `timestamp`).
- **`blessCount/main`**: Global blessing counter (`count`).
- **`quotes/{id}`**: Daily quotes shown on viewer screens (`quote`, `author`, `active`).

### Fleet Control & Telemetry
- **`admins/{uid}`**: User profiles (`uid`, `email`, `displayName`, `role`: `"admin"` | `"superadmin"`, `linkedGoogle`, `createdAt`, `createdBy`).
- **`decoderStatus/{id}`**: Telemetry from Android decoders (`customName`, `deviceName`, `playbackState`, `resolution`, `bitrate`, `freeRamMb`, `uptimeSeconds`, `sourceUptimeSeconds`, `appVersion`, `lastError`, `lastSeen`).
- **`decoderControl/{id}`**: Remote commands to decoders (`enabled`, `updatedAt`).
- **`activeViewers/{sessionId}`**: Active viewer session heartbeats (`id`, `ip`, `location`, `os`, `device`, `browser`, `lastActive`).

---

## 5. Security & Access Control (RBAC)

Firestore security rules (`firestore.rules`) and Firebase Cloud Functions enforce strict role-based access:

- **Public Read Access**: `streamState/main`, `streamState/feed1`–`feed6`, `config/main`, `announcement/current`, `blessCount/main`, `chat`, `blessings`, `quotes`.
- **Public Write Access**:
  - `blessCount/main` (increment counter).
  - `chat/{id}` & `blessings/{id}` (validated field length: name ≤ 100 chars, message ≤ 300 chars, timestamp matching `request.time`).
  - `decoderStatus/{id}` (decoders write telemetry).
- **Admin Write Access** (`role in ['admin', 'superadmin']`):
  - Modifying `streamState/main`, `streamState/feed1`–`feed6`, `config/main`, `announcement/current`, `quotes`, `decoderControl`.
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
4. **Off-Air Poster Image Rendering**: When off-air, `showOffAir()` resolves the `offlineImageUrl` from `streamState/main` (with `config/main` fallback) and displays it as a full-screen background poster at `opacity: 0.9` via the `#offair-bg` overlay element. If no image is set, displays a clean dark slate.
5. **Off-Air Message Display**: Shows `offlineText` as a subtitle message on the off-air screen.

### B. Admin Control Dashboard (`public/admin.html`) — 7 Tabs

#### Tab 1: 🔴 Stream
- **Stream Mode Selector**: Radio toggle between `Same Stream` (single stream for Public & Decoder) and `Different Streams` (independent parallel control).
- **Same Mode**: Single toggle + stream URL input + YouTube/MP4 playback mode selectors + Go Live button + YouTube Playlist Selector with Select All/Deselect All.
- **Different Mode**:
  - **📺 Public Stream Card**: Independent toggle, URL input, playback modes, Go Live, offline fields (Image URL, HLS URL, Playback Mode, Text), playlist selector.
  - **📡 Decoder Stream Card**: Independent toggle, URL input, playback modes, Go Live, offline fields (Image URL, HLS URL, Playback Mode, Text), playlist selector.
- **Offline Fields** (per stream):
  - `Offline Image URL`: Direct poster image (PNG/JPG/WebP) displayed on web app, website, and decoder when off-air.
  - `Offline HLS Stream URL`: HLS/TS/MP4 URL played in IPTV players when off-air (never disconnects IPTV clients).
  - `Playback Mode` (auto-revealed when TS URL detected): Radio — `Loop / Repeat` (default) | `Off Air`.
  - `Offline Message`: Custom text shown on the off-air screen.
- **IPTV Direct Links Card**: Static URLs (`/live.m3u8`, `/live`) based on configured `iptvDomain` with copy-to-clipboard.

#### Tab 2: 📢 Announce
- Announcement text input, theme color dropdown (Gold/Red/Blue/Green/Purple), Push Live button, Clear Banner button.

#### Tab 3: 🎨 Branding
- Channel name, logo URL (with live preview), offline image/video URL, offline duration, offline message, off-air logo visibility toggle, watermark text, live badge text, app download URL.
- Range sliders: Logo size (30–150px), watermark text size (10–36px), badge size (8–24px).
- IPTV Domain field (Cloudflare Tunnel domain for generating IPTV endpoint URLs).

#### Tab 4: 👤 Users (Superadmin only)
- Admin list table (email, display name, role, Google link status, Edit/Delete/Link Google actions).
- Add Admin modal (name, email, password, role).
- Edit Admin modal (display name, role change, password reset email).

#### Tab 5: 👥 Live Count
- Real-time active web viewer count.
- Active Viewers table (IP, Geolocation, Browser, OS/Device, Last Active).
- Auto-cleanup of stale sessions (>45 seconds).

#### Tab 6: 📡 Decoders
- Monitoring dashboard for Android TV decoder hardware.
- Per-decoder status cards: Online/Stale/Offline indicators, Hardware ID, Custom Name, Remote Switch toggle, App State, Resolution, Bitrate, RAM, Source/Device Uptime, App Version, Last Error.
- Actions: Rename, Toggle remote switch, Delete decoder record.

#### Tab 7: 📡 Other Streams (Feeds 1–6)
- Sub-tabs for **Feed 1 (live1)** through **Feed 6 (live6)**.
- Each feed has an independent control panel:
  - Stream status toggle.
  - Stream URL input with auto-type detection.
  - YouTube Playback Mode radios (Off Air / Repeat / Loop).
  - MP4 Playback Settings radios (Repeat / Off Air) with real-time elapsed timer.
  - YouTube Playlist Selector Card.
  - **Per-Feed Offline Fields**:
    - Offline Image URL (poster for web/app/decoder).
    - Offline HLS Stream URL (for IPTV players).
    - Playback Mode (Loop / Off Air — auto-revealed when TS URL detected, default: Loop).
    - Offline Message text.
  - Save Feed Settings button.
  - Static IPTV URL display (`https://{iptvDomain}/live{i}.m3u8`) with copy button.

### C. Cloudflare Worker IPTV Proxy (`cloudflare-worker/index.js`)
1. **Stream State Router**: Queries Firestore REST API on every request to determine live/offline state per channel.
2. **HLS Manifest Rewriter (`rewriteM3u8`)**: Rewrites relative M3U8 playlists to route sub-manifests and `.ts`/`.mp4` media segments back through the worker proxy.
3. **Session Cookie Management (`extractSetCookies`)**: Preserves upstream provider session cookies for protected HLS streams.
4. **Off-Air HLS Generator (`makeOfflinePlaylist`)**: Generates a 5-segment sliding window HLS playlist for offline TS/MP4 media, keeping IPTV players continuously connected during off-air periods.
5. **Discontinuity Injection**: Inserts `#EXT-X-DISCONTINUITY` tags when stream sources change to prevent playback glitches.
6. **Routes**:

| Route | Description |
| :--- | :--- |
| `/live`, `/live.m3u8` | Main Public Stream → `streamState/main` |
| `/decoder`, `/decoder.m3u8` | Decoder Box Stream → `streamState/main` (reads `decoderIsLive`/`decoderStreamUrl` if `mode === "different"`) |
| `/live1`–`/live6`, `/live1.m3u8`–`/live6.m3u8` | Feed 1–6 → `streamState/feed1`–`streamState/feed6` |
| `/proxy?doc=&url=&cookie=` | Proxies live/offline HLS manifests and media segments with Firestore state awareness |

### D. IPTV Playlist (`public/playlist.m3u`)
- Hosted at `https://jtbs-classic.web.app/playlist.m3u`.
- Contains 8 channel entries with TVG metadata (IDs, names, logos, group titles) pointing to Cloudflare Worker endpoints.
- Compatible with VLC, TiviMate, OTT Navigator, and any standard IPTV player.
- **Channels**: JTBS Main Stream (Public), JTBS Decoder Stream (Box), JTBS Feed 1–6.

### E. Native Android TV Decoder (`jtbs-apk/jtbs-launcher`)
1. **Auto-Boot on Device Startup**: `BootReceiver.kt` listens for `ACTION_BOOT_COMPLETED` to immediately start `MainActivity.kt` on TV box power-on.
2. **ExoPlayer Playback Engine**: Robust hardware-accelerated playback with custom error handling (`PlayerHealthMonitor`, `RecoveryManager`) to restart streams on network interruption.
3. **Firestore Sync & Control**: Continuously pulls `streamState/main` from Firestore and synchronizes playback. Sends heartbeats to `decoderStatus`.

---

## 7. Off-Air Architecture

The system uses a **two-field offline architecture** for seamless off-air experiences across all platforms:

### Field 1: Offline Image URL (`offlineImageUrl`)
- **Purpose**: Static poster image displayed when the channel is off-air.
- **Consumers**: Web App (`index.html`), Website, Android Decoder App.
- **Rendering**: Full-screen background image at 90% opacity over a black backdrop.

### Field 2: Offline HLS Stream URL (`offlineHlsUrl`)
- **Purpose**: HLS/TS/MP4 video that plays continuously in IPTV players when off-air.
- **Consumers**: VLC, TiviMate, OTT Navigator, and any IPTV player via Cloudflare Worker proxy.
- **Playback Mode** (when TS link detected):
  - `Loop / Repeat` (default): Continuously loops the offline video.
  - `Off Air`: Plays a single segment then sends `#EXT-X-ENDLIST`.
- **Mechanism**: Cloudflare Worker generates a sliding-window HLS playlist (`makeOfflinePlaylist`) with 5 segments, maintaining an active HLS session so IPTV players never disconnect.

### Per-Channel Offline Support
- **Main Stream (Same Mode)**: Shared offline fields for both Public and Decoder.
- **Main Stream (Different Mode)**: Independent offline fields for Public and Decoder cards.
- **Feeds 1–6**: Each feed has its own offline image, HLS URL, playback mode, and message.

---

## 8. Deployment & Operations Guide

### Firebase Deployment
From `jtbs-live/` directory:
```bash
# 1. Deploy Cloud Functions
cd functions && npm install && cd ..
firebase deploy --only functions

# 2. Deploy Firestore Rules
firebase deploy --only firestore:rules

# 3. Deploy Web Hosting (includes admin.html, index.html, playlist.m3u)
firebase deploy --only hosting

# Full Deployment
firebase deploy
```

### Cloudflare Worker Deployment
1. Open [Cloudflare Dashboard](https://dash.cloudflare.com) → **Workers & Pages** → **jtbs-iptv**.
2. Copy the contents of `cloudflare-worker/index.js` into the worker editor.
3. Click **Save and Deploy**.

### Initial Superadmin Setup
1. Download Service Account Key from Firebase Console to `jtbs-live/serviceAccountKey.json`.
2. Edit `seed-admin.js` with superadmin email, password, and display name.
3. Run `node seed-admin.js` to create the initial superadmin in Firebase Auth and Firestore.
4. Delete `serviceAccountKey.json` after setup.

### IPTV Playlist Distribution
- Direct URL for IPTV players: `https://jtbs-classic.web.app/playlist.m3u`
- Contains all 8 channels pointing to `https://jtbsclassic.dpdns.org/` endpoints.

---

## 9. Development & Maintenance Guidelines

- **No Unintended Code Changes**: Always preserve existing API contracts, Firestore field structures, and CSS styling tokens.
- **Service Account Safety**: Never commit `serviceAccountKey.json` or Firebase private keys to version control.
- **Cross-Platform Parity**: Any changes to stream synchronization math (`startTime`, `duration`, `playMode`) in `index.html` must be reflected in `admin.html` (duration auto-fetcher), `jtbs-launcher/MainActivity.kt` (Android ExoPlayer decoder), and `cloudflare-worker/index.js` (IPTV proxy).
- **Global Scope for Admin Functions**: Functions called inside dynamic loops like `initFeedCards()` must be bound to `window` (e.g., `window.checkOfflineHlsUrl`) or defined globally before `initFeedCards()` executes.
- **HTML `<video>` HLS Limitation**: Setting `video.src = "file.m3u8"` on raw `<video>` elements fails in desktop Chrome/Firefox without HLS.js. For web off-air screens, always use `offlineImageUrl` with CSS background rendering, not raw video playback.
- **Worker Code Sync**: After modifying `cloudflare-worker/index.js`, always copy the updated code to the Cloudflare Dashboard manually (Workers & Pages → jtbs-iptv → Save and Deploy).
