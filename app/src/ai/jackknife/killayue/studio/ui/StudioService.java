package ai.jackknife.killayue.studio.ui;
import android.app.*;
import android.content.*;
import android.os.*;
/** Keeps an explicitly started take alive when the screen rotates or another app is captured. */
public final class StudioService extends Service {
    public void onCreate() {
        super.onCreate();
        NotificationManager n = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        n.createNotificationChannel(new NotificationChannel("studio-record", "Studio recording", NotificationManager.IMPORTANCE_LOW));
        Intent i = new Intent(this, StudioActivity.class);
        PendingIntent p = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE);
        startForeground(184, new Notification.Builder(this, "studio-record")
                                 .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                                 .setContentTitle("Killa YuE Studio")
                                 .setContentText("Recording session active")
                                 .setContentIntent(p)
                                 .setOngoing(true)
                                 .build());
    }
    public int onStartCommand(Intent i, int flags, int id) { return START_NOT_STICKY; }
    public IBinder onBind(Intent i) { return null; }
    public void onDestroy() {
        StudioSession.get(this).recording.stop();
        super.onDestroy();
    }
}
