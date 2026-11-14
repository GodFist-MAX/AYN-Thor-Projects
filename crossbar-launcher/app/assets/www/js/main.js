'use strict';
/* Boot sequence, key routing, status bar and background polling. */

const Stage = {
  scale: 1,
  /* The stage clips with overflow:hidden, which still lets focus and scrollIntoView shift it. */
  pin() {
    const st = $('#stage');
    st.addEventListener('scroll', () => { if (st.scrollLeft || st.scrollTop) st.scrollTo(0, 0); });
    addEventListener('scroll', () => { if (scrollX || scrollY) scrollTo(0, 0); });
  },
  fit() {
    const s = Math.min(innerWidth / 1920, innerHeight / 1080);
    this.scale = s;
    $('#stage').style.transform = `translate(${(innerWidth - 1920 * s) / 2}px,${(innerHeight - 1080 * s) / 2}px) scale(${s})`;
  },
};

function batterySvg(level, charging) {
  const w = Math.round(38 * clamp(level, 0, 100) / 100);
  const low = level <= 15 && !charging;
  return `<svg viewBox="0 0 64 64"><rect x="4" y="19" width="51" height="27" rx="6" fill="none" stroke="#fff" stroke-width="4"/>` +
    `<rect x="57" y="27" width="5" height="11" rx="2" fill="#fff"/><rect x="10" y="25" width="${w}" height="15" rx="2" fill="${low ? '#ff5a5a' : '#fff'}"/>` +
    (charging ? '<path d="M34 9 20 35h11l-4 20 17-28H33z" fill="#ffd34d" stroke="#000" stroke-opacity=".6" stroke-width="2"/>' : '') + '</svg>';
}

function wifiSvg(on, level, max) {
  const n = on ? Math.max(1, Math.round(3 * (level + 1) / Math.max(1, max + 1))) : 0;
  const a = i => (on && i <= n ? 1 : 0.28);
  return `<svg viewBox="0 0 64 64" fill="none" stroke="#fff" stroke-width="6" stroke-linecap="round">` +
    `<path d="M8 25a34 34 0 0 1 48 0" opacity="${a(3)}"/><path d="M17 35a21 21 0 0 1 30 0" opacity="${a(2)}"/>` +
    `<path d="M26 45a8.5 8.5 0 0 1 12 0" opacity="${a(1)}"/><circle cx="32" cy="53" r="3" fill="#fff" stroke="none" opacity="${on ? 1 : 0.28}"/>` +
    (on ? '' : '<path d="M12 12 52 56" stroke="#ff6b6b"/>') + '</svg>';
}

const Status = {
  h24: false,
  showDate: true,
  init() {
    this.np = $('#st-np');
    this.npText = $('#np-mini');
    this.friends = $('#st-friends');
    this.notifs = $('#st-notif');
    this.wifi = $('#st-wifi');
    this.bat = $('#st-bat');
    this.clock = $('#clock');
    $('.sti', this.np).innerHTML = miniGlyph('music');
    $('.sti', this.friends).innerHTML = miniGlyph('friends');
    $('.sti', this.notifs).innerHTML = miniGlyph('bell');
    this.tick();
  },
  /* Redraws the clock and sleeps until the next minute starts. */
  tick() {
    clearTimeout(this.tt);
    const d = new Date();
    setText(this.clock, (this.showDate ? Fmt.date(d) + '  ' : '') + Fmt.clock(d, this.h24));
    this.tt = setTimeout(() => this.tick(), 60500 - d.getSeconds() * 1000 - d.getMilliseconds());
  },
  update(s) {
    const bat = `<span class="sti">${batterySvg(s.battery, s.charging)}</span><span class="n">${s.battery}%</span>`;
    if (bat !== this.batHtml) this.bat.innerHTML = this.batHtml = bat;
    const wifi = `<span class="sti">${wifiSvg(s.wifi, s.wifiLevel || 0, s.wifiMax || 4)}</span>`;
    if (wifi !== this.wifiHtml) this.wifi.innerHTML = this.wifiHtml = wifi;
    this.wifi.classList.toggle('dim', !s.online);
    this.setCount(this.friends, s.friends);
    this.setCount(this.notifs, s.notifs);
    if (s.np) Media.setNp(s.np);
  },
  setCount(e, n) {
    e.classList.toggle('hide', !n);
    setText($('.n', e), String(n || ''));
  },
  setNp(text, src) {
    this.np.classList.toggle('hide', !text || !(src && src.playing));
    setText(this.npText, text);
  },
};

function applySettings(s) {
  if (Perf.set(s.perfPreset)) startPolling();
  Sound.on = s.sounds !== false;
  Sound.setVol(Number(s.soundVol) || 0.6);
  Bg.configure(s);
  Status.h24 = !!s.clock24;
  Status.showDate = s.showDate !== false;
  Status.tick();
  $('#avatar').textContent = ((s.userName || '').trim().charAt(0) || 'P').toUpperCase();
  if (XMB.cats.length) XMB.focusChanged(true);
}

