import { ADMIN_HTML } from './adminPage.js';
import { INDEX_HTML } from './indexPage.js';
import { PLAYLIST_M3U } from './playlistPage.js';
import { WORKER_JSON, CHANNELS_XML } from './epgPages.js';
import { LOGO_BASE64 } from './logoPage.js';

function base64ToUint8Array(base64) {
  const binaryString = atob(base64);
  const len = binaryString.length;
  const bytes = new Uint8Array(len);
  for (let i = 0; i < len; i++) {
    bytes[i] = binaryString.charCodeAt(i);
  }
  return bytes;
}

function deduplicateCookies(cookieStr) {
  if (!cookieStr) return '';
  const seen = new Set();
  const result = [];
  const parts = cookieStr.split(';');
  for (const part of parts) {
    const trimmed = part.trim();
    if (!trimmed) continue;
    const kv = trimmed.split('=');
    const key = kv[0].trim().toLowerCase();
    if (!seen.has(key)) {
      seen.add(key);
      result.push(trimmed);
    }
  }
  return result.join('; ');
}

function extractSetCookies(headers) {
  const cookies = [];
  const seen = new Set();
  let rawCookies = [];
  if (typeof headers.getSetCookie === 'function') {
    rawCookies = headers.getSetCookie();
  } else {
    const setCookieHeader = headers.get('set-cookie');
    if (setCookieHeader) {
      rawCookies = setCookieHeader.split(/,(?=[^;]+;)/);
    }
  }
  for (const part of rawCookies) {
    const cookieKV = part.split(';')[0].trim();
    if (cookieKV && !cookieKV.toLowerCase().startsWith('path=') && !cookieKV.toLowerCase().startsWith('expires=')) {
      const key = cookieKV.split('=')[0].trim().toLowerCase();
      if (!seen.has(key)) {
        seen.add(key);
        cookies.push(cookieKV);
      }
    }
  }
  return cookies.join('; ');
}

const upstreamSessionStore = new Map();

async function refreshUpstreamSession(docName, streamUrl) {
  if (!streamUrl) return '';
  try {
    let target = streamUrl;
    if (!target.includes('cookieCheck=')) {
      target += (target.includes('?') ? '&' : '?') + 'cookieCheck=1';
    }
    const res = await fetch(target, {
      redirect: 'follow',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'cf-tunnel-skip-offline-page': 'true',
        'Cookie': 'cookieCheck=1'
      }
    });
    if (res.ok) {
      const cookie = extractSetCookies(res.headers);
      if (cookie) {
        upstreamSessionStore.set(docName, { cookie, timestamp: Date.now(), streamUrl });
        return cookie;
      }
    }
  } catch (e) {}
  return '';
}

const memoryStore = new Map();
const docCache = new Map();

function getCacheKeyUrl(firestoreUrl, workerOrigin) {
  try {
    const urlObj = new URL(firestoreUrl);
    const pathParts = urlObj.pathname.split('/documents/');
    const subPath = pathParts[1] || 'default';
    return `${workerOrigin}/api-cache/${subPath}`;
  } catch (e) {
    return `${workerOrigin}/api-cache/default`;
  }
}

async function getCachedFirestoreDoc(url, workerOrigin) {
  const now = Date.now();
  const cached = docCache.get(url);
  
  if (cached && (now - cached.timestamp < 10000)) {
    return cached.data;
  }

  const cacheKeyUrl = getCacheKeyUrl(url, workerOrigin);
  const cacheKey = new Request(cacheKeyUrl);
  const cache = (typeof caches !== 'undefined' && caches.default) ? caches.default : null;

  try {
    const res = await fetch(url, {
      headers: { 'Accept': 'application/json' }
    });
    if (res.status === 200) {
      const data = await res.json();
      docCache.set(url, { timestamp: now, data: data });
      
      if (cache) {
        try {
          const bodyStr = JSON.stringify(data);
          const bodyBytes = new TextEncoder().encode(bodyStr);
          const cacheResponse = new Response(bodyBytes, {
            status: 200,
            headers: {
              'Content-Type': 'application/json',
              'Cache-Control': 'public, max-age=86400',
              'Content-Length': bodyBytes.length.toString()
            }
          });
          await cache.put(cacheKey, cacheResponse);
        } catch (e) {}
      }
      return data;
    }
  } catch (e) {}

  if (cache) {
    try {
      const cachedResponse = await cache.match(cacheKey);
      if (cachedResponse) {
        const data = await cachedResponse.json();
        docCache.set(url, { timestamp: now, data: data });
        return data;
      }
    } catch (e) {}
  }

  if (cached && cached.data) {
    cached.timestamp = now;
    return cached.data;
  }

  if (url.includes('/decoderControl/')) {
    return {
      fields: {
        enabled: { booleanValue: true }
      }
    };
  }

  return null;
}

function parseFirestoreBool(field) {
  if (field === true || field === 'true' || field === 1) return true;
  if (!field) return false;
  if (field.booleanValue !== undefined) return field.booleanValue === true;
  if (field.stringValue !== undefined) return field.stringValue === 'true' || field.stringValue === '1';
  if (field.integerValue !== undefined) return parseInt(field.integerValue, 10) === 1;
  return false;
}

function parseFirestoreString(field) {
  if (typeof field === 'string') return field;
  if (!field) return '';
  if (field.stringValue !== undefined) return field.stringValue;
  if (field.integerValue !== undefined) return String(field.integerValue);
  return '';
}

