'use strict';
/* Full-screen media: music player, video player and photo viewer. */

const Media = {
  np: null,

  init() {
    MusicOv.init();
    VideoOv.init();
    PhotoOv.init();
  },

  /* The player to show and control: CrossBar's own queue, or another app's media session. */
  source(np = this.np) {
    if (!np) return null;
    const l = np.local || {}, x = np.external || {};
    if (l.active && (l.playing || !x.playing)) return Object.assign({ src: 'local' }, l);
    if (x.active && x.title) return Object.assign({ src: 'external' }, x);
    return null;
  },

  npText() {
    const s = this.source();
    if (!s || !s.title) return '';
    return s.title + (s.artist ? ' — ' + s.artist : '');
  },

  setNp(np) {
    const before = this.npText();
    this.np = np;
    const now = this.npText();
    Status.setNp(now, this.source());
    if (now !== before && XMB.category() && XMB.category().id === 'music') XMB.refresh();
    if (MusicOv.open_) MusicOv.render();
  },

  async refreshNp() {
    try {
      this.setNp(await Api.get('/api/nowplaying'));
    } catch (e) { /* server busy */ }
  },

  control(cmd) {
    const s = this.source();
    if (s && s.src === 'external') Native.mediaControl(s.pkg, cmd);
    else if (s) Native.music(cmd, '');
    else return Sound.play('bump');
    setTimeout(() => this.refreshNp(), 350);
  },

  openMusic() { MusicOv.open(); },
  playVideo(list, i) { VideoOv.open(list, i); },
  viewPhotos(list, i, slideshow) {
    if (!list || !list.length) return Sound.play('bump');
    PhotoOv.open(list, i, slideshow);
  },
};

