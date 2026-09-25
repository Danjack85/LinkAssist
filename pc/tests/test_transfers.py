import hashlib
import importlib.util
import asyncio
from pathlib import Path

import pytest
from aiohttp.test_utils import TestClient, TestServer


SERVER_PATH = Path(__file__).parents[1] / "server.py"
SPEC = importlib.util.spec_from_file_location("linkassist_server", SERVER_PATH)
server = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(server)


async def with_client(tmp_path, scenario):
    server.CFG = {"token": "ABC123", "port": 8765, "name": "Test PC"}
    server.LAN_IP = "127.0.0.1"
    server.TRANSFERS.clear()
    server.TRANSFER_TOKENS.clear()
    server.TRANSFER_DIR = str(tmp_path / "transfers")
    server.TRANSFERS_PATH = str(tmp_path / "transfers.jsonl")
    test_client = TestClient(TestServer(server.create_app()))
    await test_client.start_server()
    try:
        await scenario(test_client)
    finally:
        await test_client.close()


async def offer(client, content=b"hello"):
    response = await client.post(
        "/api/transfers/offer?token=ABC123",
        json={
            "name": "sample.txt",
            "size": len(content),
            "mime": "text/plain",
            "sha256": hashlib.sha256(content).hexdigest(),
            "direction": "pc_to_phone",
        },
    )
    assert response.status == 200
    return await response.json()


def test_transfer_list_requires_pairing_token(tmp_path):
    async def scenario(client):
        response = await client.get("/api/transfers")
        assert response.status == 401
    asyncio.run(with_client(tmp_path, scenario))


def test_download_token_can_only_be_used_once(tmp_path):
    async def scenario(client):
        content = b"hello"
        created = await offer(client, content)
        transfer_id = created["transfer"]["id"]
        uploaded = await client.post(
            f"/api/transfers/{transfer_id}/upload?token={created['uploadToken']}",
            data=content,
        )
        assert uploaded.status == 200
        payload = await uploaded.json()
        download_url = payload["transfer"]["downloadUrl"]

        first = await client.get(download_url)
        assert first.status == 200
        assert await first.read() == content

        second = await client.get(download_url)
        assert second.status == 401
    asyncio.run(with_client(tmp_path, scenario))


def test_checksum_mismatch_marks_transfer_failed(tmp_path):
    async def scenario(client):
        created = await offer(client, b"expected")
        transfer_id = created["transfer"]["id"]
        response = await client.post(
            f"/api/transfers/{transfer_id}/upload?token={created['uploadToken']}",
            data=b"differen",
        )
        assert response.status == 400
        assert server.TRANSFERS[transfer_id]["status"] == "failed"
    asyncio.run(with_client(tmp_path, scenario))


def test_safe_transfer_name_removes_path_components():
    assert server.safe_transfer_name("../../folder/report.txt") == "report.txt"
    with pytest.raises(ValueError):
        server.safe_transfer_name("..")
