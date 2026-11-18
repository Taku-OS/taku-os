#!/usr/bin/env python3
"""Actual Linux FIFO open, writer handoff and EOF; no simulated build success."""
import os
import pathlib
import subprocess
import sys
import tempfile
import threading
import time
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


@unittest.skipUnless(pathlib.Path('/proc/self/fdinfo').is_dir(), 'Linux builder/CI only')
class FifoGuard(unittest.TestCase):
    def test_reader_unblocks_and_real_writer_controls_eof(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'ninja_fifo'
            os.mkfifo(path)
            guard = subprocess.Popen([sys.executable, str(ROOT / 'image/ninja-fifo-guard.py'), str(path), str(os.getpid())], stdout=subprocess.PIPE)
            reader_open = threading.Event()
            received = []

            def read():
                with path.open('rb') as source:
                    reader_open.set()
                    received.append(source.read())

            reader = threading.Thread(target=read, daemon=True)
            reader.start()
            writer = None
            try:
                self.assertTrue(reader_open.wait(5), 'Guard must release the blocking open')
                writer = path.open('wb', buffering=0)
                writer.write(b'actual-ninja-protocol-fixture')
                time.sleep(0.3)
                self.assertTrue(reader.is_alive(), 'Reader must wait for the actual writer')
                writer.close(); writer = None
                reader.join(5)
                self.assertFalse(reader.is_alive(), 'Guard must not suppress the real writer EOF')
                self.assertEqual(received, [b'actual-ninja-protocol-fixture'])
            finally:
                if writer is not None: writer.close()
                guard.terminate()
                output = guard.communicate(timeout=5)[0]
                self.assertIn(b'Handed FIFO to the actual Ninja writer', output)


if __name__ == '__main__':
    unittest.main()
