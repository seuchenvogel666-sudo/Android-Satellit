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

/** Reiter Übungsdepot und Realdepot. */
abstract class ReiterDepots extends Basis {

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
            TextView aktiv = chip(notaus ? "NOTAUS" : d.optBoolean("Gebremst", false) && d.optBoolean("Aktiv", true) ? "gebremst" : d.optBoolean("Aktiv", true) ? "aktiv" : "pausiert",
                    notaus || (d.optBoolean("Gebremst", false) && d.optBoolean("Aktiv", true)) ? ROT : d.optBoolean("Aktiv", true) ? GRUEN : GELB);
            zeile.addView(aktiv);
            karte.addView(zeile);
            karte.addView(text(geld(d.optDouble("Wert", 0), w), 26, TEXT, true));
            double gv = d.optDouble("Gv", 0), gvp = d.optDouble("GvProzent", 0);
            karte.addView(text((gv >= 0 ? "+" : "−") + geld(Math.abs(gv), w) + "  (" + prozent(gvp) + ") seit Start", 14, gv >= 0 ? GRUEN : ROT, false));
            double ms = d.optDouble("MsciSeitStart", Double.NaN);
            double msb = d.optDouble("MsciBetrag", Double.NaN);
            if (!Double.isNaN(ms)) {
                // gleiches Geld (Start + Einzahlungen) im MSCI World: Ergebnis in % und Betrag, dazu Vorsprung/Rückstand des Depots
                String t = "MSCI World (gleiches Geld): " + prozent(ms);
                if (!Double.isNaN(msb)) {
                    double diff = gv - msb;
                    t += " (" + (msb >= 0 ? "+" : "−") + geld(Math.abs(msb), w) + ") · Depot " + (diff >= 0 ? "+" : "−") + geld(Math.abs(diff), w) + (diff >= 0 ? " vorn" : " zurück");
                }
                karte.addView(text(t, 12, GRAU, false));
            }
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
            // Nachholen läuft: Fortschrittsbalken
            int nh = d.optInt("Nachholen", -1);
            if (nh >= 0) {
                android.widget.ProgressBar pb = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                pb.setProgress(Math.max(3, nh));
                pb.setProgressTintList(android.content.res.ColorStateList.valueOf(BLAU));
                LinearLayout.LayoutParams lpb = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
                lpb.setMargins(0, dp(4), 0, dp(2));
                karte.addView(pb, lpb);
            }

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
                JSONArray mk = d.optJSONArray("Marken");
                if (mk != null && mk.length() > 0) {
                    double[][] m = new double[mk.length()][];
                    for (int k = 0; k < mk.length(); k++) {
                        JSONArray e = mk.optJSONArray(k);
                        m[k] = e == null ? null : new double[] { e.optDouble(0, 0), e.optDouble(1, 0), e.optDouble(2, 0) };
                    }
                    g.setzeMarken(m);
                }
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

}
