'use strict';
/* Shared helpers: DOM, API, native bridge, formatting, UI sounds. */

const $ = (s, root = document) => root.querySelector(s);
const el = (tag, cls, html) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (html != null) e.innerHTML = html;
  return e;
};
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g,
  c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
/* Writing the same text still dirties layout, so skip it when nothing changed. */
const setText = (e, v) => { if (e && e.textContent !== v) e.textContent = v; };
const sleep = ms => new Promise(r => setTimeout(r, ms));
/* Pictures from the SD card at the width they are drawn (see Api.scaled); other URLs pass through. */
const sized = (u, w) => u && u.startsWith('/img/file?') ? u.replace(/&w=\d+/, '') + '&w=' + w : u;

const ON_DEVICE = typeof window.Native !== 'undefined';
const Native = window.Native || new Proxy({}, {
  get: (_, name) => (...args) => { console.log('[native]', name, ...args); return ''; },
});

const Api = {
  async get(path) {
    const r = await fetch(path, { cache: 'no-store' });
    if (!r.ok) throw new Error(path + ': HTTP ' + r.status);
    return r.json();
  },
  async post(path, body) {
    const r = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    if (!r.ok) throw new Error(path + ': HTTP ' + r.status);
    return r.json();
  },
  log(msg) { fetch('/api/log', { method: 'POST', body: String(msg) }).catch(() => {}); },
};
/* Uncaught errors go to the log and, through CrossBar, to Coronamon's bug log for Grok Build. */
const PAGE = location.pathname.includes('bottom') ? 'bottom' : 'top';
window.addEventListener('error', e => Api.log('error[' + PAGE + ']: ' + e.message + ' @' + e.filename + ':' + e.lineno));
window.addEventListener('unhandledrejection', e => Api.log('rejection[' + PAGE + ']: ' + (e.reason && (e.reason.stack || e.reason))));

/* Settings › Performance. The native side paces both screens to `fps`; the pages scale their own work. */
const PERF_PRESETS = {
  smooth60: { fps: 60, battery: false, label: '60 FPS · Smooth' },
  battery60: { fps: 60, battery: true, label: '60 FPS · Battery Saver' },
  smooth30: { fps: 30, battery: false, label: '30 FPS · Smooth' },
  battery30: { fps: 30, battery: true, label: '30 FPS · Battery Saver' },
};
const Perf = {
  id: 'smooth60', fps: 60, battery: false,
  /* Returns whether the preset changed. */
  set(id) {
    if (!PERF_PRESETS[id]) id = 'smooth60';
    const p = PERF_PRESETS[id];
    const changed = id !== this.id;
    Object.assign(this, { id, fps: p.fps, battery: p.battery });
    document.documentElement.classList.toggle('lite', p.battery);
    document.documentElement.classList.toggle('fps30', p.fps === 30);
    return changed;
  },
};

/* Tiny pre-blurred copies of pictures. A CSS blur on a layer is redone by the GPU on every frame
   that layer is drawn; a 160px picture stretched over the screen looks the same and costs nothing. */
const Blur = {
  cache: new Map(),
  /* o: { w, h (box to draw into; h defaults to the picture's aspect), blur (px at that size), filter, fit ('cover' or a fraction of w) } */
  get(url, o = {}) {
    const key = JSON.stringify([url, o]);
    let p = this.cache.get(key);
    if (p) {
      this.cache.delete(key);
      this.cache.set(key, p);
      return p;
    }
    p = this.make(url, o);
    this.cache.set(key, p);
    p.catch(() => this.cache.delete(key));
    while (this.cache.size > 160) {
      const k = this.cache.keys().next().value;
      this.cache.get(k).then(u => URL.revokeObjectURL(u), () => {});
      this.cache.delete(k);
    }
    return p;
  },
  async make(url, { w = 160, h = 0, blur = 3, filter = '', fit = 'cover' }) {
    const r = await fetch(url);
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const src = await createImageBitmap(await r.blob());
    if (!h) h = Math.max(1, Math.round(w * src.height / src.width));
    const c = new OffscreenCanvas(w, h);
    const g = c.getContext('2d');
    g.filter = `blur(${blur}px) ${filter}`.trim();
    if (fit === 'cover') {
      // Overhang each edge by 3 blur radii so the blur does not fade out at the borders.
      const s = Math.max((w + 6 * blur) / src.width, (h + 6 * blur) / src.height);
      const dw = src.width * s, dh = src.height * s;
      g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh);
    } else {
      const dw = w * fit, dh = dw * src.height / src.width;
      g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh);
    }
    src.close();
    return URL.createObjectURL(await c.convertToBlob({ type: 'image/png' }));
  },
};

