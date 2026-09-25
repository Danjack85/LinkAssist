import asyncio
import copy
import importlib.util
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[1]))
from updater import UpdateChecker, parse_release, release_asset_url, validate_repository, version_tuple

REPO = "sample/LinkAssist"
TAG = "v3.1.0"
URL = f"https://github.com/{REPO}/releases/download/{TAG}/LinkAssist-windows.zip"


def fixture_release():
    release = {"tag_name": TAG, "draft": False, "prerelease": False,
               "assets": [{"name": "LinkAssist-windows.zip", "browser_download_url": URL, "size": 100}]}
    manifest = {"schemaVersion": 1, "version": "3.1.0", "notes": "New QR pairing",
                "windows": {"file": "LinkAssist-windows.zip", "url": URL, "size": 100, "sha256": "ab" * 32}}
    return release, manifest


def test_numeric_version_comparison():
    assert version_tuple("v3.10.0") > version_tuple("3.9.0")
    assert version_tuple("2.2") == (2, 2, 0)
    for version in ("3.0.0-beta", "latest", "", "3.0.0/evil"):
        with pytest.raises(ValueError):
            version_tuple(version)


@pytest.mark.parametrize("repo", ["x/y", "Danjack85/LinkAssist", "a-b/r_v.1"])
def test_valid_repository(repo):
    assert validate_repository(repo) == repo


@pytest.mark.parametrize("repo", ["https://github.com/x/y", "x/y/z", "x/..", "../y", "a/b?", "", "x y/z"])
def test_invalid_repository(repo):
    with pytest.raises(ValueError):
        validate_repository(repo)


@pytest.mark.parametrize("url", [
    "http://github.com/sample/LinkAssist/releases/download/v3.1.0/a.zip",
    "https://evil.example/sample/LinkAssist/releases/download/v3.1.0/a.zip",
    "https://github.com/other/repo/releases/download/v3.1.0/a.zip",
    "https://user@github.com/sample/LinkAssist/releases/download/v3.1.0/a.zip",
    "https://github.com/sample/LinkAssist/releases/download/v3.0.0/a.zip",
    "https://github.com/sample/LinkAssist/releases/download/v3.1.0/../a.zip",
    "https://github.com/sample/LinkAssist/releases/download/v3.1.0/a.zip?token=anything",
])
def test_untrusted_download_urls_rejected(url):
    with pytest.raises(ValueError):
        release_asset_url(url, REPO, TAG)


def test_update_available_and_current():
    release, manifest = fixture_release()
    result = parse_release(release, manifest, REPO, "3.0.0")
    assert result["status"] == "available"
    assert result["downloadUrl"] == URL
    assert parse_release(release, manifest, REPO, "3.1.0")["status"] == "up_to_date"
    assert parse_release(release, manifest, REPO, "3.2.0")["status"] == "up_to_date"


def test_manifest_must_match_release_assets():
    release, manifest = fixture_release()
    for field, bad in [("sha256", ""), ("size", -1), ("size", 101), ("file", "different.zip")]:
        broken = copy.deepcopy(manifest)
        broken["windows"][field] = bad
        with pytest.raises(ValueError):
            parse_release(release, broken, REPO)
    manifest["version"] = "3.2.0"
    with pytest.raises(ValueError):
        parse_release(release, manifest, REPO)


def test_disabled_automatic_check_does_not_call_network():
    async def scenario():
        checker = UpdateChecker({"autoCheckUpdates": False})
        async def fail_network(*args, **kwargs):
            raise AssertionError("Network request should not run")
        checker._json = fail_network
        assert (await checker.check())["status"] == "idle"
    asyncio.run(scenario())


def test_private_repository_is_reported_and_never_false_latest():
    async def scenario():
        events = []
        async def publish(event):
            events.append(event)
        checker = UpdateChecker({"autoCheckUpdates": True}, publish)
        async def fail_network(*args, **kwargs):
            raise ValueError("仓库是私有仓库或尚无 Release")
        checker._json = fail_network
        result = await checker.check(force=True)
        assert result["status"] == "error"
        assert result["checkedAt"]
        assert result["downloadUrl"] == ""
        assert "私有" in result["message"]
        assert [event["update"]["status"] for event in events] == ["checking", "error"]
    asyncio.run(scenario())


def test_valid_mock_release_check():
    async def scenario():
        checker = UpdateChecker({"autoCheckUpdates": True, "updateRepository": REPO})
        release, manifest = fixture_release()
        manifest_url = f"https://github.com/{REPO}/releases/download/{TAG}/linkassist-update.json"
        release["assets"].append({"name": "linkassist-update.json", "browser_download_url": manifest_url})
        async def metadata(session, url, asset=False):
            return manifest if asset else release
        checker._json = metadata
        result = await checker.check(force=True)
        assert result["status"] == "available"
        assert result["latestVersion"] == "3.1.0"
    asyncio.run(scenario())
