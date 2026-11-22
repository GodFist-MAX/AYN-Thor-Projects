'use strict';
// The bottom screen's companion page. Data comes from the app (window.Deck) and later updates
// arrive through window.deck; touches become commands for the top screen.
const D = window.Deck;
const S = {
  tab: 'now', state: {}, eps: [], lib: { lists: {} }, net: true, settings: {}, media: {}, version: '',
  shelf: { recent: [], saved: [] }, listTab: 'CURRENT', range: 0, dragging: false,
  // Downloads: the list from Downloads.java, download speeds by id, a Delete waiting for its second press.
  dls: { items: [] }, speed: {}, dlAt: 0, confirmDl: '',
  // The open sheet: server, sub, audio (the episode playing) or pick:<setting>.
  sheet: '',
};
const DONE = 0.85;
const TITLES = { now: 'Now Playing', next: 'Up Next', schedule: 'Schedule', lists: 'Lists', browse: 'Browse', downloads: 'Downloads', settings: 'Settings' };
const LIST_NAMES = { CURRENT: 'Watching', REPEATING: 'Rewatching', COMPLETED: 'Completed', PAUSED: 'Paused', PLANNING: 'Planning', DROPPED: 'Dropped' };
const STATUS_NAMES = { RELEASING: 'Airing', FINISHED: 'Finished', NOT_YET_RELEASED: 'Upcoming', HIATUS: 'On hiatus', CANCELLED: 'Cancelled' };
// The same choices as the top screen's Settings.
const PICKS = {
  subLang: [['en', 'English'], ['es', 'Spanish'], ['pt', 'Portuguese'], ['fr', 'French'], ['de', 'German'], ['it', 'Italian'], ['ar', 'Arabic'], ['ru', 'Russian'], ['id', 'Indonesian'], ['tr', 'Turkish'], ['ja', 'Japanese']],
  audioLang: [['ja', 'Japanese (original)'], ['en', 'English (dub)'], ['es', 'Spanish'], ['pt', 'Portuguese'], ['fr', 'French'], ['de', 'German'], ['it', 'Italian']],
  dlQuality: [['360', '360p'], ['480', '480p'], ['720', '720p'], ['1080', '1080p'], ['2160', 'Best available']],
};
const PICK_TITLES = { subLang: 'Subtitles', audioLang: 'Audio', dlQuality: 'Download quality' };
const I = {
  play: '<svg viewBox="0 0 24 24"><path class="fill" d="M8 5.5v13l10.5-6.5z"/></svg>',
  pause: '<svg viewBox="0 0 24 24"><path class="fill" d="M7 5h3.6v14H7zM13.4 5H17v14h-3.6z"/></svg>',
  prev: '<svg viewBox="0 0 24 24"><path class="fill" d="M17.5 6v12L9 12z"/><path d="M6.5 6v12"/></svg>',
  next: '<svg viewBox="0 0 24 24"><path class="fill" d="M6.5 6v12L15 12z"/><path d="M17.5 6v12"/></svg>',
  back10: '<svg viewBox="0 0 24 24"><path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3"/><path d="M4.5 4.5v3.7h3.7"/><text x="12.5" y="15" text-anchor="middle" font-size="6.5" font-weight="700" fill="currentColor" stroke="none">10</text></svg>',
  fwd10: '<svg viewBox="0 0 24 24"><path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3"/><path d="M19.5 4.5v3.7h-3.7"/><text x="11.5" y="15" text-anchor="middle" font-size="6.5" font-weight="700" fill="currentColor" stroke="none">10</text></svg>',
  pad: '<svg viewBox="0 0 24 24"><path d="M7.5 7.5h9a4.5 4.5 0 0 1 4.4 3.6l.8 4a2.6 2.6 0 0 1-4.5 2.2l-1.7-1.8h-7l-1.7 1.8a2.6 2.6 0 0 1-4.5-2.2l.8-4a4.5 4.5 0 0 1 4.4-3.6z"/><path d="M8 10v3.4M6.3 11.7h3.4"/><circle class="fill" cx="15.6" cy="10.8" r="1"/><circle class="fill" cx="17.6" cy="12.8" r="1"/></svg>',
};

const $ = (sel) => document.querySelector(sel);
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const L = (status) => (S.lib.lists && S.lib.lists[status]) || [];
const allEntries = () => Object.keys(LIST_NAMES).reduce((a, k) => a.concat(L(k)), []);
const entry = (id) => allEntries().find((e) => e.id === id);
const info = (id) => entry(id) || S.media[id] || {};
const pct = (e) => (e.episodes ? Math.min(100, Math.round((e.progress / e.episodes) * 100)) : 0);
const watched = (e) => (e.episodes ? `${e.progress} of ${e.episodes} watched` : `${e.progress} watched · ${e.aired} aired`);
const caughtUp = (e) => e.progress >= (e.aired || 0) && !!(e.aired || e.nextAt);
const playing = () => S.state.page === 'watch';
// Saved and recently watched shows are kept on the Thor, covers included, so they show offline.
const coverSrc = (name) => (name ? `https://shelf.anideck/cover/${name}` : '');
// Pictures from a source, fetched with its headers and cached by the app.
const viaApp = (source, url) => (url ? `https://shelf.anideck/img/${encodeURIComponent(source || '0')}?u=${encodeURIComponent(url)}` : '');
const shelfImg = (b) => coverSrc(b.cover) || viaApp(b.source, b.coverUrl);
const isSaved = (key) => !!key && S.shelf.saved.some((b) => b.key === key);
const nextOf = (b) => {
  const l = b.last;
  return l ? { done: l.d > 0 && l.t >= l.d * DONE, num: l.num, t: l.t, d: l.d, name: l.name } : null;
};

// ---- formatting ----

const pad = (n) => String(n).padStart(2, '0');
const numLabel = (n) => (n == null || n < 0 || Number.isNaN(+n) ? '' : String(Math.round(n * 100) / 100));
// An episode's own title, without a repeat of its number ("Episode 3 - …").
const bareTitle = (t) => {
  const s = String(t || '').trim().replace(/^(episode|ep\.?|e)\s*[\d.]+\s*[:\-–—]?\s*/i, '').trim();
  return /^[\d.]*$/.test(s) ? '' : s;
};
const rateLabel = (r) => String(Math.round((r || 1) * 100) / 100);
const clock = (t) => {
  t = Math.max(0, Math.floor(t || 0));
  const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = t % 60;
  return h ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
};
const until = (at) => {
  const s = at - Date.now() / 1000;
  if (s <= 60) return 'now';
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
  return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : `${m}m`;
};
const ago = (ms) => {
  const s = (Date.now() - ms) / 1000;
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  return `${Math.floor(s / 86400)} d ago`;
};
const hm = (at) => new Date(at * 1000).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
const dayName = (at) => {
  const d = new Date(at * 1000), now = new Date();
  const diff = Math.round((new Date(d.getFullYear(), d.getMonth(), d.getDate()) - new Date(now.getFullYear(), now.getMonth(), now.getDate())) / 86400000);
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Tomorrow';
  return d.toLocaleDateString([], { weekday: 'long', month: 'short', day: 'numeric' });
};

