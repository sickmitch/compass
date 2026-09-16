# Android 0.28.17 — tappe manuali prima del piano CNG

## Criteri di accettazione

- Il backend genera prima l'itinerario che attraversa nell'ordine tutte le tappe inserite
  dall'utente.
- La ricerca predittiva associa ogni rifornimento alla tratta corretta e calcola distanze,
  deviazioni, ETA e riserva senza saltare tappe manuali.
- Android compone l'ordine finale usando `insertion_leg_index`, ricalcola una sola volta il
  percorso misto e conserva il dwell time delle sole soste CNG.
- Nessun ordinamento del piano dipende dalla semplice prossimità geometrica alla polilinea.
- Il live gate produce `dist/compass-0.28.17-debug.apk`, firmato e installabile.

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

1. Creare un percorso abbastanza lungo da richiedere almeno un rifornimento CNG.
2. Da **Piano CNG**, inserire prima una tappa manuale sensibilmente fuori dal percorso diretto.
3. Confermare le tappe e calcolare il piano.
4. Verificare nel riepilogo e sulla mappa che la tappa manuale resti nell'ordine scelto e che le
   soste CNG compaiano prima o dopo di essa secondo la tratta calcolata.
5. Avviare la navigazione e verificare che tappe ordinarie e soste CNG siano entrambe presenti; il
   tempo di sosta deve essere applicato soltanto ai rifornimenti.
