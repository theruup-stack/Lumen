// Brygga mellan Lumens gränssnitt och webbmotorn.
const { contextBridge, ipcRenderer } = require('electron');
const send = (cmd, ...args) => ipcRenderer.send('lumen', cmd, ...args);
contextBridge.exposeInMainWorld('lumenHost', {
  platform: process.platform,
  version: (process.argv.find((a) => a.startsWith('--lumen-version=')) || '').split('=')[1] || '',
  show: (tab, url, priv, space) => send('show', String(tab), url || '', !!priv, String(space || '')),
  run: (tab, code, req) => send('run', String(tab), code, String(req)),
  fetch: (url, req) => send('fetch', url, String(req)),
  find: (tab, q) => send('find', String(tab), q),
  findNext: (tab, fwd) => send('findNext', String(tab), !!fwd),
  findClear: (tab) => send('findClear', String(tab)),
  downloads: (req) => send('downloads', '', String(req)),
  setAdAllow: (list) => send('adallow', '', JSON.stringify(list || [])),
  sitePerms: (req) => send('sitePerms', '', String(req)),
  resetSite: (host) => send('resetSite', String(host)),
  setZoom: (tab, pct) => send('zoom', String(tab), Number(pct) || 100),
  print: (tab) => send('print', String(tab)),
  openDownload: (id) => send('openDownload', String(id)),
  setAdblock: (on) => send('adblock', !!on),
  checkUpdate: () => send('checkUpdate'),
  update: () => send('update'),
  close: (tab) => send('close', String(tab)),
  back: (tab) => send('back', String(tab)),
  fwd: (tab) => send('fwd', String(tab)),
  reload: (tab) => send('reload', String(tab)),
  layout: (x, y, w, h) => send('layout', x, y, w, h),
  visible: (v) => send('visible', !!v)
});
