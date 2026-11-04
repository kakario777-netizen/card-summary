package com.kakario.cardsummary

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/* ---------- 색 ---------- */

data class Pal(
    val bg: Color, val text: Color, val sub: Color, val soft: Color,
    val line: Color, val accent: Color, val red: Color, val dark: Boolean,
)

fun palOf(bg: Color): Pal {
    val dark = bg.luminance() < 0.4f
    return if (dark) Pal(
        bg, Color(0xFFECECEC), Color(0xFF9A9A9A), Color(0x1FFFFFFF),
        Color(0x26FFFFFF), Color(0xFF7FB3E0), Color(0xFFFF7B6E), true
    ) else Pal(
        bg, Color(0xFF111111), Color(0xFF777777), Color(0x0F1F4E79),
        Color(0x1A000000), Color(0xFF1F4E79), Color(0xFFC0392B), false
    )
}

val LocalPal = staticCompositionLocalOf { palOf(Color.White) }

enum class Screen { MAIN, SETTINGS, SETTLE, EXTRAS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            var bg by remember { mutableLongStateOf(Store.bgColor(ctx)) }
            val pal = palOf(Color(bg.toInt()))
            SideEffect {
                val w = (ctx as Activity).window
                w.statusBarColor = pal.bg.toArgb()
                w.navigationBarColor = pal.bg.toArgb()
                val c = WindowCompat.getInsetsController(w, w.decorView)
                c.isAppearanceLightStatusBars = !pal.dark
                c.isAppearanceLightNavigationBars = !pal.dark
            }
            val scheme = if (pal.dark)
                darkColorScheme(primary = pal.accent, background = pal.bg, surface = pal.bg,
                    onBackground = pal.text, onSurface = pal.text)
            else
                lightColorScheme(primary = pal.accent, background = pal.bg, surface = pal.bg,
                    onBackground = pal.text, onSurface = pal.text)
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalPal provides pal) {
                    Surface(
                        Modifier.fillMaxSize().systemBarsPadding().imePadding(),
                        color = pal.bg, contentColor = pal.text
                    ) {
                        App(onBg = { bg = it; Store.saveBg(ctx, it) })
                    }
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
fun App(onBg: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(Screen.MAIN) }
    var cards by remember { mutableStateOf(Store.loadCards(ctx)) }
    var extras by remember { mutableStateOf(Store.loadExtras(ctx)) }
    var mode by remember { mutableIntStateOf(0) }          // 0 이번 달, 1 지난달, 2 직접
    var from by remember { mutableLongStateOf(monthRange(0).first) }
    var to by remember { mutableLongStateOf(monthRange(0).second) }
    val txns = remember { mutableStateListOf<Txn>() }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    fun hasPerm() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun load() {
        loading = true
        val cs = cards; val f = from; val t = to
        scope.launch {
            val list = try {
                withContext(Dispatchers.IO) { SmsReader.collect(ctx, cs, f, t) }
            } catch (e: Exception) {
                Toast.makeText(ctx, "문자를 읽지 못했어요: ${e.message}", Toast.LENGTH_LONG).show()
                emptyList()
            }
            txns.clear(); txns.addAll(list)
            loading = false; loaded = true
        }
    }

    var pendingLoad by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val go = pendingLoad
        pendingLoad = false
        if (ok) { if (go) load() } else Toast.makeText(ctx, "문자 읽기 권한이 있어야 정리할 수 있어요", Toast.LENGTH_LONG).show()
    }

    fun loadOrAsk() {
        if (hasPerm()) load() else { pendingLoad = true; permLauncher.launch(Manifest.permission.READ_SMS) }
    }

    // 처음 켤 때 자동으로 불러오기
    LaunchedEffect(Unit) { loadOrAsk() }

    BackHandler(enabled = screen != Screen.MAIN) { screen = Screen.MAIN }

    when (screen) {
        Screen.MAIN -> MainScreen(
            cards = cards.filter { it.enabled }, allCards = cards, txns = txns, extras = extras,
            mode = mode, from = from, to = to, loading = loading, loaded = loaded,
            onMode = { m ->
                mode = m
                if (m < 2) { val r = monthRange(-m); from = r.first; to = r.second; loadOrAsk() }
            },
            onFrom = { from = startOfDay(it); mode = 2; loadOrAsk() },
            onTo = { to = endOfDay(it); mode = 2; loadOrAsk() },
            onReload = { loadOrAsk() },
            onSettings = { screen = Screen.SETTINGS },
            onExtras = { screen = Screen.EXTRAS },
            onSettle = { screen = Screen.SETTLE },
        )

        Screen.SETTINGS -> SettingsScreen(
            cards = cards,
            hasPerm = { hasPerm() },
            askPerm = { pendingLoad = false; permLauncher.launch(Manifest.permission.READ_SMS) },
            onBg = onBg,
            onSave = {
                val changed = it != cards
                cards = it; Store.saveCards(ctx, it); screen = Screen.MAIN
                if (changed) loadOrAsk()
            },
            onBack = { screen = Screen.MAIN },
        )

        Screen.EXTRAS -> ExtrasScreen(
            extras = extras, from = from, to = to,
            onChange = { extras = it; Store.saveExtras(ctx, it) },
            onBack = { screen = Screen.MAIN },
        )

        Screen.SETTLE -> SettleScreen(
            cards = cards.filter { it.enabled }, txns = txns.filter { tx -> cards.any { it.id == tx.card && it.enabled } },
            extras = extras, from = from, to = to,
            onBack = { screen = Screen.MAIN },
        )
    }
}

