<div align="center">
  <img src="https://jtbsclassic.dpdns.org/logo.png" alt="JTBS Classic Logo" width="160" height="160" />
  <h1>JTBS Classic / JTBS Live</h1>
  <p><strong>Official Digital Live Television Broadcasting Platform & Android Hardware Decoder Fleet</strong></p>
  <p>A Channel of Jishu Television’s Broadcasting Services (Siti Networks LCN 279 Krishnanagar)</p>

  <p>
    <a href="https://jtbsclassic.dpdns.org"><strong>🌐 Watch Live Web Player</strong></a> • 
    <a href="https://github.com/joelgomes001/jtbs-classic/releases/latest"><strong>📱 Download Android App</strong></a> • 
    <a href="#-shifting-website-hosting--iptv-m3u-to-another-host"><strong>🔄 Hosting & M3U Shift Guide</strong></a> • 
    <a href="HOSTING.md"><strong>🚀 Hosting Manual</strong></a> • 
    <a href="project-context.md"><strong>📖 System Specs</strong></a>
  </p>
</div>

---

## 📑 Table of Contents

1. [System Overview & Purpose](#-system-overview--purpose)
2. [Architecture & Core Subsystems](#-architecture--core-subsystems)
3. [⚡ Shifting Website Hosting & IPTV M3U to Another Host (Migration Blueprint)](#-shifting-website-hosting--iptv-m3u-to-another-host)
   - [Architectural Decoupling: What Moves vs. What Stays](#1-architectural-decoupling-what-moves-vs-what-stays)
   - [Step 1: Website Hosting Migration Options (Nginx, VPS, Docker, cPanel, Vercel)](#2-step-1-website-hosting-migration-options)
   - [Step 2: IPTV M3U Playlist & Stream Routing Migration](#3-step-2-iptv-m3u-playlist--stream-routing-migration)
   - [Step 3: CORS Headers & MIME Type Requirements](#4-step-3-cors-headers--mime-type-requirements)
   - [Step 4: Updating Android TV Decoders & Mobile Apps](#5-step-4-updating-android-tv-decoders--mobile-apps)
   - [Pre-Flight Migration & Cutover Checklist](#6-pre-flight-migration--cutover-checklist)
4. [Quick Start: Host in 3 Minutes](#-quick-start-host-in-3-minutes)
5. [Repository Structure](#-repository-structure)
6. [Core Technical Specifications](#-core-technical-specifications)
   - [Synchronized IPTV Playback Engine](#synchronized-iptv-playback-engine)
   - [Database Schema (Firestore)](#database-schema-firestore)
   - [Decoder Fleet Telemetry & Remote Control](#decoder-fleet-telemetry--remote-control)
7. [Daily Operations: Admin Control Panel](#-daily-operations-admin-control-panel)
8. [Hardware Decoder Setup (Android TV Box)](#-hardware-decoder-setup-android-tv-box)
9. [Rebroadcasting, Contact & Legal Notice](#-rebroadcasting-contact--legal-notice)

---

## 📺 System Overview & Purpose

**JTBS Classic** is the digital television transmission, channel management, and remote decoder distribution system for **Jishu Television's Broadcasting Services (JTBS)**, operating on **Siti Networks LCN 279 Krishnanagar, Nadia, West Bengal**.

The platform is engineered to solve the technical and financial hurdles of 24/7 continuous television broadcasting:
- **Zero Ongoing Cloud Costs**: Runs completely within the free usage tiers of modern cloud infrastructure (Firebase Hosting, Cloud Firestore, Cloud Functions, and edge CDNs).
- **Synchronized Multi-Device Playback**: Guarantees that whether a viewer watches on a web browser in Kolkata, a mobile device in London, or an Android TV set-top decoder box in Krishnanagar, every viewer is synchronized to the exact same second of the broadcast.
- **Dynamic Multi-Protocol Video Routing**: Plays HLS (`.m3u8`), MPEG-DASH (`.mpd`), static MP4 video loops, YouTube Live embeds, and Facebook Live streams interchangeably through a unified viewer interface without requiring player rebuilds.
- **Hardware Fleet Management**: Acts as an over-the-air central control unit for remote Android TV set-top boxes connected to cable distribution headends, offering live telemetry, buffer monitoring, and instant remote reboot/stream switching.

---

## 🏗️ Architecture & Core Subsystems

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                JTBS MASTER ARCHITECTURE                                  │
└─────────────────────────────────────────────────────────────────────────────────────────┘

        ┌─────────────────────────┐                ┌─────────────────────────┐
        │   ADMIN CONTROL PANEL   │                │   PUBLIC LIVE VIEWER    │
        │    (public/admin.html)  │                │    (public/index.html)  │
        └────────────┬────────────┘                └────────────▲────────────┘
                     │                                          │
                     │ Writes state & commands                  │ Real-time state updates
                     ▼                                          │
        ┌───────────────────────────────────────────────────────┴─────────────────────────┐
        │                            CLOUD FIRESTORE                                      │
        │  • streamState/main    (isLive, streamUrl, playMode, startTime, duration)       │
        │  • config/main         (channelName, logoUrl, customMarquee)                    │
        │  • decoderStatus/{id}  (hardware metrics, active stream, frame drops)           │
        │  • decoderControl/{id} (remote restart, URL override, volume control)          │
        │  • chat/{id} & blessings/{id} (viewer engagement & prayers)                     │
        └───────────────────────────────────────────────────────▲─────────────────────────┘
                     │                                          │
                     │ Role-Based Access                        │ Real-time sync & heartbeats
                     ▼                                          │
        ┌─────────────────────────┐                ┌────────────┴────────────┐
        │ FIREBASE AUTH & RULES   │                │  ANDROID TV DECODERS    │
        │ Cloud Functions (RBAC)  │                │  • jtbs-launcher        │
        │ setAdminClaim / delete  │                │  • jtbs-launcher-vlc    │
        └─────────────────────────┘                └─────────────────────────┘
```

The system comprises 4 primary components:

1. **Web Live Viewer (`public/index.html`)**:
   - Modern, lightweight responsive web player utilizing **HLS.js** (`v1.4.12`) and **Dash.js** (`v4.7.4`).
   - Integrated IPTV Sync Engine that calculates elapsed play time from `startTime` and loops static MP4 files uniformly.
   - Interactive overlays: live on-air badge, channel branding watermark, prayer/blessing ticker, viewer live chat, and announcement bar.

2. **Superadmin Control Dashboard (`public/admin.html`)**:
   - Master on/off-air broadcast switch with instant real-time propagation (<1 second).
   - Live stream URL validator and media duration auto-fetcher.
   - Hardware decoder telemetry monitor displaying real-time box CPU/memory health, buffer status, and playback state.
   - Moderation tools: chat message removal, blessing approval tally, and marquee announcement editor.
   - User administration: provision and revoke admin/superadmin accounts using Firebase Auth custom claims.

3. **Backend & Access Control (`functions/index.js` & `firestore.rules`)**:
   - Node.js 18 serverless functions managing administrative permissions with strict cryptographic token validation.
   - Granular Firestore security rules ensuring public viewers can only read stream status and post sanitized chat/blessings, while stream controls and telemetry commands require verified admin claims.

4. **Dedicated Hardware Decoder Fleet (`jtbs-apk/`)**:
   - Native Android applications (`jtbs-launcher` with ExoPlayer / Media3 and `jtbs-launcher-vlc` with LibVLC) designed for standalone set-top TV boxes.
   - Automated boot receiver (`BootReceiver.kt`) that boots into full-screen video playback the moment power is applied.
   - Self-healing watchdog (`PlayerHealthMonitor` & `RecoveryManager`) that auto-reconnects or power-cycles playback on network dropouts.
   - Continuous telemetry reporting to Firestore collection `decoderStatus`.

---

## 🔄 Shifting Website Hosting & IPTV M3U to Another Host

If you are planning to shift your website hosting and your IPTV M3U playlist from Firebase / Cloudflare to another hosting environment (such as a **Linux VPS with Nginx, Docker, cPanel / Apache, Vercel, Netlify, or a Dedicated Streaming Server**), follow this complete blueprint.

---

### 1. Architectural Decoupling: What Moves vs. What Stays

A critical advantage of JTBS is that **the web frontend is 100% decoupled from the database**:

| Component | Can It Be Moved to Another Host? | How It Works On the New Host |
| :--- | :---: | :--- |
| **Website Player & Admin (`public/`)** | **YES** (Anywhere) | Pure static HTML, CSS, and JS. The browser communicates directly with Firestore and stream servers. You can host it on Nginx, Apache, Vercel, Netlify, or Docker without touching Firebase. |
| **IPTV M3U Playlist (`playlist.m3u`)** | **YES** (Anywhere) | A standard text file with `#EXTM3U` headers. Can be served from any web server with the correct MIME type and CORS headers. |
| **Live Stream Relay (`/live.m3u8`)** | **YES** (Anywhere) | Can be served as a direct HLS link, a 302 HTTP redirect (via Nginx or `server.js`), or proxied directly through your new server. |
| **Firestore Database & Auth** | **STAYS IN CLOUD** | You **do not** need to move Firestore! Keep your free Firebase project active for real-time state synchronization (`streamState/main`), viewer chat, and admin authentication. It works seamlessly regardless of where the website is hosted. |

> [!TIP]
> You do **NOT** need to pay for Firebase Hosting or keep Firebase CLI to host the site elsewhere. The frontend JavaScript code connects to Firebase Auth and Firestore via Google's client SDK from any origin domain!

---

### 2. Step 1: Website Hosting Migration Options

Choose the environment where you plan to host the website:

#### Option A: Linux VPS (Ubuntu / Debian + Nginx) — *Recommended for Custom Control*

1. **Upload Code**:
   Copy the `jtbs-live/public` folder to your server (e.g., `/var/www/jtbs-live/public`).
2. **Install Nginx & Certbot**:
   ```bash
   sudo apt update && sudo apt install -y nginx certbot python3-certbot-nginx
   ```
3. **Configure Nginx Server Block**:
   Create `/etc/nginx/sites-available/jtbs-live`:
   ```nginx
   server {
       listen 80;
       server_name yourdomain.com www.yourdomain.com;
       root /var/www/jtbs-live/public;
       index index.html;

       # Security & CORS Headers
       add_header Access-Control-Allow-Origin "*" always;
       add_header Access-Control-Allow-Methods "GET, HEAD, OPTIONS" always;
       add_header X-Content-Type-Options "nosniff" always;

       # 1. SPA Rewrites for Admin Dashboard
       location /admin {
           try_files $uri /admin.html;
       }

       # 2. IPTV Playlist Serving with proper MIME type
       location ~* \.(m3u|m3u8)$ {
           types {
               application/vnd.apple.mpegurl m3u8;
               application/x-mpegurl m3u;
           }
           add_header Access-Control-Allow-Origin "*" always;
           add_header Cache-Control "no-cache, no-store, must-revalidate" always;
       }

       # 3. Dynamic Live Stream Redirect (/live.m3u8)
       # (Redirects to active HLS feed or proxies local IPTV server)
       location = /live.m3u8 {
           proxy_pass http://127.0.0.1:5000/live.m3u8;
           proxy_set_header Host $host;
           add_header Access-Control-Allow-Origin "*" always;
           add_header Cache-Control "no-cache, no-store, must-revalidate" always;
       }

       # 4. Static Files Fallback
       location / {
           try_files $uri $uri/ /index.html;
       }
   }
   ```
4. **Enable & Secure with SSL**:
   ```bash
   sudo ln -s /etc/nginx/sites-available/jtbs-live /etc/nginx/sites-enabled/
   sudo nginx -t && sudo systemctl reload nginx
   sudo certbot --nginx -d yourdomain.com -d www.yourdomain.com
   ```

---

#### Option B: Docker / Docker Compose (Any VPS / Cloud Server)

This repository includes a production-ready `Dockerfile` and `docker-compose.yml`.

1. Clone the repository on your server:
   ```bash
   git clone https://github.com/joelgomes001/JTBS-Migration.git
   cd JTBS-Migration
   ```
2. Start the container:
   ```bash
   docker compose up -d --build
   ```
3. The platform is now live on `http://YOUR_SERVER_IP:8080` with built-in `/admin` routing and `/live.m3u8` IPTV stream relay!

---

#### Option C: cPanel / Apache / Shared Hosting

If you are shifting to a standard cPanel or Apache shared hosting account:

1. Copy all files inside `jtbs-live/public/` directly into your cPanel `public_html/` directory.
2. An official [`.htaccess`](file:///c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY%20PROJECTS/jtbs-live%20%281%29/jtbs-live/public/.htaccess) file is already provided in the `public/` directory with:
   - `RewriteRule ^admin(/.*)?$ admin.html [L,QSA]` (handles `/admin` navigation)
   - `AddType application/x-mpegurl .m3u .m3u8` (ensures IPTV players accept the playlist)
   - `Header always set Access-Control-Allow-Origin "*"` (permits cross-origin IPTV playback)
3. Ensure SSL is enabled in cPanel via **AutoSSL** or **Let's Encrypt**.

---

#### Option D: Vercel / Netlify / Cloudflare Pages

1. **Vercel**:
   - Connect this GitHub repository to Vercel.
   - Set the Root Directory to the repository root.
   - The included [`vercel.json`](file:///c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY%20PROJECTS/jtbs-live%20%281%29/vercel.json) automatically configures routes for `/admin` -> `admin.html`.
2. **Netlify**:
   - Connect repository to Netlify.
   - Set **Publish directory** to `jtbs-live/public`.
   - The included [`_redirects`](file:///c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY%20PROJECTS/jtbs-live%20%281%29/jtbs-live/public/_redirects) file automatically routes `/admin` to `admin.html`.

---

### 3. Step 2: IPTV M3U Playlist & Stream Routing Migration

The IPTV playlist file is located at [**`jtbs-live/public/playlist.m3u`**](file:///c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY%20PROJECTS/jtbs-live%20%281%29/jtbs-live/public/playlist.m3u).

#### Understanding the M3U Structure:
```m3u
#EXTM3U url-tvg="https://yournewdomain.com/main.epg.xml" tvg-shift="0" x-tvg-url="https://yournewdomain.com/main.epg.xml"

#EXTINF:-1 tvg-id="JTBSClassic.in" tvg-name="JTBS Classic - Main Stream" tvg-logo="https://yournewdomain.com/favicon.ico" group-title="JTBS Network",JTBS Classic - Main Stream (Public)
https://yournewdomain.com/live.m3u8

#EXTINF:-1 tvg-id="JTBS.Decoder" tvg-name="JTBS Classic - Decoder Stream" tvg-logo="https://yournewdomain.com/favicon.ico" group-title="JTBS Network",JTBS Classic - Decoder Stream (Box)
https://yournewdomain.com/decoder.m3u8
```

#### How to Update for Your New Host:
1. Open `jtbs-live/public/playlist.m3u`.
2. Replace all occurrences of `https://jtbsclassic.dpdns.org` with your new hosting domain (e.g. `https://yournewdomain.com`).
3. For the stream links:
   - **Method 1 (Dynamic Redirect via `server.js` or `iptv-server.js`)**: Keep the URL pointing to `https://yournewdomain.com/live.m3u8`. The server reads Firestore `streamState/main` in real time and automatically redirects the player (HTTP 302) to whatever stream URL is active in the admin panel.
   - **Method 2 (Static Direct HLS Link)**: If you don't want a Node.js server running, paste your direct upstream HLS URL (e.g. `https://your-streaming-server.com/live/stream.m3u8`) directly under `#EXTINF` in `playlist.m3u`.

---

### 4. Step 3: CORS Headers & MIME Type Requirements

IPTV players (such as **TiviMate, VLC Media Player, OTT Navigator, IPTV Smarters**, and Smart TV apps) will fail to play streams or parse the playlist if the server lacks proper CORS headers and MIME types.

#### Required HTTP Response Headers for `playlist.m3u` & `live.m3u8`:
```http
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, HEAD, OPTIONS
Access-Control-Allow-Headers: Origin, X-Requested-With, Content-Type, Accept, Range
Content-Type: application/vnd.apple.mpegurl  (or application/x-mpegurl)
Cache-Control: no-cache, no-store, must-revalidate
```

Both [**`server.js`**](file:///c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY%20PROJECTS/jtbs-live%20%281%29/server.js), [**`jtbs-live/public/.htaccess`**](file:///c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY%20PROJECTS/jtbs-live%20%281%29/jtbs-live/public/.htaccess), and the Nginx configuration above are preconfigured with these headers.

---

### 5. Step 4: Updating Android TV Decoders & Mobile Apps

When you shift hosting domains:

1. **Android TV Decoders (`jtbs-launcher`)**:
   - The hardware decoders read `streamState/main` from **Firebase Cloud Firestore**, not from the web server.
   - Therefore, changing website hosts **does not disconnect your decoder boxes** as long as Firebase remains active!
   - If you also change your primary stream URL, update it directly in the Admin Dashboard (`/admin`), and all connected decoder boxes will switch automatically within 1–2 seconds.
2. **If You Change the Web Player URL in the Android App**:
   - In `jtbs-live/jtbs-apk/jtbs/app/src/main/java/com/example/jtbs/ui/web/WebPlayerScreen.kt`, update the embedded web view URL to your new domain.
   - Rebuild the APK using `./gradlew assembleRelease` or upload the new version to GitHub Releases.

---

### 6. Pre-Flight Migration & Cutover Checklist

Follow this sequence to ensure zero broadcast downtime during your hosting transition:

| Step | Action | Status | Notes |
| :---: | :--- | :---: | :--- |
| **1** | Deploy `public/` files to your new host (VPS / Docker / cPanel / Vercel) | 🔲 | Test via direct server IP or staging URL |
| **2** | Verify `/admin` route rewrite works on new host | 🔲 | Confirm opening `/admin` loads the admin dashboard |
| **3** | Test Firebase login on the new domain | 🔲 | Add new domain to Firebase Console -> Auth -> Authorized domains |
| **4** | Verify `playlist.m3u` loads with `Content-Type: application/x-mpegurl` | 🔲 | Test in VLC Player: Media -> Open Network Stream |
| **5** | Lower DNS TTL on your domain to 300 seconds (5 minutes) | 🔲 | Do this 24 hours before cutover |
| **6** | Update DNS A/CNAME records to point to the new host | 🔲 | Switch traffic |
| **7** | Issue SSL Certificate (Let's Encrypt / AutoSSL) | 🔲 | Ensure HTTPS works with no mixed-content warnings |
| **8** | Verify live stream playback on mobile, desktop, and IPTV player | 🔲 | Check audio/video synchronization |

---

## ⚡ Quick Start: Host in 3 Minutes

JTBS includes an interactive CLI setup wizard that configures any hosting target:

```bash
# Clone the repository
git clone https://github.com/joelgomes001/JTBS-Migration.git
cd JTBS-Migration

# Run the interactive setup wizard
npm run setup
```
*(On Windows: double-click `setup.bat`. On Linux/macOS: `./setup.sh`.)*

### Setup Wizard Menu:
```
╔═══════════════════════════════════════════════════════════════════════╗
║                  JTBS STREAMING PLATFORM SETUP WIZARD                 ║
╚═══════════════════════════════════════════════════════════════════════╝

  [1] 🚀 Full Firebase Serverless Setup       (Edge CDN + Cloud Functions RBAC)
  [2] 💻 Local / VPS Preview Server          (Instant HTTP server on port 5000)
  [3] 🐳 Docker Container Hosting            (1-command docker compose on port 8080)
  [4] ⚡ Cloudflare Worker Deployment        (Global Edge Worker deployment)
  [5] 🔑 Configure Firebase API Keys & ID     (Interactive web app credentials)
  [6] 👑 Superadmin Account Setup             (Assign superadmin role via service key)
  [7] 📖 View Documentation & Guides
  [0] 🚪 Exit
```

---

## 🗂️ Repository Structure

```
JTBS-Migration/
├── setup.js                        # Cross-platform interactive setup wizard
├── setup.bat                       # Windows 1-click launcher
├── setup.sh                        # Linux/macOS 1-click launcher
├── server.js                       # Zero-dependency local preview & VPS server
├── Dockerfile                      # Container build definition
├── docker-compose.yml              # 1-command Docker deployment
├── vercel.json                     # Vercel deployment configuration
├── HOSTING.md                      # Detailed multi-platform hosting manual
├── project-context.md              # Complete architecture & Firestore database schemas
│
├── jtbs-classic-showcase/          # Public documentation & download showcase repo
│   └── README.md                   # Channel showcase & user guides
│
└── jtbs-live/                      # Main production codebase & application workspace
    ├── firebase.json               # Firebase Hosting, Firestore, and Functions config
    ├── .firebaserc                 # Active Firebase project mapping
    ├── firestore.rules             # Cloud Firestore security rules & RBAC
    ├── firestore.indexes.json      # Firestore index definitions
    ├── SETUP.md                    # Firebase provisioning & superadmin guide
    ├── run-once-superadmin.js      # Script to grant superadmin claims
    │
    ├── functions/                  # Firebase Cloud Functions (Node.js 18)
    │   ├── package.json
    │   └── index.js                # Secure admin user provisioning & deletion
    │
    ├── public/                     # Static Web Application (Deployable to ANY host)
    │   ├── index.html              # Live Web Viewer with IPTV Sync Engine
    │   ├── admin.html              # Superadmin Control Dashboard & Decoder Fleet Monitor
    │   ├── playlist.m3u            # IPTV Channel Playlist (M3U8)
    │   ├── .htaccess               # Apache / cPanel configuration with SPA rewrites
    │   ├── _redirects              # Netlify & Cloudflare Pages routing rules
    │   ├── manifest.json           # PWA Web App Manifest
    │   └── sw.js                   # Service Worker
    │
    ├── cloudflare-worker/          # Cloudflare Worker Edge deployment
    │   ├── index.js                # Edge router & playlist responder
    │   └── wrangler.jsonc          # Wrangler configuration
    │
    └── jtbs-apk/                   # Native Android Applications & Hardware Decoders
        ├── jtbs/                   # Jetpack Compose / WebView client app
        ├── jtbs-launcher/          # Primary ExoPlayer-based Android TV Decoder app
        └── jtbs-launcher-vlc/      # LibVLC fallback decoder for legacy chipsets
```

---

## ⚙️ Core Technical Specifications

### Synchronized IPTV Playback Engine

When playing static files (MP4 video loops, recorded programs, or scheduled segments), standard video players start from second 0 for every user. JTBS implements an **Epoch-based Synchronized Clock Algorithm** across all web clients and hardware decoders:

$$\text{Elapsed Time} = (\text{Current Timestamp} - \text{Start Timestamp}) \pmod{\text{Media Duration}}$$

```javascript
// Viewer synchronization logic (public/index.html & Android ExoPlayer)
if (playMode === 'loop' && duration > 0) {
  const now = Date.now() / 1000;
  const elapsed = (now - startTime) % duration;
  videoElement.currentTime = elapsed;
}
```

This guarantees that:
- Every viewer across the globe watches the exact same video frame simultaneously.
- When an Android TV decoder box reboots, it seeks directly to the current live second rather than starting from the beginning.

---

### Database Schema (Firestore)

The platform is driven by real-time listeners on Cloud Firestore:

| Collection / Path | Purpose | Key Fields |
| :--- | :--- | :--- |
| **`streamState/main`** | Master live stream control | `isLive` (*bool*), `streamUrl` (*string*), `playMode` (*"loop" \| "once"*), `startTime` (*number*), `duration` (*number*), `offlineMessage` (*string*), `offlineImageUrl` (*string*) |
| **`config/main`** | Channel branding & metadata | `channelName` (*string*), `logoUrl` (*string*) |
| **`announcement/current`**| Marquee ticker | `text` (*string*), `active` (*bool*) |
| **`decoderStatus/{id}`** | Box telemetry | `activeUrl` (*string*), `bufferHealth` (*string*), `frameDrops` (*number*), `ip` (*string*), `lastHeartbeat` (*timestamp*) |
| **`decoderControl/{id}`**| Remote commands | `command` (*"reboot" \| "refresh" \| "changeUrl"*), `targetUrl` (*string*), `timestamp` (*timestamp*) |
| **`chat/{id}`** | Viewer live chat | `name` (*string*), `message` (*string*), `timestamp` (*timestamp*) |
| **`blessings/{id}`** | Prayer requests | `name` (*string*), `message` (*string*), `timestamp` (*timestamp*) |

---

### Decoder Fleet Telemetry & Remote Control

Standalone Android TV boxes running `jtbs-launcher`:
1. Listen to `decoderControl/{boxId}` for administrative commands issued from the Admin Panel.
2. Emit heartbeats every 15–30 seconds to `decoderStatus/{boxId}` reporting network throughput, playback buffer level, and video render state.
3. Automatically restart ExoPlayer if the video buffer freezes or network disconnects.

---

## 🛠️ Daily Operations: Admin Control Panel

Access the dashboard at `/admin` (e.g., `https://yourdomain.com/admin`):

1. **Turn Stream ON**:
   - Flip the live switch to **ON**.
   - Input the stream source URL (HLS `.m3u8`, YouTube Live, Facebook Live, or MP4).
   - If using an MP4 loop, click **Auto-Detect Duration** to calculate duration for clock synchronization.
   - Click **▶ Go Live**. All connected viewers and decoders update within ~1 second.
2. **Turn Stream OFF**:
   - Flip the toggle to **OFF**.
   - Optionally configure custom off-air screen text and image URL.
   - Click **Save Offline Settings**.
3. **Send Remote Commands to Decoders**:
   - Open the **Decoders** tab to view all online set-top boxes.
   - Click **Restart Stream** or **Reboot Application** to trigger immediate remote recovery.
4. **Moderate Chat & Blessings**:
   - Review incoming viewer messages and click the trash icon to delete inappropriate entries instantly.

---

## 📺 Hardware Decoder Setup (Android TV Box)

To deploy a dedicated television set-top decoder box:

1. **Get the APK**:
   - Download the latest decoder APK from [GitHub Releases](https://github.com/joelgomes001/jtbs-classic/releases/latest).
2. **Installation**:
   - Copy the APK to a USB drive and insert into the Android TV Box.
   - Use any Android file manager to install `JTBS-Decoder.apk`.
3. **Auto-Boot Operation**:
   - On power-on, `BootReceiver.kt` immediately launches the playback activity.
   - The app runs in immersive full-screen mode, suppresses Android system bars, and starts streaming automatically.

---

## 📡 Rebroadcasting, Contact & Legal Notice

- **Channel Name**: JTBS Classic (Siti Networks LCN 279 Krishnanagar)
- **Broadcasting Network**: Jishu Television’s Broadcasting Services (JTBS)
- **Proprietor**: Joel Sohan Gomes
- **Phone**: +91 83738 28015
- **Email**: [helpdesk.jtbs@gmail.com](mailto:helpdesk.jtbs@gmail.com)
- **Broadcast Studio**: Krishnanagar, Nadia, West Bengal, India

### Network Distribution & LCO Inquiries
If any **LCO (Local Cable Operator)**, **MSO (Multi System Operator)**, or **IPTV Network Provider** wishes to rebroadcast JTBS Classic on their network, please contact us. We supply preconfigured Android TV hardware decoder units for seamless headend integration.

---

### Copyright & Fair Use
All original video programming and live event coverage broadcast by JTBS Classic is the copyrighted property of **Jishu Television’s Broadcasting Services** (Joel Sohan Gomes). Unauthorized copying, rebroadcasting, or commercial redistribution without prior written authorization is strictly prohibited.
