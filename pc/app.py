#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
互传助手 LinkAssist —— 电脑端桌面应用(exe)
- 主窗口 = 完整控制台(图二):启动即显示,是程序的主界面
- 悬浮球(可选,在控制台设置里开启):圆形置顶小窗;左键打开独立迷你面板,右键打开主窗口
- 迷你面板:点悬浮球弹出的独立小窗,只显示消息与发送框,不打扰主界面
- 系统托盘:左键呼出/隐藏主窗口;右键菜单可显示主窗口 / 打开迷你面板 / 退出
开发运行: python app.py
打包 exe: build.bat (PyInstaller)
"""
import ctypes
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

BALL_SIZE = 84          # 悬浮球窗口逻辑像素(实际按屏幕 DPI 缩放)
BALL_BG = "#243f3c"     # 裁剪生效前的底色,与球体主色一致,避免闪白
MINI_SIZE = (430, 620)  # 迷你面板尺寸(逻辑像素)


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


def start_tray(toggle_cb, quit_cb, mini_cb=None):
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

        def _on_mini(icon, item):
            if mini_cb:
                mini_cb()

        def _on_quit(icon, item):
            quit_cb()

        menu = pystray.Menu(
            pystray.MenuItem("显示 / 隐藏主窗口", _on_toggle, default=True),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("打开迷你面板", _on_mini),
            pystray.MenuItem("显示主窗口", _on_show),
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
        """托盘/标题栏使用:显示或隐藏主窗口"""
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

    def show_main(self):
        """把主窗口带到前台(迷你面板里的"打开主程序")"""
        w = panel_win
        if w is None:
            return False
        try:
            w.show()
        except Exception:
            return False
        Api.panel_visible = True
        return True

    def open_mini(self):
        """点悬浮球:打开/聚焦独立迷你面板,不改动主窗口"""
        global mini_win
        if mini_win is not None:
            try:
                mini_win.show()
                return True
            except Exception:
                mini_win = None
        try:
            mini_win = create_mini_window(PORT)
        except Exception as exc:
            srv.log("[迷你面板] 打开失败:", exc)
            mini_win = None
            return False
        return True

    def close_mini(self):
        """迷你面板自己的关闭按钮"""
        global mini_win
        w, mini_win = mini_win, None
        if w is not None:
            try:
                w.destroy()
            except Exception:
                pass
        return True

    def set_ball_enabled(self, enabled):
        """设置页开关悬浮球;返回实际生效状态"""
        return set_ball_enabled(bool(enabled))

    def ball_enabled(self):
        return ball_win is not None

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
ball_win = None
mini_win = None
PORT = 8765


def _wait_form(win):
    for _ in range(120):
        form = getattr(win, "native", None)
        if form is not None:
            return form
        time.sleep(0.05)
    return None


def _work_area():
    rect = ctypes.wintypes.RECT()
    if ctypes.windll.user32.SystemParametersInfoW(0x0030, 0, ctypes.byref(rect), 0):
        return rect.left, rect.top, rect.right, rect.bottom
    return 0, 0, ctypes.windll.user32.GetSystemMetrics(0), ctypes.windll.user32.GetSystemMetrics(1)


def _anchor_panel(form, right_margin, bottom_margin, hard_size=None):
    """用原生 SetWindowPos 把窗口放到工作区右下角。

    走 WinForms 的 Location/Size 会被 DPI 自动缩放二次放大(pywebview 小窗
    因此被撑成大白块),这里直接用物理像素,绕开该机制。
    """
    try:
        handle = int(form.Handle.ToInt64())
        width = hard_size or int(form.Width)
        height = hard_size or int(form.Height)
        left, top, right, bottom = _work_area()
        x = max(left, right - width - right_margin)
        y = max(top, bottom - height - bottom_margin)
        flags = 0x0010 | 0x0004          # SWP_NOACTIVATE | SWP_NOZORDER
        ctypes.windll.user32.SetWindowPos(handle, 0, int(x), int(y), int(width), int(height), flags)
        form.TopMost = True
        return True
    except Exception as exc:
        srv.log("[窗口] 定位失败:", exc)
        return False


def _shape_round_window(win):
    """把悬浮球窗口裁成圆形并放到屏幕右下角。

    pywebview 的 transparent 在 Windows/WebView2 上会露出白底方块,默认
    min_size=(200,100) 还会把小窗撑大;这里按窗口真实物理尺寸用 SetWindowRgn
    裁剪形状 —— 任何 DPI 下都能得到真正的圆形悬浮球。
    """
    form = _wait_form(win)
    if form is None:
        srv.log("[悬浮球] 未取得原生窗口,跳过圆形裁剪")
        return
    try:
        import clr  # noqa: F401  (pythonnet,随 pywebview 提供)
        from System import Action

        def apply():
            try:
                side = min(int(form.Width), int(form.Height))
                _anchor_panel(form, 40, 156, hard_size=side)
                handle = int(form.Handle.ToInt64())
                region = ctypes.windll.gdi32.CreateEllipticRgn(0, 0, side + 1, side + 1)
                if region and ctypes.windll.user32.SetWindowRgn(handle, region, True):
                    srv.log("[悬浮球] 已应用圆形窗口 (%dx%d)" % (side, side))
                else:
                    srv.log("[悬浮球] 圆形裁剪未生效,悬浮球将保持方形")
            except Exception as exc:
                srv.log("[悬浮球] 圆形裁剪失败:", exc)

        form.Invoke(Action(apply))
    except Exception as exc:
        srv.log("[悬浮球] 无法调度裁剪:", exc)


def create_ball_window(port):
    """创建圆形悬浮球窗口(不依赖窗口透明,改用窗口区域裁剪)"""
    win = webview.create_window(
        "LinkAssist 悬浮球",
        f"http://127.0.0.1:{port}/ball",
        js_api=api,
        width=BALL_SIZE, height=BALL_SIZE, min_size=(BALL_SIZE, BALL_SIZE),
        frameless=True, on_top=True, shadow=False, resizable=False, zoomable=False,
        background_color=BALL_BG,
    )
    threading.Thread(target=_shape_round_window, args=(win,), daemon=True).start()
    return win


def create_mini_window(port):
    """创建独立迷你面板:只显示消息与发送框的小窗口,贴在屏幕右下角"""
    win = webview.create_window(
        "LinkAssist · 迷你面板",
        f"http://127.0.0.1:{port}/?mini=1&app=1",
        js_api=api,
        width=MINI_SIZE[0], height=MINI_SIZE[1], min_size=(340, 420),
        frameless=True, on_top=True, shadow=True,
        background_color="#101716",
    )
    threading.Thread(target=_place_side_window, args=(win,), daemon=True).start()
    return win


def _place_side_window(win):
    """把迷你面板放到屏幕右下角(悬浮球上方),避免遮挡主界面"""
    form = _wait_form(win)
    if form is None:
        return
    try:
        import clr  # noqa: F401
        from System import Action

        def apply():
            _anchor_panel(form, 40, 156)
            form.TopMost = True

        form.Invoke(Action(apply))
    except Exception as exc:
        srv.log("[迷你面板] 无法调度定位:", exc)


def set_ball_enabled(enabled):
    """按需创建/销毁悬浮球窗口(设置开关与托盘共用)"""
    global ball_win
    if enabled and ball_win is None:
        try:
            ball_win = create_ball_window(PORT)
        except Exception as exc:
            srv.log("[悬浮球] 创建失败:", exc)
            ball_win = None
    elif not enabled and ball_win is not None:
        w, ball_win = ball_win, None
        try:
            w.destroy()
        except Exception:
            pass
    return ball_win is not None


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
    global panel_win, PORT
    srv.CFG = srv.load_config()
    srv.load_history()
    srv.load_transfers()
    port = pick_port(int(srv.CFG["port"]))
    srv.CFG["port"] = port
    PORT = port
    # 实际端口通过 UDP 发现(37777)对外公布,手机端连接失败时会自动跟随,无需写回配置

    threading.Thread(target=start_backend, args=(port,), daemon=True).start()
    if not wait_backend(port):
        srv.log("[服务] 后端未就绪,仍继续启动界面(可能稍后自动恢复)")
    srv.log("[网络] 若手机无法连接，请在 Windows 提示中仅允许专用网络访问。")

    # 主窗口就是主程序:启动即显示完整控制台
    panel_win = webview.create_window(
        "LinkAssist · 互传助手",
        f"http://127.0.0.1:{port}/?app=1",
        js_api=api,
        width=1120, height=800, min_size=(420, 600),
        frameless=True, on_top=False,
        background_color="#F5F7F9",
    )
    # 悬浮球仅在设置里开启后出现(默认关闭,避免打扰)
    if srv.CFG.get("ballEnabled"):
        set_ball_enabled(True)

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

    def _mini():
        api.open_mini()

    start_tray(_toggle, _quit, _mini)
    webview.start()
    stop_tray()


if __name__ == "__main__":
    main()
