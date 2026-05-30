package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.example.ui.AudioProcessorDashboard
import com.example.ui.theme.MyApplicationTheme

/**
 * Main Activity utama untuk aplikasi Equalizer & Sound Engine 'BRO EQ JOSSS'.
 * Menampilkan antarmuka panel visualizer dan editor DSP real-time.
 */
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        Surface(
          color = MaterialTheme.colorScheme.background
        ) {
          AudioProcessorDashboard()
        }
      }
    }
  }
}

