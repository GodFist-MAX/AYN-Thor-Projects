'use strict';
/* Feeds the bottom screen: a view model of what the top screen shows, and touch commands back. */

const SETTING_HELP = {
  'set:theme': 'The menu background: rotating wallpapers, the light wave, or a picture of your own.',
  'set:bgMode': 'Wallpapers rotate behind the wave. Wave Only shows just the animated wave. Custom Image keeps one picture.',
  'set:waveColor': 'The color of the wave and highlights. Match Selection follows each category and console.',
  'set:waveOverlay': 'Draws the light wave over the wallpapers.',
  'set:rotateSec': 'How often wallpapers and game art change.',
  'set:heroArt': 'Shows game art behind the menu while a game is selected.',
  'set:custombg': 'Use one of your photos or a built-in wallpaper as the background.',
  'set:sound': 'Menu sounds and their volume.',
  'set:sounds': 'Plays a sound as you move through the menu.',
  'set:soundVol': 'How loud the menu sounds are.',
  'set:time': 'How the clock looks.',
  'set:clock24': 'Shows the time in 12-hour or 24-hour format.',
  'set:showDate': 'Shows the date next to the clock.',
  'set:games': 'Where CrossBar finds games, artwork and descriptions, and which emulator runs each system.',
  'set:romroot': 'CrossBar looks for games here, in one folder per system.',
  'set:romtree': 'Lets emulators open games straight from the ROM folder.',
  'set:rescan': 'Looks for new or removed games.',
  'set:downloadArt': 'Downloads box art, title screens and screenshots for your games.',
  'set:onlineGameInfo': 'Looks up game descriptions on Wikipedia when Cocoon has none. Only the game title is sent.',
  'set:emus': 'Choose which emulator runs each system.',
  'set:accounts': 'Connect Steam, Discord and YouTube to see friends and new videos.',
  'set:music': 'Which folders count as music, music while you play, and joystick lights that match what\'s playing.',
  'set:musicAllFolders': 'Off: only the Music folder. On: every audio file on the Thor.',
  'set:musicDuringGames': 'On: CrossBar music keeps playing over games and emulators, and stops when other media starts. Off: music pauses when a game starts.',
  'set:mediaLights': 'Colors the joystick lights from the artwork of whatever is playing in media and streaming apps. Your own light colors come back when playback stops or a game is in front.',
  'set:system': 'Android settings, permissions, the home screen and the bottom screen.',
  'set:bottomScreen': 'Game info, artwork and touch controls on the Thor\'s bottom screen.',
  'set:perf': 'Frame rate and battery use of both screens.',
  'set:perfPreset': '60 FPS is the smoothest; 30 FPS halves the drawing work. Battery Saver also slows the wave, stills it when you leave the menu alone, and keeps the bottom screen calm.',
  'set:devMode': 'For editing the theme over USB.',
  'set:about': 'Version and device information.',
};

function iconVM(ic) {
  if (!ic || ic.glyph || !ic.img) return { glyph: (ic && ic.glyph) || 'folder' };
  return { img: ic.img, shape: ic.shape || 'sq', style: ic.style || '', blur: ic.blur || '', fallback: ic.fallback || '' };
}

function cardVM(it) {
  const c = { id: it.id || '', kind: it.kind, label: it.label, icon: iconVM(it.icon) };
  if (it.subHtml) c.subHtml = it.subHtml;
  else if (it.sub) c.sub = String(it.sub);
  return c;
}

