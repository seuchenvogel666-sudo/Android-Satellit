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

/** Reiter Auswertung, Backtest und System (inkl. Abfragen & Auslastung). */
abstract class ReiterAnalyse extends ReiterDepots {

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
            if (s(a, "StreckungText").length() > 0) at += " · seltener: " + s(a, "StreckungText");
            k.addView(text(at, 12, BLAU, true));
            JSONArray v = a.optJSONArray("Verlauf");
            if (v != null) for (int i = 0; i < v.length() && i < 3; i++) {
                TextView tv = text(v.optString(i, ""), 11, GRAU, false);
                tv.setPadding(dp(12), 0, 0, 0);
                k.addView(tv);
            }
        } else {
            String fest = "Automatik aus – feste Obergrenze" + (a.optInt("ZielWirksam", 0) > 0 ? " ≈ " + a.optInt("ZielWirksam", 0) + "/Std." : "");
            if (s(a, "StreckungText").length() > 0) fest += " · seltener: " + s(a, "StreckungText");
            k.addView(text(fest + " (am PC: Einstellungen › Datenabfragen).", 11, GRAU, false));
        }
        String pause = s(a, "Pause");
        if (pause.length() > 0) k.addView(text("⏸ Yahoo sperrt gerade (zu viele Abfragen) – Pause bis " + pause + " Uhr.", 12, ROT, true));
        abfragenListe(k, a.optJSONArray("Quellen"), "Nach Datenquelle", true);
        abfragenListe(k, a.optJSONArray("Zwecke"), "Wofür", false);
        k.addView(text("* Yahoo nennt keine offizielle Grenze; ≈ 2.000/Std. ist ein Erfahrungswert. „Eingestellt“ = was die aktuellen Einstellungen höchstens anfordern. Rangfolge bei Knappheit: Markt-Scanner > Übungsdepot > Realdepot > Positionen.", 11, GRAU, false));
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
}
