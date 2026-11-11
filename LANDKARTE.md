# LANDKARTE – Aktien-Satellit (Handy-App zu Aktien-Graphen)

Ausführliche Regeln und Arbeitsweise: LANDKARTE.md im privaten PC-Repository seuchenvogel666-sudo/AGraph2.

- `app/src/main/java/de/aktiengraphen/satellit/`
  - `MainActivity` – Start, Koppeln (QR), Laden, Kopfzeile, Reiterleiste, Notaus, Meldungen, Einstellungen
  - `ReiterMarkt` – Empfehlungen, Positionen
  - `ReiterAnalyse` – Auswertung, Backtest, System (inkl. Abfragen & Auslastung)
  - `ReiterDepots` – Übungsdepot und Realdepot
  - `Basis` – Farben, gemeinsamer Zustand, Bausteine (text, karte, knopf, zweispaltig, geld, prozent …)
  - `Verbindung` (HMAC, Port 47811), `Heimnetz`, `Waechter` (WorkManager, alle 15 Min.), `Hinweise` (Benachrichtigungen), `Verlaufsgraph`
- Vererbung: MainActivity → ReiterMarkt → ReiterAnalyse → ReiterDepots → Basis → Activity.
  Methoden, die ein Reiter aus MainActivity braucht, in `Basis` als `abstract` ankündigen.
- Version in `app/build.gradle` erhöhen. Push baut die APK (GitHub Actions) und legt sie in die Release `satellit-latest`.
