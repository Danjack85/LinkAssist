#!/usr/bin/env python3
"""Opt-in smoke test for a phone hub. Never connects while imported by pytest."""
import asyncio
import json
import os
import time
from urllib.parse import urlsplit

import aiohttp


async def main():
    target = os.environ.get("LINKASSIST_HUB_URL", "")
    token = os.environ.get("LINKASSIST_HUB_TOKEN", "")
    if not target or not token:
        raise SystemExit("Set LINKASSIST_HUB_URL and LINKASSIST_HUB_TOKEN for an isolated test hub.")
    url = urlsplit(target)
    if url.scheme != "http" or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise SystemExit("LINKASSIST_HUB_URL must be an HTTP host and port without credentials.")
    base = target.rstrip("/")
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as session:
        async with session.ws_connect(base + "/device", params={"token": token}) as ws:
            await ws.send_json({"type": "hello", "device": "Hub smoke test", "deviceId": "test-hub-client"})
            await ws.send_json({"type": "chat", "text": "LinkAssist hub smoke test", "ts": int(time.time() * 1000)})
            await asyncio.sleep(0.2)
            async with session.get(base + "/api/history", params={"token": token, "limit": 50}) as response:
                assert response.status == 200
                history = (await response.json())["messages"]
                assert any(m.get("text", m.get("body")) == "LinkAssist hub smoke test" for m in history)
            async with session.get(base + "/api/history", params={"token": "invalid"}) as response:
                assert response.status == 401
    print("Hub connection, chat history and authentication: PASS")


if __name__ == "__main__":
    asyncio.run(main())
