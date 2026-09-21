# Android 0.28.29 — avanzamento ricalcolo senza autostrade

## Incremento

- Dopo **Ricalcola senza**, il popup di conferma viene sostituito immediatamente da un dialogo di
  avanzamento animato.
- Il dialogo blocca interazioni concorrenti e resta visibile fino al completamento o all'errore del
  ricalcolo.
- La policy temporanea senza autostrade e la preferenza persistente restano invariate.

## Gate locale

```bash
cd android
JAVA_HOME=/home/mike/toolchains/jdk17 \
ANDROID_SDK_ROOT=/home/mike/toolchains/android-sdk \
./gradlew --no-daemon -PCOMPASS_API_BASE_URL=https://compass.sickmitch.cc/ \
  testDebugUnitTest lintDebug assembleDebug
```

L'APK risultante deve essere copiato in `dist/compass-0.28.29-debug.apk`.

## Gate dispositivo

1. Con **Autostrade: Attivo**, calcolare una tratta autostradale.
2. Nel popup scegliere **Ricalcola senza**.
3. Verificare che compaiano immediatamente l'indicatore animato e il testo
   **Cerco un percorso senza autostrade…**.
4. Verificare che il dialogo scompaia solo quando viene mostrato il nuovo percorso o un errore.
5. Verificare che nelle opzioni **Autostrade** resti **Attivo**.
