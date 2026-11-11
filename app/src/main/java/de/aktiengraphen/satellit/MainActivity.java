package de.aktiengraphen.satellit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Aktien-Satellit: zeigt die Übersicht von Aktien-Graphen am PC – nur im Heimnetz.
 * - gekoppelt wird ausschließlich über den Kopplungs-QR-Code, den der PC anzeigt (enthält einen geheimen Schlüssel)
 * - verbunden wird nur über WLAN/LAN (nie Mobilfunk, nie VPN) und nur, wenn der PC im selben Netz liegt
 * - die Daten kommen verschlüsselt und werden nur im Arbeitsspeicher gehalten, nicht gespeichert
 * Die App liest nur. Einzige Ausnahme ist der NOTAUS: Er stoppt am PC sofort jeden Handel (freigeben nur am PC).
 * Meldungen (Verlustbremse, großer Gewinn, Stillstand, Notaus, Tagesbericht, PC offline) kommen als Benachrichtigung –
 * abgeholt etwa alle 15 Minuten, ebenfalls nur im Heimnetz.
 */
public class MainActivity extends ReiterMarkt {


    final Runnable automatisch = new Runnable() {
        @Override public void run() {
            laden();
            handler.postDelayed(this, NEU_LADEN_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("satellit", MODE_PRIVATE);
        reiter = Math.max(0, Math.min(REITER.length - 1, prefs.getInt("reiter7", 2)));
        getWindow().setStatusBarColor(HINTERGRUND);
        getWindow().setNavigationBarColor(HINTERGRUND);

        LinearLayout wurzel = new LinearLayout(this);
        wurzel.setOrientation(LinearLayout.VERTICAL);
        wurzel.setBackgroundColor(HINTERGRUND);

        // Kopf: Titel, Stand, Knöpfe
        LinearLayout kopf = new LinearLayout(this);
        kopf.setOrientation(LinearLayout.VERTICAL);
        kopf.setPadding(dp(16), dp(12), dp(16), dp(4));
        // Titel und Verbindungspunkt in einer Zeile (grün = aktuell, gelb = alt, rot = keine Verbindung)
        LinearLayout titelZeile = new LinearLayout(this);
        titelZeile.setOrientation(LinearLayout.HORIZONTAL);
        titelZeile.setGravity(Gravity.CENTER_VERTICAL);
        kopfPunkt = text("●", 14, GRAU, false);
        kopfPunkt.setPadding(0, 0, dp(8), 0);
        titelZeile.addView(kopfPunkt);
        titelZeile.addView(text("Aktien-Satellit", 20, TEXT, true));
        kopf.addView(titelZeile);
        kopfStatus = text("", 12, GRAU, false);
        kopfStatus.setPadding(0, dp(2), 0, 0);
        kopf.addView(kopfStatus);

        LinearLayout knoepfe = new LinearLayout(this);
        knoepfe.setOrientation(LinearLayout.HORIZONTAL);
        knoepfe.setPadding(0, dp(8), 0, 0);
        TextView neu = knopf("↻ Laden");
        neu.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { laden(); } });
        TextView koppeln = knopf("⌗ Koppeln (QR)");
        koppeln.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { scannen(); } });
        TextView trennen = knopf("Trennen");
        trennen.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { trennenFragen(); } });
        // drei gleich breite Knöpfe – passen auch auf schmale Handys in eine Zeile
        knoepfe.addView(neu, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        knoepfe.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
        knoepfe.addView(koppeln, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
        knoepfe.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
        knoepfe.addView(trennen, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        kopf.addView(knoepfe);
        wurzel.addView(kopf);

        // Reiter wie am PC – in zwei Zeilen, damit nichts seitlich gescrollt werden muss
        LinearLayout reiterBlock = new LinearLayout(this);
        reiterBlock.setOrientation(LinearLayout.VERTICAL);
        reiterBlock.setPadding(dp(12), dp(4), dp(12), dp(4));
        LinearLayout reiterLeiste = null;
        for (int i = 0; i < REITER.length; i++) {
            if (i == 0 || i == 4) {
                reiterLeiste = new LinearLayout(this);
                reiterLeiste.setOrientation(LinearLayout.HORIZONTAL);
                reiterLeiste.setPadding(0, dp(2), 0, dp(2));
                reiterBlock.addView(reiterLeiste);
            }
            final int nr = i;
            TextView t = text(REITER[i], 12.5f, TEXT, true);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(dp(2), dp(7), dp(2), dp(7));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    reiter = nr;
                    prefs.edit().putInt("reiter7", nr).apply();
                    anzeigen();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            reiterLeiste.addView(t, lp);
            reiterKnoepfe[i] = t;
        }
        wurzel.addView(reiterBlock);

        ScrollView scroll = new ScrollView(this);
        inhalt = new LinearLayout(this);
        inhalt.setOrientation(LinearLayout.VERTICAL);
        inhalt.setPadding(dp(12), dp(4), dp(12), dp(24));
        scroll.addView(inhalt);
        wurzel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(wurzel);
        Hinweise.kanaele(this);
        if (prefs.getString("kopplung", null) != null) benachrichtigungenStarten();
        anzeigen();
    }

    // Hintergrund-Abruf der Meldungen und (ab Android 13) die Erlaubnis für Benachrichtigungen
    void benachrichtigungenStarten() {
        boolean an = prefs.getBoolean("melden", true);
        Waechter.planen(getApplicationContext(), an);
        if (an && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("erlaubnis_gefragt", false)) {
            prefs.edit().putBoolean("erlaubnis_gefragt", true).apply();
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(automatisch);
        handler.post(automatisch);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(automatisch);
        handler.removeCallbacks(nochmal);
    }

    // ---------- Koppeln: nur über den QR-Code, den der PC anzeigt ----------
    void scannen() {
        try {
            GmsBarcodeScannerOptions o = new GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build();
            GmsBarcodeScanning.getClient(this, o).startScan()
                    .addOnSuccessListener(b -> koppeln(b.getRawValue()))
                    .addOnFailureListener(e -> {
                        fehler = "Der QR-Scanner konnte nicht starten (er braucht die Google Play-Dienste und lädt sich beim ersten Mal kurz herunter): " + e.getMessage();
                        anzeigen();
                    });
        } catch (Exception e) {
            fehler = "Der QR-Scanner ist auf diesem Handy nicht verfügbar: " + e.getMessage();
            anzeigen();
        }
    }

    void koppeln(String text) {
        try {
            Verbindung.Kopplung k = Verbindung.lesen(text);
            prefs.edit().putString("kopplung", k.alsText()).remove("meldung_letzte").apply();
            warten = null;
            daten = null;
            fehler = null;
            benachrichtigungenStarten();
            laden();
        } catch (Verbindung.Fehler f) {
            fehler = f.getMessage();
            anzeigen();
        }
    }

    void trennenFragen() {
        if (prefs.getString("kopplung", null) == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Vom PC trennen?")
                .setMessage("Der gespeicherte Schlüssel wird gelöscht. Zum erneuten Verbinden musst du am PC den Kopplungs-Code wieder scannen.")
                .setPositiveButton("Trennen", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        prefs.edit().remove("kopplung").remove("geraet").remove("meldung_letzte").apply();
                        Waechter.planen(getApplicationContext(), false);
                        warten = null;
                        daten = null;
                        fehler = null;
                        anzeigen();
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    // ---------- Heimnetz: nur WLAN/LAN, kein Mobilfunk, kein VPN ----------
    Network heimnetz() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        Network n = cm.getActiveNetwork();
        if (n == null) return null;
        NetworkCapabilities c = cm.getNetworkCapabilities(n);
        if (c == null || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null;
        if (!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null;
        return n;
    }

    // liegt die PC-Adresse im Teilnetz dieses WLANs?
    boolean imSelbenNetz(Network n, String ip) {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties lp = cm == null ? null : cm.getLinkProperties(n);
        if (lp == null) return false;
        byte[] ziel = Verbindung.zahlen(ip);
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (!(a instanceof Inet4Address)) continue;
            byte[] eigen = a.getAddress();
            int bits = la.getPrefixLength();
            if (bits < 8 || bits > 32) continue;
            boolean gleich = true;
            for (int i = 0; i < 32 && i < bits; i++) {
                int maske = 0x80 >> (i % 8);
                if ((eigen[i / 8] & maske) != (ziel[i / 8] & maske)) { gleich = false; break; }
            }
            if (gleich) return true;
        }
        return false;
    }

    // ---------- Laden (im Hintergrund) ----------
    void laden() {
        final String code = prefs.getString("kopplung", null);
        if (code == null) { anzeigen(); return; }
        if (laedt) return;
        final Network netz = heimnetz();
        final Verbindung.Kopplung k;
        try {
            k = Verbindung.lesen(code);
        } catch (Verbindung.Fehler f) {
            fehler = f.getMessage();
            anzeigen();
            return;
        }
        // Außerhalb des Heimnetzes: gar keine Verbindung versuchen und nichts anzeigen
        java.util.List<String> passend = new java.util.ArrayList<>();
        if (netz != null) for (String ip : k.adressen) if (imSelbenNetz(netz, ip)) passend.add(ip);
        if (netz == null || passend.isEmpty()) {
            imHeimnetz = false;
            warten = null;
            daten = null;
            fehler = null;
            anzeigen();
            return;
        }
        imHeimnetz = true;
        k.adressen = passend;
        laedt = true;
        aktualisiereKopf();
        final String gid = geraetId(), gname = geraetName();
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject neu = null;
                String f = null, w = null;
                try {
                    neu = new JSONObject(Verbindung.abrufen(k, netz.getSocketFactory(), gid, gname));
                    if (!neu.has("Depots")) throw new Verbindung.Fehler("Unerwartete Daten vom PC.");
                    Hinweise.pcErreichbar(getApplicationContext());
                    Hinweise.verarbeiten(getApplicationContext(), neu);
                } catch (Verbindung.Warten e) {
                    w = e.getMessage();
                } catch (Verbindung.Fehler e) {
                    f = e.getMessage();
                } catch (Exception e) {
                    f = "Die Daten vom PC konnten nicht gelesen werden: " + e.getMessage();
                }
                final JSONObject ergebnis = neu;
                final String fehlerText = f, wartenText = w;
                handler.post(new Runnable() {
                    @Override public void run() {
                        laedt = false;
                        if (ergebnis != null) daten = ergebnis;
                        fehler = fehlerText;
                        warten = wartenText;
                        // solange der PC fragt: alle 3 Sekunden nachsehen, ob erlaubt wurde
                        handler.removeCallbacks(nochmal);
                        if (wartenText != null) handler.postDelayed(nochmal, 3000);
                        anzeigen();
                    }
                });
            }
        }).start();
    }

    // ---------- Anzeige ----------
    void aktualisiereKopf() {
        StringBuilder s = new StringBuilder();
        if (daten != null) {
            s.append("Stand ").append(daten.optString("Stand", "–"));
            String pc = s(daten, "Rechner");
            if (pc.length() > 0) s.append(" · PC ").append(pc);
            long alter = System.currentTimeMillis() / 1000 - daten.optLong("StandUnix", 0);
            if (daten.optLong("StandUnix", 0) > 0 && alter > 600)
                s.append(" · ").append(alter / 60).append(" Min. alt");
        } else if (prefs.getString("kopplung", null) == null) s.append("Nicht gekoppelt");
        else if (!imHeimnetz) s.append("Nicht im Heimnetz – keine Verbindung");
        else s.append("Noch keine Daten");
        if (laedt) s.append(" · lädt …");
        kopfStatus.setText(s.toString());
        long alter = daten == null ? 0 : System.currentTimeMillis() / 1000 - daten.optLong("StandUnix", 0);
        kopfStatus.setTextColor(daten != null && alter > 600 ? GELB : GRAU);
        kopfPunkt.setTextColor(fehler != null || !imHeimnetz ? ROT : daten == null ? GRAU : alter > 600 ? GELB : GRUEN);
        for (int i = 0; i < REITER.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(8));
            g.setColor(i == reiter ? BLAU : KARTE);
            reiterKnoepfe[i].setBackground(g);
            reiterKnoepfe[i].setTextColor(i == reiter ? Color.WHITE : TEXT);
        }
    }

    void anzeigen() {
        aktualisiereKopf();
        inhalt.removeAllViews();
        if (fehler != null) inhalt.addView(hinweis("⚠  Keine Verbindung zum PC\n" + fehler, ROT));
        if (warten != null && prefs.getString("kopplung", null) != null) inhalt.addView(hinweis("⏳ " + warten, GELB));
        if (prefs.getString("kopplung", null) == null) {
            inhalt.addView(hinweis("Willkommen! So koppelst du den Satelliten mit deinem PC:\n\n" +
                    "1. Das Handy ins selbe WLAN bringen, in dem dein PC steht.\n\n" +
                    "2. Am PC in Aktien-Graphen unter Einstellungen › Synchronisation den Handy-Satelliten einschalten und „Kopplungs-Code zeigen“ klicken.\n\n" +
                    "3. Hier oben „QR-Code scannen“ tippen und den Code vom PC-Bildschirm scannen.\n\n" +
                    "4. Am PC erscheint eine Abfrage – dort „Erlauben“ klicken.\n\n" +
                    "Danach holt die App die Daten jede Minute direkt vom PC – nur in deinem Heimnetz, verschlüsselt und ohne Cloud. " +
                    "Am PC kann sie nur eines: per NOTAUS sofort jeden Handel stoppen. Freigeben geht nur am PC.", TEXT));
            return;
        }
        if (!imHeimnetz) {
            inhalt.addView(hinweis("Du bist nicht in deinem Heimnetz – deshalb gibt es keine Verbindung und keine Anzeige.\n\n" +
                    "Der Satellit verbindet sich nur über das WLAN, in dem dein PC steht – nie über Mobilfunk oder VPN. " +
                    "Sobald du wieder zu Hause im WLAN bist, lädt er automatisch.", GELB));
            return;
        }
        if (daten == null) {
            if (fehler == null) inhalt.addView(hinweis("Lade Daten …", GRAU));
            return;
        }
        long alter = System.currentTimeMillis() / 1000 - daten.optLong("StandUnix", 0);
        if (daten.optLong("StandUnix", 0) > 0 && alter > 600)
            inhalt.addView(hinweis("Der Stand vom PC ist " + (alter / 60) + " Minuten alt. Läuft Aktien-Graphen am PC noch, und stimmt die Uhrzeit?", GELB));
        switch (reiter) {
            case 0: positionen(); break;
            case 1: empfehlungen(); break;
            case 2: depots(false); break;
            case 3: depots(true); break;
            case 4: auswertung(); break;
            case 5: backtest(); break;
            default: system(); break;
        }
    }

    // ---------- NOTAUS: einziger Befehl ans PC-Programm ----------
    String notausErgebnis;
    int notausFarbe = GRAU;
    boolean notausLaeuft;

    void notausBereich() {
        JSONObject na = daten.optJSONObject("Notaus");
        if (notausErgebnis != null) inhalt.addView(hinweis(notausErgebnis, notausFarbe));
        if (na != null && na.optBoolean("Aktiv", false)) {
            inhalt.addView(hinweis("■ " + s(na, "Text") + ".\nFreigeben geht nur am PC (rote Leiste oben im Programm).", ROT));
            return;
        }
        TextView k = text(notausLaeuft ? "NOTAUS wird gesendet …" : "■  NOTAUS – allen Handel am PC stoppen", 16, Color.WHITE, true);
        k.setGravity(Gravity.CENTER);
        k.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(Color.rgb(170, 25, 35));
        k.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        k.setLayoutParams(lp);
        k.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { notausFragen(); } });
        inhalt.addView(k);
    }

    void notausFragen() {
        if (notausLaeuft) return;
        new AlertDialog.Builder(this)
                .setTitle("NOTAUS")
                .setMessage("Jetzt sofort jeden Handel am PC stoppen?\n\nAlle Depots (Übungs- und Realdepots) handeln danach nicht mehr – auch keine Schutzverkäufe –, " +
                        "bis du den Notaus am PC wieder aufhebst. Freigeben geht nur am PC.")
                .setPositiveButton("STOPPEN", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { notausSenden(); }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    void notausSenden() {
        final String code = prefs.getString("kopplung", null);
        final Network netz = heimnetz();
        final Verbindung.Kopplung k;
        try { k = Verbindung.lesen(code); } catch (Verbindung.Fehler f) { notausErgebnis = f.getMessage(); notausFarbe = ROT; anzeigen(); return; }
        java.util.List<String> passend = Heimnetz.passend(this, netz, k);
        if (netz == null || passend.isEmpty()) {
            notausErgebnis = "Notaus NICHT gesendet: Das Handy ist nicht im Heimnetz des PCs.";
            notausFarbe = ROT;
            anzeigen();
            return;
        }
        k.adressen = passend;
        notausLaeuft = true;
        anzeigen();
        final String gid = geraetId(), gname = geraetName();
        new Thread(new Runnable() {
            @Override public void run() {
                String erg; int farbe;
                try {
                    JSONObject a = new JSONObject(Verbindung.notaus(k, netz.getSocketFactory(), gid, gname));
                    if (!a.optBoolean("Notaus", false)) throw new Verbindung.Fehler("Der PC hat den Notaus nicht bestätigt.");
                    erg = "✓ Vom PC bestätigt: " + s(a, "Text") + " (" + a.optInt("Depots", 0) + " aktive Depots angehalten).";
                    farbe = GELB;
                } catch (Exception e) {
                    erg = "Notaus NICHT angekommen: " + e.getMessage() + "\nBitte am PC selbst stoppen (Reiter System › NOTAUS).";
                    farbe = ROT;
                }
                final String e2 = erg; final int f2 = farbe;
                handler.post(new Runnable() {
                    @Override public void run() {
                        notausLaeuft = false;
                        notausErgebnis = e2;
                        notausFarbe = f2;
                        laden();
                        anzeigen();
                    }
                });
            }
        }).start();
    }

    void meldungenBereich() {
        JSONArray m = daten.optJSONArray("Meldungen");
        if (m == null || m.length() == 0) return;
        LinearLayout k = karte();
        k.addView(text("Letzte Meldungen", 15, TEXT, true));
        for (int i = m.length() - 1, n = 0; i >= 0 && n < 4; i--, n++) {
            JSONObject e = m.optJSONObject(i);
            if (e == null) continue;
            String zeit = new java.text.SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(new java.util.Date(e.optLong("Zeit", 0) * 1000));
            String art = s(e, "Art");
            int farbe = art.equals("notaus") || art.equals("bremse") || art.equals("daten") ? ROT : art.equals("gewinn") ? GRUEN : BLAU;
            k.addView(text(zeit + " · " + s(e, "Titel"), 13, farbe, true));
            TextView t = text(s(e, "Text"), 12, GRAU, false);
            t.setPadding(0, 0, 0, dp(6));
            k.addView(t);
        }
        inhalt.addView(k);
    }

    void einstellungenBereich() {
        LinearLayout k = karte();
        k.addView(text("Benachrichtigungen", 15, TEXT, true));
        k.addView(text("Etwa alle 15 Minuten fragt das Handy im Heimnetz beim PC nach neuen Meldungen: Notaus, Verlustbremse, großer Gewinn, " +
                "Depot steht still, Datenquelle gestört, PC nicht erreichbar – und um 20 Uhr der Tagesbericht. Außerhalb des Heimnetzes gibt es keine.", 12, GRAU, false));
        LinearLayout z = new LinearLayout(this);
        z.setOrientation(LinearLayout.HORIZONTAL);
        z.setPadding(0, dp(8), 0, 0);
        final boolean an = prefs.getBoolean("melden", true), bericht = prefs.getBoolean("bericht", true);
        TextView k1 = knopf(an ? "Meldungen: an" : "Meldungen: aus");
        k1.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putBoolean("melden", !an).apply();
                benachrichtigungenStarten();
                anzeigen();
            }
        });
        TextView k2 = knopf(bericht ? "Tagesbericht: an" : "Tagesbericht: aus");
        k2.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { prefs.edit().putBoolean("bericht", !bericht).apply(); anzeigen(); }
        });
        z.addView(k1);
        z.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        z.addView(k2);
        k.addView(z);
        inhalt.addView(k);
    }
}
