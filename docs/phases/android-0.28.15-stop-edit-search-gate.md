# Android 0.28.15 — modifica tappe e ricerca lungo percorso

## Criteri di accettazione

- `Modifica` evidenzia la tappa scelta, espande i metodi di sostituzione e mostra `Annulla`.
- Una sostituzione conserva ID e posizione nella lista della sola tappa selezionata.
- L'apertura di una nuova aggiunta azzera l'eventuale destinazione di modifica: una tappa CNG non
  può essere rimpiazzata accidentalmente dalla successiva selezione su mappa.
- La ricerca non ripropone una coordinata già presente nell'itinerario.
- Se il filtraggio svuota la prima pagina, il backend può consumare una seconda pagina Google entro
  lo stesso limite di recovery già configurato.
- Ogni candidato viene valutato nella tratta geometricamente più vicina e
  `insertion_leg_index` attraversa API e client fino al commit nell'ordine corretto.
- Un'aggiunta positiva inferiore a sessanta secondi viene mostrata come `<1 min`, non `0 min`.
- Il live gate produce `dist/compass-0.28.15-debug.apk`, APK debug firmato e installabile.

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

1. Installare `dist/compass-0.28.15-debug.apk` come aggiornamento della build debug.
2. Creare un percorso con una tappa ordinaria e una CNG.
3. Premere `Modifica` su entrambe e verificare evidenziazione, scelta sostitutiva e `Annulla`.
4. Dopo `Modifica` sulla CNG, annullare/aprire `Aggiungi tappa` e aggiungere un punto da mappa:
   devono restare tre tappe e quella CNG deve conservare il dwell time.
5. Cercare un luogo già inserito: non deve apparire nuovamente; selezionare poi un risultato vicino
   a una tratta precedente e verificare ordine lista, polilinea e tempo aggiunto.