/* ---------- 공통 ---------- */

@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, right: @Composable () -> Unit = {}) {
    val pal = LocalPal.current
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) TextButton(onClick = onBack) { Text("〈 뒤로", color = pal.accent) }
        else Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        right()
    }
    HorizontalDivider(color = pal.line)
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val pal = LocalPal.current
    Box(
        Modifier
            .background(if (selected) pal.accent else pal.soft, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (selected) (if (pal.dark) Color.Black else Color.White) else pal.text, fontSize = 14.sp)
    }
}

/* ---------- 메인 ---------- */

@Composable
fun MainScreen(
    cards: List<CardConf>, allCards: List<CardConf>, txns: MutableList<Txn>, extras: List<Extra>,
    mode: Int, from: Long, to: Long, loading: Boolean, loaded: Boolean,
    onMode: (Int) -> Unit, onFrom: (Long) -> Unit, onTo: (Long) -> Unit, onReload: () -> Unit,
    onSettings: () -> Unit, onExtras: () -> Unit, onSettle: () -> Unit,
) {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    val pageCount = cards.size + 1
    val pager = rememberPagerState(pageCount = { pageCount })
    var editing by remember { mutableStateOf<Txn?>(null) }
    var addingCard by remember { mutableStateOf<String?>(null) }

    val visible = txns.filter { tx -> cards.any { it.id == tx.card } }
    val shortOf = allCards.associate { it.id to it.short }
    val extraSum = extras.sumOf { it.totalIn(from, to) }
    val extraCount = extras.count { it.countIn(from, to) > 0 }
    val cardSum = visible.filter { it.include }.sumOf { it.signed }

    Column(Modifier.fillMaxSize()) {
        TopBar("카드 정리") {
            TextButton(onClick = onReload, enabled = !loading) { Text("새로고침", color = pal.accent) }
            TextButton(onClick = onSettings) { Text("설정", color = pal.accent) }
        }

        // 기간
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("이번 달", mode == 0) { onMode(0) }
                Chip("지난달", mode == 1) { onMode(1) }
                Chip("직접", mode == 2) { onMode(2) }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { pickDate(ctx, from, onFrom) }, contentPadding = PaddingValues(4.dp)) {
                    Text(dayText(from), color = pal.text)
                }
                Text("~", color = pal.sub)
                TextButton(onClick = { pickDate(ctx, to, onTo) }, contentPadding = PaddingValues(4.dp)) {
                    Text(dayText(to), color = pal.text)
                }
                if (loading) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }

        // 탭
        ScrollableTabRow(
            selectedTabIndex = pager.currentPage.coerceIn(0, pageCount - 1),
            containerColor = pal.bg, contentColor = pal.accent, edgePadding = 8.dp,
            divider = { HorizontalDivider(color = pal.line) }
        ) {
            val titles = listOf("전체" to visible.size) + cards.map { c -> c.short to visible.count { it.card == c.id } }
            titles.forEachIndexed { i, (name, n) ->
                Tab(
                    selected = pager.currentPage == i,
                    onClick = { scope.launch { pager.animateScrollToPage(i) } },
                    selectedContentColor = pal.accent, unselectedContentColor = pal.sub,
                    text = { Text("$name $n", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                )
            }
        }

        // 좌우 슬라이드
        HorizontalPager(state = pager, modifier = Modifier.weight(1f), key = { it }) { page ->
            val card = if (page == 0) null else cards.getOrNull(page - 1)
            val list = if (card == null) visible else visible.filter { it.card == card.id }
            val sum = list.filter { it.include }.sumOf { it.signed }
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(card?.name ?: "전체 카드", color = pal.sub, modifier = Modifier.weight(1f))
                        Text("${list.count { it.include }}건 · ${won(sum)}", color = pal.sub)
                    }
                }
                if (list.isEmpty()) item {
                    Text(
                        if (!loaded) "문자를 불러오는 중이에요."
                        else "이 기간에 읽힌 결제 문자가 없어요.\n설정에서 문자 번호를 확인해 보세요.",
                        color = pal.sub, modifier = Modifier.padding(20.dp)
                    )
                }
                items(list, key = { it.id }) { t ->
                    TxnRow(
                        t, tag = if (card == null) shortOf[t.card] ?: "" else "",
                        onToggle = { v ->
                            val idx = txns.indexOfFirst { it.id == t.id }
                            if (idx >= 0) txns[idx] = t.copy(include = v)
                        },
                        onClick = { editing = t }
                    )
                    HorizontalDivider(color = pal.line)
                }
                if (card != null) item {
                    TextButton(onClick = { addingCard = card.id }, modifier = Modifier.padding(8.dp)) {
                        Text("+ ${card.short} 직접 추가", color = pal.accent)
                    }
                }
            }
        }

        // 기타 비용
        HorizontalDivider(color = pal.line)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onExtras).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("기타 비용", fontWeight = FontWeight.SemiBold)
                Text(
                    if (extras.isEmpty()) "정산 때 같이 넣을 돈을 등록하세요"
                    else "이 기간 ${extraCount}건 · 등록 ${extras.size}건",
                    color = pal.sub, fontSize = 13.sp
                )
            }
            Text(won(extraSum), fontWeight = FontWeight.SemiBold)
            Text("  〉", color = pal.sub)
        }

        // 총금액 + 정산
        HorizontalDivider(color = pal.line)
        Row(
            Modifier.fillMaxWidth().background(pal.soft).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("총금액", color = pal.sub, fontSize = 13.sp)
                Text(won(cardSum + extraSum), fontWeight = FontWeight.Bold, fontSize = 22.sp, color = pal.accent)
            }
            Button(onClick = onSettle, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(50.dp)) {
                Text("정산", fontSize = 17.sp)
            }
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
    addingCard?.let { cid ->
        val nextId = (txns.maxOfOrNull { it.id } ?: 0) + 1
        EditDialog(
            title = "${shortOf[cid]} 직접 추가",
            init = Txn(nextId, cid, minOf(System.currentTimeMillis(), to), "", 0, false, "", true, "", true),
            onDismiss = { addingCard = null },
            onDelete = null,
            onSave = { n ->
                val sorted = (txns + n).sortedBy { it.time }
                txns.clear(); txns.addAll(sorted)
                addingCard = null
            }
        )
    }
}

