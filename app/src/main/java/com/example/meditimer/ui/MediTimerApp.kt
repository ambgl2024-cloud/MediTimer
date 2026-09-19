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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlin.math.max

private enum class AppTab(val label: String) {
    TODAY("Oggi"), MEDS("Farmaci"), ALARMS("Sveglie"), PACKAGES("Confezioni"), CALENDAR("Calendario")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediTimerApp(requestExactAlarmPermission: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { MedicationRepository(context) }
    var tab by remember { mutableStateOf(AppTab.TODAY) }
    var revision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Medication?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    fun refresh() { revision++ }
    val meds = remember(revision) { repo.getMedications() }

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
                    val compactNavigation = maxWidth < 390.dp
                    val navIconSize = if (compactNavigation) 19.dp else 22.dp
                    val navLabelSize = if (compactNavigation) 9.sp else 10.sp

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
            }
        }
    }

    if (showInfo) {
        AboutDialog(onDismiss = { showInfo = false })
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
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
            .ifBlank { "sconosciuta" }
    }

    val changelog = remember {
        listOf(
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } }
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
                when (med.stockCount) {
                    0 -> add("${med.name}: scorta esaurita")
                    1 -> add("${med.name}: ultima confezione in scorta — pianifica l'acquisto")
                    else -> Unit
                }
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
}

