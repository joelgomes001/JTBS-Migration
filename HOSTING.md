# 🌐 JTBS Hosting & Deployment Guide

This guide explains how to host the **JTBS Classic / JTBS Live** digital television streaming platform.

The repository includes an interactive setup wizard and preconfigured deployment manifests for **Firebase, Docker, Cloudflare Workers, Node.js VPS, and Static Web Hosts**.

---

## ⚡ Quick Start: Interactive Setup Wizard

The fastest way to configure and host JTBS is using the built-in setup wizard:

```bash
npm run setup
```
*On Windows, you can simply double-click `setup.bat`. On Linux/macOS, run `./setup.sh`.*

The wizard will guide you through:
1. Setting up your Firebase project & credentials
2. Running a local preview server
3. Launching in Docker
4. Deploying to Cloudflare Workers
5. Provisioning your first Superadmin account

---

## 🚀 Hosting Option 1: Firebase Serverless (Official / Recommended)

This is the native architecture designed for $0/month cost with edge CDN delivery, real-time Firestore synchronization, and secure Cloud Functions RBAC.

### Prerequisites
- Node.js 18+
- Firebase CLI: `npm install -g firebase-tools`
- Google Account

### Step-by-Step
1. **Create Firebase Project**:
   - Go to [Firebase Console](https://console.firebase.google.com).
   - Click **Add Project** (e.g., `my-jtbs-live`).
   - Enable **Firestore Database** in **Production mode**.
   - Enable **Authentication** with **Email/Password** sign-in provider.
   - Upgrade your project to the **Blaze plan** (pay-as-you-go). Cloud Functions require Blaze, but the free tier covers 2,000,000 calls/month, keeping your cost at $0.

2. **Configure Project Credentials**:
   - Run `npm run setup` and choose **[5] Configure Firebase API Keys & ID**, or manually update:
     - `jtbs-live/.firebaserc`: replace `"default": "jtbs-classic"` with your project ID.
     - `jtbs-live/public/index.html` & `jtbs-live/public/admin.html`: replace `firebaseConfig` credentials.

3. **Install Functions Dependencies**:
   ```bash
   cd jtbs-live/functions
   npm install
   cd ../..
   ```

4. **Deploy**:
   ```bash
   # Log in to Firebase
   firebase login

   # Deploy everything
   npm run deploy
   ```
   *Or deploy selectively:*
   - `npm run deploy:hosting` — Web viewer & admin dashboard
   - `npm run deploy:firestore` — Security rules & database indexes
   - `npm run deploy:functions` — RBAC user management functions

5. **Access Your Live Site**:
   - Web Player: `https://<your-project-id>.web.app`
   - Admin Panel: `https://<your-project-id>.web.app/admin`

---

## 💻 Hosting Option 2: Local & VPS Server (Zero Dependencies)

JTBS comes with a zero-dependency HTTP server built directly on Node.js (`server.js`). It serves the web viewer, rewrites `/admin` routes, sets security headers, and relays the live IPTV stream endpoint (`/live.m3u8`).

### Run on Local Machine or VPS
```bash
npm start
# or specify a custom port:
PORT=8080 node server.js
```

### Run as a Background Service with PM2 (VPS / Ubuntu / Debian)
```bash
npm install -g pm2
pm2 start server.js --name "jtbs-live"
pm2 startup
pm2 save
```

---

## 🐳 Hosting Option 3: Docker Container

JTBS includes a preconfigured `Dockerfile` and `docker-compose.yml` for 1-command containerized deployment on any Docker host (VPS, Synology, Unraid, TrueNAS, Raspberry Pi).

### Quick Start
```bash
# Build and start container in background
docker compose up -d

# Check status
docker compose ps

# View logs
docker compose logs -f

# Stop container
docker compose down
```

The container listens on port **8080**:
- Web Viewer: `http://<server-ip>:8080`
- Admin Dashboard: `http://<server-ip>:8080/admin`
- IPTV Stream: `http://<server-ip>:8080/live.m3u8`

---

## ⚡ Hosting Option 4: Cloudflare Worker (Global Edge)

If you want global edge deployment with near-zero latency and no server maintenance:

1. Navigate to the worker directory:
   ```bash
   cd jtbs-live/cloudflare-worker
   ```
2. Check or edit `wrangler.jsonc` (set your desired worker name and custom domain).
3. Deploy to Cloudflare:
   ```bash
   npx wrangler deploy
   ```
   *Or from project root:*
   ```bash
   npm run deploy:worker
   ```

---

## 🌐 Hosting Option 5: Static Web Hosts (Vercel, Netlify, Cloudflare Pages)

The `jtbs-live/public` folder is a complete static Single-Page Application (SPA).

### Deploying to Vercel
1. Install Vercel CLI: `npm install -g vercel`
2. Run: `vercel`
   - A preconfigured `vercel.json` is included at root to route `/admin` to `admin.html`.

### Deploying to Netlify
1. Connect this GitHub repository in Netlify.
2. Set **Publish directory** to: `jtbs-live/public`
   - The included `_redirects` file automatically handles SPA routing for `/admin`.

---

## 👑 Superadmin Account Setup

To log in to the Admin Dashboard (`/admin`) and manage live streams:

1. Open [Firebase Console](https://console.firebase.google.com) → **Project Settings** → **Service accounts**.
2. Click **Generate new private key** and save the downloaded file as:
   `jtbs-live/serviceAccountKey.json`
3. Run the setup wizard (`npm run setup` → Option 6), or run:
   ```bash
   cd jtbs-live
   node run-once-superadmin.js
   ```
4. Once completed, **delete `jtbs-live/serviceAccountKey.json`** to ensure your credentials are never exposed.

---

## ❓ Frequently Asked Questions

**Q: Do I have to pay for Firebase?**  
A: No. While the Blaze pay-as-you-go plan is required to enable Cloud Functions, the standard free monthly quotas (2M Cloud Function calls, 50K Firestore reads/day, 10GB Hosting bandwidth) exceed typical regional broadcaster usage.

**Q: Can I use my own custom domain?**  
A: Yes! In Firebase Console, go to **Hosting** → **Add custom domain**. Enter your domain and add the provided TXT/A DNS records at your registrar. Free SSL certificates are provisioned automatically.
