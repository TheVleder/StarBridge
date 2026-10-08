// StarBridge · visible zones: the part of the sky you can really see (a window, a gap in the
// trees), drawn on the map or traced with the telescope. The hub keeps them (zones / setZone /
// deleteZone) and marks every object inZone. Here: shading on the sky map, the editor, the list
// in Más. Uses the API of app.js.
(() => {
  'use strict';
  const SB = window.SB;
  if (!SB) return;
  const { $, send, toast, esc, S } = SB;
  S.zones = [];
  let draft = null; // { id?, name?, points: [[az, alt], …] } while drawing

  // ---------------------------------------------------------------- geometry
  const rad = Math.PI / 180;
  const unit = ([az, alt]) => [Math.cos(alt * rad) * Math.sin(az * rad), Math.cos(alt * rad) * Math.cos(az * rad), Math.sin(alt * rad)];
  function toAzAlt(v) {
    const l = Math.hypot(v[0], v[1], v[2]) || 1;
    const x = v[0] / l, y = v[1] / l, z = v[2] / l;
    return [((Math.atan2(x, y) / rad) + 360) % 360, Math.asin(Math.max(-1, Math.min(1, z))) / rad];
  }
  /** The zone outline in screen coordinates: great-circle edges, 16 steps each. */
  function outline(points, project, closed = true) {
    const out = [];
    const n = points.length;
    for (let i = 0; i < (closed ? n : n - 1); i++) {
      const a = unit(points[i]), b = unit(points[(i + 1) % n]);
      for (let k = 0; k < 16; k++) {
        const t = k / 16;
        out.push(project(...toAzAlt([a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t])));
      }
    }
    if (!closed && n) out.push(project(...points[n - 1]));
    return out;
  }
  function trace(ctx, pts) {
    pts.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
  }
  let layer = null;

  // ---------------------------------------------------------------- sky map
  SB.on('skyDraw', (ctx, v) => {
    const active = S.zones.filter((z) => z.on);
    if (active.length) {
      // dim what you cannot see: a dark disc with the zones cut out of it
      layer = layer || document.createElement('canvas');
      if (layer.width !== ctx.canvas.width || layer.height !== ctx.canvas.height) { layer.width = ctx.canvas.width; layer.height = ctx.canvas.height; }
      const o = layer.getContext('2d');
      o.setTransform(1, 0, 0, 1, 0, 0);
      o.clearRect(0, 0, layer.width, layer.height);
      o.setTransform(v.dpr, 0, 0, v.dpr, 0, 0);
      o.fillStyle = 'rgba(0, 0, 0, 0.6)';
      o.beginPath(); o.arc(v.zenith[0], v.zenith[1], v.radius, 0, 2 * Math.PI); o.fill();
      o.globalCompositeOperation = 'destination-out';
      for (const z of active) { o.beginPath(); trace(o, outline(z.points, v.project)); o.closePath(); o.fill(); }
      o.globalCompositeOperation = 'source-over';
      ctx.save(); ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.drawImage(layer, 0, 0); ctx.restore();
    }
    ctx.lineWidth = 1.5;
    for (const z of S.zones) {
      if (draft && draft.id === z.id) continue;
      ctx.strokeStyle = getComputedStyle(document.body).getPropertyValue('--good').trim() || '#5c5';
      ctx.globalAlpha = z.on ? 0.9 : 0.35;
      ctx.setLineDash(z.on ? [] : [4, 4]);
      ctx.beginPath(); trace(ctx, outline(z.points, v.project)); ctx.closePath(); ctx.stroke();
    }
    ctx.globalAlpha = 1; ctx.setLineDash([]);
    if (draft && draft.points.length) {
      ctx.strokeStyle = getComputedStyle(document.body).getPropertyValue('--accent').trim() || '#f80';
      ctx.fillStyle = ctx.strokeStyle;
      ctx.setLineDash([5, 4]);
      ctx.beginPath(); trace(ctx, outline(draft.points, v.project, draft.points.length >= 3)); ctx.stroke();
      ctx.setLineDash([]);
      for (const p of draft.points) { const [x, y] = v.project(...p); ctx.beginPath(); ctx.arc(x, y, 4, 0, 2 * Math.PI); ctx.fill(); }
    }
  });

  // a tap while drawing adds a corner instead of opening an object
  SB.on('skyTap', (sky) => {
    if (!draft) return false;
    if (sky.alt < -10) { toast('Eso está bajo el horizonte'); return true; }
    if (draft.points.length >= 64) { toast('Máximo 64 esquinas'); return true; }
    draft.points.push([Math.round(sky.az * 100) / 100, Math.round(Math.max(sky.alt, -10) * 100) / 100]);
    renderBar(); SB.drawSky();
    return true;
  });

  // ---------------------------------------------------------------- editing
  function startDraft(z) {
    draft = z ? { id: z.id, name: z.name, on: z.on, points: z.points.map((p) => p.slice()) } : { points: [] };
    SB.showTab('sky');
    renderBar(); SB.drawSky();
  }
  function endDraft() { draft = null; renderBar(); SB.drawSky(); }

  function renderBar() {
    const bar = $('#zoneBar');
    bar.classList.toggle('hidden', !draft);
    if (!draft) return;
    const n = draft.points.length;
    const scope = S.status && S.status.alt !== undefined;
    bar.innerHTML = `
      <div class="zone-bar-title">${draft.id ? `Editando «${esc(draft.name)}»` : 'Nueva zona visible'} · ${n} esquina${n === 1 ? '' : 's'}</div>
      <div class="zone-bar-hint">Toca el mapa en cada esquina de lo que ves (en orden), o apunta el telescopio a cada esquina.</div>
      <div class="zone-bar-btns">
        <button class="btn small" id="zScope" ${scope ? '' : 'disabled'}>Esquina = telescopio</button>
        <button class="btn small" id="zUndo" ${n ? '' : 'disabled'}>Deshacer</button>
        <button class="btn small ghost" id="zCancel">Cancelar</button>
        <button class="btn small primary" id="zSave" ${n >= 3 ? '' : 'disabled'}>Guardar</button>
      </div>`;
    $('#zScope').addEventListener('click', () => {
      const st = S.status;
      if (st.alt === undefined) { toast('El telescopio aún no sabe dónde apunta: alinéalo'); return; }
      draft.points.push([Math.round(st.az * 100) / 100, Math.round(Math.max(st.alt, -10) * 100) / 100]);
      renderBar(); SB.drawSky();
    });
    $('#zUndo').addEventListener('click', () => { draft.points.pop(); renderBar(); SB.drawSky(); });
    $('#zCancel').addEventListener('click', endDraft);
    $('#zSave').addEventListener('click', () => {
      let name = draft.name;
      if (!draft.id) {
        name = window.prompt('Nombre de la zona', 'Ventana') ?? null;
        if (name === null) return;
      }
      const zone = { name: name || 'Zona visible', on: draft.on ?? true, points: draft.points };
      if (draft.id) zone.id = draft.id;
      if (send({ type: 'setZone', zone })) endDraft(); else toast('Sin conexión', true);
    });
  }

  // ---------------------------------------------------------------- Más › Zonas visibles
  function renderCard() {
    const card = $('#zonesCard');
    if (!card) return;
    const rows = S.zones.map((z) => `
      <div class="row-between zone-row" data-id="${esc(z.id)}">
        <label class="check-row"><input type="checkbox" data-on ${z.on ? 'checked' : ''}> ${esc(z.name)} <span class="dim">· ${z.points.length} esquinas</span></label>
        <span><button class="btn small" data-edit>Editar</button> <button class="btn small ghost" data-del>Borrar</button></span>
      </div>`).join('');
    card.innerHTML = `${rows || '<p class="hint">Sin zonas: se considera visible todo el cielo.</p>'}
      <button class="btn full" id="zNew">Nueva zona visible…</button>
      <p class="hint">Marca la parte del cielo que ves de verdad (una ventana, un hueco entre árboles). En el mapa se oscurece lo demás, la búsqueda puede mostrar solo lo que ves y un GoTo fuera de la zona avisa.</p>`;
    card.querySelectorAll('.zone-row').forEach((row) => {
      const z = S.zones.find((x) => x.id === row.dataset.id);
      row.querySelector('[data-on]').addEventListener('change', (e) => send({ type: 'setZone', zone: { id: z.id, name: z.name, on: e.target.checked, points: z.points } }));
      row.querySelector('[data-edit]').addEventListener('click', () => startDraft(z));
      row.querySelector('[data-del]').addEventListener('click', () => { if (window.confirm(`¿Borrar la zona «${z.name}»?`)) send({ type: 'deleteZone', id: z.id }); });
    });
    $('#zNew').addEventListener('click', () => startDraft(null));
  }

  SB.on('zones', (m) => {
    S.zones = m.zones || [];
    const any = S.zones.some((z) => z.on);
    $('#onlyZoneRow')?.classList.toggle('hidden', !any);
    renderCard(); SB.drawSky(); SB.renderResults();
  });
  SB.on('status', () => { const b = $('#zScope'); if (draft && b) b.disabled = S.status.alt === undefined; });
  renderCard();
})();
