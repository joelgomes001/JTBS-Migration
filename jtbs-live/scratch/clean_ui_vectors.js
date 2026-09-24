const fs = require('fs');
const path = require('path');

const filePath = path.join(__dirname, '..', 'public', 'admin.html');
let html = fs.readFileSync(filePath, 'utf8');

// SVG Helper definitions
const SVG = {
  crown: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><path d="M2 4l3 12h14l3-12-6 7-4-7-4 7-6-7zm3 16h14"/></svg>`,
  crownLg: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fbbf24" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-3px; margin-right:6px;"><path d="M2 4l3 12h14l3-12-6 7-4-7-4 7-6-7zm3 16h14"/></svg>`,
  lock: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>`,
  globe: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>`,
  shield: `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>`,
  key: `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"/></svg>`,
  bolt: `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>`,
  copy: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-1px; margin-right:3px;"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>`,
  revoke: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-1px; margin-right:3px;"><circle cx="12" cy="12" r="10"/><line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/></svg>`,
  alert: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>`,
  refresh: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>`,
  play: `<svg width="11" height="11" viewBox="0 0 24 24" fill="currentColor" style="vertical-align:-1px; margin-right:3px;"><polygon points="5 3 19 12 5 21 5 3"/></svg>`,
  stop: `<svg width="11" height="11" viewBox="0 0 24 24" fill="currentColor" style="vertical-align:-1px; margin-right:3px;"><rect x="4" y="4" width="16" height="16" rx="2"/></svg>`,
  save: `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg>`,
  network: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><rect x="2" y="2" width="20" height="8" rx="2" ry="2"/><rect x="2" y="14" width="20" height="8" rx="2" ry="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/></svg>`,
  matrix: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><rect x="2" y="2" width="20" height="20" rx="2.18" ry="2.18"/><line x1="7" y1="2" x2="7" y2="22"/><line x1="17" y1="2" x2="17" y2="22"/><line x1="2" y1="12" x2="22" y2="12"/><line x1="2" y1="7" x2="7" y2="7"/><line x1="2" y1="17" x2="7" y2="17"/><line x1="17" y1="17" x2="22" y2="17"/><line x1="17" y1="7" x2="22" y2="7"/></svg>`,
  audit: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>`,
  telegram: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>`,
  exportIcon: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>`,
  restoreIcon: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>`,
  randomIcon: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="vertical-align:-2px; margin-right:4px;"><polyline points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/><line x1="4" y1="4" x2="9" y2="9"/></svg>`
};

console.log('Replacing emojis with vector SVGs across admin.html...');

// Replace Godfather tab button
html = html.replace(
  '<span style="font-size:14px; margin-right:4px;">👑</span> Godfather',
  `${SVG.crown}Godfather`
);

// Replace Godfather HUD crown
html = html.replace(
  '<span style="font-size:22px; filter: drop-shadow(0 2px 4px rgba(0,0,0,0.5));">👑</span>',
  `${SVG.crownLg}`
);

// Replace Godfather HUD clear button
html = html.replace(
  '🛑 Clear Broadcast',
  `${SVG.stop}Clear Broadcast`
);

// Replace Main Stream ownership & locked badges
html = html.replace(
  '🌐 OPEN TO ALL',
  `${SVG.globe}OPEN TO ALL`
);
html = html.replace(
  '<span>🔒 MAIN STREAM LOCKED — RESTRICTED ACCESS</span>',
  `<span>${SVG.lock}MAIN STREAM LOCKED — RESTRICTED ACCESS</span>`
);

// Replace Backup Stream URL labels
html = html.replaceAll(
  '🛡️ Backup Stream URL (Optional Auto-Failover)',
  `${SVG.shield}Backup Stream URL (Auto-Failover)`
);
html = html.replaceAll(
  '🛡️ Public Backup Stream URL',
  `${SVG.shield}Public Backup Stream URL`
);
html = html.replaceAll(
  '🛡️ Decoder Backup Stream URL',
  `${SVG.shield}Decoder Backup Stream URL`
);

// Replace Announcement banner title
html = html.replace(
  '📢 Viewer Announcement Banner',
  `<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align:-2px; margin-right:4px;"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>Viewer Announcement Banner`
);

// Replace Godfather Suite Card Title
html = html.replace(
  '<span style="font-size:22px;">👑</span> GODFATHER MASTER SUITE',
  `${SVG.crownLg}GODFATHER MASTER SUITE`
);

// Replace Master Emergency Kill-Switch & buttons
html = html.replace(
  '🚨 Master Emergency Kill-Switch',
  `${SVG.alert}Master Emergency Controls`
);
html = html.replace(
  '🛑 Blackout All Feeds',
  `${SVG.stop}Blackout All Feeds`
);
html = html.replace(
  '🟢 Set All Live',
  `${SVG.play}Set All Live`
);

// Replace Snapshot & Recovery
html = html.replace(
  '📦 Network Snapshot & Recovery',
  `${SVG.network}Network Snapshot & Recovery`
);
html = html.replace(
  '📥 Export Backup',
  `${SVG.exportIcon}Export Backup`
);
html = html.replace(
  '📤 Restore Backup',
  `${SVG.restoreIcon}Restore Backup`
);

// Replace Direct Password Override
html = html.replace(
  '🔑 Direct Password Override Master',
  `${SVG.key}Master Password Override`
);
html = html.replace(
  '⚡ Set Password Directly',
  `${SVG.bolt}Set Password Directly`
);
html = html.replace(
  'title="Generate Random Secure Password">🎲</button>',
  `title="Generate Random Secure Password">${SVG.randomIcon}</button>`
);

