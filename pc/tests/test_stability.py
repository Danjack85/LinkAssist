"""连接稳定性回归:慢客户端背压、重复设备、脏数据、记录上限、并发连接/断开。"""
import asyncio
import importlib.util
import json
import time
from pathlib import Path

from aiohttp.test_utils import TestClient, TestServer

SPEC = importlib.util.spec_from_file_location("stability_server", Path(__file__).parents[1] / "server.py")
server = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(server)


def _read_tail(path, max_bytes=128 * 1024, max_lines=500):
    """复用服务端的尾部读取(不直接拼接路径做文件读取)"""
    return server._tail_lines(str(path), max_bytes, max_lines)


async def run_server(tmp_path, scenario, prepare=None):
    server.CFG = {"token": "UNITTK", "port": 8765, "name": "测试电脑",
                  "autoCheckUpdates": False, "updateRepository": "Danjack85/LinkAssist"}
    server.STATE = {"messages": [], "devices": {}, "uis": set()}
    server.TRANSFERS.clear()
    server.TRANSFER_TOKENS.clear()
    server.ACTIVE_UPLOADS.clear()
    server.BASE = str(tmp_path)
    server.CONFIG_PATH = str(tmp_path / "config.json")
    server.HISTORY_PATH = str(tmp_path / "messages.jsonl")
    server.LOG_PATH = str(tmp_path / "linkassist.log")
    server.TRANSFER_DIR = str(tmp_path / "transfers")
    server.TRANSFERS_PATH = str(tmp_path / "transfers.jsonl")
    server.LAN_IP = "192.168.20.5"
    if prepare:
        prepare()
    async with TestClient(TestServer(server.create_app(auto_updates=False))) as client:
        await scenario(client)


class FakeWebSocket:
    """只实现广播需要的接口:send_str 可以故意慢/失败"""

    def __init__(self, delay=0.0, fail=False):
        self.delay = delay
        self.fail = fail
        self.sent = 0

    async def send_str(self, data):
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.fail:
            raise ConnectionResetError("client gone")
        self.sent += 1


def test_slow_ui_client_is_dropped_without_blocking(monkeypatch):
    """一个卡死的控制台页面不能拖住整条广播"""
    async def scenario():
        slow = FakeWebSocket(delay=5)
        dead = FakeWebSocket(fail=True)
        fast = FakeWebSocket()
        server.STATE["uis"] = {slow, dead, fast}
        monkeypatch.setattr(server, "SEND_TIMEOUT", 0.05)
        started = time.monotonic()
        await server.broadcast_ui({"type": "msg", "message": {"text": "hi"}})
        elapsed = time.monotonic() - started
        assert elapsed < 1.0, f"broadcast blocked for {elapsed:.2f}s"
        assert fast.sent == 1
        assert slow not in server.STATE["uis"]
        assert dead not in server.STATE["uis"]
        assert fast in server.STATE["uis"]

    asyncio.run(scenario())


def test_duplicate_device_id_replaces_old_connection(tmp_path):
    async def scenario(client):
        first = await client.ws_connect("/device?token=UNITTK")
        await first.send_json({"type": "hello", "deviceId": "same-id", "device": "手机 A"})
        second = await client.ws_connect("/device?token=UNITTK")
        await second.send_json({"type": "hello", "deviceId": "same-id", "device": "手机 A"})
        await asyncio.sleep(0.2)
        ids = [d["id"] for d in server.STATE["devices"].values()]
        assert ids == ["same-id"], "同一设备重复连接后不应出现两份"
        assert len(server.STATE["devices"]) == 1
        closed = None
        for _ in range(10):
            closed = await first.receive(timeout=3)
            if closed.type.name in {"CLOSE", "CLOSING", "CLOSED"}:
                break
        assert closed.type.name in {"CLOSE", "CLOSING", "CLOSED"}, "旧连接应被服务端关闭"
        await second.close()

    asyncio.run(run_server(tmp_path, scenario))


def test_malformed_messages_do_not_drop_connection(tmp_path):
    async def scenario(client):
        ws = await client.ws_connect("/device?token=UNITTK")
        await ws.send_json({"type": "hello", "deviceId": "dev-1", "device": "脏数据测试机"})
        await ws.send_json({"type": "sms", "from": "10086", "body": "验证码 1234", "ts": "not-a-number"})
        await ws.send_json({"type": "chat", "text": "x" * 40000, "ts": None})
        await ws.send_json({"type": "notif", "app": None, "title": None, "body": None})
        await ws.send_json({"type": "chat", "text": "仍然可用", "ts": int(time.time() * 1000)})
        await asyncio.sleep(0.2)
        assert ws.closed is False, "脏数据不应踢掉设备连接"
        stored = [m for m in server.STATE["messages"] if m.get("type") == "chat"]
        assert stored and stored[-1]["text"] == "仍然可用"
        assert all(isinstance(m.get("ts"), int) and m["ts"] > 0 for m in server.STATE["messages"])
        await ws.close()

    asyncio.run(run_server(tmp_path, scenario))


