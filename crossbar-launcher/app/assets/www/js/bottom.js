'use strict';
/* The bottom screen. Follows the top screen's selection with PS Vita LiveArea-style game pages,
   PS4/PS5-style cards and a Control Center dock, and turns touches into menu commands. */

const NB = window.NativeB || new Proxy({}, {
  get: (_, name) => (...args) => { console.log('[NativeB]', name, ...args); return name === 'topVisible'; },
});
const W = 1240, H = 1080;

const cssUrl = u => `url('${String(u || '').replace(/['"\\\n()]/g, c => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))}')`;
const bgAttr = u => `style="background-image:${esc(cssUrl(u))}"`;
const attr = o => esc(JSON.stringify(o));
const metaHtml = list => (list || []).filter(Boolean).map(m => `<span>${esc(m)}</span>`).join('');
const OK = { t: 'key', k: 'ok' };

function fit() {
  const s = Math.min(innerWidth / W, innerHeight / H);
  $('#stage').style.transform = `translate(${(innerWidth - W * s) / 2}px,${(innerHeight - H * s) / 2}px) scale(${s})`;
}

function dur(ms) {
  if (!ms) return '—';
  const m = Math.round(ms / 60000);
  if (m < 60) return Math.max(1, m) + ' min';
  const h = ms / 3600000;
  return (h >= 10 ? Math.round(h) : h.toFixed(1)) + ' h';
}

function stars(r) {
  const n = Math.round(r * 10) / 2;
  let s = '';
  for (let i = 1; i <= 5; i++) s += `<i class="${n >= i ? 'f' : n >= i - 0.5 ? 'h' : ''}">★</i>`;
  return s;
}

function batterySvg(level, charging) {
  const w = Math.round(38 * clamp(level, 0, 100) / 100);
  const low = level <= 15 && !charging;
  return `<svg viewBox="0 0 64 64"><rect x="4" y="19" width="51" height="27" rx="6" fill="none" stroke="#fff" stroke-width="4"/>` +
    `<rect x="57" y="27" width="5" height="11" rx="2" fill="#fff"/><rect x="10" y="25" width="${w}" height="15" rx="2" fill="${low ? '#ff5a5a' : '#fff'}"/>` +
    (charging ? '<path d="M34 9 20 35h11l-4 20 17-28H33z" fill="#ffd34d" stroke="#000" stroke-opacity=".6" stroke-width="2"/>' : '') + '</svg>';
}

function wifiSvg(on, level, max) {
  const n = on ? Math.max(1, Math.round(3 * (level + 1) / Math.max(1, max + 1))) : 0;
  const a = i => (on && i <= n ? 1 : 0.28);
  return `<svg viewBox="0 0 64 64" fill="none" stroke="#fff" stroke-width="6" stroke-linecap="round">` +
    `<path d="M8 25a34 34 0 0 1 48 0" opacity="${a(3)}"/><path d="M17 35a21 21 0 0 1 30 0" opacity="${a(2)}"/>` +
    `<path d="M26 45a8.5 8.5 0 0 1 12 0" opacity="${a(1)}"/><circle cx="32" cy="53" r="3" fill="#fff" stroke="none" opacity="${on ? 1 : 0.28}"/></svg>`;
}

/* Same icon objects as the top screen's items: { glyph } or { img, shape, style, blur, fallback }. */
function iconHtml(icon, cls = '') {
  if (!icon || icon.glyph || !icon.img) return `<div class="ic glyph ${cls}">${glyph((icon && icon.glyph) || 'folder')}</div>`;
  const st = (icon.style || '').split(' ').filter(Boolean);
  return `<div class="ic ${st.join(' ')} ${cls}">` +
    (st.includes('tile') ? `<div class="bl" data-blur="${esc(icon.blur || icon.img)}"></div>` : '') +
    `<img src="${esc(icon.img)}" alt="" decoding="async" data-fb="${esc(icon.fallback || 'folder')}" onerror="imgFail(this)"></div>`;
}

function imgFail(img) {
  const ic = img.closest('.ic, .cover, .awart, .art');
  if (!ic) return img.remove();
  ic.classList.add('glyph');
  ic.innerHTML = ic.classList.contains('art') ? miniGlyph(img.dataset.fb || 'music') : glyph(img.dataset.fb || 'folder');
}

function imgAlt(img) {
  img.onerror = null;
  if (img.dataset.alt) img.src = img.dataset.alt;
}

/* Scrolls a strip or list so the child is centered, without moving any outer container. */
function centerIn(box, child) {
  if (!box || !child) return;
  const b = box.getBoundingClientRect(), c = child.getBoundingClientRect();
  if (box.scrollWidth > box.clientWidth) box.scrollLeft += c.left - b.left - (b.width - c.width) / 2;
  if (box.scrollHeight > box.clientHeight) box.scrollTop += c.top - b.top - (b.height - c.height) / 2;
}

function subHtml(x) {
  if (x.subHtml) return `<div class="ts">${x.subHtml}</div>`;
  return x.sub ? `<div class="ts">${esc(x.sub)}</div>` : '';
}

function tileHtml(x, cmd) {
  return `<div class="tile k-${esc(x.kind)}" data-cmd="${attr(cmd)}">${iconHtml(x.icon)}<div class="tl">${esc(x.label)}</div>${subHtml(x)}</div>`;
}

function rowHtml(x, cmd, cur) {
  return `<div class="row k-${esc(x.kind)}${cur ? ' cur' : ''}" data-cmd="${attr(cmd)}">${iconHtml(x.icon, 'mini')}` +
    `<div class="rt"><div class="tl">${esc(x.label)}</div>${subHtml(x)}</div><div class="chev">›</div></div>`;
}

function optBtn() {
  return `<div class="btn" data-cmd="${attr({ t: 'key', k: 'options' })}"><span class="k">Y</span><span>Options</span></div>`;
}

/* Siblings of the selected item on the top screen; tapping the selected one activates it. */
/* An app's sections: what it played or saved lately (companion appFeed, and Feeds.java on the Java side). */
function feedHtml(sections) {
  return (sections || []).map(s => {
    const items = s.items.map(x => feedItemHtml(x, s.style)).join('');
    return `<div class="fsec"><div class="slbl">${esc(s.title)}</div>${s.style === 'rows' ? `<div class="frows">${items}</div>` : `<div class="frow">${items}</div>`}</div>`;
  }).join('');
}

function feedItemHtml(x, style) {
  const cmd = x.cmd ? ` data-cmd="${attr(x.cmd)}"` : '';
  const img = x.img ? `<img${x.pixel ? ' class="px"' : ''} src="${esc(x.img)}" alt="" decoding="async" onerror="this.remove()">` : '';
  const prog = x.progress > 0 ? `<div class="fprog"><i style="width:${Math.round(x.progress * 100)}%"></i></div>` : '';
  const when = x.at ? Fmt.ago(x.at) : '';
  if (style === 'posters') return `<div class="fposter"${cmd}><div class="fi">${img}</div><b>${esc(x.title)}</b><span>${esc(x.sub || when)}</span></div>`;
  if (style === 'cards') {
    return `<div class="fcard"${cmd}><div class="fi">${img}</div><div class="ft"><b>${esc(x.title)}</b><span>${esc(x.sub)}</span>${prog}${when ? `<em>${esc(when)}</em>` : ''}</div></div>`;
  }
  return `<div class="frowi"${cmd}><div class="fi">${img}</div><div class="ft"><b>${esc(x.title)}</b><span>${esc(x.sub)}</span></div><em>${esc(when)}</em></div>`;
}

