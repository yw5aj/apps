import { inspect, convert, transcode } from './core.js';

const $ = (id) => document.getElementById(id);
const VIDEO_NAMES = { hevc: 'H.265', avc: 'H.264', prores: 'ProRes', vp9: 'VP9', av1: 'AV1' };
// One item per chosen file: { file, info, video, audioId, slow, problem, status, blob, url, sec, note, error }
const state = { items: [], job: null, limit: 0, forceSlow: false, cancelAll: false };
const HAS_SLOW = !!window.FFmpegWASM;

// mbPerSec was measured on one real studio clip; other clips will differ with how much motion they have.
const QUALITY = {
  small: { maxSide: 1280, crf: 24, mbPerSec: 0.37, res: '720p' },
  std: { maxSide: 1920, crf: 23, mbPerSec: 0.8, res: '1080p' },
  high: { maxSide: 1920, crf: 18, mbPerSec: 1.8, res: '1080p' },
  ultra: { maxSide: 2560, crf: 18, mbPerSec: 3.0, res: '1440p' },
};

function store(key, value) {
  try {
    if (value === undefined) return localStorage.getItem(key);
    localStorage.setItem(key, String(value));
  } catch (e) { /* storage can be unavailable; the page works without it */ }
  return null;
}
function show(id, on) { $(id).hidden = !on; }
function el(tag, cls, text) { const n = document.createElement(tag); if (cls) n.className = cls; if (text != null) n.textContent = text; return n; }
function mb(n) { return (n / 1048576).toFixed(n > 10485760 ? 0 : 1) + ' MB'; }
function clock(sec) { sec = Math.max(0, Math.round(sec)); return Math.floor(sec / 60) + ':' + String(sec % 60).padStart(2, '0'); }
function minutes(sec) { return sec < 50 ? '不到 1 分钟' : '大约 ' + Math.max(1, Math.round(sec / 60)) + ' 分钟'; }
function span(sec) { sec = Math.round(sec); return sec < 60 ? sec + ' 秒' : Math.floor(sec / 60) + ' 分 ' + (sec % 60) + ' 秒'; }
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
function outName(it) { return it.file.name.replace(/\.[^.]+$/, '') + '_可播放.mp4'; }
function quality() {
  const q = document.querySelector('input[name="quality"]:checked');
  return q && QUALITY[q.value] ? q.value : 'high';
}
function usable() { return state.items.filter((it) => !it.problem); }
function setStatus(kind, text) {
  const v = $('verdict');
  v.className = 'verdict ' + kind;
  v.textContent = text;
}
function setProgress(p) {
  $('pct').firstChild.nodeValue = String(Math.round(p * 100));
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
function clearResult(it) {
  if (it.url) URL.revokeObjectURL(it.url);
  it.blob = null; it.url = null; it.status = 'new'; it.note = ''; it.error = '';
}
function clearPreview() { $('preview').removeAttribute('src'); $('preview').load(); }
function fail(msg) { $('error').textContent = msg; show('error', true); }

async function probe(it) {
  let info;
  try { info = await inspect(it.file); } catch (e) { it.problem = '读不出这个文件'; return; }
  it.info = info;
  const v = info.video[0];
  if (!v) { it.problem = '里面没有画面'; return; }
  it.video = v;
  if (!info.audio.length) { it.problem = '本身没有录声音'; return; }
  it.audioId = info.audio[0].id;
  const copyable = v.codec === 'hevc' || v.codec === 'avc';
  it.slow = !(copyable && v.canDecode);
  if (it.slow && !HAS_SLOW) it.problem = '页面没加载好，刷新后再试';
}

function renderFiles() {
  const list = $('files');
  list.textContent = '';
  for (const it of state.items) {
    const li = el('li');
    li.appendChild(el('span', 'name', it.file.name));
    let meta = mb(it.file.size);
    if (it.video) meta += (it.info.duration ? '，' + span(it.info.duration) : '') + '，' + resName(it.video);
    li.appendChild(el('span', it.problem ? 'meta bad' : 'meta', it.problem ? it.problem + '，转不了' : meta));
    list.appendChild(li);
  }
  $('fname').textContent = '已选 ' + state.items.length + ' 条';
}

function describe() {
  const ok = usable(), slow = ok.filter((it) => it.slow);
  const total = ok.reduce((s, it) => s + (it.info.duration || 0), 0);
  const slowDur = slow.reduce((s, it) => s + (it.info.duration || 0), 0);
  const speed = Number(store('dv-speed'));
  const skipped = state.items.length - ok.length;
  let text;
  if (ok.length === 1) {
    const it = ok[0];
    text = (it.info.duration ? span(it.info.duration) + '，' : '') + resName(it.video) + '。';
    text += it.slow
      ? '手机放不了原片，转成 MP4 要' + (speed > 0 && slowDur ? minutes(speed * slowDur) : '几分钟') + '。转的时候可以切去别的 App，别关掉这个页面。'
      : '手机能放它的画面，只需要转声音，几秒就好。';
  } else {
    text = ok.length + ' 条视频，一共 ' + span(total) + '。';
    text += slow.length
      ? '手机放不了原片，会一条接一条转成 MP4，' + (speed > 0 && slowDur ? '全部转完要' + minutes(speed * slowDur) : '每条要几分钟') + '。转的时候可以切去别的 App，别关掉这个页面。'
      : '手机能放它们的画面，只需要转声音，很快。';
  }
  if (skipped) text += '有 ' + skipped + ' 条转不了，会跳过。';
  return text;
}

function labelQuality() {
  const d = usable().filter((it) => it.slow).reduce((s, it) => s + (it.info.duration || 0), 0);
  for (const key of Object.keys(QUALITY)) {
    const q = QUALITY[key];
    const est = d ? Math.max(5, Math.round(q.mbPerSec * d / 5) * 5) : 0;
    $('est-' + key).textContent = est ? '约 ' + est + ' MB' : '';
  }
}

async function onFiles(fileList) {
  state.items.forEach(clearResult); clearPreview();
  $('error').textContent = ''; show('error', false);
  const files = Array.from(fileList || []);
  if (!files.length) { state.items = []; stage('pick'); return; }
  const items = files.map((file) => ({ file, status: 'new' }));
  state.items = items;
  stage('info'); show('ready', false);
  setStatus('wait', '正在读取视频…');
  renderFiles();
  for (const it of items) {
    await probe(it);
    if (state.items !== items) return; // a newer selection replaced this one
    renderFiles();
  }
  const ok = usable();
  if (!ok.length) {
    setStatus('bad', items.length === 1 ? '这条视频转不了：' + items[0].problem + '。请确认选的是舞社下载的视频。' : '这几条都转不了。请确认选的是舞社下载的视频。');
    return;
  }
  const first = ok[0];
  $('v-info').textContent = (VIDEO_NAMES[first.video.codec] || first.video.codec || '未知') + '，' + first.video.width + '×' + first.video.height +
    (first.video.codecString ? '（' + first.video.codecString + '）' : '');
  $('a-info').textContent = audioName(first.info.audio[0]);
  const sel = $('audio-sel');
  sel.textContent = '';
  first.info.audio.forEach((a) => {
    const o = el('option', '', '第 ' + a.number + ' 轨：' + audioName(a));
    o.value = String(a.id);
    sel.appendChild(o);
  });
  show('audio-pick', ok.length === 1 && first.info.audio.length > 1);
  show('quality-pick', ok.some((it) => it.slow));
  labelQuality();
  setStatus('ok', describe());
  $('go').textContent = ok.length > 1 ? '转换并保存 ' + ok.length + ' 条' : '转换并保存';
  $('try').textContent = ok.length > 1 ? '先试转第一条的前 5 秒' : '先试转前 5 秒';
  show('ready', true);
}

const logLines = [];
function log(m) {
  const box = $('log'); logLines.push(m); if (logLines.length > 500) logLines.shift();
  box.textContent = logLines.join('\n'); box.scrollTop = box.scrollHeight;
}

async function runOne(it, slow) {
  setProgress(0);
  $('run-label').textContent = slow ? '正在准备…' : '正在转声音，马上就好';
  $('run-stats').textContent = '';
  const audioIndex = Math.max(0, it.info.audio.findIndex((a) => a.id === it.audioId));
  const q = QUALITY[quality()];
  const job = {
    audioTrackId: it.audioId, audioIndex, duration: it.info.duration, limit: state.limit,
    maxSide: q.maxSide, crf: q.crf, progress: 0,
    onProgress: (p) => { job.progress = p; setProgress(p); },
    onStats: (x) => { job.stats = x; },
    onLog: log,
    onStage: (st) => {
      job.stage = st;
      $('run-label').textContent = st === 'load'
        ? '正在加载转换组件。第一次要下载约 30 MB。'
        : '正在转换。可以切去别的 App，别关掉这个页面。';
    },
  };
  state.job = job;
  const t0 = performance.now();
  const tick = setInterval(() => {
    const sec = (performance.now() - t0) / 1000;
    let text = '已用 ' + clock(sec);
    if (job.progress > 0.04 && job.progress < 1) text += '，这一条大约还要 ' + clock(sec * (1 - job.progress) / job.progress);
    else if (slow && job.stage === 'encode' && !job.stats) text += '，还没出第一帧';
    $('run-stats').textContent = text;
  }, 1000);
  try {
    let buf;
    if (slow) {
      buf = await transcode(it.file, job);
    } else {
      // A fresh Input per run: an Input cannot be reused after a conversion.
      const info = await inspect(it.file);
      buf = await convert(info.input, job);
    }
    it.sec = (performance.now() - t0) / 1000;
    if (slow && !state.limit && it.info.duration) store('dv-speed', (it.sec / it.info.duration).toFixed(2));
    it.blob = new Blob([buf], { type: 'video/mp4' });
    it.url = URL.createObjectURL(it.blob);
    it.usedSlow = slow; it.multiThread = !!job.multiThread;
    it.status = 'done';
  } catch (e) {
    if (job.canceled || (e && e.name === 'ConversionCanceledError')) { state.cancelAll = true; it.status = 'new'; return; }
    it.status = 'failed';
    if (e && e.discarded) {
      const what = e.discarded.map((d) => (d.type === 'video' ? '画面' : '声音')).join('和');
      it.error = '这个视频的' + what + '格式处理不了。把这行截图发给宇翔：' + e.discarded.map((d) => d.type + ':' + d.reason).join(', ');
    } else {
      it.error = '转换失败：' + (e && e.message ? e.message : e) + (e && e.log ? '（' + e.log + '）' : '') + '。如果是手机内存不够，关掉别的 App 再试一次。';
    }
  } finally {
    clearInterval(tick);
  }
}

async function runAll(forceSlow, limit) {
  const all = usable();
  const queue = limit ? all.slice(0, 1) : all;
  if (!queue.length) return;
  state.limit = limit || 0; state.forceSlow = forceSlow === true; state.cancelAll = false;
  logLines.length = 0; $('log').textContent = '';
  queue.forEach(clearResult); clearPreview();
  show('error', false);
  stage('run'); window.scrollTo(0, 0);
  const anySlow = state.forceSlow || queue.some((it) => it.slow);
  if (anySlow) keepAliveStart();
  let lock = null;
  try { if (anySlow && navigator.wakeLock) lock = await navigator.wakeLock.request('screen'); } catch (e) { lock = null; }
  try {
    for (let i = 0; i < queue.length; i++) {
      if (state.cancelAll) break;
      const it = queue[i];
      $('run-which').textContent = queue.length > 1 ? '第 ' + (i + 1) + ' 条，共 ' + queue.length + ' 条：' + it.file.name : it.file.name;
      if (queue.length > 1) log('[app] ===== 第 ' + (i + 1) + ' 条：' + it.file.name);
      await runOne(it, state.forceSlow || it.slow);
      if (it.status === 'done' && !state.limit) { try { await save(it, true); } catch (e) { /* the manual link stays */ } }
    }
  } finally {
    keepAliveStop();
    try { if (lock) await lock.release(); } catch (e) { /* already released */ }
  }
  const done = queue.filter((it) => it.status === 'done');
  const failed = queue.filter((it) => it.status === 'failed');
  if (!done.length) {
    stage('info');
    if (failed.length) fail(failed[0].error);
    return;
  }
  showDone(queue, done, failed);
}

function showDone(queue, done, failed) {
  const single = queue.length === 1;
  const first = done[0];
  show('preview', single);
  show('results', !single);
  if (single) {
    $('preview').src = first.url;
    $('save-note').textContent = first.note || '';
    $('out-info').textContent = outName(first) + '，' + mb(first.blob.size) + '，用时 ' + clock(first.sec) +
      (first.usedSlow ? (first.multiThread ? '，多线程' : '，单线程') : '');
  } else {
    const list = $('results');
    list.textContent = '';
    for (const it of queue) {
      const li = el('li');
      li.appendChild(el('span', 'name', it.status === 'done' ? outName(it) : it.file.name));
      if (it.status === 'done') {
        li.appendChild(el('span', 'meta', mb(it.blob.size) + '，用时 ' + clock(it.sec)));
        const again = el('button', 'link', '再保存一次');
        again.type = 'button';
        again.addEventListener('click', () => save(it, false).then(() => { again.textContent = '又存了一次'; }));
        li.appendChild(again);
      } else if (it.status === 'failed') {
        li.appendChild(el('span', 'meta bad', it.error));
      } else {
        li.appendChild(el('span', 'meta', '已取消，没有转'));
      }
      list.appendChild(li);
    }
    $('save-note').textContent = '转好的都已自动存进手机的“下载”，可以添加到 Google Photos 了。';
    $('out-info').textContent = '';
  }
  let title;
  if (state.limit) title = '前 ' + state.limit + ' 秒转好了，先看看';
  else if (single) title = '转好了';
  else if (done.length === queue.length) title = done.length + ' 条都转好了';
  else title = '转好 ' + done.length + ' 条，' + (queue.length - done.length) + ' 条没转成';
  $('done-title').textContent = title;
  show('redo', single && !first.usedSlow && HAS_SLOW);
  show('full', !!state.limit);
  show('save', single && !state.limit);
  $('full').textContent = usable().length > 1 ? '画面和声音都正常，转换全部 ' + usable().length + ' 条' : '画面和声音都正常，转换整条并保存';
  $('again').classList.toggle('quiet', !!state.limit);
  $('again').textContent = state.limit ? '换别的视频' : '再转别的视频';
  stage('done'); window.scrollTo(0, 0);
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
      navigator.mediaSession.metadata = new MediaMetadata({ title: '正在转换视频', artist: 'EDS视频转换' });
    }
  } catch (e) { /* conversion still works in the foreground */ }
}
function keepAliveStop() { try { if (keepAudio) keepAudio.pause(); } catch (e) { /* nothing to stop */ } }

