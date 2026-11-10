package de.aktiengraphen.satellit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.SocketFactory;

/**
 * Verbindung zum PC im Heimnetz (reines Java, ohne Android-Teile – dadurch auch am PC testbar).
 *
 * Ablauf: Das Handy fragt GET /satellit?t=Zeit&n=Zufall&m=HMAC an. Der PC antwortet nur, wenn die Anfrage
 * aus seinem lokalen Netz kommt und mit dem Schlüssel aus dem Kopplungs-QR-Code signiert ist.
 * Die Antwort ist verschlüsselt: IV | AES-256-CBC(gzip-JSON) | HMAC-SHA256(Zufall | IV | Chiffre).
 */
public final class Verbindung {

    /** Inhalt eines Kopplungs-QR-Codes: agsat://koppeln?v=1&n=PC&a=192.168.1.10,...&p=47811&k=Schlüssel */
    public static final class Kopplung {
        public String rechner = "";
        public List<String> adressen = new ArrayList<>();
        public int port;
        public byte[] schluessel;

        public String alsText() {
            StringBuilder a = new StringBuilder();
            for (String s : adressen) { if (a.length() > 0) a.append(','); a.append(s); }
            return "agsat://koppeln?v=1&n=" + enc(rechner) + "&a=" + a + "&p=" + port + "&k=" + b64url(schluessel);
        }
    }

    public static class Fehler extends Exception {
        public Fehler(String s) { super(s); }
    }

    static boolean testOhneHeimnetz;   // nur für Tests am PC

    private Verbindung() { }

    // ---------- Kopplungs-Code lesen ----------
    public static Kopplung lesen(String text) throws Fehler {
        if (text == null) throw new Fehler("Kein QR-Code gelesen.");
        text = text.trim();
        if (!text.startsWith("agsat://koppeln?"))
            throw new Fehler("Das ist kein Kopplungs-Code von Aktien-Graphen. Bitte am PC unter Einstellungen › Synchronisation „Kopplungs-Code zeigen“ antippen und diesen Code scannen.");
        Kopplung k = new Kopplung();
        try {
            for (String teil : text.substring(text.indexOf('?') + 1).split("&")) {
                int g = teil.indexOf('=');
                if (g <= 0) continue;
                String name = teil.substring(0, g), wert = URLDecoder.decode(teil.substring(g + 1), "UTF-8");
                if (name.equals("n")) k.rechner = wert;
                else if (name.equals("p")) k.port = Integer.parseInt(wert);
                else if (name.equals("k")) k.schluessel = b64urlLesen(wert);
                else if (name.equals("a")) {
                    for (String a : wert.split(",")) {
                        a = a.trim();
                        if (a.length() > 0 && privateAdresse(a)) k.adressen.add(a);
                    }
                }
            }
        } catch (Exception e) {
            throw new Fehler("Der Kopplungs-Code ist beschädigt. Bitte erneut scannen.");
        }
        if (k.schluessel == null || k.schluessel.length != 32 || k.port <= 0 || k.port > 65535)
            throw new Fehler("Der Kopplungs-Code ist unvollständig. Bitte erneut scannen.");
        if (k.adressen.isEmpty())
            throw new Fehler("Der Code enthält keine Heimnetz-Adresse des PCs. Ist der PC mit dem WLAN/LAN verbunden?");
        return k;
    }

