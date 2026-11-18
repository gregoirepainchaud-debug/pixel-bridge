// dsp.js — cœur d'analyse du tuner web (prototype).
// Partagé entre : le test Node (test-dsp.js), l'AudioWorklet (importScripts)
// et, au besoin, le fil principal. Aucune dépendance.
//
// Méthode : FFT fenêtrée (Hann, 32768) sur le flux audio, recherche du pic
// dans une fenêtre de ±60 cents autour du partiel de référence attendu,
// raffinement par interpolation complexe (Jacobsen) puis par l'avance de
// phase entre trames (méthode « phase vocoder »), lissage pondéré par la
// confiance (RSB spectral local).

'use strict';

function makeFFT(size) {
  const levels = Math.round(Math.log2(size));
  if (2 ** levels !== size) throw new Error('FFT: taille non puissance de 2');
  const rev = new Uint32Array(size);
  for (let i = 0; i < size; i++) {
    let r = 0, x = i;
    for (let j = 0; j < levels; j++) { r = (r << 1) | (x & 1); x >>= 1; }
    rev[i] = r;
  }
  const cosT = new Float64Array(size / 2);
  const sinT = new Float64Array(size / 2);
  for (let i = 0; i < size / 2; i++) {
    cosT[i] = Math.cos((2 * Math.PI * i) / size);
    sinT[i] = -Math.sin((2 * Math.PI * i) / size);
  }
  function fft(re, im) {
    const n = size;
    for (let i = 0; i < n; i++) {
      const j = rev[i];
      if (j > i) {
        let t = re[i]; re[i] = re[j]; re[j] = t;
        t = im[i]; im[i] = im[j]; im[j] = t;
      }
    }
    let len = 2;
    while (len <= n) {
      const half = len / 2;
      const step = n / len;
      for (let i = 0; i < n; i += len) {
        let tw = 0;
        for (let j = 0; j < half; j++, tw += step) {
          const wr = cosT[tw], wi = sinT[tw];
          const a = i + j, b = i + j + half;
          const xr = re[b] * wr - im[b] * wi;
          const xi = re[b] * wi + im[b] * wr;
          re[b] = re[a] - xr; im[b] = im[a] - xi;
          re[a] += xr; im[a] += xi;
        }
      }
      len <<= 1;
    }
  }
  return { fft, size };
}

function wrapPi(x) {
  while (x > Math.PI) x -= 2 * Math.PI;
  while (x < -Math.PI) x += 2 * Math.PI;
  return x;
}

class PartialTracker {
  constructor(opts) {
    this.sr = opts.sampleRate;
    this.N = opts.fftSize || 32768;
    this.hop = opts.hopSize || 4096;
    // Le raffinement par avance de phase exige des pas d'analyse à nombre
    // d'échantillons connu (worklet). En repli (AnalyserNode sondé), les
    // fenêtres se succèdent sans décalage connu : on le désactive.
    this.phaseEnabled = opts.phaseRefine !== false;
    this.buf = new Float32Array(this.N);
    this.filled = 0;       // échantillons reçus (≤ N au début)
    this.sinceHop = 0;     // échantillons depuis la dernière analyse
    this.total = 0;        // échantillons reçus au total
    this.fftObj = makeFFT(this.N);
    this.re = new Float64Array(this.N);
    this.im = new Float64Array(this.N);
    this.win = new Float64Array(this.N);
    for (let i = 0; i < this.N; i++) {
      this.win[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (this.N - 1));
    }
    this.targetHz = null;
    this.lastBin = -1;
    this.lastPhase = 0;
    this.phaseOk = 0;      // trames consécutives avec phase cohérente
    this.hist = [];        // {f, conf} récents
    this.latest = null;    // {freqHz, cents, confidence, snrDb, levelDb}
    this.frameConf = 0;    // confiance de la dernière trame analysée
    this.frameLevelDb = -120;
    this.frameSnrDb = 0;
    this.lastAcceptedTotal = -1; // échantillon de la dernière trame acceptée
  }

  setTarget(hz) {
    this.targetHz = hz;
    this.lastBin = -1;
    this.phaseOk = 0;
    this.hist.length = 0;
    this.latest = null;
    this.lastAcceptedTotal = -1;
  }

  // Âge (secondes) de la dernière trame acceptée dans l'historique —
  // sert à l'affichage « tenue » quand le signal s'éteint.
  latestAgeSec() {
    return this.lastAcceptedTotal < 0 ? 999 : (this.total - this.lastAcceptedTotal) / this.sr;
  }

