'use strict';
/* Builds the category tree (XMB columns) from the device data. */

let WALLS = {};
const CAT_ACCENT = {
  settings: '#7d8ea8', photo: '#e0a03a', music: '#e0408a', video: '#d23a48', tv: '#8a52e6',
  game: '#2f6bff', apps: '#1fb5a5', network: '#22b8e0', friends: '#ff8a3d',
};
const KIND_LABEL = { game: 'Android Game', emulator: 'Emulator', video: 'Video', music: 'Music', social: 'Social', network: 'Internet', photo: 'Photos', app: 'App' };
const APP_GLYPH = {
  'com.google.android.youtube': 'youtube', 'com.google.android.videos': 'googletv', 'com.google.android.apps.youtube.music': 'ytmusic',
  'com.twitter.android': 'x', 'com.discord': 'discord', 'com.valvesoftware.android.steam.community': 'steam',
  'com.tiktok.lite.go': 'tiktok', 'com.tubitv': 'tubi', 'com.opera.browser': 'browser', 'com.android.chrome': 'browser',
  'com.google.android.apps.docs': 'drive', 'com.google.android.apps.chromecast.app': 'cast', 'com.android.vending': 'store',
  'dev.imranr.obtainium': 'refresh', 'james.dsp': 'sound',
};
const STEAM_PKG = 'com.valvesoftware.android.steam.community';
const GTV_PKG = 'com.google.android.videos';
const YT_PKG = 'com.google.android.youtube';
const X_PKG = 'com.twitter.android';
const DISCORD_PKG = 'com.discord';

const Model = {
  H: null,
  social: null,
  cache: {},
  appByPkg: {},

  setHome(H) {
    this.H = H;
    this.appByPkg = {};
    for (const a of H.apps) this.appByPkg[a.pkg] = a;
  },
  app(pkg) { return this.appByPkg[pkg]; },
  platform(id) { return (this.H.platforms || []).find(p => p.id === id); },
  settings() { return this.H.settings || {}; },

  async songs() { return this.cache.songs || (this.cache.songs = await Api.get('/api/music')); },
  async videos() { return this.cache.videos || (this.cache.videos = await Api.get('/api/videos')); },
  async photos() { return this.cache.photos || (this.cache.photos = await Api.get('/api/photos')); },

  build() {
    return [settingsCat(), photoCat(), musicCat(), videoCat(), tvCat(), gameCat(), appsCat(), networkCat(), friendsCat()];
  },
};

// ---------- item helpers ----------
/* Like Object.assign, but keeps getters (live subtitles) instead of freezing their current value. */
function props(target, ...sources) {
  for (const src of sources) if (src) Object.defineProperties(target, Object.getOwnPropertyDescriptors(src));
  return target;
}
function mk(o) { return props({ kind: 'item', label: '', sub: '', icon: { glyph: 'folder' } }, o); }
function folderItem(label, g, o = {}) { return mk(props({ kind: 'folder', label, icon: { glyph: g } }, o)); }
function infoItem(label, sub, g = 'info', o = {}) { return mk(props({ kind: 'info', label, sub, icon: { glyph: g } }, o)); }
function actionItem(label, g, run, o = {}) { return mk(props({ kind: 'action', label, icon: { glyph: g }, run }, o)); }
function wallsFor(key) {
  const custom = Model.H && Model.H.wallpapers && Model.H.wallpapers[key];
  return custom && custom.length ? custom : (WALLS[key] || []);
}
function appIconUrl(a) { return `/img/app?pkg=${encodeURIComponent(a.pkg)}&cls=${encodeURIComponent(a.cls || '')}`; }
function wide(u) { return u ? u + (u.includes('?') ? '&' : '?') + 'w=1920' : null; }

function report(err) { if (err) Panels.error(err); }
function launchApp(a) { Sound.play('ok'); Companion.launched(XMB.current()); XMB.launchFx(() => report(Native.launchApp(a.pkg))); }
function openUrl(url, pkg) { Sound.play('ok'); Companion.launched(XMB.current()); report(Native.openUrl(url, pkg || '')); }
function startShortcut(pkg, id) { Sound.play('ok'); Companion.launched(XMB.current()); report(Native.startShortcut(pkg, id)); }
function launchGame(g, player = '') {
  Sound.play('ok');
  Companion.launched(XMB.current());
  XMB.launchFx(() => report(Native.launchGame(g.id, player)));
}
function openOnBottom(a) { Sound.play('ok'); report(Native.launchAppOnBottom(a.pkg)); }
function rescan() {
  Panels.toast('Scanning for games…', 'refresh');
  Native.rescan();
  if (!ON_DEVICE) Api.get('/api/rescan').then(() => CB.reload());
}

function appMeta(a) {
  return [KIND_LABEL[a.kind] || 'App', Fmt.played(a.fgTime), a.lastUsed ? 'Used ' + Fmt.ago(a.lastUsed) : ''].filter(Boolean);
}
function appOptions(a) {
  const opts = [{ label: 'Open', run: () => launchApp(a) }];
  if ((Model.H.device || {}).dualScreen) opts.push({ label: 'Open on Bottom Screen', run: () => openOnBottom(a) });
  const sc = (Model.H.shortcuts || {})[a.pkg] || [];
  sc.slice(0, 5).forEach(s => opts.push({ label: s.label, run: () => startShortcut(a.pkg, s.id) }));
  opts.push({ label: 'App Info', run: () => Native.appInfo(a.pkg) });
  if (!a.system) opts.push({ label: 'Uninstall', run: () => Native.uninstall(a.pkg) });
  return opts;
}
function appItem(a, o = {}) {
  const g = o.glyph;
  delete o.glyph;
  return mk(props({
    kind: 'app', id: 'app:' + a.pkg, label: a.label, sub: KIND_LABEL[a.kind] || 'App', app: a,
    icon: g ? { glyph: g } : { img: appIconUrl(a), shape: 'sq', style: 'app' },
    detail: { title: a.label, meta: appMeta(a), action: 'Open' },
    run: () => launchApp(a),
    options: () => appOptions(a),
  }, o));
}