function setAccent(hex) {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex || '');
  const v = m ? m[1] : '7aa7ff';
  let r = parseInt(v.slice(0, 2), 16), g = parseInt(v.slice(2, 4), 16), b = parseInt(v.slice(4, 6), 16);
  // Dark cover colors are lifted so the accent stays visible and text on it readable.
  const lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  if (lum < 120) {
    const k = 120 / Math.max(lum, 1);
    [r, g, b] = [r, g, b].map((c) => Math.min(255, Math.round(c * k + 40)));
  }
  document.documentElement.style.setProperty('--accent', `rgb(${r}, ${g}, ${b})`);
  document.documentElement.style.setProperty('--accent-rgb', `${r}, ${g}, ${b}`);
}

// ---- updates from the app ----

window.deck = {
  onState(s) {
    const prev = S.state;
    S.state = s || {};
    stateChanged(prev);
  },
  // The show's episodes for the strip: {i, n, name, w} streaming, {dl, n, name, w} for downloads.
  onEpisodes(list) {
    S.eps = Array.isArray(list) ? list : [];
    if (S.tab === 'now' && !S.dragging) render();
  },
  onLibrary(l) {
    S.lib = l || { lists: {} };
    badge();
    if (!S.dragging) render();
  },
  onNet(on) {
    S.net = !!on;
    status();
  },
  onSettings(s) {
    S.settings = s || {};
    if (S.tab === 'settings') render();
  },
  onShelf(j) {
    S.shelf = j || { recent: [], saved: [] };
    if (!S.dragging && S.tab !== 'browse') render();
  },
  onMedia(m) {
    if (!m || !m.id) return;
    S.media[m.id] = m;
    if (S.tab === 'now') render();
  },
  onDownloads: (j) => dlUpdate(j),
  // Extensions are listed on the top screen.
  onSources() {},
  onBack() {
    if (S.sheet) S.sheet = '';
    else if (S.tab !== 'now') S.tab = 'now';
    else return;
    render();
  },
  toast,
  nav: (dir) => navMove(dir),
  ok: () => navOk(),
  back: () => navBack(),
  // The controller moved to this screen (active) or away; `show` puts the highlight up.
  pad(active, show) {
    K.active = !!active;
    status();
    if (show) navMove(null);
    else navHide();
  },
  scroll(dy) {
    ($('.sheet') || $('#view')).scrollBy(0, dy);
  },
};

// Which episode is on: a show's episode, or a download.
const epId = (s) => (s.page !== 'watch' ? '' : s.offline ? 'dl:' + s.download : s.key + '#' + s.epIndex);

// Everything Now Playing shows except the time, which moves on its own.
const shape = (s) => {
  const p = s.player || {};
  return JSON.stringify([s.page, epId(s), s.show, s.ep, s.epTitle, s.hasPrev, s.hasNext, s.cover, s.coverUrl, s.sourceName, s.quality, p.state, p.error, p.allFailed, p.note,
    s.servers, s.server, s.video, s.dl, s.dlId, s.stamp, s.subs, s.sub, s.audios, s.audio, s.rate]);
};

function stateChanged(prev) {
  const s = S.state;
  const newEpisode = playing() && epId(prev) !== epId(s);
  if (newEpisode) {
    const i = curIndex();
    S.range = Math.floor(Math.max(0, i) / 100);
    if (prev.page !== 'watch') S.tab = 'now';
    if (S.sheet && !S.sheet.startsWith('pick:')) S.sheet = '';
  }
  if (!playing() && S.sheet && !S.sheet.startsWith('pick:')) S.sheet = '';
  if (s.anilistId && !entry(s.anilistId) && !S.media[s.anilistId]) {
    S.media[s.anilistId] = { id: s.anilistId, pending: true };
    D.media(s.anilistId);
  }
  if (S.tab === 'now' && (newEpisode || shape(prev) !== shape(s))) render();
  else progress();
}

// ---- rendering ----

function render() {
  const view = $('#view');
  const keep = view.dataset.tab === S.tab ? view.scrollTop : 0;
  const sheetTop = $('.sheet') ? $('.sheet').scrollTop : 0;
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('on', t.dataset.tab === S.tab));
  $('#heading').textContent = TITLES[S.tab];
  if (S.tab !== 'now') setAccent();
  const f = { now: renderNow, next: renderNext, schedule: renderSchedule, lists: renderLists, browse: renderBrowse,
    downloads: renderDownloads, settings: renderSettings }[S.tab];
  view.innerHTML = f() + sheetHtml();
  view.dataset.tab = S.tab;
  view.scrollTop = keep;
  if ($('.sheet')) $('.sheet').scrollTop = sheetTop;
  if (S.tab === 'now') {
    wireTrack();
    progress();
    const strip = $('#strip'), cur = $('.ep.cur');
    if (strip && cur) strip.scrollLeft = cur.offsetLeft - strip.clientWidth / 2 + cur.offsetWidth / 2;
  }
  status();
  navRestore();
}

function renderNow() {
  const s = S.state;
  if (!playing()) {
    setAccent();
    return renderIdle();
  }
  const m = info(s.anilistId);
  setAccent(m.color);
  const facts = [];
  if (s.quality) facts.push(s.quality);
  if (m.format) facts.push(m.format.replace(/_/g, ' '));
  if (m.year) facts.push(m.year);
  if (m.mstatus) facts.push(STATUS_NAMES[m.mstatus] || m.mstatus);
  const cover = coverSrc(s.cover) || viaApp(s.source, s.coverUrl) || m.cover || '';
  const n = numLabel(s.ep);
  const name = bareTitle(s.epTitle);
  const epLine = `Episode ${n}${name ? ' · ' + esc(name) : ''}`;
  let h = `<div class="hero" style="--img:url('${esc(s.offline ? cover : m.banner || cover)}')">
    ${cover ? `<img class="cover" src="${esc(cover)}" data-alt="${esc(m.cover || '')}">` : ''}
    <div class="meta">
      <div class="kicker">${s.offline ? 'Playing a download' : `Now playing${s.sourceName ? ' · ' + esc(s.sourceName) : ''}`}</div>
      <div class="htitle">${esc(s.show || m.title || '')}</div>
      <div class="hsub">${epLine}</div>
      <div class="facts">${facts.map((f) => `<span class="chip">${esc(f)}</span>`).join('')}${listChip(m)}${m.nextAt ? `<span class="chip">Ep ${m.nextEp} in ${until(m.nextAt)}</span>` : ''}</div>
    </div>
  </div>`;
  h += playerNote(s);
  const p = s.player || {};
  h += `<div class="seek"><span id="tCur">0:00</span><div class="track" id="track"><div class="fill" id="fill"></div><div class="knob" id="knob"></div></div><span id="tDur">0:00</span></div>
  <div class="controls">
    <button class="ctl" data-cmd="prev" ${s.hasPrev ? '' : 'disabled'}>${I.prev}</button>
    <button class="ctl" data-cmd="seekBy" data-d="-10">${I.back10}</button>
    <button class="ctl play" data-cmd="toggle" id="playBtn">${p.state === 'playing' ? I.pause : I.play}</button>
    <button class="ctl" data-cmd="seekBy" data-d="10">${I.fwd10}</button>
    <button class="ctl" data-cmd="next" ${s.hasNext ? '' : 'disabled'}>${I.next}</button>
  </div>
  <div class="pills">${pills(s)}</div>`;
  return h + episodeStrip(s);
}

