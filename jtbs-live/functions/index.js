const functions = require('firebase-functions');
const admin     = require('firebase-admin');
admin.initializeApp();

// ── setAdminClaim ─────────────────────────────────────────────
exports.setAdminClaim = functions.https.onCall(async (data, context) => {
  if (!context.auth || context.auth.token.role !== 'superadmin')
    throw new functions.https.HttpsError('permission-denied','Superadmins only.');
  const { uid, role } = data;
  if (!uid || !role) throw new functions.https.HttpsError('invalid-argument','uid and role required.');
  if (!['admin','superadmin'].includes(role)) throw new functions.https.HttpsError('invalid-argument','Invalid role.');
  await admin.auth().setCustomUserClaims(uid, { role });
  return { success: true };
});

// ── createAdminUser ───────────────────────────────────────────
exports.createAdminUser = functions.https.onCall(async (data, context) => {
  if (!context.auth || context.auth.token.role !== 'superadmin')
    throw new functions.https.HttpsError('permission-denied','Superadmins only.');
  const { email, password, displayName, role } = data;
  if (!email || !password || !displayName || !role)
    throw new functions.https.HttpsError('invalid-argument','All fields required.');
  if (!['admin','superadmin'].includes(role))
    throw new functions.https.HttpsError('invalid-argument','Invalid role.');
  if (password.length < 8)
    throw new functions.https.HttpsError('invalid-argument','Password too short.');

  let user;
  try {
    user = await admin.auth().createUser({ email, password, displayName, emailVerified: true });
  } catch (err) {
    console.error("Error creating user in Auth:", err);
    throw new functions.https.HttpsError('already-exists', err.message || 'User already exists.');
  }

  try {
    await admin.auth().setCustomUserClaims(user.uid, { role });
    await admin.firestore().doc(`admins/${user.uid}`).set({
      uid: user.uid, email, displayName, role,
      linkedGoogle: false,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      createdBy: context.auth.uid
    });
  } catch (err) {
    console.error("Error setting custom claims or firestore doc:", err);
    try {
      await admin.auth().deleteUser(user.uid);
    } catch (cleanupErr) {
      console.error("Failed to clean up created user:", cleanupErr);
    }
    throw new functions.https.HttpsError('internal', err.message || 'Failed to complete admin creation.');
  }
  return { success: true, uid: user.uid };
});

// ── deleteAdminUser ───────────────────────────────────────────
exports.deleteAdminUser = functions.https.onCall(async (data, context) => {
  if (!context.auth || context.auth.token.role !== 'superadmin')
    throw new functions.https.HttpsError('permission-denied','Superadmins only.');
  const { uid } = data;
  if (!uid) throw new functions.https.HttpsError('invalid-argument','uid required.');
  if (uid === context.auth.uid)
    throw new functions.https.HttpsError('failed-precondition','Cannot delete your own account.');

  const snap = await admin.firestore().collection('admins').where('role','==','superadmin').get();
  const target = await admin.firestore().doc(`admins/${uid}`).get();
  if (target.exists && target.data().role === 'superadmin' && snap.size <= 1)
    throw new functions.https.HttpsError('failed-precondition','Cannot delete last superadmin.');

  try {
    await admin.auth().deleteUser(uid);
  } catch (err) {
    console.error("Error deleting user from Auth:", err);
  }

  try {
    await admin.firestore().doc(`admins/${uid}`).delete();
  } catch (err) {
    console.error("Error deleting document from firestore:", err);
    throw new functions.https.HttpsError('internal', err.message || 'Failed to delete admin document.');
  }
  return { success: true };
});

// ── liveStream (IPTV redirect endpoint) ──────────────────────
// Public HTTP GET endpoint that redirects to the current live stream URL.
// Usage: https://jtbs-classic.web.app/live.m3u8  or  /live
// Compatible with VLC, TiviMate, Kodi, IPTV Smarters, ExoPlayer, FFmpeg, etc.
exports.liveStream = functions.https.onRequest(async (req, res) => {
  // Allow GET and HEAD only
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.set('Allow', 'GET, HEAD');
    return res.status(405).send('Method Not Allowed');
  }

  // CORS headers for broad IPTV player compatibility
  res.set('Access-Control-Allow-Origin', '*');
  res.set('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS');
  res.set('Access-Control-Allow-Headers', '*');

  if (req.method === 'OPTIONS') {
    return res.status(204).send('');
  }

  try {
    const snap = await admin.firestore().doc('streamState/main').get();

    if (!snap.exists) {
      res.set('Cache-Control', 'no-cache, no-store, must-revalidate');
      return res.status(503).type('text/plain').send('JTBS Classic — Channel is currently off air.');
    }

    const data = snap.data();

    if (!data.isLive || !data.streamUrl) {
      res.set('Cache-Control', 'no-cache, no-store, must-revalidate');
      return res.status(503).type('text/plain').send('JTBS Classic — Channel is currently off air.');
    }

    // Redirect to the actual stream URL
    // no-cache ensures IPTV players always check for the latest stream URL
    res.set('Cache-Control', 'no-cache, no-store, must-revalidate');
    return res.redirect(302, data.streamUrl);
  } catch (err) {
    console.error('liveStream error:', err);
    return res.status(500).type('text/plain').send('Internal server error.');
  }
});

exports.forceUpdateStream = functions.https.onRequest(async (req, res) => {
  const urlParam = req.query.url || 'http://video1.mayapur.tv/allTemples/MayapurTV/MayapurTVHD/manifest.mpd';
  try {
    const payload = {
      isLive: true,
      streamUrl: urlParam,
      streamType: 'dash',
      playMode: 'live',
      mp4PlayMode: 'repeat',
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    };
    await admin.firestore().doc('streamState/main').set(payload, { merge: true });
    for (let i = 1; i <= 6; i++) {
      await admin.firestore().doc(`streamState/feed${i}`).set(payload, { merge: true });
    }
    return res.status(500).send('Error updating streamState: ' + err.message);
  }
});

exports.getStreamState = functions.https.onRequest(async (req, res) => {
  res.set('Access-Control-Allow-Origin', '*');
  const docName = req.query.doc || 'main';
  try {
    const snap = await admin.firestore().doc(`streamState/${docName}`).get();
    if (snap.exists && snap.data().streamUrl) {
      return res.status(200).json(snap.data());
    }
    const mainSnap = await admin.firestore().doc('streamState/main').get();
    return res.status(200).json(mainSnap.exists ? mainSnap.data() : { isLive: false });
  } catch (err) {
    return res.status(500).json({ error: err.message });
  }
});
