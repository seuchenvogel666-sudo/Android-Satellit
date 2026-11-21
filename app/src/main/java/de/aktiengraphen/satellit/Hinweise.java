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
        zeigen(c, id, titel, text, wichtig, null);
    }

    // ---------- Signaltöne (1.15): der PC nennt je Meldung einen Ton ("4-Sirene", "1-Glocke", "2-Sonar-Ping", "3-Doppelsignal", "5-Dreiklang") ----------
    // Die Töne liegen als Dateien in res/raw. Unter Android 8+ gehört der Ton zum Benachrichtigungskanal – deshalb ein Kanal je Ton.

    /** "4-Sirene" -> "sirene", "2-Sonar-Ping" -> "sonar_ping"; null, wenn kein bekannter Ton genannt ist. */
    static String tonName(String ton) {
        if (ton == null) return null;
        String n = ton.toLowerCase(java.util.Locale.ROOT).replaceFirst("^\\d+-", "").replace('-', '_');
        return n.equals("sirene") || n.equals("glocke") || n.equals("sonar_ping") || n.equals("doppelsignal") || n.equals("dreiklang") ? n : null;
    }

    static android.net.Uri tonUri(Context c, String name) {
        int res = c.getResources().getIdentifier(name, "raw", c.getPackageName());
        return res == 0 ? null : android.net.Uri.parse("android.resource://" + c.getPackageName() + "/" + res);
    }

    // ---------- Eigene Töne (1.15): zu jedem Signalton lässt sich ein Systemton des Handys wählen (oder stumm) ----------
    static final String[] TON_NAMEN = { "sirene", "glocke", "sonar_ping", "doppelsignal", "dreiklang" };
    static final String[] TON_TEXT = {
            "Sirene – Warnung: Notaus, Verlustbremse, Datenfehler, Abgleich, PC nicht erreichbar",
            "Glocke – eine Order wartet auf deine Freigabe",
            "Sonar-Ping – Hinweis: Wallet bewegt sich, Markttrend kippt, Abweichung",
            "Doppelsignal – alle übrigen Meldungen",
            "Dreiklang – Gewinn mitgenommen" };

    /** Gespeicherte Wahl: "" = mitgelieferter Ton, "-" = stumm, sonst die Adresse (URI) des gewählten Systemtons. */
    static String tonWahl(Context c, String name) { return prefs(c).getString("ton_" + name, ""); }

    /** Der Ton, der wirklich erklingt (null = stumm). */
    static android.net.Uri tonJetzt(Context c, String name) {
        String w = tonWahl(c, name);
        if (w.equals("-")) return null;
        if (w.length() > 0) { try { return android.net.Uri.parse(w); } catch (Exception ignored) { } }
        return tonUri(c, name);
    }

    static String tonAnzeige(Context c, String name) {
        String w = tonWahl(c, name);
        if (w.equals("-")) return "stumm";
        if (w.length() == 0) return "mitgelieferter Ton";
        try {
            android.media.Ringtone r = android.media.RingtoneManager.getRingtone(c, android.net.Uri.parse(w));
            if (r != null) return r.getTitle(c);
        } catch (Exception ignored) { }
        return "gewählter Ton";
    }

    static void tonVorspielen(Context c, String name) {
        try {
            android.net.Uri u = tonJetzt(c, name);
            if (u == null) return;
            android.media.Ringtone r = android.media.RingtoneManager.getRingtone(c, u);
            if (r != null) r.play();
        } catch (Exception ignored) { }
    }

    /** Wahl speichern; die Kanäle dieses Tons werden gelöscht, damit der neue Ton wirkt (Android legt den Ton im Kanal fest). */
    static void tonSpeichern(Context c, String name, String wert) {
        prefs(c).edit().putString("ton_" + name, wert == null ? "" : wert).apply();
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        for (NotificationChannel ch : nm.getNotificationChannels())
            if (ch.getId().startsWith("ton_" + name + "_")) nm.deleteNotificationChannel(ch.getId());
    }

    /** Kanal für einen Ton: Sirene und Glocke dringend (HIGH, mit Banner), die übrigen normal. Die Kennung enthält die Wahl, damit eine neue Wahl einen neuen Kanal ergibt. */
    static String tonKanal(Context c, NotificationManager nm, String name) {
        String id = "ton_" + name + "_" + Integer.toHexString(tonWahl(c, name).hashCode());
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(id) == null) {
            boolean dringend = name.equals("sirene") || name.equals("glocke");
            NotificationChannel ch = new NotificationChannel(id, "Signalton: " + name.replace('_', ' '),
                    dringend ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT);
            android.net.Uri u = tonJetzt(c, name);
            if (u != null)
                ch.setSound(u, new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            else ch.setSound(null, null);   // stumm gewählt
            nm.createNotificationChannel(ch);
        }
        return id;
    }

    static void zeigen(Context c, int id, String titel, String text, boolean wichtig, String ton) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return;
        kanaele(c);
        String tn = tonName(ton);
        String kanal = wichtig ? KANAL_WICHTIG : KANAL;
        if (tn != null && Build.VERSION.SDK_INT >= 26) kanal = tonKanal(c, nm, tn);
        Intent oeffnen = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, oeffnen, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, kanal) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_satellit).setContentTitle(titel).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(pi);
        if (Build.VERSION.SDK_INT < 26) {
            if (wichtig) b.setPriority(Notification.PRIORITY_HIGH);
            if (tn != null) { android.net.Uri u = tonJetzt(c, tn); if (u != null) b.setSound(u); }
        }
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
            // 1.15: der PC sagt selbst, was wichtig ist ("Wichtig") und welcher Ton passt ("Ton"); ältere PC-Versionen kennen die Felder nicht -> nach der Art entscheiden.
            // (Vorher fehlten "datenfehler" und "abgleich" – der PC meldet sie so, die App kannte nur "daten".)
            boolean wichtig = m.has("Wichtig") ? m.optBoolean("Wichtig", false)
                    : art.equals("notaus") || art.equals("bremse") || art.equals("daten") || art.equals("datenfehler") || art.equals("abgleich") || art.equals("freigabe");
            zeigen(c, (int) (id % 1000000000L), m.optString("Titel", "Aktien-Graphen"), m.optString("Text", ""), wichtig, m.has("Ton") ? m.optString("Ton", null) : null);
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
                    "). Ist der PC aus, im Ruhezustand oder das Programm geschlossen?", true, "4-Sirene");
        }
    }
}
