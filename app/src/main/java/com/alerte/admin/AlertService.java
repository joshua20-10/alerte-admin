package com.alerte.admin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;

public class AlertService extends Service {

    static final String CH_ALERTE = "alerte_v1";
    static final String CH_SERVICE = "service_v1";
    static final String DB_URL = "https://alertemedicale-default-rtdb.firebaseio.com/alertes.json";

    private volatile boolean running = false;
    private Thread thread;
    private PowerManager.WakeLock wl;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("alertes", Context.MODE_PRIVATE);
        creerCanaux();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new Notification.Builder(this, CH_SERVICE)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Surveillance des alertes active")
                .setContentText("En écoute des nouvelles alertes")
                .setOngoing(true)
                .build();
        startForeground(1, n);

        if (wl == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alerte:ecoute");
        }
        if (!wl.isHeld()) wl.acquire();

        if (thread == null || !thread.isAlive()) {
            running = true;
            thread = new Thread(this::boucle, "ecoute-firebase");
            thread.start();
        }
        return START_STICKY;
    }

    private void creerCanaux() {
        NotificationManager nm = getSystemService(NotificationManager.class);

        NotificationChannel svc = new NotificationChannel(CH_SERVICE, "Service d'écoute",
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(svc);

        NotificationChannel ch = new NotificationChannel(CH_ALERTE, "Alertes médicales",
                NotificationManager.IMPORTANCE_HIGH);
        Uri son = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.alarme);
        ch.setSound(son, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 800, 400, 800, 400, 800});
        ch.enableLights(true);
        ch.setBypassDnd(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private void boucle() {
        while (running) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(DB_URL).openConnection();
                c.setRequestProperty("Accept", "text/event-stream");
                c.setConnectTimeout(15000);
                c.setReadTimeout(75000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                String ev = null, line;
                while (running && (line = r.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        ev = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        traiter(ev, line.substring(5).trim());
                    }
                }
            } catch (Exception e) {
                // on retente après une pause
            } finally {
                if (c != null) c.disconnect();
            }
            if (running) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
            }
        }
    }

    private void traiter(String ev, String data) {
        if (!"put".equals(ev)) return;
        try {
            JSONObject m = new JSONObject(data);
            String path = m.getString("path");
            Object d = m.opt("data");
            if (path.equals("/")) {
                if (d instanceof JSONObject) {
                    JSONObject o = (JSONObject) d;
                    Iterator<String> it = o.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        nouvelle(k, o.optJSONObject(k));
                    }
                }
            } else {
                String[] p = path.substring(1).split("/");
                if (p.length == 1) {
                    if (d instanceof JSONObject) nouvelle(p[0], (JSONObject) d);
                    else supprimee(p[0]);
                }
            }
        } catch (Exception e) { }
    }

    private synchronized void nouvelle(String key, JSONObject o) {
        if (o == null) return;
        if (o.optString("statut", "").contains("terminée")) return;

        String vus = prefs.getString("vus", "");
        if (("," + vus + ",").contains("," + key + ",")) return;
        vus = vus.isEmpty() ? key : vus + "," + key;
        if (vus.length() > 6000) {
            vus = vus.substring(vus.length() - 3000);
            int i = vus.indexOf(',');
            if (i >= 0) vus = vus.substring(i + 1);
        }
        prefs.edit().putString("vus", vus).apply();

        if (!MainActivity.enAvant) notifier(key, o);
    }

    private void supprimee(String key) {
        getSystemService(NotificationManager.class).cancel(key.hashCode());
    }

    private void notifier(String key, JSONObject o) {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, key.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String titre = "🚨 ALERTE : " + o.optString("nom", "Inconnu");
        String texte = o.optString("telephone", "") + " — " + o.optString("details", "");

        Notification n = new Notification.Builder(this, CH_ALERTE)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(titre)
                .setContentText(texte)
                .setStyle(new Notification.BigTextStyle().bigText(texte))
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(key.hashCode(), n);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (thread != null) thread.interrupt();
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
