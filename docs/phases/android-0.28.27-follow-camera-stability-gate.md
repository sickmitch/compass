# Android 0.28.27 — stabilità camera senza percorso

## Criteri di accettazione

- La camera senza percorso accetta solo fix con precisione dichiarata non superiore a 75 m.
- Un fix recente e preciso non viene sostituito da un aggiornamento concorrente sensibilmente meno
  preciso proveniente dall'altro provider.
- Gli aggiornamenti fuori ordine vengono ignorati.
- Posizione, velocità e bearing passano attraverso lo stesso filtro usato dalla navigazione.
- Da fermo il rumore del bearing non ruota la mappa.
- Il live gate produce `dist/compass-0.28.27-debug.apk`, firmato e installabile.

## Verifica locale

```bash
cd /home/mike/NAS/tech/projects/compass/android
JAVA_HOME=/home/mike/toolchains/jdk17 \
ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk \
./gradlew --no-daemon \
  -PCOMPASS_API_BASE_URL=https://compass.sickmitch.cc/ \
  testDebugUnitTest lintDebug assembleDebug
```

## Live gate

1. Installare l'APK 0.28.27 e aprire la schermata GPS senza creare un percorso.
2. Lasciare il dispositivo fermo per almeno un minuto con GPS e rete attivi.
3. Verificare che la mappa non compia rotazioni o traslazioni temporanee come quelle osservate a
   4,5–6 s e 25–27 s nel video di riferimento.
4. Percorrere un breve tratto e verificare che posizione e orientamento continuino ad aggiornarsi.
