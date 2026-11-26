# music-stack

自建的無損音樂獲取與校驗棧：**Soulseek P2P 取源 + Lidarr 管庫 + 假無損檢測**。
不依賴任何付費串流平台會員。

```
music-stack/
├── docker-compose.yml          # slskd + lidarr + soularr
├── .env.example
├── conf/
│   └── soularr.ini.example
├── scripts/
│   ├── lossless_check.py       # 假無損檢測（規格 + 標籤 + 頻譜）
│   └── run-check.sh            # 定時巡檢入口
└── README.md
```

## 1. 啟動

```bash
cp .env.example .env          # 填 Soulseek 帳號（免費註冊，不需付費會員）
mkdir -p conf/soularr data music/{downloads,shared,quarantine} reports
cp conf/soularr.ini.example conf/soularr/config.ini   # 填 Lidarr API Key
docker compose up -d
```

- slskd Web UI：`http://<host>:5030`
- Lidarr：`http://<host>:8686`
- 首次進 slskd 需在介面建立使用者，並確認 `50300` 埠已對外（否則只能被動連線，搜尋結果會很少）

## 2. 三類音樂的搜尋策略

### 日本動漫歌曲

Soulseek 上的日系分享者多為 CD 原盤抓軌，命名對得上才搜得到：

- 日文原名與羅馬字各搜一次：`君の名は` / `Kimi no Na wa`
- **商品番號命中率最高**：`VTCL-`、`LACM-`、`KICA-`、`GNCA-`、`PCLE-`、`LACA-`
- 關鍵字：`OP`、`ED`、`OST`、`サントラ`、`キャラソン`、`ドラマCD`
- 檔名特徵：`[アーティスト] アルバム [FLAC]`
- 現實預期：動漫單曲絕大多數為 44.1kHz/16bit，Hi-Res 僅見於部分劇場版或數位發行

### 華語歌曲

- 同一位歌手至少試四種寫法：繁體 / 簡體 / 英文名 / 拼音
- 專輯用 `專輯名 + 年份` 搜，例如 `范特西 2001`
- Soulseek 分佈零散，這類建議交給聚合工具補（例如 `CharlesPikachu/musicdl`），Soulseek 只補老專輯與冷門盤
- 華語「無損」假檔比例高，入庫必過本倉的檢測腳本

### DJ

- **混音輯**：搜廠牌與系列名，`Fabric`、`Global Underground`、`Ministry of Sound`、`Balance`、`Balance 0xx`
- **電子單曲**：Soulseek 新發行滯後，跑場子建議走 Beatport 官方購買 WAV/FLAC
- **DJ set 錄音**（SoundCloud / Mixcloud）：本身非無損發行，不要期待 FLAC
- 入庫留意採樣率：電子樂常見 48kHz，與 44.1kHz 混放會在同一個 DAW session 出問題，建議按採樣率分目錄

## 3. 假無損檢測

```bash
# 全量掃描，可疑檔移到隔離區
python3 scripts/lossless_check.py ./music/downloads \
  --move ./music/quarantine --report ./reports/check.csv

# 只驗規格與標籤（不做頻譜，速度快 10 倍以上）
python3 scripts/lossless_check.py ./music/downloads --no-spectral
```

判定依據：

| 檢查項 | 判定 |
|---|---|
| 容器 / 編碼 | 僅接受 flac、alac、ape、wavpack、pcm(wav)，其餘判假 |
| 採樣率 | < 44.1 kHz 判可疑 |
| 位深 | < 16 bit 判可疑 |
| 標籤 | encoder 含 mp3 / aac / vorbis / opus → 判假 |
| 近奈奎斯特比值 | 21.2–22.0 kHz 相對 17.2–18.0 kHz < -25 dB → 判假 |
| 截斷頻率 | 19 kHz 前後能量就已止住 → 判可疑（電子樂/極簡曲目需人工複查） |

頻譜實作：解碼前 30 秒 → 單聲道 44.1 kHz → Welch PSD（8192 點 Hann 窗、50% 重疊）。
真無損近奈奎斯特能量通常與相鄰高頻旗鼓相當（實測差異約 -1 dB）；MP3/AAC 轉碼後
21 kHz 以上被數位濾波器清空，實測差異達 -70 dB 左右，區分度非常高。

輸出 `verdict`：`OK` / `SUSPECT` / `FAKE` / `ERROR`，並附 CSV 報告
（含 `nyq_ratio_db`、`cutoff_hz` 兩欄原始數值，便於複核）。

依賴：`ffmpeg`（含 `ffprobe`）、Python 3.9+、`numpy`（僅頻譜檢查需要，
`--no-spectral` 時可省）。

## 4. 合規提示

- slskd 為 P2P 客戶端，使用時需遵守 Soulseek 網路規則並開啟分享目錄
- 本倉僅提供工具與校驗邏輯；請只用於自有購買、公有領域、作者授權發布的內容
