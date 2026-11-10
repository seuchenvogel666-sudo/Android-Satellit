package de.aktiengraphen.satellit;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Aktien-Satellit: zeigt die Übersicht, die Aktien-Graphen auf dem PC in die Datei satellit.json schreibt.
 * Die Datei liegt im Cloud-Ordner (Google Drive, OneDrive, Dropbox …) und wird über die Android-Dateiauswahl
 * verbunden. Die App liest nur – sie kann am PC nichts verändern.
 */
public class MainActivity extends Activity {

    static final int ANFRAGE_DATEI = 1;
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
    TextView[] reiterKnoepfe = new TextView[3];
    int reiter = 0;
    JSONObject daten;
    String fehler;
    boolean laedt;
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
        reiter = prefs.getInt("reiter", 0);
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
        TextView verbinden = knopf("Datei verbinden");
        verbinden.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dateiWaehlen(); } });
        knoepfe.addView(neu);
        View abstand = new View(this);
        knoepfe.addView(abstand, new LinearLayout.LayoutParams(dp(8), 1));
        knoepfe.addView(verbinden);
        kopf.addView(knoepfe);
        wurzel.addView(kopf);

        // Reiter
        LinearLayout reiterLeiste = new LinearLayout(this);
        reiterLeiste.setOrientation(LinearLayout.HORIZONTAL);
        reiterLeiste.setPadding(dp(12), dp(6), dp(12), dp(6));
        String[] namen = { "Depots", "Empfehlungen", "Positionen" };
        for (int i = 0; i < 3; i++) {
            final int nr = i;
            TextView t = text(namen[i], 14, TEXT, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(6), dp(10), dp(6), dp(10));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    reiter = nr;
                    prefs.edit().putInt("reiter", nr).apply();
                    anzeigen();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(4), 0, dp(4), 0);
            reiterLeiste.addView(t, lp);
            reiterKnoepfe[i] = t;
        }
        wurzel.addView(reiterLeiste);

        ScrollView scroll = new ScrollView(this);
        inhalt = new LinearLayout(this);
        inhalt.setOrientation(LinearLayout.VERTICAL);
        inhalt.setPadding(dp(12), dp(4), dp(12), dp(24));
        scroll.addView(inhalt);
        wurzel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(wurzel);
        anzeigen();
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
    }

    // ---------- Datei verbinden (Android-Dateiauswahl: Drive, OneDrive, Dropbox, Dateien …) ----------
    void dateiWaehlen() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, ANFRAGE_DATEI);
        } catch (Exception e) {
            fehler = "Die Dateiauswahl konnte nicht geöffnet werden.";
            anzeigen();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != ANFRAGE_DATEI || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            // manche Anbieter erlauben keine dauerhafte Freigabe – dann gilt sie bis zum Neustart der App
        }
        prefs.edit().putString("datei", uri.toString()).apply();
        fehler = null;
        laden();
    }

    // ---------- Laden (im Hintergrund) ----------
    void laden() {
        final String u = prefs.getString("datei", null);
        if (u == null) { anzeigen(); return; }
        if (laedt) return;
        laedt = true;
        aktualisiereKopf();
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject neu = null;
                String f = null;
                try {
                    InputStream in = getContentResolver().openInputStream(Uri.parse(u));
                    if (in == null) throw new Exception("Datei nicht lesbar");
                    ByteArrayOutputStream puffer = new ByteArrayOutputStream();
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = in.read(b)) > 0) puffer.write(b, 0, n);
                    in.close();
                    String s = new String(puffer.toByteArray(), "UTF-8");
                    if (s.length() > 0 && s.charAt(0) == '﻿') s = s.substring(1);
                    neu = new JSONObject(s);
                    if (!neu.has("Depots")) throw new Exception("Das ist keine Satellit-Datei (satellit.json) von Aktien-Graphen.");
                } catch (SecurityException e) {
                    f = "Kein Zugriff mehr auf die Datei – bitte „Datei verbinden“ und satellit.json neu wählen.";
                } catch (Exception e) {
                    f = "Datei konnte nicht gelesen werden: " + e.getMessage();
                }
                final JSONObject ergebnis = neu;
                final String fehlerText = f;
                handler.post(new Runnable() {
                    @Override public void run() {
                        laedt = false;
                        if (ergebnis != null) daten = ergebnis;
                        fehler = fehlerText;
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
        } else s.append("Noch keine Daten");
        s.append(" · nur Ansicht");
        if (laedt) s.append(" · lädt …");
        kopfStatus.setText(s.toString());
        long alter = daten == null ? 0 : System.currentTimeMillis() / 1000 - daten.optLong("StandUnix", 0);
        kopfStatus.setTextColor(daten != null && alter > 600 ? GELB : GRAU);
        for (int i = 0; i < 3; i++) {
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
        if (prefs.getString("datei", null) == null) {
            inhalt.addView(hinweis("Willkommen! So verbindest du den Satelliten mit deinem PC:\n\n" +
                    "1. Am PC in Aktien-Graphen unter Einstellungen › Synchronisation einen Cloud-Dienst wählen und „Handy-Satellit“ einschalten.\n\n" +
                    "2. Hier „Datei verbinden“ tippen, links im Menü die App deines Cloud-Dienstes wählen (z. B. Google Drive, OneDrive, Dropbox) " +
                    "und im Ordner AktienGraphen die Datei satellit.json antippen.\n\n" +
                    "Die App lädt danach jede Minute neu und kann am PC nichts verändern.", TEXT));
            return;
        }
        if (daten == null) {
            if (fehler == null) inhalt.addView(hinweis("Lade Daten …", GRAU));
            return;
        }
        long alter = System.currentTimeMillis() / 1000 - daten.optLong("StandUnix", 0);
        if (daten.optLong("StandUnix", 0) > 0 && alter > 600)
            inhalt.addView(hinweis("Die Daten sind " + (alter / 60) + " Minuten alt. Läuft Aktien-Graphen am PC, und ist die Cloud verbunden? " +
                    "Manche Cloud-Apps übertragen Änderungen erst nach einiger Zeit.", GELB));
        if (reiter == 0) depots();
        else if (reiter == 1) empfehlungen();
        else positionen();
    }

    void depots() {
        JSONArray l = daten.optJSONArray("Depots");
        if (l == null || l.length() == 0) { inhalt.addView(hinweis("Keine Übungsdepots vorhanden.", GRAU)); return; }
        for (int i = 0; i < l.length(); i++) {
            JSONObject d = l.optJSONObject(i);
            if (d == null) continue;
            final String name = d.optString("Name", "Depot");
            String w = waehrung(d.optString("Waehrung", "EUR"));
            LinearLayout karte = karte();

            // Kopf der Karte (antippen = auf-/zuklappen)
            LinearLayout zeile = new LinearLayout(this);
            zeile.setOrientation(LinearLayout.HORIZONTAL);
            zeile.setGravity(Gravity.CENTER_VERTICAL);
            TextView tn = text(name, 17, TEXT, true);
            zeile.addView(tn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView aktiv = text(d.optBoolean("Aktiv", true) ? "aktiv" : "pausiert", 11, d.optBoolean("Aktiv", true) ? GRUEN : GELB, true);
            zeile.addView(aktiv);
            karte.addView(zeile);
            karte.addView(text(geld(d.optDouble("Wert", 0), w), 26, TEXT, true));
            double gv = d.optDouble("Gv", 0), gvp = d.optDouble("GvProzent", 0);
            karte.addView(text((gv >= 0 ? "+" : "−") + geld(Math.abs(gv), w) + "  (" + prozent(gvp) + ") seit Start", 14, gv >= 0 ? GRUEN : ROT, false));
            karte.addView(text(s(d, "Laufzeit"), 12, GRAU, false));
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
