# PulsePopup

Kleine Android-App, die sich per Bluetooth LE mit einem Brustgurt-Pulsmesser (z. B. dem
Chest Monitor vom CAROL Bike) verbindet und den Live-Puls in einem kleinen, verschiebbaren
Always-on-top-Fenster anzeigt – optional mit hochzählendem Timer.

## Bedienung

1. App öffnen, **Berechtigungen erteilen** (Bluetooth, Benachrichtigungen, „Über anderen Apps einblenden“).
2. **Pulsmesser suchen und auswählen** – Gurt vorher anlegen bzw. befeuchten, damit er sendet.
   Falls er nicht erscheint, „Alle Geräte“ antippen.
3. **Starten**. Das Popup bleibt über allen Apps sichtbar, auch wenn die App geschlossen wird
   (läuft als Vordergrunddienst). Beenden über „Stoppen“ oder die Benachrichtigung.

Im Popup: **Ziehen** = verschieben (Position wird gespeichert), **Tippen** = Timer pausieren/fortsetzen,
**langes Drücken** = Timer zurücksetzen. Der Timer startet automatisch beim ersten Pulswert und
lässt sich in der App ausblenden. Bei Verbindungsabbruch verbindet die App automatisch neu.

Popup nicht zu sehen? In der Benachrichtigung auf **„Popup zurückholen“** tippen oder in der App
**„Popup-Position zurücksetzen“** – das Popup springt dann zurück an den linken oberen Bildschirmrand.

## Bauen

Android Studio: Projekt öffnen und ausführen (minSdk 26, keine externen Bibliotheken).
Per Kommandozeile: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
Der GitHub-Actions-Workflow „Build APK“ baut die APK bei jedem Push und stellt sie als Artifact bereit.

## Hinweise

- Es wird das Standard-Bluetooth-Herzfrequenzprofil (Service `0x180D`) genutzt. ANT+-only-Geräte gehen nicht.
- Viele Gurte erlauben nur **eine** BLE-Verbindung. Ist der Gurt gerade mit dem Bike oder einer anderen App
  verbunden, ist er für PulsePopup nicht sichtbar bzw. nicht verbindbar.
- Android 8–11 verlangt fürs BLE-Scannen die Standortberechtigung (und aktivierte Ortung).
