# JTBS Classic — Setup Guide
## $0 cost · Firebase Hosted · No Storage

---

## PREREQUISITES

Install these on your machine before starting:

1. **Node.js** (v18 or later) → https://nodejs.org
2. **Firebase CLI**
   ```
   npm install -g firebase-tools
   ```
3. A **Google account** to create the Firebase project.

---

## STEP 1 — Create Firebase Project

1. Go to https://console.firebase.google.com
2. Click **Add project** → name it (e.g. `jtbs-classic`) → Continue
3. Disable Google Analytics (not needed) → **Create project**

---

## STEP 2 — Enable Firebase Services

Inside your new project, enable these one by one:

### Authentication
- Left menu → **Build → Authentication** → Get started
- **Sign-in method** tab:
  - Enable **Email/Password**
  - Enable **Google** (set support email to yours)

### Firestore Database
- Left menu → **Build → Firestore Database** → Create database
- Choose **Production mode** → select your nearest region → Enable

### Functions
- Left menu → **Build → Functions** → Get started
- Upgrade billing to **Blaze (pay as you go)** — Functions require this.
  Functions free tier = 2 million calls/month → your cost stays $0.

### Hosting
- Left menu → **Build → Hosting** → Get started → follow prompts → skip to finish

---

## STEP 3 — Get Your Firebase Config

1. Firebase Console → **Project Settings** (gear icon, top-left)
2. Scroll to **Your apps** → click **</>** (Web app) → register it
3. Copy the `firebaseConfig` object. It looks like:
   ```js
   {
     apiKey: "AIza...",
     authDomain: "jtbs-classic.firebaseapp.com",
     projectId: "jtbs-classic",
     storageBucket: "jtbs-classic.appspot.com",
     messagingSenderId: "123456789",
     appId: "1:123456789:web:abc123"
   }
   ```

---

## STEP 4 — Fill In Your Config

Open these 3 files and paste your config into the `FIREBASE_CONFIG` object:

- `public/index.html`  → search for `YOUR_API_KEY`
- `public/admin.html`  → search for `YOUR_API_KEY`

Also edit `.firebaserc`:
```json
{
  "projects": {
    "default": "jtbs-classic"   <-- replace with your actual Project ID
  }
}
```

---

## STEP 5 — Create First Superadmin (run once)

This script creates your first admin account.

### 5a — Get service account key
1. Firebase Console → **Project Settings** → **Service accounts** tab
2. Click **Generate new private key** → download JSON file
3. Rename it to `serviceAccountKey.json`
4. Move it into the `jtbs-live/` folder (same level as `seed-admin.js`)

### 5b — Edit seed-admin.js
Open `seed-admin.js` and change:
```js
const EMAIL    = 'YOUR_ADMIN_EMAIL@gmail.com';  // your email
const PASSWORD = 'YourStrongPassword123!';       // strong password
const NAME     = 'Super Admin';                  // your name
```

### 5c — Run the script
```bash
cd jtbs-live
npm install firebase-admin
node seed-admin.js
```

You should see:
```
✅ Superadmin created: your@email.com
✅ UID: abc123...
✅ Default config + streamState seeded.
```

### 5d — Delete the key when done (IMPORTANT)
```bash
rm serviceAccountKey.json
```
Never commit `serviceAccountKey.json` to git.

---

## STEP 6 — Install Functions Dependencies

```bash
cd jtbs-live/functions
npm install
cd ..
```

---

## STEP 7 — Login to Firebase CLI

```bash
firebase login
```
Opens browser — sign in with your Google account.

---

## STEP 8 — Deploy Everything

From the `jtbs-live/` folder:

```bash
# Deploy Firestore rules
firebase deploy --only firestore:rules

# Deploy Cloud Functions
firebase deploy --only functions

# Deploy Hosting (your website)
firebase deploy --only hosting
```

Or deploy all at once:
```bash
firebase deploy
```

---

## STEP 9 — Visit Your Site

