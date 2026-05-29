"use strict";

const TOKEN_KEY = "pockethost.token";
const state = {
  token: sessionStorage.getItem(TOKEN_KEY) || "",
  services: [],
  timer: null,
};

const $ = (sel) => document.querySelector(sel);
const el = (tag, cls, text) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
};

// --- API ---------------------------------------------------------------
async function api(path, opts = {}) {
  const headers = Object.assign({ "X-PocketHost-Token": state.token }, opts.headers || {});
  const resp = await fetch(path, Object.assign({}, opts, { headers }));
  if (resp.status === 401) {
    lock("Invalid or expired token.");
    throw new Error("unauthorized");
  }
  return resp;
}

async function apiJSON(path, opts) {
  const resp = await api(path, opts);
  const text = await resp.text();
  let data = {};
  try { data = text ? JSON.parse(text) : {}; } catch (_) { data = { raw: text }; }
  if (!resp.ok) throw new Error(data.error || `HTTP ${resp.status}`);
  return data;
}

// --- Auth gate ---------------------------------------------------------
function lock(message) {
  state.token = "";
  sessionStorage.removeItem(TOKEN_KEY);
  stopAuto();
  $("#dashboard").hidden = true;
  $("#gate").hidden = false;
  $("#lock-btn").hidden = true;
  setConn("locked", "err");
  const e = $("#gate-error");
  if (message) { e.textContent = message; e.hidden = false; } else { e.hidden = true; }
}

async function unlock(token) {
  state.token = token.trim();
  if (!state.token) return;
  setConn("checking…", "muted");
  try {
    await apiJSON("/api/services");
    sessionStorage.setItem(TOKEN_KEY, state.token);
    $("#gate").hidden = true;
    $("#dashboard").hidden = false;
    $("#lock-btn").hidden = false;
    $("#gate-error").hidden = true;
    startAuto();
    refresh();
  } catch (err) {
    if (err.message !== "unauthorized") lock("Could not reach hostd: " + err.message);
  }
}

// --- Status / rendering ------------------------------------------------
function statusKind(svc) {
  if (!svc.ok) return "err";
  if (svc.status && svc.status !== "ok" && svc.status !== "up") return "warn";
  return "ok";
}

function isExposed(svc) {
  const addr = svc.addr || "";
  return addr.startsWith("0.0.0.0:") || addr.startsWith("[::]:") || addr.startsWith(":");
}

function fmtUptime(s) {
  if (!s || s < 0) return "—";
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60), sec = s % 60;
  if (d) return `${d}d ${h}h`;
  if (h) return `${h}h ${m}m`;
  if (m) return `${m}m ${sec}s`;
  return `${sec}s`;
}

function fmtBytes(n) {
  n = Number(n);
  if (!isFinite(n)) return "—";
  if (n < 1024) return n + " B";
  const u = ["KB", "MB", "GB", "TB"]; let i = -1;
  do { n /= 1024; i++; } while (n >= 1024 && i < u.length - 1);
  return n.toFixed(1) + " " + u[i];
}

async function refresh() {
  setConn("refreshing…", "muted");
  try {
    const data = await apiJSON("/api/services");
    state.services = data.services || [];
    renderSummary();
    renderGrid();
    setConn("connected", "ok");
  } catch (err) {
    if (err.message !== "unauthorized") setConn("error", "err");
  }
}

function renderSummary() {
  const up = state.services.filter((s) => statusKind(s) === "ok").length;
  const bad = state.services.filter((s) => statusKind(s) !== "ok").length;
  $("#stat-up").textContent = up;
  $("#stat-down").textContent = bad;
  $("#stat-total").textContent = state.services.length;

  const exposed = state.services.filter(isExposed);
  const banner = $("#exposure-banner");
  if (exposed.length) {
    banner.hidden = false;
    banner.classList.add("danger");
    banner.textContent =
      `⚠ ${exposed.length} service(s) are bound to all interfaces (0.0.0.0) and reachable from the network: ` +
      exposed.map((s) => s.name).join(", ") + ". Toggle back to loopback in the app if unintended.";
  } else {
    banner.hidden = false;
    banner.classList.remove("danger");
    banner.textContent = "🔒 All services are bound to loopback (127.0.0.1). Public access requires a tunnel.";
  }
}

