package com.nxnson.vesnavi.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nxnson.vesnavi.AppModel
import com.nxnson.vesnavi.BuildConfig
import com.nxnson.vesnavi.L
import com.nxnson.vesnavi.navcore.BugReport

/**
 * Opens a pre-filled GitHub issue (the rider submits it from their own account, no token in the app),
 * with the Bluetooth log redacted for a public page.
 */
@Composable
fun BugReportDialog(model: AppModel, includeLogByDefault: Boolean = true, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var details by remember { mutableStateOf("") }
    var includeLog by remember { mutableStateOf(includeLogByDefault) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("Báo lỗi lên GitHub", "Report a bug on GitHub")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text(L("Mô tả lỗi", "What went wrong")) },
                    placeholder = { Text(L("Bạn làm gì, mong đợi gì, thực tế thấy gì…", "What you did, what you expected, what you saw…")) },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L("Đính kèm log Bluetooth", "Attach the Bluetooth log"), Modifier.weight(1f))
                    Switch(checked = includeLog, onCheckedChange = { includeLog = it })
                }
                Text(
                    L(
                        "Issue trên GitHub là công khai: địa chỉ Bluetooth, khoá ghép đôi và tên thiết bị khác được ẩn trước khi gửi. Log dài thì chỉ phần mới nhất được điền vào; bản đầy đủ (đã ẩn) được chép sẵn — dán vào bình luận nếu cần. Cần tài khoản GitHub (miễn phí); không có gì được gửi cho tới khi bạn bấm \"Submit new issue\".",
                        "GitHub issues are public: Bluetooth addresses, pairing keys and other devices' names are removed first. A long log is cut to its newest part; the full (redacted) log is copied — paste it in a comment if needed. Needs a (free) GitHub account; nothing is sent until you tap \"Submit new issue\".",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = details.isNotBlank(), onClick = {
                open(context, model, details.trim(), includeLog)
                onDismiss()
            }) { Text(L("Mở GitHub để gửi", "Open GitHub to send")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("Huỷ", "Cancel")) } },
    )
}

private fun open(context: Context, model: AppModel, details: String, includeLog: Boolean) {
    val ble = model.ble
    val scooter = listOfNotNull(ble.connectedName, ble.linkProtocol?.title, ble.firmwareInfo).joinToString(" · ")
    val device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}" + if (scooter.isEmpty()) "" else " · $scooter"
    val log = if (includeLog) ble.logText(redacted = true) else null
    if (log != null) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("VesNavi log", log))
    }
    val title = "[Android] " + details.lineSequence().first().take(70)
    val url = BugReport.issueURL(title, details, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", device, log)
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
