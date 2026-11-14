'use strict';
// The bottom screen's companion page. Data comes from the app (window.Deck) and later updates
// arrive through window.deck; touches become commands for the top screen.
const D = window.Deck;
const S = {
  tab: 'now', state: {}, lib: { lists: {} }, net: true, settings: {}, media: {}, version: '',
  shelf: { recent: [], saved: [] }, listTab: 'CURRENT', range: 0, rate: 1, dragging: false, expectNav: false,
};
const RATES = [1, 1.25, 1.5, 2, 0.75];
const TITLES = { now: 'Now Playing', next: 'Up Next', schedule: 'Schedule', lists: 'Lists', browse: 'Browse', settings: 'Settings' };
const LIST_NAMES = { CURRENT: 'Watching', REPEATING: 'Rewatching', COMPLETED: 'Completed', PAUSED: 'Paused', PLANNING: 'Planning', DROPPED: 'Dropped' };
const STATUS_NAMES = { RELEASING: 'Airing', FINISHED: 'Finished', NOT_YET_RELEASED: 'Upcoming', HIATUS: 'On hiatus', CANCELLED: 'Cancelled' };
// Tags on the site's server choices; "dl" (downloadable) is left out because downloads are off.
const SERVER_TAGS = { 's-sub': 'Soft subs', 'h-sub': 'Hard subs', embed: 'Embedded player', dl: '' };
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
// Saved and recently played shows (kept on the Thor, covers included, so they show offline).
const isSaved = (id) => !!id && S.shelf.saved.some((e) => e.id === id);
const shelfCover = (e) => (e.id > 0 ? `https://shelf.anideck/cover/${e.id}` : e.cover || '');

// ---- formatting ----

