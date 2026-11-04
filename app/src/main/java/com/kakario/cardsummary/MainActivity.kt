package com.kakario.cardsummary

import android.Manifest
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private val Navy = Color(0xFF1F4E79)
private val Red = Color(0xFFC0392B)
private val Gray = Color(0xFF777777)
private val Line = Color(0xFFE6E6E6)
private val Soft = Color(0xFFF4F7FA)

enum class Screen { HOME, SETTINGS, REVIEW, DONE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Navy, background = Color.White, surface = Color.White
                )
            ) {
                Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding(), color = Color.White) {
                    App()
                }
            }
        }
    }
}

/* ---------- 기간 ---------- */

fun monthRange(offset: Int): Pair<Long, Long> {
    val c = Calendar.getInstance()
    c.add(Calendar.MONTH, offset)
    c.set(Calendar.DAY_OF_MONTH, 1)
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    val from = c.timeInMillis
    c.add(Calendar.MONTH, 1)
    return from to (c.timeInMillis - 1)
}

fun startOfDay(t: Long): Long {
    val c = Calendar.getInstance(); c.timeInMillis = t
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

fun endOfDay(t: Long) = startOfDay(t) + 24L * 3600 * 1000 - 1

fun pickDate(ctx: Context, init: Long, onPick: (Long) -> Unit) {
    val c = Calendar.getInstance(); c.timeInMillis = init
    DatePickerDialog(ctx, { _, y, m, d ->
        val r = Calendar.getInstance(); r.clear(); r.set(y, m, d)
        onPick(r.timeInMillis)
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

/* ---------- 앱 ---------- */

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(Screen.HOME) }
    var cards by remember { mutableStateOf(Store.load(ctx)) }
    var mode by remember { mutableIntStateOf(0) }          // 0 이번 달, 1 지난달, 2 직접
    var from by remember { mutableLongStateOf(monthRange(0).first) }
    var to by remember { mutableLongStateOf(monthRange(0).second) }
    val txns = remember { mutableStateListOf<Txn>() }
    var loading by remember { mutableStateOf(false) }
    var lastSummary by remember { mutableStateOf(Store.lastSummary(ctx)) }
    var savedNote by remember { mutableStateOf("") }
    var pendingLoad by remember { mutableStateOf(false) }

    fun hasPerm() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun load() {
        loading = true
        scope.launch {
            val list = try {
                withContext(Dispatchers.IO) { SmsReader.collect(ctx, cards, from, to) }
            } catch (e: Exception) {
                Toast.makeText(ctx, "문자를 읽지 못했어요: ${e.message}", Toast.LENGTH_LONG).show()
                emptyList()
            }
            txns.clear(); txns.addAll(list)
            loading = false
            screen = Screen.REVIEW
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val go = pendingLoad
        pendingLoad = false
        if (ok) { if (go) load() } else Toast.makeText(ctx, "문자 읽기 권한이 있어야 정리할 수 있어요", Toast.LENGTH_LONG).show()
    }

    BackHandler(enabled = screen != Screen.HOME) {
        screen = if (screen == Screen.DONE) Screen.REVIEW else Screen.HOME
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            cards = cards, mode = mode, from = from, to = to, loading = loading,
            lastSummary = lastSummary,
            onMode = { m ->
                mode = m
                if (m < 2) { val r = monthRange(-m); from = r.first; to = r.second }
            },
            onFrom = { from = startOfDay(it); mode = 2 },
            onTo = { to = endOfDay(it); mode = 2 },
            onStart = {
                if (hasPerm()) load()
                else { pendingLoad = true; permLauncher.launch(Manifest.permission.READ_SMS) }
            },
            onSettings = { screen = Screen.SETTINGS },
        )

        Screen.SETTINGS -> SettingsScreen(
            cards = cards,
            hasPerm = { hasPerm() },
            askPerm = { pendingLoad = false; permLauncher.launch(Manifest.permission.READ_SMS) },
            onSave = { cards = it; Store.save(ctx, it); screen = Screen.HOME },
            onBack = { screen = Screen.HOME },
        )

        Screen.REVIEW -> ReviewScreen(
            cards = cards, txns = txns, from = from, to = to,
            onBack = { screen = Screen.HOME },
            onConfirm = {
                val notes = ArrayList<String>()
                cards.forEachIndexed { i, c ->
                    val text = Exporter.memoText(c, txns.filter { it.card == i }, from, to)
                    val name = Exporter.fileName(c, from, to)
                    val uri = try { Exporter.saveToDownloads(ctx, name, text) } catch (_: Exception) { null }
                    notes.add(if (uri != null) "다운로드/카드정리/$name" else "${c.name} 저장 실패")
                }
                savedNote = notes.joinToString("\n")
                val total = txns.filter { it.include }.sumOf { it.signed }
                lastSummary = "${dayText(from)} ~ ${dayText(to)}  총 ${won(total)}"
                Store.saveLastSummary(ctx, lastSummary)
                screen = Screen.DONE
            },
        )

        Screen.DONE -> DoneScreen(
            cards = cards, txns = txns, from = from, to = to, savedNote = savedNote,
            onShare = { i ->
                val text = Exporter.memoText(cards[i], txns.filter { it.card == i }, from, to)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, Exporter.fileName(cards[i], from, to))
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                ctx.startActivity(Intent.createChooser(send, "${cards[i].name} 메모 보내기"))
            },
            onReview = { screen = Screen.REVIEW },
            onHome = { screen = Screen.HOME },
        )
    }
}

/* ---------- 공통 ---------- */

@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, right: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) TextButton(onClick = onBack) { Text("〈 뒤로", color = Navy) }
        else Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        right()
    }
    HorizontalDivider(color = Line)
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Navy else Soft, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) { Text(text, color = if (selected) Color.White else Color.Black, fontSize = 15.sp) }
}

