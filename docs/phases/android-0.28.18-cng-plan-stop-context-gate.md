# Android 0.28.18 — contesto tappe dal Piano CNG

## Criteri di accettazione

- Entrando in **Inserisci tappa intermedia** dal Piano CNG viene inizializzato il limite temporale
  predefinito a un terzo della durata di guida.
- Ricerca lungo il percorso e selezione sulla mappa usano immediatamente il percorso già calcolato.
- Non compaiono più `Calcola prima un percorso` o `Inserisci un tempo aggiuntivo massimo valido`
  quando il percorso base è presente.
- Il live gate produce `dist/compass-0.28.18-debug.apk`, firmato e installabile.

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

1. Calcolare un percorso e aprire **Piano CNG**.
2. Premere **Inserisci tappa intermedia** senza avere precedentemente aperto Aggiungi tappe.
3. Cercare una località lungo il percorso e verificarne i risultati.
4. Ripetere scegliendo un punto sulla mappa e confermare con **Scegli**.
5. Verificare che entrambe le modalità mostrino l'anteprima della tappa senza errori di contesto.