@Composable
fun TxnRow(t: Txn, tag: String, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    val pal = LocalPal.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = t.include, onCheckedChange = onToggle)
        Column(Modifier.weight(1f)) {
            Text(
                t.merchant.ifBlank { "(가맹점 없음)" },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (t.include) pal.text else pal.sub, fontSize = 16.sp
            )
            val tags = buildList {
                if (tag.isNotBlank()) add(tag)
                add(rowTime(t.time))
                if (t.cancel) add("취소")
                if (t.installment.isNotBlank() && t.installment != "일시불") add(t.installment)
                if (!t.parsed) add("금액 확인 필요")
            }
            Text(tags.joinToString(" · "), color = if (!t.parsed) pal.red else pal.sub, fontSize = 13.sp)
        }
        Text(
            won(t.signed),
            color = when { !t.include -> pal.sub; t.cancel -> pal.red; else -> pal.text },
            fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
            modifier = Modifier.padding(end = 10.dp)
        )
    }
}

@Composable
fun EditDialog(
    title: String, init: Txn,
    onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (Txn) -> Unit,
) {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    var merchant by remember { mutableStateOf(init.merchant) }
    var amount by remember { mutableStateOf(if (init.amount > 0) init.amount.toString() else "") }
    var cancel by remember { mutableStateOf(init.cancel) }
    var time by remember { mutableLongStateOf(init.time) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = pal.bg,
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
                    Text("원본 문자", color = pal.sub, fontSize = 12.sp)
                    Text(
                        init.raw, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().background(pal.soft, RoundedCornerShape(8.dp)).padding(10.dp)
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
                if (onDelete != null) TextButton(onClick = onDelete) { Text("삭제", color = pal.red) }
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        }
    )
}

