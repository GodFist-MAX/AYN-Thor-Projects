'use strict';
/* Overlay stack, detail panel, hints, options menu, dialogs and toasts. */

const Overlay = {
  stack: [],
  push(o) { if (!this.stack.includes(o)) this.stack.push(o); },
  pop(o) {
    const i = this.stack.lastIndexOf(o);
    if (i >= 0) this.stack.splice(i, 1);
  },
  top() { return this.stack[this.stack.length - 1]; },
  closeAll() { while (this.stack.length) this.stack[this.stack.length - 1].close(true); },
};

/* PS3-style side menu used for options and choices. */
const Opts = {
  items: [],
  si: 0,
  init() {
    this.el = $('#opts');
    this.title = $('#opts h3');
    this.list = $('#opts .list');
    this.list.addEventListener('click', e => {
      const o = e.target.closest('.o');
      if (!o) return;
      this.si = Number(o.dataset.i);
      this.render();
      this.key('ok');
    });
  },
  open(title, items, si = 0) {
    this.items = items;
    this.si = clamp(si, 0, items.length - 1);
    this.title.textContent = title;
    this.list.innerHTML = '<div class="rows">' + items.map((o, i) =>
      `<div class="o${o.disabled ? ' off' : ''}" data-i="${i}"><span>${esc(o.label)}</span>${o.checked != null ? `<span class="chk">${o.checked ? '✓' : ''}</span>` : ''}</div>`).join('') + '</div>';
    this.rows = this.list.firstChild;
    this.render();
    this.el.classList.add('on');
    Overlay.push(this);
  },
  render() {
    [...this.rows.children].forEach((e, i) => e.classList.toggle('sel', i === this.si));
    const visible = 10;
    const top = clamp(this.si - Math.floor(visible / 2), 0, Math.max(0, this.items.length - visible));
    this.rows.style.transform = `translateY(${-top * 74}px)`;
    Companion.sync();
  },
  close(silent) {
    this.el.classList.remove('on');
    Overlay.pop(this);
    if (!silent) Sound.play('back');
    Companion.sync();
  },
  pick(i) {
    if (!this.items[i]) return;
    this.si = i;
    this.render();
    this.key('ok');
  },
  key(k) {
    if (k === 'up' || k === 'down') {
      const ni = clamp(this.si + (k === 'up' ? -1 : 1), 0, this.items.length - 1);
      if (ni !== this.si) {
        this.si = ni;
        Sound.play('move');
        this.render();
      }
    } else if (k === 'ok') {
      const o = this.items[this.si];
      if (!o || o.disabled) return Sound.play('bump');
      this.close(true);
      Sound.play('ok');
      if (o.run) o.run();
    } else if (k === 'back' || k === 'options' || k === 'left') {
      this.close();
    }
  },
};

/* Centered dialog: alerts, confirmations and text input. */
const Dialog = {
  init() {
    this.el = $('#dialog');
    this.box = $('#dialog .box');
    this.box.addEventListener('click', e => {
      const b = e.target.closest('.btn');
      if (!b) return;
      this.bi = Number(b.dataset.i);
      this.press();
    });
  },
  open({ title, text, input, value, hint, buttons, onClose }) {
    this.buttons = buttons || [{ label: 'OK' }];
    this.onClose = onClose;
    this.bi = 0;
    this.box.innerHTML = `<h2>${esc(title || '')}</h2>${text ? `<p>${esc(text)}</p>` : ''}` +
      (input ? `<input type="${input === 'secret' ? 'password' : 'text'}" spellcheck="false" autocomplete="off" placeholder="${esc(hint || '')}">` : '') +
      `<div class="btns">${this.buttons.map((b, i) => `<div class="btn" data-i="${i}">${esc(b.label)}</div>`).join('')}</div>`;
    this.input = $('input', this.box);
    if (this.input) {
      this.input.value = value || '';
      this.input.addEventListener('keydown', e => {
        if (e.key === 'Enter') {
          e.preventDefault();
          this.bi = 0;
          this.press();
        }
      });
    }
    this.render();
    this.el.classList.add('on');
    Overlay.push(this);
    if (this.input) {
      Native.setTextMode(true);
      setTimeout(() => {
        this.input.focus({ preventScroll: true });
        this.input.setSelectionRange(this.input.value.length, this.input.value.length);
      }, 80);
    }
  },
  render() {
    [...this.box.querySelectorAll('.btn')].forEach((b, i) => b.classList.toggle('sel', i === this.bi));
    Companion.sync();
  },
  close(silent) {
    if (!this.el.classList.contains('on')) return;
    this.el.classList.remove('on');
    Overlay.pop(this);
    if (this.input) {
      this.input.blur();
      Native.setTextMode(false);
    }
    if (!silent) Sound.play('back');
    Companion.sync();
  },
  pick(i) {
    if (!this.buttons[i]) return;
    this.bi = i;
    this.press();
  },
  press() {
    const b = this.buttons[this.bi];
    const val = this.input ? this.input.value : undefined;
    this.close(true);
    Sound.play(b && b.cancel ? 'back' : 'ok');
    if (b && b.run) b.run(val);
  },
  key(k) {
    if (k === 'left' || k === 'right') {
      const ni = clamp(this.bi + (k === 'left' ? -1 : 1), 0, this.buttons.length - 1);
      if (ni !== this.bi) {
        this.bi = ni;
        Sound.play('move');
        this.render();
      }
    } else if (k === 'ok') {
      this.press();
    } else if (k === 'back') {
      const ci = this.buttons.findIndex(b => b.cancel);
      if (ci >= 0) {
        this.bi = ci;
        this.press();
      } else this.close();
    }
  },
};

