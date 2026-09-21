# Android 0.28.28 — gestione autostrade

## Incremento

- Le opzioni persistono **Autostrade: Attivo/Disattivo**.
- `Disattivo` propaga `allow_highways=false` a percorsi diretti, tappe, ricerca lungo il percorso,
  CNG, piano predittivo e ricalcoli di navigazione.
- `Attivo` permette il calcolo normale; se Valhalla segnala autostrade, Android richiede una scelta
  esplicita tra mantenere il percorso e ricalcolarlo senza autostrade.
- Il ricalcolo senza autostrade è una scelta della sola rotta corrente e non modifica la preferenza.
- Valhalla abilita le hard exclusions con un servizio Compose one-shot e il backend rifiuta una
  risposta non conforme alla richiesta senza autostrade.

## Gate locale

```bash
API_AUTH_ENABLED=false .venv/bin/pytest -q
.venv/bin/ruff check src tests
docker compose --profile routing config --quiet
cd android
JAVA_HOME=/home/mike/toolchains/jdk17 \
ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk \
./gradlew --no-daemon -PCOMPASS_API_BASE_URL=https://compass.sickmitch.cc/ \
  testDebugUnitTest lintDebug assembleDebug
```

L'APK risultante deve essere copiato in `dist/compass-0.28.28-debug.apk`.

## Gate live

Dal repository sincronizzato sul server:

```bash
docker compose --profile routing config --quiet
docker compose build api
docker compose --profile routing up -d --force-recreate valhalla api
docker compose --profile routing ps -a
docker compose --profile routing run --rm valhalla-config
docker compose --profile routing exec -T valhalla python3 -c \
  'import json; print(json.load(open("/custom_files/valhalla.json"))["service_limits"]["allow_hard_exclusions"])'
```

Atteso: `valhalla-config` termina con codice zero, Valhalla e API sono healthy e l'ultimo comando
stampa `True`.

Sul dispositivo:

1. con **Autostrade: Attivo**, calcolare una tratta che usa certamente l'autostrada: compare il
   prompt; **Usa autostrada** conserva la rotta;
2. ripetere e scegliere **Ricalcola senza**: viene mostrata una rotta senza autostrada, mentre nelle
   opzioni il selettore resta **Attivo**;
3. impostare **Autostrade: Disattivo** e calcolare una nuova tratta: il primo risultato esclude già
   le autostrade e non compare il prompt;
4. con la rotta senza autostrade aggiungere una tappa ordinaria o CNG e forzare un ricalcolo durante
   la navigazione: la policy resta invariata.

Se il gate fallisce, restituire:

```bash
docker compose --profile routing ps -a
docker compose --profile routing logs --no-color --tail=300 valhalla-config valhalla api
docker compose --profile routing exec -T valhalla sh -c \
  'python3 -m json.tool /custom_files/valhalla.json | grep -A2 allow_hard_exclusions'
```

Inoltre restituire la risposta completa di una richiesta `/api/v1/routes` con
`"allow_highways":false` e il messaggio mostrato dall'app.
