/* LinkAssist local workspace. No remote assets, optimistic delivery claims, or HTML injection. */
(() => {
  'use strict';
  const $ = s => document.querySelector(s), $$ = s => [...document.querySelectorAll(s)];
  const MAX_FILE = 512 * 1024 * 1024, NS = 'http://www.w3.org/2000/svg';
  const LOCAL = ['127.0.0.1', 'localhost', '[::1]'].includes(location.hostname);
  const APP = new URLSearchParams(location.search).get('app') === '1';
  const MINI = new URLSearchParams(location.search).get('mini') === '1';
  const views = {
    connections: ['设备连接', '连接你的设备', '一次扫码，让手机与电脑在同一局域网内互通。', 'CONNECTED WORKSPACE'],
    messages: ['消息', '接着聊，不必切换设备', '聊天、验证码与通知，在这里有序汇集。', 'MESSAGES, IN SYNC'],
    files: ['文件', '文件，轻松到另一端', '从手机到电脑，从这台设备到下一台。', 'FILES WITHOUT FRICTION'],
    history: ['历史', '每一次传输，都有迹可循', '查看真实传输状态，按需重新下载。', 'TRANSFER HISTORY'],
    settings: ['设置', '让互传更合你的习惯', '管理更新来源与偏好，保持工作不中断。', 'MAKE IT YOURS']
  };
  let cfg = null, devices = [], messages = [], view = 'connections', filter = 'all', target = '', targetName = '';
  let socket, initialized = false, firstInit = true, userNavigated = false, unread = 0, composing = false;
  let reconnectTimer, handshakeTimer, reconnectAt = 0, reconnectDelay = 1000, channel = 'connecting';
  let pendingMessage = null, messageTimer, messageRevision = 0, clearBusy = false, toastTimer, lastErrorAt = 0;
  let pairing = null, pairBusy = false, pairSeq = 0, pairController, pairRetryAt = 0, selectedHost = '', qrSource = '';
  const transfers = new Map(), jobs = [], downloads = new Set(), toastTimes = new Map();
  let transferSeq = 0, transferRevision = 0, transferBusy = false, renderTimer, jobNumber = 0, queueRunning = false;
  let update = null, updateSeq = 0, updateRevision = 0, updateBusy = false, updatePollTimer;
  let settingsDirty = false, settingsSaving = false, ballBusy = false;

  /* Small safe DOM and request helpers. All server/user strings are text nodes. */
  function el(tag, className = '', text) {
    const node = document.createElement(tag); if (className) node.className = className;
    if (text !== undefined) node.textContent = String(text); return node;
  }
  function icon(name) {
    const svg = document.createElementNS(NS, 'svg'), use = document.createElementNS(NS, 'use');
    svg.setAttribute('class', 'icon'); svg.setAttribute('aria-hidden', 'true');
    use.setAttribute('href', '#i-' + name); svg.append(use); return svg;
  }
  function button(text, action, className = 'button ghost small') {
    const b = el('button', className, text); b.type = 'button'; b.onclick = action; return b;
  }
  function notice(id, text) { const node = $(id); node.textContent = text || ''; node.hidden = !text; }
  function toast(text, error = false, force = false) {
    text = String(text || '操作失败'); const now = Date.now();
    if (error && !force && (now - (toastTimes.get(text) || 0) < 5000 || now - lastErrorAt < 1100)) return;
    if (error) lastErrorAt = now;
    if (toastTimes.size > 40) toastTimes.clear(); toastTimes.set(text, now);
    const node = $('#toast'); node.textContent = text; node.classList.toggle('error', error); node.classList.add('show');
    clearTimeout(toastTimer); toastTimer = setTimeout(() => node.classList.remove('show'), error ? 5500 : 2800);
  }
  const ready = () => initialized && socket?.readyState === WebSocket.OPEN;
  const canTarget = id => ready() && devices.length > 0 && (!id || devices.some(d => d.id === id));
  const auth = path => path + (path.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(cfg?.token || '');
  const size = n => !Number.isFinite(Number(n)) ? '—' : n < 1024 ? `${n} B` : n < 1048576 ? `${(n / 1024).toFixed(1)} KiB` : `${(n / 1048576).toFixed(1)} MiB`;
  function time(ts) { const d = new Date(Number(ts)); return Number.isFinite(d.getTime()) && Number(ts) > 0 ? d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '时间未提供'; }
  async function api(path, options = {}, timeout = 15000) {
    const controller = new AbortController(), parent = options.signal;
    const abort = () => controller.abort(); let timedOut = false;
    if (parent?.aborted) abort(); else parent?.addEventListener('abort', abort, { once: true });
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeout);
    try {
      const response = await fetch(path, { ...options, cache: 'no-store', credentials: 'same-origin', signal: controller.signal });
      const text = await response.text(); let data;
      try { data = text ? JSON.parse(text) : {}; } catch (_) { data = null; }
      if (!response.ok) { const error = new Error(data?.error || ({ 401: '本机授权已失效，请重新连接', 403: '请求被拒绝，请使用本机控制台', 404: '当前服务尚未提供此功能', 413: '文件超过 512 MiB 上限' }[response.status]) || `服务请求失败（HTTP ${response.status}）`); error.status = response.status; throw error; }
      if (!data || typeof data !== 'object') throw new Error('服务返回了无法识别的数据');
      return data;
    } catch (error) {
      if (timedOut) throw new Error('请求超时，请稍后重试');
      if (error.name === 'TypeError') throw new Error('无法连接本机服务，请检查程序是否仍在运行');
      throw error;
    } finally { clearTimeout(timer); parent?.removeEventListener('abort', abort); }
  }
  const post = (body = {}) => ({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  async function copy(text, b, tip = '已复制') {
    if (text === undefined || text === null || String(text) === '') return toast('暂无可复制的内容', true);
    const nodes = b ? [...b.childNodes] : []; if (b) { b.disabled = true; b.dataset.copying = 'true'; b.setAttribute('aria-busy', 'true'); b.replaceChildren(icon('copy'), el('span', '', '复制中…')); }
    try {
      try { if (!navigator.clipboard?.writeText) throw new Error(); await navigator.clipboard.writeText(String(text)); }
      catch (_) {
        const active = document.activeElement, ta = el('textarea', 'clipboard-helper'); ta.value = String(text); ta.readOnly = true;
        document.body.append(ta); ta.select(); let ok;
        try { ok = document.execCommand('copy'); } finally { ta.remove(); active?.focus({ preventScroll: true }); }
        if (!ok) throw new Error('剪贴板不可用，请选中文字手动复制');
      }
      toast(tip);
    } catch (error) { toast(error.message || '复制失败，请重试', true, true); }
    finally { if (b) { b.replaceChildren(...nodes); b.disabled = false; delete b.dataset.copying; b.removeAttribute('aria-busy'); } controls(); }
  }

  /* Navigation, live device roster, and a recipient shared by chat and files. */
  function navigate(next, explicit = true) {
    if (!views[next]) return;
    if (explicit) userNavigated = true;
    const changed = view !== next; view = next;
    $$('.nav-item').forEach(b => { const active = b.dataset.view === next; b.classList.toggle('active', active); active ? b.setAttribute('aria-current', 'page') : b.removeAttribute('aria-current'); });
    $$('.page').forEach(p => { p.hidden = p.id !== 'page-' + next; });
    ['#viewLabel', '#pageTitle', '#pageDescription', '#viewEyebrow'].forEach((id, i) => { $(id).textContent = views[next][i]; });
    $('#topRecipient').hidden = !['messages', 'files'].includes(next);
    $('#mainScroll').scrollTop = 0;
    if (next === 'messages') { unread = 0; if (changed) renderMessages(); }
    if (next === 'settings' && ready() && !update) loadUpdates();
    updateUnread(); if (explicit) $('#pageTitle').focus({ preventScroll: true });
  }
  function updateUnread() { $('#navMessageCount').hidden = !unread; $('#navMessageCount').textContent = unread > 99 ? '99+' : unread; }
  function selectRecipient(value) {
    target = value; targetName = devices.find(d => d.id === value)?.name || targetName;
    [$('#recipientSelect'), $('#recipientComposer')].forEach(s => { s.value = value; }); controls();
  }
  function roster(list) {
    devices = Array.isArray(list) ? list.filter(d => d && d.id != null && d.online !== false).map(d => ({ id: String(d.id), name: String(d.name || '未命名设备') })) : [];
    if (devices.some(d => d.id === target)) targetName = devices.find(d => d.id === target).name;
    renderConnection(); pumpUploads();
  }
  function renderConnection() {
    const online = ready(), connecting = channel === 'connecting';
    const seconds = Math.max(1, Math.ceil((reconnectAt - Date.now()) / 1000));
    $('#serviceText').textContent = online ? (devices.length ? `${devices.length} 台设备在线` : '等待设备') : (connecting ? '连接本机服务中' : '连接已中断');
    $('#sidebarStatus').textContent = online ? '本机服务已连接' : (connecting ? '正在连接本机服务' : '正在等待重连');
    $('#dotService').className = 'dot' + (online && devices.length ? ' on' : ' wait'); $('#sidebarDot').className = 'dot' + (online ? ' on' : '');
    $('#connectionNotice').hidden = online;
    $('#connectionNoticeText').textContent = !LOCAL ? '控制台仅供本机使用，请在电脑上通过 127.0.0.1 打开。' : connecting ? '正在与本机服务建立连接…' : `实时连接已中断，${seconds} 秒后重试。草稿与队列已保留。`;
    $('#btnReconnect').disabled = !LOCAL || connecting;
    $('#channelText').textContent = online ? '本机实时通道已连通 · 设备状态实时同步' : '实时通道未连接 · 暂时无法确认设备状态';
    $('#deviceCount').textContent = online ? `${devices.length} 台在线` : '状态待同步';
    $('#deviceEmpty').hidden = online && devices.length > 0;
    $('#deviceEmptyTitle').textContent = online ? '等待手机连接' : '设备状态待同步';
    $('#deviceEmptyHint').textContent = online ? '扫码完成后，设备会显示在这里。' : '本机服务连接恢复后，自动更新在线设备。';
    const list = $('#deviceList'); list.replaceChildren();
    if (online) for (const d of devices) {
      const row = el('div', 'device-row'), av = el('span', 'device-avatar'), info = el('div', 'device-info'), state = el('span', 'device-online');
      av.append(icon('phone')); info.append(el('div', 'device-name', d.name), el('div', 'device-sub', 'ID ' + d.id)); state.append(el('span', 'dot on'), document.createTextNode('在线')); row.append(av, info, state); list.append(row);
    }
    for (const select of [$('#recipientSelect'), $('#recipientComposer')]) {
      select.replaceChildren(new Option('全部在线设备', ''));
      for (const d of devices) select.add(new Option(d.name + (online ? '' : '（状态待同步）'), d.id));
      if (target && !devices.some(d => d.id === target)) { const o = new Option((targetName || '已选设备') + '（离线）', target); o.disabled = true; select.add(o); }
      select.value = target;
    }
    manualInfo(); controls(); tickPair();
  }
  function controls() {
    const online = ready(), can = canTarget(target);
    $('#btnSend').disabled = !can || !$('#messageInput').value.trim() || !!pendingMessage || composing;
    $('#messageInput').readOnly = !!pendingMessage; $('#sendLabel').textContent = pendingMessage ? '确认中…' : '发送';
    $('#composeHint').textContent = pendingMessage ? '等待本机服务确认；这不代表手机已读。' : !online ? '连接中断，草稿已保留；恢复连接后可发送。' : !devices.length ? '请先连接手机。Enter 发送，Shift + Enter 换行。' : !can ? '所选设备已离线，请重新选择接收对象。' : 'Enter 发送 · Shift + Enter 换行';
    $('#btnClear').disabled = !online || !messages.length || clearBusy;
    $('#btnPickFile').disabled = !can; $('#dropZone').setAttribute('aria-disabled', String(!can));
    $('#uploadHint').textContent = !online ? '实时连接已中断，队列将在连接恢复后继续。' : !can ? '请连接手机，或选择一个在线接收对象。' : `发送至：${target ? targetName : '全部在线设备'} · 每次传输一个文件，可取消或重试。`;
    $('#btnRefreshTransfers').disabled = $('#btnRefreshHistory').disabled = !online || transferBusy;
    $('#btnRefreshPair').disabled = !online || pairBusy;
    $('#btnCopyPair').disabled = !online || pairBusy || !pairing || pairing.expiresAt <= Date.now();
    $('#hostSelect').disabled = !online || pairBusy || !$('#hostSelect').value;
    $('#btnCopyAddr').disabled = !online || !cfg; $('#btnCopyToken').disabled = !online || !cfg?.token;
    $('#btnCheckUpdates').disabled = !online || updateBusy || update?.status === 'checking';
    $('#btnSaveSettings').disabled = !online || settingsSaving || !settingsDirty;
    $('#autoCheckUpdates').disabled = $('#updateRepository').disabled = settingsSaving;
    [$('#recipientSelect'), $('#recipientComposer')].forEach(s => { s.disabled = !!pendingMessage; });
    $$('[data-copying]').forEach(b => { b.disabled = true; });
    updateBallControls();
  }
  /* 桌面悬浮球开关:只有桌面客户端能真正创建窗口,浏览器控制台里显示为不可用 */
  function ballSupported() { return typeof window.pywebview?.api?.set_ball_enabled === 'function'; }
  function updateBallControls() {
    const box = $('#ballEnabled'); if (!box) return;
    const supported = ballSupported();
    box.disabled = !supported || ballBusy;
    const tray = $('#closeToTray'); if (tray) tray.disabled = !supported;
    const quitBtn = $('#btnQuitApp'); if (quitBtn) quitBtn.disabled = !supported;
    if (ballBusy) return;
    $('#ballHint').textContent = supported
      ? (box.checked ? '悬浮球已开启：点击它打开迷你面板，右键打开主窗口。' : '开启后屏幕上出现圆形悬浮球，可随时关闭。')
      : '仅在电脑桌面客户端中可用；浏览器控制台没有悬浮球。';
  }
  async function toggleBall(event) {
    const box = event.target, wanted = box.checked;
    if (!ballSupported()) { box.checked = false; return updateBallControls(); }
    ballBusy = true; updateBallControls();
    try {
      await api('/api/settings', post({ ballEnabled: wanted }));
      const applied = await window.pywebview.api.set_ball_enabled(wanted);
      if (applied !== wanted) throw new Error(wanted ? '系统未能创建悬浮球窗口' : '系统未能关闭悬浮球');
      cfg = { ...cfg, ballEnabled: wanted };
      toast(wanted ? '桌面悬浮球已开启' : '桌面悬浮球已关闭');
    } catch (error) {
      box.checked = !wanted;
      toast('悬浮球设置失败：' + error.message, true);
    } finally { ballBusy = false; updateBallControls(); }
  }
  /* 关闭行为:收进托盘(后台继续) 还是 直接退出 */
  async function toggleCloseToTray(event) {
    const box = event.target, wanted = box.checked;
    if (!ballSupported()) { box.checked = true; return updateBallControls(); }
    box.disabled = true;
    try {
      await api('/api/settings', post({ closeToTray: wanted }));
      cfg = { ...cfg, closeToTray: wanted };
      toast(wanted ? '关闭窗口后将收进托盘，后台继续运行' : '关闭窗口将直接退出程序');
    } catch (error) {
      box.checked = !wanted;
      toast('设置失败：' + error.message, true);
    } finally { box.disabled = false; updateBallControls(); }
  }
  async function hideToTray() {
    if (!ballSupported()) return toast('此操作仅在桌面客户端中可用。', true);
    try { await window.pywebview.api.hide_to_tray(); toast('已最小化到托盘，后台继续运行'); }
    catch (_) { toast('最小化失败，请从系统托盘操作。', true); }
  }
  async function requestClose() {
    if (!ballSupported()) { if (confirm('关闭当前页面？后台服务仍在运行。')) window.close(); return; }
    try {
      const result = await window.pywebview.api.request_close();
      if (result === 'hidden') toast('已收进托盘，手机连接与传输不受影响');
      else if (result === 'error') toast('未能收进托盘，请使用系统托盘退出。', true);
    } catch (_) { toast('关闭操作未完成，请重试。', true); }
  }
  /* 迷你面板:深色聊天式小窗,标题栏两个按钮分别是"打开主程序"和"关闭" */
  function applyMiniMode() {
    document.body.classList.add('mini');
    document.title = 'LinkAssist · 迷你面板';
    const label = $('#desktopTitlebar .titlebar-label span'); if (label) label.textContent = '互传助手 · 迷你面板';
    const toMain = $('#btnCollapse'); toMain.title = '打开主程序'; toMain.setAttribute('aria-label', '打开主程序');
    toMain.querySelector('use')?.setAttribute('href', '#i-external');
    toMain.onclick = () => nativeCall('show_main', '此操作仅在桌面客户端中可用。');
    const closeMini = $('#btnQuit'); closeMini.title = '关闭迷你面板'; closeMini.setAttribute('aria-label', '关闭迷你面板');
    closeMini.onclick = () => nativeCall('close_mini', '此操作仅在桌面客户端中可用。');
    const input = $('#messageInput'); if (input) input.placeholder = '发消息给手机…';
    installMiniResize();
    navigate('messages', false);
  }
  /* 迷你面板缩放:拖右边缘 / 下边缘 / 右下角;窗口大小交给桌面端设置,尺寸记忆在配置里 */
  function installMiniResize() {
    const ready = () => typeof window.pywebview?.api?.resize_mini === 'function';
    if (!ready()) {
      // 页面加载时 pywebview 桥接可能尚未注入,等它就绪后再装(避免重复安装)
      if (installMiniResize.pending) return;
      installMiniResize.pending = true;
      const retry = () => { installMiniResize.pending = false; installMiniResize(); };
      window.addEventListener('pywebviewready', retry, { once: true });
      let tries = 0;
      const timer = setInterval(() => { if (ready() || ++tries > 40) { clearInterval(timer); retry(); } }, 250);
      return;
    }
    if (installMiniResize.done) return;
    installMiniResize.done = true;
    const drag = { dir: '', startX: 0, startY: 0, startW: 0, startH: 0, lastW: 0, lastH: 0, sentAt: 0 };
    function apply(width, height, remember) {
      const minW = 360, minH = 420;
      width = Math.max(minW, Math.min(Math.round(width), 1400));
      height = Math.max(minH, Math.min(Math.round(height), 1600));
      try { window.pywebview.api.resize_mini(width, height, !!remember).catch(() => {}); } catch (_) { /* 关闭瞬间忽略 */ }
    }
    function start(dir, event) {
      if (event.button !== 0) return;
      drag.dir = dir; drag.startX = event.clientX; drag.startY = event.clientY;
      drag.startW = window.innerWidth; drag.startH = window.innerHeight;
      drag.sentAt = 0; document.body.classList.add('resizing');
      event.preventDefault();
    }
    function move(event) {
      if (!drag.dir) return;
      drag.lastW = drag.startW + (event.clientX - drag.startX);
      drag.lastH = drag.startH + (event.clientY - drag.startY);
      const now = performance.now();                      // 拖动中限频,松手时再记尺寸
      if (now - drag.sentAt < 45) return;
      drag.sentAt = now; apply(drag.lastW, drag.lastH, false);
    }
    function end() {
      if (!drag.dir) return;
      apply(drag.lastW || window.innerWidth, drag.lastH || window.innerHeight, true);
      drag.dir = ''; drag.lastW = 0; drag.lastH = 0;
      document.body.classList.remove('resizing');
    }
    [['right', '左右拖动调整宽度'], ['bottom', '上下拖动调整高度'], ['corner', '拖动调整窗口大小']].forEach(([dir, label]) => {
      const grip = el('div', 'mini-resize ' + dir);
      grip.setAttribute('role', 'separator'); grip.setAttribute('aria-label', label); grip.title = label;
      grip.addEventListener('mousedown', event => start(dir, event));
      document.body.append(grip);
    });
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', end);
    window.addEventListener('blur', end);
  }

  /* Pairing codes are local server SVG images, never inserted as markup. */
  function manualInfo() {
    const host = pairing?.host || cfg?.addr, port = pairing?.port || cfg?.port;
    $('#detailAddr').textContent = host && port ? `http://${host}:${port}` : '等待服务连接';
    $('#detailToken').textContent = cfg?.token || '尚未获取'; $('#detailName').textContent = pairing?.name || cfg?.name || '尚未获取';
  }
  async function loadPair(force = false, host = selectedHost) {
    if (!ready()) return;
    const seq = ++pairSeq; pairController?.abort(); pairController = new AbortController(); pairBusy = true; tickPair(); controls();
    try {
      const data = await api(force ? '/api/pairing/refresh' : '/api/pairing' + (host ? '?host=' + encodeURIComponent(host) : ''), { ...(force ? post(host ? { host } : {}) : {}), signal: pairController.signal });
      if (seq !== pairSeq) return;
      if (typeof data.uri !== 'string' || !data.uri || typeof data.qrSvg !== 'string' || !/<svg[\s>]/i.test(data.qrSvg) || !Number.isFinite(Number(data.expiresAt))) throw new Error('配对信息不完整，请刷新二维码');
      pairing = { ...data, expiresAt: Number(data.expiresAt) }; selectedHost = String(data.host || host); qrSource = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(data.qrSvg);
      const hosts = [...new Set([selectedHost, ...(Array.isArray(data.hosts) ? data.hosts : [])])].filter(h => typeof h === 'string' && /^(\d{1,3}\.){3}\d{1,3}$/.test(h) && h.split('.').every(n => +n <= 255));
      $('#hostSelect').replaceChildren(...hosts.map(h => new Option(h + (h === selectedHost ? ' · 当前接口' : ''), h))); $('#hostSelect').value = selectedHost;
      $('#pairHostHint').textContent = `端口 ${data.port || cfg?.port || '—'} · 选择手机可访问的 IPv4 地址。`;
      notice('#pairError', ''); pairRetryAt = Date.now() + 10000; manualInfo();
    } catch (error) {
      if (seq !== pairSeq || error.name === 'AbortError') return;
      pairing = null; if (error.status === 409) selectedHost = ''; pairRetryAt = Date.now() + 15000; notice('#pairError', error.message); toast('无法获取二维码：' + error.message, true);
    } finally { if (seq === pairSeq) { pairBusy = false; tickPair(); controls(); } }
  }
  function tickPair() {
    const left = pairing ? Math.max(0, Math.ceil((pairing.expiresAt - Date.now()) / 1000)) : 0;
    const valid = ready() && !pairBusy && !!pairing && left > 0;
    $('#qrFrame').setAttribute('aria-busy', String(pairBusy)); $('#qrImage').hidden = !valid; $('#qrPlaceholder').hidden = valid;
    if (valid && $('#qrImage').getAttribute('src') !== qrSource) $('#qrImage').src = qrSource;
    $('#qrState').textContent = !ready() ? '等待本机服务' : pairBusy ? '正在生成二维码' : pairing ? '二维码已失效' : '二维码暂不可用';
    $('#qrHint').textContent = !ready() ? '连接恢复后自动重新获取' : pairBusy ? '请稍候，由本机安全生成' : '请点击刷新，或展开手动配对';
    $('#pairCountdown').textContent = valid ? `剩余 ${Math.floor(left / 60).toString().padStart(2, '0')}:${(left % 60).toString().padStart(2, '0')} · 一次有效` : pairBusy ? '正在获取新的连接码' : '连接码未就绪';
    $('#qrDot').className = 'dot' + (valid ? (left <= 30 ? ' wait' : ' on') : '');
    $('#pairCountdown').parentElement.classList.toggle('expiring', valid && left <= 30);
    $('#btnCopyPair').disabled = !valid || $('#btnCopyPair').hasAttribute('data-copying');
    if (ready() && pairing && !left && !pairBusy && Date.now() > pairRetryAt) loadPair(true);
    else if (ready() && !pairing && !pairBusy && pairRetryAt > 0 && view === 'connections' && Date.now() > pairRetryAt) loadPair();
  }

  /* Messages: plaintext rendering, IME-safe composer, server-confirmed echo only. */
  const messageText = m => [m.text, m.body, m.from, m.title, m.app, m.code, m.fileName].filter(v => v != null).join(' ');
  function messageCard(m) {
    const chat = m.type === 'chat', out = m.direction === 'out';
    const row = el('article', chat ? 'bubble' + (out ? ' out' : '') : 'message-card'); row.setAttribute('role', 'listitem');
    const body = el('div', 'message-content'), meta = el('div', 'message-meta');
    if (!chat) { const avatar = el('span', 'message-avatar'); avatar.append(icon(m.type === 'sms' ? 'message' : 'bell')); row.append(avatar); meta.append(el('span', 'badge ' + (m.code ? 'success' : 'neutral'), m.type === 'sms' ? '短信' : '通知')); }
    const source = out ? '我 → ' + (m.targetDeviceId ? (devices.find(d => d.id === String(m.targetDeviceId))?.name || m.targetName || '指定设备') : '全部设备') : (m.from || m.app || '手机') + (m.title ? ' · ' + m.title : '');
    meta.append(el('span', 'message-source', source), el('time', 'message-time', time(m.ts))); body.append(meta);
    body.append(el('div', 'message-body', chat ? (m.text || '') : (m.body || '')));
    if (chat && m.fileName) {
      const fileName = String(m.fileName), extension = fileName.split('.').pop().toLowerCase();
      const kind = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'].includes(extension) ? 'img' : ['mp4', 'webm', 'mov', '3gp'].includes(extension) ? 'video' : '';
      if (kind && cfg?.token) {
        const media = el(kind, 'message-media'); media.src = auth('/api/received/' + encodeURIComponent(fileName));
        if (kind === 'img') { media.alt = fileName; media.loading = 'lazy'; } else { media.controls = true; media.preload = 'metadata'; }
        media.onerror = () => { media.hidden = true; if (!body.querySelector('.media-error')) body.append(el('p', 'media-error', '预览不可用，可在文件记录中下载。')); }; body.append(media);
      }
      const attachment = button('', () => downloadAttachment(m, attachment), 'attachment-button'); attachment.append(icon('file'), el('span', '', fileName), icon('download')); attachment.setAttribute('aria-label', '下载附件 ' + fileName); body.append(attachment);
    }
    if (m.code) { const code = button('', () => copy(m.code, code, '已复制验证码'), 'code-button'); code.append(el('strong', '', m.code), el('span', '', '复制验证码')); body.append(code); }
    const actions = el('div', 'message-actions'), cp = button('复制全文', () => copy(chat ? (m.text || m.fileName || '') : (m.body || ''), cp), 'text-button'); actions.append(cp); body.append(actions); row.append(body); return row;
  }
  function renderMessages() {
    const query = $('#messageSearch').value.trim().toLocaleLowerCase();
    const shown = messages.filter(m => (filter === 'all' || (filter === 'chat' ? m.type === 'chat' : m.type === 'sms' || m.type === 'notif')) && (!query || messageText(m).toLocaleLowerCase().includes(query))).slice().sort((a, b) => Number(b.ts || 0) - Number(a.ts || 0));
    const fragment = document.createDocumentFragment(); shown.forEach(m => fragment.append(messageCard(m))); $('#feed').replaceChildren(fragment);
    $('#messageEmpty').hidden = shown.length > 0;
    $('#messageEmptyTitle').textContent = messages.length ? '没有匹配的消息' : '让消息在设备间流动';
    $('#messageEmptyHint').textContent = messages.length ? '试试其他关键词，或切换消息分类。' : '连接手机后，聊天、验证码与通知会显示在这里。'; controls();
  }
  function syncMessages() { if (view === 'messages') renderMessages(); else controls(); }
  function settleMessage(confirmed, reason) {
    clearTimeout(messageTimer); if (!pendingMessage) return;
    if (confirmed && $('#messageInput').value === pendingMessage.draft) $('#messageInput').value = '';
    pendingMessage = null; if (reason) toast(reason, true); controls();
  }
  function sendMessage(event) {
    event?.preventDefault(); const draft = $('#messageInput').value, text = draft.trim();
    if (!text || composing || pendingMessage) return;
    if (!canTarget(target)) return toast('接收设备或实时连接不可用，消息未发送', true);
    pendingMessage = { text, draft, target };
    try { socket.send(JSON.stringify({ type: 'chat', text, targetDeviceId: target })); }
    catch (_) { return settleMessage(false, '发送失败，草稿已保留'); }
    controls(); messageTimer = setTimeout(() => settleMessage(false, '未收到服务确认，草稿已保留。重发前请确认对方是否已收到，避免重复。'), 10000);
  }
  function receiveMessage(m) {
    if (!m || typeof m !== 'object') return;
    if (pendingMessage && m.type === 'chat' && m.direction === 'out' && m.text === pendingMessage.text && (m.targetDeviceId == null || String(m.targetDeviceId) === pendingMessage.target)) settleMessage(true);
    const index = m.id == null ? -1 : messages.findIndex(x => String(x.id) === String(m.id));
    if (index >= 0) messages[index] = m; else { messages.push(m); if (view !== 'messages' && m.direction !== 'out') unread++; }
    messages = messages.slice(-500); messageRevision++; syncMessages(); updateUnread();
  }

  /* Transfers: stable creation ordering, resilient queries, and fresh download grants. */
  const activeStatus = s => ['queued', 'offering', 'offered', 'uploading', 'processing', 'downloading', 'cancelling'].includes(s);
  function statusLabel(t) {
    if (t.status === 'complete') return t.direction === 'phone_to_pc' ? '已送达电脑' : '中转就绪';
    if (t.status === 'queued' && !canTarget(t.targetDeviceId)) return '排队中 · 等待设备上线';
    return ({ queued: '排队中', offering: '准备传输', offered: '等待上传', uploading: '正在上传', processing: '电脑正在校验', downloading: '下载中', cancelling: '正在取消', cancelled: '已取消', failed: '传输失败' })[t.status] || '等待状态同步';
  }
  function mergeTransfer(t, render = true) {
    if (!t || t.id == null) return;
    const id = String(t.id); transfers.set(id, { ...transfers.get(id), ...t, id, _revision: ++transferRevision });
    if (render) scheduleTransfers();
  }
  function transferRows() {
    const rows = [...transfers.values()].map(t => ({ ...t, _key: t.id }));
    for (const j of jobs) {
      const index = rows.findIndex(t => t.id === j.id), server = index >= 0 ? rows.splice(index, 1)[0] : {};
      const completed = j.id ? transfers.get(j.id) : null;
      if (completed?.status === 'complete' && ['failed', 'cancelled'].includes(j.status)) { j.status = 'complete'; j.progress = 100; j.file = null; j.error = ''; }
      rows.push({ ...server, ...j, _job: j, _key: j.key, direction: 'pc_to_phone', targetDeviceId: j.target });
    }
    return rows.sort((a, b) => Number(b.created || b.createdAt || 0) - Number(a.created || a.createdAt || 0) || String(a._key).localeCompare(String(b._key)));
  }
  function scheduleTransfers() { if (renderTimer) return; renderTimer = setTimeout(() => { renderTimer = null; renderTransfers(); }, 100); }
  function transferItem(t) {
    const row = el('article', 'transfer-item'), head = el('div', 'transfer-head'), info = el('div', 'transfer-info'), avatar = el('span', 'file-avatar'); row.setAttribute('role', 'listitem');
    avatar.append(icon(t.direction === 'phone_to_pc' ? 'download' : 'file'));
    const recipient = t.direction === 'phone_to_pc' ? '手机 → 电脑' : '电脑 → ' + (t.targetName || (t.targetDeviceId ? devices.find(d => d.id === t.targetDeviceId)?.name || '指定设备' : '全部设备'));
    info.append(el('div', 'file-name', t.name || '未命名文件'), el('div', 'file-meta', `${size(t.size || 0)} · ${recipient} · ${time(t.created || t.createdAt)}`)); head.append(avatar, info);
    const actions = el('div', 'transfer-actions');
    function add(text, fn, key, disabled = false) { const b = button(text, fn); b.disabled = disabled; b.dataset.focus = t._key + ':' + key; b.setAttribute('aria-label', text + ' ' + (t.name || '文件')); actions.append(b); }
    if (t._job && activeStatus(t.status)) add('取消', () => cancelJob(t._job), 'cancel', t.status === 'cancelling');
    if (t._job?.file && ['failed', 'cancelled'].includes(t.status)) add('重试', () => retryJob(t._job), 'retry', !canTarget(t._job.target));
    if (t.status === 'complete' && t.id) add(downloads.has(t.id) ? '获取中…' : '下载', () => downloadTransfer(t.id), 'download', !ready() || downloads.has(t.id));
    head.append(actions); row.append(head);
    const percent = t.status === 'complete' ? 100 : Math.max(0, Math.min(100, Number(t.progress) || 0));
    const detail = el('div', 'transfer-detail'); detail.append(el('span', 'status-' + (['complete', 'failed'].includes(t.status) ? t.status : 'active'), statusLabel(t)), el('span', '', activeStatus(t.status) ? `${Math.round(percent)}%${t.speed ? ' · ' + size(t.speed) + '/s' : ''}` : ''));
    row.append(detail);
    if (activeStatus(t.status) || ['failed', 'cancelled'].includes(t.status)) {
      const track = el('div', 'progress ' + (['failed', 'cancelled'].includes(t.status) ? t.status : '')), bar = el('span'); bar.style.width = percent + '%'; track.append(bar); track.setAttribute('role', 'progressbar'); track.setAttribute('aria-label', '文件传输进度'); track.setAttribute('aria-valuemin', '0'); track.setAttribute('aria-valuemax', '100'); track.setAttribute('aria-valuenow', String(Math.round(percent))); row.append(track);
    }
    if (t.error) row.append(el('p', 'transfer-error', t.error));
    if (t.savedAs && t.status === 'complete') row.append(el('p', 'file-meta', '电脑保存位置：' + t.savedAs)); return row;
  }
  function renderTransfers() {
    clearTimeout(renderTimer); renderTimer = null; const rows = transferRows(), query = $('#historySearch').value.trim().toLowerCase(), state = $('#historyFilter').value;
    const focused = document.activeElement?.dataset.focus, focusedList = document.activeElement?.closest('.transfer-list')?.id;
    const history = rows.filter(t => (!query || [t.name, t.targetName, statusLabel(t), t.status].join(' ').toLowerCase().includes(query)) && (state === 'all' || (state === 'active' ? activeStatus(t.status) : t.status === state)));
    $('#transferList').replaceChildren(...rows.map(transferItem)); $('#historyList').replaceChildren(...history.map(transferItem));
    $('#transferEmpty').hidden = rows.length > 0; $('#historyEmpty').hidden = history.length > 0;
    $('#historyEmptyTitle').textContent = rows.length ? '没有匹配的记录' : '每一次传输，都留有记录'; $('#historyEmptyHint').textContent = rows.length ? '换个关键词，或选择其他状态。' : '文件传输开始后，可在这里查看结果与重新下载。';
    const pending = jobs.filter(j => activeStatus(j.status)).length;
    $('#queueSummary').textContent = pending ? `${pending} 项进行中 / 待传` : rows.length ? `${rows.length} 条记录` : '暂无记录';
    $('#recentTransfers').replaceChildren(...rows.slice(0, 3).map(t => { const r = el('div', 'recent-row'), a = el('span', 'file-avatar'), i = el('div', 'recent-info'); r.setAttribute('role', 'listitem'); a.append(icon('file')); const n = el('span', 'recent-name', t.name || '未命名文件'); n.title = String(t.name || ''); i.append(n, el('span', 'recent-meta', `${size(t.size || 0)} · ${statusLabel(t)}`)); r.append(a, i); return r; }));
    $('#recentEmpty').hidden = rows.length > 0;
    if (focused && focusedList) [...$('#' + focusedList).querySelectorAll('[data-focus]')].find(b => b.dataset.focus === focused)?.focus({ preventScroll: true });
  }
  async function loadTransfers() {
    if (!cfg || !ready()) return null;
    const seq = ++transferSeq, revision = transferRevision; transferBusy = true; controls();
    try {
      const data = await api(auth('/api/transfers'));
      if (!Array.isArray(data.transfers)) throw new Error('传输列表格式不正确');
      if (seq === transferSeq) {
        data.transfers.forEach(t => { if (t?.id != null && (transfers.get(String(t.id))?._revision || 0) <= revision) mergeTransfer(t, false); });
        ['#transferError', '#historyError', '#recentError'].forEach(id => notice(id, '')); renderTransfers();
      }
      return data.transfers;
    } catch (error) {
      if (seq === transferSeq) { ['#transferError', '#historyError', '#recentError'].forEach(id => notice(id, '查询失败，保留上次记录。' + error.message)); toast('无法读取传输记录：' + error.message, true); }
      return null;
    } finally { if (seq === transferSeq) { transferBusy = false; controls(); } }
  }
  function triggerDownload(t) {
    const url = new URL(t.downloadUrl, location.origin);
    if (url.origin !== location.origin || url.username || url.password || url.pathname !== `/api/transfers/${encodeURIComponent(t.id)}/download` || !url.searchParams.get('token')) throw new Error('下载地址无效，请刷新后重试');
    const link = el('a'); link.href = url.href; link.download = String(t.name || 'download'); link.target = 'downloadFrame'; link.rel = 'noopener'; document.body.append(link); link.click(); link.remove();
    toast('已请求下载，请查看系统下载提示。');
  }
  async function downloadTransfer(id) {
    id = String(id); if (downloads.has(id)) return; if (!ready()) return toast('本机服务未连接，暂时无法下载', true);
    downloads.add(id); renderTransfers();
    try { const fresh = await loadTransfers(); if (!fresh) return; const t = fresh.find(t => String(t.id) === id); if (!t || t.status !== 'complete' || !t.downloadUrl) throw new Error('文件暂不可下载，记录已刷新'); triggerDownload(t); }
    catch (error) { toast('下载失败：' + error.message, true); }
    finally { downloads.delete(id); renderTransfers(); }
  }
  async function downloadAttachment(m, b) {
    b.disabled = true;
    try { const fresh = await loadTransfers(); if (!fresh) return; const t = fresh.filter(t => t.status === 'complete' && (m.transferId ? String(t.id) === String(m.transferId) : t.name === m.fileName)).sort((a, b) => Number(b.created || 0) - Number(a.created || 0))[0]; if (!t?.downloadUrl) throw new Error('未找到附件的可下载记录，请查看文件历史'); triggerDownload(t); }
    catch (error) { toast(error.message, true); } finally { b.disabled = false; }
  }

  /* One File at a time, awaited end-to-end. Failed/cancelled jobs retain the original File. */
  function enqueue(files, destination = target, name = targetName) {
    if (!canTarget(destination)) return toast('请先连接接收设备，文件尚未加入队列', true);
    let accepted = 0, rejected = 0;
    for (const file of files) {
      if (file.size > MAX_FILE) { rejected++; continue; }
      jobs.push({ key: 'local-' + (++jobNumber), id: null, name: file.name, file, size: file.size, created: Date.now(), status: 'queued', progress: 0, speed: 0, target: destination, targetName: destination ? name : '全部设备', cancelled: false }); accepted++;
    }
    if (accepted) toast(`已加入 ${accepted} 个文件，将按顺序传输${rejected ? `；${rejected} 个超限文件已跳过` : ''}`);
    else if (rejected) toast('文件超过单个 512 MiB 上限，未加入队列', true);
    renderTransfers(); pumpUploads();
  }
  async function cancelRemote(j) {
    if (!j.id || j.cancelPromise) return j.cancelPromise;
    j.cancelPromise = api(auth(`/api/transfers/${encodeURIComponent(j.id)}/cancel`), post()).then(data => { if (data.transfer) mergeTransfer(data.transfer); }).catch(async error => {
      if (error.status === 409) { const fresh = await loadTransfers(); const delivered = fresh?.find(t => String(t.id) === j.id && t.status === 'complete'); if (delivered) mergeTransfer(delivered); if (transfers.get(j.id)?.status === 'complete') return; }
      j.error = '本机上传已中止；服务器取消确认失败：' + error.message; toast(j.error, true);
    });
    return j.cancelPromise;
  }
  function cancelJob(j) {
    if (!activeStatus(j.status)) return; j.cancelled = true;
    if (j.status === 'queued') { j.status = 'cancelled'; renderTransfers(); pumpUploads(); return; }
    j.status = 'cancelling'; j.xhr?.abort(); cancelRemote(j); renderTransfers();
  }
  function retryJob(j) { if (!j.file) return toast('原始文件已不在内存中，请重新选择', true); if (!canTarget(j.target)) return toast('原接收设备不在线，请稍后重试', true); const file = j.file; j.file = null; enqueue([file], j.target, j.targetName); }
  function uploadStream(j, token) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest(); j.xhr = xhr; let lastTime = performance.now(), lastBytes = 0;
      xhr.open('POST', `/api/transfers/${encodeURIComponent(j.id)}/upload?token=${encodeURIComponent(token)}`); xhr.timeout = 30 * 60 * 1000;
      xhr.upload.onprogress = event => {
        const now = performance.now(), elapsed = (now - lastTime) / 1000;
        if (elapsed >= .25) { j.speed = Math.max(0, (event.loaded - lastBytes) / elapsed); lastTime = now; lastBytes = event.loaded; }
        j.progress = event.lengthComputable ? event.loaded / event.total * 100 : (j.size ? event.loaded / j.size * 100 : 0); scheduleTransfers();
      };
      xhr.upload.onload = () => { if (!j.cancelled) { j.status = 'processing'; j.speed = 0; scheduleTransfers(); } };
      xhr.onload = () => { let data; try { data = JSON.parse(xhr.responseText); } catch (_) { return reject(new Error('上传响应无法识别，请刷新记录确认结果')); } if (xhr.status < 200 || xhr.status >= 300 || !data?.transfer) reject(new Error(data?.error || `上传失败（HTTP ${xhr.status}）`)); else resolve(data.transfer); };
      xhr.onerror = () => reject(new Error('上传连接中断，可保留原文件重试'));
      xhr.ontimeout = () => reject(new Error('上传超时，请检查网络后重试'));
      xhr.onabort = () => reject(new DOMException('上传已取消', 'AbortError'));
      if (j.cancelled) return reject(new DOMException('上传已取消', 'AbortError')); xhr.send(j.file);
    });
  }
  async function uploadJob(j) {
    j.status = 'offering'; renderTransfers();
    try {
      const offer = await api(auth('/api/transfers/offer'), post({ name: j.name, size: j.size, mime: j.file.type || 'application/octet-stream', direction: 'pc_to_phone', targetDeviceId: j.target }));
      // Let the small offer return its ID before cancelling; the actual file HTTP request is aborted immediately.
      if (!offer.transfer?.id || !offer.uploadToken) throw new Error('无法创建传输任务');
      j.id = String(offer.transfer.id); mergeTransfer(offer.transfer, false);
      if (j.cancelled) throw new DOMException('上传已取消', 'AbortError');
      if (!canTarget(j.target)) { await cancelRemote(j); throw new Error('接收设备或实时连接已断开，原文件可重试'); }
      j.status = 'uploading'; renderTransfers(); const completed = await uploadStream(j, offer.uploadToken); mergeTransfer(completed, false);
      if (completed.status !== 'complete') throw new Error(completed.error || '服务尚未确认文件就绪，请刷新记录');
      j.status = 'complete'; j.progress = 100; j.file = null; j.error = ''; j.savedAs = completed.savedAs;
    } catch (error) {
      const confirmed = transfers.get(j.id);
      if (confirmed?.status === 'complete') { j.status = 'complete'; j.progress = 100; j.file = null; j.error = ''; if (j.cancelled) toast('文件已经中转就绪，取消未生效'); }
      else if (j.cancelled) { await cancelRemote(j); if (transfers.get(j.id)?.status === 'complete') { j.status = 'complete'; j.progress = 100; j.file = null; j.error = ''; toast('文件已经中转就绪，取消未生效'); } else j.status = 'cancelled'; }
      else { j.status = 'failed'; j.error = error.message || '文件发送失败'; toast('文件发送失败：' + j.error, true); }
    } finally { j.xhr = null; j.speed = 0; renderTransfers(); }
  }
  async function pumpUploads() {
    if (queueRunning) return; queueRunning = true;
    try { for (;;) { const job = jobs.find(j => j.status === 'queued'); if (!job || !canTarget(job.target)) break; await uploadJob(job); } }
    finally { queueRunning = false; }
  }

  /* Update preferences never install anything; only a validated GitHub URL can open. */
  function safeReleaseUrl() {
    if (!update || update.status !== 'available') return null;
    const repository = String(update.repository || '').toLowerCase(); if (!/^[a-z0-9-]+\/[a-z0-9_.-]+$/.test(repository)) return null;
    const configured = String(cfg?.updateRepository || repository).toLowerCase(); if (configured !== repository) return null;
    for (const value of [update.releaseUrl, update.downloadUrl]) {
      try { const url = new URL(value); const path = url.pathname.toLowerCase(); if (url.protocol === 'https:' && url.hostname === 'github.com' && !url.username && !url.password && (!url.port || url.port === '443') && (path === '/' + repository + '/releases' || path.startsWith('/' + repository + '/releases/'))) return url.href; } catch (_) { /* Invalid or non-GitHub URLs are never opened. */ }
    }
    return null;
  }
  function fillSettings(source) {
    if (settingsDirty || settingsSaving) return;
    if (typeof source?.autoCheckUpdates === 'boolean') $('#autoCheckUpdates').checked = source.autoCheckUpdates;
    else if (typeof source?.enabled === 'boolean') $('#autoCheckUpdates').checked = source.enabled;
    if (typeof source?.ballEnabled === 'boolean' && !ballBusy) $('#ballEnabled').checked = source.ballEnabled;
    if (typeof source?.closeToTray === 'boolean') $('#closeToTray').checked = source.closeToTray;
    const repository = source?.updateRepository ?? source?.repository; if (typeof repository === 'string') $('#updateRepository').value = repository;
  }
  function applyUpdate(data) {
    if (!data || !['idle', 'checking', 'up_to_date', 'available', 'error'].includes(data.status)) throw new Error('更新状态格式不正确');
    update = data; updateRevision++; fillSettings(data);
    $('#currentVersion').textContent = data.currentVersion || cfg?.version || '—'; $('#latestVersion').textContent = data.latestVersion || '尚未获取';
    const labels = { idle: '尚未检查', checking: '正在检查', up_to_date: '已是最新', available: '发现新版本', error: '检查失败' };
    $('#updateStatus').textContent = labels[data.status]; $('#updateStatus').className = 'badge ' + (data.status === 'error' ? 'error' : ['available', 'up_to_date'].includes(data.status) ? 'success' : 'neutral');
    $('#updateMessage').textContent = data.message || ({ idle: data.enabled ? '可手动检查 GitHub 发布信息。' : '自动检查已关闭，仍可手动检查更新。', checking: '正在向 GitHub 查询，消息与文件传输不受影响。', up_to_date: '当前版本与可用发布一致。', available: '新版本可供下载，请自行查看发布说明并决定是否更新。', error: '未能获取版本信息，请检查仓库设置与网络。' })[data.status];
    $('#updateMessage').classList.toggle('error', data.status === 'error'); $('#updateCheckedAt').textContent = data.checkedAt ? '上次检查 ' + time(data.checkedAt) : '尚未检查';
    $('#btnDownloadUpdate').hidden = !safeReleaseUrl(); $('#releaseDetails').hidden = !data.notes && !data.sha256 && !data.size;
    $('#releaseNotes').textContent = data.notes || '此版本未提供说明。'; $('#updateSha').textContent = data.sha256 || '未提供'; $('#updateSize').textContent = data.size ? size(data.size) : '未提供';
    clearTimeout(updatePollTimer); if (data.status === 'checking') updatePollTimer = setTimeout(() => { if (ready()) loadUpdates(); }, 2500); controls();
  }
  async function loadUpdates(check = false) {
    if (!ready()) return; const seq = ++updateSeq, revision = updateRevision; updateBusy = true; controls();
    if (check) { $('#updateStatus').textContent = '正在检查'; $('#updateMessage').textContent = '正在检查新版本，不影响消息与文件传输。'; }
    try {
      const data = await api(check ? '/api/updates/check' : '/api/updates', check ? post() : {}, check ? 45000 : 15000);
      if (seq === updateSeq && (revision === updateRevision || Number(data.checkedAt || 0) > Number(update?.checkedAt || 0) || (['available', 'up_to_date', 'error'].includes(data.status) && Number(data.checkedAt || 0) >= Number(update?.checkedAt || 0)))) applyUpdate(data);
    } catch (error) {
      if (seq === updateSeq) { $('#updateStatus').textContent = '获取失败'; $('#updateStatus').className = 'badge error'; $('#updateMessage').textContent = error.message; $('#updateMessage').classList.add('error'); toast('更新检查失败：' + error.message, true); }
    } finally { if (seq === updateSeq) { updateBusy = false; controls(); } }
  }
  async function saveSettings(event) {
    event.preventDefault(); if (!ready() || settingsSaving) return; const repository = $('#updateRepository').value.trim();
    if (!repository || !/^[A-Za-z0-9][A-Za-z0-9-]{0,38}\/[A-Za-z0-9_.-]{1,100}$/.test(repository) || ['.', '..'].includes(repository.split('/')[1])) { $('#updateRepository').focus(); return toast('更新来源应为 owner/repo，不要留空或填写完整网址', true); }
    settingsSaving = true; controls(); $('#settingsHint').textContent = '正在保存…';
    try {
      const data = await api('/api/settings', post({ autoCheckUpdates: $('#autoCheckUpdates').checked, updateRepository: repository }));
      const settings = data.settings || data; if (typeof settings.autoCheckUpdates !== 'boolean' || typeof settings.updateRepository !== 'string') throw new Error('设置保存响应不完整');
      cfg = { ...cfg, ...settings }; settingsDirty = false; settingsSaving = false; fillSettings(settings); $('#btnDownloadUpdate').hidden = !safeReleaseUrl();
      $('#settingsHint').textContent = '设置已保存到本机。'; toast('更新偏好已保存'); loadUpdates();
    } catch (error) { $('#settingsHint').textContent = '未能保存：' + error.message; toast('设置保存失败：' + error.message, true); }
    finally { settingsSaving = false; controls(); }
  }
  async function nativeCall(name, fallback, ...args) {
    const bridge = window.pywebview?.api; if (typeof bridge?.[name] !== 'function') return toast(fallback, true);
    try { const result = await bridge[name](...args); if (result?.error || result?.ok === false || (result === false && name !== 'toggle_panel')) throw new Error(result?.error || '桌面操作未完成'); }
    catch (error) { toast(error.message || '桌面操作失败，请重试', true); }
  }
  function openUpdate() {
    const url = safeReleaseUrl(); if (!url) return toast('当前发布地址不符合 GitHub 安全规则', true);
    if (!confirm(`将打开 ${update.repository} 的 GitHub 发布页面，由你决定是否下载。不会自动安装。是否继续？`)) return;
    if (window.pywebview?.api?.open_external_url) nativeCall('open_external_url', '请使用桌面客户端打开 GitHub', url);
    else { const link = el('a'); link.href = url; link.target = '_blank'; link.rel = 'noopener noreferrer'; document.body.append(link); link.click(); link.remove(); }
  }

  /* WS reconnects only report confirmed state. The original event contract is preserved. */
  function handleEvent(data) {
    if (!data || typeof data.type !== 'string') return;
    if (data.type === 'init') {
      cfg = data.config || {}; initialized = true; channel = 'online'; pairRetryAt = 0; clearTimeout(handshakeTimer); reconnectDelay = 1000; reconnectAt = 0;
      messages = Array.isArray(data.messages) ? data.messages.filter(m => m && typeof m === 'object').slice(-500) : []; messageRevision++; roster(data.deviceInfos); fillSettings(cfg); syncMessages();
      if (firstInit) { if (!userNavigated) navigate(MINI || devices.length ? 'messages' : 'connections', false); firstInit = false; }
      if (data.update) applyUpdate(data.update); loadPair(); loadTransfers(); loadUpdates();
    } else if (data.type === 'status') roster(data.deviceInfos);
    else if (data.type === 'config') { cfg = data.config || cfg; manualInfo(); fillSettings(cfg); controls(); }
    else if (data.type === 'msg') receiveMessage(data.message);
    else if (data.type === 'cleared') { messages = []; unread = 0; messageRevision++; syncMessages(); updateUnread(); }
    else if (data.type.startsWith('file_')) mergeTransfer(data.transfer);
    else if (data.type === 'pairing_used') { pairing = null; pairRetryAt = 0; loadPair(); }
    else if (data.type === 'update') applyUpdate(data.update);
    else if (data.type === 'error') { const text = String(data.error || '服务器拒绝了此次操作'); if (pendingMessage) settleMessage(false, text + '，草稿已保留'); else toast(text, true); }
  }
  function connect() {
    if (!LOCAL) { channel = 'offline'; renderConnection(); return; }
    clearTimeout(reconnectTimer); clearTimeout(handshakeTimer); initialized = false; channel = 'connecting'; reconnectAt = 0;
    settleMessage(false, '正在重新连接，草稿已保留。重发前请确认对方是否收到。');
    const previous = socket; socket = null; previous?.close(); pairing = null; pairSeq++; pairController?.abort(); pairBusy = false; renderConnection();
    let ws;
    try { ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`); socket = ws; }
    catch (_) { return scheduleReconnect(); }
    handshakeTimer = setTimeout(() => { if (socket === ws && !initialized) ws.close(); }, 12000);
    ws.onmessage = event => { if (socket !== ws) return; let data; try { data = JSON.parse(event.data); } catch (_) { return; } try { handleEvent(data); } catch (error) { toast('服务事件无法处理：' + error.message, true); } };
    ws.onerror = () => { if (socket === ws) ws.close(); };
    ws.onclose = () => { if (socket !== ws) return; initialized = false; clearTimeout(handshakeTimer); settleMessage(false, '连接中断，草稿已保留。重发前请确认对方是否收到。'); scheduleReconnect(); };
  }
  function scheduleReconnect() {
    initialized = false; channel = 'reconnecting'; reconnectAt = Date.now() + reconnectDelay;
    clearTimeout(reconnectTimer); reconnectTimer = setTimeout(connect, reconnectDelay); reconnectDelay = Math.min(reconnectDelay * 2, 15000); renderConnection(); scheduleTransfers();
  }

  /* Bindings */
  $$('.nav-item').forEach(b => { b.setAttribute('aria-label', views[b.dataset.view][0]); b.title = views[b.dataset.view][0]; b.onclick = () => navigate(b.dataset.view); });
  $$('.filter').forEach(b => { b.onclick = () => { filter = b.dataset.filter; $$('.filter').forEach(x => { x.classList.toggle('active', x === b); x.setAttribute('aria-pressed', String(x === b)); }); renderMessages(); }; });
  $('#btnQuickPair').onclick = () => { navigate('connections'); if (ready() && !pairing && !pairBusy) loadPair(); };
  $('#btnAllHistory').onclick = () => navigate('history'); $('#btnReconnect').onclick = connect;
  $('#recipientSelect').onchange = $('#recipientComposer').onchange = event => selectRecipient(event.target.value);
  $('#messageSearch').oninput = renderMessages; $('#messageComposer').onsubmit = sendMessage; $('#messageInput').oninput = controls;
  $('#messageInput').addEventListener('compositionstart', () => { composing = true; controls(); }); $('#messageInput').addEventListener('compositionend', () => { composing = false; controls(); });
  $('#messageInput').onkeydown = event => { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && !composing && event.keyCode !== 229) { event.preventDefault(); sendMessage(); } };
  $('#btnClear').onclick = async () => {
    if (!ready() || clearBusy || !confirm('清空全部消息记录？此操作不会删除文件传输记录。')) return;
    clearBusy = true; controls(); const revision = messageRevision;
    try { await api('/api/clear', post()); if (messageRevision === revision) { messages = []; unread = 0; messageRevision++; renderMessages(); updateUnread(); } toast('消息记录已清空'); }
    catch (error) { toast('清空失败，原记录已保留：' + error.message, true); } finally { clearBusy = false; controls(); }
  };
  $('#btnRefreshPair').onclick = () => loadPair(true); $('#hostSelect').onchange = event => loadPair(false, event.target.value);
  $('#btnCopyPair').onclick = event => { if (pairing && pairing.expiresAt > Date.now()) copy(pairing.uri, event.currentTarget, '已复制一次性连接码，请在手机端粘贴'); };
  $('#btnCopyAddr').onclick = event => copy($('#detailAddr').textContent, event.currentTarget, '已复制手机端连接地址'); $('#btnCopyToken').onclick = event => copy(cfg?.token, event.currentTarget, '已复制手动配对码');
  $('#qrImage').onerror = () => { pairing = null; pairRetryAt = Date.now() + 15000; tickPair(); notice('#pairError', '二维码图像无法显示，请刷新或使用手动配对。'); };
  $('#btnPickFile').onclick = () => { if (canTarget(target)) $('#fileInput').click(); };
  $('#fileInput').onchange = event => { enqueue([...event.target.files]); event.target.value = ''; };
  const zone = $('#dropZone'); let dragDepth = 0;
  ['dragenter', 'dragover'].forEach(type => zone.addEventListener(type, event => { event.preventDefault(); if (type === 'dragenter') dragDepth++; if (canTarget(target)) zone.classList.add('drag'); event.dataTransfer.dropEffect = canTarget(target) ? 'copy' : 'none'; }));
  zone.addEventListener('dragleave', event => { event.preventDefault(); if (--dragDepth <= 0) zone.classList.remove('drag'); });
  zone.addEventListener('drop', event => { event.preventDefault(); dragDepth = 0; zone.classList.remove('drag'); enqueue([...event.dataTransfer.files]); });
  zone.addEventListener('keydown', event => { if (event.target === zone && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); if (canTarget(target)) $('#fileInput').click(); else toast('请先连接手机并选择在线接收对象', true); } });
  ['dragover', 'drop'].forEach(type => window.addEventListener(type, event => { if ([...(event.dataTransfer?.types || [])].includes('Files')) event.preventDefault(); }));
  $('#btnRefreshTransfers').onclick = $('#btnRefreshHistory').onclick = loadTransfers; $('#historySearch').oninput = $('#historyFilter').onchange = renderTransfers;
  $('#btnFolder').onclick = () => nativeCall('open_received_folder', '浏览器模式无法打开本机文件夹，请在桌面客户端使用“接收文件夹”。');
  $('#btnCheckUpdates').onclick = () => loadUpdates(true); $('#btnDownloadUpdate').onclick = openUpdate; $('#updateSettings').onsubmit = saveSettings;
  [$('#autoCheckUpdates'), $('#updateRepository')].forEach(input => input.addEventListener('input', () => { settingsDirty = true; $('#settingsHint').textContent = '有尚未保存的修改。'; controls(); }));
  $('#desktopTitlebar').hidden = !APP; $('#btnCollapse').onclick = () => hideToTray();
  $('#btnQuit').onclick = () => requestClose();
  $('#btnQuitApp').onclick = () => { if (confirm('退出互传助手？后台服务将停止，手机将断开连接；未发送的草稿会丢失。')) nativeCall('quit_app_confirm', '浏览器模式请直接关闭当前标签页。'); };
  $('#ballEnabled').onchange = toggleBall;
  $('#closeToTray').onchange = toggleCloseToTray;
  if (MINI) applyMiniMode();
  window.addEventListener('online', () => { if (!ready()) connect(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) { tickPair(); if (ready() && update?.status === 'checking') loadUpdates(); } });
  window.addEventListener('beforeunload', event => { if (jobs.some(j => activeStatus(j.status)) || pendingMessage) { event.preventDefault(); event.returnValue = ''; } });
  setInterval(() => { tickPair(); if (channel === 'reconnecting') $('#connectionNoticeText').textContent = `实时连接已中断，${Math.max(1, Math.ceil((reconnectAt - Date.now()) / 1000))} 秒后重试。草稿与队列已保留。`; }, 1000);
  renderMessages(); renderTransfers(); connect();
})();