async function save(it, auto) {
  const filename = outName(it);
  let downloads = null;
  if (window.claude && typeof window.claude.use === 'function') {
    try { downloads = await window.claude.use('downloads'); } catch (e) { downloads = null; }
  }
  if (downloads) {
    try {
      await downloads.save({ filename, data: it.blob });
      it.note = '已保存到手机的“下载”。';
    } catch (e) {
      const code = e && e.code;
      if (code === 'declined') it.note = '已取消。想保存就再点一次。';
      else if (code === 'too_large') it.note = '文件超过 Claude App 的 200 MB 上限。请用 Chrome 打开这个页面再转一次。';
      else if (code === 'rate_limited') it.note = '点得太快了，等几秒再点一次。';
      else it.note = '这里没法保存文件。请用 Chrome 打开这个页面再试。';
    }
    return;
  }
  if (window.claude) { it.note = '这里没法保存文件。请用 Chrome 打开这个页面再试。'; return; }
  const a = document.createElement('a');
  a.href = it.url; a.download = filename;
  document.body.appendChild(a); a.click(); a.remove();
  it.note = auto === true
    ? '已自动存进手机的“下载”，可以添加到 Google Photos 了。'
    : '又存了一次，在手机的“下载”里。';
}

function startOver() {
  state.items.forEach(clearResult); clearPreview();
  state.items = []; $('file').value = '';
  stage('pick'); window.scrollTo(0, 0);
}