/* ---------- 홈 ---------- */

@Composable
fun HomeScreen(
    cards: List<CardConf>, mode: Int, from: Long, to: Long, loading: Boolean, lastSummary: String,
    onMode: (Int) -> Unit, onFrom: (Long) -> Unit, onTo: (Long) -> Unit,
    onStart: () -> Unit, onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TopBar("카드 정리") { TextButton(onClick = onSettings) { Text("번호 설정", color = Navy) } }
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Text("기간", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("이번 달", mode == 0) { onMode(0) }
                Chip("지난달", mode == 1) { onMode(1) }
                Chip("직접 지정", mode == 2) { onMode(2) }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickDate(ctx, from, onFrom) }) { Text(dayText(from)) }
                Text("  ~  ")
                OutlinedButton(onClick = { pickDate(ctx, to, onTo) }) { Text(dayText(to)) }
            }

            Spacer(Modifier.height(24.dp))
            Text("등록된 카드", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            cards.forEach { c ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .background(Soft, RoundedCornerShape(10.dp)).padding(14.dp)
                ) {
                    Text(c.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (c.numbers.isEmpty()) "문자 번호 없음 — 번호 설정에서 등록해 주세요"
                        else "문자 번호: " + c.numbers.joinToString(", "),
                        color = Gray, fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onStart, enabled = !loading,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("문자 불러와서 검토하기", fontSize = 17.sp)
            }

            if (lastSummary.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Text("지난 정리", color = Gray, fontSize = 13.sp)
                Text(lastSummary, fontSize = 15.sp)
            }
        }
    }
}

/* ---------- 설정 ---------- */

