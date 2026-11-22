import {
  Input, ALL_FORMATS, BlobSource, Output, BufferTarget, Mp4OutputFormat,
  Conversion, canEncodeAudio,
} from 'mediabunny';
import { registerAacEncoder } from '@mediabunny/aac-encoder';

let aacReady = null;
function ensureAac() {
  if (!aacReady) {
    aacReady = (async () => {
      let native = false;
      try { native = await canEncodeAudio('aac'); } catch (e) { native = false; }
      if (!native) registerAacEncoder();
      return native;
    })();
  }
  return aacReady;
}

export async function inspect(file) {
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS });
  const format = await input.getFormat();
  const videoTracks = await input.getVideoTracks();
  const audioTracks = await input.getAudioTracks();
  let duration = null;
  try { duration = await input.computeDuration(); } catch (e) { duration = null; }
  const video = [];
  for (const t of videoTracks) {
    video.push({
      id: t.id, number: t.number, codec: await t.getCodec(),
      internal: await t.getInternalCodecId().catch(() => null),
      width: await t.getCodedWidth().catch(() => 0),
      height: await t.getCodedHeight().catch(() => 0),
      codecString: await t.getCodecParameterString().catch(() => null),
      canDecode: await t.canDecode().catch(() => false),
    });
  }
  const audio = [];
  for (const t of audioTracks) {
    audio.push({
      id: t.id, number: t.number, codec: await t.getCodec(),
      internal: await t.getInternalCodecId().catch(() => null),
      channels: t.numberOfChannels, sampleRate: t.sampleRate,
    });
  }
  return { input, formatName: format && format.name, duration, video, audio };
}

export async function convert(input, opts) {
  const { audioTrackId, onProgress } = opts;
  await ensureAac();
  const target = new BufferTarget();
  const output = new Output({ format: new Mp4OutputFormat({ fastStart: false }), target });
  const conversion = await Conversion.init({
    input, output, showWarnings: false,
    video: (track) => (track.number === 1 ? {} : { discard: true }),
    audio: (track) => {
      if (track.id !== audioTrackId) return { discard: true };
      const o = { codec: 'aac' };
      if (track.numberOfChannels > 2) o.numberOfChannels = 2;
      if (track.sampleRate > 48000) o.sampleRate = 48000;
      return o;
    },
    tags: (t) => t,
    ...(opts.limit ? { trim: { end: opts.limit } } : {}),
  });
  const discarded = conversion.discardedTracks
    .filter((d) => d.reason !== 'discarded_by_user')
    .map((d) => ({ type: d.track.type, reason: d.reason }));
  if (!conversion.isValid || discarded.length) {
    const err = new Error('tracks_discarded');
    err.discarded = discarded;
    throw err;
  }
  if (onProgress) conversion.onProgress = (p) => onProgress(p);
  opts.cancel = () => conversion.cancel();
  await conversion.execute();
  return target.buffer;
}

// Slow path: re-encode the picture to H.264 with ffmpeg.wasm (loaded as window.FFmpegWASM).
export async function transcode(file, opts) {
  const { onProgress, onStage } = opts;
  const duration = opts.limit && opts.duration ? Math.min(opts.limit, opts.duration) : opts.duration;
  if (!window.FFmpegWASM) throw new Error('no_ffmpeg');
  const ff = new window.FFmpegWASM.FFmpeg();
  opts.cancel = () => { opts.canceled = true; try { ff.terminate(); } catch (e) {} };
  const mt = !!self.crossOriginIsolated && typeof SharedArrayBuffer !== 'undefined';
  opts.multiThread = mt;
  const base = new URL(mt ? 'vendor/core-mt/' : 'vendor/core/', location.href).href;
  const cfg = { coreURL: base + 'ffmpeg-core.js', wasmURL: base + 'ffmpeg-core.wasm' };
  if (mt) cfg.workerURL = base + 'ffmpeg-core.worker.js';
  if (onStage) onStage('load');
  const log = (m) => { if (opts.onLog) opts.onLog(m); };
  log('[app] ' + (mt ? '多线程' : '单线程') + '，CPU 核数 ' + (navigator.hardwareConcurrency || '?') + '，内存 ' + (navigator.deviceMemory || '?') + ' GB');
  log('[app] 加载 ffmpeg 组件…');
  await ff.load(cfg);
  log('[app] 组件加载完成');
  const tail = [];
  ff.on('log', ({ message }) => {
    tail.push(message); if (tail.length > 30) tail.shift();
    if (opts.onLog) opts.onLog(message);
    const f = /frame=\s*(\d+)\s+fps=\s*([\d.]+)/.exec(message);
    if (f && opts.onStats) opts.onStats({ frame: +f[1], fps: +f[2] });
    const m = /time=(\d+):(\d+):(\d+(?:\.\d+)?)/.exec(message);
    if (m && duration && onProgress) {
      const t = (+m[1]) * 3600 + (+m[2]) * 60 + (+m[3]);
      onProgress(Math.max(0, Math.min(1, t / duration)));
    }
  });
  await ff.createDir('/in');
  await ff.mount('WORKERFS', { files: [new File([file], 'input.mov')] }, '/in');
  log('[app] 输入文件已挂载，开始转码');
  if (onStage) onStage('encode');
  const side = opts.maxSide || 1920, crf = String(opts.crf || 23);
  const scale = "scale=w='if(gte(iw,ih),min(" + side + ",iw),-2)':h='if(gte(iw,ih),-2,min(" + side + ",ih))'";
  log('[app] 画质：长边 ' + side + '，CRF ' + crf);
  const q = new URLSearchParams(location.search).get('threads');
  const threads = String(q ? Math.max(1, +q || 1) : Math.max(1, Math.min(4, navigator.hardwareConcurrency || 2)));
  opts.threads = threads;
  log('[app] 线程数 ' + threads);
  const code = await ff.exec([
    ...(mt ? ['-threads', threads] : []), '-i', '/in/input.mov', '-map', '0:v:0', '-map', '0:a:' + (opts.audioIndex || 0) + '?',
    '-vf', scale, '-c:v', 'libx264', ...(mt ? ['-threads', threads] : []), '-preset', 'ultrafast', '-crf', crf, '-pix_fmt', 'yuv420p',
    '-c:a', 'aac', '-b:a', '192k', '-ac', '2', '-map_metadata', '0', '-write_tmcd', '0', '-movflags', '+faststart',
    ...(opts.limit ? ['-t', String(opts.limit)] : []),
    'out.mp4',
  ]);
  log('[app] ffmpeg 结束，返回码 ' + code);
  if (code !== 0) { try { ff.terminate(); } catch (e) {} const err = new Error('ffmpeg_failed'); err.log = tail.slice(-4).join(' | '); throw err; }
  const data = await ff.readFile('out.mp4');
  try { ff.terminate(); } catch (e) {}
  return data.buffer;
}
