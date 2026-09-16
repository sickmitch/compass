# Android 0.28.16 — ricerca lungo percorso generica

## Criteri di accettazione

- Nessuna parola o categoria di ricerca riceve un filtro hard-coded.
- `farmacia`, `ristorante`, `parcheggio` e qualsiasi altra query generica attraversano la stessa
  ricerca Google, il medesimo filtro duplicati e la stessa valutazione temporale Valhalla.
- Restano attivi l'esclusione delle coordinate già nell'itinerario, il recupero paginato limitato e
  l'inserimento nella tratta più vicina.
- Il live gate produce `dist/compass-0.28.16-debug.apk`, firmato e installabile.

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

1. Aggiornare API e app.
2. Ripetere la ricerca lungo lo stesso percorso con almeno tre categorie diverse.
3. Verificare che i risultati seguano la risposta del provider senza regole specifiche per una
   singola parola e che una tappa già selezionata non venga riproposta.
