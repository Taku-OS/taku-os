#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Taku AI Inc.
# SPDX-License-Identifier: Apache-2.0
"""Offline tests for cloud-builder-prune.py mount safety, hardlink accounting, and baseline pins."""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("cloud_builder_prune", ROOT / "scripts/cloud-builder-prune.py")
assert SPEC and SPEC.loader
PRUNE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = PRUNE
SPEC.loader.exec_module(PRUNE)


class CloudBuilderPruneTests(unittest.TestCase):
    def test_parse_proc_mounts_unescapes_and_detects_overlay_dirs(self) -> None:
        sample = (
            "overlay /mnt/taku-build/runs/r1/overlay/merged overlay "
            "rw,lowerdir=/mnt/taku-build/source,upperdir=/mnt/taku-build/runs/r1/overlay/upper,"
            "workdir=/mnt/taku-build/runs/r1/overlay/work 0 0\n"
        )
        mounts = PRUNE.parse_proc_mounts(sample)
        self.assertTrue(PRUNE.has_active_mount(Path("/mnt/taku-build/runs/r1"), mounts))
        self.assertTrue(PRUNE.has_active_mount(Path("/mnt/taku-build/source"), mounts))
        self.assertFalse(PRUNE.has_active_mount(Path("/mnt/taku-build/runs/r2"), mounts))

    def test_hardlink_reclaimed_bytes_counts_shared_inode_only_when_all_links_removed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            a = base / "a"
            b = base / "b"
            a.mkdir()
            b.mkdir()
            payload = b"T" * 16384
            fa = a / "ota.zip"
            fb = b / "lineage-alias.zip"
            fa.write_bytes(payload)
            os.link(fa, fb)

            # Removing only one hardlink does not free the underlying file blocks.
            only_a = PRUNE.estimate_reclaimed_bytes([a])
            both = PRUNE.estimate_reclaimed_bytes([a, b])
            self.assertLess(only_a, len(payload))
            self.assertGreaterEqual(both, len(payload))

    def test_dry_run_plan_preserves_pinned_baselines_and_active_mounts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp) / "work"
            runs = work / "runs"
            runs.mkdir(parents=True)
            (work / "pinned-baselines.json").write_text(
                json.dumps({
                    "schema": "taku.cloud-builder-pins/1",
                    "pinnedBuildNumbers": ["20261111080323"],
                    "pinnedRuns": ["ui-c22c87c2-20261111080323"],
                }),
                encoding="utf-8",
            )
            pinned = runs / "ui-c22c87c2-20261111080323"
            pinned.mkdir()
            (pinned / "TakuOS-1.3.1-tokay-20261111080323-full.zip").write_bytes(b"pinned")

            mounted = runs / "active-mounted-run"
            mounted.mkdir()
            (mounted / "status.json").write_text('{"phase":"compile"}\n', encoding="utf-8")

            for i in range(6):
                old = runs / f"scratch-2026110{i}"
                old.mkdir()
                (old / "status.json").write_text('{"phase":"failed-exit-1"}\n', encoding="utf-8")
                old_ts = 1_700_000_000 + i * 60
                os.utime(old, (old_ts, old_ts))

            mounts = {mounted / "overlay/merged"}
            plan = PRUNE.build_plan(
                work=work,
                root_dir=Path(tmp),
                systemd_dir=Path(tmp),
                releases_dirs=[],
                policy=PRUNE.Policy(keep_full_runs=1, keep_scratch_runs=2, scratch_max_age_days=1.0),
                now_ts=1_795_000_000.0,
                mount_paths=mounts,
                containers=[],
                images=[],
            )
            planned_targets = {act.get("path", "") for act in plan["filesystemActions"]}
            self.assertNotIn(str(pinned), planned_targets)
            self.assertNotIn(str(mounted), planned_targets)
            self.assertGreaterEqual(len(plan["filesystemActions"]), 4)


if __name__ == "__main__":
    unittest.main()
