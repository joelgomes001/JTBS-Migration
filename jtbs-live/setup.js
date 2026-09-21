#!/usr/bin/env node

/**
 * JTBS Streaming Platform - Interactive Setup Wizard
 * Zero external dependencies: pure Node.js 16+
 */

const readline = require('readline');
const fs = require('fs');
const path = require('path');
const { execSync, spawn } = require('child_process');

const IS_SUBDIR = !fs.existsSync(path.join(__dirname, 'jtbs-live'));
const ROOT_DIR = IS_SUBDIR ? path.resolve(__dirname, '..') : __dirname;
const JTBS_LIVE_DIR = IS_SUBDIR ? __dirname : path.join(__dirname, 'jtbs-live');
const PUBLIC_DIR = path.join(JTBS_LIVE_DIR, 'public');
const FIREBASE_RC_PATH = path.join(JTBS_LIVE_DIR, '.firebaserc');
const INDEX_HTML_PATH = path.join(PUBLIC_DIR, 'index.html');
const ADMIN_HTML_PATH = path.join(PUBLIC_DIR, 'admin.html');

// ANSI Colors
const C = {
  reset:   '\x1b[0m',
  bold:    '\x1b[1m',
  dim:     '\x1b[2m',
  cyan:    '\x1b[36m',
  green:   '\x1b[32m',
  yellow:  '\x1b[33m',
  blue:    '\x1b[34m',
  magenta: '\x1b[35m',
  red:     '\x1b[31m'
};

function clearScreen() {
  process.stdout.write('\x1b[2J\x1b[0f');
}

function printHeader() {
  console.log(`${C.cyan}${C.bold}`);
  console.log(`╔═══════════════════════════════════════════════════════════════════════╗`);
  console.log(`║                  JTBS STREAMING PLATFORM SETUP WIZARD                 ║`);
  console.log(`║           Digital TV Broadcasting & Hardware Decoder Management       ║`);
  console.log(`╚═══════════════════════════════════════════════════════════════════════╝${C.reset}\n`);
}

function prompt(question) {
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout
  });
  return new Promise((resolve) => {
    rl.question(`${C.bold}${question}${C.reset} `, (ans) => {
      rl.close();
      resolve(ans.trim());
    });
  });
}

