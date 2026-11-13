(function () {
  'use strict';

  const C = window.Coro;
  const TABS = ['apps', 'activity', 'crashes', 'inbox', 'settings', 'about'];
  const TITLES = { apps: 'Apps', activity: 'Activity', crashes: 'Crashes', inbox: 'Inbox', settings: 'Settings', about: 'About' };
  const QUIPS = {
    apps: 'Everything Grok Build made for the Thor. I keep an eye on all of it!',
    activity: "Builds, installs, crashes... I remember all of it.",
    crashes: "When something falls over, I write down why.",
    inbox: 'Drop APKs in Coronamon/inbox (or Download) and I can install them.',
    settings: 'How I hang out on your Thor.',
    about: "That's me! Coronamon, the little sun.",
  };
  let current = 'apps';
  const icons = {};

  function call(fn, ...args) {
    const r = JSON.parse(C[fn](...args));
    if (!r.ok) throw new Error(r.error || 'failed');
    return r.data;
  }

  function el(tag, attrs, ...kids) {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k === 'class') e.className = v;
      else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else e.setAttribute(k, v === true ? '' : v);
    }
    for (const k of kids.flat()) {
      if (k == null || k === false) continue;
      e.append(k instanceof Node ? k : document.createTextNode(String(k)));
    }
    return e;
  }

  function toast(msg) {
    const t = document.getElementById('toast');
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => (t.hidden = true), 3200);
  }

  function safe(fn, done) {
    try {
      const r = fn();
      if (done) toast(done);
      return r;
    } catch (e) {
      toast(e.message);
      return null;
    }
  }

  function ago(ms) {
    if (!ms) return '';
    const s = Math.round((Date.now() - ms) / 1000);
    if (s < 60) return 'just now';
    if (s < 3600) return Math.round(s / 60) + 'm ago';
    if (s < 86400) return Math.round(s / 3600) + 'h ago';
    return Math.round(s / 86400) + 'd ago';
  }

  function when(ms) {
    const d = new Date(ms);
    const today = new Date().toDateString() === d.toDateString();
    const hm = d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
    return today ? hm : d.toLocaleDateString([], { month: 'short', day: 'numeric' }) + ' ' + hm;
  }

  function size(b) {
    if (!b) return '';
    if (b > 1 << 30) return (b / (1 << 30)).toFixed(1) + ' GB';
    if (b > 1 << 20) return (b / (1 << 20)).toFixed(1) + ' MB';
    return Math.round(b / 1024) + ' KB';
  }

  function icon(pkg) {
    if (!(pkg in icons)) icons[pkg] = safe(() => call('icon', pkg)) || '';
    return icons[pkg] ? el('img', { src: icons[pkg], alt: '' }) : el('div', { class: 'noicon' });
  }

  // ---- views ----

  function apps() {
    const list = call('apps') || [];
    list.sort((a, b) => (b.installed - a.installed) || String(a.label).localeCompare(b.label));
    if (!list.length) return [el('div', { class: 'empty' }, 'No apps yet. Ask Grok Build to make one!')];
    return list.map((a) => {
      const badges = [];
      badges.push(el('span', { class: 'badge ' + (a.kind === 'built' ? 'warn' : 'info') }, a.kind === 'built' ? 'Grok Build' : 'Installed app'));
      if (!a.installed) badges.push(el('span', { class: 'badge bad' }, 'not installed'));
      if (a.installer === 'dev.grokbuild.coronamon') badges.push(el('span', { class: 'badge good' }, 'Coronamon updates it'));
      else if (a.installed) badges.push(el('span', { class: 'badge' }, 'installed by ' + (a.installer === 'adb' ? 'adb' : a.installer)));
      if (a.update && a.update.version) badges.push(el('span', { class: 'badge warn' }, 'update ' + a.update.version));
      if (a.lastCrash && Date.now() - a.lastCrash < 7 * 86400e3) {
        badges.push(el('span', { class: 'badge bad' }, (a.lastCrashKind === 'anr' ? 'froze ' : 'crashed ') + ago(a.lastCrash)));
      }
      const sub = [a.pkg, a.versionName && 'v' + a.versionName, a.lastUpdate && 'updated ' + ago(a.lastUpdate), size(a.apkSize)]
        .filter(Boolean).join('  ·  ');
      const actions = [];
      if (a.installed) actions.push(el('button', { onclick: () => safe(() => call('open', a.pkg)) }, 'Open'));
      if (a.update && a.update.url && a.updateMode !== 'notify') {
        actions.push(el('button', { class: 'primary', onclick: () => safe(() => call('update', a.pkg), 'Updating ' + a.label + '...') }, 'Update'));
      }
      if (a.github) actions.push(el('button', { onclick: () => safe(() => call('check', a.pkg), 'Checking ' + a.label + '...') }, 'Check'));
      if (a.installed) actions.push(el('button', { onclick: () => safe(() => call('info', a.pkg)) }, 'Info'));
      return el('div', { class: 'card' }, icon(a.pkg),
        el('div', { class: 'body' }, el('div', { class: 'name' }, a.label || a.pkg), el('div', { class: 'sub' }, sub), el('div', {}, badges)),
        el('div', { class: 'actions' }, actions));
    }).concat([el('div', { class: 'buttons' },
      el('button', { onclick: () => safe(() => call('check', ''), 'Checking every GitHub release...') }, 'Check all for updates'))]);
  }

  const KIND = {
    build: ['Build', 'info'], build_start: ['Build started', 'info'], install: ['Install', 'good'], install_start: ['Installing', 'info'],
    crash: ['Crash', 'bad'], native: ['Native crash', 'bad'], anr: ['Froze', 'bad'], update_found: ['Update found', 'warn'],
    update: ['Update', 'warn'], start: ['Coronamon woke up', ''], shizuku: ['Shizuku', 'info'], shizuku_down: ['Shizuku stopped', 'warn'],
  };

  function activity() {
    const ev = (call('history', 120) || []).slice().reverse();
    if (!ev.length) return [el('div', { class: 'empty' }, 'Nothing yet. It is very peaceful.')];
    return ev.map((e) => {
      const k = KIND[e.kind] || [e.kind, ''];
      let cls = k[1];
      if (e.ok === false) cls = 'bad';
      const title = k[0] + (e.ok === false && !/crash|froze/i.test(k[0]) ? ' failed' : '');
      return el('div', { class: 'event' }, el('div', { class: 'when' }, when(e.t)),
        el('div', { class: 'what' }, el('div', {}, el('span', { class: 'dot ' + cls }), title + (e.app ? ': ' + e.app : '') + (e.version ? ' ' + e.version : '')),
          e.msg ? el('div', { class: 'msg' }, e.msg) : null));
    });
  }

  function crashes() {
    const list = call('reports', '') || [];
    if (!list.length) return [el('div', { class: 'empty' }, "No crashes. Everything's running strong!")];
    return list.map((r) => el('div', { class: 'card' }, icon(r.pkg),
      el('div', { class: 'body' }, el('div', { class: 'name' }, (r.kind === 'anr' ? 'Froze: ' : 'Crashed: ') + r.pkg),
        el('div', { class: 'sub' }, r.headline), el('div', { class: 'sub' }, when(r.time))),
      el('div', { class: 'actions' }, el('button', { onclick: () => openReport(r) }, 'Read'))));
  }

  function openReport(r) {
    const text = safe(() => call('report', r.path));
    if (text == null) return;
    document.getElementById('modal-title').textContent = r.pkg + ' - ' + when(r.time);
    document.getElementById('modal-body').textContent = text;
    document.getElementById('modal').hidden = false;
    document.getElementById('modal-close').focus();
  }

  function inbox() {
    const list = call('inbox') || [];
    if (!list.length) return [el('div', { class: 'empty' }, 'No APKs waiting in Coronamon/inbox or Download.')];
    return list.map((f) => {
      const sub = [f.pkg, f.version && 'v' + f.version, f.installed && 'installed v' + f.installed, size(f.size), f.folder].filter(Boolean).join('  ·  ');
      return el('div', { class: 'card' }, f.pkg ? icon(f.pkg) : el('div', { class: 'noicon' }),
        el('div', { class: 'body' }, el('div', { class: 'name' }, f.label || f.name), el('div', { class: 'sub' }, sub)),
        el('div', { class: 'actions' }, el('button', { class: 'primary', disabled: !f.pkg,
          onclick: () => safe(() => call('install', f.path), 'Installing ' + (f.label || f.name) + '...') }, f.installed ? 'Update' : 'Install')));
    });
  }

  function seg(key, value, options) {
    return el('div', { class: 'seg' }, options.map(([v, label]) => el('button', {
      class: String(value) === String(v) ? 'on' : '',
      onclick: () => { safe(() => call('set', key, String(v))); render(); },
    }, label)));
  }

  function settings() {
    const s = call('settings');
    const st = safe(() => call('status')) || {};
    const rows = [
      ['Size', 'how big I am', seg('size', s.size, [[0.8, 'Small'], [1, 'Normal'], [1.25, 'Big']])],
      ['Chatter', 'how often I talk', seg('chatter', s.chatter, [[0, 'Quiet'], [1, 'Normal'], [2, 'Chatty']])],
      ['Games & videos', 'when one is on my screen', seg('games', s.games, [['move', 'Move away'], ['ghost', 'Ghost'], ['hide', 'Hide'], ['stay', 'Stay']])],
      ['Screen', 'where I like to be', seg('screen', s.screen, [['auto', 'Both'], ['top', 'Top'], ['bottom', 'Bottom']])],
      ['Night sleep', '11 PM to 7 AM', seg('nightSleep', s.nightSleep, [[true, 'On'], [false, 'Off']])],
      ['Updates', 'for apps with GitHub releases', seg('updates', s.updates, [['ask', 'Ask me'], ['auto', 'Automatic'], ['off', 'Off']])],
      ['Quote sources', 'show where a quote is from', seg('showSources', s.showSources, [[true, 'On'], [false, 'Off']])],
      ['On screen', '', seg('visible', s.visible, [[true, 'Out'], [false, 'In the den']])],
    ].map(([label, small, ctl]) => el('div', { class: 'row' }, el('div', { class: 'label' }, label, small ? el('small', {}, small) : null), ctl));
    const p = st.permissions || {};
    const missing = Object.entries(p).filter(([, v]) => !v).map(([k]) => k);
    return rows.concat([
      el('div', { class: 'buttons' },
        el('button', { onclick: () => safe(() => call('summon', 'top'), 'Coming up!') }, 'Call me to the top screen'),
        el('button', { onclick: () => safe(() => call('summon', 'bottom'), 'On my way down!') }, 'Call me to the bottom screen'),
        el('button', { onclick: () => { const r = safe(() => call('shizuku')); toast(r === 'granted' ? 'Shizuku already trusts me.' : r === 'stopped' ? 'Shizuku is not running. Run coro setup on the Mac.' : 'Check the Shizuku prompt!'); } }, 'Connect Shizuku'),
        el('button', { onclick: () => safe(() => call('reloadLines'), 'Reloaded my lines.') }, 'Reload my lines')),
      el('p', { class: 'sub', style: 'color:var(--muted);margin-top:10px' },
        'Shizuku: ' + (st.shizuku || '?') + '.  App watching: ' + (st.foregroundWorking ? 'working' : 'off') + '.  ' +
        (missing.length ? 'Missing permissions: ' + missing.join(', ') + ' (run coro setup on the Mac).' : 'All permissions granted.')),
    ]);
  }

  function about() {
    const data = safe(() => call('lines')) || { lines: {}, sources: {} };
    const counts = {};
    for (const list of Object.values(data.lines)) for (const [src] of list) counts[src] = (counts[src] || 0) + 1;
    const st = safe(() => call('status')) || {};
    const src = Object.entries(counts).sort((a, b) => b[1] - a[1]).map(([k, n]) =>
      el('p', {}, el('b', {}, n + ' lines: '), data.sources[k] || (k ? k : 'Original lines written in his voice for this app')));
    return [el('div', { class: 'prose' },
      el('p', {}, "Coronamon lives on your Thor and looks after the apps Grok Build makes for it. He watches installs, updates and crashes, keeps reports, and wanders between the screens when he isn't busy."),
      el('p', {}, 'Tap him to say hi, hold to pet him, drag to carry him, or fling him at the other screen. He moves out of the way of games and videos.'),
      el('h3', {}, 'Where his words come from'),
      src,
      el('p', {}, 'Add your own in ', el('code', {}, 'Coronamon/lines.json'), ' on the Thor\'s storage.'),
      el('h3', {}, 'From the Mac'),
      el('p', {}, el('code', {}, '~/thor-coronamon/coro status'), '  ', el('code', {}, 'coro install app.apk'), '  ', el('code', {}, 'coro say "hi"'), '  ', el('code', {}, 'coro act dance')),
      el('p', { style: 'color:var(--muted)' }, 'Version ' + (st.version || '?') + '. Coronamon and Digimon belong to Bandai Namco; this is a fan-made companion for personal use.'))];
  }

  const VIEWS = { apps, activity, crashes, inbox, settings, about };

  function render() {
    const view = document.getElementById('view');
    const focused = document.activeElement && document.activeElement.textContent;
    const scroll = view.scrollTop;
    view.replaceChildren();
    try {
      view.append(...VIEWS[current]());
    } catch (e) {
      view.append(el('div', { class: 'empty' }, e.message));
    }
    view.scrollTop = scroll;
    document.getElementById('title').textContent = TITLES[current];
    document.getElementById('quip').textContent = QUIPS[current];
    for (const t of document.querySelectorAll('.tab')) t.classList.toggle('active', t.dataset.tab === current);
    if (focused) {
      const again = [...view.querySelectorAll('button')].find((b) => b.textContent === focused && b.classList.contains('on'));
      if (again) again.focus();
    }
  }

  function show(tab) {
    current = tab;
    document.getElementById('view').scrollTop = 0;
    render();
  }

  // ---- gamepad and d-pad focus ----

  function focusables() {
    const modal = document.getElementById('modal');
    const root = modal.hidden ? document : modal;
    return [...root.querySelectorAll('button:not([disabled])')].filter((b) => b.offsetParent !== null);
  }

  function move(dir) {
    const all = focusables();
    const cur = document.activeElement;
    if (!all.includes(cur)) {
      (all.find((b) => b.classList.contains('active')) || all[0]).focus();
      return;
    }
    const a = cur.getBoundingClientRect();
    const ax = a.left + a.width / 2, ay = a.top + a.height / 2;
    let best = null, bestScore = Infinity;
    for (const b of all) {
      if (b === cur) continue;
      const r = b.getBoundingClientRect();
      const bx = r.left + r.width / 2, by = r.top + r.height / 2;
      const dx = bx - ax, dy = by - ay;
      const along = dir === 'left' ? -dx : dir === 'right' ? dx : dir === 'up' ? -dy : dy;
      const across = dir === 'left' || dir === 'right' ? Math.abs(dy) : Math.abs(dx);
      if (along <= 2) continue;
      const score = along + across * 2.2;
      if (score < bestScore) { bestScore = score; best = b; }
    }
    if (best) {
      best.focus();
      best.scrollIntoView({ block: 'nearest' });
    }
  }

  document.addEventListener('keydown', (e) => {
    const dirs = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' };
    if (dirs[e.key]) {
      e.preventDefault();
      move(dirs[e.key]);
    }
  });

  document.getElementById('modal-close').addEventListener('click', () => { document.getElementById('modal').hidden = true; });
  for (const t of document.querySelectorAll('.tab')) t.addEventListener('click', () => show(t.dataset.tab));

  window.den = {
    tab(d) {
      const i = (TABS.indexOf(current) + d + TABS.length) % TABS.length;
      show(TABS[i]);
      document.querySelector('.tab.active').focus();
    },
    back() {
      const modal = document.getElementById('modal');
      if (!modal.hidden) {
        modal.hidden = true;
        return true;
      }
      return false;
    },
    refresh() {
      render();
    },
  };

  const avatar = safe(() => call('avatar', 'wave'));
  if (avatar) document.getElementById('avatar').src = avatar;
  show('apps');
  const first = document.querySelector('.tab.active');
  if (first) first.focus();
  setInterval(() => { if (document.getElementById('modal').hidden && current !== 'settings') render(); }, 15000);
})();
