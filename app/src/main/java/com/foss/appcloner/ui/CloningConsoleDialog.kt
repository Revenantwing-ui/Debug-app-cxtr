package com.foss.appcloner.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.foss.appcloner.service.CloningService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CloningConsoleDialog : DialogFragment() {

    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnClose: Button
    private lateinit var btnSave: Button
    
    private val fullLog = StringBuilder()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Cloning Terminal")
        
        // Simple programmatic layout to avoid XML complexity for this single view
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        scrollView = ScrollView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1.0f
            )
            setBackgroundColor(0xFF000000.toInt()) // Black background
        }

        logTextView = TextView(context).apply {
            setTextColor(0xFF00FF00.toInt()) // Green text (Terminal style)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setPadding(16, 16, 16, 16)
            text = "Initializing...\n"
        }
        
        scrollView.addView(logTextView)
        layout.addView(scrollView)

        // Buttons container
        val btnContainer = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }

        btnSave = Button(context).apply {
            text = "Save Report"
            visibility = View.GONE
            setOnClickListener { saveLogReport() }
        }

        btnClose = Button(context).apply {
            text = "Close"
            visibility = View.GONE
            setOnClickListener { dismiss() }
        }

        btnContainer.addView(btnSave)
        btnContainer.addView(btnClose)
        layout.addView(btnContainer)

        builder.setView(layout)
        builder.setCancelable(false) // User cannot dismiss by tapping outside
        
        return builder.create()
    }

    override fun onStart() {
        super.onStart()
        // Subscribe to live logs
        lifecycleScope.launch {
            CloningService.logFlow.collectLatest { msg ->
                appendLog(msg)
                
                // Auto-dismiss logic on success
                if (msg.contains("SUCCESS: Cloned app created")) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        dismiss() 
                    }, 2000) // Disappear after 2 seconds on success
                }

                // Show options on failure
                if (msg.startsWith("ERROR:")) {
                    btnSave.visibility = View.VISIBLE
                    btnClose.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun appendLog(msg: String) {
        fullLog.append(msg).append("\n")
        logTextView.append("$msg\n")
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun saveLogReport() {
        try {
            val fileName = "CloneReport_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
            val file = File(requireContext().getExternalFilesDir("reports"), fileName)
            file.parentFile?.mkdirs()
            file.writeText(fullLog.toString())
            appendLog("\n>> Report saved to: ${file.absolutePath}")
        } catch (e: Exception) {
            appendLog("\n>> Failed to save report: ${e.message}")
        }
    }

    companion object {
        const val TAG = "CloningConsoleDialog"
    }
}
