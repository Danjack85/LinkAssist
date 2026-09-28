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
import json
import os
import socket
import sys
import threading
import time
import urllib.error
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

import webview

try:
    from webview.window import FixPoint
    FIXPOINT_NW = FixPoint.NORTH | FixPoint.WEST   # 缩放时固定左上角,向右/下生长
except Exception:  # 老版本 pywebview 的兜底(NORTH=1, WEST=4)
    FIXPOINT_NW = 5

try:
    webview.settings["ALLOW_DOWNLOADS"] = True
    webview.settings["OPEN_EXTERNAL_LINKS_IN_BROWSER"] = True
except (AttributeError, KeyError, TypeError):
    pass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import server as srv

BALL_SIZE = 84          # 悬浮球窗口逻辑像素(实际按屏幕 DPI 缩放)
BALL_BG = "#243f3c"     # 裁剪生效前的底色,与球体主色一致,避免闪白
MINI_SIZE = (430, 620)  # 迷你面板默认尺寸(逻辑像素)
MINI_LIMITS = (360, 420, 1400, 1600)   # 最小宽/最小高/最大宽/最大高


def _port_in_use(port: int) -> bool:
    """端口是否已被占用。

    只用 bind 测试在 Windows 上不可靠:当另一个程序以 SO_REUSEADDR 绑了
    127.0.0.1:8765 时,本程序 bind 0.0.0.0:8765 仍可能成功,但之后
    127.0.0.1 的连接会被对方截走(网页窗口就打开了别人的页面)。
    因此先探测"能否连上",再补 bind 测试(覆盖端口被保留的情况)。
    """
    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    probe.settimeout(0.35)
    try:
        if probe.connect_ex(("127.0.0.1", port)) == 0:
            return True
    finally:
        probe.close()
    for host in ("0.0.0.0", "127.0.0.1"):
        test = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            test.bind((host, port))
        except OSError:
            return True
        finally:
            test.close()
    return False


def _probe_status(port: int, timeout: float = 0.8) -> str:
    """探测端口上的服务是谁:ours=本程序 / foreign=别的程序 / none=还没有服务"""
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/status", timeout=timeout) as resp:
            raw = resp.read(65536)
    except urllib.error.HTTPError:
        return "foreign"          # 有服务在响应,但不是我们的接口
    except Exception:
        return "none"             # 端口还没有服务在监听
    try:
        data = json.loads(raw.decode("utf-8", "replace"))
    except ValueError:
        return "foreign"
    return "ours" if isinstance(data, dict) and data.get("app") == "linkassist" else "foreign"


def pick_port(preferred: int) -> int:
    """优先使用配置端口;被占用则向后找一个空闲端口"""
    for p in range(preferred, preferred + 10):
        if _port_in_use(p):
            continue
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            s.bind(("0.0.0.0", p))
            return p
        except OSError:
            continue
        finally:
            s.close()
    return preferred


def _show_startup_error(reason):
    """所有候选端口都不可用时,明确报错,而不是打开一个显示别人页面的窗口"""
    message = ("互传助手无法启动本机服务。\n\n"
               f"{reason or '端口不可用'}\n\n"
               "请关闭占用端口的程序后重试,或修改 config.json 里的 port。")
    srv.log("[启动失败]", message.replace("\n", " "))
    if sys.platform == "win32":
        try:
            ctypes.windll.user32.MessageBoxW(0, message, "LinkAssist · 互传助手", 0x10)
        except Exception:
            pass


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


BACKEND_ERROR = {"text": ""}   # start_backend 失败原因,供端口选择循环判断


def start_backend(port: int):
    try:
        srv.LAN_IP = srv.lan_ip()
        threading.Thread(target=srv.discovery_loop, args=(srv.LAN_IP,), daemon=True).start()
        web = srv.web
        web.run_app(srv.create_app(), host="0.0.0.0", port=port, print=lambda *a, **k: None)
    except Exception as e:
        BACKEND_ERROR["text"] = f"端口 {port} 服务启动失败: {e}"
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


def _notify_tray(message, title="互传助手 LinkAssist"):
    """托盘气泡提醒(不支持时静默跳过)"""
    t = _tray
    if t is None:
        return
    try:
        t.notify(message, title)
    except Exception:
        pass


