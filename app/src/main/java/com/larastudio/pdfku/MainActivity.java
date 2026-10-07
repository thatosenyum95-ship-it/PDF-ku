package com.larastudio.pdfku;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_PDF = 1, REQ_IMG = 2, REQ_MERGE = 3, REQ_SPLIT = 4, REQ_SAVE_AS = 5;
    private static final String PREFS = "pdfku_prefs";
    private static final String RECENT = "recent_uris";

    private LinearLayout content;
    private TextView status;
    private ImageView image;
    private PdfRenderer renderer;
    private ParcelFileDescriptor fd;
    private int page = 0;
    private Uri openedUri;
    private final ArrayList<Uri> recent = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private File pendingSaveFile;
    private String pendingSaveName;

    int dp(int x) {
        return (int) (x * getResources().getDisplayMetrics().density + .5f);
    }

    TextView tx(String s, int z) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(z);
        t.setTextColor(Color.WHITE);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        return t;
    }

    Button bt(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        return b;
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        PDFBoxResourceLoader.init(getApplicationContext());
        loadRecent();
        home();
    }

    void shell(String h) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16), dp(16), dp(16), dp(10));
        r.setBackgroundColor(Color.rgb(15, 23, 42));

        TextView t = tx(h, 28);
        t.setTypeface(null, 1);
        r.addView(t);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        r.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        status = tx("PDF-ku • Semua urusan PDF, satu aplikasi.", 13);
        r.addView(status);
        setContentView(r);
    }

    void add(String s, View.OnClickListener l) {
        Button b = bt(s);
        content.addView(b, new LinearLayout.LayoutParams(-1, dp(56)));
        b.setOnClickListener(l);
    }

    void home() {
        closePdf();
        shell("PDF-ku");
        content.addView(tx("Baca • Buat • Kelola • Bagikan", 16));
        add("📄  Buka PDF", v -> pickPdf());
        add("🖼️  Gambar → PDF", v -> pickImg());
        add("✍️  Teks → PDF", v -> textPdf());
        add("🧰  PDF Tools", v -> tools());
        add("🕘  File Terakhir", v -> recent());
    }

    void pickPdf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("application/pdf");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PDF);
    }

    void pickImg() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_IMG);
    }

    @Override
    protected void onActivityResult(int r, int c, Intent d) {
        super.onActivityResult(r, c, d);
        if (c != RESULT_OK || d == null) return;

        if (r == REQ_PDF && d.getData() != null) {
            Uri u = d.getData();
            persistReadPermission(d, u);
            addRecent(u);
            open(u);
        } else if (r == REQ_IMG) {
            persistReadPermissions(d);
            imagePdf(d);
        } else if (r == REQ_MERGE) {
            persistReadPermissions(d);
            mergeSelected(d);
        } else if (r == REQ_SPLIT && d.getData() != null) {
            Uri u = d.getData();
            persistReadPermission(d, u);
            showSplitDialog(u);
        } else if (r == REQ_SAVE_AS && pendingSaveFile != null) {
            Uri target = d.getData();
            persistWritePermission(d, target);
            File source = pendingSaveFile;
            String name = pendingSaveName;
            pendingSaveFile = null;
            pendingSaveName = null;
            copyToUri(source, target, name);
        }
    }

    void persistReadPermission(Intent d, Uri u) {
        try {
            getContentResolver().takePersistableUriPermission(u,
                    d.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
    }

    void persistWritePermission(Intent d, Uri u) {
        try {
            int flags = d.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (flags != 0) getContentResolver().takePersistableUriPermission(u, flags);
        } catch (Exception ignored) {}
    }

    void persistReadPermissions(Intent d) {
        try {
            int flags = d.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (flags == 0) return;
            if (d.getClipData() != null) {
                for (int i = 0; i < d.getClipData().getItemCount(); i++) {
                    Uri u = d.getClipData().getItemAt(i).getUri();
                    try { getContentResolver().takePersistableUriPermission(u, flags); } catch (Exception ignored) {}
                }
            } else if (d.getData() != null) {
                persistReadPermission(d, d.getData());
            }
        } catch (Exception ignored) {}
    }

    void open(Uri u) {
        try {
            closePdf();
            fd = getContentResolver().openFileDescriptor(u, "r");
            if (fd == null) throw new IOException("File descriptor null");
            renderer = new PdfRenderer(fd);
            page = 0;
            openedUri = u;
            viewer();
        } catch (Exception e) {
            toast("Gagal membuka PDF: " + e.getMessage());
        }
    }

    void viewer() {
        shell("PDF Viewer");

        image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setBackgroundColor(Color.WHITE);
        content.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout n = new LinearLayout(this);
        Button p = bt("‹ Sebelumnya");
        Button q = bt("Berikutnya ›");
        Button save = bt("Simpan As");
        Button share = bt("Bagikan");

        n.addView(p, new LinearLayout.LayoutParams(0, dp(56), 1));
        n.addView(q, new LinearLayout.LayoutParams(0, dp(56), 1));
        n.addView(save, new LinearLayout.LayoutParams(0, dp(56), 1));
        n.addView(share, new LinearLayout.LayoutParams(0, dp(56), 1));
        content.addView(n);

        add("⌂  Beranda", v -> home());

        p.setOnClickListener(v -> {
            if (page > 0) { page--; render(); }
        });
        q.setOnClickListener(v -> {
            if (renderer != null && page < renderer.getPageCount() - 1) { page++; render(); }
        });
        save.setOnClickListener(v -> saveCurrentPageAs());
        share.setOnClickListener(v -> shareUri(openedUri));
        render();
    }

    void render() {
        try {
            if (renderer == null) return;
            PdfRenderer.Page p = renderer.openPage(page);
            Bitmap b = Bitmap.createBitmap(p.getWidth() * 2, p.getHeight() * 2, Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.WHITE);
            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            p.close();
            image.setImageBitmap(b);
            status.setText("Halaman " + (page + 1) + " / " + renderer.getPageCount());
        } catch (Exception e) {
            toast("Gagal menampilkan halaman");
        }
    }

    void textPdf() {
        EditText e = new EditText(this);
        e.setHint("Tulis isi PDF…");
        e.setMinLines(8);
        e.setGravity(Gravity.TOP | Gravity.START);
        new AlertDialog.Builder(this)
                .setTitle("Teks → PDF")
                .setView(e)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Buat", (d, w) -> makeText(e.getText().toString()))
                .show();
    }

    void makeText(String s) {
        if (s.trim().isEmpty()) { toast("Teks kosong"); return; }
        worker.execute(() -> {
            File f = null;
            try {
                f = tempFile("text");
                PdfDocument d = new PdfDocument();
                Paint x = new Paint(Paint.ANTI_ALIAS_FLAG);
                x.setColor(Color.BLACK);
                x.setTextSize(16);
                float y = 60;
                int pageNo = 1;
                PdfDocument.Page p = d.startPage(new PdfDocument.PageInfo.Builder(595, 842, pageNo).create());

                for (String line : s.split("\\n", -1)) {
                    if (y > 800) {
                        d.finishPage(p);
                        pageNo++;
                        p = d.startPage(new PdfDocument.PageInfo.Builder(595, 842, pageNo).create());
                        y = 60;
                    }
                    p.getCanvas().drawText(line, 40, y, x);
                    y += 24;
                }

                d.finishPage(p);
                try (FileOutputStream o = new FileOutputStream(f)) {
                    d.writeTo(o);
                }
                d.close();
                File result = f;
                runOnUiThread(() -> requestSaveAs(result, "PDF-ku-text.pdf"));
            } catch (Exception e) {
                if (f != null) f.delete();
                runOnUiThread(() -> toast("Gagal membuat PDF teks: " + e.getMessage()));
            }
        });
    }

    void imagePdf(Intent d) {
        worker.execute(() -> {
            File f = null;
            try {
                ArrayList<Uri> a = selectedUris(d);
                if (a.isEmpty()) throw new IOException("Tidak ada gambar dipilih");

                f = tempFile("images");
                PdfDocument p = new PdfDocument();
                int n = 1;

                for (Uri u : a) {
                    Bitmap b = BitmapFactory.decodeStream(getContentResolver().openInputStream(u));
                    if (b == null) continue;
                    float s = Math.min(535f / b.getWidth(), 762f / b.getHeight());
                    int w = Math.max(1, (int) (b.getWidth() * s));
                    int h = Math.max(1, (int) (b.getHeight() * s));
                    PdfDocument.Page z = p.startPage(new PdfDocument.PageInfo.Builder(595, 842, n++).create());
                    z.getCanvas().drawBitmap(b, null,
                            new RectF((595 - w) / 2f, (842 - h) / 2f, (595 + w) / 2f, (842 + h) / 2f),
                            new Paint(Paint.ANTI_ALIAS_FLAG));
                    p.finishPage(z);
                    b.recycle();
                }

                try (FileOutputStream o = new FileOutputStream(f)) {
                    p.writeTo(o);
                }
                p.close();
                File result = f;
                runOnUiThread(() -> requestSaveAs(result, "PDF-ku-gambar.pdf"));
            } catch (Exception e) {
                if (f != null) f.delete();
                runOnUiThread(() -> toast("Gagal membuat PDF gambar: " + e.getMessage()));
            }
        });
    }

    void tools() {
        shell("PDF Tools");
        add("🔗  Gabung PDF", v -> pickMerge());
        add("✂️  Split PDF", v -> pickSplit());
        add("ℹ️  Tentang PDF-ku", v -> new AlertDialog.Builder(this)
                .setTitle("PDF-ku 1.1")
                .setMessage("Toolkit PDF offline-first dari Lara Studio. Merge dan Split memakai engine PDF native berbasis PDFBox.")
                .setPositiveButton("OK", null).show());
        add("⌂  Kembali", v -> home());
    }

    void pickMerge() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_MERGE);
    }

    void pickSplit() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("application/pdf");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_SPLIT);
    }

    void showSplitDialog(Uri u) {
        worker.execute(() -> {
            int count = -1;
            try (InputStream in = getContentResolver().openInputStream(u);
                 PDDocument doc = PDDocument.load(in)) {
                count = doc.getNumberOfPages();
            } catch (Exception e) {
                int finalCount = count;
                runOnUiThread(() -> toast("Gagal membaca PDF: " + e.getMessage()));
                return;
            }

            int finalCount = count;
            runOnUiThread(() -> {
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(20), dp(8), dp(20), 0);

                EditText range = new EditText(this);
                range.setHint("Contoh: 2-5 atau 3");
                range.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
                box.addView(range);

                TextView info = tx("Total halaman: " + finalCount, 14);
                box.addView(info);

                new AlertDialog.Builder(this)
                        .setTitle("✂️ Split PDF")
                        .setMessage("Masukkan halaman yang ingin dipisahkan. Contoh 2-5 berarti halaman 2 sampai 5.")
                        .setView(box)
                        .setNegativeButton("Batal", null)
                        .setPositiveButton("Split", (dialog, which) -> {
                            String value = range.getText().toString().trim();
                            int start = 0, end = 0;
                            try {
                                if (value.contains("-")) {
                                    String[] parts = value.split("-", 2);
                                    start = Integer.parseInt(parts[0].trim());
                                    end = Integer.parseInt(parts[1].trim());
                                } else {
                                    start = Integer.parseInt(value);
                                    end = start;
                                }
                                if (start < 1 || end < start || end > finalCount) throw new NumberFormatException();
                                splitPdf(u, start - 1, end - 1);
                            } catch (Exception e) {
                                toast("Range tidak valid. Gunakan contoh 2-5.");
                            }
                        }).show();
            });
        });
    }

    void splitPdf(Uri u, int start, int end) {
        worker.execute(() -> {
            File f = null;
            try (InputStream in = getContentResolver().openInputStream(u);
                 PDDocument source = PDDocument.load(in)) {

                f = tempFile("split");
                PDDocument out = new PDDocument();

                for (int i = start; i <= end; i++) {
                    out.importPage(source.getPage(i));
                }

                try (FileOutputStream os = new FileOutputStream(f)) {
                    out.save(os);
                }
                out.close();

                File result = f;
                String name = "PDF-ku-split-" + (start + 1) + "-" + (end + 1) + ".pdf";
                runOnUiThread(() -> requestSaveAs(result, name));
            } catch (Exception e) {
                if (f != null) f.delete();
                runOnUiThread(() -> toast("Gagal split PDF: " + e.getMessage()));
            }
        });
    }

    void mergeSelected(Intent d) {
        worker.execute(() -> {
            File f = null;
            ArrayList<File> sources = new ArrayList<>();
            try {
                ArrayList<Uri> a = selectedUris(d);
                if (a.size() < 2) throw new IOException("Pilih minimal 2 PDF");

                f = tempFile("merge");
                PDFMergerUtility merger = new PDFMergerUtility();

                for (Uri u : a) {
                    File source = File.createTempFile("pdfku-source-", ".pdf", getCacheDir());
                    try (InputStream in = getContentResolver().openInputStream(u);
                         FileOutputStream out = new FileOutputStream(source)) {
                        if (in == null) throw new IOException("Tidak bisa membaca " + u);
                        copy(in, out);
                    }
                    sources.add(source);
                    merger.addSource(source);
                }

                merger.setDestinationFileName(f.getAbsolutePath());
                merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());

                File result = f;
                runOnUiThread(() -> requestSaveAs(result, "PDF-ku-merge.pdf"));
            } catch (Exception e) {
                if (f != null) f.delete();
                runOnUiThread(() -> toast("Gagal menggabungkan PDF: " + e.getMessage()));
            } finally {
                for (File source : sources) source.delete();
            }
        });
    }

    void saveCurrentPageAs() {
        if (openedUri == null || renderer == null) return;
        final Uri sourceUri = openedUri;
        final int pageIndex = page;

        worker.execute(() -> {
            File f = null;
            try {
                f = tempFile("page");
                try (ParcelFileDescriptor sourceFd = getContentResolver().openFileDescriptor(sourceUri, "r")) {
                    if (sourceFd == null) throw new IOException("File descriptor null");
                    PdfRenderer safeRenderer = new PdfRenderer(sourceFd);
                    if (pageIndex < 0 || pageIndex >= safeRenderer.getPageCount()) {
                        safeRenderer.close();
                        throw new IOException("Halaman tidak tersedia");
                    }
                    PdfRenderer.Page src = safeRenderer.openPage(pageIndex);
                    PdfDocument out = new PdfDocument();
                    PdfDocument.Page dst = out.startPage(new PdfDocument.PageInfo.Builder(src.getWidth(), src.getHeight(), 1).create());

                    Bitmap bm = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
                    bm.eraseColor(Color.WHITE);
                    src.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                    dst.getCanvas().drawBitmap(bm, 0, 0, new Paint(Paint.ANTI_ALIAS_FLAG));

                    out.finishPage(dst);
                    src.close();
                    safeRenderer.close();
                    bm.recycle();

                    try (FileOutputStream os = new FileOutputStream(f)) {
                        out.writeTo(os);
                    }
                    out.close();
                }

                File result = f;
                runOnUiThread(() -> requestSaveAs(result, "PDF-ku-halaman-" + (pageIndex + 1) + ".pdf"));
            } catch (Exception e) {
                if (f != null) f.delete();
                runOnUiThread(() -> toast("Gagal menyimpan halaman: " + e.getMessage()));
            }
        });
    }

    void requestSaveAs(File source, String suggestedName) {
        pendingSaveFile = source;
        pendingSaveName = suggestedName;

        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_TITLE, suggestedName);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_SAVE_AS);
    }

    void copyToUri(File source, Uri target, String name) {
        worker.execute(() -> {
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = getContentResolver().openOutputStream(target, "w")) {
                if (out == null) throw new IOException("Lokasi penyimpanan tidak tersedia");
                copy(in, out);
                addRecent(target);
                source.delete();
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("PDF tersimpan")
                        .setMessage(name + "\\n\\nFile sudah tersimpan dan bisa dibuka atau dibagikan.")
                        .setPositiveButton("Bagikan", (d, w) -> shareUri(target))
                        .setNegativeButton("Tutup", null)
                        .show());
            } catch (Exception e) {
                runOnUiThread(() -> toast("Gagal menyimpan PDF: " + e.getMessage()));
            }
        });
    }

    void shareUri(Uri u) {
        if (u == null) { toast("Tidak ada PDF yang dipilih"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/pdf");
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Bagikan PDF"));
        } catch (Exception e) {
            toast("Tidak ada aplikasi yang bisa membagikan PDF");
        }
    }

    ArrayList<Uri> selectedUris(Intent d) {
        ArrayList<Uri> a = new ArrayList<>();
        if (d.getClipData() != null) {
            for (int i = 0; i < d.getClipData().getItemCount(); i++) {
                a.add(d.getClipData().getItemAt(i).getUri());
            }
        } else if (d.getData() != null) {
            a.add(d.getData());
        }
        return a;
    }

    File tempFile(String prefix) throws IOException {
        return File.createTempFile("pdfku-" + prefix + "-", ".pdf", getCacheDir());
    }

    void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    void addRecent(Uri u) {
        if (u == null) return;
        recent.remove(u);
        recent.add(0, u);
        while (recent.size() > 20) recent.remove(recent.size() - 1);
        saveRecent();
    }

    void loadRecent() {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(RECENT, "");
        if (raw.isEmpty()) return;
        for (String s : raw.split("\\n")) {
            if (!s.trim().isEmpty()) {
                try { recent.add(Uri.parse(s)); } catch (Exception ignored) {}
            }
        }
    }

    void saveRecent() {
        StringBuilder s = new StringBuilder();
        for (Uri u : recent) s.append(u.toString()).append("\\n");
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(RECENT, s.toString()).apply();
    }

    void recent() {
        shell("File Terakhir");
        if (recent.isEmpty()) {
            content.addView(tx("Belum ada PDF yang dibuka.", 16));
        } else {
            for (Uri u : new ArrayList<>(recent)) {
                Button b = bt("📄  " + displayName(u));
                content.addView(b, new LinearLayout.LayoutParams(-1, dp(56)));
                b.setOnClickListener(v -> open(u));
            }
        }
        add("⌂  Kembali", v -> home());
    }

    String displayName(Uri u) {
        String name = u.getLastPathSegment();
        try {
            Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) name = c.getString(0);
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {}
        return name == null ? "PDF" : name;
    }

    void closePdf() {
        try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
        try { if (fd != null) fd.close(); } catch (Exception ignored) {}
        renderer = null;
        fd = null;
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        closePdf();
        super.onDestroy();
    }
}
