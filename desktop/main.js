// Lumen för Windows, macOS och Linux.
// Gränssnittet ligger i en vy som täcker hela fönstret; varje flik är en egen
// webbvy som läggs exakt över gränssnittets innehållsyta.
const { app, BaseWindow, WebContentsView, ipcMain, shell, nativeTheme, Menu, session } = require('electron');
const path = require('path');
const fs = require('fs');

/* ---- Annonsblockering ---- */
let adblock = true;
let domains = new Set();
const blockedCount = new Map(); // webContentsId -> antal
const wcToTab = new Map();
const LIST_URL = 'https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts';
const parseHosts = (text) => {
  const out = new Set();
  for (const raw of text.split('\n')) {
    const line = raw.split('#')[0].trim();
    if (!line) continue;
    const p = line.split(/\s+/);
    if (p.length < 2 || (p[0] !== '0.0.0.0' && p[0] !== '127.0.0.1')) continue;
    const h = p[1].toLowerCase();
    if (h === 'localhost' || h === '0.0.0.0' || !h.includes('.')) continue;
    out.add(h);
  }
  return out;
};
async function loadBlocklist() {
  try {
    fs.readFileSync(path.join(__dirname, 'ui', 'blocklist.txt'), 'utf8').split('\n')
      .map((l) => l.trim().toLowerCase()).filter((l) => l && !l.startsWith('#')).forEach((d) => domains.add(d));
  } catch (e) {}
  const file = path.join(app.getPath('userData'), 'hosts.txt');
  let fresh = false;
  try {
    const st = fs.statSync(file);
    parseHosts(fs.readFileSync(file, 'utf8')).forEach((d) => domains.add(d));
    fresh = Date.now() - st.mtimeMs < 7 * 864e5;
  } catch (e) {}
  if (fresh) return;
  try {
    const r = await fetch(LIST_URL);
    if (!r.ok) return;
    const text = await r.text();
    const set = parseHosts(text);
    if (set.size > 1000) { fs.writeFileSync(file, text); set.forEach((d) => domains.add(d)); }
  } catch (e) {}
}
function isBlocked(host) {
  if (!adblock || !host) return false;
  let h = host.toLowerCase();
  for (;;) {
    if (domains.has(h)) return true;
    const i = h.indexOf('.');
    if (i < 0) return false;
    h = h.slice(i + 1);
    if (!h.includes('.')) return false;
  }
}
const guarded = new WeakSet();
function guard(ses) {
  if (guarded.has(ses)) return;
  guarded.add(ses);
  ses.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*'] }, (d, cb) => {
    if (d.resourceType === 'mainFrame' || !d.webContentsId || !wcToTab.has(d.webContentsId)) return cb({});
    let host = '';
    try { host = new URL(d.url).hostname; } catch (e) {}
    if (!isBlocked(host)) return cb({});
    blockedCount.set(d.webContentsId, (blockedCount.get(d.webContentsId) || 0) + 1);
    scheduleEmit(d.webContentsId);
    cb({ cancel: true });
  });
}
const emitters = new Map();
const pendingEmit = new Set();
function scheduleEmit(wcId) {
  if (pendingEmit.has(wcId)) return;
  pendingEmit.add(wcId);
  setTimeout(() => { pendingEmit.delete(wcId); const f = emitters.get(wcId); if (f) f(); }, 400);
}

/* ---- Uppdateringar ---- */
const RELEASES_API = 'https://api.github.com/repos/theruup-stack/Lumen-releases/releases/latest';
const ASSET = process.platform === 'win32' ? 'Lumen-win-x64.exe' : process.platform === 'darwin' ? 'Lumen-mac-universal.dmg' : 'Lumen-linux-x86_64.AppImage';
let upd = null;
let updBusy = false;
const sendUpd = (d) => run(`window.lumenUpdate&&lumenUpdate(${JSON.stringify(Object.assign({ version: upd ? upd.version : '' }, d))})`);
async function checkUpdate(manual) {
  if (manual) sendUpd({ state: 'checking' });
  try {
    const r = await fetch(RELEASES_API, { headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'Lumen' } });
    if (!r.ok) throw new Error(String(r.status));
    const j = await r.json();
    const n = Number(String(j.tag_name || '').split('.').pop()) || 0;
    const cur = Number(app.getVersion().split('.').pop()) || 0;
    const a = (j.assets || []).find((x) => x.name === ASSET);
    if (n > cur && a) {
      upd = { url: a.browser_download_url, name: a.name, version: String(j.tag_name).replace(/^v/, '') };
      sendUpd({ state: 'available' });
    } else if (manual) sendUpd({ state: 'latest' });
  } catch (e) {
    if (manual) sendUpd({ state: 'error' });
  }
}
async function doUpdate() {
  if (!upd) return checkUpdate(true);
  if (updBusy) return;
  updBusy = true;
  try {
    sendUpd({ state: 'downloading', progress: 0 });
    const r = await fetch(upd.url);
    if (!r.ok || !r.body) throw new Error('download');
    const total = Number(r.headers.get('content-length')) || 0;
    const file = path.join(app.getPath('temp'), upd.name);
    const out = fs.createWriteStream(file);
    let got = 0;
    let lastP = -1;
    for await (const chunk of r.body) {
      out.write(Buffer.from(chunk));
      got += chunk.length;
      const p = total ? Math.floor((got * 100) / total) : 0;
      if (p >= lastP + 5) { lastP = p; sendUpd({ state: 'downloading', progress: p }); }
    }
    await new Promise((res) => out.end(res));
    if (process.platform === 'linux') {
      fs.chmodSync(file, 0o755);
      shell.showItemInFolder(file);
      sendUpd({ state: 'ready-linux' });
    } else {
      await shell.openPath(file);
      if (process.platform === 'win32') { sendUpd({ state: 'installing' }); setTimeout(() => app.quit(), 1500); }
      else sendUpd({ state: 'ready-mac' });
    }
  } catch (e) {
    sendUpd({ state: 'error' });
  }
  updBusy = false;
}

