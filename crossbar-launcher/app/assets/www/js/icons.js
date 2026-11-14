'use strict';
/* XMB-style white glyphs (64x64). Dark details are drawn as translucent black on the white shape. */

const D = 'fill="#000" fill-opacity=".34"';
const DS = 'fill="none" stroke="#000" stroke-opacity=".34"';

function grid(cols, rows, cell, ox, oy, pattern) {
  let s = '';
  pattern.forEach((row, y) => [...row].forEach((ch, x) => {
    if (ch === 'X') s += `<rect x="${ox + x * cell}" y="${oy + y * cell}" width="${cell + .2}" height="${cell + .2}"/>`;
  }));
  return s;
}

const GLYPHS = {
  // ---- categories ----
  settings: `<path d="M22 11h20a4 4 0 0 1 4 4v7h-6v-5H24v5h-6v-7a4 4 0 0 1 4-4z"/><rect x="5" y="22" width="54" height="33" rx="5"/><rect x="5" y="33" width="54" height="3" ${D}/><rect x="26" y="29" width="12" height="11" rx="2" ${D}/>`,
  photo: `<path d="M7 22a5 5 0 0 1 5-5h8l4-6h16l4 6h8a5 5 0 0 1 5 5v25a5 5 0 0 1-5 5H12a5 5 0 0 1-5-5z"/><circle cx="32" cy="34" r="12" ${D}/><circle cx="32" cy="34" r="7.5"/><circle cx="50" cy="24" r="2.6" ${D}/>`,
  music: `<path d="M21 14 52 6v9L21 23z"/><rect x="21" y="15" width="4.5" height="32"/><rect x="47.5" y="7" width="4.5" height="32"/><ellipse cx="15.5" cy="47.5" rx="9.5" ry="7.2" transform="rotate(-22 15.5 47.5)"/><ellipse cx="42" cy="39.5" rx="9.5" ry="7.2" transform="rotate(-22 42 39.5)"/>`,
  video: `<rect x="10" y="6" width="44" height="52" rx="3"/><g ${D}>${[10, 19.5, 29, 38.5, 48].map(y => `<rect x="13" y="${y}" width="5" height="5.5" rx="1"/><rect x="46" y="${y}" width="5" height="5.5" rx="1"/>`).join('')}<rect x="22" y="10" width="20" height="19" rx="1.5"/><rect x="22" y="35" width="20" height="19" rx="1.5"/></g>`,
  tv: `<rect x="5" y="11" width="54" height="36" rx="4"/><rect x="10" y="16" width="44" height="26" rx="2" ${D}/><path d="M24 53h16l-2-6H26z"/><rect x="17" y="52" width="30" height="4.5" rx="2.2"/>`,
  game: `<path d="M18 17h28c7.5 0 11.5 5 13.5 12.5l3 14.5c1.2 6.2-3 10.5-8.2 10.5-4 0-6.2-2.8-8.2-6.2L43 43H21l-3.1 5.3c-2 3.4-4.2 6.2-8.2 6.2-5.2 0-9.4-4.3-8.2-10.5l3-14.5C6.5 22 10.5 17 18 17z"/><g ${D}><rect x="12" y="27.5" width="13" height="4.5" rx="1"/><rect x="16.25" y="23.25" width="4.5" height="13" rx="1"/><circle cx="46" cy="24.5" r="2.8"/><circle cx="52" cy="30" r="2.8"/><circle cx="40" cy="30" r="2.8"/><circle cx="46" cy="35.5" r="2.8"/><circle cx="25" cy="42" r="4.2"/><circle cx="39" cy="42" r="4.2"/></g>`,
  apps: [0, 1, 2].map(i => [0, 1, 2].map(j => `<rect x="${7 + i * 17.5}" y="${7 + j * 17.5}" width="15" height="15" rx="4"/>`).join('')).join(''),
  network: `<circle cx="32" cy="32" r="25"/><g ${DS} stroke-width="2.6"><ellipse cx="32" cy="32" rx="10.5" ry="25"/><path d="M7 32h50M10.5 19.5h43M10.5 44.5h43M32 7v50"/></g>`,
  friends: `<circle cx="22" cy="25" r="15" fill-opacity=".72"/><g ${D}><circle cx="16.5" cy="22" r="1.9"/><circle cx="26" cy="22" r="1.9"/></g><circle cx="41" cy="38" r="18" fill="#000" fill-opacity=".28"/><circle cx="41" cy="38" r="15.5"/><g ${D}><circle cx="35.5" cy="34.5" r="2.2"/><circle cx="46.5" cy="34.5" r="2.2"/></g><path d="M34.5 41.5q6.5 6.5 13 0" ${DS} stroke-width="2.8" stroke-linecap="round"/>`,
  // ---- generic ----
  folder: `<path d="M5 15a4 4 0 0 1 4-4h15l5 5h26a4 4 0 0 1 4 4v30a4 4 0 0 1-4 4H9a4 4 0 0 1-4-4z"/><path d="M5 23h54v27a4 4 0 0 1-4 4H9a4 4 0 0 1-4-4z" fill="#000" fill-opacity=".1"/>`,
  user: `<circle cx="32" cy="21" r="12"/><path d="M8 57c1-13 11-21 24-21s23 8 24 21z"/>`,
  info: `<circle cx="32" cy="32" r="25"/><g ${D}><circle cx="32" cy="19.5" r="3.6"/><rect x="28.8" y="27" width="6.4" height="20" rx="2"/></g>`,
  warn: `<path d="M32 6 60 54H4z" stroke-linejoin="round"/><g ${D}><rect x="29" y="22" width="6" height="17" rx="2"/><circle cx="32" cy="46" r="3.4"/></g>`,
  star: `<path d="m32 6 7.6 16.2L57 24.6 44.2 37l3.1 17.6L32 46.3l-15.3 8.3L19.8 37 7 24.6l17.4-2.4z"/>`,
  clock: `<circle cx="32" cy="32" r="25"/><path d="M32 16v17l11 7" ${DS} stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"/>`,
  refresh: `<path d="M32 8a24 24 0 1 0 23.4 29.3h-7.3A17 17 0 1 1 32 15c4.6 0 8.8 1.8 11.9 4.8L37 26.7h20V6.8l-7.9 7.9A23.9 23.9 0 0 0 32 8z"/>`,
  search: `<circle cx="27" cy="27" r="17"/><circle cx="27" cy="27" r="10.5" ${D}/><rect x="38" y="35" width="8" height="24" rx="3" transform="rotate(-45 42 47)"/>`,
  palette: `<path d="M32 6C17 6 6 17 6 31c0 13 10 23 22 23 4 0 5-2.4 4-5-1.2-3 .6-6 4-6h7c8 0 15-6 15-15C58 15 46 6 32 6z"/><g ${D}><circle cx="20" cy="27" r="4.5"/><circle cx="29" cy="17" r="4.5"/><circle cx="41" cy="18" r="4.5"/><circle cx="48" cy="28" r="4.5"/></g>`,
  sound: `<path d="M8 24h10l14-12v40L18 40H8z"/><g ${DS} stroke-width="4" stroke-linecap="round" fill="none" stroke="#fff" stroke-opacity="1"><path d="M40 23q6 9 0 18"/><path d="M46 16q11 16 0 32"/></g>`,
  display: `<circle cx="32" cy="32" r="11"/><g stroke="#fff" stroke-width="4.5" stroke-linecap="round">${[0, 45, 90, 135, 180, 225, 270, 315].map(a => `<path d="M32 9v6" transform="rotate(${a} 32 32)"/>`).join('')}</g>`,
  stick: `<circle cx="32" cy="32" r="18"/><circle cx="32" cy="32" r="13" ${D}/><circle cx="32" cy="32" r="9.5"/><circle cx="32" cy="32" r="5" ${D}/><g stroke="#fff" stroke-width="4" stroke-linecap="round">${[0, 45, 90, 135, 180, 225, 270, 315].map(a => `<path d="M32 4.5v4.5" transform="rotate(${a} 32 32)"/>`).join('')}</g>`,
  wifi: `<path d="M32 50a5 5 0 1 0 0 .1z"/><g fill="none" stroke="#fff" stroke-width="5.5" stroke-linecap="round"><path d="M21 39a15 15 0 0 1 22 0"/><path d="M13 30a26 26 0 0 1 38 0"/><path d="M5 21a37 37 0 0 1 54 0"/></g>`,
  bluetooth: `<path d="M30 5v21.5L18.5 15l-4 4L28 32 14.5 45l4 4L30 37.5V59h3l14-14-11-13 11-13L33 5zm5.5 10.6 4.8 4.4-4.8 4.6zm0 23.6 4.8 4.6-4.8 4.4z"/>`,
  battery: `<rect x="4" y="18" width="50" height="28" rx="5"/><rect x="55" y="26" width="5" height="12" rx="2"/><rect x="9" y="23" width="40" height="18" rx="2" ${D}/>`,
  storage: `<ellipse cx="32" cy="14" rx="22" ry="8"/><path d="M10 14v12c0 4.4 9.8 8 22 8s22-3.6 22-8V14c0 4.4-9.8 8-22 8s-22-3.6-22-8z"/><path d="M10 32v12c0 4.4 9.8 8 22 8s22-3.6 22-8V32c0 4.4-9.8 8-22 8s-22-3.6-22-8z"/>`,
  key: `<circle cx="21" cy="32" r="14"/><circle cx="17" cy="32" r="4.5" ${D}/><path d="M33 28h26v8h-5v7h-7v-7h-4v4h-6v-4h-4z"/>`,
  bell: `<path d="M32 6c-1.7 0-3 1.3-3 3v1.5C21 12 16 19 16 27v12l-5 6v3h42v-3l-5-6V27c0-8-5-15-13-16.5V9c0-1.7-1.3-3-3-3z"/><path d="M26 52a6 6 0 0 0 12 0z"/>`,
  chat: `<path d="M10 10h44a5 5 0 0 1 5 5v26a5 5 0 0 1-5 5H28L16 56V46h-6a5 5 0 0 1-5-5V15a5 5 0 0 1 5-5z"/><g ${D}><circle cx="21" cy="28" r="3.3"/><circle cx="32" cy="28" r="3.3"/><circle cx="43" cy="28" r="3.3"/></g>`,
  power: `<path d="M28.5 6h7v26h-7z"/><path d="M19 13.5l4.4 5.4a17 17 0 1 0 17.2 0l4.4-5.4a24 24 0 1 1-26 0z"/>`,
  home: `<path d="M32 7 5 30h8v25h15V40h8v15h15V30h8z"/>`,
  shuffle: `<g fill="none" stroke="#fff" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 18h9c12 0 18 28 30 28h7"/><path d="M6 46h9c5 0 8.6-5 11.8-10.6M33 26c3-4.8 6.6-8 12-8h7"/></g><path d="M50 10l10 8-10 8zM50 38l10 8-10 8z"/>`,
  repeat: `<g fill="none" stroke="#fff" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"><path d="M10 34v-6a10 10 0 0 1 10-10h30"/><path d="M54 30v6a10 10 0 0 1-10 10H14"/></g><path d="M48 9l10 9-10 9zM16 37 6 46l10 9z"/>`,
  play: `<path d="M18 9 54 32 18 55z"/>`,
  pause: `<rect x="15" y="10" width="12" height="44" rx="2"/><rect x="37" y="10" width="12" height="44" rx="2"/>`,
  next: `<path d="M8 12 34 32 8 52z"/><path d="M31 12 57 32 31 52z"/>`,
  prev: `<path d="M56 12 30 32l26 20z"/><path d="M33 12 7 32l26 20z"/>`,
  album: `<circle cx="32" cy="32" r="26"/><circle cx="32" cy="32" r="9" ${D}/><circle cx="32" cy="32" r="3"/><path d="M32 12a20 20 0 0 1 20 20" ${DS} stroke-width="2"/>`,
  artist: `<rect x="23" y="5" width="18" height="30" rx="9"/><path d="M15 27a17 17 0 0 0 34 0" fill="none" stroke="#fff" stroke-width="4.5"/><rect x="29.5" y="44" width="5" height="10"/><rect x="20" y="54" width="24" height="5" rx="2.5"/>`,
  song: `<path d="M26 8h26v10H32v28a10 10 0 1 1-6-9.2z"/>`,
  film: `<path d="M6 22h52v30a4 4 0 0 1-4 4H10a4 4 0 0 1-4-4z"/><path d="m6 14 48-9 1.5 8-48 9z"/><g ${D}><path d="m14 12.5 8 6.4 6-1.1-8-6.4zM30 9.5l8 6.4 6-1.1-8-6.4z"/></g>`,
  image: `<rect x="5" y="10" width="54" height="44" rx="5"/><g ${D}><circle cx="21" cy="24" r="5"/><path d="M9 50l15-17 9 10 8-7 14 14z"/></g>`,
  globe: '',
  check: `<path d="M9 33l8-8 9 9 21-21 8 8-29 29z"/>`,
  trash: `<rect x="10" y="12" width="44" height="7" rx="2"/><rect x="24" y="6" width="16" height="7" rx="2"/><path d="M14 22h36l-3 34H17z"/>`,
  link: `<g fill="none" stroke="#fff" stroke-width="6" stroke-linecap="round"><path d="M27 37a10 10 0 0 0 14 0l9-9a10 10 0 0 0-14-14l-3 3"/><path d="M37 27a10 10 0 0 0-14 0l-9 9a10 10 0 0 0 14 14l3-3"/></g>`,
  // ---- emulators (same design language) ----
  psp: `<path d="M14 17h36c8.5 0 13 6.5 13 15s-4.5 15-13 15H14C5.5 47 1 40.5 1 32s4.5-15 13-15z"/><rect x="16.5" y="21.5" width="31" height="21" rx="1.5" ${D}/><g ${D}><rect x="4" y="30.5" width="9" height="3" rx="1"/><rect x="7" y="27.5" width="3" height="9" rx="1"/><circle cx="55" cy="27.6" r="1.7"/><circle cx="58.6" cy="32" r="1.7"/><circle cx="51.4" cy="32" r="1.7"/><circle cx="55" cy="36.4" r="1.7"/><rect x="25" y="44" width="14" height="1.6" rx=".8"/></g>`,
  ps2: `<path d="M17 4h28l3 3v47H14V7z"/><rect x="11" y="54" width="40" height="5" rx="2.5"/><g ${D}><rect x="14" y="17" width="34" height="3"/><rect x="21" y="9" width="20" height="2.2" rx="1.1"/><rect x="21" y="38" width="3.5" height="11" rx="1"/><rect x="27" y="38" width="3.5" height="11" rx="1"/><circle cx="40" cy="45" r="2.6"/></g>`,
  psx: `<rect x="5" y="16" width="54" height="32" rx="3"/><g ${D}><circle cx="23" cy="32" r="10"/><rect x="40" y="22" width="13" height="3" rx="1"/><circle cx="44" cy="39" r="2.4"/><circle cx="51" cy="39" r="2.4"/></g><circle cx="23" cy="32" r="3"/>`,
  nds: `<rect x="12" y="4" width="40" height="27" rx="4"/><rect x="12" y="33" width="40" height="27" rx="4"/><g ${D}><rect x="19" y="8.5" width="26" height="18" rx="1"/><rect x="22" y="37.5" width="20" height="16" rx="1"/><rect x="14.3" y="44.2" width="5.4" height="2"/><rect x="16" y="42.5" width="2" height="5.4"/><circle cx="47" cy="42.5" r="1.4"/><circle cx="49.6" cy="45.2" r="1.4"/><circle cx="44.4" cy="45.2" r="1.4"/><circle cx="47" cy="47.9" r="1.4"/></g>`,
  n3ds: `<rect x="9" y="4" width="46" height="27" rx="4"/><rect x="12" y="33" width="40" height="27" rx="4"/><g ${D}><rect x="14" y="8" width="36" height="19" rx="1"/><rect x="21" y="37" width="22" height="16.5" rx="1"/><circle cx="16.8" cy="40.5" r="2.7"/><rect x="14.3" y="47.6" width="5" height="1.8"/><rect x="15.9" y="46" width="1.8" height="5"/><circle cx="47.5" cy="41" r="1.3"/><circle cx="49.9" cy="43.4" r="1.3"/><circle cx="45.1" cy="43.4" r="1.3"/><circle cx="47.5" cy="45.8" r="1.3"/></g>`,
  switch: `<path d="M14 14h5.5v36H14a8 8 0 0 1-8-8V22a8 8 0 0 1 8-8z"/><path d="M44.5 14H50a8 8 0 0 1 8 8v20a8 8 0 0 1-8 8h-5.5z"/><rect x="21" y="14" width="22" height="36" rx="1.5"/><g ${D}><rect x="23" y="17" width="18" height="30" rx="1"/><circle cx="13" cy="23.5" r="3.1"/><circle cx="51" cy="38.5" r="3.1"/><circle cx="13" cy="34.6" r="1.45"/><circle cx="13" cy="41.4" r="1.45"/><circle cx="9.6" cy="38" r="1.45"/><circle cx="16.4" cy="38" r="1.45"/><circle cx="51" cy="20.6" r="1.45"/><circle cx="51" cy="27.4" r="1.45"/><circle cx="47.6" cy="24" r="1.45"/><circle cx="54.4" cy="24" r="1.45"/></g>`,
  gba: `<path d="M12 16h40c5 0 8 4 9 9l2 10c1 6-3 11-8 11-3 0-5-2-7-4H16c-2 2-4 4-7 4-5 0-9-5-8-11l2-10c1-5 4-9 9-9z"/><g ${D}><rect x="21" y="19" width="22" height="16" rx="1"/><rect x="7" y="29" width="8" height="2.8" rx="1"/><rect x="9.6" y="26.4" width="2.8" height="8" rx="1"/><circle cx="51" cy="28" r="2.2"/><circle cx="56" cy="31" r="2.2"/></g>`,
  gb: `<rect x="13" y="4" width="38" height="56" rx="4"/><g ${D}><rect x="18" y="9" width="28" height="22" rx="1.5"/><rect x="17" y="41" width="10" height="3.3" rx="1"/><rect x="20.35" y="37.6" width="3.3" height="10" rx="1"/><circle cx="41" cy="40" r="2.6"/><circle cx="46" cy="37" r="2.6"/><rect x="26" y="52" width="5" height="2" rx="1" transform="rotate(-25 28 53)"/><rect x="33" y="52" width="5" height="2" rx="1" transform="rotate(-25 35 53)"/></g>`,
  retro: `<rect x="4" y="20" width="56" height="26" rx="4"/><g ${D}><rect x="10" y="31" width="11" height="4" rx="1"/><rect x="13.5" y="27.5" width="4" height="11" rx="1"/><circle cx="45" cy="35" r="3.4"/><circle cx="53" cy="31" r="3.4"/><rect x="26" y="31" width="5" height="2.4" rx="1.2"/><rect x="33" y="31" width="5" height="2.4" rx="1.2"/></g>`,
  retroarch: grid(11, 8, 5, 4.5, 12, ['..X.....X..', '...X...X...', '..XXXXXXX..', '.XX.XXX.XX.', 'XXXXXXXXXXX', 'X.XXXXXXX.X', 'X.X.....X.X', '...XX.XX...']),
  joiplay: `<path d="M32 5l21 7v15c0 15-9 25-21 32C20 52 11 42 11 27V12z"/><path d="M32 12.5l13.5 4.6v10c0 9.6-5.6 16.6-13.5 21.6z" ${D}/><path d="m32 21 2.6 5.4 5.9.8-4.3 4.1 1 5.8L32 34.4l-5.2 2.7 1-5.8-4.3-4.1 5.9-.8z"/>`,
  android: `<rect x="3" y="15" width="58" height="34" rx="7"/><rect x="10" y="20" width="44" height="24" rx="2" ${D}/><path d="M24.5 25.5h15c3 0 5 2 6 5l1.2 6.2c.4 3-1.3 4.8-3.4 4.8-1.6 0-2.6-1.2-3.4-2.5l-1.3-2.2H25.4l-1.3 2.2c-.8 1.3-1.8 2.5-3.4 2.5-2.1 0-3.8-1.8-3.4-4.8l1.2-6.2c1-3 3-5 6-5z"/>`,
  // ---- apps / services ----
  youtube: `<rect x="3" y="13" width="58" height="39" rx="12"/><path d="M26.5 23.5 42 32.5 26.5 41.5z" ${D}/>`,
  googletv: `<rect x="5" y="10" width="54" height="36" rx="5"/><path d="M26 19.5 40 28l-14 8.5z" ${D}/><rect x="19" y="50" width="26" height="4.5" rx="2.2"/>`,
  ytmusic: `<circle cx="32" cy="32" r="26"/><circle cx="32" cy="32" r="14.5" ${DS} stroke-width="3"/><path d="M27.5 24.5 39 32l-11.5 7.5z" ${D}/>`,
  x: `<path d="M8 7h13.5l34.5 50H42.5z"/><path d="M49.5 7h6.5L36.2 29.8l-3.6-4.6z"/><path d="M8 57 28.8 33.2l3.6 4.6L15 57z"/>`,
  discord: `<path d="M17 13c5-2.2 9-3.2 11.5-3.5l1.6 3.2c1.2-.2 2.6-.2 3.8 0L35.5 9.5c2.5.3 6.5 1.3 11.5 3.5 5.5 8.3 8.3 17.3 7.3 27.6-4.3 3.2-8.4 5-12.4 6.3l-3-4.2c1.9-.7 3.5-1.5 5-2.5-.4-.3-.8-.6-1.2-.9-7.5 3.5-15.9 3.5-23.4 0-.4.3-.8.6-1.2.9 1.5 1 3.1 1.8 5 2.5l-3 4.2c-4-1.3-8.1-3.1-12.4-6.3-1-10.3 1.8-19.3 7.3-27.6z"/><g ${D}><ellipse cx="24.5" cy="31.5" rx="4.3" ry="4.8"/><ellipse cx="39.5" cy="31.5" rx="4.3" ry="4.8"/></g>`,
  steam: `<circle cx="32" cy="32" r="26"/><g ${D}><circle cx="41" cy="24" r="8.8"/><circle cx="22.5" cy="41" r="6.2"/><path d="M6.5 35.5 22 42l2.2-5.4L8.5 30z"/><path d="m27 37.5 10.5-8.4 3.4 3.6-10.6 8.4z"/></g><circle cx="41" cy="24" r="5"/>`,
  tiktok: `<path d="M35 6h8c.6 6 4.6 10.3 11 11v8c-4.4 0-8-1.3-11-3.4V41c0 9.4-7.6 17-17 17s-17-7.6-17-17 7.6-17 17-17c.9 0 1.7.1 2.6.2v8.4c-.8-.3-1.7-.4-2.6-.4-4.7 0-8.6 3.9-8.6 8.8s3.9 8.8 8.6 8.8 9-3.9 9-8.8z"/>`,
  tubi: `<rect x="5" y="12" width="54" height="40" rx="10"/><path d="M24 22h16v5h-5.5v15h-5V27H24z" ${D}/>`,
  browser: `<circle cx="32" cy="32" r="25"/><g ${DS} stroke-width="2.6"><ellipse cx="32" cy="32" rx="10.5" ry="25"/><path d="M7 32h50M10.5 19.5h43M10.5 44.5h43"/></g>`,
  drive: `<path d="M23 8h18l20 34-9 15z"/><path d="M21 11 3 42l9 15 18-31z" fill-opacity=".75"/><path d="M14 57h36l9-15H23z" fill-opacity=".88"/>`,
  cast: `<path d="M6 12h52a4 4 0 0 1 4 4v32a4 4 0 0 1-4 4H38v-6h18V18H8v6H2v-8a4 4 0 0 1 4-4z"/><path d="M2 30a22 22 0 0 1 22 22h-6A16 16 0 0 0 2 36zM2 41a11 11 0 0 1 11 11H7a5 5 0 0 0-5-5z"/><path d="M2 52h5v-5a5 5 0 0 0-5 0z"/>`,
  store: `<path d="M8 20h48l-4 34a4 4 0 0 1-4 4H16a4 4 0 0 1-4-4z"/><path d="M22 24v-6a10 10 0 0 1 20 0v6" fill="none" stroke="#fff" stroke-width="4.5"/><path d="M27 32l13 7.5L27 47z" ${D}/>`,
};
GLYPHS.globe = GLYPHS.network;

function glyph(name) {
  const body = GLYPHS[name] || GLYPHS.folder;
  return `<svg class="g" viewBox="0 0 64 64"><g fill="url(#gw)">${body}</g></svg>`;
}

/* Small flat icons for the status bar and controls. */
function miniGlyph(name) {
  return `<svg viewBox="0 0 64 64"><g fill="#fff">${GLYPHS[name] || ''}</g></svg>`;
}