function stripHtml(vm, items, label, cls = '') {
  const cur = vm.item.id;
  return `<div class="strip ${cls}">${label ? `<div class="slbl">${esc(label)}</div>` : ''}<div class="srow">${items.slice(0, 24).map(x =>
    `<div class="stile${x.id === cur ? ' cur' : ''}" data-cmd="${attr({ t: 'select', id: x.id, run: x.id === cur })}">${iconHtml(x.icon)}</div>`).join('')}</div></div>`;
}

function statsHtml(stats) {
  return `<div class="stats">${stats.map(([v, l]) => `<div class="stat"><b>${esc(v)}</b><span>${esc(l)}</span></div>`).join('')}</div>`;
}

function folderLayout(kids) {
  const n = kids.length;
  const count = (...kinds) => kids.filter(x => kinds.includes(x.kind)).length;
  if (count('photo') > n / 2) return 'photos';
  if (count('movie') > n / 2) return 'posters';
  if (count('friend') > n / 2) return 'people';
  if (count('setting', 'notif', 'song', 'action', 'info') > n / 2) return 'list';
  if (kids.filter(x => x.icon && x.icon.img && x.icon.shape === 'wide').length > n / 2) return 'wide';
  return 'squares';
}

/* The other items in the top screen's current column, as touch rows. */
function siblingRows(vm, it) {
  const sibs = vm.list.items;
  if (sibs.length < 2) return '';
  return `<div class="rows sibs scroll">${sibs.map(x => rowHtml(x, { t: 'select', id: x.id, run: x.id === it.id }, x.id === it.id)).join('')}</div>`;
}

/* Dark text on light accent colors (the DS and Settings accents are near white). */
function lightColor(c) {
  let m = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(String(c).trim());
  let rgb;
  if (m) {
    const h = m[1].length === 3 ? m[1].replace(/./g, d => d + d) : m[1];
    rgb = [0, 2, 4].map(i => parseInt(h.substr(i, 2), 16));
  } else if ((m = /rgba?\(([^)]+)\)/i.exec(String(c)))) {
    rgb = m[1].split(',').slice(0, 3).map(v => parseFloat(v));
  } else {
    return false;
  }
  return (0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]) / 255 > 0.62;
}

/* Install dates (and a version too long for a stat) from /api/gameinfo?app=. */
function appFacts(info) {
  const real = ts => ts > Date.UTC(2010, 0, 1); // apps that came with the system report a placeholder 2008/2009 date
  const day = ts => new Date(ts).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
  const v = String(info.version || '');
  return [real(info.installed) ? `Installed ${day(info.installed)}` : '',
    real(info.updated) && info.updated - info.installed > 86400000 ? `Updated ${day(info.updated)}` : '',
    v.length > 12 ? `Version ${v}` : ''].filter(Boolean).join(' · ');
}

function appStats(a, info, never, last, time) {
  const v = String(info.version || '');
  return [[a.last ? Fmt.ago(a.last) : never, last], [dur(a.fgTime), time], [Fmt.size(info.size) || '—', 'Size'],
    v && v.length <= 12 ? [v, 'Version'] : [a.kindLabel || 'Android', 'Type']];
}

function sysinfoHtml(s) {
  const model = String(s.model || '');
  const device = s.maker && !model.toLowerCase().startsWith(s.maker.toLowerCase()) ? `${s.maker} ${model}` : model;
  const bar = f => `<div class="bar"><i style="width:${(100 * clamp(f || 0, 0, 1)).toFixed(1)}%"></i></div>`;
  return [
    [device, `Android ${s.android || ''}`, ''],
    [`${Fmt.size(s.storageFree)} free`, `Storage · ${Fmt.size(s.storageTotal)}`, bar((s.storageTotal - s.storageFree) / s.storageTotal)],
    [`${Fmt.size(s.memFree)} free`, `Memory · ${Fmt.size(s.memTotal)}`, bar((s.memTotal - s.memFree) / s.memTotal)],
    [s.batteryTemp ? `${s.batteryTemp.toFixed(1)} °C` : '—', `Battery · up ${dur(s.uptime)}`, ''],
  ].map(([v, l, extra]) => `<div class="si"><b>${esc(v)}</b><span>${esc(l)}</span>${extra}</div>`).join('');
}

function npSource(np) {
  if (!np) return null;
  const l = np.local || {}, x = np.external || {};
  if (l.active && (l.playing || !x.playing)) return Object.assign({ src: 'local' }, l);
  if (x.active && x.title) return Object.assign({ src: 'external' }, x);
  return null;
}

function send(cmd) {
  NB.toTop(JSON.stringify(cmd));
}

