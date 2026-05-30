package com.example.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AudioDatabase
import com.example.data.PresetEntity
import com.example.data.PresetRepository
import com.example.dsp.AudioSignalGenerator
import com.example.service.AudioProcessorService
import com.example.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioProcessorDashboard() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Status inisialisasi Foreground Service
    var isServiceBound by remember { mutableStateOf(false) }
    var serviceInstance by remember { mutableStateOf<AudioProcessorService?>(null) }

    // Memantau instance service statis
    LaunchedEffect(Unit) {
        // Otomatis nyalakan service audio di awal
        try {
            val intent = Intent(context, AudioProcessorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Mengambil data real-time berkala demi 60FPS visual rendering
    // Kita gunakan polling yang sangat efisien berbasis coroutine jika Service aktif
    LaunchedEffect(isServiceBound) {
        while (true) {
            serviceInstance = AudioProcessorService.SERVICE_INSTANCE
            isServiceBound = serviceInstance != null
            kotlinx.coroutines.delay(100)
        }
    }

    // State data flows dari service (jika terhubung, jika belum fallback ke default nol)
    val activePresetName by (serviceInstance?.activePresetName ?: MutableStateFlow("Sedang Memuat...")).collectAsStateWithLifecycle()
    val activePreset by (serviceInstance?.activePreset ?: MutableStateFlow<PresetEntity?>(null)).collectAsStateWithLifecycle()
    
    val vuLeft by (serviceInstance?.vuLeft ?: MutableStateFlow(0f)).collectAsStateWithLifecycle()
    val vuRight by (serviceInstance?.vuRight ?: MutableStateFlow(0f)).collectAsStateWithLifecycle()
    val clipLeft by (serviceInstance?.clipLeft ?: MutableStateFlow(false)).collectAsStateWithLifecycle()
    val clipRight by (serviceInstance?.clipRight ?: MutableStateFlow(false)).collectAsStateWithLifecycle()
    
    val spectrumData by (serviceInstance?.spectrumData ?: MutableStateFlow(FloatArray(31) { 0f })).collectAsStateWithLifecycle()

    // Membaca Preset dari Database Room
    val database = remember { AudioDatabase.getDatabase(context) }
    val repository = remember { PresetRepository(database.presetDao()) }
    val presetList by repository.allPresets.collectAsStateWithLifecycle(initialValue = emptyList())

    // State Editor Lokal
    var selectedTab by remember { mutableStateOf(0) } // 0 = Equalizer, 1 = Compressor, 2 = Spasial FX
    var showPresetDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }
    var eqBandResolution by remember { mutableStateOf(1) } // 0 = 31 Band (Pro), 1 = 15 Band (Medium), 2 = 7 Band (Simpel)

    // Logika Request Perizinan Android (Sesuai Syarat No. 4 Metadata)
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

    var permissionsGranted by remember {
        mutableStateOf(
            permissionsToRequest.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) {
            serviceInstance?.checkAndStartVisualizer()
        } else {
            Toast.makeText(context, "Izin Audio diperlukan untuk mengaktifkan spectrum visualizer!", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            launcher.launch(permissionsToRequest)
        }
    }

    // Tampilan Dashboard Utama bertema Glassmorphic Dark-Neon
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = AudioDbBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. HEADER UTAMA: Judul Futuristik & Status Engine Audio
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_header_row"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "BRO EQ JOSSS",
                        fontWeight = FontWeight.Black,
                        fontSize = 28.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AudioNeonCyan,
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.testTag("title_app")
                    )
                    Text(
                        text = "RESPECT AUDIO ENGINE • SDK 35 PRO",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AudioTextSecondary,
                        letterSpacing = 1.5.sp
                    )
                }

                // Indikator Status Aktif
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x1F00E5FF))
                        .border(1.dp, Color(0x3300E5FF), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val blinkAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "blink"
                        )
                        
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (serviceInstance?.signalGenerator?.isPlaying == true) {
                                        AudioNeonGreen.copy(alpha = blinkAlpha)
                                    } else {
                                        Color.Gray
                                    }
                                )
                        )
                        Text(
                            text = if (serviceInstance?.signalGenerator?.isPlaying == true) "ENG. ACTIVE" else "ENG. STANDBY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (serviceInstance?.signalGenerator?.isPlaying == true) AudioNeonGreen else Color.Gray
                        )
                    }
                }
            }            // 2. INPUT DRAWER & GLOBAL AUDIO FILTER PANEL
            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.SettingsInputHdmi, contentDescription = "Source", tint = AudioNeonCyan)
                            Text(
                                text = "SUMBER INPUT: HP ANDROID",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = AudioTextPrimary
                            )
                        }

                        // Tombol ON/OFF EQ Bypass
                        Button(
                            onClick = {
                                serviceInstance?.let { service ->
                                    val triggerIntent = Intent(context, AudioProcessorService::class.java).apply {
                                        action = "TOGGLE_PLAYBACK"
                                    }
                                    context.startService(triggerIntent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (serviceInstance?.signalGenerator?.isPlaying == true) AudioNeonGreen else AudioNeonMagenta,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("btn_power_playback")
                        ) {
                            Icon(
                                imageVector = if (serviceInstance?.signalGenerator?.isPlaying == true) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                                contentDescription = "Toggle Equalizer Engine",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (serviceInstance?.signalGenerator?.isPlaying == true) "EQ AKTIF" else "EQ MATI",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        text = "Aplikasi akan memodifikasi suara perangkat secara global. Putar audio di aplikasi pihak ketiga seperti YouTube, Spotify, atau TikTok. Pengaturan Equalizer, Bass Boost, dan Reverb akan langsung berpengaruh secara real-time pada suara yang dihasilkan dari HP Anda!",
                        fontSize = 11.sp,
                        color = AudioTextSecondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 3. DISPLAY VISUAL UTAMA: DUAL GLOWING L/R VU METER & SPECTROGRAPH
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "SPECTRUM ANALYZER & MASTER VU METER L/R",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = AudioTextSecondary,
                        letterSpacing = 1.sp
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // KANVAS UTAMA: REAL-TIME 60FPS GRAPHIC SPECTRUM & BEZIER EQ CURVE OVERLAY
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF040508))
                                .border(1.dp, Color(0x1Fffffff), RoundedCornerShape(8.dp))
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val width = size.width
                                val height = size.height

                                // Draw Background Frequencies grid
                                val gridLineCount = 6
                                for (g in 1 until gridLineCount) {
                                    val y = height * (g.toFloat() / gridLineCount)
                                    drawLine(
                                        color = Color(0x0FFFFFFF),
                                        start = Offset(0f, y),
                                        end = Offset(width, y),
                                        strokeWidth = 1f
                                    )
                                }

                                // 3A. Draw 60FPS Spectrum Bars (Sinyal Hijau/Biru Neon melayang)
                                val barCount = 31
                                val gap = 2f
                                val barWidth = (width - (gap * (barCount - 1))) / barCount
                                
                                for (b in 0 until barCount) {
                                    val amp = if (b < spectrumData.size) spectrumData[b] else 0f
                                    // Batasi tinggi bar visual
                                    val barHeight = (amp * height).coerceIn(0f, height)
                                    
                                    val x = b * (barWidth + gap)
                                    val y = height - barHeight

                                    // Gradient glowing bar
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            colors = listOf(
                                                AudioNeonMagenta.copy(alpha = 0.8f),
                                                AudioNeonCyan.copy(alpha = 0.5f),
                                                AudioNeonCyan.copy(alpha = 0.1f)
                                            )
                                        ),
                                        topLeft = Offset(x, y),
                                        size = Size(barWidth, barHeight)
                                    )
                                }

                                // 3B. Draw Bezier Curve of EQ Gains Overlay
                                if (serviceInstance != null) {
                                    val path = Path()
                                    val gains = serviceInstance?.getEqGains() ?: FloatArray(31) { 0f }
                                    
                                    val points = mutableListOf<Offset>()
                                    for (i in 0 until 31) {
                                        val x = i * (width / 30f)
                                        // Rentang gain -12 s.d. +12 dB dipetakan ke tengah tinggi layar
                                        val gain = gains[i]
                                        val y = (height / 2f) - (gain / 12f) * (height / 2.5f)
                                        points.add(Offset(x, y))
                                    }

                                    path.moveTo(points[0].x, points[0].y)
                                    for (i in 1 until points.size) {
                                        val prev = points[i - 1]
                                        val curr = points[i]
                                        val cp1 = Offset(prev.x + (curr.x - prev.x) / 2f, prev.y)
                                        val cp2 = Offset(prev.x + (curr.x - prev.x) / 2f, curr.y)
                                        path.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, curr.x, curr.y)
                                    }

                                    // Gambar kurva garis bersinar
                                    drawPath(
                                        path = path,
                                        color = AudioNeonCyan,
                                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                                    )
                                }
                            }
                        }

                        // KANVAS VU METER: DOUBLE L/R LED GRAPHIC METER WITH CLIPPING LEDS
                        Column(
                            modifier = Modifier
                                .width(56.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF040508))
                                .border(1.dp, Color(0x1Fffffff), RoundedCornerShape(8.dp))
                                .padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("VU METER", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = AudioTextSecondary)
                            
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Left VU
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(Color(0xFF101015))
                                ) {
                                    val leftHeightFraction = (vuLeft).coerceIn(0f, 1f)
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val h = size.height
                                        val w = size.width
                                        
                                        // Draw segmented LED blocks
                                        val totalLeds = 14
                                        val blockH = (h / totalLeds) - 2
                                        for (i in 0 until totalLeds) {
                                            val fraction = (totalLeds - i).toFloat() / totalLeds
                                            val color = when {
                                                fraction > 0.85f -> if (leftHeightFraction >= fraction) AudioNeonRed else Color(0x33FF1744)
                                                fraction > 0.65f -> if (leftHeightFraction >= fraction) AudioNeonAmber else Color(0x33FFB300)
                                                else -> if (leftHeightFraction >= fraction) AudioNeonGreen else Color(0x3300E676)
                                            }
                                            
                                            drawRoundRect(
                                                color = color,
                                                topLeft = Offset(0f, i * (blockH + 2)),
                                                size = Size(w, blockH),
                                                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                                            )
                                        }
                                    }
                                }

                                // Right VU
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(Color(0xFF101015))
                                ) {
                                    val rightHeightFraction = (vuRight).coerceIn(0f, 1f)
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val h = size.height
                                        val w = size.width
                                        
                                        // Draw segmented LED blocks
                                        val totalLeds = 14
                                        val blockH = (h / totalLeds) - 2
                                        for (i in 0 until totalLeds) {
                                            val fraction = (totalLeds - i).toFloat() / totalLeds
                                            val color = when {
                                                fraction > 0.85f -> if (rightHeightFraction >= fraction) AudioNeonRed else Color(0x33FF1744)
                                                fraction > 0.65f -> if (rightHeightFraction >= fraction) AudioNeonAmber else Color(0x33FFB300)
                                                else -> if (rightHeightFraction >= fraction) AudioNeonGreen else Color(0x3300E676)
                                            }
                                            
                                            drawRoundRect(
                                                color = color,
                                                topLeft = Offset(0f, i * (blockH + 2)),
                                                size = Size(w, blockH),
                                                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                                            )
                                        }
                                    }
                                }
                            }

                            // Glowing Peak Clip LED Indicators
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (clipLeft) AudioNeonRed else Color(0x33FF1744))
                                        .border(1.dp, if (clipLeft) Color.White else Color.Transparent)
                                )
                                Text("CLIP", fontSize = 7.sp, fontWeight = FontWeight.Bold, color = AudioTextSecondary)
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (clipRight) AudioNeonRed else Color(0x33FF1744))
                                        .border(1.dp, if (clipRight) Color.White else Color.Transparent)
                                )
                            }
                        }
                    }
                }
            }

            // 4. PEMILIH PRESET AUDIO (DANGDUT, DUGEM, SHOLAWAT, ROCK, DLL)
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PRESET AUDIO JOSS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = AudioTextSecondary,
                        letterSpacing = 1.sp
                    )

                    // Menyimpan Preset Cust
                    IconButton(
                        onClick = {
                            newPresetName = ""
                            showPresetDialog = true
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Tambah Preset", tint = AudioNeonCyan)
                    }
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(presetList) { preset ->
                        val isPresetSelected = preset.isSelected
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isPresetSelected) Color(0x3DFF0066) else Color(0x1Fffffff))
                                .border(
                                    1.dp,
                                    if (isPresetSelected) AudioNeonMagenta else Color(0x1Fffffff),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    coroutineScope.launch {
                                        repository.selectPreset(preset.id)
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = preset.name,
                                    color = if (isPresetSelected) Color.White else AudioTextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = if (isPresetSelected) FontWeight.ExtraBold else FontWeight.Bold
                                )
                                if (!preset.isSystemPreset) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Hapus Custom Preset",
                                        tint = Color.Gray,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable {
                                                coroutineScope.launch {
                                                    repository.deletePresetById(preset.id)
                                                }
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. EDITOR PARAMETRIK KONTROL TABS (EQ / COMP / SPATIAL)
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = AudioNeonCyan,
                divider = {},
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = AudioNeonCyan,
                        height = 3.dp
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("31 BAND EQ", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("KOMPRESOR", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("EFEK SPASIAL", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
            }

            // ISI TAB YANG TERPILIH
            when (selectedTab) {
                0 -> {
                    // TAB EQ GRAFIS: 31, 15, atau 7 Band
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Segmented control untuk resolusi pita frekuensi
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF10111A))
                                .padding(2.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            listOf("7 BAND", "15 BAND", "31 BAND PRO").forEachIndexed { idx, resName ->
                                val isResSelected = eqBandResolution == idx
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isResSelected) Color(0x2E00E5FF) else Color.Transparent)
                                        .border(
                                            1.dp,
                                            if (isResSelected) AudioNeonCyan else Color.Transparent,
                                            RoundedCornerShape(6.dp)
                                        )
                                        .clickable { eqBandResolution = idx }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = resName,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isResSelected) AudioNeonCyan else AudioTextSecondary
                                    )
                                }
                            }
                        }

                        // Tampilkan Sliders Slider EQ sesuai Filter Resolusi
                        val fullGains = serviceInstance?.getEqGains() ?: FloatArray(31) { 0f }
                        
                        // Pemetaan indeks berdasarkan resolusi
                        val activeIndexes = when (eqBandResolution) {
                            2 -> (0..30).toList() // Tampilkan semua 31 band
                            1 -> (0..30 step 2).toList() // Tampilkan 15 band
                            else -> listOf(2, 6, 11, 17, 21, 25, 29) // 7 band (Sub-bass, Bass, Mid-bass, Mid, Mid-high, Treble, Air)
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxHeight(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                activeIndexes.forEach { bandIdx ->
                                    val fHz = serviceInstance?.eqFrequencies?.get(bandIdx) ?: 1000f
                                    val dbValue = if (bandIdx < fullGains.size) fullGains[bandIdx] else 0f
                                    
                                    VerticalEqSlider(
                                        label = formatFreq(fHz),
                                        value = dbValue,
                                        onValueChange = { newValue ->
                                            if (serviceInstance != null) {
                                                if (eqBandResolution == 2) {
                                                    // Ubah satu band tunggal
                                                    serviceInstance?.updateSingleEqBand(bandIdx, newValue)
                                                } else {
                                                    // Interpolasi bell-curve menyebar ke samping band tetangga agar kurva EQ melandai natural
                                                    applyBellCurveInterpolation(serviceInstance!!, bandIdx, newValue)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // TAB KOMPRESOR MASTER DINAMIS (THRESHOLD, RATIO, ATTACK, MAKEUP, DLL)
                    val thresh = serviceInstance?.getCompressorThreshold() ?: -12f
                    val ratio = serviceInstance?.getCompressorRatio() ?: 4f
                    val att = serviceInstance?.getCompressorAttack() ?: 10f
                    val rel = serviceInstance?.getCompressorRelease() ?: 100f
                    val makeup = serviceInstance?.getCompressorMakeup() ?: 0f

                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "KONFIGURASI MASTER COMPRESSOR & BRICKWALL LIMITER",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = AudioNeonCyan
                            )

                            // Threshold
                            DoubleSliderWidget(
                                label = "THRESHOLD",
                                valueText = "${thresh.toInt()} dB",
                                value = thresh,
                                valueRange = -48f..0f,
                                onValueChange = { serviceInstance?.updateCompressorThreshold(it) }
                            )

                            // Ratio
                            DoubleSliderWidget(
                                label = "COMP. RATIO",
                                valueText = String.format("%.1f:1", ratio),
                                value = ratio,
                                valueRange = 1f..12f,
                                onValueChange = { serviceInstance?.updateCompressorRatio(it) }
                            )

                            // Attack
                            DoubleSliderWidget(
                                label = "ATTACK TIME",
                                valueText = "${att.toInt()} ms",
                                value = att,
                                valueRange = 1f..150f,
                                onValueChange = { serviceInstance?.updateCompressorAttack(it) }
                            )

                            // Release
                            DoubleSliderWidget(
                                label = "RELEASE TIME",
                                valueText = "${rel.toInt()} ms",
                                value = rel,
                                valueRange = 20f..500f,
                                onValueChange = { serviceInstance?.updateCompressorRelease(it) }
                            )

                            // Makeup
                            DoubleSliderWidget(
                                label = "MAKEUP GAIN",
                                valueText = String.format("+%.1f dB", makeup),
                                value = makeup,
                                valueRange = 0f..16f,
                                onValueChange = { serviceInstance?.updateCompressorMakeup(it) }
                            )
                        }
                    }
                }
                2 -> {
                    // TAB EFEK SPASIAL (STEREO WIDENER, REVERB, SUBHARMONIC BASS)
                    val widthVal = serviceInstance?.getStereoWidth() ?: 1.0f
                    val bassVal = serviceInstance?.bassBoostDb ?: 0.0f
                    val reverbVal = serviceInstance?.getReverbLevel() ?: 0.0f

                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "PENGEMBANG AMBIENT 3D SPATIAL & SUB-BASS",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = AudioNeonMagenta
                            )

                            // Subharmonic Bass Booster
                            DoubleSliderWidget(
                                label = "SUBHARMONIC BASS BOOSTER 🔊",
                                valueText = String.format("+%.1f dB", bassVal),
                                value = bassVal,
                                valueRange = 0f..12f,
                                onValueChange = { serviceInstance?.bassBoostDb = it }
                            )

                            // Stereo Widener
                            DoubleSliderWidget(
                                label = "STEREO IMAGE WIDENER 🗺️",
                                valueText = when {
                                    widthVal < 0.1f -> "MONO MURNI"
                                    widthVal <= 1.0f -> String.format("%.0f%% (Normal)", widthVal * 100f)
                                    else -> String.format("%.0f%% (Ultra Wide)", widthVal * 100f)
                                },
                                value = widthVal,
                                valueRange = 0f..2.5f,
                                onValueChange = { serviceInstance?.updateStereoWidth(it) }
                            )

                            // 3D Reverb
                            DoubleSliderWidget(
                                label = "3D SPACE REVERB GEMA 🕌",
                                valueText = String.format("%.0f%% Wet", reverbVal * 100f),
                                value = reverbVal,
                                valueRange = 0f..0.85f,
                                onValueChange = { serviceInstance?.updateReverbLevel(it) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialog Pengisian Nama Preset baru
    if (showPresetDialog) {
        AlertDialog(
            onDismissRequest = { showPresetDialog = false },
            title = { Text("SIMPAN PRESET KUSTOM", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Simpan konfigurasi 31-Band dan efek audio saat ini agar tidak hilang.", fontSize = 12.sp, color = AudioTextSecondary)
                    TextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        placeholder = { Text("Asyik Abis Presets...") },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF10111A),
                            unfocusedContainerColor = Color(0xFF10111A),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPresetName.isNotBlank()) {
                            serviceInstance?.saveCustomPreset(newPresetName.trim())
                            showPresetDialog = false
                        }
                    }
                ) {
                    Text("SIMPAN", color = AudioNeonCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPresetDialog = false }) {
                    Text("BATAL", color = Color.Gray)
                }
            },
            containerColor = AudioDbSurface
        )
    }
}

/**
 * Menerapkan bell-curve damping untuk mengubah band-band di sekitar band terpilih
 * agar tarikan garis EQ visual terasa melandai luas, menyalin fungsi equalizer analog.
 */
fun applyBellCurveInterpolation(service: AudioProcessorService, centerIdx: Int, targetGainDb: Float) {
    service.updateSingleEqBand(centerIdx, targetGainDb)
    
    // Rentang penyebaran bell: 4 band ke kiri, 4 band ke kanan
    val range = 4
    for (offset in 1..range) {
        // Redaman kuadratik (Gaussian-bell shape style)
        val factor = cos((offset.toFloat() / (range + 1)) * (PI / 2)).toFloat()
        val deltaGain = targetGainDb * factor

        val leftIdx = centerIdx - offset
        if (leftIdx >= 0) {
            // Berikan penengah / interpolasi dengan gain asal band tersebut
            val currentLeft = service.getEqGains()[leftIdx]
            val blendedGain = currentLeft + (deltaGain - currentLeft) * 0.45f
            service.updateSingleEqBand(leftIdx, blendedGain)
        }

        val rightIdx = centerIdx + offset
        if (rightIdx < 31) {
            val currentRight = service.getEqGains()[rightIdx]
            val blendedGain = currentRight + (deltaGain - currentRight) * 0.45f
            service.updateSingleEqBand(rightIdx, blendedGain)
        }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AudioDbSurface)
            .border(1.dp, AudioGlassBorder, RoundedCornerShape(16.dp))
    ) {
        Column(content = content)
    }
}