// ---------- Game ----------
function gameIcon(g, p) {
  const a = g.art || {};
  const tile = (img, style, blur) => ({ img: sized(img, 256), shape: 'wide', style, blur: sized(blur, 128) });
  if (a.icon0) return tile(a.icon0, 'tile');
  if (a.cocoon_icon) return tile(a.cocoon_icon, 'tile contain', a.cocoon_hero || a.cocoon_icon);
  if (a.boxart || a.cocoon_boxart) return tile(a.boxart || a.cocoon_boxart, 'tile contain', a.snap || a.boxart);
  if (a.sqicon) return tile(a.sqicon, 'tile contain', a.sqicon);
  if (a.pixelicon) return { img: a.pixelicon, shape: 'wide', style: 'tile pixel', blur: a.pixelicon };
  return { glyph: p.glyph };
}

function gameItem(g, o = {}) {
  const p = Model.platform(g.platform) || { short: g.platform, name: g.platform, glyph: 'game', accent: CAT_ACCENT.game, players: [] };
  const a = g.art || {};
  const meta = g.meta || {};
  const h = (Model.H.history || {})['game:' + g.id] || {};
  const tags = (g.tags || '').split(' · ').filter(Boolean);
  const heroes = [a.pic1, a.cocoon_hero, a.cocoon_screenshot_gameplay, a.snap, a.cocoon_screenshot_title, a.titlescreen]
    .filter(Boolean).map(wide);
  const cards = [a.snap, a.cocoon_screenshot_gameplay, a.titlescreen, a.cocoon_screenshot_title, a.boxart]
    .filter(Boolean).slice(0, 3).map(u => ({ img: sized(u, 320) }));
  const title = meta.longTitle && meta.longTitle.length <= 48 ? meta.longTitle : g.title;
  return mk({
    kind: 'game', id: (o.recent ? 'recent:' : 'game:') + g.id, game: g,
    label: g.title,
    sub: o.recent ? `${p.short} · Played ${Fmt.ago(h.last)}` : [p.short, tags[0]].filter(Boolean).join(' · '),
    icon: gameIcon(g, p), heroes, accent: p.accent, wallKey: p.id,
    detail: {
      title, logo: sized(a.cocoon_logo, 560),
      meta: [p.name, tags[0], Fmt.size(g.size), h.last ? 'Played ' + Fmt.ago(h.last) : 'Not played yet', Fmt.played(h.time)].filter(Boolean),
      desc: [meta.publisher, meta.serial].filter(Boolean).join(' · '),
      action: 'Play', cards,
    },
    run: () => launchGame(g),
    options: () => {
      const opts = [{ label: 'Play', run: () => launchGame(g) }];
      if ((p.players || []).length > 1) p.players.forEach(pl => opts.push({ label: 'Play with ' + pl.name, run: () => launchGame(g, pl.key) }));
      opts.push({ label: 'Information', run: () => Panels.alert(title, [p.name, g.file, Fmt.size(g.size), tags.join(', '), meta.publisher, meta.serial, h.last ? 'Last played ' + Fmt.ago(h.last) : '', Fmt.played(h.time)].filter(Boolean).join('\n')) });
      opts.push({ label: 'Rescan Games', run: rescan });
      return opts;
    },
  });
}

function androidGameItem(a, o = {}) {
  const h = (Model.H.history || {})['app:' + a.pkg] || {};
  const last = Math.max(h.last || 0, a.lastUsed || 0);
  return appItem(a, {
    kind: 'app', id: (o.recent ? 'recent:' : '') + 'app:' + a.pkg,
    sub: o.recent ? `Android · Played ${Fmt.ago(last)}` : (Fmt.played(a.fgTime) || 'Android game'),
    wallKey: 'android', accent: '#3ddc84', heroes: [appIconUrl(a)], iconBlur: true,
    detail: { title: a.label, meta: ['Android', Fmt.played(a.fgTime), last ? 'Played ' + Fmt.ago(last) : ''].filter(Boolean), action: 'Play' },
  });
}

function chooseEmulator(p) {
  const cur = (Model.settings().players || {})[p.id] || '';
  Panels.choose('Default Emulator', p.players.map(pl => ({ label: pl.name, value: pl.key, checked: pl.key === cur || (!cur && pl.name === p.emulator) })), v => {
    const players = Object.assign({}, Model.settings().players || {}, { [p.id]: v });
    saveSetting('players', players).then(() => CB.reload());
  });
}

function platformFolder(p, games) {
  const emuApp = p.emulatorPkg && Model.app(p.emulatorPkg);
  const emuName = emuApp ? emuApp.label : (p.emulator || 'No emulator installed');
  const children = games.map(g => gameItem(g));
  if (!games.length) children.push(infoItem('No games yet', `Copy ${p.short} games into the Roms folder on the Thor`, 'info'));
  if (emuApp) {
    children.push(appItem(emuApp, {
      id: 'emu:' + p.id, label: 'Open ' + emuApp.label, sub: 'Emulator settings and library', glyph: p.glyph,
      wallKey: p.id, accent: p.accent, heroes: null, iconBlur: false,
    }));
  }
  const cards = games.map(g => {
    const a = g.art || {};
    return { img: sized(a.icon0 || a.boxart || a.cocoon_hero || a.snap || a.sqicon || a.pixelicon, 320) };
  }).filter(c => c.img).slice(0, 3);
  const opts = [];
  if (emuApp) opts.push({ label: 'Open ' + emuName, run: () => launchApp(emuApp) });
  if ((p.players || []).length > 1) opts.push({ label: 'Default Emulator…', run: () => chooseEmulator(p) });
  opts.push({ label: 'Rescan Games', run: rescan });
  const hist = Model.H.history || {};
  const played = games.reduce((n, g) => n + ((hist['game:' + g.id] || {}).time || 0), 0);
  return folderItem(p.name, p.glyph, {
    id: 'plat:' + p.id, sub: `${emuName} · ${Fmt.plural(games.length, 'game')}`,
    platform: { id: p.id, name: p.name, short: p.short, glyph: p.glyph, emulator: emuName, emuIcon: emuApp ? appIconUrl(emuApp) : '',
      emuPkg: emuApp ? p.emulatorPkg : '',
      games: games.length, size: games.reduce((n, g) => n + (g.size || 0), 0), played },
    wallKey: p.id, accent: p.accent, children,
    detail: { title: p.name, meta: [emuName, Fmt.plural(games.length, 'game')], cards },
    options: () => opts,
  });
}

