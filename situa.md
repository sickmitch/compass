# A. Executive summary

1. L’architettura implementata è principalmente A: TomTom alimenta un overlay di velocità nei traffic tile di Valhalla; non esiste una correzione ETA indipendente basata direttamente sui segmenti TomTom.
2. Il percorso viene prima calcolato da Valhalla, poi Compass aggiorna l’overlay e può richiedere un secondo routing.
3. currentSpeed e freeFlowSpeed vengono letti, ma solo currentSpeed viene scritto nel traffic extract.
4. currentTravelTime, freeFlowTravelTime e confidence non entrano direttamente nella formula ETA di Compass.
5. Il sistema non misura la copertura del percorso: “traffico live incluso” non significa che gli archi effettivamente percorsi siano stati aggiornati.
6. Il probing della rotta usa punti distanziati normalmente 25 km; una rotta breve viene ridotta a origine, punto centrale e destinazione.
7. Un segmento con currentSpeed <= 0 viene rifiutato se non è marcato come strada chiusa.
8. Il client Android ricalcola il tempo residuo proporzionalmente alla distanza percorsa, usando la durata ricevuta dal backend.
9. Durante la navigazione il refresh traffico avviene al massimo ogni cinque minuti e richiede un nuovo routing completo.
10. Gli errori di rete possono lasciare in uso la rotta già scaricata.
11. L’etichetta “Traffico live incluso” è condizionata allo stato globale del provider e a un ritardo calcolato, non alla copertura degli archi della rotta.
12. Il divario 9 minuti previsti / circa 39 minuti reali è compatibile con velocità statiche o copertura insufficiente, ma l’incidente non è attribuibile con certezza senza log runtime.
13. Non risultano implementazioni Redis o Celery nel codice esaminato; il refresh è un servizio FastAPI/Uvicorn con stato JSON.
14. Le impostazioni predefinite del repository differiscono da alcune configurazioni riportate: zoom TomTom predefinito 18, non 10.
15. Il repository è rimasto invariato (git status --short vuoto).

# B. Architettura effettiva

This Mermaid diagram exceeds the terminal renderer's size limits.
flowchart TD
    A[Android GPS update] --> B[NavigationEngine]
    B --> C[RouteUpdateController]
    C -->|off-route / traffic refresh ogni 5 min| D[NavigationRouteRecalculator]
    D --> E[Compass API route endpoint]

    E --> F[Valhalla route iniziale]
    F --> G[encoded polyline]

    G --> H[TrafficRouteRefresher]
    H --> I[traffic-updater FastAPI :8003]
    I --> J[sample route probes]
    J --> K[TomTom Flow Segment API]

    K --> L[currentSpeed/freeFlowSpeed/OpenLR/geometry]
    L --> M[Valhalla trace_attributes]
    M --> N[directed GraphId + match confidence]
    N --> O[TrafficOverlayPlanner]
    O --> P[native traffic_tar_tool]
    P --> Q[shared traffic.tar]

    Q --> R[second Valhalla route with date_time + speed_types]
    R --> S[traffic duration]
    F --> T[baseline Valhalla route without time-dependent options]
    T --> U[traffic_delay_seconds = max(traffic - baseline, 0)]
    R --> V[route duration / maneuvers / polyline]
    U --> W[API NavigationTiming]
    V --> W
    W --> X[Android cache/session]
    X --> Y[local residual ETA projection]
    Y --> Z[UI “Traffico live incluso”]

La parte TomTom → Valhalla è un overlay binario sui traffic tile, non un calcolo ETA esterno.

# C. Risposta alla domanda principale

La risposta è E, con architettura primaria A:

- A confermata: TomTom aggiorna il traffic extract Valhalla attraverso traffic_tar_tool.
- B parzialmente presente: Compass calcola un secondo percorso Valhalla senza traffico per stimare il ritardo, ma non applica direttamente una formula distanza / currentSpeed sui dati TomTom.
- D esclusa: i dati TomTom possono influenzare il routing quando overlay e configurazione runtime sono validi.
- Non esiste una pipeline separata TomTom → ETA Android.

