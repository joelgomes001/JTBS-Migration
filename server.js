/**
 * JTBS Platform - Lightweight Self-Hosted & Local Preview Server
 * Zero dependencies: runs natively on Node.js 16+
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const https = require('https');

const PORT = parseInt(process.env.PORT || '5000', 10);
const PUBLIC_DIR = path.join(__dirname, 'jtbs-live', 'public');

// Attempt to read default project ID from .firebaserc
let PROJECT_ID = 'jtbs-classic';
try {
  const rcPath = path.join(__dirname, 'jtbs-live', '.firebaserc');
  if (fs.existsSync(rcPath)) {
    const rc = JSON.parse(fs.readFileSync(rcPath, 'utf8'));
    if (rc.projects && rc.projects.default) {
      PROJECT_ID = rc.projects.default;
    }
  }
} catch (e) {
  // Use fallback default
}

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js':   'application/javascript; charset=utf-8',
  '.css':  'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png':  'image/png',
  '.jpg':  'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif':  'image/gif',
  '.svg':  'image/svg+xml',
  '.ico':  'image/x-icon',
  '.m3u':  'application/x-mpegurl',
  '.m3u8': 'application/vnd.apple.mpegurl',
  '.mp4':  'video/mp4',
  '.mpd':  'application/dash+xml',
  '.xml':  'application/xml'
};

function fetchLiveStreamUrl() {
  return new Promise((resolve) => {
    const firestoreUrl = `https://firestore.googleapis.com/v1/projects/${PROJECT_ID}/databases/(default)/documents/streamState/main`;
    https.get(firestoreUrl, { timeout: 3000 }, (res) => {
      let data = '';
      res.on('data', chunk => { data += chunk; });
      res.on('end', () => {
        try {
          const doc = JSON.parse(data);
          if (doc.fields && doc.fields.streamUrl && doc.fields.streamUrl.stringValue) {
            resolve(doc.fields.streamUrl.stringValue);
          } else {
            resolve(null);
          }
        } catch {
          resolve(null);
        }
      });
    }).on('error', () => resolve(null));
  });
}

const server = http.createServer(async (req, res) => {
  // CORS & Security Headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', '*');
  res.setHeader('X-Content-Type-Options', 'nosniff');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const parsedUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  let pathname = decodeURIComponent(parsedUrl.pathname);

  // 1. Live IPTV Stream Redirect Handler (/live.m3u8)
  if (pathname === '/live.m3u8') {
    try {
      const activeUrl = await fetchLiveStreamUrl();
      if (activeUrl) {
        res.writeHead(302, {
          'Location': activeUrl,
          'Cache-Control': 'no-cache, no-store, must-revalidate'
        });
        res.end();
        return;
      }
    } catch (e) {
      // Fallback to static playlist if live stream fetch fails
    }
  }

  // 2. Route Rewriting for Admin Dashboard
  if (pathname === '/admin' || pathname.startsWith('/admin/')) {
    pathname = '/admin.html';
  } else if (pathname === '/' || pathname === '') {
    pathname = '/index.html';
  }

  // 3. Static File Serving
  let filePath = path.join(PUBLIC_DIR, pathname);

  // Prevent path traversal
  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.writeHead(403, { 'Content-Type': 'text/plain' });
    res.end('403 Forbidden');
    return;
  }

  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      // If file not found, try index.html fallback for SPA
      filePath = path.join(PUBLIC_DIR, 'index.html');
    }

    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';

    // HTML files should never be aggressively cached
    if (ext === '.html') {
      res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');
    } else {
      res.setHeader('Cache-Control', 'public, max-age=86400');
    }

    res.writeHead(200, { 'Content-Type': contentType });
    const stream = fs.createReadStream(filePath);
    stream.pipe(res);
    stream.on('error', () => {
      if (!res.headersSent) {
        res.writeHead(500, { 'Content-Type': 'text/plain' });
        res.end('500 Internal Server Error');
      }
    });
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`\n======================================================`);
  console.log(`  🚀 JTBS Live Streaming Server Running`);
  console.log(`======================================================`);
  console.log(`  🌐 Web Viewer:     http://localhost:${PORT}`);
  console.log(`  🛠️  Admin Portal:   http://localhost:${PORT}/admin`);
  console.log(`  📺 IPTV Stream:    http://localhost:${PORT}/live.m3u8`);
  console.log(`  📁 Serving from:   ${PUBLIC_DIR}`);
  console.log(`======================================================\n`);
});