function renderGrid() {
  const grid = $("#grid");
  grid.innerHTML = "";
  state.services.forEach((svc) => {
    const kind = statusKind(svc);
    const card = el("div", "svc");
    card.addEventListener("click", () => openDrawer(svc.id));

    const head = el("div", "svc-head");
    head.appendChild(el("span", "dot dot-" + kind));
    const nameWrap = el("div");
    nameWrap.appendChild(el("div", "svc-name", svc.name));
    nameWrap.appendChild(el("div", "svc-id", svc.id + " · :" + svc.port));
    head.appendChild(nameWrap);
    const pill = el("span", "pill pill-" + kind, svc.ok ? (svc.status || "up") : "down");
    pill.style.marginLeft = "auto";
    head.appendChild(pill);
    card.appendChild(head);

    const meta = el("div", "svc-meta");
    if (svc.ok) {
      meta.appendChild(rowEl("version", svc.version || "—"));
      meta.appendChild(rowEl("uptime", fmtUptime(svc.uptime_seconds)));
    } else {
      meta.appendChild(rowEl("error", svc.error || "unreachable"));
    }
    card.appendChild(meta);

    const tags = el("div", "svc-tags");
    if (isExposed(svc)) tags.appendChild(el("span", "tag tag-expose", "exposed 0.0.0.0"));
    else tags.appendChild(el("span", "tag", "loopback"));
    (svc.actions || []).forEach((a) => tags.appendChild(el("span", "tag", a)));
    card.appendChild(tags);

    grid.appendChild(card);
  });
}

function rowEl(k, v) {
  const r = el("div");
  r.innerHTML = `${k}: <b></b>`;
  r.querySelector("b").textContent = v;
  return r;
}

// --- Drawer ------------------------------------------------------------
function openDrawer(id) {
  const svc = state.services.find((s) => s.id === id);
  if (!svc) return;
  $("#drawer-title").textContent = svc.name;
  const body = $("#drawer-body");
  body.innerHTML = "";

  const overview = el("section");
  overview.appendChild(el("h3", null, "Overview"));
  const kv = el("div", "kv");
  const add = (k, v) => { kv.appendChild(el("span", null, k)); kv.appendChild(el("span", null, v)); };
  add("status", svc.ok ? (svc.status || "up") : "down");
  add("endpoint", svc.endpoint);
  add("listen addr", svc.addr || "—");
  add("version", svc.version || "—");
  add("uptime", fmtUptime(svc.uptime_seconds));
  add("binding", isExposed(svc) ? "0.0.0.0 (network)" : "127.0.0.1 (loopback)");
  if (svc.error) add("error", svc.error);
  overview.appendChild(kv);
  body.appendChild(overview);

  // Per-daemon actions
  if ((svc.actions || []).includes("update-now")) body.appendChild(ddnsSection());
  if ((svc.actions || []).includes("browse")) body.appendChild(filesSection());

  if (svc.extra && Object.keys(svc.extra).length) {
    const ex = el("section");
    ex.appendChild(el("h3", null, "Health detail"));
    const pre = el("pre", "json", JSON.stringify(svc.extra, null, 2));
    ex.appendChild(pre);
    body.appendChild(ex);
  }

  $("#drawer").hidden = false;
}

function closeDrawer() { $("#drawer").hidden = true; }

function ddnsSection() {
  const s = el("section");
  s.appendChild(el("h3", null, "Dynamic DNS"));
  const btn = el("button", "btn btn-primary btn-sm", "Update DNS now");
  const out = el("p", "muted");
  out.style.fontSize = ".82rem";
  btn.addEventListener("click", async () => {
    btn.disabled = true; out.textContent = "updating…";
    try {
      const r = await apiJSON("/api/ddns/update-now", { method: "POST" });
      out.textContent = "Result: " + (r.status || JSON.stringify(r));
      toast("DDNS update triggered", "ok");
    } catch (e) { out.textContent = "Failed: " + e.message; toast("DDNS update failed", "err"); }
    finally { btn.disabled = false; }
  });
  s.appendChild(btn);
  s.appendChild(out);
  return s;
}