// Trouble with the video, or what it's waiting for.
function playerNote(s) {
  const p = s.player || {};
  if (p.state === 'error' && s.offline) {
    return `<div class="error"><div class="et"><b>This download won't play</b>${esc(p.error)}. Delete it in Downloads and
      download the episode again.</div><button class="btn" data-act="close">Close</button></div>`;
  }
  if (p.state === 'error') {
    const what = p.allFailed ? '<b>None of the servers could play this episode</b>The source may be down, or its servers blocked on this network.'
      : `<b>This video won't load</b>${esc(p.error)}.`;
    return `<div class="error"><div class="et">${what} Try again, or pick another server.</div>
      <button class="btn" data-cmd="retry">Try again</button><button class="btn" data-sheet="server">Servers</button></div>`;
  }
  if (p.state === 'loading' && p.note) return `<div class="note" style="text-align:center;padding:18px 0 0">${esc(p.note)}</div>`;
  return '';
}

function pills(s) {
  const subName = s.sub >= 0 && s.subs && s.subs[s.sub] ? s.subs[s.sub] : 'Off';
  const audioName = s.audio >= 0 && s.audios && s.audios[s.audio] ? s.audios[s.audio] : '';
  const short = (t) => (t.length > 22 ? t.slice(0, 21) + '…' : t);
  const srv = !s.offline && s.servers && s.servers[s.server] ? s.servers[s.server].name : '';
  let h = `<button class="pill${s.stamp ? ' on' : ''}" data-cmd="skip">${s.stamp ? 'Skip ' + esc(s.stamp.toLowerCase()) : 'Skip 85 s'}</button>`;
  if (!s.offline) h += `<button class="pill" data-sheet="server">${srv ? esc(short(srv)) : 'Servers'}</button>`;
  if (s.subs && s.subs.length) h += `<button class="pill${s.sub >= 0 ? ' on' : ''}" data-sheet="sub">Subtitles · ${esc(short(subName))}</button>`;
  if (s.audios && s.audios.length > 1) h += `<button class="pill" data-sheet="audio">Audio · ${esc(short(audioName || 'Default'))}</button>`;
  h += `<button class="pill" data-cmd="speed">Speed ${rateLabel(s.rate)}×</button>`;
  if (!s.offline) h += dlPill(s);
  if (s.key) h += `<button class="pill ${isSaved(s.key) ? 'on' : ''}" data-act="save">${isSaved(s.key) ? '★ Saved' : '☆ Save show'}</button>`;
  return h + '<button class="pill" data-act="close">Close player</button>';
}

// The episode's download, once there is one.
function dlPill(s) {
  const r = dlFor(s);
  if (r && r.state === 'done') return `<button class="pill on" id="dlPill" data-act="playDl" data-dl="${esc(r.id)}">Play download</button>`;
  if (r && r.state === 'failed') return `<button class="pill" id="dlPill" data-tab="downloads">Download failed</button>`;
  if (r) return `<button class="pill" id="dlPill" data-tab="downloads">${esc(dlShort(r))}</button>`;
  return `<button class="pill" id="dlPill" data-act="download">Download</button>`;
}

function listChip(m) {
  if (!m.status) return '';
  return `<span class="chip acc">${LIST_NAMES[m.status] || m.status} · ${m.progress || 0}/${m.episodes || '?'}</span>`;
}

// Where the episode on screen is in S.eps.
function curIndex() {
  const s = S.state;
  return s.offline ? S.eps.findIndex((e) => e.dl === s.download) : s.epIndex;
}

function episodeStrip(s) {
  const eps = S.eps || [];
  if (eps.length < 2) return '';
  const cur = curIndex();
  const per = 100, pages = Math.ceil(eps.length / per);
  S.range = Math.min(S.range, pages - 1);
  const from = S.range * per, to = Math.min(eps.length, from + per);
  let chips = '';
  for (let i = from; i < to; i++) {
    const e = eps[i];
    const target = e.dl ? `data-epdl="${esc(e.dl)}"` : `data-epi="${i}"`;
    chips += `<button class="ep${i === cur ? ' cur' : ''}${e.w ? ' seen' : ''}" ${target}>${esc(e.n)}</button>`;
  }
  let ranges = '';
  if (pages > 1) {
    for (let r = 0; r < pages; r++) {
      const a = eps[r * per], b = eps[Math.min(eps.length, (r + 1) * per) - 1];
      ranges += `<button class="${r === S.range ? 'on' : ''}" data-range="${r}">${esc(a.n)}–${esc(b.n)}</button>`;
    }
  }
  return `<div class="eps-head"><div class="slbl">${s.offline ? 'Downloaded episodes' : 'Episodes'}</div><div class="ranges">${ranges}</div></div><div class="strip" id="strip">${chips}</div>`;
}

// ---- sheets ----

const sheetBox = (title, body, note) => `<div class="sheet"><div class="sheet-head"><div class="sheet-title">${esc(title)}</div>
    <button class="btn" data-act="closeSheet">Close</button></div>${body}${note ? `<div class="note">${esc(note)}</div>` : ''}</div>`;

function sheetHtml() {
  const s = S.state;
  if (S.sheet.startsWith('pick:')) return pickSheet(S.sheet.slice(5));
  if (!playing() || S.tab !== 'now') return '';
  if (S.sheet === 'server' && !s.offline) return serverSheet(s);
  if (S.sheet === 'sub' || S.sheet === 'audio') return trackSheet(S.sheet, s);
  return '';
}

// Each server with its qualities; servers that load their videos when picked show one choice.
function serverSheet(s) {
  const body = (s.servers || []).map((h, hi) => {
    const vids = h.videos || [];
    const choices = vids.length
      ? vids.map((t, vi) => `<button class="choice${hi === s.server && vi === s.video ? ' on' : ''}" data-srv="${hi}" data-v="${vi}"><b>${esc(t || 'Video ' + (vi + 1))}</b></button>`).join('')
      : `<button class="choice${hi === s.server ? ' on' : ''}" data-srv="${hi}" data-v="-1"><b>${h.lazy ? 'Load this server' : 'Try this server'}</b></button>`;
    return `<div class="slbl">${esc(h.name)}${h.error ? ` · <span class="err">${esc(h.error)}</span>` : ''}</div><div class="choices">${choices}</div>`;
  }).join('');
  return sheetBox('Servers and quality', body || '<div class="empty">No servers yet</div>',
    "If an episode won't load, another server usually works. Y on the controller opens these on the top screen too.");
}

function trackSheet(kind, s) {
  const names = (kind === 'sub' ? s.subs : s.audios) || [];
  const cur = kind === 'sub' ? s.sub : s.audio;
  let opts = names.map((n, i) => `<button class="choice${cur === i ? ' on' : ''}" data-trk="${kind}" data-i="${i}"><b>${esc(n)}</b></button>`).join('');
  if (kind === 'sub') opts = `<button class="choice${cur < 0 ? ' on' : ''}" data-trk="sub" data-i="-1"><b>Off</b></button>` + opts;
  return sheetBox(kind === 'sub' ? 'Subtitles' : 'Audio', `<div class="choices">${opts}</div>`,
    kind === 'sub' ? 'New episodes start with the language picked in Settings when the source has it.' : '');
}

