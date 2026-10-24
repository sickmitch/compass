# Android 0.28.37 — Completamento automatico delle tappe

Le tappe intermedie adottano il controllo di allontanamento già usato per le
soste CNG: ogni 10 secondi, al primo aggiornamento GPS accettato disponibile,
una distanza superiore a 50 metri con accuratezza entro 25 metri completa
la tappa. La regola si applica ad auto, bici e piedi. Il primo controllo
avviene dopo 10 secondi dall'arrivo; non serve una velocità GPS disponibile.
Senza nuovi fix validi la tappa resta attiva.

La navigazione riprende elaborando la posizione che ha rilevato la partenza;
durata residua ed ETA vengono aggiornate. Le tappe intermedie non hanno dwell;
le soste CNG mantengono la rimozione del dwell residuo alla conclusione.

## Verifica locale

Da `android/`, con JDK 17 e Android SDK configurati:

```bash
./gradlew --no-daemon testDebugUnitTest --tests org.compass.cng.navigation.NavigationEngineTest
./gradlew --no-daemon assembleDebug
```

I test coprono la cadenza di 10 secondi, tutte le modalità di viaggio,
la ripresa senza velocità GPS, il rifiuto di una posizione imprecisa e
il mantenimento del controllo sulle soste CNG.

## Live gate

Installare `dist/compass-0.28.37-debug.apk` e raggiungere una tappa intermedia.
Restando entro 50 m, la tappa deve restare attiva. Allontanandosi oltre 50 m
con GPS accurato, deve completarsi al controllo successivo (entro circa
10 secondi con fix regolari), senza premere il pulsante di conferma.
Verificare la ripresa delle indicazioni e l'aggiornamento di durata ed ETA.
Ripetere con una sosta CNG: il dwell non consumato deve sparire dalla durata
residua mentre le soste future restano conteggiate.

In caso di errore restituire tipo di tappa, modalità di viaggio, tempo
dall'allontanamento, schermata e log del dispositivo, se disponibile:

```bash
adb logcat -d | rg -i 'compass|stop completed|refuell|GPS departure'
```

La validazione sul dispositivo resta a cura dell'operatore.
