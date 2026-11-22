# EDS视频转换

把 Enjoy Dance Studio 的 MOV（H.265 10-bit 4:2:2 + PCM 声音）在手机浏览器里转成相册能放的 MP4。视频不上传。

- 快速模式：手机能解画面时，只把声音转成 AAC（Mediabunny）。
- 慢速模式：手机解不了画面时，用 ffmpeg.wasm 把画面重编码成 H.264。
- `coi-serviceworker.min.js` 让 GitHub Pages 开启跨域隔离，ffmpeg 才能用多线程。

## 改代码

源码在 `src/`，`app.js` 是打包结果：

```
npm i mediabunny @mediabunny/aac-encoder esbuild
npx esbuild src/app.js --bundle --format=iife --minify --target=es2020 --outfile=app.js
```

`vendor/` 来自 `@ffmpeg/ffmpeg@0.12.15`、`@ffmpeg/core@0.12.10`、`@ffmpeg/core-mt@0.12.10` 的 `dist/umd`。

## 外观

- 颜色用的是中国传统色名：月白（底）、黛（字）、胭脂（强调）、藕荷（浅填充），定义在 `index.html` 顶部的 `:root`。
- 标题字体是站酷小薇，`fonts/xiaowei-subset.woff2` 只含页面用到的字。页面文字改动后用 `tools/subset_font.py` 重新做子集，否则新出现的字会退回系统宋体。
