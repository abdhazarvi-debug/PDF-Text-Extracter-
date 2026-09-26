package com.abdhazarvi.pdftextextractor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

enum class ExtractMode { AUTO, TEXT, OCR }

class PdfProcessor(private val context: Context) {
    init { PDFBoxResourceLoader.init(context.applicationContext) }

    private fun parsePages(spec: String, count: Int): Set<Int> {
        if (spec.isBlank()) return (1..count).toSet()
        val out = linkedSetOf<Int>()
        spec.split(',').map(String::trim).filter(String::isNotEmpty).forEach { token ->
            if ('-' in token) {
                val p = token.split('-', limit = 2)
                val a = p[0].toIntOrNull()
                val b = p[1].toIntOrNull()
                if (a != null && b != null) for (n in minOf(a, b)..maxOf(a, b)) if (n in 1..count) out += n
            } else token.toIntOrNull()?.let { if (it in 1..count) out += it }
        }
        return out
    }

    private fun copyToCache(uri: Uri, name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(context.cacheDir, "pdf_" + System.nanoTime() + "_" + safe)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open PDF." }
            FileOutputStream(file).use { output -> input.copyTo(output) }
        }
        return file
    }

    suspend fun process(
        selected: SelectedPdf,
        mode: ExtractMode,
        languages: String,
        pageSpec: String,
        onPage: suspend (done: Int, total: Int, preview: String) -> Unit
    ): DocumentResult = withContext(Dispatchers.IO) {
        val temp = copyToCache(selected.uri, selected.displayName)
        try {
            val pageCount = PDDocument.load(temp).use { it.numberOfPages }
            val wanted = parsePages(pageSpec, pageCount).ifEmpty { (1..pageCount).toSet() }
            val native = HashMap<Int, String>()

            if (mode != ExtractMode.OCR) {
                PDDocument.load(temp).use { doc ->
                    val stripper = PDFTextStripper()
                    for (page in 1..pageCount) {
                        coroutineContext.ensureActive()
                        stripper.startPage = page
                        stripper.endPage = page
                        native[page] = stripper.getText(doc).trim()
                    }
                }
            }

            val results = mutableListOf<PageResult>()
            val needsOcr = wanted.filter { p ->
                mode == ExtractMode.OCR || (mode == ExtractMode.AUTO && native[p].orEmpty().length < 20)
            }.toSet()

            var done = 0
            val rendererPfd = if (needsOcr.isNotEmpty()) context.contentResolver.openFileDescriptor(selected.uri, "r") else null
            try {
                val renderer = rendererPfd?.let { PdfRenderer(it) }
                val tess = if (needsOcr.isNotEmpty()) TessBaseAPI() else null

                try {
                    if (needsOcr.isNotEmpty()) {
                        requireNotNull(renderer)
                        val dataPath = TessData.ensure(context)
                        requireNotNull(tess)
                        require(tess.init(dataPath, languages, TessBaseAPI.OEM_LSTM_ONLY)) {
                            "OCR language data is missing."
                        }
                        tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                    }

                    for (pageNumber in wanted.sorted()) {
                        coroutineContext.ensureActive()
                        val nativeText = native[pageNumber].orEmpty()
                        if (!needsOcr.contains(pageNumber)) {
                            results += PageResult(pageNumber, nativeText, false, 100f)
                        } else {
                            val page = renderer!!.openPage(pageNumber - 1)
                            val scale = 2.5f
                            val width = (page.width * scale).toInt().coerceAtMost(3200)
                            val height = (page.height * scale).toInt().coerceAtMost(4400)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_OCR)
                            page.close()

                            tess!!.setImage(bitmap)
                            val words = mutableListOf<WordBox>()
                            val it = tess.resultIterator
                            if (it != null) {
                                it.begin()
                                do {
                                    val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                                    if (text.isNotBlank()) {
                                        val rect = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                                        words += WordBox(
                                            text, rect.left, rect.top, rect.right, rect.bottom,
                                            it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD),
                                            isRtl(text)
                                        )
                                    }
                                } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                                it.delete()
                            }

                            val lines = orderIntoLines(words)
                            val text = lines.joinToString("\n") { line -> line.joinToString(" ") }
                            val confidence = if (words.isEmpty()) 0f else words.map { it.confidence }.average().toFloat()
                            results += PageResult(pageNumber, text.trim(), true, confidence, words)
                            bitmap.recycle()
                        }
                        done++
                        onPage(done, wanted.size, results.last().text.take(12000))
                    }
                } finally {
                    tess?.recycle()
                    renderer?.close()
                }
            } finally {
                rendererPfd?.close()
            }

            DocumentResult(selected.displayName, results.sortedBy(PageResult::pageNumber))
        } finally {
            temp.delete()
        }
    }

    private fun isRtl(text: String): Boolean =
        text.any { c -> (c.code in 0x0590..0x08FF) || (c.code in 0xFB1D..0xFEFF) }

    private fun orderIntoLines(words: List<WordBox>): List<List<WordBox>> {
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<MutableList<WordBox>>()
        for (word in words.sortedWith(compareBy<WordBox> { it.top }.thenBy { it.left })) {
            val center = (word.top + word.bottom) / 2
            val current = lines.lastOrNull()
            val currentCenter = current?.map { (it.top + it.bottom) / 2 }?.average()?.toInt()
            val tolerance = max(12, (word.bottom - word.top) * 2 / 3)
            if (current != null && currentCenter != null && abs(center - currentCenter) <= tolerance) {
                current += word
            } else {
                lines += mutableListOf(word)
            }
        }
        return lines.map { line ->
            val rtl = line.count(WordBox::rtl) > line.size / 2
            if (rtl) line.sortedByDescending(WordBox::left) else line.sortedBy(WordBox::left)
        }
    }

    fun toJson(results: List<DocumentResult>): String {
        val root = JSONArray()
        results.forEach { doc ->
            val d = JSONObject().put("fileName", doc.fileName)
            val pages = JSONArray()
            doc.pages.forEach { page ->
                val p = JSONObject()
                    .put("page", page.pageNumber)
                    .put("ocr", page.ocr)
                    .put("confidence", page.confidence)
                    .put("text", page.text)
                val words = JSONArray()
                page.words.forEach { w ->
                    words.put(JSONObject()
                        .put("text", w.text)
                        .put("left", w.left)
                        .put("top", w.top)
                        .put("right", w.right)
                        .put("bottom", w.bottom)
                        .put("confidence", w.confidence)
                        .put("rtl", w.rtl))
                }
                p.put("words", words)
                pages.put(p)
            }
            d.put("pages", pages)
            root.put(d)
        }
        return root.toString(2)
    }

    object TessData {
        fun ensure(context: Context): String {
            val root = File(context.filesDir, "tesseract")
            val dir = File(root, "tessdata")
            dir.mkdirs()
            for (name in listOf("eng.traineddata", "urd.traineddata", "ara.traineddata")) {
                val target = File(dir, name)
                if (!target.exists() || target.length() < 100_000) {
                    context.assets.open("tessdata/$name").use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                }
            }
            return root.absolutePath + File.separator
        }
    }
}