function pickSheet(key) {
  const cur = String(S.settings[key] == null ? '' : S.settings[key]);
  const opts = (PICKS[key] || []).map(([v, label]) => `<button class="choice${v === cur ? ' on' : ''}" data-choose="${key}" data-v="${v}"><b>${esc(label)}</b></button>`).join('');
  return sheetBox(PICK_TITLES[key] || key, `<div class="choices">${opts}</div>`, '');
}

// ---- seek bar ----

function progress() {
  const p = S.state.player;
  if (!p || S.dragging || !$('#fill')) return;
  const f = p.d ? Math.min(1, p.t / p.d) : 0;
  $('#fill').style.width = f * 100 + '%';
  $('#knob').style.left = f * 100 + '%';
  $('#tCur').textContent = clock(p.t);
  $('#tDur').textContent = clock(p.d);
}

function wireTrack() {
  const tr = $('#track');
  if (!tr) return;
  const at = (ev) => {
    const r = tr.getBoundingClientRect();
    return Math.min(1, Math.max(0, (ev.clientX - r.left) / r.width));
  };
  const show = (f) => {
    $('#fill').style.width = f * 100 + '%';
    $('#knob').style.left = f * 100 + '%';
    const p = S.state.player;
    if (p) $('#tCur').textContent = clock(f * p.d);
  };
  tr.addEventListener('pointerdown', (ev) => {
    const p = S.state.player;
    if (!p || !p.d) return;
    S.dragging = true;
    tr.setPointerCapture(ev.pointerId);
    show(at(ev));
  });
  tr.addEventListener('pointermove', (ev) => {
    if (S.dragging) show(at(ev));
  });
  tr.addEventListener('pointerup', (ev) => {
    if (!S.dragging) return;
    S.dragging = false;
    const p = S.state.player;
    if (p && p.d) D.cmd(JSON.stringify({ c: 'seek', t: at(ev) * p.d }));
  });
  tr.addEventListener('pointercancel', () => {
    S.dragging = false;
    progress();
  });
}

// ---- nothing playing ----

function renderIdle() {
  const g = groups();
  const picks = g.fresh.concat(g.cont, g.waiting).slice(0, 4);
  const recent = S.shelf.recent.slice(0, 4), saved = S.shelf.saved.slice(0, 5);
  let h = `<div class="idle"><div class="idle-title">Nothing playing</div>
    <div class="idle-sub">Pick something to watch, or browse on the top screen.</div>${searchForm()}</div>`;
  if (recent.length) h += `<div class="slbl">Continue watching</div><div class="cards">${recent.map(recentCard).join('')}</div>`;
  if (saved.length) h += `<div class="slbl" style="margin-top:30px">Saved shows</div><div class="grid">${saved.map(savedTile).join('')}</div>`;
  if (picks.length) h += `<div class="slbl" style="margin-top:30px">From your Watching list</div><div class="cards">${picks.map(card).join('')}</div>`;
  if (!recent.length && !saved.length) {
    h += `<div class="group" style="margin-top:30px"><div class="gt">Getting started</div>
      <div class="note">Shows come from extensions. Add one or two on the top screen (Extensions), then browse or search them
        there or here. Episodes stream in AniDeck's own player, and downloads play without a connection.</div>
      <div class="acct-actions"><button class="btn primary" data-go="#/extensions">Extensions</button><button class="btn" data-go="#/browse">Browse</button></div></div>`;
  }
  if (!S.lib.account && !recent.length) h += signInCard();
  return h;
}

// A show from the history on the Thor: resumes the episode, or plays the next one after it was watched.
function recentCard(b) {
  const n = nextOf(b);
  let line = b.sourceName || '';
  if (n && n.done) line = n.num >= 0 ? `Episode ${numLabel(n.num + 1)} is next` : 'Next episode';
  else if (n) line = `${n.num >= 0 ? `Episode ${numLabel(n.num)}` : esc(n.name || '')}${n.d > 0 ? ` · ${Math.max(1, Math.round((n.d - n.t) / 60))} min left` : ''}`;
  const p = n && !n.done && n.d > 0 ? Math.round(Math.min(1, n.t / n.d) * 100) : 0;
  return `<button class="card" data-show="${esc(b.key)}" data-resume="1">
    <img src="${esc(shelfImg(b))}" loading="lazy">
    <div class="ct"><div class="t">${esc(b.title)}</div><div class="m">${line}</div>
      <div class="prog"><i style="width:${p}%"></i></div><div class="m">${esc([b.sourceName, b.at ? ago(b.at) : ''].filter(Boolean).join(' · '))}</div></div>
  </button>`;
}

function savedTile(b) {
  const n = nextOf(b);
  const line = n ? (n.done ? `Ep ${numLabel(n.num + 1)} next` : `Ep ${numLabel(n.num)}`) : b.sourceName || 'AniList';
  return `<button class="tile" data-show="${esc(b.key)}">
    <img src="${esc(shelfImg(b))}" loading="lazy"><div class="t">${esc(b.title)}</div><div class="m">${esc(line)}</div></button>`;
}

// Watching list, split into new episodes (airing shows), unwatched episodes of finished shows,
// and airing shows that are caught up.
function groups() {
  const cur = L('CURRENT').concat(L('REPEATING'));
  const fresh = cur.filter((e) => e.newCount > 0 && e.mstatus === 'RELEASING');
  const cont = cur.filter((e) => e.newCount > 0 && e.mstatus !== 'RELEASING');
  const waiting = cur.filter((e) => e.newCount <= 0).sort((a, b) => (a.nextAt || 9e12) - (b.nextAt || 9e12));
  return { fresh, cont, waiting };
}

// The episode to open for an entry: the next unwatched one, or the show's page when caught up.
const openEp = (e) => (caughtUp(e) ? -1 : (e.progress || 0) + 1);

function card(e) {
  const next = e.progress + 1;
  let line;
  if (e.newCount > 0) {
    const what = e.mstatus === 'RELEASING' ? 'to watch' : 'left';
    line = `<span class="chip new">${e.newCount} ${what}</span> Episode ${next} is ready`;
  } else if (e.nextAt) {
    line = `Episode ${e.nextEp} in ${until(e.nextAt)}`;
  } else {
    line = caughtUp(e) ? 'All caught up' : `Episode ${next}`;
  }
  return `<button class="card" data-open="${e.id}" data-ep="${openEp(e)}">
    <img src="${esc(e.cover)}" loading="lazy">
    <div class="ct"><div class="t">${esc(e.title)}</div><div class="m">${line}</div>
      <div class="prog"><i style="width:${pct(e)}%"></i></div><div class="m">${watched(e)}</div></div>
  </button>`;
}

