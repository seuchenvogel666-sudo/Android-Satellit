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

/** Reiter Empfehlungen und Positionen. */
abstract class ReiterMarkt extends ReiterAnalyse {

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
            // Name kräftig, Kurs groß; Symbol klein und grau darunter
            LinearLayout kopfZ = zweispaltig(s(p, "Name"), s(p, "Kurs"), TEXT, TEXT);
            ((TextView) kopfZ.getChildAt(0)).setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            ((TextView) kopfZ.getChildAt(0)).setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            ((TextView) kopfZ.getChildAt(1)).setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            karte.addView(kopfZ);
            karte.addView(text(s(p, "Symbol"), 11, GRAU, false));
            String empf = s(p, "Empfehlung");
            String links = empf.length() > 0 ? (p.isNull("Punkte") ? "" : p.optInt("Punkte", 50) + "  ") + empf : "";
            int fe = empf.equals("kaufen") ? GRUEN : empf.equals("verkaufen") ? ROT : GELB;
            karte.addView(zweispaltig(links, Double.isNaN(ae) ? "" : prozent(ae) + " heute", fe, Double.isNaN(ae) ? GRAU : ae >= 0 ? GRUEN : ROT));
            // 1.01: Trendwende-Punkt (grün = Wende nach oben, rot = nach unten) und Signale (WaveTrend, ADX, Orderbuch)
            int wende = p.optInt("Wende", 0);
            String sig = s(p, "Signale");
            if (wende != 0 || sig.length() > 0)
                karte.addView(text((wende > 0 ? "● Wende nach oben  " : wende < 0 ? "● Wende nach unten  " : "") + sig, 11, wende > 0 ? GRUEN : wende < 0 ? ROT : GRAU, false));
            String korr = s(p, "Korrektur");
            if (korr.length() > 0) karte.addView(text(korr, 11, GELB, false));
            // Alter des Kurses am PC: ● grün frisch, gelb gebremst, rot > 5 Min., grau Börse zu – antippen zeigt den Grund
            String alter = s(p, "Alter");
            if (alter.length() > 0) {
                int st = p.optInt("AlterStufe", -1);
                int fa = st == 0 ? GRUEN : st == 1 ? GELB : st == 2 ? ROT : GRAU;
                TextView ta = text("● " + alter + "  ⓘ", 11, fa, false);
                final String grund = s(p, "AlterGrund");
                final String titel = s(p, "Name");
                ta.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(titel).setMessage(grund).setPositiveButton("OK", null).show());
                karte.addView(ta);
            }
            View linie = new View(this);
            linie.setBackgroundColor(KARTE_HELL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            lp.setMargins(0, dp(8), 0, dp(4));
            karte.addView(linie, lp);
        }
        inhalt.addView(karte);
    }
}