// ---------- pages for the selected item ----------
const Pages = {
  /* PS Vita LiveArea: banner art, a big Start button, stats, description and more games. */
  game(vm, it) {
    const g = it.game, a = it.app;
    const hero = g ? (it.heroes || [])[0] : '';
    const cover = g ? g.cover : a.icon;
    const plat = g ? { glyph: g.glyph, label: g.short || g.platform } : { glyph: 'android', label: 'Android' };
    const stats = g ? [
      [g.last ? Fmt.ago(g.last) : 'Never', 'Last played'], [dur(g.time), 'Play time'],
      [String(g.count || 0), g.count === 1 ? 'Session' : 'Sessions'], [Fmt.size(g.size) || '—', 'Size'],
    ] : [[a.last ? Fmt.ago(a.last) : 'Never', 'Last played'], [dur(a.fgTime), 'Play time'], ['Android', 'Platform']];
    const sibs = (vm.list.items || []).filter(x => x.kind === it.kind && !String(x.id).startsWith('emu:'));
    const bannerBg = hero || (g ? '' : cover) || (vm.bg && vm.bg.url) || '';
    const label = vm.depth && g ? `More ${g.short || g.platform} games` : `More in ${vm.path[vm.path.length - 1]}`;
    return `<div class="page live${sibs.length > 1 ? '' : ' nostrip'}">
      <div class="banner${hero ? '' : ' blur'}">
        <div class="bimg" ${hero ? bgAttr(bannerBg) : `data-blur="${esc(bannerBg)}" data-look="banner"`}></div><div class="bshade"></div>
        <div class="chip">${miniGlyph(plat.glyph)}<span>${esc(plat.label)}</span></div>
        <div class="chip right rating hide"></div>
        <div class="btitle">${g && g.logo ? `<img class="logo" src="${esc(g.logo)}" alt="" onerror="this.remove()">` : ''}</div>
        <div class="start" data-cmd="${attr(OK)}"><span class="tri">${miniGlyph('play')}</span><span>Start</span></div>
      </div>
      <div class="lrow">
        <div class="cover${g && g.pixel ? ' pixel' : ''}${g && g.small ? ' small' : ''}${a ? ' appicon' : ''}">
          <div class="bl" data-blur="${esc(cover)}" data-look="cover"></div><img src="${esc(cover)}" alt="" data-fb="${esc(plat.glyph)}" onerror="imgFail(this)"></div>
        <div class="linfo">
          <div class="t">${esc(g ? g.title : a.label)}</div>
          <div class="m">${metaHtml(g ? [g.platform, (g.tags || '').split(' · ')[0], g.publisher] : ['Android game'])}</div>
          ${statsHtml(stats)}
        </div>
        <div class="rbtns">
          ${it.options ? `<div class="rbtn" data-cmd="${attr({ t: 'key', k: 'options' })}"><span class="k">Y</span><span>Options</span></div>` : ''}
          ${a ? `<div class="rbtn" data-cmd="${attr({ t: 'appBottom', pkg: a.pkg })}">${miniGlyph('nds')}<span>Play Here</span></div>` : ''}
        </div>
      </div>
      <div class="desc loading"><p></p><span class="src"></span></div>
      ${sibs.length > 1 ? stripHtml(vm, sibs, label, g ? '' : 'apps') : ''}
    </div>`;
  },

  system(vm, it) {
    const p = it.platform;
    const kids = it.children || [];
    const games = kids.filter(x => x.kind === 'game');
    const emu = kids.find(x => x.kind === 'app');
    const note = kids.find(x => x.kind === 'info');
    return `<div class="page sys">
      <div class="phead">
        <div class="pglyph">${glyph(p.glyph)}</div>
        <div class="pinfo"><div class="kicker">Game Library</div><div class="t big">${esc(p.name)}</div>
          <div class="m">${metaHtml([p.emulator, Fmt.plural(p.games, 'game'), Fmt.size(p.size), p.played ? dur(p.played) + ' played' : ''])}</div></div>
        ${emu ? `<div class="btn" data-cmd="${attr({ t: 'child', id: emu.id, run: true })}">${p.emuIcon ? `<img class="bi" src="${esc(p.emuIcon)}" alt="">` : ''}<span>${esc(p.emulator)}</span></div>` : ''}
      </div>
      <div class="feed sysfeed">${feedHtml(p.feed)}<div class="fj"></div></div>
      ${games.length ? `<div class="grid wide scroll${games.length <= 2 ? ' c2' : games.length <= 6 ? ' c3' : ''}">${games.map(x => tileHtml(x, { t: 'child', id: x.id })).join('')}</div>`
        : `<div class="empty">${glyph(p.glyph)}<b>No ${esc(p.short)} games yet</b><span>${esc(note ? note.sub : '')}</span></div>`}
    </div>`;
  },

  folder(vm, it) {
    const kids = it.children || [];
    const meta = it.meta.length ? it.meta : [it.sub || (it.count != null ? Fmt.plural(it.count, 'item') : '')];
    const head = `<div class="fhead">${iconHtml(it.icon, 'big')}
      <div class="finfo"><div class="kicker">${esc(vm.path.join('  ›  '))}</div><div class="t big">${esc(it.title)}</div>
      <div class="m">${metaHtml(meta)}</div></div>
      <div class="btn primary" data-cmd="${attr(OK)}">Open</div></div>`;
    if (!kids.length) {
      return `<div class="page fold">${head}<div class="empty">${it.loading ? '<div class="spin"></div><b>Loading…</b>' : `${glyph('folder')}<b>Nothing here yet</b>`}</div></div>`;
    }
    const layout = folderLayout(kids);
    const cmd = x => ({ t: 'child', id: x.id });
    const help = vm.cat === 'settings' && !vm.depth ? '<div class="sysinfo compact"></div>'
      : it.help ? `<div class="help fhelp">${esc(it.help)}</div>` : '';
    let body;
    if (layout === 'list') body = `<div class="rows scroll">${kids.map(x => rowHtml(x, cmd(x))).join('')}</div>`;
    else if (layout === 'photos') body = `<div class="grid photos scroll">${kids.map(x => `<div class="ptile" data-cmd="${attr(cmd(x))}">${iconHtml(x.icon)}</div>`).join('')}</div>`;
    else body = `<div class="grid ${layout} scroll">${kids.map(x => tileHtml(x, cmd(x))).join('')}</div>`;
    return `<div class="page fold${help ? ' hashelp' : ''}">${head}${help}${body}</div>`;
  },

  movie(vm, it) {
    const m = it.movie || {};
    const sibs = vm.list.items.filter(x => x.kind === 'movie');
    return `<div class="page mv">
      <div class="mvtop">
        <div class="poster${m.portrait ? '' : ' land'}" ${bgAttr(m.poster || m.hero || '')}></div>
        <div class="mvinfo">
          <div class="kicker">${miniGlyph('googletv')}<span>Google TV · Downloaded</span></div>
          <div class="t big">${esc(it.title)}</div>
          <div class="m">${metaHtml(it.meta)}</div>
          ${it.desc ? `<div class="help">${esc(it.desc)}</div>` : ''}
          <div class="btns"><div class="btn primary xl" data-cmd="${attr(OK)}">${miniGlyph('play')}<span>${esc(it.action || 'Watch')}</span></div></div>
        </div>
      </div>
      ${sibs.length > 1 ? stripHtml(vm, sibs, 'More downloads', 'posters') : ''}
    </div>`;
  },

  video(vm, it) {
    const v = it.video;
    const thumb = v ? `/img/thumb?type=video&id=${v.id}&s=1280` : (it.heroes || [])[0] || (it.icon && it.icon.img) || '';
    const sibs = vm.list.items.filter(x => x.kind === 'video');
    return `<div class="page vd">
      <div class="vtop">
        <div class="vthumb" data-cmd="${attr(OK)}" ${bgAttr(thumb)}>
          <div class="vplay">${miniGlyph('play')}</div>${v ? `<div class="vdur">${esc(Fmt.dur(v.duration))}</div>` : ''}</div>
        <div class="vinfo">
          <div class="kicker">${miniGlyph(it.yt ? 'youtube' : 'video')}<span>${it.yt ? 'YouTube' : 'Video'}</span></div>
          <div class="t">${esc(it.title)}</div>
          <div class="m col">${metaHtml(it.meta)}</div>
          <div class="btns"><div class="btn primary" data-cmd="${attr(OK)}">${miniGlyph('play')}<span>${esc(it.action || 'Play')}</span></div>${it.options ? optBtn() : ''}</div>
        </div>
      </div>
      ${sibs.length > 1 ? stripHtml(vm, sibs, it.yt ? 'More from your channels' : 'More videos', 'wide') : ''}
    </div>`;
  },

  song(vm, it) {
    const s = it.song;
    const sibs = vm.list.items.filter(x => x.kind === 'song');
    const sel = Math.max(0, sibs.findIndex(x => x.id === it.id));
    const from = clamp(sel - 2, 0, Math.max(0, sibs.length - 5));
    return `<div class="page sg">
      <div class="sgtop">
        <div class="sart">${iconHtml({ img: `/img/thumb?type=audio&id=${s.id}&s=512`, style: 'photo', fallback: 'song' })}</div>
        <div class="sinfo">
          <div class="kicker">${miniGlyph('music')}<span>Music</span></div>
          <div class="t big">${esc(s.title)}</div>
          <div class="m">${metaHtml([s.artist, s.album, Fmt.dur(s.duration)])}</div>
          <div class="btns"><div class="btn primary xl" data-cmd="${attr(OK)}">${miniGlyph('play')}<span>Play</span></div>${it.options ? optBtn() : ''}</div>
        </div>
      </div>
      <div class="tracks">${sibs.slice(from, from + 5).map((x, k) => `<div class="trk${x.id === it.id ? ' cur' : ''}" data-cmd="${attr({ t: 'select', id: x.id, run: x.id === it.id })}">
        <span class="tn">${vm.list.off + vm.list.items.indexOf(x) + 1}</span><span class="tt">${esc(x.label)}</span><span class="td">${esc(x.sub || '')}</span></div>`).join('')}</div>
    </div>`;
  },

  photo(vm, it) {
    const p = it.photo;
    const big = p ? `/img/thumb?type=image&id=${p.id}&s=1280` : String(it.icon && it.icon.img || '').replace(/s=\d+|w=\d+/, 's=1280');
    const sibs = vm.list.items.filter(x => x.kind === 'photo');
    return `<div class="page ph${sibs.length > 1 ? '' : ' nostrip'}">
      <div class="phbig" data-cmd="${attr(OK)}"><img src="${esc(big)}" alt="" data-alt="${esc(it.icon && it.icon.img || '')}" onerror="imgAlt(this)">
        <div class="phcap"><b>${esc(it.label)}</b><span>${esc(it.sub || '')}</span></div></div>
      ${sibs.length > 1 ? stripHtml(vm, sibs, '', 'photos') : ''}
    </div>`;
  },

  app(vm, it) {
    const a = it.app;
    const stats = [[a.last ? Fmt.ago(a.last) : 'Not yet', 'Last used'], [dur(a.fgTime), 'Time used'], [a.kindLabel, 'Type']];
    const sibs = vm.list.items.filter(x => x.icon && x.icon.img);
    const sibCls = sibs.every(x => x.kind === 'app') ? 'apps' : '';
    return `<div class="page ap">
      <div class="aptop">
        <div class="apic">${iconHtml({ img: a.icon, style: 'app', fallback: it.icon.glyph || 'apps' })}</div>
        <div class="apinfo">
          <div class="kicker">${esc(vm.path.join('  ›  '))}</div>
          <div class="t big">${esc(it.label)}</div>
          ${it.label !== a.label ? `<div class="m">${esc(a.label)}</div>` : ''}
          <div class="m facts"></div>
          ${statsHtml(stats)}
          <div class="btns">
            <div class="btn primary xl" data-cmd="${attr(OK)}">${miniGlyph('play')}<span>${esc(it.action || 'Open')}</span></div>
            <div class="btn" data-cmd="${attr({ t: 'appBottom', pkg: a.pkg })}">${miniGlyph('nds')}<span>Open Here</span></div>
            ${it.options ? optBtn() : ''}
          </div>
        </div>
      </div>
      ${a.shortcuts.length ? `<div class="sc"><div class="slbl">Shortcuts</div><div class="scrow">${a.shortcuts.map(s =>
        `<div class="scb" data-cmd="${attr({ t: 'shortcut', pkg: a.pkg, id: s.id })}"><img src="/img/shortcut?pkg=${encodeURIComponent(a.pkg)}&amp;id=${encodeURIComponent(s.id)}" alt="" onerror="this.remove()"><span>${esc(s.label)}</span></div>`).join('')}</div></div>` : ''}
      <div class="feed scroll">${feedHtml(a.feed)}<div class="fj"></div></div>
      ${sibs.length > 1 && !a.hasFeed ? stripHtml(vm, sibs, `More in ${vm.path[vm.path.length - 1]}`, sibCls) : ''}
    </div>`;
  },

  friend(vm, it) {
    const f = it.friend || {};
    const people = vm.list.items.filter(x => x.kind === 'friend');
    return `<div class="page fr">
      <div class="frtop">
        <div class="frav">${iconHtml(it.icon)}</div>
        <div class="frinfo">
          <div class="kicker">${esc(f.src || 'Friend')}</div>
          <div class="t big">${esc(it.label)}</div>
          <div class="pres">${it.subHtml || esc(it.sub || '')}</div>
          ${f.game ? `<div class="frgame">${miniGlyph('game')}<span>${esc(f.game)}</span></div>` : ''}
          ${it.action ? `<div class="btns"><div class="btn primary" data-cmd="${attr(OK)}">${esc(it.action)}</div></div>` : ''}
        </div>
      </div>
      ${people.length > 1 ? stripHtml(vm, people, 'Friends', 'people') : ''}
    </div>`;
  },

  notif(vm, it) {
    const n = it.notif || {};
    return `<div class="page nt">
      <div class="nthead">${iconHtml(it.icon, 'mini')}<b>${esc(n.app || '')}</b><span>${esc(Fmt.ago(n.time))}</span></div>
      <div class="t big">${esc(it.label)}</div>
      ${n.text ? `<div class="nttext">${esc(n.text)}</div>` : ''}
      <div class="btns"><div class="btn primary xl" data-cmd="${attr(OK)}">Open</div></div>
    </div>`;
  },

  generic(vm, it) {
    const setting = it.kind === 'setting';
    const label = setting ? 'Change' : it.action === 'Select' ? 'Open' : it.action;
    return `<div class="page gn">
      <div class="gntop">
        <div class="gnic">${iconHtml(it.icon, 'big')}</div>
        <div class="gninfo">
          <div class="kicker">${esc(vm.path.join('  ›  '))}</div>
          <div class="t big">${esc(it.title || it.label)}</div>
          ${setting ? `<div class="value">${esc(it.sub || '')}</div>` : subHtml(it).replace('class="ts"', 'class="m"')}
          ${it.help || it.desc ? `<div class="help">${esc(it.help || it.desc)}</div>` : ''}
          ${it.action ? `<div class="btns"><div class="btn primary" data-cmd="${attr(OK)}">${esc(label)}</div>${it.options ? optBtn() : ''}</div>` : ''}
        </div>
      </div>
      ${siblingRows(vm, it)}
    </div>`;
  },
};

