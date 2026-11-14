// Runs in the site's main frame; MainActivity evaluates it on every page load with a secret that
// proves bridge calls come from here, and with AniNav (nav.js). It reports the page and player to
// the app, carries out commands from the controller and the bottom screen, and draws the
// controller's highlight. The player is the site's <strmcx-embed> element: only its public
// methods and events are used, never its stream or download links.
(nonce, Nav) => {
  'use strict';
  // The Thor's WebView (Chrome 109) lacks AbortSignal.any, which the site's health check uses;
  // without it the site says "Server unreachable" while the server is fine.
  if (!AbortSignal.any) {
    AbortSignal.any = (signals) => {
      const c = new AbortController();
      for (const s of signals) {
        if (s.aborted) {
          c.abort(s.reason);
          break;
        }
        s.addEventListener('abort', () => c.abort(s.reason), { once: true });
      }
      return c.signal;
    };
    // The site re-runs failed checks when the page regains focus.
    window.dispatchEvent(new Event('visibilitychange'));
  }
  const B = window.AniDeckTop;
  if (!B) return;
  if (window.__anideck) {
    window.__anideck.refresh();
    return;
  }
  const A = (window.__anideck = {});
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const MENUS = ['Server', 'Language'];
  let prefs = {};
  try {
    prefs = JSON.parse(B.prefs(nonce));
  } catch (e) {}

  let embed = null;
  let player = null;
  let theater = false;
  let theaterDeclined = false; // the user left fill-screen mode for this episode
  let playUntil = 0; // an episode picked from the app starts playing once it loads, until this time
  let expectUntil = 0; // playback should have started by this time (after a server or episode pick)
  let failing = null; // servers tried for the episode on screen: { loc, names }
  let switching = false;
  let lastLoc = location.pathname + location.search;
  let lastSent = '';
  let reported = '';
  let finding = null;
  let lastToken = '';
  const learned = {};

  // ---- page ----

  const page = () => {
    const p = location.pathname;
    if (p.startsWith('/watch/')) return 'watch';
    if (p.startsWith('/info/')) return 'info';
    if (p === '/' || p === '') return 'home';
    return p.split('/')[1];
  };
  const anilistId = () => {
    const a = document.querySelector('a[href*="anilist.co/anime/"]');
    const m = a && a.href.match(/anilist\.co\/anime\/(\d+)/);
    return m ? +m[1] : 0;
  };
  const episode = () => (page() === 'watch' ? +new URLSearchParams(location.search).get('ep') || 1 : 0);
  // The site's episode list buttons are labelled "EP 3: Title".
  const episodeButtons = () => [...document.querySelectorAll('button[aria-label^="EP "]')];
  const episodes = () => {
    const list = [];
    for (const b of episodeButtons()) {
      const m = b.getAttribute('aria-label').match(/^EP (\d+)(?::\s*(.*))?$/);
      if (m) list.push({ n: +m[1], title: m[2] || '' });
    }
    return list;
  };
  const showTitle = () => document.title.replace(/\s*·\s*Miruro.*$/i, '').replace(/^Watch\s+/i, '').trim();
  const button = (label) => document.querySelector(`button[aria-label="${label}"]`);
  const enabled = (label) => {
    const b = button(label);
    return !!b && !b.disabled;
  };
  const click = (label) => {
    const b = button(label);
    if (!b || b.disabled) return false;
    b.click();
    return true;
  };

  function state() {
    const pg = page();
    const show = pg === 'watch' || pg === 'info';
    const eps = pg === 'watch' ? episodes() : [];
    const ep = episode();
    const cur = eps.find((e) => e.n === ep);
    return {
      url: location.href,
      path: location.pathname,
      page: pg,
      show: show ? showTitle() : '',
      anilistId: show ? anilistId() : 0,
      ep,
      epTitle: cur ? cur.title : '',
      epCount: eps.reduce((m, e) => Math.max(m, e.n), 0),
      hasPrev: enabled('Previous Episode') || eps.some((e) => e.n < ep),
      hasNext: enabled('Next Episode') || eps.some((e) => e.n > ep),
      player: embed ? player : null,
      theater,
      menu: pg === 'watch' ? openMenu() : null,
      nav: ringOn && !!focused && focused.isConnected,
      typing: editable(document.activeElement),
      popup: !!popupEl(),
    };
  }

  // The site's Server or Language menu while it's open, so the bottom screen can show its choices.
  function openMenu() {
    const label = MENUS.find((l) => {
      const b = button(l);
      return b && b.getAttribute('aria-expanded') === 'true';
    });
    const list = label && document.querySelector('[role="listbox"]');
    if (!list) return null;
    const options = [...list.querySelectorAll('[role="option"]')].map((o) => ({
      parts: [...o.querySelectorAll('*')].filter((x) => !x.children.length && x.textContent.trim()).map((x) => x.textContent.trim()),
      active: o.getAttribute('aria-selected') === 'true',
      disabled: o.getAttribute('aria-disabled') === 'true',
    }));
    return { label, options };
  }

  function send() {
    const s = JSON.stringify(state());
    if (s === lastSent) return;
    lastSent = s;
    B.state(nonce, s);
  }

  // ---- player ----

  function hook() {
    const e = document.querySelector('strmcx-embed');
    if (e === embed) return;
    embed = e;
    reset('');
    if (!e) setTheater(false);
  }

  // A new episode: the site keeps the same <strmcx-embed> and gives it a new session.
  function reset(session) {
    player = embed ? { state: 'loading', t: 0, d: 0, error: '', session, at: Date.now() } : null;
    theaterDeclined = false;
  }

  // Session ids end in the episode number. A session left over from the previous episode
  // (before the new one reports in) must not be started.
  const sessionIsCurrent = () => {
    const m = /:(\d+)$/.exec(player.session || '');
    return !m || +m[1] === episode();
  };

  // An episode or server picked from the app starts as soon as the player takes it.
  function autoplayCheck() {
    if (!playUntil || !player || !embed) return;
    if (Date.now() > playUntil || player.state === 'playing') {
      playUntil = 0;
    } else if (player.state !== 'error' && sessionIsCurrent()) {
      start(embed);
    }
  }

  // The player remembers being muted; the Thor's volume buttons are the volume control here.
  function start(e) {
    e.setMuted(false);
    e.play();
  }

  // A server that fails often shows its error inside the player's frame without telling the page,
  // so a video that stops getting time updates, or never starts when it should, counts as stalled.
  function stallCheck() {
    if (!player) return;
    const now = Date.now();
    if (player.state === 'playing' && now - player.at > 8000) player.state = 'stalled';
    if (expectUntil && now > expectUntil) {
      expectUntil = 0;
      if (player.state !== 'playing' && player.state !== 'error') {
        player.state = 'stalled';
        failover(true);
      }
    }
  }

  // After a server fails, tries the site's next server for this episode (setting "autoServer").
  async function failover(play) {
    if (!prefs.autoServer || switching || page() !== 'watch') return;
    const b = button('Server');
    if (!b) return;
    const loc = location.pathname + location.search;
    if (!failing || failing.loc !== loc) failing = { loc, names: [] };
    switching = true;
    try {
      if (b.getAttribute('aria-expanded') !== 'true') {
        b.click();
        await sleep(350);
      }
      const opts = [...document.querySelectorAll('[role="listbox"] [role="option"]')];
      const name = (o) => o.textContent.trim();
      const cur = opts.find((o) => o.getAttribute('aria-selected') === 'true');
      if (cur && !failing.names.includes(name(cur))) failing.names.push(name(cur));
      const next = opts.find((o) => o.getAttribute('aria-disabled') !== 'true' && !failing.names.includes(name(o)));
      if (!next) {
        if (b.getAttribute('aria-expanded') === 'true') b.click();
        player.state = 'error';
        player.error = 'None of the servers could play this episode';
        player.allFailed = true;
        return;
      }
      failing.names.push(name(next));
      B.notice(nonce, "That server didn't work, so AniDeck is trying another one");
      if (play) autoplay();
      player.state = 'loading';
      player.error = '';
      player.allFailed = false;
      player.at = Date.now();
      next.click();
    } finally {
      switching = false;
      send();
    }
  }

  // The embed re-dispatches the player's messages as bubbling, composed events.
  const on = (type, f) =>
    document.addEventListener(
      type,
      (ev) => {
        if (!ev.target || ev.target.tagName !== 'STRMCX-EMBED') return;
        if (ev.target !== embed) hook();
        if (!player) return;
        f(ev.detail || {});
        send();
      },
      true
    );
  on('strmcx-time-update', (d) => {
    const t = +d.currentTime || 0;
    if (d.sessionId && d.sessionId !== player.session) {
      reset(d.sessionId);
      autoplayCheck();
    } else if ((player.state === 'loading' || player.state === 'stalled') && t > player.t && t - player.t < 3) {
      // State changes only arrive on change, so one sent before this session's first update is lost.
      player.state = 'playing';
    }
    if (player.state === 'playing') expectUntil = 0;
    player.t = t;
    player.d = +d.duration || 0;
    player.at = Date.now();
    watchedCheck();
  });
  on('strmcx-state-change', (d) => {
    player.state = d.state || player.state;
    player.at = Date.now();
    if (player.state === 'paused') expectUntil = 0;
    if (player.state !== 'playing') return;
    expectUntil = playUntil = 0;
    player.error = '';
    if (prefs.autoTheater && !theater && !theaterDeclined) setTheater(true);
  });
  on('strmcx-error', (d) => {
    const wanted = Date.now() < expectUntil;
    expectUntil = 0;
    player.state = 'error';
    player.error = [d.title, d.description].filter(Boolean).join('. ') || 'The video could not be played';
    failover(wanted);
  });

  function watchedCheck() {
    if (!player.d || player.t / player.d < 0.85) return;
    const id = anilistId();
    const ep = episode();
    const key = id + ':' + ep;
    if (!id || !ep || key === reported) return;
    reported = key;
    B.watched(nonce, id, ep);
  }

  // ---- fill-screen mode: the player covers the whole top screen ----

  function ensureStyle() {
    if (document.getElementById('anideck-style')) return;
    const s = document.createElement('style');
    s.id = 'anideck-style';
    // Ancestors with a transform, filter or z-index would trap the fixed player inside them.
    s.textContent = `
      html[data-anideck-theater], html[data-anideck-theater] body { overflow: hidden !important; }
      html[data-anideck-theater] :has(strmcx-embed) {
        transform: none !important; filter: none !important; backdrop-filter: none !important;
        contain: none !important; will-change: auto !important; z-index: auto !important; isolation: auto !important; }
      html[data-anideck-theater] strmcx-embed {
        position: fixed !important; inset: 0 !important; width: 100vw !important; height: 100vh !important;
        max-width: none !important; max-height: none !important; margin: 0 !important; border-radius: 0 !important;
        z-index: 2147483647 !important; background: #000 !important; }`;
    (document.head || document.documentElement).appendChild(s);
  }

  function setTheater(on) {
    theater = !!(on && embed);
    if (theater) {
      ensureStyle();
      document.documentElement.setAttribute('data-anideck-theater', '');
    } else {
      document.documentElement.removeAttribute('data-anideck-theater');
      if (ringOn && !ringLoop) ringLoop = requestAnimationFrame(follow);
    }
  }

  // ---- controller highlight: the d-pad moves it over the site's links and buttons ----

  const TARGETS = 'a[href], button, input:not([type="hidden"]), select, textarea, summary, [role="button"], [role="link"], ' +
    '[role="tab"], [role="option"], [role="menuitem"], [role="menuitemradio"], [role="menuitemcheckbox"], [role="switch"], ' +
    '[role="checkbox"], [role="radio"], [tabindex]:not([tabindex="-1"]), strmcx-embed';
  const POPUPS = '[role="dialog"], [role="menu"], [role="listbox"], [aria-modal="true"]';
  let focused = null; // the highlighted element
  let ringOn = false;
  let ring = null;
  let ringLoop = 0;
  let ringRadius = '12px';
  let homeEl = null; // highlighted before a menu opened; the highlight returns there when it closes
  let homeRect = null;
  let lastRect = null; // where the ring was last drawn
  let lastHref = ''; // the page it was put on
  let moves = 0; // d-pad presses
  let recovering = 0;

  const editable = (el) => !!el && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName));

  // The open menu or dialog, which holds the highlight until it closes.
  function popupEl() {
    const open = [...document.querySelectorAll(POPUPS)].filter((p) => p.querySelector(TARGETS) && Nav.visible(p));
    return open.length ? open[open.length - 1] : null;
  }

  // The bottom of the site's fixed top bar; what's under it can't be seen or tapped.
  function topBar() {
    for (let el = document.elementFromPoint(innerWidth / 2, 4); el && el !== document.body; el = el.parentElement) {
      const pos = getComputedStyle(el).position;
      if (pos === 'fixed' || pos === 'sticky') return Math.max(0, el.getBoundingClientRect().bottom);
    }
    return 0;
  }

  function covered(el) {
    const r = el.getBoundingClientRect();
    const x = (r.left + r.right) / 2, y = (r.top + r.bottom) / 2;
    if (x < 0 || x >= innerWidth || y < 0 || y >= innerHeight) return false;
    const hit = document.elementFromPoint(x, y);
    return !!hit && !el.contains(hit) && !hit.contains(el);
  }

  function targets() {
    const scope = popupEl() || document;
    return [...scope.querySelectorAll(TARGETS)].filter((el) =>
      !el.disabled && el.getAttribute('aria-disabled') !== 'true' && !el.closest('[aria-hidden="true"], [inert]') &&
      Nav.visible(el) && !covered(el));
  }

  // A d-pad press. The first one (or dir null) only shows the highlight.
  function move(dir) {
    if (dir) moves++;
    const els = targets();
    if (!els.length) return;
    const pop = popupEl();
    if (pop && focused && !pop.contains(focused)) {
      homeEl = focused;
      homeRect = focused.getBoundingClientRect();
      // Start on the current page's item (the site marks it with an "active" class) or the picked
      // choice, if it's in view; a list can open past the edge of the screen.
      const find = (sel) => els.find((el) => el.matches(sel) && Nav.onScreen(el));
      return highlight(find('[aria-current]:not([aria-current="false"]), [aria-selected="true"]')
        || find('[class~="active"], [class*="_active"], [class*="-active"]')
        || (pop.matches('[role="menu"], [role="listbox"]') && find('[aria-checked="true"]'))
        || Nav.first(els) || els[0]);
    }
    // targets() leaves out what's under the header, so this is a highlight that scrolled out of sight.
    if (!focused || !focused.isConnected || !els.includes(focused) || !Nav.onScreen(focused)) {
      return highlight(replacement(els));
    }
    if (!ringOn || !dir) return highlight(focused);
    const next = Nav.best(focused, els, dir);
    if (next) highlight(next);
    else if (dir === 'up' || dir === 'down') scrollBy({ top: (dir === 'down' ? 0.5 : -0.5) * innerHeight, behavior: 'smooth' });
  }

  // Where the highlight goes when its element is gone: back to the menu's button after a menu
  // closes, onto whatever is in the same spot (the site re-renders buttons), or on a new page
  // the episode's video, else the first item.
  function replacement(els) {
    const samePage = lastHref === location.href;
    // While the menu is open, the highlight stays in it and its button waits for it to close.
    if (popupEl()) return (samePage && near(els, lastRect)) || Nav.first(els) || els[0];
    const home = homeEl, rect = homeEl ? homeRect : samePage && lastRect;
    homeEl = homeRect = null;
    if (home && home.isConnected && els.includes(home)) return home;
    if (!samePage) {
      // A new page's video takes a few seconds to load; look for it until the user moves on.
      const n = moves;
      let tries = 12;
      const look = () => {
        if (!ringOn || moves !== n || !tries--) return;
        const e = video(targets());
        if (!e) setTimeout(look, 500);
        else if (e !== focused) highlight(e);
      };
      setTimeout(look, 500);
    }
    return near(els, rect) || video(els) || Nav.first(els, topBar()) || els[0];
  }

  const video = (els) => els.find((el) => el.tagName === 'STRMCX-EMBED' && Nav.onScreen(el));

  function near(els, r) {
    if (!r) return null;
    const x = (r.left + r.right) / 2, y = (r.top + r.bottom) / 2;
    let pick = null, low = 48;
    for (const el of els) {
      const b = el.getBoundingClientRect();
      const d = Math.hypot((b.left + b.right) / 2 - x, (b.top + b.bottom) / 2 - y);
      if (d < low) {
        low = d;
        pick = el;
      }
    }
    return pick;
  }

  function highlight(el) {
    if (!el) return;
    focused = el;
    ringOn = true;
    lastHref = location.href;
    ringRadius = (parseFloat(getComputedStyle(el).borderTopLeftRadius) || 6) + 6 + 'px';
    const r = el.getBoundingClientRect(), top = topBar();
    const vOut = r.top < top || r.bottom > innerHeight;
    if (vOut || r.left < 0 || r.right > innerWidth) {
      el.scrollIntoView({ block: vOut ? 'center' : 'nearest', inline: 'nearest', behavior: 'smooth' });
    }
    if (!ringLoop) ringLoop = requestAnimationFrame(follow);
    send();
  }

  function hideRing() {
    ringOn = false;
    if (ring) ring.style.display = 'none';
  }

  // Keeps the ring on the highlighted element while the page scrolls and moves.
  function follow() {
    ringLoop = 0;
    if (!ring || !ring.isConnected) {
      ring = document.createElement('div');
      ring.setAttribute('aria-hidden', 'true');
      ring.style.cssText = 'position:fixed;left:0;top:0;pointer-events:none;z-index:2147483646;box-sizing:border-box;display:none;' +
        'border:3px solid #7aa7ff;box-shadow:0 0 0 3px rgba(0,0,0,.6),0 0 24px rgba(122,167,255,.75)';
      document.documentElement.appendChild(ring);
    }
    if (!ringOn || theater || !focused || !focused.isConnected) {
      ring.style.display = 'none';
      if (ringOn && focused && !focused.isConnected) recover();
      return;
    }
    const r = focused.getBoundingClientRect();
    // Gone from view: hidden (a closed drawer slides off-screen) or scrolled away.
    if (r.width === 0 || r.bottom <= 0 || r.top >= innerHeight || r.right <= 0 || r.left >= innerWidth) recover();
    else lastRect = r;
    const s = ring.style;
    s.display = 'block';
    s.width = r.width + 12 + 'px';
    s.height = r.height + 12 + 'px';
    s.transform = `translate(${r.left - 6}px, ${r.top - 6}px)`;
    s.borderRadius = ringRadius;
    ringLoop = requestAnimationFrame(follow);
  }

  // The highlighted element went away (a new page, a closed menu): pick something on what's there now.
  function recover() {
    if (recovering) return;
    recovering = setTimeout(() => {
      recovering = 0;
      if (!ringOn || (focused && focused.isConnected && Nav.onScreen(focused) && Nav.visible(focused))) return;
      const els = targets();
      if (els.length) highlight(replacement(els));
    }, 400);
  }

  // A on the highlighted element. The app taps it (MainActivity.ok) so the site gets a real
  // touch; a scripted click isn't a user gesture, and pages it opens drop out of back history.
  A.ok = () => {
    if (!ringOn || !focused || !focused.isConnected) {
      move(null);
      return null;
    }
    if (focused.tagName === 'STRMCX-EMBED') {
      A.cmd({ c: 'toggle' });
      return null;
    }
    const r = focused.getBoundingClientRect();
    const x = (r.left + r.right) / 2, y = (r.top + r.bottom) / 2;
    // The tap has to land on it: bring it back into view first.
    if (x < 0 || x >= innerWidth || y < 0 || y >= innerHeight || covered(focused)) {
      highlight(focused);
      return null;
    }
    const v = window.visualViewport;
    const k = devicePixelRatio * (v ? v.scale : 1);
    // A menu the tap opens takes the highlight.
    setTimeout(() => ringOn && move(null), 450);
    return { x: (x - (v ? v.offsetLeft : 0)) * k, y: (y - (v ? v.offsetTop : 0)) * k };
  };

  // ---- commands from the app ----

  A.cmd = (c) => {
    hook();
    const e = embed;
    switch (c.c) {
      case 'toggle':
        if (!e) break;
        if (player.state === 'playing') {
          e.pause();
        } else {
          expect();
          start(e);
        }
        break;
      case 'play':
        if (!e) break;
        expect();
        start(e);
        break;
      case 'pause':
        expectUntil = playUntil = 0;
        if (e) e.pause();
        break;
      case 'seekBy': if (e) e.seekBy(c.d); break;
      case 'seek':
        if (e) {
          e.seek(c.t);
          if (c.play) start(e);
        }
        break;
      case 'skip': if (e) e.smartSkip(); break;
      case 'next': autoplay(); if (!click('Next Episode')) step(1); break;
      case 'prev': autoplay(); if (!click('Previous Episode')) step(-1); break;
      case 'episode': autoplay(); goEpisode(c.ep); break;
      case 'autoplay':
        autoplay();
        autoplayCheck();
        break;
      case 'rate': if (e) e.setPlaybackRate(c.r); break;
      case 'captions': if (e) e.toggleCaptions(); break;
      case 'theater': {
        const want = c.on === undefined ? !theater : !!c.on;
        if (!want) theaterDeclined = true;
        setTheater(want);
        break;
      }
      case 'menu': toggleMenu(c.label); break;
      case 'pick': pick(c.i); break;
      case 'click': click(c.label); break;
      case 'find': finding = { a: c.a, ep: c.ep, until: Date.now() + 15000 }; break;
      case 'login': login(); break;
      case 'prefs': prefs = c.prefs || {}; break;
      case 'nav':
        if (c.d === 'hide') hideRing();
        else move(c.d === 'show' ? null : c.d);
        break;
      case 'blur': if (document.activeElement) document.activeElement.blur(); break;
    }
    send();
  };

  // Opens or closes the Server or Language menu. In fill-screen mode it opens out of sight under
  // the player and its choices show on the bottom screen.
  function toggleMenu(label) {
    const b = MENUS.includes(label) && button(label);
    if (!b) return;
    const toggle = () => {
      b.click();
      setTimeout(send, 400);
    };
    if (theater || b.getAttribute('aria-expanded') === 'true') return toggle();
    b.scrollIntoView({ block: 'center' });
    setTimeout(toggle, 250);
  }

  function pick(i) {
    const list = document.querySelector('[role="listbox"]');
    const o = list && list.querySelectorAll('[role="option"]')[i];
    if (!o || o.getAttribute('aria-disabled') === 'true') return;
    // Picking a server means "play it from there".
    autoplay();
    if (player) {
      player.state = 'loading';
      player.error = '';
      player.allFailed = false;
      player.at = Date.now();
    }
    o.click();
    setTimeout(send, 400);
  }

  const autoplay = () => (playUntil = expectUntil = Date.now() + 20000);
  const expect = () => (expectUntil = Date.now() + 15000);

  function goEpisode(n) {
    const b = episodeButtons().find((x) => new RegExp('^EP ' + n + '(:|$)').test(x.getAttribute('aria-label')));
    if (b) b.click();
    else location.assign(location.pathname + '?ep=' + n);
  }

  // The neighbouring episode in the site's list, which can skip numbers.
  function step(dir) {
    const ep = episode();
    const ns = episodes().map((e) => e.n).filter((n) => (dir > 0 ? n > ep : n < ep));
    if (ns.length) goEpisode(dir > 0 ? Math.min(...ns) : Math.max(...ns));
    else if (embed) dir > 0 ? embed.nextEpisode() : embed.previousEpisode();
  }

  async function login() {
    const menu = button('Profile menu');
    if (!menu) {
      B.notice(nonce, 'Log in from the profile menu at the top right');
      return;
    }
    menu.click();
    await sleep(700);
    const item = [...document.querySelectorAll('a, button')].find((x) => /^Log in with/i.test(x.textContent.trim()));
    if (item) item.click();
    else B.notice(nonce, 'Log in from the profile menu at the top right');
  }

  // ---- AniList id <-> site page ----

  // Cards link to /watch/<site id>/<slug> and show AniList's cover, whose file name has the AniList id.
  function cards() {
    const out = [];
    for (const a of document.querySelectorAll('a[href^="/watch/"], a[href^="/info/"]')) {
      const img = a.querySelector('img');
      const src = img ? img.currentSrc || img.src || '' : '';
      const m = src.match(/\/media\/anime\/cover\/[^/]+\/(?:bx|b)?(\d+)(?:-[^/]*)?\.\w+$/);
      if (m) out.push({ a: +m[1], path: a.getAttribute('href').split('?')[0].replace(/^\/info\//, '/watch/') });
    }
    return out;
  }

  function learn() {
    const add = {};
    let n = 0;
    const note = (id, path) => {
      if (!id || learned[id] === path) return;
      learned[id] = add[id] = path;
      n++;
    };
    for (const c of cards()) note(c.a, c.path);
    const pg = page();
    if (pg === 'watch' || pg === 'info') note(anilistId(), location.pathname.replace(/^\/info\//, '/watch/'));
    if (n) B.map(nonce, JSON.stringify(add));
  }

  function find() {
    if (!finding) return;
    if (Date.now() > finding.until) {
      finding = null;
      B.notice(nonce, 'Pick the show from the search results');
      return;
    }
    const hit = cards().find((c) => c.a === finding.a);
    if (!hit) return;
    const ep = finding.ep;
    finding = null;
    location.assign(ep < 0 ? hit.path.replace(/^\/watch\//, '/info/') : hit.path + (ep > 0 ? '?ep=' + ep : ''));
  }

  // ---- the site's AniList login ----

  // After logging in on the site, an AniList access token (a JWT) may sit in its storage.
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
    if (best && best !== lastToken) {
      lastToken = best;
      B.token(nonce, best);
    }
  }

  function tick() {
    hook();
    const loc = location.pathname + location.search;
    if (loc !== lastLoc) {
      lastLoc = loc;
      if (player) reset(player.session);
    }
    stallCheck();
    autoplayCheck();
    if (theater) ensureStyle();
    learn();
    find();
    send();
  }

  A.refresh = () => {
    tick();
    scanToken();
  };
  setInterval(tick, 1000);
  setInterval(scanToken, 15000);
  setTimeout(scanToken, 2000);
  window.addEventListener('storage', scanToken);
  // The app needs to know at once when a text field takes or loses focus (see MainActivity.nav).
  document.addEventListener('focusin', send, true);
  document.addEventListener('focusout', () => setTimeout(send, 0), true);
  tick();
}