@Composable
fun SettingsScreen(
    cards: List<CardConf>, hasPerm: () -> Boolean, askPerm: () -> Unit,
    onSave: (List<CardConf>) -> Unit, onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val names = remember { mutableStateListOf(*cards.map { it.name }.toTypedArray()) }
    val kws = remember { mutableStateListOf(*cards.map { it.keyword }.toTypedArray()) }
    val nums = remember { mutableStateListOf(*cards.map { it.numbers.joinToString(", ") }.toTypedArray()) }
    var found by remember { mutableStateOf<Pair<Int, List<Pair<String, Int>>>?>(null) }

    fun build() = names.indices.map { i ->
        CardConf(names[i], kws[i], nums[i].split(",").map { digits(it) }.filter { it.isNotEmpty() })
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("번호 설정", onBack) { TextButton(onClick = { onSave(build()) }) { Text("저장", color = Navy) } }
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Text(
                "카드사 결제 문자가 오는 번호를 적어 두면 그 번호 문자만 골라 정리해요. " +
                        "번호를 모르면 '최근 문자에서 찾기'를 눌러 보세요.",
                color = Gray, fontSize = 14.sp
            )
            for (i in names.indices) {
                Spacer(Modifier.height(20.dp))
                Text("카드 ${i + 1}", fontWeight = FontWeight.Bold)
                OutlinedTextField(names[i], { names[i] = it }, label = { Text("카드 이름") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(nums[i], { nums[i] = it }, label = { Text("문자 번호 (여러 개는 쉼표로)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(kws[i], { kws[i] = it }, label = { Text("번호 찾기용 문자 속 단어") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = {
                    if (!hasPerm()) {
                        askPerm()
                    } else {
                        val kw = kws[i]
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                try { SmsReader.findSenders(ctx, kw) } catch (_: Exception) { emptyList() }
                            }
                            found = i to r
                        }
                    }
                }) { Text("최근 문자에서 찾기") }
            }
        }
    }

    found?.let { (i, list) ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text("${names[i]} 번호 후보") },
            text = {
                if (list.isEmpty()) Text("최근 90일 문자에서 '${kws[i]}' 결제 문자를 못 찾았어요.")
                else Column {
                    Text("눌러서 추가하세요", color = Gray, fontSize = 13.sp)
                    list.take(8).forEach { (n, cnt) ->
                        Text(
                            "$n  (${cnt}건)",
                            fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth().clickable {
                                val cur = nums[i].split(",").map { digits(it) }.filter { it.isNotEmpty() }
                                if (n !in cur) nums[i] = (cur + n).joinToString(", ")
                                found = null
                            }.padding(vertical = 10.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { found = null }) { Text("닫기") } }
        )
    }
}

/* ---------- 검토 ---------- */

@Composable
fun ReviewScreen(
    cards: List<CardConf>, txns: MutableList<Txn>, from: Long, to: Long,
    onBack: () -> Unit, onConfirm: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Txn?>(null) }
    var adding by remember { mutableStateOf(false) }

    fun sumOf(card: Int) = txns.filter { it.card == card && it.include }.sumOf { it.signed }

    Column(Modifier.fillMaxSize()) {
        TopBar("검토", onBack)
        Text("${dayText(from)} ~ ${dayText(to)}", color = Gray, fontSize = 13.sp,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp))

        TabRow(selectedTabIndex = tab, containerColor = Color.White) {
            cards.forEachIndexed { i, c ->
                Tab(selected = tab == i, onClick = { tab = i }, text = {
                    Text("${c.name} ${txns.count { it.card == i }}건")
                })
            }
        }

        val list = txns.filter { it.card == tab }
        Box(Modifier.weight(1f)) {
            if (list.isEmpty()) {
                Text(
                    "이 기간에 읽힌 결제 문자가 없어요.\n번호 설정에서 문자 번호를 확인해 보세요.",
                    color = Gray, modifier = Modifier.padding(24.dp)
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { t ->
                    TxnRow(
                        t,
                        onToggle = { v ->
                            val idx = txns.indexOfFirst { it.id == t.id }
                            if (idx >= 0) txns[idx] = t.copy(include = v)
                        },
                        onClick = { editing = t }
                    )
                    HorizontalDivider(color = Line)
                }
                item {
                    TextButton(onClick = { adding = true }, modifier = Modifier.padding(12.dp)) {
                        Text("+ 직접 추가", color = Navy)
                    }
                }
            }
        }

        HorizontalDivider(color = Line)
        Column(Modifier.fillMaxWidth().background(Soft).padding(16.dp)) {
            cards.forEachIndexed { i, c ->
                Row {
                    Text(c.name, modifier = Modifier.weight(1f))
                    Text(won(sumOf(i)))
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("총합", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Text(won(cards.indices.sumOf { sumOf(it) }), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Navy)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onConfirm, modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text("확정하고 메모장 저장", fontSize = 16.sp) }
        }
    }

    editing?.let { t ->
        EditDialog(
            title = "내역 고치기", init = t,
            onDismiss = { editing = null },
            onDelete = {
                val idx = txns.indexOfFirst { it.id == t.id }
                if (idx >= 0) txns.removeAt(idx)
                editing = null
            },
            onSave = { n ->
                val idx = txns.indexOfFirst { it.id == t.id }
                if (idx >= 0) txns[idx] = n
                editing = null
            }
        )
    }
    if (adding) {
        val nextId = (txns.maxOfOrNull { it.id } ?: 0) + 1
        EditDialog(
            title = "${cards[tab].name} 직접 추가",
            init = Txn(nextId, tab, minOf(System.currentTimeMillis(), to), "", 0, false, "", true, "", true),
            onDismiss = { adding = false },
            onDelete = null,
            onSave = { n ->
                val sorted = (txns + n).sortedBy { it.time }
                txns.clear(); txns.addAll(sorted)
                adding = false
            }
        )
    }
}

@Composable
fun TxnRow(t: Txn, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = t.include, onCheckedChange = onToggle)
        Column(Modifier.weight(1f)) {
            Text(
                t.merchant.ifBlank { "(가맹점 없음)" },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (t.include) Color.Black else Gray, fontSize = 16.sp
            )
            val tags = buildList {
                add(rowTime(t.time))
                if (t.cancel) add("취소")
                if (t.installment.isNotBlank() && t.installment != "일시불") add(t.installment)
                if (!t.parsed) add("금액 확인 필요")
            }
            Text(tags.joinToString(" · "), color = if (!t.parsed) Red else Gray, fontSize = 13.sp)
        }
        Text(
            won(t.signed),
            color = when { !t.include -> Gray; t.cancel -> Red; else -> Color.Black },
            fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
            modifier = Modifier.padding(end = 8.dp)
        )
    }
}

