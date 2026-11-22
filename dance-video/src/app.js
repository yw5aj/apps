import { inspect, convert, transcode } from './core.js';

const $ = (id) => document.getElementById(id);
const VIDEO_NAMES = { hevc: 'H.265', avc: 'H.264', prores: 'ProRes', vp9: 'VP9', av1: 'AV1' };
const state = { file: null, info: null, audioId: null, blob: null, url: null, job: null, slow: false };
const HAS_SLOW = !!window.FFmpegWASM;

function show(id, on) { $(id).hidden = !on; }
function mb(n) { return (n / 1048576).toFixed(n > 10485760 ? 0 : 1) + ' MB'; }
function audioName(a) {
  const c = a.codec || '未知';
  const m = /^pcm-([a-z])(\d+)/.exec(c);
  const name = m ? 'PCM ' + m[2] + ' 位（未压缩）' : c.toUpperCase();
  return name + ' · ' + a.channels + ' 声道 · ' + Math.round(a.sampleRate / 1000) + ' kHz';
}
function setStatus(kind, text) {
  const el = $('verdict');
  el.className = 'verdict ' + kind;
  el.textContent = text;
}
function reset() {
  if (state.url) URL.revokeObjectURL(state.url);
  state.blob = null; state.url = null;
  $('preview').removeAttribute('src'); $('preview').load();
  show('step-info', false); show('step-run', false); show('step-done', false);
  $('error').textContent = ''; show('error', false);
}
function fail(msg) { $('error').textContent = msg; show('error', true); }

async function onFile(file) {
  reset();
  if (!file) return;
  state.file = file;
  $('fname').textContent = file.name + ' · ' + mb(file.size);
  show('step-info', true);
  setStatus('wait', '正在读取视频信息…');
  $('v-info').textContent = '…'; $('a-info').textContent = '…';
  show('audio-pick', false); show('go', false); show('try', false);
  let info;
  try { info = await inspect(file); }
  catch (e) {
    setStatus('bad', '读不出这个文件。请确认选的是舞社下载的视频。');
    return;
  }
  state.info = info;
  const v = info.video[0];
  if (!v) { setStatus('bad', '这个文件里没有画面。'); return; }
  $('v-info').textContent = (VIDEO_NAMES[v.codec] || v.codec || '未知') + ' · ' + v.width + '×' + v.height +
    (info.duration ? ' · ' + Math.round(info.duration) + ' 秒' : '');
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
    o.value = String(a.id); o.textContent = '第 ' + a.number + ' 轨 · ' + audioName(a);
    sel.appendChild(o);
  });
  show('audio-pick', info.audio.length > 1);
  const a = info.audio[0];
  const copyable = v.codec === 'hevc' || v.codec === 'avc';
  state.slow = !(copyable && v.canDecode);
  $('v-info').textContent += v.codecString ? '（' + v.codecString + '）' : '';
  if (state.slow && !HAS_SLOW) {
    setStatus('bad', '这台手机放不了这种画面，这个页面也没法重新编码画面。');
    return;
  }
  if (state.slow) {
    setStatus('warn', '这台手机放不了这种画面。需要把画面和声音都重新转一遍，大约要几分钟。转换时可以切去别的 App，别关掉这个页面。');
  } else if (a.codec === 'aac' && a.channels <= 2) {
    setStatus('warn', '声音已经是 AAC，按理能播。可以转一次试试，没变化的话问题在别处。');
  } else {
    setStatus('ok', '声音是 Google Photos 不认的格式。画面原样保留，只把声音转成 AAC，很快。');
  }
  show('go', true); show('try', true);
}

async function run(forceSlow, limit) {
  const slow = forceSlow === true || state.slow;
  state.limit = limit || 0; state.lastSlow = slow;
  if (state.url) { URL.revokeObjectURL(state.url); state.url = null; }
  $('preview').removeAttribute('src'); $('preview').load();
  show('go', false); show('try', false); show('step-run', true); show('step-done', false); show('error', false);
  $('bar').style.width = '0%'; $('pct').textContent = '0%';
  $('run-label').textContent = slow ? '正在准备…' : '正在转换，请别离开这个页面';
  const audioIndex = Math.max(0, state.info.audio.findIndex((a) => a.id === state.audioId));
  const job = {
    audioTrackId: state.audioId, audioIndex, duration: state.info.duration, limit: state.limit,
    onProgress: (p) => {
      const n = Math.round(p * 100);
      $('bar').style.width = n + '%'; $('pct').textContent = n + '%';
    },
    onStage: (st) => {
      $('run-label').textContent = st === 'load' ? '正在加载转换组件（第一次要下载约 30 MB）…' : '正在重新编码画面。可以切去别的 App，别关掉这个页面';
    },
  };
  state.job = job;
  if (slow) keepAliveStart();
  let lock = null;
  try { if (slow && navigator.wakeLock) lock = await navigator.wakeLock.request('screen'); } catch (e) { lock = null; }
  const t0 = performance.now();
  try {
    let buf;
    if (slow) {
      buf = await transcode(state.file, job);
    } else {
      // A fresh Input per run: an Input cannot be reused after a conversion.
      const info = await inspect(state.file);
      buf = await convert(info.input, job);
    }
    state.blob = new Blob([buf], { type: 'video/mp4' });
    state.url = URL.createObjectURL(state.blob);
    $('preview').src = state.url;
    $('out-info').textContent = outName() + ' · ' + mb(state.blob.size) + ' · 用时 ' +
      Math.max(1, Math.round((performance.now() - t0) / 1000)) + ' 秒' +
      (slow ? (job.multiThread ? ' · 多线程' : ' · 单线程') : '');
    show('step-run', false); show('step-done', true);
    show('redo', !slow && HAS_SLOW);
    show('full', !!state.limit); show('save', !state.limit);
    $('done-title').textContent = state.limit ? '试看前 ' + state.limit + ' 秒' : '试听并保存';
    $('save-note').textContent = '';
  } catch (e) {
    show('step-run', false); show('go', true); show('try', true);
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
    keepAliveStop();
    try { if (lock) await lock.release(); } catch (e) {}
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
  } catch (e) {}
}
function keepAliveStop() { try { if (keepAudio) keepAudio.pause(); } catch (e) {} }

function outName() {
  const base = (state.file ? state.file.name : 'video').replace(/\.[^.]+$/, '');
  return base + '_可播放.mp4';
}

async function save() {
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
      note.textContent = '已保存。到手机的“下载”里找，再添加到 Google Photos。';
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
  note.textContent = '已开始下载。到手机的“下载”里找，再添加到 Google Photos。';
}

$('file').addEventListener('change', (e) => onFile(e.target.files && e.target.files[0]));
$('audio-sel').addEventListener('change', (e) => { state.audioId = Number(e.target.value); });
$('go').addEventListener('click', () => run());
$('redo').addEventListener('click', () => run(true, state.limit));
$('try').addEventListener('click', () => run(false, 5));
$('full').addEventListener('click', () => run(state.lastSlow, 0));
$('cancel').addEventListener('click', () => { if (state.job && state.job.cancel) state.job.cancel(); });
$('save').addEventListener('click', save);
$('again').addEventListener('click', () => { $('file').value = ''; reset(); $('fname').textContent = '还没选视频'; window.scrollTo(0, 0); });
