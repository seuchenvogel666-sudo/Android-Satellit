package de.aktiengraphen.satellit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Benachrichtigungen aus den Meldungen des PCs (Verlustbremse, großer Gewinn, Stillstand, Datenquelle, Notaus,
 * Tagesbericht) und der Hinweis "PC nicht erreichbar".
 */
final class Hinweise {
    private Hinweise() { }

    static final String KANAL = "meldungen", KANAL_WICHTIG = "wichtig";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("satellit", Context.MODE_PRIVATE); }

    static void kanaele(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(KANAL, "Meldungen und Tagesbericht", NotificationManager.IMPORTANCE_DEFAULT));
        NotificationChannel w = new NotificationChannel(KANAL_WICHTIG, "Wichtig: Notaus, Verlustbremse, PC offline", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(w);
    }

    static void zeigen(Context c, int id, String titel, String text, boolean wichtig) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return;
        kanaele(c);
        Intent oeffnen = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, oeffnen, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, wichtig ? KANAL_WICHTIG : KANAL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_satellit).setContentTitle(titel).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(pi);
        if (Build.VERSION.SDK_INT < 26 && wichtig) b.setPriority(Notification.PRIORITY_HIGH);
        try { nm.notify(id, b.build()); } catch (SecurityException ignored) { }
    }

    /** Neue Meldungen aus den PC-Daten als Benachrichtigung zeigen (jede nur einmal). */
    static synchronized void verarbeiten(Context c, JSONObject daten) {
        SharedPreferences p = prefs(c);
        JSONArray l = daten.optJSONArray("Meldungen");
        if (l == null) return;
        long letzte = p.getLong("meldung_letzte", -1), max = letzte;
        boolean ersteMal = letzte < 0;   // beim ersten Abruf nichts nachholen – sonst kämen alle alten Meldungen auf einmal
        boolean an = p.getBoolean("melden", true), bericht = p.getBoolean("bericht", true);
        for (int i = 0; i < l.length(); i++) {
            JSONObject m = l.optJSONObject(i);
            if (m == null) continue;
            long id = m.optLong("Id", 0);
            if (id > max) max = id;
            if (ersteMal || id <= letzte || !an) continue;
            String art = m.optString("Art", "");
            if (art.equals("bericht") && !bericht) continue;
            boolean wichtig = art.equals("notaus") || art.equals("bremse") || art.equals("daten");
            zeigen(c, (int) (id % 1000000000L), m.optString("Titel", "Aktien-Graphen"), m.optString("Text", ""), wichtig);
        }
        if (max != letzte) p.edit().putLong("meldung_letzte", max).apply();
    }

    static void pcErreichbar(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean("offline_gemeldet", false))
            zeigen(c, 7, "PC wieder erreichbar", "Aktien-Graphen am PC antwortet wieder.", false);
        p.edit().putInt("offline_zaehler", 0).putBoolean("offline_gemeldet", false).apply();
    }

    /** Im Heimnetz, aber der PC antwortet nicht: nach 2 Fehlversuchen in Folge (etwa 30 Minuten) einmal melden. */
    static void pcNichtErreichbar(Context c, String grund) {
        SharedPreferences p = prefs(c);
        int n = p.getInt("offline_zaehler", 0) + 1;
        p.edit().putInt("offline_zaehler", n).apply();
        if (n >= 2 && !p.getBoolean("offline_gemeldet", false) && p.getBoolean("melden", true)) {
            p.edit().putBoolean("offline_gemeldet", true).apply();
            zeigen(c, 7, "PC nicht erreichbar", "Du bist im Heimnetz, aber Aktien-Graphen am PC antwortet nicht (" + grund +
                    "). Ist der PC aus, im Ruhezustand oder das Programm geschlossen?", true);
        }
    }
}