const savedQuality = store('dv-quality');
if (savedQuality && QUALITY[savedQuality]) $('q-' + savedQuality).checked = true;
$('quality').addEventListener('change', () => store('dv-quality', quality()));
$('file').addEventListener('change', (e) => onFiles(e.target.files));
$('change').addEventListener('click', () => $('file').click());
$('audio-sel').addEventListener('change', (e) => { const it = usable()[0]; if (it) it.audioId = Number(e.target.value); });
$('go').addEventListener('click', () => runAll(false, 0));
$('try').addEventListener('click', () => runAll(false, 5));
$('redo').addEventListener('click', () => runAll(true, state.limit));
$('full').addEventListener('click', () => runAll(state.forceSlow, 0));
$('cancel').addEventListener('click', () => { state.cancelAll = true; if (state.job && state.job.cancel) state.job.cancel(); });
$('save').addEventListener('click', () => {
  const it = state.items.find((x) => x.status === 'done');
  if (it) save(it, false).then(() => { $('save-note').textContent = it.note; });
});
$('again').addEventListener('click', startOver);
$('log-copy').addEventListener('click', () => {
  try {
    navigator.clipboard.writeText(logLines.join('\n')).then(() => { $('log-copy').textContent = '已复制'; }, () => {});
  } catch (e) { /* clipboard unavailable */ }
});
