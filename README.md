# MediTimer Android — v0.5.1

MediTimer è un'app Android locale/offline per gestire farmaci periodici, promemoria, countdown post-assunzione, cambio confezione e storico delle assunzioni.


## Novità v0.5.1 — Snooze per farmaco

- Ogni farmaco ha un parametro **Snooze tra gli avvisi (minuti)**, con default 10 minuti.
- La notifica propone **Farmaco assunto** e **Rimanda X min**.
- Lo snooze può essere ripetuto più volte.
- Premendo **Assunto**, lo snooze pendente della dose viene cancellato.
- Gli snooze pendenti vengono salvati localmente e ripristinati dopo un riavvio del telefono.
- Lo snooze non modifica data/orario programmati della dose e non crea eventi nello storico finché il farmaco non viene realmente segnato come assunto.

## Novità v0.5.0

- Rimossi i beep intermedi del countdown.
- Rimossa la richiesta di esclusione dal risparmio energetico.
- Il countdown continua a essere calcolato tramite timestamp e viene notificato solo al termine tramite exact alarm.
- Durata predefinita del countdown per un nuovo farmaco: **2 minuti**.
- Se imposti più assunzioni giornaliere, gli orari proposti sono equidistanti sulle 24 ore a partire dal primo orario: 2 = ogni 12h, 3 = ogni 8h, 4 = ogni 6h.
- Gli orari si impostano tramite selettore Android in formato **24 ore HH:mm**, non più come testo libero.
- Modificando il primo orario con più assunzioni, gli altri vengono ricalcolati equidistanti; puoi poi modificare ogni singolo orario manualmente.
- Restano import/export CSV, modifica/cancellazione eventi del Calendario e storico permanente anche dopo l'eliminazione di un farmaco.

## Firma stabile

La build release usa i GitHub Actions Secrets già configurati:

- `MEDITIMER_KEYSTORE_BASE64`
- `MEDITIMER_STORE_PASSWORD`
- `MEDITIMER_KEY_ALIAS`
- `MEDITIMER_KEY_PASSWORD`

Il workflow compila `assembleRelease`, verifica il fingerprint della chiave MediTimer e pubblica `app-release.apk`.

## Permessi Android

MediTimer richiede solo i permessi necessari per notifiche, allarmi precisi, reboot e vibrazione. Non richiede più l'esclusione dalle ottimizzazioni batteria né un foreground service per il countdown.

## Nota aggiornamento v0.5.1 FIX

Il browser uploader di GitHub sovrascrive i file presenti ma non elimina automaticamente i file rimossi dal progetto. Per questo il pacchetto include un `CountdownService.kt` neutro che sovrascrive eventuali copie obsolete provenienti dalle versioni precedenti. Il servizio non viene utilizzato dall'app.
