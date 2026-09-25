package com.example.meditimer.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import com.example.meditimer.data.*
import com.example.meditimer.notifications.NotificationHelper
import com.example.meditimer.notifications.Scheduler
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private enum class AppTab(val label: String) {
    TODAY("Oggi"), MEDS("Farmaci"), ALARMS("Sveglie"), PACKAGES("Confezioni"), CALENDAR("Calendario"), HISTORY("Storia")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediTimerApp(requestExactAlarmPermission: () -> Unit) {
    val context = LocalContext.current
    val repo = remember {
        MedicationRepository(context).also { it.ensureTherapyHistoryBaselines() }
    }
    var tab by remember { mutableStateOf(AppTab.TODAY) }
    var revision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Medication?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    val guidePreferences = remember {
        context.getSharedPreferences("meditimer_guide", Context.MODE_PRIVATE)
    }

    fun refresh() { revision++ }
    val meds = remember(revision) { repo.getMedications() }

    // Show the guide automatically only on a clean first use.
    // Existing users with configured medications are not interrupted.
    LaunchedEffect(Unit) {
        val guideSeen = guidePreferences.getBoolean("guide_v1_seen", false)
        if (!guideSeen && repo.getMedications().isEmpty()) {
            showGuide = true
        }
    }

    // One app-level clock keeps countdown rendering independent from the selected tab.
    // The countdown itself is persisted in MedicationRepository; this clock only renders
    // the remaining time from the stored endMillis.
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            clockNow = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val activeCountdowns = remember(clockNow, revision) { repo.getActiveCountdowns(clockNow) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MediTimer", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Default.Info, contentDescription = "Versione e changelog")
                    }
                }
            )
        },
        bottomBar = {
            Column {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compactNavigation = maxWidth < 430.dp
                    val navIconSize = if (compactNavigation) 18.dp else 21.dp
                    val navLabelSize = if (compactNavigation) 8.sp else 9.sp

                    NavigationBar(
                        modifier = Modifier.fillMaxWidth(),
                        windowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        AppTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = {
                                    Icon(
                                        when (item) {
                                            AppTab.TODAY -> Icons.Default.Today
                                            AppTab.MEDS -> Icons.Default.Medication
                                            AppTab.ALARMS -> Icons.Default.Alarm
                                            AppTab.PACKAGES -> Icons.Default.Inventory2
                                            AppTab.CALENDAR -> Icons.Default.CalendarMonth
                                            AppTab.HISTORY -> Icons.Default.History
                                        },
                                        contentDescription = item.label,
                                        modifier = Modifier.size(navIconSize)
                                    )
                                },
                                label = {
                                    Text(
                                        text = item.label,
                                        fontSize = navLabelSize,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Clip
                                    )
                                },
                                alwaysShowLabel = true
                            )
                        }
                    }
                }

                // Dedicated background behind Android's navigation buttons/gesture area.
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsBottomHeight(WindowInsets.navigationBars)
                        .background(androidx.compose.ui.graphics.Color(0xFF0F766E))
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                AppTab.TODAY -> TodayScreen(
                    meds = meds,
                    repo = repo,
                    revision = revision,
                    refresh = ::refresh,
                    requestExactAlarmPermission = requestExactAlarmPermission,
                    now = clockNow,
                    countdowns = activeCountdowns
                )
                AppTab.MEDS -> MedicationListScreen(
                    meds = meds,
                    onAdd = { creating = true },
                    onEdit = { editing = it },
                    onDelete = { med ->
                        Scheduler.cancelMedication(context, med)
                        repo.getActiveCountdowns().filter { it.medicationId == med.id }.forEach { Scheduler.cancelCountdown(context, it) }
                        repo.deleteMedication(med.id)
                        refresh()
                    }
                )
                AppTab.ALARMS -> AlarmListScreen(meds, onEdit = { editing = it })
                AppTab.PACKAGES -> PackageScreen(meds, repo, ::refresh)
                AppTab.CALENDAR -> CalendarScreen(repo, meds, revision, ::refresh)
                AppTab.HISTORY -> HistoryScreen(repo, meds, revision, ::refresh)
            }
        }
    }

    if (showInfo) {
        AboutDialog(
            onDismiss = { showInfo = false },
            onOpenGuide = {
                showInfo = false
                showGuide = true
            }
        )
    }

    if (showGuide) {
        GuideDialog(
            onFinish = {
                guidePreferences.edit().putBoolean("guide_v1_seen", true).apply()
                showGuide = false
            }
        )
    }

    if (creating || editing != null) {
        MedicationEditorDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { med ->
                editing?.let { Scheduler.cancelMedication(context, it) }
                repo.upsertMedication(med)
                repo.clearPackageReminderState(med.id)
                med.alarmTimes.forEach { Scheduler.scheduleNextForSlot(context, med, it) }
                Scheduler.scheduleNextPackageReminder(context, med)
                Scheduler.scheduleNextStockReminder(context, med)
                creating = false
                editing = null
                refresh()
            }
        )
    }
}

