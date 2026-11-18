#!/usr/bin/env python3
"""Device regression for the persistent translation entry and controller-owned session.

Requires a rooted development emulator with Taku Audio Engine installed and the
Kotoba/BYOK configuration already present. The session checks use the existing
30-second source-audio window, so they finish/reopen/rotate without playing media.
"""
import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "org.agentos.audio"
ACTIVITY = f"{PACKAGE}/.MediaTranslationActivity"
PREFS = "/data/user/0/org.agentos.audio/shared_prefs"
MEDIA_PREFS = f"{PREFS}/media-translation.xml"
LEGACY_PREFS = f"{PREFS}/MediaTranslationActivity.xml"


class Device:
    def __init__(self, adb, serial):
        self.adb = adb
        self.serial = serial

    def run(self, *args, check=True):
        command = [self.adb, "-s", self.serial, *map(str, args)]
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if check and result.returncode != 0:
            raise RuntimeError(f"{' '.join(command)} failed: {result.stdout.strip()}")
        return result.stdout.strip()

    def shell(self, command, check=True):
        return self.run("shell", command, check=check)

    def root(self):
        return self.shell("id -u", check=False) == "0"

    def dump(self):
        self.shell("uiautomator dump /sdcard/taku-overlay-test.xml >/dev/null 2>&1", check=False)
        raw = self.shell("cat /sdcard/taku-overlay-test.xml", check=False)
        if not raw.startswith("<?xml"):
            return None
        return ET.fromstring(raw)

    def find(self, text):
        tree = self.dump()
        if tree is None:
            return None
        for node in tree.iter("node"):
            if node.get("text") == text:
                return node
        return None

    def tap(self, text, scrolls=0):
        for attempt in range(scrolls + 1):
            node = self.find(text)
            if node is not None:
                left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
                self.shell(f"input tap {(left + right) // 2} {top + max(1, (bottom - top) // 4)}")
                return True
            if attempt < scrolls:
                self.shell("input swipe 540 2000 540 450 250")
                time.sleep(0.7)
        return False

    def find_scrolled(self, text, scrolls=2):
        for attempt in range(scrolls + 1):
            node = self.find(text)
            if node is not None:
                return node
            if attempt < scrolls:
                self.shell("input swipe 540 2000 540 450 250")
                time.sleep(0.6)
        return None

    def find_contains(self, text):
        tree = self.dump()
        if tree is None:
            return None
        for node in tree.iter("node"):
            if text in (node.get("text") or ""):
                return node
        return None

    def find_desc(self, desc):
        tree = self.dump()
        if tree is None:
            return None
        for node in tree.iter("node"):
            if node.get("content-desc") == desc:
                return node
        return None

    def tap_desc(self, desc, scrolls=0):
        for attempt in range(scrolls + 1):
            node = self.find_desc(desc)
            if node is not None:
                left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
                self.shell(f"input tap {(left + right) // 2} {top + max(1, (bottom - top) // 4)}")
                return True
            if attempt < scrolls:
                self.shell("input swipe 540 2000 540 450 250")
                time.sleep(0.7)
        return False

    def wait_text(self, text, timeout=10):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.find(text) is not None:
                return True
            time.sleep(0.4)
        return False

    def overlays(self):
        output = self.shell("dumpsys window windows", check=False)
        found = []
        for block in output.split("Window #")[1:]:
            if f"u0 {PACKAGE}" not in block or "ty=APPLICATION_OVERLAY" not in block:
                continue
            window = re.search(r"Window #\d+ Window\{([0-9a-f]+)", "Window #" + block)
            size = re.search(r"\((\d+)x(\d+)\)", block)
            found.append((window.group(1) if window else "?", size.group(0) if size else "?"))
        return found

    def wait_overlays(self, present=True, timeout=5):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if bool(self.overlays()) == present:
                return True
            time.sleep(0.4)
        return False

    def config_present(self):
        output = self.shell("dumpsys window windows", check=False)
        return re.search(r"Window #\d+ Window\{[0-9a-f]+ u0 " + re.escape(PACKAGE + "/.MediaTranslationActivity") + r"\}", output) is not None

    def wait_no_config(self, timeout=5):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if not self.config_present():
                return True
            time.sleep(0.4)
        return False

    def pref_enabled(self):
        def read(path):
            raw = self.shell(f"cat {path}", check=False)
            match = re.search(r'name="overlay" value="(true|false)"', raw)
            return None if match is None else match.group(1) == "true"
        media = read(MEDIA_PREFS)
        legacy = read(LEGACY_PREFS)
        return all(value for value in (media, legacy) if value is not None), media, legacy

    def snapshot_prefs(self):
        snapshot = {}
        for path in (MEDIA_PREFS, LEGACY_PREFS):
            exists = self.shell(f"[ -f {path} ] && echo yes || echo no", check=False) == "yes"
            snapshot[path] = self.shell(f"cat {path}", check=False) if exists else None
        return snapshot

    def restore_prefs(self, snapshot):
        """Restore the exact pre-test XML files, then let HOME re-read them."""
        self.shell(f"am force-stop {PACKAGE}")
        uid = re.search(r"uid:(\d+)", self.shell(f"cmd package list packages -U {PACKAGE}")).group(1)
        for path, content in snapshot.items():
            if content is None:
                self.shell(f"rm -f {path}", check=False)
                continue
            local = f"/tmp/taku-restore-{path.rsplit('/', 1)[-1]}"
            with open(local, "w", encoding="utf-8") as handle:
                handle.write(content)
            self.run("push", local, "/data/local/tmp/taku-restore-pref.xml")
            self.shell(
                f"cp /data/local/tmp/taku-restore-pref.xml {path}; "
                f"chown {uid}:{uid} {path}; restorecon {path}"
            )
        self.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME", check=False)
        time.sleep(3)
        enabled, _, _ = self.pref_enabled()
        return enabled

    def has_active_session(self):
        for _, size in self.overlays():
            if size not in {"(wrapxwrap)", "?"}:
                return True
        return False

    def cleanup_session(self):
        if not self.has_active_session():
            return
        self.open_page("video")
        if self.find_scrolled("翻译进行中", 3) is not None:
            self.tap("Stop translation", scrolls=3)
            time.sleep(2)

    def open_page(self, page):
        if page == "call":
            self.shell(f"am start -n {ACTIVITY} --es page call", check=False)
        else:
            self.shell(f"am start -n {ACTIVITY}", check=False)
        time.sleep(1.0)
        title = "通话翻译 · 实验" if page == "call" else "视频翻译 · 实验"
        deadline = time.time() + 5
        while time.time() < deadline:
            if self.find(title) is not None:
                return
            time.sleep(0.4)

    def ensure_enabled(self):
        self.open_page("video")
        if self.find_scrolled("翻译进行中", 3) is not None:
            raise RuntimeError("active translation session present; refusing to change the overlay")
        self.tap("开启浮窗", scrolls=3)
        time.sleep(1.2)
        enabled, _, _ = self.pref_enabled()
        assert enabled, "overlay preference is not enabled"
        self.shell("input keyevent HOME")
        assert self.wait_overlays(True, 6), "enabled overlay is not visible"

    def write_pref(self, path, value):
        self.shell(f"am force-stop {PACKAGE}")
        raw = self.shell(f"cat {path}")
        if 'name="overlay"' in raw:
            raw = re.sub(r'name="overlay" value="(true|false)"', f'name="overlay" value="{value}"', raw)
        else:
            raw = raw.replace("</map>", f'    <boolean name="overlay" value="{value}" />\n</map>')
        local = "/tmp/taku-overlay-pref.xml"
        with open(local, "w", encoding="utf-8") as handle:
            handle.write(raw)
        self.run("push", local, "/data/local/tmp/taku-overlay-pref.xml")
        uid = re.search(r"uid:(\d+)", self.shell(f"cmd package list packages -U {PACKAGE}")).group(1)
        self.shell(
            f"cp /data/local/tmp/taku-overlay-pref.xml {path}; "
            f"chown {uid}:{uid} {path}; restorecon {path}"
        )


