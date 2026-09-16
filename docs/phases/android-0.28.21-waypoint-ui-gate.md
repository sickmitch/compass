# Android 0.28.21 — interfaccia tappe e navigazione

## Criteri di accettazione

- Le schede dei risultati distribuiscono identità e metadati su entrambe le metà disponibili.
- Durante la modifica di una tappa non compare il pannello espandibile **Aggiungi tappa**.
- Il calcolo del piano CNG mantiene sulla mappa la polilinea passante per le tappe manuali.
- I pannelli superiori di navigazione sono più compatti e non riservano spazio alla status bar
  nascosta.
- La tappa manuale resta sotto al CNG finché è più lontana; quando diventa la prossima sosta sale
  nella prima posizione.
- Il pannello della tappa manuale mostra il suo nome, non **Tappa intermedia**.
- Il live gate produce `dist/compass-0.28.21-debug.apk`, firmato e installabile.

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

1. Cercare una tappa lungo il percorso e controllare l'uso delle due colonne nella scheda.
2. Modificare una tappa esistente e verificare che restino il banner di modifica e i metodi di
   selezione, senza il chevron **Aggiungi tappa**.
3. Calcolare un piano con una tappa fuori dal percorso diretto e verificare la polilinea durante il
   caricamento.
4. Avviare la navigazione con una tappa manuale e un CNG e verificare ordine dinamico, nome e
   compattezza dei pannelli superiori.