const MusicOv = {
  open_: false,
  init() {
    const e = this.el = $('#music-ov');
    e.innerHTML = `<div class="mbg"></div>
      <div class="mwrap">
        <div class="mart"></div>
        <div class="mtext">
          <div class="msrc"></div><div class="mtitle"></div><div class="martist"></div><div class="malbum"></div>
          <div class="prog"><i></i></div>
          <div class="times"><span class="tpos"></span><span class="tdur"></span></div>
          <div class="ctrls">
            <span class="c-shuffle">${miniGlyph('shuffle')}</span><span class="c-prev">${miniGlyph('prev')}</span>
            <span class="big c-play"></span><span class="c-next">${miniGlyph('next')}</span><span class="c-repeat">${miniGlyph('repeat')}</span>
          </div>
        </div>
      </div>
      <div class="bars">${'<i></i>'.repeat(56)}</div>
      <div class="ovhints"><span class="h"><span class="k">A</span>Play/Pause</span><span class="h"><span class="k">◀▶</span>Previous/Next</span><span class="h local"><span class="k">L1</span><span class="k">R1</span>Seek</span><span class="h local"><span class="k">X</span>Shuffle</span><span class="h local"><span class="k">Y</span>Repeat</span><span class="h"><span class="k">B</span>Back</span></div>`;
    this.localHints = [...e.querySelectorAll('.ovhints .local')];
    this.r = {};
    for (const c of ['mbg', 'mart', 'msrc', 'mtitle', 'martist', 'malbum', 'tpos', 'tdur', 'c-play', 'c-shuffle', 'c-repeat', 'c-prev', 'c-next']) this.r[c] = $('.' + c, e);
    this.bar = $('.prog i', e);
    this.bars = [...e.querySelectorAll('.bars i')];
    this.phase = this.bars.map(() => [Math.random() * 6.28, 1.5 + Math.random() * 3.5]);
    this.r['c-play'].addEventListener('click', () => Media.control('toggle'));
    this.r['c-prev'].addEventListener('click', () => Media.control('prev'));
    this.r['c-next'].addEventListener('click', () => Media.control('next'));
    this.r['c-shuffle'].addEventListener('click', () => this.key('x'));
    this.r['c-repeat'].addEventListener('click', () => this.key('options'));
  },
  open() {
    if (this.open_) return;
    this.open_ = true;
    this.artKey = null;
    Sound.play('open');
    this.el.classList.add('on');
    Overlay.push(this);
    this.render();
    Media.refreshNp();
    this.poll = setInterval(() => Media.refreshNp(), 1500);
    this.anim = setInterval(() => this.tick(), Perf.battery ? 500 : 120);
    Companion.sync();
  },
  close(silent) {
    if (!this.open_) return;
    this.open_ = false;
    this.el.classList.remove('on');
    Overlay.pop(this);
    clearInterval(this.poll);
    clearInterval(this.anim);
    if (!silent) Sound.play('back');
    Companion.sync();
  },
  key(k) {
    const s = Media.source();
    const local = s && s.src === 'local';
    switch (k) {
      case 'ok': case 'playpause': return Media.control('toggle');
      case 'left': case 'prev': return Media.control('prev');
      case 'right': case 'next': return Media.control('next');
      case 'l1': case 'r1':
        if (!local) return Sound.play('bump');
        Native.music('seekBy', String(k === 'l1' ? -10000 : 10000));
        return setTimeout(() => Media.refreshNp(), 250);
      case 'x': case 'options':
        if (!local) return Sound.play('bump');
        Native.music(k === 'x' ? 'shuffle' : 'repeat', '');
        Sound.play('move');
        return setTimeout(() => Media.refreshNp(), 250);
      case 'back': case 'start': return this.close();
      default: return null;
    }
  },
  render() {
    const s = Media.source();
    const r = this.r;
    this.localHints.forEach(h => { h.style.display = s && s.src === 'local' ? '' : 'none'; });
    if (!s) {
      r.msrc.textContent = 'Music';
      r.mtitle.textContent = 'Nothing playing';
      r.martist.textContent = 'Pick songs from Music › All Songs, or start music in any app';
      r.malbum.textContent = '';
      r.mart.style.backgroundImage = '';
      r.mart.innerHTML = miniGlyph('music');
      r.mbg.style.backgroundImage = '';
      r['c-play'].innerHTML = miniGlyph('play');
      this.base = null;
      this.tick();
      return;
    }
    r.msrc.textContent = s.src === 'local' ? `CrossBar Music · ${s.index + 1} of ${s.count}` : `Playing in ${s.app}`;
    r.mtitle.textContent = s.title;
    r.martist.textContent = s.artist || '';
    r.malbum.textContent = s.album || '';
    r['c-play'].innerHTML = miniGlyph(s.playing ? 'pause' : 'play');
    r['c-shuffle'].className = 'c-shuffle ' + (s.src !== 'local' ? 'gone' : s.shuffle ? 'on' : 'offc');
    r['c-repeat'].className = 'c-repeat ' + (s.src !== 'local' ? 'gone' : s.repeat ? 'on' : 'offc');
    r['c-repeat'].dataset.mode = s.repeat === 2 ? '1' : '';
    const key = s.src + '|' + s.title + '|' + (s.id || '');
    if (key !== this.artKey) {
      this.artKey = key;
      const url = '/img/np?k=' + encodeURIComponent(key) + '&ts=' + Date.now();
      const img = new Image();
      img.onload = () => {
        r.mart.innerHTML = '';
        r.mart.style.backgroundImage = `url("${url}")`;
        Blur.get(url, { w: 192, h: 108, blur: 6, filter: 'saturate(1.5) brightness(.55)' })
          .then(u => { if (this.artKey === key) r.mbg.style.backgroundImage = `url("${u}")`; }, () => {});
      };
      img.onerror = () => {
        r.mart.style.backgroundImage = '';
        r.mart.innerHTML = miniGlyph('song');
        r.mbg.style.backgroundImage = '';
      };
      img.src = url;
    }
    this.base = { pos: s.position || 0, dur: s.duration || 0, at: Date.now(), playing: !!s.playing };
    this.tick();
  },
  tick() {
    const b = this.base;
    const pos = b ? Math.min(b.dur || Infinity, b.pos + (b.playing ? Date.now() - b.at : 0)) : 0;
    this.bar.style.transform = `scaleX(${b && b.dur ? (pos / b.dur).toFixed(4) : 0})`;
    setText(this.r.tpos, b ? Fmt.dur(pos) : '');
    setText(this.r.tdur, b && b.dur ? Fmt.dur(b.dur) : '');
    const t = performance.now() / 1000, on = b && b.playing && !Perf.battery;
    if (!on && this.barsIdle) return;
    this.barsIdle = !on;
    this.bars.forEach((e, i) => {
      const [ph, f] = this.phase[i];
      const v = on ? 0.12 + 0.75 * Math.abs(Math.sin(t * f + ph) * Math.sin(t * f * 0.37 + ph * 2)) : 0.06;
      e.style.transform = `scaleY(${v.toFixed(3)})`;
    });
  },
};