function gameCat() {
  const H = Model.H;
  const items = [];
  const hist = H.history || {};
  const recent = [];
  for (const g of H.games) {
    const h = hist['game:' + g.id];
    if (h && h.last) recent.push([h.last, () => gameItem(g, { recent: true })]);
  }
  for (const a of H.apps) {
    if (a.kind !== 'game') continue;
    const h = hist['app:' + a.pkg];
    const t = Math.max(h ? h.last : 0, a.lastUsed || 0);
    if (t && Date.now() - t < 21 * 864e5) recent.push([t, () => androidGameItem(a, { recent: true })]);
  }
  recent.sort((x, y) => y[0] - x[0]);
  recent.slice(0, 3).forEach(r => items.push(r[1]()));

  const dedicated = ['psp', 'ps2', 'nds', 'n3ds', 'switch'];
  for (const p of H.platforms) {
    if (p.id === 'retroarch' || p.id === 'rpgmaker') continue;
    const games = H.games.filter(g => g.platform === p.id);
    const hasEmu = dedicated.includes(p.id) && p.emulatorPkg && Model.app(p.emulatorPkg);
    if (games.length || hasEmu) items.push(platformFolder(p, games));
  }
  const ra = Model.platform('retroarch');
  const raApp = ra && Model.app(ra.appPkg);
  if (raApp) {
    items.push(appItem(raApp, {
      id: 'emu:retroarch', label: 'RetroArch', sub: 'Classic consoles · cores and playlists', glyph: 'retroarch',
      wallKey: 'retroarch', accent: ra.accent,
      detail: { title: 'RetroArch', meta: ['Multi-system emulator', Fmt.played(raApp.fgTime)].filter(Boolean), action: 'Open' },
    }));
  }
  const rpg = Model.platform('rpgmaker');
  const joi = rpg && Model.app(rpg.appPkg);
  if (joi) {
    items.push(appItem(joi, {
      id: 'emu:joiplay', label: 'RPG Maker', sub: 'JoiPlay · RPG Maker and Ren\'Py games', glyph: 'joiplay',
      wallKey: 'rpgmaker', accent: rpg.accent,
      detail: { title: 'RPG Maker', meta: ['JoiPlay', Fmt.played(joi.fgTime)].filter(Boolean), action: 'Open' },
    }));
  }
  const ag = H.apps.filter(a => a.kind === 'game');
  if (ag.length) {
    items.push(folderItem('Android Games', 'android', {
      id: 'folder:android', sub: Fmt.plural(ag.length, 'game'), wallKey: 'android', accent: '#3ddc84',
      children: ag.map(a => androidGameItem(a)),
      detail: { title: 'Android Games', meta: [Fmt.plural(ag.length, 'game')], cards: [] },
    }));
  }
  return { id: 'game', label: 'Game', glyph: 'game', accent: CAT_ACCENT.game, wallKey: 'game', items };
}

// ---------- TV & Video ----------
function googleTvFolder(app) {
  const movies = Model.H.googleTv || [];
  const movieItems = movies.map(m => mk({
    kind: 'movie', id: 'gtv:' + m.id, label: m.title || 'Downloaded movie', sub: 'Downloaded · plays offline', movie: m,
    icon: m.portrait ? { img: m.poster, shape: 'wide', style: 'tile contain', blur: m.poster, fallback: 'film' } : { img: m.poster, shape: 'wide', style: 'tile', fallback: 'film' },
    heroes: [m.hero], iconBlur: !!m.portrait, accent: CAT_ACCENT.tv, wallKey: 'tv',
    detail: { title: m.title || 'Downloaded movie', meta: ['Google TV', 'Downloaded'], desc: 'Opens in Google TV, where the download plays offline.', action: 'Watch' },
    run: () => openUrl(m.url, GTV_PKG),
  }));
  if (!movieItems.length) movieItems.push(infoItem('No downloads found', 'Download movies in Google TV, then run the sync script on the Mac', 'info'));
  const children = [folderItem('Downloaded Movies', 'film', {
    id: 'gtv:downloads', sub: Fmt.plural(movies.length, 'movie'), children: movieItems, wallKey: 'tv',
    detail: movies.length ? { title: 'Downloaded Movies', meta: [Fmt.plural(movies.length, 'movie'), 'Google TV'], cards: movies.slice(0, 3).map(m => ({ img: m.poster })) } : null,
  })];
  const sc = (Model.H.shortcuts || {})[GTV_PKG] || [];
  sc.forEach(s => children.push(actionItem(s.label, 'googletv', () => startShortcut(GTV_PKG, s.id), { id: 'gtvsc:' + s.id, sub: s.long || 'Google TV' })));
  children.push(appItem(app, { id: 'open:gtv', label: 'Open Google TV', sub: 'Library, store and watchlist', glyph: 'googletv', wallKey: 'tv' }));
  return folderItem('Google TV', 'googletv', {
    id: 'folder:gtv', sub: movies.length ? Fmt.plural(movies.length, 'downloaded movie') : 'Movies and shows', children, wallKey: 'tv',
    detail: { title: 'Google TV', meta: movies.length ? [Fmt.plural(movies.length, 'downloaded movie')] : ['Movies and shows'], cards: movies.slice(0, 3).map(m => ({ img: m.poster })) },
  });
}

function ytVideoItem(v, prefix) {
  return mk({
    kind: 'video', id: prefix + v.id, label: v.title, sub: `${v.channel} · ${Fmt.ago(Date.parse(v.published))}`, yt: v,
    icon: { img: v.thumb, shape: 'wide', style: 'tile' }, heroes: [`https://i.ytimg.com/vi/${v.id}/maxresdefault.jpg`, v.thumb],
    detail: { title: v.title, meta: [v.channel, Fmt.ago(Date.parse(v.published))], action: 'Watch' },
    run: () => openUrl('https://www.youtube.com/watch?v=' + v.id, YT_PKG),
  });
}

function youtubeFolder(app) {
  const links = [['Home', 'https://www.youtube.com/', 'youtube'], ['Subscriptions', 'https://www.youtube.com/feed/subscriptions', 'youtube'],
    ['Shorts', 'https://www.youtube.com/shorts', 'youtube'], ['Watch Later', 'https://www.youtube.com/playlist?list=WL', 'clock'],
    ['History', 'https://www.youtube.com/feed/history', 'refresh']];
  const children = [];
  const vids = (Model.social && Model.social.youtube && Model.social.youtube.videos) || [];
  if (vids.length) {
    children.push(folderItem('Latest From Your Channels', 'youtube', { id: 'yt:latest', sub: Fmt.plural(vids.length, 'video'), children: vids.map(v => ytVideoItem(v, 'ytv:')) }));
  }
  links.forEach(([label, url, g]) => children.push(actionItem(label, g, () => openUrl(url, YT_PKG), { id: 'yt:' + label, sub: 'YouTube' })));
  children.push(appItem(app, { id: 'open:yt', label: 'Open YouTube', glyph: 'youtube' }));
  return folderItem('YouTube', 'youtube', { id: 'folder:yt', sub: 'Subscriptions, Shorts, Watch Later', children, wallKey: 'tv' });
}

