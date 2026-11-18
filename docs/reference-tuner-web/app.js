// app.js — interface du prototype web du tuner Simon.
// Trois modes d'analyse (SPEC v1) :
//   Auto    — détection libre : le NoteDetector propose la note jouée, la
//             mesure fine se verrouille sur son partiel de référence.
//   Demi-ton — la détection ne peut adopter qu'une note à ±1 demi-ton.
//   Bloqué  — mesure verrouillée sur la note sélectionnée.
// Toute sélection manuelle (‹ ›, liste) bascule en Bloqué, comme la SPEC.
// Deux chemins de capture : AudioWorklet (défaut) ou repli AnalyserNode
// (navigateurs intégrés qui refusent le worklet) — même moteur dsp.js.
'use strict';

// --- Barre −30..+30 ¢ à échelle segmentée (SPEC v1) ---
const SEG = [[-30, 0], [-10, 0.10], [-1, 0.25], [0, 0.50], [1, 0.75], [10, 0.90], [30, 1.00]];
function centsToFrac(c) {
  c = Math.max(-30, Math.min(30, c));
  for (let i = 0; i < SEG.length - 1; i++) {
    const [c0, f0] = SEG[i], [c1, f1] = SEG[i + 1];
    if (c >= c0 && c <= c1) return f0 + (f1 - f0) * (c - c0) / (c1 - c0);
  }
  return c < 0 ? 0 : 1;
}

const $ = (id) => document.getElementById(id);
const el = {
  tuning: $('selTuning'), pitch: $('segPitch'), mode: $('segMode'), note: $('selNote'),
  btnMic: $('btnMic'), err: $('err'), noteName: $('noteName'), noteMeta: $('noteMeta'),
  badge: $('noteBadge'), cents: $('cents'), cursor: $('cursor'), bar: $('barWrap'), diag: $('diag'),
  rng: $('rngSmooth'),
};

// Graduations de la barre
for (const g of [30, 20, 10, 5, 1]) {
  for (const s of [-1, 1]) {
    const f = centsToFrac(s * g);
    const t = document.createElement('div'); t.className = 'tick'; t.style.left = (f * 100) + '%';
    el.bar.appendChild(t);
    const l = document.createElement('div'); l.className = 'tickLab'; l.style.left = (f * 100) + '%';
    l.textContent = g; el.bar.appendChild(l);
  }
}
{ const l = document.createElement('div'); l.className = 'tickLab'; l.style.left = '50%'; l.textContent = '0'; el.bar.appendChild(l); }

let DATA = null;
let tuningId = null, key = 49, pitch = 440, mode = 'auto';
let actx = null, workletNode = null, stream = null, wakeLock = null;
let fbTimer = null, fbTracker = null, fbDetector = null, fbTableId = null, runMode = null;
let lastState = null, dispCents = 0;

// --- Réactivité de la barre (curseur « Réactivité de la barre ») ---
// Moyenne exponentielle sur la valeur AFFICHÉE (le curseur), rien
// d'autre : la détection, la confiance et la lecture numérique
// (toujours la mesure brute) ne sont pas touchées. Curseur à 100 =
// alpha 1 (aucun lissage, la barre suit la mesure brute) ; curseur
// à 0 = alpha 0,02 (barre très lente et stable). Échelle
// exponentielle : le défaut 73 redonne alpha ≈ 0,35, le lissage
// d'origine du prototype. Réglage persisté (localStorage).
let smoothAlpha = 0.35;
function alphaDepuisCurseur(v) { return 0.02 * Math.pow(50, v / 100); }
{
  let v = 73;
  try {
    const saved = Number(localStorage.getItem('tunerReactivite'));
    if (Number.isFinite(saved) && saved >= 0 && saved <= 100) v = saved;
  } catch (e) { /* stockage indisponible : défaut */ }
  el.rng.value = String(v);
  smoothAlpha = alphaDepuisCurseur(v);
  el.rng.addEventListener('input', () => {
    const val = Number(el.rng.value);
    smoothAlpha = alphaDepuisCurseur(val);
    try { localStorage.setItem('tunerReactivite', String(val)); } catch (e) { /* best effort */ }
  });
}

