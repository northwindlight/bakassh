#!/usr/bin/env python3
"""在模拟器里走一遍主要界面并截图，供 CI 审查界面改动。

用法：tour.py <输出目录>
需要：adb 已连上模拟器，宿主机 10.0.2.2:2222 上有密码为 demo 的 demo 账户。
每一步失败都会记录并截图后继续，最后按失败数返回退出码。
"""
import os
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "dev.northwind.bakassh"
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
failures = []


def log(message):
    print(message, flush=True)
    with open(os.path.join(OUT, "tour.log"), "a", encoding="utf-8") as f:
        f.write(message + "\n")


def adb(*args, check=True, capture=False):
    result = subprocess.run(["adb", *args], check=check, capture_output=capture, timeout=120)
    return result.stdout if capture else None


def sh(command, check=True):
    return adb("shell", command, check=check, capture=True)


def nodes():
    for attempt in range(6):
        out = sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml", check=False)
        text = out.decode("utf-8", "replace") if out else ""
        start = text.find("<?xml")
        if start >= 0:
            try:
                return list(ET.fromstring(text[start:]).iter("node"))
            except ET.ParseError:
                pass
        time.sleep(1)
    return []


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def dismiss_system_dialogs(found):
    """模拟器偶发“某应用无响应”弹窗（例如 Pixel Launcher），点“等待”后继续。"""
    for node in found:
        if node.get("resource-id") == "android:id/aerr_wait":
            x, y = center(node)
            sh(f"input tap {x} {y}")
            log("  已关闭系统无响应弹窗")
            time.sleep(1)
            return True
    return False


def find(rid=None, text=None, contains=None, timeout=20, scroll=False):
    deadline = time.time() + timeout
    while time.time() < deadline:
        found = nodes()
        if dismiss_system_dialogs(found):
            continue
        for node in found:
            if rid and node.get("resource-id") != f"{PKG}:id/{rid}":
                continue
            if text and node.get("text") != text and node.get("content-desc") != text:
                continue
            if contains and contains not in (node.get("text") or ""):
                continue
            return node
        if scroll:
            sh("input swipe 540 1700 540 1000 300")
        time.sleep(1)
    raise RuntimeError(f"找不到控件 rid={rid} text={text} contains={contains}")


def tap(rid=None, text=None, contains=None, timeout=20, scroll=False):
    x, y = center(find(rid, text, contains, timeout, scroll))
    sh(f"input tap {x} {y}")
    time.sleep(0.8)


def type_text(value):
    # input text 用 %s 表示空格；其余字符整体交给远端 shell 引号保护。
    sh("input text " + shlex.quote(value.replace(" ", "%s")))
    time.sleep(0.3)


def ime_shown():
    out = sh("dumpsys input_method", check=False) or b""
    return b"mInputShown=true" in out


def hide_ime():
    # 只在软键盘显示时按返回：键盘收起时返回键会关闭编辑页。
    if ime_shown():
        sh("input keyevent KEYCODE_BACK")
        time.sleep(0.8)


def fill(rid, value, clear=0):
    tap(rid, scroll=True)
    if clear:
        sh("input keyevent KEYCODE_MOVE_END")
        sh("input keyevent " + " ".join(["KEYCODE_DEL"] * clear))
    type_text(value)
    hide_ime()


def back():
    sh("input keyevent KEYCODE_BACK")
    time.sleep(1)


def shot(name):
    time.sleep(1.2)
    data = adb("exec-out", "screencap", "-p", capture=True)
    with open(os.path.join(OUT, name + ".png"), "wb") as f:
        f.write(data)
    log(f"  截图 {name}.png")


def step(name, action):
    log(f"[步骤] {name}")
    try:
        action()
    except Exception as error:  # noqa: BLE001 - CI 审查需要继续走完剩余页面
        failures.append(f"{name}: {error}")
        log(f"  失败：{error}")
        try:
            shot("fail-" + re.sub(r"\W+", "-", name))
        except Exception:  # noqa: BLE001
            pass


def launch():
    sh(f"am start -W -n {PKG}/.HostListActivity")
    time.sleep(2)


def add_host(name, address, port, user, password=None, startup=None, capture=None):
    tap("fab_add_host")
    fill("et_host_name", name)
    fill("et_host_ip", address)
    if port != 22:
        fill("et_host_port", str(port), clear=4)
    fill("et_host_user", user)
    if password is not None:
        tap("btn_toggle_password", scroll=True)
        fill("et_host_password", password)
    if startup:
        fill("et_host_startup", startup)
    if capture:
        sh("input swipe 540 600 540 1400 200")
        shot(capture)
    tap("btn_save_host")


def home_empty():
    sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS", check=False)
    launch()
    shot("01-home-empty")


def editor_validation():
    tap("fab_add_host")
    tap("btn_save_host")
    shot("02-editor-error")
    back()


def hosts():
    add_host("Homelab", "10.0.2.2", 2222, "demo", password="demo",
             startup="cat blocks.txt; ls -la --color=always /etc | head -n 20", capture="03-editor-filled")
    add_host("nas", "192.168.1.20", 22, "admin")
    add_host("build-box", "build.example.org", 2200, "ci")
    shot("04-home-hosts")


