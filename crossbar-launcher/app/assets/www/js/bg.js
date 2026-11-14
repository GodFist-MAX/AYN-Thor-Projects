'use strict';
/* Background engine: animated light wave (WebGL), rotating wallpapers and PS5-style hero art. */

/* DS and 3DS captures stack both screens vertically; only the top (main) screen makes a good backdrop. */
function isStacked(img) {
  return img.naturalHeight > img.naturalWidth;
}

const MONTH_COLORS = ['#b9bfcc', '#d8b13b', '#62c24f', '#e47cc4', '#3fae5a', '#a57fd8',
  '#2fb6c9', '#3a82e8', '#8a4fe0', '#e0a12e', '#b4623a', '#d23a3a'];

const WAVE_PRESETS = {
  blue: '#2f6bff', purple: '#8a4fe0', red: '#d23a3a', green: '#3fae5a', gold: '#d8b13b',
  pink: '#e47cc4', teal: '#22b5b0', silver: '#b9bfcc', orange: '#ff8a3d',
};

function hexToRgb(hex) {
  const n = parseInt(hex.replace('#', ''), 16);
  return [(n >> 16 & 255) / 255, (n >> 8 & 255) / 255, (n & 255) / 255];
}

const Bg = {
  mode: 'wallpapers',
  waveOverlay: true,
  heroArt: true,
  rotateSec: 15,
  target: [0.18, 0.42, 1],
  walls: [],
  wallKey: '',
  wallIdx: 0,
  heroes: [],
  heroKey: '',
  heroIdx: 0,
  heroIconBlur: false,
  timer: null,
  running: true,
  idle: false,
  waveAlpha: 1,
  bgOpaque: false,
  tokens: { wall: 0, hero: 0 },

  init() {
    this.wallA = $('#wall-a');
    this.wallB = $('#wall-b');
    this.heroA = $('#hero-a');
    this.heroB = $('#hero-b');
    this.shade = $('#hero-shade');
    this.canvas = $('#wave');
    this.initWave();
    this.poke();
  },

  configure(s) {
    this.mode = s.bgMode || 'wallpapers';
    this.waveOverlay = s.waveOverlay !== false;
    this.heroArt = s.heroArt !== false;
    this.rotateSec = Number(s.rotateSec) || 0;
    this.waveSetting = s.waveColor || 'auto';
    this.customBg = s.customBg || '';
    this.wallKey = '';
    this.heroKey = '';
    this.restartTimer();
    this.poke();
  },

  /* Called when the focused item changes. */
  setTheme({ accent, walls, heroes, iconBlur }) {
    let c = accent || '#2f6bff';
    if (this.waveSetting === 'month') c = MONTH_COLORS[new Date().getMonth()];
    else if (WAVE_PRESETS[this.waveSetting]) c = WAVE_PRESETS[this.waveSetting];
    this.target = hexToRgb(c);
    // Only the avatar uses the accent; setting the variable on the root would restyle all ~500 elements.
    const av = c !== this.accent && document.getElementById('avatar');
    if (av) av.style.setProperty('--accent', c);
    this.accent = c;

    let list = walls || [];
    if (this.mode === 'wave') list = [];
    if (this.mode === 'custom' && this.customBg) list = [this.customBg];
    const key = list.join('|');
    if (key !== this.wallKey) {
      this.wallKey = key;
      this.walls = list;
      this.wallIdx = 0;
      if (list.length) this.show('wall', list[0]);
      else this.hideLayer('wall');
      this.restartTimer();
    }

    const hlist = this.heroArt ? (heroes || []) : [];
    const hkey = hlist.join('|') + (iconBlur ? '#blur' : '');
    if (hkey !== this.heroKey) {
      this.heroKey = hkey;
      this.heroes = hlist;
      this.heroIdx = 0;
      this.heroIconBlur = !!iconBlur;
      if (hlist.length) {
        this.show('hero', hlist[0]);
        this.shade.classList.add('on');
      } else {
        this.hideLayer('hero');
        this.shade.classList.remove('on');
      }
      this.restartTimer();
    }
    const hero = this.heroes.length > 0;
    const wallsOn = this.walls.length > 0;
    this.waveAlpha = hero ? (this.heroIconBlur ? 0.8 : 0.32) : (wallsOn ? (this.waveOverlay ? 0.9 : 0) : 1);
    if (this.canvas.style.opacity !== String(this.waveAlpha)) this.canvas.style.opacity = this.waveAlpha;
    this.bgOpaque = !wallsOn && !hero;
    this.waveSync();
  },

  /* Cross-fades to url once it is decoded, so the fade never waits on a decode mid-animation. */
  show(kind, url) {
    const a = kind === 'wall' ? this.wallA : this.heroA;
    const b = kind === 'wall' ? this.wallB : this.heroB;
    const blur = kind === 'hero' && this.heroIconBlur;
    const token = ++this.tokens[kind];
    let ready;
    if (blur) {
      // The old look: the icon at 84% of the screen width under a 60px blur, drawn 10x smaller.
      ready = Blur.get(url, { w: 192, h: 108, blur: 6, filter: 'saturate(1.6) brightness(.75)', fit: 0.84 })
        .then(u => ({ u, stacked: false }));
    } else {
      const img = new Image();
      img.decoding = 'async';
      img.src = url;
      ready = img.decode().then(() => ({ u: url, stacked: kind === 'hero' && isStacked(img) }));
    }
    ready.then(({ u, stacked }) => {
      if (token !== this.tokens[kind] || (kind === 'hero' && !this.heroes.length)) return;
      const front = a.classList.contains('on') ? a : (b.classList.contains('on') ? b : null);
      const back = front === a ? b : a;
      back.style.backgroundImage = `url("${u}")`;
      back.classList.toggle('iconblur', blur);
      back.classList.toggle('stacked', stacked);
      back.classList.add('on');
      if (front) front.classList.remove('on');
    }, () => {
      if (kind !== 'hero' || token !== this.tokens[kind]) return;
      this.heroes = this.heroes.filter(u => u !== url);
      if (!this.heroes.length) { this.hideLayer('hero'); this.shade.classList.remove('on'); }
    });
  },

  hideLayer(kind) {
    this.tokens[kind]++;
    const a = kind === 'wall' ? this.wallA : this.heroA;
    const b = kind === 'wall' ? this.wallB : this.heroB;
    a.classList.remove('on');
    b.classList.remove('on');
  },

  restartTimer() {
    clearInterval(this.timer);
    if (!this.rotateSec) return;
    const sec = Perf.battery ? Math.max(60, this.rotateSec) : this.rotateSec;
    this.timer = setInterval(() => {
      if (!this.running || (this.idle && Perf.battery)) return;
      if (this.heroes.length > 1) {
        this.heroIdx = (this.heroIdx + 1) % this.heroes.length;
        this.show('hero', this.heroes[this.heroIdx]);
      } else if (!this.heroes.length && this.walls.length > 1) {
        this.wallIdx = (this.wallIdx + 1) % this.walls.length;
        this.show('wall', this.walls[this.wallIdx]);
      } else {
        return;
      }
      Companion.sync();
    }, sec * 1000);
  },

  /* The picture behind the menu right now, for the bottom screen. */
  current() {
    if (this.heroes.length) return { url: this.heroes[this.heroIdx] || this.heroes[0], blur: this.heroIconBlur, hero: true };
    if (this.walls.length) return { url: this.walls[this.wallIdx] || this.walls[0] };
    return null;
  },

  pause() {
    this.running = false;
    this.waveSync();
  },
  resume() {
    if (this.running) return;
    this.running = true;
    this.poke();
  },

  /* Input happened: full-speed wave again, and idle counts from now. */
  poke() {
    clearTimeout(this.idleT);
    this.idleT = setTimeout(() => {
      this.idle = true;
      this.waveSync();
    }, Perf.battery ? 15000 : 60000);
    if (this.idle) {
      this.idle = false;
      this.waveSync();
    }
  },

  // ---------- light wave (js/wave.js) ----------
  initWave() {
    const log = m => Api.log('wave: ' + m);
    const c = this.canvas;
    try {
      if (!c.transferControlToOffscreen || typeof Worker === 'undefined') throw new Error('no OffscreenCanvas');
      const off = c.transferControlToOffscreen();
      const w = new Worker('js/wave.js');
      w.onmessage = e => {
        const m = e.data;
        if (m.log) log(m.log);
        if (m.ready === false) this.pageWave('no WebGL in the worker');
        if (m.frames != null && this.onFrames) this.onFrames(m.frames);
      };
      w.onerror = e => this.pageWave(e.message || 'worker failed');
      w.postMessage({ canvas: off }, [off]);
      this.worker = w;
      this.wave = { set: o => w.postMessage({ set: o }) };
    } catch (e) {
      this.pageWave(e.message);
    }
  },

  /* Draws the wave on the page itself, on a fresh canvas (a transferred one can't come back). */
  pageWave(why) {
    if (this.worker) this.worker.terminate();
    this.worker = null;
    Api.log('wave: drawing on the page (' + why + ')');
    const old = this.canvas;
    const c = el('canvas');
    c.id = 'wave';
    c.style.opacity = old.style.opacity;
    old.replaceWith(c);
    this.canvas = c;
    this.wave = WaveRenderer(c, m => Api.log('wave: ' + m));
    this.waveKey = '';
    this.waveSync();
  },

  /* Light frames for the preset: the wave moves slowly, so 15-30 FPS looks the same as 60. */
  waveSync() {
    if (!this.wave) return;
    const b = Perf.battery, f30 = Perf.fps === 30;
    const fps = this.idle ? (b ? 0 : (f30 ? 15 : 20)) : (b ? (f30 ? 15 : 20) : 30);
    const set = {
      run: this.running && this.waveAlpha > 0.01 && fps > 0,
      fps: fps || 1, scale: b ? 0.4 : 0.5, lines: b ? 9 : 13,
      opaque: this.bgOpaque ? 1 : 0, target: this.target,
    };
    const key = JSON.stringify(set);
    if (key === this.waveKey) return;
    this.waveKey = key;
    this.wave.set(set);
  },
};
