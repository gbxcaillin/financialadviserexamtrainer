// Exercises shared/index.html in three contexts: plain web, a mocked Android
// window.AppBridge, and a mocked iOS webkit message handler. Run: npm test

const { chromium } = require('playwright');
const path = require('path').resolve(__dirname, '../shared/index.html');
const SHOTS = process.env.SHOTS; // optional screenshot directory
let failures = 0;
function check(cond, msg) { console.log((cond ? 'PASS ' : 'FAIL ') + msg); if (!cond) failures++; }

async function newPage(browser, init) {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error' && !m.text().includes('Failed to load resource')) errors.push(m.text()); });
  if (init) await page.addInitScript(init);
  // Google Fonts are not needed for the logic tests
  await page.route(/fonts\.(googleapis|gstatic)\.com/, r => r.abort());
  await page.goto('file://' + path);
  await page.waitForTimeout(400);
  return { page, errors, ctx };
}

(async () => {
  const browser = await chromium.launch();

  // ── Web / free tier ──
  {
    const { page, errors } = await newPage(browser);
    const info = await page.evaluate(() => ({
      total: ALL_QUESTIONS.length, unlocked: isUnlocked(), free: buildFreePool().length,
      freeSections: new Set(buildFreePool().map(q => q.section)).size,
      freeHasScenario: buildFreePool().some(q => q.pool === 'Scenario'),
      sessionInFree: pSession.every(q => buildFreePool().includes(q)), sessionLen: pSession.length,
      qcount: document.getElementById('home-q-count').textContent,
      live: document.getElementById('live-label').textContent,
      access: document.getElementById('access-status').textContent,
    }));
    console.log(JSON.stringify(info));
    check(info.total === 1062, 'all 1,062 questions loaded');
    check(!info.unlocked, 'web starts locked');
    check(info.free === 50 && !info.freeHasScenario, 'free pool = 50 non-scenario questions');
    check(info.sessionInFree && info.sessionLen === 20, 'initial practice session drawn from free pool');
    if (SHOTS) await page.screenshot({ path: SHOTS + '/home.png' });

    // Exhaust the free pool and confirm sessions stay in it
    const stays = await page.evaluate(() => { for (let i = 0; i < 5; i++) nextPracticeSession(); return pSession.every(q => buildFreePool().includes(q)); });
    check(stays, 'repeat sessions stay within free pool');

    // Timed exam is gated
    await page.evaluate(() => switchTab('exam'));
    await page.click('text=Begin Exam');
    check(await page.isVisible('#paywall-overlay'), 'Begin Exam shows paywall when locked');
    check(!(await page.isVisible('#ob-overlay.show')), 'open-book splash not shown when locked');
    if (SHOTS) await page.screenshot({ path: SHOTS + '/paywall.png' });

    // Section practice is gated
    await page.click('text=Continue with free tier');
    check(!(await page.isVisible('#paywall-overlay')), 'continue with free tier closes paywall');
    await page.evaluate(() => switchTab('sections'));
    await page.locator('.sec-chip').first().click();
    await page.click('#sec-start-btn');
    check(await page.isVisible('#paywall-overlay'), 'section practice shows paywall when locked');

    // Dev unlock (file:// counts as dev context) resumes the pending section session
    await page.click('text=Watch an ad');
    await page.waitForTimeout(200);
    const after = await page.evaluate(() => ({
      unlocked: isUnlocked(), overlay: document.getElementById('paywall-overlay').classList.contains('show'),
      practiceActive: document.getElementById('panel-practice').classList.contains('active'),
      access: document.getElementById('access-status').textContent,
      outsideFree: pPool.some(q => !buildFreePool().includes(q)),
    }));
    console.log(JSON.stringify(after));
    check(after.unlocked && !after.overlay, 'ad unlock grants access and closes paywall');
    check(after.practiceActive, 'pending section practice resumes after unlock');
    check(after.outsideFree, 'unlocked practice pool uses full bank');
    await page.evaluate(() => switchTab('exam'));
    await page.click('text=Begin Exam');
    check(await page.isVisible('#ob-overlay.show'), 'Begin Exam proceeds to open-book splash after unlock');
    check(errors.length === 0, 'no JS errors (web): ' + errors.join(' | '));
  }

  // ── Android bridge mock ──
  {
    const { page, errors } = await newPage(browser, () => {
      window.__calls = [];
      window.AppBridge = {
        _unlocked: false,
        isUnlocked() { return this._unlocked; },
        ready() { window.__calls.push(['ready']); },
        showRewardedAd() { window.__calls.push(['showRewardedAd', arguments.length]); },
        showInterstitialAd() { window.__calls.push(['showInterstitialAd']); },
        purchaseProduct(id) { window.__calls.push(['purchaseProduct', id, arguments.length]); },
        restorePurchases() { window.__calls.push(['restorePurchases']); },
      };
    });
    await page.evaluate(() => { requestAdUnlock(); requestIAPUnlock('fasea_lifetime'); requestIAPUnlock('bogus'); });
    let calls = await page.evaluate(() => window.__calls);
    console.log(JSON.stringify(calls));
    check(calls[0][0] === 'ready', 'android: ready() sent on load');
    check(calls.some(c => c[0] === 'showRewardedAd' && c[1] === 0), 'android: showRewardedAd called with no args');
    check(calls.some(c => c[0] === 'purchaseProduct' && c[1] === 'fasea_lifetime' && c[2] === 1), 'android: purchaseProduct(id)');
    check(!calls.some(c => c[1] === 'bogus'), 'android: unknown product ignored');
    check(await page.evaluate(() => document.documentElement.classList.contains('platform-android')), 'android: platform class set');
    // finish a free session → interstitial requested
    await page.evaluate(() => { pIdx = pSession.length - 1; pAnswered = {}; pSession.forEach((q, i) => pAnswered[i] = 0); renderSessionComplete(); });
    calls = await page.evaluate(() => window.__calls);
    check(calls.some(c => c[0] === 'showInterstitialAd'), 'android: interstitial requested after free session');
    // native purchase completes
    await page.evaluate(() => { AppBridge._unlocked = true; onIAPComplete('fasea_lifetime'); onProductsLoaded({ fasea_monthly: 'A$9.99' }); });
    const st = await page.evaluate(() => ({ u: isUnlocked(), s: document.getElementById('access-status').textContent, b: document.getElementById('pw-btn-fasea_monthly').textContent }));
    console.log(JSON.stringify(st));
    check(st.u && st.s === 'Full access', 'android: purchase reflected in settings');
    check(st.b.includes('A$9.99'), 'android: localised price shown');
    check(errors.length === 0, 'no JS errors (android): ' + errors.join(' | '));
  }

  // ── iOS bridge mock ──
  {
    const { page, errors } = await newPage(browser, () => {
      window.__msgs = [];
      window.webkit = { messageHandlers: { AppBridge: { postMessage(m) { window.__msgs.push(m); } } } };
      window.__FASEA_ENTITLEMENT__ = { purchased: false, productId: null, adUnlockExpires: 0 };
      window.__FASEA_APP_VERSION__ = 'ios-1.3 (4)';
    });
    await page.evaluate(() => { requestIAPUnlock('fasea_monthly'); switchTab('exam'); });
    await page.click('text=Begin Exam');
    const msgs = await page.evaluate(() => window.__msgs);
    console.log(JSON.stringify(msgs));
    check(msgs[0].action === 'ready', 'ios: ready message sent');
    check(msgs.some(m => m.action === 'purchaseProduct' && m.productId === 'fasea_monthly'), 'ios: purchaseProduct message');
    check(await page.isVisible('#paywall-overlay'), 'ios: exam gated');
    await page.evaluate(() => onEntitlementChanged({ purchased: false, adUnlockExpires: Date.now() + 86400000 }));
    await page.evaluate(() => grantAdUnlock(Date.now() + 86400000));
    const st = await page.evaluate(() => ({ u: isUnlocked(), ob: document.getElementById('ob-overlay').classList.contains('show'), v: appVersion() }));
    console.log(JSON.stringify(st));
    check(st.u && st.ob, 'ios: ad unlock resumes pending exam');
    check(st.v === 'ios-1.3 (4)', 'ios: injected version used');
    await page.evaluate(() => onEntitlementChanged({ purchased: false, adUnlockExpires: Date.now() - 1 }));
    check(!(await page.evaluate(() => isUnlocked())), 'ios: expired ad unlock relocks');
    check(errors.length === 0, 'no JS errors (ios): ' + errors.join(' | '));
  }

  await browser.close();
  console.log(failures ? failures + ' FAILURE(S)' : 'ALL PASSED');
  process.exit(failures ? 1 : 0);
})();
