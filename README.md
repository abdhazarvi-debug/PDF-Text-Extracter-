# PDF Text Extractor

Offline-first Android PDF text extraction and OCR for Urdu, Arabic and English.

This repository contains the mobile application. It extracts native PDF text first, falls back to offline Tesseract OCR for scanned pages, keeps RTL/LTR text in logical Unicode order, and can export TXT/JSON/Markdown/HTML/CSV.

OCR model files are fetched during CI and packaged into the APK, so the built application does not need a cloud OCR service or a network connection to process documents.


Build status: Android APK is built automatically from main.


UI interpolation fix applied.