function filesSection() {
  const s = el("section");
  s.appendChild(el("h3", null, "Files"));
  const crumbs = el("div", "crumbs");
  const bar = el("div", "files-bar");
  const upBtn = el("button", "btn btn-ghost btn-sm", "Upload");
  const input = el("input"); input.type = "file"; input.hidden = true;
  const reload = el("button", "btn btn-ghost btn-sm", "Reload");
  bar.appendChild(upBtn); bar.appendChild(reload); bar.appendChild(input);
  const list = el("ul", "file-list");
  s.appendChild(crumbs); s.appendChild(bar); s.appendChild(list);

  let cwd = "";
  const navigate = (p) => { cwd = p; render(); };
  const render = async () => {
    list.innerHTML = "";
    crumbs.innerHTML = "";
    const root = el("a", null, "files"); root.onclick = () => { cwd = ""; render(); };
    crumbs.appendChild(root);
    let acc = "";
    cwd.split("/").filter(Boolean).forEach((part) => {
      acc = acc ? acc + "/" + part : part;
      const path = acc;
      crumbs.append(" / ");
      const a = el("a", null, part); a.onclick = () => { cwd = path; render(); };
      crumbs.appendChild(a);
    });
    try {
      const data = await apiJSON("/api/files?path=" + encodeURIComponent(cwd));
      (data.items || []).forEach((it) => list.appendChild(fileRow(it, cwd, navigate, render)));
      if (!(data.items || []).length) list.appendChild(el("li", "muted", "empty"));
    } catch (e) { list.appendChild(el("li", "error", e.message)); }
  };

  reload.onclick = render;
  upBtn.onclick = () => input.click();
  input.onchange = async () => {
    if (!input.files.length) return;
    const fd = new FormData();
    fd.append("file", input.files[0]);
    try {
      await api("/api/files/upload?path=" + encodeURIComponent(cwd), { method: "POST", body: fd });
      toast("Uploaded " + input.files[0].name, "ok");
      input.value = ""; render();
    } catch (e) { toast("Upload failed: " + e.message, "err"); }
  };

  render();
  return s;
}

function fileRow(it, cwd, navigate, render) {
  const li = el("li");
  const name = el("span", "fname", (it.is_dir ? "📁 " : "📄 ") + it.name);
  const full = cwd ? cwd + "/" + it.name : it.name;
  if (it.is_dir) {
    name.onclick = () => navigate(full);
  } else {
    name.onclick = () => download(full, it.name);
  }
  li.appendChild(name);
  if (!it.is_dir) li.appendChild(el("span", "fsize", fmtBytes(it.size)));
  const del = el("button", "btn btn-danger btn-sm", "Delete");
  del.onclick = async (e) => {
    e.stopPropagation();
    if (!confirm("Delete " + full + "?")) return;
    try {
      await apiJSON("/api/files/delete", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ path: full }),
      });
      toast("Deleted " + it.name, "ok"); render();
    } catch (err) { toast("Delete failed: " + err.message, "err"); }
  };
  li.appendChild(del);
  return li;
}

async function download(path, name) {
  try {
    const resp = await api("/api/files/download?path=" + encodeURIComponent(path));
    if (!resp.ok) throw new Error("HTTP " + resp.status);
    const blob = await resp.blob();
    const url = URL.createObjectURL(blob);
    const a = el("a"); a.href = url; a.download = name; document.body.appendChild(a);
    a.click(); a.remove(); URL.revokeObjectURL(url);
  } catch (e) { toast("Download failed: " + e.message, "err"); }
}

// --- misc UI -----------------------------------------------------------
function setConn(text, kind) {
  const c = $("#conn");
  c.textContent = text;
  c.className = "pill pill-" + (kind === "ok" ? "ok" : kind === "err" ? "err" : kind === "warn" ? "warn" : "muted");
}

let toastTimer = null;
function toast(msg, kind) {
  const t = $("#toast");
  t.textContent = msg; t.className = "toast " + (kind || ""); t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 3200);
}

function startAuto() {
  stopAuto();
  if ($("#auto-refresh").checked) state.timer = setInterval(refresh, 5000);
}
function stopAuto() { if (state.timer) { clearInterval(state.timer); state.timer = null; } }

function tickClock() {
  $("#clock").textContent = new Date().toLocaleTimeString();
}

// --- boot --------------------------------------------------------------
function init() {
  $("#origin-host").textContent = location.host;
  setInterval(tickClock, 1000); tickClock();

  $("#token-form").addEventListener("submit", (e) => { e.preventDefault(); unlock($("#token-input").value); });
  $("#lock-btn").addEventListener("click", () => lock());
  $("#refresh-btn").addEventListener("click", refresh);
  $("#auto-refresh").addEventListener("change", startAuto);
  document.querySelectorAll("[data-close]").forEach((n) => n.addEventListener("click", closeDrawer));
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeDrawer(); });

  if (state.token) unlock(state.token); else lock();
}

document.addEventListener("DOMContentLoaded", init);