function tvCat() {
  const H = Model.H;
  const items = [];
  const gtv = Model.app(GTV_PKG);
  if (gtv) items.push(googleTvFolder(gtv));
  const yt = Model.app(YT_PKG);
  if (yt) items.push(youtubeFolder(yt));
  H.apps.filter(a => a.kind === 'video' && a.pkg !== GTV_PKG && a.pkg !== YT_PKG)
    .forEach(a => items.push(appItem(a, { glyph: APP_GLYPH[a.pkg], sub: 'Watch', wallKey: 'tv' })));
  if (!items.length) items.push(infoItem('No TV apps installed', 'Install Google TV or YouTube to see them here', 'tv'));
  return { id: 'tv', label: 'TV & Video', glyph: 'tv', accent: CAT_ACCENT.tv, wallKey: 'tv', items };
}

// ---------- Music ----------
function playSongs(list, index, shuffle) {
  Sound.play('ok');
  Native.music('play', JSON.stringify({ ids: list.map(s => s.id), index, shuffle: !!shuffle }));
  setTimeout(() => Media.openMusic(), 350);
}
function songItem(s, list, i) {
  return mk({
    kind: 'song', id: 'song:' + s.id, label: s.title, sub: `${s.artist} · ${Fmt.dur(s.duration)}`, song: s,
    icon: { img: `/img/thumb?type=audio&id=${s.id}&s=256`, shape: 'sq', style: 'photo', fallback: 'song' },
    run: () => playSongs(list, i),
    options: () => [{ label: 'Play', run: () => playSongs(list, i) }, { label: 'Shuffle From Here', run: () => playSongs(list, i, true) }],
  });
}
function groupBy(list, key) {
  const m = new Map();
  for (const s of list) {
    const k = s[key] || 'Unknown';
    if (!m.has(k)) m.set(k, []);
    m.get(k).push(s);
  }
  return [...m.entries()].sort((a, b) => a[0].localeCompare(b[0]));
}
function albumFolders(songs) {
  return groupBy(songs, 'album').map(([album, list]) => {
    list.sort((a, b) => (a.track || 0) - (b.track || 0));
    return folderItem(album, 'album', {
      id: 'album:' + album, sub: `${list[0].artist} · ${Fmt.plural(list.length, 'song')}`,
      icon: { img: `/img/thumb?type=audio&id=${list[0].id}&s=256`, shape: 'sq', style: 'photo', fallback: 'album' },
      children: list.map((s, i) => songItem(s, list, i)),
    });
  });
}
function artistFolders(songs) {
  return groupBy(songs, 'artist').map(([artist, list]) => folderItem(artist, 'artist', {
    id: 'artist:' + artist, sub: Fmt.plural(list.length, 'song'), children: list.map((s, i) => songItem(s, list, i)),
  }));
}
function nowPlayingItem() {
  return actionItem('Now Playing', 'music', () => Media.openMusic(), {
    id: 'music:np', get sub() { return Media.npText() || 'Nothing playing'; },
  });
}
function musicCat() {
  const H = Model.H;
  const n = (H.media || {}).music || 0;
  const items = [nowPlayingItem()];
  if (n) {
    items.push(folderItem('All Songs', 'song', { id: 'music:all', sub: Fmt.plural(n, 'song'), load: async () => { const s = await Model.songs(); return s.map((x, i) => songItem(x, s, i)); } }));
    items.push(actionItem('Shuffle All', 'shuffle', async () => playSongs(await Model.songs(), 0, true), { id: 'music:shuffle', sub: 'Play every song in random order' }));
    items.push(folderItem('Albums', 'album', { id: 'music:albums', load: async () => albumFolders(await Model.songs()) }));
    items.push(folderItem('Artists', 'artist', { id: 'music:artists', load: async () => artistFolders(await Model.songs()) }));
  } else {
    items.push(infoItem('No music on this device', 'Copy songs into the Music folder on the Thor', 'music'));
  }
  H.apps.filter(a => a.kind === 'music').forEach(a => items.push(appItem(a, { glyph: APP_GLYPH[a.pkg], sub: 'Music app', wallKey: 'music' })));
  return { id: 'music', label: 'Music', glyph: 'music', accent: CAT_ACCENT.music, wallKey: 'music', items };
}

// ---------- Video ----------
function videoItem(v, list, i) {
  const thumb = `/img/thumb?type=video&id=${v.id}&s=480`;
  return mk({
    kind: 'video', id: 'video:' + v.id, label: v.title, sub: `${Fmt.dur(v.duration)} · ${v.folder || 'Videos'}`, video: v,
    icon: { img: thumb, shape: 'wide', style: 'tile', fallback: 'film' },
    heroes: [`/img/thumb?type=video&id=${v.id}&s=1280`],
    detail: { title: v.title, meta: [Fmt.dur(v.duration), v.width ? `${v.width}×${v.height}` : '', Fmt.size(v.size), v.folder].filter(Boolean), action: 'Play' },
    run: () => Media.playVideo(list, i),
    options: () => [{ label: 'Play', run: () => Media.playVideo(list, i) }, { label: 'Play in Native Player', run: () => Native.playVideoNative(String(v.id)) }],
  });
}
function videoCat() {
  const H = Model.H;
  const n = (H.media || {}).videos || 0;
  const items = [];
  if (n) {
    items.push(folderItem('All Videos', 'video', { id: 'video:all', sub: Fmt.plural(n, 'video'), load: async () => { const v = await Model.videos(); return v.map((x, i) => videoItem(x, v, i)); } }));
    items.push(folderItem('Folders', 'folder', {
      id: 'video:folders', sub: 'Videos grouped by folder',
      load: async () => groupBy(await Model.videos(), 'folder').map(([f, list]) => folderItem(f, 'folder', {
        id: 'vfolder:' + f, sub: Fmt.plural(list.length, 'video'), children: list.map((x, i) => videoItem(x, list, i)),
      })),
    }));
  } else {
    items.push(infoItem('No videos on this device', 'Copy videos into the Movies folder on the Thor', 'video'));
  }
  const gtv = Model.app(GTV_PKG);
  if (gtv) items.push(googleTvFolder(gtv));
  return { id: 'video', label: 'Video', glyph: 'video', accent: CAT_ACCENT.video, wallKey: 'video', items };
}