function currentTuning() { return DATA.tunings.find(x => x.id === tuningId); }
function currentNote() { return currentTuning().notes[key - 1]; }
function targetHz() { return currentNote().partialTargetHz440 * (pitch / 440); }
function tableId() { return tuningId + '@' + pitch; }
function notesTable() {
  return currentTuning().notes.map(n => ({
    key: n.key,
    fundamentalHz: n.fundamentalHz440 * (pitch / 440),
    partialTargetHz: n.partialTargetHz440 * (pitch / 440),
    listenPartial: n.listenPartial,
  }));
}

// Envoie la configuration complète au moteur (worklet ou objets locaux).
function sendConfig() {
  if (!DATA) return;
  if (workletNode) {
    workletNode.port.postMessage({ type: 'config', mode, key, tableId: tableId(), notes: notesTable() });
  }
  if (fbTracker) {
    if (fbTableId !== tableId()) { fbTableId = tableId(); fbDetector.setNotes(notesTable()); }
    if (mode === 'bloque') fbTracker.setTarget(targetHz());
  }
  refreshMeta();
}

function refreshMeta() {
  const n = currentNote();
  el.noteName.textContent = n.name;
  el.note.value = String(key);
  el.noteMeta.textContent =
    `Touche ${n.key} · partiel écouté nº ${n.listenPartial} · cible du partiel ${targetHz().toFixed(1)} Hz` +
    ` · fondamental cible ${(n.fundamentalHz440 * pitch / 440).toFixed(1)} Hz`;
}

function setMode(m) {
  mode = m;
  for (const b of el.mode.children) b.classList.toggle('on', b.dataset.mode === m);
  if (m === 'bloque') el.badge.textContent = '';
  sendConfig();
}

function setKey(k, fromDetector) {
  key = k;
  el.badge.textContent = fromDetector ? '🎯 note détectée' : '';
  if (fromDetector) {
    // Le worklet a déjà verrouillé son traqueur ; en repli, on le fait ici.
    if (fbTracker) fbTracker.setTarget(targetHz());
    refreshMeta();
  } else {
    if (mode !== 'bloque') setMode('bloque'); else sendConfig();
  }
}

for (const b of el.mode.children) b.onclick = () => setMode(b.dataset.mode);

fetch('tunings.json').then(r => r.json()).then(d => {
  DATA = d;
  for (const t of d.tunings) {
    const o = document.createElement('option');
    o.value = t.id; o.textContent = `${t.name} (${t.serial})`;
    el.tuning.appendChild(o);
  }
  tuningId = d.tunings[0].id;
  for (let k = 1; k <= 88; k++) {
    const n = d.tunings[0].notes[k - 1];
    const o = document.createElement('option');
    o.value = k; o.textContent = `${k} · ${n.name}`;
    el.note.appendChild(o);
  }
  for (const p of d.pitchChoicesHz) {
    const b = document.createElement('button');
    b.textContent = p; if (p === pitch) b.className = 'on';
    b.onclick = () => {
      pitch = p;
      for (const x of el.pitch.children) x.classList.toggle('on', Number(x.textContent) === p);
      sendConfig();
    };
    el.pitch.appendChild(b);
  }
  refreshMeta();
}).catch(e => { el.err.textContent = 'Impossible de charger tunings.json : ' + e.message; });

el.tuning.onchange = () => { tuningId = Number(el.tuning.value); fbTableId = null; sendConfig(); };
el.note.onchange = () => setKey(Number(el.note.value), false);
$('btnUp').onclick = () => { if (key < 88) setKey(key + 1, false); };
$('btnDown').onclick = () => { if (key > 1) setKey(key - 1, false); };

