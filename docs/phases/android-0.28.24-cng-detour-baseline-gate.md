# Android 0.28.24 — baseline coerente per deviazioni CNG

## Criteri di accettazione

- La deviazione confronta tratta diretta e tratta tramite stazione usando costi della stessa
  matrice Valhalla, con identico istante di partenza e modello di traffico.
- Il costo diretto viene ottenuto nella chiamata matrice già necessaria: non viene aggiunta una
  richiesta per ogni stazione.
- Una stazione con deviazione reale superiore al limite non viene inclusa anche quando il riepilogo
  `/route` ha una durata maggiore del corrispondente costo matrice.
- La geometria della rotta base continua a provenire da `/route`.
- Il live gate produce `dist/compass-0.28.24-debug.apk`, firmato e installabile.

## Verifica locale

```bash
cd /home/mike/NAS/tech/projects/compass
API_AUTH_ENABLED=false .venv/bin/pytest -q
PYTHONPATH=src API_AUTH_ENABLED=false .venv/bin/python scripts/export-openapi.py --check

cd android
JAVA_HOME=/home/mike/toolchains/jdk17 \
ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk \
./gradlew --no-daemon \
  -PCOMPASS_API_BASE_URL=https://compass.sickmitch.cc/ \
  testDebugUnitTest lintDebug assembleDebug
```

## Live gate

1. Aggiornare il backend e installare l'APK 0.28.24.
2. Calcolare Verona → Bologna con autonomia residua 150 km e deviazione massima 10 minuti.
3. Verificare che le stazioni dalla parte opposta di Verona non riportino più una deviazione
   artificiale di `+0,0 min` e che quelle oltre 10 minuti siano escluse.
4. Controllare allo stesso modo le proposte nell'area di Modena.
