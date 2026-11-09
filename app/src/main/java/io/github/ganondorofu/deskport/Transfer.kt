package io.github.ganondorofu.deskport

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TransferState { WAITING, RUNNING, DONE, FAILED, CANCELED }

class TransferItem(val name: String, val upload: Boolean) {
    var total by mutableLongStateOf(-1L)
    var done by mutableLongStateOf(0L)
    var state by mutableStateOf(TransferState.WAITING)
    var note by mutableStateOf("")
    /** 保存先。送ったファイルなら PC のパス、受け取ったファイルなら、スマホの「ダウンロード/Deskport」。 */
    var dest by mutableStateOf("")
    /** 転送の速さ(バイト/秒)。 */
    var speed by mutableLongStateOf(0L)
    /** 実際に通信した量。圧縮したときは、元より小さい。 */
    var wire by mutableLongStateOf(-1L)
    var compressed by mutableStateOf(false)
    @Volatile var cancelled = false
    /** PC での保存名(送ったあと)。「PC で表示」に使う。 */
    var savedName: String? = null
    /** スマホに保存したファイル(受け取ったあと)。「開く」に使う。 */
    var fileUri: Uri? = null
    // 再試行のための、元
    var source: Uri? = null
    var text: String? = null
    var remote: RemoteFile? = null
}

/** すでに圧縮されているものは、さらに圧縮しても小さくならず、時間と電池のむだになる。圧縮の設定がオンでも、そのまま送る。 */
private val ALREADY_COMPRESSED = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "mp4", "m4v", "mov", "mkv", "avi", "webm", "3gp", "mp3", "m4a", "aac", "ogg", "opus", "flac",
    "zip", "7z", "rar", "gz", "bz2", "xz", "zst", "apk", "aab", "jar", "docx", "xlsx", "pptx", "pdf",
)

/**
 * ファイル転送の本体。シートを閉じても、アプリの画面を離れても続くよう、画面とは別のスコープで動かす。
 * 送り先は接続中のホスト、なければ最後に使ったホスト。共有メニュー(他アプリの「共有」)からも使える。
 *  - スマホ → PC: PC の「ダウンロード\Deskport」に保存される(保存先の絶対パスは、PC が教えてくれる)。
 *  - PC → スマホ: PC の窓の「ファイル」タブでフォルダに入れたファイルが、「PC のファイル」に出る。保存すると、スマホの「ダウンロード/Deskport」に入る。
 */
