// Runs on the site's file host (the page its Download button opens), out of sight in AniDeck: picks
// the episode's version, audio and quality, asks the host to prepare that MP4 and leaves its address
// in window.__anideckDl for Downloads.java. want: { versions, audios, qualities }, lists of patterns
// in order of preference.
(want) => {
  'use strict';
  if (window.__anideckDl) return;
  const R = (window.__anideckDl = { stage: 'loading' });
  // How long a quality may sit in the host's queue before another one it already has will do.
  const QUEUE_MS = 90000;
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const text = (el) => (el ? el.textContent || '' : '').replace(/\s+/g, ' ').trim();
  const fail = (error) => Object.assign(R, { stage: 'error', error: error.slice(0, 160) });
  const done = (href, extra) => Object.assign(R, extra || {}, { stage: 'ready', href });
  const rank = (patterns, s) => {
    const i = patterns.findIndex((p) => new RegExp(p, 'i').test(s));
    return i < 0 ? patterns.length : i;
  };
  const link = () => [...document.querySelectorAll('a[href]')]
    .find((a) => /^https:/.test(a.href) && (a.hasAttribute('download') || /download mp4/i.test(text(a))));

  // The page's data lists each version's qualities, each with the address its Prepare button posts
  // to and the one it then asks how that's going: "queued", "running", "ready" or "failed".
  function variants() {
    const t = [...document.scripts].map((s) => s.textContent).find((x) => x.includes('mp4_versions:')) || '';
    const from = t.indexOf('mp4_versions:'), to = t.indexOf('mp4_qualities:', from);
    const body = from < 0 ? '' : t.slice(from, to > from ? to : undefined);
    const field = (o, k) => (new RegExp('\\b' + k + ':"([^"]*)"').exec(o) || [])[1] || '';
    const heads = [...body.matchAll(/label:"([^"]*)",track:/g)];
    const out = [];
    heads.forEach((h, i) => {
      const part = body.slice(h.index, i + 1 < heads.length ? heads[i + 1].index : undefined);
      for (const [o] of part.matchAll(/\{[^{}]*\}/g)) {
        const request = field(o, 'request_url'), status = field(o, 'status_url');
        if (request && status) out.push({ version: h[1], quality: field(o, 'label'), audio: field(o, 'audio_label'), request, status });
      }
    });
    return out;
  }

  async function ask(url, method) {
    try {
      const r = await fetch(url, { method, credentials: 'include', cache: 'no-store' });
      const j = await r.json().catch(() => ({}));
      return r.ok ? j : { status: 'failed', error: j.title || j.error || 'The file host answered ' + r.status };
    } catch (e) {
      return {};
    }
  }

  const progress = (st) => (st.status === 'queued' ? 'queued'
    : typeof st.progress_percent === 'number' ? Math.round(st.progress_percent) + '%' : '');

  // The choices are <select>s inside labels that start with "Version", "Audio" and "Quality".
  function choices() {
    const out = {};
    for (const s of document.querySelectorAll('select')) {
      const name = text(s.closest('label') || s.parentElement).replace(text(s), '').trim().toLowerCase();
      for (const k of ['version', 'audio', 'quality']) if (!out[k] && name.startsWith(k)) out[k] = s;
    }
    return out;
  }

  // Sets a choice the way a person would, so the page's own handlers see it. True if it changed.
  function choose(sel, patterns) {
    if (!sel) return false;
    for (const p of patterns) {
      const re = new RegExp(p, 'i');
      const o = [...sel.options].find((x) => re.test(text(x)));
      if (!o) continue;
      if (sel.value === o.value) return false;
      Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set.call(sel, o.value);
      sel.dispatchEvent(new Event('input', { bubbles: true }));
      sel.dispatchEvent(new Event('change', { bubbles: true }));
      return true;
    }
    return false;
  }

  const prepare = () => [...document.querySelectorAll('button')].find((b) => /prepare/i.test(text(b)));

  // An expired or bad link gets a short error page instead of the choices.
  function problem() {
    const t = text(document.body);
    return t && t.length < 300 && /invalid|expired|not found|denied|forbidden|error/i.test(t) ? t : '';
  }

  async function settled() {
    for (let i = 0; i < 80; i++) {
      const sels = [...document.querySelectorAll('select')];
      if (link() || variants().length || (sels.length && sels.every((s) => s.options.length) && prepare())) return true;
      const p = problem();
      if (p) {
        fail(p);
        return false;
      }
      await sleep(250);
    }
    return false;
  }

  // Asks the host for the best of the episode's versions straight from the page's data.
  async function byData(all) {
    const best = (list, key, patterns) => {
      const top = Math.min(...list.map((v) => rank(patterns, v[key])));
      return list.filter((v) => rank(patterns, v[key]) === top);
    };
    const options = best(best(all, 'version', want.versions), 'audio', want.audios)
      .sort((a, b) => rank(want.qualities, a.quality) - rank(want.qualities, b.quality));
    const mine = options[0];
    Object.assign(R, { version: mine.version, audio: mine.audio, quality: mine.quality });
    let st = await ask(mine.status, 'GET');
    if (st.status === 'ready' && st.download_url) return done(st.download_url);
    R.stage = 'preparing';
    if (st.status !== 'queued' && st.status !== 'running') st = await ask(mine.request, 'POST');
    const asked = Date.now();
    let looked = 0;
    for (let i = 0; i < 900; i++) {
      if (st.status === 'ready' && st.download_url) return done(st.download_url);
      if (st.status === 'failed') return fail(st.error || "The file host couldn't prepare the episode");
      R.note = progress(st);
      if (st.status === 'queued' && Date.now() - asked > QUEUE_MS && Date.now() - looked > 30000) {
        looked = Date.now();
        for (const v of options.slice(1)) {
          const o = await ask(v.status, 'GET');
          if (o.status === 'ready' && o.download_url) return done(o.download_url, { quality: v.quality, instead: mine.quality });
        }
      }
      await sleep(2000);
      st = await ask(mine.status, 'GET');
    }
    fail('The file host took too long to prepare the episode');
  }

  // Without the page's data: make the choices on the page and press its Prepare button.
  async function byHand() {
    // A pick can change the choices after it (another version has other audio), so they're
    // gone through again until none changes.
    for (let round = 0; round < 3; round++) {
      let changed = false;
      for (const [k, patterns] of [['version', want.versions], ['audio', want.audios], ['quality', want.qualities]]) {
        if (choose(choices()[k], patterns)) {
          changed = true;
          await sleep(400);
        }
      }
      if (!changed) break;
    }
    const c = choices();
    for (const k of ['version', 'audio', 'quality']) R[k] = c[k] ? text(c[k].selectedOptions[0]) : '';
    let go = prepare();
    for (let i = 0; go && go.disabled && i < 20; i++) {
      await sleep(250);
      go = prepare();
    }
    if (!go || go.disabled) return fail("The file host's page has no download button");
    R.stage = 'preparing';
    go.click();
    for (let i = 0; i < 1800; i++) {
      await sleep(1000);
      const a = link();
      if (a) return done(a.href);
      const p = problem();
      if (p) return fail(p);
      const line = text(document.querySelector('.download-status'));
      if (/fail|error|expired|invalid/i.test(line)) return fail(line);
      const pct = /(\d{1,3})\s*%/.exec(line);
      R.note = /queued/i.test(line) ? 'queued' : pct ? pct[1] + '%' : '';
    }
    fail('The file host took too long to prepare the episode');
  }

  (async () => {
    try {
      if (!(await settled())) {
        if (R.stage !== 'error') fail("The file host's page didn't load");
        return;
      }
      const shown = link();
      if (shown) return done(shown.href);
      const all = variants();
      await (all.length ? byData(all) : byHand());
    } catch (e) {
      fail(String((e && e.message) || e));
    }
  })();
}
