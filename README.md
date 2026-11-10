# Aktien-Satellit (Android, nur Ansicht)

Zeigt auf dem Handy, was Aktien-Graphen auf dem PC macht: Übungsdepots (Wert, Verlauf,
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

## Mit dem PC verbinden

1. Am PC: Aktien-Graphen › Einstellungen › Synchronisation
   - Dienst wählen (Google Drive, OneDrive, Dropbox …) – dessen PC-Programm muss installiert sein.
   - „Übersicht für die Handy-App bereitstellen“ einschalten.
   - Den Link von oben eintragen → der QR-Code erscheint.
2. Handy: QR-Code mit der Kamera scannen, APK laden und installieren
   (beim ersten Mal „Installation aus unbekannten Quellen“ erlauben).
3. In der App „Datei verbinden“ → im Menü die App des Cloud-Dienstes wählen
   (Google Drive, OneDrive, Dropbox) → Ordner `AktienGraphen` → `satellit.json`.
4. Fertig. Die App lädt jede Minute neu, solange sie geöffnet ist („Neu laden“ geht jederzeit).

Hinweis: Neue Versionen lassen sich einfach über die alte installieren (fester Schlüssel
`app/satellit.keystore`, nur für diese private App gedacht).