object Transfers {
    val items = mutableStateListOf<TransferItem>()
    var showSheet by mutableStateOf(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** PC の置き場所の一覧(最後に取れたもの)。取れなかったときは null。 */
    var remote by mutableStateOf<RemoteList?>(null)
        private set
    var remoteLoading by mutableStateOf(false)
        private set
    var remoteFailed by mutableStateOf(false)
        private set

    fun targetHost(): Host? = Session.host ?: HostStore.last()

    fun refresh() {
        val host = targetHost() ?: return
        scope.launch {
            remoteLoading = true
            val r = HostLink(host).list()
            remoteLoading = false
            if (r != null) { remote = r; remoteFailed = false } else remoteFailed = true
        }
    }

    private fun wantCompress(name: String): Boolean =
        FileSettings.compress && name.substringAfterLast('.', "").lowercase() !in ALREADY_COMPRESSED

    /** 進み具合の通知。キャンセルされていたら、ここで例外を投げて、転送をやめる。 */
    private fun progress(item: TransferItem): (Long) -> Unit {
        var lastT = System.currentTimeMillis(); var lastN = 0L
        return { n ->
            if (item.cancelled) throw IOException("キャンセルしました")
            item.done = n
            val now = System.currentTimeMillis()
            if (now - lastT >= 500) { item.speed = (n - lastN) * 1000 / (now - lastT); lastT = now; lastN = n }
        }
    }

    private fun finish(item: TransferItem, e: Throwable) {
        if (item.cancelled) { item.state = TransferState.CANCELED; item.note = "キャンセルしました" }
        else { item.state = TransferState.FAILED; item.note = e.message ?: "失敗" }
        item.speed = 0
    }

    fun uploadUri(ctx: Context, uri: Uri) {
        var name = "file"
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
            }
        }
        val item = TransferItem(name, true).also { it.source = uri; it.total = size; items.add(0, it) }
        startUpload(ctx, item)
    }

    /** 共有された文字列を、テキストファイルとして送る。 */
    fun uploadText(ctx: Context, text: String) {
        val name = "共有テキスト-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.JAPAN).format(Date()) + ".txt"
        val item = TransferItem(name, true).also { it.text = text; it.total = text.toByteArray().size.toLong(); items.add(0, it) }
        startUpload(ctx, item)
    }

    private fun startUpload(ctx: Context, item: TransferItem) {
        val host = targetHost()
        if (host == null) { item.state = TransferState.FAILED; item.note = "ホストが未登録"; return }
        item.cancelled = false; item.done = 0; item.speed = 0; item.wire = -1; item.note = ""
        item.compressed = wantCompress(item.name)
        item.state = TransferState.RUNNING
        scope.launch {
            val input = try {
                item.text?.let { java.io.ByteArrayInputStream(it.toByteArray()) } ?: item.source?.let { ctx.contentResolver.openInputStream(it) }
            } catch (e: Exception) { null }
            if (input == null) { item.state = TransferState.FAILED; item.note = "読み込めません"; return@launch }
            input.use {
                HostLink(host).upload(item.name, it, item.total, item.compressed, progress(item))
                    .onSuccess { r ->
                        item.state = TransferState.DONE; item.speed = 0
                        item.savedName = r.name; item.wire = r.wire
                        item.done = item.total.coerceAtLeast(item.done)
                        val dir = remote?.dir.orEmpty().ifEmpty { "ダウンロード\\Deskport" }
                        item.dest = "$dir\\${r.name}"
                        refresh()
                    }
                    .onFailure { e -> finish(item, e) }
            }
        }
    }

    /** PC のファイルを、スマホの「ダウンロード/Deskport」に保存する。 */
    fun download(ctx: Context, f: RemoteFile) {
        val item = TransferItem(f.name, false).also { it.total = f.size; it.remote = f; items.add(0, it) }
        startDownload(ctx, item)
    }

    private fun startDownload(ctx: Context, item: TransferItem) {
        val host = targetHost()
        val f = item.remote
        if (host == null || f == null) { item.state = TransferState.FAILED; item.note = "ホストが未登録"; return }
        item.cancelled = false; item.done = 0; item.speed = 0; item.wire = -1; item.note = ""
        item.compressed = wantCompress(item.name)
        item.state = TransferState.RUNNING
        scope.launch {
            val resolver = ctx.contentResolver
            val ext = f.name.substringAfterLast('.', "").lowercase()
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Deskport")
                put(MediaStore.Downloads.IS_PENDING, 1)   // 書き終わるまで、ほかのアプリに見せない
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            val out = uri?.let { try { resolver.openOutputStream(it) } catch (e: Exception) { null } }
            if (uri == null || out == null) { item.state = TransferState.FAILED; item.note = "保存先を作れません"; return@launch }
            val r = out.use { HostLink(host).download(f.name, it, item.compressed, progress(item)) }
            r.onSuccess { wire ->
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                var shown = f.name
                resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) shown = c.getString(0) ?: shown }
                item.fileUri = uri; item.wire = wire
                item.dest = "ダウンロード/Deskport/$shown"
                item.state = TransferState.DONE; item.speed = 0; item.done = item.total.coerceAtLeast(item.done)
            }.onFailure { e ->
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}   // 途中までのファイルは、残さない
                finish(item, e)
            }
        }
    }

    fun cancel(item: TransferItem) { item.cancelled = true }

    fun retry(ctx: Context, item: TransferItem) {
        if (item.state == TransferState.RUNNING) return
        if (item.upload) startUpload(ctx, item) else startDownload(ctx, item)
    }

    /** 送ったファイルを、PC のエクスプローラーで表示する。 */
    fun revealOnPc(name: String) {
        val host = targetHost() ?: return
        scope.launch { HostLink(host).reveal(name) }
    }

    fun deleteOnPc(name: String) {
        val host = targetHost() ?: return
        scope.launch { HostLink(host).delete(name); refresh() }
    }

    /** スマホに保存したファイルを、開く。 */
    fun openLocal(ctx: Context, item: TransferItem) {
        val uri = item.fileUri ?: return
        val type = ctx.contentResolver.getType(uri) ?: "*/*"
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            item.note = "開けるアプリがありません"
        }
    }

    /** 他アプリの「共有」から来たインテントを処理する。処理したら true。 */
    fun handleShare(ctx: Context, intent: Intent?): Boolean {
        intent ?: return false
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: arrayListOf()
            else -> return false
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (uris.isEmpty() && text == null) return false
        uris.forEach { uploadUri(ctx, it) }
        if (uris.isEmpty() && text != null) uploadText(ctx, text)
        showSheet = true
        return true
    }
}

