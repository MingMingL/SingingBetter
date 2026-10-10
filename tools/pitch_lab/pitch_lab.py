#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SingingBetter 音高算法离线调参台。

把 app/src/main/java/com/singingbetter/app/PitchDetector.java 的 estimateHz()
逐行复刻成可传参的 Python 实现，在电脑上对 WAV 或合成信号跑参数网格，
用客观指标挑参数，避免"改代码 -> 编译 -> 装机 -> 唱一遍"的慢循环。

三个子命令
    synth   生成带 ground truth 的合成测试集（无需真机，立刻可跑）
    dump    打印某条录音的逐帧中间量（rms / 最佳相关系数 / 检出音高）
    sweep   参数网格扫描，按等效音分误差排序并给出推荐值

典型用法
    python pitch_lab.py synth  --out ./synth
    python pitch_lab.py dump   --wav ./recordings/take_xxx.wav --target 220
    python pitch_lab.py sweep  --synth
    python pitch_lab.py sweep  --wav ./recordings/take_xxx.wav --target 220

所有默认值与 Java 版当前硬编码值一致，见 DEFAULT_PARAMS。
"""

import argparse
import json
import math
import os
import sys
import wave

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

import numpy as np


# --------------------------------------------------------------------------
# 与 PitchDetector.java 对齐的默认参数
# --------------------------------------------------------------------------
DEFAULT_PARAMS = {
    "rms_threshold": 180.0,   # Java: if (rms < 180) return 0f
    "corr_threshold": 0.58,   # Java: if (bestLag <= 0 || best < 0.58) return 0f
    "min_hz": 65.0,           # Java: maxLag = sampleRate / 65
    "max_hz": 950.0,          # Java: minLag = sampleRate / 950
    "octave_ratio": 0.92,     # Java: confidenceThreshold = max(0.58, best * 0.92)
    "stride": 2,              # Java: for (int i = 0; i < limit; i += 2)
    "min_length": 512,        # Java: if (length < 512) return 0f
}

DEFAULT_SAMPLE_RATE = 44100
DEFAULT_FRAME_SIZE = 4096   # 与 AudioSession.FRAME_SIZE 一致
DEFAULT_HOP = 4096          # 真机当前无重叠；sweep 时会一并测试重叠分帧


# --------------------------------------------------------------------------
# 算法复刻
# --------------------------------------------------------------------------
def estimate_hz(frame, sample_rate, params=None, debug=False):
    """复刻 PitchDetector.estimateHz，返回 (hz, info)。

    hz == 0 表示"未检出"，与 Java 版返回 0f 语义一致。
    """
    p = dict(DEFAULT_PARAMS)
    if params:
        p.update(params)

    length = len(frame)
    if length < p["min_length"]:
        return (0.0, {"reason": "too_short"}) if debug else 0.0

    x = frame.astype(np.float64)
    mean = float(np.mean(x))
    rms = float(np.sqrt(np.mean((x - mean) ** 2)))
    if rms < p["rms_threshold"]:
        return (0.0, {"reason": "silent", "rms": rms}) if debug else 0.0

    min_lag = max(2, int(sample_rate / p["max_hz"]))
    max_lag = min(length // 2, int(sample_rate / p["min_hz"]))
    if max_lag <= min_lag + 2:
        return (0.0, {"reason": "lag_range", "rms": rms}) if debug else 0.0

    stride = int(p["stride"])
    correlations = np.zeros(max_lag + 2, dtype=np.float64)
    best = -1.0
    best_lag = -1
    for lag in range(min_lag, max_lag + 1):
        limit = length - lag
        left = x[0:limit:stride] - mean
        right = x[lag:lag + limit:stride] - mean
        cross = float(np.dot(left, right))
        denom = math.sqrt(float(np.dot(left, left)) * float(np.dot(right, right)))
        correlation = cross / denom if denom > 0 else 0.0
        correlations[lag] = correlation
        if correlation > best:
            best = correlation
            best_lag = lag

    if best_lag <= 0 or best < p["corr_threshold"]:
        return (0.0, {"reason": "low_corr", "rms": rms, "best": best}) if debug else 0.0

    fundamental_lag = best_lag
    confidence = max(p["corr_threshold"], best * p["octave_ratio"])
    for lag in range(min_lag + 1, best_lag):
        if (correlations[lag] >= confidence
                and correlations[lag] >= correlations[lag - 1]
                and correlations[lag] > correlations[lag + 1]):
            fundamental_lag = lag
            break

    before = correlations[fundamental_lag - 1] if fundamental_lag > min_lag else correlations[fundamental_lag]
    center = correlations[fundamental_lag]
    after = correlations[fundamental_lag + 1] if fundamental_lag < max_lag else center
    shift = 0.0
    curvature = before - 2 * center + after
    if abs(curvature) > 1e-9:
        shift = 0.5 * (before - after) / curvature

    hz = float(sample_rate / (fundamental_lag + shift))
    if debug:
        info = {
            "reason": "ok", "rms": rms, "best": best, "best_lag": best_lag,
            "fundamental_lag": fundamental_lag, "shift": shift,
            "octave_corrected": fundamental_lag != best_lag,
        }
        return hz, info
    return hz


def midi(hz):
    if hz <= 0:
        return -1
    return int(round(69 + 12 * math.log2(hz / 440.0)))


def note_name(hz):
    m = midi(hz)
    if m < 0:
        return "--"
    names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
    return "%s%d" % (names[m % 12], m // 12 - 1)


def cents(hz, ref):
    if hz <= 0 or ref <= 0:
        return 0.0
    return 1200.0 * math.log2(hz / ref)


# --------------------------------------------------------------------------
# WAV 读写
# --------------------------------------------------------------------------
def read_wav(path):
    with wave.open(path, "rb") as handle:
        channels = handle.getnchannels()
        width = handle.getsampwidth()
        rate = handle.getframerate()
        raw = handle.readframes(handle.getnframes())
    if width != 2:
        raise ValueError("只支持 16-bit PCM，当前 %d byte/样本" % width)
    data = np.frombuffer(raw, dtype="<i2").astype(np.int16)
    if channels > 1:
        data = data.reshape(-1, channels).mean(axis=1).astype(np.int16)
    return data, rate


def write_wav(path, data, rate):
    with wave.open(path, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(rate)
        handle.writeframes(np.asarray(data, dtype="<i2").tobytes())


def frame_signal(x, frame_size, hop):
    count = (len(x) - frame_size) // hop + 1
    for index in range(max(0, count)):
        start = index * hop
        yield start, x[start:start + frame_size]


# --------------------------------------------------------------------------
# 合成测试集
# --------------------------------------------------------------------------
SYNTH_SEQUENCE = [110.0, 146.83, 196.0, 220.0, 261.63, 329.63,
                  392.0, 440.0, 523.25, 659.26, 783.99, 880.0]


def synth_voice(f0_sequence, seg_seconds=0.7, sample_rate=DEFAULT_SAMPLE_RATE,
                seed=0, vibrato_cents=25.0, vibrato_hz=5.0,
                noise_amp=170.0, harmonics=6, amplitude=9000.0):
    """生成类人声信号：谐波叠加 + 颤音 + 分段包络 + 宽带噪声。"""
    rng = np.random.default_rng(seed)
    seg_len = int(seg_seconds * sample_rate)
    total = seg_len * len(f0_sequence)
    t = np.arange(total) / sample_rate

    f0 = np.zeros(total, dtype=np.float64)
    for k, value in enumerate(f0_sequence):
        f0[k * seg_len:(k + 1) * seg_len] = value

    modulated = f0 * (2.0 ** ((vibrato_cents / 1200.0) * np.sin(2 * np.pi * vibrato_hz * t)))
    phase = 2 * np.pi * np.cumsum(modulated) / sample_rate

    signal = np.zeros(total, dtype=np.float64)
    for h in range(1, harmonics + 1):
        signal += (1.0 / h ** 1.2) * np.sin(h * phase)
    peak = np.max(np.abs(signal))
    if peak > 0:
        signal /= peak

    envelope = np.ones(total, dtype=np.float64)
    ramp = int(0.03 * sample_rate)
    envelope[:ramp] = np.linspace(0.0, 1.0, ramp)
    envelope[-ramp:] = np.linspace(1.0, 0.0, ramp)
    signal *= envelope

    signal = signal * amplitude + rng.normal(0.0, noise_amp, total)
    return np.clip(signal, -32768, 32767).astype(np.int16)


def frame_truth(f0_sequence, seg_len, frame_size, hop, frame_count):
    truth = np.zeros(frame_count, dtype=np.float64)
    for index in range(frame_count):
        center = index * hop + frame_size // 2
        k = min(center // seg_len, len(f0_sequence) - 1)
        truth[index] = f0_sequence[k]
    return truth


def build_synth_set(frame_size, hop, sample_rate=DEFAULT_SAMPLE_RATE, seg_seconds=0.7):
    signal = synth_voice(SYNTH_SEQUENCE, seg_seconds=seg_seconds, sample_rate=sample_rate)
    seg_len = int(seg_seconds * sample_rate)
    frame_count = (len(signal) - frame_size) // hop + 1
    truth = frame_truth(SYNTH_SEQUENCE, seg_len, frame_size, hop, frame_count)
    return signal, truth, sample_rate


# --------------------------------------------------------------------------
# 指标
# --------------------------------------------------------------------------
def analyze(detected, truth=None, octave_tol_cents=50.0, jump_tol_cents=80.0,
            pitch_change_cents=100.0):
    """计算指标。

    truth 为 None 时只算不依赖基准的指标（真机录音没有 ground truth 也能用）。
    """
    detected = np.asarray(detected, dtype=np.float64)
    voiced = detected > 0

    metrics = {
        "voiced_ratio": float(voiced.mean()) if len(voiced) else 0.0,
    }

    # 不依赖 truth：相邻帧的八度跳变、乱跳与抖动
    jumps = 0
    pairs = 0
    steps = []
    unstable = 0
    for a in range(len(detected) - 1):
        if not (voiced[a] and voiced[a + 1]):
            continue
        delta = abs(cents(detected[a + 1], detected[a]))
        pairs += 1
        if abs(delta - 1200.0) <= jump_tol_cents:
            jumps += 1
        if delta <= pitch_change_cents:
            steps.append(delta)
        else:
            unstable += 1
    metrics["octave_jump_rate"] = (jumps / pairs) if pairs else 0.0
    metrics["unstable_rate"] = (unstable / pairs) if pairs else 0.0
    metrics["jitter_std_cents"] = float(np.std(steps)) if len(steps) > 1 else 0.0

    if truth is None:
        metrics["score"] = (300.0 * (1.0 - metrics["voiced_ratio"])
                            + metrics["jitter_std_cents"]
                            + 1200.0 * metrics["octave_jump_rate"]
                            + 600.0 * metrics["unstable_rate"])
        return metrics

    truth = np.asarray(truth, dtype=np.float64)
    voiced_truth = truth > 0
    total_voiced = int(voiced_truth.sum())
    if total_voiced == 0:
        metrics["score"] = float("inf")
        return metrics

    hit = int((voiced & voiced_truth).sum())
    metrics["recall"] = hit / total_voiced
    metrics["miss_rate"] = 1.0 - metrics["recall"]

    octave_errors = 0
    good_cents = []
    for index in range(len(detected)):
        if not (voiced[index] and voiced_truth[index]):
            continue
        value = cents(detected[index], truth[index])
        if abs(abs(value) - 1200.0) <= octave_tol_cents:
            octave_errors += 1
        else:
            good_cents.append(abs(value))
    metrics["octave_err_rate"] = (octave_errors / hit) if hit else 0.0
    metrics["cents_mae"] = float(np.mean(good_cents)) if good_cents else 0.0

    # 把所有错误折算成"等效音分误差"，用于单一排序键（低者优）
    metrics["score"] = (metrics["cents_mae"]
                        + 1200.0 * metrics["octave_err_rate"]
                        + 600.0 * metrics["unstable_rate"]
                        + 300.0 * metrics["miss_rate"])
    return metrics


def detect_sequence(signal, sample_rate, params, frame_size, hop):
    out = []
    for _, frame in frame_signal(signal, frame_size, hop):
        out.append(estimate_hz(frame, sample_rate, params))
    return np.array(out, dtype=np.float64)


# --------------------------------------------------------------------------
# 子命令
# --------------------------------------------------------------------------
def cmd_synth(args):
    out_dir = args.out or os.path.join(os.path.dirname(os.path.abspath(__file__)), "synth")
    os.makedirs(out_dir, exist_ok=True)
    signal, truth, rate = build_synth_set(args.frame_size, args.hop, args.sample_rate)
    wav_path = os.path.join(out_dir, "synth_scale.wav")
    write_wav(wav_path, signal, rate)
    meta = {
        "wav": os.path.basename(wav_path),
        "sample_rate": rate,
        "frame_size": args.frame_size,
        "hop": args.hop,
        "f0_sequence": SYNTH_SEQUENCE,
        "truth_hz": [round(float(v), 3) for v in truth],
    }
    with open(os.path.join(out_dir, "synth_scale.json"), "w", encoding="utf-8") as handle:
        json.dump(meta, handle, ensure_ascii=False, indent=2)
    print("已生成合成测试集：")
    print("  %s   (%d 帧, %.1f 秒)" % (wav_path, len(truth), len(signal) / rate))
    print("  音高序列: %s" % ", ".join("%.2f" % f for f in SYNTH_SEQUENCE))
    print("  基准标注: synth_scale.json")


def cmd_dump(args):
    signal, rate = read_wav(args.wav)
    params = parse_params(args.params)
    print("文件 %s" % args.wav)
    print("采样率 %d Hz, 时长 %.2f 秒, 帧长 %d, hop %d"
          % (rate, len(signal) / rate, args.frame_size, args.hop))
    print("参数 %s" % json.dumps(params, ensure_ascii=False))
    print()
    header = "%5s %8s %10s %9s %8s %8s %7s %s" % (
        "帧", "时间(s)", "音高(Hz)", "音名", "音分", "rms", "相关", "状态")
    print(header)
    print("-" * len(header))
    target = args.target
    for index, (start, frame) in enumerate(frame_signal(signal, args.frame_size, args.hop)):
        if args.max_frames and index >= args.max_frames:
            break
        hz, info = estimate_hz(frame, rate, params, debug=True)
        stamp = start / rate
        if hz > 0:
            cents_text = "%+.1f" % cents(hz, target) if target else "-"
            print("%5d %8.3f %10.2f %9s %8s %8.0f %7.3f %s"
                  % (index, stamp, hz, note_name(hz), cents_text,
                     info.get("rms", 0), info.get("best", 0),
                     "八度校正" if info.get("octave_corrected") else "ok"))
        else:
            print("%5d %8.3f %10s %9s %8s %8.0f %7s %s"
                  % (index, stamp, "--", "--", "-", info.get("rms", 0),
                     ("%.3f" % info["best"]) if "best" in info else "-",
                     info.get("reason", "")))


def cmd_sweep(args):
    if args.wav:
        signal, rate = read_wav(args.wav)
        truth = None
        if args.target:
            truth = np.full(len(signal), float(args.target), dtype=np.float64)
            truth[:] = float(args.target)
    else:
        signal, truth, rate = build_synth_set(args.frame_size, args.hop, args.sample_rate)
        print("数据源：合成音阶（%d 个音，%.2f-%.2f Hz）"
              % (len(SYNTH_SEQUENCE), min(SYNTH_SEQUENCE), max(SYNTH_SEQUENCE)))

    grid = parse_grid(args.grid)
    keys = list(grid.keys())
    combos = [{}]
    for key in keys:
        combos = [dict(base, **{key: value}) for base in combos for value in grid[key]]

    base_params = parse_params(args.params)
    print("测试 %d 组参数，帧长 %d / hop %d\n" % (len(combos), args.frame_size, args.hop))

    rows = []
    for combo in combos:
        params = dict(base_params)
        params.update(combo)
        detected = detect_sequence(signal, rate, params, args.frame_size, args.hop)
        metrics = analyze(detected, truth)
        rows.append((combo, params, metrics))
    rows.sort(key=lambda item: item[2]["score"])

    show = rows[:args.top]
    head = "%3s %-40s %8s %8s %9s %8s %8s %9s" % (
        "#", "参数", "检出率", "八度错", "音分MAE", "抖动std", "乱跳率", "评分")
    print(head)
    print("-" * len(head))
    for rank, (combo, params, metrics) in enumerate(show, 1):
        label = ", ".join("%s=%s" % (k, v) for k, v in combo.items()) or "(默认)"
        if len(label) > 40:
            label = label[:37] + "..."
        print("%3d %-40s %8s %8s %9s %8s %8s %9.1f"
              % (rank, label,
                 fmt_pct(metrics.get("voiced_ratio")),
                 fmt_pct(metrics.get("octave_err_rate")),
                 fmt_num(metrics.get("cents_mae")),
                 fmt_num(metrics.get("jitter_std_cents")),
                 fmt_pct(metrics.get("unstable_rate")),
                 metrics["score"]))

    baseline = None
    for combo, params, metrics in rows:
        if not combo:
            baseline = metrics
            break
    if baseline:
        print("\n当前默认参数（Java 版硬编码值）的评分：%.1f" % baseline["score"])
    best = rows[0]
    if best[0]:
        print("建议改用：%s  ->  评分 %.1f"
              % (", ".join("%s=%s" % (k, v) for k, v in best[0].items()), best[2]["score"]))
        print("回填到 PitchDetector.java 的映射：")
        for key, value in best[0].items():
            print("  %-16s -> %s" % (key, java_hint(key, value)))

    if args.csv:
        with open(args.csv, "w", encoding="utf-8") as handle:
            handle.write("combo,voiced_ratio,miss_rate,octave_err_rate,cents_mae,"
                         "jitter_std_cents,octave_jump_rate,unstable_rate,score\n")
            for combo, params, metrics in rows:
                handle.write("%s,%s,%s,%s,%s,%s,%s,%s,%s\n" % (
                    json.dumps(combo, ensure_ascii=False),
                    metrics.get("voiced_ratio", ""), metrics.get("miss_rate", ""),
                    metrics.get("octave_err_rate", ""), metrics.get("cents_mae", ""),
                    metrics.get("jitter_std_cents", ""), metrics.get("octave_jump_rate", ""),
                    metrics.get("unstable_rate", ""), metrics["score"]))
        print("\n完整结果已写入 %s" % args.csv)


def java_hint(key, value):
    return {
        "rms_threshold": "if (rms < %s) return 0f;" % fmt_num(value),
        "corr_threshold": "if (bestLag <= 0 || best < %s) return 0f;" % value,
        "min_hz": "int maxLag = Math.min(length / 2, sampleRate / %s);" % fmt_num(value),
        "max_hz": "int minLag = Math.max(2, sampleRate / %s);" % fmt_num(value),
        "octave_ratio": "double confidenceThreshold = Math.max(%s, best * %s);"
                        % (DEFAULT_PARAMS["corr_threshold"], value),
        "stride": "for (int i = 0; i < limit; i += %s)" % value,
    }.get(key, str(value))


def fmt_pct(value):
    return "%.1f%%" % (100.0 * value) if value is not None else "-"


def fmt_num(value):
    if value is None:
        return "-"
    if isinstance(value, float):
        return "%.1f" % value
    return str(value)


# --------------------------------------------------------------------------
# 参数解析
# --------------------------------------------------------------------------
NUMERIC_KEYS = {"rms_threshold", "corr_threshold", "min_hz", "max_hz", "octave_ratio"}


def coerce(key, text):
    if key == "stride" or key == "min_length":
        return int(text)
    if key in NUMERIC_KEYS:
        return float(text)
    return float(text)


def parse_params(text):
    params = {}
    if not text:
        return params
    for chunk in text.split(","):
        chunk = chunk.strip()
        if not chunk:
            continue
        key, _, value = chunk.partition("=")
        key = key.strip()
        if key not in DEFAULT_PARAMS:
            raise SystemExit("未知参数 %s，可用：%s" % (key, ", ".join(DEFAULT_PARAMS)))
        params[key] = coerce(key, value.strip())
    return params


def parse_grid(items):
    grid = {}
    for item in items or []:
        key, _, values = item.partition("=")
        key = key.strip()
        if key not in DEFAULT_PARAMS:
            raise SystemExit("未知参数 %s，可用：%s" % (key, ", ".join(DEFAULT_PARAMS)))
        grid[key] = [coerce(key, v.strip()) for v in values.split(",") if v.strip()]
    return grid


# --------------------------------------------------------------------------
# 入口
# --------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(
        description="SingingBetter 音高算法离线调参台",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    def add_common(target):
        target.add_argument("--frame-size", type=int, default=DEFAULT_FRAME_SIZE)
        target.add_argument("--hop", type=int, default=DEFAULT_HOP)
        target.add_argument("--sample-rate", type=int, default=DEFAULT_SAMPLE_RATE)

    p_synth = sub.add_parser("synth", help="生成合成测试集")
    p_synth.add_argument("--out", default=None)
    add_common(p_synth)
    p_synth.set_defaults(func=cmd_synth)

    p_dump = sub.add_parser("dump", help="打印逐帧中间量")
    p_dump.add_argument("--wav", required=True)
    p_dump.add_argument("--target", type=float, default=None)
    p_dump.add_argument("--params", default=None)
    p_dump.add_argument("--max-frames", type=int, default=0)
    add_common(p_dump)
    p_dump.set_defaults(func=cmd_dump)

    p_sweep = sub.add_parser("sweep", help="参数网格扫描")
    source = p_sweep.add_mutually_exclusive_group(required=True)
    source.add_argument("--wav", default=None)
    source.add_argument("--synth", action="store_true")
    p_sweep.add_argument("--target", type=float, default=None,
                         help="录音的真实音高；给出后才会算漏检/八度错误")
    p_sweep.add_argument("--params", default=None, help="固定参数 k=v,k=v")
    p_sweep.add_argument("--grid", action="append", default=None,
                         help="扫描维度，可重复，如 --grid rms_threshold=90,180,300")
    p_sweep.add_argument("--top", type=int, default=12)
    p_sweep.add_argument("--csv", default=None)
    add_common(p_sweep)
    p_sweep.set_defaults(func=cmd_sweep)

    args = parser.parse_args()
    if args.command == "sweep" and not args.grid:
        args.grid = [
            "corr_threshold=0.45,0.58,0.70",
            "rms_threshold=60,180,300",
            "max_hz=950,1200",
            "octave_ratio=0.85,0.92,0.97",
        ]
    args.func(args)


if __name__ == "__main__":
    main()
