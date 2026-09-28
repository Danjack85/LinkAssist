#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
互传助手 LinkAssist —— 电脑端服务
- 手机端 App 通过 WebSocket (/device?token=配对码) 连接本服务
- 短信验证码 / APP通知 自动推送到网页控制台,可一键复制
- 双向聊天: 控制台 <-> 手机
- 浏览器打开 http://本机IP:8765 即为电脑端控制台
- app.py(桌面版/exe)以库方式复用本文件
"""
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import webbrowser
import itertools
import hashlib
import uuid
import asyncio
import contextlib
import ipaddress
from urllib.parse import quote, urlsplit

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pairing import PairingInvitations, is_lan_ipv4
from updater import UpdateChecker, VERSION, DEFAULT_REPOSITORY, validate_repository

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def log(*args):
    """打包成 --noconsole exe 后没有 stdout,所有输出静默降级"""
    try:
        print(*args, flush=True)
    except Exception:
        pass


try:
    from aiohttp import web, WSMsgType
except ImportError:
    log("缺少 aiohttp,正在自动安装 ...")
    subprocess.run([sys.executable, "-m", "pip", "install", "aiohttp"], check=False)
    from aiohttp import web, WSMsgType

# 目录:打包成 exe 后,只读资源在 _MEIPASS,可写数据(config/历史)在 exe 旁
# LINKASSIST_DATA_DIR 可把可写数据指到别处(测试/便携使用)
if getattr(sys, "frozen", False):
    BASE = os.environ.get("LINKASSIST_DATA_DIR") or os.path.dirname(sys.executable)
    ASSET = getattr(sys, "_MEIPASS", BASE)
else:
    BASE = os.path.realpath(os.path.dirname(os.path.abspath(__file__)))
    ASSET = BASE
    if os.environ.get("LINKASSIST_DATA_DIR"):
        BASE = os.path.realpath(os.environ["LINKASSIST_DATA_DIR"])
        os.makedirs(BASE, exist_ok=True)


def _safe(name: str, root: str = None) -> str:
    """把文件名限制在指定目录内,防止路径穿越"""
    root = os.path.realpath(root or BASE)
    p = os.path.realpath(os.path.join(root, name))
    if os.path.commonpath([p, root]) != root:
        raise ValueError("illegal path")
    return p


STATIC = _safe("static", ASSET)
CONFIG_PATH = _safe("config.json", BASE)
HISTORY_PATH = _safe("messages.jsonl", BASE)

DISCOVER_PORT = 37777          # UDP 配对探测端口(手机自动发现电脑)
DISCOVER_MAGIC = "LINKASSIST_DISCOVER"
MAX_KEEP = 500                 # 内存中最多保留的消息条数
MAX_FILE_SIZE = 512 * 1024 * 1024
TRANSFER_DIR = _safe("transfers", BASE)
TRANSFERS_PATH = _safe("transfers.jsonl", BASE)
APK_DIR = _safe("apk", BASE)   # 手机端自更新:LinkAssist.apk + version.json(构建时发布)

_ids = itertools.count(1)
_lock = threading.Lock()
STATE = {"messages": [], "devices": {}, "uis": set()}   # devices: ws->{id,name}, uis: 网页客户端集合
CFG = {}
LAN_IP = "127.0.0.1"
TRANSFERS = {}
TRANSFER_TOKENS = {}
PAIRING = PairingInvitations()
UPDATE_CHECKER_KEY = web.AppKey("update_checker", UpdateChecker)
ACTIVE_UPLOADS = set()
# 桌面端注册"把主窗口带到前台"的回调;第二次启动 exe 时经本机接口唤回已有窗口
SHOW_WINDOW = None


# ---------------------------------------------------------------- 配置
# 注:实际生效端口通过 UDP 发现(37777)对外公布,手机端连接失败会自动跟随,
#     因此不做配置写回,避免任何写盘引入的风险。


def load_config():
    cfg = {}
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, encoding="utf-8") as f:
                cfg = json.load(f)
        except Exception:
            cfg = {}
    if "token" not in cfg:
        alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        cfg["token"] = "".join(secrets.choice(alphabet) for _ in range(6))
    cfg.setdefault("port", 8765)
    cfg.setdefault("name", socket.gethostname() or "我的电脑")
    cfg.setdefault("auto_open", True)
    cfg.setdefault("autoCheckUpdates", True)
    cfg.setdefault("updateRepository", DEFAULT_REPOSITORY)
    cfg.setdefault("ballEnabled", False)   # 桌面悬浮球默认关闭,可在控制台设置中开启
    cfg.setdefault("closeToTray", True)    # 点关闭按钮默认收进托盘后台继续运行
    if not os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "x", encoding="utf-8") as f:
                json.dump(cfg, f, ensure_ascii=False, indent=2)
        except Exception:
            pass
    return cfg


def load_history():
    if not os.path.exists(HISTORY_PATH):
        return
    try:
        with open(HISTORY_PATH, encoding="utf-8") as f:
            lines = f.readlines()
        for line in lines[-MAX_KEEP:]:
            try:
                STATE["messages"].append(json.loads(line))
            except Exception:
                pass
    except Exception:
        pass


def load_transfers():
    if not os.path.exists(TRANSFERS_PATH):
        os.makedirs(TRANSFER_DIR, exist_ok=True)
        return
    try:
        with open(TRANSFERS_PATH, encoding="utf-8") as f:
            for line in f.readlines()[-MAX_KEEP:]:
                try:
                    item = json.loads(line)
                    TRANSFERS[item["id"]] = item
                except Exception:
                    continue
    except Exception:
        pass


def save_transfer(item):
    TRANSFERS[item["id"]] = item
    try:
        with open(TRANSFERS_PATH, "a", encoding="utf-8") as f:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")
    except Exception:
        pass


def transfer_event(kind, item):
    return {"type": kind, "transfer": item}


def safe_transfer_name(name):
    name = os.path.basename(str(name or "").replace("\\", "/")).strip()
    if not name or name in {".", ".."}:
        raise ValueError("invalid filename")
    return name[:180]


TRANSFER_ID_RE = re.compile(r"[0-9a-f]{32}")


def transfer_or_404(transfer_id):
    """传输 ID 一律限定为服务端生成的 uuid hex,杜绝任何路径拼接注入"""
    if not TRANSFER_ID_RE.fullmatch(str(transfer_id or "")):
        raise web.HTTPNotFound(text="transfer not found")
    item = TRANSFERS.get(transfer_id)
    if not item:
        raise web.HTTPNotFound(text="transfer not found")
    return item


def transfer_bin_path(transfer_id):
    """先把 ID 解析成规范 UUID(非法即 404),再参与路径拼接"""
    try:
        canonical = uuid.UUID(str(transfer_id)).hex
    except (ValueError, AttributeError, TypeError):
        raise web.HTTPNotFound(text="transfer not found")
    return _safe(canonical + ".bin", TRANSFER_DIR)


# ---------------------------------------------------------------- 工具
def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def next_id():
    return next(_ids)


async def broadcast_ui(payload: dict):
    data = json.dumps(payload, ensure_ascii=False)
    for ws in list(STATE["uis"]):
        try:
            await ws.send_str(data)
        except Exception:
            STATE["uis"].discard(ws)


def status_payload():
    infos = list(STATE["devices"].values())
    return {"type": "status",
            "connected": bool(STATE["devices"]),
            "devices": [d["name"] for d in infos],
            "deviceInfos": infos}


async def store_and_broadcast(msg: dict):
    with _lock:
        STATE["messages"].append(msg)
        del STATE["messages"][:-MAX_KEEP]
        try:
            with open(HISTORY_PATH, "a", encoding="utf-8") as f:
                f.write(json.dumps(msg, ensure_ascii=False) + "\n")
        except Exception:
            pass
    await broadcast_ui({"type": "msg", "message": msg})


async def send_to_devices(payload: dict, target_id=None, exclude_id=None):
    data = json.dumps(payload, ensure_ascii=False)
    for d, info in list(STATE["devices"].items()):
        if target_id and info["id"] != target_id:
            continue
        if exclude_id and info["id"] == exclude_id:
            continue
        try:
            await d.send_str(data)
        except Exception:
            STATE["devices"].pop(d, None)


async def broadcast_device_roster():
    infos = list(STATE["devices"].values())
    for ws, current in list(STATE["devices"].items()):
        peers = [d for d in infos if d["id"] != current["id"]]
        try:
            await ws.send_str(json.dumps({"type": "peers", "devices": peers}, ensure_ascii=False))
        except Exception:
            STATE["devices"].pop(ws, None)


def save_config(cfg):
    """CONFIG_PATH 为模块级常量。写临时文件后原子替换,读取端对损坏文件已容错"""
    try:
        data = json.dumps(cfg, ensure_ascii=False, indent=2).encode("utf-8")
        fd, tmp = tempfile.mkstemp(dir=str(BASE), prefix="config-")
        try:
            view = memoryview(data)
            while view:
                view = view[os.write(fd, view):]
        finally:
            os.close(fd)
        os.replace(tmp, CONFIG_PATH)
    except OSError:
        log("[配置] 保存失败,请检查数据目录权限")


# ---------------------------------------------------------------- 路由
CONSOLE_PATHS = {
    "/", "/ball", "/ws", "/api/status", "/api/messages", "/api/send", "/api/clear",
    "/api/pairing", "/api/pairing/refresh", "/api/updates", "/api/updates/check", "/api/settings",
    "/api/show",
}


def _loopback(host):
    if host == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


@web.middleware
async def control_access(request, handler):
    host = urlsplit("http://" + request.host).hostname or ""
    console = request.path in CONSOLE_PATHS or request.path.startswith("/static/")
    if console and (not _loopback(request.remote or "") or not _loopback(host)):
        return web.json_response({"error": "电脑控制台仅允许本机访问；请使用手机 App 扫码连接"}, status=403)
    origin = request.headers.get("Origin")
    if origin and origin != f"{request.scheme}://{request.host}":
        return web.json_response({"error": "不允许跨站请求"}, status=403)
    return await handler(request)


async def response_headers(request, response):
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["X-Frame-Options"] = "DENY"
    if request.path.startswith("/api/"):
        response.headers["Cache-Control"] = "no-store"


def available_hosts():
    hosts = [LAN_IP]
    try:
        hosts += [address[4][0] for address in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)]
    except OSError:
        pass
    return list(dict.fromkeys(h for h in hosts if is_lan_ipv4(h)))


async def api_pairing(request):
    hosts = available_hosts()
    data = {}
    if request.method == "POST":
        try:
            data = await request.json()
            if not isinstance(data, dict):
                raise ValueError()
        except (ValueError, TypeError):
            return web.json_response({"error": "无效的请求"}, status=400)
    host = data.get("host") or request.query.get("host") or (hosts[0] if hosts else "")
    if host not in hosts:
        return web.json_response({"error": "没有可用的局域网地址，请先连接 Wi-Fi 或网线"}, status=409)
    payload = PAIRING.invitation(host, CFG["port"], CFG["name"], refresh=request.method == "POST")
    return web.json_response(dict(payload, hosts=hosts))


async def api_pair(request):
    if request.content_length and request.content_length > 4096:
        return web.json_response({"error": "配对请求过大"}, status=413)
    try:
        body = await request.read()
        if len(body) > 4096:
            raise ValueError("配对请求过大")
        data = json.loads(body)
        if not isinstance(data, dict):
            raise ValueError("配对请求无效")
        PAIRING.redeem(data.get("key"), request.remote or "unknown")
    except PermissionError as exc:
        return web.json_response({"error": str(exc)}, status=429)
    except (ValueError, TypeError) as exc:
        message = "二维码无效、已使用或已过期，请刷新后重扫" if isinstance(exc, json.JSONDecodeError) else str(exc)
        return web.json_response({"error": message}, status=400)
    await broadcast_ui({"type": "pairing_used"})
    return web.json_response({"token": CFG["token"], "name": CFG["name"], "port": CFG["port"]})


async def api_updates(request):
    checker = request.app[UPDATE_CHECKER_KEY]
    return web.json_response(await checker.check(force=True) if request.method == "POST" else checker.snapshot())


async def api_show(request):
    """第二次启动 exe 时唤回已有主窗口(仅本机可调用,由控制台访问限制保护)"""
    callback = SHOW_WINDOW
    if callback is None:
        return web.json_response({"error": "此版本没有可唤出的桌面窗口"}, status=409)
    try:
        await asyncio.to_thread(callback)
    except Exception as exc:
        return web.json_response({"error": str(exc)[:160]}, status=500)
    return web.json_response({"ok": True})


async def api_settings(request):
    try:
        data = await request.json()
        if not isinstance(data, dict):
            raise ValueError("设置格式无效")
        changes = {}
        if "updateRepository" in data:
            changes["updateRepository"] = validate_repository(data["updateRepository"])
        if "autoCheckUpdates" in data:
            if type(data["autoCheckUpdates"]) is not bool:
                raise ValueError("自动检查开关必须是布尔值")
            changes["autoCheckUpdates"] = data["autoCheckUpdates"]
        if "ballEnabled" in data:
            if type(data["ballEnabled"]) is not bool:
                raise ValueError("悬浮球开关必须是布尔值")
            changes["ballEnabled"] = data["ballEnabled"]
        if "closeToTray" in data:
            if type(data["closeToTray"]) is not bool:
                raise ValueError("关闭行为开关必须是布尔值")
            changes["closeToTray"] = data["closeToTray"]
        updated = dict(CFG, **changes)
        save_config(updated)
        repository_changed = updated.get("updateRepository") != CFG.get("updateRepository")
        CFG.update(changes)
        checker = request.app[UPDATE_CHECKER_KEY]
        if repository_changed:
            checker.state.update(status="idle", message="更新源已保存，请检查新版本", checkedAt=None,
                                 releaseUrl="", downloadUrl="", notes="", latestVersion="", sha256="", size=0)
        await checker._publish()
        return web.json_response({"autoCheckUpdates": CFG.get("autoCheckUpdates", True),
                                  "updateRepository": CFG.get("updateRepository", DEFAULT_REPOSITORY),
                                  "ballEnabled": bool(CFG.get("ballEnabled", False)),
                                  "closeToTray": bool(CFG.get("closeToTray", True))})
    except (ValueError, TypeError) as exc:
        return web.json_response({"error": str(exc)}, status=400)
    except OSError:
        return web.json_response({"error": "无法保存设置，请检查程序数据目录权限"}, status=500)


async def index(request):
    resp = web.FileResponse(_safe(os.path.join("static", "index.html"), ASSET))
    resp.headers["Cache-Control"] = "no-cache"
    return resp


async def ball_page(request):
    resp = web.FileResponse(_safe(os.path.join("static", "ball.html"), ASSET))
    resp.headers["Cache-Control"] = "no-cache"
    return resp


async def api_status(request):
    # "app" 用于身份识别:桌面端启动/单实例检测时确认端口上跑的是本程序
    return web.json_response({
        "app": "linkassist",
        "version": VERSION,
        "addr": LAN_IP, "port": CFG["port"], "token": CFG["token"],
        "name": CFG["name"],
        "devices": [d["name"] for d in STATE["devices"].values()],
        "deviceInfos": list(STATE["devices"].values()),
        "messages": len(STATE["messages"]),
        "transports": {"lan": {"available": True, "protocol": "websocket+http"},
                       "bluetooth": {"available": False, "reason": "planned"}},
    })


async def api_messages(request):
    return web.json_response({"messages": STATE["messages"][-300:]},
                             dumps=lambda o: json.dumps(o, ensure_ascii=False))


async def api_history(request):
    """手机端拉取历史(带配对码鉴权),用于断线期间的消息补齐"""
    if request.query.get("token", "") != CFG["token"]:
        return web.json_response({"error": "unauthorized"}, status=401)
    try:
        limit = min(max(int(request.query.get("limit", "200")), 1), MAX_KEEP)
    except ValueError:
        limit = 200
    with _lock:
        items = list(STATE["messages"][-limit:])
    return web.json_response({"messages": items},
                             dumps=lambda o: json.dumps(o, ensure_ascii=False))


async def api_clear(request):
    with _lock:
        STATE["messages"].clear()
        try:
            os.remove(HISTORY_PATH)
        except Exception:
            pass
    await broadcast_ui({"type": "cleared"})
    return web.json_response({"ok": True})


async def api_send(request):
    """POST {"text": "..."} —— 电脑向手机发消息(调试/脚本用)"""
    try:
        data = await request.json()
    except Exception:
        return web.json_response({"error": "bad json"}, status=400)
    text = str(data.get("text") or "").strip()
    target = str(data.get("targetDeviceId") or "")
    if not text or len(text) > 16000:
        return web.json_response({"error": "消息不能为空且不能超过 16000 字"}, status=400)
    if not STATE["devices"] or (target and not any(d["id"] == target for d in STATE["devices"].values())):
        return web.json_response({"error": "目标设备已离线，请连接后重试"}, status=409)
    msg = {"id": next_id(), "type": "chat", "direction": "out", "from": "电脑",
           "text": text, "targetDeviceId": target, "ts": int(time.time() * 1000)}
    await store_and_broadcast(msg)
    await send_to_devices({"type": "chat", "text": text, "ts": msg["ts"]}, target_id=target or None)
    return web.json_response({"ok": True})


def _new_transfer_token(transfer_id, purpose, ttl=900):
    now = time.time()
    for old, info in list(TRANSFER_TOKENS.items()):
        if info["expires"] < now:
            TRANSFER_TOKENS.pop(old, None)
    token = secrets.token_urlsafe(24)
    TRANSFER_TOKENS[token] = {
        "id": transfer_id, "purpose": purpose, "expires": now + ttl,
    }
    return token


def transfer_with_download(item):
    token = _new_transfer_token(item["id"], "download", ttl=3600)
    return dict(item, downloadUrl=f"/api/transfers/{item['id']}/download?token={token}")


async def publish_completed_transfer(item):
    for ws in list(STATE["uis"]):
        try:
            await ws.send_json(transfer_event("file_complete", transfer_with_download(item)))
        except Exception:
            STATE["uis"].discard(ws)
    target = item.get("targetDeviceId")
    for ws, info in list(STATE["devices"].items()):
        if target and info["id"] != target:
            continue
        if item.get("direction") != "pc_to_phone" and info["id"] == item.get("fromDeviceId"):
            continue
        try:
            await ws.send_json(transfer_event("file_offer", transfer_with_download(item)))
        except Exception:
            STATE["devices"].pop(ws, None)


def _check_transfer_token(request, transfer_id, purpose, consume=False):
    token = request.query.get("token", "")
    info = TRANSFER_TOKENS.get(token)
    if not info or info["id"] != transfer_id or info["purpose"] != purpose or info["expires"] < time.time():
        raise web.HTTPUnauthorized(text="invalid or expired transfer token")
    if consume:
        TRANSFER_TOKENS.pop(token, None)


async def api_transfer_offer(request):
    if request.query.get("token", "") != CFG.get("token"):
        raise web.HTTPUnauthorized(text="invalid pairing token")
    try:
        data = await request.json()
        name = safe_transfer_name(data.get("name"))
        size = int(data.get("size", 0))
    except Exception:
        return web.json_response({"error": "invalid transfer metadata"}, status=400)
    if size < 0 or size > MAX_FILE_SIZE:
        return web.json_response({"error": "file too large", "max": MAX_FILE_SIZE}, status=413)
    transfer_id = uuid.uuid4().hex
    item = {
        "id": transfer_id, "name": name, "size": size,
        "mime": str(data.get("mime") or "application/octet-stream")[:120],
        "sha256": str(data.get("sha256") or "").lower(),
        "direction": str(data.get("direction") or "pc_to_phone"),
        "fromDeviceId": str(data.get("fromDeviceId") or "pc"),
        "targetDeviceId": str(data.get("targetDeviceId") or ""),
        "status": "offered", "progress": 0, "created": int(time.time() * 1000),
    }
    upload_token = _new_transfer_token(transfer_id, "upload")
    save_transfer(item)
    await broadcast_ui(transfer_event("file_offer", item))
    return web.json_response({"transfer": item, "uploadToken": upload_token})


async def api_transfer_upload(request):
    transfer_id = request.match_info["id"]
    item = transfer_or_404(transfer_id)
    if item.get("status") != "offered" or transfer_id in ACTIVE_UPLOADS:
        return web.json_response({"error": "传输已经结束或正在上传"}, status=409)
    _check_transfer_token(request, transfer_id, "upload", consume=True)
    ACTIVE_UPLOADS.add(transfer_id)
    item.update(status="uploading", progress=0)
    save_transfer(item)
    os.makedirs(TRANSFER_DIR, exist_ok=True)
    path = _safe(item["id"] + ".bin", TRANSFER_DIR)
    total = 0
    digest = hashlib.sha256()
    tmp_name = None
    try:
        fd, tmp_name = tempfile.mkstemp(dir=str(TRANSFER_DIR), prefix="upload-")
        with os.fdopen(fd, "w+b") as f:
            async for chunk in request.content.iter_chunked(256 * 1024):
                if item.get("status") == "cancelled":
                    raise ValueError("传输已取消")
                total += len(chunk)
                if total > MAX_FILE_SIZE or total > item["size"]:
                    raise web.HTTPRequestEntityTooLarge(max_size=MAX_FILE_SIZE, actual_size=total)
                digest.update(chunk)
                f.write(chunk)
        if item.get("status") == "cancelled":
            raise ValueError("传输已取消")
        if total != item["size"]:
            raise ValueError("size mismatch")
        actual = digest.hexdigest()
        if item.get("sha256") and item["sha256"] != actual:
            raise ValueError("checksum mismatch")
        os.replace(tmp_name, path)
        tmp_name = None
        item.update({"status": "complete", "progress": 100, "sha256": actual,
                     "completed": int(time.time() * 1000)})
        # 手机→电脑方向:自动按原文件名另存一份,便于直接使用
        if item.get("direction") == "phone_to_pc":
            try:
                recv_dir = _safe(os.path.join("transfers", "received"), BASE)
                os.makedirs(recv_dir, exist_ok=True)
                safe_name = safe_transfer_name(item["name"])
                dest = _safe(os.path.join("transfers", "received", safe_name), BASE)
                i = 1
                while os.path.exists(dest):
                    stem, ext = os.path.splitext(safe_name)
                    dest = _safe(os.path.join("transfers", "received", f"{stem}-{i}{ext}"), BASE)
                    i += 1
                shutil.copyfile(path, dest)
                item["savedAs"] = os.path.relpath(dest, BASE)
            except Exception as e:
                log(f"[文件] 原名另存失败: {e}")
        save_transfer(item)
        await publish_completed_transfer(item)
        return web.json_response({"transfer": transfer_with_download(item)})
    except web.HTTPException as exc:
        if item.get("status") != "cancelled":
            item.update(status="failed", error=exc.reason)
        save_transfer(item)
        await broadcast_ui(transfer_event("file_error", item))
        return web.json_response({"error": exc.reason}, status=exc.status)
    except Exception as exc:
        if item.get("status") != "cancelled":
            item.update({"status": "failed", "error": str(exc)})
        save_transfer(item)
        await broadcast_ui(transfer_event("file_error", item))
        return web.json_response({"error": str(exc)}, status=400)
    finally:
        ACTIVE_UPLOADS.discard(transfer_id)
        if tmp_name:
            with contextlib.suppress(OSError):
                os.remove(tmp_name)


async def api_transfer_download(request):
    transfer_id = request.match_info["id"]
    item = transfer_or_404(transfer_id)
    if item.get("status") != "complete":
        raise web.HTTPNotFound(text="transfer not found")
    _check_transfer_token(request, transfer_id, "download", consume=True)
    path = _safe(item["id"] + ".bin", TRANSFER_DIR)
    if not os.path.exists(path):
        raise web.HTTPNotFound(text="file missing")
    return web.FileResponse(path, headers={
        "Content-Disposition": f"attachment; filename*=UTF-8''{quote(item['name'])}"
    })


def _app_meta():
    """读取 apk/version.json + APK 文件大小;不存在返回 None"""
    try:
        vpath = _safe(os.path.join("apk", "version.json"), BASE)
        if not os.path.exists(vpath):
            return None
        with open(vpath, encoding="utf-8") as f:
            meta = json.load(f)
        apk_name = safe_transfer_name(meta.get("file") or "LinkAssist.apk")
        apk_path = _safe(os.path.join("apk", apk_name), BASE)
        if not os.path.exists(apk_path):
            return None
        meta["size"] = os.path.getsize(apk_path)
        meta["path"] = apk_path
        with open(apk_path, "rb") as apk:
            meta["sha256"] = hashlib.file_digest(apk, "sha256").hexdigest()
        return meta
    except Exception:
        return None


async def api_app_version(request):
    if request.query.get("token", "") != CFG.get("token"):
        raise web.HTTPUnauthorized(text="invalid pairing token")
    meta = await asyncio.to_thread(_app_meta)
    if not meta:
        return web.json_response({"error": "no apk published"}, status=404)
    return web.json_response({
        "versionCode": int(meta.get("versionCode", 0)),
        "versionName": str(meta.get("versionName", "")),
        "size": meta["size"],
        "sha256": meta["sha256"],
    })


async def api_app_download(request):
    if request.query.get("token", "") != CFG.get("token"):
        raise web.HTTPUnauthorized(text="invalid pairing token")
    meta = await asyncio.to_thread(_app_meta)
    if not meta:
        raise web.HTTPNotFound(text="no apk published")
    return web.FileResponse(meta["path"], headers={
        "Content-Type": "application/vnd.android.package-archive",
    })


async def api_received(request):
    """消息里图片/视频的在线预览:按原文件名读取 received 目录"""
    if request.query.get("token", "") != CFG.get("token"):
        raise web.HTTPUnauthorized(text="invalid pairing token")
    try:
        name = safe_transfer_name(request.match_info["name"])
    except Exception:
        raise web.HTTPNotFound(text="not found")
    p = _safe(os.path.join("transfers", "received", name), BASE)
    if not os.path.exists(p):
        raise web.HTTPNotFound(text="not found")
    import mimetypes
    ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
    return web.FileResponse(p, headers={"Content-Type": ctype,
                                        "Cache-Control": "private, max-age=3600"})


async def api_transfers(request):
    if request.query.get("token", "") != CFG.get("token"):
        raise web.HTTPUnauthorized(text="invalid pairing token")
    items = []
    for saved in sorted(TRANSFERS.values(), key=lambda x: x.get("created", 0), reverse=True):
        item = dict(saved)
        if item.get("status") == "complete" and os.path.exists(_safe(item["id"] + ".bin", TRANSFER_DIR)):
            token = _new_transfer_token(item["id"], "download", ttl=3600)
            item["downloadUrl"] = f"/api/transfers/{item['id']}/download?token={token}"
        items.append(item)
    return web.json_response({"transfers": items[:300]}, dumps=lambda o: json.dumps(o, ensure_ascii=False))


async def api_transfer_cancel(request):
    if request.query.get("token", "") != CFG.get("token"):
        raise web.HTTPUnauthorized(text="invalid pairing token")
    item = transfer_or_404(request.match_info["id"])
    if item.get("status") == "complete":
        return web.json_response({"error": "传输已经完成，无法取消"}, status=409)
    item.update({"status": "cancelled", "completed": int(time.time() * 1000)})
    for token, info in list(TRANSFER_TOKENS.items()):
        if info["id"] == item["id"]:
            TRANSFER_TOKENS.pop(token, None)
    save_transfer(item)
    await broadcast_ui(transfer_event("file_error", item))
    return web.json_response({"ok": True})


async def ws_device(request):
    """手机端连接入口,需携带配对码"""
    if request.query.get("token", "") != CFG["token"]:
        return web.Response(status=401, text="invalid token")
    wsr = web.WebSocketResponse(heartbeat=25)
    await wsr.prepare(request)
    fallback_id = uuid.uuid4().hex
    STATE["devices"][wsr] = {"id": fallback_id, "name": "手机"}
    log(f"[手机] 已连接 (来自 {request.remote})")
    await broadcast_ui(status_payload())
    await broadcast_device_roster()
    try:
        async for m in wsr:
            if m.type != WSMsgType.TEXT:
                if m.type == WSMsgType.ERROR:
                    break
                continue
            try:
                data = json.loads(m.data)
            except Exception:
                continue
            t = data.get("type")
            ts = int(time.time() * 1000)
            source = STATE["devices"].get(wsr, {"id": fallback_id, "name": "手机"})
            if t == "hello":
                STATE["devices"][wsr] = {
                    "id": str(data.get("deviceId") or fallback_id)[:80],
                    "name": str(data.get("device") or "手机")[:80],
                }
                await broadcast_ui(status_payload())
                await broadcast_device_roster()
            elif t == "sms":
                msg = {"id": next_id(), "type": "sms", "direction": "in",
                       "from": str(data.get("from") or "未知号码"),
                       "body": str(data.get("body") or ""),
                       "code": data.get("code"),
                       "ts": int(data.get("ts") or ts),
                       "fromDeviceId": source["id"], "fromDevice": source["name"]}
                await store_and_broadcast(msg)
            elif t == "notif":
                msg = {"id": next_id(), "type": "notif", "direction": "in",
                       "app": str(data.get("app") or ""),
                       "title": str(data.get("title") or ""),
                       "body": str(data.get("body") or ""),
                       "code": data.get("code"),
                       "ts": int(data.get("ts") or ts),
                       "fromDeviceId": source["id"], "fromDevice": source["name"]}
                await store_and_broadcast(msg)
            elif t == "chat":
                target_id = str(data.get("targetDeviceId") or "")
                file_name = str(data.get("fileName") or "")
                orig_ts = int(data.get("ts") or ts)
                msg = {"id": next_id(), "type": "chat", "direction": "in",
                       "from": source["name"], "fromDeviceId": source["id"],
                       "targetDeviceId": target_id,
                       "text": str(data.get("text") or ""),
                       "fileName": file_name,
                       "ts": orig_ts}
                await store_and_broadcast(msg)
                forward = {"type": "chat", "text": msg["text"], "ts": orig_ts,
                           "from": source["name"], "fromDeviceId": source["id"],
                           "fileName": file_name}
                if target_id:
                    await send_to_devices(forward, target_id=target_id)
                else:
                    # 无定向目标:广播给除发送者外的所有设备(手机↔手机互通)
                    await send_to_devices(forward, exclude_id=source["id"])
    finally:
        STATE["devices"].pop(wsr, None)
        log("[手机] 已断开")
        await broadcast_ui(status_payload())
        await broadcast_device_roster()
    return wsr


async def ws_ui(request):
    """网页控制台连接入口"""
    wsr = web.WebSocketResponse(heartbeat=25)
    await wsr.prepare(request)
    STATE["uis"].add(wsr)
    try:
        # 注意:不要用 **status_payload() 展开,会把 type 覆盖成 status
        init = {"type": "init",
                "connected": bool(STATE["devices"]),
                "devices": [d["name"] for d in STATE["devices"].values()],
                "deviceInfos": list(STATE["devices"].values()),
                "config": {"addr": LAN_IP, "port": CFG["port"], "token": CFG["token"], "name": CFG["name"],
                           "ballEnabled": bool(CFG.get("ballEnabled", False)),
                           "closeToTray": bool(CFG.get("closeToTray", True)),
                           "updateRepository": CFG.get("updateRepository", DEFAULT_REPOSITORY)},
                "transports": {"lan": {"available": True, "protocol": "websocket+http"},
                               "bluetooth": {"available": False, "reason": "planned"}},
                "messages": STATE["messages"][-300:]}
        await wsr.send_str(json.dumps(init, ensure_ascii=False))
        async for m in wsr:
            if m.type != WSMsgType.TEXT:
                continue
            try:
                data = json.loads(m.data)
            except Exception:
                continue
            if not isinstance(data, dict):
                continue
            if data.get("type") == "chat":
                text = str(data.get("text") or "").strip()
                target = str(data.get("targetDeviceId") or "")
                if not text or len(text) > 16000:
                    await wsr.send_json({"type": "error", "error": "消息不能为空且不能超过 16000 字"})
                    continue
                if not STATE["devices"] or (target and not any(d["id"] == target for d in STATE["devices"].values())):
                    await wsr.send_json({"type": "error", "error": "目标设备已离线，消息未发送"})
                    continue
                msg = {"id": next_id(), "type": "chat", "direction": "out",
                       "from": "电脑", "text": text, "targetDeviceId": target, "ts": int(time.time() * 1000)}
                await store_and_broadcast(msg)
                await send_to_devices({"type": "chat", "text": text, "ts": msg["ts"]}, target_id=target or None)
    finally:
        STATE["uis"].discard(wsr)
    return wsr


# ---------------------------------------------------------------- UDP 自动发现
def local_addr_for(target_ip):
    """本机与 target_ip 通信时使用的接口地址(多网卡时避免回复不可达的虚拟接口 IP)"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((target_ip, DISCOVER_PORT))
        return s.getsockname()[0]
    except Exception:
        return ""
    finally:
        s.close()


