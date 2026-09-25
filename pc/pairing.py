"""Short-lived invitations; saved device credentials are never embedded in QR codes."""
import io
import ipaddress
import secrets
import time
from urllib.parse import urlencode

import qrcode
import qrcode.image.svg


PRIVATE_NETWORKS = tuple(ipaddress.ip_network(n) for n in (
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16",
))


def is_lan_ipv4(host):
    try:
        address = ipaddress.ip_address(host)
        return isinstance(address, ipaddress.IPv4Address) and any(address in net for net in PRIVATE_NETWORKS)
    except ValueError:
        return False


class PairingInvitations:
    def __init__(self, ttl=300, clock=time.time):
        self.ttl = ttl
        self.clock = clock
        self.sessions = {}
        self.attempts = {}

    def invitation(self, host, port, name, refresh=False):
        if not is_lan_ipv4(host):
            raise ValueError("请选择当前电脑的局域网 IPv4 地址")
        now = self.clock()
        self.sessions = {h: s for h, s in self.sessions.items() if s["expiresAt"] > now * 1000}
        current = self.sessions.get(host)
        if refresh or not current:
            key = secrets.token_urlsafe(32)
            uri = "linkassist://pair?" + urlencode({
                "v": 1, "host": host, "port": port, "key": key,
                "name": str(name)[:80], "kind": "pc",
            })
            qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=4, box_size=6)
            qr.add_data(uri)
            qr.make(fit=True)
            out = io.BytesIO()
            qr.make_image(image_factory=qrcode.image.svg.SvgPathImage).save(out)
            current = {
                "key": key, "uri": uri, "qrSvg": out.getvalue().decode("utf-8"),
                "expiresAt": int((now + self.ttl) * 1000),
                "host": host, "port": port, "name": str(name)[:80],
            }
            self.sessions[host] = current
        return {k: v for k, v in current.items() if k != "key"}

    def redeem(self, key, remote):
        now = self.clock()
        self.attempts = {ip: item for ip, item in self.attempts.items() if item[0] > now - 60}
        started, count = self.attempts.get(remote, (now, 0))
        if count >= 8:
            raise PermissionError("尝试过于频繁，请稍后重新扫码")
        if len(self.attempts) >= 1024 and remote not in self.attempts:
            raise PermissionError("配对繁忙，请稍后再试")
        self.attempts[remote] = (started, count + 1)
        if not isinstance(key, str) or not 20 <= len(key) <= 128 or not key.isascii():
            raise ValueError("二维码无效、已使用或已过期，请刷新后重扫")
        for host, session in list(self.sessions.items()):
            if session["expiresAt"] <= now * 1000:
                del self.sessions[host]
                continue
            if secrets.compare_digest(key, session["key"]):
                del self.sessions[host]
                self.attempts.pop(remote, None)
                return host
        raise ValueError("二维码无效、已使用或已过期，请刷新后重扫")
