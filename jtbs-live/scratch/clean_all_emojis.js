const fs = require('fs');
const path = require('path');

const filePath = path.join(__dirname, '..', 'public', 'admin.html');
let html = fs.readFileSync(filePath, 'utf8');

const folderSvg = `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>`;
const userSvg = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>`;
const mailSvg = `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z"/><polyline points="22,6 12,13 2,6"/></svg>`;
const photoSvg = `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>`;

html = html.replace('📁 Import .txt / .m3u', `${folderSvg}Import .txt / .m3u`);
html = html.replace('placeholder="🔍 Search links by title or URL..."', 'placeholder="Search links by title or URL..."');
html = html.replace('<span>️ Schedule Conflict Detected</span>', '<span>Schedule Conflict Detected</span>');
html = html.replace('✂️ <strong>Auto-Adjust Times</strong>', '<strong>Auto-Adjust Times</strong>');
html = html.replace('🗑️ <strong>Replace Existing</strong>', '<strong>Replace Existing</strong>');
html = html.replace('❌ <strong>Cancel</strong>', '<strong>Cancel</strong>');
html = html.replace('<span style="display:flex; align-items:center; gap:6px;">👤 My Admin Profile</span>', `<span style="display:flex; align-items:center; gap:6px;">${userSvg}My Admin Profile</span>`);
html = html.replaceAll('📁 Choose Photo / Gallery', `${photoSvg}Choose Photo / Gallery`);
html = html.replaceAll('📁 Choose Photo', `${photoSvg}Choose Photo`);
html = html.replace('✉ Send Password Reset Email', `${mailSvg}Send Password Reset Email`);
html = html.replace('✉ Send Password Reset', `${mailSvg}Send Password Reset`);

// Clean string notifications
html = html.replaceAll("'✓ Copied!'", "'Copied!'");
html = html.replaceAll("'✓ Stream is live'", "'Stream is live'");
html = html.replaceAll("'✓ Public stream is live'", "'Public stream is live'");
html = html.replaceAll("'✓ Decoder stream is live'", "'Decoder stream is live'");
html = html.replaceAll("'✓ Banner cleared'", "'Banner cleared'");
html = html.replaceAll("'✓ Photo loaded! Click \"Save Profile\" below to apply.'", "'Photo loaded. Click \"Save Profile\" below to apply.'");
html = html.replaceAll("'✓ Profile saved successfully!'", "'Profile saved successfully.'");
html = html.replaceAll("✓ Linked", "Linked");
html = html.replaceAll("'✓ YouTube detected'", "'YouTube detected'");
html = html.replaceAll("'✓ HLS stream detected'", "'HLS stream detected'");
html = html.replaceAll("'✓ DASH stream detected'", "'DASH stream detected'");
html = html.replaceAll("'✓ RTMP stream detected'", "'RTMP stream detected'");
html = html.replaceAll("'✓ Video file (MP4/WebM/MKV/AVI/MOV/TS) detected'", "'Video file detected'");
html = html.replaceAll("'✗ Facebook not supported'", "'Facebook not supported'");
html = html.replaceAll("'✗ Facebook not accepted'", "'Facebook not accepted'");
html = html.replaceAll("'✗ YouTube not supported on decoder'", "'YouTube not supported on decoder'");
html = html.replaceAll("'✗ Facebook not supported on decoder'", "'Facebook not supported on decoder'");
html = html.replaceAll("<span>✓ Token Generated for", "<span>Token Generated for");
html = html.replaceAll("'✓ ACCESS AUTHORIZED'", "'ACCESS AUTHORIZED'");
html = html.replaceAll("✓ PERMITTED", "PERMITTED");
html = html.replaceAll("'✓ Links Saved Successfully!'", "'Links Saved Successfully.'");
html = html.replaceAll("✓ ", "");
html = html.replaceAll("✗ ", "Error: ");
html = html.replaceAll("✕", "Close");
html = html.replaceAll("👤 ", "");
html = html.replaceAll("▶️ Preview", "Preview");
html = html.replaceAll("▶️ Play All Video Feeds", "Play All Feeds");
html = html.replaceAll("⏹️ Stop All Video Feeds", "Stop All Feeds");
html = html.replaceAll("⏸️ Stop All Live Previews", "Stop All Previews");
html = html.replaceAll("▶️ Activate All Video Feeds", "Activate All Feeds");
html = html.replaceAll("ℹ️ HLS Only Preview:", "HLS Preview Note:");

fs.writeFileSync(filePath, html, 'utf8');
console.log('admin.html thoroughly cleaned!');