// ---------- Photo ----------
function photoItem(p, list, i) {
  return mk({
    kind: 'photo', id: 'photo:' + p.id, label: p.title, sub: `${p.folder || 'Photos'} · ${new Date(p.added * 1000).toLocaleDateString()}`, photo: p,
    icon: { img: `/img/thumb?type=image&id=${p.id}&s=320`, shape: 'sq', style: 'photo', fallback: 'image' },
    run: () => Media.viewPhotos(list, i),
    options: () => [{ label: 'View', run: () => Media.viewPhotos(list, i) }, { label: 'Slideshow', run: () => Media.viewPhotos(list, i, true) },
      { label: 'Set as Background', run: () => setCustomBackground(`/img/thumb?type=image&id=${p.id}&s=1920`) }],
  });
}
function photoCat() {
  const n = (Model.H.media || {}).photos || 0;
  const items = [];
  if (n) {
    items.push(folderItem('All Photos', 'image', { id: 'photo:all', sub: Fmt.plural(n, 'photo'), load: async () => { const p = await Model.photos(); return p.map((x, i) => photoItem(x, p, i)); } }));
    items.push(folderItem('Albums', 'folder', {
      id: 'photo:albums', sub: 'Screenshots, camera and more',
      load: async () => groupBy(await Model.photos(), 'folder').map(([f, list]) => folderItem(f, 'folder', {
        id: 'palbum:' + f, sub: Fmt.plural(list.length, 'photo'),
        icon: { img: `/img/thumb?type=image&id=${list[0].id}&s=320`, shape: 'sq', style: 'photo', fallback: 'folder' },
        children: list.map((x, i) => photoItem(x, list, i)),
      })),
    }));
    items.push(actionItem('Slideshow', 'play', async () => { const p = await Model.photos(); Media.viewPhotos(p, 0, true); }, { id: 'photo:slideshow', sub: 'Play every photo full screen' }));
  } else {
    items.push(infoItem('No photos on this device', 'Screenshots and pictures appear here', 'photo'));
  }
  return { id: 'photo', label: 'Photo', glyph: 'photo', accent: CAT_ACCENT.photo, wallKey: 'photo', items };
}

// ---------- Apps / Network ----------
function appsCat() {
  const items = Model.H.apps.map(a => appItem(a, { wallKey: 'apps' }));
  return { id: 'apps', label: 'Apps', glyph: 'apps', accent: CAT_ACCENT.apps, wallKey: 'apps', items };
}
function networkCat() {
  const items = [actionItem('Internet Search', 'search', () => openUrl('https://www.google.com/', ''), { id: 'net:search', sub: 'Open the web browser' })];
  Model.H.apps.filter(a => a.kind === 'network').forEach(a => items.push(appItem(a, { wallKey: 'network' })));
  items.push(actionItem('Wi-Fi Settings', 'wifi', () => Native.openSettings('wifi'), { id: 'net:wifi' }));
  items.push(actionItem('Bluetooth Settings', 'bluetooth', () => Native.openSettings('bluetooth'), { id: 'net:bt' }));
  return { id: 'network', label: 'Network', glyph: 'network', accent: CAT_ACCENT.network, wallKey: 'network', items };
}

// ---------- Friends ----------
const STEAM_STATES = ['Offline', 'Online', 'Busy', 'Away', 'Snooze', 'Looking to trade', 'Looking to play'];
function presence(state, game) {
  if (game) return `<span class="dot game"></span>Playing ${esc(game)}`;
  const on = state === 'online' || state === 1 || state === 5 || state === 6;
  const away = state === 'idle' || state === 'dnd' || state === 2 || state === 3 || state === 4;
  const label = typeof state === 'number' ? STEAM_STATES[state] || 'Online' : ({ online: 'Online', idle: 'Away', dnd: 'Do Not Disturb', offline: 'Offline' }[state] || state);
  return `<span class="dot ${on ? 'on' : away ? 'away' : 'off'}"></span>${esc(label)}`;
}
function goAccounts() { XMB.goto('settings', ['set:accounts']); }
function setupItem(label, sub) { return actionItem(label, 'key', goAccounts, { sub, id: 'setup:' + label }); }

function steamFolder(st) {
  let children = [];
  if (!st.configured) {
    children.push(setupItem('Connect Steam', 'Add a Steam Web API key and your SteamID in Settings › Accounts'));
  } else {
    if (st.me) children.push(mk({ kind: 'friend', id: 'steam:me', label: st.me.name + ' (You)', subHtml: presence(st.me.state, st.me.game), icon: { img: st.me.avatar, shape: 'sq', style: 'photo round', fallback: 'user' }, friend: { src: 'Steam', avatar: st.me.avatar, game: st.me.game || '' }, run: () => openUrl(st.me.profile, STEAM_PKG) }));
    const friends = st.friends || [];
    const on = friends.filter(f => f.state > 0 || f.game);
    const off = friends.filter(f => !(f.state > 0 || f.game));
    const fi = f => mk({
      kind: 'friend', id: 'steam:' + f.id, label: f.name, subHtml: presence(f.state, f.game) + (f.state === 0 && f.lastOnline ? ' · ' + esc(Fmt.ago(f.lastOnline)) : ''),
      icon: { img: f.avatar, shape: 'sq', style: 'photo round', fallback: 'user' },
      friend: { src: 'Steam', avatar: f.avatar, game: f.game || '', lastOnline: f.lastOnline || 0 },
      detail: { title: f.name, meta: [f.game ? 'Playing ' + f.game : STEAM_STATES[f.state] || 'Offline', 'Steam'], action: 'Profile' },
      run: () => openUrl(f.profile, STEAM_PKG),
    });
    children.push(...on.map(fi));
    if (off.length) children.push(folderItem('Offline', 'user', { id: 'steam:offline', sub: Fmt.plural(off.length, 'friend'), children: off.map(fi) }));
    if (st.error) children.push(infoItem('Steam', st.error, 'warn'));
    if (!friends.length && !st.error) children.push(infoItem('No friends found', 'Your Steam friends list may be private', 'info'));
  }
  return folderItem('Steam', 'steam', {
    id: 'friends:steam', sub: st.configured ? `${st.online || 0} online · ${st.inGame || 0} in game` : 'Not connected',
    children, wallKey: 'friends',
    detail: st.configured ? { title: 'Steam', meta: [`${st.online || 0} friends online`, `${st.inGame || 0} in game`], cards: [] } : null,
  });
}

