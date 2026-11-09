package io.github.ganondorofu.deskport

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** One UI 風の入力欄: ラベルを上に小さく、角丸の塗りつぶし。Material のアウトライン型は使わない。 */
@Composable
fun OneUiField(
    label: String, value: String, onChange: (String) -> Unit,
    modifier: Modifier = Modifier, secret: Boolean = false, number: Boolean = false, placeholder: String = "",
) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(OneUi.RadiusSmall))
                .background(MaterialTheme.colorScheme.background).padding(horizontal = 14.dp, vertical = 12.dp),
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else if (secret) KeyboardType.Password else KeyboardType.Uri),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                inner()
            },
        )
    }
}

/**
 * ホスト一覧(接続の入口)。到達性は、各ホストへ数秒ごとに問い合わせて緑/灰の点で示す。
 * タップで接続、長押しで編集・削除。接続中のホストには「接続中」を表示し、画面から戻っても接続は切れない。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HostsScreen(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Host?>(null) }
    var adding by remember { mutableStateOf(false) }
    val checked = remember { mutableStateMapOf<String, Boolean>() }   // id → 最初の確認が終わったか
    var pendingConnect by remember { mutableStateOf<(() -> Unit)?>(null) }
    pendingConnect?.let { go -> PasswordPrompt(onOk = { pendingConnect = null; go() }, onCancel = { pendingConnect = null }) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val native = remember { mutableStateMapOf<String, Boolean>() }   // id → 自作ホストが応答したか
    val via = remember { mutableStateMapOf<String, String>() }        // id → 応答した(最も速い)アドレス
    // QRコードで登録する(PC のトレイアプリに出る QR を読む)
    val scanQr = {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        GmsBarcodeScanning.getClient(ctx, opts).startScan()
            .addOnSuccessListener { bc ->
                val n = PairQr.import(bc.rawValue ?: "")
                Toast.makeText(ctx, tr(if (n > 0) "登録しました" else "このアプリのQRコードではありません"), Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { Toast.makeText(ctx, tr("読み取りできませんでした: ${it.message}"), Toast.LENGTH_LONG).show() }
        Unit
    }

    // 一覧が見えている間、各ホストのホストへの到達性を確認する
    LaunchedEffect(HostStore.hosts.toList()) {
        while (isActive) {
            HostStore.hosts.toList().forEach { h ->
                val picked = NativeProbe.pick(h)
                native[h.id] = picked != null
                if (picked != null) via[h.id] = picked.address else via.remove(h.id)
                checked[h.id] = true
            }
            delay(5000)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = OneUi.TitleMargin, end = OneUi.SideMargin, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ホスト", fontSize = 28.sp, fontWeight = FontWeight.Medium)
                Text("接続先を選んでください", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PillButton("QRで登録", onClick = scanQr)
            Spacer(Modifier.width(8.dp))
            PillButton("手入力", onClick = { adding = true })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = OneUi.SideMargin)) {
            if (HostStore.hosts.isEmpty()) {
                FocusBlock(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(24.dp)) {
                        Text("ホストがありません", fontWeight = FontWeight.Medium)
                        Text(
                            "PC で Deskport ホストを起動すると QR コードが出ます。「QRで登録」で読み取ってください。",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            } else {
                FocusBlock(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column {
                        HostStore.hosts.forEachIndexed { i, h ->
                            if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                            val nativeOk = native[h.id] == true
                            val ok = nativeOk
                            val connected = Session.host?.id == h.id
                            Row(
                                Modifier.fillMaxWidth()
                                    .combinedClickable(onClick = {
                                        val go = {
                                            if (!connected) {
                                                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                // 最も速く応えたアドレスでつなぐ(まだ確認できていなければ、先頭のアドレス)
                                                val target = via[h.id]?.let { h.copy(address = it) } ?: h
                                                Session.connect(ctx, target)
                                                Stream.start(target)
                                            }
                                            onOpen()
                                        }
                                        // 接続中のホストを開くだけなら、パスワードは要らない
                                        if (!connected && AppLock.enabled) pendingConnect = go else go()
                                    }, onLongClick = { editing = h })
                                    .padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) Color(0xFF34C759) else MaterialTheme.colorScheme.outline))
                                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                    Text(h.name, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                    val sub = MaterialTheme.colorScheme.onSurfaceVariant
                                    Text(via[h.id] ?: h.address, fontSize = 12.sp, color = sub)
                                    val done = checked[h.id] == true
                                    Text(
                                        "映像・操作: " + when {
                                            nativeOk -> "応答あり"
                                            !done -> "確認中…"
                                            else -> "応答なし(PC のホストが動いているか、QRで登録し直してください)"
                                        },
                                        fontSize = 12.sp, color = sub,
                                    )
                                }
                                if (connected) Text("接続中", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Text("長押しで編集・削除", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
            }
        }
    }
    if (adding) HostEditor(null, onDismiss = { adding = false })
    editing?.let { HostEditor(it, onDismiss = { editing = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostEditor(host: Host?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(host?.name ?: "") }
    var address by remember { mutableStateOf(host?.address ?: "") }
    var token by remember { mutableStateOf(host?.token ?: "") }
    var nativePort by remember { mutableStateOf((host?.nativePort ?: 49000).toString()) }
    var alt by remember { mutableStateOf(host?.alt?.joinToString(", ") ?: "") }
    var dispCount by remember { mutableStateOf((host?.displayCount ?: 1).toString()) }
    val valid = name.isNotBlank() && address.isNotBlank() && token.isNotBlank() && nativePort.toIntOrNull() in 1..65535 && dispCount.toIntOrNull() in 1..4

    ModalBottomSheet(
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss, containerColor = Color.Transparent, shape = RectangleShape,
        dragHandle = null, tonalElevation = 0.dp,
    ) {
        val bottomGap = if (LocalConfiguration.current.screenWidthDp >= 600) 68.dp else 12.dp
        FocusBlock(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = bottomGap).navigationBarsPadding()) {
            Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 16.dp)) {
                Text(if (host == null) "ホストを追加" else "ホストを編集", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                OneUiField("名前", name, { name = it }, placeholder = "自宅PC")
                OneUiField("アドレス(IP または名前)", address, { address = it.trim() }, placeholder = "192.168.0.10")
                OneUiField("別のアドレス(カンマ区切り)", alt, { alt = it }, placeholder = "100.x.x.x, pc.example.com")
                OneUiField("ポート", nativePort, { nativePort = it.filter(Char::isDigit) }, number = true)
                OneUiField("ディスプレイ数", dispCount, { dispCount = it.filter(Char::isDigit) }, number = true)
                OneUiField("鍵", token, { token = it.trim() }, secret = true)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (host != null) {
                        Text(
                            "削除", color = Color(0xFFD93025), fontWeight = FontWeight.Medium,
                            modifier = Modifier.clip(CircleShape).clickable {
                                HostStore.hosts.removeAll { it.id == host.id }
                                if (Session.host?.id == host.id) Session.host = null
                                onDismiss()
                            }.padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                    Text(
                        "キャンセル", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onDismiss).padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                    Text(
                        "保存", fontWeight = FontWeight.Bold,
                        color = if (valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.clip(CircleShape).clickable(enabled = valid) {
                            HostStore.upsert(Host(host?.id ?: java.util.UUID.randomUUID().toString(), name.trim(), address, token, dispCount.toInt(), nativePort.toInt(), alt.split(',', ' ', '、').map { it.trim() }.filter { it.isNotEmpty() }))
                            onDismiss()
                        }.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
