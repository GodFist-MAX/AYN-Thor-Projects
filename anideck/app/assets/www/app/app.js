// AniDeck's top screen: shows from the installed Aniyomi extensions, show pages and episodes, the library,
// downloads, extensions and settings. The app answers requests through window.AniDeck (call → app.reply)
// and pushes changes with app.on(event, data); the controller arrives as app.nav/ok/back/pad/scroll.
'use strict';
(() => {
  const B = window.AniDeck;
  const app = (window.app = {});
  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => Array.from(el.querySelectorAll(s));
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  const enc = encodeURIComponent;
  const DONE = 0.85;

  const ICONS = {
    home: '<path d="M3 11l9-8 9 8"/><path d="M5 10v10h5v-6h4v6h5V10"/>',
    browse: '<circle cx="12" cy="12" r="9"/><path d="M15.5 8.5l-2 5-5 2 2-5z"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/>',
    library: '<path d="M6 3h12v18l-6-4-6 4z"/>',
    download: '<path d="M12 4v11"/><path d="M7 10l5 5 5-5"/><path d="M5 20h14"/>',
    ext: '<rect x="4" y="4" width="7" height="7" rx="1.5"/><rect x="13" y="4" width="7" height="7" rx="1.5"/><rect x="4" y="13" width="7" height="7" rx="1.5"/><path d="M16.5 13.5v6M13.5 16.5h6"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M12 2.5v2.2M12 19.3v2.2M4.6 4.6l1.6 1.6M17.8 17.8l1.6 1.6M2.5 12h2.2M19.3 12h2.2M4.6 19.4l1.6-1.6M17.8 6.2l1.6-1.6"/>',
    play: '<path class="fill" d="M7 4.5v15l13-7.5z"/>',
    star: '<path d="M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8L12 16.8l-5.2 2.8 1-5.8-4.3-4.1 5.9-.8z"/>',
    starOn: '<path class="fill" d="M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.8L12 16.8l-5.2 2.8 1-5.8-4.3-4.1 5.9-.8z"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    x: '<path d="M6 6l12 12M18 6L6 18"/>',
    more: '<circle class="fill" cx="5.5" cy="12" r="1.7"/><circle class="fill" cx="12" cy="12" r="1.7"/><circle class="fill" cx="18.5" cy="12" r="1.7"/>',
    back: '<path d="M15 5l-7 7 7 7"/>',
    refresh: '<path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 4v7h-7"/>',
    filter: '<path d="M4 5h16l-6 7.5V19l-4 2v-8.5z"/>',
    trash: '<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/>',
    link: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>',
    gear: '<circle cx="12" cy="12" r="3"/><path d="M12 2.5v2.2M12 19.3v2.2M4.6 4.6l1.6 1.6M17.8 17.8l1.6 1.6M2.5 12h2.2M19.3 12h2.2M4.6 19.4l1.6-1.6M17.8 6.2l1.6-1.6"/>',
    sort: '<path d="M7 4v16M3.5 16.5L7 20l3.5-3.5M17 20V4M13.5 7.5L17 4l3.5 3.5"/>',
  };
  const icon = (n, cls = '') => `<svg class="i ${cls}" viewBox="0 0 24 24">${ICONS[n]}</svg>`;

  // ---- talking to the app ----

  let seq = 0;
  const waiting = new Map();
  function api(method, args) {
    return new Promise((resolve, reject) => {
      const id = ++seq;
      waiting.set(id, { resolve, reject });
      try {
        B.call(id, method, JSON.stringify(args || {}));
      } catch (e) {
        waiting.delete(id);
        reject(e);
      }
    });
  }
  app.reply = (id, j) => {
    const w = waiting.get(id);
    if (!w) return;
    waiting.delete(id);
    if (j && j.error !== undefined) w.reject(new Error(j.error || 'Something went wrong'));
    else w.resolve(j ? j.ok : null);
  };
  const toast = (t) => B.toast(String(t));

  const S = {
    sources: [],
    sourcesLoaded: false,
    shelf: { recent: [], saved: [] },
    downloads: { items: [] },
    library: null,
    settings: {},
    net: true,
  };
  const srcById = (id) => S.sources.find((s) => s.id === String(id));
  const keyOf = (source, url) => source + '|' + url;
  const imgUrl = (source, url) => (url && /^https?:\/\//.test(url) ? `/img/${enc(source || '0')}?u=${enc(url)}` : '');
  const coverOf = (b) => (b.cover ? `/cover/${b.cover}` : imgUrl(b.source || '0', b.coverUrl));
  const numLabel = (n) => (n == null || n < 0 || Number.isNaN(+n) ? '' : String(Math.round(n * 100) / 100));
  // An episode's own title, without a repeat of its number ("Episode 3 - …").
  const bareTitle = (t) => {
    const s = String(t || '').trim().replace(/^(episode|ep\.?|e)\s*[\d.]+\s*[:\-–—]?\s*/i, '').trim();
    return /^[\d.]*$/.test(s) ? '' : s;
  };
  const clock = (sec) => {
    const s = Math.max(0, Math.round(sec)), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
    return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0');
  };
  const mins = (sec) => Math.max(1, Math.round(sec / 60));
  const size = (b) => (b >= 1e9 ? (b / 1e9).toFixed(1) + ' GB' : Math.max(0, Math.round(b / 1e6)) + ' MB');
  const initials = (t) => String(t || '?').replace(/[^\p{L}\p{N} ]/gu, '').split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase();
  const ago = (ms) => {
    const m = Math.round((Date.now() - ms) / 60000);
    return m < 1 ? 'just now' : m < 60 ? m + ' min ago' : m < 1440 ? Math.round(m / 60) + ' h ago' : Math.round(m / 1440) + ' d ago';
  };
  const animeMem = new Map();

  // ---- the controller ----

  let focusEl = null, padOn = false;
  // The highlight rests where it is only because the page had nothing to highlight yet (still loading).
  let parked = false;
  const typing = (el) => el && /^(INPUT|TEXTAREA)$/.test(el.tagName);
  const sheetOpen = () => document.body.classList.contains('sheet');

  function focusables() {
    const roots = sheetOpen() ? [$('#sheet')] : [$('#rail'), cur() && cur().el];
    const out = [];
    for (const root of roots) {
      if (!root) continue;
      for (const el of root.querySelectorAll('button, input, [data-f]')) {
        if (el.disabled || el.closest('[hidden]')) continue;
        if (AniNav.visible(el)) out.push(el);
      }
    }
    return out;
  }

  function setFocus(el, scroll = true) {
    if (focusEl) focusEl.classList.remove('kf');
    focusEl = el || null;
    if (!el) return;
    el.classList.add('kf');
    if (scroll) reveal(el);
  }

  /** Scrolls the highlight into view: rows sideways, the page or sheet up and down, clear of the sticky header. */
  function reveal(el) {
    const row = el.closest('.row, .ranges');
    if (row) {
      const r = el.getBoundingClientRect(), rr = row.getBoundingClientRect();
      if (r.left < rr.left + 6) row.scrollLeft -= rr.left + 6 - r.left + 30;
      else if (r.right > rr.right - 6) row.scrollLeft += r.right - rr.right + 6 + 30;
    }
    const box = el.closest('#sheet, .page');
    if (!box) return;
    const r = el.getBoundingClientRect(), b = box.getBoundingClientRect();
    const head = box.classList.contains('page') && !el.closest('.ph') ? box.querySelector('.ph') : null;
    const top = Math.max(b.top, head ? head.getBoundingClientRect().bottom : 0) + 6, bottom = Math.min(b.bottom, innerHeight) - 8;
    if (r.top < top) box.scrollTop -= top - r.top + 8;
    else if (r.bottom > bottom) box.scrollTop += r.bottom - bottom + 8;
  }

  function startFocus(els = focusables()) {
    const c = cur();
    parked = false;
    if (!sheetOpen() && c && c.focus && els.includes(c.focus)) return c.focus;
    const pool = els.filter((el) => !el.closest('#rail'));
    const pref = pool.find((el) => el.hasAttribute('data-first') && AniNav.onScreen(el));
    if (pref) return pref;
    const head = !sheetOpen() && c ? c.el.querySelector('.ph') : null;
    const first = AniNav.first(pool, head ? head.getBoundingClientRect().bottom : 0);
    if (first && !(head && head.contains(first))) return first;
    parked = true;
    return first || AniNav.first(els) || null;
  }

  app.pad = (show) => {
    padOn = !!show;
    document.body.classList.toggle('pad', padOn);
    if (padOn && (parked || !(focusEl && focusables().includes(focusEl)))) setFocus(startFocus());
  };

  app.nav = (dir) => {
    if (!padOn) return app.pad(true);
    const els = focusables();
    if (!focusEl || !els.includes(focusEl)) return setFocus(startFocus(els));
    if (typing(focusEl)) focusEl.blur();
    let next = AniNav.best(focusEl, els, dir);
    if (!next && dir === 'left' && !sheetOpen() && !focusEl.closest('#rail')) next = $('#rail .tab.on') || $('#rail .tab');
    parked = false;
    if (next) return setFocus(next);
    const box = sheetOpen() ? $('#sheet') : cur() && cur().el;
    if (box && (dir === 'down' || dir === 'up')) box.scrollTop += dir === 'down' ? 140 : -140;
  };

  app.ok = () => {
    if (!padOn || !focusEl || !document.contains(focusEl)) return app.pad(true);
    if (typing(focusEl)) {
      // A real tap, so the keyboard opens.
      const r = focusEl.getBoundingClientRect(), k = devicePixelRatio * (window.visualViewport ? visualViewport.scale : 1);
      B.tap(((r.left + r.right) / 2) * k, ((r.top + r.bottom) / 2) * k);
      return;
    }
    focusEl.click();
  };

  app.scroll = (dy) => {
    const box = sheetOpen() ? $('#sheet') : cur() && cur().el;
    if (box) box.scrollTop += dy;
  };

  /** Re-renders keep the highlight on the same thing, found by data-sig (or what it opens or does). */
  function keepFocus(fn) {
    const was = padOn ? focusEl : null;
    const box = was && was.closest('.page, #sheet');
    const d = was ? was.dataset : {};
    const sel = d.sig ? `[data-sig="${CSS.escape(d.sig)}"]` : d.go ? `[data-go="${CSS.escape(d.go)}"]`
      : d.act ? `[data-act="${CSS.escape(d.act)}"]${d.i ? `[data-i="${CSS.escape(d.i)}"]` : ''}` : '';
    fn();
    if (parked && was) {
      const into = startFocus();
      if (!parked || !document.contains(was)) setFocus(into, false);
      return;
    }
    if (!was || document.contains(was)) return;
    const again = sel && box && document.contains(box) ? $(sel, box) : null;
    setFocus(again || startFocus(), false);
  }

  // ---- sheets ----

  let sheetReturn = null, sheetClosed = null;
  function openSheet(html, opt = {}) {
    const sh = $('#sheet');
    if (!sheetOpen()) sheetReturn = focusEl;
    sh.className = opt.wide ? 'wide' : '';
    sh.innerHTML = html;
    sheetClosed = opt.onClose || null;
    document.body.classList.add('sheet');
    sh.scrollTop = 0;
    if (padOn) setFocus(sh.querySelector('[data-first]') || startFocus());
    return sh;
  }
  function closeSheet() {
    if (!sheetOpen()) return false;
    document.body.classList.remove('sheet');
    $('#sheet').innerHTML = '';
    const f = sheetClosed;
    sheetClosed = null;
    if (padOn) setFocus(sheetReturn && document.contains(sheetReturn) ? sheetReturn : startFocus());
    if (f) f();
    return true;
  }
  $('#scrim').addEventListener('click', closeSheet);

  /** A list of choices; options are {v, label, sub}. */
  function choose(title, options, current, onPick) {
    const sh = openSheet(`<h3><span>${esc(title)}</span></h3><div class="opts">${options
      .map((o, i) => `<button class="opt${o.v === current ? ' on' : ''}" data-i="${i}"${o.v === current || (i === 0 && !options.some((x) => x.v === current)) ? ' data-first' : ''}><span>${esc(o.label)}${o.sub ? `<small>${esc(o.sub)}</small>` : ''}</span>${o.v === current ? icon('check') : ''}</button>`)
      .join('')}</div>`);
    sh.querySelectorAll('.opt').forEach((b) =>
      b.addEventListener('click', () => {
        const o = options[+b.dataset.i];
        closeSheet();
        onPick(o.v);
      }),
    );
  }

  function confirmSheet(title, text, yes, onYes) {
    const sh = openSheet(`<h3><span>${esc(title)}</span></h3><p>${esc(text)}</p><div class="foot"><button class="btn" data-no data-first>Cancel</button><button class="btn primary" data-yes>${esc(yes)}</button></div>`);
    sh.querySelector('[data-no]').addEventListener('click', closeSheet);
    sh.querySelector('[data-yes]').addEventListener('click', () => {
      closeSheet();
      onYes();
    });
  }

  function textSheet(title, value, onSave, opt = {}) {
    const sh = openSheet(`<h3><span>${esc(title)}</span></h3>${opt.text ? `<p>${esc(opt.text)}</p>` : ''}<form><input class="text" value="${esc(value)}" placeholder="${esc(opt.placeholder || '')}" data-first enterkeyhint="done"></form><div class="foot"><button class="btn" data-no>Cancel</button><button class="btn primary" data-yes>${esc(opt.yes || 'Save')}</button></div>`);
    const input = sh.querySelector('input');
    const save = () => {
      const v = input.value;
      closeSheet();
      onSave(v);
    };
    sh.querySelector('form').addEventListener('submit', (e) => {
      e.preventDefault();
      save();
    });
    sh.querySelector('[data-no]').addEventListener('click', closeSheet);
    sh.querySelector('[data-yes]').addEventListener('click', save);
  }

  // ---- pages ----

  const TABS = ['home', 'browse', 'search', 'library', 'downloads', 'extensions', 'settings'];
  const stack = [];
  const cur = () => stack[stack.length - 1];

  function parse(route) {
    const s = String(route || '').replace(/^#?\/?/, '');
    const qi = s.indexOf('?');
    const path = qi < 0 ? s : s.slice(0, qi);
    const parts = path.split('/').map((x) => {
      try {
        return decodeURIComponent(x);
      } catch (e) {
        return x;
      }
    });
    const q = {};
    new URLSearchParams(qi < 0 ? '' : s.slice(qi + 1)).forEach((v, k) => (q[k] = v));
    return { name: PAGES[parts[0]] ? parts[0] : 'home', args: parts.slice(1), q, path };
  }

  /** Rail pages start a new stack; everything else goes on top, so B comes back. */
  function go(route, opt = {}) {
    const r = parse(route);
    const c = cur();
    if (c && !opt.replace && c.r.path === r.path && c.onQuery && Object.keys(r.q).length) {
      c.onQuery(r.q);
      return;
    }
    if (c) saveView(c);
    if (TABS.includes(r.name)) while (stack.length) kill(stack.pop());
    else if (opt.replace && c) kill(stack.pop());
    const el = document.createElement('div');
    el.className = 'page';
    $('#pages').appendChild(el);
    const p = { route, r, el, alive: true, scroll: 0, focus: null, stale: false, refresh: null, onQuery: null, observers: [], timers: [] };
    stack.push(p);
    if (stack.length > 12) kill(stack.splice(1, 1)[0]);
    activate(p, true);
    try {
      PAGES[r.name](p);
    } catch (e) {
      console.error(e && e.stack || e);
      el.innerHTML = header('Error') + errBox(e.message);
    }
    if (padOn) setFocus(startFocus(), false);
  }
  app.go = (route) => go(route);
  /** The back stack, oldest first (dev/hist.py prints it). */
  app.routes = () => stack.map((p) => p.route);

  function kill(p) {
    p.alive = false;
    p.observers.forEach((o) => o.disconnect());
    p.timers.forEach((t) => clearInterval(t));
    p.el.remove();
  }

  function saveView(p) {
    p.scroll = p.el.scrollTop;
    p.focus = focusEl && p.el.contains(focusEl) ? focusEl : null;
  }

  function activate(p, fresh) {
    for (const x of stack) x.el.classList.toggle('cur', x === p);
    const tab = stack[0] ? stack[0].r.name : 'home';
    $$('#rail .tab').forEach((t) => t.classList.toggle('on', t.dataset.tab === tab));
    if (fresh) return;
    p.el.scrollTop = p.scroll;
    if (p.stale && p.refresh) {
      p.stale = false;
      p.refresh('all');
    }
    if (padOn) setFocus(p.focus && document.contains(p.focus) ? p.focus : startFocus(), false);
  }

  app.back = () => {
    if (closeSheet()) return true;
    const a = document.activeElement;
    if (typing(a)) {
      a.blur();
      return true;
    }
    if (stack.length > 1) {
      kill(stack.pop());
      activate(cur(), false);
      return true;
    }
    if (cur() && cur().r.name !== 'home') {
      go('#/home');
      return true;
    }
    return false;
  };

  /** Tells the page on screen about a change; the others catch up when they're shown. */
  function bump(event) {
    for (const p of stack) {
      if (!p.refresh) continue;
      if (p === cur()) p.refresh(event);
      else p.stale = true;
    }
    updateBadge();
  }

  document.addEventListener('click', (e) => {
    const g = e.target.closest('[data-go]');
    if (g) {
      e.preventDefault();
      if (sheetOpen()) closeSheet();
      go(g.dataset.go);
      return;
    }
    if (e.target.closest('[data-act="back"]')) app.back();
  });
  document.addEventListener('submit', (e) => e.preventDefault());
  // A missing picture leaves the initials underneath showing.
  document.addEventListener('error', (e) => {
    if (e.target && e.target.tagName === 'IMG') e.target.style.visibility = 'hidden';
  }, true);

  // ---- shared pieces ----

  const header = (title, actions = '') =>
    `<header class="ph">${stack.length > 1 ? `<button class="btn icon ghost" data-act="back" aria-label="Back">${icon('back')}</button>` : ''}<h1>${esc(title)}</h1>${actions}</header>`;
  const section = (title, body, action = '') => `<div class="sec"><h3>${title}</h3>${action}</div>${body}`;
  const loadingBox = (text = 'Loading…') => `<div class="loading"><div class="spin"></div>${esc(text)}</div>`;
  const errBox = (text) => `<div class="errbox">${esc(text)}</div>`;
  const emptyBox = (title, text = '', action = '') => `<div class="empty"><b>${esc(title)}</b>${text ? `<span>${esc(text)}</span>` : ''}${action}</div>`;
  const imgTag = (src) => (src ? `<img loading="lazy" decoding="async" src="${esc(src)}" alt="">` : '');

  function card(o) {
    const prog = o.prog > 0 ? `<span class="bar"><i style="width:${Math.round(Math.min(1, o.prog) * 100)}%"></i></span>` : '';
    return `<button class="card" ${o.attrs || ''}><div class="poster"><span class="ini">${esc(initials(o.title))}</span>${imgTag(o.img)}${o.badge ? `<span class="badge ${o.badgeCls || ''}">${esc(o.badge)}</span>` : ''}${prog}</div><div class="t">${esc(o.title || 'Untitled')}</div>${o.sub ? `<div class="s">${esc(o.sub)}</div>` : ''}</button>`;
  }
  const row = (cards, name = '') => `<div class="row"${name ? ` data-row="${name}"` : ''}>${cards.join('')}</div>`;

  /** Rebuilds a block, keeping each named row scrolled where it was. */
  function keepRows(box, fn) {
    const at = {};
    $$('.row[data-row]', box).forEach((r) => (at[r.dataset.row] = r.scrollLeft));
    fn();
    $$('.row[data-row]', box).forEach((r) => {
      if (!at[r.dataset.row]) return;
      r.style.scrollBehavior = 'auto';
      r.scrollLeft = at[r.dataset.row];
      r.style.scrollBehavior = '';
    });
  }

  function animeCards(source, list, prefix, offset = 0, attrs = '') {
    return list.map((a, k) => {
      const i = offset + k;
      return card({ img: imgUrl(source, a.thumbnail_url), title: a.title, attrs: `data-anime="${i}" data-sig="${esc(prefix)}:${i}" ${attrs}` });
    });
  }

  function openAnime(source, anime, query = '') {
    if (!anime || !anime.url) return;
    animeMem.set(keyOf(source, anime.url), anime);
    go(`#/show/${enc(source)}/${enc(anime.url)}${query}`);
  }

  /** The episode a history entry continues with: where it stopped, or the next one once it's finished. */
  function nextOf(b) {
    const last = b.last;
    if (!last) return null;
    const done = last.d > 0 && last.t >= last.d * DONE;
    return { done, num: last.num, t: last.t, d: last.d, name: last.name };
  }

  function shelfSub(b) {
    const n = nextOf(b);
    if (!n) return b.sourceName || '';
    if (n.done) return n.num >= 0 ? `Ep ${numLabel(n.num + 1)} next` : 'Next episode';
    const ep = n.num >= 0 ? `Ep ${numLabel(n.num)}` : n.name || '';
    return ep + (n.d > 0 ? ` · ${mins(n.d - n.t)}m left` : '');
  }

  function shelfCard(b, i, kind) {
    const n = kind === 'recent' ? nextOf(b) : null;
    return card({
      img: coverOf(b), title: b.title, sub: kind === 'recent' ? shelfSub(b) : b.sourceName || 'AniList',
      prog: n && !n.done && n.d > 0 ? n.t / n.d : 0, attrs: `data-shelf="${kind}" data-i="${i}" data-sig="${kind}:${esc(b.key)}"`,
    });
  }

  function openShelf(b, resume) {
    if (!b) return;
    if (!b.source) {
      const n = nextOf(b);
      const ep = resume && n && n.num > 0 ? Math.floor(n.num) + (n.done ? 1 : 0) : 0;
      go(`#/anilist/${b.a}?title=${enc(b.title || '')}${ep ? '&ep=' + ep : ''}`);
      return;
    }
    go(`#/show/${enc(b.source)}/${enc(b.url)}${resume ? '?resume=1' : ''}`);
  }

  const aniList = (status) => (S.library && S.library.lists && S.library.lists[status]) || [];
  function aniCard(e, i, list) {
    const total = e.episodes || '?';
    return card({
      img: imgUrl('0', e.cover), title: e.title, sub: `${e.progress}/${total}${e.format ? ' · ' + e.format.replace('_', ' ') : ''}`,
      prog: e.episodes ? e.progress / e.episodes : 0, badge: e.newCount > 0 ? `${e.newCount} new` : '', badgeCls: 'new',
      attrs: `data-ani="${i}" data-list="${list}" data-sig="ani:${list}:${e.id}"`,
    });
  }
  function openAniEntry(e) {
    go(`#/anilist/${e.id}?title=${enc(e.romaji || e.title)}`);
  }

  const doneDownloads = () => (S.downloads.items || []).filter((r) => r.state === 'done');
  const activeDownloads = () => (S.downloads.items || []).filter((r) => ['queued', 'preparing', 'downloading'].includes(r.state));
  const dlImg = (r) => (r.cover ? `/cover/${r.cover}` : imgUrl(r.source, r.anime && r.anime.thumbnail_url));
  function dlCard(r) {
    const prog = r.d > 0 ? r.t / r.d : 0;
    const watched = r.d > 0 && r.t >= r.d * DONE;
    return card({ img: dlImg(r), title: r.show, sub: `Ep ${numLabel(r.ep)}${watched ? ' · watched' : r.quality ? ' · ' + r.quality : ''}`, prog: watched ? 0 : prog, attrs: `data-dl="${esc(r.id)}" data-sig="dl:${esc(r.id)}"` });
  }

  function updateBadge() {
    const n = activeDownloads().length;
    const em = $('#rail .tab[data-tab="downloads"] em');
    if (!em) return;
    em.textContent = n;
    em.classList.toggle('show', n > 0);
  }

  // Catalog pages are kept for a while, so going back and forth doesn't load them again.
  const catCache = new Map();
  async function catalog(source, kind, page, query, filters) {
    const k = [source, kind, page, query || '', filters ? JSON.stringify(filters) : ''].join('\u0001');
    const c = catCache.get(k);
    if (c && Date.now() - c.at < 10 * 60e3) return c.data;
    const args = { source, page };
    if (kind === 'search') {
      args.query = query || '';
      if (filters) args.filters = filters;
    }
    const data = await api(kind, args);
    catCache.set(k, { at: Date.now(), data });
    if (catCache.size > 120) catCache.delete(catCache.keys().next().value);
    return data;
  }

  /** Sources ordered by how much the user watches from them. */
  function sourcesByUse() {
    const use = {};
    for (const b of [...(S.shelf.recent || []), ...(S.shelf.saved || [])]) if (b.source) use[b.source] = (use[b.source] || 0) + 1;
    return [...S.sources].sort((a, b) => (use[b.id] || 0) - (use[a.id] || 0));
  }

  function onboarding() {
    return `<div class="empty"><b>Add a source to start watching</b><span>AniDeck finds and plays shows through Aniyomi extensions from the Yūzōnō repo. Pick one or two in Extensions; they install in a few seconds.</span><button class="btn primary" data-go="#/extensions" data-first>${icon('ext')} Extensions</button></div>`;
  }

  // ---- AniList matching (like the app's Api.score) ----

  function cleanTitle(t) {
    return String(t || '').replace(/[(\[](dub|sub|dubbed|subbed|uncensored|uncut|tv|eng(lish)? dub)[)\]]/gi, '').replace(/\s+(dub|dubbed)$/i, '').trim();
  }
  function norm(s) {
    return cleanTitle(s).normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase().replace(/&/g, ' and ')
      .replace(/\b(the|season|part|cour)\b/g, ' ').replace(/\b(\d+)(st|nd|rd|th)\b/g, '$1').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  }
  const numsOf = (s) => [...new Set((s.match(/\d+/g) || []).map((n) => String(+n)))].sort().join(',');
  function dice(x, y) {
    if (x.length < 2 || y.length < 2) return x === y ? 1 : 0;
    const pairs = new Map();
    for (let i = 0; i < x.length - 1; i++) pairs.set(x.substr(i, 2), (pairs.get(x.substr(i, 2)) || 0) + 1);
    let hits = 0;
    for (let i = 0; i < y.length - 1; i++) {
      const p = y.substr(i, 2), c = pairs.get(p);
      if (c > 0) {
        hits++;
        pairs.set(p, c - 1);
      }
    }
    return (2 * hits) / (x.length + y.length - 2);
  }
  function matchScore(title, names) {
    const want = norm(title);
    if (!want) return 0;
    let best = 0;
    for (const n of names) {
      const have = norm(n);
      if (!have) continue;
      let s = have === want ? 1 : dice(want, have);
      if (numsOf(have) !== numsOf(want)) s = Math.min(s, 0.8);
      best = Math.max(best, s);
    }
    // Between a sub and a dub of the same show, the one in the preferred audio.
    const dub = /\bdub(bed)?\b/i.test(title);
    if (dub !== ((S.settings.audioLang || 'ja') === 'en')) best -= 0.04;
    return best;
  }

  // ================================================================ Home

  function pageHome(p) {
    p.el.innerHTML = `${header('AniDeck', `<button class="btn icon" data-go="#/search" aria-label="Search">${icon('search')}</button>`)}<div class="mine"></div><div class="cats"></div>`;
    const mine = $('.mine', p.el), cats = $('.cats', p.el);

    // Downloads report progress twice a second; Home only changes when what it shows does.
    let mineSig = '';
    const mineState = () => JSON.stringify([S.net, (S.shelf.recent || []).map((b) => [b.key, b.cover, shelfSub(b)]), (S.shelf.saved || []).map((b) => b.key + b.cover),
      doneDownloads().map((r) => r.id + (r.cover || '') + Math.round((r.t || 0) / 30)), aniList('CURRENT').map((e) => [e.id, e.progress, e.newCount])]);
    const drawMine = () => {
      const sig = mineState();
      if (sig === mineSig) return;
      mineSig = sig;
      keepFocus(() => keepRows(mine, fillMine));
    };
    const fillMine = () => {
      let h = '';
      const rec = S.shelf.recent || [], saved = S.shelf.saved || [];
      if (!S.net) h += `<div class="banner warn"><span class="tx">No internet. Downloaded episodes still play.</span><button class="btn sm" data-go="#/downloads">Downloads</button></div>`;
      if (rec.length) h += section('Continue watching', row(rec.map((b, i) => shelfCard(b, i, 'recent')), 'recent'), `<button class="btn sm" data-go="#/library?tab=history">All</button>`);
      const dl = doneDownloads();
      if (dl.length) h += section('Downloaded', row(dl.slice(0, 20).map(dlCard), 'dl'), `<button class="btn sm" data-go="#/downloads">All</button>`);
      if (saved.length) h += section('Saved shows', row(saved.map((b, i) => shelfCard(b, i, 'saved')), 'saved'), `<button class="btn sm" data-go="#/library?tab=saved">All</button>`);
      const watching = aniList('CURRENT');
      if (watching.length) h += section('Watching on AniList', row(watching.slice(0, 30).map((e, i) => aniCard(e, i, 'CURRENT')), 'ani'), `<button class="btn sm" data-go="#/library?tab=anilist">All</button>`);
      mine.innerHTML = h;
    };

    let catsSig = null;
    const drawCats = () => {
      const sig = S.sourcesLoaded + ':' + S.sources.map((x) => x.id).join(',');
      if (sig === catsSig) return;
      catsSig = sig;
      p.observers.forEach((o) => o.disconnect());
      p.observers = [];
      if (!S.sourcesLoaded) {
        cats.innerHTML = loadingBox('Loading your extensions…');
        return;
      }
      if (!S.sources.length) {
        cats.innerHTML = onboarding();
        return;
      }
      cats.innerHTML = sourcesByUse().slice(0, 8).map((s) => {
        const kind = s.latest ? 'latest' : 'popular';
        return `<div class="lazy" data-src="${esc(s.id)}" data-kind="${kind}">${section(`${kind === 'latest' ? 'Latest on' : 'Popular on'} ${esc(s.name)}`, `<div class="row">${loadingBox()}</div>`, `<button class="btn sm" data-go="#/source/${enc(s.id)}?tab=${kind}">More</button>`)}</div>`;
      }).join('');
      const io = new IntersectionObserver((list) => {
        for (const en of list) {
          if (!en.isIntersecting) continue;
          io.unobserve(en.target);
          fillRow(en.target);
        }
      }, { root: p.el, rootMargin: '250px' });
      $$('.lazy', cats).forEach((el) => io.observe(el));
      p.observers.push(io);
    };

    async function fillRow(el) {
      const s = srcById(el.dataset.src), rowEl = $('.row', el);
      if (!s) return;
      if (S.net === false) {
        el._failed = true;
        rowEl.innerHTML = '<div class="note">Loads when you’re back online.</div>';
        return;
      }
      el._failed = false;
      rowEl.innerHTML = loadingBox();
      try {
        const r = await catalog(s.id, el.dataset.kind, 1);
        if (!p.alive) return;
        el._list = (r.animes || []).slice(0, 24);
        keepFocus(() => {
          rowEl.innerHTML = el._list.length ? animeCards(s.id, el._list, 'home:' + s.id).join('') : '<div class="note">Nothing here right now.</div>';
        });
      } catch (e) {
        if (!p.alive) return;
        el._failed = true;
        rowEl.innerHTML = `<div class="note">${esc(e.message)}</div>`;
      }
    }

    p.el.addEventListener('click', (e) => {
      const t = e.target.closest('[data-shelf], [data-dl], [data-ani], [data-anime]');
      if (!t) return;
      if (t.dataset.shelf) openShelf((S.shelf[t.dataset.shelf] || [])[+t.dataset.i], t.dataset.shelf === 'recent');
      else if (t.dataset.dl) B.playDownload(t.dataset.dl);
      else if (t.dataset.ani) openAniEntry(aniList(t.dataset.list)[+t.dataset.ani]);
      else {
        const lz = t.closest('.lazy');
        if (lz && lz._list) openAnime(lz.dataset.src, lz._list[+t.dataset.anime]);
      }
    });

    p.refresh = (ev) => {
      if (ev === 'sources' || ev === 'all') drawCats();
      if (ev !== 'sources') drawMine();
      if ((ev === 'net' || ev === 'all') && S.net) $$('.lazy', cats).forEach((el) => el._failed && fillRow(el));
    };
    drawMine();
    drawCats();
  }

  // ================================================================ Browse: the installed sources

  function pageBrowse(p) {
    const draw = () => keepFocus(() => {
      let h = header('Browse', `<button class="btn sm" data-go="#/extensions">${icon('ext')} Extensions</button>`);
      if (!S.sourcesLoaded) h += loadingBox('Loading your extensions…');
      else if (!S.sources.length) h += onboarding();
      else {
        const byLang = {};
        for (const s of S.sources) (byLang[s.lang] = byLang[s.lang] || []).push(s);
        for (const lang of Object.keys(byLang).sort((a, b) => (a === 'all' ? -1 : b === 'all' ? 1 : a.localeCompare(b)))) {
          h += section(esc(langName(lang)), `<div class="tiles">${byLang[lang].map((s) => `<button class="tile" data-go="#/source/${enc(s.id)}" data-sig="src:${esc(s.id)}"><span class="ic">${esc(initials(s.name))}</span><span class="tx"><b>${esc(s.name)}</b><small>${esc(s.ext && s.ext !== s.name ? s.ext : (s.baseUrl || '').replace(/^https?:\/\//, ''))}${s.rating ? ' · 18+' : ''}</small></span></button>`).join('')}</div>`);
        }
      }
      p.el.innerHTML = h;
    });
    p.refresh = (ev) => {
      if (ev === 'sources' || ev === 'all') draw();
    };
    draw();
  }

  const LANGS = { all: 'Multi', en: 'English', es: 'Spanish', 'pt-BR': 'Portuguese (Brazil)', pt: 'Portuguese', ar: 'Arabic', fr: 'French', de: 'German', id: 'Indonesian', it: 'Italian', ru: 'Russian', zh: 'Chinese', 'zh-hant': 'Chinese (Traditional)', tr: 'Turkish', pl: 'Polish', sr: 'Serbian', uk: 'Ukrainian', bg: 'Bulgarian', hi: 'Hindi', ko: 'Korean', ja: 'Japanese', vi: 'Vietnamese', th: 'Thai' };
  const langName = (l) => LANGS[l] || l;

  // ================================================================ A source's catalog

  /** Pages opened while extensions are still loading draw again once they're in. */
  function laterSources(p, page) {
    if (S.sourcesLoaded) return false;
    p.el.innerHTML = header('Loading') + loadingBox();
    waitFor(() => S.sourcesLoaded, 90000).then(() => p.alive && page(p));
    return true;
  }

  function pageSource(p) {
    const id = p.r.args[0], s = srcById(id);
    if (!s && laterSources(p, pageSource)) return;
    if (!s) {
      p.el.innerHTML = header('Source') + emptyBox("This source isn't installed", '', `<button class="btn primary" data-go="#/extensions" data-first>Extensions</button>`);
      return;
    }
    let tab = p.r.q.tab || (p.r.q.q ? 'search' : s.latest ? 'latest' : 'popular');
    let query = p.r.q.q || '', filters = null, page = 0, next = true, loading = false, list = [], run = 0;

    p.el.innerHTML = `${header(s.name, `${s.settings ? `<button class="btn icon" data-go="#/prefs/${enc(s.id)}" aria-label="Source settings">${icon('gear')}</button>` : ''}`)}
      <div class="seg"><button class="btn sm" data-tab="popular">Popular</button>${s.latest ? '<button class="btn sm" data-tab="latest">Latest</button>' : ''}
      <form class="q" style="display:flex;gap:6px;flex:1;min-width:220px"><input class="text" type="search" enterkeyhint="search" placeholder="Search ${esc(s.name)}" value="${esc(query)}"></form>
      <button class="btn sm" data-act="filters">${icon('filter')} Filters</button></div>
      <div class="grid"></div><div class="more"></div>`;
    const grid = $('.grid', p.el), more = $('.more', p.el), input = $('input', p.el);

    const tabs = () => $$('[data-tab]', p.el).forEach((b) => b.classList.toggle('on', b.dataset.tab === tab));
    function reset() {
      run++;
      page = 0;
      next = true;
      list = [];
      grid.innerHTML = '';
      tabs();
      p.el.scrollTop = 0;
      load();
    }
    async function load() {
      if (loading || !next) return;
      loading = true;
      const mine = run;
      more.innerHTML = '<div class="spin"></div>';
      try {
        const r = await catalog(s.id, tab, page + 1, query, filters);
        if (!p.alive || mine !== run) return;
        page++;
        // Some sources repeat the last page instead of ending the list.
        const seen = new Set(list.map((a) => a.url));
        const fresh = (r.animes || []).filter((a) => a && !seen.has(a.url) && seen.add(a.url));
        next = !!r.next && fresh.length > 0;
        const start = list.length;
        list.push(...fresh);
        grid.insertAdjacentHTML('beforeend', animeCards(s.id, fresh, 'grid', start).join(''));
        more.innerHTML = list.length ? '' : emptyBox(tab === 'search' ? 'No results' : 'Nothing here', tab === 'search' ? 'Try other words or fewer filters.' : '');
        if (padOn && (!focusEl || !document.contains(focusEl) || focusEl === input)) setFocus(startFocus(), false);
      } catch (e) {
        if (!p.alive || mine !== run) return;
        next = false;
        more.innerHTML = errBox(e.message) + `<button class="btn sm" data-act="more" style="margin-top:8px">${icon('refresh')} Try again</button>`;
      } finally {
        if (mine === run) loading = false;
      }
      // A short first page doesn't fill the screen, so the observer wouldn't fire.
      if (p.alive && mine === run && next && p.el.scrollHeight <= p.el.clientHeight + 300) load();
    }
    const io = new IntersectionObserver((en) => {
      if (en.some((x) => x.isIntersecting)) load();
    }, { root: p.el, rootMargin: '500px' });
    io.observe(more);
    p.observers.push(io);

    $('form.q', p.el).addEventListener('submit', () => {
      query = input.value.trim();
      input.blur();
      tab = 'search';
      reset();
    });
    p.el.addEventListener('click', (e) => {
      const t = e.target.closest('[data-tab], [data-anime], [data-act="filters"], [data-act="more"]');
      if (!t) return;
      if (t.dataset.tab) {
        tab = t.dataset.tab;
        filters = null;
        reset();
      } else if (t.dataset.anime) openAnime(s.id, list[+t.dataset.anime]);
      else if (t.dataset.act === 'more') {
        next = true;
        load();
      } else openFilters();
    });

    let filterDefs = null;
    async function openFilters() {
      if (!filterDefs) {
        openSheet(`<h3><span>Filters</span></h3>${loadingBox()}`);
        try {
          filterDefs = await api('filters', { source: s.id });
        } catch (e) {
          openSheet(`<h3><span>Filters</span></h3>${errBox(e.message)}`);
          return;
        }
      }
      const work = JSON.parse(JSON.stringify(filters || filterDefs));
      if (!work.length) {
        openSheet(`<h3><span>Filters</span></h3><p>${esc(s.name)} has no filters.</p>`);
        return;
      }
      filterSheet(work, (applied) => {
        filters = applied;
        tab = 'search';
        reset();
      }, () => {
        filters = null;
        tab = 'search';
        reset();
      });
    }

    tabs();
    load();
  }

  /** Filters from the extension as controls; select lists open in place. */
  function filterSheet(work, onApply, onReset) {
    const sh = openSheet(`<h3><span>Filters</span></h3><div class="fl"></div><div class="foot"><button class="btn" data-reset>Reset</button><button class="btn primary" data-apply>Apply</button></div>`, { wide: true });
    const box = $('.fl', sh);
    const open = new Set();
    const label = (f) => esc(f.name || '');
    function render() {
      const sig = focusEl && focusEl.dataset.sig;
      const draw = (items, path) => items.map((f, i) => {
        const id = path + i;
        switch (f.type) {
          case 'header': return `<div class="fsec">${label(f)}</div>`;
          case 'separator': return '<hr>';
          case 'text': return `<div class="field"><span class="lbl">${label(f)}</span><input class="text" data-text="${id}" data-sig="${id}" value="${esc(f.state)}" style="max-width:55%"></div>`;
          case 'check': return `<button class="field${f.state ? ' on' : ''}" data-check="${id}" data-sig="${id}"><span class="lbl">${label(f)}</span><span class="toggle"></span></button>`;
          case 'tri': return `<button class="field" data-tri="${id}" data-sig="${id}"><span class="lbl">${label(f)}</span><span class="tri ${f.state === 1 ? 'inc' : f.state === 2 ? 'exc' : ''}">${f.state === 1 ? 'Include' : f.state === 2 ? 'Exclude' : 'Any'}</span></button>`;
          case 'select': {
            const opts = open.has(id) ? `<div class="chips" style="margin:-2px 0 8px">${f.values.map((v, k) => `<button class="fchip${k === f.state ? ' on' : ''}" data-pick="${id}" data-k="${k}" data-sig="${id}:${k}">${esc(v)}</button>`).join('')}</div>` : '';
            return `<button class="field" data-sel="${id}" data-sig="${id}"><span class="lbl">${label(f)}</span><span class="val">${esc(f.values[f.state] ?? '')}</span></button>${opts}`;
          }
          case 'sort': {
            const st = f.state || null;
            return `<div class="fgroup"><div class="fsec">${label(f)}</div><div class="chips">${f.values.map((v, k) => `<button class="fchip${st && st.index === k ? ' on' : ''}" data-sort="${id}" data-k="${k}" data-sig="${id}:${k}">${esc(v)}${st && st.index === k ? (st.asc ? ' ↑' : ' ↓') : ''}</button>`).join('')}</div></div>`;
          }
          case 'group': {
            const simple = f.items.every((x) => x.type === 'check' || x.type === 'tri');
            if (!simple) return `<div class="fsec">${label(f)}</div>${draw(f.items, id + '.')}`;
            return `<div class="fgroup"><div class="fsec">${label(f)}</div><div class="chips">${f.items.map((x, k) => {
              const cls = x.type === 'check' ? (x.state ? 'on' : '') : x.state === 1 ? 'inc' : x.state === 2 ? 'exc' : '';
              return `<button class="fchip ${cls}" data-gi="${id}.${k}" data-sig="${id}.${k}">${esc(x.name)}</button>`;
            }).join('')}</div></div>`;
          }
          default: return '';
        }
      }).join('');
      box.innerHTML = draw(work, '');
      if (padOn && sig) {
        const again = box.querySelector(`[data-sig="${CSS.escape(sig)}"]`);
        if (again) setFocus(again, false);
      }
    }
    const at = (id) => id.split('.').reduce((o, k, i) => (i === 0 ? work[+k] : o.items[+k]), null);
    box.addEventListener('click', (e) => {
      const t = e.target.closest('button');
      if (!t) return;
      const d = t.dataset;
      if (d.check) at(d.check).state = !at(d.check).state;
      else if (d.tri) at(d.tri).state = (at(d.tri).state + 1) % 3;
      else if (d.sel) open.has(d.sel) ? open.delete(d.sel) : open.add(d.sel);
      else if (d.pick) {
        at(d.pick).state = +d.k;
        open.delete(d.pick);
        render();
        const f = box.querySelector(`[data-sel="${CSS.escape(d.pick)}"]`);
        if (padOn && f) setFocus(f);
        return;
      } else if (d.sort) {
        const f = at(d.sort);
        f.state = f.state && f.state.index === +d.k ? { index: +d.k, asc: !f.state.asc } : { index: +d.k, asc: false };
      } else if (d.gi) {
        const x = at(d.gi);
        x.state = x.type === 'check' ? !x.state : (x.state + 1) % 3;
      } else return;
      render();
    });
    box.addEventListener('input', (e) => {
      const id = e.target.dataset.text;
      if (id) at(id).state = e.target.value;
    });
    sh.querySelector('[data-apply]').addEventListener('click', () => {
      closeSheet();
      onApply(work);
    });
    sh.querySelector('[data-reset]').addEventListener('click', () => {
      closeSheet();
      onReset();
    });
    render();
    if (padOn) setFocus(startFocus());
  }

  // ================================================================ Search every source

  function pageSearch(p) {
    const q = (p.r.q.q || '').trim();
    const recent = JSON.parse(localStorage.getItem('searches') || '[]');
    p.el.innerHTML = `${header('Search')}<form class="q" style="display:flex;gap:8px;margin-bottom:10px"><input class="text" type="search" enterkeyhint="search" placeholder="Search all your sources" value="${esc(q)}" ${q ? '' : 'data-first'}><button class="btn primary">${icon('search')} Search</button></form><div class="res"></div>`;
    const res = $('.res', p.el), input = $('input', p.el);
    $('form', p.el).addEventListener('submit', () => {
      const v = input.value.trim();
      input.blur();
      if (v) go('#/search?q=' + enc(v));
    });
    const lists = {};
    p.el.addEventListener('click', (e) => {
      const t = e.target.closest('[data-anime], [data-recent]');
      if (!t) return;
      if (t.dataset.recent !== undefined) go('#/search?q=' + enc(t.dataset.recent));
      else openAnime(t.dataset.src, lists[t.dataset.src][+t.dataset.anime]);
    });
    if (!q) {
      res.innerHTML = !S.sources.length && S.sourcesLoaded ? onboarding()
        : recent.length ? section('Recent searches', `<div class="chips">${recent.map((r) => `<button class="btn sm" data-recent="${esc(r)}">${esc(r)}</button>`).join('')}</div>`) : '';
      return;
    }
    localStorage.setItem('searches', JSON.stringify([q, ...recent.filter((r) => r !== q)].slice(0, 12)));
    const run = () => {
      const sources = sourcesByUse();
      if (!sources.length) {
        res.innerHTML = S.sourcesLoaded ? onboarding() : loadingBox('Loading your extensions…');
        return;
      }
      res.innerHTML = sources.map((s) => `<div data-block="${esc(s.id)}">${section(esc(s.name), `<div class="row">${loadingBox()}</div>`, `<button class="btn sm" data-go="#/source/${enc(s.id)}?q=${enc(q)}">More</button>`)}</div>`).join('');
      for (const s of sources) {
        catalog(s.id, 'search', 1, q).then((r) => {
          if (!p.alive) return;
          lists[s.id] = r.animes || [];
          const block = res.querySelector(`[data-block="${CSS.escape(s.id)}"]`);
          if (!block) return;
          keepFocus(() => {
            if (!lists[s.id].length) block.remove();
            else $('.row', block).innerHTML = animeCards(s.id, lists[s.id], 'srch:' + s.id, 0, `data-src="${esc(s.id)}"`).join('');
          });
          if (!res.querySelector('[data-block]')) res.innerHTML = emptyBox(`Nothing found for “${q}”`, 'Try another title, or the Japanese name.');
        }).catch((e) => {
          const block = res.querySelector(`[data-block="${CSS.escape(s.id)}"]`);
          if (block && p.alive) $('.row', block).innerHTML = `<div class="note">${esc(e.message)}</div>`;
        });
      }
    };
    p.refresh = (ev) => {
      if (ev === 'sources') run();
    };
    run();
  }

  // ================================================================ A show

  const epNum = (e, i) => (e.episode_number >= 0 ? numLabel(e.episode_number) : String(i + 1));
  const epName = (e, i) => (e.name && e.name.trim() ? e.name.trim() : `Episode ${epNum(e, i)}`);

  /** Oldest first: extensions list newest first, and the player plays index + 1 next. */
  function sortEpisodes(list) {
    const xs = list.map((e, i) => ({ e, i }));
    if (xs.every(({ e }) => typeof e.episode_number === 'number' && e.episode_number >= 0)) xs.sort((a, b) => a.e.episode_number - b.e.episode_number || b.i - a.i);
    else xs.reverse();
    return xs.map((x) => x.e);
  }

  const STATUS = ['', 'Ongoing', 'Completed', 'Licensed', 'Publishing finished', 'Cancelled', 'On hiatus'];

  async function pageShow(p) {
    const source = p.r.args[0], url = p.r.args[1] || '';
    const key = keyOf(source, url);
    let s = srcById(source);
    let anime = Object.assign({ url, title: p.r.q.title || '' }, animeMem.get(key) || {});
    let show = null, coverName = '', dlSig = '', eps = null, epsErr = '', infoErr = '', seasons = null, matchA = 0, range = -1, newest = localStorage.getItem('newestFirst') === '1';
    // eps holds only the downloaded episodes because the source couldn't be reached.
    let fromDls = false;
    // ?after=<episode url>&dir=1 plays the episode after (or before) that one, e.g. past the last download.
    const autoOf = (q) => (q.resume ? { resume: true } : q.ep ? { ep: +q.ep } : q.after ? { after: q.after, dir: +q.dir < 0 ? -1 : 1 } : null);
    let auto = autoOf(p.r.q);

    p.el.innerHTML = `${header(anime.title || 'Show', `${s && s.settings ? `<button class="btn icon" data-go="#/prefs/${enc(source)}" aria-label="Source settings">${icon('gear')}</button>` : ''}`)}<div class="backdrop"></div><div class="show"><div class="heroBox"></div><div class="epBox">${loadingBox('Loading episodes…')}</div></div>`;
    const heroBox = $('.heroBox', p.el), epBox = $('.epBox', p.el);

    const linked = () => (show && show.a) || matchA || 0;
    const dls = () => {
      const out = {};
      for (const r of S.downloads.items || []) {
        if (r.key !== key || !r.episode) continue;
        out[r.episode.url] = { id: r.id, state: r.state, pct: r.size > 0 ? Math.min(100, Math.round((r.got * 100) / r.size)) : 0, error: r.error };
      }
      return out;
    };
    const dlState = () => Object.entries(dls()).map(([u, x]) => u + ':' + x.state).sort().join('|');
    const savedEps = () => sortEpisodes((S.downloads.items || []).filter((r) => r.key === key && r.state === 'done' && r.episode).map((r) => r.episode));
    const progressOf = (e) => (show && show.eps && show.eps[e.url]) || null;
    const watched = (e) => {
      const pr = progressOf(e);
      return !!(pr && (pr.w || (pr.d > 0 && pr.t >= pr.d * DONE)));
    };
    const aniEntry = () => {
      const a = linked();
      if (!a || !S.library || !S.library.lists) return null;
      for (const k of Object.keys(S.library.lists)) {
        const e = (S.library.lists[k] || []).find((x) => x.id === a);
        if (e) return e;
      }
      return null;
    };

    /** The episode Play continues with. */
    function target() {
      if (!eps || !eps.length) return null;
      const last = show && show.last;
      if (last) {
        let i = last.url ? eps.findIndex((e) => e.url === last.url) : -1;
        if (i < 0 && last.num >= 0) i = eps.findIndex((e) => Math.abs(e.episode_number - last.num) < 0.01);
        if (i >= 0) {
          const done = last.d > 0 && last.t >= last.d * DONE;
          if (!done) return { i, label: `Resume episode ${epNum(eps[i], i)}`, sub: last.t > 5 && last.url ? clock(last.t) : '' };
          if (i + 1 < eps.length) return { i: i + 1, label: `Play episode ${epNum(eps[i + 1], i + 1)}` };
          return { i, label: `Watch episode ${epNum(eps[i], i)} again` };
        }
      }
      const e = aniEntry();
      if (e && e.progress > 0) {
        const i = eps.findIndex((x) => Math.abs(x.episode_number - (e.progress + 1)) < 0.01);
        if (i >= 0) return { i, label: `Play episode ${epNum(eps[i], i)}` };
      }
      const first = eps.findIndex((x) => !watched(x));
      const i = first < 0 ? 0 : first;
      return { i, label: `Play episode ${epNum(eps[i], i)}` };
    }

    function play(i, start) {
      if (!eps || !eps[i]) return;
      const item = { source, sourceName: s ? s.name : '', anime, episodes: eps, index: i, a: linked() };
      if (start !== undefined) item.start = start;
      B.play(JSON.stringify(item));
    }

    function drawHero() {
      const t = target();
      const saved = show && show.saved;
      const genres = (anime.genre || '').split(/\s*,\s*/).filter(Boolean).slice(0, 6);
      const meta = [s ? s.name : '', STATUS[anime.status] || '', anime.author || anime.artist || ''].filter(Boolean).join(' · ');
      const img = coverName ? '/cover/' + coverName : imgUrl(source, anime.thumbnail_url);
      $('.backdrop', p.el).style.backgroundImage = img ? `url("${img}")` : '';
      $('.ph h1', p.el).textContent = anime.title || 'Show';
      const a = linked(), entry = aniEntry();
      const desc = (anime.description || '').trim().replace(/\n\s*\n+/g, '\n');
      const hadPlay = !!$('[data-act="play"]', heroBox);
      keepFocus(() => {
        heroBox.innerHTML = `<div class="hero"><div class="poster"><span class="ini">${esc(initials(anime.title))}</span>${imgTag(img)}</div><div class="info">
          <h2>${esc(anime.title || '')}</h2><div class="meta">${esc(meta)}</div>
          ${genres.length ? `<div class="chips">${genres.map((g) => `<span class="chip">${esc(g)}</span>`).join('')}</div>` : ''}
          ${desc ? `<div class="desc" data-f data-act="desc" data-sig="desc">${esc(desc)}</div>` : ''}
          ${infoErr && !desc && !epsErr ? `<div class="note">${esc(infoErr)}</div>` : ''}
          <div class="actions">
            ${t ? `<button class="btn primary" data-act="play" data-first data-sig="play">${icon('play')} ${esc(t.label)}${t.sub ? ` · ${esc(t.sub)}` : ''}</button>` : ''}
            <button class="btn${saved ? ' on' : ''}" data-act="save" data-sig="save">${icon(saved ? 'starOn' : 'star')} ${saved ? 'Saved' : 'Save'}</button>
            ${eps && eps.length && !fromDls ? `<button class="btn" data-act="dlmany" data-sig="dlmany">${icon('download')} Download</button>` : ''}
            <button class="btn${a ? ' on' : ''}" data-act="anilist" data-sig="anilist">${icon('link')} ${a ? (entry ? `AniList ${entry.progress}/${entry.episodes || '?'}` : 'AniList') : 'Link AniList'}</button>
          </div></div></div>`;
      });
      // The play button shows up once the episodes are in; the highlight moves to it unless it was moved.
      const playBtn = $('[data-act="play"]', heroBox);
      if (padOn && playBtn && !hadPlay && focusEl && focusEl.dataset.sig === 'save') setFocus(playBtn, false);
    }

    function drawEps() {
      if (seasons) {
        epBox.innerHTML = section('Seasons', `<div class="grid">${animeCards(source, seasons, 'season').join('')}</div>`);
        return;
      }
      if (epsErr && !eps) {
        const offline = Object.values(dls()).some((d) => d.state === 'done');
        epBox.innerHTML = section('Episodes', errBox(epsErr) + (offline ? `<div class="note">Downloaded episodes of this show are in <button class="btn sm" data-go="#/downloads">Downloads</button></div>` : '') + `<div style="margin-top:8px"><button class="btn sm" data-act="retry">${icon('refresh')} Try again</button></div>`);
        return;
      }
      if (!eps) return;
      if (!eps.length) {
        epBox.innerHTML = section('Episodes', emptyBox('No episodes yet', `${s ? s.name : 'The source'} doesn't list any episodes for this show.`));
        return;
      }
      const d = dls();
      dlSig = dlState();
      const order = eps.map((e, i) => i);
      if (newest) order.reverse();
      const CHUNK = 100;
      let shown = order, ranges = '';
      if (order.length > 150) {
        const n = Math.ceil(order.length / CHUNK);
        if (range < 0) {
          const t = target();
          const pos = t ? order.indexOf(t.i) : 0;
          range = Math.max(0, Math.floor(pos / CHUNK));
        }
        range = Math.min(range, n - 1);
        shown = order.slice(range * CHUNK, range * CHUNK + CHUNK);
        ranges = `<div class="ranges">${Array.from({ length: n }, (_, k) => {
          const a = order[k * CHUNK], b = order[Math.min(order.length, (k + 1) * CHUNK) - 1];
          return `<button class="btn sm${k === range ? ' on' : ''}" data-range="${k}" data-sig="range:${k}">${esc(epNum(eps[a], a))}–${esc(epNum(eps[b], b))}</button>`;
        }).join('')}</div>`;
      }
      const rows = shown.map((i) => {
        const e = eps[i], pr = progressOf(e), w = watched(e), x = d[e.url];
        const pct = pr && !w && pr.d > 0 && pr.t > 5 ? Math.min(1, pr.t / pr.d) : 0;
        const date = e.date_upload > 0 ? new Date(e.date_upload).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' }) : '';
        const meta = [date, e.scanlator, e.fillermark ? 'Filler' : '', pct ? `${clock(pr.t)} of ${clock(pr.d)}` : ''].filter(Boolean).join(' · ');
        const side = !x ? `<button class="side" data-act="dl1" data-i="${i}" data-sig="dl:${i}" aria-label="Download">${icon('download')}</button>`
          : x.state === 'done' ? `<button class="side ok" data-act="dlx" data-i="${i}" data-sig="dl:${i}" aria-label="Downloaded">${icon('check')}</button>`
          : x.state === 'failed' ? `<button class="side bad" data-act="dlx" data-i="${i}" data-sig="dl:${i}" aria-label="Download failed">!</button>`
          : `<button class="side busy" data-act="dlx" data-i="${i}" data-sig="dl:${i}">${x.state === 'downloading' && x.pct ? x.pct + '%' : '···'}</button>`;
        return `<div class="ep${w ? ' watched' : ''}"><button class="main" data-act="ep" data-i="${i}" data-sig="ep:${i}"><span class="num">${esc(epNum(e, i))}</span><span class="tx"><span class="name">${esc(epName(e, i))}</span>${meta ? `<span class="meta">${esc(meta)}</span>` : ''}</span>${w ? icon('check', 'w') : ''}${pct ? `<i class="prog" style="width:${pct * 100}%"></i>` : ''}</button>${side}<button class="side" data-act="epmenu" data-i="${i}" data-sig="more:${i}" aria-label="More">${icon('more')}</button></div>`;
      }).join('');
      const note = fromDls ? `<div class="note" style="margin-bottom:8px">${esc(epsErr.replace(/\.$/, ''))}. These are the episodes you downloaded. <button class="btn sm" data-act="retry" data-sig="retry">${icon('refresh')} Try again</button></div>` : '';
      keepFocus(() => {
        epBox.innerHTML = section(`${fromDls ? 'Downloaded' : 'Episodes'} <small>${eps.length}</small>`, note + ranges + `<div class="eps">${rows}</div>`, `<button class="btn sm" data-act="order" data-sig="order">${icon('sort')} ${newest ? 'Newest first' : 'Oldest first'}</button>`);
      });
    }

    function runAuto() {
      if (!auto || !eps) return;
      const a = auto;
      auto = null;
      if (!eps.length) return toast('This show has no episodes yet');
      if (a.resume) {
        const t = target();
        if (t) play(t.i);
      } else if (a.ep) {
        const i = eps.findIndex((e) => Math.abs(e.episode_number - a.ep) < 0.01);
        if (i >= 0) play(i);
        else toast(`Episode ${a.ep} isn't on ${s ? s.name : 'this source'} yet`);
      } else if (a.after) {
        const k = eps.findIndex((e) => e.url === a.after);
        const i = k < 0 ? -1 : k + a.dir;
        if (i >= 0 && i < eps.length) play(i);
        else toast(k < 0 ? `That episode isn't on ${s ? s.name : 'this source'} anymore` : a.dir > 0 ? 'That was the last episode' : 'That’s the first episode');
      }
    }
    p.onQuery = (q) => {
      auto = autoOf(q);
      runAuto();
    };

    async function remember() {
      await api('remember', { source, sourceName: s ? s.name : '', anime });
    }
    async function reloadShow() {
      try {
        const r = await api('show', { key });
        if (!p.alive) return;
        show = r.show;
        coverName = r.cover || '';
      } catch (e) {}
    }

    function loadEpisodes() {
      epsErr = '';
      epBox.innerHTML = loadingBox('Loading episodes…');
      api('episodes', { source, anime }).then((list) => {
        if (!p.alive) return;
        eps = sortEpisodes(list || []);
        fromDls = false;
        drawHero();
        drawEps();
        runAuto();
        if (p === cur() && padOn && (!focusEl || !p.el.contains(focusEl))) setFocus(startFocus(), false);
      }).catch((e) => {
        if (!p.alive) return;
        epsErr = e.message;
        const saved = !eps || fromDls ? savedEps() : [];
        if (saved.length) {
          eps = saved;
          fromDls = true;
        }
        drawHero();
        drawEps();
        if (!saved.length) return;
        runAuto();
        if (p === cur() && padOn && (!focusEl || !p.el.contains(focusEl))) setFocus(startFocus(), false);
      });
    }

    async function autoMatch() {
      if (linked() || !S.library || !S.library.account || !anime.title) return;
      try {
        const r = await api('anilistMatch', { title: anime.title, key });
        if (!p.alive || !r || !r.sure) return;
        matchA = r.a;
        drawHero();
      } catch (e) {}
    }

    // Episodes count against the source's rate limit too, so they load once the details are in.
    async function start() {
      if (!s && !S.sourcesLoaded) {
        await waitFor(() => S.sourcesLoaded, 90000);
        if (!p.alive) return;
        s = srcById(source);
      }
      if (!s) {
        await reloadShow();
        if (show && show.anime) anime = Object.assign({}, show.anime, anime.title ? anime : {});
        const offline = Object.values(dls()).some((d) => d.state === 'done');
        heroBox.innerHTML = '';
        epBox.innerHTML = emptyBox("This show's source isn't installed", show && show.sourceName ? `It came from ${show.sourceName}.` : '',
          `<span style="display:flex;gap:8px"><button class="btn primary" data-go="#/extensions" data-first>Extensions</button>${offline ? '<button class="btn" data-go="#/downloads">Downloads</button>' : ''}</span>`);
        return;
      }
      await reloadShow();
      if (!p.alive) return;
      if (show && show.anime) anime = Object.assign({}, show.anime, animeMem.get(key) || {}, { url });
      drawHero();
      try {
        const d = await api('details', { source, anime });
        if (!p.alive) return;
        anime = Object.assign({}, anime, d, { url });
        animeMem.set(key, anime);
        if (d.fetch_type === 'Seasons') {
          seasons = await api('seasons', { source, anime });
          if (!p.alive) return;
          drawHero();
          drawEps();
          return;
        }
      } catch (e) {
        if (!p.alive) return;
        infoErr = e.message;
      }
      drawHero();
      loadEpisodes();
      autoMatch();
    }

    p.el.addEventListener('click', async (e) => {
      const t = e.target.closest('[data-act], [data-range], [data-anime]');
      if (!t || t.dataset.act === 'back') return;
      if (t.dataset.range) {
        range = +t.dataset.range;
        drawEps();
        return;
      }
      if (t.dataset.anime) {
        openAnime(source, seasons[+t.dataset.anime]);
        return;
      }
      const i = +t.dataset.i;
      switch (t.dataset.act) {
        case 'play': {
          const tg = target();
          if (tg) play(tg.i);
          break;
        }
        case 'ep': play(i); break;
        case 'desc': t.classList.toggle('open'); break;
        case 'order':
          newest = !newest;
          localStorage.setItem('newestFirst', newest ? '1' : '0');
          range = -1;
          drawEps();
          break;
        case 'retry': loadEpisodes(); break;
        case 'save':
          try {
            const on = !(show && show.saved);
            await remember();
            await api('save', { key, on });
            await reloadShow();
            drawHero();
            toast(on ? 'Saved. It stays on the Thor, with its cover.' : 'Removed from saved shows');
          } catch (err) {
            toast(err.message);
          }
          break;
        case 'dl1': download([eps[i]]); break;
        case 'dlx': downloadMenu(i); break;
        case 'dlmany': downloadSheet(); break;
        case 'epmenu': episodeMenu(i); break;
        case 'anilist': aniListSheet(); break;
      }
    });

    async function download(list) {
      list = list.filter(Boolean);
      if (!list.length) return toast('Those episodes are already downloaded or queued');
      try {
        const r = await api('download', { source, sourceName: s.name, anime, episodes: list, a: linked() });
        toast(r.added ? `Downloading ${r.added === 1 ? 'episode ' + epNum(list[0], eps.indexOf(list[0])) : r.added + ' episodes'} in ${S.settings.dlQuality || '1080'}p or the closest` : 'Already downloaded or queued');
      } catch (err) {
        toast(err.message);
      }
    }

    function downloadSheet() {
      const d = dls();
      const free = eps.filter((e) => !d[e.url] || d[e.url].state === 'failed');
      const unwatched = free.filter((e) => !watched(e));
      const t = target();
      const fromNext = t ? free.filter((e) => eps.indexOf(e) >= t.i) : unwatched;
      const opts = [
        { v: 'next', label: 'Next episode', sub: fromNext[0] ? `Episode ${epNum(fromNext[0], eps.indexOf(fromNext[0]))}` : 'Nothing left to download', list: fromNext.slice(0, 1) },
        { v: 'next5', label: 'Next 5 episodes', sub: `${Math.min(5, fromNext.length)} episodes`, list: fromNext.slice(0, 5) },
        { v: 'unwatched', label: 'All unwatched', sub: `${unwatched.length} episodes`, list: unwatched },
        { v: 'all', label: 'Every episode', sub: `${free.length} episodes`, list: free },
      ];
      choose(`Download · ${S.settings.dlQuality || '1080'}p`, opts, '', (v) => download(opts.find((o) => o.v === v).list));
    }

    function downloadMenu(i) {
      const x = dls()[eps[i].url];
      if (!x) return download([eps[i]]);
      const opts = x.state === 'done' ? [{ v: 'play', label: 'Play the download' }, { v: 'del', label: 'Delete the download' }]
        : x.state === 'failed' ? [{ v: 'retry', label: 'Try again', sub: x.error || '' }, { v: 'del', label: 'Remove' }]
        : [{ v: 'del', label: 'Cancel the download', sub: x.state === 'downloading' ? `${x.pct}% done` : 'Waiting its turn' }];
      choose(`Episode ${epNum(eps[i], i)}`, opts, '', async (v) => {
        try {
          if (v === 'play') B.playDownload(x.id);
          else if (v === 'del') await api('deleteDownload', { id: x.id });
          else if (v === 'retry') await api('retryDownload', { id: x.id });
        } catch (err) {
          toast(err.message);
        }
      });
    }

    function episodeMenu(i) {
      const e = eps[i], w = watched(e);
      const opts = [
        { v: 'start', label: 'Play from the beginning' },
        { v: 'mark', label: w ? 'Mark as not watched' : 'Mark as watched' },
        { v: 'upto', label: 'Mark this and earlier ones as watched' },
      ];
      choose(epName(e, i), opts, '', async (v) => {
        if (v === 'start') return play(i, 0);
        const urls = v === 'mark' ? [e.url] : eps.slice(0, i + 1).map((x) => x.url);
        try {
          await remember();
          await api('mark', { key, urls, watched: v === 'upto' ? true : !w });
          await reloadShow();
          drawHero();
          drawEps();
        } catch (err) {
          toast(err.message);
        }
      });
    }

    async function aniListSheet() {
      if (!S.library || !S.library.account) {
        return confirmSheet('AniList isn’t connected', 'Connect AniList in Settings to keep your lists and progress in sync.', 'Settings', () => go('#/settings'));
      }
      const a = linked(), entry = aniEntry();
      const sh = openSheet(`<h3><span>AniList</span></h3>${a ? `<p>Linked to ${esc(entry ? entry.title : '#' + a)}${entry ? ` · ${entry.progress}/${entry.episodes || '?'} watched` : ''}.</p>` : `<p>Pick the AniList entry for this show, so episodes you watch count there.</p>`}
        <form style="display:flex;gap:8px;margin-bottom:10px"><input class="text" value="${esc(cleanTitle(anime.title))}" enterkeyhint="search"><button class="btn">${icon('search')}</button></form>
        <div class="cands">${loadingBox('Searching AniList…')}</div>${a ? '<div class="foot"><button class="btn danger" data-unlink>Unlink</button></div>' : ''}`, { wide: true });
      const box = $('.cands', sh);
      let found = [];
      const draw = () => {
        box.innerHTML = found.length ? `<div class="opts">${found.map((m, k) => `<button class="opt${m.id === a ? ' on' : ''}" data-k="${k}"${k === 0 ? ' data-first' : ''}><span>${esc(m.title)}<small>${esc([m.format && m.format.replace('_', ' '), m.year, m.episodes ? m.episodes + ' episodes' : '', m.romaji !== m.title ? m.romaji : ''].filter(Boolean).join(' · '))}</small></span>${m.id === a ? icon('check') : ''}</button>`).join('')}</div>` : emptyBox('No matches', 'Try another name.');
        if (padOn) setFocus(box.querySelector('[data-first]') || startFocus());
      };
      const search = async (q) => {
        box.innerHTML = loadingBox('Searching AniList…');
        try {
          found = (await api('anilistSearch', { q })) || [];
        } catch (err) {
          box.innerHTML = errBox(err.message);
          return;
        }
        if (sheetOpen()) draw();
      };
      $('form', sh).addEventListener('submit', () => {
        const input = $('input', sh);
        input.blur();
        search(input.value);
      });
      box.addEventListener('click', async (e) => {
        const t = e.target.closest('[data-k]');
        if (!t) return;
        const m = found[+t.dataset.k];
        closeSheet();
        try {
          await remember();
          await api('link', { key, a: m.id });
          matchA = m.id;
          await reloadShow();
          drawHero();
          toast(`Linked to ${m.title}`);
        } catch (err) {
          toast(err.message);
        }
      });
      const un = $('[data-unlink]', sh);
      if (un) un.addEventListener('click', async () => {
        closeSheet();
        await api('link', { key, a: 0 }).catch(() => {});
        matchA = 0;
        await reloadShow();
        drawHero();
      });
      search(cleanTitle(anime.title));
    }

    p.refresh = async (ev) => {
      if ((ev === 'net' || ev === 'all') && S.net && s && !seasons && (epsErr || infoErr)) {
        infoErr = '';
        start();
        return;
      }
      if (ev === 'downloads' && fromDls && dlState() !== dlSig) {
        const saved = savedEps();
        eps = saved.length ? saved : null;
        fromDls = !!eps;
        drawHero();
        return drawEps();
      }
      if (ev === 'downloads') {
        if (!eps) return;
        if (dlState() !== dlSig) return drawEps();
        // Only percentages moved.
        const d = dls();
        for (const b of $$('.side.busy', epBox)) {
          const x = eps[+b.dataset.i] && d[eps[+b.dataset.i].url];
          if (x) b.textContent = x.state === 'downloading' && x.pct ? x.pct + '%' : '···';
        }
        return;
      }
      if (ev === 'shelf' || ev === 'playerClosed' || ev === 'all' || ev === 'library') {
        await reloadShow();
        if (!p.alive) return;
        if (eps || seasons) {
          drawHero();
          drawEps();
        }
      }
    };
    drawHero();
    start();
  }

  // ================================================================ An AniList entry: find it on a source

  async function pageAniList(p) {
    const id = +p.r.args[0];
    const playEp = +(p.r.q.ep || 0);
    const hint = p.r.q.title || '';
    p.el.innerHTML = `${header(hint || 'Finding the show')}<div class="body">${loadingBox('Looking it up…')}</div>`;
    const body = $('.body', p.el);
    if (!S.sourcesLoaded) await waitFor(() => S.sourcesLoaded, 90000);
    if (!p.alive) return;
    if (!S.sources.length) {
      body.innerHTML = onboarding();
      return;
    }
    let entry = null;
    try {
      entry = await api('entry', { a: id });
    } catch (e) {}
    if (!p.alive) return;
    const names = [...new Set([hint, entry && entry.romaji, entry && entry.english, entry && entry.title].filter(Boolean))];
    if (!names.length) {
      body.innerHTML = emptyBox("Couldn't load that show from AniList", 'Check the connection and try again.');
      return;
    }
    const title = (entry && entry.title) || hint;
    $('.ph h1', p.el).textContent = title;
    const sources = sourcesByUse();
    const found = new Map(), status = new Map();
    let opened = false;

    const open = async (s, a) => {
      if (opened || !p.alive) return;
      opened = true;
      try {
        await api('remember', { source: s.id, sourceName: s.name, anime: a });
        await api('link', { key: keyOf(s.id, a.url), a: id });
      } catch (e) {}
      if (!p.alive) return;
      animeMem.set(keyOf(s.id, a.url), a);
      const ep = playEp || 0;
      go(`#/show/${enc(s.id)}/${enc(a.url)}${ep ? '?ep=' + ep : ''}`, { replace: true });
    };

    const draw = () => {
      if (opened || !p.alive) return;
      const pending = sources.filter((s) => !status.has(s.id)).length;
      let h = `<div class="banner"><span class="tx">${pending ? `Searching ${pending} source${pending === 1 ? '' : 's'} for <b>${esc(title)}</b>…` : `Pick <b>${esc(title)}</b> on one of your sources. AniDeck remembers the choice.`}</span>${pending ? '<div class="spin"></div>' : ''}</div>`;
      for (const s of sources) {
        const list = found.get(s.id);
        if (!list) {
          const st = status.get(s.id);
          if (st && st !== 'ok') h += section(esc(s.name), `<div class="note">${esc(st)}</div>`);
          continue;
        }
        if (!list.length) continue;
        h += `<div data-block="${esc(s.id)}">${section(esc(s.name), row(list.map((x, i) => card({ img: imgUrl(s.id, x.a.thumbnail_url), title: x.a.title, sub: x.score >= 0.9 ? 'Match' : '', attrs: `data-src="${esc(s.id)}" data-i="${i}" data-sig="m:${esc(s.id)}:${i}"` }))))}</div>`;
      }
      if (!pending && ![...found.values()].some((l) => l.length)) h += emptyBox(`None of your sources found ${title}`, 'Try searching for another name.', `<button class="btn primary" data-go="#/search?q=${enc(cleanTitle(names[0]))}" data-first>Search</button>`);
      keepFocus(() => (body.innerHTML = h));
    };
    p.el.addEventListener('click', (e) => {
      const t = e.target.closest('[data-src][data-i]');
      if (!t) return;
      const s = srcById(t.dataset.src), x = (found.get(t.dataset.src) || [])[+t.dataset.i];
      if (s && x) open(s, x.a);
    });

    draw();
    await Promise.all(sources.map(async (s) => {
      const results = [];
      for (const q of names.slice(0, 2)) {
        try {
          const r = await catalog(s.id, 'search', 1, cleanTitle(q));
          for (const a of (r.animes || []).slice(0, 15)) if (!results.some((x) => x.a.url === a.url)) results.push({ a, score: matchScore(a.title, names) });
          status.set(s.id, 'ok');
          if (results.some((x) => x.score >= 0.9)) break;
        } catch (e) {
          if (!status.has(s.id)) status.set(s.id, e.message);
        }
      }
      if (!p.alive || opened) return;
      results.sort((x, y) => y.score - x.score);
      found.set(s.id, results);
      if (!status.has(s.id)) status.set(s.id, 'ok');
      const best = results[0];
      if (best && best.score >= 0.9) return open(s, best.a);
      draw();
    }));
    draw();
  }

  function waitFor(test, ms) {
    return new Promise((resolve) => {
      const t0 = Date.now();
      const tick = () => (test() || Date.now() - t0 > ms ? resolve() : setTimeout(tick, 200));
      tick();
    });
  }

  // ================================================================ Library

  function pageLibrary(p) {
    let tab = p.r.q.tab || 'history';
    let list = p.r.q.list || 'CURRENT';
    const LISTS = [['CURRENT', 'Watching'], ['REPEATING', 'Rewatching'], ['PLANNING', 'Planning'], ['COMPLETED', 'Completed'], ['PAUSED', 'Paused'], ['DROPPED', 'Dropped']];
    const draw = () => keepFocus(() => {
      let h = header('Library', tab === 'history' && (S.shelf.recent || []).length ? `<button class="btn sm" data-act="clear">${icon('trash')} Clear history</button>`
        : tab === 'anilist' && S.library && S.library.account ? `<button class="btn sm" data-act="sync">${icon('refresh')} ${S.library.syncing ? 'Syncing…' : 'Sync'}</button>` : '');
      h += `<div class="seg">${[['history', 'History'], ['saved', 'Saved'], ['anilist', 'AniList']].map(([k, l]) => `<button class="btn sm${tab === k ? ' on' : ''}" data-tab="${k}" data-sig="tab:${k}">${l}</button>`).join('')}</div>`;
      if (tab === 'history') {
        const rec = S.shelf.recent || [];
        h += rec.length ? `<div class="list">${rec.map((b, i) => `<div class="item"><span class="thumb">${imgTag(coverOf(b))}</span><span class="tx"><b>${esc(b.title)}</b><small>${esc([shelfSub(b), b.sourceName, b.at ? ago(b.at) : ''].filter(Boolean).join(' · '))}</small></span><span class="acts"><button class="btn sm primary" data-resume="${i}" data-sig="res:${esc(b.key)}">${icon('play')} Continue</button><button class="btn sm" data-open="${i}" data-sig="open:${esc(b.key)}">Show</button><button class="btn sm icon" data-forget="${i}" data-sig="del:${esc(b.key)}" aria-label="Remove">${icon('x')}</button></span></div>`).join('')}</div>`
          : emptyBox('Nothing watched yet', 'Shows you play show up here, so you can pick up where you left off.');
      } else if (tab === 'saved') {
        const saved = S.shelf.saved || [];
        h += saved.length ? `<div class="grid">${saved.map((b, i) => shelfCard(b, i, 'saved')).join('')}</div>` : emptyBox('No saved shows', 'Save a show from its page to keep it here, cover included, even offline.');
      } else {
        const L = S.library;
        if (!L || !L.account) {
          h += `<div class="empty"><b>Connect AniList</b><span>See your lists here, get new-episode alerts, and have what you watch count on AniList.</span><span style="display:flex;gap:8px"><button class="btn primary" data-act="login" data-first>Log in to AniList</button><button class="btn" data-act="uname">Use a username</button></span></div>`;
        } else {
          h += `<div class="seg">${LISTS.map(([k, l]) => `<button class="btn sm${list === k ? ' on' : ''}" data-list="${k}" data-sig="list:${k}">${l} <small style="opacity:.6">${aniList(k).length}</small></button>`).join('')}</div>`;
          if (L.error) h += `<div class="banner warn"><span class="tx">${esc(L.error)}</span></div>`;
          const items = aniList(list);
          h += items.length ? `<div class="grid">${items.map((e, i) => aniCard(e, i, list)).join('')}</div>` : emptyBox('Nothing on this list');
          if (L.updated) h += `<div class="note">Updated ${esc(ago(L.updated))}.</div>`;
        }
      }
      p.el.innerHTML = h;
    });
    p.el.addEventListener('click', (e) => {
      const t = e.target.closest('button');
      if (!t) return;
      const d = t.dataset, rec = S.shelf.recent || [];
      if (d.tab) {
        tab = d.tab;
        draw();
      } else if (d.list) {
        list = d.list;
        draw();
      } else if (d.resume) openShelf(rec[+d.resume], true);
      else if (d.open) openShelf(rec[+d.open], false);
      else if (d.forget) api('forget', { key: rec[+d.forget].key }).catch((err) => toast(err.message));
      else if (d.shelf) openShelf((S.shelf[d.shelf] || [])[+d.i], false);
      else if (d.ani) openAniEntry(aniList(d.list)[+d.ani]);
      else if (d.act === 'clear') confirmSheet('Clear history?', 'Continue Watching and episode progress go. Saved shows, downloads and AniList stay.', 'Clear', () => api('clearHistory').catch((err) => toast(err.message)));
      else if (d.act === 'sync') api('syncLibrary').catch(() => {});
      else if (d.act === 'login') B.login();
      else if (d.act === 'uname') askUserName();
    });
    p.refresh = (ev) => {
      if (['shelf', 'library', 'all'].includes(ev)) draw();
    };
    draw();
  }

  function askUserName() {
    textSheet('AniList username', '', (v) => {
      if (v.trim()) B.useName(v.trim());
    }, { text: 'Shows the lists of any public AniList profile, read-only: nothing gets marked as watched.', placeholder: 'Username', yes: 'Use' });
  }

  // ================================================================ Downloads

  function pageDownloads(p) {
    const draw = () => keepFocus(() => {
      const D = S.downloads, items = D.items || [];
      const active = items.filter((r) => ['queued', 'preparing', 'downloading'].includes(r.state));
      const failed = items.filter((r) => r.state === 'failed');
      const done = items.filter((r) => r.state === 'done');
      let h = header('Downloads');
      h += `<div class="note" style="margin-bottom:6px">${done.length} episode${done.length === 1 ? '' : 's'} · ${size(D.used || 0)} in ${esc(D.folder || 'Movies/AniDeck')} · ${size(D.free || 0)} free</div>`;
      if (!items.length) h += emptyBox('No downloads', 'Download episodes from a show’s page to watch them without internet.');
      if (active.length) {
        h += section('Downloading', `<div class="list">${active.map((r) => {
          const pct = r.size > 0 ? Math.min(100, Math.round((r.got * 100) / r.size)) : 0;
          const what = r.state === 'queued' ? 'Waiting' : r.state === 'preparing' ? r.note || 'Finding a video…' : `${pct}%${r.size ? ' of ' + size(r.size) : ''}${r.note ? ' · ' + r.note : ''}`;
          return `<div class="item"><span class="thumb">${imgTag(dlImg(r))}</span><span class="tx"><b>${esc(r.show)} · Ep ${esc(numLabel(r.ep))}</b><small>${esc(what)}</small>${r.state === 'downloading' ? `<div class="dlbar"><i style="width:${pct}%"></i></div>` : ''}</span><span class="acts"><button class="btn sm" data-del="${esc(r.id)}" data-sig="del:${esc(r.id)}">Cancel</button></span></div>`;
        }).join('')}</div>`);
      }
      if (failed.length) {
        h += section('Didn’t finish', `<div class="list">${failed.map((r) => `<div class="item"><span class="thumb">${imgTag(dlImg(r))}</span><span class="tx"><b>${esc(r.show)} · Ep ${esc(numLabel(r.ep))}</b><small class="err">${esc(r.error || 'Failed')}</small></span><span class="acts"><button class="btn sm" data-retry="${esc(r.id)}" data-sig="retry:${esc(r.id)}">${icon('refresh')} Retry</button><button class="btn sm" data-del="${esc(r.id)}" data-sig="del:${esc(r.id)}">Remove</button></span></div>`).join('')}</div>`);
      }
      const shows = new Map();
      for (const r of done) {
        const k = r.key || r.show;
        if (!shows.has(k)) shows.set(k, []);
        shows.get(k).push(r);
      }
      for (const list of shows.values()) {
        list.sort((a, b) => a.ep - b.ep);
        const r0 = list[0];
        h += section(esc(r0.show) + ` <small>${list.length}</small>`, `<div class="list">${list.map((r) => {
          const watched = r.d > 0 && r.t >= r.d * DONE;
          const sub = [bareTitle(r.epTitle), r.quality, r.size ? size(r.size) : '', watched ? 'watched' : r.d > 0 && r.t > 5 ? `${mins(r.d - r.t)} min left` : ''].filter(Boolean).join(' · ');
          return `<div class="item"><span class="thumb">${imgTag(dlImg(r))}</span><span class="tx"><b>Episode ${esc(numLabel(r.ep))}</b><small>${esc(sub)}</small></span><span class="acts"><button class="btn sm primary" data-play="${esc(r.id)}" data-sig="play:${esc(r.id)}">${icon('play')} Play</button><button class="btn sm icon" data-del="${esc(r.id)}" data-done="1" data-sig="del:${esc(r.id)}" aria-label="Delete">${icon('trash')}</button></span></div>`;
        }).join('')}</div>`, r0.source && r0.anime ? `<button class="btn sm" data-show="${esc(r0.id)}">Show</button>` : '');
      }
      p.el.innerHTML = h;
    });
    p.el.addEventListener('click', (e) => {
      const t = e.target.closest('button');
      if (!t) return;
      const d = t.dataset;
      if (d.play) B.playDownload(d.play);
      else if (d.retry) api('retryDownload', { id: d.retry }).catch((err) => toast(err.message));
      else if (d.del) {
        const r = (S.downloads.items || []).find((x) => x.id === d.del);
        if (d.done && r) confirmSheet('Delete this episode?', `${r.show}, episode ${numLabel(r.ep)} leaves the Thor.`, 'Delete', () => api('deleteDownload', { id: d.del }).catch((err) => toast(err.message)));
        else api('deleteDownload', { id: d.del }).catch((err) => toast(err.message));
      } else if (d.show) {
        const r = (S.downloads.items || []).find((x) => x.id === d.show);
        if (r) openAnime(r.source, r.anime);
      }
    });
    p.refresh = (ev) => {
      if (ev === 'downloads' || ev === 'all') draw();
    };
    draw();
  }

  // ================================================================ Extensions

  function pageExtensions(p) {
    let repo = null, err = '', busy = new Set(), filter = 'mine';
    const langs = () => new Set(String(S.settings.langs || 'en').split(',').map((x) => x.trim()).filter(Boolean));
    const draw = () => keepFocus(() => {
      let h = header('Extensions', `<button class="btn sm" data-act="refresh">${icon('refresh')} Check for updates</button>`);
      if (err) h += errBox(err);
      if (!repo) {
        p.el.innerHTML = h + (err ? '' : loadingBox('Loading the Yūzōnō repo…'));
        return;
      }
      if (repo.offline) h += `<div class="banner warn"><span class="tx">Couldn't reach the repo, so only installed extensions show.</span></div>`;
      const exts = repo.extensions || [];
      const mine = exts.filter((x) => x.installed);
      const L = langs(), nsfw = !!S.settings.showNsfw;
      const all = [...new Set(exts.map((x) => x.lang))].sort((a, b) => langName(a).localeCompare(langName(b)));
      h += `<div class="seg"><button class="btn sm${filter === 'mine' ? ' on' : ''}" data-filter="mine">Installed ${mine.length}</button><button class="btn sm${filter === 'all' ? ' on' : ''}" data-filter="all">Available</button><button class="btn sm" data-act="langs">Languages: ${esc([...L].map(langName).join(', ') || 'all')}</button><button class="btn sm${nsfw ? ' on' : ''}" data-act="nsfw">18+ ${nsfw ? 'shown' : 'hidden'}</button></div>`;
      const shown = filter === 'mine' ? mine : exts.filter((x) => !x.installed && (L.has(x.lang) || x.lang === 'all') && (nsfw || x.rating < 2));
      if (filter === 'mine' && !mine.length) h += emptyBox('No extensions yet', 'Each extension adds one or more sites to browse and watch from.', `<button class="btn primary" data-filter="all" data-first>Browse the repo</button>`);
      else if (!shown.length) h += emptyBox('Nothing in these languages', 'Pick more languages above.');
      h += `<div class="list">${shown.map((x) => {
        const b = busy.has(x.pkg);
        const lib = x.lib ? `lib ${x.lib}` : '';
        const sub = [langName(x.lang), x.installed ? `v${x.installed}` + (x.update ? ` → v${x.version}` : '') : `v${x.version}`, x.rating === 2 ? '18+' : x.rating === 1 ? 'some 18+' : '', x.sources && x.sources.length > 1 ? `${x.sources.length} sources` : ''].filter(Boolean).join(' · ');
        const acts = b ? '<div class="spin"></div>'
          : x.installed ? `${x.update ? `<button class="btn sm primary" data-install="${esc(x.pkg)}" data-sig="up:${esc(x.pkg)}">Update</button>` : ''}<button class="btn sm" data-remove="${esc(x.pkg)}" data-sig="rm:${esc(x.pkg)}">Remove</button>`
          : `<button class="btn sm primary" data-install="${esc(x.pkg)}" data-sig="in:${esc(x.pkg)}">Install</button>`;
        return `<div class="item"><span class="ic">${imgTag(imgUrl('0', x.icon))}</span><span class="tx"><b>${esc(x.name)}</b><small>${esc(sub)}${esc(lib ? ' · ' + lib : '')}</small>${x.broken ? `<small class="err">${esc(x.broken)}</small>` : ''}</span><span class="acts">${acts}</span></div>`;
      }).join('')}</div>`;
      p.el.innerHTML = h;
    });
    async function load(force) {
      err = '';
      try {
        repo = await api('repo', { force: !!force });
      } catch (e) {
        err = e.message;
      }
      if (!p.alive) return;
      if (repo && !(repo.extensions || []).some((x) => x.installed) && filter === 'mine' && !force) filter = 'all';
      draw();
    }
    p.el.addEventListener('click', async (e) => {
      const t = e.target.closest('button');
      if (!t) return;
      const d = t.dataset;
      if (d.filter) {
        filter = d.filter;
        draw();
      } else if (d.act === 'refresh') {
        repo = null;
        draw();
        load(true);
      } else if (d.act === 'nsfw') {
        await api('set', { key: 'showNsfw', value: !S.settings.showNsfw }).then((r) => (S.settings = r)).catch(() => {});
        draw();
      } else if (d.act === 'langs') {
        const all = [...new Set((repo.extensions || []).map((x) => x.lang))].filter((l) => l !== 'all');
        multiSheet('Languages', all.sort((a, b) => langName(a).localeCompare(langName(b))).map((l) => ({ v: l, label: langName(l) })), [...langs()], async (vals) => {
          S.settings = await api('set', { key: 'langs', value: vals.join(',') || 'en' }).catch(() => S.settings);
          draw();
        });
      } else if (d.install || d.remove) {
        const pkg = d.install || d.remove;
        const x = (repo.extensions || []).find((y) => y.pkg === pkg);
        if (d.remove) return confirmSheet(`Remove ${x ? x.name : 'extension'}?`, 'Its sources go from Browse. Shows you watched and downloads stay.', 'Remove', () => act('uninstall', pkg, x));
        act('install', pkg, x);
      }
    });
    async function act(method, pkg, x) {
      busy.add(pkg);
      draw();
      try {
        repo = await api(method, { pkg });
        toast(method === 'install' ? `${x ? x.name : 'Extension'} is ready` : `${x ? x.name : 'Extension'} removed`);
      } catch (e) {
        toast(e.message);
      }
      busy.delete(pkg);
      if (p.alive) draw();
    }
    p.refresh = (ev) => {
      if (ev === 'settings') draw();
    };
    draw();
    load(false);
  }

  function multiSheet(title, options, current, onDone) {
    const on = new Set(current);
    const sh = openSheet(`<h3><span>${esc(title)}</span></h3><div class="opts"></div><div class="foot"><button class="btn primary" data-done>Done</button></div>`);
    const box = $('.opts', sh);
    const draw = () => {
      const sig = focusEl && focusEl.dataset.sig;
      box.innerHTML = options.map((o, i) => `<button class="opt${on.has(o.v) ? ' on' : ''}" data-i="${i}" data-sig="o:${i}"${i === 0 ? ' data-first' : ''}><span>${esc(o.label)}${o.sub ? `<small>${esc(o.sub)}</small>` : ''}</span>${on.has(o.v) ? icon('check') : ''}</button>`).join('');
      if (padOn && sig) {
        const again = box.querySelector(`[data-sig="${sig}"]`);
        if (again) setFocus(again, false);
      }
    };
    box.addEventListener('click', (e) => {
      const t = e.target.closest('[data-i]');
      if (!t) return;
      const v = options[+t.dataset.i].v;
      on.has(v) ? on.delete(v) : on.add(v);
      draw();
    });
    sh.querySelector('[data-done]').addEventListener('click', () => {
      closeSheet();
      onDone(options.map((o) => o.v).filter((v) => on.has(v)));
    });
    draw();
    if (padOn) setFocus(startFocus());
  }

  // ================================================================ A source's own settings

  function pageSourcePrefs(p) {
    const id = p.r.args[0], s = srcById(id);
    if (!s && laterSources(p, pageSourcePrefs)) return;
    let items = null, err = '';
    const flat = [];
    const draw = () => keepFocus(() => {
      flat.length = 0;
      let h = header(`${s ? s.name : 'Source'} settings`);
      if (err) h += errBox(err);
      if (!items) {
        p.el.innerHTML = h + (err ? '' : loadingBox());
        return;
      }
      const render = (list) => list.filter((x) => x.visible !== false).map((x) => {
        if (x.type === 'group') return `<div class="fsec">${esc(x.title)}</div>${render(x.items || [])}`;
        const i = flat.push(x) - 1;
        const dis = x.enabled === false ? ' disabled' : '';
        // Like Android, a list's summary can show its choice with %s.
        const k = x.type === 'list' ? (x.values || []).indexOf(x.value) : -1;
        const entry = x.type === 'list' ? (k >= 0 ? x.entries[k] : x.value || '') : '';
        const inSummary = x.type === 'list' && /%s/.test(x.summary || '');
        const summary = inSummary ? x.summary.replace(/%s/g, entry).replace(/%%/g, '%') : x.summary;
        const lbl = `<span class="lbl">${esc(x.title)}${summary ? `<small>${esc(summary)}</small>` : ''}</span>`;
        if (x.type === 'switch') return `<button class="field${x.value ? ' on' : ''}" data-p="${i}" data-sig="p:${esc(x.key)}"${dis}>${lbl}<span class="toggle"></span></button>`;
        if (x.type === 'list') return `<button class="field" data-p="${i}" data-sig="p:${esc(x.key)}"${dis}>${lbl}${inSummary ? '' : `<span class="val">${esc(entry)}</span>`}</button>`;
        if (x.type === 'multi') return `<button class="field" data-p="${i}" data-sig="p:${esc(x.key)}"${dis}>${lbl}<span class="val">${(x.value || []).length} chosen</span></button>`;
        if (x.type === 'text') return `<button class="field" data-p="${i}" data-sig="p:${esc(x.key)}"${dis}>${lbl}<span class="val">${esc(x.value || '')}</span></button>`;
        return `<div class="field info">${lbl}</div>`;
      }).join('');
      h += items.length ? render(items) : emptyBox('No settings', `${s ? s.name : 'This source'} has nothing to set.`);
      p.el.innerHTML = h;
    });
    const set = async (x, value) => {
      try {
        const r = await api('setPref', { source: id, key: x.key, value });
        items = r.items || [];
        if (r.accepted === false) toast('The source didn’t accept that value');
        catCache.clear();
      } catch (e) {
        toast(e.message);
      }
      if (p.alive) draw();
    };
    p.el.addEventListener('click', (e) => {
      const t = e.target.closest('[data-p]');
      if (!t) return;
      const x = flat[+t.dataset.p];
      if (x.type === 'switch') set(x, !x.value);
      else if (x.type === 'list') choose(x.title, (x.values || []).map((v, k) => ({ v, label: x.entries[k] })), x.value, (v) => set(x, v));
      else if (x.type === 'multi') multiSheet(x.title, (x.values || []).map((v, k) => ({ v, label: x.entries[k] })), x.value || [], (v) => set(x, v));
      else if (x.type === 'text') textSheet(x.dialogTitle || x.title, x.value || '', (v) => set(x, v), { text: x.dialogMessage || '' });
    });
    draw();
    api('prefs', { source: id }).then((r) => {
      items = r.items || [];
    }).catch((e) => {
      err = e.message;
    }).then(() => p.alive && draw());
  }

  // ================================================================ Settings

  function pageSettings(p) {
    const SUB = [['en', 'English'], ['es', 'Spanish'], ['pt', 'Portuguese'], ['fr', 'French'], ['de', 'German'], ['it', 'Italian'], ['ar', 'Arabic'], ['ru', 'Russian'], ['id', 'Indonesian'], ['tr', 'Turkish'], ['ja', 'Japanese']];
    const AUDIO = [['ja', 'Japanese (original)'], ['en', 'English (dub)'], ['es', 'Spanish'], ['pt', 'Portuguese'], ['fr', 'French'], ['de', 'German'], ['it', 'Italian']];
    const QUALITY = [['360', '360p', 'Smallest files'], ['480', '480p'], ['720', '720p'], ['1080', '1080p', 'Default'], ['2160', 'Best available', 'Up to 4K; large files']];
    const sw = (k, title, sub) => `<button class="field${S.settings[k] ? ' on' : ''}" data-sw="${k}" data-sig="sw:${k}"><span class="lbl">${esc(title)}${sub ? `<small>${esc(sub)}</small>` : ''}</span><span class="toggle"></span></button>`;
    const pick = (k, title, opts) => {
      const o = opts.find((x) => x[0] === String(S.settings[k]));
      return `<button class="field" data-pick="${k}" data-sig="pk:${k}"><span class="lbl">${esc(title)}</span><span class="val">${esc(o ? o[1] : S.settings[k] || '')}</span></button>`;
    };
    const OPTS = { subLang: SUB, audioLang: AUDIO, dlQuality: QUALITY };
    const TITLES = { subLang: 'Subtitles', audioLang: 'Audio', dlQuality: 'Download quality' };
    const draw = () => keepFocus(() => {
      const L = S.library, acct = L && L.account;
      let h = header('Settings');
      h += '<div class="fsec">Playback</div>';
      h += sw('autoNext', 'Play the next episode', 'When an episode ends');
      h += sw('autoSkip', 'Skip intros and recaps', 'On sources that mark them; otherwise X skips 85 s');
      h += sw('autoServer', 'Switch servers when one fails', 'Tries the next server or quality by itself');
      h += pick('subLang', 'Subtitles', SUB) + pick('audioLang', 'Audio', AUDIO);
      h += '<div class="fsec">Downloads</div>' + pick('dlQuality', 'Download quality', QUALITY);
      h += `<div class="field info"><span class="lbl">Episodes save to ${esc((S.downloads && S.downloads.folder) || 'Movies/AniDeck')} on the Thor, with subtitles kept by AniDeck.</span></div>`;
      h += '<div class="fsec">AniList</div>';
      if (acct) {
        h += `<div class="account">${acct.avatar ? `<img src="${esc(imgUrl('0', acct.avatar))}" alt="">` : '<span class="av"></span>'}<span class="tx"><b>${esc(acct.name)}</b><small>${acct.via === 'user' ? 'Read-only: lists only, nothing counts as watched' : 'Logged in: episodes you finish count on AniList'}</small></span><button class="btn sm" data-act="signout">Sign out</button></div>`;
        if (acct.via === 'user') h += `<button class="field" data-act="login"><span class="lbl">Log in to AniList<small>To mark episodes as watched</small></span></button>`;
      } else {
        h += `<div class="account"><span class="av"></span><span class="tx"><b>Not connected</b><small>Log in to sync lists and progress</small></span><button class="btn sm primary" data-act="login">Log in</button><button class="btn sm" data-act="uname">Use a username</button></div>`;
      }
      if (L && L.error) h += `<div class="banner warn"><span class="tx">${esc(L.error)}</span></div>`;
      h += sw('autoTrack', 'Count episodes on AniList', 'After 85% of an episode');
      h += sw('notify', 'New-episode alerts', 'For shows on your Watching list');
      h += '<div class="fsec">Extensions</div>';
      h += sw('showNsfw', 'Show 18+ extensions', 'In the extension list');
      h += `<button class="field" data-go="#/extensions"><span class="lbl">Manage extensions<small>${S.sources.length} source${S.sources.length === 1 ? '' : 's'} installed</small></span></button>`;
      h += '<div class="fsec">About</div>';
      h += `<div class="field info"><span class="lbl">AniDeck ${esc(S.version || '')}. Shows come from Aniyomi extensions (Yūzōnō repo); episodes stream and download straight from each source.</span></div>`;
      p.el.innerHTML = h;
    });
    p.el.addEventListener('click', async (e) => {
      const t = e.target.closest('button');
      if (!t) return;
      const d = t.dataset;
      if (d.sw) S.settings = await api('set', { key: d.sw, value: !S.settings[d.sw] }).catch(() => S.settings);
      else if (d.pick) {
        return choose(TITLES[d.pick], OPTS[d.pick].map(([v, label, sub]) => ({ v, label, sub })), String(S.settings[d.pick]), async (v) => {
          S.settings = await api('set', { key: d.pick, value: v }).catch(() => S.settings);
          draw();
        });
      } else if (d.act === 'login') return B.login();
      else if (d.act === 'uname') return askUserName();
      else if (d.act === 'signout') return confirmSheet('Sign out of AniList?', 'Your lists leave AniDeck; nothing changes on AniList.', 'Sign out', () => B.signOut());
      else return;
      draw();
    });
    p.refresh = (ev) => {
      if (['settings', 'library', 'all', 'sources'].includes(ev)) draw();
    };
    draw();
  }

  const PAGES = {
    home: pageHome, browse: pageBrowse, source: pageSource, search: pageSearch, show: pageShow, anilist: pageAniList,
    library: pageLibrary, downloads: pageDownloads, extensions: pageExtensions, prefs: pageSourcePrefs, settings: pageSettings,
  };

  // ---- pushes from the app ----

  app.on = (event, data) => {
    switch (event) {
      case 'shelf': S.shelf = data || S.shelf; break;
      case 'downloads': S.downloads = data || S.downloads; break;
      case 'library': S.library = data; break;
      case 'settings': S.settings = data || S.settings; break;
      case 'net': S.net = !!data; break;
      case 'sources': loadSources(); return;
      default: break;
    }
    bump(event);
  };

  async function loadSources() {
    try {
      S.sources = (await api('sources')) || [];
    } catch (e) {
      S.sources = [];
    }
    S.sourcesLoaded = true;
    catCache.clear();
    bump('sources');
  }

  function start() {
    try {
      const info = JSON.parse(B.info());
      S.version = info.version;
      S.net = info.net !== false;
    } catch (e) {}
    $('#rail').innerHTML = `<div class="logo">Ani<b>Deck</b></div>${[['home', 'Home', 'home'], ['browse', 'Browse', 'browse'], ['search', 'Search', 'search'], ['library', 'Library', 'library'], ['downloads', 'Downloads', 'download'], ['extensions', 'Extensions', 'ext'], ['settings', 'Settings', 'settings']]
      .map(([r, label, ic]) => `<button class="tab" data-go="#/${r}" data-tab="${r}">${icon(ic)}<span>${label}</span>${r === 'downloads' ? '<em></em>' : ''}</button>`).join('')}`;
    go('#/home');
    api('settings').then((r) => { S.settings = r || {}; bump('settings'); }).catch(() => {});
    api('shelf').then((r) => { S.shelf = r || S.shelf; bump('shelf'); }).catch(() => {});
    api('downloads').then((r) => { S.downloads = r || S.downloads; bump('downloads'); }).catch(() => {});
    api('library').then((r) => { S.library = r; bump('library'); }).catch(() => {});
    loadSources();
  }
  start();
})();
