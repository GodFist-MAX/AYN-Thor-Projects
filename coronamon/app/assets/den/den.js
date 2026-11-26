(function () {
  'use strict';

  const C = window.Coro;
  const TABS = ['apps', 'talk', 'activity', 'bugs', 'grok', 'inbox', 'settings', 'about'];
  const TITLES = { apps: 'Apps', talk: 'Talk', activity: 'Activity', bugs: 'Bugs', grok: 'Grok Build', inbox: 'Inbox', settings: 'Settings', about: 'About' };
  const QUIPS = {
    apps: 'Everything Grok Build made for the Thor. I keep an eye on all of it!',
    talk: 'Type to me here, or double-tap me and talk out loud!',
    activity: "Builds, installs, crashes... I remember all of it.",
    bugs: "Anything that breaks goes to Grok next time we're plugged into the Mac.",
    grok: 'I can ask the Mac to check on Grok Build, pause it or stop it.',
    inbox: 'Drop APKs in Coronamon/inbox (or Download) and I can install them.',
    settings: 'How I hang out on your Thor.',
    about: "That's me! Coronamon, the little sun.",
  };
  let current = 'apps';
  const icons = {};

  function call(fn, ...args) {
    const r = JSON.parse(C[fn](...args));
    if (!r.ok) throw new Error((r.error || 'failed').replace(/^[\w.$]+(Exception|Error): /, ''));
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
    issue: ['Problem noted', 'warn'], report: ['You reported a problem', 'warn'], bugs_sent: ['Sent to Grok', 'info'],
    bug_fixed: ['Bug fixed', 'good'], published: ['Put on GitHub', 'good'],
    grok_connected: ['Grok Build connected', 'good'], grok_away: ['Grok Build not ready', 'warn'],
    grok_fixing: ['Grok Build is fixing', 'info'], grok_done: ['Grok Build finished', 'good', 'Grok Build stopped early'],
    grok_stopped: ['Grok Build stopped', 'info'],
    mac_control: ['Asked the Mac', 'info', "The Mac couldn't do it"], diagnostics: ['Diagnostics', 'info', 'Diagnostics found problems'],
    brain: ['Grok brain', 'good'],
  };

  const GROK = { connected: ['Connected on the Mac', 'good'], away: ['Not ready on the Mac', 'warn'], fixing: ['Fixing things now', 'info'] };

  function grokNow() {
    const g = (safe(() => call('status')) || {}).grok;
    if (!g || !GROK[g.state]) return null;
    const [label, cls] = GROK[g.state];
    return el('div', { class: 'event' }, el('div', { class: 'when' }, 'Grok Build'),
      el('div', { class: 'what' }, el('div', {}, el('span', { class: 'dot ' + cls }), label + (g.since ? '  ·  ' + ago(g.since) : '')),
        g.msg ? el('div', { class: 'msg' }, g.msg) : null));
  }

  function activity() {
    const ev = (call('history', 120) || []).slice().reverse();
    const now = grokNow();
    if (!ev.length) return [now, el('div', { class: 'empty' }, 'Nothing yet. It is very peaceful.')].filter(Boolean);
    return [now].filter(Boolean).concat(ev.map((e) => {
      const k = KIND[e.kind] || [e.kind, ''];
      let cls = k[1];
      if (e.ok === false) cls = 'bad';
      const title = e.ok === false && k[2] ? k[2] : k[0] + (e.ok === false && !/crash|froze/i.test(k[0]) ? ' failed' : '');
      return el('div', { class: 'event' }, el('div', { class: 'when' }, when(e.t)),
        el('div', { class: 'what' }, el('div', {}, el('span', { class: 'dot ' + cls }), title + (e.app ? ': ' + e.app : '') + (e.version ? ' ' + e.version : '')),
          e.msg ? el('div', { class: 'msg' }, e.msg) : null));
    }));
  }

  const BUGKIND = {
    crash: 'Crash', anr: 'Froze', native: 'Native crash', renderer: 'Page crash', js: 'Script error',
    launch: "Couldn't start", freeze: 'Froze', error: 'Problem', report: 'You reported it',
  };

  function bugs() {
    const d = call('bugs') || { bugs: [], waiting: 0, lastSync: 0 };
    const status = d.waiting
      ? d.waiting + (d.waiting === 1 ? ' bug is' : ' bugs are') + ' waiting for Grok. Plug the Thor into the Mac to send.'
      : d.lastSync ? 'Grok has everything (last plugged in ' + ago(d.lastSync) + ').' : 'Nothing waiting for Grok.';
    const bar = el('div', { class: 'bugbar' }, el('button', { class: 'primary', onclick: startReport }, 'Report a problem'),
      el('span', { class: 'sub' }, status));
    if (!d.bugs.length) return [bar, el('div', { class: 'empty' }, "No bugs. Everything's running strong!")];
    return [bar].concat(d.bugs.map(bugCard));
  }

  function bugCard(b) {
    const badges = [];
    if (b.state === 'fixed') badges.push(el('span', { class: 'badge good' }, b.fixedIn ? 'fixed in ' + b.fixedIn : 'fixed'));
    else if (b.state === 'ignored') badges.push(el('span', { class: 'badge' }, 'ignored'));
    else badges.push(el('span', { class: 'badge bad' }, b.reopened ? 'came back' : 'open'));
    badges.push(b.rev > b.syncedRev ? el('span', { class: 'badge warn' }, 'waiting for Grok') : el('span', { class: 'badge info' }, 'Grok has it'));
    if (b.issue) badges.push(el('span', { class: 'badge info' }, 'GitHub #' + b.issue));
    if (b.external) badges.push(el('span', { class: 'badge' }, 'not made by Grok Build'));
    const last = (b.versions || []).slice(-1)[0];
    const sub = [BUGKIND[b.kind] || b.kind, b.count > 1 && b.count + ' times', last && 'v' + last, 'last ' + ago(b.last)]
      .filter(Boolean).join('  ·  ');
    const actions = [el('button', { onclick: () => openBug(b) }, 'Read')];
    if (b.state === 'open') {
      actions.push(el('button', { onclick: () => setBug(b, 'fixed', 'Marked fixed.') }, 'Fixed'));
      actions.push(el('button', { onclick: () => setBug(b, 'ignored', 'Ignoring that one.') }, 'Ignore'));
    } else {
      actions.push(el('button', { onclick: () => setBug(b, 'open', 'Opened it again.') }, 'Reopen'));
    }
    return el('div', { class: 'card' }, b.pkg ? icon(b.pkg) : el('div', { class: 'noicon' }),
      el('div', { class: 'body' }, el('div', { class: 'name' }, b.app || 'The Thor'), el('div', { class: 'sub' }, b.title),
        el('div', { class: 'sub' }, sub), el('div', {}, badges)),
      el('div', { class: 'actions' }, actions));
  }

  function setBug(b, state, done) {
    if (safe(() => call('setBugState', b.id, state), done)) render();
  }

  function openBug(b) {
    const text = safe(() => call('bugText', b.id));
    if (text == null) return;
    document.getElementById('modal-title').textContent = (b.app || 'The Thor') + ' - ' + b.title;
    document.getElementById('modal-body').textContent = text;
    document.getElementById('modal').hidden = false;
    document.getElementById('modal-close').focus();
  }

  // ---- problem report form ----

  const CATEGORIES = ['Crashed or closed', 'Froze or slow', 'Looks wrong', "Doesn't work right", 'Sound or controls', 'Something else'];
  let report = null;

  function startReport() {
    toast('Taking pictures of the screens...');
    setTimeout(() => openReportForm(safe(() => call('startReport')) || {}), 60);
  }

  function reportApps(cap) {
    const reg = safe(() => call('apps')) || [];
    const byPkg = {};
    for (const a of reg) byPkg[a.pkg] = a;
    const out = [], seen = new Set();
    const screens = cap.screens || {}, labels = cap.labels || {};
    for (const where of ['top', 'bottom']) {
      const p = screens[where];
      if (!p || seen.has(p) || p === 'dev.grokbuild.coronamon') continue;
      seen.add(p);
      const a = byPkg[p];
      out.push({ pkg: p, label: (a && a.label) || labels[p] || p, where: where + ' screen', built: !!(a && a.kind === 'built') });
    }
    for (const a of reg) {
      if (a.kind !== 'built' || !a.installed || seen.has(a.pkg)) continue;
      seen.add(a.pkg);
      out.push({ pkg: a.pkg, label: a.label, built: true });
    }
    out.push({ pkg: '', label: 'The Thor itself', built: false });
    return out;
  }

  function openReportForm(cap) {
    const apps = reportApps(cap);
    const thumbs = (cap.shots || []).map((p) => safe(() => call('thumb', p)) || '').filter(Boolean);
    report = { capture: cap, apps, thumbs, pkg: apps[0].pkg, category: '', text: '', shots: true, github: false };
    document.getElementById('report').hidden = false;
    renderReport();
    const first = document.querySelector('#report-body button');
    if (first) first.focus();
  }

  function closeReportForm(discard) {
    if (!report) return;
    if (discard && report.capture && report.capture.dir) safe(() => call('discardReport', JSON.stringify(report.capture)));
    report = null;
    document.getElementById('report').hidden = true;
  }

  function choice(options, value, pick) {
    return el('div', { class: 'seg' }, options.map(([v, label]) => el('button', {
      class: v === value ? 'on' : '', onclick: () => { pick(v); renderReport(); },
    }, label)));
  }

  function field(label, ...kids) {
    return el('div', { class: 'field' }, el('div', { class: 'label' }, label), kids);
  }

  function renderReport() {
    const r = report;
    if (!r) return;
    const body = document.getElementById('report-body');
    const focused = document.activeElement && document.activeElement.textContent;
    const app = r.apps.find((a) => a.pkg === r.pkg) || r.apps[0];
    if (!app.built) r.github = false;
    const text = el('textarea', { id: 'r-text', rows: 3, placeholder: 'What were you doing, and what happened?' });
    text.value = r.text;
    text.addEventListener('input', () => { r.text = text.value; });
    text.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !text.value) { e.preventDefault(); C.keyboard(); }
    });
    const parts = [
      field('Which app?', choice(r.apps.map((a) => [a.pkg, a.label + (a.where ? ' (' + a.where + ')' : '')]), r.pkg, (v) => { r.pkg = v; })),
      field('What went wrong?', choice(CATEGORIES.map((c) => [c, c]), r.category, (v) => { r.category = v; })),
      field('Tell Grok about it', text),
    ];
    if (r.thumbs.length) {
      parts.push(field('Screenshots', el('div', { class: 'shots' }, r.thumbs.map((src) => el('img', { src, alt: '' }))),
        choice([[true, 'Include them'], [false, 'Leave them out']], r.shots, (v) => { r.shots = v; }),
        el('div', { class: 'note' }, 'Screenshots and logs only go to Grok on the Mac. They are never posted on GitHub.')));
    } else if (r.capture.shizuku === false) {
      parts.push(field('Screenshots', el('div', { class: 'note' }, 'Connect Shizuku (Settings tab) and I can add screenshots and logs.')));
    }
    if (app.built) {
      parts.push(field('GitHub', choice([[false, 'Just tell Grok'], [true, 'Also post it on GitHub']], r.github, (v) => { r.github = v; }),
        el('div', { class: 'note' }, 'GitHub issues are public: the app, its version, the kind of problem and what you wrote (with personal details taken out).')));
    }
    body.replaceChildren(...parts);
    if (focused) {
      const again = [...body.querySelectorAll('button')].find((b) => b.textContent === focused);
      if (again) again.focus();
    }
  }

  function sendReport() {
    const r = report;
    if (!r) return;
    const text = r.text.trim();
    if (!text && !r.category) {
      toast('Pick what went wrong, or write a few words for Grok.');
      return;
    }
    const b = safe(() => call('submitReport', JSON.stringify({
      pkg: r.pkg, category: r.category || 'Something else', text, capture: r.capture, shots: r.shots, github: r.github,
    })));
    if (!b) return;
    report = null;
    document.getElementById('report').hidden = true;
    toast("Saved! Grok gets it next time the Thor is plugged into the Mac.");
    show('bugs');
  }

  // ---- the Mac: Grok Build's status, and buttons that run coro there ----

  const MACDO = {
    status: 'Check status', hold: 'Pause runs 1 hour', resume: 'Resume runs', off: 'Turn runs off', on: 'Turn runs on',
    'stop-run': 'Stop current run', 'watch-stop': 'Stop watcher', diagnostics: 'Mac diagnostics',
  };
  const REQ = {
    waiting: ['Waiting for the Mac...', 'info'], working: ['The Mac is on it...', 'info'], done: [null, 'good'],
    failed: [null, 'bad'], expired: [null, 'warn'],
  };
  const MACNOTE = {
    waiting: 'Waiting for the Mac to pick this up...', working: 'The Mac is checking...',
    skipped: 'Not plugged into the Mac, so only the Thor was checked.',
  };
  let grokBusy = false, grokPoll = 0, diagScroll = false, confirmKey = null;

  function plural(n, word) {
    return n + ' ' + word + (n === 1 ? '' : 's');
  }

  function clock(ms) {
    return new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  }

  function reachable(m) {
    const u = m.usb || {};
    return !u.known || (u.connected && u.adb);
  }

  function pendingReq(r) {
    return r.state === 'waiting' || r.state === 'working';
  }

  function askMac(action, arg) {
    const r = safe(() => call('control', action, arg == null ? '' : String(arg)));
    if (r) toast(Date.now() - r.t < 2000 ? 'Asking the Mac...' : 'Already asked. Waiting for the Mac...');
    render();
  }

  /** Asks the Mac how things are when the page opens, unless it said so in the last 30 seconds. */
  function freshen() {
    const m = safe(() => call('mac'));
    if (!m || !reachable(m) || (m.statusAt && Date.now() - m.statusAt < 30e3)) return;
    if ((m.requests || []).some((r) => r.action === 'status' && pendingReq(r))) return;
    safe(() => call('control', 'status', 'auto'));
  }

  function confirmThen(title, text, yes, fn) {
    const box = document.getElementById('confirm');
    document.getElementById('confirm-title').textContent = title;
    document.getElementById('confirm-text').textContent = text;
    const ok = document.getElementById('confirm-yes');
    ok.textContent = yes;
    ok.onclick = () => { closeConfirm(); fn(); };
    confirmKey = document.activeElement && document.activeElement.dataset ? document.activeElement.dataset.k : null;
    box.hidden = false;
    document.getElementById('confirm-no').focus();
  }

  function closeConfirm() {
    document.getElementById('confirm').hidden = true;
    refocus(confirmKey);
  }

  function refocus(key) {
    const view = document.getElementById('view');
    const again = view.querySelector('[data-k="' + key + '"]:not([disabled])') || view.querySelector('.buttons button:not([disabled])');
    if (again) again.focus();
  }

  function runDiagnostics() {
    if (((safe(() => call('mac')) || {}).diag || {}).running) {
      toast('Still checking...');
      return;
    }
    const d = safe(() => call('diagnostics'));
    if (!d) return;
    toast(d.macState === 'skipped' ? 'Checking the Thor. Plug into the Mac to check it too.' : 'Checking the Thor and asking the Mac...');
    diagScroll = true;
    render();
  }

  function linkLine(m, pending) {
    const u = m.usb || {};
    const heard = m.statusAt ? ago(m.statusAt) : '';
    if (u.known && !u.connected) return ['', 'Not plugged into the Mac' + (heard ? '  ·  last heard ' + heard : '')];
    if (u.known && !u.adb) return ['warn', "Plugged in, but USB debugging is off, so the Mac can't reach me"];
    if (m.status && Date.now() - m.statusAt < 4 * 60e3) return ['good', 'Connected to the Mac  ·  updated ' + heard];
    if (pending) return ['info', 'Waiting for the Mac to answer...'];
    return ['warn', "Plugged in, but the Mac's watcher hasn't answered" + (heard ? ' since ' + when(m.statusAt) : ' yet')];
  }

  function statusPanel(st) {
    const g = st.grok || {}, af = st.autofix || {}, w = st.watcher || {};
    const rows = [['Grok Build', g.ok ? plural(g.sessions, 'session') + ' open, signed in, online' : g.why || 'Unknown', g.ok ? 'good' : 'warn']];
    if (g.busy) rows.push(['In use', plural(g.busy, 'session') + ' active in the last 10 minutes, so runs wait', 'info']);
    const runs = !af.on ? ['Off', 'warn'] : af.holdUntil ? ['Paused until ' + clock(af.holdUntil), 'info'] : ['On', 'good'];
    rows.push(['Automatic runs', runs[0] + '  ·  ' + af.today + ' of ' + af.perDay + ' in the last day', runs[1]]);
    if (af.current) {
      rows.push(['Running now', af.current.what + ', since ' + clock(af.current.started) + (af.current.stopping ? '  ·  stopping...' : ''), 'info']);
    } else if (af.waiting) {
      rows.push(['Waiting', af.waiting, 'info']);
    }
    const next = af.next || [];
    rows.push(['Next run', next.length ? next.join(', ') + (af.nextMore ? ' and ' + af.nextMore + ' more' : '') : 'Nothing new to fix', '']);
    const last = (af.runs || [])[0];
    if (last) rows.push(['Last run', when(last.started) + (last.test ? '  ·  test' : '') + '  ·  ' + last.summary, last.ok ? 'good' : last.stopped ? 'warn' : 'bad']);
    if (w.pid) {
      rows.push(['Watcher', 'Running' + (w.since ? ' since ' + when(w.since) : '') + (w.agent === 'loaded' ? ', starts at login' : ", won't restart by itself"),
        w.agent === 'loaded' ? 'good' : 'warn']);
    } else {
      rows.push(['Watcher', (w.stopped ? 'Stopped from here ' + ago(w.stopped) + '.' : 'Not running.') +
        ' It starts again when you log in to the Mac, or run coro watch there.', 'bad']);
    }
    return el('div', { class: 'panel' }, rows.map(([k, v, cls]) => el('div', { class: 'kv' },
      el('div', { class: 'k' }, el('span', { class: 'dot ' + cls }), k), el('div', { class: 'v' }, v))));
  }

  function requestList(reqs) {
    const recent = reqs.filter((r) => !(r.action === 'status' && r.arg === 'auto') && Date.now() - r.t < 5 * 60e3).slice(0, 4);
    if (!recent.length) return null;
    return el('div', { class: 'reqs' }, recent.map((r) => {
      const [text, cls] = REQ[r.state] || ['', ''];
      return el('div', { class: 'req' }, el('span', { class: 'dot ' + cls }), el('b', {}, MACDO[r.action] || r.action),
        '  ' + (text || r.msg || ''), el('span', { class: 'ago' }, ago(r.t)));
    }));
  }

  function checkRows(list) {
    return list.map((c) => el('div', { class: 'check' }, el('span', { class: 'dot ' + (c.state || '') }),
      el('div', { class: 'what' }, el('b', {}, c.name), '  ' + (c.detail || ''), c.fix ? el('div', { class: 'fix' }, c.fix) : null)));
  }

  function diagView(d) {
    const look = (d.thor || []).concat(d.mac || []).filter((c) => c.state === 'warn' || c.state === 'bad');
    const busy = d.running || d.macState === 'waiting' || d.macState === 'working';
    const head = busy ? 'checking...' : look.length ? plural(look.length, 'thing') + ' to look at' : 'everything looks good';
    return el('div', { class: 'diag' },
      el('h3', {}, 'Diagnostics  ·  ' + when(d.started) + '  ·  ' + head),
      el('div', { class: 'diaghead' }, 'On the Thor'),
      d.thor ? checkRows(d.thor) : el('div', { class: 'note' }, 'Checking...'),
      el('div', { class: 'diaghead' }, 'On the Mac'),
      d.mac ? checkRows(d.mac) : el('div', { class: 'note' }, MACNOTE[d.macState] || d.macMsg || "The Mac didn't answer."),
      d.file ? el('div', { class: 'note' }, 'Saved to ' + d.file.replace(/^\/sdcard\//, '')) : null);
  }

  function grok() {
    const m = call('mac') || {};
    const st = m.status, af = st && st.autofix;
    const reqs = m.requests || [], d = m.diag || {};
    const pending = reqs.some(pendingReq);
    const can = reachable(m);
    grokBusy = pending || d.running || d.macState === 'waiting' || d.macState === 'working';
    const [dot, text] = linkLine(m, pending);
    const btn = (k, label, fn, disabled) => el('button', { 'data-k': k, onclick: fn, disabled: !can || disabled }, label);
    const out = [el('div', { class: 'linkbar' }, el('span', { class: 'dot ' + dot }), text)];
    if (st) out.push(statusPanel(st));
    else out.push(el('div', { class: 'note' }, pending ? 'Asking the Mac how things are...' : 'No word from the Mac yet.'));
    out.push(el('div', { class: 'buttons' },
      btn('status', 'Check status', () => askMac('status')),
      af && af.holdUntil ? btn('hold', 'Resume runs', () => askMac('resume')) : btn('hold', 'Pause runs 1 hour', () => askMac('hold', 60)),
      af && !af.on ? btn('onoff', 'Turn runs on', () => askMac('on')) : btn('onoff', 'Turn runs off', () => askMac('off')),
      btn('stop-run', 'Stop current run', () => confirmThen('Stop the current run?',
        "Grok Build stops what it's doing, saves its session and reports back. The same fixes can start again in 10 minutes unless you pause or turn off runs.",
        'Stop the run', () => askMac('stop-run')), !(af && af.current) || af.current.stopping),
      btn('watch-stop', 'Stop watcher', () => confirmThen('Stop the watcher on the Mac?',
        "Nothing on the Mac will check the Thor, sync bugs or start runs, and these buttons stop working, until you log in to the Mac again or run coro watch there. A run that's already going keeps going.",
        'Stop the watcher', () => askMac('watch-stop')), st && !(st.watcher || {}).pid),
      el('button', { 'data-k': 'diag', class: 'primary', onclick: runDiagnostics }, 'Run diagnostics')));
    if (!can) out.push(el('div', { class: 'note' }, 'Plug the Thor into the Mac to use these. Run diagnostics still checks the Thor.'));
    const list = requestList(reqs);
    if (list) out.push(list);
    if (d.started) out.push(diagView(d));
    if (st && st.log && st.log.length) {
      out.push(el('div', { class: 'diaghead' }, 'Watcher log, as of ' + when(st.t)), el('pre', { class: 'log' }, st.log.join('\n')));
    }
    return out;
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

  // ---- talk: his Grok brain ----

  const MODELS = [['grok-4.7', 'Smartest'], ['grok-4.20-0309-non-reasoning', 'Fastest']];
  const VOICES = [['kepler', 'Kepler'], ['sal', 'Sal'], ['naksh', 'Naksh'], ['helix', 'Helix'], ['rex', 'Rex']];
  const ACTED = { done: 'done', doing: 'working on it...', declined: 'you said no', failed: "that didn't work" };
  const ALOUD = { listening: 'Listening...', thinking: 'Thinking...', speaking: 'Talking...' };
  let talkDraft = '', talkRev = -1, voiceRev = -1, talkPoll = 0, keyForm = false;

  function money(n) {
    return '$' + (n || 0).toFixed(n && n < 0.1 ? 3 : 2);
  }

  function talk() {
    const b = call('brain');
    talkRev = b.rev;
    voiceRev = b.voice ? b.voice.rev : -1;
    if (!b.hasKey || keyForm) return keyView(b);
    const out = [];
    if (!b.on) out.push(el('div', { class: 'note' }, 'My Grok brain is switched off. Turn it back on in Settings and we can talk.'));
    const log = el('div', { class: 'chat' });
    if (!b.chat.length && !b.busy) log.append(el('div', { class: 'empty' }, 'Ask me about the Thor, your apps, bugs or updates... or just say hi!'));
    for (const m of b.chat) log.append(said(m));
    if (b.busy) log.append(el('div', { class: 'say me thinking' }, el('span', { class: 'who' }, 'Coronamon'), 'Thinking...'));
    const box = el('textarea', { id: 'talk-text', 'data-k': 'talk', rows: 2, maxlength: 2000, placeholder: 'Say something to Coronamon...' });
    box.value = talkDraft;
    box.addEventListener('input', () => { talkDraft = box.value; });
    box.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter' || e.shiftKey) return;
      e.preventDefault();
      if (box.value.trim()) sendTalk();
      else C.keyboard();
    });
    out.push(log, el('div', { class: 'talkbar' }, box,
      el('button', { class: 'primary', 'data-k': 'send', disabled: b.busy || !b.on, onclick: sendTalk }, 'Send')));
    const vb = voiceBar(b);
    if (vb) out.push(vb);
    const t = b.today || {};
    const model = (MODELS.find(([id]) => id === b.model) || [b.model, b.model])[1];
    out.push(el('div', { class: 'talkfoot' },
      el('span', {}, model + ' brain  ·  today ' + money(t.cost) + ' of ' + money(b.cap) +
        (b.notes.length ? '  ·  remembers ' + plural(b.notes.length, 'thing') : '')),
      el('button', { 'data-k': 'clear', disabled: !b.chat.length, onclick: () => confirmThen('Clear the chat?',
        'He forgets this conversation. Things you asked him to remember stay until you forget them in Settings.', 'Clear chat',
        () => { safe(() => call('brainClear', 'chat')); render(); }) }, 'Clear chat')));
    return out;
  }

  /** Talking out loud: what he's doing right now, with Stop; or the button that makes him listen. */
  function voiceBar(b) {
    const v = b.voice;
    if (!v || !v.on) return null;
    const live = v.state !== 'idle';
    const text = live ? ALOUD[v.state] + (v.heard ? '  “' + v.heard + '”' : '')
      : v.error ? 'Last time: ' + v.error : 'Or double-tap me on screen and talk out loud.';
    return el('div', { class: 'voicebar' + (live ? ' live' : '') }, el('span', {}, text),
      live ? el('button', { 'data-k': 'voice-stop', onclick: () => { safe(() => call('voiceStop')); render(); } }, 'Stop')
        : el('button', { class: 'primary', 'data-k': 'voice', disabled: b.busy || !b.on,
          onclick: () => { if (safe(() => call('voiceStart'))) render(); } }, 'Talk out loud'));
  }

  function said(m) {
    const you = m.role === 'user', oops = m.role === 'error';
    const kids = [el('span', { class: 'who' }, (you ? 'You' : 'Coronamon') + (m.from === 'voice' ? ', out loud' : '')), el('div', {}, m.text)];
    for (const a of m.actions || []) {
      kids.push(a.state === 'asked'
        ? el('div', { class: 'acts' },
          el('button', { class: 'primary', onclick: () => { if (safe(() => call('brainAction', m.id, a.id, true))) render(); } }, a.label),
          el('button', { onclick: () => { if (safe(() => call('brainAction', m.id, a.id, false))) render(); } }, 'No thanks'))
        : el('div', { class: 'acted' }, a.label + ': ' + (ACTED[a.state] || a.state)));
    }
    kids.push(el('span', { class: 'at' }, when(m.t) + (m.ms ? '  ·  ' + (m.ms / 1000).toFixed(1) + 's' : '')));
    return el('div', { class: 'say ' + (you ? 'you' : oops ? 'me oops' : 'me') }, kids);
  }

  function sendTalk() {
    const text = talkDraft.trim();
    if (!text) return;
    if (safe(() => call('brainSend', text))) {
      talkDraft = '';
      render();
    }
  }

  function keyView(b) {
    const input = el('input', { type: 'password', id: 'key-in', 'data-k': 'key', placeholder: 'xai-...', autocomplete: 'off', spellcheck: 'false' });
    input.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter') return;
      e.preventDefault();
      if (input.value.trim()) saveKey(input.value);
      else C.keyboard();
    });
    const out = [el('div', { class: 'prose' },
      el('h3', {}, b.hasKey ? 'Change my API key' : 'Give me a Grok brain!'),
      el('p', {}, 'With an xAI API key I can really talk: answer questions about the Thor, its apps, bugs and updates, and come up with new things to say every day.'),
      el('p', {}, 'Make a key at console.x.ai (it needs credits) and paste it here. It stays on this Thor, encrypted, and only goes to xAI. ' +
        'I stop for the day when I reach the daily limit in Settings.')),
      el('div', { class: 'talkbar plain' }, input, el('button', { class: 'primary', onclick: () => saveKey(input.value) }, 'Save key'))];
    if (b.hasKey) out.push(el('div', { class: 'buttons' }, el('button', { onclick: () => { keyForm = false; render(); } }, 'Keep my key (' + b.hint + ')')));
    return out;
  }

  function saveKey(value) {
    const hint = safe(() => call('brainKey', value.trim()));
    if (!hint) return;
    keyForm = false;
    toast('Key ' + hint + ' saved. My brain is waking up!');
    show('talk');
  }

  function watchTalk() {
    talkPoll = setTimeout(function tick() {
      if (current !== 'talk' || keyForm) return;
      let b = null;
      try { b = call('brain'); } catch (e) { /* the service may be restarting */ }
      const v = (b && b.voice) || {};
      if (b && (b.rev !== talkRev || (v.rev != null && v.rev !== voiceRev)) && !overlay()) render();
      else talkPoll = setTimeout(tick, v.state && v.state !== 'idle' ? 500 : b && b.busy ? 800 : 4000);
    }, 800);
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
    const b = safe(() => call('brain')) || { notes: [], fresh: {} };
    const rows = [
      ['Size', 'how big I am', seg('size', s.size, [[0.8, 'Small'], [1, 'Normal'], [1.25, 'Big']])],
      ['Chatter', 'how often I talk', seg('chatter', s.chatter, [[0, 'Quiet'], [1, 'Normal'], [2, 'Chatty']])],
      ['Games & videos', 'when one is on my screen', seg('games', s.games, [['home', 'Go home'], ['move', 'Move away'], ['ghost', 'Ghost'], ['hide', 'Hide'], ['stay', 'Stay']])],
      ['Screen', 'where I like to be', seg('screen', s.screen, [['auto', 'Both'], ['top', 'Top'], ['bottom', 'Bottom']])],
      ['Night sleep', '11 PM to 7 AM', seg('nightSleep', s.nightSleep, [[true, 'On'], [false, 'Off']])],
      ['Updates', 'for apps with GitHub releases', seg('updates', s.updates, [['ask', 'Ask me'], ['auto', 'Automatic'], ['off', 'Off']])],
      ['Quote sources', 'show where a quote is from', seg('showSources', s.showSources, [[true, 'On'], [false, 'Off']])],
      ['On screen', '', seg('visible', s.visible, [[true, 'Out'], [false, 'In the den']])],
      ['Grok brain', b.hasKey ? 'key ' + b.hint : 'needs an xAI API key', seg('brain', s.brain, [[true, 'On'], [false, 'Off']])],
      ['Brain model', 'Fastest is also cheapest', seg('brainModel', s.brainModel, MODELS)],
      ['Daily limit', 'Grok spending per day', seg('brainCap', s.brainCap, [[0.25, '$0.25'], [1, '$1'], [3, '$3'], [10, '$10']])],
      ['Fresh lines', (b.fresh.count ? b.fresh.count + ' from Grok' : 'new things to say') + ', daily', seg('freshLines', s.freshLines, [[true, 'On'], [false, 'Off']])],
      ['Voice chat', 'double-tap me to talk out loud', seg('voice', s.voice, [[true, 'On'], [false, 'Off']])],
      ['My voice', "one of xAI's voices", seg('voiceId', s.voiceId, VOICES)],
      ['Voice speed', 'how fast I talk', seg('voiceSpeed', s.voiceSpeed, [[0.9, 'Calm'], [1.05, 'Normal'], [1.2, 'Quick']])],
      ['Keep listening', 'a few seconds after I answer', seg('voiceFollowUp', s.voiceFollowUp, [[true, 'On'], [false, 'Off']])],
      ['Screens dim', 'when nobody touches the Thor', seg('dimHome', s.dimHome, [[true, 'I go to my room'], [false, 'I stay out']])],
    ].map(([label, small, ctl]) => el('div', { class: 'row' }, el('div', { class: 'label' }, label, small ? el('small', {}, small) : null), ctl));
    const p = st.permissions || {};
    const missing = Object.entries(p).filter(([, v]) => !v).map(([k]) => k);
    return rows.concat([
      el('div', { class: 'buttons' },
        el('button', { onclick: () => { keyForm = true; show('talk'); } }, b.hasKey ? 'Change API key' : 'Add API key'),
        b.hasKey && el('button', { 'data-k': 'forget-key', onclick: () => confirmThen('Remove the API key?',
          'He goes back to his built-in lines until you add a key again.', 'Remove key',
          () => { safe(() => call('brainForgetKey'), 'Key removed.'); render(); }) }, 'Remove API key'),
        b.notes.length > 0 && el('button', { 'data-k': 'forget-notes', onclick: () => confirmThen('Forget what he remembers?',
          b.notes.map((n) => n.text).join('  /  '), 'Forget',
          () => { safe(() => call('brainClear', 'notes'), 'Forgotten.'); render(); }) }, 'Forget what he remembers (' + b.notes.length + ')'),
        b.hasKey && s.freshLines && el('button', { onclick: () => {
          const r = safe(() => call('brainLines'));
          if (r != null) toast(r ? 'Writing some new lines. They take a couple of minutes.' : 'Already writing some, or the daily limit is reached.');
        } }, 'New lines now'),
        b.hasKey && s.voice && el('button', { 'data-k': 'voice-try', onclick: () => safe(() => call('voiceTry'), 'Listen up!') }, 'Hear my voice')),
      el('div', { class: 'buttons' },
        el('button', { onclick: () => safe(() => call('summon', 'top'), 'Coming up!') }, 'Call me to the top screen'),
        el('button', { onclick: () => safe(() => call('summon', 'bottom'), 'On my way down!') }, 'Call me to the bottom screen'),
        el('button', { onclick: () => safe(() => call('home', 'open'), 'Come on in!') }, 'Visit my home'),
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
      el('p', {}, 'Tap him to say hi, hold to pet him, drag to carry him, or fling him at the other screen.'),
      el('p', {}, "While you watch or play something on the top screen he goes home: his room fills the bottom screen, with his bed, his computer and gaming chair, a bean bag, and a TV that replays what you've been watching (muted). Tap his furniture and he'll use it; tap the TV to skip or watch on top. If a game needs the bottom screen too, he stays home and leaves a house button there instead, with his messages about updates, crashes and anything he needs from you."),
      el('p', {}, 'With an xAI API key he gets a Grok brain: chat with him in the Talk tab (or with ', el('code', {}, 'coro brain ask'),
        ' from the Mac). He can check on apps, bugs and updates for you, and writes a fresh batch of lines about once a day. Anything that changes the Thor waits for your OK.'),
      el('p', {}, "Double-tap him to talk out loud. He listens with xAI's speech-to-text, answers with his Grok brain, and talks back in his own voice with xAI's text-to-speech, then listens a few more seconds in case you answer. Tap him when you've finished talking, or while he talks to hush him. His voice counts toward the same daily limit."),
      el('p', {}, "When the screens dim because nobody's touched the Thor for a while, he heads to his room on the bottom screen, and walks back out when you're back."),
      el('h3', {}, 'Where his words come from'),
      src,
      el('p', {}, 'Add your own in ', el('code', {}, 'Coronamon/lines.json'), ' on the Thor\'s storage.'),
      el('h3', {}, 'From the Mac'),
      el('p', {}, el('code', {}, '~/thor-coronamon/coro status'), '  ', el('code', {}, 'coro install app.apk'), '  ', el('code', {}, 'coro say "hi"'), '  ', el('code', {}, 'coro act dance')),
      el('p', { style: 'color:var(--muted)' }, 'Version ' + (st.version || '?') + '. Coronamon and Digimon belong to Bandai Namco; this is a fan-made companion for personal use.'))];
  }

  const VIEWS = { apps, talk, activity, bugs, grok, inbox, settings, about };

  function overlay() {
    return !document.getElementById('modal').hidden || !document.getElementById('confirm').hidden || !!report;
  }

  function render() {
    const view = document.getElementById('view');
    const a = document.activeElement;
    const focused = a && a.textContent;
    const key = a && view.contains(a) ? a.dataset.k : null;
    const scroll = view.scrollTop;
    view.replaceChildren();
    try {
      view.append(...VIEWS[current]());
    } catch (e) {
      view.append(el('div', { class: 'empty' }, e.message));
    }
    view.scrollTop = current === 'talk' && !keyForm ? view.scrollHeight : scroll;
    document.getElementById('title').textContent = TITLES[current];
    document.getElementById('quip').textContent = QUIPS[current];
    for (const t of document.querySelectorAll('.tab')) t.classList.toggle('active', t.dataset.tab === current);
    if (key) {
      refocus(key);
    } else if (focused) {
      const again = [...view.querySelectorAll('button')].find((b) => b.textContent === focused && b.classList.contains('on'));
      if (again) again.focus();
    }
    if (diagScroll) {
      diagScroll = false;
      const d = view.querySelector('.diag');
      if (d) d.scrollIntoView({ block: 'start' });
    }
    clearTimeout(talkPoll);
    if (current === 'talk') watchTalk();
    clearTimeout(grokPoll);
    if (current === 'grok' && grokBusy) {
      grokPoll = setTimeout(function tick() {
        if (current !== 'grok') return;
        if (overlay()) grokPoll = setTimeout(tick, 1000);
        else render();
      }, 1000);
    }
  }

  function show(tab) {
    if (tab !== 'talk') keyForm = false;
    current = tab;
    document.getElementById('view').scrollTop = 0;
    if (tab === 'grok') freshen();
    render();
  }

  // ---- gamepad and d-pad focus ----

  function focusables() {
    const modal = document.getElementById('modal'), form = document.getElementById('report'), ask = document.getElementById('confirm');
    const root = !ask.hidden ? ask : !modal.hidden ? modal : !form.hidden ? form : document;
    return [...root.querySelectorAll('button:not([disabled]), textarea, input')].filter((b) => b.offsetParent !== null);
  }

  /** What the d-pad scrolls when there's nothing more to focus that way. */
  function scroller() {
    if (!document.getElementById('modal').hidden) return document.getElementById('modal-body');
    if (report) return document.getElementById('report-body');
    return document.getElementById('view');
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
    } else if (dir === 'up' || dir === 'down') {
      const box = scroller();
      box.scrollBy({ top: (dir === 'down' ? 0.6 : -0.6) * box.clientHeight });
    }
  }

  document.addEventListener('keydown', (e) => {
    const dirs = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' };
    const typing = document.activeElement && ['TEXTAREA', 'INPUT'].includes(document.activeElement.tagName);
    if (dirs[e.key] && !(typing && (e.key === 'ArrowLeft' || e.key === 'ArrowRight'))) {
      e.preventDefault();
      move(dirs[e.key]);
    }
  });

  document.getElementById('modal-close').addEventListener('click', () => { document.getElementById('modal').hidden = true; });
  document.getElementById('confirm-no').addEventListener('click', closeConfirm);
  document.getElementById('report-cancel').addEventListener('click', () => closeReportForm(true));
  document.getElementById('report-send').addEventListener('click', sendReport);
  for (const t of document.querySelectorAll('.tab')) t.addEventListener('click', () => show(t.dataset.tab));

  window.den = {
    tab(d) {
      const i = (TABS.indexOf(current) + d + TABS.length) % TABS.length;
      show(TABS[i]);
      document.querySelector('.tab.active').focus();
    },
    back() {
      const modal = document.getElementById('modal'), ask = document.getElementById('confirm');
      if (!ask.hidden) {
        closeConfirm();
        return true;
      }
      if (!modal.hidden) {
        modal.hidden = true;
        return true;
      }
      if (report) {
        closeReportForm(true);
        return true;
      }
      return false;
    },
    refresh() {
      if (current === 'grok') freshen();
      render();
    },
    checkReport() {
      const cap = safe(() => call('takeReport'));
      if (!cap) return;
      closeReportForm(true);
      show('bugs');
      openReportForm(cap);
    },
    toast(msg) {
      toast(msg);
      if (current === 'talk') render();
    },
  };

  const avatar = safe(() => call('avatar', 'wave'));
  if (avatar) document.getElementById('avatar').src = avatar;
  show('apps');
  const first = document.querySelector('.tab.active');
  if (first) first.focus();
  den.checkReport();
  setInterval(() => { if (!overlay() && current !== 'settings' && current !== 'talk') render(); }, 15000);
})();