function discordFolder(d) {
  const children = [];
  if (!d.configured) {
    children.push(setupItem('Connect Discord', 'Add server IDs (Server Widget on) or Lanyard user IDs in Settings › Accounts'));
  }
  for (const f of d.friends || []) {
    children.push(mk({ kind: 'friend', id: 'dfriend:' + f.id, label: f.name, subHtml: presence(f.status, f.game),
      icon: f.avatar ? { img: f.avatar, shape: 'sq', style: 'photo round', fallback: 'user' } : { glyph: 'user' },
      friend: { src: 'Discord', avatar: f.avatar || '', game: f.game || '' } }));
  }
  for (const s of d.servers || []) {
    const sc = [];
    if (s.error) sc.push(infoItem(s.name, s.error, 'warn'));
    for (const v of s.voice || []) sc.push(infoItem(v.channel, `In voice: ${v.members.join(', ')}`, 'sound', { id: 'voice:' + s.id + v.channel }));
    for (const m of s.members || []) {
      sc.push(mk({ kind: 'friend', id: 'dmember:' + s.id + m.name, label: m.name,
        subHtml: presence(m.status, m.game) + (m.channel ? ` · in ${esc(m.channel)}` : ''),
        icon: m.avatar ? { img: m.avatar, shape: 'sq', style: 'photo round', fallback: 'user' } : { glyph: 'user' },
        friend: { src: s.name, avatar: m.avatar || '', game: m.game || '', channel: m.channel || '' } }));
    }
    if (s.invite) sc.push(actionItem('Open Server', 'discord', () => openUrl(s.invite, DISCORD_PKG), { id: 'dinv:' + s.id }));
    const inVoice = (s.voice || []).reduce((n, v) => n + v.members.length, 0);
    children.push(folderItem(s.name, 'discord', { id: 'dserver:' + s.id, sub: s.error ? 'Widget unavailable' : `${s.online || 0} online · ${inVoice} in voice`, children: sc }));
  }
  const app = Model.app(DISCORD_PKG);
  if (app) children.push(appItem(app, { id: 'open:discord', label: 'Open Discord', glyph: 'discord' }));
  const online = (d.friendsOnline || 0) + (d.servers || []).reduce((n, s) => n + (s.online || 0), 0);
  return folderItem('Discord', 'discord', { id: 'friends:discord', sub: d.configured ? `${online} online` : 'Not connected', children, wallKey: 'friends' });
}

function notifItem(n) {
  const a = Model.app(n.pkg);
  return mk({
    kind: 'notif', id: 'notif:' + n.key, label: n.title || n.app, sub: `${n.app} · ${Fmt.ago(n.time)}${n.text ? ' · ' + n.text : ''}`, notif: n,
    icon: a ? { img: appIconUrl(a), shape: 'sq', style: 'app' } : { glyph: APP_GLYPH[n.pkg] || 'bell' },
    detail: { title: n.title || n.app, meta: [n.app, Fmt.ago(n.time)], desc: n.text },
    run: () => report(Native.openNotification(n.key)),
  });
}

function xFolder(S) {
  const app = Model.app(X_PKG);
  const children = [];
  const links = [['Home', 'https://x.com/home'], ['Notifications', 'https://x.com/notifications'], ['Messages', 'https://x.com/messages'], ['Post', 'https://x.com/compose/post'], ['Explore', 'https://x.com/explore']];
  const notifs = (S.notifications || []).filter(n => n.pkg === X_PKG);
  notifs.slice(0, 15).forEach(n => children.push(notifItem(n)));
  links.forEach(([l, u]) => children.push(actionItem(l, l === 'Messages' ? 'chat' : l === 'Notifications' ? 'bell' : 'x', () => openUrl(u, X_PKG), { id: 'x:' + l, sub: 'X' })));
  if (app) children.push(appItem(app, { id: 'open:x', label: 'Open X', glyph: 'x' }));
  return folderItem('X', 'x', { id: 'friends:x', sub: notifs.length ? Fmt.plural(notifs.length, 'recent notification') : 'Posts, messages, notifications', children, wallKey: 'friends' });
}

function friendsCat() {
  const S = Model.social;
  const items = [];
  if (!S) {
    items.push(infoItem('Loading friends…', 'Checking Steam, Discord and notifications', 'friends'));
  } else {
    items.push(steamFolder(S.steam || {}));
    items.push(discordFolder(S.discord || {}));
    if (Model.app(X_PKG)) items.push(xFolder(S));
    const yt = Model.app(YT_PKG);
    if (yt) {
      const vids = (S.youtube && S.youtube.videos) || [];
      const ytn = (S.notifications || []).filter(n => n.pkg === YT_PKG);
      const children = [...ytn.slice(0, 10).map(notifItem), ...vids.map(v => ytVideoItem(v, 'fyt:'))];
      children.push(actionItem('Subscriptions', 'youtube', () => openUrl('https://www.youtube.com/feed/subscriptions', YT_PKG), { id: 'fyt:subs' }));
      if (!(S.youtube && S.youtube.configured)) children.push(setupItem('Follow Channels', 'Add YouTube channel IDs in Settings › Accounts'));
      items.push(folderItem('YouTube', 'youtube', { id: 'friends:youtube', sub: vids.length ? Fmt.plural(vids.length, 'new video') : 'Channels and notifications', children, wallKey: 'friends' }));
    }
    const notes = S.notifications || [];
    items.push(folderItem('Notifications', 'bell', {
      id: 'friends:notifs', sub: S.listener ? Fmt.plural(notes.length, 'notification') : 'Notification access is off',
      children: notes.length ? notes.map(notifItem) : [infoItem('No notifications', S.listener ? 'Messages from social apps show up here' : 'Turn on Notification Access in Settings › System Settings', 'bell')],
      wallKey: 'friends',
    }));
  }
  items.push(setupItem('Set Up Accounts', 'Steam, Discord and YouTube'));
  return { id: 'friends', label: 'Friends', glyph: 'friends', accent: CAT_ACCENT.friends, wallKey: 'friends', items };
}

