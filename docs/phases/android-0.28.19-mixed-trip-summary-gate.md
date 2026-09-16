# Android 0.28.19 — riepilogo itinerario misto

## Criteri di accettazione

- Il conteggio nella card include tutte le soste: tappe utente più rifornimenti CNG.
- **Tappe CNG** contiene soltanto i rifornimenti e conserva il dwell time.
- **Tappe del viaggio** contiene soltanto le tappe utente e ne mostra il nome corretto.
- **Modifica** compare dopo entrambe le sezioni e non duplica le soste CNG come tappe ordinarie.
- I controlli di modifica mostrano i nomi reali delle tappe e delle stazioni.
- Il live gate produce `dist/compass-0.28.19-debug.apk`, firmato e installabile.

## Verifica locale

```bash
cd /home/mike/NAS/tech/projects/compass
API_AUTH_ENABLED=false .venv/bin/pytest -q

cd android
JAVA_HOME=/home/mike/toolchains/jdk17 \
ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk \
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

## Live gate

1. Creare un percorso con una tappa utente e un piano contenente due rifornimenti CNG.
2. Nel riepilogo verificare il conteggio di tre tappe totali.
3. Verificare due elementi in **Tappe CNG** e uno, col nome corretto, in **Tappe del viaggio**.
4. Aprire **Modifica** e verificare che compaia dopo i recap e contenga una sola tappa utente più
   le due soste CNG, senza `Tappa 2` o `Tappa 3` fittizie.
