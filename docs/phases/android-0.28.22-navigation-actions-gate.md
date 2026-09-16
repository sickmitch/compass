# Android 0.28.22 — azioni rapide di navigazione

## Criteri di accettazione

- Il pulsante in basso a destra è un chevron con gli stessi colori degli altri controlli mappa.
- Il chevron apre verso l'alto, nell'ordine: termina navigazione, rimuovi prossima tappa quando
  disponibile, aggiungi tappa, forza ricalcolo.
- **Rimuovi prossima tappa** elimina la prima sosta globale, sia essa manuale o CNG, ricalcola dalla
  posizione corrente e conserva tutte le soste successive.
- **Aggiungi tappa** apre la gestione tappe usando la posizione GPS corrente come nuova partenza,
  mantenendo destinazione e tappe future nell'ordine originale.
- **Forza ricalcolo percorso** interroga immediatamente il backend partendo dal fix GPS grezzo,
  così può riallineare una posizione agganciata a una strada urbana non più corretta.
- Il live gate produce `dist/compass-0.28.22-debug.apk`, firmato e installabile.

## Verifica locale

```bash
cd /home/mike/NAS/tech/projects/compass
API_AUTH_ENABLED=false .venv/bin/pytest -q

cd android
JAVA_HOME=/home/mike/toolchains/jdk17 \
ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk \
./gradlew --no-daemon \
  -PCOMPASS_API_BASE_URL=https://compass.sickmitch.cc/ \
  testDebugUnitTest lintDebug assembleDebug

cd ..
adb install -r dist/compass-0.28.22-debug.apk
```

## Live gate

1. Avviare una navigazione con almeno una tappa manuale e una CNG.
2. Aprire il chevron e verificare ordine, colori e chiusura delle azioni.
3. Rimuovere prima una tappa manuale e poi, in un secondo percorso, una CNG; controllare che ogni
   volta restino destinazione e soste successive.
4. Premere **Aggiungi tappa** e verificare che la partenza sia la posizione corrente e che le tappe
   future siano ancora presenti e riordinabili.
5. Durante una deviazione urbana premere **Forza ricalcolo percorso** e verificare indicatore,
   chiamata backend e nuova rotta allineata al fix corrente.
