package com.fbint.collector.ui.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

const val APP_DOWNLOAD_URL = "https://github.com/ahlan-sa/ahlan-voc/releases/latest/download/app-debug.apk"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQrScreen(nav: NavHostController) {
    val bitmap = remember { encodeQr(APP_DOWNLOAD_URL, 900) }
    val uriHandler = LocalUriHandler.current
    Scaffold(topBar = { TopAppBar(title = { Text("Download Ahlan VOC") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Scan to download the latest Android app from GitHub.")
            Spacer(Modifier.height(20.dp))
            bitmap?.let {
                Image(it.asImageBitmap(), contentDescription = "Download Ahlan VOC QR code",
                    modifier = Modifier.fillMaxWidth().weight(1f))
            }
            Text("This download QR is safe to share. Set up the app separately after installing.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { uriHandler.openUri(APP_DOWNLOAD_URL) }) { Text("Open download") }
            TextButton(onClick = { nav.popBackStack() }) { Text("Done") }
        }
    }
}
