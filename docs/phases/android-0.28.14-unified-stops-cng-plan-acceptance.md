# Android 0.28.14 — tappe unificate e piano CNG

## Criteri di accettazione

- `Personalizza viaggio` usa due righe 2×2: `Cambia percorso / Aggiungi tappe` e
  `Piano CNG / Percorso diretto`; non esiste più il pulsante separato `Sosta CNG`.
- La mappa occupa tutto lo spazio verticale non richiesto dal pannello azioni inferiore.
- Nell'editor tappe, `Sosta CNG` apre la selezione MIMIT e la stazione confermata entra nella lista
  ordinata insieme alle tappe ordinarie, con dwell time CNG conservato.
- Nome luogo, nome stazione o coordinate sostituiscono il testo generico delle card.
- La pressione prolungata anima la card; ogni riordino annulla l'eventuale richiesta obsoleta,
  ricalcola la route waypoint e aggiorna la mappa.
- Un piano CNG può essere aperto con tappe già presenti. Il suo editor usa quattro campi in griglia
  2×2 e non mostra campi benzina preventivi.
- `Inserisci tappa intermedia` apre l'editor in modalità piano: `Sosta CNG` non è presente e il
  comando finale è `Calcola piano`.
- La richiesta predittiva include `intermediate_stops`; il backend usa la route Valhalla con tappe
  come geometria del corridoio e base di distanza/durata.
- Se il piano CNG è impossibile compare una scelta esplicita tra modifica parametri e fallback
  benzina; il fallback richiede un profilo veicolo e una stima residua.
- La route finale mista conserva destinazione, ordine delle tappe e dwell CNG e viene rifiutata se
  una tratta viola la riserva richiesta.
- Un ricalcolo durante la navigazione conserva l'ordine residuo misto di tappe ordinarie e CNG,
  invece di scartare una delle due categorie.
- Il limite del contratto waypoint resta di otto tappe complessive; Android impedisce una nona
  aggiunta e spiega come ridurre il piano.

## Limiti noti

- Il primo tentativo predittivo usa la route waypoint come corridoio e costo base, quindi fonde le
  soste CNG calcolate con le tappe manuali e ricalcola l'ordine completo. Se quella route reale non
  conserva la riserva, non viene mostrata come navigabile: l'utente deve ridurre o riordinare le
  tappe e ricalcolare il piano.
- Il fallback benzina non viene attivato automaticamente e richiede un profilo veicolo con riserva
  benzina più una stima residua inserita dall'utente.

## Verifica locale

```bash
cd /home/mike/NAS/tech/projects/compass
API_AUTH_ENABLED=false .venv/bin/pytest -q

cd android
JAVA_HOME=/home/mike/toolchains/jdk17 \
ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk \
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

## Verifica manuale

1. Calcolare un percorso e verificare il layout 2×2 e la mappa espansa.
2. Aprire `Aggiungi tappe`, aggiungere una tappa da mappa e una `Sosta CNG`.
3. Controllare nome/coordinate, dwell della sosta CNG e destinazione finale invariata.
4. Tenere premuta una card, trascinarla e verificare animazione, nuovo ordine e nuova polilinea.
5. Tornare a `Personalizza viaggio`, aprire `Piano CNG`, poi `Inserisci tappa intermedia`.
6. Verificare che `Sosta CNG` sia assente, aggiungere una tappa ordinaria e premere `Calcola piano`.
7. Confermare il piano e verificare nel riepilogo e in navigazione tutte le tappe nell'ordine scelto.
8. Provare parametri che non producono un piano CNG e verificare il prompt fallback/modifica.
