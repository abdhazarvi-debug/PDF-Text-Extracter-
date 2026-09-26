package com.abdhazarvi.pdftextextractor

data class SelectedPdf(val displayName: String, val uri: android.net.Uri)

data class WordBox(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float,
    val rtl: Boolean
)

data class PageResult(
    val pageNumber: Int,
    val text: String,
    val ocr: Boolean,
    val confidence: Float,
    val words: List<WordBox> = emptyList()
)

data class DocumentResult(val fileName: String, val pages: List<PageResult>)