const VideoOv = {
  open_: false,
  init() {
    const e = this.el = $('#video-ov');
    e.innerHTML = `<video playsinline preload="auto"></video><div class="vstate"></div>
      <div class="vbar"><div class="vtitle"></div><div class="prog"><i></i></div>
      <div class="times"><span class="tpos"></span><span class="tdur"></span></div>
      <div class="ovhints"><span class="h"><span class="k">A</span>Play/Pause</span><span class="h"><span class="k">◀▶</span>10 s</span><span class="h"><span class="k">▲▼</span>1 min</span><span class="h"><span class="k">L1</span><span class="k">R1</span>Previous/Next</span><span class="h"><span class="k">Y</span>Options</span><span class="h"><span class="k">B</span>Back</span></div></div>`;
    this.v = $('video', e);
    this.st = $('.vstate', e);
    this.title = $('.vtitle', e);
    this.bar = $('.prog i', e);
    this.tpos = $('.tpos', e);
    this.tdur = $('.tdur', e);
    this.v.addEventListener('timeupdate', () => this.progress());
    this.v.addEventListener('loadedmetadata', () => this.progress());
    this.v.addEventListener('ended', () => (this.i < this.list.length - 1 ? this.go(1) : this.close()));
    this.v.addEventListener('error', () => this.fallback());
    for (const ev of ['play', 'pause', 'seeked', 'loadedmetadata']) this.v.addEventListener(ev, () => Companion.sync());
    this.v.addEventListener('play', () => Native.videoPlaying(String(this.list[this.i].id)));
    this.v.addEventListener('pause', () => Native.videoPlaying(''));
    e.addEventListener('click', () => { this.poke(); this.toggle(); });
  },
  open(list, i) {
    this.list = list;
    this.i = i;
    if (this.open_) return this.load();
    this.open_ = true;
    Sound.play('ok');
    const s = Media.source();
    if (s && s.playing) Media.control('pause');
    this.el.classList.add('on');
    Overlay.push(this);
    Bg.pause();
    this.load();
  },
  load() {
    const v = this.list[this.i];
    this.title.textContent = videoTitle(v);
    this.v.src = `/media/video?id=${v.id}`;
    this.v.play().catch(() => {});
    this.poke();
  },
  fallback() {
    if (!this.open_ || !this.v.getAttribute('src')) return;
    const v = this.list[this.i];
    this.close(true);
    Api.log('video fallback to native player: ' + v.id);
    Native.playVideoNative(videoPlaylist(this.list, this.i));
  },
  close(silent) {
    if (!this.open_) return;
    this.open_ = false;
    this.v.pause();
    this.v.removeAttribute('src');
    this.v.load();
    this.el.classList.remove('on');
    Overlay.pop(this);
    Bg.resume();
    if (!silent) Sound.play('back');
    Companion.sync();
  },
  pause() { if (this.open_) this.v.pause(); },
  toggle() {
    if (this.v.paused) { this.v.play().catch(() => {}); this.flash('play'); }
    else { this.v.pause(); this.flash('pause'); }
  },
  seek(d) {
    if (!isFinite(this.v.duration)) return;
    this.v.currentTime = clamp(this.v.currentTime + d, 0, this.v.duration - 0.5);
    this.flash(d < 0 ? 'prev' : 'next');
  },
  go(d) {
    const ni = clamp(this.i + d, 0, this.list.length - 1);
    if (ni === this.i) return Sound.play('bump');
    this.i = ni;
    this.load();
  },
  flash(g) {
    this.st.innerHTML = miniGlyph(g);
    this.st.classList.add('on');
    clearTimeout(this.ft);
    this.ft = setTimeout(() => this.st.classList.remove('on'), 650);
  },
  poke() {
    this.el.classList.remove('idle');
    clearTimeout(this.it);
    this.it = setTimeout(() => this.el.classList.add('idle'), 3200);
  },
  progress() {
    const d = this.v.duration, t = this.v.currentTime;
    this.bar.style.transform = `scaleX(${isFinite(d) && d ? (t / d).toFixed(4) : 0})`;
    setText(this.tpos, Fmt.dur(t * 1000));
    setText(this.tdur, isFinite(d) ? Fmt.dur(d * 1000) : '');
  },
  key(k) {
    this.poke();
    switch (k) {
      case 'ok': case 'playpause': return this.toggle();
      case 'left': return this.seek(-10);
      case 'right': return this.seek(10);
      case 'up': return this.seek(60);
      case 'down': return this.seek(-60);
      case 'l1': case 'prev': return this.go(-1);
      case 'r1': case 'next': return this.go(1);
      case 'options': {
        const v = this.list[this.i];
        return Panels.options(videoTitle(v), [
          { label: 'Play in Native Player', run: () => { this.close(true); Native.playVideoNative(videoPlaylist(this.list, this.i)); } },
          { label: 'Restart', run: () => { this.v.currentTime = 0; this.v.play().catch(() => {}); } },
        ]);
      }
      case 'back': return this.close();
      default: return null;
    }
  },
};

