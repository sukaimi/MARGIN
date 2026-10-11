"""Production storage/extractive flow on JVM. Native ASR and Android APIs are explicit test doubles."""
import os, pathlib, subprocess, tempfile
R=pathlib.Path(__file__).resolve().parents[2]
jar_path=os.environ.get('JSON_JAR')
assert jar_path, 'Set JSON_JAR to org.json:json:20240303'
jar=pathlib.Path(jar_path).expanduser().resolve()
assert jar.is_file(), 'JSON_JAR must point to org.json:json:20240303'
stubs={
'android/content/Context.java':'''package android.content; import java.io.File; public class Context {private final File root; public Context(File r){root=r;} public File getFilesDir(){return root;}}''',
'android/os/SystemClock.java':'''package android.os; public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}''',
'android/system/OsConstants.java':'''package android.system;public class OsConstants {public static final int O_RDONLY=0;}''',
'android/system/Os.java':'''package android.system;import java.io.*;public class Os {public static FileDescriptor open(String p,int f,int m){return new FileDescriptor();}public static void fsync(FileDescriptor f){}public static void close(FileDescriptor f){}}''',
'android/util/AtomicFile.java':'''package android.util; import java.io.*;import java.nio.file.*;public class AtomicFile {private final File f; public AtomicFile(File f){this.f=f;} public byte[] readFully()throws IOException{return Files.readAllBytes(f.toPath());}public FileOutputStream startWrite()throws IOException{return new FileOutputStream(new File(f+".new"));}public void finishWrite(FileOutputStream s)throws IOException{s.getFD().sync();s.close();Files.move(new File(f+".new").toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING);}public void failWrite(FileOutputStream s)throws IOException{s.close();new File(f+".new").delete();}}''',
'ai/codeandcraft/secondbrain/RecorderService.java':'''package ai.codeandcraft.secondbrain;public class RecorderService {public static boolean recording;public static boolean isRecording(){return recording;}}''',
'ai/codeandcraft/secondbrain/WindowedTranscription.java':'''package ai.codeandcraft.secondbrain;import android.content.Context;import java.io.*;import java.nio.file.*;import org.json.*;public class WindowedTranscription {public static JSONObject inspect(Context c,File m,int n)throws Exception{return new JSONObject(Files.readString(new File(m,"host-native-job.json").toPath()));}public static JSONObject inspectJob(Context c,File m,String id)throws Exception{JSONObject j=inspect(c,m,64);if(!id.equals(j.getString("jobId")))throw new IOException("job");return j;}}''',
'ai/codeandcraft/secondbrain/SourceBundle.java':'''package ai.codeandcraft.secondbrain;import java.io.*;import java.nio.file.*;import java.security.*;public class SourceBundle {public static String sha(File f)throws Exception{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath())));}}'''
}
with tempfile.TemporaryDirectory(prefix='margin-flow-') as tmp:
 p=pathlib.Path(tmp)
 for name,body in stubs.items():
  f=p/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(body)
 names=['SessionStore','WavChunkWriter','PcmWavReader','TranscriptTruthStore','EvidenceExcerptBaseline','MeetingBriefEngine','MeetingBriefStore','AsrConfiguration']
 sources=[str(R/'app/src/main/java/ai/codeandcraft/secondbrain'/f'{n}.java') for n in names]
 subprocess.run(['javac','--release','17','-cp',str(jar),'-d',str(p),*[str(p/n) for n in stubs],*sources,str(R/'tests/flow-host/FlowHostTest.java')],check=True)
 cp=str(p)+os.pathsep+str(jar)
 for mode in ['write','reopen','tamper']:
  subprocess.run(['java','-cp',cp,'ai.codeandcraft.secondbrain.FlowHostTest',mode,str(p/'data')],check=True)
