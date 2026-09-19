# MediTimer v0.6.3

Correzioni interfaccia per schermi diversi:
- barra di navigazione inferiore responsive;
- testo dei menu sempre su una riga;
- icone e testi leggermente ridotti sui display stretti;
- fascia dei tasti/gesture Android in verde petrolio, con icone di sistema chiare;
- fallback anche tramite window.navigationBarColor per dispositivi/OEM che non disegnano edge-to-edge nello stesso modo.

Funzionalità farmaci, scorte, data apertura, countdown, doppio bip e snooze invariate.

# MediTimer v0.6.2

Correzione gestione confezioni:
- ripristinato il comando Modifica data apertura;
- Cambiata oggi = registra un nuovo cambio e scala 1 dalla scorta;
- Modifica data = corregge solo la data e NON modifica la scorta;
- il prossimo cambio e i promemoria vengono ricalcolati sulla nuova data;
- restano visibili solo ultimo cambio, giorni mancanti e confezioni rimaste;
- countdown, doppio bip, snooze e firma stabile invariati.

# MediTimer v0.6.1

- Schermata Confezioni semplificata.
- Mostra solo ultimo cambio, giorni mancanti e confezioni rimaste.
- Nessuno storico visibile.
- Comandi: Cambiata oggi, Imposta scorta, Acquisto, Scarto.
- Avvisi scorte/cambio invariati.
- Countdown, doppio bip, snooze e firma stabile invariati.

# MediTimer v0.6.0

Nuova gestione Confezioni e Scorte:
- scorta per farmaco (confezioni chiuse, esclusa quella in uso);
- cambio confezione = -1 automatico dalla scorta;
- + Acquisto, - Scarto, Imposta scorta;
- avvisi quotidiani dalle 09:00, a partire da 7 giorni prima del cambio;
- avviso immediato quando la scorta scende a 1 o 0, poi promemoria quotidiano finché non viene ripristinata;
- avvisi visibili anche nella schermata Oggi;
- countdown e doppio bip v0.5.6 invariati.

# MediTimer v0.5.6

Correzione doppio bip di fine countdown:
- stesso doppio bip originale;
- 250 ms di silenzio iniziale per evitare il taglio del primo bip a schermo spento;
- nessun bip intermedio;
- countdown persistente;
- exact alarm + WAKE_LOCK;
- snooze configurabile per farmaco;
- schermata Info/versione/changelog.

# MediTimer v0.5.5

Release di correzione del countdown finale.

- Countdown persistente tra pagine, uscita/riapertura app e schermo spento.
- Nessun bip intermedio.
- Fine countdown: exact AlarmManager + bip personalizzato `countdown_finish.wav` tramite `SoundHelper`.
- `WAKE_LOCK` ripristinato solo per mantenere il breve audio finale; non richiede alcun consenso o esclusione dal risparmio energetico.
- NotificationChannel finale intenzionalmente silenzioso: vibra e mostra la notifica, ma non riproduce la suoneria sveglia Android.
- Snooze configurabile per ogni farmaco.
- Info app con versione installata e changelog.
- Firma release stabile tramite GitHub Secrets.

Versione: `0.5.5` (`versionCode 12`).
