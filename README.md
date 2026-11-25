# Folia iOS（IPA）自行構建包

上游 `chthollyphile/folia-major` **沒有官方 iOS App**，官方只給 PWA。這個包是把 Web 版用 Capacitor 包成 iOS 工程、在 GitHub Actions 的 macOS runner 上編譯出**未簽名 IPA** 的最小可行方案，改編自社群倉庫 `leiting2327/folia-major-ios`。

## 包含什麼

| 檔案 | 作用 |
| --- | --- |
| `.github/workflows/ios-build.yml` | 完整流程：clone 上游 → `npm ci` → `npm run build` → Capacitor 加 iOS 平台 → 套 App 圖示 → `xcodebuild` 編譯 → 打包 `Folia.ipa` → 上傳 artifact |
| `capacitor.config.json` | appId `top.izuna.foliamajor`、名稱 `Folia`、`webDir: dist`、iOS 最低 15.0 |
| `assets/icon-1024.png` | 由上游 `icon.svg` 轉出的 1024×1024 App 圖示（上游只給 SVG，iOS 需要 PNG） |

## 怎麼用

1. GitHub 建一個新倉庫（public / private 都行，免費帳號即可）
2. 把本包檔案推到倉庫根目錄
3. 進 **Actions** → **Build iOS IPA** → **Run workflow**
4. 等約 20–40 分鐘（macOS runner 較慢，`npm ci` 是主要耗時）
5. 下方 **Artifacts** 下載 `Folia-ipa`，解壓得到 `Folia.ipa`

編譯產物是**未簽名**的，必須自己簽名才能裝到 iPhone：

- **Sideloadly** / **AltStore** / **Feather**：用你的 Apple ID 自簽，免費帳號簽出來的有效期 7 天，過期重簽
- 付費開發者帳號（99 美元/年）可以簽 1 年，或走 TestFlight 分發

## 要接線上音源

倉庫 Settings → Secrets and variables → **Variables** 新增：

- `VITE_NETEASE_API_BASE`
- `VITE_KUGOU_API_BASE`
- `VITE_QQ_API_BASE`

不填就是純本地曲庫版本。填的話必須是**手機能直接連到**的位址（例如你自己的 VPS 或內網穿透），而且 HTTP 位址要靠 workflow 裡那段 ATS 設定放行——這也是為什麼社群那版 IPA 把後端寫死成一個 Tailscale 內網 IP，別人裝了其實連不上。

## 已知限制

- 這份 workflow **我沒有實際跑過**（手上只有 Linux 雲主機，沒有 Xcode）。基於上游社群倉庫已驗證可產出 IPA 的流程改寫，主要差異是把寫死的後端位址改成可選變數、加了 App 圖示、固定 `macos-14` 與 Node 24。第一次跑若失敗，多半是 Capacitor 版本或 Xcode 專案模板差異，把 log 貼出來即可定位。
- iOS 端相對於桌面版功能縮水：沒有桌面歌詞、遙控窗、Wallpaper 模式、Folium 模組、影片匯出
- 本機音樂在 iOS 上只能透過「檔案」App 匯入，沒有資料夾掃描
- AGPL-3.0：自己編譯自用沒問題，對外分發需要同樣開源