def expect(condition, message):
    if not condition:
        raise AssertionError(message)


def test_preferences(device):
    print("test: preference round-trip", flush=True)
    device.ensure_enabled()

    device.open_page("call")
    assert device.tap("关闭浮窗", scrolls=3), "call page: 关闭浮窗 not found"
    time.sleep(1.5)
    enabled, media, legacy = device.pref_enabled()
    expect(not enabled and media is False and legacy is None, f"call close did not persist: media={media} legacy={legacy}")
    device.shell("input keyevent HOME")
    expect(device.wait_overlays(False, 6), "overlay reappeared after closing from the call page")

    device.open_page("video")
    assert device.find("开启浮窗") is not None, "video page did not read the call-page close"
    assert device.tap("开启浮窗", scrolls=2), "video page: 开启浮窗 not found"
    time.sleep(1.5)
    enabled, media, legacy = device.pref_enabled()
    expect(enabled and media is True and legacy is None, f"video enable did not persist: media={media} legacy={legacy}")
    device.shell("input keyevent HOME")
    expect(device.wait_overlays(True, 6), "overlay did not return after enabling from the video page")

    device.open_page("call")
    expect(device.find_scrolled("关闭浮窗", 3) is not None, "call page did not read the video-page enable")


def test_legacy_off(device):
    print("test: legacy overlay=false", flush=True)
    device.ensure_enabled()
    device.write_pref(LEGACY_PREFS, "false")
    device.shell("am start -n org.chromium.webview_shell/.WebViewBrowserActivity", check=False)
    time.sleep(1)
    device.shell("input keyevent HOME")
    time.sleep(3)
    expect(not device.overlays(), "legacy overlay=false was ignored")

    device.open_page("call")
    assert device.tap("开启浮窗", scrolls=3), "call page: 开启浮窗 not found after legacy=false"
    time.sleep(1.5)
    enabled, media, legacy = device.pref_enabled()
    expect(enabled and media is True and legacy is None, f"legacy migration did not clean up: media={media} legacy={legacy}")
    device.shell("input keyevent HOME")
    expect(device.wait_overlays(True, 6), "overlay did not return after legacy migration")


