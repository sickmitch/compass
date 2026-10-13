# Android 0.28.33 — pannelli e ripartenza CNG

## Consegna

Modifiche solo Android, nessun deploy backend o nuova configurazione. APK:
`dist/compass-0.28.33-debug.apk`, versionCode 79. Installabile direttamente dal dispositivo.
Dalla radice del repository, con telefono collegato e debug USB autorizzato:

```bash
adb install -r dist/compass-0.28.33-debug.apk
```

Mantenere server e credenziali dell'app già configurati. Nessun segreto nuovo richiesto.

## Gate unico sul dispositivo

1. Avviare un viaggio con una sosta CNG. La schedina della prossima sosta deve adattarsi al contenuto
   ed essere centrata; nomi lunghi restano contenuti nella larghezza disponibile. Durante il
   rifornimento rimane spazio per countdown e pulsante Completato.
2. A sinistra, accanto al muto, deve comparire l'orario previsto di arrivo alla destinazione.
   L'ingranaggio deve essere presente solo nel menu aperto dal chevron. Verificare entrata/uscita
   dalle opzioni senza interrompere la navigazione.
3. Passare alla panoramica: Ricentra diventa un'icona con sfondo/colore uguali agli altri controlli;
   al tocco torna il follow. Ripetere con pannello informativo aperto/chiuso e tema chiaro/scuro.
4. Controllare un cartiglio di svolta/rotatoria e uno di tappa: bordo continuo e regolare,
   anche attorno alla punta inferiore, senza bordi tagliati o giunzioni sovrapposte.
5. Durante una sosta CNG, restare vicino alla stazione: countdown ancora attivo. Ripartire senza
   premere Completato: oltre 50 m, confermati da due fix affidabili, la sosta deve risultare conclusa,
   guida e puck devono riprendere e l'ETA deve perdere il dwell residuo. La soglia include la
   precisione GPS: con precisione 5 m occorre superare 55 m; fix oltre 25 m di incertezza non contano.
   Non serve attendere che finisca il timer. Le tappe successive e la destinazione si conservano.
6. Fermarsi in sicurezza e riaprire l'app: la sosta conclusa non deve tornare attiva. Verificare anche
   che Completato manuale continui a funzionare. Non manipolare il telefono durante la guida.

Restituire esito dei punti e screenshot in caso di difetti visuali. Per mancata ripartenza allegare
GPS/precisione visibili negli strumenti sviluppatore e, da computer collegato:

```bash
adb logcat -d -s CompassNavigation CompassNavigationUi CompassPlanner AndroidRuntime
```

Il log di sessione deve riportare `CNG refuelling completed by GPS departure` quando la sosta si
chiude automaticamente. Indicare anche distanza approssimativa dalla stazione e versione installata.
La validazione live resta a carico dell'operatore; il prossimo passo è valutare questo gate.

## Validazione locale

Da `android/`, con JDK 17 e Android SDK configurati tramite `JAVA_HOME` e `ANDROID_SDK_ROOT`:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

I test coprono completamento GPS, aggiornamento immediato di progress/ETA, fix isolato,
precisione insufficiente e mantenimento del completamento manuale. La resa grafica va verificata
sul dispositivo: i test locali non sostituiscono tale prova.

Esito locale 2026-10-12: `testDebugUnitTest lintDebug assembleDebug` completato con successo,
278 test Android passati. `git diff --check` senza errori. Prova live ancora da confermare.