function row(e) {
  const side = e.nextAt ? `<b>Ep ${e.nextEp}</b>in ${until(e.nextAt)}` : caughtUp(e) ? '<b>Done</b>all watched' : `<b>Ep ${e.progress + 1}</b>up next`;
  return `<button class="row" data-open="${e.id}" data-ep="${openEp(e)}">
    <img src="${esc(e.cover)}" loading="lazy">
    <div class="ct"><div class="t">${esc(e.title)}</div>
      <div class="m">${watched(e)}${e.mstatus === 'RELEASING' && e.episodes ? ' · airing' : ''}</div>
      <div class="prog"><i style="width:${pct(e)}%"></i></div></div>
    <div class="side">${side}</div>
  </button>`;
}

function renderNext() {
  if (!S.lib.account) return signInCard();
  const g = groups();
  if (!g.fresh.length && !g.cont.length && !g.waiting.length) {
    return `<div class="empty"><b>Your Watching list is empty</b>Shows you're watching on AniList appear here with their new episodes.</div>` + syncLine();
  }
  let h = '';
  if (g.fresh.length) h += `<div class="slbl">New episodes</div><div class="cards">${g.fresh.map(card).join('')}</div>`;
  if (g.cont.length) h += `<div class="slbl">Continue</div><div class="cards">${g.cont.map(card).join('')}</div>`;
  if (g.waiting.length) h += `<div class="slbl">Waiting for episodes</div><div class="rows">${g.waiting.map(row).join('')}</div>`;
  return h + syncLine();
}

function syncLine() {
  const l = S.lib;
  const when = l.syncing ? 'Updating your lists…' : l.updated ? `Lists updated ${ago(l.updated)}` : 'Lists not loaded yet';
  return `<div class="note" style="margin-top:26px">${when}${l.error ? ` · <span class="err">${esc(l.error)}</span>` : ''}</div>
    <button class="btn" data-act="sync">Refresh now</button>`;
}

function renderSchedule() {
  if (!S.lib.account) return signInCard();
  const now = Date.now() / 1000, week = now + 7 * 86400;
  const items = [];
  for (const st of ['CURRENT', 'REPEATING', 'PLANNING', 'PAUSED']) for (const e of L(st)) if (e.nextAt && e.nextAt < week) items.push(e);
  items.sort((a, b) => a.nextAt - b.nextAt);
  if (!items.length) return `<div class="empty"><b>Nothing airing this week</b>Airing shows on your Watching and Planning lists appear here.</div>`;
  const days = [];
  for (const e of items) {
    const d = dayName(e.nextAt);
    if (!days.length || days[days.length - 1].d !== d) days.push({ d, items: [] });
    days[days.length - 1].items.push(e);
  }
  return days.map((day) => `<div class="day">${esc(day.d)}</div><div class="rows">${day.items.map((e) => `
    <button class="row" data-open="${e.id}" data-ep="-1">
      <div class="time">${hm(e.nextAt)}</div><img src="${esc(e.cover)}" loading="lazy">
      <div class="ct"><div class="t">${esc(e.title)}</div>
        <div class="m">Episode ${e.nextEp}${e.episodes ? ' of ' + e.episodes : ''} · ${LIST_NAMES[e.status] || ''}</div></div>
      <div class="side"><b>${until(e.nextAt)}</b>${e.newCount > 0 ? e.newCount + ' to watch' : 'caught up'}</div>
    </button>`).join('')}</div>`).join('');
}

function renderLists() {
  const saved = S.shelf.saved;
  const savedGrid = () => (saved.length ? `<div class="grid">${saved.map(savedTile).join('')}</div>`
    : `<div class="empty"><b>No saved shows</b>Press Save on a show, here or on the top screen, to keep it here with its cover, even offline.</div>`);
  if (!S.lib.account) return (saved.length ? `<div class="slbl">Saved shows</div>${savedGrid()}<div style="height:30px"></div>` : '') + signInCard();
  const savedSeg = saved.length || S.listTab === 'SAVED'
    ? `<button class="${S.listTab === 'SAVED' ? 'on' : ''}" data-list="SAVED">Saved ${saved.length}</button>` : '';
  if (S.listTab === 'SAVED') return `<div class="seg">${savedSeg}${listSegs()}</div>` + savedGrid();
  return listTiles(savedSeg);
}

const listSegs = () => Object.keys(LIST_NAMES)
  .filter((k) => L(k).length || k === 'CURRENT' || k === 'COMPLETED')
  .map((k) => `<button class="${k === S.listTab ? 'on' : ''}" data-list="${k}">${LIST_NAMES[k]} ${L(k).length}</button>`)
  .join('');

function listTiles(savedSeg) {
  const seg = savedSeg + listSegs();
  const done = S.listTab === 'COMPLETED' || S.listTab === 'DROPPED';
  const tiles = L(S.listTab).map((e) => {
    const m = S.listTab === 'COMPLETED' ? (e.score ? `★ ${e.score}` : `${e.episodes || e.progress} episodes`) : `${e.progress} / ${e.episodes || '?'}`;
    return `<button class="tile" data-open="${e.id}" data-ep="${done ? -1 : openEp(e)}">
      <img src="${esc(e.cover)}" loading="lazy"><div class="t">${esc(e.title)}</div><div class="m">${m}</div></button>`;
  }).join('');
  return `<div class="seg">${seg}</div>` + (tiles ? `<div class="grid">${tiles}</div>` : `<div class="empty">Nothing on this list yet.</div>`);
}

const searchForm = () =>
  `<form class="search" id="search"><input id="q" type="search" placeholder="Search your sources" enterkeyhint="search" autocomplete="off"><button class="btn primary">Search</button></form>`;
const link = (route, label) => `<button data-go="${route}">${label}</button>`;

function renderBrowse() {
  return searchForm() + `<div class="links">
    ${link('#/home', 'Home')}${link('#/browse', 'Sources')}${link('#/search', 'Search')}
    ${link('#/library', 'Library')}${link('#/downloads', 'Downloads')}${link('#/extensions', 'Extensions')}
  </div>
  <div class="note" style="margin-top:26px">Pages open on the top screen. Searches look through every installed source at once.</div>`;
}

// ---- downloads ----

const dlFor = (s) => (s.dlId ? (S.dls.items || []).find((r) => r.id === s.dlId) : null);
const dlActive = (r) => r.state === 'queued' || r.state === 'preparing' || r.state === 'downloading';
const dlWatched = (r) => r.d > 0 && r.t >= r.d * DONE;
const dlPct = (r) => (r.size > 0 ? Math.min(100, Math.round(((r.got || 0) / r.size) * 100)) : 0);
const mb = (n) => (n >= 1e9 ? (n / 1e9).toFixed(1) + ' GB' : Math.round((n || 0) / 1e6) + ' MB');
const dlShort = (r) => (r.state === 'queued' ? 'Waiting to download' : r.state === 'preparing' ? 'Preparing download…' : r.size ? `Downloading ${dlPct(r)}%` : 'Downloading…');
const dlImg = (r) => coverSrc(r.cover) || viaApp(r.source, r.anime && r.anime.thumbnail_url);

