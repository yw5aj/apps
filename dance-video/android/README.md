# 舞社视频转换 · Android 版

和网页版做同一件事，但转换放在前台服务里跑：切去别的 App 或锁屏都不会停，转好了发通知。

- 自带一个为手机编译的 ffmpeg（`build-ffmpeg.sh`），能解 H.265 10-bit 4:2:2。画面先试手机的硬件 H.264 编码，失败就自动换 x264。
- 手机本来就能放的画面（H.264、4:2:0 的 H.265）直接复制，只转声音。
- 输出存在 `Movies/舞社视频`。

## 下载

https://github.com/yw5aj/apps/releases/latest/download/dance-video.apk

## 打包和测试

全在 GitHub Actions 里（`.github/workflows/dance-video-android.yml`）：编译 ffmpeg → 打包 APK → 在 Android 模拟器里用 4K 4:2:2 样本跑试转、整条转换（中途切到后台）和快速模式 → 发布 Release。

签名密钥不放在仓库里。仓库的 Actions secret `DANCE_VIDEO_KEYSTORE`（.jks 文件的 base64）存在时用它签名，这样新版本能直接覆盖安装；没有时每次临时生成一把，新版本要先卸载旧版。