@Composable
private fun AboutDialog(
    onDismiss: () -> Unit,
    onOpenGuide: () -> Unit
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
            .ifBlank { "sconosciuta" }
    }

    val changelog = remember {
        listOf(
            "0.7.2" to listOf(
                "Corretto il calcolo dei periodi critici nelle statistiche Storia.",
                "Le assunzioni future del giorno, della settimana o del mese in corso non vengono considerate come non effettuate.",
                "Le statistiche considerano un farmaco solo nei periodi in cui risultava realmente attivo e previsto dalla ricorrenza."
            ),
            "0.7.1" to listOf(
                "Aggiunto Reminder scorta configurabile: Nessuno, Punto di riordino o Tempo residuo.",
                "Il reminder genera un avviso persistente in Oggi, una notifica Android iniziale e un promemoria settimanale finché resta attivo.",
                "Per i farmaci esistenti il comportamento viene mantenuto come Punto di riordino con soglia 1.",
                "Riorganizzata Storia con filtro periodo persistente, KPI di regolarità, analisi degli orari medi e periodi più critici.",
                "Timeline e Storia per farmaco restano discorsive e rispettano il periodo selezionato."
            ),
            "0.7.0" to listOf(
                "Aggiunta in Storia → Farmaci la funzione manuale Unisci farmaci.",
                "Permette di unire un vecchio farmaco storico/eliminato con il farmaco attuale corrispondente.",
                "Le vecchie assunzioni e la cronologia vengono trasferite al farmaco attuale e il falso passaggio eliminato → ricreato viene rimosso.",
                "Le impostazioni correnti, le sveglie, le scorte, la confezione e il countdown del farmaco attuale non vengono modificati.",
                "L'unione richiede sempre conferma esplicita."
            ),
            "0.6.9" to listOf(
                "Aggiunta sezione Storia con Panoramica, Timeline discorsiva e riepilogo per farmaco.",
                "La storia registra automaticamente creazione, modifiche di schema, disattivazione, riattivazione ed eliminazione del farmaco.",
                "Aggiunto filtro stato Tutti / Attivi / Sospesi nella schermata Farmaci; l'eliminazione richiede sempre conferma.",
                "Aggiunti filtri comprimibili per periodo e farmaco nel Calendario.",
                "Confezioni mostra solo farmaci attivi; i tre KPI sono uniformati e perfettamente allineati.",
                "Acquisti, scarti e cambi confezione non vengono inclusi nella Timeline terapeutica."
            ),
            "0.6.8" to listOf(
                "Restyling grafico delle schermate Farmaci, Sveglie e Confezioni.",
                "Farmaci più compatti con indicatore grafico Attivo/Sospeso.",
                "Riquadri Sveglie uniformati in larghezza e Confezioni ottimizzata con indicatori più grandi e pulsanti più compatti.",
                "Aggiunte ricerca testuale e ordinamento A–Z / Z–A in Farmaci e Confezioni.",
                "Nessuna modifica alla logica funzionale dell'app."
            ),
            "0.6.7" to listOf(
                "Aggiunta guida interattiva passo-passo all'utilizzo di MediTimer.",
                "La guida viene proposta automaticamente su una nuova installazione senza farmaci configurati.",
                "La guida resta sempre accessibile dalla schermata Info."
            ),
            "0.6.6" to listOf(
                "Gestione Confezioni differenziata per singolo farmaco.",
                "Per i farmaci con durata in assunzioni è possibile impostare manualmente le assunzioni rimaste della confezione in uso.",
                "Imposta scorta e Imposta assunzioni rimaste sono affiancati solo per i farmaci a conteggio.",
                "I farmaci con durata in giorni mantengono il menu precedente.",
                "Il residuo manuale continua a diminuire automaticamente a ogni Assunto e si resetta al nuovo cambio confezione."
            ),
            "0.6.5" to listOf(
                "Riorganizzati i pulsanti nella sezione Confezioni.",
                "Imposta scorta ora occupa una riga intera.",
                "Acquisto e Scarto sono affiancati sulla stessa riga.",
                "Nessuna modifica alla logica di scorte, avvisi o countdown."
            ),
            "0.6.4" to listOf(
                "Aggiunta la durata confezione per numero di assunzioni in alternativa ai giorni.",
                "Per ogni farmaco si può scegliere un solo criterio: giorni oppure assunzioni.",
                "In modalità assunzioni il conteggio usa gli eventi realmente registrati come Assunto e mostra le assunzioni rimaste.",
                "Avvisi automatici da 7 assunzioni residue in giù, oltre agli avvisi in giorni già esistenti."
            ),
            "0.6.3" to listOf(
                "Menu inferiore reso responsive per schermi più stretti.",
                "Etichette Confezioni e Calendario mantenute su una sola riga con dimensionamento adattivo di testo e icone.",
                "Area dei tasti/gesture di sistema colorata in verde petrolio per rendere visibili i controlli bianchi.",
                "Gestione confezioni, scorte, countdown e doppio bip invariati."
            ),
            "0.6.2" to listOf(
                "Ripristinata la modifica manuale della data di apertura dell'ultima confezione.",
                "Cambiata oggi continua a scalare automaticamente una confezione dalla scorta.",
                "Modifica data corregge solo la data e non altera la scorta.",
                "Dopo la correzione vengono ricalcolati i promemoria del prossimo cambio."
            ),
            "0.6.1" to listOf(
                "Semplificata la schermata Confezioni.",
                "Per ogni farmaco vengono mostrati solo ultimo cambio, giorni mancanti e confezioni rimaste.",
                "Mantenuti i comandi Cambiata oggi, Imposta scorta, Acquisto e Scarto.",
                "Logica degli avvisi e gestione automatica della scorta invariati."
            ),
            "0.6.0" to listOf(
                "Gestione scorte per ogni farmaco: acquisti, scarti e correzione quantità.",
                "Ogni cambio confezione consuma automaticamente una confezione dalla scorta.",
                "Avvisi giornalieri da 7 giorni prima del cambio confezione e avvisi giornalieri di scorta a 1 o 0 confezioni (ore 09:00).",
                "Aggiunti avvisi scorte anche nelle schermate Oggi e Confezioni."
            ),
            "0.5.6" to listOf(
                "Ripristinato il doppio bip originale di fine countdown.",
                "Aggiunto un breve pre-roll silenzioso per evitare che il primo bip venga tagliato quando Android riattiva l'audio a schermo spento.",
                "Mantenuti exact alarm, WAKE_LOCK, persistenza countdown e nessun suono intermedio."
            ),
            "0.5.5" to listOf(
                "Ripristinato il bip personalizzato di fine countdown tramite exact alarm e SoundHelper.",
                "Ripristinato WAKE_LOCK senza richieste all'utente e mantenuta la persistenza del countdown.",
                "Rimosso il suono sveglia Android dal canale di fine countdown per evitare il doppio avviso.",
                "Pulizia dei residui del vecchio servizio countdown e dei beep intermedi."
            ),
            "0.5.4" to listOf(
                "Ripristinato il suono finale del countdown con lo stesso NotificationChannel delle versioni iniziali.",
                "Aggiunta schermata Info con versione installata e changelog."
            ),
            "0.5.3" to listOf(
                "Countdown persistente tra pagine, chiusura e riapertura dell'app.",
                "Salvataggio sincrono dello stato e ripristino degli exact alarm."
            ),
            "0.5.2" to listOf(
                "Revisione del sistema audio di fine countdown."
            ),
            "0.5.1" to listOf(
                "Snooze configurabile per ogni farmaco e ripristinabile dopo riavvio."
            ),
            "0.5.0" to listOf(
                "Countdown predefinito a 2 minuti.",
                "Rimossi i bip intermedi e la richiesta di esclusione dal risparmio energetico.",
                "Orari multipli equidistanti e selettore HH:mm."
            ),
            "0.4.0" to listOf(
                "Import/export CSV dello storico e modifica degli eventi del calendario."
            ),
            "0.3.0" to listOf(
                "Calendario storico delle assunzioni e modifica degli eventi."
            ),
            "0.2.0" to listOf(
                "Gestione Non assunto, countdown e storico assunzioni."
            ),
            "0.1.0" to listOf(
                "Prima versione MVP di MediTimer."
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Info MediTimer") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Versione installata: $versionName", fontWeight = FontWeight.Bold)
                Divider()
                Text("Changelog", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                changelog.forEach { (version, changes) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("v$version", fontWeight = FontWeight.Bold)
                        changes.forEach { change -> Text("• $change", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
        dismissButton = {
            TextButton(onClick = onOpenGuide) {
                Icon(Icons.Default.HelpOutline, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Guida")
            }
        }
    )
}


private data class GuideStep(
    val title: String,
    val intro: String,
    val points: List<String>,
    val note: String? = null
)

@Composable
private fun GuideDialog(onFinish: () -> Unit) {
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }

    val steps = remember {
        listOf(
            GuideStep(
                title = "Benvenuto in MediTimer",
                intro = "MediTimer organizza assunzioni, promemoria, countdown post-assunzione e gestione delle confezioni.",
                points = listOf(
                    "Oggi: mostra le assunzioni previste, gli avvisi e gli eventuali countdown attivi.",
                    "Farmaci: crea e modifica l'anagrafica dei medicinali.",
                    "Sveglie: controlla gli orari programmati per ogni farmaco.",
                    "Confezioni: gestisce durata della confezione, scorta e cambio scatola.",
                    "Calendario: conserva lo storico delle assunzioni ed esporta/importa il CSV."
                ),
                note = "Per promemoria puntuali consenti le notifiche e, quando richiesto da Android, gli allarmi precisi."
            ),
            GuideStep(
                title = "1. Crea un farmaco",
                intro = "Apri Farmaci e premi +. Compila l'anagrafica una volta; potrai modificarla in qualsiasi momento.",
                points = listOf(
                    "Nome farmaco: nome con cui verrà mostrato nell'app e nelle notifiche.",
                    "Dose / nota: campo libero, ad esempio “1 compressa” o altre indicazioni utili.",
                    "Attivo: disattivalo se vuoi conservare il farmaco senza ricevere promemoria.",
                    "Assunzioni nei giorni attivi: scegli quante volte deve essere assunto nella giornata.",
                    "Gli orari vengono proposti equidistanti; tocca ogni orario per modificarlo in formato HH:mm."
                )
            ),
            GuideStep(
                title = "2. Ricorrenza e snooze",
                intro = "Definisci quando il farmaco deve comparire nel piano e dopo quanti minuti deve ricomparire un avviso posticipato.",
                points = listOf(
                    "Ogni giorno: il farmaco è previsto tutti i giorni.",
                    "Giorni della settimana: scegli i singoli giorni.",
                    "Ogni N giorni: indica l'intervallo e la data di partenza.",
                    "Giorni del mese: inserisci i giorni desiderati, ad esempio 1, 10, 20.",
                    "Snooze tra gli avvisi: è il numero di minuti usato dal pulsante Rimanda nella notifica."
                )
            ),
            GuideStep(
                title = "3. Durata della confezione",
                intro = "Per ogni farmaco scegli un solo criterio per stabilire quando la confezione in uso deve essere cambiata.",
                points = listOf(
                    "Giorni: indica la durata massima dopo l'apertura, ad esempio 28 giorni.",
                    "Assunzioni: indica il numero massimo di assunzioni della confezione.",
                    "In modalità Assunzioni il contatore diminuisce di 1 ogni volta che registri Assunto.",
                    "Se assumi più compresse nello stesso momento, inserisci il numero di assunzioni previste dalla confezione, non necessariamente il numero fisico di compresse."
                )
            ),
            GuideStep(
                title = "4. Sveglie, notifiche e Rimanda",
                intro = "Salvando il farmaco, gli orari vengono programmati automaticamente e sono visibili nella sezione Sveglie.",
                points = listOf(
                    "All'orario previsto ricevi la notifica Farmaco da assumere.",
                    "Farmaco assunto: registra subito l'assunzione nello storico.",
                    "Rimanda X min: chiude l'avviso e lo ripropone dopo lo snooze impostato per quel farmaco.",
                    "Puoi usare Rimanda più volte finché non registri l'assunzione.",
                    "La sezione Sveglie mostra gli orari e la ricorrenza attualmente associati a ciascun farmaco."
                )
            ),
            GuideStep(
                title = "5. Oggi, Assunto e countdown",
                intro = "La sezione Oggi è il punto principale per controllare cosa devi assumere e cosa hai già registrato.",
                points = listOf(
                    "Premi Assunto per registrare l'assunzione. L'evento viene salvato nel Calendario.",
                    "Se hai premuto Assunto per errore, usa Non assunto per annullare quella registrazione.",
                    "Se il farmaco ha un countdown post-assunzione, il countdown parte automaticamente quando registri Assunto.",
                    "Il countdown continua cambiando pagina, uscendo dall'app o spegnendo lo schermo.",
                    "Alla fine viene emesso il doppio bip di fine countdown."
                )
            ),
            GuideStep(
                title = "6. Scorte e cambio confezione",
                intro = "Nella sezione Confezioni la scorta indica solo le scatole chiuse disponibili; la confezione in uso non è conteggiata.",
                points = listOf(
                    "Cambiata oggi: registra l'apertura della nuova confezione e riduce la scorta di 1.",
                    "Modifica data: corregge la data di apertura dell'ultima confezione senza modificare la scorta.",
                    "Imposta scorta: imposta direttamente il numero reale di confezioni chiuse disponibili.",
                    "Acquisto: aggiunge alla scorta il numero di confezioni acquistate.",
                    "Scarto: sottrae confezioni eliminate, ad esempio perché scadute o inutilizzabili."
                )
            ),
            GuideStep(
                title = "7. Avvisi confezione: giorni o assunzioni",
                intro = "La schermata Confezioni cambia automaticamente in base al criterio scelto nell'anagrafica del singolo farmaco.",
                points = listOf(
                    "Farmaco a giorni: mostra ultimo cambio, giorni mancanti e confezioni rimaste.",
                    "Da 7 giorni in giù MediTimer segnala che il cambio confezione si sta avvicinando.",
                    "Farmaco ad assunzioni: mostra le assunzioni rimaste della confezione corrente.",
                    "Solo per i farmaci ad assunzioni compare Imposta assunzioni rimaste, utile per correggere manualmente il residuo.",
                    "Quando resta 1 confezione in scorta viene segnalato di pianificare un nuovo acquisto; a 0 la scorta risulta esaurita."
                )
            ),
            GuideStep(
                title = "8. Calendario e backup CSV",
                intro = "Il Calendario conserva lo storico delle assunzioni, anche se successivamente elimini un farmaco dall'anagrafica.",
                points = listOf(
                    "Tocca un evento per modificarne data, ora o farmaco, oppure per eliminarlo.",
                    "Esporta CSV crea un backup dello storico.",
                    "Importa CSV permette di ripristinare uno storico esportato in precedenza.",
                    "L'import dello storico non crea automaticamente farmaci, sveglie o ricorrenze."
                ),
                note = "Puoi riaprire questa guida in qualsiasi momento da Info → Guida."
            )
        )
    }

    val step = steps[stepIndex]
    val progress = (stepIndex + 1).toFloat() / steps.size.toFloat()

    AlertDialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Guida MediTimer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Passo ${stepIndex + 1} di ${steps.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(step.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(step.intro)

                step.points.forEach { point ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("•", fontWeight = FontWeight.Bold)
                        Text(point, modifier = Modifier.weight(1f))
                    }
                }

                step.note?.let { note ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(Icons.Default.Lightbulb, contentDescription = null)
                            Text(note, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (stepIndex < steps.lastIndex) {
                        stepIndex++
                    } else {
                        onFinish()
                    }
                }
            ) {
                Text(if (stepIndex < steps.lastIndex) "Avanti" else "Fine")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (stepIndex > 0) {
                    TextButton(onClick = { stepIndex-- }) {
                        Text("Indietro")
                    }
                }
                TextButton(onClick = onFinish) {
                    Text("Salta")
                }
            }
        }
    )
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun TodayScreen(
    meds: List<Medication>,
    repo: MedicationRepository,
    revision: Int,
    refresh: () -> Unit,
    requestExactAlarmPermission: () -> Unit,
    now: Long,
    countdowns: List<ActiveCountdown>
) {
    val context = LocalContext.current
    val today = remember(now) { Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate() }
    val doses = remember(meds, today, revision) {
        meds.filter { it.isActiveOn(today) }
            .flatMap { med -> med.alarmTimes.map { time -> med to time } }
            .sortedBy { it.second }
    }

    ScreenColumn {
        Text("Oggi", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(today.itDate(), style = MaterialTheme.typography.bodyLarge)

        if (!Scheduler.canScheduleExact(context)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Allarmi precisi non abilitati", fontWeight = FontWeight.Bold)
                    Text("Android può ritardare i promemoria. Abilita gli allarmi precisi per avere orari affidabili.")
                    Button(onClick = requestExactAlarmPermission) { Text("Abilita") }
                }
            }
        }

        val packageAttention = meds.mapNotNull { med ->
            val dayRemaining = if (med.packageDurationMode == PackageDurationMode.DAYS) {
                med.lastPackageChangeEpochDay?.let(LocalDate::ofEpochDay)
                    ?.plusDays(med.packageMaxDays.toLong())
                    ?.toEpochDay()?.minus(today.toEpochDay())
            } else null
            val intakeRemaining = if (med.packageDurationMode == PackageDurationMode.INTAKES) repo.getPackageIntakesRemaining(med) else null
            val messages = buildList {
                if (dayRemaining != null && dayRemaining <= 7L) {
                    add(when {
                        dayRemaining > 1 -> "${med.name}: cambio confezione tra $dayRemaining giorni"
                        dayRemaining == 1L -> "${med.name}: cambio confezione domani"
                        dayRemaining == 0L -> "${med.name}: cambio confezione oggi"
                        dayRemaining == -1L -> "${med.name}: cambio confezione scaduto da 1 giorno"
                        else -> "${med.name}: cambio confezione scaduto da ${-dayRemaining} giorni"
                    })
                }
                if (intakeRemaining != null && intakeRemaining <= 7) {
                    add(when {
                        intakeRemaining > 1 -> "${med.name}: restano $intakeRemaining assunzioni prima del cambio"
                        intakeRemaining == 1 -> "${med.name}: resta 1 assunzione prima del cambio"
                        intakeRemaining == 0 -> "${med.name}: numero massimo di assunzioni raggiunto — cambia confezione"
                        intakeRemaining == -1 -> "${med.name}: durata superata di 1 assunzione"
                        else -> "${med.name}: durata superata di ${-intakeRemaining} assunzioni"
                    })
                }
                val stockReminder = evaluateStockReminder(med, repo, today)
                if (stockReminder.active) add(stockReminder.message)
            }
            messages.takeIf { it.isNotEmpty() }
        }.flatten()

        if (packageAttention.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Text("Attenzione confezioni e scorte", fontWeight = FontWeight.Bold)
                    }
                    packageAttention.forEach { Text("• $it") }
                }
            }
        }

        if (countdowns.isNotEmpty()) {
            Text("Countdown attivi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            countdowns.forEach { c ->
                val remaining = max(0L, c.endMillis - now)
                val min = remaining / 60_000
                val sec = (remaining % 60_000) / 1_000
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(c.medicationName, fontWeight = FontWeight.Bold)
                        Text(String.format("%02d:%02d", min, sec), style = MaterialTheme.typography.headlineMedium)
                        if (c.note.isNotBlank()) Text(c.note)
                        Text("Fine alle ${millisToTime(c.endMillis)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Text("Assunzioni", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (doses.isEmpty()) Text("Nessuna assunzione programmata per oggi.")
        doses.forEach { (med, time) ->
            val taken = repo.isTaken(med.id, today.toEpochDay(), time)
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("$time · ${med.name}", fontWeight = FontWeight.Bold)
                        if (med.doseNote.isNotBlank()) Text(med.doseNote)
                        if (med.countdownEnabled && med.countdownMinutes > 0)
                            Text("Dopo: countdown ${med.countdownMinutes}min", style = MaterialTheme.typography.bodySmall)
                    }
                    if (taken) {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(
                                onClick = {},
                                label = { Text("Assunto") },
                                leadingIcon = { Icon(Icons.Default.Check, null) }
                            )
                            OutlinedButton(onClick = {
                                markNotTaken(context, repo, med, today.toEpochDay(), time)
                                refresh()
                            }) { Text("Non assunto") }
                        }
                    } else {
                        Button(onClick = {
                            markTaken(context, repo, med, today.toEpochDay(), time)
                            refresh()
                        }) { Text("Assunto") }
                    }
                }
            }
        }
    }
}

private fun markTaken(context: Context, repo: MedicationRepository, med: Medication, epochDay: Long, time: String) {
    val now = System.currentTimeMillis()
    repo.recordIntake(
        IntakeEvent(
            medicationId = med.id,
            medicationName = med.name,
            plannedEpochDay = epochDay,
            plannedTime = time,
            takenAtMillis = now
        )
    )
    Scheduler.scheduleNextPackageReminder(context, med)
    Scheduler.scheduleNextStockReminder(context, repo.getMedication(med.id) ?: med)
    Scheduler.cancelSnooze(context, med.id, epochDay, time)
    NotificationManagerCompat.from(context).cancel(NotificationHelper.notificationId(med.id, time))
    if (med.countdownEnabled && med.countdownMinutes > 0) {
        val c = ActiveCountdown(
            id = now + med.id,
            medicationId = med.id,
            medicationName = med.name,
            note = med.countdownNote,
            startMillis = now,
            endMillis = now + med.countdownMinutes * 60_000L,
            plannedEpochDay = epochDay,
            plannedTime = time
        )
        repo.addCountdown(c)
        Scheduler.scheduleCountdown(context, c)
    }
}

private fun markNotTaken(context: Context, repo: MedicationRepository, med: Medication, epochDay: Long, time: String) {
    repo.getCountdownsForIntake(med.id, epochDay, time).forEach { countdown ->
        Scheduler.cancelCountdown(context, countdown)
        repo.removeCountdown(countdown.id)
    }
    repo.removeIntake(med.id, epochDay, time)
    Scheduler.scheduleNextPackageReminder(context, med)
    Scheduler.scheduleNextStockReminder(context, repo.getMedication(med.id) ?: med)
}

@Composable
private fun MedicationSearchSortBar(
    query: String,
    onQueryChange: (String) -> Unit,
    ascending: Boolean,
    onToggleSort: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Cerca farmaco…") },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp)
                )
            },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancella ricerca")
                    }
                }
            } else null,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(
            onClick = onToggleSort,
            modifier = Modifier.height(56.dp),
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            Icon(Icons.Default.SortByAlpha, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
            Text(if (ascending) "A–Z" else "Z–A", fontSize = 12.sp, maxLines = 1)
        }
    }
}

private enum class MedicationStatusFilter(val label: String) {
    ALL("Tutti"),
    ACTIVE("Attivi"),
    SUSPENDED("Sospesi")
}

