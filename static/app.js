const $ = (id) => document.getElementById(id);
const canvas = $("sketch");
const ctx = canvas.getContext("2d");
const output = $("output");
const status = $("status");

const SWATCHES = ["#000000", "#ffffff", "#1e3a8a", "#38bdf8", "#16a34a", "#facc15", "#f97316", "#dc2626", "#7c3aed", "#78350f"];
const history = [];
let tool = "brush";
let seed = Math.floor(Math.random() * 2 ** 31);

// ---- drawing ---------------------------------------------------------------

function resetCanvas() {
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
}

function saveHistory() {
  history.push(ctx.getImageData(0, 0, canvas.width, canvas.height));
  if (history.length > 50) history.shift();
}

function toCanvas(e) {
  const r = canvas.getBoundingClientRect();
  return [(e.clientX - r.left) * (canvas.width / r.width), (e.clientY - r.top) * (canvas.height / r.height)];
}

let last = null;
canvas.addEventListener("pointerdown", (e) => {
  canvas.setPointerCapture(e.pointerId);
  saveHistory();
  last = toCanvas(e);
  stroke(e, last);
});
canvas.addEventListener("pointermove", (e) => {
  if (!last) return;
  // Coalesced events give smooth lines on fast pen/mouse movement.
  for (const ev of e.getCoalescedEvents?.() ?? [e]) stroke(ev, toCanvas(ev));
});
canvas.addEventListener("pointerup", () => (last = null));
canvas.addEventListener("pointercancel", () => (last = null));

function stroke(e, point) {
  const pressure = e.pointerType === "pen" ? Math.max(e.pressure, 0.1) * 2 : 1;
  ctx.strokeStyle = tool === "eraser" ? "#ffffff" : $("color").value;
  ctx.lineWidth = $("size").value * pressure;
  ctx.lineCap = ctx.lineJoin = "round";
  ctx.beginPath();
  ctx.moveTo(...last);
  ctx.lineTo(...point);
  ctx.stroke();
  last = point;
  requestFrame();
}

// ---- toolbar ---------------------------------------------------------------

function setTool(name) {
  tool = name;
  $("brush").classList.toggle("active", name === "brush");
  $("eraser").classList.toggle("active", name === "eraser");
}

for (const c of SWATCHES) {
  const s = document.createElement("div");
  s.className = "swatch";
  s.style.background = c;
  s.onclick = () => { $("color").value = c; setTool("brush"); };
  $("swatches").append(s);
}

$("brush").onclick = () => setTool("brush");
$("eraser").onclick = () => setTool("eraser");
$("fill").onclick = () => { saveHistory(); ctx.fillStyle = $("color").value; ctx.fillRect(0, 0, canvas.width, canvas.height); requestFrame(); };
$("clear").onclick = () => { saveHistory(); resetCanvas(); requestFrame(); };
$("undo").onclick = undo;
$("reseed").onclick = () => { seed = Math.floor(Math.random() * 2 ** 31); requestFrame(); };
$("swap").onclick = () => { saveHistory(); ctx.drawImage(output, 0, 0, canvas.width, canvas.height); requestFrame(); };
$("prompt").addEventListener("input", requestFrame);
$("strength").addEventListener("input", requestFrame);

function undo() {
  const prev = history.pop();
  if (prev) { ctx.putImageData(prev, 0, 0); requestFrame(); }
}

document.addEventListener("keydown", (e) => {
  if (e.target === $("prompt")) return;
  if ((e.ctrlKey || e.metaKey) && e.key === "z") { e.preventDefault(); undo(); }
  else if (e.key === "b") setTool("brush");
  else if (e.key === "e") setTool("eraser");
});

// ---- streaming -------------------------------------------------------------
// At most one frame is in flight. Changes made meanwhile set `dirty`, and the
// newest canvas is sent as soon as the previous result arrives.

let socket;
let inFlight = false;
let dirty = false;
let frameId = 0;

function connect() {
  socket = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`);
  socket.onopen = () => { status.textContent = "ready"; inFlight = false; requestFrame(); };
  socket.onclose = () => { status.textContent = "reconnecting…"; setTimeout(connect, 1000); };
  socket.onmessage = (e) => {
    const msg = JSON.parse(e.data);
    if (msg.loading) { status.textContent = `loading ${msg.loading}…`; return; }
    if (msg.error) {
      status.textContent = "error (see server log)";
      console.error(msg.error);
      inFlight = false;
      return;
    }
    output.src = msg.image;
    $("download").href = msg.image;
    status.textContent = `${msg.ms} ms · ${(1000 / Math.max(msg.ms, 1)).toFixed(1)} fps`;
    inFlight = false;
    if (dirty) sendFrame();
  };
}

function requestFrame() {
  dirty = true;
  if (!inFlight) sendFrame();
}

function sendFrame() {
  if (socket?.readyState !== WebSocket.OPEN) return;
  dirty = false;
  inFlight = true;
  socket.send(JSON.stringify({
    id: ++frameId,
    image: canvas.toDataURL("image/png"),
    prompt: $("prompt").value,
    strength: parseFloat($("strength").value),
    model: $("model").value,
    seed,
  }));
}

async function loadModels() {
  const { default: def, models } = await (await fetch("/models")).json();
  for (const m of models) {
    const opt = new Option(m.label, m.id, m.id === def, m.id === def);
    opt.title = `License: ${m.license}`;
    $("model").append(opt);
  }
  $("model").addEventListener("change", requestFrame);
}

resetCanvas();
loadModels().then(connect);
