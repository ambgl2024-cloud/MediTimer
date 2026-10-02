# MediTimer v0.7.5

Correzione richiesta:
- `Farmaco assunto` da notifica Android o dalla sveglia continua a registrare la stessa dose e ad avviare l'eventuale countdown;
- all'apertura automatica di MediTimer viene ora forzato anche il refresh dei dati;
- la dose passa immediatamente a `Assunto` in `Oggi`;
- l'evento appena registrato compare immediatamente nel `Calendario` e nelle statistiche `Storia`;
- nessuna modifica a sveglie, snooze, countdown, scorte o altre funzioni.

# MediTimer v0.7.4

Correzioni richieste:
- `Farmaco assunto` dalla notifica preliminare o dalla sveglia apre automaticamente l'app su `Oggi`, così l'eventuale countdown è subito visibile.
- Se una dose in snooze viene registrata come `Assunto` dall'app, il repeat pendente viene cancellato; `scheduleSnooze` ricontrolla inoltre lo stato della dose per evitare race condition.
- Alla fine del countdown non viene più pubblicata alcuna notifica Android: restano esclusivamente il doppio bip personalizzato e la vibrazione.
- Nessun'altra funzione è stata modificata.

# MediTimer v0.7.3

Modifiche richieste alle assunzioni e alle sveglie:
- notifica silenziosa 1 ora prima dell'orario previsto, con azione `Farmaco assunto`;
- all'orario previsto, se la dose non è già registrata, vera sveglia Android con suoneria di sistema e vibrazione;
- sveglia a schermo intero quando Android lo consente, con `Farmaco assunto` e `Snooze`;
- durata massima del suono: 2 minuti;
- se non viene premuto nulla, nuova sveglia automatica dopo lo snooze configurato;
- premendo Snooze, il nuovo intervallo decorre dal momento del tap;
- dopo `Farmaco assunto` non viene generata alcuna notifica Android di conferma;
- una dose notturna non assunta resta pending in `Oggi` anche dopo mezzanotte e viene mantenuta fino all'orario della successiva dose dello stesso farmaco;
- quando arriva la dose successiva, la precedente smette di essere pending ed è trattata dalle statistiche come non assunta;
- countdown `Oggi` reso flottante, senza spostare l'elenco delle assunzioni.

Le logiche di scorte, Storia, reminder riacquisto e doppio bip del countdown non sono state modificate.

# MediTimer v0.7.2

Correzione mirata delle statistiche Storia / periodi critici:
- una dose viene considerata prevista solo dopo il suo orario effettivo;
- il resto della giornata, settimana o mese ancora futuro non peggiora la regolarità;
- giorni precedenti all'attivazione e successivi a sospensione/eliminazione non vengono conteggiati;
- nessun'altra funzione dell'app è stata modificata.

# MediTimer v0.7.1

Modifiche richieste:
- reminder di riacquisto scorta configurabile per farmaco: Nessuno / Punto di riordino / Tempo residuo;
- Punto di riordino: avviso quando scorta <= soglia;
- Tempo residuo: con scorta 0, avviso X giorni o X assunzioni prima della fine dell’ultima confezione;
- primo avviso Android + promemoria settimanale finché la condizione resta valida;
- avviso sempre visibile in Oggi mentre la condizione è valida;
- farmaci esistenti migrati automaticamente a Punto di riordino, soglia 1;
- Storia con filtro superiore persistente: mese corrente/precedente, anno corrente, settimana corrente/precedente, da sempre, Dal–Al;
- KPI: regolarità periodo, mese, anno, da sempre, andamento recente, orari medi per slot con scostamento e dispersione, periodi più critici;
- Timeline e Farmaci mantengono presentazione discorsiva e usano lo stesso filtro persistente.

Non sono state modificate le logiche di sveglie farmaco, snooze, countdown o doppio bip.

# MediTimer v0.7.0

Modifica richiesta: correzione manuale dei farmaci duplicati nella Storia.

- In `Storia → Farmaci`, i farmaci storici/eliminati mostrano `Unisci con farmaco attuale`.
- L'utente sceglie il farmaco attuale di destinazione e conferma l'operazione.
- Le vecchie assunzioni vengono riassegnate al farmaco attuale.
- La cronologia terapeutica viene unificata.
- Il passaggio accidentale `eliminato → ricreato` non viene più mostrato come interruzione della terapia.
- Se la configurazione del farmaco ricreato era realmente diversa, quella differenza viene conservata come modifica dello schema.
- Le impostazioni attuali del farmaco di destinazione NON vengono sovrascritte:
  sveglie, ricorrenza, scorte, confezione, countdown e snooze restano invariati.
