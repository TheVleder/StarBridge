// StarBridge for the computer. Same brain and WebSocket API as the phone page, more room and
// more control: keyboard, any speed, big sky map, planner, alignment table, live charts,
// slack tools and a console. No frameworks, no network dependencies.
(() => {
  'use strict';

  const $ = (s, root = document) => root.querySelector(s);
  const $$ = (s, root = document) => Array.from(root.querySelectorAll(s));
  const HOLD_MS = 250;
  const SIDEREAL = 15.041; // arcsec/s

  // ------------------------------------------------------------------ icons (inline, offline)
  const ICONS = {
    sky: '<circle cx="12" cy="12" r="9"/><path d="M9 9l1.2 2.6L13 12l-2.8.4L9 15l-.8-2.6L5.5 12l2.7-.4z"/>',
    list: '<path d="M8 6h12M8 12h12M8 18h12"/><circle cx="4" cy="6" r="1"/><circle cx="4" cy="12" r="1"/><circle cx="4" cy="18" r="1"/>',
    target: '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/>',
    chart: '<path d="M3 20h18M5 16l4-5 4 3 6-8"/>',
    gears: '<circle cx="9" cy="10" r="4"/><path d="M9 4v2M9 14v2M3 10h2M13 10h2"/><circle cx="17" cy="17" r="3"/>',
    terminal: '<rect x="3" y="4" width="18" height="16" rx="3"/><path d="M7 9l3 3-3 3M12 15h5"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/>',
    up: '<path d="M6 15l6-6 6 6"/>', down: '<path d="M6 9l6 6 6-6"/>',
    left: '<path d="M15 6l-6 6 6 6"/>', right: '<path d="M9 6l6 6-6 6"/>',
    moon: '<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/>',
    windows: '<rect x="3" y="7" width="13" height="11" rx="2"/><path d="M8 7V5a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-3"/>',
    dpad: '<path d="M9 3h6v6h6v6h-6v6H9v-6H3V9h6z"/>',
    camera: '<path d="M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z"/><circle cx="12" cy="13" r="3.5"/>',
  };
  const icon = (name) => `<svg class="ic" viewBox="0 0 24 24" aria-hidden="true">${ICONS[name] || ''}</svg>`;
  $$('[data-icon]').forEach((el) => { el.outerHTML = icon(el.dataset.icon); });
  $('#nightBtn').innerHTML = icon('moon');
  $('#winBtn').innerHTML = icon('windows');
  $('#padToggle').innerHTML = icon('dpad');

  // ------------------------------------------------------------------ this window
  // ?win=<section> opens one section alone (several windows, arranged by the user); without it,
  // the full panel. Every window is its own connection with its own STOP.
  const WIN_NAMES = {
    sky: 'Cielo', objects: 'Objetos', photos: 'Cámara y fotos', control: 'Mando', align: 'Alineación',
    tracking: 'Seguimiento', slack: 'Holgura', console: 'Consola', settings: 'Ajustes',
  };
  const WIN = (() => { const w = new URLSearchParams(location.search).get('win'); return w && WIN_NAMES[w] ? w : null; })();
  if (WIN) {
    document.body.classList.add('solo');
    if (WIN === 'control') document.body.classList.add('solo-control');
    document.title = `${WIN_NAMES[WIN]} · StarBridge`;
    $('.app-name').textContent = WIN_NAMES[WIN];
  }

  // Opened from another brain's panel to drive this one (see "other brains on the WiFi").
  const FROM = (() => {
    const f = new URLSearchParams(location.search).get('from');
    return f && /^https?:\/\/[^\s/?#]+\/desk\.html(\?[^\s#]*)?$/.test(f) ? f : null;
  })();
  const KIND_NAME = { android: 'el Android', pc: 'el PC' };

  // ------------------------------------------------------------------ preferences
  const prefs = {
    get(k, d) { try { const v = localStorage.getItem('sbd.' + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem('sbd.' + k, JSON.stringify(v)); } catch { /* private mode */ } },
  };

  // ------------------------------------------------------------------ state
  const S = {
    ws: null, open: false, clientId: null,
    info: {}, status: {}, alignment: { points: [], suggestions: [] }, sky: null,
    view: WIN && WIN !== 'control' ? WIN : 'sky', selected: null, objects: {}, nights: {}, results: [], category: 'best', query: '',
    activeStar: null, visual: null, backlash: null,
    trackHist: [], tracePaused: false,
    photos: [], photoSel: null, photoUnread: 0,
    cams: {}, camExpOpen: false,
    zones: [], zoneEdit: null,
  };

  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const pad2 = (n) => String(n).padStart(2, '0');
  const fmtRA = (h) => {
    if (h == null) return '—';
    let s = Math.round(h * 3600); s = ((s % 86400) + 86400) % 86400;
    return `${Math.floor(s / 3600)}h ${pad2(Math.floor(s / 60) % 60)}m ${pad2(s % 60)}s`;
  };
  const fmtDec = (d) => {
    if (d == null) return '—';
    const sign = d < 0 ? '−' : '+'; const a = Math.round(Math.abs(d) * 3600);
    return `${sign}${Math.floor(a / 3600)}° ${pad2(Math.floor(a / 60) % 60)}′ ${pad2(a % 60)}″`;
  };
  const fmtDeg = (d, n = 1) => (d == null ? '—' : `${d.toFixed(n)}°`);
  const fmtTime = (ms) => { if (!ms) return '—'; const d = new Date(ms); return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`; };
  const fmtLst = (h) => { if (h == null) return '—'; const s = Math.round(h * 3600); return `${pad2(Math.floor(s / 3600) % 24)}:${pad2(Math.floor(s / 60) % 60)}:${pad2(s % 60)}`; };
  const CAT_ICON = { planet: '◉', moon: '☾︎', star: '✦︎', galaxy: '⬬', nebula: '▣', cluster: '⁂', other: '◌' };
  const titleOf = (o) => (o.name ? `${o.id} · ${o.name}` : o.id);
  const liveStyle = getComputedStyle(document.body); // live: follows the night mode
  const cssVar = (n) => liveStyle.getPropertyValue(n).trim();

  // ------------------------------------------------------------------ toasts and modal
  function toast(text, error = false) {
    const t = document.createElement('div');
    t.className = 'toast' + (error ? ' error' : '');
    t.textContent = text;
    $('#toasts').appendChild(t);
    setTimeout(() => t.remove(), error ? 6000 : 3800);
    while ($('#toasts').children.length > 4) $('#toasts').firstChild.remove();
  }
  function modal(html) {
    $('#modalCard').innerHTML = html;
    $('#modal').classList.remove('hidden');
    return $('#modalCard');
  }
  function closeModal() { $('#modal').classList.add('hidden'); }
  $('#modal').addEventListener('click', (e) => { if (e.target.id === 'modal' || e.target.closest('[data-close]')) closeModal(); });
  function confirmBox(title, text, okLabel = 'Continuar') {
    return new Promise((resolve) => {
      const card = modal(`<h2>${esc(title)}</h2><p class="muted">${esc(text)}</p>
        <div class="row-actions" style="justify-content:flex-end"><button class="btn ghost" data-close>Cancelar</button><button class="btn primary" id="okBtn">${esc(okLabel)}</button></div>`);
      $('#okBtn', card).addEventListener('click', () => { closeModal(); resolve(true); });
      $('[data-close]', card).addEventListener('click', () => resolve(false));
    });
  }

  // ------------------------------------------------------------------ access key and socket
  function accessKey() {
    const fromUrl = new URLSearchParams(location.search).get('k');
    if (fromUrl) { prefs.set('key', fromUrl); return fromUrl; }
    return prefs.get('key', null);
  }
  $('#codeForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const code = $('#codeInput').value.toLowerCase().replace(/[^a-z0-9]/g, '');
    if (code.length < 8) return;
    prefs.set('key', code);
    if (new URLSearchParams(location.search).get('k')) history.replaceState(null, '', location.pathname);
    connect();
  });

  function connect() {
    const key = accessKey();
    if (!key) { $('#locked').classList.remove('hidden'); return; }
    let ws;
    try { ws = new WebSocket(`ws://${location.host}/ws?k=${encodeURIComponent(key)}`); } catch { setTimeout(connect, 2000); return; }
    S.ws = ws;
    ws.onopen = () => { S.open = true; S.lostSince = 0; $('#locked').classList.add('hidden'); renderTop(); requestView(); requestSky(); };
    ws.onclose = (e) => {
      S.open = false; releaseAll(false); renderTop();
      if (!S.lostSince) S.lostSince = Date.now();
      if (e.code === 4401) { prefs.set('key', null); $('#locked').classList.remove('hidden'); return; }
      setTimeout(connect, 1500);
    };
    ws.onerror = () => ws.close();
    ws.onmessage = (e) => {
      let m; try { m = JSON.parse(e.data); } catch { return; }
      const h = handlers[m.type];
      if (h) { try { h(m); } catch (err) { console.error(err); } }
    };
  }
  /** The computer's map: constellation figures and every deep-sky object. */
  const requestSky = () => send({ type: 'sky', lines: true, deep: true });

  function send(obj) {
    if (S.ws && S.ws.readyState === WebSocket.OPEN) { S.ws.send(JSON.stringify(obj)); return true; }
    return false;
  }

  let pendingRaw = null;
  const handlers = {
    info(m) {
      if (m.clientId) S.clientId = m.clientId;
      S.info = m; // complete every time: a disconnected telescope drops its fields
      renderTop(); renderSettings(); renderSlack(); renderAlign(); renderLevel();
      maybeWelcome();
    },
    status(m) {
      S.status = m;
      // One point per tracking correction (the status repeats the last one every second).
      const lastPoint = S.trackHist[S.trackHist.length - 1];
      if (m.track && (!lastPoint || lastPoint.t !== m.track.at)) {
        S.trackHist.push({ t: m.track.at, ...m.track });
        if (S.trackHist.length > 900) S.trackHist.shift();
      }
      renderTop(); renderInspector(); renderStatusBar(); renderGear();
      if (S.view === 'sky') drawSky();
      if (S.view === 'tracking') renderTracking();
    },
    alignment(m) {
      S.alignment = m;
      // The star just centred is in: done with it.
      const p = S.pendingAlign;
      if (p && (m.points || []).some((x) => x.label === p.id)) {
        if (S.activeStar?.id === p.id) S.activeStar = null;
        S.pendingAlign = null;
        // Guided flow: the next star comes up by itself until it is enough.
        if (S.alignFlow && (m.stars || 0) < (m.level ? 1 : 2)) {
          S.activeStar = (m.suggestions || []).find((s) => s.alt > 0) || null;
          toast(`${p.id} añadida. Ahora ${S.activeStar ? S.activeStar.id : 'otra estrella'}, lejos de la primera.`);
        } else {
          if (S.alignFlow) toast('¡Alineado! Ya puedes ir a cualquier objeto.');
          else toast(`${p.id} añadida a la alineación`);
          S.alignFlow = false;
        }
      }
      renderTop(); renderAlign(); renderLevel(); if (!S.center?.active) renderCenter();
    },
    // Leveling with the iPhone: its progress and result, for every page.
    level(m) {
      S.level = m;
      if (m.phase === 'done') toast(m.message);
      if (m.phase === 'failed') toast(`Nivelación: ${m.message}`, true);
      renderLevel();
    },
    // Centred with the last touch against the way the GoTo arrives: say which arrows to finish with.
    alignFinish(m) {
      const star = S.pendingAlign && S.pendingAlign.id === m.id ? S.pendingAlign : { id: m.id };
      S.pendingAlign = null;
      const need = [['azm', 'acimut'], ['alt', 'altura']].filter(([k]) => m[k] != null);
      const arrows = need.map(([k, name]) => `<b class="arrow-big">${arrowFor(k, m[k])}</b> en ${name}`).join(' y ');
      const away = need.map(([k]) => `<b>${arrowFor(k, -m[k])}</b>`).join(' y ');
      const card = modal(`<h2>Termina de centrar con ${need.map(([k]) => arrowFor(k, m[k])).join(' ')}</h2>
        <p>El GoTo llega a ${esc(m.id)} moviéndose con ${arrows}. Si el último toque va en ese mismo sentido, la holgura de los engranajes no influye en la alineación.</p>
        <p class="muted">Pásate un poco con ${away} y vuelve a centrarla terminando con ${arrows}. Luego pulsa otra vez «Está centrada».</p>
        <div class="row-actions" style="justify-content:flex-end"><button class="btn ghost" id="afForce">Añadir igualmente</button><button class="btn primary" data-close>La vuelvo a centrar</button></div>`);
      $('#afForce', card).addEventListener('click', () => { closeModal(); addAlignmentStar(star, true); });
    },
    sky(m) {
      S.sky = m;
      if (S.selected) send({ type: 'object', id: S.selected.id }); // its altitude changes too
      drawSky();
    },
    searchResult(m) { S.results = m.items; renderResults(); },
    object(m) { S.objects[m.id] = m; if (S.selected && S.selected.id === m.id) { S.selected = m; renderDetail(); renderMapCard(); } },
    objectNight(m) { S.nights[m.id] = m; if (S.selected && S.selected.id === m.id) renderDetail(); },
    backlash(m) { S.backlash = m; renderNative(); },
    visualCal(m) { S.visual = m; renderVisual(); },
    // Photos from the iPhone app (StarBridge EAA), shared through the brain: the running stack
    // ("live-<source>", replaced every ~10 s, v increments) and the saved ones ("p1", "p2"…).
    photos(m) {
      S.photos = sortPhotos(m.items || []);
      if (!S.photos.some((p) => p.id === S.photoSel)) S.photoSel = S.photos[0]?.id ?? null;
      renderPhotos();
    },
    photo(m) {
      const p = Object.assign({}, m);
      delete p.type;
      const known = S.photos.some((x) => x.id === p.id);
      S.photos = sortPhotos(S.photos.filter((x) => x.id !== p.id).concat([p]));
      if (prefs.get('photoFollow', true) || !S.photoSel) S.photoSel = p.id;
      // Saved photos are news; a live stack only when it starts (it refreshes every few seconds).
      if (S.view !== 'photos' && (!p.live || !known)) {
        S.photoUnread++;
        toast(p.live ? `El iPhone está apilando${p.label ? ' ' + p.label : ''}: míralo en Fotos` : `Nueva foto del iPhone${p.label ? ': ' + p.label : ''}`);
      }
      renderPhotos();
    },
    // The iPhone camera driven from here: its status (relayed by the brain), and its notices.
    camStatus(m) {
      const src = m.source || 'iphone';
      const first = !S.cams[src] || S.cams[src].online === false;
      S.cams[src] = m.online === false ? { source: src, online: false, name: S.cams[src]?.name } : m;
      if (m.online !== false) camNews(m, first);
      renderCam();
      if (first || m.online === false) renderLevel(); // the iPhone came or went: the level button
    },
    camNotice(m) { toast(m.text || ''); },
    // Visible zones (shared by every page): the map shades what is outside, lists say inZone.
    zones(m) {
      S.zones = m.zones || [];
      if (S.zoneEdit?.id && !S.zones.some((z) => z.id === S.zoneEdit.id)) endZoneEdit();
      $('#onlyZoneWrap').classList.toggle('hidden', !S.zones.some((z) => z.on));
      renderZonePanel(); drawSky();
      // Every object's inZone may have changed.
      if (S.view === 'objects') search();
      if (S.selected) { send({ type: 'object', id: S.selected.id }); send({ type: 'objectNight', id: S.selected.id }); }
      if (S.view === 'align') send({ type: 'alignState' });
    },
    slackCenter(m) { S.centerPrev = S.center; S.center = m; renderCenter(); },
    trace(m) { renderTrace(m.lines); },
    raw(m) { pendingRaw = null; $('#rawOut').textContent = `→ ${m.sent}\n← ${m.text || '(vacía)'}   [${m.answer.join(' ')}]   ${m.ms} ms`; },
    diag(m) { modal(`<h2>Diagnóstico</h2><pre>${esc(m.text)}</pre><div class="row-actions" style="justify-content:flex-end"><button class="btn" data-close>Cerrar</button></div>`); },
    event(m) { toast(m.message); },
    error(m) {
      if (pendingRaw && /confírmala/.test(m.message)) { askRawConfirm(); return; }
      toast(m.message, true);
    },
  };

  // ------------------------------------------------------------------ views
  const VIEWS = ['sky', 'objects', 'photos', 'align', 'tracking', 'slack', 'console', 'settings'];

  function showView(name) {
    releaseAll(true);
    S.view = name;
    if (!WIN) prefs.set('view', name); // a section window must not change what the main one opens
    $$('.sidebar > button').forEach((b) => b.classList.toggle('active', b.dataset.view === name));
    $$('.view').forEach((v) => v.classList.toggle('active', v.id === `view-${name}`));
    requestView();
    if (name === 'sky') requestAnimationFrame(drawSky);
    if (name === 'tracking') requestAnimationFrame(renderTracking);
    if (name === 'objects' && S.selected) requestAnimationFrame(renderDetail); // charts need a visible size
    if (name === 'slack') { renderSlack(); renderGear(); renderCenter(); }
    if (name === 'photos') { S.photoUnread = 0; renderPhotos(); renderCam(); }
    if (name === 'align') renderLevel();
  }
  function requestView() {
    if (!S.open) return;
    if (S.view === 'sky') requestSky();
    if (S.view === 'objects') search();
    if (S.view === 'align') send({ type: 'alignState' });
    if (S.view === 'slack' && S.status.connected) send({ type: 'getBacklash' });
    if (S.view === 'console') send({ type: 'trace' });
    if (S.view === 'photos') send({ type: 'photos' });
  }
  $$('.sidebar > button').forEach((b) => b.addEventListener('click', () => showView(b.dataset.view)));
  $$('[data-view-link]').forEach((b) => b.addEventListener('click', () => showView(b.dataset.viewLink)));
  setInterval(() => { if (S.open && S.view === 'sky' && !document.hidden) requestSky(); }, 60_000);

  // ------------------------------------------------------------------ toolbar and status bar
  function renderTop() {
    const st = S.status, i = S.info;
    const connected = !!(st.connected || i.connected);
    $('#connDot').className = 'dot' + (S.open && connected ? ' on' : S.open ? ' half' : '');
    $('#connText').textContent = !S.open ? 'Sin conexión con el programa'
      : !connected ? 'Telescopio no conectado'
      : `${i.model === 7 ? 'NexStar SLT' : 'NexStar'} · mando ${i.hcVersion || '?'} · ${(st.mode || i.mode) === 'hc' ? 'alineación del mando' : 'modo StarBridge'}`;
    const a = S.alignment, mode = st.mode || i.mode;
    const pill = $('#alignPill');
    if (mode === 'hc') { pill.className = 'pill ' + (i.hcAligned ? 'ok' : 'warn'); pill.textContent = i.hcAligned ? 'Mando alineado' : 'Mando sin alinear'; }
    else if (!a.aligned) { pill.className = 'pill warn'; pill.textContent = 'Sin alinear'; }
    else if (a.sensorOnly) { pill.className = 'pill warn'; pill.textContent = 'Alineación aproximada'; }
    else { pill.className = 'pill ok'; pill.textContent = `Alineado · ${a.stars} ★${a.stars >= 3 ? ` · ${a.rmsArcmin}′` : ''}`; }
    if (S.open && !connected) pill.classList.add('hidden'); // nothing to align on this brain
    const sp = $('#statePill');
    if (st.goto) { sp.className = 'pill busy'; sp.textContent = 'GoTo en curso…'; }
    else if (st.tracking) { sp.className = 'pill ok'; sp.textContent = `Siguiendo ${st.trackingLabel || ''}${st.trackRate && st.trackRate !== 'sidereal' ? ' · ' + st.trackRate : ''}`; }
    else sp.className = 'pill hidden';
    renderPeers();
  }
  // Language (i18n.js): the page reloads in the chosen one.
  $$('#langSeg button').forEach((b) => {
    b.classList.toggle('active', b.dataset.l === (window.I18N ? I18N.lang : 'es'));
    b.addEventListener('click', () => window.I18N && I18N.set(b.dataset.l));
  });


  // ------------------------------------------------------------------ the PC program: COM port, welcome
  handlers.pcState = (m) => { S.pc = m; renderPcPort(); };
  function renderPcPort() {
    const pc = S.pc;
    const box = $('#pcPort');
    if (!pc) { box.classList.add('hidden'); $('#portPill').classList.add('hidden'); return; }
    const ports = pc.ports || [];
    const choose = !pc.connected && ports.length > 1 && /elige/i.test(pc.message || '');
    $('#portPill').classList.toggle('hidden', !choose);
    box.classList.remove('hidden');
    box.innerHTML = `<div class="set-title">Puerto del cable</div>
      ${ports.length ? `<div class="row-actions" style="margin-top:4px">${ports.map((p) => `<button class="btn ${p.name === (pc.port || pc.preferred) ? 'primary' : ''}" data-port="${esc(p.name)}" title="${esc(p.description)}">${esc(p.name)}</button>`).join('')}</div>`
        : '<p class="muted">No hay ningún puerto COM: enchufa el cable (y su driver).</p>'}
      ${pc.message ? `<p class="muted">${esc(pc.message)}</p>` : ''}`;
    $$('[data-port]', box).forEach((b) => b.addEventListener('click', () => send({ type: 'pcSetPort', name: b.dataset.port })));
  }
  $('#portPill').addEventListener('click', () => showView('settings'));

  /** First run of the PC program: what is needed, once (and from Ajustes › Acerca de). */
  function showWelcome() {
    const card = modal(`<div class="welcome">
      <img src="icon-192.png" alt="" class="logo big">
      <h2>Bienvenido a StarBridge</h2>
      <p class="muted">Este PC será el cerebro del telescopio. Esto es todo lo que necesita:</p>
      <ol class="flow">
        <li><b>El driver del cable.</b> Los cables PL2303 necesitan el driver de Prolific (los FTDI, CP210x y CH34x suelen instalarse solos). Windows lo busca al enchufarlo; si no, descárgalo de la web del fabricante.</li>
        <li><b>Enchufa el cable al mando NexStar y enciende el telescopio.</b> StarBridge encuentra el puerto solo; si hay varios, te dejará elegirlo en Ajustes.</li>
        <li><b>Permite el acceso en el cortafuegos de Windows</b> (redes privadas) cuando lo pida: así el iPhone puede conectarse.</li>
        <li><b>Conecta el iPhone o un Android</b> a la misma WiFi y escanea el QR de Ajustes › Conectar el iPhone.</li>
        <li><b>StarBridge sigue funcionando junto al reloj.</b> Para salir, usa su icono: para el telescopio antes de cerrar.</li>
      </ol>
      <div class="row-actions" style="justify-content:flex-end"><button class="btn primary" data-close>Empezar</button></div>
    </div>`);
    $('[data-close]', card).addEventListener('click', () => { prefs.set('welcomed', true); closeModal(); });
  }
  $('#welcomeAgain').addEventListener('click', showWelcome);
  // Only in the PC program, once, after the language choice (i18n.js) if that is pending.
  function maybeWelcome() {
    if (prefs.get('welcomed', false) || S.info.brainKind !== 'pc' || WIN || FROM) return;
    if (document.getElementById('sbLangPick')) { setTimeout(maybeWelcome, 800); return; }
    prefs.set('welcomed', true);
    showWelcome();
  }

  // ------------------------------------------------------------------ other brains on the WiFi
  // Each brain (this PC program, the Android) announces itself on the network. If the telescope
  // is on another one, the toolbar offers to drive it: this window opens its panel with
  // ?from=<this panel>, and "Volver" (or losing it for a while) comes back here.
  if (FROM) $('#locked p').textContent = 'Escribe el código de acceso que aparece en la pantalla del Android (se recuerda para la próxima vez).';
  handlers.peers = (m) => { S.peers = m.items || []; S.selfKind = m.selfKind; renderPeers(); };
  function renderPeers() {
    const here = !!(S.status.connected || S.info.connected);
    const other = S.open && !here ? (S.peers || []).find((p) => p.telescope) : null;
    const btn = $('#peerPill');
    btn.classList.toggle('hidden', !other);
    if (other) {
      btn.textContent = `El telescopio está en ${other.name} · Usarlo`;
      btn.dataset.url = other.url;
    }
    const home = $('#homePill');
    home.classList.toggle('hidden', !FROM);
    if (FROM) home.textContent = `Manejando ${KIND_NAME[S.selfKind] || 'otro StarBridge'} · Volver al PC`;
  }
  $('#peerPill').addEventListener('click', () => {
    const url = $('#peerPill').dataset.url;
    if (!url) return;
    const back = location.origin + location.pathname + (WIN ? `?win=${WIN}` : '');
    location.href = `${url}/desk.html?from=${encodeURIComponent(back)}${WIN ? `&win=${WIN}` : ''}`;
  });
  $('#homePill').addEventListener('click', () => { if (FROM) location.href = FROM; });
  // Driving another brain that went away (switched off, out of the WiFi): back to this PC.
  if (FROM) setInterval(() => { if (S.lostSince && Date.now() - S.lostSince > 20_000) location.href = FROM; }, 2_000);

  function renderStatusBar() {
    const site = S.info.site || {}, st = S.status;
    const src = { gps: 'GPS', manual: 'manual', hand_control: 'del mando', default: 'sin fijar' }[site.source] || '';
    $('#sbSite').textContent = site.lat != null ? `${Math.abs(site.lat).toFixed(4)}° ${site.lat >= 0 ? 'N' : 'S'}  ${Math.abs(site.lon).toFixed(4)}° ${site.lon >= 0 ? 'E' : 'O'} (${src})` : '';
    const now = st.time ? new Date(st.time) : new Date();
    $('#sbTime').textContent = `${pad2(now.getHours())}:${pad2(now.getMinutes())}:${pad2(now.getSeconds())} · UTC ${pad2(now.getUTCHours())}:${pad2(now.getUTCMinutes())}`;
    $('#sbLst').textContent = st.lst != null ? `Tiempo sidéreo ${fmtLst(st.lst)}` : '';
    $('#sbLink').textContent = st.link ? `Cable ${st.link.ms} ms · ${st.link.errors} errores` : (st.connected ? '' : 'Sin telescopio');
  }

  // ------------------------------------------------------------------ inspector: pointing
  function renderInspector() {
    const st = S.status;
    const known = st.alt !== undefined;
    if (!st.goto) S.gotoLabel = null;
    $('#pointLabel').textContent = st.goto ? 'GoTo en curso' : st.tracking ? 'Siguiendo' : 'Apuntando';
    $('#pointTitle').textContent = !S.open ? 'Sin conexión' : !st.connected ? 'Telescopio no conectado'
      : st.goto && S.gotoLabel ? S.gotoLabel
      : st.tracking && st.trackingLabel ? st.trackingLabel : known ? `Al ${st.dir}` : 'Posición desconocida';
    $('#coords').innerHTML = `
      <div><span>Ascensión recta</span>${fmtRA(st.ra)}</div><div><span>Declinación</span>${fmtDec(st.dec)}</div>
      <div><span>Acimut</span>${fmtDeg(st.az, 2)}</div><div><span>Altura</span>${fmtDeg(st.alt, 2)}</div>
      <div><span>Encoder Az</span>${fmtDeg(st.raw?.az, 3)}</div><div><span>Encoder Alt</span>${fmtDeg(st.raw?.alt, 3)}</div>`;
    const tg = $('#trackToggle');
    if (document.activeElement !== tg) tg.checked = !!st.tracking;
    $('#trackSub').textContent = st.tracking ? `${st.trackingLabel || 'posición actual'}${st.trackRate ? ' · ' + ({ sidereal: 'sideral', lunar: 'lunar', solar: 'solar' }[st.trackRate] || '') : ''}` : 'Compensa la rotación del cielo';
  }

  // ------------------------------------------------------------------ moving: pad, keyboard, speed
  const held = new Map(); // axis -> {timer, el}
  let speedArcsec = prefs.get('speed', 1800);
  const sliderToArcsec = (v) => Math.round(Math.pow(10, (v / 1000) * Math.log10(16000)) * 10) / 10;
  const arcsecToSlider = (a) => Math.round((Math.log10(Math.max(1, a)) / Math.log10(16000)) * 1000);
  function speedLabel(a) {
    if (a >= 12000) return 'máxima del motor'; // the brain uses the hand control's rate 9 there
    if (a < 360) return `${Math.round(a)}″/s · ${(a / SIDEREAL).toFixed(a < 60 ? 1 : 0)}× sideral`;
    return `${(a / 3600).toFixed(a < 3600 ? 2 : 1)}°/s`;
  }
  function setSpeed(a) {
    speedArcsec = Math.max(1, Math.min(16000, a));
    prefs.set('speed', speedArcsec);
    $('#speed').value = arcsecToSlider(speedArcsec);
    $('#speedText').textContent = speedLabel(speedArcsec);
    $$('#presets button').forEach((b) => b.classList.toggle('active', Math.abs(Number(b.dataset.arcsec) - speedArcsec) < 1));
  }
  $('#speed').addEventListener('input', (e) => setSpeed(sliderToArcsec(Number(e.target.value))));
  $$('#presets button').forEach((b) => b.addEventListener('click', () => setSpeed(Number(b.dataset.arcsec))));

  function invert(axis, dir) {
    const inv = axis === 'azm' ? prefs.get('invertAz', false) : prefs.get('invertAlt', false);
    return inv ? (dir === 'pos' ? 'neg' : 'pos') : dir;
  }
  function press(axis, dir, el, fast = false) {
    release(axis, true);
    if (S.status.controller && S.status.controller !== S.clientId) send({ type: 'takeControl' });
    const arcsec = Math.min(16000, speedArcsec * (fast ? 4 : 1));
    if (!send({ type: 'slew', axis, dir: invert(axis, dir), arcsec })) { toast('Sin conexión con el programa', true); return; }
    el?.classList.add('pressed');
    const timer = setInterval(() => send({ type: 'hold', axis }), HOLD_MS);
    held.set(axis, { timer, el });
  }
  function release(axis, sendStop) {
    const h = held.get(axis);
    if (!h) return;
    clearInterval(h.timer);
    h.el?.classList.remove('pressed');
    held.delete(axis);
    if (sendStop) send({ type: 'stop', axis });
  }
  function releaseAll(sendStop = true) { for (const a of Array.from(held.keys())) release(a, sendStop); }

  $$('#pad .dir').forEach((el) => {
    el.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      try { el.setPointerCapture(e.pointerId); } catch { /* ignore */ }
      press(el.dataset.axis, el.dataset.dir, el, e.shiftKey);
    });
    for (const ev of ['pointerup', 'pointercancel', 'lostpointercapture']) {
      el.addEventListener(ev, () => { if (held.get(el.dataset.axis)?.el === el) release(el.dataset.axis, true); });
    }
  });
  function stopAll() {
    releaseAll(false);
    const b = $('#stopBtn'); b.classList.remove('flash'); void b.offsetWidth; b.classList.add('flash');
    if (!send({ type: 'stopAll' })) toast('Sin conexión con el programa', true);
  }
  $('#stopBtn').addEventListener('pointerdown', (e) => { e.preventDefault(); stopAll(); });
  $('#padStop').addEventListener('pointerdown', (e) => { e.preventDefault(); stopAll(); });

  const KEYS = { ArrowUp: ['alt', 'pos'], ArrowDown: ['alt', 'neg'], ArrowLeft: ['azm', 'neg'], ArrowRight: ['azm', 'pos'] };
  const PAD_EL = { ArrowUp: '#pad .up', ArrowDown: '#pad .down', ArrowLeft: '#pad .left', ArrowRight: '#pad .right' };
  const typing = (e) => e.target.closest && e.target.closest('input, textarea, select');
  document.addEventListener('keydown', (e) => {
    if (e.key === ' ' || e.key === 'Escape') {
      if (e.key === 'Escape' && !$('#modal').classList.contains('hidden')) { closeModal(); return; }
      if (e.key === 'Escape' && !$('#winMenu').classList.contains('hidden')) { closeWinMenu(); return; }
      if (e.key === 'Escape' && S.zoneEdit) { endZoneEdit(); return; }
      if (typing(e) && e.key === ' ') return;
      e.preventDefault(); stopAll(); return;
    }
    if (typing(e)) return;
    if (S.zoneEdit && e.key === 'Enter') { e.preventDefault(); finishZoneEdit(); return; }
    if (S.zoneEdit && e.key === 'Backspace' && S.zoneEdit.mode !== 'edit') { e.preventDefault(); S.zoneEdit.points.pop(); drawSky(); renderZoneBar(); return; }
    if (KEYS[e.key]) {
      e.preventDefault();
      if (e.repeat) return;
      const [axis, dir] = KEYS[e.key];
      press(axis, dir, $(PAD_EL[e.key]), e.shiftKey);
      return;
    }
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    const k = e.key.toLowerCase();
    if (k >= '1' && k <= '8') { if (WIN !== 'control') showView(VIEWS[Number(k) - 1]); }
    else if (S.view === 'photos' && (k === ',' || k === '.')) stepPhoto(k === ',' ? -1 : 1);
    else if (S.view === 'photos' && k === 'z') photoFullscreen();
    else if (k === '+') setSpeed(speedArcsec * 1.6);
    else if (k === '-') setSpeed(speedArcsec / 1.6);
    else if (k === 't') send({ type: 'track', on: !S.status.tracking, rate: prefs.get('trackRate', 'sidereal') });
    else if (k === 'g' && S.selected) goTo(S.selected);
    else if (k === 'f') { e.preventDefault(); showView('objects'); $('#q').focus(); }
    else if (k === 'n') toggleNight();
  });
  document.addEventListener('keyup', (e) => {
    if (KEYS[e.key]) { const [axis] = KEYS[e.key]; if (held.get(axis)?.el === $(PAD_EL[e.key]) || held.has(axis)) release(axis, true); }
  });
  const panic = () => { if (held.size) releaseAll(true); };
  window.addEventListener('blur', panic);
  document.addEventListener('visibilitychange', () => { if (document.hidden) panic(); else requestView(); });
  for (const ev of ['pointerup', 'pointercancel']) {
    document.addEventListener(ev, () => { for (const [axis, h] of Array.from(held.entries())) if (h.el && !h.el.isConnected) release(axis, true); });
  }

  // ------------------------------------------------------------------ tracking, coordinates, park
  $('#trackToggle').addEventListener('change', (e) => send({ type: 'track', on: e.target.checked, rate: prefs.get('trackRate', 'sidereal') }));
  function renderRateSeg() { $$('#rateSeg button').forEach((b) => b.classList.toggle('active', b.dataset.rate === prefs.get('trackRate', 'sidereal'))); }
  $$('#rateSeg button').forEach((b) => b.addEventListener('click', () => {
    prefs.set('trackRate', b.dataset.rate); renderRateSeg();
    if (S.status.tracking && (S.status.trackingLabel === 'posición actual')) send({ type: 'track', on: true, rate: b.dataset.rate });
  }));
  $('#cancelGoto').addEventListener('click', () => send({ type: 'cancelGoto' }));
  $('#parkBtn').addEventListener('click', async () => {
    if (await confirmBox('Aparcar el telescopio', 'Vuelve a la posición en la que se encendió. Después puedes apagarlo y la próxima vez arrancará desde ahí.', 'Aparcar')) send({ type: 'park' });
  });
  let coordKind = 'radec';
  $$('#coordSeg button').forEach((b) => b.addEventListener('click', () => {
    coordKind = b.dataset.kind;
    $$('#coordSeg button').forEach((x) => x.classList.toggle('active', x === b));
    $('#coordRadec').classList.toggle('hidden', coordKind !== 'radec');
    $('#coordAltaz').classList.toggle('hidden', coordKind !== 'altaz');
    $('#coordErr').classList.add('hidden');
  }));
  /** "0 42 44", "0h42m44s", "0:42:44" or "0.712" → number; sign kept for Dec. */
  function parseSexa(text) {
    const t = String(text).trim();
    if (!t) return null;
    const neg = /^[-−]/.test(t);
    const parts = t.replace(/^[+\-−]/, '').split(/[^0-9.]+/).filter(Boolean).map(Number);
    if (!parts.length || parts.some(Number.isNaN)) return null;
    const v = parts[0] + (parts[1] || 0) / 60 + (parts[2] || 0) / 3600;
    return neg ? -v : v;
  }
  function coordError(text) { const el = $('#coordErr'); el.textContent = text; el.classList.remove('hidden'); }
  ['#raIn', '#decIn', '#azIn', '#altIn'].forEach((id) => $(id).addEventListener('input', () => $('#coordErr').classList.add('hidden')));
  $('#coordGo').addEventListener('click', () => {
    if (coordKind === 'radec') {
      const ra = parseSexa($('#raIn').value), dec = parseSexa($('#decIn').value);
      if (ra == null || ra < 0 || ra >= 24) return coordError('Escribe la AR en horas: 0 42 44');
      if (dec == null || dec < -90 || dec > 90) return coordError('Escribe la Dec en grados: +41 16 9');
      if (send({ type: 'gotoCoords', ra, dec })) S.gotoLabel = `AR ${fmtRA(ra)} · Dec ${fmtDec(dec)}`;
    } else {
      const az = Number($('#azIn').value), alt = Number($('#altIn').value);
      if ($('#azIn').value === '' || !(az >= 0 && az <= 360)) return coordError('Acimut entre 0 y 360°');
      if ($('#altIn').value === '' || !(alt >= 0 && alt <= 90)) return coordError('Altura entre 0 y 90°');
      if (send({ type: 'gotoAltAz', az, alt })) S.gotoLabel = `Az ${az}° · Alt ${alt}°`;
    }
  });

  /** Add the centred star; the brain first checks that the last touches match the GoTo arrival. */
  function addAlignmentStar(star, force) {
    S.pendingAlign = star;
    send({ type: 'alignAdd', id: star.id, checkFinish: true, force: force || undefined });
  }

  function aligned() { const mode = S.status.mode || S.info.mode; return mode === 'hc' ? S.info.hcAligned : S.alignment.aligned; }
  function goTo(o) {
    if (o.alt != null && o.alt <= 0) { toast(`${o.id} está bajo el horizonte`, true); return; }
    const rough = !aligned() && (S.status.mode || S.info.mode) === 'starbridge' && S.info.orientation;
    if (send({ type: 'goto', id: o.id, rough: rough || undefined })) S.gotoLabel = titleOf(o);
  }

  // ------------------------------------------------------------------ sky map
  const canvas = $('#sky');
  const ctx = canvas.getContext('2d');
  const DEG = Math.PI / 180;
  const view = { cAz: 180, cAlt: 90, face: prefs.get('face', 180), zoom: 1 };
  const layers = prefs.get('layers', { lines: true, names: true, dso: true });
  let hits = [];
  let hover = null;

  const vec = (az, alt) => [Math.cos(alt * DEG) * Math.sin(az * DEG), Math.cos(alt * DEG) * Math.cos(az * DEG), Math.sin(alt * DEG)];
  const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
  const norm = (a) => { const l = Math.hypot(a[0], a[1], a[2]) || 1; return [a[0] / l, a[1] / l, a[2] / l]; };
  let basis = null;

  /**
   * The part of the map not covered by the floating glass panels (sidebar, toolbar, inspector,
   * status bar): the sky is centred and sized in it, while still drawn behind the glass.
   */
  function safeArea(w, h) {
    const r = canvas.getBoundingClientRect();
    const box = (sel) => { const el = $(sel); if (!el) return null; const b = el.getBoundingClientRect(); return b.width && b.height ? b : null; };
    const side = box('#sidebar'), insp = box('.inspector'), bar = box('.toolbar'), status = box('.statusbar'), tools = box('.map-tools');
    const l = side ? Math.max(0, side.right - r.left) : 0;
    const rr = insp ? Math.max(0, r.right - insp.left) : 0;
    const t = Math.max(bar ? bar.bottom - r.top : 0, tools ? tools.bottom - r.top : 0, 0) + 4; // below the toolbar and the map tools
    const b = status ? Math.max(0, r.bottom - status.top) : 0;
    const aw = Math.max(120, w - l - rr), ah = Math.max(120, h - t - b);
    return { cx: l + aw / 2, cy: t + ah / 2, size: Math.min(aw, ah) };
  }
  function makeBasis(w, h) {
    const f = vec(view.cAz, view.cAlt);
    let u = [-f[2] * f[0], -f[2] * f[1], 1 - f[2] * f[2]]; // zenith minus its component along f
    if (Math.hypot(u[0], u[1], u[2]) < 1e-3) { const back = vec(view.face + 180, 0); u = back; }
    u = norm(u);
    const r = cross(f, u); // east appears on the left, as when looking up
    const area = safeArea(w, h);
    const full = (area.size / 2 - 26) / 2; // horizon of a zenith view at radius 2 (stereographic)
    basis = { f, u, r, scale: full * view.zoom, cx: area.cx, cy: area.cy };
  }
  function project(az, alt) {
    const p = vec(az, alt);
    const c = dot(p, basis.f);
    if (c < -0.6) return null;
    const k = 2 / (1 + c);
    return [basis.cx + k * dot(p, basis.r) * basis.scale, basis.cy - k * dot(p, basis.u) * basis.scale];
  }
  function unproject(x, y) {
    const X = (x - basis.cx) / basis.scale, Y = -(y - basis.cy) / basis.scale;
    const rho = Math.hypot(X, Y);
    if (rho < 1e-9) return [view.cAz, view.cAlt];
    const c = 2 * Math.atan(rho / 2);
    const s = Math.sin(c), co = Math.cos(c);
    const p = [0, 1, 2].map((i) => co * basis.f[i] + s * (X / rho * basis.r[i] + Y / rho * basis.u[i]));
    const alt = Math.asin(Math.max(-1, Math.min(1, p[2]))) / DEG;
    const az = ((Math.atan2(p[0], p[1]) / DEG) + 360) % 360;
    return [az, alt];
  }

  function drawSky() {
    if (S.view !== 'sky') return;
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (!w || !h) return;
    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr); }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    makeBasis(w, h);
    hits = [];
    const g = ctx.createRadialGradient(basis.cx, basis.cy, 0, basis.cx, basis.cy, Math.max(w, h) * 0.75);
    g.addColorStop(0, cssVar('--sky-1')); g.addColorStop(1, cssVar('--sky-2'));
    ctx.fillStyle = g; ctx.fillRect(0, 0, w, h);
    const zoomBoost = Math.pow(view.zoom, 0.35);

    // Altitude circles and azimuth spokes.
    ctx.strokeStyle = cssVar('--sky-grid'); ctx.lineWidth = 1; ctx.setLineDash([3, 5]);
    for (const alt of [30, 60]) polyline(Array.from({ length: 121 }, (_, i) => [i * 3, alt]));
    for (let az = 0; az < 360; az += 30) polyline(Array.from({ length: 19 }, (_, i) => [az, i * 5]));
    ctx.setLineDash([]);

    const sky = S.sky;
    if (sky && layers.lines && sky.lines) {
      ctx.strokeStyle = cssVar('--sky-line'); ctx.lineWidth = 1.1;
      for (const line of sky.lines) polyline(line);
    }
    if (sky) {
      const star = cssVar('--sky-star');
      ctx.fillStyle = star;
      ctx.font = '11px ' + cssVar('--font');
      for (const [az, alt, mag, label] of sky.stars) {
        if (alt < -1) continue;
        const p = project(az, alt); if (!p) continue;
        const r = Math.max(0.5, (2.9 - 0.5 * mag) * zoomBoost);
        ctx.globalAlpha = Math.min(1, 0.4 + (4.6 - mag) * 0.16);
        ctx.beginPath(); ctx.arc(p[0], p[1], r, 0, 2 * Math.PI); ctx.fill();
        hits.push({ id: label || null, x: p[0], y: p[1], pr: 1, star: { mag, az, alt } });
        if (label && layers.names && (mag <= 1.5 || view.zoom >= 2.5)) {
          ctx.globalAlpha = 0.75; ctx.textAlign = 'left'; ctx.textBaseline = 'middle'; ctx.fillText(label, p[0] + r + 4, p[1]);
        }
      }
      ctx.globalAlpha = 1;
      const dso = cssVar('--sky-dso'), planet = cssVar('--sky-planet');
      for (const o of sky.objects) {
        if (o.alt < -1) continue;
        const p = project(o.az, o.alt); if (!p) continue;
        const [x, y] = p;
        if (o.cat === 'planet' || o.cat === 'moon') {
          ctx.fillStyle = planet;
          ctx.beginPath(); ctx.arc(x, y, (o.cat === 'moon' ? 8 : 4.5) * Math.min(2, zoomBoost), 0, 2 * Math.PI); ctx.fill();
          ctx.font = '600 12px ' + cssVar('--font'); ctx.textAlign = 'left'; ctx.textBaseline = 'middle'; ctx.fillText(o.name || o.id, x + 10, y);
          hits.push({ id: o.id, x, y, pr: 0 }); continue;
        }
        if (!layers.dso) continue;
        // Fainter objects appear as you zoom in: Messier always, magnitude 7.5 at the full view,
        // about 12 at ×4 and everything (to 12-13) beyond.
        const messier = /^M\d+$/.test(o.id);
        const limit = 7.5 + 2.2 * Math.log2(Math.max(1, view.zoom));
        if (!messier && !(o.name && view.zoom >= 2) && (o.mag ?? 12.5) > limit) continue;
        ctx.strokeStyle = dso; ctx.lineWidth = 1.2; const s = 1 * Math.min(2.2, zoomBoost);
        if (o.cat === 'galaxy') { ctx.beginPath(); ctx.ellipse(x, y, 5.5 * s, 2.8 * s, -0.5, 0, 2 * Math.PI); ctx.stroke(); }
        else if (o.cat === 'nebula') { ctx.strokeRect(x - 4 * s, y - 4 * s, 8 * s, 8 * s); }
        else { ctx.setLineDash([1.5, 1.5]); ctx.beginPath(); ctx.arc(x, y, 4.5 * s, 0, 2 * Math.PI); ctx.stroke(); ctx.setLineDash([]); }
        if (layers.names && (/^M\d+$/.test(o.id) || view.zoom >= 3)) {
          ctx.fillStyle = dso; ctx.globalAlpha = 0.85; ctx.font = '10.5px ' + cssVar('--font'); ctx.textAlign = 'left'; ctx.textBaseline = 'top';
          ctx.fillText(o.id, x + 6 * s, y + 4 * s); ctx.globalAlpha = 1;
        }
        hits.push({ id: o.id, x, y, pr: 2 });
      }
      if (layers.lines && layers.names && sky.labels) {
        ctx.fillStyle = cssVar('--sky-label'); ctx.font = `${view.zoom >= 2 ? 12 : 10.5}px ` + cssVar('--font'); ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
        for (const [name, az, alt] of sky.labels) { const p = project(az, alt); if (p) ctx.fillText(name.toUpperCase(), p[0], p[1]); }
      }
    }

    drawZones(w, h, dpr);

    // Ground below the horizon, then the horizon line and the cardinal points.
    ctx.fillStyle = 'rgba(0,0,0,0.55)';
    groundFill(w, h);
    ctx.strokeStyle = cssVar('--sky-horizon'); ctx.lineWidth = 1.5;
    polyline(Array.from({ length: 181 }, (_, i) => [i * 2, 0]));
    ctx.fillStyle = cssVar('--text-2'); ctx.font = '600 13px ' + cssVar('--font'); ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    for (const [lab, az] of [['N', 0], ['NE', 45], ['E', 90], ['SE', 135], ['S', 180], ['SO', 225], ['O', 270], ['NO', 315]]) {
      const p = project(az, -3.5); if (p) { ctx.globalAlpha = lab.length === 1 ? 1 : 0.55; ctx.fillText(lab, p[0], p[1]); }
    }
    ctx.globalAlpha = 1;

    // Selected object and the telescope.
    const sel = S.selected;
    if (sel && sel.alt != null) {
      const p = project(sel.az, sel.alt);
      if (p) { ctx.strokeStyle = cssVar('--sky-select'); ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(p[0], p[1], 14, 0, 2 * Math.PI); ctx.stroke(); }
    }
    const st = S.status;
    if (st.alt !== undefined && st.alt > -5) { const p = project(st.az, st.alt); if (p) reticle(p, false); }
    else if (st.sensor && st.sensor.alt > -5) { const p = project(st.sensor.az, st.sensor.alt); if (p) reticle(p, true); }
    $('#mapLegend').textContent = sky ? `Cielo de las ${fmtTime(sky.time)} · rueda: zoom · arrastrar: mover · doble clic: centrar` : (S.open ? 'Cargando el cielo…' : 'Sin conexión');
  }
  function polyline(points) {
    ctx.beginPath();
    let pen = false;
    for (const [az, alt] of points) {
      const p = project(az, alt);
      if (!p) { pen = false; continue; }
      if (pen) ctx.lineTo(p[0], p[1]); else ctx.moveTo(p[0], p[1]);
      pen = true;
    }
    ctx.stroke();
  }
  /** Projection without the far-side cut-off (the horizon circle needs its exact points). */
  function projectRaw(az, alt) {
    const p = vec(az, alt);
    const k = 2 / (1 + Math.max(-0.999, dot(p, basis.f)));
    return [basis.cx + k * dot(p, basis.r) * basis.scale, basis.cy - k * dot(p, basis.u) * basis.scale];
  }
  function groundFill(w, h) {
    // The stereographic projection maps the horizon to a circle: shade the side below it.
    const A = projectRaw(view.cAz - 90, 0), B = projectRaw(view.cAz + 90, 0), C = projectRaw(view.cAz, 0);
    const G = projectRaw(view.cAz, -20); // a point of the ground
    const d = 2 * (A[0] * (B[1] - C[1]) + B[0] * (C[1] - A[1]) + C[0] * (A[1] - B[1]));
    ctx.beginPath();
    if (Math.abs(d) < 1e-6) {
      // Looking exactly at the horizon: it is a straight line, the ground is one half-plane.
      const dx = B[0] - A[0], dy = B[1] - A[1], l = Math.hypot(dx, dy) || 1;
      let nx = -dy / l, ny = dx / l;
      if ((G[0] - A[0]) * nx + (G[1] - A[1]) * ny < 0) { nx = -nx; ny = -ny; }
      const far = 4 * (w + h);
      ctx.moveTo(A[0] - dx / l * far, A[1] - dy / l * far); ctx.lineTo(A[0] + dx / l * far, A[1] + dy / l * far);
      ctx.lineTo(A[0] + dx / l * far + nx * far, A[1] + dy / l * far + ny * far); ctx.lineTo(A[0] - dx / l * far + nx * far, A[1] - dy / l * far + ny * far);
      ctx.fill(); return;
    }
    const s = (P) => P[0] * P[0] + P[1] * P[1];
    const ux = (s(A) * (B[1] - C[1]) + s(B) * (C[1] - A[1]) + s(C) * (A[1] - B[1])) / d;
    const uy = (s(A) * (C[0] - B[0]) + s(B) * (A[0] - C[0]) + s(C) * (B[0] - A[0])) / d;
    const r = Math.hypot(A[0] - ux, A[1] - uy);
    const groundInside = Math.hypot(G[0] - ux, G[1] - uy) < r;
    if (groundInside) { ctx.arc(ux, uy, r, 0, 2 * Math.PI); ctx.fill(); return; }
    ctx.rect(-10, -10, w + 20, h + 20);
    ctx.arc(ux, uy, r, 0, 2 * Math.PI);
    ctx.fill('evenodd');
  }
  function reticle([x, y], dashed) {
    ctx.strokeStyle = cssVar('--sky-scope'); ctx.lineWidth = 2;
    if (dashed) ctx.setLineDash([4, 4]);
    ctx.beginPath(); ctx.arc(x, y, 12, 0, 2 * Math.PI); ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(x - 20, y); ctx.lineTo(x - 8, y); ctx.moveTo(x + 8, y); ctx.lineTo(x + 20, y);
    ctx.moveTo(x, y - 20); ctx.lineTo(x, y - 8); ctx.moveTo(x, y + 8); ctx.lineTo(x, y + 20);
    ctx.stroke(); ctx.setLineDash([]);
  }

  function hitAt(x, y) {
    let best = null, bestD = 18 * 18;
    for (const h of hits) {
      const d = (h.x - x) ** 2 + (h.y - y) ** 2 - (2 - h.pr) * 25;
      if (d < bestD && (h.id || h.star)) { best = h; bestD = d; }
    }
    return best;
  }
  let drag = null;
  canvas.addEventListener('pointerdown', (e) => {
    canvas.setPointerCapture(e.pointerId);
    const rect = canvas.getBoundingClientRect();
    const ze = S.zoneEdit;
    if (ze && ze.mode !== 'scope') {
      const i = zoneHandleAt(e.clientX - rect.left, e.clientY - rect.top);
      if (i >= 0) { ze.dragging = i; return; } // move that corner, not the map
    }
    drag = { x: e.clientX, y: e.clientY, moved: false };
  });
  canvas.addEventListener('pointermove', (e) => {
    const rect = canvas.getBoundingClientRect();
    const x = e.clientX - rect.left, y = e.clientY - rect.top;
    const ze = S.zoneEdit;
    if (ze && ze.dragging != null) {
      const [az, alt] = unproject(x, y);
      ze.points[ze.dragging] = [az, Math.max(-10, Math.min(90, alt))];
      drawSky(); renderZoneBar();
      return;
    }
    if (ze && ze.mode === 'draw' && !drag) { ze.mouse = [x, y]; requestAnimationFrame(drawSky); }
    if (drag) {
      const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
      if (Math.abs(dx) + Math.abs(dy) > 2) {
        drag.moved = true; canvas.classList.add('dragging');
        const [az, alt] = unproject(basis.cx - dx, basis.cy - dy);
        view.cAz = az; view.cAlt = Math.max(-10, Math.min(90, alt));
        drag.x = e.clientX; drag.y = e.clientY;
        drawSky();
      }
      return;
    }
    const h = hitAt(x, y);
    const tip = $('#tooltip');
    if (!h) { tip.classList.add('hidden'); hover = null; return; }
    hover = h;
    const o = h.id ? (S.sky.objects.find((q) => q.id === h.id) || S.objects[h.id]) : null;
    tip.innerHTML = o ? `<b>${esc(o.name ? `${o.id} · ${o.name}` : o.id)}</b><br>${esc(o.cat || '')}${o.mag != null ? ' · mag ' + o.mag : ''} · alt ${fmtDeg(o.alt)}`
      : h.id ? `<b>${esc(h.id)}</b><br>estrella · mag ${h.star?.mag ?? ''}` : `estrella · mag ${h.star.mag} · alt ${fmtDeg(h.star.alt)}`;
    tip.style.left = `${x + 16}px`; tip.style.top = `${y + 12}px`;
    tip.classList.remove('hidden');
  });
  canvas.addEventListener('pointerup', (e) => {
    canvas.classList.remove('dragging');
    const ze = S.zoneEdit;
    if (ze && ze.dragging != null) { ze.dragging = null; return; }
    const wasDrag = drag && drag.moved;
    drag = null;
    if (wasDrag) return;
    const rect = canvas.getBoundingClientRect();
    if (ze && ze.mode === 'draw') {
      // Drawing: each click is a corner of what can be seen.
      const [az, alt] = unproject(e.clientX - rect.left, e.clientY - rect.top);
      if (alt < -10) { toast('Eso está bajo el horizonte'); return; }
      if (ze.points.length >= 64) { toast('Como mucho 64 esquinas', true); return; }
      ze.points.push([az, Math.min(90, alt)]);
      drawSky(); renderZoneBar();
      return;
    }
    const h = hitAt(e.clientX - rect.left, e.clientY - rect.top);
    if (h && h.id) selectObject(h.id);
  });
  canvas.addEventListener('pointerleave', () => $('#tooltip').classList.add('hidden'));
  canvas.addEventListener('dblclick', (e) => {
    if (S.zoneEdit) return; // clicks are corners while editing a zone
    const rect = canvas.getBoundingClientRect();
    const [az, alt] = unproject(e.clientX - rect.left, e.clientY - rect.top);
    view.cAz = az; view.cAlt = Math.max(-10, Math.min(90, alt)); view.zoom = Math.min(40, view.zoom * 1.8); drawSky();
  });
  canvas.addEventListener('wheel', (e) => {
    e.preventDefault();
    const rect = canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left, my = e.clientY - rect.top;
    const f = Math.exp(-e.deltaY * 0.0015);
    const before = unproject(mx, my);
    view.zoom = Math.max(0.6, Math.min(40, view.zoom * f));
    makeBasis(canvas.clientWidth, canvas.clientHeight);
    // Keep the point under the cursor where it was (one correction step is plenty).
    const p = project(before[0], before[1]);
    if (p) {
      const [az, alt] = unproject(basis.cx + (p[0] - mx), basis.cy + (p[1] - my));
      view.cAz = az; view.cAlt = Math.max(-10, Math.min(90, alt));
    }
    drawSky();
  }, { passive: false });
  $$('#faceSeg button').forEach((b) => b.addEventListener('click', () => {
    view.face = Number(b.dataset.face); prefs.set('face', view.face);
    view.cAz = view.face; view.cAlt = 30; view.zoom = 1.6;
    $$('#faceSeg button').forEach((x) => x.classList.toggle('active', x === b));
    drawSky();
  }));
  $('#zoomIn').addEventListener('click', () => { view.zoom = Math.min(40, view.zoom * 1.5); drawSky(); });
  $('#zoomOut').addEventListener('click', () => { view.zoom = Math.max(0.6, view.zoom / 1.5); drawSky(); });
  $('#zoomReset').addEventListener('click', () => { view.cAz = 180; view.cAlt = 90; view.zoom = 1; drawSky(); });
  $('#centerScope').addEventListener('click', () => {
    const st = S.status;
    if (st.alt === undefined) { toast('El telescopio aún no sabe dónde apunta: alinéalo'); return; }
    view.cAz = st.az; view.cAlt = Math.max(-10, st.alt); view.zoom = Math.max(view.zoom, 4); drawSky();
  });
  $$('#layerSeg button').forEach((b) => {
    b.classList.toggle('active', !!layers[b.dataset.layer]);
    b.addEventListener('click', () => { layers[b.dataset.layer] = !layers[b.dataset.layer]; prefs.set('layers', layers); b.classList.toggle('active', layers[b.dataset.layer]); drawSky(); });
  });
  window.addEventListener('resize', () => { drawSky(); if (S.view === 'tracking') renderTracking(); if (S.selected) renderDetail(); });

  // ------------------------------------------------------------------ selection: map card and object page
  function selectObject(id) {
    const known = S.objects[id] || (S.sky && S.sky.objects.find((o) => o.id === id)) || { id };
    S.selected = Object.assign({ id }, known);
    send({ type: 'object', id });
    send({ type: 'objectNight', id });
    renderMapCard(); renderDetail(); drawSky();
  }
  function objectActions(o) {
    const visible = o.alt == null || o.alt > 0;
    const mode = S.status.mode || S.info.mode;
    const canAlign = mode === 'starbridge' && visible && (o.cat === 'star' || o.cat === 'planet');
    const rough = !aligned() && mode === 'starbridge' && S.info.orientation;
    let note = '';
    if (!S.status.connected) note = 'Telescopio no conectado';
    else if (!visible) note = 'Está bajo el horizonte ahora mismo';
    else if (!aligned() && !rough) note = 'Sin alinear: céntralo con las flechas y pulsa «Usar para alinear».';
    return `<div class="row-actions">
        <button class="btn primary" data-act="goto" ${note ? 'disabled' : ''}>Ir a ${esc(o.id)}${rough ? ' (aprox.)' : ''}</button>
        ${canAlign ? '<button class="btn" data-act="align">Usar para alinear</button>' : ''}
        ${mode === 'hc' && S.info.canSync && S.info.hcAligned && visible ? '<button class="btn" data-act="sync">Sync: está centrado</button>' : ''}
      </div>${note ? `<div class="muted" style="margin-top:6px">${esc(note)}</div>` : ''}`;
  }
  function bindActions(root, o) {
    $('[data-act="goto"]', root)?.addEventListener('click', () => goTo(o));
    $('[data-act="align"]', root)?.addEventListener('click', () => startAlign(o));
    $('[data-act="sync"]', root)?.addEventListener('click', () => send({ type: 'sync', id: o.id }));
    $('[data-act="page"]', root)?.addEventListener('click', () => { showView('objects'); renderDetail(); });
  }
  function renderMapCard() {
    const card = $('#mapCard');
    const o = S.selected;
    if (!o) { card.classList.add('hidden'); return; }
    card.classList.remove('hidden');
    card.innerHTML = `<button class="close" aria-label="Cerrar">✕</button>
      <h2>${esc(titleOf(o))}${zoneTag(o)}</h2>
      <div class="muted">${esc(o.kind || '')}${o.const ? ' · ' + esc(o.const) : ''}${o.mag != null ? ' · magnitud ' + o.mag : ''}${o.aka ? ' · ' + esc(o.aka) : ''}</div>
      <div class="facts"><div><span>Altura</span><b>${fmtDeg(o.alt)}</b></div><div><span>Dirección</span><b>${esc(o.dir || '')} ${fmtDeg(o.az)}</b></div>
        <div><span>AR</span><b>${fmtRA(o.ra)}</b></div><div><span>Dec</span><b>${fmtDec(o.dec)}</b></div></div>
      ${objectActions(o)}
      <div class="row-actions"><button class="btn ghost" data-act="page">Ficha y noche ›</button></div>`;
    $('.close', card).addEventListener('click', () => { S.selected = null; card.classList.add('hidden'); drawSky(); });
    bindActions(card, o);
  }

  // Objects view: search and list.
  let searchTimer = null;
  function search() {
    const q = S.query.trim();
    const category = q && S.category === 'best' ? null : S.category;
    send({ type: 'search', q, category: category || undefined });
  }
  $('#q').addEventListener('input', () => { S.query = $('#q').value; clearTimeout(searchTimer); searchTimer = setTimeout(search, 160); });
  $$('#chips .chip').forEach((c) => c.addEventListener('click', () => {
    $$('#chips .chip').forEach((x) => x.classList.toggle('active', x === c));
    S.category = c.dataset.cat; search();
  }));
  $('#onlyVisible').addEventListener('change', renderResults);
  $('#onlyZone').checked = prefs.get('onlyZone', false);
  $('#onlyZone').addEventListener('change', (e) => { prefs.set('onlyZone', e.target.checked); renderResults(); });
  /** A little tag after a name: inside or outside the zones that are on (nothing without zones). */
  const zoneTag = (o) => (o.inZone === false ? '<span class="tag">fuera de tu zona</span>' : o.inZone === true ? '<span class="tag in">la ves</span>' : '');
  function renderResults() {
    const only = $('#onlyVisible').checked;
    const onlyZone = $('#onlyZone').checked && S.zones.some((z) => z.on);
    const items = S.results.filter((it) => (!only || it.alt > 0) && (!onlyZone || it.inZone !== false));
    for (const it of S.results) S.objects[it.id] = Object.assign(S.objects[it.id] || {}, it);
    $('#results').innerHTML = items.length ? items.map((it) => `
      <div class="item ${it.alt < 0 ? 'below' : ''} ${it.inZone === false ? 'outzone' : ''} ${S.selected && S.selected.id === it.id ? 'sel' : ''}" data-id="${esc(it.id)}">
        <div class="badge">${CAT_ICON[it.cat] || '◌'}</div>
        <div class="meta"><div class="name">${esc(titleOf(it))}${zoneTag(it)}</div>
          <div class="sub">${esc(it.kind)}${it.mag != null ? ' · mag ' + it.mag : ''}${it.const ? ' · ' + esc(it.const) : ''}${it.aka ? ' · ' + esc(it.aka) : ''}</div></div>
        <div class="alt"><b>${it.alt.toFixed(0)}°</b>${esc(it.dir)}</div>
      </div>`).join('') : `<div class="muted" style="padding:20px 8px">${S.open ? (onlyZone ? 'Nada de esto se ve desde tus zonas ahora mismo.' : 'No hay resultados sobre el horizonte.') : 'Sin conexión.'}</div>`;
    $$('#results .item').forEach((el) => el.addEventListener('click', () => { selectObject(el.dataset.id); renderResults(); }));
  }

  // Object page with its night.
  function renderDetail() {
    const o = S.selected;
    const root = $('#objectDetail');
    if (!o) return;
    const n = S.nights[o.id];
    root.innerHTML = `
      <div class="detail-head">
        <div><h1>${esc(titleOf(o))}${zoneTag(o)}</h1>
          <div class="detail-kind">${esc(o.kind || '')}${o.const ? ' · ' + esc(o.const) : ''}${o.mag != null ? ' · magnitud ' + o.mag : ''}${o.aka ? ' · ' + esc(o.aka) : ''}</div></div>
      </div>
      ${objectActions(o)}
      <div class="grid-3" style="margin-top:16px">
        <div class="stat"><div class="k">Altura ahora</div><div class="v">${fmtDeg(o.alt)}</div></div>
        <div class="stat"><div class="k">Dirección</div><div class="v">${esc(o.dir || '—')} <small>${fmtDeg(o.az)}</small></div></div>
        <div class="stat"><div class="k">Máxima esta noche</div><div class="v">${n ? fmtDeg(n.maxAlt) : '…'}</div></div>
      </div>
      <div class="card" style="margin-top:16px">
        <div class="card-title">Esta noche <span class="muted">(altura sobre el horizonte; sombreado: cielo oscuro${n?.zoneWindows ? '; verde: por tu zona visible' : ''})</span></div>
        <canvas class="night-chart" id="nightChart"></canvas>
        <div class="times">${nightTimes(o, n)}</div>
      </div>
      <div class="card" style="margin-top:16px"><div class="facts"><div><span>Ascensión recta</span><b>${fmtRA(o.ra)}</b></div><div><span>Declinación</span><b>${fmtDec(o.dec)}</b></div></div></div>`;
    bindActions(root, o);
    if (n) drawNight($('#nightChart'), n);
  }
  /** Rise, culmination and set in the order they happen in the next hours, then the best moment. */
  function nightTimes(o, n) {
    const stat = (k, v) => `<div class="stat"><div class="k">${k}</div><div class="v">${v}</div></div>`;
    if (!n) return stat('Sale', '…') + stat('Culmina', '…') + stat('Se pone', '…') + stat('Mejor momento', '…');
    const events = [['Sale', n.rise], ['Culmina', n.transit], ['Se pone', n.set]].filter(([, t]) => t).sort((a, b) => a[1] - b[1]);
    const always = n.samples.every(([, a]) => a > 0), never = n.samples.every(([, a]) => a <= 0);
    const head = events.length ? events.map(([k, t]) => stat(k, fmtTime(t))).join('')
      : stat('Esta noche', always ? 'siempre visible' : never ? 'no sale' : '—');
    const zone = n.zoneWindows ? stat('Por tu zona visible', n.zoneWindows.length
      ? n.zoneWindows.map(([a, b]) => (a === b ? fmtTime(a) : `${fmtTime(a)}–${fmtTime(b)}`)).join(', ')
      : 'no pasa esta noche') : '';
    return head + stat('Mejor momento', n.best ? fmtTime(n.best) : '—') + zone;
  }

  function drawNight(cv, n) {
    const dpr = window.devicePixelRatio || 1, w = cv.clientWidth, h = cv.clientHeight;
    cv.width = w * dpr; cv.height = h * dpr;
    const c = cv.getContext('2d'); c.setTransform(dpr, 0, 0, dpr, 0, 0);
    const L = 34, R = 10, T = 10, B = 24;
    const t0 = n.samples[0][0], t1 = n.samples[n.samples.length - 1][0];
    const X = (t) => L + ((t - t0) / (t1 - t0)) * (w - L - R);
    const Y = (a) => T + (1 - Math.max(0, Math.min(90, a)) / 90) * (h - T - B);
    // Sky brightness bands from the Sun's altitude.
    for (let i = 0; i < n.samples.length - 1; i++) {
      const sun = n.samples[i][2];
      const shade = sun > 0 ? 0.0 : sun > -6 ? 0.12 : sun > -12 ? 0.22 : sun > -18 ? 0.32 : 0.45;
      c.fillStyle = `rgba(10, 132, 255, ${shade * 0.35})`;
      if (document.body.classList.contains('night')) c.fillStyle = `rgba(255, 40, 30, ${shade * 0.25})`;
      c.fillRect(X(n.samples[i][0]), T, X(n.samples[i + 1][0]) - X(n.samples[i][0]) + 0.5, h - T - B);
    }
    // When it is inside a visible zone (a window): green band at the bottom.
    for (const [a, b] of n.zoneWindows || []) {
      c.fillStyle = cssVar('--green'); c.globalAlpha = 0.28;
      c.fillRect(X(a), T, Math.max(3, X(b) - X(a)), h - T - B);
      c.globalAlpha = 1;
    }
    c.strokeStyle = cssVar('--line-2'); c.fillStyle = cssVar('--text-3'); c.font = '11px ' + cssVar('--font'); c.lineWidth = 1;
    for (const a of [0, 30, 60, 90]) { c.beginPath(); c.moveTo(L, Y(a)); c.lineTo(w - R, Y(a)); c.stroke(); c.textAlign = 'right'; c.textBaseline = 'middle'; c.fillText(`${a}°`, L - 6, Y(a)); }
    c.textAlign = 'center'; c.textBaseline = 'top';
    const firstHour = Math.ceil(t0 / 3_600_000) * 3_600_000;
    for (let t = firstHour; t <= t1; t += 2 * 3_600_000) c.fillText(fmtTime(t), X(t), h - B + 6);
    c.strokeStyle = cssVar('--accent'); c.lineWidth = 2.2; c.beginPath();
    n.samples.forEach(([t, a], i) => { const x = X(t), y = Y(a); if (i) c.lineTo(x, y); else c.moveTo(x, y); });
    c.stroke();
    const now = Date.now();
    c.strokeStyle = cssVar('--orange'); c.lineWidth = 1.5; c.setLineDash([4, 4]); c.beginPath(); c.moveTo(X(now), T); c.lineTo(X(now), h - B); c.stroke(); c.setLineDash([]);
    if (n.best) {
      const s = n.samples.reduce((p, q) => (Math.abs(q[0] - n.best) < Math.abs(p[0] - n.best) ? q : p));
      c.fillStyle = cssVar('--green'); c.beginPath(); c.arc(X(s[0]), Y(s[1]), 5, 0, 2 * Math.PI); c.fill();
    }
  }

  // ------------------------------------------------------------------ alignment
  $$('#modeSeg button').forEach((b) => b.addEventListener('click', () => send({ type: 'setMode', mode: b.dataset.mode })));
  $('#alignClear').addEventListener('click', async () => { if (await confirmBox('Borrar la alineación', 'Se olvidan todas las estrellas; tendrás que alinear de nuevo.', 'Borrar')) send({ type: 'alignClear' }); });
  /**
   * Alignment as a guided flow: "Empezar a alinear" picks the best star, each step says where
   * it is and what to press, and after each star the next one comes up until it is enough
   * (2 stars, or 1 with the iPhone level).
   */
  function starsNeeded() { return S.alignment.level ? 1 : 2; }
  function nextSuggestion(after) {
    const list = (S.alignment.suggestions || []).filter((s) => s.alt > 0);
    if (!list.length) return null;
    if (!after) return list[0];
    const i = list.findIndex((s) => s.id === after.id);
    return list[(i + 1) % list.length];
  }
  function startAlign(star) {
    S.alignFlow = true;
    S.activeStar = star || nextSuggestion(null);
    if (!S.activeStar) { toast('No hay estrellas buenas visibles ahora (¿es de día o está nublado?)', true); S.alignFlow = false; }
    if (S.view !== 'align') showView('align');
    renderAlign();
    $('#view-align .page')?.scrollTo({ top: 0, behavior: 'smooth' });
  }
  function canGoto() { return aligned() || !!S.info.orientation; }
  function renderAlign() {
    const a = S.alignment, i = S.info;
    const mode = a.mode || i.mode || 'starbridge';
    $$('#modeSeg button').forEach((b) => b.classList.toggle('active', b.dataset.mode === mode));
    const hero = $('#alignHero');
    const star = S.activeStar;
    const connected = !!(S.status.connected || i.connected);
    const kbd = '<div class="kbd-row"><kbd>←</kbd><kbd>→</kbd><kbd>↑</kbd><kbd>↓</kbd> mover · <kbd>⇧</kbd> rápido · <kbd>+</kbd><kbd>−</kbd> velocidad · <kbd>Espacio</kbd> STOP</div>';
    if (mode === 'hc') {
      hero.innerHTML = `<div class="hero-state"><div class="ring ${i.hcAligned ? 'ok' : ''}">${i.hcAligned ? '✓' : '!'}</div>
        <div><h2>${i.hcAligned ? 'El mando está alineado' : 'El mando no está alineado'}</h2>
        <p class="muted">${i.hcAligned ? (i.canSync ? 'Para afinar: centra un objeto y usa «Sync» en su ficha.' : 'GoTo y seguimiento los hace el mando.') : 'Alinéalo en el propio mando (SkyAlign…), o cambia arriba a «Alinea StarBridge» y hazlo desde aquí.'}</p></div></div>`;
    } else if (star) {
      const step = Math.min((a.stars || 0) + 1, Math.max(starsNeeded(), (a.stars || 0) + 1));
      const total = Math.max(starsNeeded(), step);
      const where = `${esc(star.dir)} · ${star.alt.toFixed(0)}° de altura${star.mag != null ? ` · magnitud ${String(star.mag).replace('.', ',')}` : ''}${star.sepDeg != null ? ` · a ${star.sepDeg}° de la anterior` : ''}`;
      const goto = canGoto();
      hero.innerHTML = `<div class="hero-step">
        <div class="step-chip">Paso ${step} de ${total}</div>
        <h2 class="star-name">Centra ${esc(star.id)}</h2>
        <p class="star-where">${where}</p>
        <ol class="flow">
          <li class="${goto ? '' : 'manual'}">${goto ? `Pulsa <b>Ir a ${esc(star.id)}</b>: el telescopio se acerca solo.`
            : `Muévelo con las flechas hasta verla en el ocular: es la estrella más brillante hacia el <b>${esc(star.dir)}</b>, a ${star.alt.toFixed(0)}° de altura.`}</li>
          <li>Céntrala en el ocular con las flechas; al final, velocidad fina.</li>
          <li>Pulsa <b>Está centrada</b>.</li>
        </ol>
        ${kbd}
        <div class="row-actions hero-actions">
          ${goto ? `<button class="btn big" id="aGo" ${connected ? '' : 'disabled'}>Ir a ${esc(star.id)}</button>` : ''}
          <button class="btn big primary" id="aDone" ${connected ? '' : 'disabled'}>Está centrada ✓</button>
          <button class="btn ghost" id="aOther">Otra estrella</button>
          <button class="btn ghost" id="aMap">Ver en el mapa</button>
          <button class="btn ghost" id="aCancel">Cancelar</button>
        </div>
        ${connected ? '' : '<p class="muted" style="color:var(--orange)">El telescopio no está conectado.</p>'}
      </div>`;
      $('#aGo')?.addEventListener('click', () => goTo(star));
      $('#aDone').addEventListener('click', () => {
        if (S.status.goto) { toast(`Espera a que termine el GoTo y centra ${star.id} en el ocular`, true); return; }
        releaseAll(true); addAlignmentStar(star, false);
      });
      $('#aOther').addEventListener('click', () => { S.activeStar = nextSuggestion(star); renderAlign(); });
      $('#aMap').addEventListener('click', () => { showView('sky'); selectObject(star.id); });
      $('#aCancel').addEventListener('click', () => { S.activeStar = null; S.alignFlow = false; renderAlign(); });
    } else if (!a.aligned || a.sensorOnly) {
      const rough = a.aligned && a.sensorOnly;
      hero.innerHTML = `<div class="hero-state"><div class="ring ${rough ? 'rough' : ''}">${rough ? '~' : '0'}</div>
        <div><h2>${rough ? 'Alineación aproximada' : 'Sin alinear'}</h2>
        <p class="muted">${rough ? 'Hecha con los sensores (±5°). Centra una estrella para que el GoTo sea preciso.'
          : `Centra ${starsNeeded() === 1 ? 'una estrella' : 'dos estrellas brillantes'} y StarBridge sabrá dónde está todo el cielo. Te guía paso a paso.`}</p></div></div>
        <div class="row-actions hero-actions">
          <button class="btn big primary" id="aStart" ${connected ? '' : 'disabled'}>Empezar a alinear</button>
          ${i.orientation && !a.aligned ? '<button class="btn big" id="aSensor">Alineación rápida con sensores</button>' : ''}
        </div>
        ${connected ? '' : '<p class="muted" style="color:var(--orange)">Conecta el telescopio para alinear.</p>'}
        ${a.needsCheck ? '<p class="muted" style="color:var(--orange)">El telescopio se ha reconectado: comprueba la alineación con una estrella.</p>' : ''}`;
      $('#aStart').addEventListener('click', () => startAlign());
      $('#aSensor')?.addEventListener('click', () => send({ type: 'alignSensor' }));
    } else {
      const enough = a.stars >= starsNeeded();
      const sub = a.stars >= 3 ? `Error medio ${a.rmsArcmin}′ · inclinación de la base ${a.tiltDeg}°`
        : a.stars === 2 ? `Inclinación de la base ${a.tiltDeg}°. Una 3.ª estrella, lejos de las otras, comprueba la puntería.`
        : a.level ? 'Con la nivelación del iPhone esta estrella basta para todo el cielo.'
        : 'Una 2.ª estrella lejos de la primera corrige la inclinación de la base (o nivela con el iPhone, abajo).';
      hero.innerHTML = `<div class="hero-state"><div class="ring ${enough ? 'ok' : 'rough'}">${a.stars}</div>
        <div><h2>${enough ? '¡Alineado! ' : ''}${a.stars === 1 ? '1 estrella' : `${a.stars} estrellas`}</h2><p class="muted">${sub}</p></div></div>
        ${a.needsCheck ? '<p class="muted" style="color:var(--orange)">El telescopio se ha reconectado: comprueba la alineación con una estrella.</p>' : ''}
        <div class="row-actions hero-actions">
          <button class="btn big ${enough ? '' : 'primary'}" id="aMore">${enough ? 'Añadir otra estrella' : 'Añadir la 2.ª estrella'}</button>
          ${enough ? '<button class="btn big primary" id="aObjects">Ir a un objeto</button>' : ''}
        </div>`;
      $('#aMore').addEventListener('click', () => startAlign());
      $('#aObjects')?.addEventListener('click', () => showView('objects'));
    }
    const sugg = a.suggestions || [];
    $('#alignSuggestions').innerHTML = sugg.map((s, k) => `
      <div class="item align-item ${star && star.id === s.id ? 'sel' : ''}" data-id="${esc(s.id)}"><div class="badge">${CAT_ICON[s.cat] || '✦'}</div>
        <div class="meta"><div class="name">${esc(s.id)}${k === 0 ? '<span class="tag in">Recomendada</span>' : ''}${zoneTag(s)}</div>
          <div class="sub">${esc(s.dir)} · ${s.alt.toFixed(0)}° de altura${s.mag != null ? ' · mag ' + s.mag : ''}${s.sepDeg != null ? ' · a ' + s.sepDeg + '° de la anterior' : ''}</div></div>
        ${mode === 'starbridge' ? `<button class="btn small-btn" data-align="${esc(s.id)}">${star && star.id === s.id ? 'Elegida' : 'Alinear'}</button>` : ''}
      </div>`).join('') || '<div class="muted">No hay estrellas buenas visibles ahora.</div>';
    $$('#alignSuggestions .item').forEach((el) => el.addEventListener('click', () => {
      if (mode !== 'starbridge') return;
      startAlign(sugg.find((s) => s.id === el.dataset.id));
    }));
    const pts = a.points || [];
    $('#alignPoints').innerHTML = pts.length ? `<tr><th>Punto</th><th>Tipo</th><th>Error</th><th></th></tr>` + pts.map((p) => `
      <tr><td>${esc(p.label)}</td><td>${p.sensor ? 'sensores' : 'estrella'}</td><td>${p.residualArcmin != null ? p.residualArcmin + '′' : (p.sensor ? 'aprox.' : '—')}</td>
        <td style="text-align:right"><button class="btn ghost" data-remove="${esc(p.label)}">Quitar</button></td></tr>`).join('')
      : '<tr><td class="muted">Aún no hay puntos: pulsa «Empezar a alinear».</td></tr>';
    $$('[data-remove]').forEach((b) => b.addEventListener('click', () => send({ type: 'alignRemove', label: b.dataset.remove })));
  }

  // ------------------------------------------------------------------ tracking charts
  function renderTracking() {
    const st = S.status, hist = S.trackHist;
    $('#trackHead').textContent = st.tracking ? `Siguiendo ${st.trackingLabel || ''} · ${({ sidereal: 'velocidad sideral', lunar: 'velocidad lunar', solar: 'velocidad solar' }[st.trackRate] || '')}` : 'Sin seguimiento: activa «Seguimiento» o haz un GoTo';
    const last = st.track;
    const recent = hist.filter((p) => p.t > (st.time || Date.now()) - 120_000);
    const rms = (k) => (recent.length ? Math.sqrt(recent.reduce((s, p) => s + p[k] * p[k], 0) / recent.length) : null);
    const stat = (k, v, unit) => `<div class="stat"><div class="k">${k}</div><div class="v">${v == null ? '—' : v}${v == null ? '' : ` <small>${unit}</small>`}</div></div>`;
    $('#trackStats').innerHTML = stat('Error acimut', last ? last.errAz.toFixed(1) : null, '″') + stat('Error altura', last ? last.errAlt.toFixed(1) : null, '″')
      + stat('Error medio (2 min)', recent.length ? Math.hypot(rms('errAz'), rms('errAlt')).toFixed(1) : null, '″')
      + stat('Velocidad acimut', last ? last.rateAz.toFixed(2) : null, '″/s') + stat('Velocidad altura', last ? last.rateAlt.toFixed(2) : null, '″/s')
      + stat('Cable', st.link ? st.link.ms : null, 'ms');
    lineChart($('#errChart'), hist, [['errAz', 'Acimut', '--chart-a'], ['errAlt', 'Altura', '--chart-b']], true);
    lineChart($('#rateChart'), hist, [['rateAz', 'Acimut', '--chart-a'], ['rateAlt', 'Altura', '--chart-b']], false);
  }
  function lineChart(cv, data, series, symmetric) {
    const dpr = window.devicePixelRatio || 1, w = cv.clientWidth, h = cv.clientHeight;
    if (!w) return;
    cv.width = w * dpr; cv.height = h * dpr;
    const c = cv.getContext('2d'); c.setTransform(dpr, 0, 0, dpr, 0, 0);
    const L = 46, R = 90, T = 10, B = 20;
    c.font = '11px ' + cssVar('--font');
    if (data.length < 2) { c.fillStyle = cssVar('--text-3'); c.textAlign = 'center'; c.fillText('Sin datos todavía', w / 2, h / 2); return; }
    const t1 = data[data.length - 1].t, t0 = t1 - 600_000;
    let lo = Infinity, hi = -Infinity;
    for (const p of data) for (const [k] of series) { lo = Math.min(lo, p[k]); hi = Math.max(hi, p[k]); }
    if (symmetric) { const m = Math.max(5, Math.abs(lo), Math.abs(hi)); lo = -m; hi = m; } else { const pad = Math.max(0.5, (hi - lo) * 0.15); lo -= pad; hi += pad; }
    const X = (t) => L + ((t - t0) / (t1 - t0)) * (w - L - R);
    const Y = (v) => T + (1 - (v - lo) / (hi - lo)) * (h - T - B);
    c.strokeStyle = cssVar('--line'); c.fillStyle = cssVar('--text-3'); c.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const v = lo + ((hi - lo) * i) / 4; c.beginPath(); c.moveTo(L, Y(v)); c.lineTo(w - R, Y(v)); c.stroke();
      c.textAlign = 'right'; c.textBaseline = 'middle'; c.fillText(v.toFixed(Math.abs(hi - lo) < 10 ? 1 : 0), L - 6, Y(v));
    }
    c.textAlign = 'center'; c.textBaseline = 'top';
    for (let m = 0; m <= 10; m += 2) c.fillText(m === 0 ? 'ahora' : `−${m} min`, X(t1 - m * 60_000), h - B + 4);
    series.forEach(([k, label, color], idx) => {
      c.strokeStyle = cssVar(color); c.lineWidth = 1.8; c.beginPath();
      let first = true;
      for (const p of data) { if (p.t < t0) continue; const x = X(p.t), y = Y(p[k]); if (first) c.moveTo(x, y); else c.lineTo(x, y); first = false; }
      c.stroke();
      c.fillStyle = cssVar(color); c.fillRect(w - R + 14, T + 6 + idx * 18, 10, 3);
      c.fillStyle = cssVar('--text-2'); c.textAlign = 'left'; c.textBaseline = 'middle'; c.fillText(label, w - R + 30, T + 8 + idx * 18);
    });
  }

  // ------------------------------------------------------------------ slack
  function renderSlack() {
    const st = S.info.settings || {};
    if (document.activeElement !== $('#slackAz') && st.slackAz != null) $('#slackAz').value = st.slackAz;
    if (document.activeElement !== $('#slackAlt') && st.slackAlt != null) $('#slackAlt').value = st.slackAlt;
    $('#takeUp').checked = st.manualTakeUp !== false;
  }
  $('#saveSlack').addEventListener('click', () => {
    const az = Number($('#slackAz').value), alt = Number($('#slackAlt').value);
    if (!(az >= 0 && az <= 3) || !(alt >= 0 && alt <= 3)) { toast('La holgura va de 0 a 3 grados', true); return; }
    send({ type: 'setSetting', key: 'slackAz', value: String(az) });
    send({ type: 'setSetting', key: 'slackAlt', value: String(alt) });
    toast('Holgura guardada');
  });
  $('#takeUp').addEventListener('change', (e) => send({ type: 'setSetting', key: 'manualTakeUp', value: String(e.target.checked) }));
  function renderGear() {
    if (S.view !== 'slack') return;
    const st = S.info.settings || {}, gear = S.status.gear || {};
    $('#gearBars').innerHTML = [['azm', 'Acimut', st.slackAz], ['alt', 'Altitud', st.slackAlt]].map(([k, name, slack]) => {
      const off = gear[k];
      const pos = slack > 0 && off != null ? 50 + (off / slack) * 100 : 50;
      return `<div class="gear"><div class="gear-head"><span>${name}</span><span>${off == null ? 'sin saber' : `${off >= 0 ? '+' : ''}${off.toFixed(2)}° de ±${((slack || 0) / 2).toFixed(2)}°`}</span></div>
        <div class="gear-track"><span class="end" style="left:8px">− cargado</span><span class="end" style="right:8px">cargado +</span><div class="mid"></div>
          ${off == null ? '' : `<div class="knob" style="left:${Math.max(3, Math.min(97, pos))}%"></div>`}</div></div>`;
    }).join('');
  }
  $$('[data-visual]').forEach((b) => b.addEventListener('click', async () => {
    if (!(await confirmBox('Medir la holgura con el ocular', 'El telescopio se moverá un poco hacia un lado y luego, muy despacio, hacia el otro. Ten una estrella centrada.', 'Empezar'))) return;
    S.visual = { axis: b.dataset.visual, phase: 'preparing' }; renderVisual();
    send({ type: 'slackVisualStart', axis: b.dataset.visual });
  }));
  // Slack by centring a star with opposite last touches (the precise method).
  /** The arrow that sends [cmd] (+1 = "pos") on [axis], with the user's inversions. */
  function arrowFor(axis, cmd) {
    const button = invert(axis, cmd > 0 ? 'pos' : 'neg'); // invert() is its own inverse
    return axis === 'azm' ? (button === 'pos' ? '→' : '←') : (button === 'pos' ? '↑' : '↓');
  }
  function renderCenter() {
    const c = S.center, box = $('#centerBody');
    if (!c || !c.active) {
      const stars = (S.alignment.suggestions || []).filter((x) => x.cat === 'star');
      const chosen = stars.some((x) => x.id === S.centerStar) ? S.centerStar : stars[0]?.id;
      box.innerHTML = stars.length ? `<div class="center-start">
          <label class="inline">Estrella<select id="centerStar">${stars.map((x) => `<option value="${esc(x.id)}" ${x.id === chosen ? 'selected' : ''}>${esc(x.id)} · ${esc(x.dir)} · ${x.alt.toFixed(0)}° de altura</option>`).join('')}</select></label>
          <button class="btn primary" id="centerStart" ${S.status.connected ? '' : 'disabled'}>Empezar</button></div>
        <p class="muted">Mejor una estrella brillante entre 20° y 60° de altura y el ocular de más aumento. El seguimiento se apaga mientras mides.</p>`
        : '<p class="muted">No hay estrellas brillantes visibles ahora mismo.</p>';
      $('#centerStar')?.addEventListener('change', (e) => { S.centerStar = e.target.value; });
      $('#centerStart')?.addEventListener('click', () => { S.centerStar = $('#centerStar').value; send({ type: 'slackCenterStart', id: S.centerStar }); });
      return;
    }
    const n = c.marks;
    const next = `<span class="arrow">${arrowFor('azm', c.azm.next)}</span> y <span class="arrow">${arrowFor('alt', c.alt.next)}</span>`;
    const away = `<span class="arrow">${arrowFor('azm', -c.azm.next)}</span> y <span class="arrow">${arrowFor('alt', -c.alt.next)}</span>`;
    const step = n === 0
      ? `<b>1.</b> Centra <b>${esc(c.star)}</b> terminando con ${next}. Si te pasas, vuelve atrás y acércate otra vez: el <b>último toque</b> de cada eje tiene que ser ese y tiene que mover la estrella. Después pulsa «Centrada».`
      : `<b>${n + 1}.</b> Desplázala con ${away} hasta que se aleje del centro y vuelve a centrarla <b>solo</b> con ${next}. Los primeros grados no la moverán (es la holgura): usa velocidad media para eso y toques finos al final. Pulsa «Centrada».`;
    const warn = [];
    const prev = S.centerPrev?.active && S.centerPrev.marks === n - 1 ? S.centerPrev : null;
    for (const [k, name] of [['azm', 'acimut'], ['alt', 'altitud']]) {
      if (n >= 1 && !c[k].last) warn.push(`No he visto moverse el motor de ${name} antes de este centrado: termina con un toque que mueva la estrella.`);
      else if (prev && n >= 2 && prev[k].last && c[k].last === prev[k].last) warn.push(`En ${name} terminaste en el mismo sentido que la vez anterior: esa pareja no cuenta para ${name}.`);
    }
    const nat = c.native;
    if (nat && (nat.azm.pos || nat.azm.neg || nat.alt.pos || nat.alt.neg)) {
      warn.push(`El mando tiene anti-backlash propio (acimut +${nat.azm.pos}/−${nat.azm.neg}, altitud +${nat.alt.pos}/−${nat.alt.neg}): mientras esté puesto la medida no es la holgura real. Mejor «Poner a 0» (abajo) y empezar de nuevo.`);
    }
    const row = (k, name) => {
      const a = c[k];
      return `<tr><td>${name}</td><td>${a.samples.length ? a.samples.map((x) => x.toFixed(2) + '°').join(' · ') : '—'}</td>
        <td class="center-result">${a.slack != null ? `<b>${a.slack.toFixed(2)}°</b>${a.samples.length > 1 ? ` <span class="muted">± ${(a.spread / 2).toFixed(2)}</span>` : ''}` : '—'}</td>
        <td>${a.saved.toFixed(2)}°</td></tr>`;
    };
    const canSave = c.azm.slack != null || c.alt.slack != null;
    box.innerHTML = `<div class="center-step">${step}</div>
      ${warn.map((w) => `<div class="center-warn">${esc(w)}</div>`).join('')}
      <div class="row-actions"><button class="btn primary" id="centerMark">Centrada ✓</button>
        <button class="btn" id="centerSave" ${canSave ? '' : 'disabled'}>Guardar holgura</button>
        <button class="btn ghost" id="centerStop">Terminar</button>
        <span class="muted">${n} centrado${n === 1 ? '' : 's'} de ${esc(c.star)}</span></div>
      <table class="table" style="margin-top:12px"><tr><th>Eje</th><th>Medidas</th><th>Holgura</th><th>Usando ahora</th></tr>${row('azm', 'Acimut')}${row('alt', 'Altitud')}</table>`;
    $('#centerMark').addEventListener('click', () => { releaseAll(true); send({ type: 'slackCenterMark' }); });
    $('#centerSave').addEventListener('click', () => send({ type: 'slackCenterSave' }));
    $('#centerStop').addEventListener('click', () => send({ type: 'slackCenterStop' }));
  }

  // ------------------------------------------------------------------ photos from the iPhone
  /** Live stacks first, then the saved photos, newest first (the brain's own order). */
  function sortPhotos(list) {
    return list.slice().sort((a, b) => (b.live ? 1 : 0) - (a.live ? 1 : 0) || (b.at || 0) - (a.at || 0)).slice(0, 60);
  }
  /** The image itself: needs the access key; v changes with every upload of a live stack. */
  function photoUrl(p) {
    return `photos/${encodeURIComponent(p.id)}.jpg?k=${encodeURIComponent(accessKey() || '')}&v=${p.v ?? 0}`;
  }
  const fmtSeconds = (t) => (t < 1 ? `${t.toFixed(2)} s` : t < 120 ? `${t.toFixed(t < 10 ? 1 : 0)} s` : `${(t / 60).toFixed(t < 600 ? 1 : 0)} min`);
  function photoText(p) {
    const parts = [];
    if (p.live) parts.push({ stacking: 'Apilando en directo', paused: 'Apilado en pausa', stopped: 'Apilado terminado' }[p.state] || 'En directo');
    if (p.at) { const d = new Date(p.at); parts.push(`${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`); }
    if (p.frames > 1) parts.push(`${p.frames} tomas`);
    if (p.exposureS) parts.push(`${fmtSeconds(p.exposureS)}${p.frames > 1 ? ' cada una' : ''}`);
    const total = p.integrationS ?? (p.frames > 1 && p.exposureS ? p.frames * p.exposureS : null);
    if (total && p.frames > 1) parts.push(`total ${fmtSeconds(total)}`);
    if (p.w && p.h) parts.push(`${p.w}×${p.h}`);
    return parts.join(' · ');
  }
  function photoName(p) {
    return p.label || (p.objectId ? titleOf(S.objects[p.objectId] || { id: p.objectId }) : p.live ? 'Apilado del iPhone' : 'Foto del iPhone');
  }
  function renderPhotos() {
    const badge = $('#photoBadge');
    badge.textContent = S.photoUnread > 9 ? '9+' : String(S.photoUnread);
    badge.classList.toggle('hidden', !S.photoUnread || S.view === 'photos');
    $('#photoFollow').checked = prefs.get('photoFollow', true);
    if (S.view !== 'photos') return;
    const list = S.photos;
    const sel = list.find((p) => p.id === S.photoSel) || list[0];
    const big = $('#photoBig');
    $('#photoEmpty').classList.toggle('hidden', !!sel);
    big.classList.toggle('hidden', !sel);
    $('#photoInfo').classList.toggle('hidden', !sel);
    for (const id of ['#photoPrev', '#photoNext', '#photoDownload', '#photoFull']) $(id).classList.toggle('hidden', !sel);
    if (sel) {
      const src = photoUrl(sel);
      if (big.getAttribute('src') !== src) big.setAttribute('src', src);
      $('#photoInfo').innerHTML = `<b>${sel.live ? '<span class="live-dot"></span>' : ''}${esc(photoName(sel))}</b>${esc(photoText(sel))}`;
      const dl = $('#photoDownload');
      dl.href = src;
      dl.setAttribute('download', `${(sel.label || sel.objectId || 'starbridge').replace(/[^\w.-]+/g, '_')}_${sel.live ? 'directo_' + (sel.v ?? 0) : sel.id}.jpg`);
      const idx = list.indexOf(sel);
      $('#photoPrev').disabled = idx <= 0;
      $('#photoNext').disabled = idx >= list.length - 1;
      $('#photoGoto').classList.toggle('hidden', !sel.objectId);
      $('#photoGoto').textContent = sel.objectId ? `Ir a ${sel.objectId}` : '';
    } else $('#photoGoto').classList.add('hidden');
    $('#photoStrip').innerHTML = list.length ? list.map((p) => `
      <button data-photo="${esc(p.id)}" class="${sel && p.id === sel.id ? 'sel' : ''}" title="${esc(photoName(p))}">
        <img loading="lazy" src="${esc(photoUrl(p))}" alt=""><span>${p.live ? '● ' : ''}${esc(photoName(p))}${p.at && !p.live ? ' · ' + fmtTime(p.at) : ''}</span></button>`).join('')
      : '<div class="muted">Las fotos del iPhone irán apareciendo aquí.</div>';
    $$('#photoStrip [data-photo]').forEach((b) => b.addEventListener('click', () => {
      S.photoSel = b.dataset.photo;
      // Picking something other than the newest by hand stops jumping to each new one.
      if (S.photos[0]?.id !== S.photoSel) prefs.set('photoFollow', false);
      renderPhotos();
    }));
  }
  function stepPhoto(d) {
    const list = S.photos, i = list.findIndex((p) => p.id === S.photoSel);
    const j = Math.max(0, Math.min(list.length - 1, (i < 0 ? 0 : i) + d));
    if (!list[j]) return;
    S.photoSel = list[j].id;
    if (j > 0) prefs.set('photoFollow', false);
    renderPhotos();
  }
  function photoFullscreen() {
    if (document.fullscreenElement) document.exitFullscreen?.(); else $('#photoStage').requestFullscreen?.();
  }
  $('#photoPrev').addEventListener('click', () => stepPhoto(-1));
  $('#photoNext').addEventListener('click', () => stepPhoto(1));
  $('#photoFull').addEventListener('click', photoFullscreen);
  $('#photoBig').addEventListener('dblclick', photoFullscreen);
  $('#photoFollow').addEventListener('change', (e) => {
    prefs.set('photoFollow', e.target.checked);
    if (e.target.checked && S.photos.length) { S.photoSel = S.photos[0].id; renderPhotos(); }
  });
  $('#photoGoto').addEventListener('click', () => {
    const p = S.photos.find((x) => x.id === S.photoSel);
    if (p?.objectId) goTo(S.objects[p.objectId] || { id: p.objectId });
  });

  // ------------------------------------------------------------------ the iPhone camera
  const CAM = 'iphone';
  const ACTIVITY = {
    idle: 'En espera', preparing: 'Preparando', capturing: 'Apilando', paused: 'En pausa', framing: 'Encuadre',
    focusing: 'Enfocando', darks: 'Tomando darks', flats: 'Tomando flats', optimizing: 'Optimizando', characterizing: 'Midiendo la cámara', replaying: 'Reprocesando',
  };
  /** Activities where the camera is busy on its own: only STOP-like actions make sense then. */
  const CAM_BUSY = new Set(['preparing', 'focusing', 'darks', 'flats', 'optimizing', 'characterizing', 'replaying']);
  function camCmd(cmd, extra = {}) {
    if (send(Object.assign({ type: 'camCmd', source: CAM, cmd }, extra))) {
      // Driving the camera from here means looking at what it sees.
      if (cmd === 'start' || cmd === 'framing' || cmd === 'newStack') {
        prefs.set('photoFollow', true);
        const live = S.photos.find((p) => p.live && p.source === CAM) || S.photos.find((p) => p.live);
        if (live) { S.photoSel = live.id; renderPhotos(); }
      }
    }
  }
  function renderCam() {
    const c = S.cams[CAM];
    const on = !!(c && c.online !== false);
    const act = on ? (c.activity || 'idle') : null;
    const pill = $('#camPill');
    pill.classList.toggle('hidden', !on);
    if (on) {
      pill.className = 'pill' + (act === 'capturing' || act === 'framing' ? ' ok busy' : act === 'paused' ? ' warn' : '');
      pill.textContent = `iPhone · ${ACTIVITY[act] || act}${act === 'capturing' && c.stacked != null ? ' · ' + c.stacked : ''}`;
    }
    const bar = $('#camBar');
    if (!on) {
      bar.innerHTML = `<div class="cam-state"><span class="dot"></span>Cámara del iPhone</div>
        <div class="cam-offline">${c ? 'Desconectada.' : 'No conectada.'} Abre StarBridge EAA en el iPhone, emparejado con este PC, para manejarla desde aquí.</div>`;
      S.camExpOpen = false; // (the skeleton is rebuilt when it comes back)
      return;
    }
    const busy = CAM_BUSY.has(act);
    const stats = [];
    if (c.stacked != null) stats.push(`<span><b>${c.stacked}</b> apiladas${c.seen != null ? ` de ${c.seen}` : ''}${c.rejected ? ` · ${c.rejected} descartadas` : ''}</span>`);
    if (c.integrationS) stats.push(`<span>total <b>${fmtSeconds(c.integrationS)}</b></span>`);
    if (c.exposureS || c.iso) stats.push(`<span>${c.exposureS ? `<b>${fmtSeconds(c.exposureS)}</b>` : ''}${c.iso ? ` · ISO <b>${c.iso}</b>` : ''}${c.autoExposure ? ' (auto)' : ''}</span>`);
    // Settings changed from here apply to the next stack: say so until they are in use.
    if (c.autoExposure === false && c.exposureSetting != null && (c.exposureSetting !== c.exposureS || (c.isoSetting != null && c.isoSetting !== c.iso))) {
      stats.push(`<span>próximo: ${fmtSeconds(c.exposureSetting)}${c.isoSetting ? ` · ISO ${c.isoSetting}` : ''}</span>`);
    }
    const startLabel = act === 'paused' ? 'Reanudar' : 'Empezar';
    const buttons = [
      act === 'capturing'
        ? '<button class="btn" data-cam="pause">Pausar</button>'
        : `<button class="btn primary" data-cam="start" ${busy || act === 'framing' ? 'disabled' : ''}>${startLabel}</button>`,
      act === 'framing'
        ? '<button class="btn primary" data-cam="stopFraming">Terminar encuadre</button>'
        : `<button class="btn" data-cam="framing" ${busy || act === 'capturing' ? 'disabled' : ''}>Encuadre</button>`,
      `<button class="btn" data-cam="save" ${c.stacked ? '' : 'disabled'}>Guardar</button>`,
      // The iPhone's lens autofocus on the stars (a few minutes; it also does it before the first stack).
      `<button class="btn" data-cam="focus" title="Barrido de enfoque del iPhone sobre las estrellas (tarda unos minutos)" ${busy || act === 'capturing' ? 'disabled' : ''}>${act === 'focusing' ? 'Enfocando…' : 'Enfocar'}</button>`,
      `<button class="btn" data-cam="newStack" ${busy ? 'disabled' : ''}>Nuevo apilado</button>`,
      `<button class="btn ghost" data-cam="exposure">Exposición ${S.camExpOpen ? '▾' : '▴'}</button>`,
    ];
    // Fixed parts, refreshed in place: the exposure card must survive the status updates
    // (every few seconds) or it would lose what is being typed in it.
    if (!$('#camBar .cam-actions')) {
      bar.innerHTML = '<div class="cam-state"></div><div class="cam-msg"></div><div class="cam-stats"></div><div class="cam-actions"></div><div class="cam-log" id="camLog"></div><div id="camExpHost"></div>';
    }
    $('#camBar .cam-state').innerHTML = `<span class="dot ${act === 'capturing' || act === 'framing' || busy ? 'busy' : 'on'}"></span>${esc(c.name || 'iPhone')} · ${esc(ACTIVITY[act] || act)}`;
    const msg = $('#camBar .cam-msg');
    msg.textContent = `${c.target ? `${c.target}${c.message ? ' — ' : ''}` : ''}${c.message || ''}`;
    msg.title = c.message || '';
    $('#camBar .cam-stats').innerHTML = stats.join('');
    $('#camBar .cam-actions').innerHTML = buttons.join('');
    $$('#camBar [data-cam]').forEach((b) => b.addEventListener('click', () => camButton(b.dataset.cam)));
    renderCamLog(c);
    const host = $('#camExpHost');
    if (!S.camExpOpen) host.innerHTML = '';
    else if (!$('#camExp')) { host.innerHTML = camExposureCard(c); bindCamExposure(c); }
  }
  // Why the iPhone keeps or drops each frame, and what it has been saying.
  const FRAME_WORDS = { accepted: 'aceptada', downweighted: 'con menos peso', rejected: 'descartada', pending: 'pendiente', undone: 'deshecha' };
  function frameLine(f) {
    const extra = [];
    if (f.reasons?.length) extra.push(f.reasons.join(', '));
    if (f.stars != null) extra.push(`${f.stars} estrella${f.stars === 1 ? '' : 's'}`);
    if (f.fwhm != null) extra.push(`FWHM ${Number(f.fwhm).toFixed(1)}`);
    if (f.note) extra.push(f.note);
    return `Toma ${f.index} · ${f.decisionText || FRAME_WORDS[f.decision] || f.decision}${extra.length ? ' · ' + extra.join(' · ') : ''}`;
  }
  function renderCamLog(c) {
    const box = $('#camLog');
    if (!box) return;
    const frames = c.frames || [], events = c.events || [];
    if (!frames.length && !events.length) { box.innerHTML = ''; box.classList.add('hidden'); return; }
    box.classList.remove('hidden');
    const n = S.camLogAll ? 6 : 3;
    const time = (ms) => { const d = new Date(ms); return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`; };
    box.innerHTML = `<div class="col">${frames.slice(0, n).map((f) => {
        const t = frameLine(f), head = t.split(' · ').slice(0, 2).join(' · '), rest = t.split(' · ').slice(2).join(' · ');
        return `<div class="line ${esc(f.decision)}" title="${esc(t)}"><b>${esc(head)}</b>${rest ? ' · ' + esc(rest) : ''}</div>`;
      }).join('')}</div>
      <div class="col">${events.slice(0, n).map((e) => `<div class="line ${esc(e.kind || '')}" title="${esc(e.text)}">${e.at ? time(e.at) + ' · ' : ''}${esc(e.text)}</div>`).join('')}
        ${frames.length > 3 || events.length > 3 ? `<button class="more" id="camLogMore">${S.camLogAll ? 'Ver menos' : 'Ver más'}</button>` : ''}</div>`;
    $('#camLogMore')?.addEventListener('click', () => { S.camLogAll = !S.camLogAll; renderCamLog(S.cams[CAM] || c); });
  }
  /** A notice for each newly rejected frame and each new warning, once (not the backlog on connect). */
  function camNews(c, first) {
    const seen = (S.camSeen = S.camSeen || { frame: -1, event: 0 });
    // A new stack numbers its frames from the start again.
    const top = Math.max(-1, ...(c.frames || []).map((f) => f.index));
    if (top < seen.frame - 6) seen.frame = -1;
    const rejected = (c.frames || []).filter((f) => f.decision === 'rejected' && f.index > seen.frame);
    const warnings = (c.events || []).filter((e) => (e.kind === 'warning' || e.kind === 'guardian') && e.at > seen.event);
    seen.frame = Math.max(seen.frame, ...(c.frames || []).map((f) => f.index));
    seen.event = Math.max(seen.event, ...(c.events || []).map((e) => e.at || 0));
    if (first) return;
    // Rejections: gathered, at most one notice every 30 s, none while Fotos (with its log) is open.
    if (rejected.length) {
      seen.pending = (seen.pending || 0) + rejected.length;
      seen.lastReason = rejected[0].reasons?.join(', ') || seen.lastReason || '';
    }
    const now = Date.now();
    if (seen.pending && S.view !== 'photos' && now - (seen.toastAt || 0) > 30_000) {
      toast(`iPhone: ${seen.pending === 1 ? '1 toma descartada' : `${seen.pending} tomas descartadas`}${seen.lastReason ? ' · ' + seen.lastReason : ''}`, true);
      seen.toastAt = now; seen.pending = 0;
    }
    if (S.view === 'photos') seen.pending = 0;
    for (const e of warnings.slice(0, 2)) toast(`iPhone: ${e.text}`, e.kind === 'guardian');
  }

  function camExposureCard(c) {
    const auto = c.autoExposure !== false;
    return `<div class="cam-exp" id="camExp">
      <div class="switch-row"><div><div class="row-title">Exposición automática</div><div class="muted">El iPhone elige tiempo e ISO.</div></div>
        <label class="switch"><input id="camAuto" type="checkbox" ${auto ? 'checked' : ''}><span></span></label></div>
      <div class="form-grid">
        <label>Tiempo (s)<input id="camExpS" type="number" min="0.01" max="60" step="0.1" value="${c.exposureSetting ?? c.exposureS ?? ''}" ${auto ? 'disabled' : ''}></label>
        <label>ISO<input id="camIso" type="number" min="25" max="12800" step="1" value="${c.isoSetting ?? c.iso ?? ''}" ${auto ? 'disabled' : ''}></label>
      </div>
      <div class="muted">Se aplica al próximo apilado.</div>
      <div class="row-actions"><button class="btn primary" id="camApply">Aplicar</button><button class="btn ghost" id="camExpClose">Cerrar</button></div>
    </div>`;
  }
  function bindCamExposure() {
    $('#camAuto').addEventListener('change', (e) => {
      $('#camExpS').disabled = e.target.checked;
      $('#camIso').disabled = e.target.checked;
    });
    $('#camExpClose').addEventListener('click', () => { S.camExpOpen = false; renderCam(); });
    $('#camApply').addEventListener('click', () => {
      const auto = $('#camAuto').checked;
      const extra = { autoExposure: auto };
      if (!auto) {
        const t = Number($('#camExpS').value), iso = Number($('#camIso').value);
        if (!(t > 0 && t <= 60) || !(iso >= 25 && iso <= 12800)) { toast('Tiempo entre 0,01 y 60 s; ISO entre 25 y 12800', true); return; }
        extra.exposureS = t; extra.iso = Math.round(iso);
      }
      camCmd('settings', extra);
      toast(auto ? 'Enviado al iPhone: exposición automática' : `Enviado al iPhone: ${extra.exposureS} s · ISO ${extra.iso}`);
      S.camExpOpen = false;
      renderCam();
    });
  }
  async function camButton(what) {
    if (what === 'exposure') { S.camExpOpen = !S.camExpOpen; renderCam(); return; }
    if (what === 'newStack') {
      const c = S.cams[CAM];
      if (c?.stacked) {
        const choice = await new Promise((resolve) => {
          const card = modal(`<h2>Nuevo apilado</h2><p class="muted">El apilado actual lleva ${c.stacked} tomas. ¿Lo guardas antes de empezar otro?</p>
            <div class="row-actions" style="justify-content:flex-end"><button class="btn ghost" data-close>Cancelar</button>
              <button class="btn" id="nsDiscard">Descartar</button><button class="btn primary" id="nsSave">Guardar y empezar</button></div>`);
          $('#nsSave', card).addEventListener('click', () => { closeModal(); resolve('save'); });
          $('#nsDiscard', card).addEventListener('click', () => { closeModal(); resolve('discard'); });
          $('[data-close]', card).addEventListener('click', () => resolve(null));
        });
        if (!choice) return;
        camCmd('newStack', choice === 'save' ? { save: true } : {});
      } else camCmd('newStack');
      return;
    }
    camCmd(what);
  }

  // ------------------------------------------------------------------ several windows
  const GEO_KEY = `geo.${WIN || 'main'}`;
  /** Where each kind of window opens the first time (afterwards, where the user left it). */
  function defaultGeometry(view) {
    const L = screen.availLeft || 0, T = screen.availTop || 0, W = screen.availWidth, H = screen.availHeight;
    if (view === 'control') return { x: L + W - 400, y: T + 20, w: 380, h: Math.min(900, H - 40) };
    if (view === 'photos') return { x: L + 40, y: T + 40, w: Math.min(1180, W - 460), h: Math.min(820, H - 80) };
    return { x: L + 80, y: T + 60, w: Math.min(1100, W - 160), h: Math.min(800, H - 120) };
  }
  // Each window puts itself back where it was and remembers where it is now.
  (() => {
    const g = prefs.get(GEO_KEY, null);
    if (g && g.w > 200 && g.h > 200) { try { window.resizeTo(g.w, g.h); window.moveTo(g.x, g.y); } catch { /* not allowed here */ } }
  })();
  let lastGeo = '';
  function saveGeometry() {
    const g = { x: window.screenX, y: window.screenY, w: window.outerWidth, h: window.outerHeight };
    const txt = JSON.stringify(g);
    if (txt !== lastGeo && g.w > 200 && g.h > 200) { lastGeo = txt; prefs.set(GEO_KEY, g); }
  }
  setInterval(saveGeometry, 2000);
  window.addEventListener('beforeunload', saveGeometry);

  // Which windows are open right now (every window of this panel says hello on a channel).
  const winChannel = 'BroadcastChannel' in window ? new BroadcastChannel('starbridge-windows') : null;
  const winId = Math.random().toString(36).slice(2);
  const otherWindows = new Map();
  const announce = (type = 'here') => winChannel?.postMessage({ type, win: WIN || 'main', id: winId });
  if (winChannel) {
    winChannel.onmessage = (e) => {
      const m = e.data || {};
      if (m.type === 'bye') otherWindows.delete(m.id);
      else { otherWindows.set(m.id, { win: m.win, at: Date.now() }); if (m.type === 'hello') announce(); }
      if (!$('#winMenu').classList.contains('hidden')) renderWinMenu();
    };
    announce('hello');
    setInterval(announce, 3000);
    window.addEventListener('beforeunload', () => announce('bye'));
  }
  function openSections() {
    const now = Date.now(), set = new Set(WIN ? [WIN] : []);
    for (const [id, w] of otherWindows) { if (now - w.at > 8000) otherWindows.delete(id); else if (w.win !== 'main') set.add(w.win); }
    return Object.keys(WIN_NAMES).filter((v) => set.has(v));
  }
  function openWindow(view) {
    const g = prefs.get(`geo.${view}`, null) || defaultGeometry(view);
    const url = `desk.html?k=${encodeURIComponent(accessKey() || '')}&win=${view}`;
    const w = window.open(url, `starbridge-${view}`, `popup,width=${g.w},height=${g.h},left=${g.x},top=${g.y}`);
    if (!w) { toast('No se pudo abrir la ventana: permite las ventanas emergentes de StarBridge', true); return; }
    w.focus();
    setTimeout(renderWinMenu, 600);
  }
  let savedLayout = null;
  async function loadLayout() {
    try {
      const r = await fetch(`desk-layout?k=${encodeURIComponent(accessKey() || '')}`, { cache: 'no-store' });
      savedLayout = r.ok ? (await r.text()).split(',').map((x) => x.trim()).filter((x) => WIN_NAMES[x]) : null;
    } catch { savedLayout = null; }
    renderWinMenu();
  }
  async function saveLayout(list) {
    try {
      const r = await fetch(`desk-layout?k=${encodeURIComponent(accessKey() || '')}`, { method: 'POST', body: list.join(',') });
      if (!r.ok) throw new Error(String(r.status));
      savedLayout = list;
      toast(list.length ? `Al arrancar se abrirán: ${list.map((v) => WIN_NAMES[v]).join(', ')}` : 'Al arrancar solo se abrirá la ventana principal');
    } catch { toast('Esto solo se puede guardar en el programa del PC', true); }
    renderWinMenu();
  }
  function renderWinMenu() {
    const menu = $('#winMenu');
    if (menu.classList.contains('hidden')) return;
    const open = openSections();
    const names = (list) => list.map((v) => WIN_NAMES[v]).join(', ');
    const icons = { sky: 'sky', objects: 'list', photos: 'camera', control: 'dpad', align: 'target', tracking: 'chart', slack: 'gears', console: 'terminal', settings: 'settings' };
    menu.innerHTML = `<div class="card-title">Abrir en otra ventana</div>
      <div class="win-grid">${Object.entries(WIN_NAMES).map(([v, n]) => `<button data-open="${v}" class="${open.includes(v) ? 'open' : ''}">${icon(icons[v])}<span>${n}</span></button>`).join('')}</div>
      <p class="muted">Colócalas y dales el tamaño que quieras: cada una lo recuerda. Todas tienen su STOP.</p>
      <div class="sep"></div>
      <div class="muted">Abiertas ahora: ${open.length ? names(open) : 'solo esta'}</div>
      <div class="row-actions"><button class="btn primary" id="winSave" ${open.length ? '' : 'disabled'}>Abrir estas al arrancar</button>
        <button class="btn ghost" id="winClear">Ninguna</button></div>
      <p class="muted">${savedLayout == null ? '' : savedLayout.length ? `Al arrancar el programa: ventana principal + ${names(savedLayout)}` : 'Al arrancar el programa: solo la ventana principal'}</p>`;
    $$('#winMenu [data-open]').forEach((b) => b.addEventListener('click', () => openWindow(b.dataset.open)));
    $('#winSave').addEventListener('click', () => saveLayout(openSections()));
    $('#winClear').addEventListener('click', () => saveLayout([]));
  }
  function closeWinMenu() { $('#winMenu').classList.add('hidden'); }
  $('#winBtn').addEventListener('click', (e) => {
    e.stopPropagation();
    const menu = $('#winMenu');
    if (!menu.classList.contains('hidden')) { closeWinMenu(); return; }
    menu.classList.remove('hidden');
    renderWinMenu();
    loadLayout();
  });
  document.addEventListener('click', (e) => { if (!e.target.closest('#winMenu, #winBtn')) closeWinMenu(); });
  // A section window can show the telescope controls beside it (remembered per window).
  if (WIN && WIN !== 'control') {
    const key = `pad.${WIN}`;
    $('#padToggle').classList.remove('hidden');
    const apply = () => { document.body.classList.toggle('with-inspector', prefs.get(key, false)); requestAnimationFrame(() => { if (S.view === 'sky') drawSky(); if (S.view === 'tracking') renderTracking(); }); };
    $('#padToggle').addEventListener('click', () => { prefs.set(key, !prefs.get(key, false)); apply(); });
    apply();
  }

  // ------------------------------------------------------------------ visible zones
  /** Points along the great circles between the corners (a window frame is straight in space). */
  function zoneOutline(points) {
    const out = [];
    for (let i = 0; i < points.length; i++) {
      const a = vec(points[i][0], points[i][1]), b = vec(points[(i + 1) % points.length][0], points[(i + 1) % points.length][1]);
      for (let k = 0; k < 16; k++) {
        const t = k / 16;
        const v = norm([a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t]);
        out.push([((Math.atan2(v[0], v[1]) / DEG) + 360) % 360, Math.asin(Math.max(-1, Math.min(1, v[2]))) / DEG]);
      }
    }
    return out;
  }
  function zonePath(c, points) {
    c.beginPath();
    zoneOutline(points).forEach(([az, alt], i) => { const p = projectRaw(az, alt); if (i) c.lineTo(p[0], p[1]); else c.moveTo(p[0], p[1]); });
    c.closePath();
  }
  let shade = null;
  function drawZones(w, h, dpr) {
    const editing = S.zoneEdit;
    const shown = S.zones.filter((z) => z.on && (!editing || z.id !== editing.id));
    const zoneList = editing && editing.points.length >= 3 ? shown.concat([{ points: editing.points, on: true }]) : shown;
    if (zoneList.length) {
      // Darken everything outside the zones: shade the whole sky, then cut the zones out of it.
      shade = shade || document.createElement('canvas');
      if (shade.width !== canvas.width || shade.height !== canvas.height) { shade.width = canvas.width; shade.height = canvas.height; }
      const sc = shade.getContext('2d');
      sc.setTransform(1, 0, 0, 1, 0, 0); sc.clearRect(0, 0, shade.width, shade.height);
      sc.setTransform(dpr, 0, 0, dpr, 0, 0);
      sc.fillStyle = 'rgba(0, 0, 0, 0.62)'; sc.fillRect(0, 0, w, h);
      sc.globalCompositeOperation = 'destination-out';
      for (const z of zoneList) { zonePath(sc, z.points); sc.fill(); }
      sc.globalCompositeOperation = 'source-over';
      ctx.save(); ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.drawImage(shade, 0, 0); ctx.restore();
    }
    // Outlines and names; zones that are off only as a faint dashed line.
    ctx.font = '600 12px ' + cssVar('--font'); ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    for (const z of S.zones) {
      if (editing && z.id === editing.id) continue;
      ctx.strokeStyle = cssVar('--green'); ctx.lineWidth = z.on ? 1.8 : 1; ctx.globalAlpha = z.on ? 0.9 : 0.35;
      if (!z.on) ctx.setLineDash([5, 5]);
      zonePath(ctx, z.points); ctx.stroke(); ctx.setLineDash([]);
      const c = zoneCentre(z.points), p = project(c[0], c[1]);
      if (p && z.on) { ctx.fillStyle = cssVar('--green'); ctx.globalAlpha = 0.85; ctx.fillText(z.name, p[0], p[1] - 0); }
      ctx.globalAlpha = 1;
    }
    if (editing) {
      const pts = editing.points;
      ctx.strokeStyle = cssVar('--accent'); ctx.lineWidth = 2;
      if (pts.length >= 3 && editing.mode !== 'draw') { zonePath(ctx, pts); ctx.stroke(); }
      else if (pts.length) {
        // Open outline while drawing, plus a rubber band to the mouse.
        ctx.beginPath();
        zoneOutline(pts).slice(0, (pts.length - 1) * 16 + 1).forEach(([az, alt], i) => { const p = projectRaw(az, alt); if (i) ctx.lineTo(p[0], p[1]); else ctx.moveTo(p[0], p[1]); });
        if (editing.mouse && editing.mode === 'draw') { ctx.lineTo(editing.mouse[0], editing.mouse[1]); }
        ctx.stroke();
      }
      ctx.fillStyle = cssVar('--accent');
      for (const [az, alt] of pts) { const p = projectRaw(az, alt); ctx.beginPath(); ctx.arc(p[0], p[1], 6, 0, 2 * Math.PI); ctx.fill(); }
    }
  }
  function zoneCentre(points) {
    const s = points.reduce((acc, [az, alt]) => { const v = vec(az, alt); return [acc[0] + v[0], acc[1] + v[1], acc[2] + v[2]]; }, [0, 0, 0]);
    const v = norm(s);
    return [((Math.atan2(v[0], v[1]) / DEG) + 360) % 360, Math.asin(v[2]) / DEG];
  }
  function zoneHandleAt(x, y) {
    const pts = S.zoneEdit?.points || [];
    for (let i = pts.length - 1; i >= 0; i--) {
      const p = projectRaw(pts[i][0], pts[i][1]);
      if ((p[0] - x) ** 2 + (p[1] - y) ** 2 <= 11 * 11) return i;
    }
    return -1;
  }
  function startZoneEdit(mode, zone = null) {
    if (mode === 'scope' && S.status.alt === undefined) {
      toast('Para marcar con el telescopio, StarBridge tiene que saber dónde apunta: alinéalo primero', true);
      return;
    }
    S.zoneEdit = { mode, id: zone?.id || null, name: zone?.name || '', on: zone ? zone.on : true, points: zone ? zone.points.map((p) => p.slice()) : [], dragging: null, mouse: null };
    $('#zonePanel').classList.add('hidden');
    canvas.classList.toggle('drawing', mode === 'draw');
    if (S.view !== 'sky') showView('sky');
    renderZoneBar(); drawSky();
  }
  function endZoneEdit() {
    S.zoneEdit = null;
    canvas.classList.remove('drawing');
    $('#zoneBar').classList.add('hidden');
    drawSky();
  }
  async function finishZoneEdit() {
    const ze = S.zoneEdit;
    if (!ze) return;
    if (ze.points.length < 3) { toast('Una zona necesita al menos 3 esquinas', true); return; }
    let name = ze.name;
    if (!ze.id) {
      name = await new Promise((resolve) => {
        const n = S.zones.length + 1;
        const card = modal(`<h2>Nombre de la zona</h2><p class="muted">Por ejemplo «Ventana del salón» o «Terraza».</p>
          <input type="text" id="zoneName" value="Zona ${n}" maxlength="40" style="margin:8px 0 4px">
          <div class="row-actions" style="justify-content:flex-end"><button class="btn ghost" data-close>Cancelar</button><button class="btn primary" id="zoneOk">Guardar</button></div>`);
        const input = $('#zoneName', card); input.focus(); input.select();
        const ok = () => { closeModal(); resolve(input.value.trim() || `Zona ${n}`); };
        $('#zoneOk', card).addEventListener('click', ok);
        input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); e.stopPropagation(); ok(); } });
        $('[data-close]', card).addEventListener('click', () => resolve(null));
      });
      if (name == null) return;
    }
    const zone = { name, on: ze.on, points: ze.points.map(([az, alt]) => [Math.round(az * 100) / 100, Math.round(alt * 100) / 100]) };
    if (ze.id) zone.id = ze.id;
    if (send({ type: 'setZone', zone })) { endZoneEdit(); toast(`Zona «${name}» guardada`); }
  }
  function renderZoneBar() {
    const ze = S.zoneEdit, bar = $('#zoneBar');
    if (!ze) { bar.classList.add('hidden'); return; }
    bar.classList.remove('hidden');
    const n = ze.points.length;
    const text = ze.mode === 'draw' ? `Pulsa en el mapa las esquinas de lo que ves (${n}). Intro termina · Retroceso deshace · Esc cancela`
      : ze.mode === 'scope' ? `Apunta el telescopio a una esquina del marco y pulsa «Marcar esquina» (${n} marcadas)`
      : 'Arrastra las esquinas para ajustar la zona';
    bar.innerHTML = `<span>${esc(text)}</span>
      ${ze.mode === 'scope' ? '<button class="btn primary" id="zMark">Marcar esquina</button>' : ''}
      ${ze.mode !== 'edit' && n ? '<button class="btn ghost" id="zUndo">Deshacer</button>' : ''}
      <button class="btn ${ze.mode === 'scope' ? '' : 'primary'}" id="zDone" ${n >= 3 ? '' : 'disabled'}>${ze.id ? 'Guardar' : 'Terminar'}</button>
      <button class="btn ghost" id="zCancel">Cancelar</button>`;
    $('#zMark')?.addEventListener('click', () => {
      const st = S.status;
      if (st.alt === undefined) { toast('StarBridge no sabe dónde apunta: alinéalo primero', true); return; }
      ze.points.push([st.az, Math.max(-10, st.alt)]);
      drawSky(); renderZoneBar();
    });
    $('#zUndo')?.addEventListener('click', () => { ze.points.pop(); drawSky(); renderZoneBar(); });
    $('#zDone').addEventListener('click', finishZoneEdit);
    $('#zCancel').addEventListener('click', endZoneEdit);
  }
  function renderZonePanel() {
    const panel = $('#zonePanel');
    if (panel.classList.contains('hidden')) return;
    panel.innerHTML = `<button class="close" aria-label="Cerrar">✕</button>
      <div class="card-title">Zonas visibles</div>
      <p class="muted">Dibuja lo que ves desde donde estás (una ventana, un hueco entre árboles…). Lo de fuera se oscurece en el mapa, las listas te dicen qué se ve y cada ficha, cuándo pasa por ahí. Lo ven también el iPhone y el Android.</p>
      ${S.zones.map((z) => `<div class="zone-row">
        <label class="switch"><input type="checkbox" data-zone-on="${esc(z.id)}" ${z.on ? 'checked' : ''}><span></span></label>
        <span class="zname" title="${esc(z.name)}">${esc(z.name)}</span>
        <button class="btn ghost" data-zone-edit="${esc(z.id)}">Ajustar</button>
        <button class="btn ghost" data-zone-del="${esc(z.id)}">Borrar</button></div>`).join('') || '<p class="muted">Aún no tienes ninguna.</p>'}
      <div class="row-actions"><button class="btn primary" id="zNewDraw">Dibujar en el mapa</button><button class="btn" id="zNewScope">Con el telescopio</button></div>`;
    $('.close', panel).addEventListener('click', () => panel.classList.add('hidden'));
    $$('[data-zone-on]', panel).forEach((el) => el.addEventListener('change', () => {
      const z = S.zones.find((x) => x.id === el.dataset.zoneOn);
      if (z) send({ type: 'setZone', zone: Object.assign({}, z, { on: el.checked }) });
    }));
    $$('[data-zone-edit]', panel).forEach((el) => el.addEventListener('click', () => startZoneEdit('edit', S.zones.find((x) => x.id === el.dataset.zoneEdit))));
    $$('[data-zone-del]', panel).forEach((el) => el.addEventListener('click', async () => {
      const z = S.zones.find((x) => x.id === el.dataset.zoneDel);
      if (z && await confirmBox('Borrar la zona', `«${z.name}» desaparece en todas las pantallas.`, 'Borrar')) send({ type: 'deleteZone', id: z.id });
    }));
    $('#zNewDraw').addEventListener('click', () => startZoneEdit('draw'));
    $('#zNewScope').addEventListener('click', () => startZoneEdit('scope'));
  }
  $('#zonesBtn').addEventListener('click', () => {
    const panel = $('#zonePanel');
    panel.classList.toggle('hidden');
    renderZonePanel();
  });

  // ------------------------------------------------------------------ leveling with the iPhone
  function renderLevel() {
    const card = $('#levelCard');
    if (!card) return;
    const run = S.level && ['preparing', 'moving', 'measuring'].includes(S.level.phase) ? S.level : null;
    const stored = S.alignment.level;
    const phoneOn = !!(S.cams.iphone && S.cams.iphone.online !== false);
    const mode = S.alignment.mode || S.info.mode;
    let body;
    if (run) {
      const done = run.phase === 'measuring' ? run.step - 0.5 : Math.max(0, run.step - 1);
      body = `<div class="row-title">${esc(run.message)}</div>
        <div class="level-progress"><div style="width:${Math.round((done / (run.of || 4)) * 100)}%"></div></div>
        <p class="muted">No toques el iPhone ni el trípode. STOP lo para en cualquier momento.</p>
        <div class="row-actions"><button class="btn" id="levelCancel">Cancelar</button></div>`;
    } else {
      const result = stored ? `<div class="level-result"><div class="big">${stored.tiltDeg.toFixed(2)}°</div>
          <div><div class="row-title">Inclinación de la base${stored.towardAz != null ? ` hacia el ${esc(compass(stored.towardAz))}` : ''}</div>
          <div class="muted">Medida ${fmtTime(stored.at)} · calidad ±${Math.max(0.01, stored.rmsDeg).toFixed(2)}°. Con esto basta una estrella para alinear.</div></div></div>` : '';
      const fail = S.level?.phase === 'failed' ? `<div class="level-fail">${esc(S.level.message)}</div>` : '';
      body = `<div class="level-grid"><div>
          ${result || '<p class="muted">Mide con el iPhone cuánto y hacia dónde está inclinada la base. Con eso, una sola estrella alinea todo el cielo.</p>'}
          <ol class="steps"><li>Abre StarBridge EAA en el iPhone, con «Compartir la cámara con StarBridge» activado.</li>
            <li>Pon el iPhone <b>sobre el tubo</b>, pantalla arriba, con la parte de arriba hacia la boca del tubo y el lado largo paralelo al tubo. No hace falta que esté recto.</li>
            <li>Aparta los cables: el telescopio girará unos 270° en acimut (2–3 minutos) y volverá.</li></ol>
          ${fail}</div>
        <div><div class="row-actions" style="margin-top:0">
            <button class="btn primary" id="levelStart" ${phoneOn && S.status.connected && mode !== 'hc' ? '' : 'disabled'}>${stored ? 'Medir otra vez' : 'Nivelar con el iPhone'}</button>
            ${stored ? '<button class="btn ghost" id="levelClear">Borrar</button>' : ''}</div>
          <p class="muted">${!S.status.connected ? 'Telescopio no conectado.' : mode === 'hc' ? 'Solo en modo «Alinea StarBridge».' : phoneOn ? 'iPhone conectado.' : 'Esperando al iPhone: abre StarBridge EAA.'}</p></div></div>`;
    }
    card.innerHTML = `<div class="card-title">Nivelar con el iPhone</div>${body}`;
    $('#levelCancel')?.addEventListener('click', () => send({ type: 'levelCancel' }));
    $('#levelClear')?.addEventListener('click', async () => {
      if (await confirmBox('Borrar la nivelación', 'La alineación volverá a calcular la inclinación con las estrellas.', 'Borrar')) send({ type: 'levelClear' });
    });
    $('#levelStart')?.addEventListener('click', async () => {
      if (await confirmBox('Nivelar con el iPhone', 'El telescopio girará solo en acimut unos 270° y volverá. Comprueba que el iPhone está sobre el tubo y que nada se puede enganchar.', 'Empezar')) send({ type: 'levelStart' });
    });
  }
  /** Compass point for an azimuth. */
  function compass(az) {
    const names = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSO', 'SO', 'OSO', 'O', 'ONO', 'NO', 'NNO'];
    return names[Math.round((((az % 360) + 360) % 360) / 22.5) % 16];
  }

  function renderVisual() {
    const v = S.visual, box = $('#visualState');
    if (!v) { box.innerHTML = ''; return; }
    const axis = v.axis === 'azm' ? 'acimut' : 'altitud';
    if (v.phase === 'preparing') box.innerHTML = `<p class="muted">Tensando los engranajes (${axis})…</p><button class="btn big-mark" disabled>¡Se mueve!</button>`;
    else if (v.phase === 'watching') box.innerHTML = `<p class="muted">Ahora muy despacio. Mira la estrella…</p><button class="btn primary big-mark" id="vMark">¡Se mueve!</button>`;
    else if (v.phase === 'done') box.innerHTML = `<p>Holgura de ${axis}: <b>${v.slackDeg.toFixed(2)}°</b> · guardada.</p>`;
    else box.innerHTML = `<p style="color:var(--red)">No se pudo medir: ${esc(v.message || '')}</p>`;
    $('#vMark')?.addEventListener('click', () => send({ type: 'slackVisualMark' }));
  }
  function renderNative() {
    const b = S.backlash;
    if (!b) { $('#nativeTable').innerHTML = '<tr><td class="muted">Pulsa «Leer del mando».</td></tr>'; return; }
    $('#nativeTable').innerHTML = '<tr><th>Eje</th><th>+</th><th>−</th><th></th></tr>' + [['azm', 'Acimut'], ['alt', 'Altitud']].map(([k, name]) => `
      <tr><td>${name}</td><td><input type="number" min="0" max="99" value="${b[k].pos}" data-k="${k}" data-s="pos"></td>
        <td><input type="number" min="0" max="99" value="${b[k].neg}" data-k="${k}" data-s="neg"></td>
        <td style="text-align:right"><button class="btn" data-save="${k}">Guardar</button></td></tr>`).join('');
    $$('[data-save]').forEach((btn) => btn.addEventListener('click', () => saveNative(btn.dataset.save)));
  }
  async function saveNative(k, values) {
    const v = values || { pos: Number($(`[data-k="${k}"][data-s="pos"]`).value), neg: Number($(`[data-k="${k}"][data-s="neg"]`).value) };
    if (![v.pos, v.neg].every((x) => Number.isInteger(x) && x >= 0 && x <= 99)) { toast('Valores entre 0 y 99', true); return; }
    if (!values && !(await confirmBox('Cambiar el anti-backlash del mando', `Se escribe en los motores (${k === 'azm' ? 'acimut' : 'altitud'}: +${v.pos} / −${v.neg}). Queda guardado en el telescopio.`, 'Guardar'))) return;
    send({ type: 'setBacklash', axis: k, pos: v.pos, neg: v.neg });
    toast('Anti-backlash guardado en el telescopio');
  }
  $('#readNative').addEventListener('click', () => send({ type: 'getBacklash' }));
  $('#zeroNative').addEventListener('click', async () => {
    if (!(await confirmBox('Poner el anti-backlash a 0', 'Los dos ejes, en los dos sentidos. StarBridge se encarga de la holgura con su propia recogida.', 'Poner a 0'))) return;
    saveNative('azm', { pos: 0, neg: 0 }); saveNative('alt', { pos: 0, neg: 0 });
  });

  // ------------------------------------------------------------------ console
  const QUICK = [['Kx', 'Eco'], ['V', 'Versión'], ['m', 'Modelo'], ['J', '¿Alineado?'], ['z', 'Az/Alt'], ['e', 'AR/Dec'], ['t', 'Tracking'], ['L', '¿GoTo?'], ['w', 'Lugar']];
  $('#quickCmds').innerHTML = QUICK.map(([c, label]) => `<button data-cmd="${c}" title="${label}">${c} · ${label}</button>`).join('')
    + '<button data-cmd="P 1 16 64 0 0 0 1" title="Anti-backlash acimut +">AB az +</button><button data-cmd="P 1 16 254 0 0 0 2" title="Versión motor acimut">Motor az</button>';
  $$('#quickCmds button').forEach((b) => b.addEventListener('click', () => { $('#rawCmd').value = b.dataset.cmd; $('#rawLen').value = ''; sendRaw(); }));
  const FIXED_LEN = { V: 2, m: 1, J: 1, t: 1, w: 8, K: 1 };
  /** "Kx" → text bytes; "P 2 16 36 6 0 0 0" or "0x50 2 …" → numbers after the first letter. */
  function parseRaw(text) {
    const t = text.trim();
    if (!t) return null;
    const tokens = t.split(/[\s,]+/);
    if (tokens.length > 1 && tokens.slice(1).every((x) => /^(0x[0-9a-f]+|\d+)$/i.test(x))) {
      const first = /^(0x[0-9a-f]+|\d+)$/i.test(tokens[0]) ? [Number(tokens[0])] : tokens[0].split('').map((ch) => ch.charCodeAt(0));
      return first.concat(tokens.slice(1).map(Number));
    }
    return t.split('').map((ch) => ch.charCodeAt(0));
  }
  function sendRaw(confirm = false) {
    const bytes = parseRaw($('#rawCmd').value);
    if (!bytes || bytes.some((b) => !(b >= 0 && b <= 255))) { $('#rawOut').textContent = 'Orden no válida: texto (Kx, V, z) o letra y números (P 2 16 36 6 0 0 0).'; return; }
    let len = $('#rawLen').value.trim() === '' ? null : Number($('#rawLen').value);
    if (len == null) {
      const c = String.fromCharCode(bytes[0]);
      if (c === 'P' && bytes.length === 8) len = bytes[7];
      else if (FIXED_LEN[c] != null && (c !== 'K' || bytes.length === 2)) len = FIXED_LEN[c];
    }
    pendingRaw = { bytes, len };
    send({ type: 'raw', bytes, response: len ?? undefined, confirm: confirm || undefined });
  }
  async function askRawConfirm() {
    const p = pendingRaw; pendingRaw = null;
    if (!p) return;
    if (await confirmBox('Esta orden mueve el telescopio', 'Se enviará tal cual al mando. STOP la para como siempre.', 'Enviar')) {
      pendingRaw = p; send({ type: 'raw', bytes: p.bytes, response: p.len ?? undefined, confirm: true });
    }
  }
  $('#rawSend').addEventListener('click', () => sendRaw());
  $('#rawCmd').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); sendRaw(); } });
  $('#diagBtn').addEventListener('click', () => send({ type: 'diag' }));
  $('#pauseTrace').addEventListener('click', () => { S.tracePaused = !S.tracePaused; $('#pauseTrace').textContent = S.tracePaused ? 'Seguir' : 'Pausar'; });
  setInterval(() => { if (S.view === 'console' && !S.tracePaused && !document.hidden) send({ type: 'trace' }); }, 1000);
  function renderTrace(lines) {
    if (S.tracePaused) return;
    const pre = $('#trace');
    const atBottom = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 30;
    pre.textContent = (lines || []).join('\n');
    if (atBottom) pre.scrollTop = pre.scrollHeight;
  }

  // ------------------------------------------------------------------ settings
  function renderSettings() {
    const i = S.info, site = i.site || {}, st = i.settings || {};
    const src = { gps: 'GPS', manual: 'Introducido a mano', hand_control: 'Leído del mando', default: 'Sin fijar · usando Madrid' }[site.source] || '';
    $('#siteInfo').textContent = site.lat != null ? `${src}: ${site.lat.toFixed(4)}°, ${site.lon.toFixed(4)}°` : '';
    if (site.lat != null && document.activeElement !== $('#lat')) { $('#lat').value = site.lat.toFixed(4); $('#lon').value = site.lon.toFixed(4); }
    const method = st.gotoMethod || 'auto';
    $$('#gotoSeg button').forEach((b) => b.classList.toggle('active', b.dataset.method === method));
    $('#gotoHint').textContent = { auto: 'El mando hace el tramo largo y StarBridge la llegada, para dejar la holgura recogida.', hc: 'Siempre el GoTo del propio mando.', soft: 'StarBridge mueve los motores leyendo los encoders hasta llegar.' }[method];
    if (document.activeElement !== $('#approach') && st.approachDeg != null) $('#approach').value = st.approachDeg;
    $('#mountInfo').innerHTML = i.connected ? `<span>Mando</span><span>versión ${esc(i.hcVersion)}${i.model === 7 ? ' · NexStar SLT' : ''}</span>
      <span>Modo</span><span>${i.mode === 'hc' ? 'alineación del mando' : 'StarBridge'}</span>
      <span>GoTo del mando</span><span>${i.hcGoto === true ? 'funciona' : i.hcGoto === false ? 'no lo ejecuta (lo hace StarBridge)' : 'sin probar'}</span>
      <span>Sync</span><span>${i.canSync ? 'sí' : 'no'}</span><span>Sensores / cámara</span><span>${i.gyro || i.orientation ? 'sí' : 'no'} / ${i.imaging ? 'sí' : 'no'}</span>`
      : '<span>Telescopio</span><span>no conectado</span>';
    const key = accessKey();
    if (key) {
      for (const [id, path] of [['#shareQr', 'qr.svg'], ['#shareQrApp', 'qr-app.svg']]) {
        const src = `${path}?k=${encodeURIComponent(key)}`;
        if ($(id).getAttribute('src') !== src) $(id).setAttribute('src', src);
      }
      $('#shareText').textContent = `Código de acceso: ${key.match(/.{1,4}/g).join('-')}`;
    }
  }
  $('#saveSite').addEventListener('click', () => {
    const lat = Number($('#lat').value), lon = Number($('#lon').value);
    if (Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) { send({ type: 'setSite', lat, lon }); toast('Lugar guardado'); }
    else toast('Coordenadas no válidas', true);
  });
  $$('#gotoSeg button').forEach((b) => b.addEventListener('click', () => send({ type: 'setSetting', key: 'gotoMethod', value: b.dataset.method })));
  $('#approach').addEventListener('change', () => send({ type: 'setSetting', key: 'approachDeg', value: String(Number($('#approach').value) || 0) }));
  for (const [id, key] of [['#invertAz', 'invertAz'], ['#invertAlt', 'invertAlt']]) {
    $(id).checked = prefs.get(key, false);
    $(id).addEventListener('change', () => prefs.set(key, $(id).checked));
  }
  function applyNight(on) {
    document.body.classList.toggle('night', on);
    $('#nightSwitch').checked = on;
    prefs.set('night', on);
    drawSky(); if (S.view === 'tracking') renderTracking(); if (S.selected) renderDetail();
  }
  function toggleNight() { applyNight(!document.body.classList.contains('night')); }
  $('#nightBtn').addEventListener('click', toggleNight);
  $('#nightSwitch').addEventListener('change', (e) => applyNight(e.target.checked));

  // ------------------------------------------------------------------ boot
  applyNight(prefs.get('night', false));
  setSpeed(speedArcsec);
  renderRateSeg();
  renderNative();
  renderCam();
  showView(WIN === 'control' ? 'none' : WIN || prefs.get('view', 'sky'));
  connect();
  setInterval(renderStatusBar, 1000);
})();
