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

---

# Folia Android（APK）自行構建包

同一份補丁、同一套流程，換成 Android 骨幹。目標裝置是**梁山嘟嘟 S3 這類 9 寸安卓車機**，但普通安卓手機／平板同樣能裝。

## 包含什麼

| 檔案 | 作用 |
| --- | --- |
| `.github/workflows/android-build.yml` | clone 上游 → 打補丁 → `npm ci` + `npm run build` → Capacitor 加 Android 平台 → 改 Manifest（橫屏鎖定、cleartext）→ 換圖示 → `gradlew assembleDebug` / `assembleRelease` → 上傳 APK |
| `capacitor.config.json` | 加上 `android.allowMixedContent`、背景色 `#09090b`（避免啟動白屏） |
| `assets/icon-1024.png` | 同一張圖示，workflow 會縮成 mdpi～xxxhdpi 各尺寸 |

## 產物

一次 build 出兩個 APK：

- **Folia-debug.apk**（約 18 MB）——用 Actions runner 自帶的 debug keystore 簽過，**可以直接安裝**。車機 sideload 就用這個。
- **Folia-release-unsigned.apk**（約 16 MB）——沒簽名。要自己簽：`apksigner sign --ks 你的.keystore --out Folia.apk Folia-release-unsigned.apk`

## 裝到車機上

1. U 盤／TF 卡把 `Folia-debug.apk` 放進去，插到車機，用自帶的檔案管理器點開安裝（多數車機會攔「未知來源」，要去設定裡放行）。
2. 或走 adb：`adb connect <車機IP>:5555` → `adb install -r Folia-debug.apk`（嘟嘟車機一般要先去開發者選項開 USB 調試／網絡調試）。
3. 裝完第一次開可能會空白幾秒——那是 WebView 在載入 9 寸屏的完整前端，不是卡死。

## 針對 9 寸車機做的設定

- `android:screenOrientation="sensorLandscape"`：鎖橫屏，允許左右橫放，不會被車機轉成豎屏。
- `android:resizeableActivity="false"`：不進分屏，避免車機的分屏把畫面切碎。
- `android:usesCleartextTraffic="true"`：要接自己架的 HTTP 音源 API 才不會被攔。
- `versionName` 跟 `package.json` 對齊（目前 0.8.0），包名 `top.izuna.foliamajor`。
- minSdk 23（Android 6.0）/ compileSdk 34，嘟嘟這類 Android 10 車機沒問題。

## 已知限制（照實說）

- **這兩個 APK 我沒在真的車機上跑過**（手上只有 Linux 雲主機，沒有安卓裝置也沒有模擬器）。編譯、簽名、內容我都驗過（`aapt2 dump badging` 確認包名版本、`META-INF/CERT.RSA` 確認已簽、`assets/public` 裡確認含新介面），但**車機那一側的表現要你裝上去看**。
- 車機的 WebView 版本是關鍵：安卓車機的 System WebView 常常很舊（甚至沒連網更新過）。Folia 用了不少現代前端特性，WebView 低於 Chrome 80 很可能白屏。真遇到就裝一個獨立的 Android System WebView APK 更新它。
- 車機的 GPU 多半沒有 WebGL2，歌詞視覺器會走降級路徑（能播，但特效少）。
- 沒有桌面版的歌詞視窗、遙控窗、Wallpaper 模式、Folium 模組、影片匯出。
- 本機音樂在安卓上跟 iOS 一樣只能靠「導入文件」匯入（這版已內建），沒有資料夾掃描——要資料夾掃描得等有人做 Android 的 SAF 適配。
