import { inspect, convert, transcode } from './core.js';

const $ = (id) => document.getElementById(id);
const VIDEO_NAMES = { hevc: 'H.265', avc: 'H.264', prores: 'ProRes', vp9: 'VP9', av1: 'AV1' };
const state = { file: null, info: null, audioId: null, blob: null, url: null, job: null, slow: false };
const HAS_SLOW = !!window.FFmpegWASM;

// mbPerSec was measured on one real studio clip; other clips will differ with how much motion they have.
const QUALITY = {
  small: { maxSide: 1280, crf: 24, mbPerSec: 0.37, res: '720p' },
  std: { maxSide: 1920, crf: 23, mbPerSec: 0.8, res: '1080p' },
  high: { maxSide: 1920, crf: 18, mbPerSec: 1.8, res: '1080p' },
};

function store(key, value) {
  try {
    if (value === undefined) return localStorage.getItem(key);
    localStorage.setItem(key, String(value));
  } catch (e) { /* storage can be unavailable; the page works without it */ }
  return null;
}
function show(id, on) { $(id).hidden = !on; }
function mb(n) { return (n / 1048576).toFixed(n > 10485760 ? 0 : 1) + ' MB'; }
function clock(sec) { sec = Math.max(0, Math.round(sec)); return Math.floor(sec / 60) + ':' + String(sec % 60).padStart(2, '0'); }
function minutes(sec) { return sec < 50 ? '不到 1 分钟' : '大约 ' + Math.max(1, Math.round(sec / 60)) + ' 分钟'; }
function resName(v) {
  const side = Math.max(v.width, v.height);
  if (side >= 3800) return '4K';
  if (side >= 1900) return '1080p';
  if (side >= 1260) return '720p';
  return v.width + '×' + v.height;
}
function audioName(a) {
  const c = a.codec || '未知';
  const m = /^pcm-([a-z])(\d+)/.exec(c);
  const name = m ? 'PCM ' + m[2] + ' 位（未压缩）' : c.toUpperCase();
  return name + '，' + a.channels + ' 声道，' + Math.round(a.sampleRate / 1000) + ' kHz';
}
function quality() {
  const el = document.querySelector('input[name="quality"]:checked');
  return el && QUALITY[el.value] ? el.value : 'high';
}
function setStatus(kind, text) {
  const el = $('verdict');
  el.className = 'verdict ' + kind;
  el.textContent = text;
}
function setProgress(p) {
  const n = Math.round(p * 100);
  $('pct').firstChild.nodeValue = String(n);
  const beats = $('beats').children;
  const now = Math.min(7, Math.floor(p * 8));
  for (let i = 0; i < beats.length; i++) {
    const f = Math.max(0, Math.min(1, p * 8 - i));
    beats[i].firstElementChild.style.setProperty('--f', f.toFixed(3));
    beats[i].classList.toggle('on', f > 0);
    beats[i].classList.toggle('now', i === now && p < 1);
  }
}
function stage(name) {
  show('step-pick', name === 'pick');
  show('step-info', name === 'info');
  show('step-run', name === 'run');
  show('step-done', name === 'done');
}
function clearResult() {
  if (state.url) URL.revokeObjectURL(state.url);
  state.blob = null; state.url = null;
  $('preview').removeAttribute('src'); $('preview').load();
}
function fail(msg) { $('error').textContent = msg; show('error', true); }

function describe() {
  const info = state.info, v = info.video[0];
  const dur = info.duration ? Math.round(info.duration) + ' 秒，' : '';
  if (!state.slow) {
    return dur + resName(v) + '。手机能放它的画面，只需要转声音，几秒就好。';
  }
  const speed = Number(store('dv-speed'));
  const eta = speed > 0 && info.duration ? minutes(speed * info.duration) : '几分钟';
  return dur + resName(v) + '。手机放不了原片，转成 MP4 要' + eta + '。转的时候可以切去别的 App，别关掉这个页面。';
}

function labelQuality() {
  const d = state.info && state.info.duration;
  for (const key of Object.keys(QUALITY)) {
    const q = QUALITY[key];
    const est = d ? Math.max(5, Math.round(q.mbPerSec * d / 5) * 5) : 0;
    $('est-' + key).textContent = est ? '约 ' + est + ' MB' : q.res;
  }
}