def _hide_to_tray(announce=True):
    """把主窗口收进托盘,服务与手机连接继续在后台运行"""
    w = panel_win
    if w is None:
        return False
    try:
        w.hide()
    except Exception:
        return False
    Api.panel_visible = False
    if announce:
        _notify_tray("互传助手仍在后台运行，手机连接与文件传输不受影响。\n双击托盘图标可重新打开主窗口，右键可退出。")
    return True


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

    def request_close(self):
        """标题栏"关闭"按钮:按设置最小化到托盘或真正退出。

        返回 "hidden"(已收进托盘) 或 "quit"(已退出),供界面提示。
        """
        if not bool(srv.CFG.get("closeToTray", True)):
            self.quit_app()
            return "quit"
        return "hidden" if _hide_to_tray() else "error"

    def hide_to_tray(self):
        """最小化到托盘(标题栏 — 按钮)"""
        return _hide_to_tray()

    def quit_app_confirm(self):
        """设置页里的"退出程序"(界面已二次确认)"""
        self.quit_app()
        return "quit"

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
        """打开/聚焦独立迷你面板(托盘菜单用;悬浮球点击走 toggle_mini)"""
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

    def toggle_mini(self):
        """点悬浮球:迷你面板已打开就关掉,关着就打开。

        返回 True 表示现在处于打开状态。
        """
        w = mini_win
        if w is not None:
            try:
                form = getattr(w, "native", None)
                if form is not None and form.Visible:
                    w.hide()
                    return False
                w.show()
                return True
            except Exception:
                pass   # 窗口状态异常时按新建处理
        return self.open_mini()

    def mini_open(self):
        """迷你面板当前是否显示(悬浮球据此显示开关状态)"""
        w = mini_win
        if w is None:
            return False
        try:
            form = getattr(w, "native", None)
            return bool(form is not None and form.Visible)
        except Exception:
            return False

    def resize_mini(self, width, height, remember=False):
        """迷你面板拖动缩放(逻辑像素,由 pywebview 按 DPI 换算)。

        缩放后做一次边界校正:窗口超出工作区就往屏幕内收,避免拖大之后
        底部输入框跑到屏幕外。remember=True 时把尺寸记进配置。
        """
        w = mini_win
        if w is None:
            return False
        try:
            min_w, min_h, max_w, max_h = MINI_LIMITS
            width = max(min_w, min(int(width), max_w))
            height = max(min_h, min(int(height), max_h))
        except (TypeError, ValueError):
            return False
        try:
            w.resize(width, height, FIXPOINT_NW)
        except Exception as exc:
            srv.log("[迷你面板] 缩放失败:", exc)
            return False
        _clamp_window_into_work_area(w)
        if remember:
            srv.CFG["miniWidth"], srv.CFG["miniHeight"] = width, height
            srv.save_config(srv.CFG)
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
        _remove_pid_file()
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


def _clamp_window_into_work_area(win):
    """把窗口收进屏幕工作区:拖大后若超出右/下边缘,自动往屏幕内平移"""
    form = getattr(win, "native", None)
    if form is None:
        return
    try:
        import clr  # noqa: F401
        from System import Action

        def apply():
            try:
                handle = int(form.Handle.ToInt64())
                left, top, right, bottom = _work_area()
                width, height = int(form.Width), int(form.Height)
                x, y = int(form.Left), int(form.Top)
                nx = min(max(left, x), max(left, right - width))
                ny = min(max(top, y), max(top, bottom - height))
                if (nx, ny) == (x, y):
                    return
                SWP_NOSIZE, SWP_NOZORDER, SWP_NOACTIVATE = 0x0001, 0x0004, 0x0010
                ctypes.windll.user32.SetWindowPos(handle, 0, nx, ny, 0, 0,
                                                  SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE)
            except Exception as exc:
                srv.log("[迷你面板] 边界校正失败:", exc)

        form.Invoke(Action(apply))
    except Exception:
        pass


def _mini_size():
    """迷你面板尺寸:优先上次用户拖拽后的尺寸"""
    min_w, min_h, max_w, max_h = MINI_LIMITS
    try:
        width = int(srv.CFG.get("miniWidth", MINI_SIZE[0]))
        height = int(srv.CFG.get("miniHeight", MINI_SIZE[1]))
    except (TypeError, ValueError):
        width, height = MINI_SIZE
    return max(min_w, min(width, max_w)), max(min_h, min(height, max_h))


