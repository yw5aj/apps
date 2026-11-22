#!/usr/bin/env bash
# Cross-compile a small ffmpeg (with x264 and the phone's MediaCodec H.264 encoder)
# for arm64 phones and the x86_64 emulator. Output: build-ffmpeg/out/<abi>/ffmpeg
set -euo pipefail
cd "$(dirname "$0")"

NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}}"
API=35
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
FFV=7.1.1
WORK="$PWD/build-ffmpeg"
JOBS=$(nproc)

mkdir -p "$WORK/src"
cd "$WORK/src"
[ -d x264 ] || git clone --depth 1 --branch stable https://code.videolan.org/videolan/x264.git
[ -d "ffmpeg-$FFV" ] || curl -fsSL "https://ffmpeg.org/releases/ffmpeg-$FFV.tar.xz" | tar xJ

for ABI in arm64-v8a x86_64; do
  case $ABI in
    arm64-v8a) T=aarch64-linux-android; ARCH=aarch64; X264ASM=""; FFASM="" ;;
    x86_64)    T=x86_64-linux-android;  ARCH=x86_64;  X264ASM="--disable-asm"; FFASM="--disable-x86asm" ;;
  esac
  CC="$TC/bin/${T}${API}-clang"
  PREFIX="$WORK/prefix/$ABI"

  echo "::group::x264 $ABI"
  rm -rf "$WORK/x264-$ABI"
  cp -r "$WORK/src/x264" "$WORK/x264-$ABI"
  cd "$WORK/x264-$ABI"
  CC="$CC" ./configure --host="$T" --cross-prefix="$TC/bin/llvm-" --prefix="$PREFIX" \
    --enable-static --enable-pic --disable-cli --disable-opencl $X264ASM
  make -j"$JOBS"
  make install
  echo "::endgroup::"

  echo "::group::ffmpeg $ABI"
  rm -rf "$WORK/ff-$ABI"
  cp -r "$WORK/src/ffmpeg-$FFV" "$WORK/ff-$ABI"
  cd "$WORK/ff-$ABI"
  PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" ./configure \
    --target-os=android --arch="$ARCH" --enable-cross-compile \
    --cc="$CC" --cxx="$CC++" \
    --nm="$TC/bin/llvm-nm" --ar="$TC/bin/llvm-ar" --ranlib="$TC/bin/llvm-ranlib" --strip="$TC/bin/llvm-strip" \
    --pkg-config=pkg-config --pkg-config-flags=--static \
    --enable-pic $FFASM \
    --disable-shared --enable-static \
    --disable-doc --disable-ffplay --disable-ffprobe --disable-debug \
    --disable-vulkan --disable-v4l2-m2m \
    --enable-gpl --enable-libx264 --enable-jni --enable-mediacodec \
    --disable-everything \
    --enable-protocol=file,pipe \
    --enable-demuxer=mov,matroska,mxf,mpegts,avi,wav \
    --enable-muxer=mp4,mov,null \
    --enable-decoder=hevc,h264,prores,dnxhd,mpeg4,mjpeg,aac,alac,mp3,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f32be \
    --enable-encoder=libx264,h264_mediacodec,aac \
    --enable-parser=hevc,h264,aac,mpeg4video \
    --enable-bsf=extract_extradata,h264_mp4toannexb,hevc_mp4toannexb,h264_metadata,aac_adtstoasc,null \
    --enable-filter=scale,format,aresample,aformat,anull,null,pan,trim,atrim,setpts,asetpts \
    --extra-cflags="-I$PREFIX/include -O3" \
    --extra-ldflags="-L$PREFIX/lib -Wl,-z,max-page-size=16384" \
    || { tail -50 ffbuild/config.log; exit 1; }
  make -j"$JOBS"
  mkdir -p "$WORK/out/$ABI"
  "$TC/bin/llvm-strip" -o "$WORK/out/$ABI/ffmpeg" ffmpeg
  ls -la "$WORK/out/$ABI/ffmpeg"
  echo "::endgroup::"
  cd "$WORK/src"
done