async function onFile(file) {
  clearResult();
  $('error').textContent = ''; show('error', false);
  if (!file) { stage('pick'); return; }
  state.file = file;
  $('fname').textContent = file.name + '，' + mb(file.size);
  stage('info'); show('ready', false);
  setStatus('wait', '正在读取视频…');
  let info;
  try { info = await inspect(file); }
  catch (e) {
    setStatus('bad', '读不出这个文件。请确认选的是舞社下载的视频。');
    return;
  }
  state.info = info;
  const v = info.video[0];
  if (!v) { setStatus('bad', '这个文件里没有画面，转不了。'); return; }
  $('v-info').textContent = (VIDEO_NAMES[v.codec] || v.codec || '未知') + '，' + v.width + '×' + v.height +
    (v.codecString ? '（' + v.codecString + '）' : '');
  if (!info.audio.length) {
    $('a-info').textContent = '没有声音轨';
    setStatus('bad', '这个视频本身没有录声音，转换也救不回来。');
    return;
  }
  state.audioId = info.audio[0].id;
  $('a-info').textContent = audioName(info.audio[0]);
  const sel = $('audio-sel');
  sel.innerHTML = '';
  info.audio.forEach((a) => {
    const o = document.createElement('option');
    o.value = String(a.id); o.textContent = '第 ' + a.number + ' 轨：' + audioName(a);
    sel.appendChild(o);
  });
  show('audio-pick', info.audio.length > 1);
  const copyable = v.codec === 'hevc' || v.codec === 'avc';
  state.slow = !(copyable && v.canDecode);
  if (state.slow && !HAS_SLOW) {
    setStatus('bad', '手机放不了这种画面，这个页面现在也没法重新编码。请刷新页面再试一次。');
    return;
  }
  show('quality-pick', state.slow);
  labelQuality();
  setStatus('ok', describe());
  show('ready', true);
}

const logLines = [];
async function run(forceSlow, limit) {
  logLines.length = 0; $('log').textContent = '';
  const slow = forceSlow === true || state.slow;
  state.limit = limit || 0; state.lastSlow = slow;
  clearResult();
  show('error', false);
  stage('run'); setProgress(0);
  $('run-label').textContent = slow ? '正在准备…' : '正在转声音，马上就好';
  $('run-stats').textContent = '';
  const audioIndex = Math.max(0, state.info.audio.findIndex((a) => a.id === state.audioId));
  const q = QUALITY[quality()];
  const job = {
    audioTrackId: state.audioId, audioIndex, duration: state.info.duration, limit: state.limit,
    maxSide: q.maxSide, crf: q.crf, progress: 0,
    onProgress: (p) => { job.progress = p; setProgress(p); },
    onStats: (x) => { job.stats = x; },
    onLog: (m) => {
      const el = $('log'); logLines.push(m); if (logLines.length > 500) logLines.shift();
      el.textContent = logLines.join('\n'); el.scrollTop = el.scrollHeight;
    },
    onStage: (st) => {
      job.stage = st;
      $('run-label').textContent = st === 'load'
        ? '正在加载转换组件。第一次要下载约 30 MB。'
        : '正在转换。可以切去别的 App，别关掉这个页面。';
    },
  };
  state.job = job;
  const t0 = performance.now();
  if (slow) keepAliveStart();
  const tick = setInterval(() => {
    const sec = (performance.now() - t0) / 1000;
    let text = '已用 ' + clock(sec);
    if (job.progress > 0.04 && job.progress < 1) text += '，大约还要 ' + clock(sec * (1 - job.progress) / job.progress);
    else if (slow && job.stage === 'encode' && !job.stats) text += '，还没出第一帧';
    $('run-stats').textContent = text;
  }, 1000);
  let lock = null;
  try { if (slow && navigator.wakeLock) lock = await navigator.wakeLock.request('screen'); } catch (e) { lock = null; }
  try {
    let buf;
    if (slow) {
      buf = await transcode(state.file, job);
    } else {
      // A fresh Input per run: an Input cannot be reused after a conversion.
      const info = await inspect(state.file);
      buf = await convert(info.input, job);
    }
    const sec = (performance.now() - t0) / 1000;
    if (slow && !state.limit && state.info.duration) store('dv-speed', (sec / state.info.duration).toFixed(2));
    state.blob = new Blob([buf], { type: 'video/mp4' });
    state.url = URL.createObjectURL(state.blob);
    $('preview').src = state.url;
    $('out-info').textContent = outName() + '，' + mb(state.blob.size) + '，用时 ' + clock(sec) +
      (slow ? (job.multiThread ? '，多线程' : '，单线程') : '');
    stage('done');
    show('redo', !slow && HAS_SLOW);
    show('full', !!state.limit); show('save', !state.limit);
    $('again').classList.toggle('quiet', !!state.limit);
    $('again').textContent = state.limit ? '换一个视频' : '再转一个';
    $('done-title').textContent = state.limit ? '前 ' + state.limit + ' 秒转好了，先看看' : '转好了';
    $('save-note').textContent = '';
    if (!state.limit) { try { await save(true); } catch (e) { /* the manual button stays */ } }
  } catch (e) {
    stage('info');
    if (job.canceled || (e && e.name === 'ConversionCanceledError')) return;
    if (e && e.discarded) {
      const what = e.discarded.map((d) => (d.type === 'video' ? '画面' : '声音')).join('和');
      fail('这个视频的' + what + '格式处理不了。把这行截图发给宇翔：' +
        e.discarded.map((d) => d.type + ':' + d.reason).join(', '));
    } else {
      fail('转换失败：' + (e && e.message ? e.message : e) + (e && e.log ? '（' + e.log + '）' : '') +
        '。如果是手机内存不够，关掉别的 App 再试一次。');
    }
  } finally {
    clearInterval(tick);
    keepAliveStop();
    try { if (lock) await lock.release(); } catch (e) { /* already released */ }
  }
}

