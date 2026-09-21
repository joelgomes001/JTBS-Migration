const puppeteer = require('puppeteer');

(async () => {
  console.log('Starting Puppeteer test...');
  const browser = await puppeteer.launch({
    executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    headless: "new",
    args: ['--no-sandbox', '--disable-setuid-sandbox']
  });
  const page = await browser.newPage();
  
  // Set viewport size
  await page.setViewport({ width: 1280, height: 800 });

  // Handle page logs
  page.on('console', msg => console.log('BROWSER LOG:', msg.text()));
  page.on('requestfailed', request => {
    console.log('REQUEST FAILED:', request.url(), request.failure() ? request.failure().errorText : 'unknown');
  });
  page.on('response', response => {
    if (response.status() >= 400) {
      console.log('RESPONSE FAILED:', response.status(), response.url());
    }
  });

  try {
    console.log('Navigating to admin page...');
    await page.goto('https://jtbs-classic.web.app/admin.html', { waitUntil: 'networkidle2', timeout: 30000 });

    // Wait for login form to be fully ready
    await page.waitForSelector('#login-btn', { visible: true, timeout: 10000 });
    await new Promise(resolve => setTimeout(resolve, 2000)); // let Firebase SDK init

    console.log('Logging in...');
    await page.type('#login-email', 'joel.s.gomes001@gmail.com');
    await page.type('#login-pass', '12345678');
    await page.click('#login-btn');

    console.log('Waiting for dashboard to load (30s timeout)...');
    
    // Take diagnostic screenshot 5s after login click
    await new Promise(resolve => setTimeout(resolve, 5000));
    await page.screenshot({ path: 'post_login_screenshot.png' });
    console.log('Post-login screenshot saved.');
    
    // Check for any error messages on the page
    const pageState = await page.evaluate(() => {
      const loginErr = document.getElementById('login-error');
      const loginBtn = document.getElementById('login-btn');
      const dashboard = document.getElementById('dashboard');
      const loginPanel = document.getElementById('login-panel');
      return {
        loginError: loginErr ? loginErr.textContent.trim() : null,
        loginErrorDisplay: loginErr ? window.getComputedStyle(loginErr).display : null,
        loginBtnDisabled: loginBtn ? loginBtn.disabled : null,
        dashboardDisplay: dashboard ? window.getComputedStyle(dashboard).display : null,
        loginPanelDisplay: loginPanel ? window.getComputedStyle(loginPanel).display : null,
        bodyClasses: document.body.className
      };
    });
    console.log('Page state after login:', JSON.stringify(pageState));

    await page.waitForFunction(
      () => {
        // Check for login error
        const errEl = document.getElementById('login-error');
        if (errEl && errEl.textContent.trim()) {
          throw new Error('Login error: ' + errEl.textContent.trim());
        }
        const sameEl = document.getElementById('stream-url-input');
        const pubEl = document.getElementById('public-url-input');
        const isVisible = (el) => el && el.getBoundingClientRect().width > 0 && el.getBoundingClientRect().height > 0;
        return isVisible(sameEl) || isVisible(pubEl);
      },
      { timeout: 30000 }
    );
    console.log('Logged in successfully!');

    // Detect if we are in "Different Streams" mode (meaning #public-url-input is visible)
    const isDifferentMode = await page.evaluate(() => {
      const publicEl = document.getElementById('public-url-input');
      return publicEl && window.getComputedStyle(publicEl).display !== 'none';
    });

    const streamUrl = 'https://media.nazuna.dpdns.org/api/files/stream?path=%2Fmedia%2Fstorage%2Fanime%2Fmovie%2F%5BLYS1TH3A%5D%20Josee%20the%20Tiger%20and%20the%20Fish%20-%2001%20(1080p).mkv.mp4&token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpZCI6MSwidXNlcm5hbWUiOiJzYWt1cmEiLCJyb2xlIjoiYWRtaW4iLCJleHAiOjE3ODQwMDUwOTB9.6MofEBhGdhitwnVrut9JMrSmNiBJoJXHdq_4l6-0GKA';
    
    let urlInputSelector, saveBtnSelector, saveMsgSelector, playModeName;
    if (isDifferentMode) {
      console.log('Detected: DIFFERENT STREAMS mode.');
      urlInputSelector = '#public-url-input';
      saveBtnSelector = '#public-go-live-btn';
      saveMsgSelector = '#public-live-save-msg';
      playModeName = 'public-yt-play-mode';
    } else {
      console.log('Detected: SAME STREAM mode.');
      urlInputSelector = '#stream-url-input';
      saveBtnSelector = '#go-live-btn';
      saveMsgSelector = '#live-save-msg';
      playModeName = 'yt-play-mode';
    }

    console.log(`Setting stream URL in ${urlInputSelector}...`);
    await page.click(urlInputSelector, { clickCount: 3 });
    await page.keyboard.press('Backspace');
    await page.type(urlInputSelector, streamUrl);

    console.log('Verifying MP4 options container is visible...');
    const mp4ContainerSelector = isDifferentMode ? '#public-mp4-options-container' : '#mp4-options-container';
    await page.waitForSelector(mp4ContainerSelector, { visible: true, timeout: 5000 });

    console.log('Selecting MP4 Repeat play mode...');
    const mp4PlayModeName = isDifferentMode ? 'public-mp4-play-mode' : 'mp4-play-mode';
    await page.evaluate((mp4PlayModeName) => {
      const radio = document.querySelector(`input[name="${mp4PlayModeName}"][value="repeat"]`);
      if (radio) {
        radio.checked = true;
        radio.dispatchEvent(new Event('change', { bubbles: true }));
      }
    }, mp4PlayModeName);

    console.log(`Clicking Go Live (${saveBtnSelector})...`);
    await page.click(saveBtnSelector);

    console.log('Waiting for Firestore save confirmation...');
    await page.waitForFunction(
      (msgId) => {
        const el = document.getElementById(msgId);
        return el && el.textContent.includes('✓');
      },
      { timeout: 15000 },
      saveMsgSelector.substring(1)
    );

    const msg = await page.$eval(saveMsgSelector, el => el.textContent);
    console.log('Save result message:', msg);

    // Verify elapsed timer is ticking on admin page
    const timerSelector = isDifferentMode ? '#public-mp4-elapsed-timer' : '#mp4-elapsed-timer';
    console.log(`Checking admin elapsed timer using ${timerSelector}...`);
    const timerVal1 = await page.$eval(timerSelector, el => el.textContent);
    console.log('Timer initial value:', timerVal1);
    
    await new Promise(resolve => setTimeout(resolve, 3000));
    
    const timerVal2 = await page.$eval(timerSelector, el => el.textContent);
    console.log('Timer value after 3s:', timerVal2);
    if (timerVal1 !== timerVal2 && timerVal2 !== '00:00:00 / 00:00:00') {
      console.log('Success: Admin elapsed timer is ticking!');
    } else {
      console.warn('Warning: Admin elapsed timer did not change or is zero.', timerVal1, timerVal2);
    }

    // Let's navigate to the viewer page to check if it plays and seeks
    console.log('Navigating to viewer page...');
    const viewerPage = await browser.newPage();
    await viewerPage.setViewport({ width: 1280, height: 800 });
    viewerPage.on('console', msg => console.log('VIEWER LOG:', msg.text()));

    await viewerPage.goto('https://jtbs-classic.web.app/', { waitUntil: 'load' });

    console.log('Waiting 10 seconds for player to prepare and seek...');
    await new Promise(resolve => setTimeout(resolve, 10000));

    // Get current playback status of video element
    const status = await viewerPage.evaluate(() => {
      const video = document.getElementById('video-el');
      if (!video) return { error: 'No video-el found' };
      return {
        src: video.src,
        currentTime: video.currentTime,
        duration: video.duration,
        paused: video.paused,
        readyState: video.readyState
      };
    });

    console.log('Viewer video status:', status);
    
    // Take a screenshot
    console.log('Taking screenshot of viewer...');
    await viewerPage.screenshot({ path: 'viewer_screenshot.png' });
    console.log('Screenshot saved to viewer_screenshot.png');

  } catch (err) {
    console.error('Error during test execution:', err);
    // Take an error screenshot
    try {
      await page.screenshot({ path: 'error_screenshot.png' });
      console.log('Error screenshot saved to error_screenshot.png');
    } catch (e) {}
  } finally {
    await browser.close();
    console.log('Browser closed. Test finished.');
  }
})();
