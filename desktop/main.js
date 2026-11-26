// Lumen för Windows, macOS och Linux.
// Gränssnittet ligger i en vy som täcker hela fönstret; varje flik är en egen
// webbvy som läggs exakt över gränssnittets innehållsyta.
const { app, BaseWindow, WebContentsView, ipcMain, shell, nativeTheme, Menu } = require('electron');
const path = require('path');

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
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true }
  });
  ui.setBackgroundColor('#00000000');
  win.contentView.addChildView(ui);
  ui.webContents.loadFile(path.join(__dirname, 'ui', 'index.html'));
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

function tab(id) {
  if (tabs.has(id)) return tabs.get(id);
  const v = new WebContentsView({ webPreferences: { contextIsolation: true, sandbox: true } });
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
      progress: wc.isLoading() ? 60 : 100
    };
    run(`window.lumenEvent&&lumenEvent(${JSON.stringify(d)})`);
  };
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
      const v = tab(id);
      const url = a[1];
      if (url && v.webContents.getURL() !== url) v.webContents.loadURL(url);
      current = id;
      v.setBounds(area);
      applyVisibility();
      break;
    }
    case 'close': {
      const v = tabs.get(id);
      if (v) { win.contentView.removeChildView(v); v.webContents.close(); tabs.delete(id); }
      if (current === id) current = null;
      break;
    }
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
app.whenReady().then(() => {
  createWindow();
  app.on('activate', () => { if (!win) createWindow(); });
});
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