def test_session(device):
    print("test: controller session lifecycle", flush=True)
    device.ensure_enabled()
    device.open_page("video")
    assert device.tap("启动浮窗翻译", scrolls=2), "start button not found"
    time.sleep(2)
    started = device.overlays()
    print(f"active window: {started}", flush=True)
    expect(len(started) == 1, f"expected one active window, got {started}")
    window_id, size = started[0]
    expect("x" in size and size != "(wrapxwrap)", f"active window is not a session window: {started}")
    device.open_page("video")
    assert device.wait_text("翻译进行中", timeout=10), "active session was not rendered in settings"
    expect(len(device.overlays()) == 1, "active session created a duplicate overlay")

    device.shell(f"am start -n {ACTIVITY}")
    time.sleep(0.6)
    device.shell("input keyevent 4")
    expect(device.wait_no_config(5), "settings Activity was not finished by BACK")
    after = device.overlays()
    expect(after == started, f"session window changed after Activity finish: {started} -> {after}")

    device.open_page("video")
    assert device.wait_text("翻译进行中", timeout=5), "reopened settings lost the session"
    expect(not device.find("启动浮窗翻译"), "start button is enabled while a session is active")

    # Close the first session, then use a fresh session for the rotation check so
    # neither check depends on the other one consuming the 30-second source window.
    assert device.tap("关闭浮窗", scrolls=2), "关闭浮窗 not found during the first session"
    time.sleep(2)
    expect(not device.overlays(), "closing the first session did not remove its window")
    device.shell("input keyevent HOME")
    time.sleep(2.5)

    device.ensure_enabled()
    device.open_page("video")
    assert device.tap("启动浮窗翻译", scrolls=2), "start button not found for the rotation session"
    time.sleep(2)
    rotated_started = device.overlays()
    expect(len(rotated_started) == 1, f"expected one rotation session window, got {rotated_started}")
    device.open_page("video")
    device.shell("settings put system accelerometer_rotation 0")
    device.shell("settings put system user_rotation 1")
    time.sleep(2)
    rotated = device.overlays()
    expect(rotated == rotated_started, f"session window changed after rotation: {rotated_started} -> {rotated}")
    device.shell("settings put system user_rotation 0")
    time.sleep(2)
    device.open_page("video")
    assert device.wait_text("翻译进行中", timeout=5), "rotation lost the session"

    assert device.tap("关闭浮窗", scrolls=2), "关闭浮窗 not found during the rotation session"
    time.sleep(2)
    expect(not device.overlays(), "closing did not remove the session window")
    enabled, media, legacy = device.pref_enabled()
    expect(not enabled and media is False and legacy is None, "closing did not persist overlay=false")
    device.shell("input keyevent HOME")
    expect(device.wait_overlays(False, 6), "overlay returned after closing during a session")


def test_restore_non_default(device):
    print("test: non-default model/request restore", flush=True)
    device.ensure_enabled()
    device.open_page("video")
    if device.find("Whisper · 分段字幕") is None:
        assert device.tap_desc("本次翻译模型", scrolls=2), "model spinner not found"
        time.sleep(0.5)
        assert device.tap("Whisper · 分段字幕", scrolls=1), "Whisper option not found"
        time.sleep(0.8)
    assert device.find("Whisper · 分段字幕") is not None, "Whisper model not selected"

    assert device.tap("启动浮窗翻译", scrolls=2), "start button not found"
    time.sleep(2)
    device.open_page("video")
    assert device.wait_text("翻译进行中", timeout=10), "Whisper session did not become active"

    device.shell(f"am start -n {ACTIVITY}")
    time.sleep(0.6)
    device.shell("input keyevent 4")
    expect(device.wait_no_config(5), "settings Activity was not finished by BACK")
    device.open_page("video")
    assert device.wait_text("翻译进行中", timeout=5), "reopened settings lost the Whisper session"
    assert device.find("Whisper · 分段字幕") is not None, "reopened settings did not restore the actual Whisper model"
    expect(device.find_contains("音频分段上传") is not None, "reopened settings did not restore the Whisper disclosure")
    speech = device.find("播放译音")
    expect(speech is not None and speech.get("checked") == "false", "reopened settings did not restore CAPTIONS output")
    expect(not device.find("启动浮窗翻译"), "start button is enabled while the Whisper session is active")

    # The active video session must keep the video page even when a call page is requested.
    device.shell(f"am start -n {ACTIVITY} --es page call", check=False)
    time.sleep(1.2)
    assert device.find("视频翻译 · 实验") is not None, "active video session was replaced by the call page"
    expect(device.find("通话翻译 · 实验") is None, "call page shown while a video session is active")

    assert device.tap("关闭浮窗", scrolls=2), "关闭浮窗 not found during the Whisper session"
    time.sleep(2)
    expect(not device.overlays(), "closing did not remove the Whisper window")
    device.shell("input keyevent HOME")
    time.sleep(2.5)


