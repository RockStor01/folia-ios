#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
lossless_check.py — 假無損檢測器

用途
-----
掃描指定目錄或檔案，判定是否為「真無損」音檔，並可把可疑檔案移到隔離區。

判定邏輯
---------
1. 容器 / 編碼白名單：只接受 flac、alac、ape、wavpack、pcm(wav)
2. 規格：採樣率 < 44100 或位深 < 16 判可疑
3. 標籤：encoder 欄位出現 mp3 / aac / vorbis / opus 等有損字樣 → 判假
4. 頻譜（核心）：解碼前 30 秒做 Welch PSD，比對
   - 近奈奎斯特比值：21.2–22.0 kHz 相對 17.2–18.0 kHz 的能量差
     真無損兩者接近；MP3/ AAC 轉碼後 21 kHz 以上被數字濾波器清零 → 差值極大
   - 截斷頻率：1 kHz 分箱掃描，能量高於峰值 -55 dB 的最高頻點
     MP3 低位元率（128k 左右）截斷在 16 kHz 附近，直接暴露

用法
-----
    python3 lossless_check.py ./music
    python3 lossless_check.py ./music --move ./quarantine --report report.csv
    python3 lossless_check.py a.flac b.flac --jobs 8
    python3 lossless_check.py ./music --no-spectral     # 只驗規格與標籤

退出碼：0 全部通過；1 存在可疑檔案；2 依賴缺失或參數錯誤
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, asdict, field
from pathlib import Path

AUDIO_EXT = {".flac", ".alac", ".ape", ".wv", ".wav", ".aiff", ".aif"}
OK_CODECS = {
    "flac", "alac", "ape", "wavpack",
    "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_s16be", "pcm_s24be",
}
LOSSY_HINTS = ("mp3", "mpeg", "aac", "vorbis", "opus", "wma", "lame", "lossy")

# 近奈奎斯特比值門檻（dB）：真無損通常 > -15 dB，轉碼檔普遍 < -35 dB
DEFAULT_RATIO_DB = -25.0
# 截斷頻率下限（Hz）：低於此值幾乎可斷定是有損來源
CUTOFF_MIN_HZ = 16500
ANALYZE_SECONDS = 30
NFFT = 8192


@dataclass
class Result:
    path: str
    verdict: str            # OK / SUSPECT / FAKE / ERROR
    codec: str = ""
    sample_rate: int = 0
    bit_depth: int = 0
    channels: int = 0
    nyq_ratio_db: float | None = None
    cutoff_hz: int | None = None
    reasons: list[str] = field(default_factory=list)

    def csv_row(self) -> dict:
        return {
            "path": self.path,
            "verdict": self.verdict,
            "codec": self.codec,
            "sample_rate": self.sample_rate,
            "bit_depth": self.bit_depth,
            "channels": self.channels,
            "nyq_ratio_db": "" if self.nyq_ratio_db is None else f"{self.nyq_ratio_db:.1f}",
            "cutoff_hz": "" if self.cutoff_hz is None else self.cutoff_hz,
            "reasons": "; ".join(self.reasons),
        }


def run(cmd: list[str], timeout: int = 180) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def probe(path: Path) -> tuple[dict, dict]:
    out = run([
        "ffprobe", "-v", "error", "-hide_banner",
        "-show_streams", "-show_format", "-of", "json", str(path),
    ])
    if out.returncode != 0:
        tail = out.stderr.strip().splitlines()
        raise RuntimeError(tail[-1] if tail else "ffprobe failed")
    data = json.loads(out.stdout or "{}")
    streams = [s for s in data.get("streams", []) if s.get("codec_type") == "audio"]
    if not streams:
        raise RuntimeError("no audio stream")
    return streams[0], data.get("format", {})


def bit_depth(stream: dict) -> int:
    for key in ("bits_per_raw_sample", "bits_per_sample"):
        raw = stream.get(key)
        if raw and str(raw).isdigit() and int(raw) > 0:
            return int(raw)
    fmt = (stream.get("sample_fmt") or "")
    return {"s16": 16, "s16p": 16, "s32": 24, "s32p": 24, "flt": 32, "fltp": 32}.get(fmt, 0)


