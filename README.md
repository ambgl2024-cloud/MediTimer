# MediTimer Android MVP — v0.3.0

MediTimer è un'app Android locale/offline per gestire farmaci periodici, promemoria, countdown post-assunzione, cambio confezione e storico delle assunzioni.

## Novità v0.3.0

- Nuova icona MediTimer: capsula + orologio su sfondo azzurro/teal.
- Countdown con un singolo bip secco ogni minuto trascorso.
- Suono finale dedicato e differente allo scadere del countdown.
- Scheda Calendario con storico permanente anche dopo la cancellazione del farmaco.
- Tocco su un evento del Calendario per modificarlo o cancellarlo.
- Nell'editor dello storico sono modificabili:
  - nome del farmaco;
  - data e ora effettiva di assunzione;
  - data e ora originariamente previste.
- Export dello storico in CSV UTF-8 compatibile con Excel.
- Firma stabile release tramite GitHub Actions Secrets.

## Funzioni principali

- Farmaci con nome, dose/nota e stato attivo/sospeso.
- Ricorrenze: ogni giorno, giorni della settimana, ogni N giorni, giorni del mese.
- Più orari di assunzione per ciascun giorno attivo.
- Promemoria Android tramite AlarmManager.
- Scheda **Oggi** con pulsante **Assunto** e possibilità di correggere con **Non assunto**.
- Countdown post-assunzione configurabile per farmaco.
- Gestione cambio confezione.
- Scheda **Calendario** con data e ora effettiva delle assunzioni.
- Lo storico rimane anche se il farmaco viene eliminato dall'anagrafica.

## Storico e modifica eventi

Ogni assunzione salva un ID storico indipendente, nome del farmaco, data/orario programmati e timestamp reale.
Gli eventi precedenti alla v0.3.0 vengono migrati automaticamente usando il timestamp dell'assunzione come ID stabile.

Toccando un evento nel Calendario è possibile modificarlo e salvarlo oppure eliminarlo definitivamente.
La modifica dello storico non modifica l'anagrafica del farmaco.

## Export CSV

Nella scheda **Calendario**, premi `CSV`. Android chiederà dove salvare il file.

Colonne esportate:

- Data
- Ora assunzione
- Farmaco
- Data prevista
- Ora prevista
- Timestamp

## Firma stabile

Il workflow `.github/workflows/build-apk.yml` crea un APK **release firmato stabilmente**.
La chiave privata non deve essere caricata nel repository pubblico.

Leggere `FIRMA_STABILE_GITHUB.txt` e configurare i quattro GitHub Actions repository secrets richiesti prima della build.

Artifact GitHub Actions:

`MediTimer-APK`

APK generato:

`app/build/outputs/apk/release/app-release.apk`

## Permessi Android

Al primo utilizzo autorizzare:

- notifiche;
- allarmi precisi, quando richiesto.

I dati sono salvati localmente sul telefono tramite SharedPreferences.