  push(samples) {
    const n = samples.length;
    if (n >= this.N) {
      this.buf.set(samples.subarray(n - this.N));
      this.filled = this.N;
    } else {
      this.buf.copyWithin(0, n);
      this.buf.set(samples, this.N - n);
      this.filled = Math.min(this.N, this.filled + n);
    }
    this.total += n;
    this.sinceHop += n;
    if (this.targetHz && this.filled >= this.N && this.sinceHop >= this.hop) {
      this.sinceHop = 0;
      this.analyze();
    }
  }

  analyze() {
    const { N, sr } = this;
    const re = this.re, im = this.im;
    for (let i = 0; i < N; i++) { re[i] = this.buf[i] * this.win[i]; im[i] = 0; }
    this.fftObj.fft(re, im);

    const binHz = sr / N;
    const fT = this.targetHz;
    const lo = Math.max(2, Math.floor((fT * Math.pow(2, -60 / 1200)) / binHz));
    const hi = Math.min(N / 2 - 2, Math.ceil((fT * Math.pow(2, 60 / 1200)) / binHz));

    let k = -1, best = -1;
    for (let b = lo; b <= hi; b++) {
      const m = re[b] * re[b] + im[b] * im[b];
      if (m > best) { best = m; k = b; }
    }
    if (k < 0) return;
    const peakMag = Math.sqrt(best);

    // Plancher de bruit : médiane des magnitudes sur [fT/2, fT*2.5], hors pic ±4.
    const nlo = Math.max(2, Math.floor(fT / 2 / binHz));
    const nhi = Math.min(N / 2 - 1, Math.ceil(fT * 2.5 / binHz));
    const mags = [];
    for (let b = nlo; b <= nhi; b += 3) {
      if (Math.abs(b - k) <= 4) continue;
      mags.push(Math.sqrt(re[b] * re[b] + im[b] * im[b]));
    }
    mags.sort((a, b) => a - b);
    // 30e percentile plutôt que médiane : les partiels voisins et leurs
    // lobes gonflent la médiane et font chuter le RSB mesuré quand le
    // partiel écouté s'éteint, alors qu'il reste nettement audible.
    const floor = mags.length ? mags[Math.floor(mags.length * 0.3)] : 0;
    const snrDb = floor > 0 ? 20 * Math.log10(peakMag / floor) : 60;

    // Niveau absolu (RMS de la fenêtre) pour le seuil « signal faible ».
    let s = 0;
    for (let i = 0; i < N; i += 16) s += this.buf[i] * this.buf[i];
    const rms = Math.sqrt(s / (N / 16));
    const levelDb = 20 * Math.log10(rms + 1e-9);

    // Confiance : le RSB spectral décide ; le niveau absolu ne sert qu'à
    // écarter le quasi-silence numérique. (Avant : niveau −55 dBFS -> 0 —
    // une note de piano qui s'éteint y tombait en ~2 s alors que le partiel
    // restait 20+ dB au-dessus du bruit. Retour terrain du 2026-10-30.)
    const cSnr = Math.min(1, Math.max(0, (snrDb - 8) / 22));
    const cLvl = Math.min(1, Math.max(0, (levelDb + 70) / 30));
    const conf = cSnr * cLvl;

    // --- Interpolation complexe (Jacobsen) autour du pic ---
    const xm1 = { r: re[k - 1], i: im[k - 1] };
    const x0 = { r: re[k], i: im[k] };
    const xp1 = { r: re[k + 1], i: im[k + 1] };
    // delta = Re( (X[k-1] - X[k+1]) / (X[k-1] - 2X[k] + X[k+1]) )
    const num = { r: xm1.r - xp1.r, i: xm1.i - xp1.i };
    const den = { r: xm1.r - 2 * x0.r + xp1.r, i: xm1.i - 2 * x0.i + xp1.i };
    const denMag = den.r * den.r + den.i * den.i;
    let delta = 0;
    if (denMag > 0) delta = (num.r * den.r + num.i * den.i) / denMag;
    if (!isFinite(delta) || Math.abs(delta) > 1) delta = 0;
    // Correction empirique (calibrate.js, 2026-10-30) : avec la fenêtre Hann
    // et cette forme du quotient, la sortie brute vaut exactement −δ/2 sur
    // des sinus purs (résidu < 0,0001 case, bins 150 et 600). On applique
    // donc δ ← −2δ. Sans elle, l'estimation sans phase biaisait de 1–2 ¢.
    delta = Math.max(-0.6, Math.min(0.6, -2 * delta));
    const fInterp = (k + delta) * binHz;

    // --- Avance de phase (raffinement, si le pic est stable) ---
    const phase = Math.atan2(x0.i, x0.r);
    let fUsed = fInterp;
    if (this.phaseEnabled && k === this.lastBin) {
      const expected = (2 * Math.PI * k * this.hop) / N;
      const dphi = wrapPi(phase - this.lastPhase - expected);
      const fPhase = (k + (dphi / (2 * Math.PI)) * (N / this.hop)) * binHz;
      if (Math.abs(fPhase - fInterp) < binHz * 0.75) {
        this.phaseOk = Math.min(this.phaseOk + 1, 10);
        if (this.phaseOk >= 2) fUsed = fPhase;
      } else {
        this.phaseOk = 0;
      }
    } else {
      this.phaseOk = 0;
    }
    this.lastBin = k;
    this.lastPhase = phase;

    this.frameConf = conf;
    this.frameLevelDb = levelDb;
    this.frameSnrDb = snrDb;
    if (conf > 0.08) {
      this.hist.push({ f: fUsed, conf });
      if (this.hist.length > 16) this.hist.shift();
      this.lastAcceptedTotal = this.total;
    }

    // Sortie : médiane pondérée simple — moyenne des estimations à
    // ±0,5 cent de la médiane brute, pondérée par la confiance.
    if (this.hist.length) {
      const fs = this.hist.map(h => h.f).slice().sort((a, b) => a - b);
      const med = fs[Math.floor(fs.length / 2)];
      let sw = 0, sf = 0;
      for (const h of this.hist) {
        const dc = Math.abs(1200 * Math.log2(h.f / med));
        if (dc <= 0.5) { sw += h.conf; sf += h.conf * h.f; }
      }
      const fOut = sw > 0 ? sf / sw : med;
      const confOut = this.hist.reduce((a, h) => a + h.conf, 0) / this.hist.length;
      this.latest = {
        freqHz: fOut,
        cents: 1200 * Math.log2(fOut / fT),
        confidence: Math.min(1, confOut * (conf > 0.08 ? 1 : 0.4)),
        snrDb, levelDb,
        peakBin: k,
        rawCents: 1200 * Math.log2(fUsed / fT),
      };
    }
  }
}