function gameVM(g) {
  const p = Model.platform(g.platform) || {};
  const h = (Model.H.history || {})['game:' + g.id] || {};
  const a = g.art || {};
  const m = g.meta || {};
  const big = a.boxart || a.cocoon_boxart || a.icon0 || a.cocoon_icon;
  return {
    id: g.id, platform: p.name || g.platform, short: p.short || '', glyph: p.glyph || 'game', accent: p.accent || '',
    title: m.longTitle && m.longTitle.length <= 48 ? m.longTitle : g.title,
    size: g.size || 0, tags: g.tags || '', publisher: m.publisher || '', serial: m.serial || '',
    last: h.last || 0, time: h.time || 0, count: h.count || 0,
    cover: sized(big || a.sqicon, 256) || a.pixelicon || '', small: !big, pixel: !big && !a.sqicon && !!a.pixelicon,
    logo: sized(a.cocoon_logo, 720) || '',
    shots: [a.snap, a.cocoon_screenshot_gameplay, a.titlescreen, a.cocoon_screenshot_title].filter(Boolean).slice(0, 4),
  };
}

function appVM(a) {
  const h = (Model.H.history || {})['app:' + a.pkg] || {};
  const feed = appFeed(a);
  return {
    pkg: a.pkg, label: a.label, kind: a.kind, kindLabel: KIND_LABEL[a.kind] || 'App', system: !!a.system,
    fgTime: a.fgTime || 0, last: Math.max(a.lastUsed || 0, h.last || 0), count: h.count || 0,
    icon: appIconUrl(a), isGame: a.kind === 'game',
    shortcuts: ((Model.H.shortcuts || {})[a.pkg] || []).slice(0, 6).map(s => ({ id: s.id, label: s.label })),
    feed, hasFeed: !!a.feed || feed.length > 0,
  };
}

/* Bottom-screen sections CrossBar fills itself; the rest come from /api/appfeed (Feeds.java, same format). */
function appFeed(a) {
  const out = [];
  const plats = (Model.H.platforms || []).filter(p => p.emulatorPkg === a.pkg || p.appPkg === a.pkg).map(p => p.id);
  const games = plats.length ? recentGames(plats, 10) : [];
  if (games.length) out.push({ title: 'Recently Played', style: 'cards', items: games });
  const movies = a.pkg === GTV_PKG ? Model.H.googleTv || [] : [];
  if (movies.length) {
    out.push({ title: 'Downloaded · Plays Offline', style: 'posters', items: movies.slice(0, 12).map(m =>
      ({ title: m.title || 'Downloaded movie', sub: 'Google TV', img: m.poster, cmd: { t: 'movie', id: m.id } })) });
  }
  return out;
}

/* Games CrossBar started lately on these systems, newest first. */
function recentGames(platforms, n) {
  const hist = Model.H.history || {};
  const last = g => (hist['game:' + g.id] || {}).last || 0;
  return (Model.H.games || []).filter(g => platforms.includes(g.platform) && last(g)).sort((x, y) => last(y) - last(x)).slice(0, n).map(g => {
    const v = gameVM(g);
    return { title: v.title, sub: [v.short, Fmt.played(v.time)].filter(Boolean).join(' · '), img: v.cover, pixel: v.pixel, at: v.last, cmd: { t: 'game', id: g.id } };
  });
}

/* Folders that load on open (songs, photos, videos) are loaded early so the bottom screen can preview them. */
function preload(it) {
  if (it._preload) return;
  it._preload = it.load().then(ch => {
    if (!it.children) it.children = ch;
    Companion.sync();
  }).catch(() => {});
}

