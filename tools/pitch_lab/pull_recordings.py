#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 SingingBetter 真机录音拉到电脑，供 pitch_lab.py 离线分析。

原理：debug 构建的 APK 是 debuggable 的，所以 `adb run-as <包名>` 可以
读取应用私有目录。录音读用 `adb exec-out`（不是 `adb shell`），
避免 Windows 下 CRLF 转换把 WAV 二进制改坏。

用法
    python pull_recordings.py                # 拉到 ./recordings
    python pull_recordings.py --out D:/tmp/rec
    python pull_recordings.py --list         # 只列出，不下载
"""

import argparse
import os
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

PACKAGE = "com.singingbetter.app"
REMOTE_DIR = "files/recordings"


def run_adb(args, binary=False, serial=None):
    command = ["adb"]
    if serial:
        command += ["-s", serial]
    command += args
    try:
        result = subprocess.run(command, capture_output=True)
    except FileNotFoundError:
        raise SystemExit("找不到 adb。请把 Android SDK platform-tools 加入 PATH。")
    if result.returncode != 0:
        message = result.stderr.decode("utf-8", "replace").strip()
        hint = ""
        if "not debuggable" in message or "run-as" in message:
            hint = "\n提示：只有 debug 构建才能 run-as。请确认装的是 debug 包。"
        elif "no devices" in message or "device not found" in message:
            hint = "\n提示：没有检测到设备，先跑 `adb devices` 确认已授权。"
        raise SystemExit("adb %s 失败：%s%s" % (" ".join(args), message, hint))
    return result.stdout if binary else result.stdout.decode("utf-8", "replace")


def list_recordings(package, serial=None):
    output = run_adb(["shell", "run-as", package, "ls", "-1", REMOTE_DIR], serial=serial)
    names = []
    for line in output.splitlines():
        name = line.strip().replace("\r", "")
        if name.endswith(".wav"):
            names.append(name)
    return sorted(names)


def pull(name, out_dir, package, serial=None):
    data = run_adb(["exec-out", "run-as", package, "cat", "%s/%s" % (REMOTE_DIR, name)],
                   binary=True, serial=serial)
    if len(data) <= 44:
        print("  跳过 %s：只有 %d 字节，不是有效 WAV" % (name, len(data)))
        return None
    target = os.path.join(out_dir, name)
    with open(target, "wb") as handle:
        handle.write(data)
    print("  已拉取 %s  (%.1f KB)" % (name, len(data) / 1024.0))
    return target


def main():
    parser = argparse.ArgumentParser(description="拉取 SingingBetter 真机录音")
    parser.add_argument("--out", default=os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "recordings"))
    parser.add_argument("--list", action="store_true", help="只列出，不下载")
    parser.add_argument("--package", default=PACKAGE)
    parser.add_argument("--serial", default=None,
                        help="多台设备时指定目标，如 --serial 9HZP55OBFYYDWOPV")
    args = parser.parse_args()
    package = args.package
    serial = args.serial

    print("设备：")
    online = []
    for line in run_adb(["devices"]).splitlines()[1:]:
        text = line.strip().replace("\r", "")
        if not text:
            continue
        print("  " + text)
        if text.endswith("device"):
            online.append(text.split()[0])
    if serial is None and len(online) > 1:
        raise SystemExit("\n检测到 %d 台在线设备，请用 --serial 指定目标：\n  %s"
                         % (len(online), "\n  ".join(online)))
    if serial is None and len(online) == 1:
        serial = online[0]

    try:
        names = list_recordings(package, serial)
    except SystemExit as error:
        print(error)
        return 1

    if not names:
        print("\n%s/%s 下没有 WAV。先在 App 里录一段："
              "\n  首页 -> 「开始 3 分钟练习」 -> 「开始录音」 -> 「结束并保存」"
              % (package, REMOTE_DIR))
        return 1

    print("\n找到 %d 个录音：" % len(names))
    for name in names:
        print("  " + name)

    if args.list:
        return 0

    os.makedirs(args.out, exist_ok=True)
    print("\n下载到 %s" % os.path.abspath(args.out))
    for name in names:
        pull(name, args.out, package, serial)

    print("\n下一步：")
    print("  python pitch_lab.py dump  --wav %s/<文件> --target 220"
          % os.path.abspath(args.out))
    print("  python pitch_lab.py sweep --wav %s/<文件> --target 220"
          % os.path.abspath(args.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