function dlStatus(r) {
  if (r.state === 'queued') return 'Waiting for the downloads ahead of it';
  if (r.state === 'preparing') return r.note || 'Getting the episode ready';
  if (!r.size) return r.note || 'Starting';
  let line = `${mb(r.got)} of ${mb(r.size)} · ${dlPct(r)}%`;
  const v = S.speed[r.id];
  if (r.note) line = r.note + ' · ' + line;
  else if (v > 0) line += ` · ${Math.max(1, Math.round((r.size - r.got) / v / 60))} min left`;
  return line;
}

function renderDownloads() {
  const items = S.dls.items || [];
  const foot = `<div class="note" style="margin-top:26px">Saved in ${esc(S.dls.folder || 'Movies/AniDeck')} on this Thor ·
    ${mb(S.dls.used)} used, ${mb(S.dls.free)} free. Episodes download in the quality picked in Settings, or the closest the source has.</div>`;
  if (!items.length) {
    return `<div class="empty"><b>No downloads yet</b>Press Download on Now Playing, or on a show's page on the top screen.
      Downloaded episodes play without a connection, here and from CrossBar.</div>` + foot;
  }
  let h = '';
  const going = items.filter(dlActive), failed = items.filter((r) => r.state === 'failed');
  if (going.length) h += `<div class="slbl">Downloading</div><div class="rows">${going.map((r) => dlRow(r, false)).join('')}</div>`;
  if (failed.length) {
    h += `<div class="slbl"${going.length ? ' style="margin-top:30px"' : ''}>Didn't finish</div>
      <div class="rows">${failed.map((r) => dlRow(r, false)).join('')}</div>`;
  }
  const shows = [];
  for (const r of items.filter((x) => x.state === 'done')) {
    const k = r.key || r.show;
    let g = shows.find((x) => x.k === k);
    if (!g) shows.push((g = { k, show: r.show, items: [] }));
    g.items.push(r);
  }
  for (const g of shows) {
    g.items.sort((x, y) => x.ep - y.ep);
    const size = g.items.reduce((n, r) => n + (r.size || 0), 0);
    h += `<div class="slbl" style="margin-top:30px">${esc(g.show)} · ${g.items.length} ${g.items.length === 1 ? 'episode' : 'episodes'} · ${mb(size)}</div>
      <div class="rows">${g.items.map((r) => dlRow(r, true)).join('')}</div>`;
  }
  return h + foot;
}

// grouped: under its show's heading, so the row leads with the episode.
function dlRow(r, grouped) {
  const id = esc(r.id);
  const src = dlImg(r);
  const img = src ? `<img src="${esc(src)}" loading="lazy">` : '<div class="noimg"></div>';
  const n = numLabel(r.ep);
  const name = bareTitle(r.epTitle);
  const ep = `Episode ${n}${name ? ' · ' + esc(name) : ''}`;
  let lines, acts;
  if (r.state === 'done') {
    const left = dlWatched(r) ? 'watched' : r.d > 0 && r.t > 5 ? `${Math.max(1, Math.round((r.d - r.t) / 60))} min left` : '';
    const on = S.state.offline && S.state.download === r.id;
    lines = `<div class="m">${esc([r.quality, mb(r.size), left].filter(Boolean).join(' · '))}</div>
      ${r.d > 0 && r.t > 5 ? `<div class="prog"><i style="width:${Math.min(100, Math.round((r.t / r.d) * 100))}%"></i></div>` : ''}`;
    acts = `<button class="btn primary" data-act="playDl" data-dl="${id}">${on ? 'Playing' : 'Play'}</button>
      <button class="btn ${S.confirmDl === r.id ? 'danger' : ''}" id="del-${id}" data-act="deleteDl" data-dl="${id}">${S.confirmDl === r.id ? 'Delete?' : 'Delete'}</button>`;
  } else if (r.state === 'failed') {
    lines = `<div class="m err">${esc(r.error || 'The download failed')}</div>`;
    acts = `<button class="btn primary" data-act="retryDl" data-dl="${id}">Retry</button>
      <button class="btn" data-act="deleteDl" data-dl="${id}">Remove</button>`;
  } else {
    lines = `<div class="m dl-line">${esc(dlStatus(r))}</div><div class="prog"><i class="dl-bar" style="width:${dlPct(r)}%"></i></div>`;
    acts = `<button class="btn" data-act="deleteDl" data-dl="${id}">Cancel</button>`;
  }
  return `<div class="row" data-dlrow="${id}">${img}<div class="ct">
      <div class="t">${grouped ? ep : esc(r.show)}</div>${grouped ? '' : `<div class="m">${ep}</div>`}${lines}</div>
    <div class="dl-acts">${acts}</div></div>`;
}

// From onDownloads, about twice a second while episodes download.
function dlUpdate(j) {
  if (!j || !j.items) return;
  const shapeOf = (d) => (d.items || []).map((r) => [r.id, r.state, r.t, r.cover, r.error].join(':')).join(',');
  const before = shapeOf(S.dls), now = Date.now(), dt = (now - S.dlAt) / 1000;
  for (const r of j.items) {
    const old = (S.dls.items || []).find((x) => x.id === r.id);
    if (r.state !== 'downloading' || !old || !(r.got > old.got) || !(dt > 0.3)) continue;
    const v = (r.got - old.got) / dt;
    S.speed[r.id] = S.speed[r.id] ? S.speed[r.id] * 0.7 + v * 0.3 : v;
  }
  S.dlAt = now;
  S.dls = j;
  dlBadge();
  if (before !== shapeOf(j) && (S.tab === 'downloads' || S.tab === 'now' || S.tab === 'settings') && !S.dragging) render();
  else dlProgress();
}

// Progress in place, without re-rendering under a finger or the highlight.
function dlProgress() {
  for (const r of S.dls.items || []) {
    if (!dlActive(r)) continue;
    const row = document.querySelector(`[data-dlrow="${CSS.escape(r.id)}"]`);
    if (!row) continue;
    const line = row.querySelector('.dl-line'), bar = row.querySelector('.dl-bar');
    if (line) line.textContent = dlStatus(r);
    if (bar) bar.style.width = dlPct(r) + '%';
  }
  const pill = $('#dlPill'), cur = dlFor(S.state);
  if (pill && cur && dlActive(cur)) pill.textContent = dlShort(cur);
}

function dlBadge() {
  const n = (S.dls.items || []).filter(dlActive).length;
  const b = $('#dlBadge');
  b.textContent = n;
  b.classList.toggle('show', n > 0);
}

// ---- settings ----

function signInBody() {
  return `<div class="note">Log in with your AniList account on the top screen. Your lists then show up here, and episodes
      you finish count on AniList.</div>
    <div class="acct-actions"><button class="btn primary" data-act="login">Log in on the top screen</button></div>
    <div class="note">Or show a public AniList profile (read-only):</div>
    <form class="search" id="userForm" style="padding-bottom:22px"><input id="uname" placeholder="AniList username" autocomplete="off"><button class="btn">Show</button></form>`;
}

function signInCard() {
  return `<div class="group" style="margin-top:6px"><div class="gt">Connect AniList</div>${signInBody()}${S.lib.error ? `<div class="err">${esc(S.lib.error)}</div>` : ''}</div>`;
}

