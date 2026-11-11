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
public class MainActivity extends Activity {

    static final long NEU_LADEN_MS = 60000;

    // Farben (Nachtblau, wie am PC)
    static final int HINTERGRUND = Color.rgb(8, 14, 34);
    static final int KARTE = Color.rgb(17, 31, 66);
    static final int KARTE_HELL = Color.rgb(28, 46, 92);
    static final int TEXT = Color.rgb(230, 236, 248);
    static final int GRAU = Color.rgb(140, 156, 190);
    static final int BLAU = Color.rgb(77, 163, 255);
    static final int GRUEN = Color.rgb(61, 220, 132);
    static final int ROT = Color.rgb(255, 92, 92);
    static final int GELB = Color.rgb(255, 200, 70);

    SharedPreferences prefs;
    final Handler handler = new Handler(Looper.getMainLooper());
    LinearLayout inhalt;
    TextView kopfStatus;
    static final String[] REITER = { "Positionen", "Empfehlungen", "Übungsdepot", "Realdepot", "Auswertung", "Backtest", "System" };
    TextView[] reiterKnoepfe = new TextView[REITER.length];
    int reiter = 0;
    JSONObject daten;
    String fehler;
    boolean laedt;
    boolean imHeimnetz = true;
    String warten;   // Hinweis, solange der PC noch nicht bestätigt hat

    final Runnable nochmal = new Runnable() { @Override public void run() { laden(); } };

    // zufällige Kennung dieses Handys (neu nach "Trennen", damit der PC erneut fragt)
    String geraetId() {
        String id = prefs.getString("geraet", null);
        if (id == null) {
            byte[] z = new byte[16];
            new java.security.SecureRandom().nextBytes(z);
            id = Verbindung.hex(z);
            prefs.edit().putString("geraet", id).apply();
        }
        return id;
    }

    static String geraetName() {
        String h = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER, m = Build.MODEL == null ? "" : Build.MODEL;
        String n = m.toLowerCase(Locale.ROOT).startsWith(h.toLowerCase(Locale.ROOT)) ? m : (h + " " + m);
        n = n.trim();
        return n.length() == 0 ? "Android-Handy" : (n.length() > 60 ? n.substring(0, 60) : n);
    }
    final Set<String> zugeklappt = new HashSet<>();

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
        kopf.setPadding(dp(16), dp(14), dp(16), dp(6));
        TextView titel = text("Aktien-Satellit", 22, TEXT, true);
        kopf.addView(titel);
        kopfStatus = text("", 12, GRAU, false);
        kopf.addView(kopfStatus);

