# PDF-ku

**Semua urusan PDF, satu aplikasi.**

PDF-ku adalah toolkit PDF Android offline-first dari Lara Studio.

## Fitur versi 1.0
- 📄 Membuka dan membaca PDF
- 🔎 Viewer dengan navigasi halaman
- 💾 Menyimpan halaman aktif menjadi PDF baru (split per halaman)
- 🔗 Menggabungkan beberapa PDF menjadi satu PDF
- 🖼️ Mengubah satu atau banyak gambar menjadi PDF
- ✍️ Membuat PDF dari teks
- 🕘 Daftar file PDF yang baru dibuka selama sesi
- 📤 Membagikan PDF melalui Android Share
- 🧰 Menu PDF Tools
- ⚙️ GitHub Actions untuk membangun APK debug

## Teknologi
- Android native Java
- Android PdfRenderer / PdfDocument
- AndroidX Core FileProvider
- Min SDK 26
- Target SDK 37
- Application ID: `com.larastudio.pdfku`
- AGP 9.4.0 / Gradle 9.6.0 / JDK 17

## Build
Workflow **Build PDF-ku APK** berjalan pada push ke `main` atau dapat dijalankan manual dari GitHub Actions. APK debug diunggah sebagai workflow artifact.

## Catatan
Fitur PDF yang membutuhkan manipulasi struktur internal seperti anotasi teks, bookmark, enkripsi password, kompresi tingkat lanjut, dan tanda tangan digital memerlukan modul PDF khusus dan direncanakan sebagai tahap lanjutan.


## PDF-ku 1.3.0
- Scan dokumen dengan multi-page, orientasi EXIF, auto-crop dan enhancement.
- JPG/Gambar → PDF dan PDF → JPG.
- Kompres, reorder/rotate/delete halaman, password, watermark.
- Pencarian/ekstraksi teks, anotasi, signature, dan dark viewer.
