package de.aktiengraphen.satellit;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** Heimnetz-Prüfung: nur WLAN/LAN (nie Mobilfunk, nie VPN) und nur, wenn der PC im selben Teilnetz liegt. */
final class Heimnetz {
    private Heimnetz() { }

    static Network netz(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        Network n = cm.getActiveNetwork();
        if (n == null) return null;
        NetworkCapabilities cap = cm.getNetworkCapabilities(n);
        if (cap == null || cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null;
        if (!cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null;
        return n;
    }

    static boolean imSelbenNetz(Context c, Network n, String ip) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
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

    /** PC-Adressen, die im aktuellen WLAN liegen (leer = nicht im Heimnetz). */
    static List<String> passend(Context c, Network n, Verbindung.Kopplung k) {
        List<String> l = new ArrayList<>();
        if (n != null) for (String ip : k.adressen) if (imSelbenNetz(c, n, ip)) l.add(ip);
        return l;
    }
}
