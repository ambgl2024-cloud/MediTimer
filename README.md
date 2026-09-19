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