/* ---------- 정산 ---------- */

@Composable
fun SettleScreen(
    cards: List<CardConf>, txns: List<Txn>, extras: List<Extra>, from: Long, to: Long,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    var mode by remember { mutableIntStateOf(Store.settleMode(ctx)) }
    var withExtras by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }

    LaunchedEffect(mode, withExtras) {
        text = Settle.build(mode, cards, txns, extras, withExtras, from, to)
        Store.saveSettleMode(ctx, mode)
    }
    val total = Settle.total(txns, extras, withExtras, from, to)
    val hasExtras = extras.any { it.countIn(from, to) > 0 }

    Column(Modifier.fillMaxSize()) {
        TopBar("정산", onBack)
        Column(Modifier.weight(1f).padding(16.dp)) {
            Text("정산 방식", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("날짜순 (카드 섞어서)", mode == Settle.BY_DATE) { mode = Settle.BY_DATE }
                Chip("카드사별", mode == Settle.BY_CARD) { mode = Settle.BY_CARD }
            }
            if (hasExtras) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(withExtras, { withExtras = it })
                    Text("기타 비용 포함")
                }
            } else Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text("총금액", color = pal.sub, modifier = Modifier.weight(1f))
                Text(won(total), fontWeight = FontWeight.Bold, fontSize = 22.sp, color = pal.accent)
            }
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = pal.text),
            )
            Text("글은 직접 고쳐도 돼요. 저장·보내기는 고친 글 그대로 나가요.", color = pal.sub, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        Row(
            Modifier.fillMaxWidth().background(pal.soft).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("정산", text))
                Toast.makeText(ctx, "복사했어요", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.weight(1f)) { Text("복사") }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, Settle.fileName(from, to))
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                ctx.startActivity(Intent.createChooser(send, "정산 보내기"))
            }, modifier = Modifier.weight(1f)) { Text("보내기") }
            Button(onClick = {
                val name = Settle.fileName(from, to)
                val uri = try { Exporter.saveToDownloads(ctx, name, text) } catch (_: Exception) { null }
                Toast.makeText(
                    ctx, if (uri != null) "다운로드/카드정리/$name 에 저장했어요" else "저장하지 못했어요",
                    Toast.LENGTH_LONG
                ).show()
            }, modifier = Modifier.weight(1.3f)) { Text("메모장 저장") }
        }
    }
}

/* ---------- 기타 비용 ---------- */

