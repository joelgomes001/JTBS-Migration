const fs = require('fs');

const adminPath = 'c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY PROJECTS/jtbs-live (1)/jtbs-live/public/admin.html';
const indexPath = 'c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY PROJECTS/jtbs-live (1)/jtbs-live/public/index.html';
const playlistPath = 'c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY PROJECTS/jtbs-live (1)/jtbs-live/public/playlist.m3u';
const workerJsonPath = 'c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY PROJECTS/jtbs-live (1)/jtbs-live/epg/worker.json';
const channelsXmlPath = 'c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY PROJECTS/jtbs-live (1)/jtbs-live/epg/channels.xml';
const logoPath = 'c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY PROJECTS/jtbs-live (1)/jtbs-live/public/logo.png';

const adminHtml = fs.readFileSync(adminPath, 'utf8');
const indexHtml = fs.readFileSync(indexPath, 'utf8');
const playlistM3u = fs.readFileSync(playlistPath, 'utf8');
const workerJson = fs.readFileSync(workerJsonPath, 'utf8');
const channelsXml = fs.readFileSync(channelsXmlPath, 'utf8');
const logoBase64 = fs.readFileSync(logoPath).toString('base64');

const outAdmin = `export const ADMIN_HTML = ${JSON.stringify(adminHtml)};\n`;
const outIndex = `export const INDEX_HTML = ${JSON.stringify(indexHtml)};\n`;
const outPlaylist = `export const PLAYLIST_M3U = ${JSON.stringify(playlistM3u)};\n`;
const outEpg = `export const WORKER_JSON = ${JSON.stringify(workerJson)};\nexport const CHANNELS_XML = ${JSON.stringify(channelsXml)};\n`;
const outLogo = `export const LOGO_BASE64 = ${JSON.stringify(logoBase64)};\n`;

fs.writeFileSync('c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY PROJECTS/jtbs-live (1)/jtbs-live/cloudflare-worker/adminPage.js', outAdmin);
fs.writeFileSync('c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY PROJECTS/jtbs-live (1)/jtbs-live/cloudflare-worker/indexPage.js', outIndex);
fs.writeFileSync('c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY PROJECTS/jtbs-live (1)/jtbs-live/cloudflare-worker/playlistPage.js', outPlaylist);
fs.writeFileSync('c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY PROJECTS/jtbs-live (1)/jtbs-live/cloudflare-worker/epgPages.js', outEpg);
fs.writeFileSync('c:/Users/JTBS-LIVE/Documents/ANTIGRAVITY PROJECTS/jtbs-live (1)/jtbs-live/cloudflare-worker/logoPage.js', outLogo);

console.log('Successfully updated adminPage.js, indexPage.js, playlistPage.js, epgPages.js, logoPage.js!');