@Composable
private fun MedicationListScreen(
    meds: List<Medication>,
    onAdd: () -> Unit,
    onEdit: (Medication) -> Unit,
    onDelete: (Medication) -> Unit
) {
    ScreenColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Farmaci", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            FilledIconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Aggiungi") }
        }
        if (meds.isEmpty()) Text("Aggiungi il primo farmaco per creare il piano di assunzione.")
        meds.forEach { med ->
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(med.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (med.doseNote.isNotBlank()) Text(med.doseNote)
                        }
                        IconButton(onClick = { onEdit(med) }) { Icon(Icons.Default.Edit, "Modifica") }
                        IconButton(onClick = { onDelete(med) }) { Icon(Icons.Default.Delete, "Elimina") }
                    }
                    Text(med.recurrenceLabel())
                    Text("${med.timesPerActiveDay} assunzion${if (med.timesPerActiveDay == 1) "e" else "i"}/giorno · ${med.alarmTimes.joinToString(" · ")}")
                    Text(
                        if (med.packageDurationMode == PackageDurationMode.DAYS)
                            "Confezione: max ${med.packageMaxDays} giorni"
                        else
                            "Confezione: max ${med.packageMaxIntakes} assunzioni"
                    )
                    Text("Scorta: ${med.stockCount?.let { "$it confezion${if (it == 1) "e" else "i"}" } ?: "da impostare"}")
                    Text("Snooze: ${med.snoozeMinutes}min")
                    if (med.countdownEnabled) Text("Countdown post-assunzione: ${med.countdownMinutes}min")
                    if (!med.enabled) Text("Sospeso", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AlarmListScreen(meds: List<Medication>, onEdit: (Medication) -> Unit) {
    ScreenColumn {
        Text("Sveglie", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Gli orari sono collegati alla regola di ricorrenza del farmaco.")
        meds.filter { it.enabled }.forEach { med ->
            Card(onClick = { onEdit(med) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(med.name, fontWeight = FontWeight.Bold)
                    Text(med.recurrenceLabel())
                    med.alarmTimes.forEachIndexed { i, time -> Text("${i + 1}. $time") }
                }
            }
        }
        if (meds.none { it.enabled }) Text("Nessuna sveglia attiva.")
    }
}

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
            importMessage = result.exceptionOrNull()?.let { "Import non riuscito: ${it.message ?: "CSV non valido"}" }
        }
    }

    ScreenColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Calendario", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Storico delle assunzioni effettive", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("text/csv", "text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) },
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
                    IconButton(onClick = { importMessage = null }) { Icon(Icons.Default.Close, "Chiudi") }
                }
            }
        }

        Text("Tocca un evento per modificarlo o cancellarlo.", style = MaterialTheme.typography.bodySmall)

        if (intakes.isEmpty()) {
            Text("Nessuna assunzione registrata.")
        } else {
            val grouped = intakes.groupBy {
                Instant.ofEpochMilli(it.takenAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            }.toList().sortedByDescending { it.first }

            grouped.forEach { (date, events) ->
                Text(date.itDate(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                events.sortedBy { it.takenAtMillis }.forEach { event ->
                    val taken = Instant.ofEpochMilli(event.takenAtMillis).atZone(ZoneId.systemDefault())
                    val name = event.medicationName.ifBlank { medNames[event.medicationId] ?: "Farmaco eliminato" }
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
                    if (preview.duplicateRowsInFile > 0) Text("Duplicati interni al file: ${preview.duplicateRowsInFile}")
                    if (preview.invalidRows > 0) Text("Righe non valide ignorate: ${preview.invalidRows}")
                    Divider()
                    Text(
                        "Aggiungi mantiene lo storico attuale e inserisce solo gli eventi mancanti. Sostituisci elimina lo storico attuale e usa il contenuto del CSV.",
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
                    TextButton(onClick = { pendingImport = null }) { Text("Annulla") }
                }
            }
        )
    }

    if (confirmReplace && pendingImport != null) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Sostituire tutto lo storico?") },
            text = { Text("Lo storico presente sul telefono verrà cancellato e sostituito con gli eventi validi del CSV. Questa operazione non modifica Farmaci o Sveglie.") },
            confirmButton = {
                Button(onClick = {
                    val events = pendingImport?.events.orEmpty()
                    repo.replaceImportedIntakes(events)
                    Scheduler.scheduleAllPackageReminders(context)
                    confirmReplace = false
                    pendingImport = null
                    importMessage = "Storico sostituito: ${events.size} eventi caricati dal CSV."
                    refresh()
                }) { Text("Sì, sostituisci") }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Annulla") } }
        )
    }

    editingEvent?.let { event ->
        HistoryEventDialog(
            event = event,
            currentMedicationName = event.medicationName.ifBlank { medNames[event.medicationId].orEmpty() },
            onDismiss = { editingEvent = null },
            onSave = { updated ->
                repo.getCountdownsForIntake(event.medicationId, event.plannedEpochDay, event.plannedTime).forEach { countdown ->
                    Scheduler.cancelCountdown(context, countdown)
                    repo.removeCountdown(countdown.id)
                }
                repo.updateIntake(updated)
                Scheduler.scheduleAllPackageReminders(context)
                editingEvent = null
                refresh()
            },
            onDelete = {
                repo.getCountdownsForIntake(event.medicationId, event.plannedEpochDay, event.plannedTime).forEach { countdown ->
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
private fun PackageScreen(meds: List<Medication>, repo: MedicationRepository, refresh: () -> Unit) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var stockAction by remember { mutableStateOf<StockAction?>(null) }

    val warnings = meds.flatMap { med ->
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
            0 -> items += "${med.name}: scorta esaurita — acquisto necessario"
            1 -> items += "${med.name}: ultima confezione in scorta — pianifica l'acquisto"
        }
        items
    }

    ScreenColumn {
        Text("Confezioni", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

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

        meds.forEach { med ->
            val last = med.lastPackageChangeEpochDay?.let(LocalDate::ofEpochDay)
            val dayRemaining = if (med.packageDurationMode == PackageDurationMode.DAYS) {
                last?.plusDays(med.packageMaxDays.toLong())?.toEpochDay()?.minus(today.toEpochDay())
            } else null
            val intakeRemaining = if (med.packageDurationMode == PackageDurationMode.INTAKES) repo.getPackageIntakesRemaining(med) else null

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(med.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        when (med.stockCount) {
                            null -> AssistChip(onClick = {}, label = { Text("Scorta da impostare") })
                            0 -> AssistChip(onClick = {}, label = { Text("Scorta esaurita") })
                            1 -> AssistChip(onClick = {}, label = { Text("Ultima confezione") })
                            else -> Unit
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                            Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(last?.itDate() ?: "—", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Text("Ultimo cambio", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Surface(modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                            Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                val progressRemaining = intakeRemaining?.toLong() ?: dayRemaining
                                Text(
                                    when {
                                        progressRemaining == null -> "—"
                                        progressRemaining >= 0L -> progressRemaining.toString()
                                        else -> "−${-progressRemaining}"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if ((progressRemaining ?: 99L) <= 7L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    when {
                                        med.packageDurationMode == PackageDurationMode.INTAKES && (progressRemaining ?: 0L) < 0L -> "Assunzioni oltre"
                                        med.packageDurationMode == PackageDurationMode.INTAKES -> "Assunzioni rimaste"
                                        (progressRemaining ?: 0L) < 0L -> "Giorni oltre"
                                        else -> "Giorni mancanti"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Surface(modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                            Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    med.stockCount?.toString() ?: "—",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if ((med.stockCount ?: 99) <= 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                                Text("Confezioni rimaste", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { registerPackageChange(context, repo, med, today, refresh) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Cambiata oggi")
                        }
                        OutlinedButton(
                            onClick = {
                                showDatePicker(context, last ?: today) { selected ->
                                    updatePackageOpeningDate(context, repo, med, selected, refresh)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Modifica data")
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { stockAction = StockAction(med, StockMode.SET) }, modifier = Modifier.weight(1f)) {
                            Text("Imposta scorta")
                        }
                        OutlinedButton(onClick = { stockAction = StockAction(med, StockMode.ADD) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Acquisto")
                        }
                    }

                    OutlinedButton(
                        onClick = { stockAction = StockAction(med, StockMode.REMOVE) },
                        enabled = (med.stockCount ?: 0) > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Scarto")
                    }

                    Text(
                        "La scorta comprende solo le confezioni chiuse; quella in uso non è conteggiata.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (meds.isEmpty()) Text("Nessun farmaco configurato.")
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
                if (newStock <= 1) {
                    NotificationHelper.showLowStockWarning(context, updated, newStock)
                    repo.markStockReminderShown(updated.id, LocalDate.now().toEpochDay())
                    Scheduler.scheduleNextStockReminder(context, updated)
                } else {
                    NotificationHelper.cancelLowStockWarning(context, updated.id)
                    repo.clearStockReminderState(updated.id)
                    Scheduler.cancelStockReminder(context, updated.id)
                }
                stockAction = null
                refresh()
            }
        )
    }
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
        stockCount = newStock
    )
    repo.upsertMedication(updated)
    repo.clearPackageReminderState(medication.id)
    Scheduler.scheduleNextPackageReminder(context, updated)
    if (newStock != null && newStock <= 1) {
        NotificationHelper.showLowStockWarning(context, updated, newStock)
        repo.markStockReminderShown(updated.id, LocalDate.now().toEpochDay())
        Scheduler.scheduleNextStockReminder(context, updated)
    } else if (newStock != null) {
        NotificationHelper.cancelLowStockWarning(context, updated.id)
        repo.clearStockReminderState(updated.id)
        Scheduler.cancelStockReminder(context, updated.id)
    }
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
        lastPackageChangeMillis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    )
    repo.upsertMedication(updated)

    // Correzione della data registrata: la scorta non viene modificata.
    repo.clearPackageReminderState(updated.id)
    Scheduler.scheduleNextPackageReminder(context, updated)
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
                            lastPackageChangeEpochDay = initial?.lastPackageChangeEpochDay,
                            lastPackageChangeMillis = initial?.lastPackageChangeMillis,
                            stockCount = initial?.stockCount,
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