// Keep the tab alive in the background: Android keeps a page running while it plays audio.
// A 20 Hz tone at very low volume is inaudible on phone speakers.
let keepAudio = null;
function keepAliveStart() {
  try {
    if (!keepAudio) {
      const rate = 8000, n = rate * 10, buf = new ArrayBuffer(44 + n * 2), v = new DataView(buf);
      const w = (o, t) => { for (let i = 0; i < t.length; i++) v.setUint8(o + i, t.charCodeAt(i)); };
      w(0, 'RIFF'); v.setUint32(4, 36 + n * 2, true); w(8, 'WAVEfmt '); v.setUint32(16, 16, true);
      v.setUint16(20, 1, true); v.setUint16(22, 1, true); v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true);
      v.setUint16(32, 2, true); v.setUint16(34, 16, true); w(36, 'data'); v.setUint32(40, n * 2, true);
      for (let i = 0; i < n; i++) v.setInt16(44 + i * 2, Math.round(Math.sin(2 * Math.PI * 20 * i / rate) * 600), true);
      keepAudio = new Audio(URL.createObjectURL(new Blob([buf], { type: 'audio/wav' })));
      keepAudio.loop = true;
    }
    const p = keepAudio.play(); if (p && p.catch) p.catch(() => {});
    if (navigator.mediaSession && window.MediaMetadata) {
      navigator.mediaSession.metadata = new MediaMetadata({ title: '正在转换视频', artist: '舞社视频转换' });
    }
  } catch (e) { /* conversion still works in the foreground */ }
}
function keepAliveStop() { try { if (keepAudio) keepAudio.pause(); } catch (e) { /* nothing to stop */ } }

function outName() {
  const base = (state.file ? state.file.name : 'video').replace(/\.[^.]+$/, '');
  return base + '_可播放.mp4';
}

async function save(auto) {
  const note = $('save-note');
  const filename = outName();
  let downloads = null;
  if (window.claude && typeof window.claude.use === 'function') {
    note.textContent = '正在准备保存…';
    try { downloads = await window.claude.use('downloads'); } catch (e) { downloads = null; }
  }
  if (downloads) {
    try {
      await downloads.save({ filename, data: state.blob });
      note.textContent = '已保存到手机的“下载”。';
    } catch (e) {
      const code = e && e.code;
      if (code === 'declined') note.textContent = '已取消。想保存就再点一次。';
      else if (code === 'too_large') note.textContent = '文件超过 Claude App 的 200 MB 上限。请用 Chrome 打开这个页面再转一次。';
      else if (code === 'rate_limited') note.textContent = '点得太快了，等几秒再点一次。';
      else note.textContent = '这里没法保存文件。请用 Chrome 打开这个页面再试。';
    }
    return;
  }
  if (window.claude) { note.textContent = '这里没法保存文件。请用 Chrome 打开这个页面再试。'; return; }
  const a = document.createElement('a');
  a.href = state.url; a.download = filename;
  document.body.appendChild(a); a.click(); a.remove();
  note.textContent = auto === true
    ? '已自动存进手机的“下载”，可以添加到 Google Photos 了。'
    : '又存了一次，在手机的“下载”里。';
}

function startOver() { $('file').value = ''; clearResult(); stage('pick'); window.scrollTo(0, 0); }

const savedQuality = store('dv-quality');
if (savedQuality && QUALITY[savedQuality]) $('q-' + savedQuality).checked = true;
$('quality').addEventListener('change', () => store('dv-quality', quality()));
$('file').addEventListener('change', (e) => onFile(e.target.files && e.target.files[0]));
$('change').addEventListener('click', () => $('file').click());
$('audio-sel').addEventListener('change', (e) => { state.audioId = Number(e.target.value); });
$('go').addEventListener('click', () => run());
$('redo').addEventListener('click', () => run(true, state.limit));
$('try').addEventListener('click', () => run(false, 5));
$('full').addEventListener('click', () => run(state.lastSlow, 0));
$('cancel').addEventListener('click', () => { if (state.job && state.job.cancel) state.job.cancel(); });
$('save').addEventListener('click', () => save(false));
$('again').addEventListener('click', startOver);
$('log-copy').addEventListener('click', () => {
  try {
    navigator.clipboard.writeText(logLines.join('\n')).then(() => { $('log-copy').textContent = '已复制'; }, () => {});
  } catch (e) { /* clipboard unavailable */ }
});