const pad = (n) => String(n).padStart(2, '0');
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
  onBack() {
    if (S.tab === 'now') return;
    S.tab = 'now';
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

const shape = (s) => {
  const p = s.player;
  return [s.page, s.path, s.ep, s.anilistId, s.show, s.epTitle, s.epCount, s.hasPrev, s.hasNext, s.theater, !!p, p ? p.error : '', p ? p.state : '',
    s.menu ? JSON.stringify(s.menu) : ''].join('|');
};

function stateChanged(prev) {
  const s = S.state;
  if (s.page === 'login' && prev.page !== 'login') S.tab = 'now';
  const newEpisode = s.page === 'watch' && (prev.path !== s.path || prev.ep !== s.ep);
  if (newEpisode) S.range = Math.floor(Math.max(0, (s.ep || 1) - 1) / 100);
  // expectNav is true after an episode chip, or the AniList id of a show opened from a list.
  const show = s.page === 'watch' || s.page === 'info';
  if (show && S.expectNav && (S.expectNav === true ? newEpisode : s.anilistId === S.expectNav)) {
    S.tab = 'now';
    S.expectNav = false;
  } else if (newEpisode && prev.page !== 'watch') S.tab = 'now';
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
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('on', t.dataset.tab === S.tab));
  $('#heading').textContent = TITLES[S.tab];
  if (S.tab !== 'now') setAccent();
  const f = { now: renderNow, next: renderNext, schedule: renderSchedule, lists: renderLists, browse: renderBrowse, settings: renderSettings }[S.tab];
  view.innerHTML = f();
  view.dataset.tab = S.tab;
  view.scrollTop = keep;
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
  if (s.page === 'login') {
    setAccent();
    return `<div class="idle"><div class="idle-title">Logging in to AniList</div>
      <div class="idle-sub">Enter your AniList email and password on the top screen; the keyboard opens down here.
        When you're done, the site takes you back and your lists show up in Up Next.</div>
      <button class="btn" data-go="/">Back to the site</button></div>`;
  }
  if (s.page !== 'watch' && s.page !== 'info') {
    setAccent();
    return renderIdle();
  }
  const m = info(s.anilistId);
  setAccent(m.color);
  const facts = [];
  if (m.format) facts.push(m.format.replace(/_/g, ' '));
  if (m.year) facts.push(m.year);
  if (m.mstatus) facts.push(STATUS_NAMES[m.mstatus] || m.mstatus);
  let h = `<div class="hero" style="--img:url('${esc(m.banner || m.cover || '')}')">
    ${m.cover ? `<img class="cover" src="${esc(m.cover)}">` : ''}
    <div class="meta">
      <div class="kicker">${s.page === 'watch' ? 'Now playing' : 'On the top screen'}</div>
      <div class="htitle">${esc(s.show || m.title || '')}</div>
      ${s.page === 'watch' ? `<div class="hsub">Episode ${s.ep}${s.epTitle ? ' · ' + esc(s.epTitle) : ''}</div>` : ''}
      <div class="facts">${facts.map((f) => `<span class="chip">${esc(f)}</span>`).join('')}${listChip(m)}${m.nextAt ? `<span class="chip">Ep ${m.nextEp} in ${until(m.nextAt)}</span>` : ''}</div>
    </div>
  </div>`;
  if (s.page === 'info') {
    const next = Math.max(1, Math.min((m.progress || 0) + 1, m.aired || 1));
    return h + `<div class="cta"><button class="btn primary xl" data-act="watchEp" data-ep="${next}">${I.play}Play episode ${next}</button>
      ${s.anilistId ? `<button class="btn xl" data-act="save">${isSaved(s.anilistId) ? '★ Saved' : '☆ Save show'}</button>` : ''}</div>`;
  }
  const p = s.player;
  if (p && p.state === 'error' && p.allFailed) {
    h += `<div class="error"><div class="et"><b>None of the servers could play this episode</b>Some of the site's video
      servers can't be reached from this network. Try again later, or pick a server to retry it.</div>
      <button class="btn" data-cmd="menu" data-label="Server">Servers</button></div>`;
  } else if (p && p.state === 'error') {
    h += `<div class="error"><div class="et"><b>This video won't load</b>${esc(p.error)}. Some of the site's video servers
      can't be reached from this network, so try another server.</div><button class="btn" data-cmd="menu" data-label="Server">Servers</button></div>`;
  } else if (p && p.state === 'stalled') {
    h += `<div class="error"><div class="et"><b>Waiting for the video</b>If it doesn't start in a few seconds, this server
      may be down or blocked on this network. Try another one.</div><button class="btn" data-cmd="menu" data-label="Server">Servers</button></div>`;
  }
  h += `<div class="seek"><span id="tCur">0:00</span><div class="track" id="track"><div class="fill" id="fill"></div><div class="knob" id="knob"></div></div><span id="tDur">0:00</span></div>
  <div class="controls">
    <button class="ctl" data-cmd="prev" ${s.hasPrev ? '' : 'disabled'}>${I.prev}</button>
    <button class="ctl" data-cmd="seekBy" data-d="-10">${I.back10}</button>
    <button class="ctl play" data-cmd="toggle" id="playBtn">${p && p.state === 'playing' ? I.pause : I.play}</button>
    <button class="ctl" data-cmd="seekBy" data-d="10">${I.fwd10}</button>
    <button class="ctl" data-cmd="next" ${s.hasNext ? '' : 'disabled'}>${I.next}</button>
  </div>
  <div class="pills">
    <button class="pill" data-cmd="skip">Skip intro / outro</button>
    <button class="pill ${s.theater ? 'on' : ''}" data-cmd="theater">Fill screen</button>
    <button class="pill" data-cmd="captions">Subtitles</button>
    <button class="pill" data-act="speed">Speed ${S.rate}×</button>
    <button class="pill" data-cmd="menu" data-label="Server">Server</button>
    <button class="pill" data-cmd="menu" data-label="Language">Sub / Dub</button>
    ${s.anilistId ? `<button class="pill ${isSaved(s.anilistId) ? 'on' : ''}" data-act="save">${isSaved(s.anilistId) ? '★ Saved' : '☆ Save show'}</button>` : ''}
  </div>`;
  return h + episodeStrip(s, m) + (s.menu ? menuSheet(s.menu) : '');
}

// The choices of the site's open Server or Language menu, picked here instead of on the top screen.
function menuSheet(menu) {
  const opts = menu.options.map((o, i) => {
    const [name, ...tags] = o.parts;
    const chips = tags.map((t) => (t.toLowerCase() in SERVER_TAGS ? SERVER_TAGS[t.toLowerCase()] : t)).filter(Boolean);
    return `<button class="choice${o.active ? ' on' : ''}" data-pick="${i}" ${o.disabled ? 'disabled' : ''}>
      <b>${esc(name || 'Option ' + (i + 1))}</b>${chips.map((c) => `<span class="chip">${esc(c)}</span>`).join('')}</button>`;
  }).join('');
  return `<div class="sheet"><div class="sheet-head">
      <div class="sheet-title">${menu.label === 'Server' ? 'Choose a server' : 'Subtitles or dub'}</div>
      <button class="btn" data-cmd="menu" data-label="${esc(menu.label)}">Close</button>
    </div><div class="choices">${opts}</div>
    ${menu.label === 'Server' ? '<div class="note">If an episode won\'t load, another server usually works.</div>' : ''}</div>`;
}