def settings_and_about():
    tap("chip_settings")
    shot("05-settings")
    # 60 列：格子约 18px，方块拼缝在截图里足够明显
    fill("et_settings_cols", "60", clear=4)
    tap("settings_about", scroll=True)
    shot("06-about")
    back()
    tap("btn_save_settings")


def keys():
    tap("chip_manage_keys")
    shot("07-keys-empty")
    tap("btn_generate_key")
    shot("08-key-generate")
    tap("btn_confirm_gen")
    find(rid="btn_copy_pubkey", timeout=60)
    shot("09-key-detail")
    back()
    shot("10-keys-list")
    back()


def terminal():
    tap(text="Homelab")
    find(contains="信任并连接", timeout=40)
    shot("11-hostkey")
    tap(text="信任并连接")
    time.sleep(6)
    shot("12-terminal")


def modifier():
    tap(text="Ctrl")
    shot("13-terminal-ctrl")
    tap(text="Ctrl")


def drawer():
    hide_ime()
    tap("btn_term_sessions")
    shot("14-drawer")
    tap("btn_close_session_drawer")


def disconnect():
    hide_ime()
    subprocess.run(["sudo", "pkill", "-KILL", "-u", "demo"], check=False)
    find(rid="btn_session_state_reconnect", timeout=40)
    shot("15-disconnected")


def reconnect():
    tap("btn_session_state_reconnect")
    # 连接成功后状态卡片消失（连接中 / 失败时都会显示）。
    for _ in range(40):
        try:
            find(rid="card_session_state", timeout=1)
            time.sleep(1)
        except RuntimeError:
            break
    hide_ime()
    shot("16-reconnected")


def overflow():
    tap("btn_term_exit")
    shot("17-terminal-menu")
    back()


def home_running():
    back()
    find(rid="card_active_sessions", timeout=20)
    shot("18-home-running")


def banner_via_host(port):
    """在 CI 宿主机上连 127.0.0.1:port，读 SSH 握手横幅。"""
    import socket
    with socket.create_connection(("127.0.0.1", port), timeout=5) as conn:
        conn.settimeout(5)
        return conn.recv(64).decode("latin-1", "replace")


def forwarding():
    # 主机指纹已在终端步骤信任；Homelab 保存了密码，启动时不需要再输入。
    tap("chip_forwarding")
    shot("25-forward-empty")
    tap("tunnel_add")
    fill("rule_port", "8022")
    fill("rule_target_port", "2222")
    shot("26-forward-rule-dialog")
    tap(text="保存")
    tap("tunnel_add")
    tap("rule_remote")
    fill("rule_port", "9000")
    fill("rule_target_port", "8022")
    tap(text="保存")
    shot("27-forward-rules")
    tap("tunnel_start")
    find(text="转发中", timeout=40)
    shot("28-forward-running")
    # -L：手机 127.0.0.1:8022 → 服务器 127.0.0.1:2222（sshd 自己）
    local = (sh("echo | toybox nc -w 3 127.0.0.1 8022", check=False) or b"").decode("latin-1", "replace")
    log(f"  -L 8022 收到：{local.strip()[:40]!r}")
    if not local.startswith("SSH-"):
        raise RuntimeError("-L 转发没有收到 SSH 横幅")
    # -R：服务器 127.0.0.1:9000 → 手机 127.0.0.1:8022 → 再经 -L 回到 sshd
    remote = banner_via_host(9000)
    log(f"  -R 9000 收到：{remote.strip()[:40]!r}")
    if not remote.startswith("SSH-"):
        raise RuntimeError("-R 转发没有收到 SSH 横幅")
    tap("tunnel_manual")
    hide_ime()
    shot("29-forward-command")
    tap("tunnel_graphical")
    back()


def dark_and_palette():
    sh("cmd uimode night yes")
    time.sleep(3)
    shot("19-home-dark")
    tap("chip_forwarding")
    shot("30-forward-dark")
    back()
    tap("card_active_sessions")
    shot("20-sessions-sheet-dark")
    back()
    # 换一个系统取色种子，确认主页和终端外围跟随系统配色。
    sh("settings put secure theme_customization_overlay_packages "
       + shlex.quote('{"android.theme.customization.system_palette":"E8590C",'
                     '"android.theme.customization.theme_style":"TONAL_SPOT"}'))
    time.sleep(6)
    launch()
    shot("21-home-dark-orange")
    sh("cmd uimode night no")
    time.sleep(4)
    shot("22-home-light-orange")
    tap("card_active_sessions")
    shot("23-sessions-sheet-light-orange")
    back()
    tap(text="Homelab")
    time.sleep(3)
    tap("btn_term_sessions")
    shot("24-drawer-orange")


for name, action in [
    ("空主页", home_empty),
    ("编辑页校验", editor_validation),
    ("添加主机", hosts),
    ("设置与关于", settings_and_about),
    ("密钥", keys),
    ("连接终端", terminal),
    ("修饰键", modifier),
    ("会话侧栏", drawer),
    ("断开连接", disconnect),
    ("重新连接", reconnect),
    ("终端菜单", overflow),
    ("主页运行中", home_running),
    ("端口转发", forwarding),
    ("深色与取色", dark_and_palette),
]:
    step(name, action)

log(f"完成，失败 {len(failures)} 项")
for failure in failures:
    log("  - " + failure)
sys.exit(1 if failures else 0)
