# Android 0.28.30 — navigazione pedonale

## Obiettivo

Un solo gate copre contratto backend e prova su dispositivo della modalità **A piedi**.

## Preparazione operatore

Dal server live, nella directory del repository sincronizzato:

```bash
docker compose build api
docker compose up -d api
docker compose ps
```

L'istanza Valhalla deve contenere le tile dell'area scelta per la prova. Non servono dataset CNG per
il percorso pedonale.

Installare sul dispositivo l'APK `dist/compass-0.28.30-debug.apk`, mantenendo configurato l'endpoint
Compass già usato per le prove.

## Gate unico

1. In **Crea viaggio**, selezionare **A piedi**, impostare due punti vicini collegati anche da vie
   pedonali e calcolare il percorso.
2. Verificare che il riepilogo mostri **Modalità · A piedi**, che **Piano CNG** e **Sosta CNG** non
   siano disponibili e che sia comunque possibile aggiungere, riordinare e rimuovere una tappa
   ordinaria.
3. Avviare la navigazione a piedi e percorrere un tratto: puck e camera devono seguire il passo senza
   richiedere velocità automobilistiche; gli annunci devono arrivare al cambio di manovra e alle
   soglie pedonali 100 m, 50 m ("A breve") e 10 m.
4. Deviare volontariamente di almeno 15–20 metri e verificare il ricalcolo pedonale; chiudere e
   riaprire l'app durante l'anteprima o la navigazione e verificare che la modalità resti **A piedi**.
5. Tornare a **Auto** e verificare che Piano CNG, Sosta CNG, traffico, autostrade e limiti di velocità
   mantengano il comportamento precedente.

## Verifica contratto live

Con `COMPASS_API_BASE_URL` già comprensivo di `/` e, se l'autenticazione è attiva,
`COMPASS_API_USERNAME` e `COMPASS_API_PASSWORD`:

```bash
curl --fail --silent --show-error \
  --user "$COMPASS_API_USERNAME:$COMPASS_API_PASSWORD" \
  --header 'Content-Type: application/json' \
  --data '{"origin":{"latitude":45.4384,"longitude":10.9916},"destination":{"latitude":45.4420,"longitude":10.9980},"costing":"pedestrian"}' \
  "${COMPASS_API_BASE_URL}api/v1/routes"
```

Risultato atteso: HTTP 200, geometria non vuota e manovre pedonali. In caso di errore restituire
l'intera risposta, più:

```bash
docker compose logs --tail=200 api valhalla
curl --silent --show-error "${COMPASS_API_BASE_URL}health/ready"
```

## Criteri di accettazione

- il backend inoltra `pedestrian` a Valhalla senza arricchimenti automobilistici;
- la modalità resta coerente in calcolo, tappe, cache e ricalcolo;
- la UI pedonale non espone funzioni CNG;
- guida, camera e rilevamento fuori rotta sono utilizzabili alla velocità del passo;
- un nuovo calcolo **Auto** continua a offrire tutte le funzioni automobilistiche esistenti.
