// Brygga mellan Lumens gränssnitt och webbmotorn.
const { contextBridge, ipcRenderer } = require('electron');
const send = (cmd, ...args) => ipcRenderer.send('lumen', cmd, ...args);
contextBridge.exposeInMainWorld('lumenHost', {
  platform: process.platform,
  version: (process.argv.find((a) => a.startsWith('--lumen-version=')) || '').split('=')[1] || '',
  show: (tab, url, priv) => send('show', String(tab), url || '', !!priv),
  setAdblock: (on) => send('adblock', !!on),
  close: (tab) => send('close', String(tab)),
  back: (tab) => send('back', String(tab)),
  fwd: (tab) => send('fwd', String(tab)),
  reload: (tab) => send('reload', String(tab)),
  layout: (x, y, w, h) => send('layout', x, y, w, h),
  visible: (v) => send('visible', !!v)
});
