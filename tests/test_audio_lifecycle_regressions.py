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
    static boolean cancelInCreate; static int released, creates; static long createResult = 7; static FileTranscription owner;
    private static long nativeCreate(FileTranscription o, boolean p) {
        creates++; owner = o; if (createResult > 0) o.onStatusUpdate("Ready");
        try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (cancelInCreate) o.cancel();
        return createResult;
    }
    private static void nativeRelease(long h) { released++; if(h <= 0) throw new AssertionError("failure code released"); }
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

    def test_extractor_released_when_codec_teardown_throws(self):
        # Execute the real decoder loop/finally; fake only the Android media driver.
        stubs = {
            "android/media/AudioFormat.java": """package android.media;
public class AudioFormat { public static final int ENCODING_PCM_16BIT=2,
ENCODING_PCM_8BIT=3, ENCODING_PCM_FLOAT=4; }""",
            "android/media/MediaFormat.java": """package android.media;
public class MediaFormat {
 public static final String KEY_MIME="mime",KEY_DURATION="duration",
 KEY_SAMPLE_RATE="rate",KEY_CHANNEL_COUNT="channels",KEY_PCM_ENCODING="encoding";
 public boolean containsKey(String key){return false;}
 public String getString(String key){return "audio/raw";}
 public long getLong(String key){return 0;}
 public int getInteger(String key){return 1;}
}""",
            "android/media/MediaExtractor.java": """package android.media;
public class MediaExtractor {
 public static boolean released;
 public void setDataSource(android.content.Context c,android.net.Uri u,Object h){}
 public int getTrackCount(){return 1;}
 public MediaFormat getTrackFormat(int i){return new MediaFormat();}
 public void selectTrack(int i){}
 public int readSampleData(java.nio.ByteBuffer b,int off){return -1;}
 public long getSampleTime(){return 0;}
 public boolean advance(){return false;}
 public void release(){released=true;}
}""",
            "android/media/MediaCodec.java": """package android.media;
public class MediaCodec {
 public static final int BUFFER_FLAG_END_OF_STREAM=4,INFO_OUTPUT_FORMAT_CHANGED=-2;
 public static boolean failStop,failRelease,released;
 public static class BufferInfo {public int offset,size,flags;}
 public static MediaCodec createDecoderByType(String mime){return new MediaCodec();}
 public void configure(MediaFormat f,Object a,Object b,int flags){}
 public void start(){}
 public int dequeueInputBuffer(long timeout){return 0;}
 public java.nio.ByteBuffer getInputBuffer(int i){return java.nio.ByteBuffer.allocate(1);}
 public void queueInputBuffer(int i,int o,int s,long t,int f){}
 public int dequeueOutputBuffer(BufferInfo info,long timeout){info.flags=4;return 0;}
 public MediaFormat getOutputFormat(){return new MediaFormat();}
 public java.nio.ByteBuffer getOutputBuffer(int i){return null;}
 public void releaseOutputBuffer(int i,boolean render){}
 public void stop(){if(failStop)throw new IllegalStateException("stop");}
 public void release(){released=true;if(failRelease)throw new IllegalStateException("release");}
}""",
            "dev/notune/transcribe/DecoderCleanupProbe.java": """package dev.notune.transcribe;
import android.media.MediaCodec; import android.media.MediaExtractor;
public class DecoderCleanupProbe {
 public static void main(String[] args)throws Exception {
  for(int mode=0;mode<4;mode++) {
   MediaCodec.failStop=(mode&1)!=0; MediaCodec.failRelease=(mode&2)!=0;
   MediaCodec.released=false; MediaExtractor.released=false;
   boolean threw=false;
   try {new AudioFileDecoder(new android.content.Context(),null).decode((s,n)->true,()->false);}
   catch(IllegalStateException e){threw=true;}
   if(threw!=MediaCodec.failRelease||!MediaCodec.released||!MediaExtractor.released)
    throw new AssertionError("codec/extractor cleanup lost in mode "+mode);
  }
 }
}""",
        }
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            files = []
            for name, text in stubs.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
                files.append(path)
            fixture = ROOT / "tests/fixtures/file_lifecycle"
            files += [fixture / "android/content/Context.java",
                      fixture / "android/net/Uri.java", fixture / "android/util/Log.java",
                      ROOT / "app/src/main/java/dev/notune/transcribe/AudioFileDecoder.java"]
            subprocess.run(["javac", "-d", str(work / "classes"), *map(str, files)],
                           check=True, capture_output=True, text=True, timeout=30)
            subprocess.run(["java", "-ea", "-cp", str(work / "classes"),
                            "dev.notune.transcribe.DecoderCleanupProbe"],
                           check=True, capture_output=True, text=True, timeout=10)

if __name__ == "__main__":
    unittest.main()