/* Blurred backdrops behind pictures: any element added with data-blur="url" (and optionally data-look) gets a
   pre-blurred copy. Rasterizing a CSS blur takes the GPU ~10ms a tile, long enough to drop a frame on both screens. */
const BLUR_LOOKS = {
  tile: { w: 96, h: 54, blur: 5.5, filter: 'brightness(.62) saturate(1.3)' },
  cover: { w: 72, h: 72, blur: 6, filter: 'brightness(.6) saturate(1.3)' },
  banner: { w: 176, h: 40, blur: 3.5, filter: 'saturate(1.4) brightness(.72)' },
};
function blurInto(e, url, look) {
  if (!url) return;
  Blur.get(url, BLUR_LOOKS[look] || BLUR_LOOKS.tile).then(u => { e.style.backgroundImage = `url("${u}")`; }, () => {
    e.style.backgroundImage = `url("${url.replace(/["\\]/g, '\\$&')}")`;
    e.classList.add('fx');
  });
}
new MutationObserver(recs => {
  const fill = e => {
    const u = e.dataset.blur;
    e.removeAttribute('data-blur');
    blurInto(e, u, e.dataset.look);
  };
  for (const r of recs) {
    for (const n of r.addedNodes) {
      if (n.nodeType !== 1) continue;
      if (n.hasAttribute('data-blur')) fill(n);
      if (n.firstElementChild) n.querySelectorAll('[data-blur]').forEach(fill);
    }
  }
}).observe(document.documentElement, { childList: true, subtree: true });

const Fmt = {
  dur(ms) {
    const s = Math.max(0, Math.floor((ms || 0) / 1000));
    const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), ss = String(s % 60).padStart(2, '0');
    return h ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
  },
  played(ms) {
    if (!ms) return '';
    const h = ms / 3600000;
    if (h >= 1) return (h >= 10 ? Math.round(h) : h.toFixed(1)) + ' hours played';
    const m = Math.round(ms / 60000);
    return m > 0 ? m + (m === 1 ? ' minute played' : ' minutes played') : '';
  },
  ago(ts) {
    if (!ts) return '';
    const d = (Date.now() - ts) / 1000;
    if (d < 60) return 'just now';
    if (d < 3600) return Math.floor(d / 60) + ' min ago';
    if (d < 86400) { const h = Math.floor(d / 3600); return h + (h === 1 ? ' hour ago' : ' hours ago'); }
    const days = Math.floor(d / 86400);
    if (days < 30) return days + (days === 1 ? ' day ago' : ' days ago');
    return new Date(ts).toLocaleDateString();
  },
  size(b) {
    if (!b) return '';
    const u = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    while (b >= 1024 && i < 4) { b /= 1024; i++; }
    return (i >= 3 ? b.toFixed(1) : Math.round(b)) + ' ' + u[i];
  },
  clock(d, h24) {
    const m = String(d.getMinutes()).padStart(2, '0');
    if (h24) return `${String(d.getHours()).padStart(2, '0')}:${m}`;
    const h = d.getHours() % 12 || 12;
    return `${h}:${m} ${d.getHours() >= 12 ? 'PM' : 'AM'}`;
  },
  date(d) { return `${d.getMonth() + 1}/${d.getDate()}`; },
  plural(n, one, many) { return n + ' ' + (n === 1 ? one : (many || one + 's')); },
};