// Gestes sur le rond central (demande terrain) : glisser ← → = demi-ton
// (−1 / +1), glisser ↑ ↓ = octave (+12 / −12). Sélection manuelle, donc
// bascule en Bloqué comme les flèches (SPEC).
{
  const circle = $('noteCircle');
  let gStart = null;
  circle.addEventListener('pointerdown', (e) => { gStart = { x: e.clientX, y: e.clientY }; });
  circle.addEventListener('pointercancel', () => { gStart = null; });
  circle.addEventListener('pointerup', (e) => {
    if (!gStart) return;
    const dx = e.clientX - gStart.x, dy = e.clientY - gStart.y;
    gStart = null;
    const ax = Math.abs(dx), ay = Math.abs(dy);
    if (Math.max(ax, ay) < 24) return;
    const delta = ax > ay ? (dx > 0 ? 1 : -1) : (dy < 0 ? 12 : -12);
    const nk = Math.max(1, Math.min(88, key + delta));
    if (nk !== key) {
      setKey(nk, false);
      circle.classList.add('pulse');
      setTimeout(() => circle.classList.remove('pulse'), 160);
    }
  });
}

function diagStart(st) {
  el.diag.textContent =
    `Mode ${runMode === 'worklet' ? 'AudioWorklet' : 'compatibilité (AnalyserNode)'} · contexte audio : ${actx.sampleRate} Hz` +
    ` · micro (getSettings) : echoCancellation=${st.echoCancellation}, noiseSuppression=${st.noiseSuppression}, ` +
    `autoGainControl=${st.autoGainControl}, sampleRate=${st.sampleRate || '?'}` +
    ` · en écoute… joue une note près du micro.`;
}

function startFallback(src) {
  runMode = 'compat';
  const analyser = actx.createAnalyser();
  analyser.fftSize = 32768;
  src.connect(analyser);
  fbTracker = new DSP.PartialTracker({ sampleRate: actx.sampleRate, phaseRefine: false });
  fbDetector = new DSP.NoteDetector({ sampleRate: actx.sampleRate });
  fbTableId = null;
  sendConfig();
  const tmp = new Float32Array(analyser.fftSize);
  fbTimer = setInterval(() => {
    analyser.getFloatTimeDomainData(tmp);
    fbTracker.push(tmp);
    if (mode !== 'bloque') {
      fbDetector.push(tmp);
      let cand = fbDetector.adoptedKey;
      if (cand && mode === 'demi' && Math.abs(cand - key) > 1) cand = null;
      if (cand && cand !== key) setKey(cand, true);
    }
    const t = fbTracker;
    lastState = {
      type: 'state', mode: runMode, sampleRate: actx.sampleRate,
      frameConf: t.frameConf, levelDb: t.frameLevelDb, snrDb: t.frameSnrDb, ageSec: t.latestAgeSec(),
      latest: t.latest ? { freqHz: t.latest.freqHz, cents: t.latest.cents, rawCents: t.latest.rawCents } : null,
    };
  }, 90);
}

async function startMic() {
  el.err.textContent = '';
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
    });
  } catch (e) {
    el.err.textContent = 'Micro refusé ou indisponible : ' + e.message;
    return;
  }
  try {
    actx = new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'interactive' });
    const src = actx.createMediaStreamSource(stream);
    let workletOk = false;
    if (actx.audioWorklet && typeof AudioWorkletNode !== 'undefined') {
      try {
        await actx.audioWorklet.addModule('worklet.js');
        workletNode = new AudioWorkletNode(actx, 'tuner-processor');
        workletOk = true;
      } catch (e) {
        workletOk = false; // on tente le repli ci-dessous
      }
    }
    if (workletOk) {
      runMode = 'worklet';
      const mute = actx.createGain(); mute.gain.value = 0;
      src.connect(workletNode); workletNode.connect(mute); mute.connect(actx.destination);
      workletNode.port.onmessage = (e) => {
        if (e.data.type === 'note') setKey(e.data.key, true);
        else lastState = e.data;
      };
    } else {
      startFallback(src);
    }
    await actx.resume();
    sendConfig();
    const track = stream.getAudioTracks()[0];
    diagStart(track.getSettings ? track.getSettings() : {});
    el.btnMic.textContent = '⏹ Arrêter le micro';
    el.btnMic.classList.add('stop');
    try { wakeLock = await navigator.wakeLock.request('screen'); } catch (e) { /* best effort */ }
  } catch (e) {
    el.err.textContent = 'Échec du démarrage audio : ' + e.message;
  }
}

