# MediTimer Android — v0.4.2


## Hotfix v0.4.2 — beep del countdown a schermo spento

Il beep minuto-per-minuto ora usa un **foreground service `mediaPlayback`**, un thread dedicato e un `PARTIAL_WAKE_LOCK`. Prima veniva usato un servizio `specialUse` con timer sul main looper: su alcuni telefoni il processo veniva congelato quando il display si spegneva, mentre l'exact alarm finale continuava a funzionare.

La scheda **Oggi** segnala inoltre quando Android sta applicando l'ottimizzazione batteria a MediTimer e consente di richiedere **uso batteria senza restrizioni**. Questa autorizzazione è raccomandata per i beep intermedi a schermo spento. L'exact alarm finale resta attivo come fallback indipendente.
MediTimer è un'app Android locale/offline per gestire farmaci periodici, promemoria, countdown post-assunzione, cambio confezione e storico delle assunzioni.

## Novità v0.4.2

### Correzione firma stabile

La v0.4.0 caricata su GitHub continuava a usare il vecchio workflow `assembleDebug`; per questo Android non riconosceva gli APK successivi come aggiornamenti. La v0.4.2 compila esclusivamente `assembleRelease`, ricostruisce il keystore dai GitHub Secrets e verifica il fingerprint SHA-256 della chiave stabile prima della build. Se la chiave non è quella attesa, la build fallisce.

**Attenzione:** se sul telefono è installata una build debug precedente, serve ancora una disinstallazione una tantum prima di installare la prima v0.4.2 release firmata stabilmente. Da quel momento in poi, mantenendo gli stessi Secrets, gli aggiornamenti saranno installabili sopra la versione esistente.

## Funzioni v0.4.0 mantenute

### Countdown affidabile anche a schermo spento

Il countdown non usa più un allarme Android ogni minuto. Android può infatti rallentare gli allarmi ripetuti quando entra in Doze con lo schermo spento.

Durante un countdown MediTimer avvia un **foreground service** dedicato e mantiene un **partial wake lock** solo per la durata del countdown. Questo permette di:

- mantenere il countdown attivo con display bloccato;
- emettere il singolo bip ogni minuto anche a schermo spento;
- riprodurre il suono finale a 0;
- mostrare una notifica persistente `MediTimer · countdown attivo` mentre il servizio è in funzione.

Rimane inoltre programmato un singolo exact alarm all'orario finale come fallback nel caso il processo venga terminato.

### Backup e ripristino CSV dello storico

Nella scheda **Calendario** sono disponibili:

- `Esporta CSV`;
- `Importa CSV`.

L'import accetta i CSV esportati da MediTimer v0.2/v0.3/v0.4. Prima del salvataggio mostra:

- righe lette;
- eventi validi;
- eventi nuovi;
- eventi già presenti;
- duplicati nel file;
- righe non valide.

Sono disponibili due modalità:

- **Aggiungi**: mantiene lo storico esistente e aggiunge solo gli eventi mancanti;
- **Sostituisci**: elimina lo storico presente e lo sostituisce con il CSV, dopo una seconda conferma.

L'importazione dello storico non modifica Farmaci, Sveglie o le relative ricorrenze.

L'export v0.4 aggiunge anche `ID evento` e `ID farmaco`, mantenendo compatibili le colonne delle versioni precedenti.

## Calendario

Lo storico resta memorizzato anche quando un farmaco viene cancellato dall'anagrafica.

Toccando un evento puoi:

- modificare nome farmaco;
- modificare data/ora effettiva;
- modificare data/ora prevista;
- salvare;
- cancellare definitivamente l'evento.

## Altre funzioni

- Ricorrenze: ogni giorno, giorni della settimana, ogni N giorni, giorni del mese.
- Più orari al giorno.
- Pulsanti `Assunto` / `Non assunto`.
- Countdown configurabile per farmaco.
- Singolo bip breve ogni minuto.
- Suono differente a fine countdown.
- Cambio confezione con data ultimo cambio e scadenza.
- Icona MediTimer capsula + orologio.

## Firma stabile

La build release usa gli stessi GitHub Actions Secrets configurati dalla v0.3.0:

- `MEDITIMER_KEYSTORE_BASE64`
- `MEDITIMER_STORE_PASSWORD`
- `MEDITIMER_KEY_ALIAS`
- `MEDITIMER_KEY_PASSWORD`

Non cambiare questi secret se vuoi che Android riconosca le versioni successive come aggiornamenti della stessa app.

## Build automatica

Il workflow `.github/workflows/build-apk.yml` compila una release firmata e pubblica l'artifact:

`MediTimer-APK`

contenente:

`app-release.apk`

## Permessi Android

MediTimer utilizza:

- notifiche;
- exact alarms;
- wake lock durante il countdown;
- foreground service `specialUse` durante il countdown.

Il foreground service viene eseguito solo quando esiste almeno un countdown attivo e termina quando non ci sono più countdown.