@Composable
fun EditDialog(
    title: String, init: Txn,
    onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (Txn) -> Unit,
) {
    val ctx = LocalContext.current
    var merchant by remember { mutableStateOf(init.merchant) }
    var amount by remember { mutableStateOf(if (init.amount > 0) init.amount.toString() else "") }
    var cancel by remember { mutableStateOf(init.cancel) }
    var time by remember { mutableLongStateOf(init.time) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(merchant, { merchant = it }, label = { Text("가맹점") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(amount, { amount = digits(it) }, label = { Text("금액(원)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(cancel, { cancel = it }); Text("취소 건 (합계에서 빼기)")
                }
                OutlinedButton(onClick = {
                    pickDate(ctx, time) { d ->
                        val keep = Calendar.getInstance(); keep.timeInMillis = time
                        val c = Calendar.getInstance(); c.timeInMillis = d
                        c.set(Calendar.HOUR_OF_DAY, keep.get(Calendar.HOUR_OF_DAY))
                        c.set(Calendar.MINUTE, keep.get(Calendar.MINUTE))
                        time = c.timeInMillis
                    }
                }) { Text("날짜: ${dayText(time)}") }
                if (init.raw.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text("원본 문자", color = Gray, fontSize = 12.sp)
                    Text(
                        init.raw, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(8.dp)).padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = amount.toLongOrNull() ?: 0L
                onSave(init.copy(merchant = merchant.trim(), amount = a, cancel = cancel, time = time,
                    parsed = a > 0 || init.parsed, include = if (a > 0) true else init.include))
            }) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("삭제", color = Red) }
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        }
    )
}

/* ---------- 완료 ---------- */

@Composable
fun DoneScreen(
    cards: List<CardConf>, txns: List<Txn>, from: Long, to: Long, savedNote: String,
    onShare: (Int) -> Unit, onReview: () -> Unit, onHome: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar("정리 완료")
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Text("${dayText(from)} ~ ${dayText(to)}", color = Gray)
            Spacer(Modifier.height(16.dp))
            cards.forEachIndexed { i, c ->
                val mine = txns.filter { it.card == i && it.include }
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp)
                        .background(Soft, RoundedCornerShape(12.dp)).padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(won(mine.sumOf { it.signed }), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    }
                    Text("${mine.size}건", color = Gray, fontSize = 13.sp)
                    TextButton(onClick = { onShare(i) }, contentPadding = PaddingValues(0.dp)) {
                        Text("메모장으로 보내기", color = Navy)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("총금액", color = Gray)
            Text(
                won(txns.filter { it.include }.sumOf { it.signed }),
                fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Navy
            )
            Spacer(Modifier.height(18.dp))
            Text("저장된 파일", color = Gray, fontSize = 13.sp)
            Text(savedNote, fontSize = 13.sp)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onReview, modifier = Modifier.weight(1f)) { Text("다시 검토") }
                Button(onClick = onHome, modifier = Modifier.weight(1f)) { Text("처음으로") }
            }
        }
    }
}