function itemVM(it) {
  const d = it.detail || {};
  const o = cardVM(it);
  o.title = d.title || it.label;
  o.meta = (d.meta || []).filter(Boolean);
  if (d.desc) o.desc = d.desc;
  o.action = it.kind === 'folder' ? 'Open' : it.run ? (d.action || 'Select') : '';
  o.options = !!it.options;
  if (d.logo) o.logo = d.logo;
  if (it.heroes && it.heroes.length) o.heroes = it.heroes.slice(0, 4).map(u => sized(u, 1280));
  if (d.cards) o.cards = d.cards.map(c => c && c.img).filter(Boolean);
  if (it.game) o.game = gameVM(it.game);
  if (it.app) o.app = appVM(it.app);
  if (it.platform) {
    const games = recentGames([it.platform.id], 8);
    o.platform = Object.assign({}, it.platform, { feed: games.length ? [{ title: 'Recently Played', style: 'cards', items: games }] : [] });
  }
  if (it.movie) o.movie = { poster: it.movie.poster, hero: it.movie.hero, portrait: !!it.movie.portrait };
  if (it.song) o.song = it.song;
  if (it.video) o.video = it.video;
  if (it.yt) o.yt = { id: it.yt.id, channel: it.yt.channel, published: it.yt.published };
  if (it.photo) o.photo = { id: it.photo.id, folder: it.photo.folder, added: it.photo.added };
  if (it.friend) o.friend = it.friend;
  if (it.notif) o.notif = { app: it.notif.app, pkg: it.notif.pkg, text: it.notif.text || '', time: it.notif.time };
  if (SETTING_HELP[it.id]) o.help = SETTING_HELP[it.id];
  if (it.kind === 'folder') {
    if (!it.children && it.load) preload(it);
    if (it.children) {
      o.count = it.children.length;
      o.children = it.children.slice(0, 60).map(cardVM);
    } else {
      o.loading = !!it.load;
    }
  }
  return o;
}

function overlayVM() {
  const o = Overlay.top();
  if (!o) return null;
  if (o === Opts) {
    return { type: 'options', title: Opts.title.textContent, si: Opts.si,
      items: Opts.items.map(x => ({ label: x.label, checked: x.checked == null ? null : !!x.checked, off: !!x.disabled })) };
  }
  if (o === Dialog) {
    const p = $('p', Dialog.box);
    return { type: 'dialog', title: ($('h2', Dialog.box) || {}).textContent || '', text: p ? p.textContent : '',
      input: !!Dialog.input, bi: Dialog.bi, buttons: Dialog.buttons.map(b => b.label) };
  }
  if (o === MusicOv) return { type: 'music' };
  if (o === VideoOv) {
    const v = VideoOv.list[VideoOv.i] || {}, e = VideoOv.v;
    return { type: 'video', title: v.title || '', id: v.id, i: VideoOv.i, n: VideoOv.list.length,
      paused: e.paused, pos: Math.round(e.currentTime || 0), dur: isFinite(e.duration) ? Math.round(e.duration) : 0 };
  }
  if (o === PhotoOv) {
    const i = PhotoOv.i, off = Math.max(0, Math.min(i - 20, PhotoOv.list.length - 40));
    return { type: 'photo', i, off, n: PhotoOv.list.length, slide: !!PhotoOv.slide, title: (PhotoOv.list[i] || {}).title || '',
      ids: PhotoOv.list.slice(off, off + 40).map(p => p.id) };
  }
  return { type: 'other' };
}

function friendsVM() {
  const S = Model.social;
  if (!S) return [];
  const out = [];
  for (const f of (S.steam && S.steam.friends) || []) {
    if (f.state > 0 || f.game) out.push({ name: f.name, avatar: f.avatar || '', game: f.game || '', src: 'steam' });
  }
  for (const f of (S.discord && S.discord.friends) || []) {
    if (f.status && f.status !== 'offline') out.push({ name: f.name, avatar: f.avatar || '', game: f.game || '', src: 'discord' });
  }
  out.sort((a, b) => (b.game ? 1 : 0) - (a.game ? 1 : 0));
  return out.slice(0, 12);
}

/* What the bottom screen shows while a game or app covers the menu. */
function launchVM(it) {
  const o = { title: it.label, sub: '', img: '', hero: '', glyph: 'game', at: Date.now() };
  if (it.game) {
    const g = gameVM(it.game);
    Object.assign(o, { title: g.title, sub: g.platform, img: g.cover, pixel: g.pixel, small: g.small, glyph: g.glyph, game: g.id });
    o.hero = sized((it.heroes || [])[0], 1280) || '';
  } else if (it.app) {
    Object.assign(o, { sub: KIND_LABEL[it.app.kind] || 'App', img: appIconUrl(it.app), app: it.app.pkg, appIcon: true });
  } else {
    o.sub = it.sub ? String(it.sub) : '';
    o.img = it.icon && it.icon.img ? it.icon.img : '';
    o.hero = (it.heroes || [])[0] || '';
  }
  return o;
}

