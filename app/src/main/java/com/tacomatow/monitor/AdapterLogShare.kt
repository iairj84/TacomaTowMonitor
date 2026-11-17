package com.tacomatow.monitor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Turns the in-app adapter log into something that can be pasted into a
 * message. The surrounding context matters as much as the log: a "NO DATA"
 * line means nothing without knowing which adapter, protocol and app build
 * produced it.
 */
object AdapterLogShare {

    fun build(
        versionName: String,
        versionCode: Int,
        adapterName: String?,
        connected: Boolean,
        protocol: String?,
        voltage: Float?,
        lines: List<String>,
    ): String = buildString {
        appendLine("Tacoma Tow Monitor $versionName ($versionCode)")
        appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
        appendLine("Adapter: ${adapterName ?: "none"}${if (connected) " (connected)" else " (not connected)"}")
        protocol?.let { appendLine("Protocol: $it") }
        voltage?.let { appendLine("Adapter voltage: %.1f V".format(it)) }
        appendLine("--- adapter log ---")
        if (lines.isEmpty()) {
            appendLine("(nothing recorded yet)")
        } else {
            lines.forEach { appendLine(it) }
        }
    }

    fun share(
        context: Context,
        text: String,
        subject: String = "Tacoma Tow Monitor — adapter log",
    ) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, subject))
    }

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Adapter log", text))
    }
}
