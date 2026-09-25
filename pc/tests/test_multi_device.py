#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""多设备联机回归测试:两台模拟手机同时连一台电脑

前提:测试服务已由外部启动(环境变量 LINKASSIST_DATA_DIR 指向含测试配置的目录)。
默认连 http://127.0.0.1:8799,配对码 TESTTK;可用环境变量覆盖。
"""
import asyncio
import json
import os
import sys
import time

import aiohttp

PORT = int(os.environ.get("LINKASSIST_TEST_PORT", "8799"))
TOKEN = os.environ.get("LINKASSIST_TEST_TOKEN", "TESTTK")
BASE = f"http://127.0.0.1:{PORT}"


async def device(session, name, device_id):
    ws = await session.ws_connect(f"{BASE}/device?token={TOKEN}")
    await ws.send_str(json.dumps({"type": "hello", "device": name, "deviceId": device_id}))
    return ws


async def run_tests(session):
    ok = True
    # --- 两台设备同时连接 ---
    ws1 = await device(session, "小米14", "devAAA")
    ws2 = await device(session, "Pixel8", "devBBB")
    await asyncio.sleep(0.5)

    async with session.get(f"{BASE}/api/status") as r:
        st = await r.json()
    names = sorted(d["name"] for d in st["deviceInfos"])
    print(f"[1] 两台设备在线: {names}", "OK" if names == ["Pixel8", "小米14"] else "NG")
    ok &= names == ["Pixel8", "小米14"]

    # --- 设备1 发短信验证码(带自己的时间戳) ---
    ts_sent = int(time.time() * 1000) - 12345
    await ws1.send_str(json.dumps({
        "type": "sms", "from": "10086",
        "body": "【测】验证码 246810", "code": "246810", "ts": ts_sent,
    }))
    await asyncio.sleep(0.5)

    # --- 设备2 发聊天 ---
    await ws2.send_str(json.dumps({"type": "chat", "text": "来自Pixel8", "ts": int(time.time() * 1000)}))
    await asyncio.sleep(0.5)

    # --- 电脑回消息(应广播给两台设备) ---
    async with session.post(f"{BASE}/api/send", json={"text": "电脑群发"}) as r:
        assert (await r.json())["ok"]
    got1, got2 = None, None

    async def wait_chat(ws):
        deadline = asyncio.get_event_loop().time() + 4
        while asyncio.get_event_loop().time() < deadline:
            try:
                raw = (await asyncio.wait_for(ws.receive(), 2)).data
            except (asyncio.TimeoutError, Exception):
                return None
            if "电脑群发" in raw:
                return raw
        return None

    got1, got2 = await asyncio.gather(wait_chat(ws1), wait_chat(ws2))
    both = bool(got1 and got2)
    print(f"[2] broadcast to both devices: {'OK' if both else 'NG'}")
    ok &= bool(both)

    # --- 历史接口:鉴权 + 时间戳保真 + fromDeviceId ---
    async with session.get(f"{BASE}/api/history?token={TOKEN}&limit=50") as r:
        hist = (await r.json())["messages"]
    sms = next((m for m in hist if m["type"] == "sms"), None)
    chat2 = next((m for m in hist if m["type"] == "chat" and m.get("fromDeviceId") == "devBBB"), None)
    c1 = sms and sms["ts"] == ts_sent and sms.get("fromDeviceId") == "devAAA"
    c2 = chat2 is not None
    print(f"[3] history: ts-fidelity+deviceId: {'OK' if c1 else 'NG'} / phone-chat: {'OK' if c2 else 'NG'}")
    ok &= bool(c1 and c2)

    # --- 错误配对码被拒 ---
    async with session.get(f"{BASE}/api/history?token=WRONG") as r:
        refused = r.status == 401
    print(f"[4] history auth (wrong token 401): {'OK' if refused else 'NG'}")
    ok &= refused

    # --- 断开一台,状态应更新 ---
    await ws1.close()
    await asyncio.sleep(0.5)
    async with session.get(f"{BASE}/api/status") as r:
        st = await r.json()
    left = [d["name"] for d in st["deviceInfos"]]
    print(f"[5] after disconnect: {left}", "OK" if left == ["Pixel8"] else "NG")
    ok &= left == ["Pixel8"]

    await ws2.close()
    return ok


if __name__ == "__main__":

    async def _run():
        async with aiohttp.ClientSession() as session:
            up = False
            for _ in range(50):
                try:
                    async with session.get(f"{BASE}/api/status", timeout=aiohttp.ClientTimeout(total=0.5)) as r:
                        if r.status == 200:
                            up = True
                            break
                except Exception:
                    await asyncio.sleep(0.2)
            if not up:
                print("server did not start")
                return False
            return await run_tests(session)

    ok = asyncio.run(_run())
    print("PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)
