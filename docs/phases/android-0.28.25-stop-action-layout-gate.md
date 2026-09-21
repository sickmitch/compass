# Android 0.28.25 — azioni tappa uniformi

## Criteri di accettazione

- Tutti i pulsanti di aggiunta tappa hanno altezza esplicita di 56 dp.
- I pulsanti della stessa riga hanno la stessa larghezza.
- Le etichette `Dove sono`, `Preferiti`, `Cerca`, `Mappa` e `Sosta CNG` restano su una riga.
- La modalità percorso mostra la sosta CNG; la modalità piano CNG continua a nasconderla.
- Il live gate produce `dist/compass-0.28.25-debug.apk`, firmato e installabile.

## Verifica locale

```bash
cd /home/mike/NAS/tech/projects/compass/android
JAVA_HOME=/home/mike/toolchains/jdk17 \
ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk \
./gradlew --no-daemon \
  -PCOMPASS_API_BASE_URL=https://compass.sickmitch.cc/ \
  testDebugUnitTest lintDebug assembleDebug
```

## Live gate

1. Installare l'APK 0.28.25.
2. Aprire **Organizza le tappe** ed espandere **Aggiungi tappa**.
3. Verificare che entrambe le righe abbiano altezza uniforme e nessuna etichetta vada a capo.
4. Aprire la stessa schermata dal piano CNG e verificare la griglia 2×2 senza **Sosta CNG**.
