package de.aktiengraphen.satellit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.TypedValue;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Einfacher Wertverlauf eines Depots mit Startlinie (gestrichelt) und Achsenbeschriftung. */
public class Verlaufsgraph extends View {

    final double[] t, y;
    final double start;
    double[][] marken;   // Trades: {Zeit, 1 = Kauf / 0 = Verkauf, 1 = nachgeholt}
    final Paint marke = new Paint(Paint.ANTI_ALIAS_FLAG);

    public void setzeMarken(double[][] m) { marken = m; invalidate(); }
    final Paint linie = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint flaeche = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint startLinie = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint schrift = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Verlaufsgraph(Context c, double[] t, double[] y, double start) {
        super(c);
        this.t = t;
        this.y = y;
        this.start = start;
        linie.setStyle(Paint.Style.STROKE);
        linie.setStrokeWidth(px(2));
        linie.setColor(Color.rgb(77, 163, 255));
        flaeche.setStyle(Paint.Style.FILL);
        startLinie.setStyle(Paint.Style.STROKE);
        startLinie.setStrokeWidth(px(1));
        startLinie.setColor(Color.rgb(255, 200, 70));
        startLinie.setPathEffect(new DashPathEffect(new float[] { px(5), px(4) }, 0));
        schrift.setColor(Color.rgb(140, 156, 190));
        schrift.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, getResources().getDisplayMetrics()));
    }

    float px(float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (t.length < 2) return;
        float w = getWidth(), h = getHeight();
        float rechts = w - px(52), unten = h - px(16), oben = px(4);
        double min = y[0], max = y[0];
        for (double v : y) { if (v < min) min = v; if (v > max) max = v; }
        if (!Double.isNaN(start) && start > 0) { min = Math.min(min, start); max = Math.max(max, start); }
        if (max - min < 1e-9) { max += 1; min -= 1; }
        double pad = (max - min) * 0.08;
        min -= pad; max += pad;
        double t0 = t[0], t1 = t[t.length - 1];
        if (t1 - t0 < 1) t1 = t0 + 1;

        Path p = new Path();
        for (int i = 0; i < t.length; i++) {
            float x = (float) ((t[i] - t0) / (t1 - t0) * rechts);
            float yy = (float) (unten - (y[i] - min) / (max - min) * (unten - oben));
            if (i == 0) p.moveTo(x, yy); else p.lineTo(x, yy);
        }
        Path f = new Path(p);
        f.lineTo(rechts, unten);
        f.lineTo(0, unten);
        f.close();
        flaeche.setShader(new LinearGradient(0, oben, 0, unten, Color.argb(110, 77, 163, 255), Color.argb(10, 77, 163, 255), Shader.TileMode.CLAMP));
        c.drawPath(f, flaeche);
        c.drawPath(p, linie);

        // Trades als Dreiecke auf der Linie: Kauf ▲ grün (darunter), Verkauf ▼ rot (darüber), nachgeholt nur umrandet
        if (marken != null) {
            float s = px(5);
            for (double[] m : marken) {
                if (m == null || m.length < 3 || m[0] < t0 || m[0] > t1) continue;
                int i = 0;
                while (i < t.length - 1 && t[i + 1] <= m[0]) i++;
                double wert = y[i];
                if (i < t.length - 1 && t[i + 1] > t[i]) wert = y[i] + (y[i + 1] - y[i]) * (m[0] - t[i]) / (t[i + 1] - t[i]);
                float x = (float) ((m[0] - t0) / (t1 - t0) * rechts);
                float yy = (float) (unten - (wert - min) / (max - min) * (unten - oben));
                boolean kauf = m[1] > 0.5;
                float d0 = kauf ? yy + s * 0.6f : yy - s * 0.6f, d1 = kauf ? d0 + s * 1.6f : d0 - s * 1.6f;
                Path tr = new Path();
                tr.moveTo(x, d0); tr.lineTo(x - s, d1); tr.lineTo(x + s, d1); tr.close();
                marke.setColor(kauf ? Color.rgb(61, 220, 132) : Color.rgb(255, 92, 92));
                marke.setStyle(m[2] > 0.5 ? Paint.Style.STROKE : Paint.Style.FILL);
                marke.setStrokeWidth(px(1.4f));
                c.drawPath(tr, marke);
            }
        }

        if (!Double.isNaN(start) && start > 0) {
            float ys = (float) (unten - (start - min) / (max - min) * (unten - oben));
            c.drawLine(0, ys, rechts, ys, startLinie);
        }
        // Beschriftung: höchster/niedrigster Wert rechts, Zeit unten
        c.drawText(String.format(Locale.GERMANY, "%,.2f", max - pad), rechts + px(4), oben + px(10), schrift);
        c.drawText(String.format(Locale.GERMANY, "%,.2f", min + pad), rechts + px(4), unten, schrift);
        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY);
        c.drawText(fmt.format(new Date((long) t0 * 1000)), 0, h - px(2), schrift);
        String ende = fmt.format(new Date((long) t[t.length - 1] * 1000));
        c.drawText(ende, rechts - schrift.measureText(ende), h - px(2), schrift);
    }
}