function rewriteM3u8(m3u8Text, targetUrl, workerOrigin, cookieStr, docName, injectDiscontinuity = false) {
  const targetBaseUrl = new URL(targetUrl);
  const baseUrl = targetBaseUrl.origin + targetBaseUrl.pathname.substring(0, targetBaseUrl.pathname.lastIndexOf('/') + 1);

  const lines = m3u8Text.split(/\r?\n/);
  let insertedDiscontinuity = false;
  const rewrittenLines = [];
  const cleanCookie = deduplicateCookies(cookieStr || '');

  // Calculate actual max segment duration to fix HLS live polling interval in VLC
  let maxSegDuration = 2;
  for (let line of lines) {
    const trimmed = line.trim();
    if (trimmed.startsWith('#EXTINF:')) {
      const match = trimmed.match(/#EXTINF:([0-9.]+)/);
      if (match) {
        const d = parseFloat(match[1]);
        if (!isNaN(d) && d > maxSegDuration) {
          maxSegDuration = Math.ceil(d);
        }
      }
    }
  }

  for (let line of lines) {
    const trimmed = line.trim();
    if (!trimmed) continue;

    if (injectDiscontinuity && !insertedDiscontinuity && (trimmed.startsWith('#EXTINF:') || trimmed.startsWith('#EXT-X-STREAM-INF'))) {
      rewrittenLines.push('#EXT-X-DISCONTINUITY');
      insertedDiscontinuity = true;
    }

    // Replace bloated TARGETDURATION (e.g. 24s) with real segment duration (e.g. 3s)
    // so VLC reloads the playlist every 2-3s instead of stalling when the 14s window ends
    if (trimmed.startsWith('#EXT-X-TARGETDURATION:')) {
      rewrittenLines.push(`#EXT-X-TARGETDURATION:${maxSegDuration}`);
      continue;
    }

    if (trimmed.startsWith('#')) {
      rewrittenLines.push(line);
      continue;
    }

    try {
      const absoluteUrl = new URL(trimmed, baseUrl).href;
      let proxyUrl = `${workerOrigin}/proxy?doc=${encodeURIComponent(docName)}&url=${encodeURIComponent(absoluteUrl)}`;
      if (cleanCookie) {
        proxyUrl += `&cookie=${encodeURIComponent(cleanCookie)}`;
      }
      rewrittenLines.push(proxyUrl);
    } catch (e) {
      rewrittenLines.push(line);
    }
  }

  return rewrittenLines.join('\n');
}

export default {
  async fetch(request) {
    // 0. Handle CORS preflight options immediately
    if (request.method === 'OPTIONS') {
      return new Response(null, {
        status: 204,
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
          'Access-Control-Allow-Headers': '*',
          'Access-Control-Max-Age': '86400'
        }
      });
    }

    const url = new URL(request.url);
    const path = url.pathname.toLowerCase();

    // Helper to generate a 5-segment HLS sliding window for MP4 / TS offline videos
    const makeOfflinePlaylist = (offlineUrl, durationSec, workerOrigin, docName) => {
      const dur = durationSec > 0 ? durationSec : 10;
      const currentSeq = Math.floor(Date.now() / (dur * 1000)) % 10000;
      const startSeq = Math.max(0, currentSeq - 3);

      const cleanOfflineUrl = offlineUrl.split('?')[0];

      let proxiedVideoUrl = cleanOfflineUrl;
      try {
        if (!cleanOfflineUrl.startsWith(workerOrigin)) {
          proxiedVideoUrl = `${workerOrigin}/proxy?doc=${encodeURIComponent(docName)}&url=${encodeURIComponent(cleanOfflineUrl)}`;
        }
      } catch (e) {}

      return `#EXTM3U
#EXT-X-VERSION:3
#EXT-X-INDEPENDENT-SEGMENTS
#EXT-X-TARGETDURATION:${dur}
#EXT-X-MEDIA-SEQUENCE:${startSeq}
#EXT-X-DISCONTINUITY
#EXTINF:${dur}.0,
${proxiedVideoUrl}
#EXTINF:${dur}.0,
${proxiedVideoUrl}
#EXTINF:${dur}.0,
${proxiedVideoUrl}
#EXTINF:${dur}.0,
${proxiedVideoUrl}
#EXTINF:${dur}.0,
${proxiedVideoUrl}`;
    };

    // Helper to handle off-air response seamlessly for HLS streams (.m3u8) and MP4/TS offline videos
    const handleOffAirResponse = async (offlineHlsUrl, durationSec, workerOrigin, docName) => {
      let targetOfflineUrl = offlineHlsUrl || 'https://infinite-hls-stream.vercel.app/stream.m3u8';

      // 1. If offline video is an HLS playlist (.m3u8), proxy its TS segments directly!
      if (targetOfflineUrl.includes('.m3u8') || targetOfflineUrl.includes('mpegurl')) {
        try {
          const offRes = await fetch(targetOfflineUrl, { headers: { 'User-Agent': 'Mozilla/5.0' } });
          if (offRes.ok) {
            const rawM3u8 = await offRes.text();
            const rewritten = rewriteM3u8(rawM3u8, targetOfflineUrl, workerOrigin, '', docName, true);
            return new Response(rewritten, {
              status: 200,
              headers: {
                'Content-Type': 'application/vnd.apple.mpegurl',
                'Cache-Control': 'no-cache, no-store, must-revalidate, max-age=0',
                'Access-Control-Allow-Origin': '*'
              }
            });
          }
        } catch (e) {}
      }

      // 2. Fallback to 5-segment sliding window for MP4 / TS offline videos
      return new Response(makeOfflinePlaylist(targetOfflineUrl, durationSec, workerOrigin, docName), {
        status: 200,
        headers: {
          'Content-Type': 'application/vnd.apple.mpegurl',
          'Cache-Control': 'no-cache, no-store, must-revalidate, max-age=0',
          'Access-Control-Allow-Origin': '*'
        }
      });
    };

    if (path === '/epg/worker.json' || path === '/worker.json') {
      return new Response(WORKER_JSON, {
        status: 200,
        headers: {
          'Content-Type': 'application/json',
          'Cache-Control': 'public, max-age=3600',
          'Access-Control-Allow-Origin': '*'
        }
      });
    }

    if (path === '/epg/channels.xml' || path === '/channels.xml') {
      return new Response(CHANNELS_XML, {
        status: 200,
        headers: {
          'Content-Type': 'application/xml; charset=utf-8',
          'Cache-Control': 'public, max-age=3600',
          'Access-Control-Allow-Origin': '*'
        }
      });
    }

    if (path === '/' || path === '/index.html') {
      return new Response(INDEX_HTML, {
        status: 200,
        headers: {
          'Content-Type': 'text/html; charset=utf-8',
          'Cache-Control': 'no-cache, no-store, must-revalidate',
          'Access-Control-Allow-Origin': '*'
        }
      });
    }

    if (path === '/admin' || path === '/admin.html') {
      return new Response(ADMIN_HTML, {
        status: 200,
        headers: {
          'Content-Type': 'text/html; charset=utf-8',
          'Cache-Control': 'no-cache, no-store, must-revalidate',
          'Access-Control-Allow-Origin': '*'
        }
      });
    }
    if (path === '/api/test-cache') {
      const cache = (typeof caches !== 'undefined' && caches.default) ? caches.default : null;
      if (!cache) return new Response('No Cache API');
      
      const firestoreUrl = 'https://firestore.googleapis.com/v1/projects/jtbs-classic/databases/(default)/documents/streamState/main';
      const localUrl = `${url.origin}/api-cache/streamState/main`;
      
      const resFirestore = await cache.match(new Request(firestoreUrl));
      const resLocal = await cache.match(new Request(localUrl));
      
      let out = '';
      if (resFirestore) {
        out += 'Firestore Match Success: ' + (await resFirestore.text()) + '\n';
      } else {
        out += 'Firestore Match Null for: ' + firestoreUrl + '\n';
      }
      
      if (resLocal) {
        out += 'Local Match Success: ' + (await resLocal.text()) + '\n';
      } else {
        out += 'Local Match Null for: ' + localUrl + '\n';
      }
      
      return new Response(out, { headers: { 'Access-Control-Allow-Origin': '*' } });
    }

    if (path.startsWith('/api/')) {
      const subPath = url.pathname.substring(5);
      const method = request.method;

      if (method === 'GET' || method === 'HEAD') {
        const firestoreUrl = `https://firestore.googleapis.com/v1/projects/jtbs-classic/databases/(default)/documents/${subPath}`;
        const data = await getCachedFirestoreDoc(firestoreUrl, url.origin);
        if (data) {
          return new Response(JSON.stringify(data), {
            status: 200,
            headers: {
              'Content-Type': 'application/json; charset=utf-8',
              'Cache-Control': 'no-cache, no-store, must-revalidate',
              'Access-Control-Allow-Origin': '*'
            }
          });
        }
        
        const cache = (typeof caches !== 'undefined' && caches.default) ? caches.default : null;
        let cacheKeyUrl = '';
        let cacheMatchResult = 'no-cache';
        if (cache) {
          cacheKeyUrl = getCacheKeyUrl(firestoreUrl, url.origin);
          try {
            const matchRes = await cache.match(new Request(cacheKeyUrl));
            cacheMatchResult = matchRes ? 'matched: ' + (await matchRes.text()).substring(0, 100) : 'not-matched';
          } catch (e) {
            cacheMatchResult = 'match-error: ' + e.message;
          }
        }
        
        return new Response(JSON.stringify({ 
          error: "Failed to fetch from Firestore",
          debug: {
            firestoreUrl,
            cacheKeyUrl,
            cacheMatchResult
          }
        }), {
          status: 502,
          headers: {
            'Content-Type': 'application/json',
            'Access-Control-Allow-Origin': '*'
          }
        });
      }

      if (method === 'POST') {
        const firestoreUrl = `https://firestore.googleapis.com/v1/projects/jtbs-classic/databases/(default)/documents/${subPath}${url.search}`;
        try {
          const bodyText = await request.text();
          const fireRes = await fetch(firestoreUrl, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'X-HTTP-Method-Override': request.headers.get('X-HTTP-Method-Override') || 'PATCH'
            },
            body: bodyText
          });
          
          if (fireRes.ok) {
            const resText = await fireRes.text();
            return new Response(resText, {
              status: 200,
              headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
            });
          }
        } catch (e) {}

        return new Response(JSON.stringify({ name: subPath, fields: {} }), {
          status: 200,
          headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
        });
      }
    }

    if (path === '/logo.png' || path === '/logo' || path === '/favicon.ico') {
      try {
        const logoBytes = base64ToUint8Array(LOGO_BASE64);
        return new Response(logoBytes, {
          status: 200,
          headers: {
            'Content-Type': 'image/png',
            'Cache-Control': 'public, max-age=86400',
            'Access-Control-Allow-Origin': '*'
          }
        });
      } catch (e) {}
    }

    if (path.startsWith('/updatestreamstate')) {
      const doc = url.searchParams.get('doc') || 'main';
      const streamUrl = url.searchParams.get('url') || '';
      const isLive = url.searchParams.get('isLive') !== 'false';

      memoryStore.set(doc, { isLive, streamUrl, updatedAt: Date.now() });

      const mockDoc = {
        name: `projects/jtbs-classic/databases/(default)/documents/streamState/${doc}`,
        fields: {
          isLive: { booleanValue: isLive },
          streamUrl: { stringValue: streamUrl },
          decoderIsLive: { booleanValue: isLive },
          decoderStreamUrl: { stringValue: streamUrl },
          mode: { stringValue: doc === 'decoder' ? 'different' : 'same' }
        }
      };

      const firestoreDocUrl = `https://firestore.googleapis.com/v1/projects/jtbs-classic/databases/(default)/documents/streamState/${doc}`;
      const cacheKeyUrl = getCacheKeyUrl(firestoreDocUrl, url.origin);
      const cacheKey = new Request(cacheKeyUrl);
      const cache = (typeof caches !== 'undefined' && caches.default) ? caches.default : null;
      let cacheDebug = 'no-cache';

      if (cache) {
        try {
          const bodyStr = JSON.stringify(mockDoc);
          const bodyBytes = new TextEncoder().encode(bodyStr);
          const cacheResponse = new Response(bodyBytes, {
            status: 200,
            headers: {
              'Content-Type': 'application/json',
              'Cache-Control': 'public, max-age=86400',
              'Content-Length': bodyBytes.length.toString()
            }
          });
          await cache.put(cacheKey, cacheResponse);
          cacheDebug = 'put-success';
        } catch (e) {
          cacheDebug = 'put-error: ' + e.message;
        }
      } else {
        cacheDebug = 'caches-default-undefined';
      }

      docCache.set(firestoreDocUrl, { timestamp: Date.now(), data: mockDoc });

      return new Response(JSON.stringify({ success: true, doc, isLive, streamUrl, cacheDebug, cacheKeyUrl }), {
        status: 200,
        headers: {
          'Content-Type': 'application/json',
          'Access-Control-Allow-Origin': '*'
        }
      });
    }

    if (path === '/playlist.m3u') {
      return new Response(PLAYLIST_M3U, {
        status: 200,
        headers: {
          'Content-Type': 'application/x-mpegurl; charset=utf-8',
          'Cache-Control': 'no-cache, no-store, must-revalidate',
          'Access-Control-Allow-Origin': '*'
        }
      });
    }

    let targetFeed = null;
    let isEpgReq = false;

    if (path === '/epg.xml' || path === '/epg') {
      isEpgReq = true;
      targetFeed = url.searchParams.get('channel') || null;
    } else if (path.endsWith('.epg.xml')) {
      const feedStr = path.replace('.epg.xml', '').replace('/', '').toLowerCase();
      const validFeeds = ['main', 'decoder', 'srinjana', ...Array.from({ length: 20 }, (_, i) => `feed${i + 1}`)];
      if (validFeeds.includes(feedStr)) {
        isEpgReq = true;
        targetFeed = feedStr;
      }
    } else if (path.startsWith('/epg/')) {
      const feedStr = path.replace('/epg/', '').replace('.xml', '').toLowerCase();
      const validFeeds = ['main', 'decoder', 'srinjana', ...Array.from({ length: 20 }, (_, i) => `feed${i + 1}`)];
      if (validFeeds.includes(feedStr)) {
        isEpgReq = true;
        targetFeed = feedStr;
      }
    }

    if (isEpgReq) {
      const mainConfigUrl = `https://firestore.googleapis.com/v1/projects/jtbs-classic/databases/(default)/documents/config/main`;
      let epgSourceUrl = '';
      
      try {
        const mainData = await getCachedFirestoreDoc(mainConfigUrl, url.origin);
        if (mainData && mainData.fields) {
          const epgUrlsFields = mainData.fields.epgSourceUrls?.mapValue?.fields;
          if (targetFeed && epgUrlsFields && epgUrlsFields[targetFeed]?.stringValue) {
            epgSourceUrl = epgUrlsFields[targetFeed].stringValue;
          } else if ((!targetFeed || targetFeed === 'main') && mainData.fields.epgSourceUrl) {
            epgSourceUrl = parseFirestoreString(mainData.fields.epgSourceUrl);
          }
        }
      } catch (e) {}

      if (epgSourceUrl) {
        try {
          const proxyRes = await fetch(epgSourceUrl, {
            headers: { 'User-Agent': 'Mozilla/5.0' }
          });
          if (proxyRes.ok) {
            const xmlText = await proxyRes.text();
            return new Response(xmlText, {
              status: 200,
              headers: {
                'Content-Type': 'application/xml; charset=utf-8',
                'Cache-Control': 'no-cache, no-store, must-revalidate',
                'Access-Control-Allow-Origin': '*'
              }
            });
          }
        } catch (err) {}
      }

      try {
        const epgRes = await fetch('https://firestore.googleapis.com/v1/projects/jtbs-classic/databases/(default)/documents/epg?pageSize=300', {
          headers: { 'Accept': 'application/json' }
        });
        
        const customDocs = [];
        if (epgRes.ok) {
          const epgData = await epgRes.json();
          const docs = epgData.documents || [];
          
          for (const doc of docs) {
            const fields = doc.fields || {};
            const channelRaw = parseFirestoreString(fields.channel) || 'main';
            const title = parseFirestoreString(fields.title) || 'Live Broadcast';
            const desc = parseFirestoreString(fields.description) || '';
            const startVal = fields.start?.timestampValue;
            const endVal = fields.end?.timestampValue;
            
            if (startVal && endVal) {
              customDocs.push({
                channel: channelRaw.toLowerCase(),
                title,
                description: desc,
                start: new Date(startVal),
                end: new Date(endVal)
              });
            }
          }
        }

        const today = new Date();
        const dates = [];
        for (let i = -1; i <= 2; i++) {
          dates.push(new Date(today.getTime() + i * 24 * 3600000));
        }

        const finalProgrammes = {
          'main': [],
          'decoder': [],
          'feed1': [],
          'feed2': [],
          'feed3': [],
          'feed4': [],
          'feed5': [],
          'feed6': [],
          'feed7': [],
          'feed8': [],
          'feed9': [],
          'feed10': [],
          'feed11': [],
          'feed12': [],
          'feed13': [],
          'feed14': [],
          'feed15': [],
          'feed16': [],
          'feed17': [],
          'feed18': [],
          'feed19': [],
          'feed20': [],
          'srinjana': []
        };

        if (!targetFeed || targetFeed === 'main') {
          for (const date of dates) {
            const defaultList = generateDefaultEpgForDay(date);
            finalProgrammes['main'].push(...defaultList);
          }
        }

        for (const custom of customDocs) {
          const ch = custom.channel;
          if (finalProgrammes[ch] !== undefined) {
            if (ch === 'main') {
              finalProgrammes['main'] = finalProgrammes['main'].filter(def => {
                const overlaps = custom.start < def.end && def.start < custom.end;
                return !overlaps;
              });
            }
            finalProgrammes[ch].push(custom);
          }
        }

        let programmesXml = '';
        const channelDefs = {
          'main': { id: 'JTBSClassic.in', name: 'JTBS Classic - Main Stream' },
          'decoder': { id: 'JTBS.Decoder', name: 'JTBS Classic - Decoder Stream' },
          'feed1': { id: 'JTBS.Feed1', name: 'JTBS Feed 1' },
          'feed2': { id: 'JTBS.Feed2', name: 'JTBS Feed 2' },
          'feed3': { id: 'JTBS.Feed3', name: 'JTBS Feed 3' },
          'feed4': { id: 'JTBS.Feed4', name: 'JTBS Feed 4' },
          'feed5': { id: 'JTBS.Feed5', name: 'JTBS Feed 5' },
          'feed6': { id: 'JTBS.Feed6', name: 'JTBS Feed 6' },
          'feed7': { id: 'JTBS.Feed7', name: 'JTBS Feed 7' },
          'feed8': { id: 'JTBS.Feed8', name: 'JTBS Feed 8' },
          'feed9': { id: 'JTBS.Feed9', name: 'JTBS Feed 9' },
          'feed10': { id: 'JTBS.Feed10', name: 'JTBS Feed 10' },
          'feed11': { id: 'JTBS.Feed11', name: 'JTBS Feed 11' },
          'feed12': { id: 'JTBS.Feed12', name: 'JTBS Feed 12' },
          'feed13': { id: 'JTBS.Feed13', name: 'JTBS Feed 13' },
          'feed14': { id: 'JTBS.Feed14', name: 'JTBS Feed 14' },
          'feed15': { id: 'JTBS.Feed15', name: 'JTBS Feed 15' },
          'feed16': { id: 'JTBS.Feed16', name: 'JTBS Feed 16' },
          'feed17': { id: 'JTBS.Feed17', name: 'JTBS Feed 17' },
          'feed18': { id: 'JTBS.Feed18', name: 'JTBS Feed 18' },
          'feed19': { id: 'JTBS.Feed19', name: 'JTBS Feed 19' },
          'feed20': { id: 'JTBS.Feed20', name: 'JTBS Feed 20' },
          'srinjana': { id: 'JTBS.Srinjana', name: 'JTBS Srinjana' }
        };

        let channelHeaderXml = '';
        if (targetFeed && channelDefs[targetFeed]) {
          channelHeaderXml = `  <channel id="${channelDefs[targetFeed].id}">\n    <display-name>${escapeXml(channelDefs[targetFeed].name)}</display-name>\n  </channel>\n`;
        } else {
          for (const k of Object.keys(channelDefs)) {
            channelHeaderXml += `  <channel id="${channelDefs[k].id}">\n    <display-name>${escapeXml(channelDefs[k].name)}</display-name>\n  </channel>\n`;
          }
        }

        for (const ch of Object.keys(finalProgrammes)) {
          if (targetFeed && ch !== targetFeed) continue;
          
          const list = finalProgrammes[ch];
          list.sort((a, b) => a.start - b.start);
          
          for (const prog of list) {
            const startXml = formatXMLTVDate(prog.start.toISOString());
            const endXml = formatXMLTVDate(prog.end.toISOString());
            const title = escapeXml(prog.title);
            const desc = escapeXml(prog.description);
            const channelId = channelDefs[ch].id;
            
            programmesXml += `  <programme start="${startXml}" stop="${endXml}" channel="${channelId}">
    <title lang="en">${title}</title>
    <desc lang="en">${desc}</desc>
  </programme>\n`;
          }
        }

        const fullXml = `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<tv generator-info-name="JTBS-EPG-Generator" generator-info-url="https://jtbsclassic.dpdns.org">
${channelHeaderXml}${programmesXml}</tv>`;

        return new Response(fullXml, {
          status: 200,
          headers: {
            'Content-Type': 'application/xml; charset=utf-8',
            'Cache-Control': 'no-cache, no-store, must-revalidate',
            'Access-Control-Allow-Origin': '*'
          }
        });
      } catch (err) {
        return new Response('EPG error: ' + err.message, { status: 500 });
      }
    }

    // 1. PROXY HANDLER: Directly proxy any requested media URL with Edge Caching & 401 Session Auto-Recovery
    if (path.startsWith('/proxy')) {
      let targetUrl = url.searchParams.get('url');
      const passedCookie = url.searchParams.get('cookie') || '';
      const docName = url.searchParams.get('doc') || 'main';
      
      const rawSearch = url.search;
      const urlIdx = rawSearch.indexOf('url=');
      if (urlIdx !== -1) {
        let extracted = rawSearch.substring(urlIdx + 4);
        const cookieIdx = extracted.indexOf('&cookie=');
        if (cookieIdx !== -1) {
          extracted = extracted.substring(0, cookieIdx);
        }
        const seqIdx = extracted.indexOf('&seq=');
        if (seqIdx !== -1) {
          extracted = extracted.substring(0, seqIdx);
        }
        try {
          targetUrl = decodeURIComponent(extracted);
        } catch(e) {
          targetUrl = extracted;
        }
      }
      
      if (!targetUrl) {
        return new Response('Missing url parameter', { status: 400 });
      }

      // Check if requested resource is an immutable video segment (.ts, .m4s, .mp4)
      const isMediaSegment = !targetUrl.includes('.m3u8') && !targetUrl.includes('mpegurl') &&
        (targetUrl.includes('.ts') || targetUrl.includes('.m4s') || targetUrl.includes('.mp4'));

      // EDGE CACHE: Instant RAM delivery (<15ms) for video chunks
      const edgeCache = (typeof caches !== 'undefined' && caches.default) ? caches.default : null;
      // Normalise cache key to worker origin so Cloudflare Cache API successfully stores it
      const cleanCacheKeyUrl = targetUrl.split('?')[0];
      const segFileName = cleanCacheKeyUrl.substring(cleanCacheKeyUrl.lastIndexOf('/') + 1);
      const cacheKey = new Request(`${url.origin}/segment-cache/${encodeURIComponent(docName)}/${segFileName}`);

      if (isMediaSegment && edgeCache) {
        try {
          const cachedChunk = await edgeCache.match(cacheKey);
          if (cachedChunk) {
            return new Response(cachedChunk.body, {
              status: 200,
              headers: {
                'Content-Type': targetUrl.includes('.mp4') ? 'video/mp4' : 'video/mp2t',
                'Cache-Control': 'public, max-age=86400, s-maxage=31536000, immutable',
                'Access-Control-Allow-Origin': '*',
                'X-Edge-Cache': 'HIT'
              }
            });
          }
        } catch (e) {}
      }

      if (!targetUrl.includes('cookieCheck=')) {
        targetUrl += (targetUrl.includes('?') ? '&' : '?') + 'cookieCheck=1';
      }

      let activeCookie = passedCookie;
      const sessionData = upstreamSessionStore.get(docName);
      if (!activeCookie && sessionData && sessionData.cookie) {
        activeCookie = sessionData.cookie;
      }

      let cookieHeader = deduplicateCookies([ 'cookieCheck=1', activeCookie ].filter(Boolean).join('; '));

      try {
        let proxyRes = await fetch(targetUrl, {
          redirect: 'follow',
          cf: isMediaSegment ? { cacheEverything: true, cacheTtl: 86400 } : undefined,
          headers: {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'cf-tunnel-skip-offline-page': 'true',
            'Cookie': cookieHeader
          }
        });

        // 401 AUTO-RECOVERY: If MediaMTX session expired, re-negotiate fresh session and retry
        if (proxyRes.status === 401) {
          const streamUrl = sessionData?.streamUrl || memoryStore.get(docName)?.streamUrl || '';
          const freshCookie = await refreshUpstreamSession(docName, streamUrl);
          if (freshCookie) {
            activeCookie = freshCookie;
            cookieHeader = deduplicateCookies([ 'cookieCheck=1', freshCookie ].filter(Boolean).join('; '));
            proxyRes = await fetch(targetUrl, {
              redirect: 'follow',
              cf: isMediaSegment ? { cacheEverything: true, cacheTtl: 86400 } : undefined,
              headers: {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'cf-tunnel-skip-offline-page': 'true',
                'Cookie': cookieHeader
              }
            });
          }
        }

        if (!proxyRes.ok) {
          return new Response(`Proxy source error: ${proxyRes.status}`, { 
            status: proxyRes.status,
            headers: { 'Access-Control-Allow-Origin': '*' }
          });
        }

        const newCookie = extractSetCookies(proxyRes.headers);
        const combinedCookie = deduplicateCookies([activeCookie, newCookie].filter(Boolean).join('; '));
        if (combinedCookie) {
          const existing = upstreamSessionStore.get(docName);
          upstreamSessionStore.set(docName, {
            cookie: combinedCookie,
            timestamp: Date.now(),
            streamUrl: existing?.streamUrl || ''
          });
        }

        let contentType = proxyRes.headers.get('Content-Type') || '';

        // If sub-playlist (.m3u8), rewrite relative URLs
        if (contentType.includes('mpegurl') || contentType.includes('m3u8') || targetUrl.includes('.m3u8')) {
          const rawText = await proxyRes.text();
          const rewrittenPlaylist = rewriteM3u8(rawText, targetUrl, url.origin, combinedCookie, docName);

          return new Response(rewrittenPlaylist, {
            status: 200,
            headers: {
              'Content-Type': 'application/vnd.apple.mpegurl',
              'Cache-Control': 'no-cache, no-store, must-revalidate, max-age=0',
              'Pragma': 'no-cache',
              'Expires': '0',
              'Access-Control-Allow-Origin': '*'
            }
          });
        }

        if (!contentType || contentType.includes('audio/mpeg') || contentType.includes('text/plain')) {
          contentType = targetUrl.includes('.mp4') ? 'video/mp4' : 'video/mp2t';
        }

        // Cache video chunk in Edge Cache and stream to client
        if (isMediaSegment) {
          const chunkData = await proxyRes.arrayBuffer();
          const chunkHeaders = {
            'Content-Type': contentType,
            'Cache-Control': 'public, max-age=86400, s-maxage=31536000, immutable',
            'Access-Control-Allow-Origin': '*',
            'X-Edge-Cache': 'MISS'
          };

          if (edgeCache) {
            try {
              await edgeCache.put(cacheKey, new Response(chunkData.slice(0), {
                status: 200,
                headers: chunkHeaders
              }));
            } catch (e) {}
          }

          return new Response(chunkData, {
            status: 200,
            headers: chunkHeaders
          });
        }

        return new Response(proxyRes.body, {
          status: 200,
          headers: {
            'Content-Type': contentType,
            'Cache-Control': 'public, max-age=86400, s-maxage=31536000, immutable',
            'Access-Control-Allow-Origin': '*'
          }
        });

      } catch (err) {
        return new Response('Proxy error: ' + err.message, { status: 500 });
      }
    }



    // 2. PATH MAPPING: Map allowed IPTV paths strictly to Firestore streamState document names
    const pathToDoc = {
      '/live': { doc: 'main', type: 'public' },
      '/live.m3u8': { doc: 'main', type: 'public' },
      '/decoder': { doc: 'main', type: 'decoder' },
      '/decoder.m3u8': { doc: 'main', type: 'decoder' },
      '/live1': { doc: 'feed1', type: 'public' },
      '/live1.m3u8': { doc: 'feed1', type: 'public' },
      '/live2': { doc: 'feed2', type: 'public' },
      '/live2.m3u8': { doc: 'feed2', type: 'public' },
      '/live3': { doc: 'feed3', type: 'public' },
      '/live3.m3u8': { doc: 'feed3', type: 'public' },
      '/live4': { doc: 'feed4', type: 'public' },
      '/live4.m3u8': { doc: 'feed4', type: 'public' },
      '/live5': { doc: 'feed5', type: 'public' },
      '/live5.m3u8': { doc: 'feed5', type: 'public' },
      '/live6': { doc: 'feed6', type: 'public' },
      '/live6.m3u8': { doc: 'feed6', type: 'public' },
      '/live7': { doc: 'feed7', type: 'public' },
      '/live7.m3u8': { doc: 'feed7', type: 'public' },
      '/live8': { doc: 'feed8', type: 'public' },
      '/live8.m3u8': { doc: 'feed8', type: 'public' },
      '/live9': { doc: 'feed9', type: 'public' },
      '/live9.m3u8': { doc: 'feed9', type: 'public' },
      '/live10': { doc: 'feed10', type: 'public' },
      '/live10.m3u8': { doc: 'feed10', type: 'public' },
      '/live11': { doc: 'feed11', type: 'public' },
      '/live11.m3u8': { doc: 'feed11', type: 'public' },
      '/live12': { doc: 'feed12', type: 'public' },
      '/live12.m3u8': { doc: 'feed12', type: 'public' },
      '/live13': { doc: 'feed13', type: 'public' },
      '/live13.m3u8': { doc: 'feed13', type: 'public' },
      '/live14': { doc: 'feed14', type: 'public' },
      '/live14.m3u8': { doc: 'feed14', type: 'public' },
      '/live15': { doc: 'feed15', type: 'public' },
      '/live15.m3u8': { doc: 'feed15', type: 'public' },
      '/live16': { doc: 'feed16', type: 'public' },
      '/live16.m3u8': { doc: 'feed16', type: 'public' },
      '/live17': { doc: 'feed17', type: 'public' },
      '/live17.m3u8': { doc: 'feed17', type: 'public' },
      '/live18': { doc: 'feed18', type: 'public' },
      '/live18.m3u8': { doc: 'feed18', type: 'public' },
      '/live19': { doc: 'feed19', type: 'public' },
      '/live19.m3u8': { doc: 'feed19', type: 'public' },
      '/live20': { doc: 'feed20', type: 'public' },
      '/live20.m3u8': { doc: 'feed20', type: 'public' },
      '/srinjana': { doc: 'srinjana', type: 'public' },
      '/srinjana.m3u8': { doc: 'srinjana', type: 'public' },
      '/Srinjana': { doc: 'srinjana', type: 'public' },
      '/Srinjana.m3u8': { doc: 'srinjana', type: 'public' }
    };

    const target = pathToDoc[path];

    if (!target) {
      return new Response('404 Not Found — Invalid IPTV Endpoint', {
        status: 404,
        headers: { 
          'Content-Type': 'text/plain',
          'Access-Control-Allow-Origin': '*'
        }
      });
    }

    const firestoreUrl = `https://firestore.googleapis.com/v1/projects/jtbs-classic/databases/(default)/documents/streamState/${target.doc}`;
    const mainConfigUrl = `https://firestore.googleapis.com/v1/projects/jtbs-classic/databases/(default)/documents/config/main`;

    try {
      let isLive = false;
      let streamUrl = '';
      let offlineHlsUrl = '';

      // Get main config for global default offline URL
      let defaultOfflineHlsUrl = '';
      try {
        const mainData = await getCachedFirestoreDoc(mainConfigUrl, url.origin);
        if (mainData) {
          const mainFields = mainData.fields || {};
          defaultOfflineHlsUrl = parseFirestoreString(mainFields.offlineHlsUrl) || parseFirestoreString(mainFields.offlineVideoUrl);
        }
      } catch (e) {}

      // Fetch Firestore document for target.doc (main, feed1, feed2, feed3, feed4, feed5, feed6)
      const data = await getCachedFirestoreDoc(firestoreUrl, url.origin);
      const fields = data ? (data.fields || data) : {};

      if (target.type === 'decoder') {
        const mode = parseFirestoreString(fields.mode) || 'same';
        if (mode === 'different') {
          isLive = parseFirestoreBool(fields.decoderIsLive);
          streamUrl = parseFirestoreString(fields.decoderStreamUrl);
          offlineHlsUrl = parseFirestoreString(fields.decoderOfflineHlsUrl) || parseFirestoreString(fields.offlineHlsUrl) || parseFirestoreString(fields.offlineVideoUrl) || defaultOfflineHlsUrl;
        } else {
          isLive = parseFirestoreBool(fields.isLive);
          streamUrl = parseFirestoreString(fields.streamUrl);
          offlineHlsUrl = parseFirestoreString(fields.offlineHlsUrl) || parseFirestoreString(fields.offlineVideoUrl) || defaultOfflineHlsUrl;
        }
      } else {
        // Public streams (main or feed1..feed6)
        isLive = parseFirestoreBool(fields.isLive);
        streamUrl = parseFirestoreString(fields.streamUrl);
        offlineHlsUrl = parseFirestoreString(fields.offlineHlsUrl) || parseFirestoreString(fields.offlineVideoUrl) || defaultOfflineHlsUrl;
      }

      // Check memoryStore overrides if saved via Admin Panel sync or worker memory
      let memData = null;
      if (target.type === 'decoder') {
        const mode = parseFirestoreString(fields.mode) || 'same';
        if (mode === 'different') {
          memData = memoryStore.get('decoder');
        }
        if (!memData) {
          memData = memoryStore.get('main');
        }
      } else {
        memData = memoryStore.get(target.doc);
        if (!memData && target.doc === 'main') {
          memData = memoryStore.get('main');
        }
      }

      if (memData) {
        if (memData.isLive !== undefined) isLive = memData.isLive;
        if (memData.streamUrl) streamUrl = memData.streamUrl;
        if (memData.offlineHlsUrl) offlineHlsUrl = memData.offlineHlsUrl;
      }

      // OFFLINE STATE: If isLive is false or streamUrl is empty, play offlineHlsUrl screen!
      if (!isLive || !streamUrl) {
        if (isLive && !streamUrl) {
          // streamUrl is missing (Firestore failure) but channel is live — fallback to holding HLS stream
          const fallbackUrl = 'https://infinite-hls-stream.vercel.app/stream.m3u8';
          return await handleOffAirResponse(fallbackUrl, 10, url.origin, target.doc);
        } else {
          const targetOffline = offlineHlsUrl || defaultOfflineHlsUrl || 'https://infinite-hls-stream.vercel.app/stream.m3u8';
          return await handleOffAirResponse(targetOffline, 10, url.origin, target.doc);
        }
      }

      // If stream URL is a direct video/TS stream or non-HLS URL, dynamically redirect!
      if (!streamUrl.includes('.m3u8') && !streamUrl.includes('mpegurl')) {
        return new Response(null, {
          status: 302,
          headers: {
            'Location': streamUrl,
            'Access-Control-Allow-Origin': '*',
            'Cache-Control': 'no-cache, no-store, must-revalidate, max-age=0'
          }
        });
      }

      let targetStreamUrl = streamUrl;
      if (!targetStreamUrl.includes('cookieCheck=')) {
        targetStreamUrl += (targetStreamUrl.includes('?') ? '&' : '?') + 'cookieCheck=1';
      }

      let streamRes;
      try {
        streamRes = await fetch(targetStreamUrl, {
          redirect: 'follow',
          headers: {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'cf-tunnel-skip-offline-page': 'true',
            'Cookie': 'cookieCheck=1'
          }
        });
      } catch (e) {
        // Watchdog: Upstream fetch network error (e.g. connection refused, DNS failure)
        const targetOffline = offlineHlsUrl || defaultOfflineHlsUrl || 'https://infinite-hls-stream.vercel.app/stream.m3u8';
        return await handleOffAirResponse(targetOffline, 10, url.origin, target.doc);
      }

      if (!streamRes || !streamRes.ok) {
        // Watchdog: Upstream HTTP error (530 Cloudflare Tunnel down, 502, 1016, 404, etc.)
        const targetOffline = offlineHlsUrl || defaultOfflineHlsUrl || 'https://infinite-hls-stream.vercel.app/stream.m3u8';
        return await handleOffAirResponse(targetOffline, 10, url.origin, target.doc);
      }

      const sessionCookie = extractSetCookies(streamRes.headers);
      if (sessionCookie) {
        upstreamSessionStore.set(target.doc, {
          cookie: sessionCookie,
          timestamp: Date.now(),
          streamUrl
        });
      }
      const rawBody = await streamRes.text();
      const rewrittenBody = rewriteM3u8(rawBody, streamUrl, url.origin, sessionCookie, target.doc);

      return new Response(rewrittenBody, {
        status: 200,
        headers: {
          'Content-Type': 'application/vnd.apple.mpegurl',
          'Cache-Control': 'no-cache, no-store, must-revalidate, max-age=0',
          'Pragma': 'no-cache',
          'Expires': '0',
          'Access-Control-Allow-Origin': '*'
        }
      });

    } catch (e) {
      const targetOffline = offlineHlsUrl || defaultOfflineHlsUrl || 'https://infinite-hls-stream.vercel.app/stream.m3u8';
      return await handleOffAirResponse(targetOffline, 10, url.origin, target.doc);
    }
  }
};

