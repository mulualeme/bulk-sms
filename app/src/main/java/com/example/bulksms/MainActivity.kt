package com.example.bulksms

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.telephony.SmsMessage
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        Repo.init(this)
        setContent { AppTheme { App() } }
    }
}

sealed interface Screen {
    data object Home : Screen
    data object New : Screen
    data class Detail(val id: Long) : Screen
}

@Composable
fun App() {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    fun push(s: Screen) { stack = stack + s }
    fun pop() { stack = stack.dropLast(1) }
    BackHandler(stack.size > 1) { pop() }
    when (val s = stack.last()) {
        Screen.Home -> HomeScreen(onNew = { push(Screen.New) }, onOpen = { push(Screen.Detail(it.id)) })
        Screen.New -> NewCampaignScreen(onBack = ::pop) { c -> stack = listOf(Screen.Home, Screen.Detail(c.id)) }
        is Screen.Detail -> Repo.get(s.id)?.let { DetailScreen(it, ::pop) } ?: LaunchedEffect(Unit) { pop() }
    }
}

// ---------------- Home ----------------

private const val ABOUT = "Send a personalised SMS to a list of people from your phone's SIM.\n\n" +
    "1. Import an Excel (.xlsx), CSV or text file with phone numbers and names.\n" +
    "2. Write the message. Tap a column such as {First Name} to insert each person's own value.\n" +
    "3. Pick the SIM and the pause between messages, then send.\n\n" +
    "Messages are sent by your SIM at your carrier's normal SMS rates. Only message people who agreed to hear from you."

@Composable
fun HomeScreen(onNew: () -> Unit, onOpen: (Campaign) -> Unit) {
    var about by remember { mutableStateOf(false) }
    if (about) InfoDialog("About $APP_NAME", ABOUT) { about = false }
    val list = Repo.campaigns
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column {
            AppBar(APP_NAME, centered = true, icon = Icons.Outlined.Info, desc = "About") { about = true }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Stat("Messages Sent", list.sumOf { it.count(Status.Sent) }, Modifier.weight(1f))
                    Stat("Campaigns", list.size, Modifier.weight(1f))
                }
                if (list.isEmpty()) T("No campaigns yet.\nTap New Campaign to import your contacts.", 17.sp, Grey,
                    modifier = Modifier.fillMaxWidth().padding(top = 120.dp).wrapContentWidth())
                list.forEach { c -> CampaignCard(c) { onOpen(c) } }
            }
        }
        GreenButton("New Campaign", onNew, Modifier.align(Alignment.BottomEnd).padding(20.dp), icon = Icons.Default.Add)
    }
}

@Composable
fun Stat(label: String, value: Int, modifier: Modifier) = WhiteCard(modifier) {
    Column(Modifier.padding(16.dp)) { T(label, 15.sp, Label); T("$value", 30.sp, Green, FontWeight.SemiBold, Modifier.padding(top = 6.dp)) }
}

fun State.color() = when (this) { State.Running -> Green; State.Paused, State.Stopped -> Orange; State.Done -> OkGreen }

@Composable
fun CampaignCard(c: Campaign, onClick: () -> Unit) = WhiteCard(Modifier.clickable(onClick = onClick)) {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(c.name, 20.sp, modifier = Modifier.weight(1f), maxLines = 1)
            T(c.state.label, 14.sp, c.state.color(), FontWeight.SemiBold)
        }
        Progress(c, Modifier.padding(vertical = 10.dp))
        T("${c.count(Status.Sent)} of ${c.recipients.size} sent" + c.count(Status.Failed).let { if (it > 0) " · $it failed" else "" }, 15.sp, Label)
    }
}

@Composable
fun Progress(c: Campaign, modifier: Modifier = Modifier) = LinearProgressIndicator(
    { if (c.recipients.isEmpty()) 0f else (c.recipients.size - c.count(Status.Pending)) / c.recipients.size.toFloat() },
    modifier.fillMaxWidth().height(6.dp), color = Green, trackColor = Divider, gapSize = 0.dp, drawStopIndicator = {})

