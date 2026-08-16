# PegDown 🏍️ - Schräglagen- & Tour-Tracker

PegDown ist dein digitaler Begleiter auf dem Motorrad, um Schräglage, Beschleunigung und Bremskräfte präzise zu erfassen und deine Touren aufzuzeichnen.

---

## 1. Die Hauptansicht (Gauge)

Das zentrale Element ist die Schräglagenanzeige. Sie zeigt dir in Echtzeit an, wie tief du in der Kurve liegst.

*   **Zentrale Anzeige:** Dein aktueller Winkel in Grad (°).
*   **Farbskala:**
    *   🟢 **Grün (bis 20°):** Entspannte Schräglage.
    *   🟡 **Gelb (20° - 35°):** Sportliche Schräglage.
    *   🔴 **Rot (über 35°):** Ambitionierte Schräglage.
*   **Motorrad-Symbol:** Neigt sich entsprechend deiner tatsächlichen Position.
*   **Dreiecks-Marker:**
    *   **Gelbe Marker (oben):** Zeigen den Spitzenwert der *aktuellen* Kurve (wird nach 7 Sekunden ohne neue Peaks zurückgesetzt).
    *   **Blaue Marker (unten):** Zeigen den absoluten Höchstwert der *gesamten Tour*.

---

## 2. Bedienung & Einstellungen

### Kalibrierung (Sehr wichtig!)
Bevor du losfährst, sollte das Handy sicher in der Halterung sitzen.
*   **Aktion:** Tippe einmal auf die große runde Schräglagenanzeige.
*   **Effekt:** Der aktuelle Winkel wird als "Nullpunkt" (0°) gesetzt. Der Status oben links wechselt auf 🟢 **Kalibriert**.

### Funktionstasten (Oben)
*   **Ansicht fixieren (Lock View):** Verhindert, dass sich das Display dreht, wenn du in Schräglage gehst. Besonders nützlich, wenn das Handy im Querformat montiert ist.
*   **Achsen: Normal/Invertiert:** Falls das Handy "falsch herum" montiert ist, kannst du hier die Wirkungsrichtung der Schräglage umkehren.
*   **Tour Reset:** Setzt alle Maximalwerte (Winkel, Beschleunigung, Bremse) der aktuellen Tour auf Null zurück.

---

## 3. Beschleunigung & Geschwindigkeit

*   **Acc (Links):** Zeigt die maximale Beschleunigung in **g** an.
*   **Brake (Rechts):** Zeigt die maximale Verzögerung (Bremskraft) in **g** an.
*   **Speed (Mitte unten):** Deine aktuelle Geschwindigkeit in **km/h**, basierend auf GPS.

---

## 4. Tour-Aufzeichnung

### Manueller Modus
*   Tippe auf **⏺ Aufzeichnung**, um den Startpunkt festzulegen.
*   Ein erneuter Tipp beendet die Aufzeichnung und öffnet den Speicherdialog.

### 🤖 Auto-Modus (Intelligent)
*   **Aktivierung:** Halte den **Aufzeichnung-Button lange gedrückt**.
*   **Logik:** Die App wartet im Hintergrund. Sobald du schneller als **7 km/h** fährst, startet die Aufzeichnung automatisch. Bleibst du stehen, läuft sie weiter, bis du sie manuell beendest oder den Modus wechselst.

---

## 5. Daten & Export

Nach dem Beenden einer Aufzeichnung wirst du nach einem Dateinamen gefragt.
*   **Format:** Die Daten werden als **GPX-Datei** gespeichert.
*   **Inhalt:** GPS-Pfad inkl. Schräglagen, Beschleunigungswerten und Geschwindigkeit als Metadaten.
*   **Speicherort:** Die Dateien findest du in deinem **Download-Ordner**.
*   **Teilen:** Nach dem Speichern öffnet sich automatisch das Android-Teilen-Menü, um die Tour z.B. an Strava, Google Drive oder Freunde zu senden.

---

## 6. Tipps für beste Ergebnisse

1.  **Feste Montage:** Wackelnde Halterungen verfälschen die G-Kräfte und Winkel.
2.  **Freie GPS-Sicht:** Trage das Handy nicht in der Tasche, wenn du die Geschwindigkeit und den Pfad genau aufzeichnen willst.
3.  **Sicherheit zuerst:** Bediene die App niemals während der Fahrt! Nutze den **Auto-Modus**, um dich voll auf die Straße zu konzentrieren.