@Composable
fun ExtrasScreen(
    extras: List<Extra>, from: Long, to: Long,
    onChange: (List<Extra>) -> Unit, onBack: () -> Unit,
) {
    val pal = LocalPal.current
    var editing by remember { mutableStateOf<Extra?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar("기타 비용", onBack) {
            TextButton(onClick = {
                editing = Extra((extras.maxOfOrNull { it.id } ?: 0) + 1, "", 0, ymOf(from), 0)
            }) { Text("+ 추가", color = pal.accent) }
        }
        Text(
            "정산할 때 카드 결제와 같이 넣을 돈이에요. 매월 / 몇 개월 / 한 번 / 원하는 기간으로 정할 수 있고, " +
                    "고른 정산 기간에 해당하는 달만큼 들어가요.",
            color = pal.sub, fontSize = 13.sp, modifier = Modifier.padding(16.dp)
        )
        LazyColumn(Modifier.weight(1f)) {
            if (extras.isEmpty()) item {
                Text("등록된 비용이 없어요. 오른쪽 위 '+ 추가'를 눌러 주세요.", color = pal.sub,
                    modifier = Modifier.padding(16.dp))
            }
            items(extras, key = { it.id }) { e ->
                val n = e.countIn(from, to)
                Row(
                    Modifier.fillMaxWidth().clickable { editing = e }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name.ifBlank { "(이름 없음)" }, fontSize = 16.sp)
                        Text(
                            e.periodText() + if (n > 0) " · 이번 정산 ${n}회" else " · 이번 정산 해당 없음",
                            color = if (n > 0) pal.sub else pal.red, fontSize = 13.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(won(e.amount), fontWeight = FontWeight.SemiBold)
                        if (n > 1) Text("= ${won(e.amount * n)}", color = pal.sub, fontSize = 13.sp)
                    }
                }
                HorizontalDivider(color = pal.line)
            }
        }
    }

    editing?.let { e ->
        ExtraDialog(
            init = e,
            onDismiss = { editing = null },
            onDelete = if (extras.any { it.id == e.id }) ({ onChange(extras.filter { it.id != e.id }); editing = null }) else null,
            onSave = { n ->
                onChange(if (extras.any { it.id == n.id }) extras.map { if (it.id == n.id) n else it } else extras + n)
                editing = null
            }
        )
    }
}

@Composable
fun MonthStepper(label: String, ym: Int, onChange: (Int) -> Unit) {
    val pal = LocalPal.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = pal.sub, modifier = Modifier.width(56.dp))
        TextButton(onClick = { onChange(ymAdd(ym, -1)) }) { Text("◀") }
        Text(ymText(ym), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { onChange(ymAdd(ym, 1)) }) { Text("▶") }
    }
}