def discovery_loop(ip):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind(("", DISCOVER_PORT))
    except Exception as e:
        log(f"[发现] UDP 端口 {DISCOVER_PORT} 被占用,自动发现不可用 ({e})")
        return
    log(f"[发现] 配对探测已就绪 (UDP {DISCOVER_PORT})")
    while True:
        try:
            data, addr = s.recvfrom(1024)
            if data.decode("utf-8", "ignore").strip().startswith(DISCOVER_MAGIC):
                reply_ip = local_addr_for(addr[0]) or ip
                reply = json.dumps({"app": "linkassist", "name": CFG["name"],
                                    "host": reply_ip, "port": CFG["port"]}).encode("utf-8")
                s.sendto(reply, addr)
        except Exception:
            time.sleep(1)


# ---------------------------------------------------------------- 应用
async def update_lifecycle(app):
    task = asyncio.create_task(app[UPDATE_CHECKER_KEY].periodic())
    yield
    task.cancel()
    with contextlib.suppress(asyncio.CancelledError):
        await task
    for ws in list(STATE["uis"]) + list(STATE["devices"]):
        await ws.close()


def create_app(auto_updates=True) -> web.Application:
    app = web.Application(middlewares=[control_access])
    app[UPDATE_CHECKER_KEY] = UpdateChecker(CFG, broadcast_ui)
    app.on_response_prepare.append(response_headers)
    if auto_updates:
        app.cleanup_ctx.append(update_lifecycle)
    app.router.add_get("/", index)
    app.router.add_get("/ball", ball_page)
    app.router.add_static("/static/", STATIC)
    app.router.add_get("/api/status", api_status)
    app.router.add_get("/api/pairing", api_pairing)
    app.router.add_post("/api/pairing/refresh", api_pairing)
    app.router.add_post("/api/pair", api_pair)
    app.router.add_get("/api/updates", api_updates)
    app.router.add_post("/api/updates/check", api_updates)
    app.router.add_post("/api/settings", api_settings)
    app.router.add_post("/api/show", api_show)
    app.router.add_get("/api/messages", api_messages)
    app.router.add_get("/api/history", api_history)
    app.router.add_post("/api/clear", api_clear)
    app.router.add_post("/api/send", api_send)
    app.router.add_post("/api/transfers/offer", api_transfer_offer)
    app.router.add_post("/api/transfers/{id}/upload", api_transfer_upload)
    app.router.add_get("/api/transfers/{id}/download", api_transfer_download)
    app.router.add_get("/api/transfers", api_transfers)
    app.router.add_post("/api/transfers/{id}/cancel", api_transfer_cancel)
    app.router.add_get("/api/app/version", api_app_version)
    app.router.add_get("/api/app/download", api_app_download)
    app.router.add_get("/api/received/{name}", api_received)
    app.router.add_get("/device", ws_device)
    app.router.add_get("/ws", ws_ui)
    return app