/* ---- Privata flikar: en minnessession som kastas när sista privata fliken stängs ---- */
let privGen = 1;
const privTabs = new Set();
const privPartition = () => 'lumen-private-' + privGen;

let win = null;
let ui = null;
const tabs = new Map();
let current = null;
let showContent = false;
let area = { x: 0, y: 0, width: 1, height: 1 };

const isWeb = (u) => /^https?:\/\//i.test(u || '');
const run = (code) => { if (ui && !ui.webContents.isDestroyed()) ui.webContents.executeJavaScript(code).catch(() => {}); };
const openInLumen = (url) => run(`window.lumenOpen&&lumenOpen(${JSON.stringify(url)})`);

function createWindow() {
  win = new BaseWindow({
    width: 1320,
    height: 880,
    minWidth: 420,
    minHeight: 520,
    title: 'Lumen',
    icon: path.join(__dirname, 'build', 'icon.png'),
    backgroundColor: nativeTheme.shouldUseDarkColors ? '#161617' : '#ffffff',
    titleBarStyle: process.platform === 'darwin' ? 'hiddenInset' : 'default',
    trafficLightPosition: { x: 16, y: 18 }
  });
  ui = new WebContentsView({
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, additionalArguments: ['--lumen-version=' + app.getVersion()] }
  });
  ui.setBackgroundColor('#00000000');
  win.contentView.addChildView(ui);
  ui.webContents.loadFile(path.join(__dirname, 'ui', 'index.html'));
  ui.webContents.once('did-finish-load', () => setTimeout(() => checkUpdate(false), 3000));
  setInterval(() => checkUpdate(false), 6 * 60 * 60 * 1000);
  ui.webContents.setWindowOpenHandler(({ url }) => {
    if (isWeb(url)) openInLumen(url); else shell.openExternal(url);
    return { action: 'deny' };
  });
  ui.webContents.on('will-navigate', (e, url) => {
    if (url.startsWith('file:')) return;
    e.preventDefault();
    if (isWeb(url)) openInLumen(url); else shell.openExternal(url);
  });
  ui.webContents.on('before-input-event', (e, input) => shortcut(e, input));
  win.on('resize', fit);
  win.on('closed', () => { win = null; });
  fit();
}

function fit() {
  if (!win) return;
  const [w, h] = win.getContentSize();
  ui.setBounds({ x: 0, y: 0, width: w, height: h });
  applyLayout();
}

function shortcut(e, input) {
  if (input.type !== 'keyDown') return;
  const mod = process.platform === 'darwin' ? input.meta : input.control;
  const k = (input.key || '').toLowerCase();
  if (mod && ['t', 'l', 'b', 'd', 'r', 'w'].includes(k)) {
    e.preventDefault();
    run(`window.lumenKey&&lumenKey(${JSON.stringify(k)})`);
  } else if (input.key === 'F5') {
    e.preventDefault();
    run("window.lumenKey&&lumenKey('r')");
  } else if (input.alt && input.key === 'ArrowLeft') {
    e.preventDefault();
    run('window.lumenBack&&lumenBack()');
  }
}

