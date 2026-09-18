# MediTimer MVP — Android

MVP Android nativo in Kotlin + Jetpack Compose per:

- anagrafica farmaci;
- ricorrenze: ogni giorno, giorni della settimana, ogni N giorni, giorni specifici del mese;
- 1–8 assunzioni nei giorni attivi con orari indipendenti;
- notifiche locali con pulsante **Farmaco assunto**;
- countdown post-assunzione configurabile per ciascun farmaco;
- notifica alla fine del countdown, anche con app chiusa;
- data ultimo cambio confezione, durata massima e prossimo cambio;
- storico tecnico delle assunzioni salvato localmente (fino a 1.000 eventi);
- ripristino degli allarmi dopo riavvio del telefono.

## Privacy

L'MVP è offline: salva i dati localmente sul telefono tramite SharedPreferences/JSON. Non usa account, server o cloud.

## Requisiti

- Android Studio recente
- JDK 17
- Android SDK 35
- Min Android: 8.0 (API 26)

## Avvio

1. Apri la cartella `MediTimerMVP` in Android Studio.
2. Se Android Studio segnala che manca `gradle-wrapper.jar`, esegui una volta `./gradlew --version` (Windows: `gradlew.bat --version`): lo script scarica il wrapper ufficiale Gradle 8.9.
3. Lascia che Gradle scarichi le dipendenze.
4. Esegui `app` su telefono/emulatore Android.
5. Al primo avvio consenti le notifiche.
6. Su Android 12+ premi **Abilita** nel banner “Allarmi precisi non abilitati” e autorizza gli allarmi precisi.

## Logica degli allarmi

Per ogni orario di un farmaco viene pianificata solo la prossima occorrenza valida. Quando l'allarme scatta, il receiver programma la successiva. Questo evita di memorizzare centinaia di PendingIntent.

Il pulsante **Farmaco assunto**, disponibile sia nell'app sia nella notifica:

1. registra data/ora dell'assunzione;
2. marca l'assunzione come completata;
3. se configurato, crea un countdown;
4. pianifica una notifica esatta alla fine dell'attesa.

Il countdown non richiede un processo continuamente attivo: viene memorizzata l'ora di fine e viene usato `AlarmManager`.

## Limiti intenzionali dell'MVP

- Nessun login, cloud o sincronizzazione tra dispositivi.
- Nessuna modifica/eliminazione dello storico assunzioni dalla UI.
- Nessun “snooze” della dose.
- Nessuna notifica anticipata per cambio confezione: la scadenza è visibile nella sezione Confezioni.
- Ricorrenza mensile implementata tramite giorni del mese (es. 1, 10, 20), non ancora tramite formule tipo “secondo lunedì del mese”.
- Se l'utente nega gli allarmi esatti, Android può ritardare le notifiche.

## Nota importante

L'app è un promemoria personale e non interpreta prescrizioni mediche, interazioni o dosaggi. Le regole vanno inserite dall'utente in base alle indicazioni ricevute dal professionista sanitario o al foglietto illustrativo.
