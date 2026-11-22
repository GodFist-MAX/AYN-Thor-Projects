// Runs on the site's pages in the login overlay: opens its "Log in with AniList" once, and hands the
// AniList token the site keeps after logging in to AniDeck. Nothing is typed for the user.
(function (nonce, open) {
  const B = window.AniDeckLogin;
  if (!B || window.__anideckLogin) return;
  window.__anideckLogin = true;
  let sent = '';

  function scanToken() {
    const re = /eyJ[\w-]{8,}\.eyJ[\w-]{8,}\.[\w-]{8,}/g;
    let best = '';
    let bestExp = 0;
    const look = (text) => {
      for (const t of (text || '').match(re) || []) {
        try {
          const c = JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
          if (c.sub && c.aud && c.exp * 1000 > Date.now() && c.exp > bestExp) {
            best = t;
            bestExp = c.exp;
          }
        } catch (e) {}
      }
    };
    for (const store of [localStorage, sessionStorage]) {
      try {
        for (let i = 0; i < store.length; i++) look(store.getItem(store.key(i)));
      } catch (e) {}
    }
    try {
      look(document.cookie);
    } catch (e) {}
    if (best && best !== sent) {
      sent = best;
      B.token(nonce, best);
    }
  }

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const byLabel = (label) => document.querySelector(`[aria-label="${label}"], [title="${label}"]`);

  async function openLogin() {
    for (let i = 0; i < 20 && !byLabel('Profile menu'); i++) await sleep(400);
    const menu = byLabel('Profile menu');
    if (!menu) {
      B.notice(nonce, 'Tap the profile button at the top right, then Log in with AniList');
      return;
    }
    menu.click();
    await sleep(700);
    const item = [...document.querySelectorAll('a, button')].find((x) => /^Log in with/i.test(x.textContent.trim()));
    if (item) item.click();
    else if (!sent) B.notice(nonce, 'Tap the profile button at the top right, then Log in with AniList');
  }

  scanToken();
  setInterval(scanToken, 1000);
  if (open && !sent && location.pathname !== '/callback') openLogin();
});
