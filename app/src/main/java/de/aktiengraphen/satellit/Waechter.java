package de.aktiengraphen.satellit;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Network;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Holt etwa alle 15 Minuten die Meldungen vom PC – nur im Heimnetz (WLAN im selben Teilnetz wie der PC).
 * Außerhalb des Heimnetzes passiert nichts: keine Verbindung, keine Meldung.
 */
public class Waechter extends Worker {
    static final String NAME = "satellit-meldungen";

    public Waechter(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    static void planen(Context c, boolean an) {
        WorkManager wm = WorkManager.getInstance(c);
        if (!an) { wm.cancelUniqueWork(NAME); return; }
        PeriodicWorkRequest r = new PeriodicWorkRequest.Builder(Waechter.class, 15, TimeUnit.MINUTES).build();
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, r);
    }

    @NonNull @Override
    public Result doWork() {
        Context c = getApplicationContext();
        SharedPreferences p = Hinweise.prefs(c);
        String code = p.getString("kopplung", null), gid = p.getString("geraet", null);
        if (code == null || gid == null || !p.getBoolean("melden", true)) return Result.success();
        Verbindung.Kopplung k;
        try { k = Verbindung.lesen(code); } catch (Verbindung.Fehler f) { return Result.success(); }
        Network n = Heimnetz.netz(c);
        List<String> ips = Heimnetz.passend(c, n, k);
        if (n == null || ips.isEmpty()) return Result.success();   // nicht zu Hause: nichts tun
        k.adressen = ips;
        try {
            JSONObject d = new JSONObject(Verbindung.abrufen(k, n.getSocketFactory(), gid, MainActivity.geraetName()));
            Hinweise.pcErreichbar(c);
            Hinweise.verarbeiten(c, d);
        } catch (Verbindung.Warten w) {
            // PC fragt gerade nach – nichts melden
        } catch (Exception e) {
            Hinweise.pcNichtErreichbar(c, e.getMessage() == null ? "keine Antwort" : e.getMessage());
        }
        return Result.success();
    }
}
