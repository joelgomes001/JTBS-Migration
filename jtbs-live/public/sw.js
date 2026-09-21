// JTBS Classic Service Worker
// Caches shell for offline support — stream itself is always live/network

const CACHE = 'jtbs-v1';
const SHELL = ['/', '/index.html', '/admin.html', '/manifest.json'];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE).then(c => c.addAll(SHELL))
  );
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', e => {
  // Never cache stream URLs or Firebase API calls
  const url = e.request.url;
  if (url.includes('firestore.googleapis.com') ||
      url.includes('identitytoolkit') ||
      url.includes('.m3u8') ||
      url.includes('.ts') ||
      url.includes('youtube.com') ||
      url.includes('facebook.com')) {
    return; // pass through to network
  }

  e.respondWith(
    caches.match(e.request).then(cached => cached || fetch(e.request))
  );
});