// ---------------- New campaign ----------------

private fun fileName(ctx: android.content.Context, uri: Uri) =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "contacts"

fun smsCount(text: String) = SmsMessage.calculateLength(text, false)[0]

@Composable
fun NewCampaignScreen(onBack: () -> Unit, onStarted: (Campaign) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var help by remember { mutableStateOf<Pair<String, String>?>(null) }
    help?.let { (a, b) -> InfoDialog(a, b) { help = null } }
    BackHandler { if (step > 0) step-- else onBack() }

    // recipients
    var file by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var header by remember { mutableStateOf(true) }
    var phoneCol by remember { mutableIntStateOf(0) }
    var nameCol by remember { mutableIntStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    val table = remember(rows, header) { toTable(rows, header) }
    val (people, skipped) = remember(table, phoneCol, nameCol) { if (rows.isEmpty()) emptyList<Recipient>() to 0 else recipients(table, phoneCol, nameCol) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val name = fileName(ctx, uri)
            runCatching { withContext(Dispatchers.IO) { readRows(name, ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }) } }
                .onSuccess { r ->
                    if (r.isEmpty()) { error = "$name has no rows."; return@onSuccess }
                    error = null; file = name; rows = r
                    header = r.first().none(::looksLikePhone)
                    val t = toTable(r, header); phoneCol = guessPhoneColumn(t); nameCol = guessNameColumn(t, phoneCol)
                }
                .onFailure { error = "Could not read $name: ${it.message}" }
        }
    }

    // message
    var template by remember { mutableStateOf("Hi {First Name}, ") }
    var optOut by remember { mutableStateOf(true) }
    var optOutText by remember { mutableStateOf("Reply STOP to opt out") }
    var previewAt by remember { mutableIntStateOf(0) }
    val full = template.trim() + if (optOut && optOutText.isNotBlank()) "\n" + optOutText.trim() else ""

    // send
    var name by remember { mutableStateOf("") }
    var sims by remember { mutableStateOf(sims(ctx)) }
    var subId by remember { mutableIntStateOf(-1) }
    var delay by remember { mutableIntStateOf(5) }
    var confirm by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        sims = sims(ctx)
        if (r[Manifest.permission.SEND_SMS] == false) denied = true
    }
    fun askPerms() = perms.launch(buildList {
        add(Manifest.permission.SEND_SMS); add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())
    if (denied) InfoDialog("Permission needed", "$APP_NAME needs the SMS permission to send messages from your SIM. Allow it in Settings → Apps → $APP_NAME → Permissions.") { denied = false }

    val totalSms = remember(full, people) { people.sumOf { smsCount(fill(full, it.fields)) } }
    if (confirm) ConfirmDialog("Start sending?",
        "${people.size} messages ($totalSms SMS) will be sent from ${sims.firstOrNull { it.subId == subId }?.label ?: "your default SIM"}, one every $delay seconds. Your carrier's SMS charges apply.",
        "Cancel", "Send", { confirm = false }) {
        confirm = false
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) { askPerms(); return@ConfirmDialog }
        val c = Campaign(System.currentTimeMillis(), name.ifBlank { file?.substringBeforeLast('.') ?: "Campaign" }, full, people, subId, delay)
        SendService.start(ctx, c)
        onStarted(c)
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        AppBar("New Campaign", onNav = { if (step > 0) step-- else onBack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            when (step) {
                0 -> {
                    Heading("Recipients", 22.5.sp, FontWeight.SemiBold) { help = "Recipients" to "Import a file with one person per row:\n\n• Excel workbook (.xlsx): the first sheet is used\n• CSV, TSV or TXT: comma, semicolon or tab separated\n\nInclude a column of phone numbers and, optionally, names and any other details you want in the message. Numbers saved in Excel as numbers lose a leading 0, so save them as text or with the country code (+251…).\n\nInvalid and duplicate numbers are skipped." }
                    T("Import an Excel, CSV or text file with a phone number column and, optionally, a name column.", 15.sp, Label, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                    GreenButton(if (file == null) "Import File" else "Choose Another File", { pick.launch(arrayOf("*/*")) }, icon = Icons.Default.UploadFile)
                    error?.let { T(it, 15.sp, Color.Red, modifier = Modifier.padding(top = 12.dp)) }
                    if (file != null) {
                        T("$file · ${table.rows.size} rows", 16.sp, modifier = Modifier.padding(top = 16.dp))
                        CheckRow("First row has column names", header) { header = it; val t = toTable(rows, it); phoneCol = guessPhoneColumn(t); nameCol = guessNameColumn(t, phoneCol) }
                        T("Phone number column", 17.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                        DropBox(phoneCol, table.headers.indices.toList(), { table.headers[it] }, { phoneCol = it }, desc = "Phone column")
                        T("Name column", 17.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                        DropBox(nameCol, listOf(-1) + table.headers.indices.filter { it != phoneCol }, { if (it < 0) "None" else table.headers[it] }, { nameCol = it }, desc = "Name column")
                        Section("Summary")
                        T("${people.size} recipients" + if (skipped > 0) " · $skipped skipped (invalid or duplicate numbers)" else "", 17.sp,
                            if (people.isEmpty()) Color.Red else Color.Black, FontWeight.Medium)
                        people.take(5).forEach { p ->
                            Row(Modifier.padding(top = 8.dp)) {
                                T(p.fields[NAME]?.takeIf { it.isNotBlank() } ?: "—", 16.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                T(p.phone, 16.sp, Label)
                            }
                        }
                        if (people.size > 5) T("and ${people.size - 5} more", 15.sp, Grey, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                1 -> {
                    Heading("Message", 22.5.sp, FontWeight.SemiBold) { help = "Message" to "Words in braces are replaced for each person. {First Name} becomes \"Abebe\" for Abebe Kebede. Every column in your file can be used the same way, for example {City}.\n\nIf a person's value is empty, nothing is inserted." }
                    Field(template, { template = it }, "Type your message", Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = false, minHeight = 150.dp)
                    T("Tap to insert", 15.sp, Label, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (if (nameCol >= 0) listOf(FIRST_NAME, NAME) else emptyList()).plus(table.headers).distinct().forEach { h ->
                            TemplateChip(h, true) { template = template.trimEnd() + (if (template.isBlank()) "" else " ") + "{$h} " }
                        }
                    }
                    CheckRow("Add opt-out text", optOut) { optOut = it }
                    if (optOut) Field(optOutText, { optOutText = it }, "Reply STOP to opt out", Modifier.fillMaxWidth())
                    people.getOrNull(previewAt.coerceIn(0, (people.size - 1).coerceAtLeast(0)))?.let { p ->
                        val text = fill(full, p.fields)
                        Column(Modifier.padding(top = 20.dp).fillMaxWidth().border(1.5.dp, GreenLight, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                T("Preview: ${p.fields[NAME]?.takeIf { it.isNotBlank() } ?: p.phone}", 17.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous person", tint = Green, modifier = Modifier.size(34.dp).clickable { if (previewAt > 0) previewAt-- })
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next person", tint = Green, modifier = Modifier.size(34.dp).clickable { if (previewAt < people.size - 1) previewAt++ })
                            }
                            T(text, 16.sp, modifier = Modifier.padding(top = 6.dp))
                            T("${text.length} characters · ${smsCount(text)} SMS", 14.sp, Grey, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                else -> {
                    LaunchedEffect(Unit) { if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) askPerms() }
                    T("Campaign Name", 17.sp)
                    Field(name, { name = it }, file?.substringBeforeLast('.') ?: "Campaign", Modifier.fillMaxWidth().padding(top = 6.dp))
                    T("Send Using", 17.sp, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
                    DropBox(subId, listOf(-1) + sims.map { it.subId }, { id -> sims.firstOrNull { it.subId == id }?.label ?: "Default SIM" }, { subId = it }, desc = "SIM")
                    Row(Modifier.padding(top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        T("Pause Between Messages", 17.sp)
                        HelpIcon { help = "Pause Between Messages" to "Sending slowly keeps your carrier from flagging the SIM for spam. 5 seconds or more is recommended for large lists." }
                    }
                    DropBox(delay, listOf(3, 5, 10, 20, 30, 60), { "$it seconds" }, { delay = it }, desc = "Pause")
                    Section("Summary")
                    T("${people.size} messages · $totalSms SMS\nAbout ${minutesText(people.size * delay)} to send", 17.sp)
                    T("Android asks for confirmation after every 30 messages an app sends. Choose \"Always allow\" in that prompt so sending is not interrupted.",
                        15.sp, Warn, modifier = Modifier.padding(top = 16.dp))
                    T("Only message people who agreed to hear from you. In many countries advertising SMS requires consent and an opt-out.",
                        15.sp, Label, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        WizardBar(step, 3, { step-- }, last = "Send") {
            when {
                step == 0 && people.isEmpty() -> error = if (file == null) "Import a file first." else "No valid phone numbers in the selected column."
                step == 1 && template.isBlank() -> {}
                step < 2 -> step++
                else -> confirm = true
            }
        }
    }
}

fun minutesText(sec: Int) = if (sec < 90) "$sec seconds" else if (sec < 5400) "${(sec + 30) / 60} minutes" else "%.1f hours".format(sec / 3600.0)

// ---------------- Campaign detail ----------------

@Composable
fun DetailScreen(c: Campaign, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var del by remember { mutableStateOf(false) }
    if (del) ConfirmDialog("Delete Campaign", "Delete ${c.name} and its sending report?", "Cancel", "Delete", { del = false }) { del = false; Repo.delete(c); onBack() }
    val running = c.state == State.Running
    Column(Modifier.fillMaxSize().background(Bg)) {
        AppBar(c.name, onNav = onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                WhiteCard {
                    Column(Modifier.padding(16.dp)) {
                        T(c.state.label, 17.sp, c.state.color(), FontWeight.SemiBold)
                        Progress(c, Modifier.padding(vertical = 12.dp))
                        Row {
                            Count("Sent", c.count(Status.Sent), OkGreen, Modifier.weight(1f))
                            Count("Failed", c.count(Status.Failed), ErrRed, Modifier.weight(1f))
                            Count("Pending", c.count(Status.Pending), Grey, Modifier.weight(1f))
                        }
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (running) LightButton("Pause", { Repo.save(c.copy(state = State.Paused)) })
                            else if (!c.finished) GreenButton("Resume", { SendService.start(ctx, c) })
                            if (!running && c.count(Status.Failed) > 0) GreenButton("Retry Failed", {
                                SendService.start(ctx, c.copy(recipients = c.recipients.map { if (it.status == Status.Failed) it.copy(status = Status.Pending, error = null) else it }))
                            })
                            if (!running) LightButton("Delete", { del = true })
                        }
                    }
                }
            }
            item {
                WhiteCard {
                    Column(Modifier.padding(16.dp)) { T("Message", 15.sp, Label); T(c.template, 16.sp, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
            item { T("Recipients", 15.sp, Label, modifier = Modifier.padding(top = 8.dp)) }
            itemsIndexed(c.recipients) { i, r ->
                Column {
                    if (i > 0) HorizontalDivider(color = Divider)
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            T(r.fields[NAME]?.takeIf { it.isNotBlank() } ?: r.phone, 16.sp, maxLines = 1)
                            if (r.fields[NAME].isNullOrBlank().not()) T(r.phone, 14.sp, Label)
                            r.error?.let { T(it, 14.sp, ErrRed) }
                        }
                        T(r.status.label, 15.sp, when (r.status) { Status.Sent -> OkGreen; Status.Failed -> ErrRed; Status.Pending -> Grey }, FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun Count(label: String, n: Int, color: Color, modifier: Modifier) = Column(modifier) { T("$n", 24.sp, color, FontWeight.SemiBold); T(label, 14.sp, Label) }