function pause() {
  return prompt(`\n${C.dim}Press Enter to return to main menu...${C.reset}`);
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. FIREBASE SERVERLESS SETUP
// ─────────────────────────────────────────────────────────────────────────────
async function handleFirebaseSetup() {
  console.log(`\n${C.bold}${C.green}--- 🚀 Option 1: Firebase Serverless Setup ---${C.reset}\n`);
  console.log(`Firebase provides Edge CDN hosting, real-time Firestore synchronization,`);
  console.log(`and Cloud Functions authentication with $0 monthly cost under the free tier.\n`);

  const hasProject = await prompt(`Do you already have a Firebase project created at https://console.firebase.google.com ? (y/n):`);
  if (hasProject.toLowerCase() !== 'y') {
    console.log(`\n${C.yellow}👉 Steps to create your Firebase project:${C.reset}`);
    console.log(`  1. Go to: ${C.cyan}https://console.firebase.google.com${C.reset}`);
    console.log(`  2. Click "Add project" and choose a name (e.g. "my-jtbs-live")`);
    console.log(`  3. Enable Firestore Database (in Production mode)`);
    console.log(`  4. Enable Authentication (Email/Password sign-in)`);
    console.log(`  5. Upgrade project to Blaze plan (free tier gives 2M function calls/mo)\n`);
    await pause();
    return;
  }

  const projectId = await prompt(`Enter your Firebase Project ID (press Enter to keep current):`);
  if (projectId) {
    updateFirebaseRc(projectId);
  }

  const updateKeys = await prompt(`Do you want to configure your Firebase Web App API keys now? (y/n):`);
  if (updateKeys.toLowerCase() === 'y') {
    await configureFirebaseKeys();
  }

  console.log(`\n${C.blue}Installing dependencies in jtbs-live/functions...${C.reset}`);
  try {
    const fnDir = path.join(JTBS_LIVE_DIR, 'functions');
    if (fs.existsSync(fnDir)) {
      execSync('npm install', { cwd: fnDir, stdio: 'inherit' });
      console.log(`${C.green}✅ Functions dependencies installed successfully.${C.reset}`);
    }
  } catch (err) {
    console.log(`${C.red}⚠️ Warning: Could not run npm install in functions/: ${err.message}${C.reset}`);
  }

  const deployNow = await prompt(`\nWould you like to deploy to Firebase Hosting now? (y/n):`);
  if (deployNow.toLowerCase() === 'y') {
    try {
      console.log(`${C.cyan}Executing: firebase deploy --only hosting,firestore:rules${C.reset}\n`);
      execSync('firebase deploy --only hosting,firestore:rules', { cwd: JTBS_LIVE_DIR, stdio: 'inherit' });
      console.log(`\n${C.green}🎉 Deployment finished!${C.reset}`);
    } catch (err) {
      console.log(`\n${C.red}⚠️ Firebase CLI deployment failed.${C.reset}`);
      console.log(`Ensure you have logged in via: ${C.bold}firebase login${C.reset}`);
    }
  }

  await pause();
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. LOCAL PREVIEW / VPS SERVER
// ─────────────────────────────────────────────────────────────────────────────
async function handleLocalServer() {
  console.log(`\n${C.bold}${C.green}--- 💻 Option 2: Local & VPS Preview Server ---${C.reset}\n`);
  console.log(`Starts a zero-dependency Node.js HTTP server serving the complete live web app,`);
  console.log(`with automatic /admin route rewriting and live IPTV stream relay (/live.m3u8).\n`);

  const port = await prompt(`Enter port number [default: 5000]:`) || '5000';
  console.log(`\n${C.cyan}Starting server on port ${port}... (Press Ctrl+C to stop)${C.reset}\n`);

  const serverProc = spawn('node', ['server.js'], {
    cwd: ROOT_DIR,
    env: { ...process.env, PORT: port },
    stdio: 'inherit'
  });

  await new Promise((resolve) => {
    process.on('SIGINT', () => {
      serverProc.kill();
      resolve();
    });
    serverProc.on('close', resolve);
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. DOCKER CONTAINER HOSTING
// ─────────────────────────────────────────────────────────────────────────────
async function handleDocker() {
  console.log(`\n${C.bold}${C.green}--- 🐳 Option 3: Docker Container Hosting ---${C.reset}\n`);
  console.log(`Runs the JTBS platform in an isolated Alpine container on port 8080.\n`);

  try {
    execSync('docker --version', { stdio: 'pipe' });
  } catch {
    console.log(`${C.red}❌ Docker does not appear to be installed or running on your PATH.${C.reset}`);
    console.log(`Please install Docker Desktop from https://www.docker.com and try again.\n`);
    await pause();
    return;
  }

  const action = await prompt(`Choose action:\n  [1] Start container in background (docker compose up -d)\n  [2] View container logs\n  [3] Stop container (docker compose down)\nSelection [1]:`) || '1';

  try {
    if (action === '1') {
      execSync('docker compose up -d --build', { cwd: ROOT_DIR, stdio: 'inherit' });
      console.log(`\n${C.green}✅ Container is up and running!${C.reset}`);
      console.log(`👉 Access Viewer:    ${C.cyan}http://localhost:8080${C.reset}`);
      console.log(`👉 Access Admin:     ${C.cyan}http://localhost:8080/admin${C.reset}`);
    } else if (action === '2') {
      execSync('docker compose logs -f', { cwd: ROOT_DIR, stdio: 'inherit' });
    } else if (action === '3') {
      execSync('docker compose down', { cwd: ROOT_DIR, stdio: 'inherit' });
      console.log(`\n${C.green}✅ Container stopped.${C.reset}`);
    }
  } catch (e) {
    console.log(`\n${C.red}Docker command failed: ${e.message}${C.reset}`);
  }

  await pause();
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. CLOUDFLARE WORKER DEPLOYMENT
// ─────────────────────────────────────────────────────────────────────────────
async function handleCloudflareWorker() {
  console.log(`\n${C.bold}${C.green}--- ⚡ Option 4: Cloudflare Worker Deployment ---${C.reset}\n`);
  console.log(`Deploy the complete JTBS streaming application to Cloudflare's Edge Network`);
  console.log(`with zero origin server maintenance and worldwide low-latency caching.\n`);

  const cfDir = path.join(JTBS_LIVE_DIR, 'cloudflare-worker');
  if (!fs.existsSync(cfDir)) {
    console.log(`${C.red}Error: cloudflare-worker directory not found.${C.reset}`);
    await pause();
    return;
  }

  const deploy = await prompt(`Deploy using npx wrangler deploy now? (y/n):`);
  if (deploy.toLowerCase() === 'y') {
    try {
      execSync('npx wrangler deploy', { cwd: cfDir, stdio: 'inherit' });
      console.log(`\n${C.green}✅ Cloudflare Worker deployed successfully!${C.reset}`);
    } catch (e) {
      console.log(`\n${C.red}Wrangler deploy failed: ${e.message}${C.reset}`);
    }
  }

  await pause();
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. CONFIGURE FIREBASE KEYS & PROJECT ID
// ─────────────────────────────────────────────────────────────────────────────
async function configureFirebaseKeys() {
  console.log(`\n${C.bold}${C.yellow}--- 🔑 Firebase Credentials Configuration ---${C.reset}\n`);
  console.log(`Obtain these values from Firebase Console -> Project Settings -> General -> "Your apps" (Web app).\n`);

  const apiKey = await prompt(`Firebase apiKey [e.g. AIzaSy...]:`);
  const authDomain = await prompt(`Firebase authDomain [e.g. my-app.firebaseapp.com]:`);
  const projectId = await prompt(`Firebase projectId [e.g. my-app]:`);
  const storageBucket = await prompt(`Firebase storageBucket [e.g. my-app.appspot.com]:`);
  const messagingSenderId = await prompt(`Firebase messagingSenderId [e.g. 123456789]:`);
  const appId = await prompt(`Firebase appId [e.g. 1:12345:web:abcdef]:`);

  if (!apiKey || !projectId) {
    console.log(`${C.yellow}Configuration skipped or incomplete.${C.reset}`);
    return;
  }

  // Update .firebaserc
  updateFirebaseRc(projectId);

  const configSnippet = `const firebaseConfig = {
      apiKey: "${apiKey}",
      authDomain: "${authDomain}",
      projectId: "${projectId}",
      storageBucket: "${storageBucket}",
      messagingSenderId: "${messagingSenderId}",
      appId: "${appId}"
    };`;

  // Inject or update in index.html & admin.html
  [INDEX_HTML_PATH, ADMIN_HTML_PATH].forEach((filePath) => {
    if (fs.existsSync(filePath)) {
      let content = fs.readFileSync(filePath, 'utf8');
      const regex = /const firebaseConfig\s*=\s*\{[\s\S]*?\};/;
      if (regex.test(content)) {
        content = content.replace(regex, configSnippet);
        fs.writeFileSync(filePath, content, 'utf8');
        console.log(`${C.green}✅ Updated Firebase config in ${path.basename(filePath)}${C.reset}`);
      }
    }
  });

  console.log(`\n${C.green}✅ Firebase credentials configured successfully!${C.reset}\n`);
}

function updateFirebaseRc(projectId) {
  try {
    const rc = { projects: { default: projectId } };
    fs.writeFileSync(FIREBASE_RC_PATH, JSON.stringify(rc, null, 2), 'utf8');
    console.log(`${C.green}✅ Updated jtbs-live/.firebaserc with project: ${projectId}${C.reset}`);
  } catch (e) {
    console.log(`${C.red}Error writing .firebaserc: ${e.message}${C.reset}`);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. SUPERADMIN ACCOUNT SEEDING
// ─────────────────────────────────────────────────────────────────────────────
async function handleSuperadmin() {
  console.log(`\n${C.bold}${C.magenta}--- 👑 Option 6: Superadmin Account Setup ---${C.reset}\n`);
  console.log(`To grant yourself superadmin permissions in Firebase:`);
  console.log(`  1. Download your Service Account Key JSON from Firebase Console:`);
  console.log(`     Project Settings -> Service accounts -> "Generate new private key"`);
  console.log(`  2. Save it as: ${C.cyan}jtbs-live/serviceAccountKey.json${C.reset}\n`);

  const keyPath = path.join(JTBS_LIVE_DIR, 'serviceAccountKey.json');
  if (!fs.existsSync(keyPath)) {
    console.log(`${C.yellow}⚠️ serviceAccountKey.json not detected in jtbs-live/ yet.${C.reset}`);
    console.log(`Please place the file and rerun this option.\n`);
    await pause();
    return;
  }

  const email = await prompt(`Enter the email address of the account to grant Superadmin access:`);
  if (!email) {
    await pause();
    return;
  }

  const scriptPath = path.join(JTBS_LIVE_DIR, 'run-once-superadmin.js');
  if (fs.existsSync(scriptPath)) {
    let scriptContent = fs.readFileSync(scriptPath, 'utf8');
    scriptContent = scriptContent.replace(/const YOUR_EMAIL\s*=\s*'.*?';/, `const YOUR_EMAIL = '${email}';`);
    fs.writeFileSync(scriptPath, scriptContent, 'utf8');

    console.log(`\n${C.blue}Running superadmin grant script...${C.reset}`);
    try {
      execSync('node run-once-superadmin.js', { cwd: JTBS_LIVE_DIR, stdio: 'inherit' });
      console.log(`\n${C.green}✅ Superadmin claims successfully applied!${C.reset}`);
      console.log(`${C.yellow}Remember to delete jtbs-live/serviceAccountKey.json now to protect your keys.${C.reset}`);
    } catch (e) {
      console.log(`\n${C.red}Failed to apply superadmin claim: ${e.message}${C.reset}`);
    }
  }

  await pause();
}

// ─────────────────────────────────────────────────────────────────────────────
// 7. VIEW DOCUMENTATION
// ─────────────────────────────────────────────────────────────────────────────
async function handleDocs() {
  console.log(`\n${C.bold}${C.cyan}--- 📖 JTBS Documentation & Guides ---${C.reset}\n`);
  console.log(`Available Guides in this repository:`);
  console.log(`  • ${C.bold}README.md${C.reset}            - Main project overview, quick start & channel details`);
  console.log(`  • ${C.bold}HOSTING.md${C.reset}           - Step-by-step instructions for all 4 hosting platforms`);
  console.log(`  • ${C.bold}jtbs-live/SETUP.md${C.reset}   - Detailed Firebase provisioning & daily admin operations`);
  console.log(`  • ${C.bold}project-context.md${C.reset}   - Complete Firestore database schemas & system architecture`);
  console.log(`  • ${C.bold}jtbs-apk/README.md${C.reset}   - Android TV hardware decoder setup & compilation guide\n`);
  await pause();
}

// ─────────────────────────────────────────────────────────────────────────────
// MAIN EVENT LOOP
// ─────────────────────────────────────────────────────────────────────────────
async function main() {
  while (true) {
    clearScreen();
    printHeader();

    console.log(`${C.bold}Please select a hosting or setup option:${C.reset}\n`);
    console.log(`  ${C.green}[1] 🚀 Full Firebase Serverless Setup${C.reset}       (Edge CDN + Cloud Functions RBAC)`);
    console.log(`  ${C.cyan}[2] 💻 Local / VPS Preview Server${C.reset}          (Instant HTTP server on port 5000)`);
    console.log(`  ${C.blue}[3] 🐳 Docker Container Hosting${C.reset}            (1-command docker compose on port 8080)`);
    console.log(`  ${C.yellow}[4] ⚡ Cloudflare Worker Deployment${C.reset}        (Global Edge Worker deployment)`);
    console.log(`  ${C.magenta}[5] 🔑 Configure Firebase API Keys & ID${C.reset}     (Interactive web app credentials)`);
    console.log(`  ${C.green}[6] 👑 Superadmin Account Setup${C.reset}             (Assign superadmin role via service key)`);
    console.log(`  ${C.dim}[7] 📖 View Documentation & Guides${C.reset}`);
    console.log(`  ${C.red}[0] 🚪 Exit${C.reset}\n`);

    const choice = await prompt(`Enter selection [0-7]:`);

    switch (choice) {
      case '1': await handleFirebaseSetup(); break;
      case '2': await handleLocalServer(); break;
      case '3': await handleDocker(); break;
      case '4': await handleCloudflareWorker(); break;
      case '5': await configureFirebaseKeys(); await pause(); break;
      case '6': await handleSuperadmin(); break;
      case '7': await handleDocs(); break;
      case '0':
        console.log(`\n${C.green}Happy broadcasting! Goodbye.${C.reset}\n`);
        process.exit(0);
      default:
        console.log(`\n${C.red}Invalid selection.${C.reset}`);
        await pause();
        break;
    }
  }
}

main().catch(err => {
  console.error(`\n${C.red}Unexpected error: ${err.message}${C.reset}`);
  process.exit(1);
});