def test_voice_restore(device):
    print("test: non-default voice and language restore", flush=True)
    device.ensure_enabled()
    device.open_page("video")
    if device.find("Grok Voice") is None:
        assert device.tap_desc("本次翻译模型", scrolls=2), "model spinner not found"
        time.sleep(0.5)
        assert device.tap("Grok Voice", scrolls=1), "Grok option not found"
        time.sleep(0.8)
    assert device.find("Grok Voice") is not None, "Grok model not selected"

    assert device.tap_desc("目标语言", scrolls=2), "target language spinner not found"
    time.sleep(0.5)
    assert device.tap("日本語", scrolls=1), "Japanese target not found"
    time.sleep(0.8)
    assert device.find("日本語") is not None, "Japanese target not selected"

    assert device.tap_desc("声音", scrolls=2), "voice spinner not found"
    time.sleep(0.5)
    assert device.tap("Ara", scrolls=1), "Ara voice not found"
    time.sleep(0.8)
    assert device.find("Ara") is not None, "Ara voice not selected"

    assert device.tap("启动浮窗翻译", scrolls=2), "start button not found"
    time.sleep(2)
    device.open_page("video")
    assert device.wait_text("翻译进行中", timeout=10), "Grok session did not become active"

    device.shell(f"am start -n {ACTIVITY}")
    time.sleep(0.6)
    device.shell("input keyevent 4")
    expect(device.wait_no_config(5), "settings Activity was not finished by BACK")
    device.open_page("video")
    assert device.wait_text("翻译进行中", timeout=5), "reopened settings lost the Grok session"
    assert device.find("Grok Voice") is not None, "reopened settings did not restore the Grok model"
    assert device.find("Ara") is not None, "reopened settings did not restore the Ara voice"
    assert device.find("日本語") is not None, "reopened settings did not restore the Japanese target"

    assert device.tap("关闭浮窗", scrolls=2), "关闭浮窗 not found during the Grok session"
    time.sleep(2)
    expect(not device.overlays(), "closing did not remove the Grok window")
    device.shell("input keyevent HOME")
    time.sleep(2.5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--skip-session", action="store_true", help="only run preference/legacy checks")
    parser.add_argument("--inject-failure", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    device = Device(args.adb, args.serial)
    if not device.root():
        raise SystemExit("adb root is required for page switching and preference injection")
    snapshot = device.snapshot_prefs()
    if device.has_active_session():
        print("SKIP: an active translation session is present; refusing to alter the device", file=sys.stderr)
        return 3
    auto_rotate = device.shell("settings get system accelerometer_rotation", check=False)
    user_rotation = device.shell("settings get system user_rotation", check=False)
    try:
        test_preferences(device)
        if args.inject_failure:
            raise RuntimeError("injected failure after preference changes")
        test_legacy_off(device)
        if not args.skip_session:
            test_session(device)
            test_restore_non_default(device)
            test_voice_restore(device)
        print("PASS: overlay preference, legacy migration, request restore and controller session lifecycle")
    finally:
        try:
            device.cleanup_session()
        except Exception as error:
            print(f"warning: session cleanup failed: {error}", file=sys.stderr)
        try:
            enabled = device.restore_prefs(snapshot)
            print(f"restored overlay preference: {'on' if enabled else 'off'}", flush=True)
        except Exception as error:
            print(f"warning: preference restore failed: {error}", file=sys.stderr)
        if user_rotation in {"0", "1", "2", "3"}:
            device.shell(f"settings put system user_rotation {user_rotation}", check=False)
        if auto_rotate in {"0", "1"}:
            device.shell(f"settings put system accelerometer_rotation {auto_rotate}", check=False)


if __name__ == "__main__":
    main()
