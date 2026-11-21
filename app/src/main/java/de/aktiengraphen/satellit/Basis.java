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

/** Grundlage der App: Farben, gemeinsamer Zustand und Bausteine (Text, Karte, Knopf, Zahlenformate). */
abstract class Basis extends Activity {

    static final long NEU_LADEN_MS = 60000;

    // Farben: Vorgabe Nachtblau; sobald der PC verbunden ist, gelten seine Farben (Schwarz / Blau / Grau, siehe farbenAnwenden)
    static int HINTERGRUND = Color.rgb(8, 14, 34);
    static int KARTE = Color.rgb(17, 31, 66);
    static int KARTE_HELL = Color.rgb(28, 46, 92);
    static int TEXT = Color.rgb(230, 236, 248);
    static int GRAU = Color.rgb(140, 156, 190);
    static int BLAU = Color.rgb(77, 163, 255);
    static int GRUEN = Color.rgb(61, 220, 132);
    static int ROT = Color.rgb(255, 92, 92);
    static int GELB = Color.rgb(255, 200, 70);
    static int TIEF = Color.rgb(10, 20, 46);

    static int hexFarbe(JSONObject f, String k, int std) {
        try { return Color.parseColor(f.optString(k, "")); } catch (Exception e) { return std; }
    }

    /** Farben des PCs übernehmen (Paket-Feld "Farben"). */
    static void farbenAnwenden(JSONObject f) {
        if (f == null) return;
        HINTERGRUND = hexFarbe(f, "Hintergrund", HINTERGRUND); KARTE = hexFarbe(f, "Karte", KARTE); KARTE_HELL = hexFarbe(f, "KarteHell", KARTE_HELL);
        TEXT = hexFarbe(f, "Text", TEXT); GRAU = hexFarbe(f, "Grau", GRAU); BLAU = hexFarbe(f, "Blau", BLAU);
        GRUEN = hexFarbe(f, "Gruen", GRUEN); ROT = hexFarbe(f, "Rot", ROT); GELB = hexFarbe(f, "Gelb", GELB); TIEF = hexFarbe(f, "Tief", TIEF);
    }

    SharedPreferences prefs;
    final Handler handler = new Handler(Looper.getMainLooper());
    LinearLayout inhalt;
    TextView kopfStatus, kopfPunkt;
    static final String[] REITER = { "Positionen", "Empfehlungen", "Übungsdepot", "Realdepot", "Auswertung", "Backtest", "System", "Bericht", "Einstellungen" };
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

    // ---------- in MainActivity umgesetzt (hier nur angekündigt, damit die Reiter sie nutzen können) ----------
    abstract void laden();
    /** Befehl an den PC senden (Empfehlungen lokal neu berechnen / Backtest für ein Depot). */
    abstract void befehl(String art, String ziel, String titel);

    String befehlErgebnis;
    int befehlFarbe = GRAU;
    boolean befehlLaeuft;
    Runnable bestaetigtOk;
    static final int ANF_SPERRE = 4711;

    int appCode() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    /** Fingerabdruck/Gesicht abfragen (ersatzweise die Bildschirmsperre); führt ok nur nach erfolgreicher Bestätigung aus. */
    void bestaetigen(final String grund, final Runnable ok) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                android.hardware.biometrics.BiometricPrompt.Builder b = new android.hardware.biometrics.BiometricPrompt.Builder(this)
                        .setTitle("Bitte bestätigen").setSubtitle(grund);
                b.setNegativeButton("Abbrechen", getMainExecutor(), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { }
                });
                b.build().authenticate(new android.os.CancellationSignal(), getMainExecutor(), new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) { ok.run(); }
                    @Override public void onAuthenticationError(int code, CharSequence msg) {
                        // 1 = Sensor nicht verfügbar, 11 = nichts eingerichtet, 12 = kein Sensor: dann die Bildschirmsperre als Ersatz
                        if (code == 1 || code == 11 || code == 12) bestaetigenSperre(grund, ok);
                    }
                });
                return;
            } catch (Exception e) { /* weiter mit der Bildschirmsperre */ }
        }
        bestaetigenSperre(grund, ok);
    }

    void bestaetigenSperre(String grund, Runnable ok) {
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        android.content.Intent i = km != null && km.isKeyguardSecure() ? km.createConfirmDeviceCredentialIntent("Bitte bestätigen", grund) : null;
        if (i == null) {
            android.widget.Toast.makeText(this, "Auf diesem Handy ist weder Fingerabdruck noch Bildschirmsperre eingerichtet – Bestätigung nicht möglich.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        bestaetigtOk = ok;
        startActivityForResult(i, ANF_SPERRE);
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == ANF_SPERRE && res == RESULT_OK && bestaetigtOk != null) { Runnable r = bestaetigtOk; bestaetigtOk = null; r.run(); }
    }
    abstract void anzeigen();
    abstract void notausBereich();
    abstract void meldungenBereich();
    abstract void einstellungenBereich();


    static int farbe(double v) { return Double.isNaN(v) ? GRAU : v >= 0 ? GRUEN : ROT; }
    static String pz(double v) { return Double.isNaN(v) ? "–" : prozent(v); }
    static String zahl0(double v) { return String.format(Locale.GERMANY, "%.0f", v); }
    static String zahl1(double v) { return String.format(Locale.GERMANY, "%.1f", v); }



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
        TextView t = text(s, 13, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(10), dp(7), dp(10), dp(7));
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
        // farbige Hinweise (rot/gelb/grün/blau) mit getöntem Hintergrund und farbigem Rand – auf einen Blick erkennbar
        if (farbe != TEXT && farbe != GRAU) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(12));
            g.setColor(Color.argb(40, Color.red(farbe), Color.green(farbe), Color.blue(farbe)));
            g.setStroke(dp(1), Color.argb(150, Color.red(farbe), Color.green(farbe), Color.blue(farbe)));
            k.setBackground(g);
        }
        TextView t = text(s, 14, farbe, false);
        t.setLineSpacing(0, 1.15f);
        k.addView(t);
        return k;
    }

    TextView abschnitt(String s) {
        TextView t = text(s, 13, BLAU, true);
        t.setLetterSpacing(0.04f);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    // kleiner farbiger Status-Chip (wie am PC), z. B. „aktiv“, „pausiert“, „NOTAUS“
    TextView chip(String s, int farbe) {
        TextView t = text(s, 11, farbe, true);
        t.setPadding(dp(10), dp(2), dp(10), dp(3));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(Color.argb(55, Color.red(farbe), Color.green(farbe), Color.blue(farbe)));
        g.setStroke(dp(1), farbe);
        t.setBackground(g);
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

    // Versionsnummer dieser App (aus app/build.gradle, versionName)
    String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
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