const Panels = {
  init() {
    Opts.init();
    Dialog.init();
    this.detailEl = $('#detail');
    this.shade = $('#detail-shade');
    this.hintsEl = $('#hints');
    this.toastEl = $('#toast');
  },

  detail(it) {
    const d = it && it.detail;
    const p = this.detailEl;
    const key = d ? JSON.stringify([it.id, d.title, d.meta, d.desc, d.action, (d.cards || []).length]) : '';
    if (key === this.detailKey) return;
    this.detailKey = key;
    cancelAnimationFrame(this.dr);
    this.shade.classList.toggle('on', !!d);
    if (!d) {
      p.style.transition = '';
      p.classList.remove('on');
      return;
    }
    p.style.transition = 'none';
    p.classList.remove('on');
    const meta = (d.meta || []).filter(Boolean).map(m => `<span>${esc(m)}</span>`).join('');
    const cards = (d.cards || []).filter(c => c && c.img).map(c => `<div class="card" style="background-image:url('${c.img.replace(/['"()\\]/g, encodeURIComponent)}')"></div>`).join('');
    p.innerHTML = `<div class="title">${esc(d.title)}</div>` +
      (meta ? `<div class="meta">${meta}</div>` : '') +
      (d.desc ? `<div class="desc">${esc(d.desc)}</div>` : '') +
      (d.action ? `<div class="actions"><div class="pill primary"><span class="k">A</span>${esc(d.action)}</div>${it.options ? '<div class="pill"><span class="k">Y</span>Options</div>' : ''}</div>` : '') +
      (cards ? `<div class="cards">${cards}</div>` : '');
    (d.cards || []).filter(c => c && c.img).forEach((c, i) => {
      const img = new Image();
      img.onload = () => {
        const el = p.querySelectorAll('.card')[i];
        if (el && this.detailKey === key) el.classList.toggle('stacked', isStacked(img));
      };
      img.src = c.img;
    });
    if (d.logo) {
      const img = new Image();
      img.className = 'logo';
      img.onload = () => {
        const t = $('.title', p);
        if (t && this.detailKey === key) t.replaceWith(img);
      };
      img.src = d.logo;
    }
    // Hidden for one frame, then faded in: no forced layout in the middle of navigation.
    this.dr = requestAnimationFrame(() => {
      this.dr = requestAnimationFrame(() => {
        p.style.transition = '';
        p.classList.add('on');
      });
    });
  },

  hints(it) {
    const parts = [];
    if (it && it.kind === 'folder') parts.push(['A', 'Open']);
    else if (it && it.run) parts.push(['A', (it.detail && it.detail.action) || 'Select']);
    if (XMB.stack.length) parts.push(['B', 'Back']);
    if (it && it.options) parts.push(['Y', 'Options']);
    parts.push(['☰', 'Music']);
    const html = parts.map(([k, l]) => `<span class="h"><span class="k">${esc(k)}</span>${esc(l)}</span>`).join('');
    if (html !== this.hintsHtml) this.hintsEl.innerHTML = this.hintsHtml = html;
  },

  options(title, items) {
    const si = Math.max(0, items.findIndex(o => o.checked));
    Opts.open(title, items, si);
  },

  choose(title, choices, cb) {
    const si = Math.max(0, choices.findIndex(c => c.checked));
    Opts.open(title, choices.map(c => ({ label: c.label, checked: !!c.checked, run: () => cb(c.value) })), si);
  },

  alert(title, text) {
    Sound.play('open');
    Dialog.open({ title, text, buttons: [{ label: 'OK' }] });
  },

  confirm(title, text, onYes, yesLabel = 'Yes') {
    Sound.play('open');
    Dialog.open({ title, text, buttons: [{ label: yesLabel, run: onYes }, { label: 'Cancel', cancel: true }] });
  },

  error(msg) {
    Sound.play('error');
    Dialog.open({ title: 'Error', text: String(msg), buttons: [{ label: 'OK' }] });
  },

  input(title, hint, value, cb, secret) {
    Sound.play('open');
    Dialog.open({
      title, text: hint, input: secret ? 'secret' : 'text', value, hint: '',
      buttons: [{ label: 'Save', run: v => cb(v || '') }, { label: 'Cancel', cancel: true }],
    });
  },

  toast(msg, g = 'info') {
    const t = this.toastEl;
    t.innerHTML = `<div class="ti">${miniGlyph(g)}</div><div class="tm">${esc(msg)}</div>`;
    t.classList.add('on');
    Sound.play('toast');
    clearTimeout(this.tt);
    this.tt = setTimeout(() => t.classList.remove('on'), 3400);
  },
};
