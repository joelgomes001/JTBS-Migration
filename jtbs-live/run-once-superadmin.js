const admin = require('firebase-admin');
const serviceAccount = require('./serviceAccountKey.json');

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount)
});

// ← PUT YOUR OWN GMAIL HERE
const YOUR_EMAIL = 'you@gmail.com';

admin.auth().getUserByEmail(YOUR_EMAIL)
  .then(user => {
    return admin.auth().setCustomUserClaims(user.uid, {
      superadmin: true,
      admin: true
    });
  })
  .then(() => {
    console.log('✅ Superadmin set for', YOUR_EMAIL);
    process.exit(0);
  })
  .catch(err => {
    console.error('❌ Error:', err.message);
    process.exit(1);
  });