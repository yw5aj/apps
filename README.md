# apps

一堆小 app，每个放在自己的目录里，通过 GitHub Pages 发布。

- 首页：https://yw5aj.github.io/apps/
- 每个 app：`https://yw5aj.github.io/apps/<目录名>/`

## 加一个新 app

1. 在根目录建一个目录，比如 `my-app/`，里面放 `index.html`。
2. 在 `apps.json` 里加一条，首页就会自动列出来：

```json
[
  { "path": "my-app", "name": "我的 App", "desc": "一句话介绍", "emoji": "🎵" }
]
```

`desc` 和 `emoji` 可以不写。

## Android 版（APK）

有的 app 还有 APK，比如 `dance-video/android/`。每个 APK app：

- 代码放在它自己的目录里，比如 `<目录名>/android/`。
- 有自己的打包流程 `.github/workflows/<目录名>-android.yml`，只在它的目录有改动时运行，在模拟器里测过才发布。
- 发布到一个固定的 Release（标签 `<目录名>-apk`），下载地址永远是 `https://github.com/yw5aj/apps/releases/download/<目录名>-apk/<目录名>.apk`。
- 在 `apps.json` 那一条里加 `"apk": "<下载地址>"`，首页会多一个“下载安卓版”链接。只有 APK、没有网页版的话再加 `"web": false`。
