import asyncio
import hashlib
import importlib.util
import json
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

import pytest
from aiohttp.test_utils import TestClient, TestServer, make_mocked_request

SPEC = importlib.util.spec_from_file_location("pairing_test_server", Path(__file__).parents[1] / "server.py")
server = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(server)
from pairing import PairingInvitations, is_lan_ipv4


async def run_isolated(tmp_path, scenario):
    server.CFG = {"token": "UNITTK", "port": 8765, "name": "测试电脑", "autoCheckUpdates": False}
    server.STATE = {"messages": [], "devices": {}, "uis": set()}
    server.TRANSFERS.clear()
    server.TRANSFER_TOKENS.clear()
    server.ACTIVE_UPLOADS.clear()
    server.PAIRING = PairingInvitations()
    server.BASE = str(tmp_path)
    server.CONFIG_PATH = str(tmp_path / "config.json")
    server.HISTORY_PATH = str(tmp_path / "messages.jsonl")
    server.TRANSFER_DIR = str(tmp_path / "transfers")
    server.TRANSFERS_PATH = str(tmp_path / "transfers.jsonl")
    server.LAN_IP = "192.168.20.5"
    async with TestClient(TestServer(server.create_app(auto_updates=False))) as client:
        await scenario(client)


@pytest.mark.parametrize("host, expected", [
    ("192.168.1.2", True), ("10.0.2.2", True), ("172.16.2.3", True),
    ("172.32.0.1", False), ("8.8.8.8", False), ("127.0.0.1", False),
    ("localhost", False), ("100.64.0.1", False), ("::1", False), ("host@evil", False),
])
def test_qr_only_targets_lan(host, expected):
    assert is_lan_ipv4(host) is expected


def test_pairing_single_use_refresh_and_expiry():
    clock = [100.0]
    manager = PairingInvitations(clock=lambda: clock[0])
    first = manager.invitation("192.168.2.3", 8765, "我的电脑 & phone")
    query = parse_qs(urlsplit(first["uri"]).query)
    assert query["name"] == ["我的电脑 & phone"]
    assert "UNITTK" not in first["uri"]
    assert "<svg" in first["qrSvg"]
    key = query["key"][0]
    assert manager.invitation("192.168.2.3", 8765, "same")["uri"] == first["uri"]
    manager.redeem(key, "192.168.2.4")
    with pytest.raises(ValueError):
        manager.redeem(key, "192.168.2.4")
    new = manager.invitation("192.168.2.3", 8765, "PC", refresh=True)
    clock[0] += 301
    with pytest.raises(ValueError):
        manager.redeem(parse_qs(urlsplit(new["uri"]).query)["key"][0], "192.168.2.4")


def test_pairing_rate_limit():
    manager = PairingInvitations()
    for _ in range(8):
        with pytest.raises(ValueError):
            manager.redeem("wrong", "192.168.2.4")
    with pytest.raises(PermissionError):
        manager.redeem("wrong", "192.168.2.4")


def test_api_pairing_and_replay(tmp_path):
    async def scenario(client):
        response = await client.get("/api/pairing")
        assert response.status == 200
        invitation = await response.json()
        assert invitation["expiresAt"] > 0
        key = parse_qs(urlsplit(invitation["uri"]).query)["key"][0]
        response = await client.post("/api/pair", json={"key": key, "deviceName": "Test Phone"})
        assert response.status == 200
        assert (await response.json())["token"] == "UNITTK"
        response = await client.post("/api/pair", json={"key": key})
        assert response.status == 400
        assert (await client.get("/api/pairing?host=8.8.8.8")).status == 409
    asyncio.run(run_isolated(tmp_path, scenario))


def test_console_host_and_origin_protection(tmp_path):
    async def scenario(client):
        assert (await client.get("/api/status", headers={"Host": "evil.example"})).status == 403
        assert (await client.post("/api/clear", headers={"Origin": "https://evil.example"})).status == 403
        assert (await client.get("/api/status")).status == 200
        assert (await client.post("/api/send", json={"text": "offline"})).status == 409
    asyncio.run(run_isolated(tmp_path, scenario))


