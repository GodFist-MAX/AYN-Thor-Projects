'use strict';
/* XMB rendering and navigation: horizontal category bar, vertical item columns, nested folders. */

const L = {
  catX: 560, catY: 250, catGap: 200,
  selY: 455, belowSel: 170, gap: 116, aboveY: 112,
  colX: 560, subColX: 660, parentX: 330,
  detailX: 1090, // left edge of #detail (right 80px, width 720px) less a 30px margin
};

const XMB = {
  cats: [],
  ci: 0,
  sel: {},
  stack: [],
  catEls: [],
  catCol: null,
  busy: false,
  repeating: false,

  init() {
    this.root = $('#xmb');
    this.bar = $('#bar');
    this.crumbs = $('#crumbs');
    this.initTouch();
  },

  // ---------- state ----------
  list() { return this.stack.length ? this.stack[this.stack.length - 1].items : (this.cats[this.ci] || { items: [] }).items; },
  index() {
    if (this.stack.length) return this.stack[this.stack.length - 1].si;
    return this.sel[(this.cats[this.ci] || {}).id] || 0;
  },
  setIndex(i) {
    if (this.stack.length) this.stack[this.stack.length - 1].si = i;
    else this.sel[this.cats[this.ci].id] = i;
  },
  current() { return this.list()[this.index()]; },
  category() { return this.cats[this.ci]; },

  setCategories(cats) {
    const prev = this.cats.length ? {
      catId: this.cats[this.ci].id,
      selIds: Object.fromEntries(this.cats.map(c => [c.id, (c.items[this.sel[c.id] || 0] || {}).id])),
      path: this.stack.map(s => ({ id: s.folder.id, selId: (s.items[s.si] || {}).id, children: s.folder.children })),
    } : null;
    for (const s of this.stack) s.col.el.remove();
    this.stack = [];
    if (this.catCol) this.catCol.el.remove();
    this.cats = cats;
    this.buildBar();
    this.sel = {};
    if (prev) {
      this.ci = Math.max(0, cats.findIndex(c => c.id === prev.catId));
      for (const c of cats) {
        const id = prev.selIds[c.id];
        const i = id ? c.items.findIndex(x => x.id === id) : -1;
        this.sel[c.id] = i >= 0 ? i : 0;
      }
    } else {
      this.ci = Math.max(0, cats.findIndex(c => c.id === 'game'));
    }
    this.catCol = this.newCol();
    if (prev) {
      for (const p of prev.path) {
        const items = this.list();
        const idx = items.findIndex(x => x.id === p.id);
        if (idx < 0) break;
        const f = items[idx];
        if (!f.children && f.load && p.children) f.children = p.children;
        if (!f.children || !f.children.length) break;
        this.setIndex(idx);
        const si = Math.max(0, f.children.findIndex(x => x.id === p.selId));
        this.stack.push({ folder: f, items: f.children, si, col: this.newCol() });
      }
    }
    this.layout();
    this.focusChanged(true);
  },

  /* Swaps in a rebuilt category (e.g. after new social data) without disturbing navigation elsewhere. */
  replaceCategory(cat) {
    const i = this.cats.findIndex(c => c.id === cat.id);
    if (i < 0) return;
    if (i === this.ci) {
      const cats = this.cats.slice();
      cats[i] = cat;
      this.setCategories(cats);
      return;
    }
    const old = this.cats[i];
    const selId = (old.items[this.sel[old.id] || 0] || {}).id;
    this.cats[i] = cat;
    const si = cat.items.findIndex(x => x.id === selId);
    this.sel[cat.id] = si >= 0 ? si : 0;
  },

  // ---------- DOM ----------
  buildBar() {
    this.bar.innerHTML = '';
    this.catEls = this.cats.map((c, i) => {
      const e = el('div', 'cat');
      e.innerHTML = `<div class="ic">${glyph(c.glyph)}</div><div class="lb">${esc(c.label)}</div>`;
      e._ci = i;
      e._ic = e.firstChild;
      this.bar.appendChild(e);
      return e;
    });
  },

  newCol(cls) {
    const c = { el: el('div', 'col' + (cls ? ' ' + cls : '')), map: new Map() };
    this.root.appendChild(c.el);
    if (cls) requestAnimationFrame(() => requestAnimationFrame(() => c.el.classList.remove('enter-r', 'enter-l')));
    return c;
  },

  dropCol(c, cls) {
    if (!c) return;
    c.el.classList.add(cls);
    setTimeout(() => c.el.remove(), 330);
  },

  fillIcon(ic, icon, onFallback) {
    ic.className = 'ic';
    ic.innerHTML = '';
    if (!icon || icon.glyph) {
      ic.classList.add('sq', 'glyph');
      ic.innerHTML = glyph(icon ? icon.glyph : 'folder');
      return;
    }
    ic.classList.add(icon.shape === 'wide' ? 'wide' : 'sq');
    (icon.style || '').split(' ').filter(Boolean).forEach(c => ic.classList.add(c));
    if (ic.classList.contains('tile')) {
      const bl = el('div', 'bl');
      bl.dataset.blur = icon.blur || icon.img;
      ic.appendChild(bl);
    }
    const img = new Image();
    img.className = 'main';
    img.decoding = 'async';
    img.onerror = () => {
      ic.className = 'ic sq glyph';
      ic.innerHTML = glyph(icon.fallback || 'folder');
      if (onFallback) onFallback();
    };
    img.src = icon.img;
    ic.appendChild(img);
  },

  makeItem(it) {
    const e = el('div', 'it');
    const ic = el('div', 'ic');
    const tx = el('div', 'tx');
    e.append(ic, tx);
    e._ic = ic;
    e._tx = tx;
    e._item = it;
    this.fillIcon(ic, it.icon, () => { e._shape = 'sq'; this.layout(); });
    e._shape = it.icon && it.icon.shape === 'wide' && !it.icon.glyph ? 'wide' : 'sq';
    this.fillText(e);
    return e;
  },

  fillText(e) {
    const it = e._item;
    const sub = it.subHtml || esc(it.sub || '');
    e._hasSub = !!sub;
    e._tx.innerHTML = `<div class="t">${esc(it.label)}</div>${sub ? `<div class="s">${sub}</div>` : ''}`;
  },

  /* New items appear in place and fade in (.in); a new element's first style never transitions. */
  ensureEl(col, it) {
    let e = col.map.get(it);
    if (!e) {
      e = this.makeItem(it);
      e.classList.add('in');
      col.el.appendChild(e);
      col.map.set(it, e);
    }
    return e;
  },

  fadeRemove(e) {
    e.style.opacity = '0';
    setTimeout(() => e.remove(), 260);
  },

  /* `underDetail` rows share the screen band of the detail panel, so their labels stop short of it. */
  place(e, x, y, sel, op, underDetail) {
    const wideIc = e._shape === 'wide';
    const W = wideIc ? 240 : 140;
    const s = sel ? 1 : (wideIc ? 0.6 : 0.58);
    e.classList.toggle('sel', sel);
    e.style.transform = `translate3d(${x}px,${y}px,0)`;
    e._ic.style.transform = `scale(${s})`;
    const tx = W * s / 2 + 30;
    const ty = sel ? (e._hasSub ? -40 : -23) : -23;
    const ts = sel ? 1 : 0.8;
    e._tx.style.transform = `translate3d(${tx}px,${ty}px,0) scale(${ts})`;
    if (underDetail) e._tx.style.setProperty('--tw', Math.max(160, (L.detailX - x - tx) / ts) + 'px');
    else e._tx.style.removeProperty('--tw');
    e._tx.style.opacity = '1';
    e.style.opacity = String(op);
  },

  layoutActive(col, items, si, x, depth) {
    const lo = Math.max(0, si - 4), hi = Math.min(items.length - 1, si + 8);
    const keep = new Set();
    const hasDetail = !!(items[si] && items[si].detail);
    for (let i = lo; i <= hi; i++) {
      const it = items[i];
      const e = this.ensureEl(col, it);
      keep.add(it);
      let y;
      if (i === si) y = L.selY;
      else if (i > si) y = L.selY + L.belowSel + (i - si - 1) * L.gap;
      else y = (depth === 0 ? L.aboveY : L.selY - L.belowSel + 24) - (si - 1 - i) * L.gap;
      let op = i < si ? 0.6 : (i === si ? 1 : 0.92);
      if (y > 1110 || y < -40) op = 0;
      this.place(e, x, y, i === si, op, hasDetail && i > si);
    }
    for (const [it, e] of col.map) {
      if (!keep.has(it)) {
        col.map.delete(it);
        this.fadeRemove(e);
      }
    }
  },

  layoutParent(col, items, si) {
    const it = items[si];
    for (const [k, e] of col.map) {
      if (k !== it) {
        col.map.delete(k);
        this.fadeRemove(e);
      }
    }
    if (!it) return;
    const e = this.ensureEl(col, it);
    this.place(e, L.parentX, L.selY, false, 0.85);
    e._ic.style.transform = 'scale(0.62)';
    e._tx.style.opacity = '0';
  },

  layoutHidden(col) {
    for (const [k, e] of col.map) {
      col.map.delete(k);
      this.fadeRemove(e);
    }
  },

  layoutBar() {
    this.cats.forEach((c, i) => {
      const e = this.catEls[i];
      const x = L.catX + (i - this.ci) * L.catGap;
      const sel = i === this.ci;
      e.classList.toggle('sel', sel);
      e.style.transform = `translate3d(${x}px,${L.catY}px,0)`;
      e._ic.style.transform = `scale(${sel ? 1 : 0.72})`;
      e.style.opacity = x < -120 || x > 2040 ? 0 : (sel ? 1 : 0.85);
    });
    this.bar.classList.toggle('hidden', this.stack.length > 0);
  },

  layout() {
    if (!this.cats.length) return;
    this.layoutBar();
    const depth = this.stack.length;
    const levels = [{ col: this.catCol, items: this.category().items, si: this.sel[this.category().id] || 0 }, ...this.stack];
    for (let d = 0; d <= depth; d++) {
      const lv = levels[d];
      if (d === depth) this.layoutActive(lv.col, lv.items, lv.si, depth ? L.subColX : L.colX, d);
      else if (d === depth - 1) this.layoutParent(lv.col, lv.items, lv.si);
      else this.layoutHidden(lv.col);
    }
    const parts = [this.category().label, ...this.stack.map(s => s.folder.label)];
    const crumbs = parts.map((p, i) => `<span class="c${i === parts.length - 1 ? ' cur' : ''}">${esc(p)}</span>`).join('<span class="sep">›</span>');
    if (crumbs !== this.crumbsHtml) this.crumbs.innerHTML = this.crumbsHtml = crumbs;
  },

  /* Re-renders labels of visible items (settings values, now playing). */
  refresh() {
    const cols = [this.catCol, ...this.stack.map(s => s.col)];
    for (const c of cols) for (const e of c.map.values()) this.fillText(e);
    this.layout();
    Panels.detail(this.current());
  },

  themeFor(it) {
    const cat = this.category();
    let accent = it && it.accent, wallKey = it && it.wallKey;
    for (let d = this.stack.length - 1; d >= 0 && (!accent || !wallKey); d--) {
      const f = this.stack[d].folder;
      accent = accent || f.accent;
      wallKey = wallKey || f.wallKey;
    }
    return {
      accent: accent || cat.accent,
      walls: wallsFor(wallKey || cat.wallKey),
      heroes: it && it.heroes && it.heroes.length ? it.heroes : null,
      iconBlur: !!(it && it.iconBlur),
    };
  },

  /* The background, detail panel and bottom screen follow the selection once the menu has stopped
     moving: the main thread is shared by both screens, and work landing mid-slide drops frames. */
  focusChanged(now) {
    clearTimeout(this.ft);
    cancelAnimationFrame(this.fr);
    Panels.hints(this.current());
    if (now) return this.applyFocus();
    this.ft = setTimeout(() => this.applyFocus(), this.repeating ? 320 : 270);
  },

  applyFocus() {
    const it = this.current();
    Bg.setTheme(this.themeFor(it));
    Panels.detail(it);
    this.fr = requestAnimationFrame(() => { this.ft = setTimeout(() => Companion.sync(true), 0); });
  },

  // ---------- navigation ----------
  key(k, rep) {
    if (this.busy) return;
    this.repeating = rep;
    document.body.classList.toggle('fast', !!rep);
    clearTimeout(this.rt);
    this.rt = setTimeout(() => { this.repeating = false; document.body.classList.remove('fast'); }, 180);
    switch (k) {
      case 'up': return this.move(-1, rep);
      case 'down': return this.move(1, rep);
      case 'left': return this.stack.length ? this.back() : this.cat(-1);
      case 'right': {
        if (!this.stack.length) return this.cat(1);
        const it = this.current();
        return it && it.kind === 'folder' ? this.open(it) : null;
      }
      case 'ok': return this.activate();
      case 'back': return this.back();
      case 'options': return this.options();
      case 'l1': return this.move(-5, rep);
      case 'r1': return this.move(5, rep);
      case 'l2': return this.move(-9999, rep);
      case 'r2': return this.move(9999, rep);
      case 'start': return Media.openMusic();
      case 'playpause': return Media.control('toggle');
      case 'next': return Media.control('next');
      case 'prev': return Media.control('prev');
      default: return null;
    }
  },

  move(d, rep) {
    const items = this.list();
    const si = this.index();
    const ni = clamp(si + d, 0, items.length - 1);
    if (ni === si) {
      if (!rep) Sound.play('bump');
      return;
    }
    this.setIndex(ni);
    Sound.play('move');
    this.layout();
    this.focusChanged();
  },

  cat(d) {
    const ni = clamp(this.ci + d, 0, this.cats.length - 1);
    if (ni === this.ci) return;
    this.switchCat(ni, d > 0);
  },

  switchCat(ni, forward) {
    Sound.play('cat');
    const old = this.catCol;
    this.ci = ni;
    this.catCol = this.newCol(forward ? 'enter-r' : 'enter-l');
    this.dropCol(old, forward ? 'exit-l' : 'exit-r');
    this.layout();
    this.focusChanged();
  },

  async open(it) {
    if (this.busy) return;
    if (it.load && !it.children) {
      const e = this.visibleEl(it);
      if (e) e.classList.add('loading');
      this.busy = true;
      try {
        it.children = await it.load();
      } catch (err) {
        it.children = [infoItem("Couldn't open this folder", String(err && err.message || err), 'warn')];
      }
      this.busy = false;
      if (e) e.classList.remove('loading');
      if (this.current() !== it) return;
    }
    const children = it.children && it.children.length ? it.children : [infoItem('There are no items.', '', 'info')];
    Sound.play('open');
    const col = this.newCol('enter-r');
    this.stack.push({ folder: it, items: children, si: clamp(it._si || 0, 0, children.length - 1), col });
    this.layout();
    this.focusChanged();
  },

  back() {
    if (!this.stack.length) {
      Sound.play('bump');
      return;
    }
    const top = this.stack.pop();
    top.folder._si = top.si;
    Sound.play('back');
    this.dropCol(top.col, 'exit-r');
    this.layout();
    this.focusChanged();
  },

  activate() {
    const it = this.current();
    if (!it) return;
    if (it.kind === 'folder') return this.open(it);
    if (it.run) return it.run(it);
    Sound.play('bump');
  },

  options() {
    const it = this.current();
    const opts = it && it.options ? it.options() : null;
    if (!opts || !opts.length) {
      Sound.play('bump');
      return;
    }
    Sound.play('open');
    Panels.options(it.label, opts);
  },

  visibleEl(it) {
    const cols = [this.catCol, ...this.stack.map(s => s.col)];
    for (const c of cols) if (c.map.has(it)) return c.map.get(it);
    return null;
  },

  async goto(catId, path = []) {
    Overlay.closeAll();
    while (this.stack.length) {
      const t = this.stack.pop();
      this.dropCol(t.col, 'exit-r');
    }
    const ci = this.cats.findIndex(c => c.id === catId);
    if (ci >= 0 && ci !== this.ci) {
      const old = this.catCol;
      const forward = ci > this.ci;
      this.ci = ci;
      this.catCol = this.newCol(forward ? 'enter-r' : 'enter-l');
      this.dropCol(old, forward ? 'exit-l' : 'exit-r');
    }
    this.layout();
    for (const id of path) {
      const items = this.list();
      const idx = items.findIndex(x => x.id === id);
      if (idx < 0) break;
      this.setIndex(idx);
      this.layout();
      await this.open(items[idx]);
    }
    this.focusChanged();
  },

  /* Touch on the bottom screen: select an item of the current column, optionally activating it. */
  selectId(id, run) {
    const idx = this.list().findIndex(x => x.id === id);
    if (idx < 0) return Sound.play('bump');
    if (idx !== this.index()) {
      this.setIndex(idx);
      Sound.play('move');
      this.layout();
      this.focusChanged(true);
    }
    if (run) this.activate();
  },

  /* Opens the selected folder and selects one of its items. */
  async openChild(id, run) {
    const it = this.current();
    if (!it || it.kind !== 'folder') return;
    await this.open(it);
    if (this.stack.length && this.stack[this.stack.length - 1].folder === it) this.selectId(id, run);
  },

  /* Switches category from the bottom screen's tabs, leaving any open folders. */
  showCategory(id) {
    const ni = this.cats.findIndex(c => c.id === id);
    if (ni < 0) return;
    if (ni === this.ci) {
      if (this.stack.length) this.home();
      return;
    }
    while (this.stack.length) {
      const t = this.stack.pop();
      t.folder._si = t.si;
      this.dropCol(t.col, 'exit-r');
    }
    this.switchCat(ni, ni > this.ci);
  },

  home() {
    Overlay.closeAll();
    if (this.stack.length) {
      while (this.stack.length) {
        const t = this.stack.pop();
        this.dropCol(t.col, 'exit-r');
      }
      Sound.play('back');
      this.layout();
      this.focusChanged();
      return;
    }
    const gi = this.cats.findIndex(c => c.id === 'game');
    if (gi >= 0 && gi !== this.ci) this.switchCat(gi, gi > this.ci);
  },

  launchFx(fn) {
    const st = $('#stage');
    st.classList.add('launching');
    setTimeout(() => {
      fn();
      setTimeout(() => st.classList.remove('launching'), 1600);
    }, 260);
  },

  // ---------- touch ----------
  initTouch() {
    const stage = $('#stage');
    let sx = 0, sy = 0, down = false;
    stage.addEventListener('pointerdown', e => { sx = e.clientX; sy = e.clientY; down = true; Bg.poke(); });
    stage.addEventListener('pointerup', e => {
      if (!down) return;
      down = false;
      if (Overlay.top() || $('#boot:not(.out)')) return;
      const s = Stage.scale;
      const dx = (e.clientX - sx) / s, dy = (e.clientY - sy) / s;
      if (Math.abs(dx) > 90 || Math.abs(dy) > 90) {
        if (Math.abs(dx) > Math.abs(dy)) CB.key(dx < 0 ? 'right' : 'left', false);
        else CB.key(dy < 0 ? 'down' : 'up', false);
        return;
      }
      const cat = e.target.closest('.cat');
      if (cat && !this.stack.length) {
        if (cat._ci !== this.ci) this.switchCat(cat._ci, cat._ci > this.ci);
        return;
      }
      const itEl = e.target.closest('.it');
      if (!itEl) return;
      const it = itEl._item;
      if (this.stack.length && this.stack[this.stack.length - 1].items.indexOf(it) < 0) {
        this.back();
        return;
      }
      const idx = this.list().indexOf(it);
      if (idx < 0) return;
      if (idx === this.index()) this.activate();
      else {
        this.setIndex(idx);
        Sound.play('move');
        this.layout();
        this.focusChanged();
      }
    });
  },
};