function listChip(m) {
  if (!m.status) return '';
  return `<span class="chip acc">${LIST_NAMES[m.status] || m.status} · ${m.progress || 0}/${m.episodes || '?'}</span>`;
}

function episodeStrip(s, m) {
  const total = Math.max(s.epCount || 0, m.aired || 0, s.ep || 0);
  if (!total) return '';
  const per = 100, pages = Math.ceil(total / per);
  S.range = Math.min(S.range, pages - 1);
  const from = S.range * per + 1, to = Math.min(total, from + per - 1);
  const seen = m.progress || 0, aired = m.aired || total;
  let chips = '';
  for (let n = from; n <= to; n++) {
    const cls = (n === s.ep ? ' cur' : '') + (n <= seen ? ' seen' : '') + (n > aired ? ' soon' : '');
    chips += `<button class="ep${cls}" data-cmd="episode" data-ep="${n}">${n}</button>`;
  }
  let ranges = '';
  if (pages > 1) {
    for (let r = 0; r < pages; r++) ranges += `<button class="${r === S.range ? 'on' : ''}" data-range="${r}">${r * per + 1}–${Math.min(total, (r + 1) * per)}</button>`;
  }
  return `<div class="eps-head"><div class="slbl">Episodes</div><div class="ranges">${ranges}</div></div><div class="strip" id="strip">${chips}</div>`;
}

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

function renderIdle() {
  const g = groups();
  const picks = g.fresh.concat(g.cont, g.waiting).slice(0, 4);
  const recent = S.shelf.recent.slice(0, 4), saved = S.shelf.saved.slice(0, 5);
  let h = `<div class="idle"><div class="idle-title">Nothing playing</div>
    <div class="idle-sub">Pick something to watch, or browse the site on the top screen.</div>${searchForm()}</div>`;
  if (recent.length) h += `<div class="slbl">Recently played</div><div class="cards">${recent.map(recentCard).join('')}</div>`;
  if (saved.length) h += `<div class="slbl" style="margin-top:30px">Saved shows</div><div class="grid">${saved.map(savedTile).join('')}</div>`;
  if (picks.length) h += `<div class="slbl" style="margin-top:30px">From your Watching list</div><div class="cards">${picks.map(card).join('')}</div>`;
  else if (!S.lib.account && !recent.length) h += signInCard();
  return h;
}

// An episode from the history on the Thor: resumes it, or opens the next one once it was watched to the end.
function recentCard(e) {
  const done = e.d > 0 && e.t > e.d * 0.92;
  const next = done && (!e.epCount || e.ep < e.epCount);
  const ep = next ? e.ep + 1 : e.ep;
  const left = e.d > 0 ? Math.max(1, Math.round((e.d - e.t) / 60)) : 0;
  const line = next ? `Episode ${ep} is next` : done ? `Finished episode ${e.ep}` : `Episode ${e.ep}${left ? ` · ${left} min left` : ''}`;
  const target = e.id > 0 ? `data-open="${e.id}" data-ep="${ep}"` : `data-go="${esc(e.path)}?ep=${ep}"`;
  const p = next || !e.d ? 0 : Math.round(Math.min(1, e.t / e.d) * 100);
  return `<button class="card" ${target}>
    <img src="${esc(shelfCover(e))}" data-alt="${esc(e.cover || '')}" loading="lazy">
    <div class="ct"><div class="t">${esc(e.show)}</div><div class="m">${line}</div>
      <div class="prog"><i style="width:${p}%"></i></div><div class="m">${ago(e.at)}</div></div>
  </button>`;
}

function savedLine(e) {
  if (e.nextAt > Date.now() / 1000) return `Ep ${e.nextEp} in ${until(e.nextAt)}`;
  const format = e.format && e.format !== 'TV' ? e.format.replace(/_/g, ' ') : '';
  return [format, e.episodes ? `${e.episodes} episodes` : '', e.year || ''].filter(Boolean).join(' · ');
}

function savedTile(e) {
  const r = S.shelf.recent.find((x) => x.id === e.id);
  return `<button class="tile" data-open="${e.id}" data-ep="${r ? r.ep : 0}">
    <img src="${esc(shelfCover(e))}" data-alt="${esc(e.cover || '')}" loading="lazy"><div class="t">${esc(e.title)}</div>
    <div class="m">${esc(savedLine(e))}</div></button>`;
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
    : `<div class="empty"><b>No saved shows</b>Press "Save show" on Now Playing to keep a show here, with its cover and details, even offline.</div>`);
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
  `<form class="search" id="search"><input id="q" type="search" placeholder="Search anime" enterkeyhint="search" autocomplete="off"><button class="btn primary">Search</button></form>`;
