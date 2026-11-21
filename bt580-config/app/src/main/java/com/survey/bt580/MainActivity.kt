package com.survey.bt580

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var ble: BleClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleClient(applicationContext)
        setContent {
            MaterialTheme {
                App(ble, onShare = { shareText(it) })
            }
        }
    }

    override fun onDestroy() {
        ble.release()
        super.onDestroy()
    }

    private fun shareText(text: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(i, "Поделиться логом"))
    }
}

@Composable
fun App(ble: BleClient, onShare: (String) -> Unit) {
    val context = LocalContext.current
    val needed = remember {
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) ble.startScan() else ble.statusText = "Нет разрешения для Bluetooth"
    }
    val startScan: () -> Unit = {
        val ok = needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (ok) ble.startScan() else launcher.launch(needed)
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ble.statusText,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Medium
                )
                if (ble.linkUp) {
                    OutlinedButton(onClick = { ble.disconnect() }) { Text("Отключить") }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (ble.connected) ConnectedScreen(ble, onShare) else ScanScreen(ble, startScan)
        }
    }
}

@Composable
fun ScanScreen(ble: BleClient, startScan: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Button(
            onClick = startScan,
            enabled = !ble.scanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (ble.scanning) "Поиск..." else "Найти устройства")
        }
        Spacer(Modifier.height(8.dp))
        val sorted = ble.devices.sortedWith(
            compareByDescending<FoundDevice> { it.name.contains("BT580", ignoreCase = true) }
                .thenByDescending { it.rssi }
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(sorted, key = { it.address }) { d ->
                val mine = d.name.contains("BT580", ignoreCase = true)
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable { ble.connect(d.address) }
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            d.name,
                            fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal
                        )
                        Text("${d.address}  •  ${d.rssi} dBm", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectedScreen(ble: BleClient, onShare: (String) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Настройка") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Данные") })
        }
        Spacer(Modifier.height(8.dp))
        if (tab == 0) SettingsTab(ble) else DataTab(ble, onShare)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
    )
}

@Composable
fun SettingsTab(ble: BleClient) {
    var custom by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Ответ адаптера", fontSize = 12.sp)
                Text(
                    if (ble.lastReply.isEmpty()) "—" else ble.lastReply,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        SectionTitle("Скорость порта RS232 (бод)")
        BleClient.BAUD_RATES.toList().chunked(4).forEachIndexed { row, list ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                list.forEachIndexed { i, rate ->
                    val code = row * 4 + i
                    Button(
                        onClick = { ble.sendAt("AT+BAUDRATE=$code") },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        Text("$rate", maxLines = 1)
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { ble.sendAt("AT+BAUDRATE=?") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Какая скорость сейчас?")
        }
        Text(
            "Новая скорость заработает после перезапуска адаптера. " +
                "Связь по Bluetooth при этом прервётся, подключитесь заново.",
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp)
        )

        SectionTitle("Адаптер")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = { ble.sendAt("AT") },
                modifier = Modifier.weight(1f)
            ) { Text("Проверка (AT)") }
            Button(
                onClick = { ble.sendAt("AT+RESTART") },
                modifier = Modifier.weight(1f)
            ) { Text("Перезапуск") }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedButton(
                onClick = { ble.sendAt("AT+AUTOSLEEP=1") },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("Автосон: вкл", maxLines = 1) }
            OutlinedButton(
                onClick = { ble.sendAt("AT+AUTOSLEEP=0") },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) { Text("Автосон: выкл", maxLines = 1) }
        }

        SectionTitle("Своя команда")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("например AT+BAUDRATE=?") }
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = { if (custom.isNotBlank()) ble.sendAt(custom.trim()) }) { Text("OK") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = ble.atCrLf, onCheckedChange = { ble.atCrLf = it })
            Text("добавлять CR LF к AT-командам", fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun kindColor(kind: Int): Color = when (kind) {
    0 -> Color.Gray
    1 -> Color(0xFF1565C0)
    3 -> Color(0xFF6A1B9A)
    4 -> Color(0xFFE65100)
    else -> Color.Black
}

private fun kindPrefix(kind: Int): String = when (kind) {
    0 -> "• "
    1 -> "→ "
    2 -> "← "
    3 -> "AT→ "
    4 -> "AT← "
    else -> ""
}

@Composable
fun DataTab(ble: BleClient, onShare: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(ble.logVersion) {
        if (ble.log.isNotEmpty()) listState.scrollToItem(ble.log.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = { ble.hex = !ble.hex },
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) { Text(if (ble.hex) "HEX" else "ASCII") }
            OutlinedButton(
                onClick = { ble.clearLog() },
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) { Text("Очистить") }
            OutlinedButton(
                onClick = { onShare(ble.logText()) },
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) { Text("Поделиться") }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Отправка через:", fontSize = 13.sp)
            listOf("FFF3", "FFF1").forEach { name ->
                if (ble.dataChannel == name) {
                    Button(
                        onClick = { ble.dataChannel = name },
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) { Text(name) }
                } else {
                    OutlinedButton(
                        onClick = { ble.dataChannel = name },
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) { Text(name) }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFFF2F2F2))
                .padding(4.dp)
        ) {
            items(ble.log) { line ->
                Text(
                    kindPrefix(line.kind) + line.text,
                    color = kindColor(line.kind),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(if (ble.hex) "HEX, например 01 02 FF" else "Отправить на прибор") }
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = {
                ble.sendData(input)
                input = ""
            }) { Text("OK") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = ble.dataCrLf, onCheckedChange = { ble.dataCrLf = it })
            Text("добавлять CR LF", fontSize = 13.sp)
        }
    }
}