// ---------- Settings ----------
async function saveSetting(key, value) {
  Model.H.settings[key] = value;
  applySettings(Model.H.settings);
  XMB.refresh();
  try { await Api.post('/api/settings', { [key]: value }); } catch (e) { Panels.error('Could not save the setting'); }
}
function setCustomBackground(url) {
  Model.H.settings.customBg = url;
  Model.H.settings.bgMode = 'custom';
  applySettings(Model.H.settings);
  Api.post('/api/settings', { customBg: url, bgMode: 'custom' });
  Panels.toast('Background changed', 'image');
}
const ONOFF = [[true, 'On'], [false, 'Off']];
function setting(label, key, choices, g, o = {}) {
  const cur = () => Model.settings()[key];
  const labelOf = v => { const c = choices.find(x => x[0] === v); return c ? c[1] : String(v); };
  return mk(props({
    kind: 'setting', id: 'set:' + key, label, icon: { glyph: g },
    get sub() { return labelOf(cur()); },
    run: () => Panels.choose(label, choices.map(c => ({ label: c[1], value: c[0], checked: c[0] === cur() })), v => saveSetting(key, v)),
  }, o));
}
function textSetting(label, key, g, o = {}) {
  const cur = () => String(Model.settings()[key] || '');
  return mk({
    kind: 'setting', id: 'set:' + key, label, icon: { glyph: g },
    get sub() { const v = cur(); if (!v) return 'Not set'; return o.secret ? '••••••••' + v.slice(-4) : v; },
    run: () => Panels.input(label, o.hint || '', cur(), v => { saveSetting(key, v.trim()).then(() => Native.refreshSocial()); }, o.secret),
  });
}
async function customBgChoices() {
  const out = [];
  const custom = (Model.H.wallpapers || {}).custom || [];
  custom.forEach((u, i) => out.push(mk({ kind: 'photo', id: 'cbg:' + i, label: 'Wallpaper ' + (i + 1), sub: 'From CrossBar/wallpapers/custom', icon: { img: u.replace('w=1920', 'w=480'), shape: 'sq', style: 'photo' }, run: () => setCustomBackground(u) })));
  Object.entries(WALLS).forEach(([k, list]) => list.forEach((u, i) => out.push(mk({ kind: 'photo', id: 'wbg:' + k + i, label: `${k.toUpperCase()} ${i + 1}`, sub: 'Built-in wallpaper', icon: { img: u, shape: 'sq', style: 'photo' }, run: () => setCustomBackground(u) }))));
  try {
    const photos = await Model.photos();
    photos.slice(0, 60).forEach(p => out.push(mk({ kind: 'photo', id: 'pbg:' + p.id, label: p.title, sub: p.folder, icon: { img: `/img/thumb?type=image&id=${p.id}&s=320`, shape: 'sq', style: 'photo' }, run: () => setCustomBackground(`/img/thumb?type=image&id=${p.id}&s=1920`) })));
  } catch (e) { /* no photo access */ }
  return out;
}
function reloadConfig() {
  const changed = Native.reloadConfig();
  Panels.toast(changed ? 'Loaded CrossBar/config.json' : 'No changes in CrossBar/config.json', 'refresh');
  setTimeout(() => CB.reload(), 600);
}
function explainSocial() {
  Panels.alert('How Friends Work',
    'Steam: friends online and the games they are playing, using your Steam Web API key and SteamID (your profile and friends list must be visible).\n' +
    'Discord: who is online, in voice channels or playing, for servers that turn on Server Widget. Friends who join the Lanyard server can also share their status.\n' +
    'YouTube and X: new videos from channels you follow, and notifications from the apps.\n\n' +
    'Tip: put these values in CrossBar/config.json from the Mac, then choose "Load /CrossBar/config.json".');
}

/* Stored by Android (whether CrossBar's bottom-screen activity is enabled), not in settings.json. */
function bottomScreenSetting() {
  const on = () => (Model.H.perms || {}).bottomScreen !== false;
  return mk({
    kind: 'setting', id: 'set:bottomScreen', label: 'Bottom Screen', icon: { glyph: 'nds' },
    get sub() { return on() ? 'CrossBar companion' : 'Android launcher'; },
    run: () => Panels.choose('Bottom Screen', [[true, 'CrossBar Companion'], [false, 'Android Launcher']].map(([v, l]) => ({ label: l, value: v, checked: v === on() })), v => {
      Model.H.perms.bottomScreen = v;
      Native.setBottomScreen(v);
      XMB.refresh();
    }),
  });
}