function pageFor(it) {
  if (it.game) return 'game';
  if (it.app) return it.app.isGame ? 'game' : 'app';
  if (it.platform) return 'system';
  if (it.kind === 'folder') return 'folder';
  return Pages[it.kind] ? it.kind : 'generic';
}

// ---------- sheets for the top screen's overlays ----------
const Sheets = {
  options(ov) {
    return `<div class="sh-card opts"><h3>${esc(ov.title)}</h3><div class="olist scroll">${ov.items.map((o, i) =>
      `<div class="orow${i === ov.si ? ' sel' : ''}${o.off ? ' off' : ''}" data-cmd="${attr({ t: 'opt', i })}"><span>${esc(o.label)}</span>${o.checked ? '<b>✓</b>' : ''}</div>`).join('')}</div>
      <div class="sh-foot"><div class="btn ghost" data-cmd="${attr({ t: 'key', k: 'back' })}">Cancel</div></div></div>`;
  },
  dialog(ov) {
    return `<div class="sh-card dlg"><h3>${esc(ov.title)}</h3>${ov.text ? `<p>${esc(ov.text)}</p>` : ''}
      ${ov.input ? '<div class="hint">Type on the top screen</div>' : ''}
      <div class="dbtns">${ov.buttons.map((b, i) => `<div class="btn xl${i === ov.bi ? ' primary' : ''}" data-cmd="${attr({ t: 'dialog', i })}">${esc(b)}</div>`).join('')}</div></div>`;
  },
  music() {
    return `<div class="sh-card mus">
      <div class="mart">${miniGlyph('music')}</div>
      <div class="minfo"><div class="kicker msrc"></div><div class="mt"></div><div class="ma"></div>
        <div class="prog"><i></i></div><div class="times"><span class="tp"></span><span class="td"></span></div>
        <div class="mctl">
          <div class="cb sm c-shuffle" data-act="music:shuffle">${miniGlyph('shuffle')}</div>
          <div class="cb" data-act="music:prev">${miniGlyph('prev')}</div>
          <div class="cb big c-play" data-act="music:toggle">${miniGlyph('play')}</div>
          <div class="cb" data-act="music:next">${miniGlyph('next')}</div>
          <div class="cb sm c-repeat" data-act="music:repeat">${miniGlyph('repeat')}</div>
        </div>
      </div>
      <div class="upnext"><div class="slbl">Up Next</div><div class="qlist"></div></div>
      <div class="sh-close" data-cmd="${attr({ t: 'key', k: 'back' })}">✕</div></div>`;
  },
  video(ov) {
    const c = cmd => attr({ t: 'video', cmd });
    return `<div class="sh-card vid">
      <div class="kicker">${miniGlyph('video')}<span>Now Watching · ${ov.i + 1} of ${ov.n}</span></div>
      <div class="t">${esc(ov.title)}</div>
      <div class="prog"><i></i></div><div class="times"><span class="tp"></span><span class="td"></span></div>
      <div class="mctl">
        <div class="cb" data-cmd="${c('l1')}">${miniGlyph('prev')}</div>
        <div class="cb" data-cmd="${c('left')}"><span class="sk">−10</span></div>
        <div class="cb big" data-cmd="${c('ok')}">${miniGlyph(ov.paused ? 'play' : 'pause')}</div>
        <div class="cb" data-cmd="${c('right')}"><span class="sk">+10</span></div>
        <div class="cb" data-cmd="${c('r1')}">${miniGlyph('next')}</div>
      </div>
      <div class="sh-foot"><div class="btn ghost" data-cmd="${c('back')}">Stop</div></div></div>`;
  },
  photo(ov) {
    return `<div class="sh-card pho">
      <div class="kicker">${miniGlyph('photo')}<span>${ov.i + 1} of ${ov.n}${ov.slide ? ' · Slideshow' : ''}</span></div>
      <div class="t">${esc(ov.title)}</div>
      <div class="grid photos scroll">${ov.ids.map((id, k) => {
        const i = ov.off + k;
        return `<div class="ptile${i === ov.i ? ' cur' : ''}" data-cmd="${attr({ t: 'photo', i })}"><div class="ic photo"><img src="${esc(`/img/thumb?type=image&id=${id}&s=240`)}" alt=""></div></div>`;
      }).join('')}</div>
      <div class="sh-foot"><div class="btn" data-cmd="${attr(OK)}">${ov.slide ? 'Stop Slideshow' : 'Slideshow'}</div>
        <div class="btn ghost" data-cmd="${attr({ t: 'key', k: 'back' })}">Close</div></div></div>`;
  },
};

