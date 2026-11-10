# Aktien-Satellit (Android, nur Ansicht)

Zeigt auf dem Handy – nur im eigenen Heimnetz –, was Aktien-Graphen auf dem PC macht: Übungsdepots (Wert, Verlauf,
Bestand, Kauf- und Verkaufsbewegungen), Empfehlungen und Positionen. Die App liest nur –
sie kann am PC nichts verändern.

## APK bauen lassen (GitHub)

1. Auf github.com ein neues Repository anlegen, z. B. `android-satellit`.
   **Öffentlich (Public)**, damit der Download-Link im QR-Code ohne Anmeldung funktioniert.
2. Den kompletten Inhalt dieses Ordners hochladen – auch den Ordner `.github`
   (enthält `.github/workflows/build.yml`).
3. Unter „Actions“ läuft der Bau automatisch (ca. 3–5 Minuten).
4. Danach gibt es unter „Releases“ den Eintrag „Aktien-Satellit“ mit der Datei
   `aktien-satellit.apk`. Der Link bleibt bei jeder neuen Version gleich:

   https://github.com/seuchenvogel666-sudo/android-satellit/releases/download/satellit-latest/aktien-satellit.apk

## Mit dem PC koppeln (nur im Heimnetz)

1. Handy ins selbe WLAN bringen, in dem der PC steht.
2. Am PC: Aktien-Graphen › Einstellungen › Synchronisation › Handy-Satellit einschalten.
   Beim ersten Mal fragt Windows evtl., ob das Programm im Netzwerk erreichbar sein darf:
   nur „Private Netzwerke“ erlauben.
3. Linker QR-Code (öffentlich): mit der Handy-Kamera scannen, APK laden und installieren
   (beim ersten Mal „Installation aus unbekannten Quellen“ erlauben).
4. Am PC „Kopplungs-Code zeigen“ klicken, in der App „QR-Code scannen“ tippen und den
   rechten Code scannen.
5. Am PC erscheint „Ein Handy möchte sich verbinden“ – auf „Erlauben“ klicken. Die App wartet
   so lange und verbindet sich dann von selbst. Danach lädt sie jede Minute neu.

## Sicherheit

- Gekoppelt wird nur über den Kopplungs-Code am PC. Er enthält einen geheimen Schlüssel
  (256 Bit) und ist nur auf Knopfdruck 2 Minuten sichtbar. Die öffentliche APK allein nützt
  niemandem etwas.
- Der PC antwortet nur Geräten aus seinem eigenen lokalen Teilnetz und nur mit gültiger
  Signatur. Die Daten sind verschlüsselt (AES-256 + HMAC-SHA256).
- Die App verbindet sich nur über WLAN/LAN – nie über Mobilfunk, nie über VPN – und nur,
  wenn die PC-Adresse im aktuellen WLAN liegt. Außerhalb zeigt sie nichts an.
- Daten werden nur im Arbeitsspeicher gehalten, nicht auf dem Handy gespeichert.
  Android-Sicherungen der App sind abgeschaltet.
- Jedes neue Handy muss am PC von Hand erlaubt werden (Fenster mit Gerätename und Adresse).
  Erlaubte Handys stehen in einer Liste und lassen sich einzeln entfernen.
- „Trennen“ in der App löscht Schlüssel und Gerätekennung (danach fragt der PC erneut); „Alle Handys trennen“ am PC macht alle alten
  Kopplungen ungültig.
- Für den QR-Scanner werden die Google Play-Dienste genutzt (keine Kamera-Berechtigung nötig).

Hinweis: Neue Versionen lassen sich einfach über die alte installieren (fester Schlüssel
`app/satellit.keystore`, nur für diese private App gedacht).
