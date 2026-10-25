const $ = (id) => document.getElementById(id);
const canvas = $("sketch");
const ctx = canvas.getContext("2d", { willReadFrequently: true });
const strokeCanvas = $("stroke");
const strokeCtx = strokeCanvas.getContext("2d");
const stage = $("sketch-stage");
const output = $("output");
const status = $("status");
const W = canvas.width;
const H = canvas.height;
const MODEL_SIZE = 512;  // frames are downscaled to this before sending
const BRUSH_SCALE = W / MODEL_SIZE;  // brush sizes are in model pixels

const SWATCHES = ["#000000", "#ffffff", "#1e3a8a", "#38bdf8", "#16a34a", "#facc15", "#f97316", "#dc2626", "#7c3aed", "#78350f"];
const STYLES = {
  None: "",
  Photo: "photorealistic photo, 35mm, natural light, highly detailed",
  Watercolor: "watercolor painting, soft washes, paper texture",
  "Oil painting": "oil painting, thick brush strokes, impasto",
  Anime: "anime style, cel shading, vibrant colors",
  "3D render": "3d render, octane, soft studio lighting",
  "Pixel art": "pixel art, 16-bit, crisp pixels",
  Ink: "ink illustration, bold linework, cross-hatching",
  Cinematic: "cinematic film still, dramatic lighting, depth of field",
};
const SETTINGS_KEY = "sketchlive.settings";
const HISTORY_IDLE_MS = 1200;
const HISTORY_MAX = 30;

let tool = "brush";
let style = "None";
let seed = randomSeed();

function randomSeed() { return Math.floor(Math.random() * 2 ** 31); }

// ---- settings persistence --------------------------------------------------

function saveSettings() {
  try {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify({
      prompt: $("prompt").value,
      model: $("model").value,
      strength: $("strength").value,
      size: $("size").value,
      opacity: $("opacity").value,
      color: $("color").value,
      style,
      seed,
    }));
  } catch {}
}

function loadSettings() {
  try {
    const s = JSON.parse(localStorage.getItem(SETTINGS_KEY)) || {};
    for (const id of ["prompt", "strength", "size", "opacity", "color"]) if (s[id] != null) $(id).value = s[id];
    if (s.style in STYLES) style = s.style;
    if (Number.isInteger(s.seed)) seed = s.seed;
    return s;
  } catch {
    return {};
  }
}

// ---- undo / redo -----------------------------------------------------------

const undoStack = [];
const redoStack = [];

function snapshot() { return ctx.getImageData(0, 0, W, H); }

function saveHistory() {
  undoStack.push(snapshot());
  if (undoStack.length > 50) undoStack.shift();
  redoStack.length = 0;
}

function undo() {
  if (!undoStack.length) return;
  redoStack.push(snapshot());
  ctx.putImageData(undoStack.pop(), 0, 0);
  requestFrame();
}

function redo() {
  if (!redoStack.length) return;
  undoStack.push(snapshot());
  ctx.putImageData(redoStack.pop(), 0, 0);
  requestFrame();
}

// ---- drawing ---------------------------------------------------------------
// Strokes are drawn opaque on an overlay canvas whose CSS opacity matches the
// brush, then merged once on pointer-up. That keeps semi-transparent strokes
// even instead of darkening wherever segments overlap.

function resetCanvas() {
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, W, H);
}

function toCanvas(e, el = stage) {
  const r = el.getBoundingClientRect();
  return [(e.clientX - r.left) * (W / r.width), (e.clientY - r.top) * (H / r.height)];
}

let last = null;
let stroking = false;

stage.addEventListener("pointerdown", (e) => {
  if (e.button !== 0) return;
  if (tool === "picker" || e.altKey) { pickColor(ctx, ...toCanvas(e)); return; }
  try { stage.setPointerCapture(e.pointerId); } catch {}
  saveHistory();
  stroking = true;
  strokeCanvas.style.opacity = $("opacity").value;
  last = toCanvas(e);
  stroke(e, last);
});

