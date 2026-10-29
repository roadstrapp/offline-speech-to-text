"""Run actual Java lifecycle guards with a synchronous fake native boundary."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class AudioLifecycleRegressionTest(unittest.TestCase):
    def test_ready_before_native_handle_and_cancel_during_create(self):
        source = (ROOT / "app/src/main/java/dev/notune/transcribe/FileTranscription.java").read_text()
        # Replace only the JNI boundary; startup, cancellation and decode ownership remain real.
        prefix = source[:source.index("    private static native long nativeCreate")]
        fake = """
    static boolean cancelInCreate; static int released; static FileTranscription owner;
    private static long nativeCreate(FileTranscription o, boolean p) {
        owner = o; o.onStatusUpdate("Ready");
        try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (cancelInCreate) o.cancel();
        return 7;
    }
    private static void nativeRelease(long h) { released++; }
    private static boolean nativePush(long h, float[] a, int l) { return h == 7; }
    private static void nativeFinish(long h) { owner.onTextTranscribed("test"); }
}
"""
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            java = work / "FileTranscription.java"
            java.write_text(prefix + fake)
            stubs = sorted((ROOT / "tests/fixtures/file_lifecycle").rglob("*.java"))
            subprocess.run(["javac", "-d", str(work / "classes"), str(java), *map(str, stubs)],
                           check=True, capture_output=True, text=True, timeout=30)
            completed = subprocess.run(["java", "-ea", "-cp", str(work / "classes"),
                                        "dev.notune.transcribe.FileLifecycleProbe"],
                                       check=True, capture_output=True, text=True, timeout=10)
            self.assertIn("PASS actual Java lifecycle", completed.stdout)

if __name__ == "__main__":
    unittest.main()
