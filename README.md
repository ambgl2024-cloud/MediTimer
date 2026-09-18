# MediTimer Android MVP — v0.2.0

MediTimer è un MVP Android locale/offline per gestire farmaci periodici, promemoria, countdown post-assunzione, cambio confezione e storico delle assunzioni.

## Funzioni principali

- Farmaci con nome, dose/nota e stato attivo/sospeso.
- Ricorrenze: ogni giorno, giorni della settimana, ogni N giorni, giorni del mese.
- Più orari di assunzione per ciascun giorno attivo.
- Promemoria Android tramite AlarmManager.
- Scheda **Oggi** con stato della dose.
- Pulsante **Assunto** e possibilità di correggere con **Non assunto**.
- Countdown post-assunzione configurabile per farmaco.
- Beep breve ogni minuto trascorso durante il countdown.
- Suono differente al termine del countdown + notifica di fine attesa.
- Gestione cambio confezione.
- Scheda **Calendario** con data e ora effettiva delle assunzioni.
- Lo storico rimane anche se il farmaco viene eliminato dall'anagrafica.
- Export dello storico in CSV UTF-8 compatibile con Excel (separatore `;`).

## Comportamento storico

Quando premi **Assunto**, viene salvato un evento contenente:

- ID farmaco;
- nome del farmaco al momento dell'assunzione;
- data/orario programmati;
- timestamp reale dell'assunzione.

Se successivamente elimini il farmaco, vengono cancellati anagrafica, sveglie e countdown attivi, ma **non le assunzioni storiche**.

## Correzione di un'assunzione

Se premi per errore **Assunto**, nella scheda Oggi puoi premere **Non assunto**. Questo:

1. elimina la registrazione storica di quella dose;
2. interrompe l'eventuale countdown collegato;
3. riporta la dose allo stato da assumere.

## Export CSV

Nella scheda **Calendario**, premi `CSV`. Android chiederà dove salvare il file.

Colonne esportate:

- Data
- Ora assunzione
- Farmaco
- Data prevista
- Ora prevista
- Timestamp

## Build automatica

Il repository contiene `.github/workflows/build-apk.yml`.

Ogni push su `main` compila automaticamente l'APK e crea l'artifact `MediTimer-APK`.

APK generato:

`app/build/outputs/apk/debug/app-debug.apk`

## Permessi Android

Al primo utilizzo autorizzare:

- notifiche;
- allarmi precisi, quando richiesto.

I dati dell'MVP sono salvati localmente sul telefono tramite SharedPreferences.
