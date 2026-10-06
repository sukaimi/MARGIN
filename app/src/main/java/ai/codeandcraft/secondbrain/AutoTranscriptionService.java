package ai.codeandcraft.secondbrain;

import android.app.*;
import android.content.*;
import android.os.*;

/** Saved-audio foreground worker; API 34+ uses the OS-limited shortService type. */
public final class AutoTranscriptionService extends Service {
    private final Handler main=new Handler(Looper.getMainLooper());
    private Thread worker;
    private int latestStartId;
    private volatile boolean stopping;
    private boolean destroyed;
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        latestStartId=startId;
        if(stopping){stopSelfResult(latestStartId);return START_NOT_STICKY;}
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("offline-transcript","Offline transcription",NotificationManager.IMPORTANCE_LOW));
        Notification n=new Notification.Builder(this,"offline-transcript").setSmallIcon(android.R.drawable.ic_menu_edit).setContentTitle("Transcribing saved audio offline").setContentText("Original audio retained · recording has priority").build();
        try{if(Build.VERSION.SDK_INT>=34)startForeground(56,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);else startForeground(56,n);}
        catch(RuntimeException unavailable){stopping=true;try{AutoTranscription.cancelActive(this,"Foreground start unavailable; saved work retained.",false,worker);AutoTranscription.foregroundUnavailable(this,"Foreground start unavailable; waiting for an app foreground kick.");}catch(Exception ignored){}stopSelfResult(latestStartId);return START_NOT_STICKY;}
        ensureWorker();
        return START_NOT_STICKY;
    }
    private void ensureWorker(){
        if(worker!=null||stopping||destroyed)return;
        worker=new Thread(()->{
            try{while(!stopping&&AutoTranscription.step(this,AutoTranscription.ENGINE)){} }
            catch(Exception e){stopping=true;try{AutoTranscription.cancelActive(this,"Queue unavailable; saved work retained.",false,Thread.currentThread());}catch(Exception ignored){} }
            finally{main.post(this::workerFinished);}
        },"offline-transcription");
        worker.start();
    }
    private void workerFinished(){
        worker=null;
        if(destroyed){if(!AutoTranscription.blocked())AutoTranscription.kick(getApplicationContext());return;}
        try{if(!stopping&&!AutoTranscription.blocked()&&AutoTranscription.quiescent()&&AutoTranscription.eligible(this)){ensureWorker();return;}}
        catch(Exception ignored){}
        // Starts and this completion are serialized on the main thread. A later start creates a new worker.
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelfResult(latestStartId);
    }
    @Override public void onTimeout(int startId){
        stopping=true;
        try{AutoTranscription.cancelActive(this,"OS shortService time limit reached. Resume preserves accepted windows.",true,worker);}catch(Exception ignored){}
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(latestStartId);
    }
    @Override public void onDestroy(){
        destroyed=true;stopping=true;
        try{AutoTranscription.cancelActive(this,"Service stopped; saved work retained.",false,worker);}catch(Exception ignored){}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
