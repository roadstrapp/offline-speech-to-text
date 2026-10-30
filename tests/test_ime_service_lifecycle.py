"""Execute the actual service command lifecycle against a fault-injected Android boundary."""
from pathlib import Path
import subprocess
import tempfile
import unittest
ROOT = Path(__file__).resolve().parents[1]

class ImeServiceLifecycleTest(unittest.TestCase):
    def test_actual_service_ownership_refusal_and_stale_commands(self):
        stubs = {
            'android/content/Context.java': '''package android.content;
public class Context {
 public static int stops, starts;
 public <T>T getSystemService(Class<T> c){try{return c.getDeclaredConstructor().newInstance();}catch(Exception e){throw new RuntimeException(e);}}
 public Object startForegroundService(Intent i){starts++;return null;}
 public boolean stopService(Intent i){stops++;return true;}
 public String getString(int i){return "fixture";}
}''',
            'android/content/Intent.java': '''package android.content;
public class Intent {String action;long token;
 public Intent(Context c,Class<?> t){} public Intent setAction(String a){action=a;return this;}
 public String getAction(){return action;} public Intent setData(android.net.Uri u){return this;}
 public Intent putExtra(String n,long v){token=v;return this;} public long getLongExtra(String n,long d){return token;}}
''',
            'android/app/Service.java': '''package android.app;
public class Service extends android.content.Context {
 public static final int START_NOT_STICKY=2,STOP_FOREGROUND_REMOVE=1; public int selfStops;
 public void stopForeground(int flags){}
 public int onStartCommand(android.content.Intent i,int f,int s){return 0;}
 public void stopSelf(){throw new AssertionError("unbound service stop");} public void stopSelf(int id){if(id<=0)throw new AssertionError("missing start identity");selfStops++;} public void onDestroy(){}
 public android.os.IBinder onBind(android.content.Intent i){return null;}}
''',
            'android/app/Notification.java': 'package android.app;public class Notification {}',
            'android/app/NotificationChannel.java': '''package android.app;public class NotificationChannel {
 public NotificationChannel(String i,String n,int p){} public int getImportance(){return NotificationManager.importance;}}''',
            'android/app/NotificationManager.java': '''package android.app;public class NotificationManager {
 public static final int IMPORTANCE_NONE=0,IMPORTANCE_LOW=2;public static int importance=2;
 public static boolean enabled=true;public boolean areNotificationsEnabled(){return enabled;}
 public NotificationChannel getNotificationChannel(String id){return new NotificationChannel(id,id,importance);}
 public void createNotificationChannel(NotificationChannel c){}}
''',
            'android/app/PendingIntent.java': '''package android.app;public class PendingIntent {
 public static final int FLAG_IMMUTABLE=1,FLAG_UPDATE_CURRENT=2;
 public static PendingIntent getService(android.content.Context c,int r,android.content.Intent i,int f){return new PendingIntent();}}
''',
            'android/content/pm/ServiceInfo.java': 'package android.content.pm;public class ServiceInfo {public static final int FOREGROUND_SERVICE_TYPE_MICROPHONE=128;}',
            'android/os/IBinder.java': 'package android.os;public interface IBinder {}',
            'android/os/Looper.java': 'package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}',
            'android/os/Handler.java': '''package android.os;public class Handler {public Handler(Looper l){}
 public void removeCallbacks(Runnable r){} public boolean postDelayed(Runnable r,long t){return true;}}''',
            'android/os/PowerManager.java': '''package android.os;public class PowerManager {
 public static final int PARTIAL_WAKE_LOCK=1;public static boolean deny; public static int holds;
 public WakeLock newWakeLock(int i,String n){return new WakeLock();} public static class WakeLock {
 boolean held;public void setReferenceCounted(boolean b){} public void acquire(long t){if(deny)throw new SecurityException();if(!held)holds++;held=true;}
 public boolean isHeld(){return held;} public void release(){held=false;holds--;}}}''',
            'android/net/Uri.java': 'package android.net;public class Uri {public static Uri parse(String s){return new Uri();}}',
            'android/util/Log.java': 'package android.util;public class Log {public static int w(String t,String m,Throwable e){return 0;}public static int i(String t,String m){return 0;}}',
            'androidx/core/app/ServiceCompat.java': '''package androidx.core.app;public class ServiceCompat {
 public static boolean deny;public static int promotions;
 public static void startForeground(android.app.Service s,int id,android.app.Notification n,int type){if(deny)throw new SecurityException();promotions++;}}''',
            'androidx/core/app/NotificationCompat.java': '''package androidx.core.app;public class NotificationCompat {
 public static class Builder {public Builder(android.content.Context c,String id){}
 public Builder setSmallIcon(int i){return this;}public Builder setContentTitle(String s){return this;}
 public Builder setContentText(String s){return this;}public Builder setOngoing(boolean b){return this;}
 public Builder setSilent(boolean b){return this;}public Builder setUsesChronometer(boolean b){return this;}
 public Builder setWhen(long t){return this;}public Builder addAction(int i,String s,android.app.PendingIntent p){return this;}
 public android.app.Notification build(){return new android.app.Notification();}}}''',
            'dev/notune/transcribe/R.java': '''package dev.notune.transcribe;class R {
 static class string {static int ime_dictation_channel,ime_dictation_title,ime_dictation_text,ime_dictation_stop;}
 static class drawable {static int ic_mic;}}''',
            'dev/notune/transcribe/RustInputMethodService.java': '''package dev.notune.transcribe;
class RustInputMethodService {
 static long owner,ready,stopped,unavailable;
 static boolean ownsDictation(long t){return t>0&&owner==t;}
 static boolean dictationForegroundReady(long t){if(!ownsDictation(t))return false;ready=t;return true;}
 static void dictationForegroundUnavailable(long t){unavailable=t;if(owner==t){owner=0;ready=0;}}
 static void stopDictationFromNotification(long t){if(ownsDictation(t)){stopped=t;owner=0;ready=0;}}
}''',
            'dev/notune/transcribe/ServiceProbe.java': '''package dev.notune.transcribe;
import android.content.*;import android.os.PowerManager;import android.app.NotificationManager;
import androidx.core.app.ServiceCompat;
public class ServiceProbe {
 static Intent start(long t){return new Intent(new Context(),ImeDictationService.class).putExtra("dictation_token",t);}
 static Intent stop(long t){return start(t).setAction("dev.notune.transcribe.action.STOP_IME_DICTATION");}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] a){
  Context c=new Context();NotificationManager.enabled=false;
  check(!ImeDictationService.start(c,1)&&Context.starts==0,"hidden notification admitted");
  NotificationManager.enabled=true;NotificationManager.importance=0;
  check(!ImeDictationService.start(c,1)&&Context.starts==0,"blocked channel admitted");
  NotificationManager.importance=2;check(ImeDictationService.start(c,1),"queue refused");
  RustInputMethodService.owner=1;ServiceCompat.deny=true;
  ImeDictationService s=new ImeDictationService();s.onStartCommand(start(1),0,1);
  check(s.selfStops==1&&RustInputMethodService.unavailable==1&&RustInputMethodService.ready==0,"async refusal was ready");s.onDestroy();
  ServiceCompat.deny=false;RustInputMethodService.owner=2;
  s=new ImeDictationService();s.onStartCommand(start(2),0,2);
  check(RustInputMethodService.ready==2&&PowerManager.holds==1,"promotion or lock missing");
  int before=Context.stops;ImeDictationService.stop(c,1);
  check(Context.stops==before,"old owner stopped new service");
  s.onStartCommand(stop(1),0,3);check(s.selfStops==0&&RustInputMethodService.owner==2,"stale Stop ended new recording");
  s.onStartCommand(start(1),0,4);check(s.selfStops==0&&RustInputMethodService.ready==2,"stale start replaced new recording");
  s.onStartCommand(stop(2),0,5);check(s.selfStops==1&&RustInputMethodService.stopped==2,"matching Stop failed");
  s.onDestroy();check(PowerManager.holds==0,"wake lock leaked");
  ImeDictationService orphan=new ImeDictationService();orphan.onStartCommand(stop(1),0,1);
  check(orphan.selfStops==1,"stale action resurrected orphan");orphan.onDestroy();
  int promoted=ServiceCompat.promotions;RustInputMethodService.owner=0;
  s=new ImeDictationService();s.onStartCommand(start(99),0,1);
  check(ServiceCompat.promotions==promoted+1&&s.selfStops==1,"cancelled queued start skipped promotion obligation");s.onDestroy();
  RustInputMethodService.owner=3;PowerManager.deny=true;
  s=new ImeDictationService();s.onStartCommand(start(3),0,1);
  check(RustInputMethodService.ready==3&&PowerManager.holds==0,"wake denial killed foreground recording");s.onDestroy();
 }
}'''
        }
        with tempfile.TemporaryDirectory() as d:
            work=Path(d); files=[]
            for name,text in stubs.items():
                p=work/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);files.append(p)
            files.append(ROOT/'app/src/main/java/dev/notune/transcribe/ImeDictationService.java')
            subprocess.run(['javac','-d',str(work/'classes'),*map(str,files)],check=True,capture_output=True,text=True,timeout=30)
            subprocess.run(['java','-ea','-cp',str(work/'classes'),'dev.notune.transcribe.ServiceProbe'],check=True,capture_output=True,text=True,timeout=10)

if __name__=='__main__':unittest.main()
