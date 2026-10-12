# Android 0.28.31 — Crea viaggio compatto e callout sulla mappa

## Gate live unico

Installare `dist/compass-0.28.31-debug.apk` su un dispositivo con il backend Compass già
configurato. Non sono richieste modifiche al backend rispetto al gate 0.28.30.

1. Aprire **Crea viaggio** con partenza e destinazione valorizzate, sia in modalità Auto sia A piedi.
   Verificare che selettore modalità, due gruppi di localizzazione e pulsante **Calcola percorso**
   siano interamente visibili senza scorrere la pagina e senza testo tagliato.
2. Calcolare un percorso con almeno una svolta o rotatoria e una tappa intermedia; avviare la
   navigazione.
3. Verificare sulla mappa un callout ancorato al punto della prossima manovra, con nome strada o
   istruzione e simbolo coerente, e un callout separato sulla prossima tappa.
4. Superare la manovra e poi la tappa: i callout devono spostarsi ai successivi elementi senza
   restare sul punto completato. Ripetere almeno una volta in tema chiaro e scuro.

## Risultato atteso

- nessuno scroll nella schermata iniziale sul dispositivo di riferimento;
- pulsanti più compatti ma ancora alti almeno 56 dp;
- callout leggibili, coerenti con il tema e fissati alle coordinate della rotta;
- nessuna regressione a puck, camera, polilinea o controlli della navigazione.

## Diagnostica

Se un callout manca o resta fermo, restituire registrazione/screenshot e:

```bash
adb logcat -d -s CompassNavigationUi AndroidRuntime
adb shell dumpsys package org.compass.cng | grep version
```
