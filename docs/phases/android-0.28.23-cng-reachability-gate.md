# Android 0.28.23 — raggiungibilità manuale CNG

## Criteri di accettazione

- La ricerca manuale CNG invia esplicitamente l'autonomia residua al backend.
- Una stazione viene proposta solo quando la distanza stradale da partenza è minore o uguale
  all'autonomia residua inserita.
- La distanza euclidea dal corridoio non determina la raggiungibilità.
- Il limite di deviazione e quello di autonomia restano indipendenti e osservabili tramite
  `excluded_by_detour_count` ed `excluded_by_range_count`.
- Il live gate produce `dist/compass-0.28.23-debug.apk`, firmato e installabile.

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

1. Sincronizzare e ricostruire il backend prima di installare il nuovo APK: il campo API è
   retrocompatibile, ma il filtro è server-side.
2. Impostare un percorso con autonomia residua CNG di 10 km e deviazione massima di 10 minuti.
3. Verificare che ogni scheda restituita mostri **Da partenza** minore o uguale a 10 km.
4. Ripetere con un limite coincidente con la distanza di una stazione e verificare che il confronto
   inclusivo la mantenga tra i risultati.