def test_settings_validation_and_persistence(tmp_path):
    async def scenario(client):
        assert (await client.post("/api/settings", json={"updateRepository": "https://evil.test"})).status == 400
        assert (await client.post("/api/settings", json={"autoCheckUpdates": "false"})).status == 400
        response = await client.post("/api/settings", json={"updateRepository": "test/releases", "autoCheckUpdates": False})
        assert response.status == 200
        saved = json.loads(Path(server.CONFIG_PATH).read_text())
        assert saved["token"] == "UNITTK"
        assert saved["updateRepository"] == "test/releases"
        state = await (await client.get("/api/updates")).json()
        assert state["repository"] == "test/releases"
        assert state["enabled"] is False
    asyncio.run(run_isolated(tmp_path, scenario))


async def next_kind(ws, kind):
    for _ in range(12):
        event = await ws.receive_json(timeout=2)
        if event.get("type") == kind:
            return event
    raise AssertionError(f"Missing event {kind}")


def test_two_devices_get_independent_download_tokens(tmp_path):
    async def scenario(client):
        first = await client.ws_connect("/device?token=UNITTK")
        second = await client.ws_connect("/device?token=UNITTK")
        await first.send_json({"type": "hello", "deviceId": "one", "device": "Phone 1"})
        await second.send_json({"type": "hello", "deviceId": "two", "device": "Phone 2"})
        content = "双端文件校验".encode()
        offer = await (await client.post("/api/transfers/offer?token=UNITTK", json={
            "name": "hello.txt", "size": len(content), "sha256": hashlib.sha256(content).hexdigest(),
        })).json()
        ident = offer["transfer"]["id"]
        response = await client.post(f"/api/transfers/{ident}/upload?token={offer['uploadToken']}", data=content)
        assert response.status == 200
        returned = (await response.json())["transfer"]["downloadUrl"]
        one = (await next_kind(first, "file_offer"))["transfer"]["downloadUrl"]
        two = (await next_kind(second, "file_offer"))["transfer"]["downloadUrl"]
        assert len({one, two, returned}) == 3
        for link in (one, two, returned):
            download = await client.get(link)
            assert download.status == 200
            assert await download.read() == content
            assert (await client.get(link)).status == 401
        assert (await client.post(f"/api/transfers/{ident}/cancel?token=UNITTK")).status == 409
        await first.close()
        await second.close()
    asyncio.run(run_isolated(tmp_path, scenario))


def test_cancelled_offer_cannot_upload(tmp_path):
    async def scenario(client):
        offer = await (await client.post("/api/transfers/offer?token=UNITTK", json={"name": "cancel.txt", "size": 1})).json()
        ident = offer["transfer"]["id"]
        assert (await client.post(f"/api/transfers/{ident}/cancel?token=UNITTK")).status == 200
        assert (await client.post(f"/api/transfers/{ident}/upload?token={offer['uploadToken']}", data=b"a")).status == 409
        assert server.TRANSFERS[ident]["status"] == "cancelled"
    asyncio.run(run_isolated(tmp_path, scenario))


def test_targeted_chat_reaches_selected_device(tmp_path):
    async def scenario(client):
        first = await client.ws_connect("/device?token=UNITTK")
        await first.send_json({"type": "hello", "deviceId": "one"})
        second = await client.ws_connect("/device?token=UNITTK")
        await second.send_json({"type": "hello", "deviceId": "two"})
        for _ in range(30):
            if {d["id"] for d in server.STATE["devices"].values()} == {"one", "two"}:
                break
            await asyncio.sleep(0.01)
        response = await client.post("/api/send", json={"text": "only one", "targetDeviceId": "one"})
        assert response.status == 200
        assert (await next_kind(first, "chat"))["text"] == "only one"
        assert (await client.post("/api/send", json={"text": "invalid", "targetDeviceId": "missing"})).status == 409
        while True:
            try:
                event = await second.receive_json(timeout=0.1)
                assert event["type"] != "chat"
            except asyncio.TimeoutError:
                break
        await first.close()
        await second.close()
    asyncio.run(run_isolated(tmp_path, scenario))
