package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.service.AudioProcessorService

/**
 * BroadcastReceiver untuk mendeteksi boot ulang perangkat Android (RECEIVE_BOOT_COMPLETED).
 * Mengizinkan engine 'BRO EQ JOSSS' otomatis menyala di latar belakang demi kenyamanan pengguna.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, AudioProcessorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