const toggle = (k, label, sub) =>
  `<button class="opt" data-set="${k}"><span>${esc(label)}<small>${esc(sub)}</small></span><i class="sw ${S.settings[k] ? 'on' : ''}"></i></button>`;

function choice(k, label) {
  const v = String(S.settings[k] == null ? '' : S.settings[k]);
  const o = (PICKS[k] || []).find((x) => x[0] === v);
  return `<button class="opt" data-pick="${k}"><span>${esc(label)}</span><b style="font-size:23px;font-weight:600;color:var(--fg2)">${esc(o ? o[1] : v)}</b></button>`;
}

function renderSettings() {
  const a = S.lib.account;
  let acct;
  if (a) {
    const how = a.via === 'site' || a.via === 'login'
      ? 'Logged in. Lists and progress sync with AniList.'
      : 'Showing this public AniList profile. Log in to have episodes count automatically.';
    acct = `<div class="acct">${a.avatar ? `<img src="${esc(a.avatar)}">` : ''}<div class="ct">${esc(a.name)}<small>${how}</small></div></div>
      <div class="acct-actions">${a.via === 'user' ? '<button class="btn" data-act="login">Log in on the top screen</button>' : ''}
        <button class="btn" data-act="sync">Refresh lists</button><button class="btn" data-act="signout">Disconnect</button></div>`;
  } else {
    acct = signInBody();
  }
  if (S.lib.error) acct += `<div class="err">${esc(S.lib.error)}</div>`;
  const done = (S.dls.items || []).filter((r) => r.state === 'done').length;
  return `<div class="group"><div class="gt">AniList</div>${acct}
      ${toggle('autoTrack', 'Count episodes on AniList', S.lib.canTrack ? 'Moves your progress forward at 85% of an episode, for shows linked to AniList.' : 'Needs an AniList login (a public username is read-only).')}
    </div>
    <div class="group"><div class="gt">Playback</div>
      ${toggle('autoNext', 'Play the next episode', 'When an episode ends.')}
      ${toggle('autoSkip', 'Skip intros and recaps', 'On sources that mark them. Otherwise X skips 85 seconds.')}
      ${toggle('autoServer', 'Switch servers when one fails', 'Tries the next server or quality by itself.')}
      ${choice('subLang', 'Subtitle language')}
      ${choice('audioLang', 'Audio language')}
    </div>
    <div class="group"><div class="gt">Notifications</div>
      ${toggle('notify', 'New episode alerts', 'For shows on your Watching list, checked every 30 minutes on Wi-Fi.')}
    </div>
    <div class="group"><div class="gt">Downloads</div>
      ${choice('dlQuality', 'Download quality')}
      <div class="note" style="margin:0">${done} downloaded (${mb(S.dls.used)}) in ${esc(S.dls.folder || 'Movies/AniDeck')}, with ${mb(S.dls.free)} free on this Thor.</div>
    </div>
    <div class="group"><div class="gt">On this Thor</div>
      <div class="note" style="margin:0 0 16px">${S.shelf.recent.length} recently watched, ${S.shelf.saved.length} saved shows. They show on
        Now Playing and in CrossBar's bottom screen, with covers kept here so they work offline.</div>
      <button class="btn" data-act="clearRecent" ${S.shelf.recent.length ? '' : 'disabled'}>Clear recently watched</button>
    </div>
    <div class="group"><div class="gt">Controller and touch</div><div class="keys">
      <b>Touch</b><span>Tap either screen. The controller goes with the screen you touch, except while an episode plays on the top screen.</span>
      <b>Select</b><span>Switch the controller between the screens. Opening a show from this screen gives it to the top screen.</span>
      <b>D-pad / left stick</b><span>Move the highlight. While an episode plays: left/right seek 10 seconds, up/down change the volume.</span>
      <b>A</b><span>Choose the highlighted item; while an episode plays, play or pause</span>
      <b>B</b><span>Back: closes the keyboard and menus, then the player, then goes back a page</span>
      <b>Right stick</b><span>Scroll</span>
      <b>Start</b><span>Play or pause</span>
      <b>X</b><span>Skip the opening or ending (85 seconds where the source doesn't mark them)</span>
      <b>Y</b><span>The player's options: servers, quality, subtitles, audio and speed</span>
      <b>L1 / R1</b><span>Back or forward 10 seconds</span>
      <b>L2 / R2</b><span>Previous or next episode</span>
    </div></div>
    <div class="group"><div class="gt">Extensions</div>
      <div class="note" style="margin:0 0 16px">Shows come from Aniyomi extensions (the Yūzōnō repo). Add, update and set them up on the top screen.</div>
      <div class="acct-actions"><button class="btn" data-go="#/extensions">Extensions</button><button class="btn" data-go="#/settings">More settings</button></div>
    </div>
    <div class="note">AniDeck ${esc(S.version)}. Episodes stream and download straight from each source.</div>`;
}

// ---- chrome ----

function status() {
  $('#pad').innerHTML = `${I.pad}${K.active ? 'Bottom' : 'Top'}`;
  $('#net').innerHTML = `<i class="dot ${S.net ? '' : 'off'}"></i>${S.net ? 'Online' : 'Offline'}`;
  const a = S.lib.account;
  $('#who').innerHTML = a ? `${a.avatar ? `<img src="${esc(a.avatar)}">` : ''}${esc(a.name)}` : '';
  $('#offline').hidden = S.net;
}

function tickClock() {
  $('#clock').textContent = new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
}

function badge() {
  const n = groups().fresh.length;
  const b = $('#newBadge');
  b.textContent = n;
  b.classList.toggle('show', n > 0);
}

let toastTimer = 0;
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 2800);
}

// ---- controller: Pad.java sends the d-pad, A and B here while this screen has the controller ----

const K = { active: false, on: false, el: null, key: '', home: '' };
const DATA_KEYS = ['tab', 'cmd', 'd', 'epi', 'epdl', 'open', 'ep', 'show', 'act', 'set', 'pick', 'choose', 'sheet', 'srv', 'trk', 'v', 'list', 'range', 'go', 'dl', 'i'];
// Names a button across re-renders, which replace the elements.
const keyOf = (el) => el.id || [el.tagName, ...DATA_KEYS.map((k) => el.dataset[k] || ''), el.textContent.trim().slice(0, 24)].join('|');

function navTargets() {
  return [...($('.sheet') || document).querySelectorAll('button, input')].filter((el) => !el.disabled && AniNav.visible(el));
}

function navMark(el) {
  if (K.el && K.el !== el) K.el.classList.remove('kfocus');
  K.el = el;
  K.key = keyOf(el);
  el.classList.toggle('kfocus', K.on);
}

function navShow(el) {
  K.on = true;
  navMark(el);
  el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
}

function navHide() {
  K.on = false;
  if (K.el) K.el.classList.remove('kfocus');
}

// Where the highlight starts: the current choice in a sheet, the play button, or the page's first item.
function navStart(els) {
  return els.find((el) => el.matches('.choice.on')) || els.find((el) => el.id === 'playBtn')
    || els.find((el) => el.closest('#view')) || els[0];
}

