// StarBridge · Imagen tab: the iPhone app StarBridge EAA as a remote camera. Its live picture
// (photos/live-iphone.jpg) and state (camStatus) come through the hub; the buttons send camCmd.
// So the phone can stay at the telescope while you drive it from here. Uses the API of app.js.
(() => {
  'use strict';
  const SB = window.SB;
  if (!SB) return;
  const { $, send, prefs, toast, esc, S } = SB;

  const CAM = { st: null, v: 0, lastRejected: -1 };
  const key = () => encodeURIComponent(SB.accessKey() || '');
  const online = () => !!(CAM.st && CAM.st.online);

  function fmtExp(t) {
    if (t === undefined || t === null) return '—';
    if (t >= 10) return `${Math.round(t)} s`;
    if (t >= 0.1) return `${t.toLocaleString('es-ES', { maximumFractionDigits: 1 })} s`;
    return `1/${Math.round(1 / t)} s`;
  }
  function fmtDur(sec) {
    sec = Math.round(sec || 0);
    if (sec < 60) return `${sec} s`;
    const m = Math.floor(sec / 60), s = sec % 60;
    if (m < 60) return s ? `${m} min ${s} s` : `${m} min`;
    return `${Math.floor(m / 60)} h ${m % 60} min`;
  }

  /** Which camera the Imagen tab shows: the iPhone when it is the only one (or chosen). */
  function showingIphone() {
    if (!online()) return false;
    if (!S.info.imaging) return true;
    return prefs.get('imgSource', 'iphone') === 'iphone';
  }

  function cmd(c, extra = {}) {
    if (!send(Object.assign({ type: 'camCmd', source: 'iphone', cmd: c }, extra))) toast('Sin conexión', true);
  }

  // ---------------------------------------------------------------- messages
  SB.on('camStatus', (m) => {
    if (m.source && m.source !== 'iphone') return;
    const was = online();
    CAM.st = m;
    S.iphone = m;
    if (!was && online()) send({ type: 'photos' });
    // a frame set aside: say why (once per frame)
    const f = (m.frames || [])[0];
    if (f && f.decision === 'rejected' && f.index !== CAM.lastRejected) {
      CAM.lastRejected = f.index;
      toast(`iPhone · toma ${f.index} descartada: ${(f.reasons || []).join(', ') || 'sin motivo'}`);
    }
    SB.applyUse();
    render();
  });
  SB.on('camNotice', (m) => { if (!m.source || m.source === 'iphone') toast(`iPhone: ${m.text}`); });
  SB.on('photo', (m) => { if (m.id === 'live-iphone') { CAM.v = m.v; renderImage(); } });
  SB.on('photos', (m) => {
    const live = (m.items || []).find((p) => p.id === 'live-iphone');
    if (live) { CAM.v = live.v; renderImage(); }
  });
  SB.on('tab', () => render());

  // ---------------------------------------------------------------- view
  function renderImage() {
    const img = $('#iphView');
    if (!img || !CAM.v) return;
    const url = `photos/live-iphone.jpg?k=${key()}&v=${CAM.v}`;
    if (img.dataset.src === url) return;
    // swap only once loaded: no flash of an empty frame
    const pre = new Image();
    pre.onload = () => { img.src = url; img.dataset.src = url; $('#iphEmpty').classList.add('hidden'); };
    pre.src = url;
  }

  function render() {
    const iph = showingIphone();
    $('#iphoneStage').classList.toggle('hidden', !iph);
    $('#imgStage').classList.toggle('hidden', iph && S.tab === 'img');
    const both = online() && S.info.imaging;
    $('#camSourceSeg').classList.toggle('hidden', !both);
    // the camera switch sits over the top of the picture: the HUD goes below it
    $('#iphoneStage').classList.toggle('with-source', !!both);
    $('#imgStage').classList.toggle('with-source', !!both);
    document.querySelectorAll('#camSourceSeg button').forEach((b) => b.classList.toggle('active', b.dataset.src === (iph ? 'iphone' : 'android')));
    if (!iph) return;
    const st = CAM.st || {};
    const a = st.activity || 'idle';
    const stacking = a === 'capturing' || a === 'replaying';
    const framing = a === 'framing';
    const assistant = ['focusing', 'darks', 'flats', 'optimizing', 'characterizing'].includes(a);
    const pills = [];
    pills.push(`<span class="ipill strong">${esc(st.target || 'iPhone')}</span>`);
    pills.push(`<span class="ipill">${st.stacked || 0} tomas</span>`);
    if (st.rejected) pills.push(`<span class="ipill warn">${st.rejected} descartadas</span>`);
    if (st.integrationS) pills.push(`<span class="ipill">${fmtDur(st.integrationS)}</span>`);
    if (st.exposureS) pills.push(`<span class="ipill dim">${fmtExp(st.exposureS)} · ISO ${Math.round(st.iso || 0)}</span>`);
    $('#iphPills').innerHTML = pills.join('');
    $('#iphMsg').textContent = st.message || (online() ? 'Listo' : 'El iPhone no está conectado');
    const log = (st.frames || []).slice(0, 3).map((f) => {
      const why = (f.reasons || []).length ? ` · ${f.reasons.join(', ')}` : '';
      const stars = f.stars !== undefined ? ` (${f.stars} estrella${f.stars === 1 ? '' : 's'})` : '';
      const cls = f.decision === 'rejected' ? 'warn' : f.decision === 'downweighted' ? 'dim' : 'good';
      return `<div class="iph-row ${cls}">Toma ${f.index} · ${esc(f.decisionText || f.decision)}${esc(why)}${stars}</div>`;
    });
    $('#iphLog').innerHTML = log.join('');
    $('#iphLog').classList.toggle('hidden', !log.length);
    const main = $('#iphMain');
    main.classList.toggle('active', stacking);
    main.querySelector('span').textContent = stacking ? 'Pausar' : (a === 'paused' ? 'Seguir' : 'Empezar');
    main.disabled = !online() || assistant || a === 'preparing';
    $('#iphFrame span').textContent = framing ? 'Terminar' : 'Encuadre';
    $('#iphFrame').disabled = !online() || stacking || assistant;
    $('#iphSave').disabled = !online() || !(st.stacked > 0);
    $('#iphNew').disabled = !online() || !(st.stacked > 0 || a === 'paused');
    $('#iphExp').disabled = !online();
    if (!CAM.v) $('#iphEmpty').classList.remove('hidden');
    $('#iphEmpty').textContent = online() ? (framing ? 'Encuadre: llegando la vista…' : 'Pulsa Encuadre para ver en vivo, o Empezar') : 'El iPhone no está conectado';
  }

  // ---------------------------------------------------------------- buttons
  $('#iphMain').addEventListener('click', () => {
    const a = (CAM.st || {}).activity;
    cmd(a === 'capturing' || a === 'replaying' ? 'pause' : 'start');
  });
  $('#iphFrame').addEventListener('click', () => cmd((CAM.st || {}).activity === 'framing' ? 'stopFraming' : 'framing'));
  $('#iphSave').addEventListener('click', () => { cmd('save'); toast('Guardando en el iPhone…'); });
  $('#iphNew').addEventListener('click', () => {
    const body = SB.openSheet(`<h3>Nuevo apilado en el iPhone</h3>
      <p class="row-sub">El apilado actual (${(CAM.st || {}).stacked || 0} tomas) se cierra.</p>
      <button class="btn primary full" data-new="save">Guardar y empezar otro</button>
      <button class="btn full" data-new="discard">Empezar otro sin guardar</button>
      <button class="btn ghost full" data-new="cancel">Cancelar</button>`);
    body.querySelectorAll('[data-new]').forEach((b) => b.addEventListener('click', () => {
      if (b.dataset.new !== 'cancel') cmd('newStack', { save: b.dataset.new === 'save' });
      SB.closeSheet();
    }));
  });
  $('#iphExp').addEventListener('click', () => {
    const st = CAM.st || {};
    const body = SB.openSheet(`<h3>Exposición del iPhone</h3>
      <p class="row-sub">Se aplica en el próximo apilado.</p>
      <label class="row-between"><span>Automática</span><input type="checkbox" id="iphAuto" ${st.autoExposure ? 'checked' : ''}></label>
      <label class="row-between"><span>Exposición (s)</span><input type="number" id="iphExpS" step="0.1" min="0.01" max="60" value="${st.exposureSetting ?? 1}"></label>
      <label class="row-between"><span>ISO</span><input type="number" id="iphIso" step="50" min="25" max="25600" value="${Math.round(st.isoSetting ?? 800)}"></label>
      <button class="btn primary full" id="iphExpOk">Aplicar</button>
      <p class="row-sub">Enfoque del objetivo del iPhone sobre las estrellas (unos minutos; busca el punto más pequeño y nítido):</p>
      <button class="btn full" id="iphFocus">Enfocar el móvil</button>`);
    const sync = () => { body.querySelector('#iphExpS').disabled = body.querySelector('#iphAuto').checked; body.querySelector('#iphIso').disabled = body.querySelector('#iphAuto').checked; };
    body.querySelector('#iphAuto').addEventListener('change', sync);
    sync();
    body.querySelector('#iphFocus').addEventListener('click', () => { cmd('focus'); SB.closeSheet(); });
    body.querySelector('#iphExpOk').addEventListener('click', () => {
      const auto = body.querySelector('#iphAuto').checked;
      const e = parseFloat(body.querySelector('#iphExpS').value.replace(',', '.'));
      const iso = parseFloat(body.querySelector('#iphIso').value);
      cmd('settings', Object.assign({ autoExposure: auto }, auto ? {} : { exposureS: e, iso }));
      SB.closeSheet();
    });
  });
  document.querySelectorAll('#camSourceSeg button').forEach((b) => b.addEventListener('click', () => {
    prefs.set('imgSource', b.dataset.src);
    render();
  }));

  render();
})();