def banner(ip):
    line = "=" * 52
    log(line)
    log("  互传助手 LinkAssist · 电脑端已启动")
    log(line)
    log(f"  控制台(本机): http://127.0.0.1:{CFG['port']}")
    log("  连接方式:打开本机控制台，使用手机 App 扫描连接二维码")
    log("  手动配对信息仅显示在本机控制台的备用连接区域")
    log(line)
    log("  请保持此窗口运行;关闭即停止服务。Ctrl+C 退出。")
    log(line)


def main():
    global CFG, LAN_IP
    CFG = load_config()
    load_history()
    load_transfers()
    if len(sys.argv) > 1:
        try:
            CFG["port"] = int(sys.argv[1])
        except ValueError:
            pass
    LAN_IP = lan_ip()

    threading.Thread(target=discovery_loop, args=(LAN_IP,), daemon=True).start()
    banner(LAN_IP)
    if CFG.get("auto_open"):
        threading.Timer(1.2, lambda: webbrowser.open(f"http://127.0.0.1:{CFG['port']}")).start()
    try:
        web.run_app(create_app(), host="0.0.0.0", port=CFG["port"], print=lambda *a, **k: None)
    except OSError as e:
        log(f"\n[错误] 端口 {CFG['port']} 无法使用: {e}")
        log("可能已被占用,可运行: python server.py 其他端口")
        sys.exit(1)


if __name__ == "__main__":
    main()