- L'unione è disponibile solo da un record storico/eliminato verso un farmaco che esiste ancora.
- Conferma esplicita obbligatoria prima dell'unione.

Nessun'altra funzione dell'app è stata modificata.

# MediTimer v0.6.9

Funzioni aggiunte su richiesta:
- nuova sezione `Storia` con:
  - Panoramica aderenza/regolarità;
  - Timeline discorsiva dei periodi di terapia;
  - Storia per farmaco, inclusi farmaci eliminati;
- creazione, modifiche a orari/ricorrenza/schema, disattivazione, riattivazione ed eliminazione
  vengono registrate automaticamente senza nuovi campi da compilare;
- per i farmaci già esistenti la data iniziale viene ricostruita dall'ID temporale quando plausibile;
- nessuna voce di acquisto, scarto o cambio confezione entra nella Timeline terapeutica;
- Farmaci: filtro Tutti / Attivi / Sospesi;
- eliminazione farmaco sempre con conferma; modifica senza conferma;
- Calendario: filtri comprimibili per Tutto / Anno / Mese / Settimana / Giorno e per farmaco;
- Confezioni: solo farmaci attivi;
- KPI Confezioni uniformati con icone, valori e testi centrati e allineati.

Le logiche esistenti di sveglie, snooze, countdown, doppio bip, scorte, cambio confezione,
import/export CSV e guida non vengono modificate.

# MediTimer v0.6.8

Restyling grafico approvato:
- Farmaci: schede più compatte, icone al posto di etichette verbose, indicatore Attivo/Sospeso, ricerca e ordinamento A–Z/Z–A.
- Sveglie: schede tutte della stessa larghezza, altezza variabile in base al contenuto.
- Confezioni: indicatori informativi più grandi e uniformi; pulsanti più compatti; ricerca e ordinamento A–Z/Z–A.
- Nessuna modifica alla logica funzionale, alle notifiche, alla gestione scorte, al countdown o alla guida.

# MediTimer v0.6.7

Modifica richiesta: sola aggiunta della guida interattiva.
- guida passo-passo al primo utilizzo su installazione senza farmaci configurati;
- riapribile da Info → Guida;
- spiega menu, anagrafica farmaci, ricorrenze, sveglie, snooze, Assunto/Non assunto,
  countdown post-assunzione, gestione scorte, durata in giorni o assunzioni,
  cambio confezione, acquisto/scarto, avvisi di scorta e Calendario/CSV;
- nessuna funzione esistente modificata o rimossa.

# MediTimer v0.6.6

Gestione confezioni differenziata per singolo farmaco:
- farmaci a giorni: menu invariato;
- farmaci a numero di assunzioni: `Imposta scorta` + `Imposta assunzioni rimaste` sulla stessa riga;
- `Acquisto` + `Scarto` restano affiancati;
- il residuo manuale della confezione continua a decrementarsi a ogni registrazione `Assunto`;
- `Cambiata oggi` resetta il residuo al massimo configurato;
- `Modifica data` ricalcola il conteggio sulla nuova data;
- countdown, doppio bip, snooze e barra responsive invariati.

# MediTimer v0.6.5

Layout sezione Confezioni aggiornato:
- Cambiata oggi + Modifica data sulla prima riga;
- Imposta scorta a tutta larghezza;
- Acquisto + Scarto sulla stessa riga;
- logica funzionale invariata.

# MediTimer v0.6.4

Nuova gestione durata confezione:
- modalità Giorni oppure Assunzioni, mutuamente esclusive;
- farmaci esistenti mantengono automaticamente la modalità Giorni;
- in modalità Assunzioni il consumo è calcolato dagli eventi registrati come Assunto;
- se restano 7 assunzioni o meno vengono mostrati/gestiti gli avvisi di cambio;
- nella sezione Confezioni il KPI centrale diventa Assunzioni rimaste;
- cambio confezione, scorte, responsive navbar, countdown e doppio bip restano invariati.

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