After deploy completes, Firebase gives you a URL like:
```
https://jtbs-classic.web.app
```

- **Viewer:**  `https://jtbs-classic.web.app`
- **Admin:**   `https://jtbs-classic.web.app/admin`

---

## STEP 10 — Connect Custom Domain (optional)

1. Firebase Console → Hosting → **Add custom domain**
2. Enter your domain (e.g. `jtbsclassic.com`)
3. Firebase gives you DNS records → add them to your domain registrar
4. SSL certificate provisions automatically (~15 min)

---

## DAILY USE — Admin Panel

### Turn stream ON
1. Go to `/admin` → login
2. **Stream** tab → flip toggle to ON
3. Paste your stream URL:
   - HLS: `https://video4.mayapur.tv/.../playlist.m3u8`
   - YouTube: `https://www.youtube.com/watch?v=LIVE_VIDEO_ID`
   - Facebook: `https://www.facebook.com/YourPage/videos/123456`
4. Click **▶ Go Live**
5. Viewer page auto-updates within ~1 second

### Turn stream OFF
1. Flip toggle to OFF
2. Optionally set offline image URL + message
3. Click **Save offline settings**

### Upload logo / offline image
Free image hosts (no cost, no signup required for basic):
- https://imgbb.com — upload → copy **Direct link**
- https://imgur.com — upload → right-click image → copy image address
- GitHub: upload to any public repo → click file → right-click → copy image URL

Paste the URL into Branding tab → Save branding.

### Add new admin
1. Users tab → **+ Add admin**
2. Fill name, email, password, role
3. Click **Create**
4. New admin can log in immediately at `/admin`

---

## RTMP / SRT STREAMS

Browsers cannot play RTMP or SRT directly.
You must convert to HLS first:

### Option A — OBS Studio (free)
1. OBS → Settings → Stream → Service: Custom
2. OBS → Settings → Output → Recording tab → Type: Custom FFmpeg
   OR use OBS's built-in HLS plugin
3. Output will give you a local HLS URL — use a relay service to make it public

### Option B — Restream.io (free tier)
1. Sign up at restream.io
2. Add your RTMP source
3. Stream to YouTube Live or Facebook Live simultaneously
4. Copy the YouTube/Facebook live URL → paste in admin panel

### Option C — Mux.com (free trial)
1. Sign up at mux.com
2. Create a live stream → get HLS playback URL
3. Paste HLS URL in admin panel

---

## FILE STRUCTURE

```
jtbs-live/
├── firebase.json           hosting + firestore config
├── .firebaserc             your project ID
├── firestore.rules         database security rules
├── firestore.indexes.json  (empty — no indexes needed)
├── seed-admin.js           run once to create first admin
├── functions/
│   ├── package.json
│   └── index.js            setAdminClaim + deleteAdminUser
└── public/
    ├── index.html          viewer page (your live stream site)
    └── admin.html          admin dashboard
```

---

## COST BREAKDOWN

| Service        | Free tier              | Your usage       |
|----------------|------------------------|------------------|
| Hosting        | 10 GB/month bandwidth  | ~$0              |
| Firestore      | 50K reads/day          | ~$0              |
| Auth           | Unlimited              | $0               |
| Functions      | 2M calls/month         | ~$0              |
| **Storage**    | ~~removed~~            | $0               |
| **Total**      |                        | **$0/month**     |

---

## TROUBLESHOOTING

| Problem | Fix |
|---------|-----|
| "Your account does not have admin access" | Run `seed-admin.js` again, check email/password match |
| Stream not loading | Check URL is HLS (.m3u8) or YouTube/Facebook link |
| Functions deploy fails | Ensure billing is set to Blaze in Firebase console |
| Google sign-in popup blocked | Allow popups for your site in browser settings |
| Logo not showing | Make sure URL is a direct image link ending in `.png` or `.jpg` |
| CORS error on HLS | Use the stream provider's official HLS URL, not a raw server URL |