/* Synthesized menu sounds, so nothing copyrighted ships with the app. */
const Sound = {
  ctx: null, master: null, on: true,
  init() {
    try {
      this.ctx = new (window.AudioContext || window.webkitAudioContext)();
      this.master = this.ctx.createGain();
      this.master.connect(this.ctx.destination);
      this.setVol(0.6);
    } catch (e) { this.ctx = null; }
  },
  setVol(v) { if (this.master) this.master.gain.value = v; },
  tone(freq, t0, dur, o = {}) {
    const c = this.ctx, osc = c.createOscillator(), g = c.createGain();
    osc.type = o.type || 'sine';
    osc.frequency.setValueAtTime(freq, t0);
    if (o.slide) osc.frequency.exponentialRampToValueAtTime(freq * o.slide, t0 + dur);
    g.gain.setValueAtTime(0.0001, t0);
    g.gain.exponentialRampToValueAtTime(o.gain || 0.1, t0 + (o.attack || 0.004));
    g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
    let node = osc;
    if (o.lp) {
      const f = c.createBiquadFilter();
      f.type = 'lowpass';
      f.frequency.value = o.lp;
      osc.connect(f);
      node = f;
    }
    node.connect(g);
    g.connect(this.master);
    osc.start(t0);
    osc.stop(t0 + dur + 0.05);
  },
  play(name) {
    if (!this.on || !this.ctx) return;
    if (this.ctx.state === 'suspended') this.ctx.resume();
    // A running AudioContext keeps the audio hardware awake even in silence.
    clearTimeout(this.idle);
    this.idle = setTimeout(() => this.ctx.suspend(), name === 'boot' ? 6000 : 4000);
    const t = this.ctx.currentTime + 0.005;
    switch (name) {
      case 'move':
        this.tone(2350, t, 0.05, { gain: 0.045 });
        this.tone(4700, t, 0.03, { gain: 0.012 });
        break;
      case 'cat':
        this.tone(1480, t, 0.09, { gain: 0.055, slide: 1.12 });
        this.tone(2960, t, 0.05, { gain: 0.016 });
        break;
      case 'ok':
        this.tone(1046.5, t, 0.24, { gain: 0.075 });
        this.tone(1568, t + 0.05, 0.3, { gain: 0.065 });
        break;
      case 'open':
        this.tone(880, t, 0.16, { gain: 0.06, slide: 1.5 });
        this.tone(1760, t + 0.03, 0.2, { gain: 0.025 });
        break;
      case 'back':
        this.tone(1250, t, 0.14, { gain: 0.055, slide: 0.6 });
        break;
      case 'bump':
        this.tone(900, t, 0.06, { gain: 0.03, type: 'triangle' });
        break;
      case 'error':
        this.tone(190, t, 0.18, { type: 'triangle', gain: 0.12 });
        this.tone(150, t + 0.09, 0.2, { type: 'triangle', gain: 0.1 });
        break;
      case 'toast':
        this.tone(1318.5, t, 0.26, { gain: 0.05 });
        this.tone(1760, t + 0.08, 0.32, { gain: 0.04 });
        break;
      case 'boot': {
        [261.63, 329.63, 392.0, 493.88, 587.33].forEach((f, i) => {
          this.tone(f, t + i * 0.07, 3.4, { gain: 0.05, attack: 0.9, lp: 2200 });
          this.tone(f * 2, t + i * 0.07, 2.6, { type: 'triangle', gain: 0.01, attack: 1.2, lp: 3200 });
        });
        [1046.5, 1318.5, 1568, 2093].forEach((f, i) => this.tone(f, t + 1.0 + i * 0.13, 1.3, { gain: 0.022 }));
        break;
      }
      default:
        break;
    }
  },
};