def decode_mono_safe(path: Path, sr: int):
    """實作：直接用 subprocess 取二進位 stdout，避免文字解碼破壞樣本。"""
    import numpy as np

    try:
        proc = subprocess.run(
            ["ffmpeg", "-v", "error", "-nostdin", "-i", str(path),
             "-t", str(ANALYZE_SECONDS), "-ac", "1", "-ar", str(sr),
             "-f", "s16le", "-acodec", "pcm_s16le", "-"],
            capture_output=True, timeout=240,
        )
    except Exception:
        return None
    if proc.returncode != 0 or not proc.stdout:
        return None
    pcm = np.frombuffer(proc.stdout, dtype="<i2").astype(np.float32) / 32768.0
    if pcm.size < NFFT:
        return None
    return pcm


def welch_psd(pcm, sr: int):
    """簡易 Welch PSD，回傳 (freqs, power)。"""
    import numpy as np

    win = np.hanning(NFFT)
    step = NFFT // 2
    count = 1 + max(0, (pcm.size - NFFT) // step)
    acc = np.zeros(NFFT // 2 + 1)
    for i in range(count):
        seg = pcm[i * step: i * step + NFFT] * win
        spec = np.fft.rfft(seg)
        acc += np.abs(spec) ** 2
    acc /= count
    freqs = np.fft.rfftfreq(NFFT, 1.0 / sr)
    return freqs, acc


def band_power(freqs, power, lo: int, hi: int) -> float:
    import numpy as np

    mask = (freqs >= lo) & (freqs < hi)
    if not mask.any():
        return 0.0
    return float(np.mean(power[mask]))


def analyze_spectrum(path: Path, sr: int) -> tuple[float | None, int | None]:
    """回傳 (近奈奎斯特比值 dB, 截斷頻率 Hz)。"""
    import numpy as np

    pcm = decode_mono_safe(path, sr)
    if pcm is None:
        return None, None
    freqs, power = welch_psd(pcm, sr)
    if power.max() <= 0:
        return None, None

    near = band_power(freqs, power, 21200, 22050)
    ref = band_power(freqs, power, 17200, 18000)
    if ref <= 0 or near <= 0:
        ratio = None
    else:
        ratio = 10.0 * float(np.log10(near / ref))

    # 1 kHz 分箱掃描，找能量高於峰值 -55 dB 的最高頻點
    peak = power.max()
    floor = peak * 10 ** (-55 / 10)
    cutoff = None
    for lo in range(1000, 22001, 1000):
        seg = band_power(freqs, power, lo, min(lo + 1000, 22050))
        if seg > floor:
            cutoff = min(lo + 1000, sr // 2)
    return ratio, cutoff


def check_file(path: Path, do_spectral: bool = True, ratio_db: float = DEFAULT_RATIO_DB) -> Result:
    res = Result(path=str(path), verdict="OK")
    try:
        stream, fmt = probe(path)
    except Exception as exc:  # noqa: BLE001
        res.verdict = "ERROR"
        res.reasons.append(f"讀取失敗: {exc}")
        return res

    res.codec = (stream.get("codec_name") or "").lower()
    res.sample_rate = int(stream.get("sample_rate") or 0)
    res.channels = int(stream.get("channels") or 0)
    res.bit_depth = bit_depth(stream)

    if res.codec not in OK_CODECS:
        res.reasons.append(f"非無損編碼: {res.codec or 'unknown'}")
    if res.sample_rate and res.sample_rate < 44100:
        res.reasons.append(f"採樣率過低: {res.sample_rate} Hz")
    if res.codec in {"flac", "alac", "ape", "wavpack"} and res.bit_depth and res.bit_depth < 16:
        res.reasons.append(f"位深過低: {res.bit_depth} bit")

    tags = {str(k).lower(): str(v) for k, v in (fmt.get("tags") or {}).items()}
    for key in ("encoder", "encoded_by", "comment", "description", "tool"):
        value = tags.get(key, "").lower()
        if any(h in value for h in LOSSY_HINTS):
            res.reasons.append(f"標籤顯示曾以有損編碼處理 ({key}={tags.get(key)})")
            break

    if do_spectral and res.sample_rate:
        try:
            ratio, cutoff = analyze_spectrum(path, res.sample_rate)
        except Exception:  # noqa: BLE001
            ratio, cutoff = None, None
        res.nyq_ratio_db, res.cutoff_hz = ratio, cutoff
        if ratio is None and cutoff is None:
            res.reasons.append("頻譜分析失敗，未能完成驗證（建議人工複查）")
        if ratio is not None and ratio < ratio_db:
            res.reasons.append(f"近奈奎斯特能量塌陷 ({ratio:.1f} dB < {ratio_db:.0f} dB)")
        if cutoff is not None and cutoff < CUTOFF_MIN_HZ:
            res.reasons.append(
                f"高頻內容止於 {cutoff} Hz（低比特率轉碼常見，但電子/極簡曲目亦可能如此，需人工複查）"
            )

    if res.reasons:
        fatal = ("非無損", "近奈奎斯特", "標籤")
        res.verdict = "FAKE" if any(r.startswith(fatal) for r in res.reasons) else "SUSPECT"
    return res


def iter_files(targets: list[str]) -> list[Path]:
    found: list[Path] = []
    for target in targets:
        p = Path(target)
        if p.is_dir():
            for root, _, files in os.walk(p):
                for name in files:
                    if Path(name).suffix.lower() in AUDIO_EXT:
                        found.append(Path(root) / name)
        elif p.is_file() and p.suffix.lower() in AUDIO_EXT:
            found.append(p)
    return sorted(set(found))


def main() -> int:
    ap = argparse.ArgumentParser(description="假無損音檔檢測器")
    ap.add_argument("targets", nargs="+", help="檔案或目錄")
    ap.add_argument("--move", metavar="DIR", help="把 FAKE / SUSPECT 檔案移到此隔離目錄（保留相對結構）")
    ap.add_argument("--report", metavar="CSV", default="lossless_report.csv", help="CSV 報告輸出路徑")
    ap.add_argument("--jobs", type=int, default=max(4, (os.cpu_count() or 4)), help="並行數")
    ap.add_argument("--ratio-db", type=float, default=DEFAULT_RATIO_DB, help="近奈奎斯特比值門檻（dB）")
    ap.add_argument("--no-spectral", action="store_true", help="跳過頻譜檢查（只驗規格與標籤，快很多）")
    args = ap.parse_args()

    for tool in ("ffprobe", "ffmpeg"):
        if shutil.which(tool) is None:
            print(f"缺少依賴：{tool}，請先安裝 ffmpeg", file=sys.stderr)
            return 2
    if not args.no_spectral:
        try:
            import numpy  # noqa: F401
        except ImportError:
            print("缺少 numpy，頻譜檢查不可用；可安裝 numpy 或改用 --no-spectral", file=sys.stderr)
            return 2

    files = iter_files(args.targets)
    if not files:
        print("沒有找到任何音檔。", file=sys.stderr)
        return 2

    print(f"待檢測 {len(files)} 個檔案，並行 {args.jobs} …")
    results: list[Result] = []
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(check_file, f, not args.no_spectral, args.ratio_db): f for f in files}
        for i, fut in enumerate(as_completed(futures), 1):
            res = fut.result()
            results.append(res)
            if res.verdict != "OK":
                print(f"[{res.verdict}] {res.path} — {'; '.join(res.reasons)}")
            if i % 50 == 0:
                print(f"  … 已處理 {i}/{len(files)}", file=sys.stderr)

    results.sort(key=lambda r: r.path)
    report = Path(args.report)
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open("w", newline="", encoding="utf-8-sig") as fh:
        writer = csv.DictWriter(fh, fieldnames=["path", "verdict", "codec", "sample_rate",
                                               "bit_depth", "channels", "nyq_ratio_db",
                                               "cutoff_hz", "reasons"])
        writer.writeheader()
        for res in results:
            writer.writerow(res.csv_row())

    if args.move:
        base = Path(args.move)
        moved = 0
        for res in results:
            if res.verdict not in {"FAKE", "SUSPECT"}:
                continue
            src = Path(res.path)
            try:
                rel = src.resolve().relative_to(Path.cwd().resolve())
            except ValueError:
                rel = Path(src.name)
            dst = base / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(src), str(dst))
            moved += 1
        print(f"已隔離 {moved} 個檔案 → {base}")

    counts: dict[str, int] = {}
    for res in results:
        counts[res.verdict] = counts.get(res.verdict, 0) + 1
    print("\n=== 結果統計 ===")
    for key in ("OK", "SUSPECT", "FAKE", "ERROR"):
        if counts.get(key):
            print(f"{key:8s} {counts[key]}")
    print(f"報告：{report.resolve()}")
    return 0 if counts.get("OK", 0) == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