const link = (path, label) => `<button data-go="${path}">${label}</button>`;

function renderBrowse() {
  return searchForm() + `<div class="links">
    ${link('/', 'Home')}${link('/trending', 'Trending')}${link('/schedule', 'Airing schedule')}
    ${link('/history', 'History')}${link('/profile', 'Profile')}<button data-cmd="click" data-label="Random Anime">Random</button>
  </div>
  <div class="note" style="margin-top:26px">Pages open on the top screen. You can also tap and scroll the site up there.</div>`;
}

function signInBody() {
  return `<div class="note">Log in to the site with your AniList account on the top screen. Your Watching and Completed
      lists then show up here, and finished episodes update your progress.</div>
    <div class="acct-actions"><button class="btn primary" data-act="login">Log in on the top screen</button></div>
    <div class="note">Or show a public AniList profile (read-only):</div>
    <form class="search" id="userForm" style="padding-bottom:22px"><input id="uname" placeholder="AniList username" autocomplete="off"><button class="btn">Show</button></form>`;
}

function signInCard() {
  return `<div class="group" style="margin-top:6px"><div class="gt">Connect AniList</div>${signInBody()}${S.lib.error ? `<div class="err">${esc(S.lib.error)}</div>` : ''}</div>`;
}

const toggle = (k, label, sub) =>
  `<button class="opt" data-set="${k}"><span>${esc(label)}<small>${esc(sub)}</small></span><i class="sw ${S.settings[k] ? 'on' : ''}"></i></button>`;

function renderSettings() {
  const a = S.lib.account;
  let acct;
  if (a) {
    const how = a.via === 'site'
      ? 'Signed in through your site login. Lists and progress sync with AniList.'
      : 'Showing this public AniList profile. Log in to the site to update progress automatically.';
    acct = `<div class="acct">${a.avatar ? `<img src="${esc(a.avatar)}">` : ''}<div class="ct">${esc(a.name)}<small>${how}</small></div></div>
      <div class="acct-actions">${a.via === 'site' ? '' : '<button class="btn" data-act="login">Log in on the top screen</button>'}
        <button class="btn" data-act="sync">Refresh lists</button><button class="btn" data-act="signout">Disconnect</button></div>`;
  } else {
    acct = signInBody();
  }
  if (S.lib.error) acct += `<div class="err">${esc(S.lib.error)}</div>`;
  return `<div class="group"><div class="gt">AniList</div>${acct}</div>
    <div class="group"><div class="gt">Playback</div>
      ${toggle('autoTheater', 'Fill the top screen when an episode plays', 'Get the page back with B or Y, or tap the top screen and choose Exit fill screen.')}
      ${toggle('autoServer', 'Switch servers when a video won\'t load', 'Tries the site\'s other servers for the episode, one at a time.')}
      ${toggle('autoTrack', 'Update AniList when I finish an episode', S.lib.canTrack ? 'Moves your progress forward at 85% of an episode.' : 'Needs the site login (a public username is read-only).')}
    </div>
    <div class="group"><div class="gt">Notifications</div>
      ${toggle('notify', 'New episode alerts', 'For shows on your Watching list, checked every 30 minutes on Wi-Fi.')}
    </div>
    <div class="group"><div class="gt">On this Thor</div>
      <div class="note" style="margin:0 0 16px">${S.shelf.recent.length} recently played, ${S.shelf.saved.length} saved shows. They show on
        Now Playing and in CrossBar's bottom screen, with covers kept here so they work offline.</div>
      <button class="btn" data-act="clearRecent" ${S.shelf.recent.length ? '' : 'disabled'}>Clear recently played</button>
    </div>
    <div class="group"><div class="gt">Controller and touch</div><div class="keys">
      <b>Touch</b><span>Tap either screen. The controller goes with the screen you touch, except while a video fills the top screen.</span>
      <b>Select</b><span>Switch the controller between the screens. Opening a show from this screen gives it to the top screen.</span>
      <b>D-pad / left stick</b><span>Move the highlight</span>
      <b>A</b><span>Choose the highlighted item; with nothing highlighted, play or pause</span>
      <b>B</b><span>Back: closes the keyboard and menus, leaves fill-screen, then goes back a page</span>
      <b>Right stick</b><span>Scroll</span>
      <b>Start</b><span>Play or pause</span>
      <b>X</b><span>Skip intro or outro</span>
      <b>Y</b><span>Fill screen on or off</span>
      <b>L1 / R1</b><span>Back or forward 10 seconds</span>
      <b>L2 / R2</b><span>Previous or next episode</span>
      <b>Fill-screen</b><span>D-pad left/right seeks, up/down changes the volume</span>
    </div></div>
    <div class="note">AniDeck ${esc(S.version)}. Episodes stream from ${esc((S.settings.site || '').replace(/^https:\/\//, ''))}; downloads are turned off.</div>`;
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
const DATA_KEYS = ['tab', 'cmd', 'label', 'd', 'ep', 'open', 'act', 'set', 'pick', 'list', 'range', 'go'];
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

// Where the highlight starts: the picked server in a menu, the play button, or the page's first item.
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
    // A menu sheet opened (come back here when it closes) or closed (go back).
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
    if (S.state.player) D.cmd(JSON.stringify({ c: 'toggle' }));
    else navMove(null);
  } else if (el.tagName === 'INPUT') {
    // A real tap, so the keyboard opens. Screen pixels, through the page's zoom.
    const r = el.getBoundingClientRect(), k = devicePixelRatio * visualViewport.scale;
    D.tap(((r.left + r.right) / 2) * k, ((r.top + r.bottom) / 2) * k);
  } else {
    el.click();
  }
}

