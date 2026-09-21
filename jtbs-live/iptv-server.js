const http = require('http');
const https = require('https');

// ── CONFIG ───────────────────────────────────────────────────
const PORT = 8787;
const PROJECT_ID = 'jtbs-classic';
const FIRESTORE_URL = `https://firestore.googleapis.com/v1/projects/${PROJECT_ID}/databases/(default)/documents/streamState/main`;

// ── FETCH STREAM STATE FROM FIRESTORE REST API ───────────────
function fetchStreamState() {
  return new Promise((resolve, reject) => {
    https.get(FIRESTORE_URL, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          const doc = JSON.parse(data);
          if (!doc.fields) return resolve(null);
          const fields = doc.fields;
          resolve({
            isLive: fields.isLive?.booleanValue === true,
            streamUrl: fields.streamUrl?.stringValue || ''
          });
        } catch (e) {
          reject(e);
        }
      });
    }).on('error', reject);
  });
}

// ── HTTP SERVER ──────────────────────────────────────────────
const server = http.createServer(async (req, res) => {
  // CORS headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS');
  res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    return res.end();
  }

  // Only respond to /live, /live.m3u8, and /
  const path = req.url.split('?')[0];
  if (path !== '/' && path !== '/live' && path !== '/live.m3u8') {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    return res.end('Not found');
  }

  try {
    const state = await fetchStreamState();

    if (!state || !state.isLive || !state.streamUrl) {
      res.writeHead(503, { 'Content-Type': 'text/plain' });
      return res.end('JTBS Classic — Channel is currently off air.');
    }

    // 302 redirect to the actual stream URL
    res.writeHead(302, { 'Location': state.streamUrl });
    return res.end();

  } catch (err) {
    console.error('Error fetching stream state:', err.message);
    res.writeHead(500, { 'Content-Type': 'text/plain' });
    return res.end('Internal server error.');
  }
});

server.listen(PORT, () => {
  console.log('');
  console.log('  ╔══════════════════════════════════════════════╗');
  console.log('  ║   📡 JTBS Classic — IPTV Redirect Server    ║');
  console.log('  ╠══════════════════════════════════════════════╣');
  console.log(`  ║   Local:  http://localhost:${PORT}/live.m3u8    ║`);
  console.log('  ║   Public: https://xx.dpdns.org/live.m3u8    ║');
  console.log('  ╚══════════════════════════════════════════════╝');
  console.log('');
  console.log('  Waiting for IPTV player connections...');
  console.log('');
});