const Companion = {
  last: '',
  timer: 0,
  launch: null,

  init() {
    setInterval(() => { if (!CB.paused) this.send(); }, 5000);
  },

  sync(now) {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.send(), now ? 0 : 80);
  },

  send() {
    if (!CB.ready) return;
    let s;
    try {
      s = JSON.stringify(this.build());
    } catch (e) {
      Api.log('bottom screen view: ' + (e && (e.stack || e.message)));
      return;
    }
    if (s === this.last) return;
    this.last = s;
    Native.bottom(s);
  },

  /* Call right before something opens over the menu, so the bottom screen can show it. */
  launched(it) {
    this.launch = it ? launchVM(it) : null;
    this.sync(true);
  },

  build() {
    const cat = XMB.category() || {};
    const items = XMB.list();
    const si = XMB.index();
    const it = items[si];
    const off = Math.max(0, Math.min(si - 30, items.length - 60));
    return {
      accent: Bg.accent || cat.accent,
      bg: Bg.current(),
      perf: Perf.id,
      h24: !!Model.settings().clock24,
      cats: XMB.cats.map(c => ({ id: c.id, label: c.label, glyph: c.glyph })),
      cat: cat.id,
      path: [cat.label, ...XMB.stack.map(x => x.folder.label)],
      depth: XMB.stack.length,
      item: it ? itemVM(it) : null,
      list: { off, sel: si, items: items.slice(off, off + 60).map(cardVM) },
      ov: overlayVM(),
      friends: friendsVM(),
      launch: this.launch,
    };
  },

  /* A touch on the bottom screen. */
  command(c) {
    if (Overlay.top() && !['opt', 'dialog', 'video', 'photo', 'music', 'key'].includes(c.t)) Overlay.closeAll();
    switch (c.t) {
      case 'key': return CB.key(c.k, false);
      case 'select': return XMB.selectId(c.id, c.run);
      case 'child': return XMB.openChild(c.id, c.run);
      case 'cat': return XMB.showCategory(c.id);
      case 'goto': return XMB.goto(c.cat, c.path || []).then(() => { if (c.sel) XMB.selectId(c.sel, c.run); });
      case 'home': return XMB.home();
      case 'opt': return Overlay.top() === Opts ? Opts.pick(c.i) : null;
      case 'dialog': return Overlay.top() === Dialog ? Dialog.pick(c.i) : null;
      case 'music': return c.cmd === 'open' ? Media.openMusic() : MusicOv.key(c.cmd);
      case 'video': return Overlay.top() === VideoOv ? VideoOv.key(c.cmd) : null;
      case 'photo': return Overlay.top() === PhotoOv ? PhotoOv.jump(c.i) : null;
      case 'shortcut': Companion.launched(XMB.current()); return startShortcut(c.pkg, c.id);
      case 'appBottom': Sound.play('ok'); return report(Native.launchAppOnBottom(c.pkg));
      // Items in the bottom screen's app sections (appFeed and Feeds.java).
      case 'feed': Sound.play('ok'); Companion.launched(XMB.current()); return report(Native.openFeed(c.pkg, JSON.stringify(c.open || {})));
      case 'game': { const g = (Model.H.games || []).find(x => x.id === c.id); return g ? launchGame(g) : null; }
      case 'movie': { const m = (Model.H.googleTv || []).find(x => String(x.id) === String(c.id)); return m ? openUrl(m.url, GTV_PKG) : null; }
      case 'launch': { const a = Model.app(c.pkg); return a ? launchApp(a) : null; }
      default: return null;
    }
  },
};