function stopMic() {
  if (fbTimer) { clearInterval(fbTimer); fbTimer = null; }
  fbTracker = fbDetector = null; fbTableId = null;
  if (stream) stream.getTracks().forEach(t => t.stop());
  if (actx) actx.close();
  if (wakeLock) wakeLock.release();
  actx = workletNode = stream = wakeLock = null; lastState = null; runMode = null;
  el.btnMic.textContent = '🎙 Activer le micro';
  el.btnMic.classList.remove('stop');
  el.cents.className = 'uncertain'; el.cents.textContent = 'signal incertain';
  el.cursor.className = ''; el.cursor.id = 'cursor';
}

el.btnMic.onclick = () => { (actx ? stopMic : startMic)(); };
document.addEventListener('visibilitychange', async () => {
  if (actx && document.visibilityState === 'visible') {
    try { wakeLock = await navigator.wakeLock.request('screen'); } catch (e) { /* best effort */ }
  }
});

// Boucle d'affichage : aiguille amortie, lecture numérique = mesure brute.
function render() {
  requestAnimationFrame(render);
  if (!lastState) return;
  const s = lastState;
  const live = s.latest && s.frameConf >= 0.35;
  // Tenue : le signal faiblit (note qui s'éteint) mais la dernière mesure
  // solide reste affichée ~2,5 s, comme la mémoire d'un accordeur réel.
  const holding = !live && s.latest && s.frameConf >= 0.06 && (s.ageSec ?? 999) <= 2.5;
  const modeTxt = s.mode === 'compat' ? ' · mode compatibilité' : '';
  if (live || holding) {
    const c = s.latest.cents;
    dispCents += (c - dispCents) * smoothAlpha;
    el.cents.className = Math.abs(c) <= 0.1 ? 'ok' : '';
    el.cents.style.opacity = holding ? '0.55' : '1';
    el.cents.textContent = (c >= 0 ? '+' : '') + c.toFixed(1) + ' ¢';
    el.cursor.style.left = (centsToFrac(dispCents) * 100) + '%';
    el.cursor.className = Math.abs(c) <= 0.1 ? 'ok' : 'live';
    el.cursor.style.opacity = holding ? '0.55' : '1';
    el.diag.textContent =
      `Mesure : ${s.latest.freqHz.toFixed(2)} Hz · brut ${s.latest.rawCents >= 0 ? '+' : ''}${s.latest.rawCents.toFixed(2)} ¢` +
      ` · RSB ${s.snrDb.toFixed(0)} dB · niveau ${s.levelDb.toFixed(0)} dBFS · contexte ${s.sampleRate} Hz${modeTxt}` +
      (holding ? ' · signal faiblissant — mesure tenue' : '');
  } else {
    el.cents.style.opacity = '1';
    el.cents.className = 'uncertain';
    el.cents.textContent = 'signal incertain';
    el.cursor.className = '';
    if (actx) el.diag.textContent =
      `Signal faible ou instable · RSB ${s.snrDb.toFixed(0)} dB · niveau ${s.levelDb.toFixed(0)} dBFS · contexte ${s.sampleRate} Hz${modeTxt} — rapproche le téléphone des cordes.`;
  }
}
requestAnimationFrame(render);
