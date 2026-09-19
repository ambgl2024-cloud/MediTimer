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