@Composable
private fun MedicationListScreen(
    meds: List<Medication>,
    onAdd: () -> Unit,
    onEdit: (Medication) -> Unit,
    onDelete: (Medication) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sortAscending by rememberSaveable { mutableStateOf(true) }
    var statusFilterName by rememberSaveable { mutableStateOf(MedicationStatusFilter.ALL.name) }
    var pendingDelete by remember { mutableStateOf<Medication?>(null) }

    val statusFilter = runCatching { MedicationStatusFilter.valueOf(statusFilterName) }
        .getOrDefault(MedicationStatusFilter.ALL)

    val visibleMeds = remember(meds, query, sortAscending, statusFilterName) {
        val filtered = meds.filter { med ->
            val statusMatches = when (statusFilter) {
                MedicationStatusFilter.ALL -> true
                MedicationStatusFilter.ACTIVE -> med.enabled
                MedicationStatusFilter.SUSPENDED -> !med.enabled
            }
            statusMatches && (
                query.isBlank() ||
                    med.name.contains(query, ignoreCase = true) ||
                    med.doseNote.contains(query, ignoreCase = true)
                )
        }.sortedBy { it.name.lowercase() }
        if (sortAscending) filtered else filtered.reversed()
    }

    ScreenColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Farmaci",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            FilledIconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Aggiungi") }
        }

        if (meds.isNotEmpty()) {
            MedicationSearchSortBar(
                query = query,
                onQueryChange = { query = it },
                ascending = sortAscending,
                onToggleSort = { sortAscending = !sortAscending }
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MedicationStatusFilter.entries.forEach { item ->
                    FilterChip(
                        selected = statusFilter == item,
                        onClick = { statusFilterName = item.name },
                        label = {
                            Text(
                                item.label,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (meds.isEmpty()) {
            Text("Aggiungi il primo farmaco per creare il piano di assunzione.")
        } else if (visibleMeds.isEmpty()) {
            Text("Nessun farmaco corrisponde ai filtri impostati.")
        }

        visibleMeds.forEach { med ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (med.enabled)
                        MaterialTheme.colorScheme.surfaceVariant
                    else
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                )
            ) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            med.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = if (med.enabled)
                                androidx.compose.ui.graphics.Color(0xFFE3F4E8)
                            else
                                MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                if (med.enabled) "● Attivo" else "○ Sospeso",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (med.enabled)
                                    androidx.compose.ui.graphics.Color(0xFF247A43)
                                else
                                    MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.width(3.dp))
                        IconButton(
                            onClick = { onEdit(med) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Edit, "Modifica", modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { pendingDelete = med },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Delete, "Elimina", modifier = Modifier.size(18.dp))
                        }
                    }

                    if (med.doseNote.isNotBlank()) {
                        Text(
                            med.doseNote,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(15.dp))
                        Text(
                            "${med.timesPerActiveDay}×/giorno · ${med.alarmTimes.joinToString(" · ")}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(15.dp))
                        Text(
                            med.recurrenceLabel(),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(15.dp))
                            Text(
                                if (med.packageDurationMode == PackageDurationMode.DAYS)
                                    "${med.packageMaxDays} gg"
                                else
                                    "${med.packageMaxIntakes} ass.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Inventory, contentDescription = null, modifier = Modifier.size(15.dp))
                            Text(
                                med.stockCount?.let { "$it in scorta" } ?: "scorta —",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Snooze, contentDescription = null, modifier = Modifier.size(15.dp))
                            Text("${med.snoozeMinutes} min", style = MaterialTheme.typography.bodySmall)
                        }
                        if (med.countdownEnabled) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(15.dp))
                                Text("${med.countdownMinutes} min", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { med ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Eliminare ${med.name}?") },
            text = {
                Text(
                    "Il farmaco verrà rimosso dall'anagrafica e le sue sveglie verranno annullate. " +
                        "Lo storico delle assunzioni e della terapia resterà disponibile in Storia."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        onDelete(med)
                    }
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Elimina")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Annulla") }
            }
        )
    }
}

@Composable
private fun AlarmListScreen(meds: List<Medication>, onEdit: (Medication) -> Unit) {
    ScreenColumn {
        Text("Sveglie", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Gli orari sono collegati alla regola di ricorrenza del farmaco.",
            style = MaterialTheme.typography.bodyMedium
        )
        meds.filter { it.enabled }.forEach { med ->
            Card(
                onClick = { onEdit(med) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(med.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(med.recurrenceLabel(), style = MaterialTheme.typography.bodySmall)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(17.dp))
                        Text(
                            med.alarmTimes.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
        if (meds.none { it.enabled }) Text("Nessuna sveglia attiva.")
    }
}

private enum class HistorySection(val label: String) {
    OVERVIEW("Panoramica"), TIMELINE("Timeline"), MEDICATIONS("Farmaci")
}

private enum class HistoryRangePreset(val label: String) {
    CURRENT_MONTH("Mese corrente"),
    PREVIOUS_MONTH("Mese precedente"),
    CURRENT_YEAR("Anno corrente"),
    CURRENT_WEEK("Settimana corrente"),
    PREVIOUS_WEEK("Settimana precedente"),
    ALL_TIME("Da sempre"),
    CUSTOM("Dal–Al")
}

private data class HistoryDateRange(
    val start: LocalDate?,
    val endInclusive: LocalDate,
    val label: String
) {
    fun startMillis(fallback: Long): Long = start?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: fallback
    fun endExclusiveMillis(): Long = endInclusive.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

private fun historyDateRange(
    preset: HistoryRangePreset,
    customStart: LocalDate,
    customEnd: LocalDate,
    today: LocalDate = LocalDate.now()
): HistoryDateRange = when (preset) {
    HistoryRangePreset.CURRENT_MONTH -> HistoryDateRange(today.withDayOfMonth(1), today, "Mese corrente")
    HistoryRangePreset.PREVIOUS_MONTH -> {
        val month = YearMonth.from(today).minusMonths(1)
        HistoryDateRange(month.atDay(1), month.atEndOfMonth(), "Mese precedente")
    }
    HistoryRangePreset.CURRENT_YEAR -> HistoryDateRange(LocalDate.of(today.year, 1, 1), today, "Anno corrente")
    HistoryRangePreset.CURRENT_WEEK -> {
        val start = today.minusDays((today.dayOfWeek.value - 1).toLong())
        HistoryDateRange(start, today, "Settimana corrente")
    }
    HistoryRangePreset.PREVIOUS_WEEK -> {
        val currentStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val start = currentStart.minusWeeks(1)
        HistoryDateRange(start, start.plusDays(6), "Settimana precedente")
    }
    HistoryRangePreset.ALL_TIME -> HistoryDateRange(null, today, "Da sempre")
    HistoryRangePreset.CUSTOM -> {
        val start = minOf(customStart, customEnd)
        val end = minOf(maxOf(customStart, customEnd), today)
        HistoryDateRange(start, end, "${start.itDate()} – ${end.itDate()}")
    }
}

@Composable
private fun HistoryFilterBar(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    preset: HistoryRangePreset,
    onPresetChange: (HistoryRangePreset) -> Unit,
    customStart: LocalDate,
    customEnd: LocalDate,
    onCustomStartChange: (LocalDate) -> Unit,
    onCustomEndChange: (LocalDate) -> Unit,
    range: HistoryDateRange
) {
    val context = LocalContext.current
    var presetMenu by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FilterAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text("Filtri periodo", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text(range.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onExpandedChange(!expanded) }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
            }
            if (expanded) {
                Divider()
                Spacer(Modifier.height(6.dp))
                Box {
                    OutlinedButton(onClick = { presetMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(preset.label, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                        HistoryRangePreset.entries.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.label) },
                                onClick = { onPresetChange(item); presetMenu = false }
                            )
                        }
                    }
                }
                if (preset == HistoryRangePreset.CUSTOM) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showDatePicker(context, customStart, onCustomStartChange) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Dal ${customStart.itDate()}", fontSize = 11.sp, maxLines = 1) }
                        OutlinedButton(
                            onClick = { showDatePicker(context, customEnd, onCustomEndChange) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Al ${customEnd.itDate()}", fontSize = 11.sp, maxLines = 1) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    repo: MedicationRepository,
    meds: List<Medication>,
    revision: Int,
    refresh: () -> Unit
) {
    var section by rememberSaveable { mutableStateOf(HistorySection.OVERVIEW) }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    var presetName by rememberSaveable { mutableStateOf(HistoryRangePreset.CURRENT_MONTH.name) }
    var customStartEpoch by rememberSaveable { mutableLongStateOf(LocalDate.now().minusDays(30).toEpochDay()) }
    var customEndEpoch by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }

    val preset = runCatching { HistoryRangePreset.valueOf(presetName) }.getOrDefault(HistoryRangePreset.CURRENT_MONTH)
    val customStart = LocalDate.ofEpochDay(customStartEpoch)
    val customEnd = LocalDate.ofEpochDay(customEndEpoch)
    val range = historyDateRange(preset, customStart, customEnd)

    val history = remember(revision, meds) { repo.getTherapyHistory() }
    val intakes = remember(revision, meds) { repo.getIntakes() }
    val periods = remember(history) { buildTherapyPeriods(history) }
    val earliestEvidence = remember(periods, intakes) {
        listOfNotNull(periods.minOfOrNull { it.startMillis }, intakes.minOfOrNull { it.takenAtMillis }).minOrNull()
            ?: System.currentTimeMillis()
    }
    val startMillis = range.startMillis(earliestEvidence)
    val endMillis = range.endExclusiveMillis()

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Storia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HistorySection.entries.forEach { item ->
                FilterChip(
                    selected = section == item,
                    onClick = { section = item },
                    label = { Text(item.label, fontSize = 12.sp, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        HistoryFilterBar(
            expanded = filtersExpanded,
            onExpandedChange = { filtersExpanded = it },
            preset = preset,
            onPresetChange = { presetName = it.name },
            customStart = customStart,
            customEnd = customEnd,
            onCustomStartChange = { customStartEpoch = it.toEpochDay() },
            onCustomEndChange = { customEndEpoch = it.toEpochDay() },
            range = range
        )

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (section) {
                HistorySection.OVERVIEW -> HistoryKpiFilteredContent(
                    meds, periods, intakes, startMillis, endMillis, range.label, earliestEvidence
                )
                HistorySection.TIMELINE -> HistoryTimelineFilteredContent(
                    periods, intakes, startMillis, endMillis
                )
                HistorySection.MEDICATIONS -> HistoryMedicationsFilteredContent(
                    meds, history, periods, intakes, repo, refresh, startMillis, endMillis
                )
            }

            if (history.any { it.legacyBaseline }) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            "Per i periodi precedenti al tracciamento della Storia, MediTimer usa solo i dati disponibili e non inventa modifiche di schema non registrate.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HistoryKpiFilteredContent(
    meds: List<Medication>,
    periods: List<TherapyPeriod>,
    intakes: List<IntakeEvent>,
    rangeStartMillis: Long,
    rangeEndMillis: Long,
    rangeLabel: String,
    earliestEvidenceMillis: Long
) {
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    val selectedStats = remember(periods, intakes, rangeStartMillis, rangeEndMillis) {
        adherenceForPeriods(periods, intakes, rangeStartMillis, rangeEndMillis, now)
    }
    val monthStart = today.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val yearStart = LocalDate.of(today.year, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val monthStats = remember(periods, intakes, today) { adherenceForPeriods(periods, intakes, monthStart, now + 1, now) }
    val yearStats = remember(periods, intakes, today) { adherenceForPeriods(periods, intakes, yearStart, now + 1, now) }
    val foreverStats = remember(periods, intakes, earliestEvidenceMillis) { adherenceForPeriods(periods, intakes, earliestEvidenceMillis, now + 1, now) }

    val rangeEndDate = Instant.ofEpochMilli(minOf(rangeEndMillis - 1, now)).atZone(ZoneId.systemDefault()).toLocalDate()
    val last14Start = maxOf(
        Instant.ofEpochMilli(rangeStartMillis).atZone(ZoneId.systemDefault()).toLocalDate(),
        rangeEndDate.minusDays(13)
    )
    val last14 = remember(periods, intakes, last14Start, rangeEndDate) {
        dailyAdherence(periods, intakes, last14Start, (rangeEndDate.toEpochDay() - last14Start.toEpochDay() + 1).toInt().coerceAtLeast(1), now)
    }
    val observations = remember(periods, intakes, rangeStartMillis, rangeEndMillis) {
        doseObservations(periods, intakes, rangeStartMillis, rangeEndMillis, now)
    }
    val timing = remember(observations) { timingSlotStats(observations) }
    val critical = remember(observations, now) { criticalPeriods(observations, now) }

    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Regolarità assunzioni · $rangeLabel", fontWeight = FontWeight.Bold)
            Text(
                selectedStats.percentage?.let { "$it%" } ?: "—",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                if (selectedStats.expected > 0) "${selectedStats.taken} registrate su ${selectedStats.expected} previste"
                else "Nessuna assunzione prevista nel periodo",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HistoryMiniMetric("Mese", monthStats.percentage?.let { "$it%" } ?: "—", Modifier.weight(1f))
        HistoryMiniMetric("Anno", yearStats.percentage?.let { "$it%" } ?: "—", Modifier.weight(1f))
        HistoryMiniMetric("Da sempre", foreverStats.percentage?.let { "$it%" } ?: "—", Modifier.weight(1f))
    }

    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Andamento ultimi giorni del periodo", fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().height(90.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                last14.forEach { (date, stats) ->
                    val fraction = stats.percentage?.div(100f)?.coerceIn(0.08f, 1f) ?: 0.08f
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Box(
                            Modifier.width(11.dp).fillMaxHeight(fraction).background(
                                if (stats.expected > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.shapes.small
                            )
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(date.dayOfMonth.toString(), fontSize = 8.sp)
                    }
                }
            }
        }
    }

    Text("Orari medi di assunzione", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    if (timing.none { it.taken > 0 }) {
        Text("Non ci sono abbastanza assunzioni registrate nel periodo per calcolare gli orari medi.")
    } else {
        timing.filter { it.taken > 0 }.groupBy { it.medicationName }.forEach { (name, slots) ->
            Card {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(name, fontWeight = FontWeight.Bold)
                    slots.forEach { slot ->
                        val deviation = slot.meanDeviationMinutes ?: 0
                        val deviationText = when {
                            deviation > 0 -> "+$deviation min"
                            deviation < 0 -> "$deviation min"
                            else -> "0 min"
                        }
                        Text(
                            "Previsto ${slot.plannedTime} → media ${slot.averageTakenTime ?: "—"} · scostamento $deviationText · variabilità ±${slot.dispersionMinutes ?: 0} min",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    Text("Periodi più critici", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Card {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ProblemPeriodLine("Giorno della settimana", critical.weekday)
            ProblemPeriodLine("Settimana", critical.week)
            ProblemPeriodLine("Mese", critical.month)
            Text(
                "Il periodo peggiore è determinato prima dalla percentuale di assunzioni non registrate; a valori simili pesa maggiormente lo scostamento medio dall'orario previsto.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryMiniMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ProblemPeriodLine(label: String, item: ProblemGroupStats?) {
    if (item == null || item.expected == 0) {
        Text("$label: —", style = MaterialTheme.typography.bodySmall)
    } else {
        val missed = item.expected - item.taken
        Text(
            "$label: ${item.label} · $missed/${item.expected} non registrate · scostamento medio ${item.meanAbsoluteDeviationMinutes.toInt()} min",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun HistoryTimelineFilteredContent(
    periods: List<TherapyPeriod>,
    intakes: List<IntakeEvent>,
    rangeStartMillis: Long,
    rangeEndMillis: Long
) {
    val now = System.currentTimeMillis()
    val visible = periods.filter { period ->
        val end = period.endMillisExclusive ?: Long.MAX_VALUE
        end > rangeStartMillis && period.startMillis < rangeEndMillis
    }.sortedBy { it.startMillis }

    Text(
        "Questa parte racconta in ordine temporale quali farmaci erano previsti, con quale schema e quanto regolarmente sono stati registrati. Non include acquisti, scarti o cambi confezione.",
        style = MaterialTheme.typography.bodyMedium
    )

    if (visible.isEmpty()) Text("Nessuna terapia registrata nel periodo selezionato.")

    visible.forEach { period ->
        val start = maxOf(period.startMillis, rangeStartMillis)
        val end = minOf(period.endMillisExclusive ?: rangeEndMillis, rangeEndMillis, now + 1)
        if (end > start) {
            val stats = adherenceForPeriods(listOf(period), intakes, start, end, now, period.medicationId)
            val observations = doseObservations(listOf(period), intakes, start, end, now, period.medicationId)
            val timing = timingSlotStats(observations).filter { it.taken > 0 }
            val startDate = millisDate(start)
            val endDate = millisDate((end - 1).coerceAtLeast(start))

            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$startDate – $endDate", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(period.medicationName, fontWeight = FontWeight.Bold)
                    if (!period.snapshot.enabled) {
                        Text("In questo periodo il farmaco risultava sospeso: non erano previste assunzioni.")
                    } else {
                        val adherenceText = if (stats.expected > 0) {
                            "Hai registrato ${stats.taken} delle ${stats.expected} assunzioni previste (${stats.percentage ?: 0}%)."
                        } else "Non ci sono abbastanza dati per calcolare la regolarità delle assunzioni."
                        Text("Lo schema previsto era ${period.snapshot.scheduleDescription()}. $adherenceText")
                        if (timing.isNotEmpty()) {
                            val timeText = timing.joinToString(" ") { slot ->
                                val dev = slot.meanDeviationMinutes ?: 0
                                val direction = when { dev > 0 -> "$dev minuti dopo"; dev < 0 -> "${-dev} minuti prima"; else -> "in linea con" }
                                "Per l'orario ${slot.plannedTime}, l'assunzione media è stata alle ${slot.averageTakenTime ?: "—"}, $direction l'orario impostato (variabilità ±${slot.dispersionMinutes ?: 0} min)."
                            }
                            Text(timeText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryMedicationsFilteredContent(
    meds: List<Medication>,
    history: List<TherapyHistoryEntry>,
    periods: List<TherapyPeriod>,
    intakes: List<IntakeEvent>,
    repo: MedicationRepository,
    onMerged: () -> Unit,
    rangeStartMillis: Long,
    rangeEndMillis: Long
) {
    var query by rememberSaveable { mutableStateOf("") }
    var ascending by rememberSaveable { mutableStateOf(true) }
    var mergeSource by remember { mutableStateOf<MedicationHistorySummary?>(null) }
    val now = System.currentTimeMillis()
    val currentById = remember(meds) { meds.associateBy { it.id } }
    val historyById = remember(history) { history.groupBy { it.medicationId } }
    val intakesById = remember(intakes) { intakes.groupBy { it.medicationId } }
    val ids = remember(meds, history, intakes) { (meds.map { it.id } + history.map { it.medicationId } + intakes.map { it.medicationId }).distinct() }

    MedicationSearchSortBar(query, { query = it }, ascending, { ascending = !ascending })

    val visibleIds = ids.filter { id ->
        val periodOverlap = periods.any { it.medicationId == id && (it.endMillisExclusive ?: Long.MAX_VALUE) > rangeStartMillis && it.startMillis < rangeEndMillis }
        val intakeOverlap = intakesById[id].orEmpty().any { it.takenAtMillis in rangeStartMillis until rangeEndMillis }
        periodOverlap || intakeOverlap
    }.mapNotNull { id ->
        val name = currentById[id]?.name
            ?: historyById[id].orEmpty().lastOrNull()?.snapshot?.name
            ?: intakesById[id].orEmpty().lastOrNull()?.medicationName
        name?.let { id to it }
    }.filter { query.isBlank() || it.second.contains(query, ignoreCase = true) }
        .sortedBy { it.second.lowercase() }
        .let { if (ascending) it else it.reversed() }

    if (visibleIds.isEmpty()) Text("Nessun farmaco presente nel periodo selezionato.")

    visibleIds.forEach { (id, name) ->
        val medPeriods = periods.filter {
            it.medicationId == id && (it.endMillisExclusive ?: Long.MAX_VALUE) > rangeStartMillis && it.startMillis < rangeEndMillis
        }.sortedBy { it.startMillis }
        val stats = adherenceForPeriods(periods, intakes, rangeStartMillis, rangeEndMillis, now, id)
        val current = currentById[id]
        val entries = historyById[id].orEmpty().sortedBy { it.timestampMillis }
        val startEvidence = minOf(
            medPeriods.minOfOrNull { maxOf(it.startMillis, rangeStartMillis) } ?: Long.MAX_VALUE,
            intakesById[id].orEmpty().filter { it.takenAtMillis in rangeStartMillis until rangeEndMillis }.minOfOrNull { it.takenAtMillis } ?: Long.MAX_VALUE
        ).takeIf { it != Long.MAX_VALUE } ?: rangeStartMillis
        val endEvidence = medPeriods.maxOfOrNull { minOf(it.endMillisExclusive ?: rangeEndMillis, rangeEndMillis) }?.minus(1)
        val deleted = entries.lastOrNull { it.type == TherapyHistoryType.DELETED }
        val status = when { current == null && deleted != null -> "Eliminato"; current == null -> "Storico"; current.enabled -> "Attivo"; else -> "Sospeso" }
        val summary = MedicationHistorySummary(id, name, startEvidence, endEvidence, status, current ?: medPeriods.lastOrNull()?.snapshot, stats, intakesById[id].orEmpty().count { it.takenAtMillis in rangeStartMillis until rangeEndMillis })

        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                val narrativeParts = mutableListOf<String>()
                medPeriods.forEach { period ->
                    val start = maxOf(period.startMillis, rangeStartMillis)
                    val end = minOf(period.endMillisExclusive ?: rangeEndMillis, rangeEndMillis)
                    if (end <= start) return@forEach
                    val dates = "Dal ${millisDate(start)} al ${millisDate((end - 1).coerceAtLeast(start))}"
                    if (!period.snapshot.enabled) {
                        narrativeParts += "$dates il farmaco risultava sospeso."
                    } else {
                        val pStats = adherenceForPeriods(listOf(period), intakes, start, end, now, id)
                        val reg = if (pStats.expected > 0) " Regolarità ${pStats.percentage ?: 0}% (${pStats.taken}/${pStats.expected})." else ""
                        narrativeParts += "$dates lo schema era ${period.snapshot.scheduleDescription()}.$reg"
                    }
                }
                Text(
                    narrativeParts.joinToString(" ").ifBlank {
                        "Nel periodo selezionato risultano ${summary.registeredIntakes} assunzioni registrate."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (id !in currentById && meds.isNotEmpty()) {
                    TextButton(onClick = { mergeSource = summary }, modifier = Modifier.align(Alignment.End)) {
                        Text("Unisci con farmaco attuale")
                    }
                }
            }
        }
    }

    mergeSource?.let { source ->
        MedicationMergeDialog(
            source = source,
            currentMedications = meds,
            onDismiss = { mergeSource = null },
            onConfirm = { target ->
                if (repo.mergeHistoricalMedication(source.id, target.id)) {
                    mergeSource = null
                    onMerged()
                }
            }
        )
    }
}

@Composable
private fun HistoryOverviewContent(
    meds: List<Medication>,
    periods: List<TherapyPeriod>,
    intakes: List<IntakeEvent>
) {
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    val firstDate = today.minusDays(29)
    val rangeStart = firstDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val stats = remember(periods, intakes, today) {
        adherenceForPeriods(periods, intakes, rangeStart, now + 1, now)
    }
    val last14 = remember(periods, intakes, today) {
        dailyAdherence(periods, intakes, today.minusDays(13), 14, now)
    }

    Text("Ultimi 30 giorni", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HistoryKpiCard(
            icon = Icons.Default.EventAvailable,
            value = stats.expected.toString(),
            label = "Previste",
            modifier = Modifier.weight(1f)
        )
        HistoryKpiCard(
            icon = Icons.Default.CheckCircle,
            value = stats.taken.toString(),
            label = "Registrate",
            modifier = Modifier.weight(1f)
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HistoryKpiCard(
            icon = Icons.Default.RemoveCircleOutline,
            value = stats.missed.toString(),
            label = "Non registrate",
            modifier = Modifier.weight(1f)
        )
        HistoryKpiCard(
            icon = Icons.Default.Insights,
            value = stats.percentage?.let { "$it%" } ?: "—",
            label = "Regolarità",
            modifier = Modifier.weight(1f)
        )
    }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Andamento ultimi 14 giorni", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    stats.percentage?.let { "$it%" } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(
                Modifier.fillMaxWidth().height(92.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                last14.forEach { (date, dayStats) ->
                    val fraction = dayStats.percentage?.div(100f)?.coerceIn(0.08f, 1f) ?: 0.08f
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Box(
                            Modifier
                                .width(12.dp)
                                .fillMaxHeight(fraction)
                                .background(
                                    if (dayStats.expected > 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.shapes.small
                                )
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(date.dayOfMonth.toString(), fontSize = 8.sp, maxLines = 1)
                    }
                }
            }
            Text(
                "La regolarità confronta le assunzioni previste dalla configurazione valida nel periodo con quelle registrate come Assunto.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Text("Terapie attive (${meds.count { it.enabled }})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    meds.filter { it.enabled }.forEach { med ->
        val medStats = adherenceForPeriods(periods, intakes, rangeStart, now + 1, now, med.id)
        Card {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Medication, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(med.name, fontWeight = FontWeight.Bold)
                    Text(med.scheduleDescription(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    medStats.percentage?.let { "$it%" } ?: "—",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun HistoryKpiCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HistoryTimelineContent(
    periods: List<TherapyPeriod>,
    history: List<TherapyHistoryEntry>,
    intakes: List<IntakeEvent>
) {
    val now = System.currentTimeMillis()
    val visible = remember(periods) { periods.sortedByDescending { it.startMillis } }

    Text(
        "La Timeline racconta i periodi di terapia: durata, schema di assunzione e regolarità. " +
            "Non include acquisti, scarti o cambi confezione.",
        style = MaterialTheme.typography.bodyMedium
    )

    if (visible.isEmpty()) {
        Text("Non ci sono ancora periodi di terapia da raccontare.")
    }

    visible.forEach { period ->
        val endExclusive = period.endMillisExclusive ?: (now + 1)
        val stats = adherenceForPeriods(
            periods = listOf(period),
            intakes = intakes,
            rangeStartMillis = period.startMillis,
            rangeEndMillisExclusive = endExclusive,
            nowMillis = now,
            medicationId = period.medicationId
        )
        Card {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(period.medicationName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        therapyPeriodRange(period, now),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    therapyPeriodNarrative(period, stats, now),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    val deletedCount = history.count { it.type == TherapyHistoryType.DELETED }
    if (deletedCount > 0) {
        Text(
            "La storia comprende anche $deletedCount ${if (deletedCount == 1) "farmaco eliminato" else "farmaci eliminati"} dall'anagrafica.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun therapyPeriodRange(period: TherapyPeriod, nowMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(period.startMillis).atZone(zone).toLocalDate().itDate()
    val endMillis = period.endMillisExclusive
    return if (endMillis == null || endMillis > nowMillis) {
        "$start → oggi"
    } else {
        val end = Instant.ofEpochMilli((endMillis - 1).coerceAtLeast(period.startMillis)).atZone(zone).toLocalDate().itDate()
        "$start → $end"
    }
}

private fun therapyPeriodNarrative(
    period: TherapyPeriod,
    stats: AdherenceStats,
    nowMillis: Long
): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(period.startMillis).atZone(zone).toLocalDate().itDate()
    val endText = period.endMillisExclusive?.takeIf { it <= nowMillis }?.let {
        val end = Instant.ofEpochMilli((it - 1).coerceAtLeast(period.startMillis)).atZone(zone).toLocalDate().itDate()
        "al $end"
    } ?: "a oggi"

    if (!period.snapshot.enabled) {
        return "Dal $start $endText ${period.medicationName} è rimasto disattivato in MediTimer; in questo intervallo non erano previste sveglie."
    }

    val opening = when (period.sourceType) {
        TherapyHistoryType.CREATED -> "Dal $start hai iniziato a gestire ${period.medicationName}"
        TherapyHistoryType.ENABLED -> "Dal $start hai riattivato ${period.medicationName}"
        TherapyHistoryType.UPDATED -> "Dal $start hai seguito una nuova configurazione di ${period.medicationName}"
        TherapyHistoryType.BASELINE -> "Dal $start risulta in uso ${period.medicationName}"
        else -> "Dal $start hai utilizzato ${period.medicationName}"
    }
    val adherenceText = if (stats.expected > 0) {
        "Nel periodo hai registrato ${stats.taken} delle ${stats.expected} assunzioni previste (${stats.percentage ?: 0}%)."
    } else {
        "Nel periodo non ci sono abbastanza assunzioni previste per calcolare la regolarità."
    }
    val ending = when (period.endType) {
        TherapyHistoryType.UPDATED -> " Al termine del periodo hai modificato lo schema di assunzione."
        TherapyHistoryType.DISABLED -> " Al termine del periodo il farmaco è stato disattivato."
        TherapyHistoryType.DELETED -> " Al termine del periodo il farmaco è stato eliminato dall'anagrafica."
        TherapyHistoryType.ENABLED -> ""
        else -> ""
    }
    val legacy = if (period.legacyBaseline) " Per la parte storica precedente alla funzione Storia, lo schema è ricostruito dalla configurazione disponibile." else ""
    return "$opening, $endText, con ${period.snapshot.scheduleDescription()}. $adherenceText$ending$legacy"
}

@Composable
private fun HistoryMedicationsContent(
    meds: List<Medication>,
    history: List<TherapyHistoryEntry>,
    periods: List<TherapyPeriod>,
    intakes: List<IntakeEvent>,
    repo: MedicationRepository,
    onMerged: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var ascending by rememberSaveable { mutableStateOf(true) }
    var mergeSource by remember { mutableStateOf<MedicationHistorySummary?>(null) }
    val now = System.currentTimeMillis()
    val currentById = remember(meds) { meds.associateBy { it.id } }
    val historyById = remember(history) { history.groupBy { it.medicationId } }
    val intakeById = remember(intakes) { intakes.groupBy { it.medicationId } }
    val ids = remember(meds, history, intakes) {
        (meds.map { it.id } + history.map { it.medicationId } + intakes.map { it.medicationId }).distinct()
    }

    val summaries = remember(ids, query, ascending, meds, history, intakes) {
        ids.mapNotNull { id ->
            val entries = historyById[id].orEmpty().sortedBy { it.timestampMillis }
            val current = currentById[id]
            val latestSnapshot = current ?: entries.lastOrNull()?.previousSnapshot ?: entries.lastOrNull()?.snapshot
            val latestIntake = intakeById[id].orEmpty().maxByOrNull { it.takenAtMillis }
            val name = latestSnapshot?.name ?: latestIntake?.medicationName.orEmpty().ifBlank { return@mapNotNull null }
            val start = entries.minOfOrNull { it.timestampMillis }
                ?: intakeById[id].orEmpty().minOfOrNull { it.takenAtMillis }
                ?: return@mapNotNull null
            val deleted = entries.lastOrNull { it.type == TherapyHistoryType.DELETED }
            val end = deleted?.timestampMillis
            val status = when {
                current == null && deleted != null -> "Eliminato"
                current == null -> "Storico"
                current.enabled -> "Attivo"
                else -> "Sospeso"
            }
            val schedule = current ?: entries.asReversed().firstOrNull { it.type != TherapyHistoryType.DELETED }?.snapshot
            val stats = adherenceForPeriods(
                periods,
                intakes,
                start,
                (end ?: now) + 1,
                now,
                id
            )
            MedicationHistorySummary(id, name, start, end, status, schedule, stats, intakeById[id].orEmpty().size)
        }.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .let { if (ascending) it else it.reversed() }
    }

    MedicationSearchSortBar(
        query = query,
        onQueryChange = { query = it },
        ascending = ascending,
        onToggleSort = { ascending = !ascending }
    )

    if (summaries.isEmpty()) {
        Text("Nessun farmaco presente nella storia.")
    }

    summaries.forEach { item ->
        Card {
            Column(
                Modifier.fillMaxWidth().padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = when (item.status) {
                            "Attivo" -> androidx.compose.ui.graphics.Color(0xFFE3F4E8)
                            "Sospeso" -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Text(
                            item.status,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    if (item.endMillis == null)
                        "Dal ${millisDate(item.startMillis)} a oggi"
                    else
                        "Dal ${millisDate(item.startMillis)} al ${millisDate(item.endMillis)}",
                    style = MaterialTheme.typography.bodySmall
                )
                item.schedule?.let {
                    Text(it.scheduleDescription(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    if (item.stats.expected > 0)
                        "Regolarità: ${item.stats.percentage ?: 0}% · ${item.stats.taken}/${item.stats.expected} assunzioni previste"
                    else
                        "Assunzioni registrate: ${item.registeredIntakes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (item.id !in currentById && meds.isNotEmpty()) {
                    TextButton(
                        onClick = { mergeSource = item },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Unisci con farmaco attuale")
                    }
                }
            }
        }
    }

    mergeSource?.let { source ->
        MedicationMergeDialog(
            source = source,
            currentMedications = meds,
            onDismiss = { mergeSource = null },
            onConfirm = { target ->
                val merged = repo.mergeHistoricalMedication(
                    sourceMedicationId = source.id,
                    targetMedicationId = target.id
                )
                if (merged) {
                    mergeSource = null
                    onMerged()
                }
            }
        )
    }
}

@Composable
private fun MedicationMergeDialog(
    source: MedicationHistorySummary,
    currentMedications: List<Medication>,
    onDismiss: () -> Unit,
    onConfirm: (Medication) -> Unit
) {
    val sortedTargets = remember(currentMedications) {
        currentMedications.sortedBy { it.name.lowercase() }
    }
    var targetId by remember(source.id, currentMedications) {
        mutableStateOf(
            sortedTargets.firstOrNull { it.name.equals(source.name, ignoreCase = true) }?.id
                ?: sortedTargets.firstOrNull()?.id
        )
    }
    var targetMenuExpanded by remember { mutableStateOf(false) }
    val target = sortedTargets.firstOrNull { it.id == targetId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unisci farmaci") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Stai correggendo un farmaco duplicato nella Storia. " +
                        "Le impostazioni del farmaco attuale non verranno modificate."
                )

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("Storico da unire", style = MaterialTheme.typography.labelMedium)
                        Text(source.name, fontWeight = FontWeight.Bold)
                        Text(
                            "Dal ${millisDate(source.startMillis)}" +
                                (source.endMillis?.let { " al ${millisDate(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Text("Unisci nel farmaco attuale:", fontWeight = FontWeight.Bold)

                Box {
                    OutlinedButton(
                        onClick = { targetMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            target?.name ?: "Seleziona farmaco",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = targetMenuExpanded,
                        onDismissRequest = { targetMenuExpanded = false }
                    ) {
                        sortedTargets.forEach { medication ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(medication.name)
                                        Text(
                                            if (medication.enabled) "Attivo" else "Sospeso",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    targetId = medication.id
                                    targetMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Text(
                    "Verranno unificate le vecchie assunzioni e la cronologia terapeutica. " +
                        "Il falso passaggio eliminato → ricreato verrà rimosso dalla Storia. " +
                        "Sveglie, ricorrenza attuale, scorte, confezione e countdown resteranno quelli del farmaco attuale.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                enabled = target != null,
                onClick = { target?.let(onConfirm) }
            ) {
                Text("Conferma unione")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        }
    )
}

private data class MedicationHistorySummary(
    val id: Long,
    val name: String,
    val startMillis: Long,
    val endMillis: Long?,
    val status: String,
    val schedule: Medication?,
    val stats: AdherenceStats,
    val registeredIntakes: Int
)

private fun millisDate(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .toLocalDate()
    .itDate()


private data class HistoryImportPreview(
    val events: List<IntakeEvent>,
    val totalRows: Int,
    val invalidRows: Int,
    val duplicateRowsInFile: Int,
    val alreadyPresentRows: Int
) {
    val validRows: Int get() = events.size
    val newRows: Int get() = (events.size - alreadyPresentRows).coerceAtLeast(0)
}

private enum class CalendarPeriodFilter(val label: String) {
    ALL("Tutto"),
    YEAR("Anno"),
    MONTH("Mese"),
    WEEK("Settimana"),
    DAY("Giorno")
}

private data class CalendarMedicationOption(
    val id: Long,
    val name: String
)

private fun calendarFilterRange(
    mode: CalendarPeriodFilter,
    anchor: LocalDate
): Pair<LocalDate?, LocalDate?> = when (mode) {
    CalendarPeriodFilter.ALL -> null to null
    CalendarPeriodFilter.YEAR ->
        LocalDate.of(anchor.year, 1, 1) to LocalDate.of(anchor.year, 12, 31)
    CalendarPeriodFilter.MONTH ->
        anchor.withDayOfMonth(1) to anchor.withDayOfMonth(anchor.lengthOfMonth())
    CalendarPeriodFilter.WEEK -> {
        val start = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
        start to start.plusDays(6)
    }
    CalendarPeriodFilter.DAY -> anchor to anchor
}

private fun calendarFilterRangeLabel(
    mode: CalendarPeriodFilter,
    anchor: LocalDate
): String = when (mode) {
    CalendarPeriodFilter.ALL -> "Tutto"
    CalendarPeriodFilter.YEAR -> anchor.year.toString()
    CalendarPeriodFilter.MONTH ->
        anchor.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ITALIAN))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString() }
    CalendarPeriodFilter.WEEK -> {
        val (start, end) = calendarFilterRange(mode, anchor)
        "${start?.itDate()} – ${end?.itDate()}"
    }
    CalendarPeriodFilter.DAY -> anchor.itDate()
}

private fun moveCalendarFilterAnchor(
    mode: CalendarPeriodFilter,
    anchor: LocalDate,
    direction: Long
): LocalDate = when (mode) {
    CalendarPeriodFilter.ALL -> anchor
    CalendarPeriodFilter.YEAR -> anchor.plusYears(direction)
    CalendarPeriodFilter.MONTH -> anchor.plusMonths(direction)
    CalendarPeriodFilter.WEEK -> anchor.plusWeeks(direction)
    CalendarPeriodFilter.DAY -> anchor.plusDays(direction)
}

@Composable
private fun CalendarScreen(
    repo: MedicationRepository,
    meds: List<Medication>,
    revision: Int,
    refresh: () -> Unit
) {
    val context = LocalContext.current
    val intakes = remember(revision, meds) { repo.getIntakes().sortedByDescending { it.takenAtMillis } }
    val medNames = remember(meds) { meds.associate { it.id to it.name } }
    var editingEvent by remember { mutableStateOf<IntakeEvent?>(null) }
    var pendingImport by remember { mutableStateOf<HistoryImportPreview?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    var periodFilterName by rememberSaveable { mutableStateOf(CalendarPeriodFilter.ALL.name) }
    var filterAnchorEpochDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var selectedMedicationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var periodMenuExpanded by remember { mutableStateOf(false) }
    var medicationMenuExpanded by remember { mutableStateOf(false) }

    val periodFilter = runCatching { CalendarPeriodFilter.valueOf(periodFilterName) }
        .getOrDefault(CalendarPeriodFilter.ALL)
    val filterAnchor = LocalDate.ofEpochDay(filterAnchorEpochDay)

    val medicationOptions = remember(meds, intakes) {
        val names = linkedMapOf<Long, String>()
        meds.sortedBy { it.name.lowercase() }.forEach { names[it.id] = it.name }
        intakes.sortedByDescending { it.takenAtMillis }.forEach { event ->
            val name = event.medicationName.ifBlank { names[event.medicationId].orEmpty() }
            if (name.isNotBlank()) names.putIfAbsent(event.medicationId, name)
        }
        names.map { CalendarMedicationOption(it.key, it.value) }
            .sortedBy { it.name.lowercase() }
    }

    val selectedMedicationName = selectedMedicationId?.let { id ->
        medicationOptions.firstOrNull { it.id == id }?.name
            ?: medNames[id]
            ?: "Farmaco"
    } ?: "Tutti i farmaci"

    val filteredIntakes = remember(
        intakes,
        periodFilterName,
        filterAnchorEpochDay,
        selectedMedicationId
    ) {
        val (rangeStart, rangeEnd) = calendarFilterRange(periodFilter, filterAnchor)
        intakes.filter { event ->
            val takenDate = Instant.ofEpochMilli(event.takenAtMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val periodMatches =
                (rangeStart == null || !takenDate.isBefore(rangeStart)) &&
                    (rangeEnd == null || !takenDate.isAfter(rangeEnd))
            val medicationMatches =
                selectedMedicationId == null || event.medicationId == selectedMedicationId
            periodMatches && medicationMatches
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                    writer.write(buildHistoryCsv(intakes, medNames))
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = runCatching {
                val csv = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: error("File non leggibile")
                parseHistoryCsv(csv, meds, intakes)
            }
            pendingImport = result.getOrNull()
            importMessage = result.exceptionOrNull()?.let {
                "Import non riuscito: ${it.message ?: "CSV non valido"}"
            }
        }
    }

    ScreenColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Calendario", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Storico delle assunzioni effettive", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.FilterAlt, contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Filtri", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${calendarFilterRangeLabel(periodFilter, filterAnchor)} · $selectedMedicationName",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { filtersExpanded = !filtersExpanded }) {
                        Icon(
                            if (filtersExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (filtersExpanded) "Comprimi filtri" else "Espandi filtri"
                        )
                    }
                }

                if (filtersExpanded) {
                    Divider()
                    Spacer(Modifier.height(8.dp))

                    Text("Periodo", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Box {
                        OutlinedButton(
                            onClick = { periodMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(periodFilter.label, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = periodMenuExpanded,
                            onDismissRequest = { periodMenuExpanded = false }
                        ) {
                            CalendarPeriodFilter.entries.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.label) },
                                    onClick = {
                                        periodFilterName = item.name
                                        periodMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    if (periodFilter != CalendarPeriodFilter.ALL) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    filterAnchorEpochDay =
                                        moveCalendarFilterAnchor(periodFilter, filterAnchor, -1).toEpochDay()
                                }
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Periodo precedente")
                            }
                            OutlinedButton(
                                onClick = {
                                    showDatePicker(context, filterAnchor) {
                                        filterAnchorEpochDay = it.toEpochDay()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    calendarFilterRangeLabel(periodFilter, filterAnchor),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = {
                                    filterAnchorEpochDay =
                                        moveCalendarFilterAnchor(periodFilter, filterAnchor, 1).toEpochDay()
                                }
                            ) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "Periodo successivo")
                            }
                        }
                    }

                    Spacer(Modifier.height(7.dp))
                    Text("Farmaco", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Box {
                        OutlinedButton(
                            onClick = { medicationMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                selectedMedicationName,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = medicationMenuExpanded,
                            onDismissRequest = { medicationMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Tutti i farmaci") },
                                onClick = {
                                    selectedMedicationId = null
                                    medicationMenuExpanded = false
                                }
                            )
                            medicationOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.name) },
                                    onClick = {
                                        selectedMedicationId = option.id
                                        medicationMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    if (periodFilter != CalendarPeriodFilter.ALL || selectedMedicationId != null) {
                        TextButton(
                            onClick = {
                                periodFilterName = CalendarPeriodFilter.ALL.name
                                filterAnchorEpochDay = LocalDate.now().toEpochDay()
                                selectedMedicationId = null
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Azzera filtri")
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    importLauncher.launch(
                        arrayOf(
                            "text/csv",
                            "text/*",
                            "application/csv",
                            "application/vnd.ms-excel",
                            "application/octet-stream"
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Importa CSV")
            }
            OutlinedButton(
                onClick = { exportLauncher.launch("MediTimer_storico_${LocalDate.now()}.csv") },
                enabled = intakes.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Esporta CSV")
            }
        }

        importMessage?.let {
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { importMessage = null }) {
                        Icon(Icons.Default.Close, "Chiudi")
                    }
                }
            }
        }

        Text("Tocca un evento per modificarlo o cancellarlo.", style = MaterialTheme.typography.bodySmall)

        when {
            intakes.isEmpty() -> Text("Nessuna assunzione registrata.")
            filteredIntakes.isEmpty() -> Text("Nessun evento corrisponde ai filtri impostati.")
            else -> {
                val grouped = filteredIntakes.groupBy {
                    Instant.ofEpochMilli(it.takenAtMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                }.toList().sortedByDescending { it.first }

                grouped.forEach { (date, events) ->
                    Text(date.itDate(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    events.sortedBy { it.takenAtMillis }.forEach { event ->
                        val taken = Instant.ofEpochMilli(event.takenAtMillis)
                            .atZone(ZoneId.systemDefault())
                        val name = event.medicationName.ifBlank {
                            medNames[event.medicationId] ?: "Farmaco eliminato"
                        }
                        Card(onClick = { editingEvent = event }) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    taken.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(name, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Previsto: ${LocalDate.ofEpochDay(event.plannedEpochDay).itDate()} · ${event.plannedTime}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Icon(Icons.Default.Edit, contentDescription = "Modifica evento")
                            }
                        }
                    }
                }
            }
        }
    }

    pendingImport?.let { preview ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Importa storico CSV") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Righe lette: ${preview.totalRows}")
                    Text("Eventi validi nel file: ${preview.validRows}")
                    Text("Nuovi rispetto allo storico: ${preview.newRows}")
                    Text("Già presenti: ${preview.alreadyPresentRows}")
                    if (preview.duplicateRowsInFile > 0) {
                        Text("Duplicati interni al file: ${preview.duplicateRowsInFile}")
                    }
                    if (preview.invalidRows > 0) {
                        Text("Righe non valide ignorate: ${preview.invalidRows}")
                    }
                    Divider()
                    Text(
                        "Aggiungi mantiene lo storico attuale e inserisce solo gli eventi mancanti. " +
                            "Sostituisci elimina lo storico attuale e usa il contenuto del CSV.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = preview.events.isNotEmpty(),
                    onClick = {
                        val added = repo.mergeImportedIntakes(preview.events)
                        Scheduler.scheduleAllPackageReminders(context)
                        pendingImport = null
                        importMessage = "Import completato: $added nuovi eventi aggiunti."
                        refresh()
                    }
                ) { Text("Aggiungi") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = preview.events.isNotEmpty(),
                        onClick = { confirmReplace = true }
                    ) { Text("Sostituisci") }
                    TextButton(onClick = { pendingImport = null }) {
                        Text("Annulla")
                    }
                }
            }
        )
    }

    if (confirmReplace && pendingImport != null) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Sostituire tutto lo storico?") },
            text = {
                Text(
                    "Lo storico presente sul telefono verrà cancellato e sostituito con gli eventi validi del CSV. " +
                        "Questa operazione non modifica Farmaci o Sveglie."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val events = pendingImport?.events.orEmpty()
                        repo.replaceImportedIntakes(events)
                        Scheduler.scheduleAllPackageReminders(context)
                        confirmReplace = false
                        pendingImport = null
                        importMessage = "Storico sostituito: ${events.size} eventi caricati dal CSV."
                        refresh()
                    }
                ) { Text("Sì, sostituisci") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplace = false }) {
                    Text("Annulla")
                }
            }
        )
    }

    editingEvent?.let { event ->
        HistoryEventDialog(
            event = event,
            currentMedicationName = event.medicationName.ifBlank {
                medNames[event.medicationId].orEmpty()
            },
            onDismiss = { editingEvent = null },
            onSave = { updated ->
                repo.getCountdownsForIntake(
                    event.medicationId,
                    event.plannedEpochDay,
                    event.plannedTime
                ).forEach { countdown ->
                    Scheduler.cancelCountdown(context, countdown)
                    repo.removeCountdown(countdown.id)
                }
                repo.updateIntake(updated)
                Scheduler.scheduleAllPackageReminders(context)
                editingEvent = null
                refresh()
            },
            onDelete = {
                repo.getCountdownsForIntake(
                    event.medicationId,
                    event.plannedEpochDay,
                    event.plannedTime
                ).forEach { countdown ->
                    Scheduler.cancelCountdown(context, countdown)
                    repo.removeCountdown(countdown.id)
                }
                repo.deleteIntake(event.id)
                Scheduler.scheduleAllPackageReminders(context)
                editingEvent = null
                refresh()
            }
        )
    }
}

@Composable
private fun HistoryEventDialog(
    event: IntakeEvent,
    currentMedicationName: String,
    onDismiss: () -> Unit,
    onSave: (IntakeEvent) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val actual = remember(event.id, event.takenAtMillis) { Instant.ofEpochMilli(event.takenAtMillis).atZone(zone) }
    var medicationName by remember(event.id) { mutableStateOf(event.medicationName.ifBlank { currentMedicationName }) }
    var actualDate by remember(event.id) { mutableStateOf(actual.toLocalDate()) }
    var actualTimeText by remember(event.id) {
        mutableStateOf(actual.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")))
    }
    var plannedDate by remember(event.id) { mutableStateOf(LocalDate.ofEpochDay(event.plannedEpochDay)) }
    var plannedTimeText by remember(event.id) { mutableStateOf(event.plannedTime) }
    var confirmDelete by remember(event.id) { mutableStateOf(false) }
    var error by remember(event.id) { mutableStateOf<String?>(null) }
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifica assunzione") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = medicationName,
                    onValueChange = { medicationName = it },
                    label = { Text("Farmaco") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Assunzione effettiva", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = {
                    showDatePicker(context, actualDate) { actualDate = it }
                }) { Text("Data: ${actualDate.itDate()}") }
                OutlinedTextField(
                    value = actualTimeText,
                    onValueChange = { actualTimeText = it },
                    label = { Text("Ora effettiva (HH:mm)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Divider()
                Text("Programmazione originale", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = {
                    showDatePicker(context, plannedDate) { plannedDate = it }
                }) { Text("Data prevista: ${plannedDate.itDate()}") }
                OutlinedTextField(
                    value = plannedTimeText,
                    onValueChange = { plannedTimeText = it },
                    label = { Text("Ora prevista (HH:mm)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                if (confirmDelete) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Eliminare definitivamente questo evento dallo storico?", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onDelete) { Text("Sì, elimina") }
                                TextButton(onClick = { confirmDelete = false }) { Text("Annulla") }
                            }
                        }
                    }
                } else {
                    OutlinedButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Elimina evento")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val actualTime = runCatching { LocalTime.parse(actualTimeText.trim(), timeFormat) }.getOrNull()
                val plannedTime = runCatching { LocalTime.parse(plannedTimeText.trim(), timeFormat) }.getOrNull()
                error = when {
                    medicationName.isBlank() -> "Inserisci il nome del farmaco."
                    actualTime == null -> "Controlla l'ora effettiva: usa HH:mm."
                    plannedTime == null -> "Controlla l'ora prevista: usa HH:mm."
                    else -> null
                }
                if (error == null && actualTime != null && plannedTime != null) {
                    val millis = actualDate.atTime(actualTime).atZone(zone).toInstant().toEpochMilli()
                    onSave(
                        event.copy(
                            medicationName = medicationName.trim(),
                            plannedEpochDay = plannedDate.toEpochDay(),
                            plannedTime = plannedTime.format(timeFormat),
                            takenAtMillis = millis
                        )
                    )
                }
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } }
    )
}

private fun buildHistoryCsv(intakes: List<IntakeEvent>, medNames: Map<Long, String>): String = buildString {
    append('\uFEFF')
    appendLine("Data;Ora assunzione;Farmaco;Data prevista;Ora prevista;Timestamp;ID evento;ID farmaco")
    intakes.sortedBy { it.takenAtMillis }.forEach { event ->
        val taken = Instant.ofEpochMilli(event.takenAtMillis).atZone(ZoneId.systemDefault())
        val name = event.medicationName.ifBlank { medNames[event.medicationId] ?: "Farmaco eliminato" }
        append(csvCell(taken.toLocalDate().itDate())).append(';')
        append(csvCell(taken.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")))).append(';')
        append(csvCell(name)).append(';')
        append(csvCell(LocalDate.ofEpochDay(event.plannedEpochDay).itDate())).append(';')
        append(csvCell(event.plannedTime)).append(';')
        append(event.takenAtMillis).append(';')
        append(event.id).append(';')
        append(event.medicationId).appendLine()
    }
}

private fun parseHistoryCsv(
    raw: String,
    meds: List<Medication>,
    existing: List<IntakeEvent>
): HistoryImportPreview {
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n')
        .lineSequence()
        .filter { it.isNotBlank() }
        .toList()
    require(lines.isNotEmpty()) { "Il file è vuoto." }

    val header = parseCsvLine(lines.first()).map { normalizeCsvHeader(it) }
    fun col(vararg names: String): Int = names.asSequence()
        .map { normalizeCsvHeader(it) }
        .map { header.indexOf(it) }
        .firstOrNull { it >= 0 } ?: -1

    val dateCol = col("Data")
    val actualTimeCol = col("Ora assunzione", "Ora effettiva")
    val medCol = col("Farmaco", "Medicinale")
    val plannedDateCol = col("Data prevista")
    val plannedTimeCol = col("Ora prevista")
    val timestampCol = col("Timestamp")
    val eventIdCol = col("ID evento", "Event ID")
    val medIdCol = col("ID farmaco", "Medication ID")

    require(medCol >= 0) { "Manca la colonna Farmaco." }
    require((dateCol >= 0 && actualTimeCol >= 0) || timestampCol >= 0) {
        "Servono Data + Ora assunzione oppure Timestamp."
    }

    val dateFormats = listOf(
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ISO_LOCAL_DATE
    )
    val timeFormats = listOf(
        DateTimeFormatter.ofPattern("HH:mm:ss"),
        DateTimeFormatter.ofPattern("HH:mm")
    )
    val zone = ZoneId.systemDefault()
    val medByName = meds.associateBy { it.name.trim().lowercase() }
    val existingFingerprints = existing.mapTo(mutableSetOf()) { historyFingerprint(it) }
    val fileFingerprints = mutableSetOf<String>()
    val parsed = mutableListOf<IntakeEvent>()
    var invalid = 0
    var duplicateInFile = 0
    var alreadyPresent = 0

    fun value(row: List<String>, index: Int): String = if (index in row.indices) row[index].trim() else ""

    lines.drop(1).forEachIndexed { rowIndex, line ->
        val row = parseCsvLine(line)
        val event = runCatching {
            val name = value(row, medCol).trim()
            require(name.isNotBlank())

            val timestamp = value(row, timestampCol).toLongOrNull()
            val actualDate = value(row, dateCol).takeIf { it.isNotBlank() }?.let { parseDateFlexible(it, dateFormats) }
            val actualTime = value(row, actualTimeCol).takeIf { it.isNotBlank() }?.let { parseTimeFlexible(it, timeFormats) }
            val fieldsMillis = if (actualDate != null && actualTime != null) {
                actualDate.atTime(actualTime).atZone(zone).toInstant().toEpochMilli()
            } else null
            val takenAtMillis = when {
                timestamp != null && fieldsMillis != null -> {
                    val tsLocal = Instant.ofEpochMilli(timestamp).atZone(zone)
                    if (tsLocal.toLocalDate() == actualDate &&
                        tsLocal.toLocalTime().withNano(0) == actualTime?.withNano(0)) timestamp else fieldsMillis
                }
                fieldsMillis != null -> fieldsMillis
                timestamp != null -> timestamp
                else -> error("Data/ora effettiva non valida")
            }

            val takenLocal = Instant.ofEpochMilli(takenAtMillis).atZone(zone)
            val plannedDate = value(row, plannedDateCol).takeIf { it.isNotBlank() }
                ?.let { parseDateFlexible(it, dateFormats) }
                ?: takenLocal.toLocalDate()
            val plannedTime = value(row, plannedTimeCol).takeIf { it.isNotBlank() }
                ?.let { parseTimeFlexible(it, timeFormats) }
                ?: takenLocal.toLocalTime()

            val activeMed = medByName[name.lowercase()]
            val medicationId = value(row, medIdCol).toLongOrNull()
                ?: activeMed?.id
                ?: stableImportedMedicationId(name)
            val eventId = value(row, eventIdCol).toLongOrNull()
                ?: importedEventId(takenAtMillis, name, rowIndex)

            IntakeEvent(
                id = eventId,
                medicationId = medicationId,
                medicationName = name,
                plannedEpochDay = plannedDate.toEpochDay(),
                plannedTime = plannedTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                takenAtMillis = takenAtMillis
            )
        }.getOrNull()

        if (event == null) {
            invalid++
        } else {
            val fp = historyFingerprint(event)
            if (!fileFingerprints.add(fp)) {
                duplicateInFile++
            } else {
                if (fp in existingFingerprints) alreadyPresent++
                parsed.add(event)
            }
        }
    }

    return HistoryImportPreview(
        events = parsed,
        totalRows = lines.size - 1,
        invalidRows = invalid,
        duplicateRowsInFile = duplicateInFile,
        alreadyPresentRows = alreadyPresent
    )
}

private fun parseCsvLine(line: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                current.append('"')
                i++
            }
            ch == '"' -> quoted = !quoted
            ch == ';' && !quoted -> {
                out.add(current.toString())
                current.clear()
            }
            else -> current.append(ch)
        }
        i++
    }
    out.add(current.toString())
    return out
}

private fun normalizeCsvHeader(value: String): String = value
    .removePrefix("\uFEFF")
    .trim()
    .lowercase()
    .replace(Regex("\\s+"), " ")

private fun parseDateFlexible(text: String, formats: List<DateTimeFormatter>): LocalDate =
    formats.asSequence()
        .mapNotNull { runCatching { LocalDate.parse(text.trim(), it) }.getOrNull() }
        .firstOrNull() ?: error("Data non valida")

private fun parseTimeFlexible(text: String, formats: List<DateTimeFormatter>): LocalTime =
    formats.asSequence()
        .mapNotNull { runCatching { LocalTime.parse(text.trim(), it) }.getOrNull() }
        .firstOrNull() ?: error("Ora non valida")

private fun stableImportedMedicationId(name: String): Long =
    -1L - (name.trim().lowercase().hashCode().toLong() and 0x7FFFFFFFL)

private fun importedEventId(takenAtMillis: Long, name: String, rowIndex: Int): Long =
    takenAtMillis xor (name.hashCode().toLong() shl 16) xor rowIndex.toLong()

private fun historyFingerprint(event: IntakeEvent): String = buildString {
    append(event.medicationName.trim().lowercase())
    append('|').append(event.takenAtMillis)
    append('|').append(event.plannedEpochDay)
    append('|').append(event.plannedTime.trim())
}

private fun csvCell(value: String): String {
    val escaped = value.replace("\"", "\"\"")
    return if (escaped.any { it == ';' || it == '\n' || it == '\r' || it == '\"' }) "\"$escaped\"" else escaped
}

@Composable
private fun PackageKpiCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    valueFontSize: androidx.compose.ui.unit.TextUnit = 20.sp
) {
    Surface(
        modifier = modifier.height(112.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.fillMaxWidth().height(26.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Box(
                Modifier.fillMaxWidth().height(30.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    value,
                    fontWeight = FontWeight.Bold,
                    fontSize = valueFontSize,
                    color = valueColor,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
            Box(
                Modifier.fillMaxWidth().height(34.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    lineHeight = 12.sp,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun PackageScreen(meds: List<Medication>, repo: MedicationRepository, refresh: () -> Unit) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var stockAction by remember { mutableStateOf<StockAction?>(null) }
    var intakeRemainingMedication by remember { mutableStateOf<Medication?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortAscending by rememberSaveable { mutableStateOf(true) }

    val activeMeds = remember(meds) { meds.filter { it.enabled } }

    val visibleMeds = remember(activeMeds, query, sortAscending) {
        val filtered = activeMeds.filter { med ->
            query.isBlank() ||
                med.name.contains(query, ignoreCase = true) ||
                med.doseNote.contains(query, ignoreCase = true)
        }.sortedBy { it.name.lowercase() }
        if (sortAscending) filtered else filtered.reversed()
    }

    val warnings = activeMeds.flatMap { med ->
        val items = mutableListOf<String>()
        val dayRemaining = if (med.packageDurationMode == PackageDurationMode.DAYS) {
            med.lastPackageChangeEpochDay?.let(LocalDate::ofEpochDay)
                ?.plusDays(med.packageMaxDays.toLong())
                ?.toEpochDay()?.minus(today.toEpochDay())
        } else null
        val intakeRemaining = if (med.packageDurationMode == PackageDurationMode.INTAKES) repo.getPackageIntakesRemaining(med) else null
        if (dayRemaining != null && dayRemaining <= 7L) {
            items += when {
                dayRemaining > 1 -> "${med.name}: cambio tra $dayRemaining giorni"
                dayRemaining == 1L -> "${med.name}: cambio domani"
                dayRemaining == 0L -> "${med.name}: cambio oggi"
                dayRemaining == -1L -> "${med.name}: cambio scaduto da 1 giorno"
                else -> "${med.name}: cambio scaduto da ${-dayRemaining} giorni"
            }
        }
        if (intakeRemaining != null && intakeRemaining <= 7) {
            items += when {
                intakeRemaining > 1 -> "${med.name}: restano $intakeRemaining assunzioni prima del cambio"
                intakeRemaining == 1 -> "${med.name}: resta 1 assunzione prima del cambio"
                intakeRemaining == 0 -> "${med.name}: numero massimo di assunzioni raggiunto — cambia confezione"
                intakeRemaining == -1 -> "${med.name}: durata superata di 1 assunzione"
                else -> "${med.name}: durata superata di ${-intakeRemaining} assunzioni"
            }
        }
        when (med.stockCount) {
            null -> items += "${med.name}: imposta la scorta iniziale"
            else -> {
                val stockReminder = evaluateStockReminder(med, repo, today)
                if (stockReminder.active) items += stockReminder.message
            }
        }
        items
    }

    ScreenColumn {
        Text("Confezioni", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        if (activeMeds.isNotEmpty()) {
            MedicationSearchSortBar(
                query = query,
                onQueryChange = { query = it },
                ascending = sortAscending,
                onToggleSort = { sortAscending = !sortAscending }
            )
        }

        if (warnings.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Text("Attenzioni", fontWeight = FontWeight.Bold)
                    }
                    warnings.forEach { Text("• $it") }
                }
            }
        }

        visibleMeds.forEach { med ->
            val last = med.lastPackageChangeEpochDay?.let(LocalDate::ofEpochDay)
            val dayRemaining = if (med.packageDurationMode == PackageDurationMode.DAYS) {
                last?.plusDays(med.packageMaxDays.toLong())?.toEpochDay()?.minus(today.toEpochDay())
            } else null
            val intakeRemaining = if (med.packageDurationMode == PackageDurationMode.INTAKES) repo.getPackageIntakesRemaining(med) else null

            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(med.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        when (med.stockCount) {
                            null -> AssistChip(onClick = {}, label = { Text("Scorta da impostare") })
                            0 -> AssistChip(onClick = {}, label = { Text("Scorta esaurita") })
                            1 -> AssistChip(onClick = {}, label = { Text("Ultima confezione") })
                            else -> Unit
                        }
                    }

                    val progressRemaining = intakeRemaining?.toLong() ?: dayRemaining
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PackageKpiCard(
                            icon = Icons.Default.CalendarMonth,
                            value = last?.itDate() ?: "—",
                            label = "Ultimo cambio",
                            modifier = Modifier.weight(1f),
                            valueFontSize = 14.sp
                        )
                        PackageKpiCard(
                            icon = Icons.Default.HourglassBottom,
                            value = when {
                                progressRemaining == null -> "—"
                                progressRemaining >= 0L -> progressRemaining.toString()
                                else -> "−${-progressRemaining}"
                            },
                            label = when {
                                med.packageDurationMode == PackageDurationMode.INTAKES &&
                                    (progressRemaining ?: 0L) < 0L -> "Assunzioni oltre"
                                med.packageDurationMode == PackageDurationMode.INTAKES -> "Assunzioni rimaste"
                                (progressRemaining ?: 0L) < 0L -> "Giorni oltre"
                                else -> "Giorni mancanti"
                            },
                            modifier = Modifier.weight(1f),
                            valueColor = if ((progressRemaining ?: 99L) <= 7L)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                        PackageKpiCard(
                            icon = Icons.Default.Inventory2,
                            value = med.stockCount?.toString() ?: "—",
                            label = "Confezioni rimaste",
                            modifier = Modifier.weight(1f),
                            valueColor = if ((med.stockCount ?: 99) <= 1)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { registerPackageChange(context, repo, med, today, refresh) },
                            modifier = Modifier.weight(1f).height(42.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Cambiata oggi", fontSize = 12.sp, maxLines = 2)
                        }
                        OutlinedButton(
                            onClick = {
                                showDatePicker(context, last ?: today) { selected ->
                                    updatePackageOpeningDate(context, repo, med, selected, refresh)
                                }
                            },
                            modifier = Modifier.weight(1f).height(42.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Modifica data", fontSize = 12.sp, maxLines = 1)
                        }
                    }

                    if (med.packageDurationMode == PackageDurationMode.INTAKES) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { stockAction = StockAction(med, StockMode.SET) },
                                modifier = Modifier.weight(1f).height(42.dp),
                                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp)
                            ) {
                                Text("Imposta scorta", fontSize = 11.sp, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { intakeRemainingMedication = med },
                                enabled = med.lastPackageChangeEpochDay != null,
                                modifier = Modifier.weight(1f).height(42.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    "Imposta assunzioni rimaste",
                                    fontSize = 10.sp,
                                    maxLines = 2,
                                    lineHeight = 11.sp
                                )
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { stockAction = StockAction(med, StockMode.SET) },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text("Imposta scorta", fontSize = 12.sp)
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { stockAction = StockAction(med, StockMode.ADD) },
                            modifier = Modifier.weight(1f).height(40.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Acquisto", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { stockAction = StockAction(med, StockMode.REMOVE) },
                            enabled = (med.stockCount ?: 0) > 0,
                            modifier = Modifier.weight(1f).height(40.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Scarto", fontSize = 12.sp)
                        }
                    }

                    Text(
                        "La scorta comprende solo le confezioni chiuse; quella in uso non è conteggiata.",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (meds.isEmpty()) {
            Text("Nessun farmaco configurato.")
        } else if (activeMeds.isEmpty()) {
            Text("Nessun farmaco attivo.")
        } else if (visibleMeds.isEmpty()) {
            Text("Nessun farmaco attivo corrisponde alla ricerca.")
        }
    }

    stockAction?.let { action ->
        StockAdjustmentDialog(
            action = action,
            onDismiss = { stockAction = null },
            onConfirm = { quantity ->
                val current = action.medication.stockCount ?: 0
                val newStock = when (action.mode) {
                    StockMode.ADD -> current + quantity
                    StockMode.REMOVE -> (current - quantity).coerceAtLeast(0)
                    StockMode.SET -> quantity
                }
                val updated = action.medication.copy(stockCount = newStock)
                repo.upsertMedication(updated)
                Scheduler.scheduleNextStockReminder(context, updated)

                stockAction = null
                refresh()
            }
        )
    }

    intakeRemainingMedication?.let { medication ->
        val currentRemaining = repo.getPackageIntakesRemaining(medication)
            ?: medication.packageMaxIntakes

        IntakeRemainingAdjustmentDialog(
            medication = medication,
            currentRemaining = currentRemaining,
            onDismiss = { intakeRemainingMedication = null },
            onConfirm = { desiredRemaining ->
                val rawRemaining = medication.packageMaxIntakes - repo.getPackageIntakesUsed(medication)
                val updated = medication.copy(
                    packageIntakesAdjustment = desiredRemaining - rawRemaining
                )
                repo.upsertMedication(updated)
                repo.clearPackageReminderState(updated.id)
                Scheduler.scheduleNextPackageReminder(context, updated)
                Scheduler.scheduleNextStockReminder(context, updated)
                intakeRemainingMedication = null
                refresh()
            }
        )
    }
}

@Composable
private fun IntakeRemainingAdjustmentDialog(
    medication: Medication,
    currentRemaining: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var remainingText by remember(medication, currentRemaining) {
        mutableStateOf(currentRemaining.coerceAtLeast(0).toString())
    }
    val remaining = remainingText.toIntOrNull()
    val maxIntakes = medication.packageMaxIntakes.coerceAtLeast(1)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assunzioni rimaste · ${medication.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Durata confezione: $maxIntakes assunzioni")
                Text("Residuo attuale: ${currentRemaining.coerceAtLeast(0)}")
                OutlinedTextField(
                    value = remainingText,
                    onValueChange = { remainingText = it.filter(Char::isDigit) },
                    label = { Text("Assunzioni rimaste nella confezione") },
                    supportingText = { Text("Valore da 0 a $maxIntakes") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(remaining ?: 0) },
                enabled = remaining != null && remaining in 0..maxIntakes
            ) { Text("Conferma") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

private enum class StockMode { ADD, REMOVE, SET }
private data class StockAction(val medication: Medication, val mode: StockMode)

@Composable
private fun StockAdjustmentDialog(action: StockAction, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var quantityText by remember(action) { mutableStateOf(if (action.mode == StockMode.SET) (action.medication.stockCount ?: 0).toString() else "1") }
    val quantity = quantityText.toIntOrNull()
    val title = when (action.mode) {
        StockMode.ADD -> "Registra acquisto"
        StockMode.REMOVE -> "Riduci scorta"
        StockMode.SET -> "Imposta scorta"
    }
    val label = when (action.mode) {
        StockMode.ADD -> "Confezioni acquistate"
        StockMode.REMOVE -> "Confezioni da eliminare/scartare"
        StockMode.SET -> "Scorta reale attuale"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$title · ${action.medication.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scorta attuale: ${action.medication.stockCount?.toString() ?: "non impostata"}")
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it.filter(Char::isDigit) },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(quantity ?: 0) },
                enabled = quantity != null && if (action.mode == StockMode.SET) quantity >= 0 else quantity > 0
            ) { Text("Conferma") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

private fun registerPackageChange(
    context: Context,
    repo: MedicationRepository,
    medication: Medication,
    date: LocalDate,
    refresh: () -> Unit
) {
    val newStock = medication.stockCount?.let { (it - 1).coerceAtLeast(0) }
    val updated = medication.copy(
        lastPackageChangeEpochDay = date.toEpochDay(),
        lastPackageChangeMillis = if (date == LocalDate.now()) System.currentTimeMillis() else
            date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        packageIntakesAdjustment = 0,
        stockCount = newStock
    )
    repo.upsertMedication(updated)
    repo.clearPackageReminderState(medication.id)
    Scheduler.scheduleNextPackageReminder(context, updated)
    Scheduler.scheduleNextStockReminder(context, updated)
    refresh()
}


private fun updatePackageOpeningDate(
    context: Context,
    repo: MedicationRepository,
    medication: Medication,
    date: LocalDate,
    refresh: () -> Unit
) {
    val updated = medication.copy(
        lastPackageChangeEpochDay = date.toEpochDay(),
        lastPackageChangeMillis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        packageIntakesAdjustment = 0
    )
    repo.upsertMedication(updated)

    // Correzione della data registrata: la scorta non viene modificata.
    repo.clearPackageReminderState(updated.id)
    Scheduler.scheduleNextPackageReminder(context, updated)
    Scheduler.scheduleNextStockReminder(context, updated)
    refresh()
}


private fun packageRemainingLabel(remaining: Long?): String = when {
    remaining == null -> ""
    remaining > 1 -> "Mancano $remaining giorni"
    remaining == 1L -> "Manca 1 giorno"
    remaining == 0L -> "Cambio previsto oggi"
    remaining == -1L -> "Cambio scaduto da 1 giorno"
    else -> "Cambio scaduto da ${-remaining} giorni"
}

private fun showDatePicker(context: Context, initial: LocalDate, onSelected: (LocalDate) -> Unit) {
    DatePickerDialog(
        context,
        { _, year, month, day -> onSelected(LocalDate.of(year, month + 1, day)) },
        initial.year, initial.monthValue - 1, initial.dayOfMonth
    ).show()
}

@Composable
private fun MedicationEditorDialog(initial: Medication?, onDismiss: () -> Unit, onSave: (Medication) -> Unit) {
    val context = LocalContext.current
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var doseNote by remember(initial) { mutableStateOf(initial?.doseNote.orEmpty()) }
    var enabled by remember(initial) { mutableStateOf(initial?.enabled ?: true) }
    var timesCount by remember(initial) { mutableIntStateOf(initial?.timesPerActiveDay ?: 1) }
    var times by remember(initial) { mutableStateOf(initial?.alarmTimes ?: listOf("08:00")) }
    var recurrence by remember(initial) { mutableStateOf(initial?.recurrenceType ?: RecurrenceType.DAILY) }
    var weekdays by remember(initial) { mutableStateOf(initial?.weekdays ?: setOf(1,2,3,4,5)) }
    var everyN by remember(initial) { mutableStateOf((initial?.everyNDays ?: 2).toString()) }
    var anchorDate by remember(initial) { mutableStateOf(initial?.anchorEpochDay?.let(LocalDate::ofEpochDay) ?: LocalDate.now()) }
    var monthlyDaysText by remember(initial) { mutableStateOf(initial?.monthlyDays?.sorted()?.joinToString(",") ?: "1,15") }
    var packageDurationMode by remember(initial) { mutableStateOf(initial?.packageDurationMode ?: PackageDurationMode.DAYS) }
    var packageDays by remember(initial) { mutableStateOf((initial?.packageMaxDays?.takeIf { it > 0 } ?: 30).toString()) }
    var packageIntakes by remember(initial) { mutableStateOf((initial?.packageMaxIntakes?.takeIf { it > 0 } ?: 28).toString()) }
    var stockReminderMode by remember(initial) { mutableStateOf(initial?.stockReminderMode ?: StockReminderMode.REORDER_POINT) }
    var stockReminderThreshold by remember(initial) { mutableStateOf((initial?.stockReminderThreshold ?: 1).toString()) }
    var countdownEnabled by remember(initial) { mutableStateOf(initial?.countdownEnabled ?: false) }
    var countdownMinutes by remember(initial) { mutableStateOf((initial?.countdownMinutes?.takeIf { it > 0 } ?: 2).toString()) }
    var countdownNote by remember(initial) { mutableStateOf(initial?.countdownNote.orEmpty()) }
    var snoozeMinutes by remember(initial) { mutableStateOf((initial?.snoozeMinutes ?: 10).toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun resizeTimes(newCount: Int) {
        timesCount = newCount.coerceIn(1, 8)
        val firstTime = times.firstOrNull() ?: "08:00"
        times = generateEquidistantTimes(firstTime, timesCount)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.94f),
        title = { Text(if (initial == null) "Nuovo farmaco" else "Modifica farmaco") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome farmaco *") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(doseNote, { doseNote = it }, label = { Text("Dose / nota") }, placeholder = { Text("es. 1 compressa") }, modifier = Modifier.fillMaxWidth())

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Attivo", modifier = Modifier.weight(1f))
                    Switch(enabled, { enabled = it })
                }

                Text("Assunzioni nei giorni attivi", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { resizeTimes(timesCount - 1) }, enabled = timesCount > 1) { Text("−") }
                    Text(timesCount.toString(), style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(onClick = { resizeTimes(timesCount + 1) }, enabled = timesCount < 8) { Text("+") }
                }
                Text("Orari in formato 24 ore (HH:mm)", style = MaterialTheme.typography.bodySmall)
                times.take(timesCount).forEachIndexed { index, value ->
                    OutlinedButton(
                        onClick = {
                            showTimePicker(context, value) { selected ->
                                times = if (index == 0 && timesCount > 1) {
                                    generateEquidistantTimes(selected, timesCount)
                                } else {
                                    times.toMutableList().also { it[index] = selected }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AccessTime, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Orario ${index + 1}: $value")
                    }
                }

                Divider()
                Text("Snooze avviso", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    snoozeMinutes,
                    { snoozeMinutes = it.filter(Char::isDigit) },
                    label = { Text("Snooze tra gli avvisi (minuti)") },
                    supportingText = { Text("Quando premi Rimanda, l'avviso ricompare dopo questo intervallo.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text("Ricorrenza", fontWeight = FontWeight.Bold)
                RecurrencePicker(recurrence) { recurrence = it }
                when (recurrence) {
                    RecurrenceType.DAILY -> Text("Il farmaco è previsto ogni giorno.", style = MaterialTheme.typography.bodySmall)
                    RecurrenceType.WEEKDAYS -> WeekdayPicker(weekdays) { weekdays = it }
                    RecurrenceType.EVERY_N_DAYS -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            everyN, { everyN = it.filter(Char::isDigit) },
                            label = { Text("Ogni quanti giorni") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        OutlinedButton(onClick = {
                            showDatePicker(context, anchorDate) { anchorDate = it }
                        }) { Text("Data di partenza: ${anchorDate.itDate()}") }
                    }
                    RecurrenceType.MONTHLY_DAYS -> OutlinedTextField(
                        monthlyDaysText, { monthlyDaysText = it },
                        label = { Text("Giorni del mese") },
                        placeholder = { Text("es. 1, 10, 20") },
                        supportingText = { Text("Inserisci i giorni separati da virgola (1–31).") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Divider()
                Text("Durata confezione in uso", fontWeight = FontWeight.Bold)
                Text(
                    "Scegli un solo criterio: durata in giorni oppure numero massimo di assunzioni.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = packageDurationMode == PackageDurationMode.DAYS,
                        onClick = { packageDurationMode = PackageDurationMode.DAYS },
                        label = { Text("Giorni") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = packageDurationMode == PackageDurationMode.INTAKES,
                        onClick = { packageDurationMode = PackageDurationMode.INTAKES },
                        label = { Text("Assunzioni") },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (packageDurationMode == PackageDurationMode.DAYS) {
                    OutlinedTextField(
                        packageDays, { packageDays = it.filter(Char::isDigit) },
                        label = { Text("Durata massima dopo apertura (giorni)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                } else {
                    OutlinedTextField(
                        packageIntakes, { packageIntakes = it.filter(Char::isDigit) },
                        label = { Text("Numero massimo di assunzioni per confezione") },
                        supportingText = { Text("Il contatore diminuisce di 1 a ogni Assunto. Se prendi più compresse per volta, inserisci il numero di assunzioni della confezione.") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }

                Divider()
                Text("Reminder scorta", fontWeight = FontWeight.Bold)
                Text(
                    "Scegli quando MediTimer deve suggerire il riacquisto. L'avviso resta visibile in Oggi; Android invia il primo avviso e poi un promemoria settimanale finché la condizione rimane valida.",
                    style = MaterialTheme.typography.bodySmall
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = stockReminderMode == StockReminderMode.NONE,
                        onClick = { stockReminderMode = StockReminderMode.NONE },
                        label = { Text("Nessuno") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    FilterChip(
                        selected = stockReminderMode == StockReminderMode.REORDER_POINT,
                        onClick = { stockReminderMode = StockReminderMode.REORDER_POINT },
                        label = { Text("Punto di riordino") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    FilterChip(
                        selected = stockReminderMode == StockReminderMode.TIME_REMAINING,
                        onClick = { stockReminderMode = StockReminderMode.TIME_REMAINING },
                        label = { Text("Tempo residuo") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                when (stockReminderMode) {
                    StockReminderMode.NONE -> Text(
                        "Nessun avviso di riacquisto.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    StockReminderMode.REORDER_POINT -> OutlinedTextField(
                        value = stockReminderThreshold,
                        onValueChange = { stockReminderThreshold = it.filter(Char::isDigit) },
                        label = { Text("Confezioni minime a scorta") },
                        supportingText = { Text("L'avviso scatta quando la scorta è minore o uguale a questa soglia.") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    StockReminderMode.TIME_REMAINING -> OutlinedTextField(
                        value = stockReminderThreshold,
                        onValueChange = { stockReminderThreshold = it.filter(Char::isDigit) },
                        label = {
                            Text(
                                if (packageDurationMode == PackageDurationMode.DAYS)
                                    "Giorni prima della fine dell'ultima confezione"
                                else
                                    "Assunzioni prima della fine dell'ultima confezione"
                            )
                        },
                        supportingText = {
                            Text("Questo reminder è attivo solo quando la scorta delle confezioni chiuse è 0.")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Divider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Countdown dopo assunzione", fontWeight = FontWeight.Bold)
                        Text("Parte quando premi “Assunto”.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(countdownEnabled, { countdownEnabled = it })
                }
                if (countdownEnabled) {
                    OutlinedTextField(
                        countdownMinutes, { countdownMinutes = it.filter(Char::isDigit) },
                        label = { Text("Durata countdown (minuti)") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        countdownNote, { countdownNote = it },
                        label = { Text("Nota al countdown") },
                        placeholder = { Text("es. Attendere prima di fare colazione") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsedTimes = times.take(timesCount).map { it.trim() }
                val n = everyN.toIntOrNull() ?: 0
                val pDays = packageDays.toIntOrNull() ?: 0
                val pIntakes = packageIntakes.toIntOrNull() ?: 0
                val stockThreshold = stockReminderThreshold.toIntOrNull() ?: -1
                val c = countdownMinutes.toIntOrNull() ?: 0
                val snooze = snoozeMinutes.toIntOrNull() ?: 0
                val monthly = parseMonthlyDays(monthlyDaysText)
                error = when {
                    name.isBlank() -> "Inserisci il nome del farmaco."
                    parsedTimes.any { runCatching { LocalTime.parse(it, DateTimeFormatter.ofPattern("HH:mm")) }.isFailure } -> "Uno degli orari non è valido."
                    parsedTimes.distinct().size != parsedTimes.size -> "Gli orari delle assunzioni devono essere diversi."
                    recurrence == RecurrenceType.WEEKDAYS && weekdays.isEmpty() -> "Seleziona almeno un giorno della settimana."
                    recurrence == RecurrenceType.EVERY_N_DAYS && n < 1 -> "La ricorrenza deve essere almeno ogni 1 giorno."
                    recurrence == RecurrenceType.MONTHLY_DAYS && monthly.isEmpty() -> "Inserisci almeno un giorno del mese valido (1–31)."
                    packageDurationMode == PackageDurationMode.DAYS && pDays < 1 -> "La durata della confezione deve essere almeno 1 giorno."
                    packageDurationMode == PackageDurationMode.INTAKES && pIntakes < 1 -> "Il numero di assunzioni per confezione deve essere almeno 1."
                    stockReminderMode != StockReminderMode.NONE && stockThreshold < 0 -> "La soglia del reminder scorta deve essere 0 o superiore."
                    snooze < 1 -> "Lo snooze deve essere almeno 1 minuto."
                    countdownEnabled && c < 1 -> "Il countdown deve durare almeno 1 minuto."
                    else -> null
                }
                if (error == null) {
                    onSave(
                        Medication(
                            id = initial?.id ?: System.currentTimeMillis(),
                            name = name.trim(),
                            doseNote = doseNote.trim(),
                            timesPerActiveDay = timesCount,
                            recurrenceType = recurrence,
                            weekdays = weekdays,
                            everyNDays = n.coerceAtLeast(1),
                            anchorEpochDay = anchorDate.toEpochDay(),
                            monthlyDays = monthly,
                            alarmTimes = parsedTimes.sorted(),
                            packageDurationMode = packageDurationMode,
                            packageMaxDays = if (packageDurationMode == PackageDurationMode.DAYS) pDays else 0,
                            packageMaxIntakes = if (packageDurationMode == PackageDurationMode.INTAKES) pIntakes else 0,
                            packageIntakesAdjustment =
                                if (
                                    packageDurationMode == PackageDurationMode.INTAKES &&
                                    initial?.packageDurationMode == PackageDurationMode.INTAKES &&
                                    initial.packageMaxIntakes == pIntakes
                                ) initial.packageIntakesAdjustment else 0,
                            lastPackageChangeEpochDay = initial?.lastPackageChangeEpochDay,
                            lastPackageChangeMillis = initial?.lastPackageChangeMillis,
                            stockCount = initial?.stockCount,
                            stockReminderMode = stockReminderMode,
                            stockReminderThreshold = if (stockReminderMode == StockReminderMode.NONE) 0 else stockThreshold.coerceAtLeast(0),
                            countdownEnabled = countdownEnabled,
                            countdownMinutes = if (countdownEnabled) c else 0,
                            countdownNote = if (countdownEnabled) countdownNote.trim() else "",
                            snoozeMinutes = snooze.coerceAtLeast(1),
                            enabled = enabled
                        )
                    )
                }
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
private fun RecurrencePicker(selected: RecurrenceType, onSelected: (RecurrenceType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                when (selected) {
                    RecurrenceType.DAILY -> "Ogni giorno"
                    RecurrenceType.WEEKDAYS -> "Giorni della settimana"
                    RecurrenceType.EVERY_N_DAYS -> "Ogni N giorni"
                    RecurrenceType.MONTHLY_DAYS -> "Giorni del mese"
                },
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            listOf(
                RecurrenceType.DAILY to "Ogni giorno",
                RecurrenceType.WEEKDAYS to "Giorni della settimana",
                RecurrenceType.EVERY_N_DAYS to "Ogni N giorni",
                RecurrenceType.MONTHLY_DAYS to "Giorni del mese"
            ).forEach { (type, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelected(type); expanded = false })
            }
        }
    }
}

@Composable
private fun WeekdayPicker(selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val days = listOf(1 to "L", 2 to "M", 3 to "M", 4 to "G", 5 to "V", 6 to "S", 7 to "D")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEach { (value, label) ->
            FilterChip(
                modifier = Modifier.weight(1f),
                selected = selected.contains(value),
                onClick = { onChange(if (selected.contains(value)) selected - value else selected + value) },
                label = { Text(label) }
            )
        }
    }
}

private fun parseMonthlyDays(text: String): Set<Int> = text.split(",", ";", " ")
    .mapNotNull { it.trim().toIntOrNull() }
    .filter { it in 1..31 }
    .toSet()

private fun showTimePicker(context: Context, initial: String, onSelected: (String) -> Unit) {
    val parsed = runCatching { LocalTime.parse(initial, DateTimeFormatter.ofPattern("HH:mm")) }
        .getOrDefault(LocalTime.of(8, 0))
    TimePickerDialog(
        context,
        { _, hour, minute -> onSelected(String.format("%02d:%02d", hour, minute)) },
        parsed.hour,
        parsed.minute,
        true
    ).show()
}

private fun generateEquidistantTimes(firstTime: String, count: Int): List<String> {
    val safeCount = count.coerceIn(1, 8)
    val start = runCatching { LocalTime.parse(firstTime, DateTimeFormatter.ofPattern("HH:mm")) }
        .getOrDefault(LocalTime.of(8, 0))
    val startMinutes = start.hour * 60 + start.minute
    return (0 until safeCount).map { index ->
        val offset = kotlin.math.round(index * 1440.0 / safeCount).toInt()
        val totalMinutes = (startMinutes + offset) % 1440
        String.format("%02d:%02d", totalMinutes / 60, totalMinutes % 60)
    }
}

private fun millisToTime(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .toLocalTime()
    .format(DateTimeFormatter.ofPattern("HH:mm"))