// ---------------------------------------------------------------------------
// NoteDetector — mode Auto/Demi-ton de la SPEC : propose la note jouée.
// FFT 16384 ; pour chacune des 88 notes de l'accord chargé, score = somme
// des pics spectraux qui COÏNCIDENT (en cents, noyau triangulaire ±75 ¢)
// avec les partiels 1..6 attendus + bonus au partiel de référence quand il
// est au-delà du 6e (les graves : 7, 10, 12). La coïncidence est essentielle :
// l'énergie brute dans une fenêtre ne discrimine pas les demi-tons voisins
// dans les graves (lobe principal de la FFT, ~1re version écartée par les
// tests : marge 1,0–1,2). L'inharmonicité B de chaque note est déduite de
// ses propres données (fondamental + partiel de référence).
// Adoption : meilleur score > 9× le plancher spectral et > 1,22× le second,
// trois analyses de suite.
// ---------------------------------------------------------------------------
class NoteDetector {
  constructor(opts) {
    this.sr = opts.sampleRate;
    this.N = opts.fftSize || 16384;
    this.hop = opts.hopSize || 4096;
    this.buf = new Float32Array(this.N);
    this.filled = 0;
    this.sinceHop = 0;
    this.fftObj = makeFFT(this.N);
    this.re = new Float64Array(this.N);
    this.im = new Float64Array(this.N);
    this.win = new Float64Array(this.N);
    for (let i = 0; i < this.N; i++) {
      this.win[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (this.N - 1));
    }
    this.notes = null;
    this.candKey = null;
    this.candCount = 0;
    this.adoptedKey = null;
    this.debug = null;
  }

  // notes brutes : [{key, fundamentalHz, partialTargetHz, listenPartial}]
  // (fréquences déjà mises à l'échelle du diapason par l'appelant)
  setNotes(raw) {
    this.notes = raw.map(n => {
      const L = n.listenPartial;
      let B = 0.0002;
      if (L > 1) {
        const r = n.partialTargetHz / (L * n.fundamentalHz);
        const b = (r * r - 1) / (L * L);
        if (b > 0 && b < 0.01) B = b;
      }
      return { key: n.key, f0: n.fundamentalHz, fPart: n.partialTargetHz, L, B };
    });
    this.candKey = null;
    this.candCount = 0;
    this.adoptedKey = null;
  }

  partialFreq(note, n) {
    return n * note.f0 * Math.sqrt(1 + note.B * n * n);
  }

  push(samples) {
    const n = samples.length;
    if (n >= this.N) {
      this.buf.set(samples.subarray(n - this.N));
      this.filled = this.N;
    } else {
      this.buf.copyWithin(0, n);
      this.buf.set(samples, this.N - n);
      this.filled = Math.min(this.N, this.filled + n);
    }
    this.sinceHop += n;
    if (this.notes && this.filled >= this.N && this.sinceHop >= this.hop) {
      this.sinceHop = 0;
      this.analyze();
    }
  }

