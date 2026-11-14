'use strict';
/* The animated light wave. It normally runs in a worker that owns the canvas, so a slow GPU frame
   never holds up the menu; the page falls back to drawing it itself if that isn't possible. */

const WAVE_VS = 'attribute vec2 p; void main(){ gl_Position = vec4(p, 0.0, 1.0); }';
const WAVE_FS = `
precision highp float;
uniform vec2 uRes;
uniform float uTime;
uniform vec3 uColor;
uniform float uOpaque;
uniform float uLines;
float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float curve(float x, float t, float k){
  return 0.47 + 0.085 * sin(x * 2.2 + t * 0.20 + k * 0.55)
              + 0.045 * sin(x * 4.6 - t * 0.31 + k * 1.21)
              + 0.020 * sin(x * 9.0 + t * 0.47 + k * 2.3)
              + (k - 6.0) * 0.0065 * sin(x * 1.6 + t * 0.13 + 1.3);
}
void main(){
  vec2 uv = gl_FragCoord.xy / uRes;
  float t = uTime;
  vec3 top = uColor * 0.10;
  vec3 bot = uColor * 0.42 + vec3(0.02, 0.02, 0.05);
  vec3 bg = mix(bot, top, smoothstep(0.0, 1.0, uv.y));
  bg += uColor * 0.10 * exp(-pow((uv.x - 0.75) * 1.6, 2.0) - pow((uv.y - 0.35) * 2.2, 2.0));
  float mid = curve(uv.x, t, 6.0);
  float sheet = exp(-pow((uv.y - mid) / 0.075, 2.0)) * 0.30;
  float under = smoothstep(mid + 0.02, mid - 0.25, uv.y) * 0.10;
  float lines = 0.0;
  float stride = 13.0 / uLines;
  for (int i = 0; i < 13; i++) {
    if (float(i) >= uLines) break;
    float k = floor(float(i) * stride);
    float d = abs(uv.y - curve(uv.x, t, k));
    float w = 0.0011 + 0.0016 * (0.5 + 0.5 * sin(uv.x * 3.1 + k * 1.7 + t * 0.2));
    lines += w / (d + w * 0.7) * (0.55 + 0.45 * sin(uv.x * 2.0 + k + t * 0.1));
  }
  lines *= 0.05 * sqrt(stride);
  vec2 g = floor(gl_FragCoord.xy / 2.0);
  float tw = hash(g + floor(t * 1.5));
  float spark = step(0.9965, tw) * exp(-pow((uv.y - mid) / 0.14, 2.0)) * (0.6 + 0.4 * sin(t * 3.0 + tw * 40.0));
  float glow = sheet + under + lines + spark * 1.4;
  vec3 light = mix(uColor, vec3(1.0), 0.45) * glow;
  vec3 col = bg * uOpaque + light;
  float a = clamp(max(uOpaque, glow), 0.0, 1.0);
  gl_FragColor = vec4(min(col, vec3(a)), a);
}`;

/* Draws the wave into canvas (an HTMLCanvasElement or OffscreenCanvas); null without WebGL. */
function WaveRenderer(canvas, log) {
  const gl = canvas.getContext('webgl', {
    alpha: true, premultipliedAlpha: true, antialias: false, depth: false, stencil: false,
    preserveDrawingBuffer: false, powerPreference: 'low-power',
  });
  if (!gl) return null;
  const prog = gl.createProgram();
  for (const [type, src] of [[gl.VERTEX_SHADER, WAVE_VS], [gl.FRAGMENT_SHADER, WAVE_FS]]) {
    const s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
      log('shader: ' + gl.getShaderInfoLog(s));
      return null;
    }
    gl.attachShader(prog, s);
  }
  gl.linkProgram(prog);
  gl.useProgram(prog);
  gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  const loc = gl.getAttribLocation(prog, 'p');
  gl.enableVertexAttribArray(loc);
  gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
  const u = {};
  for (const n of ['uRes', 'uTime', 'uColor', 'uOpaque', 'uLines']) u[n] = gl.getUniformLocation(prog, n);
  const t0 = performance.now();
  const color = [0.18, 0.42, 1];
  const st = { target: color.slice(), opaque: 0, lines: 13, fps: 30, scale: 0.5, run: false };
  let last = 0, lastDraw = 0;

  function size() {
    const w = Math.round(1920 * st.scale), h = Math.round(1080 * st.scale);
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
      gl.viewport(0, 0, w, h);
    }
    gl.uniform2f(u.uRes, w, h);
  }

  function draw(now) {
    const k = 1 - Math.pow(0.94, Math.min(10, (now - lastDraw) / 33.3));
    lastDraw = now;
    for (let i = 0; i < 3; i++) color[i] += (st.target[i] - color[i]) * k;
    gl.uniform1f(u.uTime, (now - t0) / 1000);
    gl.uniform3f(u.uColor, color[0], color[1], color[2]);
    gl.uniform1f(u.uOpaque, st.opaque);
    gl.uniform1f(u.uLines, st.lines);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  }

  // Frames stay on the refresh grid: at 30 FPS on a 60 Hz screen, exactly every other refresh.
  let looping = false, frames = 0;
  function loop(now) {
    if (!st.run) { looping = false; return; }
    requestAnimationFrame(loop);
    const gap = 1000 / st.fps;
    if (now - last < gap - 6) return;
    last = now - Math.min(gap, Math.max(0, now - last - gap));
    frames++;
    draw(now);
  }

  size();
  return {
    set(o) {
      Object.assign(st, o);
      if (o.scale) size();
      if (st.run && !looping) {
        looping = true;
        last = 0;
        lastDraw = performance.now();
        requestAnimationFrame(loop);
      }
    },
    frames: () => frames,
  };
}

if (typeof WorkerGlobalScope !== 'undefined' && self instanceof WorkerGlobalScope) {
  let wave = null;
  const log = m => postMessage({ log: m });
  onmessage = e => {
    const m = e.data;
    if (m.canvas) {
      wave = WaveRenderer(m.canvas, log);
      postMessage({ ready: !!wave });
      if (!wave) return;
    }
    if (wave && m.set) wave.set(m.set);
    if (wave && m.stats) postMessage({ frames: wave.frames() });
  };
}
