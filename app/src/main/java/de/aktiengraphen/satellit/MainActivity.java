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
        Verbindung.appVersion(getApplicationContext());
        reiter =Math.max(0, Math.min(REITER.length - 1, prefs.getInt("reiter7", 2)));
        try { String fs = prefs.getString("farben", null); if (fs != null) farbenAnwenden(new JSONObject(fs)); } catch (Exception ignored) { }
        // 1.15: solange der Satellit im Vordergrund ist, bleibt das Display an (das Flag wirkt nur für das sichtbare Fenster, nicht im Hintergrund)
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        TextView ver = text("  v" + appVersion(), 12, GRAU, false);   // Versionsnummer sichtbar neben dem Titel
        titelZeile.addView(ver);
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
        reiterBlock.setPadding(dp(10), dp(8), dp(10), dp(8));
        reiterBlock.setBackgroundColor(TIEF);   // wie die Navigation im Einstellungsfenster am PC, hier oben
        LinearLayout reiterLeiste = null;
        for (int i = 0; i < REITER.length; i++) {
            if (i == 0 || i == 4) {
                reiterLeiste = new LinearLayout(this);
                reiterLeiste.setOrientation(LinearLayout.HORIZONTAL);
                reiterLeiste.setPadding(0, dp(2), 0, dp(2));
                reiterBlock.addView(reiterLeiste);
            }
            final int nr = i;
            TextView t = text(REITER[i], 13f, TEXT, true);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(dp(2), dp(11), dp(2), dp(11));
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

    // ---------- Neue Version bei GitHub (ab 1.08): beim Öffnen der App und beim Entsperren, solange sie geöffnet ist ----------
    static final String GITHUB_VERSION = "https://github.com/seuchenvogel666-sudo/android-satellit/releases/download/satellit-latest/version.txt";
    static final String GITHUB_RELEASE = "https://github.com/seuchenvogel666-sudo/android-satellit/releases/tag/satellit-latest";
    boolean githubLaeuft = false;

    void githubPruefen() {
        if (githubLaeuft) return;
        long jetzt = System.currentTimeMillis();
        if (jetzt - prefs.getLong("gh_pruef", 0) < 5 * 60 * 1000L) return;   // höchstens alle 5 Minuten
        githubLaeuft = true;
        prefs.edit().putLong("gh_pruef", jetzt).apply();
        new Thread(new Runnable() {
            @Override public void run() {
                int code = 0; String name = "";
                try {
                    // Die Versionsdatei wird vom Bau erst NACH dem Hochladen der fertigen APK veröffentlicht – so kommt der Dialog nie zu früh
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(GITHUB_VERSION).openConnection();
                    c.setConnectTimeout(6000); c.setReadTimeout(8000);
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder(); String z;
                    while ((z = r.readLine()) != null) sb.append(z).append('\n');
                    r.close();
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s+(\\S+)").matcher(sb);   // Inhalt: "<versionCode> <versionName>"
                    if (m.find()) { code = Integer.parseInt(m.group(1)); name = m.group(2); }
                } catch (Exception ignored) { }
                githubLaeuft = false;
                final int neuCode = code; final String neuName = name;
                handler.post(new Runnable() { @Override public void run() { githubDialog(neuCode, neuName); } });
            }
        }).start();
    }

    void githubDialog(int neuCode, String neuName) {
        if (isFinishing() || neuCode <= appCode()) return;
        // „Später“ gilt 6 Stunden für genau diese Version
        if (prefs.getInt("gh_spaeter_code", 0) == neuCode && System.currentTimeMillis() < prefs.getLong("gh_spaeter_bis", 0)) return;
        appCodeNeu = neuCode;
        new AlertDialog.Builder(this)
            .setTitle("Neue Version verfügbar")
            .setMessage("Aktien-Satellit v" + neuName + " ist bei GitHub verfügbar (installiert: v" + appVersion() + ").\n\nDie App lädt die neue Version selbst herunter; zum Schluss fragt Android einmal, ob du sie installieren willst.")
            .setPositiveButton("Jetzt installieren", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { apkInstallieren(); }
            })
            .setNegativeButton("Später", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    prefs.edit().putInt("gh_spaeter_code", appCodeNeu).putLong("gh_spaeter_bis", System.currentTimeMillis() + 6 * 3600 * 1000L).apply();
                }
            })
            .show();
    }
    int appCodeNeu = 0;

    // ---------- Herunterladen und Installieren in der App (ab 1.10) ----------
    static final String GITHUB_APK = "https://github.com/seuchenvogel666-sudo/android-satellit/releases/download/satellit-latest/aktien-satellit.apk";
    static final String INSTALL_AKTION = "de.aktiengraphen.satellit.INSTALL";
    boolean installEmpfaenger = false;
    AlertDialog ladeDialog;

    void apkInstallieren() {
        // Android erlaubt Installationen aus einer App nur mit einmaliger Freigabe in den Systemeinstellungen
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            android.widget.Toast.makeText(this, "Bitte „Aus dieser Quelle zulassen“ einschalten, dann zurück und noch einmal auf Installieren tippen.", android.widget.Toast.LENGTH_LONG).show();
            try { startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { }
            return;
        }
        installEmpfaengerAnmelden();
        ladeDialog = new AlertDialog.Builder(this).setTitle("Update wird geladen").setMessage("Lade herunter … 0 %").setCancelable(false).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.io.File f = new java.io.File(getCacheDir(), "update.apk");
                    herunterladen(f);
                    if (f.length() < 500000) throw new Exception("Die heruntergeladene Datei ist zu klein (" + f.length() + " Bytes)");
                    handler.post(new Runnable() { @Override public void run() { if (ladeDialog != null) ladeDialog.setMessage("Installation wird vorbereitet …"); } });
                    installSitzung(f);
                } catch (final Exception e) {
                    handler.post(new Runnable() { @Override public void run() {
                        if (ladeDialog != null) ladeDialog.dismiss();
                        new AlertDialog.Builder(MainActivity.this).setTitle("Update fehlgeschlagen").setMessage(String.valueOf(e.getMessage()) + "\n\nDu kannst es noch einmal versuchen oder die Datei im Browser laden.")
                            .setPositiveButton("Noch einmal", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { apkInstallieren(); } })
                            .setNegativeButton("Im Browser", new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int w) { try { startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(GITHUB_APK))); } catch (Exception ignored) { } } })
                            .show();
                    } });
                }
            }
        }).start();
    }

    // Download mit Wiederaufnahme: bricht die Verbindung mitten drin ab („Software caused connection abort“), geht es an der Stelle weiter (bis zu 8 Versuche)
    void herunterladen(java.io.File f) throws Exception {
        runOnUiThread(new Runnable() { @Override public void run() { getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } });
        try {
            if (f.exists()) f.delete();
            long gesamt = -1; int letzte = -1; Exception letzterFehler = null;
            for (int versuch = 1; versuch <= 8; versuch++) {
                long haben = f.exists() ? f.length() : 0;
                if (gesamt > 0 && haben >= gesamt) return;
                java.net.HttpURLConnection c = null;
                try {
                    c = (java.net.HttpURLConnection) new java.net.URL(GITHUB_APK).openConnection();
                    c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("Accept-Encoding", "identity");
                    if (haben > 0) c.setRequestProperty("Range", "bytes=" + haben + "-");
                    int code = c.getResponseCode();
                    if (code != 200 && code != 206) throw new java.io.IOException("Server antwortet mit " + code);
                    if (code == 200 && haben > 0) { f.delete(); haben = 0; }   // Server kennt keine Wiederaufnahme: von vorn
                    long laenge = c.getContentLength();
                    if (laenge > 0) gesamt = haben + laenge;
                    java.io.InputStream in = c.getInputStream();
                    java.io.FileOutputStream out = new java.io.FileOutputStream(f, haben > 0);
                    byte[] puf = new byte[32768]; int n; long summe = haben;
                    try {
                        while ((n = in.read(puf)) > 0) {
                            out.write(puf, 0, n); summe += n;
                            if (gesamt > 0) {
                                final int proz = (int) (summe * 100 / gesamt);
                                if (proz != letzte) { letzte = proz; handler.post(new Runnable() { @Override public void run() { if (ladeDialog != null) ladeDialog.setMessage("Lade herunter … " + proz + " %"); } }); }
                            }
                        }
                    } finally { out.close(); in.close(); }
                    if (gesamt <= 0 || f.length() >= gesamt) return;
                } catch (java.io.IOException e) {
                    letzterFehler = e;
                    final int v = versuch;
                    handler.post(new Runnable() { @Override public void run() { if (ladeDialog != null) ladeDialog.setMessage("Verbindung unterbrochen – neuer Versuch " + (v + 1) + " von 8 …"); } });
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
                } finally { if (c != null) c.disconnect(); }
            }
            throw new Exception("Download nach 8 Versuchen nicht möglich: " + (letzterFehler == null ? "unvollständig" : letzterFehler.getMessage()));
        } finally {
            // 1.15: der Bildschirm bleibt ohnehin an, solange der Satellit im Vordergrund ist (siehe onCreate) – hier nichts mehr zurücknehmen
        }
    }

    void installSitzung(java.io.File f) throws Exception {
        android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
        android.content.pm.PackageInstaller.SessionParams sp = new android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pi.createSession(sp);
        android.content.pm.PackageInstaller.Session s = pi.openSession(id);
        java.io.OutputStream os = s.openWrite("update", 0, f.length());
        java.io.InputStream is = new java.io.FileInputStream(f);
        byte[] puf = new byte[32768]; int n;
        while ((n = is.read(puf)) > 0) os.write(puf, 0, n);
        s.fsync(os); is.close(); os.close();
        android.content.Intent i = new android.content.Intent(INSTALL_AKTION).setPackage(getPackageName());
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_MUTABLE : 0);
        android.app.PendingIntent pend = android.app.PendingIntent.getBroadcast(this, id, i, flags);
        s.commit(pend.getIntentSender());
        s.close();
    }

    void installEmpfaengerAnmelden() {
        if (installEmpfaenger) return;
        installEmpfaenger = true;
        android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent in) {
                int st = in.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1);
                if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    android.content.Intent frage = in.getParcelableExtra(android.content.Intent.EXTRA_INTENT);
                    if (ladeDialog != null) { ladeDialog.dismiss(); ladeDialog = null; }
                    if (frage != null) { frage.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(frage); }
                } else if (st != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                    if (ladeDialog != null) { ladeDialog.dismiss(); ladeDialog = null; }
                    String m = in.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
                    new AlertDialog.Builder(MainActivity.this).setTitle("Installation nicht möglich").setMessage(m == null ? "Unbekannter Fehler (Status " + st + ")" : m).setPositiveButton("OK", null).show();
                }
            }
        };
        android.content.IntentFilter fl = new android.content.IntentFilter(INSTALL_AKTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, fl, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(r, fl);
    }

    @Override
    protected void onResume() {
        super.onResume();
        githubPruefen();
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
                        if (ergebnis != null) {
                            JSONObject fb = ergebnis.optJSONObject("Farben");
                            if (fb != null) {
                                String neuF = fb.toString();
                                if (!neuF.equals(prefs.getString("farben", ""))) {
                                    prefs.edit().putString("farben", neuF).apply();
                                    farbenAnwenden(fb);
                                    daten = ergebnis;
                                    recreate();
                                    return;
                                }
                            }
                        }
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
            String pv = s(daten, "PcVersion");
            if (pv.length() > 0) s.append(" (Programm ").append(pv).append(")");
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
            g.setColor(i == reiter ? BLAU : KARTE_HELL);
            reiterKnoepfe[i].setBackground(g);
            reiterKnoepfe[i].setTextColor(i == reiter ? Color.WHITE : TEXT);
        }
    }

    void anzeigen() {
        aktualisiereKopf();
        inhalt.removeAllViews();
        // Seitenkopf wie im Einstellungsfenster am PC: Titel und eine Zeile darunter
        inhalt.addView(text(REITER[reiter], 20, TEXT, true));
        TextView unter = text(REITER_UNTER[reiter], 12, GRAU, false);
        unter.setPadding(0, dp(2), 0, dp(6));
        inhalt.addView(unter);
        if (fehler != null) inhalt.addView(hinweis("⚠  Keine Verbindung zum PC\n" + fehler, ROT));
        if (warten != null && prefs.getString("kopplung", null) != null) inhalt.addView(hinweis("⏳ " + warten, GELB));
        if (prefs.getString("kopplung", null) == null) {
            inhalt.addView(hinweis("Willkommen! So koppelst du den Satelliten mit deinem PC:\n\n" +
                    "1. Das Handy ins selbe WLAN bringen, in dem dein PC steht.\n\n" +
                    "2. Am PC in Aktien-Graphen unter Einstellungen › Handy-Satellit den Handy-Satelliten einschalten und „Kopplungs-Code zeigen“ klicken.\n\n" +
                    "3. Hier oben „QR-Code scannen“ tippen und den Code vom PC-Bildschirm scannen.\n\n" +
                    "4. Am PC erscheint eine Abfrage – dort „Erlauben“ klicken.\n\n" +
                    "Danach holt die App die Daten jede Minute direkt vom PC – nur in deinem Heimnetz, verschlüsselt und ohne Cloud. " +
                    "Am PC kann sie: per NOTAUS sofort jeden Handel stoppen, die Empfehlungen lokal neu berechnen lassen (ab 48 Std. Alter) und einen Backtest je Depot starten (höchstens alle 2 Std.) – sofern der PC das erlaubt. Freigeben geht nur am PC.", TEXT));
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
        updateAngebot();
        switch (reiter) {
            case 0: positionen(); break;
            case 1: empfehlungen(); break;
            case 2: depots(false); break;
            case 3: depots(true); break;
            case 4: auswertung(); break;
            case 5: backtest(); break;
            case 6: system(); break;
            default: bericht(); break;
        }
    }

    static final String[] REITER_UNTER = {
        "Deine Positionen mit Kurs und Empfehlung", "Die besten Empfehlungen je Kategorie", "Übungsdepots: Wert, Bestand und Trades", "Realdepots (Echtgeld-Probelauf)",
        "Auswertung der Übungsdepots", "Backtest und Langzeittests – auch vom Handy startbar", "Zustand des PCs, Abfragen und Befehle", "Tagesberichte und Wochenteil vom PC" };

    // Update der Handy-App: nur wenn der PC es anbietet; Installation erst nach Bestätigung per Fingerabdruck / Gesicht
    void updateAngebot() {
        final JSONObject up = daten == null ? null : daten.optJSONObject("Update");
        if (up == null || !up.optBoolean("Angebot", false) || up.optInt("Code", 0) <= appCode()) return;
        LinearLayout k = karte();
        k.addView(text("Update verfügbar: Version " + s(up, "Name"), 16, TEXT, true));
        k.addView(text("Dein PC bietet eine neue Version des Satelliten an (jetzt v" + appVersion() + "). Du musst die Installation mit Fingerabdruck oder Gesicht bestätigen.", 13, GRAU, false));
        TextView b = knopf("Update installieren …");
        b.setBackground(new android.graphics.drawable.GradientDrawable() {{ setCornerRadius(dp(8)); setColor(BLAU); }});
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                bestaetigen("Update der Aktien-Satellit-App installieren", new Runnable() {
                    @Override public void run() {
                        apkInstallieren();   // ab 1.10: Herunterladen und Installieren in der App (Android fragt zum Schluss selbst nach)
                    }
                });
            }
        });
        k.addView(b);
        inhalt.addView(k);
    }

    // Befehl an den PC (Empfehlungen lokal neu berechnen / Backtest): erst bestätigen, dann senden; der PC entscheidet über Erlaubnis und Sperren
    @Override
    void befehl(final String art, final String ziel, final String titel) {
        if (befehlLaeuft) return;
        bestaetigen(titel, new Runnable() { @Override public void run() { befehlSenden(art, ziel); } });
    }

    void befehlSenden(final String art, final String ziel) {
        final String code = prefs.getString("kopplung", null);
        final Network netz = heimnetz();
        final Verbindung.Kopplung k;
        try { k = Verbindung.lesen(code); } catch (Verbindung.Fehler f) { befehlErgebnis = f.getMessage(); befehlFarbe = ROT; anzeigen(); return; }
        java.util.List<String> passend = Heimnetz.passend(this, netz, k);
        if (netz == null || passend.isEmpty()) {
            befehlErgebnis = "Befehl NICHT gesendet: Das Handy ist nicht im Heimnetz des PCs.";
            befehlFarbe = ROT;
            anzeigen();
            return;
        }
        k.adressen = passend;
        befehlLaeuft = true;
        befehlErgebnis = "Wird an den PC gesendet …";
        befehlFarbe = GRAU;
        anzeigen();
        final String gid = geraetId(), gname = geraetName();
        new Thread(new Runnable() {
            @Override public void run() {
                String erg; int farbe;
                try {
                    JSONObject a = new JSONObject(Verbindung.befehl(k, netz.getSocketFactory(), gid, gname, art, ziel));
                    boolean ok = a.optBoolean("Ok", false);
                    erg = (ok ? "✓ " : "✗ ") + s(a, "Text");
                    farbe = ok ? GRUEN : GELB;
                } catch (Exception e) {
                    erg = "Befehl NICHT angekommen: " + e.getMessage();
                    farbe = ROT;
                }
                final String e2 = erg; final int f2 = farbe;
                handler.post(new Runnable() {
                    @Override public void run() {
                        befehlLaeuft = false;
                        befehlErgebnis = e2;
                        befehlFarbe = f2;
                        laden();
                        anzeigen();
                    }
                });
            }
        }).start();
    }

    // ---------- NOTAUS ----------
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
            int farbe = art.equals("notaus") || art.equals("bremse") || art.equals("daten") || art.equals("datenfehler") || art.equals("abgleich") ? ROT : art.equals("gewinn") ? GRUEN : BLAU;
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
        // Signaltöne (1.15): je Meldungsart ein Systemton des Handys wählbar
        TextView th = text("Signaltöne", 15, TEXT, true);
        th.setPadding(0, dp(14), 0, 0);
        k.addView(th);
        k.addView(text("Welcher Ton bei welcher Meldung erklingt. Du kannst jeden Ton durch einen Systemton deines Handys ersetzen oder auf stumm stellen.", 12, GRAU, false));
        for (int i = 0; i < Hinweise.TON_NAMEN.length; i++) {
            final int idx = i;
            final String name = Hinweise.TON_NAMEN[i];
            TextView bez = text(Hinweise.TON_TEXT[i], 12, TEXT, true);
            bez.setPadding(0, dp(10), 0, 0);
            k.addView(bez);
            k.addView(text("Jetzt: " + Hinweise.tonAnzeige(this, name), 12, GRAU, false));
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setPadding(0, dp(4), 0, 0);
            TextView wahl = knopf("Ton wählen");
            wahl.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { tonWaehlen(idx); }
            });
            TextView hoeren = knopf("Anhören");
            hoeren.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { Hinweise.tonVorspielen(MainActivity.this, name); }
            });
            TextView zurueck = knopf("Standard");
            zurueck.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { Hinweise.tonSpeichern(MainActivity.this, name, ""); anzeigen(); }
            });
            r.addView(wahl);
            r.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
            r.addView(hoeren);
            r.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
            r.addView(zurueck);
            k.addView(r);
        }
        inhalt.addView(k);
    }

    // Auswahl eines Systemtons (Klingelton-Auswahl des Handys: Benachrichtigungs-, Alarm- und Klingeltöne, auch „Kein Ton“)
    static final int TON_ANFRAGE = 4100;

    void tonWaehlen(int idx) {
        String name = Hinweise.TON_NAMEN[idx];
        android.content.Intent in = new android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER);
        in.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALL);
        in.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false);
        in.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
        in.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Ton für: " + Hinweise.TON_TEXT[idx].split(" – ")[0]);
        android.net.Uri jetzt = Hinweise.tonJetzt(this, name);
        in.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, jetzt);
        try { startActivityForResult(in, TON_ANFRAGE + idx); } catch (Exception e) { android.widget.Toast.makeText(this, "Die Tonauswahl lässt sich auf diesem Handy nicht öffnen.", android.widget.Toast.LENGTH_LONG).show(); }
    }

    @Override
    protected void onActivityResult(int anfrage, int ergebnis, android.content.Intent daten2) {
        super.onActivityResult(anfrage, ergebnis, daten2);
        int idx = anfrage - TON_ANFRAGE;
        if (idx < 0 || idx >= Hinweise.TON_NAMEN.length || ergebnis != RESULT_OK || daten2 == null) return;
        android.net.Uri u = daten2.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        Hinweise.tonSpeichern(this, Hinweise.TON_NAMEN[idx], u == null ? "-" : u.toString());   // keine Adresse = „Kein Ton“ gewählt
        anzeigen();
    }
}
