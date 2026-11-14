// Spatial navigation for the controller: which element a d-pad press moves the highlight to.
// The bottom screen loads this as a script; the top screen's page script gets AniNav as an argument.
var AniNav = (() => {
  'use strict';
  const gap = (a1, a2, b1, b2) => Math.max(0, Math.max(a1, b1) - Math.min(a2, b2));

  // How far rect r is from rect c going in dir, or Infinity if it isn't that way: it has to start
  // past c's edge, or at least have its center past it. Being off to the side counts three times
  // as much as distance, so the highlight follows rows and columns.
  function score(c, r, dir) {
    const cx = (c.left + c.right) / 2, cy = (c.top + c.bottom) / 2;
    const rx = (r.left + r.right) / 2, ry = (r.top + r.bottom) / 2;
    let main, side;
    if (dir === 'down') {
      if (r.top < c.bottom - 2 && ry <= c.bottom) return Infinity;
      main = r.top - c.bottom;
      side = gap(c.left, c.right, r.left, r.right);
    } else if (dir === 'up') {
      if (r.bottom > c.top + 2 && ry >= c.top) return Infinity;
      main = c.top - r.bottom;
      side = gap(c.left, c.right, r.left, r.right);
    } else if (dir === 'right') {
      if (r.left < c.right - 2 && rx <= c.right) return Infinity;
      main = r.left - c.right;
      side = gap(c.top, c.bottom, r.top, r.bottom);
    } else if (dir === 'left') {
      if (r.right > c.left + 2 && rx >= c.left) return Infinity;
      main = c.left - r.right;
      side = gap(c.top, c.bottom, r.top, r.bottom);
    } else {
      return Infinity;
    }
    const off = dir === 'up' || dir === 'down' ? Math.abs(rx - cx) : Math.abs(ry - cy);
    return Math.max(0, main) + side * 3 + off * 0.05;
  }

  // Rendered, and not cut off by an ancestor that clips without scrolling (carousel slides).
  function visible(el) {
    const r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return false;
    if (el.checkVisibility && !el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })) return false;
    for (let p = el.parentElement, i = 0; p && p !== document.body && i < 12; p = p.parentElement, i++) {
      const cs = getComputedStyle(p);
      if (cs.overflowX === 'visible' && cs.overflowY === 'visible') continue;
      const pr = p.getBoundingClientRect();
      const outside = r.right <= pr.left || r.left >= pr.right || r.bottom <= pr.top || r.top >= pr.bottom;
      if (outside && !/auto|scroll/.test(cs.overflowX + cs.overflowY)) return false;
    }
    return true;
  }

  const onScreen = (el) => {
    const r = el.getBoundingClientRect();
    return r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth;
  };

  // The element to move to from `from` in dir, or null.
  function best(from, els, dir) {
    const c = from.getBoundingClientRect();
    let pick = null, low = Infinity;
    for (const el of els) {
      if (el === from || el.contains(from) || from.contains(el)) continue;
      const s = score(c, el.getBoundingClientRect(), dir);
      if (s < low) {
        low = s;
        pick = el;
      }
    }
    return pick;
  }

  // The first element on screen in reading order: the leftmost one in the top row. The page's
  // content comes before a fixed header ending at `top`, which is one press up from there.
  function first(els, top = 0) {
    const shown = els.filter(onScreen).map((el) => ({ el, r: el.getBoundingClientRect() }));
    const content = shown.filter(({ r }) => (r.top + r.bottom) / 2 >= top);
    const pool = content.length ? content : shown;
    if (!pool.length) return null;
    const row = pool.reduce((a, b) => (b.r.top < a.r.top ? b : a)).r;
    const line = (row.top + row.bottom) / 2;
    return pool.filter(({ r }) => r.top <= line && r.bottom >= line).reduce((a, b) => (b.r.left < a.r.left ? b : a)).el;
  }

  return { score, visible, onScreen, best, first };
})();
