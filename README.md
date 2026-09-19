# MediTimer Android — v0.5.3

Versione correttiva focalizzata sull'affidabilità del countdown.

## Countdown persistente

Il countdown non dipende più dal ciclo di vita della sola schermata **Oggi**.

- lo stato viene salvato in modo sincrono su storage locale;
- l'ora di fine (`endMillis`) resta la fonte di verità;
- il clock usato per visualizzare il tempo residuo vive a livello dell'app, quindi cambiare scheda non resetta il countdown;
- chiudendo e riaprendo normalmente l'app, il countdown viene riletto dallo storage e riprende dal tempo residuo corretto;
- a schermo spento il countdown non richiede un servizio continuo: l'avviso finale è affidato ad `AlarmManager`;
- dopo un riavvio del telefono o un processo terminato, i countdown persistiti vengono riarmati.

> Nota: come per qualunque app Android, un **Arresto forzato / Force stop** dalle impostazioni di sistema può impedire ad Android di consegnare allarmi finché l'app non viene aperta di nuovo.

## Fine countdown: ritorno al meccanismo affidabile

Il suono personalizzato `MediaPlayer` non viene usato per l'avviso finale.

La fine countdown usa un nuovo NotificationChannel:

`countdown_alarm_v4`

configurato con il **suono allarme predefinito di Android**, lo stesso meccanismo che nelle prime versioni di MediTimer funzionava correttamente anche con schermo spento.

Flusso:

`Exact Alarm -> BroadcastReceiver -> NotificationChannel Android -> suono + vibrazione + notifica`

L'exact alarm contiene anche una copia di nome farmaco e nota del countdown. Quindi l'avviso finale può essere emesso anche se il processo dell'app è stato ricreato e lo stato UI non è disponibile.

## Funzioni mantenute

- snooze configurabile per ciascun farmaco;
- pulsanti `Farmaco assunto` e `Rimanda X min` nella notifica;
- countdown predefinito 2 minuti per i nuovi farmaci;
- nessun bip intermedio;
- nessuna richiesta di disattivare il risparmio energetico;
- orari multipli equidistanti di default;
- selettore orario Android HH:mm 24 ore;
- storico calendario modificabile;
- import/export CSV;
- storico mantenuto anche dopo eliminazione farmaco;
- firma release stabile.

## Versione

- `versionCode = 10`
- `versionName = 0.5.3`

## Firma

Il workflow continua a usare gli stessi GitHub Secrets:

- `MEDITIMER_KEYSTORE_BASE64`
- `MEDITIMER_STORE_PASSWORD`
- `MEDITIMER_KEY_ALIAS`
- `MEDITIMER_KEY_PASSWORD`

Non modificare questi valori.