// Replace Master Network Takeover
html = html.replace(
  '<span>🔴 MASTER NETWORK TAKEOVER (1-CLICK OVERRIDE)</span>',
  `<span>${SVG.bolt}MASTER NETWORK TAKEOVER</span>`
);
html = html.replace(
  '💾 Save Master Takeover Settings',
  `${SVG.save}Save Master Takeover Settings`
);

// Replace Godfather VIP Keymaker
html = html.replace(
  '<span>🔑 Godfather VIP Keymaker (Expiring Token License Generator)</span>',
  `<span>${SVG.key}VIP Keymaker (Token Licensing)</span>`
);
html = html.replace(
  '🔄 Refresh Tokens',
  `${SVG.refresh}Refresh Tokens`
);
html = html.replace(
  '⚡ Generate VIP Token Link',
  `${SVG.bolt}Generate VIP Access Token`
);

// Replace Watchtower Matrix
html = html.replace(
  '<span>🦅 Godfather Watchtower: 22-Channel Live Matrix</span>',
  `<span>${SVG.matrix}Network Watchtower: 22-Channel Live Matrix</span>`
);
html = html.replace(
  '🔄 Refresh All Previews',
  `${SVG.refresh}Refresh All Previews`
);
html = html.replace(
  '🛑 Stop All Previews',
  `${SVG.stop}Stop All Previews`
);

// Replace Godfather Telegram Hotline
html = html.replace(
  '<span>📱 Godfather Telegram Watchdog Hotline</span>',
  `<span>${SVG.telegram}Telegram Security Watchdog Hotline</span>`
);
html = html.replace(
  '📲 Send Test Alert to Telegram',
  `${SVG.telegram}Send Test Alert to Telegram`
);
html = html.replace(
  '💾 Save Watchdog Settings',
  `${SVG.save}Save Watchdog Settings`
);

// Replace Godfather Audit Trail
html = html.replace(
  '<span>📜 Immutable Godfather Audit Trail</span>',
  `<span>${SVG.audit}Godfather Audit Trail & System Logs`
);
html = html.replace(
  '🔄 Refresh Audit Logs',
  `${SVG.refresh}Refresh Logs`
);
html = html.replace(
  '🧹 Purge Audit Logs (>30 Days)',
  `${SVG.revoke}Purge Logs (>30 Days)`
);

// Clean up dropdown options
html = html.replace(
  '<option value="all">🌐 All Channels (Feeds 1–20 + Main + Decoders + Srinjana)</option>',
  '<option value="all">All Channels (Feeds 1–20 + Main + Decoders + Srinjana)</option>'
);
html = html.replace(
  '<option value="feeds_only">📡 Feeds 1–20 & Srinjana Only</option>',
  '<option value="feeds_only">Feeds 1–20 & Srinjana Only</option>'
);
html = html.replace(
  '<option value="main_only">📺 Main Channel (live.m3u8) Only</option>',
  '<option value="main_only">Main Channel (live.m3u8) Only</option>'
);
html = html.replace(
  '<option value="decoders_only">📦 Decoder TV Boxes (decoder.m3u8) Only</option>',
  '<option value="decoders_only">Decoder Fleet (decoder.m3u8) Only</option>'
);
html = html.replace(
  '<option value="all" style="background:#0f172a; color:#fff;">🌐 All Channels (Feeds 1–20 + Srinjana)</option>',
  '<option value="all" style="background:#0f172a; color:#fff;">All Channels (Feeds 1–20 + Srinjana)</option>'
);

// Replace inline token generator button in feeds
html = html.replaceAll(
  '⚡ Generate VIP Access Token',
  `${SVG.bolt}Generate VIP Access Token`
);

// Replace preview buttons in Watchtower and feeds
html = html.replaceAll(
  '▶️ Play Preview',
  `${SVG.play}Play Preview`
);
html = html.replaceAll(
  '⏹️ Stop Preview',
  `${SVG.stop}Stop Preview`
);

// Clean descriptions and badges in JS functions
html = html.replaceAll(
  "'🔒 TOKENIZED'",
  `'<svg width=\"11\" height=\"11\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.2\" style=\"vertical-align:-1px; margin-right:3px;\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\" ry=\"2\"/><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"/></svg>TOKENIZED'`
);
html = html.replaceAll(
  "'🌐 FTA OPEN'",
  `'<svg width=\"11\" height=\"11\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.2\" style=\"vertical-align:-1px; margin-right:3px;\"><circle cx=\"12\" cy=\"12\" r=\"10\"/><line x1=\"2\" y1=\"12\" x2=\"22\" y2=\"12\"/><path d=\"M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z\"/></svg>FTA OPEN'`
);

html = html.replaceAll(
  '🔒 <strong>Tokenized Mode:</strong>',
  '<strong>Tokenized Security:</strong>'
);
html = html.replaceAll(
  '🌐 <strong>FTA Mode:</strong>',
  '<strong>Free-To-Air (FTA):</strong>'
);

html = html.replaceAll(
  '🔒 Upstream stream source is hidden',
  'Upstream stream source is protected'
);

// Clean up copy and revoke buttons in inline table
html = html.replaceAll(
  '📋 Copy Link',
  `${SVG.copy}Copy Link`
);
html = html.replaceAll(
  '📋 Copy',
  `${SVG.copy}Copy`
);
html = html.replaceAll(
  '🚫 Revoke',
  `${SVG.revoke}Revoke`
);

fs.writeFileSync(filePath, html, 'utf8');
console.log('admin.html updated successfully with vector SVG icons!');