function settingsCat() {
  const H = Model.H;
  const perms = H.perms || {};
  const theme = folderItem('Theme Settings', 'palette', {
    id: 'set:theme', sub: 'Background, wave color and wallpapers', children: [
      setting('Background', 'bgMode', [['wallpapers', 'Wallpapers and Wave'], ['wave', 'Wave Only'], ['custom', 'Custom Image']], 'image'),
      setting('Wave Color', 'waveColor', [['auto', 'Match Selection'], ['month', 'Change Every Month'], ['blue', 'Blue'], ['purple', 'Purple'], ['red', 'Red'], ['green', 'Green'], ['gold', 'Gold'], ['pink', 'Pink'], ['teal', 'Teal'], ['orange', 'Orange'], ['silver', 'Silver']], 'palette'),
      setting('Wave Over Wallpapers', 'waveOverlay', ONOFF, 'display'),
      setting('Rotate Wallpapers', 'rotateSec', [[8, 'Every 8 Seconds'], [15, 'Every 15 Seconds'], [30, 'Every 30 Seconds'], [60, 'Every Minute'], [0, 'Off']], 'refresh'),
      setting('Game Art Backgrounds', 'heroArt', ONOFF, 'image'),
      folderItem('Custom Background', 'image', { id: 'set:custombg', sub: 'Pick a photo or wallpaper', load: customBgChoices }),
    ],
  });
  const perf = folderItem('Performance Settings', 'battery', {
    id: 'set:perf', get sub() { return (PERF_PRESETS[Model.settings().perfPreset] || PERF_PRESETS.smooth60).label; }, children: [
      setting('Frame Rate and Battery', 'perfPreset', Object.entries(PERF_PRESETS).map(([k, p]) => [k, p.label]), 'battery'),
    ],
  });
  const sound = folderItem('Sound Settings', 'sound', {
    id: 'set:sound', sub: 'Menu sounds', children: [
      setting('Menu Sounds', 'sounds', ONOFF, 'sound'),
      setting('Menu Sound Volume', 'soundVol', [[0.3, 'Low'], [0.6, 'Medium'], [1, 'High']], 'sound'),
    ],
  });
  const dt = folderItem('Date and Time Settings', 'clock', {
    id: 'set:time', sub: 'Clock format', children: [
      setting('Clock Format', 'clock24', [[false, '12-Hour'], [true, '24-Hour']], 'clock'),
      setting('Show Date', 'showDate', ONOFF, 'clock'),
      actionItem('System Date and Time', 'clock', () => Native.openSettings('date'), { id: 'set:sysdate' }),
    ],
  });
  const multi = (H.platforms || []).filter(p => (p.players || []).length > 1);
  const games = folderItem('Game Settings', 'game', {
    id: 'set:games', sub: 'ROM folder, artwork and emulators', children: [
      infoItem('ROM Folder', (H.settings || {}).romRoot || '', 'folder', { id: 'set:romroot' }),
      actionItem('Grant ROM Folder Access', 'key', () => Native.requestRomAccess(), { id: 'set:romtree', get sub() { return (Model.H.perms || {}).romTree ? 'Granted' : 'Lets emulators open games directly from the folder'; } }),
      actionItem('Rescan Games', 'refresh', rescan, { id: 'set:rescan', sub: Fmt.plural(H.games.length, 'game') + ' found' }),
      setting('Download Game Artwork', 'downloadArt', ONOFF, 'image'),
      setting('Game Descriptions from Wikipedia', 'onlineGameInfo', ONOFF, 'info'),
      folderItem('Default Emulators', 'game', {
        id: 'set:emus', sub: 'Choose which emulator runs each system',
        children: multi.length ? multi.map(p => actionItem(p.name, p.glyph, () => chooseEmulator(p), { id: 'set:emu:' + p.id, sub: p.emulator || '' })) : [infoItem('Only one emulator per system', '', 'info')],
      }),
    ],
  });
  const accounts = folderItem('Accounts', 'key', {
    id: 'set:accounts', sub: 'Steam, Discord and YouTube', children: [
      textSetting('Steam Web API Key', 'steamApiKey', 'steam', { secret: true, hint: 'Create one at steamcommunity.com/dev/apikey' }),
      textSetting('Steam ID', 'steamId', 'steam', { hint: 'Your 17-digit SteamID64 or custom profile name' }),
      textSetting('Discord Server IDs', 'discordServers', 'discord', { hint: 'Comma-separated IDs of servers with Server Widget turned on' }),
      textSetting('Discord Friends (Lanyard IDs)', 'discordUserIds', 'discord', { hint: 'User IDs of friends who joined the Lanyard server' }),
      textSetting('YouTube Channel IDs', 'youtubeChannels', 'youtube', { hint: 'Comma-separated channel IDs that start with UC' }),
      actionItem('Load /CrossBar/config.json', 'refresh', reloadConfig, { id: 'set:config', sub: 'Enter keys from the Mac instead of the Thor' }),
      actionItem('How Friends Work', 'info', explainSocial, { id: 'set:explain' }),
    ],
  });
  const lights = (H.device || {}).joystickLights;
  const music = folderItem('Media Settings', 'music', {
    id: 'set:music', sub: lights ? 'Music folders, music during games, joystick lights' : 'Music folders and music during games', children: [
      setting('Include All Audio Folders', 'musicAllFolders', ONOFF, 'music'),
      setting('Music During Games', 'musicDuringGames', ONOFF, 'game'),
      ...(lights ? [setting('Joystick Lights Match Media', 'mediaLights', ONOFF, 'stick')] : []),
      actionItem('Clear Recently Played Media', 'trash', () => { Native.clearMediaHistory(); Panels.toast('Cleared what media apps played', 'trash'); },
        { id: 'set:mediahist', sub: 'Shown on media apps\' pages on the bottom screen' }),
    ],
  });
  const sys = (label, g, which, o = {}) => actionItem(label, g, () => report(Native.openSettings(which)), props({ id: 'sys:' + which }, o));
  const system = folderItem('System Settings', 'settings', {
    id: 'set:system', sub: 'Wi-Fi, display, permissions, home screen', children: [
      sys('Wi-Fi', 'wifi', 'wifi'), sys('Bluetooth', 'bluetooth', 'bluetooth'), sys('Display', 'display', 'display'),
      sys('Sound', 'sound', 'sound'), sys('Battery', 'battery', 'battery'), sys('Storage', 'storage', 'storage'), sys('Apps', 'apps', 'apps'),
      sys('Notification Access', 'bell', 'notifications', { get sub() { return (Model.H.perms || {}).listener ? 'On' : 'Off · needed for Friends and Now Playing'; } }),
      actionItem('Default Home App', 'home', () => Native.requestDefaultHome(), { id: 'sys:home', get sub() { return (Model.H.perms || {}).home ? 'CrossBar is the home screen' : 'Make CrossBar the home screen'; } }),
      ...((H.device || {}).dualScreen ? [bottomScreenSetting()] : []),
      sys('Android Settings', 'settings', 'all'),
      setting('Theme Development Mode', 'devMode', ONOFF, 'palette', {
        detail: { title: 'Theme Development Mode', desc: 'Loads the menu from /CrossBar/dev-www and allows USB debugging of the menu. Leave this off unless you are editing the theme.' },
        run: () => Panels.choose('Theme Development Mode', ONOFF.map(([v, l]) => ({ label: l, value: v, checked: v === !!Model.settings().devMode })),
          v => saveSetting('devMode', v).then(() => Panels.toast('Restart CrossBar to apply', 'refresh'))),
      }),
    ],
  });
  const dev = H.device || {};
  const about = infoItem('About CrossBar', `Version ${dev.version || ''} · ${dev.model || ''} · Android ${dev.android || ''}`, 'info', { id: 'set:about' });
  return {
    id: 'settings', label: 'Settings', glyph: 'settings', accent: CAT_ACCENT.settings, wallKey: 'settings',
    items: [theme, perf, sound, dt, games, accounts, music, system, about],
  };
}
