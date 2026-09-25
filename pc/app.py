#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
互传助手 LinkAssist —— 电脑端桌面应用(exe)
- 内嵌与 server.py 完全相同的服务端(手机照常连接)
- 悬浮球:透明置顶小圆球,可拖动;左键点击 = 收起/展开悬浮面板;右键 = 退出
- 系统托盘:左键点击 = 呼出/隐藏面板;右键菜单 = 显示面板 / 隐藏到托盘 / 退出
开发运行: python app.py
打包 exe: build.bat (PyInstaller)
"""
import os
import socket
import sys
import threading
import time
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

import webview

try:
    webview.settings["ALLOW_DOWNLOADS"] = True
    webview.settings["OPEN_EXTERNAL_LINKS_IN_BROWSER"] = True
except (AttributeError, KeyError, TypeError):
    pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import server as srv


def pick_port(preferred: int) -> int:
    """优先使用配置端口;被占用则向后找一个空闲端口"""
    for p in range(preferred, preferred + 10):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            s.bind(("0.0.0.0", p))
            return p
        except OSError:
            continue
        finally:
            s.close()
    return preferred


def allow_firewall(port: int):
    """尽力放行防火墙(需要管理员权限;失败静默,不影响运行)"""
    try:
        import subprocess
        for name, proto in ((f"LinkAssist TCP {port}", "tcp"), ("LinkAssist UDP 37777", "udp")):
            subprocess.run(
                ["netsh", "advfirewall", "firewall", "add", "rule",
                 f"name={name}", "dir=in", f"protocol={proto}",
                 f"localport={port if proto == 'tcp' else 37777}", "action=allow"],
                capture_output=True, timeout=10,
            )
        srv.log("[防火墙] 已尝试放行 TCP %d / UDP 37777 (若非管理员权限请手动放行)" % port)
    except Exception as e:
        srv.log("[防火墙] 自动放行跳过:", e)


def start_backend(port: int):
    try:
        srv.LAN_IP = srv.lan_ip()
        threading.Thread(target=srv.discovery_loop, args=(srv.LAN_IP,), daemon=True).start()
        web = srv.web
        web.run_app(srv.create_app(), host="0.0.0.0", port=port, print=lambda *a, **k: None)
    except Exception as e:
        srv.log("[服务] 启动失败:", e)


# ---------------------------------------------------------------- 系统托盘
_tray = None


def _tray_image():
    """程序化画一个托盘图标(渐变圆 + 天线),不依赖外部文件"""
    from PIL import Image, ImageDraw
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([2, 2, 62, 62], fill=(79, 140, 255, 255))
    d.ellipse([14, 14, 50, 50], fill=(124, 92, 255, 255))
    d.ellipse([26, 26, 38, 38], fill=(255, 255, 255, 235))
    d.line([32, 32, 46, 12], fill=(255, 255, 255, 220), width=4)
    d.ellipse([42, 8, 52, 18], fill=(255, 255, 255, 235))
    return img


def start_tray(toggle_cb, quit_cb):
    """启动系统托盘;失败(如无桌面环境)静默降级"""
    global _tray
    try:
        import pystray

        def _on_toggle(icon, item):
            toggle_cb()

        def _on_show(icon, item):
            toggle_cb(True)

        def _on_hide(icon, item):
            toggle_cb(False)

        def _on_quit(icon, item):
            quit_cb()

        menu = pystray.Menu(
            pystray.MenuItem("显示 / 隐藏面板", _on_toggle, default=True),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("显示面板", _on_show),
            pystray.MenuItem("隐藏到托盘", _on_hide),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("退出", _on_quit),
        )
        _tray = pystray.Icon("LinkAssist", _tray_image(), "互传助手 LinkAssist", menu)
        threading.Thread(target=_tray.run, daemon=True).start()
        srv.log("[托盘] 系统托盘已启动")
    except Exception as e:
        srv.log("[托盘] 不可用:", e)


def stop_tray():
    global _tray
    t = _tray
    _tray = None
    if t is not None:
        try:
            t.stop()
        except Exception:
            pass


class Api:
    """暴露给页面 JS 的接口 (window.pywebview.api.*)"""
    panel_visible = True

    def toggle_panel(self):
        global panel_win
        w = panel_win
        if w is None:
            return False
        if Api.panel_visible:
            w.hide()
            Api.panel_visible = False
        else:
            w.show()
            Api.panel_visible = True
        return Api.panel_visible

    def open_received_folder(self):
        path = os.path.join(srv.BASE, "transfers", "received")
        os.makedirs(path, exist_ok=True)
        if sys.platform == "win32":
            os.startfile(path)
            return True
        return False

    def open_external_url(self, url):
        from urllib.parse import urlsplit
        import webbrowser
        parsed = urlsplit(str(url))
        repository = srv.CFG.get("updateRepository", srv.DEFAULT_REPOSITORY)
        if (parsed.scheme != "https" or parsed.hostname != "github.com"
                or parsed.username or parsed.password or parsed.port not in (None, 443)
                or not parsed.path.startswith(f"/{repository}/releases")):
            return False
        return webbrowser.open(url)

    def quit_app(self):
        stop_tray()
        try:
            for w in list(webview.windows):
                w.destroy()
        finally:
            os._exit(0)


api = Api()
panel_win = None


def wait_backend(port: int, timeout: float = 15.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/api/status", timeout=0.5)
            return True
        except Exception:
            time.sleep(0.1)
    return False


def main():
    global panel_win
    srv.CFG = srv.load_config()
    srv.load_history()
    srv.load_transfers()
    port = pick_port(int(srv.CFG["port"]))
    srv.CFG["port"] = port
    # 实际端口通过 UDP 发现(37777)对外公布,手机端连接失败时会自动跟随,无需写回配置

    threading.Thread(target=start_backend, args=(port,), daemon=True).start()
    if not wait_backend(port):
        srv.log("[服务] 后端未就绪,仍继续启动界面(可能稍后自动恢复)")
    srv.log("[网络] 若手机无法连接，请在 Windows 提示中仅允许专用网络访问。")

    panel_win = webview.create_window(
        "LinkAssist · 互传助手",
        f"http://127.0.0.1:{port}/?app=1",
        js_api=api,
        width=1120, height=800, min_size=(420, 600),
        frameless=True, on_top=False,
        background_color="#F5F7F9",
    )
    ball_win = webview.create_window(
        "LinkAssist 悬浮球",
        f"http://127.0.0.1:{port}/ball",
        js_api=api,
        width=76, height=76,
        frameless=True, transparent=True, on_top=True,
    )

    def _toggle(force=None):
        """托盘回调:force=True 显示 / False 隐藏 / None 切换(线程安全)"""
        if force is None:
            api.toggle_panel()
        elif force and not Api.panel_visible:
            api.toggle_panel()
        elif not force and Api.panel_visible:
            api.toggle_panel()

    def _quit():
        api.quit_app()

    start_tray(_toggle, _quit)
    webview.start()
    stop_tray()


if __name__ == "__main__":
    main()