private fun fmtSize(n: Long): String = when {
    n < 0 -> "?"
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024.0)
    else -> "%.2f GB".format(n / 1024.0 / 1024.0 / 1024.0)
}

private fun fmtDate(sec: Long): String = SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date(sec * 1000))

/** 自動同期: ONの間、ホストと端末のクリップボードを双方向で合わせる(文字列のみ)。 */
@Composable
fun ClipboardSyncEffect() {
    val ctx = LocalContext.current
    val host = Session.host
    LaunchedEffect(host?.id, SessionPrefs.clipboardSync) {
        if (host == null || !SessionPrefs.clipboardSync) return@LaunchedEffect
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        var last: String? = null
        var conn: HostConn? = null
        try {
            while (isActive) {
                // 端末側の読み取りは、アプリが前面にあるときだけ可能(Android 10以降の制限)
                val local = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                try {
                    val c = conn ?: withContext(Dispatchers.IO) { HostConn.open(host) }.also { conn = it }
                    withContext(Dispatchers.IO) {
                        if (!local.isNullOrEmpty() && local != last) { c.clipSet(local); last = local }
                        else {
                            val remote = c.clipGet()
                            if (remote.isNotEmpty() && remote != last) {
                                withContext(Dispatchers.Main) { cm.setPrimaryClip(android.content.ClipData.newPlainText("host", remote)) }
                                last = remote
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ClipSync", "${e.javaClass.simpleName}: ${e.message}")
                    conn?.close(); conn = null
                }
                delay(1500)
            }
        } finally { conn?.close() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach { Transfers.uploadUri(ctx, it) } }
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> uris.forEach { Transfers.uploadUri(ctx, it) } }
    // 開いている間は、PC のファイルの一覧を、数秒ごとに更新する(PC の窓で追加したファイルが、そのうち出る)
    LaunchedEffect(Unit) { while (true) { Transfers.refresh(); delay(4000) } }

    ModalBottomSheet(
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss, containerColor = Color.Transparent, shape = RectangleShape,
        dragHandle = null, tonalElevation = 0.dp,
    ) {
        val bottomGap = if (LocalConfiguration.current.screenWidthDp >= 600) 68.dp else 12.dp
        FocusBlock(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = bottomGap).navigationBarsPadding()) {
            Column(
                Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp).padding(top = 20.dp, bottom = 16.dp),
            ) {
                Text("ファイル転送", fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
                val host = Transfers.targetHost()
                Text(
                    if (host != null) "PC: ${host.name}(${host.address})" else "ホストが未登録です。ホスト一覧で追加してください",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                )

                // ---- スマホ → PC
                SubHeader("スマホ → PC")
                SettingGroup {
                    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val dir = Transfers.remote?.dir.orEmpty()
                        Text(
                            "送ったファイルは、PC の次のフォルダに保存されます:\n" + dir.ifEmpty { "ユーザーフォルダの「ダウンロード\\Deskport」(PC につながると、正確な場所が出ます)" },
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("ファイルを選ぶ", onClick = { picker.launch(arrayOf("*/*")) })
                            PillButton("写真・動画を選ぶ", onClick = { media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("圧縮して送る・受け取る", fontSize = 15.sp)
                                Text("通信量の節約になります。写真・動画・zip などは、そのまま送ります", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OneUiSwitch(FileSettings.compress, { FileSettings.compress = it })
                        }
                        Text("他のアプリの「共有」からも、送れます", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // ---- 転送中・履歴
                if (Transfers.items.isNotEmpty()) {
                    SubHeader("転送")
                    SettingGroup {
                        Transfers.items.take(12).forEachIndexed { i, t ->
                            if (i > 0) RowDivider()
                            TransferRow(t)
                        }
                    }
                }

                // ---- PC → スマホ
                SubHeader("PC → スマホ")
                SettingGroup {
                    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "PC の Deskport の窓の「ファイル」タブで追加したファイルが、下に出ます。「保存」を押すと、スマホの「ダウンロード/Deskport」に保存されます。",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val r = Transfers.remote
                        when {
                            r == null -> Text(
                                if (Transfers.remoteLoading) "読み込み中…" else "PC につながりません(PC のホストが動いているか、確認してください)",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                            )
                            r.files.isEmpty() -> Text("ファイルはありません", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            else -> Column {
                                r.files.take(40).forEachIndexed { i, f ->
                                    if (i > 0) RowDivider()
                                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                                        Text(f.name, fontSize = 15.sp, maxLines = 2)
                                        Text("${fmtSize(f.size)} · ${fmtDate(f.mtime)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            PillButton("保存", onClick = { Transfers.download(ctx, f) })
                                            PillButton("PC で表示", onClick = { Transfers.revealOnPc(f.name) })
                                            PillButton("削除", onClick = { confirmDelete = f.name })
                                        }
                                    }
                                }
                            }
                        }
                        if (Transfers.remoteFailed && r != null) Text("最新の一覧を取れませんでした(前回の内容です)", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        PillButton("更新", onClick = { Transfers.refresh() })
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "閉じる", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, fontSize = 16.sp,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onDismiss).padding(horizontal = 32.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("PC からファイルを削除しますか?") },
            text = { Text(name) },
            confirmButton = { TextButton(onClick = { Transfers.deleteOnPc(name); confirmDelete = null }) { Text("削除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun TransferRow(t: TransferItem) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (t.upload) "↑" else "↓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
            Text(t.name, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text(
                when (t.state) {
                    TransferState.WAITING -> "待機中"
                    TransferState.RUNNING -> if (t.total > 0) "${(t.done * 100 / t.total).coerceAtMost(100)}%" else fmtSize(t.done)
                    TransferState.DONE -> "完了"
                    TransferState.FAILED -> "失敗"
                    TransferState.CANCELED -> "中止"
                },
                fontSize = 13.sp,
                color = if (t.state == TransferState.FAILED) Color(0xFFD93025) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 大きさ、速さ、圧縮の効果
        val detail = buildString {
            if (t.total >= 0) append(fmtSize(t.total))
            if (t.state == TransferState.RUNNING && t.speed > 0) append(" · ${fmtSize(t.speed)}/s")
            if (t.compressed) append(" · 圧縮")
            if (t.state == TransferState.DONE && t.compressed && t.wire >= 0 && t.total > 0) {
                val saved = ((1.0 - t.wire.toDouble() / t.total) * 100).toInt()
                append("(通信量 ${fmtSize(t.wire)}、${if (saved > 0) "$saved%減" else "ほぼ変わらず"})")
            }
        }
        if (detail.isNotEmpty()) Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        if (t.state == TransferState.RUNNING && t.total > 0) {
            val frac = (t.done.toFloat() / t.total).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 4.dp, max = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth(frac).heightIn(min = 4.dp, max = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
        // 保存先(PC のパス、またはスマホの場所)
        if (t.state == TransferState.DONE && t.dest.isNotEmpty()) {
            Text((if (t.upload) "保存先(PC): " else "保存先: ") + t.dest, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        if (t.note.isNotEmpty() && t.state != TransferState.DONE) Text(t.note, fontSize = 11.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp))
        // 操作
        val actions = @Composable {
            when (t.state) {
                TransferState.RUNNING, TransferState.WAITING -> PillButton("キャンセル", onClick = { Transfers.cancel(t) })
                TransferState.FAILED, TransferState.CANCELED -> PillButton("再試行", onClick = { Transfers.retry(ctx, t) })
                TransferState.DONE -> if (t.upload) { t.savedName?.let { n -> PillButton("PC で表示", onClick = { Transfers.revealOnPc(n) }) } } else PillButton("開く", onClick = { Transfers.openLocal(ctx, t) })
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}
