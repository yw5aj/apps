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
