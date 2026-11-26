#!/usr/bin/env python3
from argparse import Namespace
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/push-l3-apk.py"
SPEC = importlib.util.spec_from_file_location("push_l3_apk", SCRIPT)
tool = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tool)


class CliTest(unittest.TestCase):
    def invoke(self, *args):
        return subprocess.run([sys.executable, SCRIPT, *args], capture_output=True, text=True)

    def test_dry_run_without_sdk_or_device(self):
        for component in tool.COMPONENTS:
            args = (component, "--serial", "emulator-5554", "--backup-dir", "unused", "--dry-run")
            push = self.invoke(*args)
            rollback = self.invoke(*args, "--rollback")
            self.assertEqual(push.returncode, 0, push.stderr)
            self.assertIn("Would build", push.stdout)
            self.assertEqual(rollback.returncode, 0, rollback.stderr)
            self.assertIn("Would verify", rollback.stdout)

    def test_validates_arguments_before_work(self):
        for args in [
            ("agent-app", "--backup-dir", "test"),
            ("invalid", "--serial", "emulator-5554", "--backup-dir", "test"),
            ("assistant", "--serial=--help", "--backup-dir", "test"),
            ("assistant", "--serial", "emulator-5554"),
        ]:
            self.assertEqual(self.invoke(*args, "--dry-run").returncode, 2)


class DeploymentTest(unittest.TestCase):
    package = "org.agentos.assistant"
    serial = "host-fixture"

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.backup = self.root / "backup"
        self.candidate = self.root / tool.COMPONENTS["assistant"][2]
        self.candidate.parent.mkdir(parents=True)
        self.candidate.write_text("81")
        self.installed = "80"
        self.operations = []
        self.split = False
        self.invalid_signature = False
        self.fail_install = False
        for override in [patch.object(tool, "ROOT", self.root),
                         patch.object(tool, "android_tools", return_value=("fake-adb", Path("/sdk"))),
                         patch.object(tool, "run", side_effect=self.fake_command)]:
            override.start()
            self.addCleanup(override.stop)

    def fake_command(self, *args, **kwargs):
        self.operations.append(args)
        if args[0] == "bash":
            self.assertEqual(args[1:], ("system/assistant/build.sh", "--apk-only"))
            return
        if args[0] == Path("/sdk/aapt2"):
            return f"package: name='{self.package}' versionCode='{Path(args[-1]).read_text()}'"
        if args[0] == Path("/sdk/apksigner"):
            signer = "b" if self.invalid_signature and args[-1] == self.candidate else "a"
            return "Signer #1 certificate SHA-256 digest: " + signer * 64 + "\n"
        self.assertEqual(args[:3], ("fake-adb", "-s", self.serial))
        if args[3] == "shell":
            self.assertEqual(args[4:], ("pm", "path", "--user", "0", self.package))
            return "package:/fixture/base.apk\n" + ("package:/fixture/split.apk\n" if self.split else "")
        if args[3] == "pull":
            Path(args[-1]).write_text(self.installed)
            return
        self.assertEqual(args[3], "install")
        self.assertEqual(args[4:7], ("-r", "--user", "0"))
        self.assertTrue((self.backup / "backup.json").is_file())
        if self.fail_install:
            raise subprocess.CalledProcessError(1, args)
        self.installed = Path(args[-1]).read_text()

    def deploy(self, rollback=False, serial=None):
        tool.deploy(Namespace(component="assistant", serial=serial or self.serial,
                              backup_dir=self.backup, rollback=rollback, dry_run=False),
                    tool.time.monotonic())

    def test_push_and_rollback(self):
        self.deploy()
        self.assertEqual(self.installed, "81")
        self.assertEqual((self.backup / "previous.apk").read_text(), "80")
        self.assertEqual(json.loads((self.backup / "backup.json").read_text())["versionCode"], 80)
        self.operations.clear()
        self.deploy(rollback=True)
        self.assertEqual(self.installed, "80")
        installs = [args for args in self.operations if args[0] == "fake-adb" and args[3] == "install"]
        self.assertEqual(len(installs), 1)
        self.assertIn("-d", installs[0])
        self.assertFalse(any(args[0] == "bash" for args in self.operations))

    def test_failed_install_keeps_backup_and_same_version_rollback_uses_no_downgrade(self):
        self.fail_install = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.deploy()
        self.assertEqual(self.installed, "80")
        self.assertEqual((self.backup / "previous.apk").read_text(), "80")
        self.operations.clear()
        self.fail_install = False
        self.deploy(rollback=True)
        install = next(args for args in self.operations if args[0] == "fake-adb" and args[3] == "install")
        self.assertNotIn("-d", install)

    def test_rollback_rejects_wrong_target_and_modified_backup(self):
        self.deploy()
        self.operations.clear()
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.deploy(rollback=True, serial="wrong-target")
        (self.backup / "previous.apk").write_text("79")
        with self.assertRaisesRegex(ValueError, "does not match"):
            self.deploy(rollback=True)
        self.assertFalse(any(args[0] == "fake-adb" for args in self.operations))

    def test_rejects_split_signer_mismatch_and_push_downgrade(self):
        self.split = True
        with self.assertRaisesRegex(ValueError, "single APK"):
            self.deploy()
        self.split = False
        self.invalid_signature = True
        with self.assertRaisesRegex(ValueError, "certificates differ"):
            self.deploy()
        self.invalid_signature = False
        self.fail_install = False
        self.installed = "82"
        with self.assertRaisesRegex(ValueError, "downgrade"):
            self.deploy()
        self.assertEqual(self.installed, "82")
        self.assertFalse(self.backup.exists())


if __name__ == "__main__":
    unittest.main()