function tab(id, priv) {
  if (tabs.has(id)) return tabs.get(id);
  const webPreferences = { contextIsolation: true, sandbox: true };
  if (priv) { webPreferences.partition = privPartition(); privTabs.add(id); }
  const v = new WebContentsView({ webPreferences });
  guard(v.webContents.session);
  wcToTab.set(v.webContents.id, id);
  v.setBackgroundColor('#ffffff');
  v.setVisible(false);
  const wc = v.webContents;
  const emit = () => {
    if (wc.isDestroyed()) return;
    const d = {
      tab: id,
      url: wc.getURL(),
      title: wc.getTitle(),
      back: wc.navigationHistory.canGoBack(),
      fwd: wc.navigationHistory.canGoForward(),
      progress: wc.isLoading() ? 60 : 100,
      blocked: blockedCount.get(wc.id) || 0
    };
    run(`window.lumenEvent&&lumenEvent(${JSON.stringify(d)})`);
  };
  emitters.set(wc.id, emit);
  wc.on('did-start-navigation', (d) => { if (d.isMainFrame && !d.isSameDocument) blockedCount.set(wc.id, 0); });
  ['did-start-loading', 'did-stop-loading', 'did-navigate', 'did-navigate-in-page', 'page-title-updated'].forEach((ev) => wc.on(ev, emit));
  wc.setWindowOpenHandler(({ url }) => {
    if (isWeb(url)) wc.loadURL(url); else shell.openExternal(url);
    return { action: 'deny' };
  });
  wc.on('will-navigate', (e, url) => { if (!isWeb(url) && !url.startsWith('about:')) { e.preventDefault(); shell.openExternal(url); } });
  wc.on('before-input-event', (e, input) => shortcut(e, input));
  wc.on('context-menu', (e, p) => {
    const items = [
      { label: 'Bakåt', enabled: wc.navigationHistory.canGoBack(), click: () => wc.navigationHistory.goBack() },
      { label: 'Framåt', enabled: wc.navigationHistory.canGoForward(), click: () => wc.navigationHistory.goForward() },
      { label: 'Ladda om', click: () => wc.reload() },
      { type: 'separator' }
    ];
    if (p.linkURL) items.push({ label: 'Öppna länk', click: () => wc.loadURL(p.linkURL) }, { label: 'Kopiera länk', click: () => require('electron').clipboard.writeText(p.linkURL) });
    if (p.selectionText) items.push({ label: 'Kopiera', role: 'copy' });
    if (p.isEditable) items.push({ label: 'Klistra in', role: 'paste' });
    Menu.buildFromTemplate(items).popup();
  });
  win.contentView.addChildView(v);
  tabs.set(id, v);
  return v;
}

function applyLayout() {
  for (const v of tabs.values()) v.setBounds(area);
  applyVisibility();
}

function applyVisibility() {
  for (const [id, v] of tabs) v.setVisible(showContent && id === current);
}

ipcMain.on('lumen', (e, cmd, ...a) => {
  if (!win || e.sender !== ui.webContents) return;
  const [id] = a;
  switch (cmd) {
    case 'show': {
      const v = tab(id, !!a[2]);
      const url = a[1];
      if (url && v.webContents.getURL() !== url) v.webContents.loadURL(url);
      current = id;
      v.setBounds(area);
      applyVisibility();
      break;
    }
    case 'close': {
      const v = tabs.get(id);
      if (v) {
        const wcId = v.webContents.id;
        win.contentView.removeChildView(v);
        v.webContents.close();
        tabs.delete(id);
        wcToTab.delete(wcId); blockedCount.delete(wcId); emitters.delete(wcId);
      }
      if (current === id) current = null;
      if (privTabs.delete(id) && privTabs.size === 0) {
        const old = session.fromPartition(privPartition());
        privGen += 1;
        old.clearStorageData().catch(() => {});
        old.clearCache().catch(() => {});
      }
      break;
    }
    case 'adblock': adblock = !!a[0]; break;
    case 'checkUpdate': checkUpdate(true); break;
    case 'update': doUpdate(); break;
    case 'back': tabs.get(id)?.webContents.navigationHistory.goBack(); break;
    case 'fwd': tabs.get(id)?.webContents.navigationHistory.goForward(); break;
    case 'reload': tabs.get(id)?.webContents.reload(); break;
    case 'layout': {
      const [x, y, w, h] = a.map((n) => Math.max(0, Math.round(Number(n) || 0)));
      area = { x, y, width: Math.max(1, w), height: Math.max(1, h) };
      applyLayout();
      break;
    }
    case 'visible': showContent = !!a[0]; applyVisibility(); break;
  }
});

app.on('open-url', (e, url) => { e.preventDefault(); openInLumen(url); });
// Visa Lumen som vanlig Chrome, så att t.ex. "Logga in med Google" fungerar.
app.userAgentFallback = app.userAgentFallback.replace(/ Electron\/\S+/, '').replace(/ lumen\/\S+/i, '');
app.whenReady().then(() => {
  loadBlocklist();
  createWindow();
  app.on('activate', () => { if (!win) createWindow(); });
});
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
