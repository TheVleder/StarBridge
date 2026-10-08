// StarBridge · Imagen tab: live stacking on the Android camera (EAA). Uses the API of app.js.
(() => {
  'use strict';
  const SB = window.SB;
  if (!SB) return;
  const { $, $$, send, prefs, toast, esc } = SB;

  const IM = {
    st: {}, cams: null, records: [], sheet: null, offset: 0,
    hud: true, pad: false, compare: false, loaded: {}, saved: null,
  };

  // ---------------------------------------------------------------- helpers
  const nf1 = (x) => (x ?? 0).toLocaleString('es-ES', { maximumFractionDigits: 1, minimumFractionDigits: 1 });
  const nf2 = (x) => (x ?? 0).toLocaleString('es-ES', { maximumFractionDigits: 2, minimumFractionDigits: 2 });
  function fmtExp(t) {
    if (t === undefined || t === null) return '—';
    if (t >= 10) return `${Math.round(t)} s`;
    if (t >= 1) return `${nf1(t)} s`;
    if (t >= 0.1) return `${nf1(t)} s`;
    return `1/${Math.round(1 / t)} s`;
  }
  function fmtDur(sec) {
    sec = Math.round(sec || 0);
    if (sec < 60) return `${sec} s`;
    const m = Math.floor(sec / 60), s = sec % 60;
    if (m < 60) return s ? `${m} min ${s} s` : `${m} min`;
    return `${Math.floor(m / 60)} h ${m % 60} min`;
  }
  const key = () => encodeURIComponent(SB.accessKey() || '');
  const imgUrl = (name, v) => `img/${name}.jpg?k=${key()}&v=${v}`;
  const busy = (p) => ['stacking', 'exposure', 'lens_focus', 'preview', 'focus', 'darks', 'optimize'].includes(p);
  const frames = () => (IM.st.stack ? IM.st.stack.frames : 0);

  // ---------------------------------------------------------------- messages
  SB.on('imaging', (m) => {
    IM.offset = (m.now || Date.now()) - Date.now();
    const prev = IM.st;
    IM.st = m;
    if (prev.phase && prev.phase !== m.phase && m.phase === 'paused' && m.gotoPaused) toast('GoTo en marcha: apilado en pausa');
    render();
    if (IM.sheet === 'session' && (m.stack?.frames || 0) !== (prev.stack?.frames || 0)) send({ type: 'imgRecords' });
    refreshSheet();
  });
  SB.on('imgCameras', (m) => { IM.cams = m; if (IM.sheet === 'cameras') openCameras(); });
  SB.on('imgRecords', (m) => { IM.records = m.items || []; if (IM.sheet === 'session') renderSessionSheet(true); });
  SB.on('imgSaved', (m) => { IM.saved = m; openSaved(); });
  SB.on('info', () => render());
  SB.on('tab', (name) => {
    if (name === 'img') { send({ type: 'imgState' }); requestAnimationFrame(render); }
    renderMini();
  });
  SB.on('sheetClosed', () => { IM.sheet = null; });
  SB.on('night', () => render());

  // ---------------------------------------------------------------- stage
  function currentImage() {
    const im = IM.st.images || {};
    if (IM.compare && im.single !== undefined) return ['single', im.single];
    if (im.view !== undefined) return ['view', im.view];
    return null;
  }

  /** Swaps the picture only once the new one is loaded: no flicker between frames. */
  function showImage() {
    const cur = currentImage();
    const img = $('#imgView');
    if (!cur) { img.removeAttribute('src'); img.classList.add('hidden'); return; }
    const url = imgUrl(cur[0], cur[1]);
    if (IM.loaded.url === url) return;
    IM.loaded.url = url;
    const pre = new Image();
    pre.onload = () => {
      if (IM.loaded.url !== url) return;
      img.src = url;
      img.classList.remove('hidden');
      img.classList.remove('arrive'); void img.offsetWidth; img.classList.add('arrive');
    };
    pre.src = url;
  }

  function render() {
    const st = IM.st;
    const available = !!st.available;
    const phase = st.phase || 'idle';
    const hasImage = !!(st.images && st.images.view !== undefined);
    const stack = st.stack;

    // Empty state.
    const empty = $('#imgEmpty');
    if (!available) {
      empty.innerHTML = `<div class="empty-card"><h3>Sin cámara</h3>
        <p>Este Android no ofrece ninguna cámara utilizable, o falta el permiso de cámara (ábrelo en la app StarBridge del Android).</p></div>`;
      empty.classList.remove('hidden');
    } else if (!hasImage && !busy(phase)) {
      empty.innerHTML = `<div class="empty-card"><h3>Listo para fotografiar</h3>
        <p>Pon el móvil en el ocular, apunta a un objeto y pulsa <b>Empezar</b>. La app mide el cielo, enfoca el móvil y empieza a apilar sola.</p>
        <button class="btn primary full" data-act="start">Empezar a apilar</button>
        <div class="split"><button class="btn small" data-act="preview">Encuadrar</button><button class="btn small" data-act="focus">Enfocar el telescopio</button></div></div>`;
      empty.classList.remove('hidden');
    } else if (!hasImage) {
      empty.innerHTML = `<div class="empty-card"><div class="spinner"></div><p>${esc(st.message || st.phaseLabel || '')}</p></div>`;
      empty.classList.remove('hidden');
    } else empty.classList.add('hidden');
    $$('[data-act]', empty).forEach((b) => b.addEventListener('click', () => act(b.dataset.act)));

    if (SB.S.tab === 'img') showImage(); // no downloads while another tab is shown

    // HUD pills.
    const pills = [];
    const singles = phase === 'focus' || phase === 'preview';
    if (singles) pills.push(`<span class="ipill strong">${phase === 'focus' ? 'Enfoque' : 'Encuadre · no se apila'}</span>`);
    else if (st.target) pills.push(`<span class="ipill strong">${esc(st.target)}</span>`);
    if (stack && !singles) {
      const used = (stack.accepted || 0) + (stack.downweighted || 0);
      pills.push(`<span class="ipill">${used}${stack.frames !== used ? `/${stack.frames}` : ''} fotos</span>`);
      if (stack.integrationSec) pills.push(`<span class="ipill">${fmtDur(stack.integrationSec)}</span>`);
      if (stack.magGain !== undefined && used > 1) pills.push(`<span class="ipill good">+${nf1(stack.magGain)} mag</span>`);
    } else if (!singles && st.camera && available) pills.push(`<span class="ipill">${esc(st.camera.label)}</span>`);
    if (st.effective && available) {
      const measuring = st.settings && st.settings.exposureAuto && !st.auto;
      pills.push(`<span class="ipill dim">${measuring ? 'Exposición auto' : fmtExp(st.effective.exposureSec)} · ISO ${st.effective.iso}</span>`);
    }
    const h = st.health || {};
    if (h.thermal >= 3) pills.push('<span class="ipill warn">Móvil caliente</span>');
    if (h.battery !== undefined && h.battery <= 15 && !h.charging) pills.push(`<span class="ipill warn">Batería ${h.battery} %</span>`);
    if (!singles && st.images && st.images.single !== undefined && st.images.stack !== undefined) {
      pills.push('<button class="ipill btnish" id="cmpBtn">◐ Mantén: 1 foto</button>');
    }
    if (!singles && available && st.camera && st.camera.raw && st.darks && !st.darks.matching && stack && stack.frames >= 3) {
      pills.push('<button class="ipill btnish dim" id="darkHint">Sin darks</button>');
    }
    $('#imgPills').innerHTML = pills.join('');
    const cmp = $('#cmpBtn');
    if (cmp) {
      if (IM.compare) cmp.classList.add('on');
      cmp.addEventListener('pointerdown', (e) => { e.preventDefault(); IM.compare = true; showImage(); cmp.classList.add('on'); });
      cmp.addEventListener('pointerleave', compareOff);
    }
    $('#darkHint')?.addEventListener('click', openDarks);

    // Status line.
    let msg = st.message || '';
    if (phase === 'stacking' && stack && stack.plateau) msg = 'El apilado ya apenas mejora: el cielo manda. Puedes guardar.';
    $('#imgMsg').textContent = msg;
    $('#imgLine').classList.toggle('hidden', (!msg && !st.exposing && !st.progress) || phase === 'focus');
    tickProgress();

    // Banner (GoTo pause).
    const banner = $('#imgBanner');
    if (phase === 'paused' && st.gotoPaused) {
      banner.innerHTML = `<span>GoTo: apilado en pausa</span><button class="btn small" data-b="resume">Reanudar</button><button class="btn small" data-b="reset">Nuevo</button>`;
      banner.classList.remove('hidden');
      $$('[data-b]', banner).forEach((b) => b.addEventListener('click', () => (b.dataset.b === 'resume' ? send({ type: 'imgResume' }) : newStack())));
    } else banner.classList.add('hidden');

    renderFocus();

    // Bottom bar.
    const main = $('#imgMain');
    let label = 'Empezar', icon = '<path d="M7 5l12 7-12 7z" class="fill"/>', action = 'start';
    if (phase === 'stacking' || phase === 'exposure' || phase === 'lens_focus') { label = 'Pausar'; icon = '<path d="M7 5h3v14H7zM14 5h3v14h-3z" class="fill"/>'; action = 'pause'; }
    else if (phase === 'paused') { label = 'Seguir'; action = 'resume'; }
    else if (busy(phase)) { label = 'Parar'; icon = '<rect x="6" y="6" width="12" height="12" rx="2" class="fill"/>'; action = 'stop'; }
    main.innerHTML = `<svg viewBox="0 0 24 24" class="icon">${icon}</svg><span>${label}</span>`;
    main.dataset.act = action;
    main.disabled = !available;
    main.classList.toggle('active', action !== 'start' && action !== 'resume');
    $('#imgFrameBtn').classList.toggle('hidden', !SB.S.info.connected);
    $('#imgSaveBtn').disabled = !(stack && stack.referenceSet);
    $('#imgSettingsBtn').disabled = !available;
    $('#imgPadWrap').classList.toggle('hidden', !(IM.pad && SB.S.info.connected));
    $('#imgStage').classList.toggle('hud-off', !IM.hud);
    renderMini();
  }

  // Released on the window: the pills are rebuilt on every frame, maybe under the finger.
  function compareOff() {
    if (!IM.compare) return;
    IM.compare = false;
    showImage();
    $('#cmpBtn')?.classList.remove('on');
  }
  ['pointerup', 'pointercancel', 'blur'].forEach((ev) => window.addEventListener(ev, compareOff));

  function renderMini() {
    const st = IM.st;
    const mini = $('#miniStack');
    const show = !!(st.images && st.images.thumb !== undefined) && ['stacking', 'paused'].includes(st.phase) &&
      ['sky', 'move', 'search'].includes(SB.S.tab);
    mini.classList.toggle('hidden', !show);
    if (!show) return;
    // A card at the top of the control tab being shown.
    const host = $(`#tab-${SB.S.tab}`);
    if (host && mini.parentElement !== host) host.prepend(mini);
    const url = imgUrl('thumb', st.images.thumb);
    const im = $('#miniStackImg');
    if (im.getAttribute('src') !== url) im.setAttribute('src', url);
    const s = st.stack || {};
    const used = (s.accepted || 0) + (s.downweighted || 0);
    $('#miniStackTitle').textContent = st.phase === 'paused' ? 'Apilado en pausa' : `Apilando${st.target ? ' ' + st.target : ''}`;
    $('#miniStackText').textContent = `${used} fotos · ${fmtDur(s.integrationSec)}${s.magGain && used > 1 ? ` · +${nf1(s.magGain)} mag` : ''}`;
  }
  $('#miniStack').addEventListener('click', () => SB.showTab('img'));

  // Exposure progress bar (server clock offset applied).
  let progTimer = null;
  function tickProgress() {
    const st = IM.st;
    const bar = $('#imgProg');
    let frac = null;
    if (st.progress && st.progress.total) frac = (st.progress.done + (st.exposing ? expFrac() : 0)) / st.progress.total;
    else if (st.exposing) frac = expFrac();
    bar.parentElement.classList.toggle('hidden', frac === null);
    if (frac !== null) bar.style.width = `${Math.max(0, Math.min(1, frac)) * 100}%`;
    clearTimeout(progTimer);
    if (st.exposing && SB.S.tab === 'img') progTimer = setTimeout(tickProgress, 200);
  }
  function expFrac() {
    const e = IM.st.exposing;
    if (!e) return 0;
    return Math.min(1, (Date.now() + IM.offset - e.startedAt) / (e.sec * 1000 + 1));
  }

  // ---------------------------------------------------------------- focus assistant overlay
  function renderFocus() {
    const box = $('#imgFocus');
    const f = IM.st.focus;
    if (IM.st.phase !== 'focus' || !f) { box.classList.add('hidden'); return; }
    box.classList.remove('hidden');
    const trend = {
      measuring: ['Midiendo…', ''], better: ['¡Mejor! Sigue en ese sentido', 'good'], worse: ['Peor: vuelve atrás despacio', 'warn'],
      focused: ['✓ En foco', 'good'], nostars: ['Pocas estrellas: medida poco fiable', 'warn'],
    }[f.trend] || ['', ''];
    const stars = IM.st.images && IM.st.images.stars !== undefined ? `<div class="focus-stars-wrap night-img"><img class="focus-stars" src="${imgUrl('stars', IM.st.images.stars)}" alt="Estrellas ampliadas"></div>` : '';
    box.innerHTML = `
      <div class="focus-top">
        <div><div class="focus-num">${f.hfr !== undefined ? nf2(f.hfr) : '—'}<small> px</small></div>
          <div class="focus-sub">Nitidez (HFR, menos es mejor)${f.best !== undefined ? ` · mejor ${nf2(f.best)}` : ''}</div></div>
        <button class="btn small" id="focusEnd">Terminar</button>
      </div>
      <div class="focus-trend ${trend[1]}">${trend[0]}</div>
      <canvas id="focusChart" class="focus-chart"></canvas>
      ${stars}`;
    $('#focusEnd').addEventListener('click', () => send({ type: 'imgStop' }));
    drawSpark($('#focusChart'), f.history || [], f.best);
  }

  function drawSpark(canvas, values, best) {
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (!w || !h) return;
    canvas.width = w * dpr; canvas.height = h * dpr;
    const c = canvas.getContext('2d');
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    if (values.length < 2) return;
    const lo = Math.min(...values), hi = Math.max(...values);
    const y = (v) => h - 6 - (h - 12) * (hi === lo ? 0.5 : (v - lo) / (hi - lo));
    const x = (i) => 6 + (w - 12) * i / (values.length - 1);
    const css = getComputedStyle(document.body);
    if (best !== undefined) {
      c.strokeStyle = css.getPropertyValue('--good'); c.setLineDash([4, 4]); c.lineWidth = 1;
      c.beginPath(); c.moveTo(0, y(best)); c.lineTo(w, y(best)); c.stroke(); c.setLineDash([]);
    }
    c.strokeStyle = css.getPropertyValue('--accent'); c.lineWidth = 2;
    c.beginPath(); values.forEach((v, i) => (i ? c.lineTo(x(i), y(v)) : c.moveTo(x(i), y(v)))); c.stroke();
  }

  // ---------------------------------------------------------------- actions
  function act(a) {
    switch (a) {
      case 'start': send({ type: 'imgStart' }); break;
      case 'pause': send({ type: 'imgPause' }); break;
      case 'resume': send({ type: 'imgResume' }); break;
      case 'stop': send({ type: 'imgStop' }); break;
      case 'preview': send({ type: 'imgPreview' }); break;
      case 'focus': send({ type: 'imgFocus' }); break;
      default: break;
    }
  }
  function newStack() {
    if (frames() > 0 && !confirm('¿Empezar un apilado nuevo? El actual se pierde si no lo has guardado.')) return;
    send({ type: 'imgReset' });
  }
  $('#imgMain').addEventListener('click', () => act($('#imgMain').dataset.act));
  $('#imgSettingsBtn').addEventListener('click', openSettings);
  $('#imgSessionBtn').addEventListener('click', openSession);
  $('#imgSaveBtn').addEventListener('click', () => { send({ type: 'imgSave' }); toast('Guardando… (FITS, TIFF y JPEG)'); });
  $('#imgFrameBtn').addEventListener('click', () => { IM.pad = !IM.pad; render(); });
  SB.makePad($('#imgPad'), () => prefs.get('imgRate', 4));
  SB.setupSeg($('#imgSpeedSeg'), 'imgRate', 4);

  // ---------------------------------------------------------------- pinch zoom, pan, tap
  const Z = { s: 1, x: 0, y: 0, pts: new Map(), start: null, moved: false, lastTap: 0 };
  const stage = $('#imgStage');
  const frameEl = $('#imgFrame');
  function applyZoom() { $('#imgView').style.transform = `translate(${Z.x}px, ${Z.y}px) scale(${Z.s})`; }
  frameEl.addEventListener('pointerdown', (e) => {
    frameEl.setPointerCapture?.(e.pointerId);
    Z.pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
    Z.moved = false;
    Z.start = { s: Z.s, x: Z.x, y: Z.y, pts: new Map(Z.pts), t: Date.now() };
  });
  frameEl.addEventListener('pointermove', (e) => {
    if (!Z.pts.has(e.pointerId) || !Z.start) return;
    Z.pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const now = Array.from(Z.pts.values());
    const was = Array.from(Z.start.pts.values());
    if (now.length >= 2 && was.length >= 2) {
      const d = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
      Z.s = Math.max(1, Math.min(8, Z.start.s * d(now[0], now[1]) / Math.max(1, d(was[0], was[1]))));
      Z.moved = true;
    } else if (now.length === 1 && was.length === 1) {
      const dx = now[0].x - was[0].x, dy = now[0].y - was[0].y;
      if (Math.hypot(dx, dy) > 6) Z.moved = true;
      if (Z.s > 1) { Z.x = Z.start.x + dx; Z.y = Z.start.y + dy; }
    }
    applyZoom();
  });
  const endPtr = (e) => {
    if (!Z.pts.has(e.pointerId)) return;
    Z.pts.delete(e.pointerId);
    if (Z.pts.size === 0 && Z.start) {
      const quick = Date.now() - Z.start.t < 300;
      if (!Z.moved && quick) {
        const now = Date.now();
        if (now - Z.lastTap < 320) { Z.s = 1; Z.x = 0; Z.y = 0; applyZoom(); IM.hud = !IM.hud; } // double tap: reset
        else { IM.hud = !IM.hud; }
        Z.lastTap = now;
        stage.classList.toggle('hud-off', !IM.hud);
      }
      if (Z.s <= 1.02) { Z.s = 1; Z.x = 0; Z.y = 0; applyZoom(); }
      Z.start = null;
    } else if (Z.start) {
      Z.start = { s: Z.s, x: Z.x, y: Z.y, pts: new Map(Z.pts), t: Z.start.t };
    }
  };
  ['pointerup', 'pointercancel'].forEach((ev) => frameEl.addEventListener(ev, endPtr));

  // ---------------------------------------------------------------- settings sheet
  const logPos = (v, lo, hi) => Math.round(1000 * Math.log(v / lo) / Math.log(hi / lo));
  const logVal = (p, lo, hi) => lo * Math.pow(hi / lo, p / 1000);
  function niceExp(t) { return t < 0.1 ? t : t < 2 ? Math.round(t * 10) / 10 : t < 10 ? Math.round(t * 2) / 2 : Math.round(t); }
  const ISO_STEPS = [50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 25600];

  function setSettings(obj, needsNew) {
    if (needsNew && frames() > 0 && !confirm('Este cambio empieza un apilado nuevo (guarda antes el actual si lo quieres). ¿Seguir?')) { renderSettingsSheet(); return; }
    send({ type: 'imgSettings', settings: obj });
  }

  function seg(id, options, current) {
    return `<div class="seg compact" id="${id}">${options.map(([v, l]) => `<button data-v="${esc(v)}" class="${String(v) === String(current) ? 'active' : ''}">${esc(l)}</button>`).join('')}</div>`;
  }

  function openSettings() { IM.sheet = 'settings'; IM.sheetSig = null; renderSettingsSheet(false); }

  function renderSettingsSheet(keep) {
    const st = IM.st, s = st.settings || {}, cam = st.camera || {}, eff = st.effective || {};
    if (!st.available) return;
    const maxD = Math.min(cam.minFocusD || 2, 2);
    const autoTxt = st.auto ? esc(st.auto.explanation) : 'Se mide al empezar (unos segundos).';
    const body = SB.openSheet(`
      <h3>Ajustes de imagen</h3>
      <p class="kind">Todo en automático por defecto; cambia a Manual lo que quieras.</p>

      <div class="set-group">
        <div class="set-row nav" id="camRow"><span><b>Cámara</b><br><small>${esc(cam.label || '')}</small></span><span class="chev">›</span></div>
        ${(cam.notes || []).length ? `<p class="hint">${esc(cam.notes.join(' · '))}</p>` : ''}
      </div>

      <div class="set-group">
        <div class="set-title">Exposición <span class="val">${fmtExp(eff.exposureSec)}</span></div>
        ${seg('expMode', [['auto', 'Auto'], ['manual', 'Manual']], s.exposureAuto ? 'auto' : 'manual')}
        ${s.exposureAuto ? `<p class="hint">${autoTxt}</p>
          <div class="split"><button class="btn small" id="expMeasure">Volver a medir</button><button class="btn small" id="expOpt">Probar en el cielo (~${fmtDur(10.5 * (eff.exposureSec || 10) + 9)})</button></div>`
          : cam.manual ? `<input type="range" id="expSlider" min="0" max="1000" value="${logPos(Math.max(s.exposureSec, cam.minExp || 0.001), Math.max(cam.minExp || 0.001, 0.001), cam.maxExp || 30)}">
          <p class="hint">De ${fmtExp(Math.max(cam.minExp, 0.001))} a ${fmtExp(cam.maxExp)} (máximo real de esta cámara).</p>`
          : '<p class="hint">Esta cámara no deja fijar la exposición.</p>'}
      </div>

      <div class="set-group">
        <div class="set-title">ISO <span class="val">${eff.iso || '—'}</span></div>
        ${seg('isoMode', [['auto', 'Auto'], ['manual', 'Manual']], s.isoAuto ? 'auto' : 'manual')}
        ${s.isoAuto ? `<p class="hint">El ISO con menos ruido de lectura de este sensor${cam.analogIso ? ` (${cam.analogIso})` : ''}.${st.auto && st.auto.readNoise ? ` Ruido de lectura medido: ${nf2(st.auto.readNoise)}.` : ''}</p>`
          : `<input type="range" id="isoSlider" min="0" max="${ISO_STEPS.length - 1}" value="${ISO_STEPS.findIndex((v) => v >= s.iso)}">`}
      </div>

      ${cam.focusable ? `<div class="set-group">
        <div class="set-title">Enfoque del móvil <span class="val">${eff.focusDiopters === 0 ? 'infinito' : nf2(eff.focusDiopters) + ' D'}</span></div>
        ${seg('focusMode', [['auto', 'Auto'], ['manual', 'Manual']], s.focusAuto ? 'auto' : 'manual')}
        ${s.focusAuto ? `<p class="hint">Barrido automático buscando las estrellas más nítidas; queda fijo toda la sesión.</p><button class="btn small" id="lensAgain">Volver a enfocar el móvil</button>`
          : `<input type="range" id="focusSlider" min="0" max="${Math.round(maxD * 100)}" value="${Math.round((s.focusDiopters || 0) * 100)}"><p class="hint">0 = infinito.</p>`}
      </div>` : ''}

      <div class="set-group">
        <div class="set-title">Enfoque del telescopio</div>
        <p class="hint">Fotos cortas en bucle: la app mide la nitidez y te dice si vas mejor o peor mientras giras el enfocador.</p>
        <button class="btn small" id="focusAssist">Abrir el asistente de enfoque</button>
      </div>

      <div class="set-group">
        <div class="set-title">Agrupado de píxeles <span class="val">${eff.width ? `${eff.width}×${eff.height}` : ''}</span></div>
        ${seg('binSeg', [[0, 'Auto'], [1, '1×1'], [2, '2×2'], [3, '3×3'], [4, '4×4']], s.binning)}
        <p class="hint">Más agrupado: menos ruido y menos trabajo para el móvil; menos detalle.</p>
        ${cam.raw && !cam.mono ? `<div class="row-between"><span>Color</span><label class="switch"><input id="colorSw" type="checkbox" ${s.color ? 'checked' : ''}><span></span></label></div>` : ''}
      </div>

      <div class="set-group">
        <div class="set-title">Filtro de fotos</div>
        ${seg('qualSeg', [['conservative', 'Conservador'], ['normal', 'Normal'], ['off', 'Sin filtro']], s.quality)}
        <p class="hint">${{ conservative: 'Solo descarta lo que estropea: trazos, golpes, nubes, desenfoque fuerte. Lo flojo entra con menos peso.', normal: 'También descarta fotos algo movidas o borrosas: más nitidez, algo menos de señal.', off: 'Apila todo lo que se pueda alinear.' }[s.quality] || ''}</p>
      </div>

      <div class="set-group">
        <div class="set-title">Aspecto (solo la vista; los datos no cambian)</div>
        <label class="slider-row">Suavizado ${seg('dnSeg', [['auto', 'Auto'], ['off', 'No'], ['low', 'Bajo'], ['medium', 'Medio'], ['high', 'Alto']], s.denoise)}</label>
        <label class="slider-row">Brillo del fondo <input type="range" id="bgSlider" min="5" max="60" value="${Math.round((s.background || 0.25) * 100)}"></label>
        <label class="slider-row">Punto negro <input type="range" id="blackSlider" min="-100" max="100" value="${Math.round((s.blackShift || 0) * 100)}"></label>
        <label class="slider-row">Contraste <input type="range" id="contrastSlider" min="50" max="200" value="${Math.round((s.contrast || 1) * 100)}"></label>
        <div class="row-between"><span>Quitar el gradiente (contaminación, Luna)</span><label class="switch"><input id="gradSw" type="checkbox" ${s.removeGradient ? 'checked' : ''}><span></span></label></div>
        <button class="btn small ghost" id="lookReset">Restablecer el aspecto</button>
      </div>

      <div class="set-group">
        <div class="set-title">Darks <span class="val">${st.darks && st.darks.matching ? '✓ listos' : 'no hay'}</span></div>
        <p class="hint">Fotos con el telescopio tapado: quitan el ruido térmico y los píxeles calientes. Opcionales (la app ya aprende los píxeles calientes sola).</p>
        <div class="split"><button class="btn small" id="darksBtn">Hacer darks</button><button class="btn small ghost" id="darksDel" ${st.darks && st.darks.count ? '' : 'disabled'}>Borrar darks (${st.darks ? st.darks.count : 0})</button></div>
      </div>

      <div class="set-group">
        <div class="row-between"><span>Guardar también cada foto en RAW (DNG)<br><small class="hint">Para apilarlas tú en Siril o DeepSkyStacker. Ocupa mucho.</small></span>
          <label class="switch"><input id="rawSw" type="checkbox" ${s.keepRaw ? 'checked' : ''}><span></span></label></div>
      </div>
      <div class="sheet-actions"><button class="btn ghost" data-close>Cerrar</button></div>`, keep);

    $('#camRow', body).addEventListener('click', () => { IM.sheet = 'cameras'; send({ type: 'imgCameras' }); openCameras(); });
    segHandler(body, '#expMode', (v) => setSettings({ exposureAuto: v === 'auto' }, true));
    segHandler(body, '#isoMode', (v) => setSettings({ isoAuto: v === 'auto' }, true));
    segHandler(body, '#focusMode', (v) => setSettings({ focusAuto: v === 'auto' }, true));
    segHandler(body, '#binSeg', (v) => setSettings({ binning: Number(v) }, true));
    segHandler(body, '#qualSeg', (v) => setSettings({ quality: v }, false));
    segHandler(body, '#dnSeg', (v) => setSettings({ denoise: v }, false));
    $('#expMeasure', body)?.addEventListener('click', () => send({ type: 'imgExposure' }));
    $('#expOpt', body)?.addEventListener('click', () => {
      if (frames() > 0 && !confirm('La prueba hace unas fotos y, si cambia la exposición, empieza un apilado nuevo. ¿Seguir?')) return;
      send({ type: 'imgOptimize' }); SB.closeSheet();
    });
    $('#lensAgain', body)?.addEventListener('click', () => { send({ type: 'imgLensFocus' }); SB.closeSheet(); });
    $('#focusAssist', body).addEventListener('click', () => { send({ type: 'imgFocus' }); SB.closeSheet(); });
    slider(body, '#expSlider', (p) => niceExp(logVal(p, Math.max(cam.minExp || 0.001, 0.001), cam.maxExp || 30)), fmtExp, (v) => setSettings({ exposureSec: v }, true), '#expMode');
    slider(body, '#isoSlider', (p) => ISO_STEPS[p], (v) => `ISO ${v}`, (v) => setSettings({ iso: Math.max(cam.minIso || 0, Math.min(cam.maxIso || 1e6, v)) }, true), '#isoMode');
    slider(body, '#focusSlider', (p) => p / 100, (v) => (v === 0 ? 'infinito' : `${nf2(v)} D`), (v) => setSettings({ focusDiopters: v }, true), '#focusMode');
    slider(body, '#bgSlider', (p) => p / 100, null, (v) => setSettings({ background: v }, false));
    slider(body, '#blackSlider', (p) => p / 100, null, (v) => setSettings({ blackShift: v }, false));
    slider(body, '#contrastSlider', (p) => p / 100, null, (v) => setSettings({ contrast: v }, false));
    $('#colorSw', body)?.addEventListener('change', (e) => setSettings({ color: e.target.checked }, true));
    $('#gradSw', body).addEventListener('change', (e) => setSettings({ removeGradient: e.target.checked }, false));
    $('#rawSw', body).addEventListener('change', (e) => setSettings({ keepRaw: e.target.checked }, false));
    $('#lookReset', body).addEventListener('click', () => setSettings({ background: 0.25, blackShift: 0, contrast: 1, denoise: 'auto', removeGradient: true }, false));
    $('#darksBtn', body).addEventListener('click', openDarks);
    $('#darksDel', body).addEventListener('click', () => { if (confirm('¿Borrar todos los darks guardados?')) send({ type: 'imgDeleteDarks' }); });
  }

  function segHandler(root, sel, fn) {
    const el = $(sel, root);
    if (!el) return;
    $$('button', el).forEach((b) => b.addEventListener('click', () => {
      if (b.classList.contains('active')) return;
      fn(b.dataset.v);
    }));
  }

  /** Range input: live label while dragging, sends on release. */
  function slider(root, sel, map, label, commit, titleSel) {
    const el = $(sel, root);
    if (!el) return;
    const valEl = titleSel ? $(titleSel, root)?.previousElementSibling?.querySelector('.val') : null;
    el.addEventListener('input', () => { if (valEl && label) valEl.textContent = label(map(Number(el.value))); });
    el.addEventListener('change', () => commit(map(Number(el.value))));
  }

  /** Re-renders an open sheet with fresh data, but never under the user's finger. */
  function refreshSheet() {
    if (!IM.sheet || !SB.sheetOpen()) return;
    const a = document.activeElement;
    if (a && a.closest && a.closest('#sheet') && a.tagName === 'INPUT') return;
    const st = IM.st;
    const sig = JSON.stringify([st.settings, st.auto, st.effective, st.darks, st.camera, st.lensFocus, st.phase === 'darks', st.message && st.phase === 'darks' ? st.message : '']);
    if (sig === IM.sheetSig) return;
    IM.sheetSig = sig;
    if (IM.sheet === 'settings') renderSettingsSheet(true);
    else if (IM.sheet === 'darks') renderDarks(true);
  }

  // ---------------------------------------------------------------- cameras
  function openCameras() {
    IM.sheet = 'cameras';
    const c = IM.cams;
    const items = c ? c.items.map((k) => `
      <li class="item cam ${k.id === c.selected ? 'sel' : ''}" data-id="${esc(k.id)}">
        <div class="meta"><div class="name">${esc(k.label)}${k.id === c.recommended ? ' <span class="tag">Recomendada</span>' : ''}</div>
          <div class="sub">${[k.raw ? 'RAW' : 'sin RAW', k.manual ? `hasta ${fmtExp(k.maxExp)}` : 'exposición automática', k.mono ? 'monocromo' : null, `${k.mp} MP`, k.focalMm ? `${k.focalMm} mm` : null].filter(Boolean).join(' · ')}</div></div>
        <div class="alt">${k.id === c.selected ? '✓' : ''}</div></li>`).join('') : '<li class="empty">Cargando…</li>';
    const body = SB.openSheet(`<h3>Cámara</h3>
      <p class="kind">Para cielo profundo: RAW, exposición manual larga y, si hay, el sensor monocromo (capta más luz).</p>
      <ul class="list">${items}</ul>
      <div class="sheet-actions"><button class="btn ghost" id="camBack">Volver a ajustes</button></div>`);
    $$('.cam', body).forEach((li) => li.addEventListener('click', () => {
      if (li.dataset.id === c.selected) return;
      if (frames() > 0 && !confirm('Cambiar de cámara empieza un apilado nuevo. ¿Seguir?')) return;
      send({ type: 'imgSelect', id: li.dataset.id });
      send({ type: 'imgCameras' });
    }));
    $('#camBack', body).addEventListener('click', openSettings);
  }

  // ---------------------------------------------------------------- session sheet
  function openSession() { IM.sheet = 'session'; send({ type: 'imgRecords' }); renderSessionSheet(false); }

  function renderSessionSheet(keep) {
    const st = IM.st, s = st.stack;
    if (!s) {
      SB.openSheet(`<h3>Sesión</h3><p class="kind">Todavía no hay apilado.</p>
        <div class="sheet-actions"><button class="btn ghost" data-close>Cerrar</button></div>`, keep);
      return;
    }
    const used = (s.accepted || 0) + (s.downweighted || 0);
    const rej = IM.records.filter((r) => r.v === 'reject').reverse();
    const recent = IM.records.slice(-40).reverse();
    const body = SB.openSheet(`
      <h3>Sesión</h3>
      <p class="kind">${st.target ? esc(st.target) + ' · ' : ''}${fmtDur(s.integrationSec)} de exposición total</p>
      <div class="facts">
        <div class="fact"><span>Fotos usadas</span><b>${used} de ${s.frames}</b></div>
        <div class="fact"><span>Más limpia que 1 foto</span><b>×${nf1(s.snrGain)}</b></div>
        <div class="fact"><span>Más profunda</span><b>+${nf1(s.magGain)} mag</b></div>
        <div class="fact"><span>Nitidez (FWHM)</span><b>${s.singleFwhm !== undefined ? nf1(s.singleFwhm) : '—'} → ${s.stackFwhm !== undefined ? nf1(s.stackFwhm) : '—'} px</b></div>
        <div class="fact"><span>Píxeles calientes corregidos</span><b>${s.defects}</b></div>
        <div class="fact"><span>Descartadas (movimiento)</span><b>${(s.rejected || 0) + (s.skippedMoving || 0)}</b></div>
      </div>
      <div class="set-title">Calidad del apilado</div>
      <canvas id="gainChart" class="gain-chart"></canvas>
      <p class="hint">Línea: cuánto más limpia es la imagen que una sola foto (sube como √N). Puntos rojos: fotos descartadas.${s.plateau ? ' Ya apenas mejora: puedes guardar o cambiar de objeto.' : ''}</p>
      ${rej.length ? `<div class="set-title">Descartadas</div><ul class="list">${rej.map((r) => `
        <li class="item"><div class="meta"><div class="name">Foto ${r.id}</div><div class="sub">${esc((r.reasons || []).join(' · '))}</div></div>
        ${r.recoverable ? `<button class="btn small" data-rec="${r.id}">Recuperar</button>` : ''}</li>`).join('')}</ul>` : ''}
      <details class="details"><summary>Todas las fotos (${IM.records.length})</summary>
        <ul class="list compact">${recent.map((r) => `<li class="rec ${r.v}"><b>${r.id}</b> <span>${{ accept: '✓', down: '½', reject: '✕' }[r.v]}</span>
          <span class="sub">${r.fwhm !== undefined ? 'FWHM ' + nf1(r.fwhm) : ''}${r.transp !== undefined ? ' · transp. ' + Math.round(r.transp * 100) + ' %' : ''}${r.v !== 'reject' ? ' · peso ' + nf2(r.w) : ''} · ${r.ms} ms</span>
          <span class="sub">${esc((r.reasons || []).join(' · '))}</span></li>`).join('')}</ul>
      </details>
      <div class="sheet-actions">
        <button class="btn" id="sesNew">Nuevo apilado</button>
        <button class="btn ghost" data-close>Cerrar</button>
      </div>`, keep);
    $$('[data-rec]', body).forEach((b) => b.addEventListener('click', () => { send({ type: 'imgRecover', id: Number(b.dataset.rec) }); b.disabled = true; }));
    $('#sesNew', body).addEventListener('click', () => { SB.closeSheet(); newStack(); });
    requestAnimationFrame(() => drawGain($('#gainChart')));
  }

  function drawGain(canvas) {
    if (!canvas) return;
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (!w) return;
    canvas.width = w * dpr; canvas.height = h * dpr;
    const c = canvas.getContext('2d');
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    const recs = IM.records;
    if (!recs.length) return;
    const css = getComputedStyle(document.body);
    const maxG = Math.max(2, ...recs.map((r) => r.gain || 1));
    const x = (i) => 8 + (w - 16) * (recs.length === 1 ? 0.5 : i / (recs.length - 1));
    const y = (g) => h - 8 - (h - 16) * ((g - 1) / (maxG - 1));
    // √N reference.
    c.strokeStyle = css.getPropertyValue('--faint'); c.setLineDash([3, 4]); c.lineWidth = 1;
    c.beginPath();
    let n = 0;
    recs.forEach((r, i) => { if (r.v !== 'reject') n++; const g = Math.min(maxG, Math.sqrt(Math.max(1, n))); i ? c.lineTo(x(i), y(g)) : c.moveTo(x(i), y(g)); });
    c.stroke(); c.setLineDash([]);
    c.strokeStyle = css.getPropertyValue('--accent'); c.lineWidth = 2;
    c.beginPath(); recs.forEach((r, i) => (i ? c.lineTo(x(i), y(r.gain || 1)) : c.moveTo(x(i), y(r.gain || 1)))); c.stroke();
    c.fillStyle = css.getPropertyValue('--danger');
    recs.forEach((r, i) => { if (r.v === 'reject') { c.beginPath(); c.arc(x(i), h - 8, 3, 0, 2 * Math.PI); c.fill(); } });
  }

  // ---------------------------------------------------------------- darks
  function openDarks() { IM.sheet = 'darks'; IM.sheetSig = null; renderDarks(false); }
  function renderDarks(keep) {
    const st = IM.st, eff = st.effective || {};
    const t = eff.exposureSec || 10;
    const n = Math.max(10, Math.min(30, Math.round(300 / t)));
    const running = st.phase === 'darks';
    const body = SB.openSheet(`<h3>Darks</h3>
      <p class="kind">Fotos con la luz tapada, con la misma exposición (${fmtExp(t)}) e ISO (${eff.iso}).</p>
      <ol class="steps">
        <li>Tapa el telescopio (la tapa del tubo) o el ocular, sin mover el móvil.</li>
        <li>Pulsa <b>Empezar</b>: ${n} fotos, unos ${fmtDur(n * (t + 0.5))}.</li>
        <li>Al terminar, destápalo. Se guardan y se usan solas en las próximas sesiones con estos ajustes.</li>
      </ol>
      ${running ? `<p class="hint">${esc(st.message || '')}</p>` : ''}
      <div class="sheet-actions">
        ${running ? '<button class="btn" id="dkStop">Cancelar</button>' : `<button class="btn primary" id="dkStart" ${st.auto || (st.settings && !st.settings.exposureAuto) ? '' : 'disabled'}>Empezar</button>`}
        ${st.auto || (st.settings && !st.settings.exposureAuto) || running ? '' : '<p class="hint center">Empieza antes un apilado (para medir la exposición) o fija la exposición a mano.</p>'}
        <button class="btn ghost" data-close>Cerrar</button>
      </div>`, keep);
    $('#dkStart', body)?.addEventListener('click', () => {
      if (frames() > 0 && st.phase === 'stacking' && !confirm('Se pausará el apilado mientras se hacen los darks. ¿Seguir?')) return;
      send({ type: 'imgDarks', count: n });
    });
    $('#dkStop', body)?.addEventListener('click', () => send({ type: 'imgStop' }));
  }

  // ---------------------------------------------------------------- saved files
  function openSaved() {
    IM.sheet = 'saved';
    const m = IM.saved;
    const files = (m.files || []).map((f) => `
      <li class="item"><div class="meta"><div class="name">${esc(f.label)}</div><div class="sub">${esc(f.name)} · ${(f.bytes / 1048576).toFixed(1)} MB</div></div>
      <a class="btn small" href="${esc(f.url)}?k=${key()}" download="${esc(f.name)}">Descargar</a></li>`).join('');
    SB.openSheet(`<h3>Guardado</h3>
      <p class="kind">En el Android: ${esc(m.where || '')}. Descárgalos aquí para editarlos:</p>
      <ul class="list">${files}</ul>
      <p class="hint">FITS y TIFF son lineales (sin estirar): todo el rango del apilado para procesarlo a fondo. El JPEG es la imagen tal como la ves.</p>
      <div class="sheet-actions"><button class="btn ghost" data-close>Cerrar</button></div>`);
  }

  render();
})();