def create_mini_window(port):
    """创建独立迷你面板:深色聊天小窗,可拖动缩放,默认贴屏幕右下角"""
    width, height = _mini_size()
    win = webview.create_window(
        "LinkAssist · 迷你面板",
        f"http://127.0.0.1:{port}/?mini=1&app=1",
        js_api=api,
        width=width, height=height, min_size=(MINI_LIMITS[0], MINI_LIMITS[1]),
        frameless=True, on_top=True, shadow=True,
        background_color="#0d1413",
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


def wait_backend(port: int, timeout: float = 12.0):
    """等到端口上的服务确认是我们自己的(返回 True);是别的程序则立即 False"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        state = _probe_status(port)
        if state == "ours":
            return True
        if state == "foreign":
            return False
        time.sleep(0.15)
    return False


# ---------------------------------------------------------------- 单实例(后台运行支持)
def _pid_file_path():
    return os.path.join(srv.BASE, "linkassist.pid")


def _existing_instance_port():
    """已在后台运行(窗口收进了托盘)时返回它的端口,否则 None。

    重新双击 exe 不应开出第二个实例;直接唤回正在后台运行的窗口。
    端口上如果不是我们的服务(陈旧记录/端口被别的程序占用),清掉记录后照常启动。
    """
    try:
        path = _pid_file_path()
        if not os.path.exists(path):
            return None
        with open(path, encoding="utf-8") as fh:
            info = json.load(fh)
        port = int(info.get("port", 0))
        if port < 1 or port > 65535:
            _remove_pid_file()
            return None
        if _probe_status(port) == "ours":
            return port
        _remove_pid_file()
        return None
    except Exception:
        return None


def _write_pid_file(port):
    try:
        with open(_pid_file_path(), "w", encoding="utf-8") as fh:
            json.dump({"pid": os.getpid(), "port": port}, fh)
    except OSError:
        pass


def _remove_pid_file():
    try:
        os.remove(_pid_file_path())
    except OSError:
        pass


def _wake_existing_instance(port) -> bool:
    """请求已有实例显示主窗口;成功则本进程退出"""
    try:
        request = urllib.request.Request(f"http://127.0.0.1:{port}/api/show", data=b"{}", method="POST",
                                         headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=3) as resp:
            return resp.status == 200
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            srv.log("[单实例] 检测到旧版本实例(没有唤回接口),本次改为启动新实例。")
        else:
            srv.log("[单实例] 唤回已有窗口失败: HTTP", exc.code)
        return False
    except Exception as exc:
        srv.log("[单实例] 唤回已有窗口失败:", exc)
        return False


def main():
    global panel_win, PORT
    srv.CFG = srv.load_config()
    # 单实例:已在后台运行时,唤回已有窗口后本进程直接退出(不重复起服务)
    existing = _existing_instance_port()
    if existing and _wake_existing_instance(existing):
        srv.log(f"[单实例] 互传助手已在后台运行(端口 {existing}),已唤回主窗口,本次启动退出。")
        sys.exit(0)
    srv.load_history()
    srv.load_transfers()

    # 选择端口:跳过被其他程序占用的端口;每启动一个都确认"端口上的服务确实是自己"
    # 才创建界面 —— 否则 Windows 的端口共享可能让窗口打开到别的程序的页面上。
    preferred = int(srv.CFG["port"])
    port = None
    last_reason = ""
    for offset in range(10):
        candidate = preferred + offset
        if candidate > 65535:
            break
        if _port_in_use(candidate):
            last_reason = f"端口 {candidate} 已被其他程序占用"
            srv.log(f"[端口] {last_reason},跳过")
            continue
        BACKEND_ERROR["text"] = ""
        threading.Thread(target=start_backend, args=(candidate,), daemon=True).start()
        deadline = time.time() + 12
        state = "none"
        while time.time() < deadline:
            state = _probe_status(candidate)
            if state != "none":
                break
            time.sleep(0.15)
        if state == "ours":
            port = candidate
            break
        if state == "foreign":
            last_reason = f"端口 {candidate} 被其他程序抢先占用"
        else:
            last_reason = BACKEND_ERROR["text"] or f"端口 {candidate} 上服务启动超时"
        srv.log(f"[端口] {last_reason},尝试下一个端口")

    if port is None:
        _show_startup_error(last_reason)
        sys.exit(1)

    srv.CFG["port"] = port
    PORT = port
    # 实际端口通过 UDP 发现(37777)对外公布,手机端连接失败时会自动跟随,无需写回配置
    _write_pid_file(port)
    srv.SHOW_WINDOW = api.show_main
    srv.log(f"[服务] 已确认本机服务就绪: http://127.0.0.1:{port}")
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

    def _on_panel_closing():
        """窗口关闭拦截:默认收进托盘继续在后台运行,避免误关导致手机断连。

        返回 False 取消关闭;设置里关掉"关闭时最小化到托盘"才真正退出。
        """
        if bool(srv.CFG.get("closeToTray", True)):
            _hide_to_tray()
            return False
        threading.Thread(target=api.quit_app, daemon=True).start()
        return True

    try:
        panel_win.events.closing += _on_panel_closing
    except Exception as exc:
        srv.log("[托盘] 关闭拦截注册失败:", exc)
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
