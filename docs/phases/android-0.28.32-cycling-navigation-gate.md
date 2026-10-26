# Android 0.28.32 — navigazione in bici

## Incremento e preparazione

Un solo gate verifica backend e dispositivo. Stato: implementazione locale, prova live da effettuare
dall'operatore. APK installabile: `dist/compass-0.28.32-debug.apk` (versionCode 78).

Dal server, nella radice del repository sincronizzato, con il consueto `.env` e le credenziali già
configurate:

```bash
docker compose build api
docker compose up -d api
docker compose ps
```

Non servono nuove variabili, servizi o migrazioni. Valhalla deve avere tile dell'area di prova.
Non occorrono dati metano. Installare l'APK dal telefono, oppure dal computer collegato:

```bash
adb install -r dist/compass-0.28.32-debug.apk
```

Mantenere nell'app il server e le credenziali già configurati. Aggiornare solo l'APK senza il backend
può produrre HTTP 422, perché il precedente contratto non accetta `bicycle`.

## Prova unica

1. **Crea viaggio → Bici**: Auto/A piedi/Bici devono rimanere sulla stessa riga compatta.
   Calcolare una tratta breve in un'area nota con piste ciclabili. Controllare geometria e indicazioni
   rispetto alla segnaletica reale; non deve essere una rotta auto con il solo tempo modificato.
3. Aggiungere due tappe ordinarie (ricerca e mappa), riordinarle e calcolare. Destinazione e ordine
   devono restare corretti. Il tempo aggiunto nella ricerca deve usare il percorso ciclistico.
4. Avviare la navigazione e provare a velocità di pedalata in condizioni sicure. La camera deve
   mantenere il puck ancorato, essere meno inclinata rispetto all'auto e allargarsi all'aumentare
   della velocità. Restano disponibili callout su mappa per prossima manovra e tappa.
5. Con voce attiva, verificare annuncio iniziale e soglie 250/100/50/10 m dove la lunghezza della
   tratta lo permette. Fermarsi in sicurezza prima di usare i controlli: forzare un ricalcolo e
   aggiungere/rimuovere una tappa dal menu. Modalità Bici, tappe future e destinazione si conservano.
   Verificare anche ricalcolo automatico dopo una deviazione sostenuta con GPS affidabile.
6. Chiudere e riaprire l'app con rotta salvata: ripristino in Bici. Tornare ad Auto e poi A piedi:
   devono conservare i comportamenti precedenti; CNG torna disponibile soltanto in Auto.

## Contratto API e diagnostica

Dalla radice del repository sul server, con `COMPASS_API_BASE_URL` comprensivo di `/`,
`COMPASS_API_USERNAME` e `COMPASS_API_PASSWORD` già definiti (omettere `--user` se auth disattivata):

```bash
curl --fail-with-body --silent --show-error \
  --user "$COMPASS_API_USERNAME:$COMPASS_API_PASSWORD" \
  --header 'Content-Type: application/json' \
  --data '{"origin":{"latitude":45.4384,"longitude":10.9916},"destination":{"latitude":45.4420,"longitude":10.9980},"costing":"bicycle"}' \
  "${COMPASS_API_BASE_URL}api/v1/routes"
```

L'accessibilità dipende dai dati OSM presenti nelle tile;
non viene promesso un itinerario esclusivamente su piste ciclabili. Il profilo usa i default bici
ibrida/città di Valhalla, senza profili MTB/e-bike in questo incremento.

Se fallisce, restituire risposta HTTP, schermata, passaggi riproducibili e questi log, senza segreti:

```bash
docker compose logs --tail=200 api valhalla
adb logcat -d -s CompassApi CompassPlanner AndroidRuntime
```

Per problemi camera indicare se in movimento o fermi e allegare una breve registrazione.
Accettazione: calcolo e ricalcolo ciclistico reali, tappe/persistenza coerenti, guida utilizzabile
in bici e nessuna regressione Auto/A piedi. Il passo successivo è soltanto valutare questa prova live.

## Controlli locali

Dalla radice del repository:

```bash
API_AUTH_ENABLED=false .venv/bin/pytest -q
.venv/bin/ruff check .
.venv/bin/python scripts/export-openapi.py
cd android
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Per Gradle configurare `JAVA_HOME` (JDK 17) e `ANDROID_SDK_ROOT`; per compilare un APK con server
predefinito usare `-PCOMPASS_API_BASE_URL` con l'URL dell'operatore. Le prove automatiche sono
fixture/mock, non attestano un test su dispositivo o sul server live. I test PostGIS richiedono
`TEST_DATABASE_URL` e vengono saltati se assente.

Risultati locali del 2026-10-11: pytest **441 passati, 5 PostGIS saltati**; Ruff senza errori;
OpenAPI rigenerata; Gradle `testDebugUnitTest lintDebug assembleDebug` concluso con successo
(**276 test Android**, lint senza errori). APK versione 0.28.32-debug/code 78 verificato con
`aapt dump badging` e firma verificata con `apksigner verify`.
Il gate sul dispositivo e il contratto live restano da confermare dall'operatore.