def test_history_rotation_keeps_file_bounded(tmp_path, monkeypatch):
    monkeypatch.setattr(server, "HISTORY_MAX_BYTES", 4096)
    monkeypatch.setattr(server, "HISTORY_KEEP_LINES", 40)
    history = tmp_path / "messages.jsonl"

    def prepare():
        with open(history, "w", encoding="utf-8") as fh:
            for i in range(400):
                fh.write(json.dumps({"id": i, "text": "x" * 80}) + "\n")

    async def scenario():
        server.HISTORY_PATH = str(history)
        lines = [json.dumps({"id": 1000 + i, "text": "y" * 80}) for i in range(120)]
        await asyncio.to_thread(server._append_lines, str(history), lines)
        size = history.stat().st_size
        assert size < 64 * 1024, f"历史文件未轮转,仍有 {size} 字节"
        kept = _read_tail(history, max_lines=200)
        assert 0 < len(kept) <= 60, f"轮转后行数异常: {len(kept)}"
        assert json.loads(kept[-1])["id"] == 1119, "最新的记录应保留"

    asyncio.run(scenario())


def test_transfer_records_are_bounded(monkeypatch):
    async def scenario():
        monkeypatch.setattr(server, "MAX_TRANSFERS", 50)
        server.TRANSFERS.clear()
        server.WRITE_QUEUE = None
        for i in range(120):
            server.save_transfer({"id": f"{i:032x}", "name": f"f{i}", "created": i})
        assert len(server.TRANSFERS) == 50
        assert "00000000000000000000000000000077" in server.TRANSFERS
        assert "00000000000000000000000000000000" not in server.TRANSFERS

    asyncio.run(scenario())


def test_many_devices_connect_send_and_disconnect_cleanly(tmp_path):
    async def scenario(client):
        sockets = []
        for i in range(10):
            ws = await client.ws_connect("/device?token=UNITTK")
            await ws.send_json({"type": "hello", "deviceId": f"dev-{i}", "device": f"手机 {i}"})
            sockets.append(ws)
        await asyncio.sleep(0.3)
        assert len(server.STATE["devices"]) == 10
        await asyncio.gather(*[
            ws.send_json({"type": "chat", "text": f"来自 {i}", "ts": int(time.time() * 1000)})
            for i, ws in enumerate(sockets)
        ])
        await asyncio.sleep(0.4)
        chats = [m for m in server.STATE["messages"] if m.get("type") == "chat"]
        assert len(chats) == 10, f"并发消息丢失: {len(chats)}"
        await asyncio.gather(*[ws.close() for ws in sockets])
        await asyncio.sleep(0.3)
        assert server.STATE["devices"] == {}, "断开后连接表应清空"

    asyncio.run(run_server(tmp_path, scenario))


def test_device_limit_rejects_extra_connections(tmp_path, monkeypatch):
    async def scenario(client):
        monkeypatch.setattr(server, "MAX_DEVICES", 2)
        sockets = []
        for i in range(2):
            ws = await client.ws_connect("/device?token=UNITTK")
            await ws.send_json({"type": "hello", "deviceId": f"limit-{i}", "device": "手机"})
            sockets.append(ws)
        await asyncio.sleep(0.2)
        resp = await client.get("/device?token=UNITTK")
        assert resp.status == 429
        for ws in sockets:
            await ws.close()

    asyncio.run(run_server(tmp_path, scenario))


def test_writer_queue_flushes_on_shutdown(tmp_path):
    """正常关闭时,后台写盘队列应把消息刷到磁盘"""
    async def scenario():
        server.CFG = {"token": "UNITTK", "port": 8765, "name": "测试电脑",
                      "autoCheckUpdates": False, "updateRepository": "Danjack85/LinkAssist"}
        server.STATE = {"messages": [], "devices": {}, "uis": set()}
        server.HISTORY_PATH = str(tmp_path / "messages.jsonl")
        server.BASE = str(tmp_path)
        server.LOG_PATH = str(tmp_path / "linkassist.log")
        async with TestClient(TestServer(server.create_app(auto_updates=False))) as client:
            ws = await client.ws_connect("/device?token=UNITTK")
            await ws.send_json({"type": "hello", "deviceId": "d", "device": "手机"})
            await ws.send_json({"type": "chat", "text": "写盘测试", "ts": int(time.time() * 1000)})
            await asyncio.sleep(0.3)
            await ws.close()
        lines = _read_tail(tmp_path / "messages.jsonl")
        assert any("写盘测试" in line for line in lines), "关闭后仍有未落盘的消息"

    asyncio.run(scenario())