stage.addEventListener("pointermove", (e) => {
  moveCursor(e);
  if (!stroking) return;
  // Coalesced events give smooth lines on fast pen/mouse movement.
  const events = e.getCoalescedEvents?.() ?? [];
  for (const ev of events.length ? events : [e]) stroke(ev, toCanvas(ev));
});

function endStroke() {
  if (!stroking) return;
  stroking = false;
  last = null;
  ctx.globalAlpha = parseFloat($("opacity").value);
  ctx.drawImage(strokeCanvas, 0, 0);
  ctx.globalAlpha = 1;
  strokeCtx.clearRect(0, 0, W, H);
  requestFrame();
}
stage.addEventListener("pointerup", endStroke);
stage.addEventListener("pointercancel", endStroke);

function stroke(e, point) {
  const pressure = e.pointerType === "pen" ? Math.max(e.pressure, 0.1) * 2 : 1;
  strokeCtx.strokeStyle = tool === "eraser" ? "#ffffff" : $("color").value;
  strokeCtx.lineWidth = $("size").value * BRUSH_SCALE * pressure;
  strokeCtx.lineCap = strokeCtx.lineJoin = "round";
  strokeCtx.beginPath();
  strokeCtx.moveTo(...last);
  strokeCtx.lineTo(...point);
  strokeCtx.stroke();
  last = point;
  requestFrame();
}

// The canvas as the model should see it: downscaled, including any in-progress stroke.
const frameCanvas = document.createElement("canvas");
frameCanvas.width = frameCanvas.height = MODEL_SIZE;
const frameCtx = frameCanvas.getContext("2d");

function composite() {
  frameCtx.globalAlpha = 1;
  frameCtx.drawImage(canvas, 0, 0, MODEL_SIZE, MODEL_SIZE);
  if (stroking) {
    frameCtx.globalAlpha = parseFloat($("opacity").value);
    frameCtx.drawImage(strokeCanvas, 0, 0, MODEL_SIZE, MODEL_SIZE);
  }
  return frameCanvas;
}

// ---- brush cursor ----------------------------------------------------------

const cursor = $("cursor");

function moveCursor(e) {
  if (tool === "picker" || e.altKey) { cursor.style.display = "none"; return; }
  const r = stage.getBoundingClientRect();
  const d = $("size").value * BRUSH_SCALE * (r.width / W);
  Object.assign(cursor.style, {
    display: "block",
    left: `${e.clientX - r.left}px`,
    top: `${e.clientY - r.top}px`,
    width: `${d}px`,
    height: `${d}px`,
  });
}
stage.addEventListener("pointerleave", () => (cursor.style.display = "none"));

// ---- eyedropper ------------------------------------------------------------

function pickColor(sourceCtx, x, y) {
  const [r, g, b] = sourceCtx.getImageData(Math.floor(x), Math.floor(y), 1, 1).data;
  $("color").value = "#" + [r, g, b].map((v) => v.toString(16).padStart(2, "0")).join("");
  setTool("brush");
  saveSettings();
}

output.addEventListener("click", (e) => {
  if (!(e.altKey || tool === "picker") || !output.src) return;
  const c = document.createElement("canvas");
  c.width = W;
  c.height = H;
  const cc = c.getContext("2d", { willReadFrequently: true });
  cc.drawImage(output, 0, 0, W, H);
  pickColor(cc, ...toCanvas(e, output));
});

// ---- paste / drop an image -------------------------------------------------

function placeImage(blob) {
  if (!blob?.type.startsWith("image/")) return;
  const img = new Image();
  img.onload = () => {
    saveHistory();
    resetCanvas();
    // Cover-fit: fill the square canvas, cropping the longer side.
    const s = Math.max(W / img.width, H / img.height);
    ctx.drawImage(img, (W - img.width * s) / 2, (H - img.height * s) / 2, img.width * s, img.height * s);
    URL.revokeObjectURL(img.src);
    requestFrame();
  };
  img.src = URL.createObjectURL(blob);
}

