// StarBridge web UI. No frameworks, no network dependencies: works offline in the field.
(() => {
  'use strict';

  const HOLD_INTERVAL_MS = 250; // heartbeat while a direction is held (server deadline: 750 ms)
  const $ = (s, root = document) => root.querySelector(s);
  const $$ = (s, root = document) => Array.from(root.querySelectorAll(s));

  // ---------------------------------------------------------------- state
  const S = {
    ws: null, open: false, clientId: null,
    info: {}, status: {}, alignment: { points: [], suggestions: [] }, sky: null,
    tab: 'sky', results: [], category: 'best', query: '',
    selectedStar: null, // alignment target {id,...}
    sheetObj: null, guide: null, backlashOriginal: null,
  };

  // Per-device preferences (localStorage may be unavailable: private mode, previews).
  const prefs = {
    get(k, d) { try { const v = localStorage.getItem('sb.' + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem('sb.' + k, JSON.stringify(v)); } catch { /* ignore */ } },
  };

  // ---------------------------------------------------------------- access key
  // The QR link carries ?k=KEY. It is kept in the URL (so a home-screen icon keeps working)
  // and remembered on this device for links opened without it.
  function accessKey() {
    const fromUrl = new URLSearchParams(location.search).get('k');
    if (fromUrl) { prefs.set('key', fromUrl); return fromUrl; }
    return prefs.get('key', null);
  }

  // Typed by hand: "J576-XMKF-MVU2", "j576 xmkf mvu2" and "j576xmkfmvu2" are the same code.
  const normalizeKey = (s) => String(s || '').toLowerCase().replace(/[^a-z0-9]/g, '');

  $('#codeForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const code = normalizeKey($('#codeInput').value);
    if (code.length < 8) { $('#codeError').classList.remove('hidden'); return; }
    $('#codeError').classList.add('hidden');
    prefs.set('key', code);
    // Drop a stale ?k= from the address so the typed code wins.
    if (new URLSearchParams(location.search).get('k')) history.replaceState(null, '', location.pathname);
    connect();
  });
  $('#codeInput').addEventListener('input', () => $('#codeError').classList.add('hidden'));

  function showLocked(rotated) {
    $('#locked').classList.remove('hidden');
    $('#lockedWhy').textContent = rotated
      ? 'La clave de acceso ha cambiado. Escanea el nuevo QR de la pantalla del Android o del PC.'
      : 'Para controlar el telescopio, escanea el QR o escribe el código que aparece en el Android o en el PC que tiene el telescopio.';
  }

  // ---------------------------------------------------------------- websocket
  function connect() {
    const key = accessKey();
    if (!key) { showLocked(false); return; }
    let ws;
    try { ws = new WebSocket(`ws://${location.host}/ws?k=${encodeURIComponent(key)}`); } catch { setTimeout(connect, 2000); return; }
    S.ws = ws;
    ws.onopen = () => { S.open = true; $('#locked').classList.add('hidden'); renderTop(); request(); };
    ws.onclose = (e) => {
      S.open = false; releaseAll(false); renderTop();
      if (e.code === 4401) {
        // Wrong or replaced key: forget it and ask for the QR (no retry loop).
        prefs.set('key', null);
        showLocked(S.clientId !== null);
        return;
      }
      setTimeout(connect, 1500);
    };
    ws.onerror = () => ws.close();
    ws.onmessage = (e) => {
      let m;
      try { m = JSON.parse(e.data); } catch { return; }
      const h = handlers[m.type];
      if (h) h(m);
      for (const fn of extra[m.type] || []) { try { fn(m); } catch (err) { console.error(err); } }
    };
  }

  function send(obj) {
    if (S.ws && S.ws.readyState === WebSocket.OPEN) { S.ws.send(JSON.stringify(obj)); return true; }
    return false;
  }

  /** Data the current tab needs. */
  function request() {
    if (S.tab === 'img' || S.info.imaging) send({ type: 'imgState' });
    if (S.tab === 'sky') send({ type: 'sky' });
    if (S.tab === 'search') search();
    if (S.tab === 'align') send({ type: 'alignState' });
    if (S.tab === 'more' && S.info.connected) send({ type: 'getBacklash' });
    if (S.tab === 'more') renderShare();
  }

  const extra = {}; // handlers added by other modules (imaging.js)
  const handlers = {
    // Other StarBridge brains on the WiFi: if the telescope is on another one (the PC), say so.
    peers(m) { S.peers = m.items || []; renderPeerBanner(); },
    info(m) {
      if (m.clientId) S.clientId = m.clientId;
      S.info = Object.assign({}, S.info, m);
      renderTop(); renderMore(); renderAlign(); applyUse();
    },
    status(m) {
      S.status = m;
      renderTop(); renderPointing(); renderPadState(); renderAlignChecks();
      if (S.tab === 'sky') drawSky();
      if (S.tab === 'more') renderSensorLive();
    },
    alignment(m) { S.alignment = m; renderAlign(); },
    searchResult(m) { S.results = m.items; renderResults(); },
    sky(m) { S.sky = m; drawSky(); },
    object(m) {
      // Only if the user is still waiting for this object's sheet.
      if (S.sheetObj && S.sheetObj.id === m.id) { S.sheetObj = m; renderSheet(); }
    },
    guide(m) { if (S.selectedStar && m.id === S.selectedStar.id) { S.guide = m; renderGuide(); } },
    backlash(m) {
      for (const axis of ['azm', 'alt']) {
        const box = $(`.bl[data-axis="${axis}"]`);
        $('.pos', box).value = m[axis].pos;
        $('.neg', box).value = m[axis].neg;
      }
      if (!S.backlashOriginal) {
        S.backlashOriginal = m;
        $('#blOriginal').textContent = `Valores originales: Az +${m.azm.pos}/−${m.azm.neg} · Alt +${m.alt.pos}/−${m.alt.neg}`;
      }
    },
    calibration(m) {
      $('#calResult').textContent =
        `${m.axis === 'azm' ? 'Acimut' : 'Altitud'}: holgura +${m.slackPosDeg.toFixed(2)}° / −${m.slackNegDeg.toFixed(2)}° ` +
        `(anti-backlash sugerido +${m.suggestedPos} / −${m.suggestedNeg})`;
    },
    visualCal(m) { S.visual = m; renderVisual(); },
    slackMeter(m) { S.meterData = m; renderMeterLive(); },
    diag(m) { if (S.diagOpen) renderDiag(m.text); },
    error(m) { toast(m.message, true); },
    event(m) { toast(m.message); },
  };

  // ---------------------------------------------------------------- formatting
  const pad2 = (n) => String(n).padStart(2, '0');
  function fmtRA(h) {
    if (h === undefined || h === null) return '—';
    let s = Math.round(h * 3600); s = ((s % 86400) + 86400) % 86400;
    return `${Math.floor(s / 3600)}h ${pad2(Math.floor(s / 60) % 60)}m ${pad2(s % 60)}s`;
  }
  function fmtDec(d) {
    if (d === undefined || d === null) return '—';
    const sign = d < 0 ? '−' : '+'; let a = Math.round(Math.abs(d) * 60);
    return `${sign}${Math.floor(a / 60)}° ${pad2(a % 60)}′`;
  }
  const fmtDeg = (d) => (d === undefined || d === null ? '—' : `${d.toFixed(1)}°`);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  // Plain text symbols (never colour emoji): night mode must stay red.
  const CAT_ICON = { planet: '◉', moon: '☾\uFE0E', star: '✦\uFE0E', galaxy: '⬬', nebula: '▣', cluster: '⁂', other: '◌' };
  const titleOf = (o) => (o.name ? `${o.id} · ${o.name}` : o.id);

  let toastTimer = null;
  function toast(text, error = false) {
    const t = $('#toast');
    t.textContent = text;
    t.classList.toggle('error', error);
    t.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.add('hidden'), error ? 5000 : 3500);
  }

  // ---------------------------------------------------------------- top bar
  /** The brain this page talks to: the Android app or the PC program. */
  const brainName = () => (S.info.brainKind === 'pc' ? 'el PC' : 'el Android');

  function renderPeerBanner() {
    const here = !!(S.status.connected || S.info.connected);
    const other = S.open && !here ? (S.peers || []).find((p) => p.telescope) : null;
    const b = $('#peerBanner');
    b.classList.toggle('hidden', !other);
    if (!other) return;
    b.textContent = `El telescopio está conectado a ${other.name}. Toca aquí para manejarlo (te pedirá su código).`;
    b.href = `${other.url}/`;
  }

  function renderTop() {
    renderPeerBanner();
    const st = S.status;
    const dot = $('#connDot');
    dot.classList.toggle('on', S.open && !!st.connected);
    dot.classList.toggle('half', S.open && !st.connected);
    let sub;
    if (!S.open) sub = `Sin conexión con ${brainName()}…`;
    else if (!st.connected && !S.info.connected) sub = `${S.info.brainKind === 'pc' ? 'PC' : 'Android'} listo · telescopio no conectado`;
    else {
      const mode = (st.mode || S.info.mode) === 'hc' ? 'Alineación del mando' : 'Modo StarBridge';
      const al = S.alignment.aligned || (st.mode === 'hc' && S.info.hcAligned) ? '' : ' · sin alinear';
      sub = `${mode}${al}`;
    }
    $('#subtitle').textContent = sub;
    const pill = $('#trackPill');
    pill.classList.toggle('hidden', !st.tracking && !st.goto);
    pill.textContent = st.goto ? 'GoTo…' : 'Siguiendo';
    if (st.time) {
      const d = new Date(st.time);
      $('#clock').textContent = `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
    }
    const mode = st.mode || S.info.mode;
    const unaligned = S.open && !!st.connected && (mode === 'hc' ? !S.info.hcAligned : !S.alignment.aligned);
    $$('.align-cta').forEach((b) => b.classList.toggle('hidden', !unaligned));
    const ar = $('#alignRowSub');
    if (ar) ar.textContent = !st.connected ? 'Telescopio no conectado' : unaligned ? 'Sin alinear' : (mode === 'hc' ? 'Alineado con el mando' : `Alineado con ${S.alignment.stars || 0} estrella${S.alignment.stars === 1 ? '' : 's'}`);
    document.body.classList.toggle('no-mount', !st.connected && !S.info.connected);
    const banner = $('#banner');
    if (S.alignment.needsCheck) {
      banner.textContent = 'El telescopio se ha reconectado: comprueba la alineación con una estrella antes de confiar en el GoTo.';
      banner.classList.remove('hidden');
    } else banner.classList.add('hidden');
  }

  // ---------------------------------------------------------------- pointing cards
  function pointingHtml() {
    const st = S.status;
    if (!S.open) return '<div class="big">Sin conexión</div><div class="state-line">Conecta el iPhone a la misma red que el Android.</div>';
    if (!st.connected) return '<div class="big">Telescopio no conectado</div><div class="state-line">Enchufa el cable al ' + (S.info.brainKind === 'pc' ? 'PC' : 'Android') + ' y enciende el telescopio.</div>';
    const known = st.alt !== undefined;
    const title = st.tracking && st.trackingLabel ? esc(st.trackingLabel) : known ? `Apuntando al ${esc(st.dir)}` : 'Posición desconocida';
    const line = st.goto ? 'Moviéndose al objetivo…' : st.tracking ? 'Siguiendo el objeto' : known ? 'Parado' : 'Alinea el telescopio para saber dónde apunta';
    return `<div class="big">${title}</div>
      <div class="state-line">${line}</div>
      <div class="coords mono">
        <div><span>Ascensión recta</span>${fmtRA(st.ra)}</div>
        <div><span>Declinación</span>${fmtDec(st.dec)}</div>
        <div><span>Acimut</span>${fmtDeg(st.az)}</div>
        <div><span>Altura</span>${fmtDeg(st.alt)}</div>
      </div>`;
  }
  function renderPointing() {
    const html = pointingHtml();
    $('#skyCard').innerHTML = html;
    $('#moveCard').innerHTML = html;
    const st = S.status;
    const toggle = $('#trackToggle');
    if (document.activeElement !== toggle) toggle.checked = !!st.tracking;
    $('#trackSub').textContent = st.tracking ? `Siguiendo: ${st.trackingLabel || 'posición actual'}` : 'Compensa la rotación del cielo';
    const other = st.controller && S.clientId && st.controller !== S.clientId;
    $('#controlBanner').classList.toggle('hidden', !other);
  }

  // ---------------------------------------------------------------- hold-to-move pads
  const held = new Map(); // axis -> {timer, el}

  function invert(axis, dir) {
    const inv = axis === 'azm' ? prefs.get('invertAz', false) : prefs.get('invertAlt', false);
    return inv ? (dir === 'pos' ? 'neg' : 'pos') : dir;
  }

  function press(el, rate) {
    const axis = el.dataset.axis;
    const dir = invert(axis, el.dataset.dir);
    release(axis, true);
    if (!send({ type: 'slew', axis, dir, rate })) { toast(`Sin conexión con ${brainName()}`, true); return; }
    el.classList.add('pressed');
    const timer = setInterval(() => send({ type: 'hold', axis }), HOLD_INTERVAL_MS);
    held.set(axis, { timer, el });
  }

  function release(axis, sendStop) {
    const h = held.get(axis);
    if (!h) return;
    clearInterval(h.timer);
    h.el.classList.remove('pressed');
    held.delete(axis);
    if (sendStop) send({ type: 'stop', axis });
  }

  function releaseAll(sendStop = true) { for (const axis of Array.from(held.keys())) release(axis, sendStop); }

  /** Hold-to-move button: data-axis / data-dir say what it moves (read at press time). */
  function bindHold(el, getRate) {
    el.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      if (el.disabled) return;
      try { el.setPointerCapture(e.pointerId); } catch { /* ignore */ }
      press(el, getRate());
    });
    for (const ev of ['pointerup', 'pointercancel', 'lostpointercapture']) {
      el.addEventListener(ev, () => { if (held.get(el.dataset.axis)?.el === el) release(el.dataset.axis, true); });
    }
    el.addEventListener('contextmenu', (e) => e.preventDefault());
  }

  const pads = [];
  function makePad(host, getRate) {
    host.innerHTML = `<div class="pad">
      <button class="dir up" data-axis="alt" data-dir="pos" aria-label="Arriba">▲</button>
      <button class="dir left" data-axis="azm" data-dir="neg" aria-label="Izquierda">◀</button>
      <div class="center"></div>
      <button class="dir right" data-axis="azm" data-dir="pos" aria-label="Derecha">▶</button>
      <button class="dir down" data-axis="alt" data-dir="neg" aria-label="Abajo">▼</button>
    </div>`;
    for (const el of $$('.dir', host)) bindHold(el, getRate);
    pads.push($('.center', host));
  }

  function renderPadState() {
    const axes = S.status.axes || {};
    const vals = Object.values(axes);
    let text = '', cls = '';
    if (vals.some((a) => a.motion === 'taking_up_slack')) { text = 'tomando\nholgura…'; cls = 'slack'; }
    else if (vals.some((a) => a.moving)) {
      const dt = Object.entries(axes).filter(([, a]) => a.deadTimeMs !== undefined).map(([k, a]) => `${k === 'azm' ? 'Az' : 'Alt'} ${a.deadTimeMs} ms`).join('\n');
      text = 'moviendo' + (dt ? `\n${dt}` : ''); cls = 'moving';
    }
    for (const c of pads) { c.textContent = text; c.className = `center ${cls}`; }
  }

  // A held button removed from the page (its sheet re-rendered) never gets its pointerup:
  // any finger lifted anywhere releases it.
  for (const ev of ['pointerup', 'pointercancel']) {
    document.addEventListener(ev, () => {
      for (const [axis, h] of Array.from(held.entries())) if (!h.el.isConnected) release(axis, true);
    });
  }

  // Phone locked / Safari in background / tab closed: stop immediately.
  const panic = () => { if (held.size) releaseAll(true); };
  document.addEventListener('visibilitychange', () => { if (document.hidden) panic(); else request(); });
  window.addEventListener('pagehide', panic);
  window.addEventListener('blur', panic);
  document.addEventListener('touchcancel', panic);

  function setupSeg(seg, key, def, onChange) {
    const val = prefs.get(key, def);
    for (const b of $$('button', seg)) {
      b.classList.toggle('active', b.dataset.rate === String(val));
      b.addEventListener('click', () => {
        $$('button', seg).forEach((x) => x.classList.toggle('active', x === b));
        prefs.set(key, Number(b.dataset.rate));
        if (onChange) onChange(Number(b.dataset.rate));
      });
    }
  }

  // ---------------------------------------------------------------- STOP
  // pointerdown: fires on touch-down, even if the finger slides off the button.
  $('#stopBtn').addEventListener('pointerdown', (e) => {
    e.preventDefault();
    releaseAll(false);
    const b = $('#stopBtn');
    b.classList.remove('flash'); void b.offsetWidth; b.classList.add('flash');
    if (!send({ type: 'stopAll' })) toast(`Sin conexión con ${brainName()}`, true);
  });
  $('#takeControl').addEventListener('click', () => send({ type: 'takeControl' }));
  $('#trackToggle').addEventListener('change', (e) => send({ type: 'track', on: e.target.checked }));

  // ---------------------------------------------------------------- tabs
  // Tabs shown depend on what is connected (or on the "Uso" chosen in Más).
  const TABS = { observe: ['sky', 'move', 'search', 'more'], photo: ['img', 'more'], both: ['sky', 'move', 'img', 'search', 'more'] };
  function useMode() {
    const u = prefs.get('use', 'auto');
    if (u !== 'auto') return u;
    // a camera here (the Android's) or the iPhone app's, shared through the hub
    const cam = S.info.imaging || !!(S.iphone && S.iphone.online);
    if (cam && S.info.connected) return 'both';
    if (cam && !S.info.connected && S.open) return 'photo';
    return 'observe';
  }
  function applyUse() {
    const use = useMode();
    const tabs = TABS[use];
    $$('.tabbar button').forEach((b) => b.classList.toggle('hidden', !tabs.includes(b.dataset.tab)));
    $$('#useSeg button').forEach((b) => b.classList.toggle('active', b.dataset.use === prefs.get('use', 'auto')));
    const hint = { observe: 'Control del telescopio: mapa, mover y buscar.', photo: 'Solo cámara: apilado con cualquier montura (o sin ella).', both: 'Control del telescopio y apilado a la vez.' }[use];
    const h = $('#useHint');
    if (h) h.textContent = (prefs.get('use', 'auto') === 'auto' ? 'Automático según lo conectado · ahora: ' : '') + hint;
    if (S.tab !== 'align' && !tabs.includes(S.tab)) showTab(tabs[0]);
  }
  $$('#useSeg button').forEach((b) => b.addEventListener('click', () => { prefs.set('use', b.dataset.use); applyUse(); }));
  // Language (i18n.js): the page reloads in the chosen one.
  $$('#langSeg button').forEach((b) => {
    b.classList.toggle('active', b.dataset.l === (window.I18N ? I18N.lang : 'es'));
    b.addEventListener('click', () => window.I18N && I18N.set(b.dataset.l));
  });

  let tabBeforeAlign = 'sky';
  $$('[data-goalign]').forEach((b) => b.addEventListener('click', () => { if (S.tab !== 'align') tabBeforeAlign = S.tab; showTab('align'); }));
  $('#alignBack').addEventListener('click', () => showTab(tabBeforeAlign || 'sky'));

  function showTab(name) {
    releaseAll(true);
    S.tab = name;
    if (name !== 'align') prefs.set('tab', name);
    document.body.classList.toggle('tab-img', name === 'img');
    $$('.tabbar button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name || (name === 'align' && b.dataset.tab === tabBeforeAlign)));
    $$('.tab').forEach((t) => t.classList.toggle('active', t.id === `tab-${name}`));
    window.scrollTo(0, 0);
    if (name === 'sky') requestAnimationFrame(drawSky);
    request();
    for (const fn of extra.tab || []) fn(name);
  }
  $$('.tabbar button').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));

  // ---------------------------------------------------------------- sky map
  const canvas = $('#sky');
  const ctx = canvas.getContext('2d');
  // Like a planisphere held in front of you: the direction you face is at the bottom.
  const ROT = [{ face: 180, label: 'Mirando al S' }, { face: 270, label: 'Mirando al O' }, { face: 0, label: 'Mirando al N' }, { face: 90, label: 'Mirando al E' }];
  let rotIdx = prefs.get('skyRot', 0);
  let showLabels = prefs.get('skyLabels', true);
  let hitList = [];
  // Zoom and pan: `zoom` >= 1; (fx, fy) = offset (map px at zoom 1, zenith = 0) of the sky point at the centre.
  const MAX_ZOOM = 16;
  let zoom = 1, fx = 0, fy = 0, skyR = 100;

  function cssVar(n) { return getComputedStyle(document.body).getPropertyValue(n).trim(); }

  /** Offset from the zenith at zoom 1 (azimuthal equidistant). */
  function base(az, alt, R) {
    const r = R * (90 - alt) / 90;
    // Facing direction at the bottom; looking up, east is 90° counter-clockwise from north.
    const a = (az - ROT[rotIdx].face - 180) * Math.PI / 180;
    return [-r * Math.sin(a), -r * Math.cos(a)];
  }

  function project(az, alt, cx, cy, R) {
    const [bx, by] = base(az, alt, R);
    return [cx + (bx - fx) * zoom, cy + (by - fy) * zoom];
  }

  /** Sky direction of a zoom-1 map offset. */
  function skyAt(bx, by, R) {
    const r = Math.hypot(bx, by);
    const a = r < 1e-6 ? 0 : Math.atan2(-bx, -by);
    return { az: ((a * 180 / Math.PI + ROT[rotIdx].face + 180) % 360 + 360) % 360, alt: 90 - 90 * r / R };
  }

  /** At x1 the whole sky is in view; closer in, any point of it (horizon included) can be centred. */
  function clampFocus() {
    const maxR = skyR * Math.min(1, Math.max(0, zoom - 1));
    const r = Math.hypot(fx, fy);
    if (r > maxR && r > 0) { fx *= maxR / r; fy *= maxR / r; }
  }

  /** New zoom keeping the sky point under (ax, ay) (canvas px) where it is. */
  function setZoom(z, ax, ay, z0 = zoom, fx0 = fx, fy0 = fy) {
    const c = canvas.clientWidth / 2;
    const nz = Math.min(Math.max(z, 1), MAX_ZOOM);
    const bx = fx0 + (ax - c) / z0, by = fy0 + (ay - c) / z0;
    zoom = nz; fx = bx - (ax - c) / nz; fy = by - (ay - c) / nz;
    clampFocus();
    canvas.style.touchAction = zoom > 1.001 ? 'none' : 'pan-y';
    drawSky();
  }

  const COMPASS = ['N', 'NE', 'E', 'SE', 'S', 'SO', 'O', 'NO'];

  function drawSky() {
    if (S.tab !== 'sky') return;
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth;
    if (!w || w < 80) return;
    if (canvas.width !== Math.round(w * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(w * dpr); }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const cx = w / 2, cy = w / 2, R = w / 2 - 22;
    skyR = R;
    ctx.clearRect(0, 0, w, w);
    hitList = [];
    const on = (x, y) => x > -30 && y > -30 && x < w + 30 && y < w + 30;
    const [zx, zy] = project(0, 90, cx, cy, R); // zenith on screen
    const ZR = R * zoom;

    const g = ctx.createRadialGradient(zx, zy, ZR * 0.1, zx, zy, ZR);
    g.addColorStop(0, cssVar('--sky-bg-1')); g.addColorStop(1, cssVar('--sky-bg-2'));
    ctx.fillStyle = g; ctx.beginPath(); ctx.arc(zx, zy, ZR, 0, 2 * Math.PI); ctx.fill();

    // altitude rings (every 30°, every 10° when close) and, when close, azimuth spokes
    ctx.strokeStyle = cssVar('--sky-line'); ctx.lineWidth = 1; ctx.setLineDash([3, 5]);
    const step = zoom >= 3 ? 10 : 30;
    for (let alt = step; alt < 90; alt += step) {
      ctx.globalAlpha = alt % 30 === 0 ? 1 : 0.5;
      ctx.beginPath(); ctx.arc(zx, zy, ZR * (90 - alt) / 90, 0, 2 * Math.PI); ctx.stroke();
    }
    if (zoom >= 2) {
      ctx.globalAlpha = 0.5; ctx.setLineDash([2, 6]);
      for (let az = 0; az < 360; az += 30) {
        const [hx, hy] = project(az, 0, cx, cy, R);
        ctx.beginPath(); ctx.moveTo(zx, zy); ctx.lineTo(hx, hy); ctx.stroke();
      }
    }
    ctx.globalAlpha = 1; ctx.setLineDash([]);
    ctx.strokeStyle = cssVar('--sky-horizon'); ctx.lineWidth = 1.5;
    ctx.beginPath(); ctx.arc(zx, zy, ZR, 0, 2 * Math.PI); ctx.stroke();

    ctx.fillStyle = cssVar('--dim'); ctx.font = '600 13px -apple-system, system-ui'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    const cardinals = zoom >= 2 ? COMPASS.map((l, i) => [l, i * 45]) : [['N', 0], ['E', 90], ['S', 180], ['O', 270]];
    for (const [lab, az] of cardinals) {
      const [hx, hy] = project(az, 0, cx, cy, R);
      const d = Math.max(Math.hypot(hx - zx, hy - zy), 1);
      const x = hx + (hx - zx) / d * 13, y = hy + (hy - zy) / d * 13;
      if (on(x, y)) ctx.fillText(lab, x, y);
    }
    // the closer in, the fainter the names shown
    const starLabelMag = zoom >= 4 ? 99 : zoom >= 2 ? 2.5 : 1.6;
    const grow = Math.min(1 + 0.12 * (zoom - 1), 1.8);

    const sky = S.sky;
    if (sky) {
      const starColor = cssVar('--sky-star');
      ctx.fillStyle = starColor;
      for (const [az, alt, mag, label] of sky.stars) {
        if (alt < 0) continue;
        const [x, y] = project(az, alt, cx, cy, R);
        if (!on(x, y)) continue;
        const r = Math.max(0.55, 2.9 - 0.52 * mag) * grow;
        ctx.globalAlpha = Math.min(1, 0.45 + (4.5 - mag) * 0.15);
        ctx.beginPath(); ctx.arc(x, y, r, 0, 2 * Math.PI); ctx.fill();
        if (label) {
          hitList.push({ id: label, x, y, pr: 1 });
          if (showLabels && mag <= starLabelMag) { ctx.globalAlpha = 0.75; ctx.font = '11px -apple-system, system-ui'; ctx.textAlign = 'left'; ctx.fillText(label, x + r + 3, y); }
        }
      }
      ctx.globalAlpha = 1;
      const dso = cssVar('--sky-dso'), planet = cssVar('--sky-planet');
      for (const o of sky.objects) {
        if (o.alt < 0) continue;
        const [x, y] = project(o.az, o.alt, cx, cy, R);
        if (!on(x, y)) continue;
        ctx.strokeStyle = dso; ctx.fillStyle = dso; ctx.lineWidth = 1.2;
        if (o.cat === 'planet' || o.cat === 'moon') {
          ctx.fillStyle = planet;
          ctx.beginPath(); ctx.arc(x, y, o.cat === 'moon' ? 7 : 4.2, 0, 2 * Math.PI); ctx.fill();
          ctx.font = '600 12px -apple-system, system-ui'; ctx.textAlign = 'left'; ctx.fillText(o.id, x + 8, y);
          hitList.push({ id: o.id, x, y, pr: 0 });
          continue;
        }
        if (o.cat === 'galaxy') { ctx.beginPath(); ctx.ellipse(x, y, 5, 2.6, -0.5, 0, 2 * Math.PI); ctx.stroke(); }
        else if (o.cat === 'nebula') { ctx.strokeRect(x - 3.5, y - 3.5, 7, 7); }
        else { ctx.setLineDash([1.5, 1.5]); ctx.beginPath(); ctx.arc(x, y, 4, 0, 2 * Math.PI); ctx.stroke(); ctx.setLineDash([]); }
        const messier = /^M\d+$/.test(o.id);
        const text = zoom >= 6 && o.name && o.name !== o.id ? `${o.id} · ${o.name}`
          : zoom >= 4 || (zoom >= 2 && messier) || (messier && (o.mag ?? 99) <= 7) ? o.id : null;
        if (showLabels && text) { ctx.globalAlpha = 0.85; ctx.font = '10px -apple-system, system-ui'; ctx.textAlign = 'left'; ctx.fillText(text, x + 6, y + 6); ctx.globalAlpha = 1; }
        hitList.push({ id: o.id, x, y, pr: 2 });
      }
    } else {
      ctx.fillStyle = cssVar('--dim'); ctx.font = '14px -apple-system, system-ui'; ctx.textAlign = 'center';
      ctx.fillText(S.open ? 'Cargando cielo…' : 'Sin conexión', cx, cy);
    }

    // other layers (visible zones, zones.js)
    for (const fn of extra.skyDraw || []) fn(ctx, { project: (az, alt) => project(az, alt, cx, cy, R), zenith: [zx, zy], radius: ZR, dpr });

    // Selected object.
    if (S.sheetObj && S.sheetObj.alt > 0) {
      const [x, y] = project(S.sheetObj.az, S.sheetObj.alt, cx, cy, R);
      ctx.strokeStyle = cssVar('--accent'); ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(x, y, 12, 0, 2 * Math.PI); ctx.stroke();
    }
    // Phone sensors (where the tube roughly points) and the telescope.
    const st = S.status;
    if (st.sensor && st.sensor.alt > -5 && st.alt === undefined) reticle(project(st.sensor.az, Math.max(st.sensor.alt, 0), cx, cy, R), true);
    if (st.alt !== undefined && st.alt > -5) reticle(project(st.az, Math.max(st.alt, 0), cx, cy, R), false);
    // centre mark and readout while zoomed: where the centre of the view looks
    const ro = $('#skyReadout');
    if (zoom > 1.01) {
      ctx.strokeStyle = 'rgba(255,255,255,0.4)'; ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(cx - 6, cy); ctx.lineTo(cx + 6, cy); ctx.moveTo(cx, cy - 6); ctx.lineTo(cx, cy + 6); ctx.stroke();
      const c = skyAt(fx, fy, R);
      ro.textContent = c.alt < 0 ? 'Centro: bajo el horizonte'
        : `Centro: ${COMPASS[Math.floor((c.az + 22.5) / 45) % 8]} ${Math.round(c.az)}° · altura ${Math.round(c.alt)}° · ×${Math.round(zoom)}`;
    } else {
      ro.textContent = 'Pellizca para ampliar';
    }
  }

  function reticle([x, y], dashed) {
    ctx.strokeStyle = cssVar('--sky-scope'); ctx.lineWidth = 2;
    if (dashed) ctx.setLineDash([4, 4]);
    ctx.beginPath(); ctx.arc(x, y, 11, 0, 2 * Math.PI); ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(x - 18, y); ctx.lineTo(x - 7, y); ctx.moveTo(x + 7, y); ctx.lineTo(x + 18, y);
    ctx.moveTo(x, y - 18); ctx.lineTo(x, y - 7); ctx.moveTo(x, y + 7); ctx.lineTo(x, y + 18);
    ctx.stroke(); ctx.setLineDash([]);
  }

  // Pinch to zoom, drag to move (only when zoomed: at x1 a drag scrolls the page).
  const pointers = new Map();
  let gest = null;
  let suppressClick = false;
  function localPoint(e) { const r = canvas.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; }
  function startGesture() {
    const ps = [...pointers.values()];
    if (ps.length >= 2) {
      gest = { zoom, fx, fy, dist: Math.max(Math.hypot(ps[0][0] - ps[1][0], ps[0][1] - ps[1][1]), 1),
        mid: [(ps[0][0] + ps[1][0]) / 2, (ps[0][1] + ps[1][1]) / 2] };
    } else if (ps.length === 1) {
      gest = { zoom, fx, fy, start: ps[0] };
    } else gest = null;
  }
  canvas.style.touchAction = 'pan-y';
  canvas.addEventListener('pointerdown', (e) => {
    pointers.set(e.pointerId, localPoint(e));
    if (pointers.size === 1) suppressClick = false;
    if (pointers.size >= 2 || zoom > 1.001) canvas.setPointerCapture(e.pointerId);
    startGesture();
  });
  canvas.addEventListener('pointermove', (e) => {
    if (!pointers.has(e.pointerId) || !gest) return;
    pointers.set(e.pointerId, localPoint(e));
    const ps = [...pointers.values()];
    if (ps.length >= 2 && gest.dist) {
      const d = Math.hypot(ps[0][0] - ps[1][0], ps[0][1] - ps[1][1]);
      setZoom(gest.zoom * d / gest.dist, gest.mid[0], gest.mid[1], gest.zoom, gest.fx, gest.fy);
      suppressClick = true;
    } else if (ps.length === 1 && gest.start && zoom > 1.001) {
      const dx = ps[0][0] - gest.start[0], dy = ps[0][1] - gest.start[1];
      if (Math.hypot(dx, dy) > 6) suppressClick = true;
      fx = gest.fx - dx / zoom; fy = gest.fy - dy / zoom;
      clampFocus(); drawSky();
    }
  });
  const endPointer = (e) => { pointers.delete(e.pointerId); startGesture(); };
  canvas.addEventListener('pointerup', endPointer);
  canvas.addEventListener('pointercancel', endPointer);

  canvas.addEventListener('click', (e) => {
    if (suppressClick) { suppressClick = false; return; }
    const rect = canvas.getBoundingClientRect();
    const x = e.clientX - rect.left, y = e.clientY - rect.top;
    // a module may take the tap (drawing a visible zone): it gets the sky direction under the finger
    const c = canvas.clientWidth / 2;
    const sky = skyAt(fx + (x - c) / zoom, fy + (y - c) / zoom, skyR);
    for (const fn of extra.skyTap || []) if (fn(sky)) return;
    let best = null, bestD = 26 * 26;
    for (const h of hitList) {
      const d = (h.x - x) ** 2 + (h.y - y) ** 2 - (2 - h.pr) * 30; // planets win ties, then stars
      if (d < bestD) { best = h; bestD = d; }
    }
    if (best) openObject(best.id);
  });

  function updateSkyTools() {
    $('#skyRotate').textContent = ROT[rotIdx].label;
    $('#skyLabels').classList.toggle('active', showLabels);
  }
  $('#skyRotate').addEventListener('click', () => {
    // turn the planisphere, keeping the same sky point in the centre
    const next = (rotIdx + 1) % ROT.length;
    const delta = (ROT[next].face - ROT[rotIdx].face) * Math.PI / 180;
    const r = Math.hypot(fx, fy);
    if (r > 0) { const a = Math.atan2(-fx, -fy) - delta; fx = -r * Math.sin(a); fy = -r * Math.cos(a); }
    rotIdx = next; prefs.set('skyRot', rotIdx); updateSkyTools(); drawSky();
  });
  $('#skyZoomIn').addEventListener('click', () => { const c = canvas.clientWidth / 2; setZoom(zoom * 2, c, c); });
  $('#skyZoomOut').addEventListener('click', () => { const c = canvas.clientWidth / 2; setZoom(zoom / 2, c, c); });
  $('#skyAll').addEventListener('click', () => { fx = 0; fy = 0; setZoom(1, 0, 0); });
  $('#skyLabels').addEventListener('click', () => { showLabels = !showLabels; prefs.set('skyLabels', showLabels); updateSkyTools(); drawSky(); });
  $('#skyCenter').addEventListener('click', () => {
    const st = S.status;
    if (st.az === undefined) { toast('El telescopio aún no sabe dónde apunta: alinéalo'); return; }
    const off = (face) => Math.abs(((st.az - face + 540) % 360) - 180);
    rotIdx = ROT.reduce((bi, r, i) => (off(r.face) < off(ROT[bi].face) ? i : bi), 0);
    prefs.set('skyRot', rotIdx); updateSkyTools();
    // fly there, closer in
    zoom = Math.max(zoom, 3);
    [fx, fy] = base(st.az, Math.max(st.alt ?? 0, 0), skyR);
    clampFocus();
    canvas.style.touchAction = 'none';
    drawSky();
  });
  window.addEventListener('resize', () => drawSky());
  setInterval(() => { if (S.tab === 'sky' && S.open && !document.hidden) send({ type: 'sky' }); }, 60_000);

  // ---------------------------------------------------------------- object sheet
  function openObject(id) {
    S.sheetObj = { id, loading: true };
    renderSheet();
    send({ type: 'object', id });
  }

  function renderSheet() {
    const o = S.sheetObj;
    if (o) $('.sheet-panel').scrollTop = 0;
    const sheet = $('#sheet');
    if (!o) { sheet.classList.add('hidden'); sheet.setAttribute('aria-hidden', 'true'); return; }
    sheet.classList.remove('hidden'); sheet.setAttribute('aria-hidden', 'false');
    if (o.loading) { $('#sheetBody').innerHTML = `<h3>${esc(o.id)}</h3><p class="kind">Cargando…</p>`; return; }
    const visible = o.alt > 0;
    const mode = S.info.mode;
    const aligned = mode === 'hc' ? S.info.hcAligned : S.alignment.aligned;
    const canAlign = mode === 'starbridge' && visible && (o.cat === 'star' || o.cat === 'planet');
    const canSync = mode === 'hc' && S.info.canSync && S.info.hcAligned && visible;
    // Unaligned but the Android is on the tube: its compass gives a rough alignment first.
    const rough = !aligned && mode === 'starbridge' && !!S.info.orientation;
    let gotoNote = '', roughNote = '';
    if (!S.status.connected) gotoNote = 'Telescopio no conectado';
    else if (!visible) gotoNote = 'Está bajo el horizonte ahora mismo';
    else if (!aligned && mode === 'hc') gotoNote = 'El mando no está alineado: alinéalo con el mando o cambia a «Alinea StarBridge»';
    else if (!aligned && !rough) gotoNote = 'Sin alinear: centra una estrella con la cruceta y pulsa «Usar para alinear» (o sujeta el Android al tubo para usar sus sensores)';
    else if (rough) roughNote = 'Sin alinear: primero se orienta con la brújula del Android (±5°). Al llegar, céntralo y pulsa «Usar para alinear» para afinar.';
    $('#sheetBody').innerHTML = `
      <h3>${esc(titleOf(o))}</h3>
      <p class="kind">${esc(o.kind)}${o.const ? ' · ' + esc(o.const) : ''}${o.mag !== undefined ? ' · magnitud ' + o.mag : ''}</p>
      <div class="facts">
        <div class="fact"><span>Altura</span><b>${fmtDeg(o.alt)}</b></div>
        <div class="fact"><span>Dirección</span><b>${esc(o.dir)} · ${fmtDeg(o.az)}</b></div>
        <div class="fact"><span>Ascensión recta</span><b class="mono">${fmtRA(o.ra)}</b></div>
        <div class="fact"><span>Declinación</span><b class="mono">${fmtDec(o.dec)}</b></div>
      </div>
      <div class="sheet-actions">
        <button class="btn primary" id="sheetGoto" ${gotoNote ? 'disabled' : ''}>Ir a ${esc(o.id)}${rough && !gotoNote ? ' (aproximado)' : ''}</button>
        ${gotoNote || roughNote ? `<p class="hint center">${esc(gotoNote || roughNote)}</p>` : ''}
        ${o.inZone === false && o.alt > 0 ? '<p class="hint center ztag">Fuera de tu zona visible: puede que no lo veas desde aquí.</p>' : ''}
        ${canAlign ? `<button class="btn" id="sheetAlign">Usar para alinear</button>` : ''}
        ${canSync ? `<button class="btn" id="sheetSync">Sync: lo tengo centrado</button>` : ''}
        <button class="btn ghost" data-close>Cerrar</button>
      </div>`;
    $('#sheetGoto')?.addEventListener('click', () => { send({ type: 'goto', id: o.id, rough: rough || undefined }); closeSheet(); });
    $('#sheetAlign')?.addEventListener('click', () => { closeSheet(); if (S.tab !== 'align') tabBeforeAlign = S.tab; showTab('align'); selectStar(o); });
    $('#sheetSync')?.addEventListener('click', () => { send({ type: 'sync', id: o.id }); closeSheet(); });
  }
  function closeSheet() {
    endMeter(); S.diagOpen = false;
    S.sheetObj = null; S.visual = null; renderSheet(); drawSky();
    for (const fn of extra.sheetClosed || []) fn();
  }

  // A tap outside a text field closes the keyboard (mobile Safari does not always do it).
  document.addEventListener('pointerdown', (e) => {
    const a = document.activeElement;
    if (a && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA') && !e.target.closest('input, textarea, select, label')) a.blur();
  }, true);

  // ---------------------------------------------------------------- search
  let searchTimer = null;
  function search() {
    const q = S.query.trim();
    const category = q && S.category === 'best' ? null : S.category;
    send({ type: 'search', q, category: category || undefined });
  }
  $('#q').addEventListener('input', () => {
    S.query = $('#q').value;
    // typing searches the whole catalogue: show it on the chips
    if (S.query.trim() && S.category === 'best') {
      S.category = 'all';
      $$('#chips .chip').forEach((x) => x.classList.toggle('active', x.dataset.cat === 'all'));
    }
    clearTimeout(searchTimer);
    searchTimer = setTimeout(search, 180);
  });
  $$('#chips .chip').forEach((c) => c.addEventListener('click', () => {
    $$('#chips .chip').forEach((x) => x.classList.toggle('active', x === c));
    S.category = c.dataset.cat;
    search();
  }));
  $('#onlyVisible').addEventListener('change', renderResults);
  $('#onlyZone').addEventListener('change', renderResults);

  function renderResults() {
    const only = $('#onlyVisible').checked;
    const onlyZone = $('#onlyZone').checked && (S.zones || []).some((z) => z.on);
    const ul = $('#results');
    const items = S.results.filter((it) => (!only || it.alt > 0) && (!onlyZone || it.inZone !== false));
    if (!items.length) {
      const hidden = S.results.length;
      ul.innerHTML = `<li class="empty">${!S.open ? 'Sin conexión.'
        : hidden === 1 ? `${esc(S.results[0].id)} está bajo el horizonte ahora mismo.`
        : hidden ? `Los ${hidden} resultados están bajo el horizonte ahora mismo.`
        : 'No hay resultados.'}</li>`;
      return;
    }
    ul.innerHTML = items.map((it) => `
      <li class="item ${it.alt < 0 ? 'below' : ''}" data-id="${esc(it.id)}">
        <div class="badge">${CAT_ICON[it.cat] || '◌'}</div>
        <div class="meta">
          <div class="name">${esc(titleOf(it))}</div>
          <div class="sub">${esc(it.kind)}${it.mag !== undefined ? ' · mag ' + it.mag : ''}${it.const ? ' · ' + esc(it.const) : ''}${it.inZone === false && it.alt > 0 ? ' · <span class="ztag">fuera de tu zona</span>' : ''}</div>
        </div>
        <div class="alt"><b>${it.alt.toFixed(0)}°</b>${esc(it.dir)}</div>
      </li>`).join('');
    $$('.item', ul).forEach((li) => li.addEventListener('click', () => openObject(li.dataset.id)));
  }

  // ---------------------------------------------------------------- alignment
  function selectStar(o) {
    S.selectedStar = o; S.guide = null;
    renderAlign();
    window.scrollTo({ top: Math.max(0, $('#alignActive').offsetTop - 90), behavior: 'smooth' });
  }

  setInterval(() => {
    if (S.tab === 'align' && S.selectedStar && S.info.orientation && !document.hidden) send({ type: 'guide', id: S.selectedStar.id });
  }, 1000);

  function renderAlignChecks() {
    if (S.tab !== 'align') return;
    const st = S.status, info = S.info, site = info.site || {};
    const srcText = { gps: `GPS${site.accuracyM !== undefined ? ' ±' + site.accuracyM + ' m' : ''}`, manual: 'manual', hand_control: 'del mando', default: 'sin fijar (Madrid por defecto)' }[site.source] || '—';
    const checks = [
      [!!st.connected, 'Telescopio', st.connected ? `mando v${info.hcVersion || '?'}` : 'no conectado'],
      [site.source === 'gps' || site.source === 'manual' || site.source === 'hand_control', 'Ubicación', srcText],
      [site.source === 'gps', 'Hora exacta', site.source === 'gps' ? 'GPS' : 'reloj del Android'],
      [!!info.orientation && !!st.sensor, 'Sensores del Android', info.orientation ? (st.sensor ? `apunta ~${st.sensor.az.toFixed(0)}° / ${st.sensor.alt.toFixed(0)}°` : 'sin datos') : 'no disponibles'],
    ];
    $('#alignChecks').innerHTML = checks.map(([ok, name, detail]) =>
      `<div class="check"><i class="${ok ? 'ok' : 'no'}">${ok ? '✓' : '!'}</i>${name}<small>${esc(detail)}</small></div>`).join('');
  }

  function renderAlign() {
    renderTop();
    const a = S.alignment, info = S.info;
    const mode = a.mode || info.mode || 'starbridge';
    $$('#modeSeg button').forEach((b) => b.classList.toggle('active', b.dataset.mode === mode));

    // Header.
    let ring, title, sub;
    if (mode === 'hc') {
      ring = info.hcAligned ? '<div class="ring ok">✓</div>' : '<div class="ring">!</div>';
      title = info.hcAligned ? 'El mando está alineado' : 'El mando no está alineado';
      sub = info.hcAligned
        ? (info.canSync ? 'Para afinar: centra un objeto y pulsa «Sync» en su ficha.' : 'GoTo y seguimiento los hace el mando.')
        : 'Haz la alineación en el mando (SkyAlign…) o usa «Alinea StarBridge».';
    } else if (!a.aligned) {
      ring = '<div class="ring">0</div>'; title = 'Sin alinear'; sub = 'Centra una estrella brillante y el Android sabrá dónde está todo.';
    } else if (a.sensorOnly) {
      ring = '<div class="ring rough">~</div>'; title = 'Alineación aproximada'; sub = 'Hecha con los sensores. Centra una estrella para precisión completa.';
    } else {
      ring = `<div class="ring ok">${a.stars}</div>`;
      title = a.stars === 1 ? 'Alineado con 1 estrella' : `Alineado con ${a.stars} estrellas`;
      sub = a.stars === 1 ? 'Añade una segunda estrella lejos de la primera para corregir la nivelación.'
        : `Precisión ${a.rmsArcmin}′ · inclinación de la base ${a.tiltDeg}°`;
    }
    $('#alignHeader').innerHTML = `<div class="align-state">${ring}<div><div class="row-title">${title}</div><div class="row-sub">${sub}</div></div></div>`;
    renderAlignChecks();

    const body = $('#alignBody');
    if (mode === 'hc') {
      body.innerHTML = '';
      $('#alignActive').classList.add('hidden');
      $('#alignPoints').innerHTML = '';
      return;
    }
    const sensorStep = info.orientation && !(a.aligned && !a.sensorOnly)
      ? (a.sensorOnly
        ? `<div class="card row-between"><div><div class="step-title">Paso 1 · Alineación rápida ✓</div>
            <div class="row-sub">Hecha con los sensores (±5°). Ya puedes ir a la primera estrella.</div></div>
            <button id="alignSensor" class="btn small">Repetir</button></div>`
        : `<div class="card"><div class="step-title">Paso 1 · Alineación rápida (opcional)</div>
            <div class="row-sub">Con el Android sujeto al tubo, la brújula y el acelerómetro dan una primera estimación (±5°). Así el GoTo te lleva cerca de la primera estrella.</div>
            <button id="alignSensor" class="btn full">Usar los sensores del Android</button></div>`)
      : '';
    const sugg = (a.suggestions || []).map((s) => `
      <li class="item" data-id="${esc(s.id)}">
        <div class="badge">${CAT_ICON[s.cat] || '✦'}</div>
        <div class="meta"><div class="name">${esc(s.id)}</div>
          <div class="sub">${esc(s.dir)} · ${s.alt.toFixed(0)}° de altura${s.mag !== undefined ? ' · mag ' + s.mag : ''}${s.sepDeg !== undefined ? ' · a ' + s.sepDeg + '° de la anterior' : ''}</div></div>
        <div class="alt"><b>›</b></div>
      </li>`).join('');
    const stepNo = sensorStep ? 2 : 1;
    if (S.selectedStar) {
      // Focus on the chosen star: the list folds away, the panel with the pad comes up.
      body.innerHTML = `<div class="step-title" style="margin:4px 4px 10px">Paso ${stepNo} · Centra ${esc(S.selectedStar.id)}</div>`;
    } else body.innerHTML = `${sensorStep}
      <div class="card"><div class="step-title">Paso ${stepNo} · ${a.stars ? 'Otra estrella' : 'Elige una estrella brillante'}</div>
        <div class="row-sub">${a.stars ? 'Mejor si está lejos de la anterior (≥ 60°).' : 'Elige una que reconozcas en el cielo.'}</div>
        <ul class="list">${sugg || '<li class="empty">No hay estrellas buenas visibles (¿es de día?).</li>'}</ul>
        <button id="alignSearch" class="btn ghost full">Buscar otra estrella o planeta…</button>
      </div>`;
    $('#alignSensor')?.addEventListener('click', () => send({ type: 'alignSensor' }));
    $('#alignSearch')?.addEventListener('click', () => {
      if (!TABS[useMode()].includes('search')) return; S.category = 'star'; $$('#chips .chip').forEach((x) => x.classList.toggle('active', x.dataset.cat === 'star')); showTab('search'); });
    $$('.item', body).forEach((li) => li.addEventListener('click', () => {
      const s = (a.suggestions || []).find((x) => x.id === li.dataset.id);
      if (s) selectStar(s);
    }));

    // Active star panel.
    const act = $('#alignActive');
    if (S.selectedStar) {
      const o = S.selectedStar;
      act.classList.remove('hidden');
      const canGo = a.aligned || !!info.orientation;
      $('#activeInfo').innerHTML = `<div class="row-title">${esc(o.id)}</div>
        <div class="row-sub">${esc(o.dir)} · ${o.alt.toFixed(0)}° de altura. ${canGo ? 'Pulsa «Ir» o llévalo' : 'Llévalo'} con la cruceta, céntrala en el ocular (velocidad fina al final) y pulsa «Está centrada».</div>`;
      const go = $('#activeGoto');
      go.disabled = !canGo;
      go.textContent = a.aligned ? 'Ir con GoTo' : 'Ir (con sensores)';
      renderGuide();
    } else act.classList.add('hidden');

    // Points.
    const pts = a.points || [];
    $('#alignPoints').innerHTML = pts.length ? `<div class="card"><div class="step-title">Puntos de alineación</div>
        ${pts.map((p) => `<div class="point-row"><span>${p.sensor ? '📱 ' : '✦ '}${esc(p.label)}</span>
          <span class="row-sub">${p.residualArcmin !== undefined ? 'error ' + p.residualArcmin + '′' : p.sensor ? 'aprox.' : ''}
          <button data-remove="${esc(p.label)}" aria-label="Quitar">✕</button></span></div>`).join('')}
        <button id="alignClear" class="btn ghost full">Empezar de nuevo</button></div>` : '';
    $$('[data-remove]').forEach((b) => b.addEventListener('click', () => send({ type: 'alignRemove', label: b.dataset.remove })));
    $('#alignClear')?.addEventListener('click', () => { if (confirm('¿Borrar la alineación?')) send({ type: 'alignClear' }); });
  }

  function renderGuide() {
    const el = $('#guide');
    const g = S.guide;
    if (!g || g.dAz === undefined) { el.classList.add('hidden'); return; }
    el.classList.remove('hidden');
    const near = Math.abs(g.dAz) < 1.5 && Math.abs(g.dAlt) < 1.5;
    if (near) { el.innerHTML = '<span class="ok">≈ Ya casi: búscala en el ocular</span>'; return; }
    const h = Math.abs(g.dAz) >= 1 ? `${g.dAz > 0 ? '▶' : '◀'} ${Math.abs(g.dAz).toFixed(0)}°` : '';
    const v = Math.abs(g.dAlt) >= 1 ? `${g.dAlt > 0 ? '▲' : '▼'} ${Math.abs(g.dAlt).toFixed(0)}°` : '';
    el.innerHTML = `<span>${h}</span><span>${v}</span>`;
  }

  $$('#modeSeg button').forEach((b) => b.addEventListener('click', () => send({ type: 'setMode', mode: b.dataset.mode })));
  $('#activeGoto').addEventListener('click', () => {
    if (!S.selectedStar) return;
    send({ type: 'goto', id: S.selectedStar.id, rough: !S.alignment.aligned || undefined });
    toast(`Yendo a ${S.selectedStar.id}…`);
  });
  $('#activeDone').addEventListener('click', () => {
    if (!S.selectedStar) return;
    releaseAll(true);
    send({ type: 'alignAdd', id: S.selectedStar.id });
    toast(`${S.selectedStar.id} añadida a la alineación`);
    S.selectedStar = null; S.guide = null;
    renderAlign();
  });
  $('#activeCancel').addEventListener('click', () => { S.selectedStar = null; S.guide = null; renderAlign(); });

  // ---------------------------------------------------------------- more / settings
  function renderMore() {
    const i = S.info, site = i.site || {};
    $('#infoCard').innerHTML = i.connected
      ? `<div class="row-title">Conectado</div><div class="row-sub">Mando versión ${esc(i.hcVersion)}${i.model === 7 ? ' · NexStar SLT' : ''}${i.canSync ? ' · admite Sync' : ''}</div>
         <div class="row-sub">Giroscopio: ${i.gyro ? 'sí' : 'no'} · Sensores de orientación: ${i.orientation ? 'sí' : 'no'}</div>`
      : `<div class="row-title">Telescopio no conectado</div><div class="row-sub">Enchufa el cable del mando al Android con el adaptador OTG.</div>`;
    const src = { gps: 'GPS', manual: 'Introducida a mano', hand_control: 'Leída del mando', default: 'Sin fijar · usando Madrid' }[site.source] || '—';
    $('#siteInfo').innerHTML = site.lat !== undefined
      ? `${src}${site.accuracyM !== undefined ? ` (±${site.accuracyM} m)` : ''}<br><span class="mono">${site.lat.toFixed(4)}°, ${site.lon.toFixed(4)}°</span>`
      : '—';
    if (site.lat !== undefined && document.activeElement !== $('#lat')) { $('#lat').value = site.lat.toFixed(4); $('#lon').value = site.lon.toFixed(4); }
    const st = i.settings || {};
    $$('#mountingSeg button').forEach((b) => b.classList.toggle('active', b.dataset.mounting === (st.mounting || 'top')));
    if (document.activeElement !== $('#approach') && st.approachDeg !== undefined) $('#approach').value = st.approachDeg;
    if (document.activeElement !== $('#slackAz') && st.slackAz !== undefined) $('#slackAz').value = st.slackAz;
    if (document.activeElement !== $('#slackAlt') && st.slackAlt !== undefined) $('#slackAlt').value = st.slackAlt;
    if (document.activeElement !== $('#takeUp')) $('#takeUp').checked = st.manualTakeUp !== false;
    const method = st.gotoMethod || 'auto';
    $$('#gotoSeg button').forEach((b) => b.classList.toggle('active', b.dataset.method === method));
    $('#gotoHint').textContent = {
      auto: (i.hcGoto === false ? 'Este mando no hace el GoTo sin alinear: lo hace StarBridge leyendo los encoders.'
        : i.hcGoto === true ? 'El mando hace el tramo largo; la llegada la hace StarBridge para dejar la holgura bien recogida.'
        : 'El mando hace el tramo largo (si no se mueve, lo hace StarBridge); la llegada siempre StarBridge.'),
      hc: 'Siempre el GoTo del propio mando, también la llegada (puede quedar desviado tanto como la holgura).',
      soft: 'StarBridge mueve los motores con las velocidades de las flechas y lee los encoders hasta llegar.',
    }[method] || '';
  }

  function renderShare() {
    const key = accessKey();
    const img = $('#shareQr');
    if (key && S.open) {
      const src = `qr.svg?k=${encodeURIComponent(key)}`;
      if (img.getAttribute('src') !== src) img.setAttribute('src', src);
      $('#shareAddress').textContent = location.host;
      $('#shareKey').textContent = normalizeKey(key).match(/.{1,4}/g).join('-');
      $('#shareCard').classList.remove('hidden');
    } else $('#shareCard').classList.add('hidden');
  }

  function renderSensorLive() {
    const s = S.status.sensor;
    $('#sensorLive').textContent = s ? `El Android dice: acimut ${s.az.toFixed(0)}° · altura ${s.alt.toFixed(0)}°` : (S.info.orientation ? 'Sin lectura del sensor' : 'Este Android no tiene sensores de orientación');
  }

  $('#saveSite').addEventListener('click', () => {
    const lat = Number($('#lat').value), lon = Number($('#lon').value);
    if (Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) send({ type: 'setSite', lat, lon });
    else toast('Coordenadas no válidas', true);
  });
  $$('#mountingSeg button').forEach((b) => b.addEventListener('click', () => send({ type: 'setSetting', key: 'mounting', value: b.dataset.mounting })));
  $('#approach').addEventListener('change', () => send({ type: 'setSetting', key: 'approachDeg', value: String(Number($('#approach').value) || 0) }));
  $('#saveSlack').addEventListener('click', () => {
    send({ type: 'setSetting', key: 'slackAz', value: String(Number($('#slackAz').value) || 0) });
    send({ type: 'setSetting', key: 'slackAlt', value: String(Number($('#slackAlt').value) || 0) });
    toast('Holgura guardada');
  });
  $$('.cal').forEach((b) => b.addEventListener('click', () => {
    if (confirm('El telescopio se moverá solo unos grados adelante y atrás (el Android debe ir sujeto al tubo). ¿Continuar?')) {
      $('#calResult').textContent = 'Midiendo… pulsa STOP para cancelar';
      send({ type: 'calibrateBacklash', axis: b.dataset.axis });
    }
  }));
  $$('.bl').forEach((box) => $('.save', box).addEventListener('click', () => {
    const clamp = (v) => Math.max(0, Math.min(99, Math.round(Number(v) || 0)));
    send({ type: 'setBacklash', axis: box.dataset.axis, pos: clamp($('.pos', box).value), neg: clamp($('.neg', box).value) });
    toast('Anti-backlash guardado en el telescopio');
  }));
  $('#readBacklash').addEventListener('click', () => send({ type: 'getBacklash' }));

  // Visual backlash calibration, step by step in the bottom sheet.
  $$('.vcal').forEach((b) => b.addEventListener('click', () => {
    S.sheetObj = null;
    S.visual = { axis: b.dataset.axis, phase: 'intro' };
    renderVisual();
  }));

  function renderVisual() {
    const v = S.visual;
    const sheet = $('#sheet');
    if (!v) { sheet.classList.add('hidden'); return; }
    sheet.classList.remove('hidden');
    const axisName = v.axis === 'azm' ? 'acimut (izquierda/derecha)' : 'altitud (arriba/abajo)';
    let body;
    if (v.phase === 'intro') {
      body = `<p class="kind">Holgura en ${axisName}</p>
        <ol class="steps">
          <li>Centra una estrella brillante en el ocular (mejor con poco aumento).</li>
          <li>Pulsa <b>Empezar</b>. El telescopio se moverá un poco hacia un lado y luego, muy despacio, hacia el otro.</li>
          <li>Mira por el ocular y pulsa <b>¡Se mueve!</b> justo cuando la estrella empiece a desplazarse.</li>
        </ol>
        <div class="sheet-actions"><button class="btn primary" id="vStart">Empezar</button>
        <button class="btn ghost" id="vClose">Cancelar</button></div>`;
    } else if (v.phase === 'preparing') {
      body = `<p class="kind">Preparando: tensando los engranajes hacia un lado…</p>
        <div class="sheet-actions"><button class="btn big-mark" disabled>¡Se mueve!</button>
        <button class="btn ghost" id="vCancel">Cancelar</button></div>`;
    } else if (v.phase === 'watching') {
      body = `<p class="kind">Ahora se mueve muy despacio. Mira la estrella…</p>
        <div class="sheet-actions"><button class="btn primary big-mark" id="vMark">¡Se mueve!</button>
        <button class="btn ghost" id="vCancel">Cancelar</button></div>`;
    } else if (v.phase === 'done') {
      body = `<p class="kind">Holgura medida en ${axisName}</p>
        <div class="facts"><div class="fact"><span>Holgura</span><b>${v.slackDeg.toFixed(2)}°</b></div>
        <div class="fact"><span>Estado</span><b>Guardada</b></div></div>
        <p class="hint">Se usa ya en el GoTo y el seguimiento. Repite 2–3 veces si quieres confirmar el valor.</p>
        <div class="sheet-actions"><button class="btn primary" id="vStart">Repetir</button>
        <button class="btn ghost" id="vClose">Cerrar</button></div>`;
    } else {
      body = `<p class="kind">No se pudo medir: ${esc(v.message || '')}</p>
        <div class="sheet-actions"><button class="btn primary" id="vStart">Reintentar</button>
        <button class="btn ghost" id="vClose">Cerrar</button></div>`;
    }
    $('#sheetBody').innerHTML = `<h3>Calibración visual</h3>${body}`;
    $('#vStart')?.addEventListener('click', () => {
      if (!send({ type: 'slackVisualStart', axis: v.axis })) { toast('Sin conexión', true); return; }
      S.visual = { axis: v.axis, phase: 'preparing' }; renderVisual();
    });
    $('#vMark')?.addEventListener('click', () => send({ type: 'slackVisualMark' }));
    $('#vCancel')?.addEventListener('click', () => { send({ type: 'stop', axis: v.axis }); S.visual = null; renderVisual(); });
    $('#vClose')?.addEventListener('click', () => { S.visual = null; renderVisual(); });
  }

  // ---------------------------------------------------------------- backlash meter
  // The Android rests on the tube; this phone moves one axis with two arrows. Every reversal
  // is a measurement: motor degrees from the press until the gyroscope sees the tube turn.
  const AXIS_UI = {
    azm: { name: 'Acimut', neg: '◀', pos: '▶', negName: 'izquierda', posName: 'derecha' },
    alt: { name: 'Altitud', neg: '▼', pos: '▲', negName: 'abajo', posName: 'arriba' },
  };
  $('#meterOpen').addEventListener('click', openMeter);

  /** The sheet now belongs to app.js: other modules (imaging.js) must stop refreshing theirs. */
  function claimSheet() { for (const fn of extra.sheetClosed || []) fn(); }

  function openMeter() {
    // info arrives on connect; status only once a second: either says the mount is there.
    if (!S.status.connected && !S.info.connected) { toast('Conecta el telescopio primero', true); return; }
    if (!S.info.gyro) { toast('Este Android no tiene giroscopio', true); return; }
    claimSheet();
    const body = window.SB.openSheet(`
      <h3>Medir holgura</h3>
      <p class="kind">Apoya el Android del cable sobre el tubo, bien quieto. Mueve con las flechas: cada cambio de sentido es una medida.</p>
      <div class="seg" id="mAxisSeg">
        <button data-axis="azm">Acimut ◀ ▶</button><button data-axis="alt">Altitud ▲ ▼</button>
      </div>
      <div class="meter">
        <div id="mState" class="meter-state">Pulsa una flecha y mantenla</div>
        <div class="meter-num mono"><span id="mTravel">0.00</span><small>°</small></div>
        <div class="meter-cap">giro del motor desde que pulsaste</div>
        <div class="meter-bar" aria-hidden="true"><i id="mGyro"></i><b id="mThr"></b></div>
        <div class="meter-cap">movimiento real del tubo (giroscopio)</div>
      </div>
      <p id="mHint" class="meter-hint"></p>
      <div class="meter-arrows">
        <button class="mdir" id="mNeg" data-dir="neg"></button>
        <button class="mdir" id="mPos" data-dir="pos"></button>
      </div>
      <div class="seg compact" id="mSpeedSeg"><button data-rate="4">Lenta</button><button data-rate="6">Media</button><button data-rate="7">Rápida</button></div>
      <div class="meter-res">
        <div class="row-between">
          <div><div class="row-title" id="mResTitle"></div><div class="row-sub" id="mResSub"></div></div>
          <div class="meter-big mono" id="mResVal">—</div>
        </div>
        <div id="mItems" class="meter-items"></div>
        <div class="split"><button class="btn small" id="mClear">Borrar</button><button class="btn small primary" id="mSave" disabled>Guardar</button></div>
      </div>
      <button class="btn ghost full" data-close>Cerrar</button>`);
    S.meter = { axis: prefs.get('meterAxis', 'azm'), open: true };
    for (const b of $$('#mAxisSeg button', body)) b.addEventListener('click', () => {
      if (held.size) return; // never switch axes while an arrow is held
      S.meter.axis = b.dataset.axis; prefs.set('meterAxis', S.meter.axis); renderMeterAxis(); renderMeterLive();
    });
    for (const id of ['#mNeg', '#mPos']) bindHold($(id, body), () => prefs.get('meterRate', 6));
    setupSeg($('#mSpeedSeg', body), 'meterRate', 6);
    $('#mClear', body).addEventListener('click', () => send({ type: 'slackMeterClear', axis: S.meter.axis }));
    $('#mSave', body).addEventListener('click', () => send({ type: 'slackMeterSave', axis: S.meter.axis }));
    renderMeterAxis();
    S.meterData = null;
    send({ type: 'slackMeter', on: true });
    renderMeterLive();
  }

  function renderMeterAxis() {
    const ax = AXIS_UI[S.meter.axis];
    $$('#mAxisSeg button').forEach((b) => b.classList.toggle('active', b.dataset.axis === S.meter.axis));
    for (const [id, dir] of [['#mNeg', 'neg'], ['#mPos', 'pos']]) {
      const b = $(id);
      b.dataset.axis = S.meter.axis;
      b.textContent = ax[dir];
      b.setAttribute('aria-label', ax[dir + 'Name']);
    }
    $('#mResTitle').textContent = `Holgura · ${ax.name.toLowerCase()}`;
  }

  /** Arrow (as drawn) that sends direction d to the server, honouring the "invert" settings. */
  const arrowFor = (axis, d) => AXIS_UI[axis][invert(axis, d > 0 ? 'pos' : 'neg')];

  function renderMeterLive() {
    if (!S.meter?.open || !$('#mState')) return;
    const m = S.meterData;
    const axis = S.meter.axis;
    const live = (m && m.live) || {};
    const a = (m && m.axes && m.axes[axis]) || { items: [], reversals: 0, saved: 0, loaded: 0 };
    const mine = live.axis === axis;
    const phase = mine ? live.phase : 'idle';
    const state = $('#mState');
    state.className = `meter-state ${phase}`;
    state.textContent = {
      idle: 'Pulsa una flecha y mantenla',
      slack: 'Tomando holgura… el motor gira, el tubo aún no',
      moving: '¡Se mueve! Ya puedes soltar',
      lost: 'El giroscopio no nota el giro: ¿está el Android apoyado en el tubo?',
    }[phase] || '';
    $('#mTravel').textContent = (mine ? live.travelDeg : 0).toFixed(2);
    const thr = live.thresholdDegS || 0.05;
    const scale = Math.max(thr * 2, 0.1);
    $('#mGyro').style.width = `${Math.min(100, ((live.gyroDegS || 0) / scale) * 100)}%`;
    $('#mThr').style.left = `${Math.min(100, (thr / scale) * 100)}%`;

    // What to do next: reverse the last move that loaded the gears.
    let hint;
    if (!S.open) hint = `Sin conexión con ${brainName()}`;
    else if (phase === 'slack') hint = 'Mantén pulsado hasta que ponga «¡Se mueve!»';
    else if (phase === 'moving') hint = `Suelta y mantén ahora ${arrowFor(axis, -live.dir)} (cambio de sentido)`;
    else if (phase === 'lost') hint = 'Suelta. Sujeta el Android al tubo y vuelve a probar.';
    else if (!a.loaded) hint = `Mantén ${arrowFor(axis, 1)} hasta que el tubo se mueva: así se tensan los engranajes`;
    else hint = `Ahora mantén ${arrowFor(axis, -a.loaded)} hasta que ponga «¡Se mueve!»`;
    $('#mHint').textContent = hint;

    const n = a.reversals;
    $('#mResVal').textContent = a.slackDeg !== undefined ? `${a.slackDeg.toFixed(2)}°` : '—';
    $('#mResSub').textContent = n
      ? `${n} medida${n === 1 ? '' : 's'}${n > 1 ? ` · ±${a.spreadDeg.toFixed(2)}°` : ''}${a.lagDeg ? ` · retardo ${a.lagDeg.toFixed(2)}°` : ''} · guardada ${a.saved.toFixed(2)}°`
      : `Haz al menos 3 cambios de sentido · guardada ${(a.saved || 0).toFixed(2)}°`;
    const kindLabel = { first: '1ª', same: 'mismo sentido', reversal: '' };
    $('#mItems').innerHTML = a.items.map((it) => `<span class="mchip ${it.kind}">${arrowFor(axis, it.dir)} ${it.deg.toFixed(2)}°${kindLabel[it.kind] ? ` <small>${kindLabel[it.kind]}</small>` : ''}</span>`).join('');
    const save = $('#mSave');
    save.disabled = a.slackDeg === undefined;
    save.textContent = a.slackDeg !== undefined ? `Guardar ${a.slackDeg.toFixed(2)}°` : 'Guardar';
  }

  function endMeter() {
    if (!S.meter) return;
    releaseAll(true);
    S.meter = null;
    send({ type: 'slackMeter', on: false });
  }

  // ---------------------------------------------------------------- GoTo method & diagnostics
  $$('#gotoSeg button').forEach((b) => b.addEventListener('click', () => send({ type: 'setSetting', key: 'gotoMethod', value: b.dataset.method })));
  $('#takeUp').addEventListener('change', (e) => send({ type: 'setSetting', key: 'manualTakeUp', value: String(e.target.checked) }));

  $('#diagOpen').addEventListener('click', () => {
    claimSheet();
    window.SB.openSheet(`<h3>Diagnóstico</h3>
      <p class="kind">Estado, avisos y lo último que se ha hablado con el mando. Si algo falla, ábrelo como texto y compártelo.</p>
      <pre id="diagText" class="diag mono">Cargando…</pre>
      <div class="sheet-actions">
        <a class="btn primary center" id="diagLink" target="_blank" rel="noopener">Abrir como texto</a>
        <button class="btn" id="diagRefresh">Actualizar</button>
        <button class="btn ghost" data-close>Cerrar</button>
      </div>`);
    S.diagOpen = true;
    $('#diagLink').href = `diag.txt?k=${encodeURIComponent(accessKey() || '')}`;
    $('#diagRefresh').addEventListener('click', () => send({ type: 'diag' }));
    send({ type: 'diag' });
  });
  function renderDiag(text) {
    const pre = $('#diagText');
    if (!pre) return;
    pre.textContent = text;
    pre.scrollTop = pre.scrollHeight; // the latest traffic is at the end
  }

  for (const [id, key] of [['#invertAz', 'invertAz'], ['#invertAlt', 'invertAlt']]) {
    $(id).checked = prefs.get(key, false);
    $(id).addEventListener('change', () => prefs.set(key, $(id).checked));
  }
  const night = $('#night');
  night.checked = prefs.get('night', true);
  const applyNight = () => { document.body.classList.toggle('night', night.checked); drawSky(); for (const fn of extra.night || []) fn(); };
  night.addEventListener('change', () => { prefs.set('night', night.checked); applyNight(); });

  // Sheet close (backdrop, grab handle, "Cerrar"): one delegated handler, valid in every state.
  $('#sheet').addEventListener('click', (e) => {
    if (!e.target.closest('[data-close]')) return;
    if (S.visual && ['preparing', 'watching'].includes(S.visual.phase)) send({ type: 'stop', axis: S.visual.axis });
    closeSheet();
    renderVisual();
  });

  // ---------------------------------------------------------------- API for imaging.js
  window.SB = {
    S, $, $$, send, prefs, toast, esc, showTab, makePad, setupSeg, accessKey, releaseAll, applyUse, drawSky, renderResults,
    on(type, fn) { (extra[type] = extra[type] || []).push(fn); },
    /** Opens the shared bottom sheet with custom content (closing object/visual sheets). */
    openSheet(html, keepScroll = false) {
      if (!keepScroll) { endMeter(); S.diagOpen = false; }
      S.sheetObj = null; S.visual = null;
      const sheet = $('#sheet');
      sheet.classList.remove('hidden'); sheet.setAttribute('aria-hidden', 'false');
      const panel = $('.sheet-panel');
      const top = panel.scrollTop;
      // Keep an open <details> open across refreshes.
      const open = Array.from($('#sheetBody').querySelectorAll('details[open] summary')).map((s) => s.textContent.split('(')[0]);
      $('#sheetBody').innerHTML = html;
      if (keepScroll) {
        $('#sheetBody').querySelectorAll('details summary').forEach((s) => { if (open.includes(s.textContent.split('(')[0])) s.parentElement.open = true; });
        panel.scrollTop = top;
      } else panel.scrollTop = 0;
      return $('#sheetBody');
    },
    closeSheet,
    sheetOpen: () => !$('#sheet').classList.contains('hidden'),
  };

  // ---------------------------------------------------------------- boot
  makePad($('#pad'), () => prefs.get('rate', 6));
  makePad($('#alignPad'), () => prefs.get('alignRate', 4));
  setupSeg($('#speedSeg'), 'rate', 6);
  setupSeg($('#alignSpeedSeg'), 'alignRate', 4);
  updateSkyTools();
  applyNight();
  renderPointing(); renderResults(); renderAlign(); renderMore();
  showTab(prefs.get('tab', 'sky'));
  applyUse();
  // imaging.js registers its handlers right after this script runs; connect afterwards.
  setTimeout(connect, 0);
})();
