<div align="center">
  <img src="https://jtbsclassic.dpdns.org/logo.png" alt="JTBS Classic Logo" width="160" height="160" />
  <h1>JTBS Classic / JTBS Live</h1>
  <p><strong>Official Digital Live Television Broadcasting Platform & Android Hardware Decoder Fleet</strong></p>
  <p>Synchronized IPTV playback, multi-source video distribution, and remote decoder telemetry running on a zero-cost serverless architecture.</p>

  <p>
    <a href="https://jtbsclassic.dpdns.org"><strong>🌐 Watch Live Web Player</strong></a> • 
    <a href="https://github.com/joelgomes001/jtbs-classic/releases/latest"><strong>📱 Download Android App</strong></a> • 
    <a href="HOSTING.md"><strong>🚀 Hosting Guide</strong></a> • 
    <a href="project-context.md"><strong>📖 System Specs</strong></a>
  </p>
</div>

---

## 📺 Overview

**JTBS Classic** is a full-featured digital television distribution platform developed for **Jishu Television's Broadcasting Services (JTBS)**. It provides synchronized continuous live streaming to web browsers, mobile devices, and dedicated Android TV hardware decoders.

### Key Highlights
- **Zero-Cost Serverless Core**: Runs on free-tier Cloud Firestore, Firebase Hosting CDN, and Cloud Functions.
- **Synchronized IPTV Playback Engine**: Synchronizes video playback across all connected web viewers and hardware decoders using stream start timestamps, duration calculations, and modulo offset seeking.
- **Multi-Source Video Engine**: Dynamic format router supporting HLS (`.m3u8`), MPEG-DASH (`.mpd`), MP4 video loops, YouTube Live embeds, and Facebook Live streams.
- **Decoder Fleet Telemetry & Remote Control**: Real-time heartbeat monitoring (`decoderStatus`) and remote administrative command execution (`decoderControl`) for standalone set-top TV boxes.
- **Native Android TV Decoder**: Kotlin & Media3 / ExoPlayer hardware decoder with boot-time autostart and self-healing network reconnection.

---

## ⚡ Quick Start: Host in 3 Minutes

When you clone this project, you can choose from multiple ready-to-run hosting options:

```bash
# Clone the repository
git clone https://github.com/joelgomes001/JTBS-Migration.git
cd JTBS-Migration

# Run the interactive setup wizard
npm run setup
```
*(On Windows, you can also double-click `setup.bat`. On Linux/macOS, run `./setup.sh`.)*

The interactive wizard will guide you through:
- **[1] 🚀 Firebase Serverless Setup** (Edge CDN + Firestore sync + Cloud Functions RBAC)
- **[2] 💻 Local / VPS Preview Server** (Instant zero-dependency HTTP server on port 5000)
- **[3] 🐳 Docker Container Hosting** (`docker compose up -d` on port 8080)
- **[4] ⚡ Cloudflare Worker Deployment** (Global edge worker deployment)
- **[5] 🔑 Configure Firebase Credentials & API Keys**
- **[6] 👑 Superadmin Account Setup**

For detailed deployment guides for each platform, see [**HOSTING.md**](HOSTING.md).

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
    ├── public/                     # Static Web Application (Firebase Hosting root)
    │   ├── index.html              # Live Web Viewer with IPTV Sync Engine
    │   ├── admin.html              # Superadmin Control Dashboard & Decoder Fleet Monitor
    │   ├── playlist.m3u            # IPTV Channel Playlist
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

## 🛠️ Technology Stack

| Layer | Technologies | Purpose |
| :--- | :--- | :--- |
| **Web Frontend** | Vanilla HTML5, CSS3, JavaScript (ES6+) | High-performance, lightweight web viewer & admin dashboard |
| **Media Engines** | HLS.js, Dash.js, ExoPlayer (Media3), LibVLC | Low-latency stream playback across web and Android devices |
| **Cloud Hosting** | Firebase Hosting, Cloudflare Workers, Docker | Global CDN and edge distribution |
| **Realtime Database** | Cloud Firestore | Real-time state synchronization (`streamState`, `chat`, `decoderControl`) |
| **Authentication & RBAC** | Firebase Auth & Cloud Functions | Role-based authorization (`admin`, `superadmin`) |
| **Native Android** | Kotlin, Android SDK, Media3 | Auto-booting hardware decoder & TV set-top launcher |

---

## 📡 Channel Information & Rebroadcasting

- **Channel Name**: JTBS Classic (Siti Networks LCN 279 Krishnanagar)
- **Company**: Jishu Television’s Broadcasting Services (JTBS)
- **Proprietor**: Joel Sohan Gomes
- **Phone**: +91 83738 28015
- **Email**: [helpdesk.jtbs@gmail.com](mailto:helpdesk.jtbs@gmail.com)
- **Location**: Krishnanagar, Nadia, West Bengal, India

If any **LCO (Local Cable Operator)**, **MSO (Multi System Operator)**, or **IPTV Network** wishes to rebroadcast our channel in their network, please reach out. Dedicated hardware decoder boxes can be provisioned.

---

## ⚖️ Copyright & Disclaimer

© JTBS Classic Channel — Jishu Television’s Broadcasting Services. All rights reserved.  
Unauthorized redistribution or rebroadcasting of original video programming without written consent is strictly prohibited.