document.addEventListener("paste", (e) => {
  if (e.target === $("prompt") && !e.clipboardData.files.length) return;
  const file = [...e.clipboardData.files].find((f) => f.type.startsWith("image/"));
  if (file) { e.preventDefault(); placeImage(file); }
});

for (const el of [stage, output.parentElement]) {
  el.addEventListener("dragover", (e) => { e.preventDefault(); stage.classList.add("dragover"); });
  el.addEventListener("dragleave", () => stage.classList.remove("dragover"));
  el.addEventListener("drop", (e) => {
    e.preventDefault();
    stage.classList.remove("dragover");
    placeImage(e.dataTransfer.files[0]);
  });
}

// ---- toolbar ---------------------------------------------------------------

function setTool(name) {
  tool = name;
  for (const t of ["brush", "eraser", "picker"]) $(t).classList.toggle("active", t === name);
  stage.classList.toggle("picking", name === "picker");
}

for (const c of SWATCHES) {
  const s = document.createElement("div");
  s.className = "swatch";
  s.style.background = c;
  s.title = c;
  s.onclick = () => { $("color").value = c; setTool("brush"); saveSettings(); };
  $("swatches").append(s);
}

for (const name of Object.keys(STYLES)) {
  const chip = document.createElement("button");
  chip.className = "chip";
  chip.textContent = name;
  chip.title = STYLES[name] || "Just your prompt";
  chip.onclick = () => { style = name; renderStyles(); saveSettings(); requestFrame(); };
  $("styles").append(chip);
}

function renderStyles() {
  for (const chip of $("styles").children) chip.classList.toggle("active", chip.textContent === style);
}

function setSeed(value) {
  seed = value;
  $("seed").value = seed;
  saveSettings();
  requestFrame();
}

$("brush").onclick = () => setTool("brush");
$("eraser").onclick = () => setTool("eraser");
$("picker").onclick = () => setTool(tool === "picker" ? "brush" : "picker");
$("fill").onclick = () => { saveHistory(); ctx.fillStyle = $("color").value; ctx.fillRect(0, 0, W, H); requestFrame(); };
$("clear").onclick = () => { saveHistory(); resetCanvas(); requestFrame(); };
$("undo").onclick = undo;
$("redo").onclick = redo;
$("reseed").onclick = () => setSeed(randomSeed());
$("seed").addEventListener("change", () => {
  const v = parseInt($("seed").value, 10);
  if (Number.isInteger(v) && v >= 0) setSeed(v); else $("seed").value = seed;
});
$("swap").onclick = () => { saveHistory(); ctx.drawImage(output, 0, 0, W, H); requestFrame(); };
$("enhance").onclick = enhance;
for (const id of ["prompt", "strength"]) $(id).addEventListener("input", () => { saveSettings(); requestFrame(); });
for (const id of ["size", "opacity", "color"]) $(id).addEventListener("input", saveSettings);

document.addEventListener("keydown", (e) => {
  const typing = e.target.matches?.("input[type=text], input[type=number]");
  if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); enhance(); return; }
  if (typing) return;
  const key = e.key.toLowerCase();
  if ((e.ctrlKey || e.metaKey) && key === "z") { e.preventDefault(); e.shiftKey ? redo() : undo(); }
  else if ((e.ctrlKey || e.metaKey) && key === "y") { e.preventDefault(); redo(); }
  else if (e.ctrlKey || e.metaKey || e.altKey) return;
  else if (key === "b") setTool("brush");
  else if (key === "e") setTool("eraser");
  else if (key === "i") setTool("picker");
  else if (key === "[" || key === "]") {
    const size = $("size");
    const next = key === "]" ? Math.ceil(size.value * 1.15 + 1) : Math.floor(size.value / 1.15 - 1);
    size.value = Math.min(size.max, Math.max(size.min, next));
    saveSettings();
  }
});

