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
  const { duration, onProgress, onStage } = opts;
  if (!window.FFmpegWASM) throw new Error('no_ffmpeg');
  const ff = new window.FFmpegWASM.FFmpeg();
  opts.cancel = () => { opts.canceled = true; try { ff.terminate(); } catch (e) {} };
  const mt = !!self.crossOriginIsolated && typeof SharedArrayBuffer !== 'undefined';
  opts.multiThread = mt;
  const base = new URL(mt ? 'vendor/core-mt/' : 'vendor/core/', location.href).href;
  const cfg = { coreURL: base + 'ffmpeg-core.js', wasmURL: base + 'ffmpeg-core.wasm' };
  if (mt) cfg.workerURL = base + 'ffmpeg-core.worker.js';
  if (onStage) onStage('load');
  await ff.load(cfg);
  const tail = [];
  ff.on('log', ({ message }) => {
    tail.push(message); if (tail.length > 30) tail.shift();
    const m = /time=(\d+):(\d+):(\d+(?:\.\d+)?)/.exec(message);
    if (m && duration && onProgress) {
      const t = (+m[1]) * 3600 + (+m[2]) * 60 + (+m[3]);
      onProgress(Math.max(0, Math.min(1, t / duration)));
    }
  });
  await ff.createDir('/in');
  await ff.mount('WORKERFS', { files: [new File([file], 'input.mov')] }, '/in');
  if (onStage) onStage('encode');
  const scale = "scale=w='if(gte(iw,ih),min(1920,iw),-2)':h='if(gte(iw,ih),-2,min(1920,ih))'";
  const code = await ff.exec([
    '-i', '/in/input.mov', '-map', '0:v:0', '-map', '0:a:' + (opts.audioIndex || 0) + '?',
    '-vf', scale, '-c:v', 'libx264', '-preset', 'ultrafast', '-crf', '23', '-pix_fmt', 'yuv420p',
    '-c:a', 'aac', '-b:a', '192k', '-ac', '2', '-map_metadata', '0', '-movflags', '+faststart',
    'out.mp4',
  ]);
  if (code !== 0) { try { ff.terminate(); } catch (e) {} const err = new Error('ffmpeg_failed'); err.log = tail.slice(-4).join(' | '); throw err; }
  const data = await ff.readFile('out.mp4');
  try { ff.terminate(); } catch (e) {}
  return data.buffer;
}