// A d-pad press. The first one (or dir null) only shows the highlight.
function navMove(dir) {
  const els = navTargets();
  if (!els.length) return;
  const find = (key) => key && els.find((el) => keyOf(el) === key);
  let cur = K.el && K.el.isConnected && els.includes(K.el) ? K.el : find(K.key);
  if (!cur) {
    // A sheet opened (come back here when it closes) or closed (go back).
    if ($('.sheet')) K.home = K.key;
    else if ((cur = find(K.home))) K.home = '';
  }
  if (!cur) return navShow(navStart(els));
  if (!K.on || !dir) return navShow(cur);
  const next = AniNav.best(cur, els, dir);
  if (next) navShow(next);
}

// After a re-render the highlight goes back on the same button.
function navRestore() {
  if (!K.on) return;
  const el = navTargets().find((x) => x === K.el || keyOf(x) === K.key);
  if (el) navMark(el);
  else navMove(null);
}

// A: with nothing highlighted it plays or pauses, like on the top screen.
function navOk() {
  const el = K.on && K.el && K.el.isConnected ? K.el : null;
  if (!el) {
    if (playing()) D.cmd(JSON.stringify({ c: 'toggle' }));
    else navMove(null);
  } else if (el.tagName === 'INPUT') {
    // A real tap, so the keyboard opens. Screen pixels, through the page's zoom.
    const r = el.getBoundingClientRect(), k = devicePixelRatio * visualViewport.scale;
    D.tap(((r.left + r.right) / 2) * k, ((r.top + r.bottom) / 2) * k);
  } else {
    el.click();
  }
}

// B: closes the keyboard or a sheet, then goes to Now Playing, then hands the controller to the top screen.
function navBack() {
  const a = document.activeElement;
  if (a && a.tagName === 'INPUT') return a.blur();
  if (S.sheet) {
    S.sheet = '';
    return render();
  }
  if (S.tab !== 'now') {
    S.tab = 'now';
    return render();
  }
  D.padTop();
}

// ---- input ----

const cmd = (c) => D.cmd(JSON.stringify(c));

document.addEventListener('click', (ev) => {
  const t = ev.target.closest('button');
  if (!t || t.disabled) return;
  const d = t.dataset;
  if (d.tab) {
    S.tab = d.tab;
    S.sheet = '';
    render();
  } else if (d.cmd) {
    const c = { c: d.cmd };
    if (d.d) c.d = +d.d;
    cmd(c);
  } else if (d.epi !== undefined) {
    cmd({ c: 'episode', i: +d.epi });
  } else if (d.epdl) {
    cmd({ c: 'episode', dl: d.epdl });
  } else if (d.sheet) {
    S.sheet = d.sheet;
    render();
  } else if (d.srv !== undefined) {
    cmd({ c: 'server', h: +d.srv, v: +d.v });
    S.sheet = '';
    render();
  } else if (d.trk) {
    cmd({ c: d.trk, i: +d.i });
    S.sheet = '';
    render();
  } else if (d.choose) {
    S.settings[d.choose] = d.v;
    D.choose(d.choose, d.v);
    S.sheet = '';
    render();
  } else if (d.open) {
    const id = +d.open;
    const e = entry(id) || {};
    D.open(id, +(d.ep || 0), e.romaji || e.title || '');
    toast('Opening on the top screen…');
  } else if (d.show) {
    D.openShow(d.show, d.resume === '1');
    toast('Opening on the top screen…');
  } else if (d.go) {
    D.go(d.go);
  } else if (d.set) {
    S.settings[d.set] = !S.settings[d.set];
    D.set(d.set, S.settings[d.set]);
    render();
  } else if (d.pick) {
    S.sheet = 'pick:' + d.pick;
    render();
  } else if (d.list) {
    S.listTab = d.list;
    render();
  } else if (d.range !== undefined) {
    S.range = +d.range;
    render();
  } else if (d.act) {
    act(d.act, t);
  }
});

function act(a, t) {
  switch (a) {
    case 'download':
      cmd({ c: 'download' });
      break;
    case 'playDl':
      D.playDownload(t.dataset.dl);
      if (S.tab !== 'now') {
        S.tab = 'now';
        render();
      }
      break;
    case 'close':
      D.closePlayer();
      break;
    case 'retryDl':
      D.retryDownload(t.dataset.dl);
      toast('Trying the download again…');
      break;
    // Deleting a downloaded episode takes a second press; canceling one that's downloading doesn't.
    case 'deleteDl': {
      const id = t.dataset.dl, r = (S.dls.items || []).find((x) => x.id === id);
      clearTimeout(S.confirmTimer);
      if (r && r.state === 'done' && S.confirmDl !== id) {
        S.confirmDl = id;
        S.confirmTimer = setTimeout(() => {
          S.confirmDl = '';
          if (S.tab === 'downloads') render();
        }, 4000);
        render();
        break;
      }
      S.confirmDl = '';
      D.deleteDownload(id);
      toast(r && r.state === 'done' ? `Deleted episode ${numLabel(r.ep)}` : 'Download canceled');
      break;
    }
    case 'closeSheet':
      S.sheet = '';
      render();
      break;
    case 'sync':
      D.sync();
      toast('Refreshing your lists…');
      break;
    case 'login':
      D.login();
      toast('Log in on the top screen');
      break;
    case 'signout':
      D.signOut();
      break;
    case 'save': {
      const key = S.state.key, on = !isSaved(key);
      if (!key) break;
      D.save(key, on);
      toast(on ? 'Saved. It shows here and in CrossBar, even offline.' : 'Removed from your saved shows');
      break;
    }
    case 'clearRecent':
      D.clearRecent();
      toast('Cleared recently watched');
      break;
  }
}

// A cover the Thor doesn't have falls back to the online one, else its gray box stays.
document.addEventListener('error', (ev) => {
  const i = ev.target;
  if (i.tagName !== 'IMG') return;
  if (i.dataset.alt && i.getAttribute('src') !== i.dataset.alt) i.src = i.dataset.alt;
  else i.removeAttribute('src');
}, true);

document.addEventListener('submit', (ev) => {
  ev.preventDefault();
  const f = ev.target;
  if (f.id === 'search') {
    const q = $('#q').value.trim();
    if (q) {
      D.search(q);
      toast('Searching on the top screen…');
    }
  } else if (f.id === 'userForm') {
    D.useName($('#uname').value);
  }
  if (document.activeElement) document.activeElement.blur();
});

function boot() {
  try {
    const j = JSON.parse(D.init());
    S.state = j.state || {};
    S.lib = j.library || { lists: {} };
    S.net = j.net !== false;
    S.settings = j.settings || {};
    S.version = j.version || '';
    S.shelf = j.shelf || S.shelf;
    S.eps = j.episodes || [];
    S.dls = j.downloads || S.dls;
  } catch (e) {}
  if (!playing() && S.lib.account && !S.shelf.recent.length) S.tab = 'next';
  if (S.state.anilistId && !entry(S.state.anilistId)) D.media(S.state.anilistId);
  badge();
  dlBadge();
  render();
  tickClock();
  setInterval(tickClock, 15000);
  setInterval(() => {
    if (S.tab === 'next' || S.tab === 'schedule') render();
  }, 60000);
}

boot();