function formatXMLTVDate(dateStr) {
  if (!dateStr) return '';
  const d = (dateStr instanceof Date) ? dateStr : new Date(dateStr);
  if (isNaN(d.getTime())) return '';
  
  const istOffsetMs = 5.5 * 60 * 60 * 1000;
  const istDate = new Date(d.getTime() + istOffsetMs);
  
  const pad = (n) => String(n).padStart(2, '0');
  
  const year = istDate.getUTCFullYear();
  const month = pad(istDate.getUTCMonth() + 1);
  const day = pad(istDate.getUTCDate());
  const hour = pad(istDate.getUTCHours());
  const min = pad(istDate.getUTCMinutes());
  const sec = pad(istDate.getUTCSeconds());
  
  return `${year}${month}${day}${hour}${min}${sec} +0530`;
}

function escapeXml(unsafe) {
  if (!unsafe) return '';
  return unsafe.replace(/[<>&'"]/g, function (c) {
    switch (c) {
      case '<': return '&lt;';
      case '>': return '&gt;';
      case '&': return '&amp;';
      case '\'': return '&apos;';
      case '"': return '&quot;';
    }
  });
}

const DEFAULT_EPG_SCHEDULE = [
  { start: '05:00', end: '05:30', title: "Mangala Arati, Nrsimhadeva Arati & Tulasi Arati", desc: "Live Morning Worship from Sri Mayapur Chandrodaya Mandir." },
  { start: '05:30', end: '06:30', title: "Morning Temple Worship", desc: "Devotional morning services, prayers, and chants." },
  { start: '06:30', end: '07:00', title: "Darshan Arati", desc: "First morning darshan of Sri Sri Radha Madhava and other deities." },
  { start: '07:00', end: '07:30', title: "Srila Prabhupada Guru Puja", desc: "Guru puja offering to the founder-acharya of ISKCON." },
  { start: '07:30', end: '09:00', title: "Srimad Bhagavatam Class", desc: "Daily discourse and study of Srimad Bhagavatam." },
  { start: '09:00', end: '10:00', title: "Devotional & Spiritual Programmes", desc: "Devotional videos, talks, and kirtan presentations." },
  { start: '10:00', end: '11:00', title: "বাউল আসর (Baul Asor)", desc: "Traditional Bengali Baul devotional music performance." },
  { start: '11:00', end: '12:00', title: "Cultural Programme (Dance)", desc: "Traditional dance and drama devotional performance." },
  { start: '12:00', end: '12:30', title: "Devotional Music", desc: "Melodious singing and chanting of holy names." },
  { start: '12:30', end: '13:00', title: "বাউল আসর (Baul Asor)", desc: "Devotional folk songs and music segment." },
  { start: '13:00', end: '14:00', title: "Cultural Programme (Dance)", desc: "Devotional classical dance presentation." },
  { start: '14:00', end: '15:00', title: "Cultural Programme (Dance)", desc: "Cultural performing arts and dance performances." },
  { start: '15:00', end: '16:00', title: "বাউল আসর (Baul Asor)", desc: "Heartfelt Baul and folk kirtans." },
  { start: '16:00', end: '16:30', title: "Temple Reopens & Dhupa Arati", desc: "Offering of incense and lamps to deities in Sri Mayapur." },
  { start: '16:30', end: '18:00', title: "Afternoon Temple Darshan & Devotional Programmes", desc: "Afternoon prayers, darshan, and discourses." },
  { start: '18:00', end: '18:30', title: "Sandhya Arati", desc: "Evening arati and Gaura Arati ceremony." },
  { start: '18:30', end: '19:30', title: "Evening Devotional Programmes", desc: "Melodious evening chants and spiritual programs." },
  { start: '19:30', end: '20:15', title: "Bhagavad Gita Class", desc: "Study of the teachings of Lord Krishna in Bhagavad Gita." },
  { start: '20:15', end: '20:30', title: "Shayana Arati", desc: "Final offering of the day before the deities take rest." },
  { start: '20:30', end: '21:30', title: "Cultural Programme (Dance)", desc: "Devotional dance recital and cultural performance." },
  { start: '21:30', end: '22:30', title: "Cultural Programme (Dance)", desc: "Classical and traditional devotional dance drama." },
  { start: '22:30', end: '23:30', title: "Cultural Programme (Dance)", desc: "Devotional audio-visual cultural program." },
  { start: '23:30', end: '05:00', title: "বাউল আসর (Baul Asor) / Overnight Repeat Programming", desc: "Devotional folk songs and music segment / repeats until morning." }
];

function generateDefaultEpgForDay(date) {
  const y = date.getUTCFullYear();
  const m = date.getUTCMonth();
  const d = date.getUTCDate();
  const programmes = [];

  for (const slot of DEFAULT_EPG_SCHEDULE) {
    const startParts = slot.start.split(':');
    const endParts = slot.end.split(':');
    
    let startHour = parseInt(startParts[0]);
    let startMin = parseInt(startParts[1]);
    let endHour = parseInt(endParts[0]);
    let endMin = parseInt(endParts[1]);
    
    const start = new Date(Date.UTC(y, m, d, startHour, startMin) - 5.5 * 3600000);
    let end;
    if (endHour < startHour) {
      end = new Date(Date.UTC(y, m, d + 1, endHour, endMin) - 5.5 * 3600000);
    } else {
      end = new Date(Date.UTC(y, m, d, endHour, endMin) - 5.5 * 3600000);
    }
    
    programmes.push({
      title: slot.title,
      description: slot.desc,
      start: start,
      end: end
    });
  }
  return programmes;
}