// ---------- the screen ----------
const Bottom = window.Bottom = {
  vm: null,
  topVisible: true,
  awaySince: 0,
  status: null,
  info: new Map(),
  keys: {},
  lastTouch: Date.now(),
  skipped: 0,

  init() {
    fit();
    addEventListener('resize', fit);
    this.stage = $('#stage');
    this.bg = [$('#bg-a'), $('#bg-b')];
    this.tabs = $('#tabs .tabs');
    this.statusEl = $('#tabs .status');
    this.main = $('#main');
    this.sheet = $('#sheet');
    this.away = $('#away');
    this.dock = $('#dock');
    this.initTouch();
    this.topVisible = !!NB.topVisible();
    if (!this.topVisible) this.awaySince = Date.now();
    try {
      const s = NB.state();
      if (s) this.vm = JSON.parse(s);
    } catch (e) { /* first start */ }
    if (this.vm) Perf.set(this.vm.perf);
    this.render();
    this.pollStatus();
    this.startPolling();
    this.tick();
    setTimeout(() => $('#boot').classList.add('out'), this.vm ? 300 : 2500);
  },

  /* Status every 5 s while the menu is up; every 15 s in Battery Saver or while a game covers it. */
  startPolling() {
    clearInterval(this.pollT);
    this.pollT = setInterval(() => this.pollStatus(), Perf.battery || !this.topVisible ? 15000 : 5000);
  },

  update(vm) {
    const first = !this.vm;
    this.vm = vm;
    if (Perf.set(vm.perf)) this.startPolling();
    this.render();
    if (first) $('#boot').classList.add('out');
  },

  setTopVisible(on) {
    if (on === this.topVisible) return;
    this.topVisible = on;
    this.awaySince = on ? 0 : Date.now();
    this.lastTouch = Date.now();
    this.startPolling();
    this.render();
  },

  onHome() {
    this.poke();
    const sc = this.main.querySelector('.page:not(.out) .scroll');
    if (sc) sc.scrollTop = 0;
  },

  changed(name, value) {
    const k = JSON.stringify(value);
    if (this.keys[name] === k) return false;
    this.keys[name] = k;
    return true;
  },

  render() {
    const vm = this.vm;
    if (vm && vm.accent && this.changed('accent', vm.accent)) {
      document.documentElement.style.setProperty('--accent', vm.accent);
      document.documentElement.style.setProperty('--accent-fg', lightColor(vm.accent) ? '#10131c' : '#fff');
    }
    this.renderTabs();
    this.renderDock();
    if (!this.topVisible) {
      this.renderAway();
    } else {
      this.away.classList.remove('on');
      this.stage.classList.remove('dim');
      this.keys.away = '';
      this.renderSheet();
      this.renderMain();
    }
    this.renderBg();
    this.stage.classList.toggle('away', !this.topVisible);
  },

  renderBg() {
    const vm = this.vm;
    let url = '', blur = true;
    if (!this.topVisible) {
      const l = this.launch();
      url = l ? (l.hero || l.img) : (vm && vm.bg && vm.bg.url) || '';
    } else if (vm && vm.bg) {
      url = vm.bg.url;
    }
    if (!this.changed('bg', [url, blur])) return;
    const [a, b] = this.bg;
    const front = a.classList.contains('on') ? a : b.classList.contains('on') ? b : null;
    const back = front === a ? b : a;
    if (!url) {
      a.classList.remove('on');
      b.classList.remove('on');
      return;
    }
    const key = this.keys.bg;
    Blur.get(url, { w: 160, blur: 3.5, filter: 'saturate(1.35) brightness(.5)' })
      .then(u => ({ u, fx: false }), () => ({ u: url, fx: true }))
      .then(({ u, fx }) => {
        if (this.keys.bg !== key) return;
        back.style.backgroundImage = cssUrl(u);
        back.classList.toggle('fx', fx);
        back.classList.add('on');
        if (front) front.classList.remove('on');
      });
  },

  renderTabs() {
    const vm = this.vm;
    if (!vm || !this.changed('tabs', [vm.cats, vm.cat])) return;
    this.tabs.innerHTML = vm.cats.map(c => `<div class="tab${c.id === vm.cat ? ' on' : ''}" data-cmd="${attr({ t: 'cat', id: c.id })}">` +
      `<div class="tg">${glyph(c.glyph)}</div><div class="tl">${esc(c.label)}</div></div>`).join('');
  },

  renderStatus() {
    const s = this.status;
    if (!s) return;
    const d = new Date();
    const h24 = this.vm && this.vm.h24;
    const html = `<span class="sti wifi${s.online ? '' : ' dim'}">${wifiSvg(s.wifi, s.wifiLevel || 0, s.wifiMax || 4)}</span>` +
      `<span class="sti">${batterySvg(s.battery, s.charging)}</span><span class="pct">${s.battery}%</span>` +
      `<span class="clock">${Fmt.clock(d, h24)}</span>`;
    if (this.changed('status', html)) this.statusEl.innerHTML = html;
  },

  renderMain() {
    const vm = this.vm;
    if (!vm || !vm.item) {
      if (this.changed('page', 'none')) this.swap('<div class="page empty-page"><div class="bl">CROSSBAR</div></div>', true);
      return;
    }
    const it = vm.item;
    if (!this.changed('page', [it, vm.list, vm.path, vm.bg && pageFor(it) === 'game' && !(it.heroes || []).length ? vm.bg.url : ''])) return;
    const same = this.pageId === it.id;
    this.pageId = it.id;
    const page = this.swap(Pages[pageFor(it)](vm, it), !same);
    this.fill(page, vm, it);
  },

  /* Replaces the page; a different item slides in, the same item updates in place. */
  swap(html, animate) {
    const old = this.main.querySelector('.page:not(.out)');
    const holder = el('div', null, html);
    const page = holder.firstElementChild;
    const oldScroll = old && !animate ? [...old.querySelectorAll('.scroll, .srow')].map(e => [e.scrollTop, e.scrollLeft]) : null;
    if (animate) page.classList.add('enter');
    this.main.appendChild(page);
    if (oldScroll) [...page.querySelectorAll('.scroll, .srow')].forEach((e, i) => { if (oldScroll[i]) [e.scrollTop, e.scrollLeft] = oldScroll[i]; });
    if (old) {
      if (animate) {
        old.classList.add('out');
        setTimeout(() => old.remove(), 300);
      } else {
        old.remove();
      }
    }
    if (animate) requestAnimationFrame(() => requestAnimationFrame(() => page.classList.remove('enter')));
    const cur = page.querySelector('.stile.cur, .row.cur');
    if (cur && !oldScroll) centerIn(cur.parentElement, cur);
    return page;
  },

  /* Details that arrive later: game descriptions and system information. */
  async fill(page, vm, it) {
    if (pageFor(it) === 'game') {
      const g = it.game, a = it.app;
      const hero = g && (it.heroes || [])[0];
      if (hero) {
        const im = new Image();
        im.onload = () => { if (im.naturalHeight > im.naturalWidth) $('.bimg', page).classList.add('stacked'); };
        im.src = hero;
      }
      const url = g ? '/api/gameinfo?id=' + encodeURIComponent(g.id)
        : `/api/gameinfo?app=${encodeURIComponent(a.pkg)}&title=${encodeURIComponent(a.label)}`;
      const info = await this.cached('gi:' + (g ? g.id : a.pkg), url, 600000);
      if (!page.isConnected) return;
      const desc = $('.desc', page);
      desc.classList.remove('loading');
      if (info.desc) {
        $('p', desc).textContent = info.desc;
        $('.src', desc).textContent = info.source === 'Wikipedia' ? `From Wikipedia · ${info.wiki || ''}` : 'From Cocoon';
      } else {
        desc.classList.add('none');
        $('p', desc).textContent = g ? `${g.title} for ${g.platform}.` : appFacts(info) || `${a.label} for Android.`;
      }
      if (!g && info.installed) $('.stats', page).outerHTML = statsHtml(appStats(a, info, 'Never', 'Last played', 'Play time'));
      const meta = g ? [g.platform, info.year, info.genre || (g.tags || '').split(' · ')[0], info.developer || info.publisher || g.publisher]
        : ['Android game', info.year];
      $('.linfo .m', page).innerHTML = metaHtml(meta);
      if (info.rating) {
        const r = $('.rating', page);
        r.innerHTML = `<span class="stars">${stars(info.rating)}</span><span>${(info.rating * 5).toFixed(1)}</span>`;
        r.classList.remove('hide');
      }
      if (info.thumb && g && g.small) {
        const c = $('.cover', page);
        c.classList.remove('pixel', 'small');
        c.classList.add('box');
        c.innerHTML = `<div class="bl" data-blur="${esc(info.thumb)}" data-look="cover"></div><img src="${esc(info.thumb)}" alt="">`;
      }
      const banner = $('.banner', page);
      if (info.thumb && banner.classList.contains('blur') && g) blurInto($('.bimg', banner), info.thumb, 'banner');
    } else if (pageFor(it) === 'app') {
      const a = it.app;
      this.fillFeed(page, a.pkg);
      const info = await this.cached('ai:' + a.pkg, '/api/gameinfo?app=' + encodeURIComponent(a.pkg), 600000);
      if (!page.isConnected || !info.installed) return;
      $('.stats', page).outerHTML = statsHtml(appStats(a, info, 'Not yet', 'Last used', 'Time used'));
      $('.facts', page).textContent = appFacts(info);
    } else if (pageFor(it) === 'system') {
      if (it.platform.emuPkg) this.fillFeed(page, it.platform.emuPkg);
    } else if ($('.sysinfo', page)) {
      const s = await this.cached('sys', '/api/sysinfo', 30000);
      if (page.isConnected && s.storageTotal) $('.sysinfo', page).innerHTML = sysinfoHtml(s);
    }
  },

  /* Sections from the app itself, the emulator's save files, or media it played (/api/appfeed). */
  async fillFeed(page, pkg) {
    const f = await this.cached('feed:' + pkg, '/api/appfeed?pkg=' + encodeURIComponent(pkg), 15000);
    const box = page.isConnected && $('.feed .fj', page);
    if (box && f.sections && f.sections.length) box.innerHTML = feedHtml(f.sections);
  },

  async cached(key, url, ttl = 0) {
    const hit = this.info.get(key);
    if (hit && (!ttl || Date.now() - hit.at < ttl)) return hit.p;
    const p = Api.get(url).catch(() => ({}));
    this.info.set(key, { p, at: Date.now() });
    return p;
  },

  renderSheet() {
    const ov = this.vm && this.vm.ov;
    const type = ov && Sheets[ov.type] ? ov.type : '';
    if (!this.changed('sheet', ov)) return;
    this.sheet.classList.toggle('on', !!type);
    this.main.classList.toggle('under', !!type);
    clearInterval(this.sheetTimer);
    if (!type) return;
    const sameType = this.sheetType === type && type === 'music';
    this.sheetType = type;
    if (!sameType) this.sheet.innerHTML = Sheets[type](ov);
    if (type === 'music') {
      this.musicSheet();
      this.sheetTimer = setInterval(() => this.musicSheet(), 1500);
    } else if (type === 'video') {
      this.videoBase = { pos: ov.pos, dur: ov.dur, paused: ov.paused, at: Date.now() };
      this.videoTick();
      this.sheetTimer = setInterval(() => this.videoTick(), 500);
    } else {
      const sel = this.sheet.querySelector('.orow.sel, .ptile.cur');
      if (sel) centerIn(sel.parentElement, sel);
    }
  },

  videoTick() {
    const b = this.videoBase, s = this.sheet;
    if (!b || !$('.vid', s)) return;
    const pos = Math.min(b.dur || Infinity, b.pos + (b.paused ? 0 : (Date.now() - b.at) / 1000));
    $('.prog i', s).style.transform = `scaleX(${b.dur ? (pos / b.dur).toFixed(4) : 0})`;
    setText($('.tp', s), Fmt.dur(pos * 1000));
    setText($('.td', s), b.dur ? Fmt.dur(b.dur * 1000) : '');
  },

  async musicSheet() {
    let np;
    try { np = await Api.get('/api/nowplaying'); } catch (e) { return; }
    const s = this.sheet;
    if (!$('.mus', s)) return;
    if (this.status) this.status.np = np;
    const src = npSource(np);
    $('.msrc', s).textContent = !src ? 'Music' : src.src === 'local' ? `CrossBar Music · ${src.index + 1} of ${src.count}` : `Playing in ${src.app}`;
    $('.mt', s).textContent = src ? src.title : 'Nothing playing';
    $('.ma', s).textContent = src ? [src.artist, src.album].filter(Boolean).join(' · ') : 'Pick songs in Music, or start music in any app';
    $('.c-play', s).innerHTML = miniGlyph(src && src.playing ? 'pause' : 'play');
    const local = src && src.src === 'local';
    $('.c-shuffle', s).className = `cb sm c-shuffle${local ? (src.shuffle ? ' on' : '') : ' gone'}`;
    $('.c-repeat', s).className = `cb sm c-repeat${local ? (src.repeat ? ' on' : '') : ' gone'}`;
    $('.c-repeat', s).dataset.mode = local && src.repeat === 2 ? '1' : '';
    $('.prog i', s).style.transform = `scaleX(${src && src.duration ? (src.position / src.duration).toFixed(4) : 0})`;
    $('.tp', s).textContent = src ? Fmt.dur(src.position) : '';
    $('.td', s).textContent = src && src.duration ? Fmt.dur(src.duration) : '';
    const artKey = src ? src.src + '|' + src.title + '|' + (src.id || '') : '';
    if (artKey !== this.musicArt) {
      this.musicArt = artKey;
      const art = $('.mart', s);
      if (!src) {
        art.style.backgroundImage = '';
        art.innerHTML = miniGlyph('music');
      } else {
        const url = '/img/np?k=' + encodeURIComponent(artKey) + '&ts=' + Date.now();
        const img = new Image();
        img.onload = () => { art.innerHTML = ''; art.style.backgroundImage = cssUrl(url); };
        img.onerror = () => { art.style.backgroundImage = ''; art.innerHTML = miniGlyph('song'); };
        img.src = url;
      }
    }
    const q = $('.qlist', s);
    if (!local) {
      if (this.changed('queue', 'none')) q.innerHTML = `<div class="qempty">${src ? 'Controls work with ' + esc(src.app) : 'Nothing queued'}</div>`;
      return;
    }
    try {
      const [queue, songs] = await Promise.all([Api.get('/api/queue'), this.songs()]);
      const byId = new Map(songs.map(x => [x.id, x]));
      const next = queue.ids.slice(queue.index + 1, queue.index + 7).map(id => byId.get(id)).filter(Boolean);
      const html = next.length ? next.map((x, k) => `<div class="q"><span class="qn">${queue.index + k + 2}</span><span class="qt">${esc(x.title)}</span><span class="qa">${esc(x.artist || '')}</span></div>`).join('')
        : '<div class="qempty">End of the queue</div>';
      if (this.changed('queue', html)) q.innerHTML = html;
    } catch (e) { /* queue unavailable */ }
  },

  songs() {
    if (!this.songList) this.songList = Api.get('/api/music').catch(() => { this.songList = null; return []; });
    return this.songList;
  },

  /* What was just started on the top screen, if the top screen was covered because of it. */
  launch() {
    const l = this.vm && this.vm.launch;
    if (!l) return null;
    return Math.abs((this.awaySince || Date.now()) - l.at) < 20000 ? l : null;
  },

  renderAway() {
    const l = this.launch();
    this.away.classList.add('on');
    this.sheet.classList.remove('on');
    this.main.classList.add('under');
    clearInterval(this.sheetTimer);
    this.keys.sheet = '';
    this.keys.page = '';
    if (!this.changed('away', l)) return this.tick();
    const art = l && l.img
      ? `<div class="awart${l.pixel ? ' pixel' : ''}${l.small ? ' small' : ''}${l.appIcon ? ' appicon' : ''}"><div class="bl" data-blur="${esc(l.img)}" data-look="cover"></div><img src="${esc(l.img)}" alt="" data-fb="${esc(l.glyph || 'game')}" onerror="imgFail(this)"></div>`
      : `<div class="awart glyph">${glyph(l ? l.glyph || 'game' : 'home')}</div>`;
    this.away.innerHTML = `<div class="aw">
      <div class="awcard">${art}
        <div class="awinfo"><div class="kicker">${l ? 'Now Playing' : 'CrossBar'}</div>
          <div class="t">${esc(l ? l.title.replace(/^Open (?=\S)/, '') : 'Away from the menu')}</div>
          <div class="m">${esc(l ? l.sub : 'The menu is in the background')}</div>
          <div class="since"></div></div>
      </div>
      <div class="awclock"><div class="time"></div><div class="date"></div><div class="bat"></div></div>
      <div class="awbtns"><div class="btn" data-act="showTop">${miniGlyph('home')}<span>Return to CrossBar</span></div></div>
    </div>`;
    this.tick();
  },

  /* Clocks show minutes, so this runs as each minute starts (and whenever the away card changes). */
  tick() {
    clearTimeout(this.tickT);
    const now = new Date();
    this.tickT = setTimeout(() => this.tick(), 60500 - now.getSeconds() * 1000 - now.getMilliseconds());
    this.renderStatus();
    if (this.topVisible) return;
    const d = new Date(), h24 = this.vm && this.vm.h24;
    const set = (sel, v) => { const e = $(sel, this.away); if (e) e.textContent = v; };
    set('.time', Fmt.clock(d, h24).replace(/ [AP]M$/, ''));
    set('.date', d.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' }));
    const s = this.status;
    if (s) set('.bat', `Battery ${s.battery}%${s.charging ? ' · Charging' : ''}`);
    const l = this.launch();
    const since = $('.since', this.away);
    if (since && l) {
      const started = new Date(l.at);
      const age = Date.now() - l.at;
      since.innerHTML = `<i class="live"></i><span>Started ${esc(Fmt.clock(started, h24))} · ${esc(age < 60000 ? 'just now' : dur(age))}</span>`;
    }
    this.stage.classList.toggle('dim', Date.now() - this.lastTouch > 120000);
  },

  async pollStatus() {
    if (this.stage.classList.contains('dim') && ++this.skipped % 3) return;
    try {
      this.status = await Api.get('/api/status');
    } catch (e) { return; }
    this.renderStatus();
    this.renderDock();
  },

  renderDock() {
    const s = this.status || {};
    const src = npSource(s.np);
    const friends = (this.vm && this.vm.friends) || [];
    const away = !this.topVisible;
    const key = [src && [src.src, src.title, src.artist, src.playing, src.id], friends, s.friends, s.notifs, away];
    if (!this.changed('dock', key)) return;
    const artKey = src ? src.src + '|' + src.title + '|' + (src.id || '') : '';
    const av = friends.slice(0, 3).map(f => f.avatar ? `<img class="av" src="${esc(f.avatar)}" alt="" onerror="this.remove()">` : `<span class="av">${esc((f.name || '?').charAt(0))}</span>`).join('');
    const playing = friends.filter(f => f.game).length;
    const online = Math.max(s.friends || 0, friends.length);
    this.dock.innerHTML = `
      <div class="dk music" data-act="music:open">
        <div class="art">${src ? `<img src="/img/np?k=${encodeURIComponent(artKey)}" alt="" data-fb="music" onerror="imgFail(this)">` : miniGlyph('music')}</div>
        <div class="txt"><b>${esc(src ? src.title : 'Music')}</b><span>${esc(src ? (src.artist || src.app || '') : 'Nothing playing')}</span></div>
        <div class="ctl"><i class="pp" data-act="music:toggle">${miniGlyph(src && src.playing ? 'pause' : 'play')}</i><i data-act="music:next">${miniGlyph('next')}</i></div>
      </div>
      <div class="dk friends${away ? ' inert' : ''}" data-cmd="${attr({ t: 'cat', id: 'friends' })}">
        <div class="avs">${av || `<span class="av glyph">${miniGlyph('friends')}</span>`}</div>
        <div class="txt"><b>${online ? online + ' online' : 'Friends'}</b><span>${playing ? playing + ' playing' : online ? 'Tap to see' : 'Nobody online'}</span></div>
      </div>
      <div class="dk icon${away ? ' inert' : ''}" data-cmd="${attr({ t: 'goto', cat: 'friends', path: ['friends:notifs'] })}">${miniGlyph('bell')}${s.notifs ? `<span class="n">${s.notifs}</span>` : ''}<span class="l">Alerts</span></div>
      <div class="dk icon" ${away ? 'data-act="showTop"' : `data-cmd="${attr({ t: 'home' })}"`}>${miniGlyph('home')}<span class="l">Home</span></div>`;
  },

  musicCmd(cmd) {
    const src = npSource(this.status && this.status.np);
    if (cmd === 'open') {
      if (this.topVisible) send({ t: 'music', cmd: 'open' });
      return;
    }
    if (!src) {
      if (cmd === 'toggle' && this.topVisible) send({ t: 'music', cmd: 'open' });
      return;
    }
    if (src.src === 'external') {
      if (cmd === 'toggle' || cmd === 'next' || cmd === 'prev') NB.mediaControl(src.pkg, cmd);
    } else {
      NB.music(cmd, '');
    }
    setTimeout(() => this.pollStatus(), 350);
    if (this.sheetType === 'music' && this.sheet.classList.contains('on')) setTimeout(() => this.musicSheet(), 350);
  },

  act(a) {
    const [k, v] = a.split(':');
    if (k === 'music') return this.musicCmd(v);
    if (k === 'showTop') return NB.showTop();
    return null;
  },

  toast(msg) {
    const t = $('#toast');
    t.textContent = msg;
    t.classList.add('on');
    clearTimeout(this.toastT);
    this.toastT = setTimeout(() => t.classList.remove('on'), 2600);
  },

  poke() {
    this.lastTouch = Date.now();
    this.stage.classList.remove('dim');
  },

  initTouch() {
    let pressed = null;
    const target = e => e.target.closest('[data-cmd], [data-act]');
    this.stage.addEventListener('pointerdown', e => {
      const wasDim = this.stage.classList.contains('dim');
      this.poke();
      if (wasDim) { this.swallow = true; return; }
      this.swallow = false;
      pressed = target(e);
      if (pressed) pressed.classList.add('press');
    }, { passive: true });
    const release = () => { if (pressed) pressed.classList.remove('press'); pressed = null; };
    this.stage.addEventListener('pointerup', release, { passive: true });
    this.stage.addEventListener('pointercancel', release, { passive: true });
    this.stage.addEventListener('click', e => {
      if (this.swallow) return;
      const t = target(e);
      if (!t || t.closest('.inert')) return;
      e.stopPropagation();
      NB.haptic(8);
      if (t.dataset.act) return this.act(t.dataset.act);
      let cmd;
      try { cmd = JSON.parse(t.dataset.cmd); } catch (err) { return; }
      if (cmd.t === 'appBottom') {
        const err = NB.launchHere(cmd.pkg);
        if (err) this.toast(err);
        return;
      }
      if (!this.topVisible) return;
      send(cmd);
    });
  },
};

Bottom.init();
