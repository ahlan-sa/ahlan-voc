package com.fbint.collector.ui.setup

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import com.fbint.collector.data.repository.ConfigRepository
import com.fbint.collector.ui.nav.Routes
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class QrGenerateViewModel @Inject constructor(
    private val config: ConfigRepository,
) : ViewModel() {
    fun buildPayload(): String? {
        val baseUrl = config.baseUrl() ?: return null
        val apiKey = config.apiKey() ?: return null
        val envId = config.environmentId() ?: return null
        val name = config.projectName()
        return SetupConfigCodec.encode(SetupConfig(baseUrl, apiKey, envId, name, config.workspaceId()))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrGenerateScreen(
    nav: NavHostController,
    vm: QrGenerateViewModel = hiltViewModel(),
) {
    val payload = remember { vm.buildPayload() }
    val bitmap = remember(payload) { payload?.let { encodeQr(it, 720) } }

    Scaffold(topBar = { TopAppBar(title = { Text("Setup QR") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Have each surveyor scan this code from their device. It includes the server, workspace settings, and API key — keep it private.",
            )
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Setup QR", modifier = Modifier.fillMaxSize())
                } else {
                    Text("Missing config — re-run admin setup.")
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { nav.navigate(Routes.SURVEYOR_ID) { launchSingleTop = true } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use this device as a surveyor too") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { nav.popBackStack() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Done") }
        }
    }
}

private fun encodeQr(text: String, sizePx: Int): Bitmap? = try {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val w = matrix.width; val h = matrix.height
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    for (x in 0 until w) for (y in 0 until h) {
        bmp.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
    }
    bmp
} catch (_: Throwable) { null }
