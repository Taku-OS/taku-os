"""Source-level shutdown contract for the Android-only microphone recorder."""

import re
import unittest
from pathlib import Path


SOURCE = (
    Path(__file__).resolve().parents[1]
    / "src/org/agentos/assistant/MicrophoneAacRecorder.kt"
).read_text()


class MicrophoneShutdownTest(unittest.TestCase):
    def test_output_drain_checks_deadline_before_dequeue(self):
        self.assertRegex(
            SOURCE,
            r"var outputEnded = false\s+while \(true\) \{\s+"
            r"checkStopDeadline\(\)\s+val index = codec\.dequeueOutputBuffer",
        )

    def test_encoder_cleanup_does_not_wait_for_microphone(self):
        cleanup = SOURCE.split("} finally {", 1)[1].split("// A stop", 1)[0]
        self.assertIn("releaseMicrophoneAsync()", cleanup)
        self.assertIn("codec.release()", cleanup)
        self.assertIn("muxer.release()", cleanup)
        self.assertNotIn(".join(", cleanup)

    def test_stop_wait_is_bounded_and_microphone_release_is_async(self):
        stop = SOURCE.split("fun stop() {", 1)[1].split("fun release()", 1)[0]
        self.assertIn("thread.join(STOP_TIMEOUT_MS)", stop)
        self.assertRegex(stop, r'releaseMicrophoneAsync\(\)\s+throw IOException\("RECORDING_FINALIZE_TIMEOUT"\)')
        self.assertEqual(len(re.findall(r"record\.release\(\)", SOURCE)), 2)
        self.assertNotIn("record.stop()", SOURCE)


if __name__ == "__main__":
    unittest.main()