  // Contribution d'un partiel attendu : magnitude du pic spectral le plus
  // proche, pondérée par la coïncidence en cents (1 à 0 ¢, 0 à ±75 ¢).
  peakMatch(mags, f) {
    const binHz = this.sr / this.N;
    const c = f / binHz;
    const lo = Math.max(1, Math.floor(c * 0.96));
    const hi = Math.min(mags.length - 2, Math.ceil(c * 1.04));
    let kb = -1, best = -1;
    for (let b = lo; b <= hi; b++) if (mags[b] > best) { best = mags[b]; kb = b; }
    if (kb < 0) return 0;
    const a = mags[kb - 1], b0 = mags[kb], g = mags[kb + 1];
    const den = a - 2 * b0 + g;
    let d = den !== 0 ? 0.5 * (a - g) / den : 0;
    if (!isFinite(d) || Math.abs(d) > 1) d = 0;
    const fPeak = (kb + d) * binHz;
    const centsDist = Math.abs(1200 * Math.log2(fPeak / f));
    return best * Math.max(0, 1 - centsDist / 75);
  }

  analyze() {
    const { N, sr } = this;
    const re = this.re, im = this.im;
    for (let i = 0; i < N; i++) { re[i] = this.buf[i] * this.win[i]; im[i] = 0; }
    this.fftObj.fft(re, im);
    const half = N / 2;
    const mags = new Float64Array(half);
    for (let b = 0; b < half; b++) mags[b] = Math.sqrt(re[b] * re[b] + im[b] * im[b]);

    // Plancher spectral : 70e percentile des magnitudes entre 40 et 6000 Hz.
    const binHz = sr / N;
    const b0 = Math.floor(40 / binHz), b1 = Math.min(half - 1, Math.ceil(6000 / binHz));
    const band = [];
    for (let b = b0; b <= b1; b += 2) band.push(mags[b]);
    band.sort((a, b) => a - b);
    const floorV = band[Math.floor(band.length * 0.7)] || 0;

    const W = [1.0, 0.85, 0.7, 0.55, 0.45, 0.35];
    const sumW = W.reduce((a, b) => a + b, 0);
    if (!this.scoresByKey) this.scoresByKey = new Float64Array(89);
    const scores = this.scoresByKey;
    scores.fill(0);
    let best = null, bestScore = 0;
    for (const note of this.notes) {
      let s = 0;
      for (let n = 1; n <= 6; n++) {
        const f = this.partialFreq(note, n);
        if (f > sr * 0.47) break;
        s += W[n - 1] * this.peakMatch(mags, f);
      }
      if (note.L > 6) s += 0.8 * this.peakMatch(mags, note.fPart);
      const score = s / sumW;
      scores[note.key] = score;
      if (score > bestScore) { bestScore = score; best = note; }
    }
    if (!best) return;
    // Désambiguïsation d'octave : la note une octave au-dessous explique le
    // même spectre par ses partiels pairs ; si son score est comparable
    // (≥ meilleur/1,35), c'est elle la fondamentale — cas typique des graves
    // au fondamental faible. À l'inverse, une fausse sous-octave est
    // fortement pénalisée par ses partiels impairs absents, donc la règle
    // ne dégrade pas les notes à fondamental fort.
    while (best.key > 12 && scores[best.key - 12] >= bestScore / 1.35) {
      best = this.notes.find(n => n.key === best.key - 12);
      bestScore = scores[best.key];
    }
    let secondScore = 0, secondKey = null;
    for (const note of this.notes) {
      if (note.key !== best.key && scores[note.key] > secondScore) {
        secondScore = scores[note.key]; secondKey = note.key;
      }
    }
    const rel = bestScore / (floorV + 1e-12);
    const okLevel = rel > 9;
    // Marge réduite quand le rival est l'octave (±12) : leur parenté
    // spectrale est physique, pas un doute entre deux notes étrangères.
    const seuilMarge = (secondKey !== null && Math.abs(secondKey - best.key) === 12) ? 1.10 : 1.22;
    const okMargin = bestScore > seuilMarge * secondScore;
    this.debug = { bestKey: best.key, rel, margin: secondScore > 0 ? bestScore / secondScore : 99 };
    if (okLevel && okMargin) {
      if (best.key === this.candKey) this.candCount++;
      else { this.candKey = best.key; this.candCount = 1; }
      if (this.candCount >= 3) this.adoptedKey = best.key;
    } else {
      this.candKey = null;
      this.candCount = 0;
    }
  }
}

const api = { makeFFT, PartialTracker, NoteDetector, wrapPi };
if (typeof module !== 'undefined' && module.exports) module.exports = api;
if (typeof self !== 'undefined') self.DSP = api;
