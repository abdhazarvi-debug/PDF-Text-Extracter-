package com.abdhazarvi.pdftextextractor

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val selected = mutableListOf<SelectedPdf>()
    private val results = mutableListOf<DocumentResult>()
    private var job: Job? = null

    private lateinit var processor: PdfProcessor
    private lateinit var tvQueue: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvPreview: TextView
    private lateinit var progress: ProgressBar
    private lateinit var spinnerMode: Spinner
    private lateinit var spinnerFormat: Spinner
    private lateinit var etPages: EditText
    private lateinit var cbEng: CheckBox
    private lateinit var cbUrd: CheckBox
    private lateinit var cbAra: CheckBox
    private lateinit var btnProcess: Button
    private lateinit var btnCancel: Button

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
            if (selected.none { it.uri == uri }) selected += SelectedPdf(name, uri)
        }
        refreshQueue()
    }

    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null || results.isEmpty()) return@registerForActivityResult
        val data = renderOutput(spinnerFormat.selectedItem.toString())
        contentResolver.openOutputStream(uri)?.use {
            it.write(data.toByteArray(Charsets.UTF_8))
        }
        tvStatus.text = "Saved successfully."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        processor = PdfProcessor(this)
        bindViews()
        setupSpinners()
        refreshQueue()
    }

    private fun bindViews() {
        tvQueue = findViewById(R.id.tvQueue)
        tvStatus = findViewById(R.id.tvStatus)
        tvPreview = findViewById(R.id.tvPreview)
        progress = findViewById(R.id.progress)
        spinnerMode = findViewById(R.id.spinnerMode)
        spinnerFormat = findViewById(R.id.spinnerFormat)
        etPages = findViewById(R.id.etPages)
        cbEng = findViewById(R.id.cbEng)
        cbUrd = findViewById(R.id.cbUrd)
        cbAra = findViewById(R.id.cbAra)
        btnProcess = findViewById(R.id.btnProcess)
        btnCancel = findViewById(R.id.btnCancel)

        findViewById<Button>(R.id.btnAdd).setOnClickListener {
            picker.launch(arrayOf("application/pdf"))
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            job?.cancel()
            selected.clear()
            results.clear()
            refreshQueue()
            tvPreview.text = "Extracted text will appear here."
            tvStatus.text = "Ready"
            progress.progress = 0
        }
        btnProcess.setOnClickListener { startProcessing() }
        btnCancel.setOnClickListener { job?.cancel() }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            if (results.isEmpty()) tvStatus.text = "Run extraction first."
            else {
                val ext = spinnerFormat.selectedItem.toString().lowercase(Locale.US)
                saveLauncher.launch("pdf-extracted.K{ext}")
            }
        }
    }

    private fun setupSpinners() {
        spinnerMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Auto", "Text only", "OCR"))
        spinnerFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("TXT", "JSON", "MD", "HTML", "CSV"))
    }

    private fun refreshQueue() {
        tvQueue.text = if (selected.isEmpty()) "No PDF selected."
        else selected.mapIndexed { i, p -> "K{i + 1}. K{p.displayName}" }.joinToString("\n")
    }

    private fun languages(): String = buildList {
        if (cbEng.isChecked) add("eng")
        if (cbUrd.isChecked) add("urd")
        if (cbAra.isChecked) add("ara")
    }.ifEmpty { listOf("eng") }.joinToString("+")

    private fun startProcessing() {
        if (selected.isEmpty()) {
            tvStatus.text = "Add at least one PDF."
            return
        }

        job?.cancel()
        results.clear()
        progress.progress = 0
        btnProcess.isEnabled = false
        btnCancel.isEnabled = true

        val mode = when (spinnerMode.selectedItemPosition) {
            1 -> ExtractMode.TEXT
            2 -> ExtractMode.OCR
            else -> ExtractMode.AUTO
        }

        job = lifecycleScope.launch {
            try {
                selected.forEachIndexed { fileIndex, pdf ->
                    tvStatus.text = "Processing K{fileIndex + 1}/K{selected.size}: K{pdf.displayName}"
                    val result = processor.process(pdf, mode, languages(), etPages.text.toString()) { done, total, preview ->
                        val percent = (((fileIndex + done.toDouble() / total) / selected.size) * 100).toInt()
                        progress.progress = percent
                        tvPreview.text = preview
                    }
                    results += result
                    tvPreview.text = renderPreview(result)
                }
                progress.progress = 100
                tvStatus.text = "Done — K{results.size} file(s)."
            } catch (_: CancellationException) {
                tvStatus.text = "Cancelled."
            } catch (e: Exception) {
                tvStatus.text = "Error: K{e.message ?: e.javaClass.simpleName}"
            } finally {
                btnProcess.isEnabled = true
                btnCancel.isEnabled = false
            }
        }
    }

    private fun renderPreview(doc: DocumentResult): String =
        doc.pages.joinToString("\n\n") { "— Page K{it.pageNumber} —\nK{it.text}" }

    private fun renderOutput(format: String): String = when (format.uppercase(Locale.US)) {
        "JSON" -> processor.toJson(results)
        "MD" -> results.joinToString("\n\n") { doc ->
            "# K{doc.fileName}\n\n" + doc.pages.joinToString("\n\n") { p ->
                "## Page K{p.pageNumber}\n\nK{p.text}"
            }
        }
        "HTML" -> buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\"></head><body>")
            results.forEach { doc ->
                append("<h1>K{escape(doc.fileName)}</h1>")
                doc.pages.forEach { p ->
                    append("<h2>Page K{p.pageNumber}</h2><pre dir=\"auto\">K{escape(p.text)}</pre>")
                }
            }
            append("</body></html>")
        }
        "CSV" -> buildString {
            append("file,page,ocr,confidence,text\n")
            results.forEach { doc ->
                doc.pages.forEach { p ->
                    append(csv(doc.fileName)).append(',')
                        .append(p.pageNumber).append(',')
                        .append(p.ocr).append(',')
                        .append(p.confidence).append(',')
                        .append(csv(p.text)).append('\n')
                }
            }
        }
        else -> results.joinToString("\n\n") { doc ->
            "===== K{doc.fileName} =====\n" +
                doc.pages.joinToString("\n\n") { p -> "----- Page K{p.pageNumber} -----\nK{p.text}" }
        }
    }

    private fun csv(value: String): String =
        "\"" + value.replace("\"", "\"\"").replace("\n", " ") + "\""

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")
}