@Composable
fun WaveButton(
    name: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Color(0x2B00E5FF) else Color(0x0AFFFFFF))
            .border(1.dp, if (isSelected) AudioNeonCyan else Color(0x13FFFFFF), RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) AudioNeonCyan else AudioTextSecondary
        )
    }
}

@Composable
fun VerticalEqSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(42.dp)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = String.format("%+.1fdB", value),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = if (abs(value) > 0.1f) AudioNeonCyan else AudioTextSecondary,
            fontFamily = FontFamily.Monospace
        )

        // Custom Compact Vertical Slider Track
        Box(
            modifier = Modifier
                .weight(1f)
                .width(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F1018))
                .border(1.dp, Color(0x1Fffffff), RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        // Hitung ratio tinggi letak sentuh
                        val positionY = change.position.y
                        val containerHeight = size.height
                        if (containerHeight > 0) {
                            val fraction = 1f - (positionY / containerHeight).coerceIn(0f, 1f)
                            // Petakan balik fraction [0, 1] ke gain [-12, +12]
                            val mappedDb = (fraction * 24f) - 12f
                            onValueChange(mappedDb)
                        }
                    }
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            // Fill level track (glowing gradient)
            val fillFraction = ((value + 12f) / 24f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fillFraction)
                    .background(
                        Brush.verticalGradient(
                            listOf(AudioNeonMagenta, AudioNeonCyan)
                        )
                    )
            )

            // Fader Knob Visualizer
            Box(
                modifier = Modifier
                    .offset(y = 0.dp) // Letak knob menyatu dengan fill
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Color.White)
                    .border(1.dp, Color.Black)
            )
        }

        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            color = AudioTextPrimary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun DoubleSliderWidget(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AudioTextSecondary)
            Text(valueText, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AudioNeonCyan, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = AudioNeonCyan,
                activeTrackColor = AudioNeonCyan,
                inactiveTrackColor = Color(0x13FFFFFF)
            )
        )
    }
}

private fun formatFreq(hz: Float): String {
    return if (hz >= 1000f) {
        val khz = hz / 1000f
        if (khz % 1.0f == 0.0f) {
            "${khz.toInt()}k"
        } else {
            String.format("%.1fk", khz)
        }
    } else {
        if (hz % 1.0f == 0.0f) {
            "${hz.toInt()}"
        } else {
            String.format("%.1f", hz)
        }
    }
}

private fun getWaveName(type: AudioSignalGenerator.WaveformType): String {
    return when(type) {
        AudioSignalGenerator.WaveformType.SINE -> "SINE BASS 🎵"
        AudioSignalGenerator.WaveformType.SQUARE -> "SQUARE DIRT 🎚️"
        AudioSignalGenerator.WaveformType.TRIANGLE -> "TRI RETRO 🕹️"
        AudioSignalGenerator.WaveformType.SAWTOOTH -> "SAW LEAD ⚡"
        AudioSignalGenerator.WaveformType.WHITE_NOISE -> "NOISE TEST 💨"
        AudioSignalGenerator.WaveformType.AMBIENT_BEAT -> "TECHNO BEAT 🚀"
    }
}
