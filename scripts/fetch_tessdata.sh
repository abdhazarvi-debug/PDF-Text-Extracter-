#!/usr/bin/env bash
set -euo pipefail
mkdir -p app/src/main/assets/tessdata
for lang in eng ara urd; do
  curl -L --fail --retry 3 -o "app/src/main/assets/tessdata/$lang.traineddata"     "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/$lang.traineddata"
done
ls -lh app/src/main/assets/tessdata
