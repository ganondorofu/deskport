package io.github.ganondorofu.deskport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 接続のたびに求めるパスワード(この端末のアプリを守るもの)。
 * PC のホストの鍵とは別で、端末を他の人が開いたときに、勝手に PC へ接続されないようにするためのもの。
 * パスワードそのものは保存せず、ソルトつきの PBKDF2 の結果だけを保存する。
 */
object AppLock {
    enum class Kind(val label: String) { PIN("4桁の数字"), TEXT("自由な文字列") }

    var enabled by mutableStateOf(false)
        private set
    var kind by mutableStateOf(Kind.PIN)
        private set
    private var salt = ""
    private var hash = ""
    private var fails = 0
    private var lockedUntil = 0L

    fun load() {
        enabled = Prefs.getString("lock.on") == "1"
        kind = Kind.entries.firstOrNull { it.name == Prefs.getString("lock.kind") } ?: Kind.PIN
        salt = Prefs.getString("lock.salt") ?: ""
        hash = Prefs.getString("lock.hash") ?: ""
        if (salt.isEmpty() || hash.isEmpty()) enabled = false
    }

    fun set(k: Kind, password: String) {
        val s = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        salt = s; hash = derive(password, s); kind = k; enabled = true
        Prefs.putString("lock.on", "1"); Prefs.putString("lock.kind", k.name)
        Prefs.putString("lock.salt", salt); Prefs.putString("lock.hash", hash)
    }

    fun disable() {
        enabled = false; salt = ""; hash = ""
        listOf("lock.on", "lock.kind", "lock.salt", "lock.hash").forEach { Prefs.putString(it, null) }
    }

    /** 何秒待てば再び試せるか(0 なら、いま試せる)。 */
    fun waitSeconds(): Int {
        val left = lockedUntil - System.currentTimeMillis()
        return if (left > 0) (left / 1000 + 1).toInt() else 0
    }

    fun check(password: String): Boolean {
        if (waitSeconds() > 0) return false
        val ok = MessageDigest.isEqual(derive(password, salt).toByteArray(), hash.toByteArray())
        if (ok) { fails = 0; return true }
        fails++
        if (fails >= 5) { lockedUntil = System.currentTimeMillis() + 30_000L * (fails - 4).coerceAtMost(10); }
        return false
    }

    private fun derive(pw: String, saltHex: String): String {
        val spec = PBEKeySpec(pw.toCharArray(), saltHex.toByteArray(), 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
    }
}

/** パスワード入力欄。パスワードマネージャ(1Password など)の自動入力に対応する。 */
@Composable
private fun PasswordField(
    value: String, onChange: (String) -> Unit, label: String, kind: AppLock.Kind, isNew: Boolean, error: String? = null, onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = { n -> onChange(if (kind == AppLock.Kind.PIN) n.filter { it.isDigit() }.take(4) else n.take(128)) },
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (kind == AppLock.Kind.PIN) KeyboardType.NumberPassword else KeyboardType.Password,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().semantics { contentType = if (isNew) ContentType.NewPassword else ContentType.Password },
    )
}

/** 接続前の確認。合っていれば onOk。 */
@Composable
fun PasswordPrompt(title: String = "パスワードを入力", onOk: () -> Unit, onCancel: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    fun submit(entered: String = pw) {
        val w = AppLock.waitSeconds()
        if (w > 0) { err = "間違いが続いたため、${w}秒待ってください"; return }
        if (AppLock.check(entered)) onOk()
        else { pw = ""; err = AppLock.waitSeconds().let { if (it > 0) "間違いが続いたため、${it}秒待ってください" else "パスワードが違います" } }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            PasswordField(pw, { pw = it; err = null; if (AppLock.kind == AppLock.Kind.PIN && it.length == 4) submit(it) }, "パスワード", AppLock.kind, false, err, { submit() })
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = pw.isNotEmpty()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("キャンセル") } },
    )
}

/** パスワードの設定。 */
@Composable
fun PasswordSetup(onDone: () -> Unit, onCancel: () -> Unit) {
    var kind by remember { mutableStateOf(AppLock.Kind.PIN) }
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val tooShort = kind == AppLock.Kind.PIN && a.length in 1..3 || kind == AppLock.Kind.TEXT && a.length in 1..5
    val mismatch = b.isNotEmpty() && a != b
    val valid = !tooShort && a.isNotEmpty() && a == b
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("接続時のパスワード") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppLock.Kind.entries.forEach { k ->
                        PillTab(k.label, k == kind, { kind = k; a = ""; b = "" })
                    }
                }
                PasswordField(a, { a = it }, if (kind == AppLock.Kind.PIN) "4桁の数字" else "パスワード(6文字以上)", kind, true,
                    if (tooShort) (if (kind == AppLock.Kind.PIN) "4桁で入力してください" else "6文字以上にしてください") else null)
                PasswordField(b, { b = it }, "もう一度", kind, true, if (mismatch) "一致しません" else null)
                Text(
                    "このパスワードは、この端末のアプリを守るためのものです。PC の鍵とは別で、PC には送られません。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { AppLock.set(kind, a); onDone() }, enabled = valid) { Text("設定") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("キャンセル") } },
    )
}