    /** Nur private Heimnetz-Adressen (10.x, 172.16–31.x, 192.168.x) – nie Adressen aus dem Internet. */
    public static boolean privateAdresse(String ip) {
        String[] t = ip.split("\\.");
        if (t.length != 4) return false;
        int[] b = new int[4];
        try {
            for (int i = 0; i < 4; i++) { b[i] = Integer.parseInt(t[i]); if (b[i] < 0 || b[i] > 255) return false; }
        } catch (NumberFormatException e) { return false; }
        return b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168);
    }

    // ---------- Abrufen ----------
    /**
     * Holt die Daten vom PC. fabrik = Socket-Fabrik des WLANs (damit nichts über Mobilfunk läuft).
     * Gibt das JSON als Text zurück.
     */
    public static String abrufen(Kopplung k, SocketFactory fabrik, String geraetId, String geraetName) throws Fehler {
        Fehler letzter = null;
        for (String ip : k.adressen) {
            if (!privateAdresse(ip) && !testOhneHeimnetz) continue;
            try {
                return abrufenVon(ip, k, fabrik, geraetId, geraetName);
            } catch (Fehler f) {
                letzter = f;
                if (f instanceof Abgelehnt) throw f;   // PC erreicht, aber Schlüssel/Zeit falsch – andere Adressen helfen nicht
            }
        }
        throw letzter != null ? letzter : new Fehler("Keine Heimnetz-Adresse gespeichert. Bitte neu koppeln.");
    }

    static class Abgelehnt extends Fehler {
        Abgelehnt(String s) { super(s); }
    }

    /** Der PC fragt gerade nach, ob dieses Handy erlaubt wird. */
    public static final class Warten extends Abgelehnt {
        Warten(String s) { super(s); }
    }

    private static String abrufenVon(String ip, Kopplung k, SocketFactory fabrik, String geraetId, String geraetName) throws Fehler {
        Socket s = null;
        try {
            InetAddress ziel = InetAddress.getByAddress(zahlen(ip));
            if (!ziel.isSiteLocalAddress() && !testOhneHeimnetz) throw new Fehler("Keine Heimnetz-Adresse.");
            s = fabrik != null ? fabrik.createSocket() : new Socket();
            s.connect(new InetSocketAddress(ziel, k.port), 2500);
            s.setSoTimeout(15000);

            byte[] z = new byte[16];
            new SecureRandom().nextBytes(z);
            String nonce = hex(z);
            String t = String.valueOf(System.currentTimeMillis() / 1000);
            String m = hex(hmac(abgeleitet(k.schluessel, "auth"), ("GET|" + t + "|" + nonce + "|" + geraetId + "|" + geraetName).getBytes("UTF-8")));
            String anfrage = "GET /satellit?t=" + t + "&n=" + nonce + "&g=" + geraetId + "&d=" + enc(geraetName) + "&m=" + m + " HTTP/1.1\r\n" +
                    "Host: " + ip + ":" + k.port + "\r\nConnection: close\r\n\r\n";
            OutputStream aus = s.getOutputStream();
            aus.write(anfrage.getBytes("US-ASCII"));
            aus.flush();

            InputStream ein = s.getInputStream();
            ByteArrayOutputStream puffer = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            long frist = System.currentTimeMillis() + 30000;   // höchstens 30 Sekunden insgesamt
            while ((n = ein.read(b)) > 0) {
                puffer.write(b, 0, n);
                if (puffer.size() > 10 * 1024 * 1024) throw new Fehler("Antwort zu groß – das ist nicht dein PC.");
                if (System.currentTimeMillis() > frist) throw new Fehler("Der PC antwortet zu langsam – Verbindung abgebrochen.");
                s.setSoTimeout((int) Math.max(1000, Math.min(15000, frist - System.currentTimeMillis())));
            }
            byte[] alles = puffer.toByteArray();
            int ende = -1;
            for (int i = 0; i + 3 < alles.length; i++)
                if (alles[i] == 13 && alles[i + 1] == 10 && alles[i + 2] == 13 && alles[i + 3] == 10) { ende = i; break; }
            if (alles.length == 0)
                throw new Fehler("Der PC hat die Verbindung abgelehnt. Er antwortet nur Geräten aus seinem eigenen Heimnetz – ist das Handy im selben WLAN?");
            if (ende < 0) throw new Fehler("Unvollständige Antwort vom PC.");
            String kopf = new String(alles, 0, ende, "US-ASCII");
            String status = kopf.split("\r\n")[0];
            if (status.contains(" 403")) {
                if (kopf.contains("X-Grund: warten"))
                    throw new Warten("Bitte am PC bestätigen: Dort ist jetzt eine Abfrage „Ein Handy möchte sich verbinden“ zu sehen. Klicke auf „Erlauben“ – das Handy verbindet sich dann von selbst.");
                if (kopf.contains("X-Grund: abgelehnt"))
                    throw new Abgelehnt("Die Verbindung wurde am PC abgelehnt. In 10 Minuten kannst du es erneut versuchen.");
                if (kopf.contains("X-Grund: update"))
                    throw new Abgelehnt("Bitte die App aktualisieren (neue Version über den QR-Code am PC laden).");
                if (kopf.contains("X-Grund: zeit"))
                    throw new Abgelehnt("Der PC hat abgelehnt: Die Uhrzeit von Handy und PC weicht mehr als 10 Minuten ab.");
                throw new Abgelehnt("Der PC kennt dieses Handy nicht (mehr). Bitte am PC den Kopplungs-Code zeigen und hier neu scannen.");
            }
            if (status.contains(" 503")) throw new Fehler("Der PC stellt gerade noch die Daten zusammen – gleich noch einmal versuchen.");
            if (!status.contains(" 200")) throw new Fehler("Unerwartete Antwort vom PC: " + status);
            byte[] inhalt = new byte[alles.length - ende - 4];
            System.arraycopy(alles, ende + 4, inhalt, 0, inhalt.length);
            return entschluesseln(k.schluessel, nonce, inhalt);
        } catch (Fehler f) {
            throw f;
        } catch (java.net.SocketTimeoutException e) {
            throw new Fehler("PC antwortet nicht (" + ip + "). Läuft Aktien-Graphen, und ist das Handy im selben WLAN?");
        } catch (java.net.ConnectException e) {
            throw new Fehler("PC nicht erreichbar (" + ip + "). Läuft Aktien-Graphen, und ist der Satellit dort eingeschaltet?");
        } catch (Exception e) {
            throw new Fehler("Verbindung zum PC fehlgeschlagen (" + ip + "): " + e.getMessage());
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignored) { }
        }
    }

    static String entschluesseln(byte[] key, String nonce, byte[] daten) throws Exception {
        if (daten.length < 16 + 16 + 32) throw new Fehler("Antwort vom PC ist zu kurz.");
        byte[] iv = new byte[16], ct = new byte[daten.length - 48], mac = new byte[32];
        System.arraycopy(daten, 0, iv, 0, 16);
        System.arraycopy(daten, 16, ct, 0, ct.length);
        System.arraycopy(daten, 16 + ct.length, mac, 0, 32);
        Mac h = Mac.getInstance("HmacSHA256");
        h.init(new SecretKeySpec(abgeleitet(key, "mac"), "HmacSHA256"));
        h.update(nonce.getBytes("UTF-8"));
        h.update(iv);
        h.update(ct);
        if (!MessageDigest.isEqual(h.doFinal(), mac)) throw new Fehler("Die Antwort ist nicht echt (Prüfsumme falsch).");
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(abgeleitet(key, "enc"), "AES"), new IvParameterSpec(iv));
        byte[] gz = c.doFinal(ct);
        GZIPInputStream ein = new GZIPInputStream(new ByteArrayInputStream(gz));
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = ein.read(b)) > 0) aus.write(b, 0, n);
        ein.close();
        return new String(aus.toByteArray(), "UTF-8");
    }

    // ---------- Hilfen ----------
    static byte[] hmac(byte[] key, byte[] daten) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(daten);
    }

    static byte[] abgeleitet(byte[] key, String zweck) throws Exception { return hmac(key, zweck.getBytes("UTF-8")); }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    static byte[] zahlen(String ip) {
        String[] t = ip.split("\\.");
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) b[i] = (byte) Integer.parseInt(t[i]);
        return b;
    }

    static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return s; }
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    static String b64url(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length; i += 3) {
            int n = (b[i] & 0xff) << 16 | (i + 1 < b.length ? (b[i + 1] & 0xff) << 8 : 0) | (i + 2 < b.length ? (b[i + 2] & 0xff) : 0);
            s.append(ALPHABET.charAt(n >> 18 & 63)).append(ALPHABET.charAt(n >> 12 & 63));
            if (i + 1 < b.length) s.append(ALPHABET.charAt(n >> 6 & 63));
            if (i + 2 < b.length) s.append(ALPHABET.charAt(n & 63));
        }
        return s.toString();
    }

    static byte[] b64urlLesen(String s) throws Fehler {
        s = s.replace('+', '-').replace('/', '_').replace("=", "");
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        int puffer = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = ALPHABET.indexOf(s.charAt(i));
            if (v < 0) throw new Fehler("Ungültiger Schlüssel.");
            puffer = puffer << 6 | v;
            bits += 6;
            if (bits >= 8) { bits -= 8; aus.write(puffer >> bits & 0xff); }
        }
        return aus.toByteArray();
    }
}