// B: closes the keyboard or menu, then goes to Now Playing, then hands the controller to the top screen.
function navBack() {
  const a = document.activeElement;
  if (a && a.tagName === 'INPUT') return a.blur();
  if (S.state.menu) return D.cmd(JSON.stringify({ c: 'menu', label: S.state.menu.label }));
  if (S.tab !== 'now') {
    S.tab = 'now';
    return render();
  }
  D.padTop();
}

// ---- input ----

document.addEventListener('click', (ev) => {
  const t = ev.target.closest('button');
  if (!t || t.disabled) return;
  const d = t.dataset;
  if (d.tab) {
    S.tab = d.tab;
    render();
  } else if (d.cmd) {
    const c = { c: d.cmd };
    if (d.d) c.d = +d.d;
    if (d.ep) {
      c.ep = +d.ep;
      S.expectNav = true;
    }
    if (d.label) c.label = d.label;
    D.cmd(JSON.stringify(c));
  } else if (d.pick) {
    D.cmd(JSON.stringify({ c: 'pick', i: +d.pick }));
  } else if (d.open) {
    const id = +d.open;
    const e = entry(id) || S.shelf.saved.find((x) => x.id === id) || S.shelf.recent.find((x) => x.id === id) || {};
    S.expectNav = id;
    D.open(id, +(d.ep || 0), e.romaji || e.title || e.show || '');
    toast('Opening on the top screen…');
  } else if (d.go) {
    D.go(d.go);
  } else if (d.set) {
    S.settings[d.set] = !S.settings[d.set];
    D.set(d.set, S.settings[d.set]);
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
    case 'speed':
      S.rate = RATES[(RATES.indexOf(S.rate) + 1) % RATES.length];
      D.cmd(JSON.stringify({ c: 'rate', r: S.rate }));
      render();
      break;
    case 'watchEp':
      S.expectNav = true;
      D.go(S.state.path.replace(/^\/info\//, '/watch/') + '?ep=' + t.dataset.ep);
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
      const id = S.state.anilistId, on = !isSaved(id);
      if (!id) break;
      D.save(id, S.state.show || info(id).title || '', on);
      toast(on ? 'Saved. It shows here and in CrossBar, even offline.' : 'Removed from your saved shows');
      break;
    }
    case 'clearRecent':
      D.clearRecent();
      toast('Cleared recently played');
      break;
  }
}

// A cover the Thor doesn't have yet falls back to the online one.
document.addEventListener('error', (ev) => {
  const i = ev.target;
  if (i.tagName === 'IMG' && i.dataset.alt && i.getAttribute('src') !== i.dataset.alt) i.src = i.dataset.alt;
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
  } catch (e) {}
  if (S.state.page !== 'watch' && S.state.page !== 'info' && S.lib.account) S.tab = 'next';
  if (S.state.anilistId && !entry(S.state.anilistId)) D.media(S.state.anilistId);
  badge();
  render();
  tickClock();
  setInterval(tickClock, 15000);
  setInterval(() => {
    if (S.tab === 'next' || S.tab === 'schedule') render();
  }, 60000);
}

boot();