@Composable
fun ExtraDialog(init: Extra, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (Extra) -> Unit) {
    val pal = LocalPal.current
    var name by remember { mutableStateOf(init.name) }
    var amount by remember { mutableStateOf(if (init.amount > 0) init.amount.toString() else "") }
    var start by remember { mutableIntStateOf(init.startYm) }
    // 0 매월 계속, 1 몇 개월, 2 한 번, 3 직접(종료월)
    var kind by remember {
        mutableIntStateOf(
            when {
                init.endYm == 0 -> 0
                init.endYm == init.startYm -> 2
                else -> 1
            }
        )
    }
    var months by remember {
        mutableStateOf(if (init.endYm > init.startYm) (monthsBetween(init.startYm, init.endYm)).toString() else "3")
    }
    var end by remember { mutableIntStateOf(if (init.endYm > 0) init.endYm else ymAdd(init.startYm, 2)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = pal.bg,
        title = { Text(if (onDelete == null) "기타 비용 추가" else "기타 비용 고치기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("이름 (예: 관리비, 보험료)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(amount, { amount = digits(it) }, label = { Text("금액(원) — 한 달 기준") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("매월", kind == 0) { kind = 0 }
                    Chip("몇 개월", kind == 1) { kind = 1 }
                    Chip("한 번", kind == 2) { kind = 2 }
                    Chip("직접", kind == 3) { kind = 3 }
                }
                Spacer(Modifier.height(8.dp))
                MonthStepper("시작", start) { start = it; if (end < it) end = it }
                when (kind) {
                    1 -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(months, { months = digits(it).take(3) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(90.dp))
                            Text("  개월 (${ymText(start)} ~ ${ymText(ymAdd(start, ((months.toIntOrNull() ?: 1).coerceAtLeast(1)) - 1))})",
                                color = pal.sub)
                        }
                    }
                    3 -> MonthStepper("끝", end) { end = if (it < start) start else it }
                    0 -> Text("끝 없이 매달 들어가요", color = pal.sub, fontSize = 13.sp)
                    else -> Text("${ymText(start)} 한 달만 들어가요", color = pal.sub, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val endYm = when (kind) {
                    0 -> 0
                    1 -> ymAdd(start, ((months.toIntOrNull() ?: 1).coerceAtLeast(1)) - 1)
                    2 -> start
                    else -> maxOf(end, start)
                }
                onSave(init.copy(name = name.trim(), amount = amount.toLongOrNull() ?: 0L, startYm = start, endYm = endYm))
            }) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("삭제", color = pal.red) }
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        }
    )
}

fun monthsBetween(a: Int, b: Int): Int = ((b / 100) * 12 + b % 100) - ((a / 100) * 12 + a % 100) + 1

/* ---------- 설정 ---------- */

@Composable
fun SettingsScreen(
    cards: List<CardConf>, hasPerm: () -> Boolean, askPerm: () -> Unit,
    onBg: (Long) -> Unit, onSave: (List<CardConf>) -> Unit, onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    val list = remember { mutableStateListOf(*cards.toTypedArray()) }
    val numText = remember { mutableStateMapOf(*cards.map { it.id to it.numbers.joinToString(", ") }.toTypedArray()) }
    var open by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<Pair<String, List<Pair<String, Int>>>?>(null) }
    var bgNow by remember { mutableLongStateOf(Store.bgColor(ctx)) }

    fun build() = list.map { c ->
        c.copy(numbers = (numText[c.id] ?: "").split(",").map { digits(it) }.filter { it.isNotEmpty() })
    }

    fun move(i: Int, d: Int) {
        val j = i + d
        if (j < 0 || j >= list.size) return
        val a = list[i]; list[i] = list[j]; list[j] = a
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("설정", onBack) { TextButton(onClick = { onSave(build()) }) { Text("저장", color = pal.accent) } }
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("카드", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("켜고 끄기, 화살표로 탭 순서 바꾸기. 카드를 누르면 문자 번호를 고칠 수 있어요.",
                color = pal.sub, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))

            list.forEachIndexed { i, c ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .background(pal.soft, RoundedCornerShape(12.dp))
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { open = if (open == c.id) null else c.id }
                            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.SemiBold, color = if (c.enabled) pal.text else pal.sub)
                            Text(
                                (numText[c.id] ?: "").ifBlank { "문자 번호 없음" },
                                color = pal.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = { move(i, -1) }, enabled = i > 0, contentPadding = PaddingValues(6.dp)) { Text("▲") }
                        TextButton(onClick = { move(i, 1) }, enabled = i < list.size - 1, contentPadding = PaddingValues(6.dp)) { Text("▼") }
                        Switch(checked = c.enabled, onCheckedChange = { v -> list[i] = c.copy(enabled = v) })
                    }
                    if (open == c.id) {
                        Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp)) {
                            OutlinedTextField(c.name, { v -> list[i] = c.copy(name = v) }, label = { Text("카드 이름") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(numText[c.id] ?: "", { v -> numText[c.id] = v },
                                label = { Text("문자 번호 (여러 개는 쉼표로)") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(c.keyword, { v -> list[i] = c.copy(keyword = v) },
                                label = { Text("번호 찾기용 문자 속 단어") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = {
                                if (!hasPerm()) {
                                    askPerm()
                                } else {
                                    val kw = c.keyword; val id = c.id
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) {
                                            try { SmsReader.findSenders(ctx, kw) } catch (_: Exception) { emptyList() }
                                        }
                                        found = id to r
                                    }
                                }
                            }) { Text("최근 문자에서 번호 찾기") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("배경색", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(10.dp))
            BG_PRESETS.chunked(4).forEach { rowList ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceAround) {
                    rowList.forEach { p ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { bgNow = p.color; onBg(p.color) }.padding(4.dp)) {
                            Box(
                                Modifier.size(44.dp)
                                    .background(Color(p.color.toInt()), CircleShape)
                                    .border(if (bgNow == p.color) 3.dp else 1.dp,
                                        if (bgNow == p.color) pal.accent else pal.line, CircleShape)
                            )
                            Text(p.name, fontSize = 12.sp, color = pal.sub)
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    found?.let { (id, res) ->
        val c = list.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { found = null },
            containerColor = pal.bg,
            title = { Text("${c?.name ?: ""} 번호 후보") },
            text = {
                if (res.isEmpty()) Text("최근 90일 문자에서 '${c?.keyword}' 결제 문자를 못 찾았어요.")
                else Column {
                    Text("눌러서 추가하세요", color = pal.sub, fontSize = 13.sp)
                    res.take(8).forEach { (n, cnt) ->
                        Text(
                            "$n  (${cnt}건)", fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth().clickable {
                                val cur = (numText[id] ?: "").split(",").map { digits(it) }.filter { it.isNotEmpty() }
                                if (n !in cur) numText[id] = (cur + n).joinToString(", ")
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