const PhotoOv = {
  open_: false,
  init() {
    const e = this.el = $('#photo-ov');
    e.innerHTML = '<img class="pa" alt=""><img class="pb" alt=""><div class="pcap"></div>';
    this.imgs = [$('.pa', e), $('.pb', e)];
    this.front = 0;
    this.cap = $('.pcap', e);
    e.addEventListener('click', ev => this.go(ev.clientX < innerWidth / 2 ? -1 : 1));
  },
  open(list, i, slideshow) {
    this.list = list;
    this.i = clamp(i, 0, list.length - 1);
    this.open_ = true;
    Sound.play('open');
    this.imgs.forEach(im => { im.style.opacity = 0; im.removeAttribute('src'); });
    this.el.classList.add('on');
    Overlay.push(this);
    Bg.pause();
    this.show();
    this.setSlideshow(!!slideshow);
  },
  url(p) { return `/img/thumb?type=image&id=${p.id}&s=1920`; },
  show() {
    const p = this.list[this.i];
    const back = this.imgs[1 - this.front], front = this.imgs[this.front];
    const want = this.url(p);
    back.onload = () => {
      if (back.getAttribute('src') !== want) return;
      back.style.opacity = 1;
      front.style.opacity = 0;
      this.front = 1 - this.front;
    };
    back.src = want;
    this.cap.textContent = `${p.title}  ·  ${this.i + 1} of ${this.list.length}${this.slide ? '  ·  Slideshow' : ''}`;
    const nx = this.list[this.i + 1];
    if (nx) new Image().src = this.url(nx);
    Companion.sync();
  },
  jump(i) {
    const ni = clamp(i, 0, this.list.length - 1);
    if (ni === this.i) return;
    this.i = ni;
    Sound.play('move');
    this.show();
  },
  go(d) {
    const n = this.list.length;
    const ni = this.slide ? (this.i + d + n) % n : clamp(this.i + d, 0, n - 1);
    if (ni === this.i) return Sound.play('bump');
    this.i = ni;
    this.show();
  },
  setSlideshow(on) {
    this.slide = on;
    clearInterval(this.timer);
    if (on) this.timer = setInterval(() => this.go(1), 5000);
    this.show();
  },
  close(silent) {
    if (!this.open_) return;
    this.open_ = false;
    clearInterval(this.timer);
    this.el.classList.remove('on');
    Overlay.pop(this);
    Bg.resume();
    if (!silent) Sound.play('back');
    Companion.sync();
  },
  key(k) {
    switch (k) {
      case 'left': case 'l1': return this.go(-1);
      case 'right': case 'r1': return this.go(1);
      case 'ok': return this.setSlideshow(!this.slide);
      case 'options': {
        const p = this.list[this.i];
        return Panels.options(p.title, [
          { label: this.slide ? 'Stop Slideshow' : 'Start Slideshow', run: () => this.setSlideshow(!this.slide) },
          { label: 'Set as Background', run: () => setCustomBackground(this.url(p)) },
        ]);
      }
      case 'back': return this.close();
      default: return null;
    }
  },
};