async function pollStatus() {
  if (CB.paused) return;
  try {
    Status.update(await Api.get('/api/status'));
  } catch (e) { /* server restarting */ }
}

let pollTimer = 0;
function startPolling() {
  clearInterval(pollTimer);
  if (CB.ready) pollTimer = setInterval(pollStatus, Perf.battery ? 15000 : 5000);
}

let socialKey = '';
async function loadSocial(force) {
  if (CB.paused && !force) return;
  try {
    const S = await Api.get('/api/social' + (force ? '?refresh=1' : ''));
    const key = JSON.stringify(S);
    if (key === socialKey) return;
    socialKey = key;
    Model.social = S;
    XMB.replaceCategory(friendsCat());
    XMB.replaceCategory(tvCat());
    Companion.sync();
  } catch (e) { /* offline */ }
}

const CB = window.CB = {
  ready: false,
  paused: false,
  settingsKey: '',

  key(k, rep) {
    if (!this.ready) return;
    Bg.poke();
    if (k === 'select' && !Overlay.top()) return this.quickMenu();
    const ov = Overlay.top();
    if (ov) return ov.key(k, rep);
    XMB.key(k, rep);
  },

  quickMenu() {
    Sound.play('open');
    Panels.options('Quick Menu', [
      { label: 'Now Playing', run: () => Media.openMusic() },
      { label: 'Play / Pause Music', run: () => Media.control('toggle') },
      { label: 'Friends', run: () => XMB.goto('friends') },
      { label: 'Settings', run: () => XMB.goto('settings') },
      { label: 'Rescan Games', run: rescan },
      { label: 'Reload CrossBar', run: () => Native.reload() },
    ]);
  },

  onResume() {
    this.paused = false;
    Bg.resume();
    Status.tick();
    $('#stage').classList.remove('launching');
    if (!this.ready) return;
    Companion.launched(null);
    pollStatus();
    this.reload(true);
  },

  onPause() {
    this.paused = true;
    Bg.pause();
    VideoOv.pause();
  },

  onHome() {
    if (this.ready) XMB.home();
  },

  fromBottom(c) {
    if (!this.ready || !c) return;
    Bg.poke();
    Companion.command(c);
  },

  async reload(quiet) {
    try {
      const H = await Api.get('/api/home');
      Model.setHome(H);
      Model.cache = {};
      const sk = JSON.stringify(H.settings);
      if (sk !== this.settingsKey) {
        this.settingsKey = sk;
        applySettings(H.settings);
      }
      XMB.setCategories(Model.build());
      return H;
    } catch (e) {
      if (!quiet) Panels.error('CrossBar could not reload: ' + e.message);
    }
  },

  toast(msg, g) { Panels.toast(msg, g); },
};

/* Keyboard controls for testing in a desktop browser; on the Thor the native layer sends keys. */
function initKeyboard() {
  if (ON_DEVICE) return;
  const map = {
    ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Enter: 'ok', ' ': 'ok',
    Escape: 'back', Backspace: 'back', y: 'options', t: 'options', x: 'x', q: 'l1', e: 'r1', '1': 'l2', '3': 'r2',
    m: 'start', Tab: 'select',
  };
  addEventListener('keydown', ev => {
    const typing = ev.target && ev.target.tagName === 'INPUT';
    if (typing && ev.key !== 'Escape') return;
    const k = map[ev.key];
    if (!k) return;
    ev.preventDefault();
    CB.key(k, ev.repeat);
  });
}

/* Google TV titles are looked up in the background on first sight, so refresh until they arrive. */
function awaitMovieTitles(H, tries) {
  if (tries > 0 && H && (H.googleTv || []).some(m => !m.title))
    setTimeout(async () => awaitMovieTitles(await CB.reload(true), tries - 1), 10000);
}

async function boot() {
  Stage.fit();
  Stage.pin();
  addEventListener('resize', () => Stage.fit());
  Sound.init();
  Bg.init();
  Panels.init();
  Media.init();
  XMB.init();
  Status.init();
  initKeyboard();
  const t0 = Date.now();
  Sound.play('boot');
  try { WALLS = await Api.get('wallpapers/index.json'); } catch (e) { WALLS = {}; }
  let H = null;
  for (let i = 0; i < 30 && !H; i++) {
    try { H = await Api.get('/api/home'); } catch (e) { await sleep(400); }
  }
  if (!H) {
    $('#boot .bs').textContent = 'COULD NOT START - PRESS A TO RETRY';
    CB.ready = true;
    CB.key = k => { if (k === 'ok') location.reload(); };
    return;
  }
  Model.setHome(H);
  CB.settingsKey = JSON.stringify(H.settings);
  applySettings(H.settings);
  XMB.setCategories(Model.build());
  CB.ready = true;
  Companion.init();
  Companion.sync(true);
  pollStatus();
  startPolling();
  loadSocial(true);
  setTimeout(() => loadSocial(false), 6000);
  awaitMovieTitles(H, 4);
  setInterval(() => loadSocial(false), 60000);
  setTimeout(() => $('#boot').classList.add('out'), Math.max(0, 2400 - (Date.now() - t0)));
}

boot();
