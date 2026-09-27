"""Release metadata checks. Downloads and installation always require user action."""
import asyncio
import json
import re
import time
from urllib.parse import unquote, urlsplit

import aiohttp

VERSION = "3.0.4"
DEFAULT_REPOSITORY = "Danjack85/LinkAssist"
CHECK_INTERVAL = 12 * 60 * 60
METADATA_LIMIT = 1024 * 1024
REPOSITORY_RE = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9_.-]{1,100}\Z")


def validate_repository(value):
    value = str(value).strip()
    if not REPOSITORY_RE.fullmatch(value) or value.split("/")[1] in {".", ".."}:
        raise ValueError("更新仓库请填写 owner/repository，例如 Danjack85/LinkAssist")
    return value


def version_tuple(value):
    match = re.fullmatch(r"v?(\d+)\.(\d+)(?:\.(\d+))?", str(value))
    if not match:
        raise ValueError("发布版本必须使用稳定版语义版本号，例如 3.0.0")
    return tuple(int(part or 0) for part in match.groups())


def release_asset_url(url, repository, tag=None):
    parts = urlsplit(str(url))
    prefix = f"/{repository}/releases/download/"
    if parts.scheme != "https" or parts.hostname != "github.com" or parts.port not in (None, 443):
        raise ValueError("更新下载地址必须来自该 GitHub 仓库的 HTTPS Release")
    if parts.username or parts.password or parts.query or parts.fragment:
        raise ValueError("更新下载地址包含不允许的字段")
    if not parts.path.startswith(prefix) or ".." in unquote(parts.path).split("/"):
        raise ValueError("更新下载地址不属于当前仓库")
    tail = parts.path[len(prefix):].split("/")
    if len(tail) != 2 or not all(tail) or (tag is not None and unquote(tail[0]) != tag):
        raise ValueError("更新下载地址与发布标签不匹配")
    return str(url)


def parse_release(release, manifest, repository, current=VERSION):
    if not isinstance(release, dict) or not isinstance(manifest, dict):
        raise ValueError("发布信息格式无效")
    if release.get("draft") or release.get("prerelease"):
        raise ValueError("不自动检查草稿或预发布版本")
    tag = str(release.get("tag_name", ""))
    tag_version = version_tuple(tag)
    if manifest.get("schemaVersion") != 1:
        raise ValueError("不支持的更新清单版本")
    latest = str(manifest.get("version", ""))
    if version_tuple(latest) != tag_version:
        raise ValueError("版本清单与 GitHub 发布标签不一致")
    entry = manifest.get("windows")
    if not isinstance(entry, dict):
        raise ValueError("该版本没有 Windows 更新包")
    url = release_asset_url(entry.get("url", ""), repository, tag)
    sha256 = str(entry.get("sha256", "")).lower()
    size = entry.get("size")
    if not re.fullmatch(r"[0-9a-f]{64}", sha256):
        raise ValueError("更新清单缺少有效 SHA-256 校验值")
    if type(size) is not int or not 0 < size <= 2 * 1024 * 1024 * 1024:
        raise ValueError("更新包大小无效")
    asset = next((a for a in release.get("assets", []) if a.get("browser_download_url") == url), None)
    if not asset or asset.get("size") != size or asset.get("name") != entry.get("file"):
        raise ValueError("更新包与 GitHub Release 附件不匹配")
    available = tag_version > version_tuple(current)
    return {
        "latestVersion": latest,
        "status": "available" if available else "up_to_date",
        "message": f"发现新版本 {latest}，下载前请查看更新说明" if available else "当前已是最新稳定版本",
        "releaseUrl": f"https://github.com/{repository}/releases/tag/{tag}",
        "downloadUrl": url, "sha256": sha256, "size": size,
        "notes": str(manifest.get("notes") or release.get("body") or "")[:20000],
    }


