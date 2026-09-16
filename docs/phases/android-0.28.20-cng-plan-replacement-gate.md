# Android 0.28.20 — sostituzione piano CNG

## Criteri di accettazione

- Un nuovo calcolo CNG conserva tutte le tappe inserite dall'utente.
- Le soste CNG generate dal piano precedente non sono input del nuovo calcolo.
- Il piano accettato sostituisce integralmente i precedenti rifornimenti automatici senza sommarli.
- Una stazione usata dal piano precedente può essere selezionata nuovamente se resta ottimale.
- Il pannello della prossima tappa intermedia in navigazione ha opacità del 70%.
- Il live gate produce `dist/compass-0.28.20-debug.apk`, firmato e installabile.

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

1. Creare un percorso con una tappa manuale e calcolare un piano CNG.
2. Dal riepilogo modificare la tappa manuale e ricalcolare il piano.
3. Verificare che il riepilogo contenga la tappa modificata e soltanto le soste del nuovo piano.
4. Avviare la navigazione e verificare che il pannello **Tappa intermedia** mostri la mappa
   sottostante con opacità del 70%.