// ---- history strip ---------------------------------------------------------
// A result is kept once nothing newer arrives for a moment, i.e. when you pause.

let historyTimer;
let lastSaved;

function addHistory(src, enhanced) {
  if (src === lastSaved) return;
  lastSaved = src;
  const item = document.createElement("div");
  item.className = "thumb" + (enhanced ? " enhanced" : "");
  item.title = `${promptText()}\nseed ${seed} · ${$("model").selectedOptions[0]?.text ?? ""}`;
  const img = new Image();
  img.src = src;
  item.append(img);
  item.onclick = () => showResult(src);
  $("history").prepend(item);
  while ($("history").children.length > HISTORY_MAX) $("history").lastChild.remove();
}

function showResult(src) {
  output.src = src;
  $("download").href = src;
}

// ---- streaming -------------------------------------------------------------
// At most one frame is in flight. Changes made meanwhile set `dirty`, and the
// newest canvas is sent as soon as the previous result arrives.

let socket;
let inFlight = false;
let dirty = false;
let wantEnhance = false;
let frameId = 0;

function promptText() {
  return [$("prompt").value.trim(), STYLES[style]].filter(Boolean).join(", ");
}

function setLoading(text) {
  $("loading").hidden = !text;
  $("loading-text").textContent = text || "";
  $("model").disabled = !!text;
}

function connect() {
  socket = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`);
  socket.onopen = () => { status.textContent = "ready"; inFlight = false; requestFrame(); };
  socket.onclose = () => { status.textContent = "reconnecting…"; setTimeout(connect, 1000); };
  socket.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.loading) { setLoading(`Loading ${msg.loading}…`); return; }
    setLoading(null);
    $("enhance").disabled = false;
    inFlight = false;
    if (msg.error) {
      status.textContent = "error (see console)";
      console.error(msg.error);
    } else {
      showResult(msg.image);
      status.textContent = `${msg.enhanced ? "enhanced · " : ""}${msg.ms} ms` +
        (msg.enhanced ? "" : ` · ${(1000 / Math.max(msg.ms, 1)).toFixed(1)} fps`);
      clearTimeout(historyTimer);
      if (msg.enhanced) addHistory(msg.image, true);
      else historyTimer = setTimeout(() => addHistory(msg.image, false), HISTORY_IDLE_MS);
    }
    if (dirty || wantEnhance) sendFrame();
  };
}

function requestFrame() {
  dirty = true;
  if (!inFlight) sendFrame();
}

function enhance() {
  wantEnhance = true;
  $("enhance").disabled = true;
  status.textContent = "enhancing…";
  if (!inFlight) sendFrame();
}

function sendFrame() {
  if (socket?.readyState !== WebSocket.OPEN) return;
  const enhanced = wantEnhance && !dirty;  // finish catching up on edits before enhancing
  dirty = false;
  if (enhanced) wantEnhance = false;
  inFlight = true;
  socket.send(JSON.stringify({
    id: ++frameId,
    image: composite().toDataURL("image/png"),
    prompt: promptText(),
    strength: parseFloat($("strength").value),
    model: $("model").value,
    seed,
    enhance: enhanced,
  }));
}

async function loadModels(saved) {
  const { default: def, models } = await (await fetch("/models")).json();
  const pick = models.some((m) => m.id === saved) ? saved : def;
  for (const m of models) {
    const opt = new Option(m.label, m.id, m.id === pick, m.id === pick);
    opt.title = `License: ${m.license}`;
    $("model").append(opt);
  }
  $("model").addEventListener("change", () => { saveSettings(); requestFrame(); });
}

const saved = loadSettings();
$("seed").value = seed;
renderStyles();
resetCanvas();
loadModels(saved.model).then(connect);