Valhalla 3.8.3 usa il traffic tile quando viene richiesto il flow mask current; la documentazione del codice Valhalla indica inoltre che una velocità live non valida o nulla viene ignorata e che l’influenza del traffico live diminuisce con il tempo stimato fino all’arco (Valhalla GraphTile::GetSpeed (https://github.com/valhalla/valhalla/blob/3.8.3/valhalla/baldr/graphtile.h#L740-L786)).

# D. Tabella delle evidenze

| Componente | File:riga | Funzione | Comportamento verificato | Impatto ETA |
|---|---|---|---|---|
| Configurazione | config.py:85 (src/compass/config.py:85) | Settings | Traffico disabilitato di default | In assenza di override l’ETA è standard |
| Configurazione | config.py:87 (src/compass/config.py:87) | traffic_refresh_mode | Default on_demand | Nessun feed periodico autonomo |
| Configurazione | config.py:89 (src/compass/config.py:89) | traffic_route_refresh_min_interval_seconds | Default 300 s | Refresh limitato a cinque minuti per scope |
| Configurazione | config.py:90 (src/compass/config.py:90) | traffic_route_probe_spacing_km | Default 25 km | Copertura spaziale molto rada |
| Configurazione | config.py:97 (src/compass/config.py:97) | traffic_max_age_seconds | Default 300 s | Dati oltre cinque minuti marcati stale |
| Configurazione | config.py:127 (src/compass/config.py:127) | tomtom_flow_segment_zoom | Default 18 | La configurazione nota zoom 10 non è il default del repository |
| Configurazione | config.py:128 (src/compass/config.py:128) | tomtom_flow_segment_unit | Default kmph | Nessuna conversione necessaria nel default |
| Configurazione | config.py:129 (src/compass/config.py:129) | tomtom_flow_segment_openlr | Default True | OpenLR richiesto al provider |
| Configurazione | config.py:131 (src/compass/config.py:131) | tomtom_timeout_seconds | Default 20 s | Ogni probe può attendere fino a 20 s |
| Configurazione | config.py:132 (src/compass/config.py:132) | tomtom_max_retries | Default 3 | Fino a quattro tentativi complessivi |
| Configurazione | config.py:133 (src/compass/config.py:133) | tomtom_max_concurrency | Default 2 | Parallelismo basso |
| TomTom | tomtom.py:181 (src/compass/traffic/providers/tomtom.py:181) | _fetch_flow_segments | Esegue un probe per ogni punto con asyncio.gather e semaforo | Numero di segmenti dipende dai probe, non dalla rotta completa |
| TomTom | tomtom.py:203 (src/compass/traffic/providers/tomtom.py:203) | _fetch_flow_segments | Errori 429/5xx contati; errori parziali possono coesistere con successo | Un refresh parziale può risultare comunque “fresh” |
| TomTom | tomtom.py:222 (src/compass/traffic/providers/tomtom.py:222) | _fetch_flow_segments | observed_at è il massimo timestamp dei segmenti ricevuti | Il timestamp rappresenta il batch più recente, non necessariamente ogni segmento |
| TomTom | tomtom.py:270 (src/compass/traffic/providers/tomtom.py:270) | _mark_success | Un successo parziale aggiorna lo stato provider | “Fresh” non implica copertura completa |
| TomTom | tomtom.py:318 (src/compass/traffic/providers/tomtom.py:318) | _get_flow_segment | Endpoint Flow Segment v4 con style, zoom, unit, openLr | Endpoint corretto; la copertura dipende dai punti |
| TomTom | tomtom.py:341 (src/compass/traffic/providers/tomtom.py:341) | _get_flow_segment_with_retries | Retry solo su 429/5xx, backoff esponenziale senza jitter | Errori di trasporto non vengono ritentati nello stesso livello |
| TomTom | tomtom.py:402 (src/compass/traffic/providers/tomtom.py:402) | _normalise_flow_segment_payload | Legge geometria, currentSpeed, freeFlowSpeed, travel times e confidence | I dati vengono normalizzati |
| TomTom | tomtom.py:424 (src/compass/traffic/providers/tomtom.py:424) | _normalise_flow_segment_payload | currentSpeed salvato in km/h | È il valore poi scritto nell’overlay |
| TomTom | tomtom.py:426 (src/compass/traffic/providers/tomtom.py:426) | _normalise_flow_segment_payload | currentTravelTime solo memorizzato nel dominio | Non alimenta direttamente il calcolo ETA |
| TomTom | tomtom.py:428 (src/compass/traffic/providers/tomtom.py:428) | _normalise_flow_segment_payload | confidence memorizzato | Usato come filtro qualità, non come peso ETA |
| TomTom | tomtom.py:469 (src/compass/traffic/providers/tomtom.py:469) | _response_observed_at | Usa header HTTP Date, altrimenti ora locale UTC | Non è necessariamente il timestamp della misura stradale |
| Probe rotta | route_refresh.py:131 (src/compass/traffic/route_refresh.py:131) | sample_route_probe_points | Campiona lungo la polilinea | La rotta non viene interrogata punto per punto |
| Probe rotta | route_refresh.py:162 (src/compass/traffic/route_refresh.py:162) | sample_route_probe_points | Con distanza inferiore a 25 km inserisce un punto centrale | Una rotta breve ha normalmente tre probe |
| Match | matching/valhalla.py:79 (src/compass/traffic/matching/valhalla.py:79) | match | Invia la geometria TomTom a /trace_attributes | Il segmento viene trasformato in GraphId Valhalla |
| Match | matching/valhalla.py:114 (src/compass/traffic/matching/valhalla.py:114) | _verify_openlr_direction | Verifica direzione OpenLR confrontando gli endpoint | Un errore di direzione può scartare il segmento |
| Match | matching/valhalla.py:257 (src/compass/traffic/matching/valhalla.py:257) | _parse_trace_match | Calcola lunghezza matched e confidence | La confidence viene ridotta per discontinuity/distanza |
| Planner | planner.py:166 (src/compass/traffic/valhalla/planner.py:166) | _rejection_reason | Applica età, confidence, match confidence, direzione e tileset | I segmenti non conformi non modificano Valhalla |
| Planner | planner.py:178 (src/compass/traffic/valhalla/planner.py:178) | _rejection_reason | Scarta currentSpeed <= 0 se non strada chiusa | Una coda/standstill non viene rappresentata |
| Planner | planner.py:233 (src/compass/traffic/valhalla/planner.py:233) | _managed_edges | Applica la stessa velocità all’intero arco GraphId | Nessuna interpolazione per copertura parziale |
| Overlay | overlay.py:68 (src/compass/traffic/valhalla/overlay.py:68) | apply | Scrive speed_kph, congestion e chiusure nel traffic tar | TomTom influenza il traffic tile |
| Native writer | traffic_tar_tool.cc:298 (tools/valhalla-traffic/traffic_tar_tool.cc:298) | encode_speed | Quantizza la velocità in unità Valhalla da 2 km/h | Introduce arrotondamento e minimo positivo |
| Native writer | traffic_tar_tool.cc:318 (tools/valhalla-traffic/traffic_tar_tool.cc:318) | make_speed | Replica la stessa velocità nei campi dell’arco | Non conserva profili intrarco |
| Routing | dependencies.py:10 (src/compass/routing/dependencies.py:10) | get_routing_provider | Abilita traffic-aware solo se overlay e traffic sono entrambi attivi | Un solo flag disabilita il percorso live |
| Routing | valhalla.py:73 (src/compass/routing/valhalla.py:73) | route | Usa traffic-aware solo per auto | Pedonale/bici non passano da questo overlay auto |
| Routing | valhalla.py:522 (src/compass/routing/valhalla.py:522) | _apply_time_dependent_costing | Imposta date_time e speed_types | Attiva il costing dipendente dal tempo |
| Routing | valhalla.py:536 (src/compass/routing/valhalla.py:536) | _apply_time_dependent_costing | Speed types: current, predicted, constrained, freeflow | Valhalla può fondere più fonti |
| Routing | valhalla.py:598 (src/compass/routing/valhalla.py:598) | _without_time_dependent_costing | Rimuove date_time e speed_types | Fallback a velocità grafo |
| Routing | valhalla.py:110 (src/compass/routing/valhalla.py:110) | route | Richiede un secondo route baseline | Calcola il ritardo come differenza Valhalla |
| Routing | valhalla.py:115 (src/compass/routing/valhalla.py:115) | route | max(traffic_duration - baseline_duration, 0) | Ritardo negativo azzerato |
| API | routes.py:876 (src/compass/api/routes.py:876) | _navigation_traffic | Richiede provider fresh/mock, route traffic-aware e delay non nullo | Stato globale, non copertura della rotta |
| API | routes.py:891 (src/compass/api/routes.py:891) | _navigation_traffic | Espone last_success_at come traffic_observed_at | Timestamp di successo updater, non misura per arco |
| Health | health.py:70 (src/compass/traffic/health.py:70) | as_health | Stato fresh/stale basato su età globale | Nessuna soglia di copertura |
| Updater | updater_api.py:129 (src/compass/traffic/updater_api.py:129) | refresh_route | Skip se stesso scope aggiornato negli ultimi 300 s | Ricalcoli ravvicinati possono riusare overlay |
| Updater | updater_api.py:147 (src/compass/traffic/updater_api.py:147) | refresh_route | Genera probe e chiama provider | Fetch legato al routing |
| Android | NavigationEngine.kt:489 (android/app/src/main/java/org/compass/cng/navigation/NavigationEngine.kt:489) | updateLocation | Distanza residua proporzionale alla progress fraction | Non misura velocità reale del tratto |
| Android | NavigationEngine.kt:490 (android/app/src/main/java/org/compass/cng/navigation/NavigationEngine.kt:490) | updateLocation | Durata residua proporzionale alla progress fraction | ETA resta legato alla durata scaricata |
| Android | NavigationEngine.kt:583 (android/app/src/main/java/org/compass/cng/navigation/NavigationEngine.kt:583) | updateLocation | ETA = ora corrente + driving remaining + dwell | Dwell aggiunto separatamente |
| Android | RouteUpdateController.kt:68 (android/app/src/main/java/org/compass/cng/navigation/RouteUpdateController.kt:68) | nextUpdate | Traffic refresh ogni 5 minuti solo in auto | Traffico non aggiornato a ogni GPS fix |
| Android | NavigationForegroundService.kt:184 (android/app/src/main/java/org/compass/cng/navigation/NavigationForegroundService.kt:184) | startLocationUpdates | GPS richiesto ogni 1000 ms | GPS frequente, ma non equivalente a routing |
| Android | NavigationForegroundService.kt:377 (android/app/src/main/java/org/compass/cng/navigation/NavigationForegroundService.kt:377) | processNavigationState | Ricalcolo completo asincrono | Se fallisce mantiene la rotta scaricata |
| Android | NavigationForegroundService.kt:398 (android/app/src/main/java/org/compass/cng/navigation/NavigationForegroundService.kt:398) | processNavigationState | Errore: “continuing downloaded route” | ETA precedente può persistere |
| Android | NavigationDrivingUiModel.kt:382 (android/app/src/main/java/org/compass/cng/ui/route/NavigationDrivingUiModel.kt:382) | trafficTimingText | Mostra “Traffico live incluso” se trafficAware | Non verifica copertura per arco |
| Android | RoutePlannerScreen.kt:4262 (android/app/src/main/java/org/compass/cng/ui/route/RoutePlannerScreen.kt:4262) | formatDuration | Tronca i secondi ai minuti interi | Piccoli ritardi vengono nascosti |

## Raccolta TomTom

L’endpoint implementato è:

https://api.tomtom.com/traffic/services/4/flowSegmentData/{style}/{zoom}/json

con parametri:

key
point=latitude,longitude
unit=kmph|mph
openLr=true|false

Questa struttura corrisponde alla documentazione ufficiale TomTom, che specifica endpoint, zoom, unità e OpenLR (Flow Segment Data (https://docs.tomtom.com/traffic-api/documentation/tomtom-maps/v1/traffic-flow/flow-segment-data)).

La pipeline:

- non persiste TomTom in PostGIS;
- non usa Redis;
- non usa Celery;
- persiste lo stato del mapping in JSON;
- scrive le velocità nel traffic.tar condiviso con Valhalla;
- mantiene TTL e timestamp nel file di stato Compass;
- esegue uno sweep di scadenza ogni 30 secondi nel servizio updater.

# E. Analisi dell’incidente

Per 7,1 km:

- 9 minuti implicano circa 47,3 km/h medi;
- 39 minuti implicano circa 10,9 km/h medi;
- il divario è circa 30 minuti, cioè una durata 4,3 volte maggiore.

Il codice può produrre il primo valore in diversi modi:

1. Valhalla può usare velocità statiche o prevalentemente freeflow/constrained se il traffic overlay non è realmente abilitato.
2. Il percorso può avere pochi probe: su una tratta breve il sistema interroga solo origine, punto centrale e destinazione, non ogni arco.
3. I segmenti TomTom possono essere ottenuti ma non mappati sugli archi Valhalla.
4. Un segmento a velocità nulla viene scartato se non è una chiusura esplicita.
5. Il feed può risultare globalmente fresh pur avendo zero archi aggiornati.
6. Durante la navigazione il client mantiene la durata già scaricata fino al successivo ricalcolo.
7. Se il ricalcolo fallisce, l’app continua esplicitamente con la rotta precedente.

Non è possibile dimostrare dal repository che l’incidente specifico sia stato causato esclusivamente da TomTom. Mancano:

- snapshot TomTom delle 08:35;
- health.json runtime;
- numero di segmenti ricevuti/matched;
- numero di archi aggiornati;
- durata Valhalla traffic-aware e baseline;
- polilinea effettivamente percorsa;
- log dei ricalcoli Android;
- eventuali soste, deviazioni o GPS persi.

# F. Root cause analysis

| Ipotesi | Stato | Evidenza |
|---|---|---|
| H1. TomTom non fornisce rilevazioni sufficienti | PROBABILE | Il sistema usa probe discreti a 25 km; non misura ogni arco. L’incidente richiederebbe però i log TomTom per conferma. |
| H2. Rilevazioni obsolete | PROBABILE | TTL e max-age sono 300 s; il refresh è on-demand e limitato a 300 s. Mancano timestamp runtime dell’incidente. |
| H3. Map matching errato | PROBABILE | Il matching dipende da /trace_attributes, confidence, direzione e tileset; un mismatch porta a unmatched o rifiuto. |
| H4. Valhalla non usa realmente TomTom | NON VERIFICABILE / ESCLUSA come difetto strutturale | Il codice scrive traffic.tar e abilita speed_types=current; non è escluso un problema di configurazione runtime. |
| H5. Formula ETA errata o conservativa | PROBABILE | La formula del ritardo è corretta come differenza fra due routing Valhalla, ma l’overlay applica velocità all’intero arco e può ignorare speed zero. |
| H6. ETA statico durante navigazione | CONFERMATA come comportamento parziale | Android usa la durata scaricata e la scala sulla progress fraction; il refresh completo arriva solo ogni cinque minuti. |
| H7. Ricalcoli non aggiornano traffico | PROBABILE | Scope ledger, intervallo minimo 300 s e fallback alla rotta scaricata possono impedire un aggiornamento effettivo. |
| H8. Android sovrascrive l’ETA backend | PROBABILE | Android ricalcola localmente il residuo e può conservare lo stato precedente quando il progresso non è affidabile. |
| H9. Il ritardo UI non rappresenta la congestione reale | CONFERMATA | traffic_delay_seconds è differenza fra due route Valhalla; la UI non mostra copertura, segmenti validi o velocità osservate. |
| H10. Modello insufficiente per code urbane | PROBABILE | Il modello è per archi interi, con speed quantizzata e senza rappresentazione esplicita di code/intersezioni; da solo non prova l’incidente. |

# G. Lacune di osservabilità

Mancano o non sono esposti in modo sufficiente:

- copertura in metri e percentuale della polilinea;
- copertura per direzione e per GraphId;
- età min/max/media per gli archi realmente usati;
- velocità TomTom applicata per arco;
- velocità Valhalla effettivamente selezionata per arco;
- numero di archi current vs freeflow;
- motivi di fallback per singola richiesta;
- durata traffic-aware, baseline e durata finale in un’unica struttura;
- differenza fra last_success_at e feed_observed_at nella UI;
- timestamp della misura TomTom distinto dall’header HTTP Date;
- route ID e scope key associati al refresh;
- log Android che colleghino GPS fix, ricalcolo, risposta backend e nuova ETA;
- metriche di richieste TomTom fallite parzialmente;
- percentuale di segmenti matched ma poi non applicati;
- rilevazione di currentSpeed == 0 rifiutato;
- stato del traffic extract caricato dal processo Valhalla.

Il messaggio Traffico live incluso è quindi potenzialmente fuorviante: deriva da routes.py:881 (src/compass/api/routes.py:881) e non richiede managed_edge_count > 0, copertura della route o anche un singolo arco aggiornato sulla polilinea.

# H. Piano di intervento proposto

## P0 — Invalidazione dell’ETA non dimostrabile

1. Aggiungere una validazione per richiesta che rifiuti lo stato “live” se:
   - nessun arco del percorso è aggiornato;
   - copertura sotto soglia;
   - feed stale;
   - tutti i segmenti sono unmatched o scartati.

   Impatto: impedisce ETA falsamente marcati live.
   Complessità: media.
   Rischio: fallback più frequente a velocità standard.
   Verifica: fixture con zero archi matched e risposta traffic_aware=false.
2. Non scartare automaticamente currentSpeed == 0 quando il dato è valido e non è una strada chiusa; rappresentare invece uno stato di congestione/standstill distinto.

   Impatto: code severe visibili all’ETA.
   Complessità: media/alta, perché va verificato il comportamento Valhalla.
   Rischio: velocità zero rumorose possono generare costi eccessivi.
   Verifica: fixture con 0 km/h e confronto con chiusura esplicita.
3. Separare chiaramente:
   - provider fresh;
   - overlay aggiornato;
   - copertura della rotta;
   - routing traffic-aware.

## P1 — Integrazione e copertura

1. Campionare la rotta con densità configurabile in funzione della lunghezza e della classe stradale, non soltanto con intervallo fisso di 25 km.
2. Conservare nel risultato:
   - segmenti ricevuti;
   - segmenti normalizzati;
   - segmenti matched;
   - GraphId aggiornati;
   - lunghezza matched;
   - percentuale coperta.
3. Loggare ogni rifiuto con codice macchina:
   - stale;
   - low confidence;
   - direction unverified;
   - tileset mismatch;
   - zero speed;
   - no geometry.
4. Verificare che traffic.tar venga caricato dal processo Valhalla dopo l’aggiornamento e non solo scritto sul volume condiviso.
5. Distinguere feed_observed_at da last_success_at nell’API e nella UI.
6. Aggiungere un controllo esplicito che l’overlay runtime e il tileset appartengano alla stessa build.

## P2 — Accuratezza ETA

1. Calcolare il tempo di percorso coperto anche come somma segmentale indipendente, per confronto diagnostico con Valhalla.
2. Conservare la coppia:
   - durata Valhalla traffic-aware;
   - durata baseline;
   - durata finale mostrata;
   - durata Android residua.
3. Ridurre la dipendenza del client Android dalla proiezione lineare:

   remaining_time = sum(duration of remaining maneuvers/edges)

   anziché solo:

   route_duration * (1 - progress_fraction)
4. Applicare un aggiornamento ETA più frequente in presenza di:
   - velocità GPS molto inferiore alla velocità prevista;
   - ritardo crescente;
   - variazione significativa di posizione;
   - feed TomTom aggiornato.
5. Modellare in modo esplicito le code urbane, le rotatorie e le attese alle intersezioni.

## P3 — Osservabilità e ottimizzazione

1. Dashboard con:
   - freschezza provider;
   - freschezza overlay;
   - copertura route;
   - archi matched;
   - delay Valhalla;
   - delay Android;
   - errori TomTom;
   - errori ricalcolo.
2. Correlation ID fra richiesta Android, route API, refresh updater e risposta Valhalla.
3. Metriche sui tempi:
   - TomTom;
   - matching;
   - scrittura traffic tar;
   - route traffic-aware;
   - route baseline;
   - risposta Android.
4. Test fixture end-to-end con:
   - congestione 15 km/h;
   - standstill 0 km/h;
   - segmenti mancanti;
   - match sulla direzione opposta;
   - feed stale;
   - overlay vuoto.
5. Aggiungere un endpoint diagnostico read-only che esponga la qualità del calcolo senza dati sensibili.
