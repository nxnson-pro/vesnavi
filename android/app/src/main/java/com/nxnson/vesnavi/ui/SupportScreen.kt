package com.nxnson.vesnavi.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nxnson.vesnavi.L
import com.nxnson.vesnavi.R

/** Voluntary tips: Ko-fi (cards / PayPal) or a VietQR bank transfer. The app stays free and complete. */
private object Support {
    const val KO_FI_URL = "https://ko-fi.com/nxnson"
    const val BANK_NAME = "Techcombank"
    const val ACCOUNT_NUMBER = "270288886666"
}

@Composable
fun SupportScreen(nav: Navigator) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    SubScreen(L("Ủng hộ tác giả", "Support the author"), onBack = nav::pop) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                L(
                    "VesNavi miễn phí và đầy đủ tính năng. Nếu app có ích, bạn có thể mời tác giả một ly cà phê — hoàn toàn tự nguyện.",
                    "VesNavi is free and complete. If it's useful to you, you can buy the author a coffee — entirely optional.",
                ),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Support.KO_FI_URL))) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(L("☕ Ủng hộ qua Ko-fi (thẻ / PayPal)", "☕ Support on Ko-fi (card / PayPal)"), fontWeight = FontWeight.SemiBold) }

            FloatingCard(Modifier.fillMaxWidth()) {
                Text(L("Chuyển khoản ngân hàng (VietQR)", "Bank transfer in Vietnam (VietQR)"), fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally))
                Image(
                    painterResource(R.drawable.vietqr),
                    contentDescription = "VietQR ${Support.BANK_NAME} ${Support.ACCOUNT_NUMBER}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).align(Alignment.CenterHorizontally),
                )
                Text(
                    L("Chụp màn hình rồi mở app ngân hàng → Quét QR → chọn ảnh.", "Take a screenshot, then in your banking app choose Scan QR → from image."),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.align(Alignment.CenterHorizontally)) {
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(Support.BANK_NAME, Support.ACCOUNT_NUMBER))
                        copied = true
                    }) { Text(if (copied) L("✓ Đã chép", "✓ Copied") else L("Chép số TK", "Copy account no.")) }
                }
            }
        }
    }
}
