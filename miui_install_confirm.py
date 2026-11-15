#!/usr/bin/env python3
"""MIUI 「USB 安装提示」自动确认。

用法：
    python3 miui_install_confirm.py <apk_path> [--timeout 120]

做法：后台起 adb install，前台轮询 uiautomator dump，
命中「继续安装 / 安装 / 确定 / 仍要安装」等按钮时按 bounds 中心点击，
直到 install 进程结束。
"""
import re
import subprocess
import sys
import time

ENV = {"ADB_LIBUSB": "1"}
POSITIVE = ["继续安装", "仍要安装", "安装", "确定", "允许", "始终允许"]
NEGATIVE = {"取消", "放弃", "关闭"}


def adb(*args, timeout=30):
    return subprocess.run(["adb", *args], capture_output=True, text=True,
                          timeout=timeout, env={**ENV, "PATH": "/usr/bin:/bin:/usr/local/bin"})


def dump():
    for _ in range(3):
        r = adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        if "dumped" in (r.stdout or "") + (r.stderr or ""):
            break
        time.sleep(0.4)
    r = adb("shell", "cat", "/sdcard/ui.xml")
    return r.stdout or ""


def find_buttons(xml):
    """返回 [(text, cx, cy)]，只取可见文本节点。"""
    out = []
    for node in re.findall(r"<node[^>]*>", xml):
        tm = re.search(r'text="([^"]+)"', node)
        bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if not tm or not bm:
            continue
        text = tm.group(1).strip()
        if not text:
            continue
        x1, y1, x2, y2 = map(int, bm.groups())
        out.append((text, (x1 + x2) // 2, (y1 + y2) // 2, x2 - x1, y2 - y1))
    return out


def main():
    apk = sys.argv[1]
    timeout = 150
    if "--timeout" in sys.argv:
        timeout = int(sys.argv[sys.argv.index("--timeout") + 1])

    adb("shell", "svc", "power", "stayon", "true")
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")

    print("[*] 启动 adb install")
    proc = subprocess.Popen(["adb", "install", "-r", apk],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, env={**ENV, "PATH": "/usr/bin:/bin:/usr/local/bin"})

    deadline = time.time() + timeout
    tapped = 0
    while time.time() < deadline:
        if proc.poll() is not None:
            break
        xml = dump()
        if "继续安装" in xml or "USB安装" in xml or "安装提示" in xml or "未知来源" in xml:
            btns = find_buttons(xml)
            # 先按优先级找正向按钮
            hit = None
            for want in POSITIVE:
                for text, cx, cy, w, h in btns:
                    if text == want or (want in text and text not in NEGATIVE):
                        hit = (text, cx, cy)
                        break
                if hit:
                    break
            if hit:
                print(f"[+] 点击「{hit[0]}」@ ({hit[1]},{hit[2]})")
                adb("shell", "input", "tap", str(hit[1]), str(hit[2]))
                tapped += 1
                time.sleep(1.5)
                continue
        time.sleep(1.0)

    try:
        out = proc.communicate(timeout=max(30, deadline - time.time() + 30))[0]
    except subprocess.TimeoutExpired:
        proc.kill()
        out = "(timeout)"

    print("[*] 点击次数:", tapped)
    print("[*] adb install 输出:")
    print((out or "").strip())
    return 0 if "Success" in (out or "") else 1


if __name__ == "__main__":
    sys.exit(main())
