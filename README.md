# MeineSchule Expressive – native Android-App (Kotlin / Jetpack Compose / Material 3 Expressive)

Inoffizieller, moderner Client für **MeineSchule.digital**, gebaut ausschließlich auf der reverse-engineerten API-Dokumentation (`MSD-API-SPEC.md`). Nicht von Anbieter oder Schule geprüft.

> **Ehrlicher Status:** Der Quellcode wurde in einer Umgebung ohne Android SDK und ohne Netzwerk geschrieben. Er wurde **nicht kompiliert, nicht ausgeführt und nicht gegen einen echten Server getestet.** Rechne beim ersten Build mit kleinen Compile-Korrekturen (v. a. bei den neuen Material‑3‑Expressive‑APIs, die sich zwischen Versionen ändern). Die Unit-Tests sind geschrieben, aber nicht gelaufen.

## Einrichten
1. **Android Studio** (aktuell, Meerkat oder neuer) und JDK 17 installieren; Projektordner öffnen.
2. In `local.properties` (nicht einchecken!) eintragen:
   ```properties
   msd.origin=https://meineschule.digital   # Standard, nur ändern wenn nötig
   ```
   `origin` ist die Basis-URL der Plattform (nur **https**). Die App-ID erzeugt die App selbst pro Installation (`MSDAPP:<uuid>`).
3. Gradle-Wrapper erzeugen, falls nicht vorhanden: `gradle wrapper` (oder Android Studio synchronisieren lassen).
4. Starten: `./gradlew installDebug`, Tests: `./gradlew testDebugUnitTest`.
5. Optional: Schrift *Roboto Flex* als `res/font/roboto_flex.ttf` ablegen und in `ui/theme/Theme.kt` verwenden; ohne Datei wird die Systemschrift genutzt.

## Funktionsumfang
Login inkl. Zwei-Faktor, Dashboard, Stundenplan, Messenger (Liste, Verlauf, neuer Chat, Echtzeit via SignalR), Kalender (nur Ansehen/Bestätigen), Medien, Home.InfoPoint, Abwesenheiten (anlegen/ändern/löschen, Anhang), Betreuung, Fundsachen, Protokolle (nur Lesen), Schüler/Personen, Profil mit Schulwechsel, Hinweise, Weitere Links, Einstellungen (Theme, Dynamic Color, Keystore‑Login, App-Sperre, Benachrichtigungen, Abmelden).
Adaptive Navigation: Bottom Bar → Navigation Rail → Drawer je nach Fensterbreite.

## Sicherheit
- Nur HTTPS (`network_security_config`: Cleartext verboten), `allowBackup=false`.
- Access-/Refresh-Token nur im Speicher; Persistenz **nur** bei ausdrücklich gewähltem „Angemeldet bleiben“ (nie vorausgewählt), AES‑256‑GCM im Android Keystore. Passwörter werden nie gespeichert, Tokens/Passwörter nie geloggt (kein Logging-Interceptor).
- Bearer-Token wird nur an die konfigurierte Origin gesendet.
- Server-HTML (Home.InfoPoint, Protokolle) wird nie in einer WebView gerendert, sondern mit Jsoup bereinigt und nativ dargestellt.
- Externe Links: nur https, Host sichtbar, Bestätigung vor dem Öffnen. Uploads: Typ- und Größenprüfung. Destruktive Aktionen mit Bestätigung.
- Berechtigungen entscheidet der Server (`canEdit`, `canCreate…`); die UI blendet nur aus.
- Abmelden löscht Sitzung, Keystore-Daten, Downloads, Hintergrundjobs.

## Sitzungsverwaltung (AuthManager)
Ablauf aus JWT-`exp` (sonst `expireIn`-Heuristik), proaktive Erneuerung ~2 min vorher, Mutex‑geschützter Single‑Flight‑Refresh, bei 401 genau ein Refresh + ein Retry, ungültiger Refresh-Token → Login mit Hinweis, Wiederherstellung nach Neustart.

## Bekannte Einschränkungen / Annahmen
Siehe `docs/API-MAPPING.md`. Kurz:
- Nicht dokumentierte Modelle (Chat, Nachricht, Kalender, Medien, Protokoll …) werden tolerant gemappt (alternative Feldnamen); echte Antworten können Anpassungen in `data/Models.kt` erfordern.
- Kalender: Zusagen (`answer=1`) und Absagen (`answer=2`). Chat-„Vote“ sind Emoji-Reaktionen (keine Umfragen); Medien in Chats sind Anhänge (`Download/{attId}`). Profil-/Kontakt-Links der Web-App (`…/#/profil?u=…&n=…`, `#/chat/…`) und das Android-Teilen-Menü werden unterstützt.
- Chat-Anhänge: Upload an `Upload/{tempId}` und anschließendes Senden mit `tempId` ist eine **Annahme**.
- Stundenplan: keine Uhrzeiten/Räume/Lehrkräfte in der dokumentierten Antwort; der Slot‑Index ist nicht die Stundennummer.
- Hausaufgaben-Fälligkeit: „zum TT.MM.JJJJ“ aus dem Text; sonst aus dem Folgeunterricht **abgeleitet und als „abgeleitet“ markiert**.
- Kein echtes Push: Die Geräteregistrierung der Original-App ist nicht für Drittclients dokumentiert. Stattdessen lokale Hinweise (SignalR im Vordergrund, WorkManager ≥15 min mit „Angemeldet bleiben“).
- Fundsachen: Anlegen mit Name+Ort, danach Update für Beschreibung/Art.
- Schüler-Eigenschaften: nur Lesen; Bearbeiten über Web per One‑Time‑Token.
- Kein Anlegen von Kalendereinträgen/Protokollen/Medien (nicht vorgesehen).

## Architektur
`core/` (Fehler, JSON-Toleranz, Datum, URL-Prüfung) · `auth/` (AuthManager, SecureStore) · `net/` (OkHttp, ApiClient, Portal-Cookie-Client, SignalR-Hub) · `data/` (Modelle, Repositories, Parser, Nachrichtenspeicher) · `ui/` (Theme, Komponenten, Navigation, Screens) · `work/` (Hintergrundabfrage).