        LinearLayout knoepfe = new LinearLayout(this);
        knoepfe.setOrientation(LinearLayout.HORIZONTAL);
        knoepfe.setPadding(0, dp(8), 0, 0);
        TextView neu = knopf("Neu laden");
        neu.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { laden(); } });
        TextView koppeln = knopf("QR-Code scannen");
        koppeln.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { scannen(); } });
        TextView trennen = knopf("Trennen");
        trennen.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { trennenFragen(); } });
        knoepfe.addView(neu);
        knoepfe.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        knoepfe.addView(koppeln);
        knoepfe.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
        knoepfe.addView(trennen);
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
            TextView t = text(REITER[i], 13, TEXT, true);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(dp(2), dp(9), dp(2), dp(9));
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
        s.append(" · Ansicht + Notaus");
        if (laedt) s.append(" · lädt …");
        kopfStatus.setText(s.toString());
        long alter = daten == null ? 0 : System.currentTimeMillis() / 1000 - daten.optLong("StandUnix", 0);
        kopfStatus.setTextColor(daten != null && alter > 600 ? GELB : GRAU);
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
        if (fehler != null) inhalt.addView(hinweis(fehler, ROT));
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

    void depots(boolean real) {
        notausBereich();
        if (!real) meldungenBereich();
        else inhalt.addView(hinweis("Realdepots sind ein Echtgeld-PROBELAUF: Sie handeln mit den Regeln des gewählten Brokers, es fließt aber kein echtes Geld – jede Order wird am PC nur protokolliert.", GELB));
        depotListe(real);
        if (!real) einstellungenBereich();
    }

    void depotListe(boolean real) {
        JSONArray l = daten.optJSONArray("Depots");
        int n = 0;
        if (l != null) for (int i = 0; i < l.length(); i++) { JSONObject d = l.optJSONObject(i); if (d != null && d.optBoolean("Real", false) == real) n++; }
        if (n == 0) { inhalt.addView(hinweis(real ? "Keine Realdepots vorhanden – anlegen am PC im Reiter „Realdepot“." : "Keine Übungsdepots vorhanden.", GRAU)); return; }
        for (int i = 0; i < l.length(); i++) {
            JSONObject d = l.optJSONObject(i);
            if (d == null || d.optBoolean("Real", false) != real) continue;
            final String name = d.optString("Name", "Depot");
            String w = waehrung(d.optString("Waehrung", "EUR"));
            LinearLayout karte = karte();

            // Kopf der Karte (antippen = auf-/zuklappen)
            LinearLayout zeile = new LinearLayout(this);
            zeile.setOrientation(LinearLayout.HORIZONTAL);
            zeile.setGravity(Gravity.CENTER_VERTICAL);
            TextView tn = text(name, 17, TEXT, true);
            zeile.addView(tn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            JSONObject na0 = daten.optJSONObject("Notaus");
            boolean notaus = na0 != null && na0.optBoolean("Aktiv", false);
            TextView aktiv = text(notaus ? "NOTAUS" : d.optBoolean("Aktiv", true) ? "aktiv" : "pausiert", 11, notaus ? ROT : d.optBoolean("Aktiv", true) ? GRUEN : GELB, true);
            zeile.addView(aktiv);
            karte.addView(zeile);
            karte.addView(text(geld(d.optDouble("Wert", 0), w), 26, TEXT, true));
            double gv = d.optDouble("Gv", 0), gvp = d.optDouble("GvProzent", 0);
            karte.addView(text((gv >= 0 ? "+" : "−") + geld(Math.abs(gv), w) + "  (" + prozent(gvp) + ") seit Start", 14, gv >= 0 ? GRUEN : ROT, false));
            karte.addView(text(s(d, "Laufzeit"), 12, GRAU, false));
            if (d.optBoolean("Real", false))
                karte.addView(text("Echtgeld-Probelauf · " + s(d, "Broker") + " · Orders werden nur protokolliert", 12, GELB, false));
            // gesicherter Gewinn und Verlustbremse auch im zugeklappten Zustand
            double ges = d.optDouble("Gesichert", 0);
            if (ges > 0.004 || d.optDouble("GewinnSicherung", 0) > 0)
                karte.addView(text("Gesicherter Gewinn " + geld(ges, w), 13, GRUEN, true));
            if (d.optBoolean("Gebremst", false))
                karte.addView(text("Verlustbremse aktiv – kauft nicht mehr (" + String.format(Locale.GERMANY, "%.1f", d.optDouble("Rueckgang", 0)) + " % unter Höchststand)", 13, ROT, true));
            final boolean zu = zugeklappt.contains(name);
            View.OnClickListener klapp = new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (zu) zugeklappt.remove(name); else zugeklappt.add(name);
                    anzeigen();
                }
            };
            karte.setOnClickListener(klapp);
            if (zu) { inhalt.addView(karte); continue; }

            String status = s(d, "Status");
            if (status.length() > 0) karte.addView(text("Status: " + status, 12, BLAU, false));

            // Verlauf
            JSONArray v = d.optJSONArray("Verlauf");
            if (v != null && v.length() >= 2) {
                double[] t = new double[v.length()], y = new double[v.length()];
                for (int k = 0; k < v.length(); k++) {
                    JSONArray p = v.optJSONArray(k);
                    t[k] = p == null ? 0 : p.optDouble(0, 0);
                    y[k] = p == null ? 0 : p.optDouble(1, 0);
                }
                Verlaufsgraph g = new Verlaufsgraph(this, t, y, d.optDouble("Startkapital", y[0]));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
                lp.setMargins(0, dp(8), 0, dp(4));
                karte.addView(g, lp);
            }

            karte.addView(text("Bargeld " + geld(d.optDouble("Bargeld", 0), w) + " · " + d.optInt("Trades", 0) + " Trades · Kosten " +
                    geld(d.optDouble("Gebuehren", 0), w), 12, GRAU, false));
            StringBuilder zusatz = new StringBuilder();
            if (d.optBoolean("SteuerAn", false)) zusatz.append("Steuer dieses Jahr ").append(geld(d.optDouble("SteuerJahr", 0), w));
            if (d.optDouble("Einzahlung", 0) > 0) {
                if (zusatz.length() > 0) zusatz.append(" · ");
                zusatz.append("Einzahlung ").append(geld(d.optDouble("Einzahlung", 0), w)).append(" ").append(s(d, "EinzahlungTakt"))
                      .append(" · eingezahlt ").append(geld(d.optDouble("Eingezahlt", 0), w));
            }
            if (d.optDouble("Verlustbremse", 0) > 0 && !d.optBoolean("Gebremst", false)) {
                if (zusatz.length() > 0) zusatz.append(" · ");
                zusatz.append("Verlustbremse ").append(String.format(Locale.GERMANY, "%.1f / %.0f %%", d.optDouble("Rueckgang", 0), d.optDouble("Verlustbremse", 0)));
            }
            if (zusatz.length() > 0) karte.addView(text(zusatz.toString(), 12, GRAU, false));
            karte.addView(text(s(d, "Strategie") + " · " + s(d, "Ziel") + " · Tempo " + s(d, "Tempo") +
                    " · " + s(d, "Handel"), 12, GRAU, false));

            JSONArray b = d.optJSONArray("Bestand");
            karte.addView(abschnitt("Depotbestand"));
            if (b == null || b.length() == 0) karte.addView(text("nur Bargeld", 13, GRAU, false));
            else for (int k = 0; k < b.length(); k++) {
                JSONObject p = b.optJSONObject(k);
                if (p == null) continue;
                String richtung = s(p, "Richtung");
                String links = s(p, "Name") + (richtung.length() > 0 ? "  · " + richtung : "");
                double erg = p.optDouble("Ergebnis", Double.NaN);
                karte.addView(zweispaltig(links, geld(p.optDouble("Wert", 0), w), TEXT, TEXT));
                if (!Double.isNaN(erg))
                    karte.addView(zweispaltig(Double.isNaN(p.optDouble("Anteil", Double.NaN)) ? "" : prozentOhne(p.optDouble("Anteil", 0)) + " des Depots",
                            prozent(erg), GRAU, erg >= 0 ? GRUEN : ROT));
            }

            JSONArray bew = d.optJSONArray("Bewegungen");
            karte.addView(abschnitt("Letzte Kauf- und Verkaufsbewegungen"));
            if (bew == null || bew.length() == 0) karte.addView(text("noch keine", 13, GRAU, false));
            else for (int k = 0; k < bew.length() && k < 15; k++) {
                JSONObject t = bew.optJSONObject(k);
                if (t == null) continue;
                boolean kauf = t.optBoolean("Kauf", false);
                boolean verlust = !t.isNull("Gv") && t.optDouble("Gv", 0) < 0;
                int farbe = kauf ? ROT : (verlust ? ROT : GRUEN);   // Kauf rot, Verkauf grün bei Gewinn, rot bei Verlust
                String betrag = (kauf ? "−" : "+") + geld(t.optDouble("Betrag", 0), w);
                karte.addView(zweispaltig(s(t, "Zeit") + "  " + s(t, "Art") + "  " + s(t, "Name"), betrag, TEXT, farbe));
                String erg = t.isNull("Ergebnis") ? "" : s(t, "Ergebnis");
                String grund = s(t, "Grund");
                if (t.optBoolean("Nachgeholt", false)) grund = "nachgeholt · " + grund;
                if (erg.length() > 0) karte.addView(text(erg, 12, farbe, true));
                if (grund.length() > 0) karte.addView(text(grund, 11, GRAU, false));
            }
            inhalt.addView(karte);
        }
        inhalt.addView(text("Tippe auf ein Depot, um es auf- oder zuzuklappen.", 11, GRAU, false));
    }

    // ---------- Reiter Auswertung: Berichte je Zeitraum (mit Schätzung) und Rangliste ----------
    void auswertung() {
        JSONObject a = daten.optJSONObject("Auswertung");
        if (a == null) { inhalt.addView(hinweis("Der PC liefert noch keine Auswertung – bitte Aktien-Graphen am PC aktualisieren.", GELB)); return; }
        JSONArray r = a.optJSONArray("Rangliste");
        if (r != null && r.length() > 0) {
            LinearLayout k = karte();
            k.addView(text("Rangliste", 16, TEXT, true));
            for (int i = 0; i < r.length(); i++) {
                JSONObject p = r.optJSONObject(i);
                if (p == null) continue;
                double rd = p.optDouble("Rendite", Double.NaN);
                k.addView(zweispaltig(p.optInt("Platz", i + 1) + ". " + (p.optBoolean("Real", false) ? "[Real] " : "") + s(p, "Name"), pz(rd), TEXT, farbe(rd)));
                double pj = p.optDouble("ProJahr", Double.NaN), tq = p.optDouble("Trefferquote", Double.NaN);
                TextView z = text("läuft " + s(p, "Laufzeit") + (Double.isNaN(pj) ? "" : " · aufs Jahr " + pz(pj)) + " · Rückgang −" + zahl1(p.optDouble("MaxRueckgang", 0)) + " %" +
                        " · " + p.optInt("Trades", 0) + " Trades" + (Double.isNaN(tq) ? "" : " · Treffer " + zahl0(tq) + " %"), 12, GRAU, false);
                z.setPadding(0, 0, 0, dp(6));
                k.addView(z);
            }
            inhalt.addView(k);
        }
        JSONArray l = a.optJSONArray("Depots");
        if (l == null || l.length() == 0) { inhalt.addView(hinweis("Noch keine Depots – Berichte erscheinen, sobald am PC ein Depot läuft.", GRAU)); return; }
        for (int i = 0; i < l.length(); i++) {
            JSONObject d = l.optJSONObject(i);
            if (d == null) continue;
            String w = waehrung(d.optString("Waehrung", "EUR"));
            LinearLayout k = karte();
            k.addView(text((d.optBoolean("Real", false) ? "Realdepot · " : "") + s(d, "Name"), 16, TEXT, true));
            JSONArray b = d.optJSONArray("Berichte");
            if (b != null) for (int j = 0; j < b.length(); j++) {
                JSONObject e = b.optJSONObject(j);
                if (e == null) continue;
                boolean sch = e.optBoolean("Schaetzung", false);
                double rd = e.optDouble("Rendite", Double.NaN), erg = e.optDouble("Ergebnis", Double.NaN);
                k.addView(abschnitt(s(e, "Zeitraum") + (sch ? "  (geschätzt)" : "")));
                if (e.optBoolean("ZuWenig", false)) { k.addView(text(s(e, "Hinweis").length() > 0 ? s(e, "Hinweis") : "Noch zu wenig Daten.", 12, GRAU, false)); continue; }
                k.addView(zweispaltig("Rendite" + (sch ? " ≈" : ""), pz(rd) + (Double.isNaN(erg) ? "" : "  (" + (erg >= 0 ? "+" : "−") + geld(Math.abs(erg), w) + ")"), GRAU, sch ? GELB : farbe(rd)));
                if (sch) k.addView(zweispaltig("Spanne (wahrscheinlich)", pz(e.optDouble("SpanneVon", Double.NaN)) + " bis " + pz(e.optDouble("SpanneBis", Double.NaN)), GRAU, GELB));
                else if (!e.isNull("Vergleich")) k.addView(zweispaltig("Vergleich " + s(e, "VergleichName"), pz(e.optDouble("Vergleich", Double.NaN)), GRAU, farbe(e.optDouble("Vergleich", Double.NaN))));
                k.addView(zweispaltig("Größter Rückgang · Käufe/Verkäufe", "−" + zahl1(e.optDouble("MaxRueckgang", 0)) + " % · " + zahl0(e.optDouble("Kaeufe", 0)) + "/" + zahl0(e.optDouble("Verkaeufe", 0)), GRAU, TEXT));
                double tq = e.optDouble("Trefferquote", Double.NaN);
                k.addView(zweispaltig("Trefferquote · Gebühren · Steuer", (Double.isNaN(tq) ? "–" : zahl0(tq) + " %") + " · " + geld(e.optDouble("Gebuehren", 0), w) + " · " + geld(e.optDouble("Steuer", 0), w), GRAU, TEXT));
                if (e.optDouble("Gesichert", 0) > 0.004) k.addView(zweispaltig("Gesichert", geld(e.optDouble("Gesichert", 0), w), GRAU, GRUEN));
            }
            inhalt.addView(k);
        }
    }

    // ---------- Reiter Backtest: letztes Ergebnis vom PC (gestartet wird nur am PC) ----------
    void backtest() {
        JSONObject b = daten.optJSONObject("Backtest");
        if (b == null) { inhalt.addView(hinweis("Der PC liefert noch keine Backtest-Daten – bitte Aktien-Graphen am PC aktualisieren.", GELB)); return; }
        String status = s(b, "Status");
        inhalt.addView(hinweis((b.optBoolean("Laeuft", false) ? "⏳ " : "") + (status.length() > 0 ? status : "Noch kein Backtest gerechnet.") +
                "\nGestartet wird der Backtest am PC (Reiter „Backtest“) – hier siehst du das Ergebnis.", b.optBoolean("Laeuft", false) ? GELB : GRAU));
        if (!b.has("Name")) return;
        String w = waehrung(b.optString("Waehrung", "EUR"));
        LinearLayout k = karte();
        int n = b.optInt("Durchlaeufe", 1);
        k.addView(text("Backtest: „" + s(b, "Name") + "“ · " + b.optInt("Tage", 0) + " Tage", 16, TEXT, true));
        if (n > 1) k.addView(text("mittlerer Durchlauf (Nr. " + b.optInt("MittelNr", 1) + " von " + n + ")", 12, GRAU, false));
        k.addView(text(s(b, "Einstellungen"), 12, GRAU, false));
        double rd = b.optDouble("Rendite", Double.NaN);
        k.addView(text(geld(b.optDouble("Startkapital", 0), w) + " → " + geld(b.optDouble("Endwert", 0), w), 20, TEXT, true));
        k.addView(text(pz(rd), 15, farbe(rd), true));
        JSONArray v = b.optJSONArray("Verlauf");
        if (v != null && v.length() >= 2) {
            double[] t = new double[v.length()], y = new double[v.length()];
            for (int i = 0; i < v.length(); i++) { JSONArray p = v.optJSONArray(i); t[i] = p == null ? 0 : p.optDouble(0, 0); y[i] = p == null ? 0 : p.optDouble(1, 0); }
            Verlaufsgraph g = new Verlaufsgraph(this, t, y, b.optDouble("Startkapital", y[0]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
            lp.setMargins(0, dp(8), 0, dp(4));
            k.addView(g, lp);
        }
        double tq = b.optDouble("Trefferquote", Double.NaN);
        k.addView(zweispaltig("Größter Rückgang", "−" + zahl1(b.optDouble("MaxRueckgang", 0)) + " %", GRAU, TEXT));
        k.addView(zweispaltig("Käufe / Verkäufe · Treffer", b.optInt("Kaeufe", 0) + " / " + b.optInt("Verkaeufe", 0) + (Double.isNaN(tq) ? "" : " · " + zahl0(tq) + " %"), GRAU, TEXT));
        if (s(b, "DatenInfo").length() > 0) k.addView(text(s(b, "DatenInfo"), 11, GRAU, false));
        inhalt.addView(k);

        JSONObject vg = b.optJSONObject("Vergleich");
        if (vg != null) {
            LinearLayout kv = karte();
            kv.addView(text("Gegenprobe: Zufall und „einfach halten“", 15, TEXT, true));
            kv.addView(text(s(vg, "Urteil"), 13, BLAU, false));
            kv.addView(zweispaltig("Deine Einstellungen", pz(vg.optDouble("Strategie", Double.NaN)), GRAU, farbe(vg.optDouble("Strategie", Double.NaN))));
            kv.addView(zweispaltig("Zufallshandel (Median)", pz(vg.optDouble("ZufallMedian", Double.NaN)), GRAU, farbe(vg.optDouble("ZufallMedian", Double.NaN))));
            kv.addView(zweispaltig("Zufall: schlechtester … bester", pz(vg.optDouble("ZufallMin", Double.NaN)) + " … " + pz(vg.optDouble("ZufallMax", Double.NaN)), GRAU, TEXT));
            kv.addView(zweispaltig("Besser als Zufall", vg.optInt("BesserAlsZufall", 0) + " von " + vg.optInt("Zufaelle", 0), GRAU, TEXT));
            JSONArray etfs = vg.optJSONArray("Etfs");
            if (etfs != null) for (int i = 0; i < etfs.length(); i++) {
                JSONObject e = etfs.optJSONObject(i);
                if (e != null) kv.addView(zweispaltig("Halten: " + s(e, "Name"), pz(e.optDouble("Rendite", Double.NaN)), GRAU, farbe(e.optDouble("Rendite", Double.NaN))));
            }
            inhalt.addView(kv);
        }
        JSONObject em = b.optJSONObject("Empfehlung");
        if (em != null) {
            LinearLayout ke = karte();
            ke.addView(text("Empfehlung: beste Einstellungen", 15, TEXT, true));
            ke.addView(text(s(em, "Urteil"), 13, em.optBoolean("Robust", false) ? GRUEN : GELB, false));
            JSONArray ae = em.optJSONArray("Aenderungen");
            if (ae != null) for (int i = 0; i < ae.length(); i++) {
                JSONObject a = ae.optJSONObject(i);
                if (a != null) ke.addView(zweispaltig(s(a, "Name"), s(a, "Bisher") + " → " + s(a, "Neu"), GRAU, GRUEN));
            }
            ke.addView(text("Übernehmen geht am PC („Empfehlung als neues Depot anlegen“).", 11, GRAU, false));
            inhalt.addView(ke);
        }
        JSONObject st = b.optJSONObject("Statistik");
        if (st != null) {
            LinearLayout ks = karte();
            ks.addView(text("Abweichung über " + st.optInt("N", 0) + " Durchläufe", 15, TEXT, true));
            ks.addView(text(s(st, "Urteil"), 13, BLAU, false));
            ks.addView(zweispaltig("Rendite Ø / Median", pz(st.optDouble("Mittel", Double.NaN)) + " / " + pz(st.optDouble("Median", Double.NaN)), GRAU, farbe(st.optDouble("Median", Double.NaN))));
            ks.addView(zweispaltig("Schlechtester … bester", pz(st.optDouble("Min", Double.NaN)) + " … " + pz(st.optDouble("Max", Double.NaN)), GRAU, TEXT));
            ks.addView(zweispaltig("Streuung", "± " + zahl1(st.optDouble("Std", 0)) + " Punkte", GRAU, TEXT));
            ks.addView(zweispaltig("Im Gewinn · gleiche Käufe", zahl0(st.optDouble("AnteilGewinn", 0)) + " % · " + zahl0(st.optDouble("Uebereinstimmung", 0)) + " %", GRAU, TEXT));
            JSONArray la = st.optJSONArray("Laeufe");
            if (la != null) for (int i = 0; i < la.length(); i++) {
                JSONObject l = la.optJSONObject(i);
                if (l == null) continue;
                double r = l.optDouble("Rendite", Double.NaN);
                ks.addView(zweispaltig("Durchlauf " + l.optInt("Nr", 0) + (l.optInt("Versatz", 0) > 0 ? " (+" + l.optInt("Versatz", 0) + " Std.)" : " (echte Kurse)") + " · " + l.optInt("Trades", 0) + " Trades",
                        pz(r), GRAU, farbe(r)));
            }
            inhalt.addView(ks);
        }
        JSONArray ps = b.optJSONArray("Positionen");
        LinearLayout kp = karte();
        kp.addView(text("Portfolio am Ende des Backtests", 15, TEXT, true));
        if (ps == null || ps.length() == 0) kp.addView(text("Am Ende war alles verkauft – nur Bargeld.", 13, GRAU, false));
        else for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p == null) continue;
            double r = p.optDouble("Rendite", Double.NaN);
            kp.addView(zweispaltig(s(p, "Name") + (p.optBoolean("Short", false) ? " (Short)" : ""), geld(p.optDouble("Wert", 0), w) + "  " + pz(r), TEXT, farbe(r)));
            TextView z = text("24 Std. " + pz(p.optDouble("Ver24", Double.NaN)) + " · 7 Tage " + pz(p.optDouble("Ver7", Double.NaN)) + " · " + s(p, "Richtung") + " · " + s(p, "Zustand"), 12, GRAU, false);
            z.setPadding(0, 0, 0, dp(6));
            kp.addView(z);
        }
        kp.addView(text("Bargeld " + geld(b.optDouble("Bargeld", 0), w) + (b.optDouble("Gesichert", 0) > 0.004 ? " · gesichert " + geld(b.optDouble("Gesichert", 0), w) : ""), 12, GRAU, false));
        inhalt.addView(kp);
    }

    // ---------- Reiter System: Gesundheit, Notaus, Datensicherung ----------
    void system() {
        notausBereich();
        JSONObject sy = daten.optJSONObject("System");
        if (sy == null) { inhalt.addView(hinweis("Der PC liefert noch keine System-Daten – bitte Aktien-Graphen am PC aktualisieren.", GELB)); return; }
        LinearLayout k = karte();
        k.addView(text("Gesundheit", 16, TEXT, true));
        JSONArray g = sy.optJSONArray("Gesundheit");
        if (g != null) for (int i = 0; i < g.length(); i++) {
            JSONObject p = g.optJSONObject(i);
            if (p == null) continue;
            int st = p.optInt("Stufe", 0);
            int f = st >= 2 ? ROT : st == 1 ? GELB : st == 0 ? GRUEN : GRAU;
            k.addView(text("●  " + s(p, "Name"), 14, f, true));
            TextView z = text(s(p, "Text"), 12, GRAU, false);
            z.setPadding(dp(20), 0, 0, dp(6));
            k.addView(z);
        }
        inhalt.addView(k);
        abfragenKarte(sy.optJSONObject("Abfragen"));
        LinearLayout ks = karte();
        ks.addView(text("Datensicherung", 16, TEXT, true));
        JSONArray l = sy.optJSONArray("Sicherungen");
        if (l == null || l.length() == 0) ks.addView(text("Noch keine Sicherung.", 13, GRAU, false));
        else for (int i = 0; i < l.length(); i++) ks.addView(text(l.optString(i, ""), 12, GRAU, false));
        ks.addView(text("Sichern und Wiederherstellen geht am PC (Reiter „System“).", 11, GRAU, false));
        inhalt.addView(ks);
        einstellungenBereich();
    }

    // Abfragen & Auslastung: wie intensiv der PC gerade Daten abfragt (erlaubt / eingestellt / verbraucht)
    void abfragenKarte(JSONObject a) {
        if (a == null) return;
        LinearLayout k = karte();
        int st = a.optInt("Stufe", 0);
        int f = st >= 3 ? ROT : st == 2 ? GELB : st == 1 ? BLAU : GRUEN;
        k.addView(text("Abfragen & Auslastung", 16, TEXT, true));
        k.addView(text(s(a, "StufeText"), 14, f, true));
        int std = a.optInt("YahooStd", 0), rw = Math.max(1, a.optInt("Richtwert", 2000));
        double anteil = Math.min(1.0, std / (double) rw);
        LinearLayout balken = new LinearLayout(this);
        balken.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable hg = new GradientDrawable(); hg.setColor(KARTE_HELL); hg.setCornerRadius(dp(4));
        balken.setBackground(hg);
        View voll = new View(this);
        GradientDrawable vg = new GradientDrawable(); vg.setColor(f); vg.setCornerRadius(dp(4));
        voll.setBackground(vg);
        balken.addView(voll, new LinearLayout.LayoutParams(0, dp(10), (float) Math.max(0.01, anteil)));
        balken.addView(new View(this), new LinearLayout.LayoutParams(0, dp(10), (float) Math.max(0.0, 1 - anteil)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        lp.setMargins(0, dp(8), 0, dp(4));
        k.addView(balken, lp);
        k.addView(text("Yahoo, letzte 60 Min.: " + std + " Abfragen (" + Math.round(100.0 * std / rw) + " % vom Richtwert ≈ " + rw + "/Std.*)", 12, TEXT, false));
        double gp = a.optDouble("YahooGeplant", 0);
        k.addView(text("Eingestellt bei Yahoo: " + (gp < 0.5 ? "–" : "≈ " + zahl0(gp) + "/Std."), 12, GRAU, false));
        if (a.optBoolean("Auto", false)) {
            String at = "Automatik AN · Ziel „" + s(a, "Ziel") + "“ · Tempo " + a.optInt("Tempo", 100) + " % → ≈ " + a.optInt("ZielJetzt", 0) + "/Std.";
            int gr = a.optInt("Grenze", 0);
            if (gr > 0) at += " · Obergrenze ≈ " + gr + "/Std.";
            k.addView(text(at, 12, BLAU, true));
            JSONArray v = a.optJSONArray("Verlauf");
            if (v != null) for (int i = 0; i < v.length() && i < 3; i++) {
                TextView tv = text(v.optString(i, ""), 11, GRAU, false);
                tv.setPadding(dp(12), 0, 0, 0);
                k.addView(tv);
            }
        } else k.addView(text("Automatik aus – feste Grenzen (am PC einschaltbar: Einstellungen › Datenabfragen).", 11, GRAU, false));
        String pause = s(a, "Pause");
        if (pause.length() > 0) k.addView(text("⏸ Yahoo sperrt gerade (zu viele Abfragen) – Pause bis " + pause + " Uhr.", 12, ROT, true));
        abfragenListe(k, a.optJSONArray("Quellen"), "Nach Datenquelle", true);
        abfragenListe(k, a.optJSONArray("Zwecke"), "Wofür", false);
        k.addView(text("* Yahoo nennt keine offizielle Grenze; ≈ 2.000/Std. ist ein Erfahrungswert. „Eingestellt“ = was die aktuellen Einstellungen höchstens anfordern.", 11, GRAU, false));
        inhalt.addView(k);
    }

    void abfragenListe(LinearLayout k, JSONArray l, String titel, boolean erlaubt) {
        if (l == null || l.length() == 0) return;
        TextView t = text(titel, 14, TEXT, true);
        t.setPadding(0, dp(10), 0, dp(2));
        k.addView(t);
        for (int i = 0; i < l.length(); i++) {
            JSONObject r = l.optJSONObject(i);
            if (r == null) continue;
            k.addView(text(s(r, "Name"), 13, TEXT, true));
            double gp = r.optDouble("Geplant", 0);
            String z = (erlaubt ? "erlaubt " + s(r, "Erlaubt") + " · " : "")
                     + "eingestellt " + (gp < 0.5 ? "–" : "≈ " + zahl0(gp) + "/Std.")
                     + " · 60 Min.: " + r.optInt("Std", 0) + " · 24 Std.: " + r.optInt("Tag", 0);
            int fe = r.optInt("Fehler", 0), sp = r.optInt("Sperren", 0);
            if (fe > 0) z += " · Fehler: " + fe + (sp > 0 ? " (" + sp + "× 429)" : "");
            TextView zt = text(z, 12, sp > 0 ? GELB : GRAU, false);
            zt.setPadding(dp(12), 0, 0, dp(4));
            k.addView(zt);
        }
    }

    static int farbe(double v) { return Double.isNaN(v) ? GRAU : v >= 0 ? GRUEN : ROT; }
    static String pz(double v) { return Double.isNaN(v) ? "–" : prozent(v); }
    static String zahl0(double v) { return String.format(Locale.GERMANY, "%.0f", v); }
    static String zahl1(double v) { return String.format(Locale.GERMANY, "%.1f", v); }

    void empfehlungen() {
        JSONObject e = daten.optJSONObject("Empfehlungen");
        String[] schluessel = { "Aktien", "Etfs", "Krypto" };
        String[] titel = { "Aktien", "ETFs", "Krypto" };
        boolean leer = true;
        for (int k = 0; k < 3; k++) {
            JSONArray l = e == null ? null : e.optJSONArray(schluessel[k]);
            if (l == null || l.length() == 0) continue;
            leer = false;
            LinearLayout karte = karte();
            karte.addView(text(titel[k], 17, TEXT, true));
            for (int i = 0; i < l.length(); i++) {
                JSONObject w = l.optJSONObject(i);
                if (w == null) continue;
                int punkte = w.optInt("Punkte", 50);
                int farbe = punkte >= 60 ? GRUEN : punkte <= 40 ? ROT : GELB;
                String kz = s(w, "Kuerzel");
                LinearLayout z = new LinearLayout(this);
                z.setOrientation(LinearLayout.HORIZONTAL);
                z.setGravity(Gravity.CENTER_VERTICAL);
                z.setPadding(0, dp(6), 0, 0);
                z.addView(text((i + 1) + ".", 13, GRAU, false), new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));
                z.addView(text(s(w, "Name") + (kz.length() > 0 ? "  " + kz : ""), 14, TEXT, true),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView p = text(punkte + "  " + s(w, "Urteil"), 12, farbe, true);
                z.addView(p);
                karte.addView(z);
                String ch = aenderung(w, "Ch1h") + " / " + aenderung(w, "Ch1T") + " / " + aenderung(w, "Ch1W");
                LinearLayout z2 = new LinearLayout(this);
                z2.setOrientation(LinearLayout.HORIZONTAL);
                z2.setPadding(dp(30), 0, 0, 0);
                z2.addView(text(s(w, "Kurs"), 12, GRAU, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                double t1 = w.isNull("Ch1T") ? Double.NaN : w.optDouble("Ch1T", Double.NaN);
                z2.addView(text("1 Std / 1 Tag / 1 Woche: " + ch, 11, Double.isNaN(t1) ? GRAU : t1 >= 0 ? GRUEN : ROT, false));
                karte.addView(z2);
            }
            inhalt.addView(karte);
        }
        if (leer) inhalt.addView(hinweis("Noch keine Empfehlungen – der PC bewertet die Werte nach und nach.", GRAU));
    }

    void positionen() {
        JSONArray l = daten.optJSONArray("Positionen");
        if (l == null || l.length() == 0) { inhalt.addView(hinweis("Keine Positionen am PC angelegt.", GRAU)); return; }
        LinearLayout karte = karte();
        for (int i = 0; i < l.length(); i++) {
            JSONObject p = l.optJSONObject(i);
            if (p == null) continue;
            double ae = p.isNull("Aenderung") ? Double.NaN : p.optDouble("Aenderung", Double.NaN);
            karte.addView(zweispaltig(s(p, "Name") + "  " + s(p, "Symbol"), s(p, "Kurs"), TEXT, TEXT));
            String empf = s(p, "Empfehlung");
            String links = empf.length() > 0 ? (p.isNull("Punkte") ? "" : p.optInt("Punkte", 50) + "  ") + empf : "";
            int fe = empf.equals("kaufen") ? GRUEN : empf.equals("verkaufen") ? ROT : GELB;
            karte.addView(zweispaltig(links, Double.isNaN(ae) ? "" : prozent(ae) + " heute", fe, Double.isNaN(ae) ? GRAU : ae >= 0 ? GRUEN : ROT));
            View linie = new View(this);
            linie.setBackgroundColor(KARTE_HELL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            lp.setMargins(0, dp(8), 0, dp(4));
            karte.addView(linie, lp);
        }
        inhalt.addView(karte);
    }

    // ---------- Bausteine ----------
    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    TextView text(String s, float sp, int farbe, boolean fett) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(farbe);
        if (fett) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    TextView knopf(String s) {
        TextView t = text(s, 14, Color.WHITE, true);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(KARTE_HELL);
        t.setBackground(g);
        return t;
    }

    LinearLayout karte() {
        LinearLayout k = new LinearLayout(this);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(KARTE);
        g.setStroke(dp(1), KARTE_HELL);
        k.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        k.setLayoutParams(lp);
        return k;
    }

    View hinweis(String s, int farbe) {
        LinearLayout k = karte();
        TextView t = text(s, 14, farbe, false);
        t.setLineSpacing(0, 1.15f);
        k.addView(t);
        return k;
    }

    TextView abschnitt(String s) {
        TextView t = text(s, 14, TEXT, true);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    LinearLayout zweispaltig(String links, String rechts, int farbeLinks, int farbeRechts) {
        LinearLayout z = new LinearLayout(this);
        z.setOrientation(LinearLayout.HORIZONTAL);
        z.setPadding(0, dp(3), 0, 0);
        TextView l = text(links, 13, farbeLinks, false);
        z.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView r = text(rechts, 13, farbeRechts, true);
        r.setGravity(Gravity.END);
        z.addView(r);
        return z;
    }

    // leere Zeichenkette statt "null" für fehlende oder leere Felder
    static String s(JSONObject o, String feld) {
        return o == null || o.isNull(feld) ? "" : o.optString(feld, "");
    }

    static String waehrung(String w) {
        return "USD".equals(w) ? "$" : "€";
    }

    static String geld(double v, String w) {
        return String.format(Locale.GERMANY, "%,.2f %s", v, w);
    }

    static String prozent(double p) {
        return (p >= 0 ? "+" : "−") + String.format(Locale.GERMANY, "%.2f %%", Math.abs(p));
    }

    static String prozentOhne(double p) {
        return String.format(Locale.GERMANY, "%.1f %%", p);
    }

    static String aenderung(JSONObject o, String feld) {
        if (o.isNull(feld) || !o.has(feld)) return "–";
        double v = o.optDouble(feld, Double.NaN);
        if (Double.isNaN(v)) return "–";
        return (v >= 0 ? "+" : "−") + String.format(Locale.GERMANY, "%.1f%%", Math.abs(v));
    }
}