class UpdateChecker:
    def __init__(self, settings, publish=None, current=VERSION):
        self.settings = settings
        self.publish = publish
        self.current = current
        self.lock = asyncio.Lock()
        self.state = {
            "currentVersion": current, "latestVersion": "", "status": "idle",
            "message": "尚未检查更新", "checkedAt": None,
            "releaseUrl": "", "downloadUrl": "", "sha256": "", "size": 0, "notes": "",
        }

    def snapshot(self):
        return dict(self.state, enabled=self.settings.get("autoCheckUpdates", True),
                    repository=self.settings.get("updateRepository", DEFAULT_REPOSITORY))

    async def _publish(self):
        if self.publish:
            await self.publish({"type": "update", "update": self.snapshot()})

    async def _json(self, session, url, asset=False, accept="application/vnd.github+json"):
        for _ in range(5):
            async with session.get(url, allow_redirects=False,
                                   headers={"Accept": accept}) as response:
                if response.status in (301, 302, 303, 307, 308):
                    location = response.headers.get("Location", "")
                    parts = urlsplit(location)
                    allowed = {"github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"}
                    if (not asset or parts.scheme != "https" or parts.hostname not in allowed
                            or parts.username or parts.password or parts.port not in (None, 443)):
                        raise ValueError("更新服务器返回了非受信任的重定向")
                    url = location
                    continue
                if response.status == 404:
                    raise ValueError("仓库是私有仓库或尚无 Release；匿名更新检查需要公开的发布仓库")
                if response.status in (403, 429):
                    raise ValueError("GitHub 暂时限制请求，请稍后再试")
                if response.status != 200:
                    raise ValueError(f"GitHub 返回 HTTP {response.status}")
                content = bytearray()
                async for block in response.content.iter_chunked(64 * 1024):
                    content.extend(block)
                    if len(content) > METADATA_LIMIT:
                        raise ValueError("更新元数据超过大小限制")
                return json.loads(content.decode("utf-8"))
        raise ValueError("更新服务器重定向次数过多")

    async def check(self, force=False):
        if self.lock.locked():
            return self.snapshot()
        async with self.lock:
            if not force and not self.settings.get("autoCheckUpdates", True):
                return self.snapshot()
            if not force and self.state["checkedAt"] and time.time() * 1000 - self.state["checkedAt"] < CHECK_INTERVAL * 1000:
                return self.snapshot()
            repo = self.settings.get("updateRepository", DEFAULT_REPOSITORY)
            self.state.update(status="checking", message="正在检查 GitHub Releases…",
                              releaseUrl="", downloadUrl="", notes="", latestVersion="", sha256="", size=0)
            await self._publish()
            try:
                repo = validate_repository(repo)
                timeout = aiohttp.ClientTimeout(total=25, connect=6)
                async with aiohttp.ClientSession(timeout=timeout, trust_env=False,
                                               headers={"User-Agent": f"LinkAssist/{self.current}",
                                                        "Accept": "application/vnd.github+json"}) as session:
                    release = await self._json(session, f"https://api.github.com/repos/{repo}/releases/latest")
                    if not isinstance(release, dict):
                        raise ValueError("GitHub 发布信息格式无效")
                    asset = next((a for a in release.get("assets", []) if a.get("name") == "linkassist-update.json"), None)
                    if not asset:
                        raise ValueError("该 Release 尚未上传 linkassist-update.json 更新清单")
                    fallback = release_asset_url(asset.get("browser_download_url", ""), repo, release.get("tag_name"))
                    # 清单优先经 api.github.com 资产接口获取;部分网络到不了 github.com 主站
                    try:
                        asset_id = int(asset.get("id"))
                    except (TypeError, ValueError):
                        asset_id = None
                    primary = f"https://api.github.com/repos/{repo}/releases/assets/{asset_id}" if asset_id else None
                    manifest = None
                    for url in ([primary] if primary else []) + [fallback]:
                        try:
                            manifest = await self._json(session, url, asset=True, accept="application/octet-stream")
                            break
                        except (aiohttp.ClientError, asyncio.TimeoutError, OSError) as network_error:
                            if url == fallback:
                                raise
                            last_error = network_error
                    if manifest is None:
                        raise last_error
                    result = parse_release(release, manifest, repo, self.current)
                if repo == self.settings.get("updateRepository", DEFAULT_REPOSITORY):
                    self.state.update(result)
                else:
                    self.state.update(status="idle", message="更新源已改变，请重新检查")
            except (ValueError, KeyError, TypeError, StopIteration) as exc:
                self.state.update(status="error", message=str(exc)[:240])
            except (aiohttp.ClientError, asyncio.TimeoutError, OSError):
                self.state.update(status="error", message="暂时无法访问 GitHub，请检查网络；局域网传输不受影响")
            self.state["checkedAt"] = int(time.time() * 1000)
            await self._publish()
            return self.snapshot()

    async def periodic(self):
        await asyncio.sleep(6)
        while True:
            try:
                await self.check()
            except asyncio.CancelledError:
                raise
            except Exception:
                self.state.update(status="error", message="更新检查未完成，稍后自动重试")
            await asyncio.sleep(60)
