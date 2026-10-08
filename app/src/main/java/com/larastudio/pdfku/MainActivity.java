package com.larastudio.pdfku;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.graphics.pdf.PdfRenderer;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
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
import java.util.Collections;

public class MainActivity extends Activity {
    private static final int REQ_PDF = 1, REQ_IMG = 2, REQ_MERGE = 3, REQ_SPLIT = 4, REQ_SAVE_AS = 5, REQ_CAMERA = 6, REQ_SCAN = 7, REQ_IMG_ADD = 8, REQ_CAMERA_ADD = 9;
    private static final String PREFS = "pdfku_prefs";
    private static final String RECENT = "recent_uris";

    private LinearLayout content;
    private TextView status;
    private ImageView image;
    private PdfRenderer renderer;
    private ParcelFileDescriptor fd;
    private int page = 0;
    private Uri openedUri;
    private Uri cameraOutputUri;
    private File cameraOutputFile;
    private final ArrayList<Uri> scanUris = new ArrayList<>();
    private ArrayList<Uri> editingImageUris;
    private Runnable editingRefresh;
    private boolean editingScanMode;
    private boolean darkViewer = false;
    private ZoomImageView zoomImage;
    private final ArrayList<Uri> recent = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private File pendingSaveFile;
    private String pendingSaveName;

    static class ZoomImageView extends ImageView {
        private final Matrix drawMatrix = new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private float scale = 1f;
        private float minScale = 1f;
        private float lastX, lastY;
        private boolean dragging;

        ZoomImageView(Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    float next = scale * d.getScaleFactor();
                    next = Math.max(minScale, Math.min(next, minScale * 5f));
                    float factor = next / scale;
                    drawMatrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                    scale = next;
                    clamp();
                    setImageMatrix(drawMatrix);
                    return true;
                }
            });
        }

        void fitToView() {
            Drawable d = getDrawable();
            if (d == null || getWidth() <= 0 || getHeight() <= 0) return;
            float sx = (float) getWidth() / d.getIntrinsicWidth();
            float sy = (float) getHeight() / d.getIntrinsicHeight();
            minScale = Math.min(sx, sy);
            scale = minScale;
            drawMatrix.reset();
            float w = d.getIntrinsicWidth() * scale;
            float h = d.getIntrinsicHeight() * scale;
            drawMatrix.postTranslate((getWidth() - w) / 2f, (getHeight() - h) / 2f);
            setImageMatrix(drawMatrix);
        }

        void resetZoom() { fitToView(); }

        private void clamp() {
            Drawable d = getDrawable();
            if (d == null) return;
            float w = d.getIntrinsicWidth() * scale;
            float h = d.getIntrinsicHeight() * scale;
            RectF r = new RectF(0, 0, w, h);
            drawMatrix.mapRect(r);
            float dx = 0, dy = 0;
            if (w <= getWidth()) dx = getWidth() / 2f - (r.left + r.right) / 2f;
            else {
                if (r.left > 0) dx = -r.left;
                if (r.right < getWidth()) dx = getWidth() - r.right;
            }
            if (h <= getHeight()) dy = getHeight() / 2f - (r.top + r.bottom) / 2f;
            else {
                if (r.top > 0) dy = -r.top;
                if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
            }
            drawMatrix.postTranslate(dx, dy);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            post(this::fitToView);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX(); lastY = event.getY(); dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging && !scaleDetector.isInProgress()) {
                        float dx = event.getX() - lastX, dy = event.getY() - lastY;
                        drawMatrix.postTranslate(dx, dy);
                        clamp();
                        setImageMatrix(drawMatrix);
                        lastX = event.getX(); lastY = event.getY();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
            }
            return true;
        }
    }

    int dp(int x) {
        return (int) (x * getResources().getDisplayMetrics().density + .5f);
    }

    TextView tx(String s, int z) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(z);
        t.setTextColor(Color.rgb(226, 232, 240));
        t.setPadding(dp(4), dp(6), dp(4), dp(6));
        return t;
    }

    GradientDrawable bg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp((int) radius));
        return g;
    }

    Button bt(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setBackground(bg(Color.rgb(30, 41, 59), 16));
        return b;
    }

    LinearLayout actionCard(String icon, String title, String subtitle, int accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(14), dp(14));
        card.setBackground(bg(Color.rgb(17, 28, 49), 20));
        card.setClickable(true);
        card.setFocusable(true);

        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextSize(24);
        iconView.setGravity(Gravity.CENTER);
        iconView.setTextColor(Color.WHITE);
        iconView.setBackground(bg(accent, 15));
        card.addView(iconView, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(14), 0, dp(4), 0);

        TextView titleView = tx(title, 16);
        titleView.setTextColor(Color.WHITE);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setPadding(0, 0, 0, dp(3));
        copy.addView(titleView);

        TextView subView = tx(subtitle, 12);
        subView.setTextColor(Color.rgb(148, 163, 184));
        subView.setPadding(0, 0, 0, 0);
        copy.addView(subView);

        card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        return card;
    }

    TextView section(String s) {
        TextView t = tx(s, 11);
        t.setTextColor(Color.rgb(96, 165, 250));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(.12f);
        t.setPadding(2, dp(20), 2, dp(9));
        return t;
    }

    void cardRow(LinearLayout a, LinearLayout b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(104));
        rp.setMargins(0, 0, 0, dp(10));
        content.addView(row, rp);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -1, 1);
        cp.setMargins(0, 0, dp(5), 0);
        row.addView(a, cp);
        LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(0, -1, 1);
        cp2.setMargins(dp(5), 0, 0, 0);
        row.addView(b, cp2);
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        PDFBoxResourceLoader.init(getApplicationContext());
        loadRecent();
        darkViewer = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("dark_viewer", false);
        home();
    }

    void shell(String h) {
        getWindow().setStatusBarColor(Color.rgb(8, 15, 29));
        getWindow().setNavigationBarColor(Color.rgb(8, 15, 29));

        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(20), dp(14), dp(20), dp(10));
        r.setBackgroundColor(Color.rgb(8, 15, 29));

        TextView t = tx(h, 27);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        t.setTextColor(Color.WHITE);
        r.addView(t, new LinearLayout.LayoutParams(-1, dp(54)));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(4), 0, dp(18));

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.addView(content);
        r.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        status = tx("PDF-ku  •  Offline-first", 12);
        status.setTextColor(Color.rgb(100, 116, 139));
        r.addView(status);
        setContentView(r);
    }

    void add(String s, View.OnClickListener l) {
        Button b = bt(s);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
        p.setMargins(0, dp(6), 0, dp(6));
        content.addView(b, p);
        b.setOnClickListener(l);
    }

    void home() {
        closePdf();
        shell("");

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(0, dp(4), 0, dp(2));

        TextView logo = tx("PDF-ku", 34);
        logo.setTextColor(Color.WHITE);
        logo.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        logo.setPadding(0, 0, 0, dp(3));
        brand.addView(logo);

        TextView tagline = tx("Semua urusan PDF, lebih simpel.", 14);
        tagline.setTextColor(Color.rgb(148, 163, 184));
        tagline.setPadding(0, 0, 0, 0);
        brand.addView(tagline);
        content.addView(brand);

        LinearLayout hero = actionCard("📄", "Buka PDF", "Baca dokumen dari perangkat", Color.rgb(37, 99, 235));
        hero.setBackground(bg(Color.rgb(20, 55, 110), 22));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, dp(88));
        hp.setMargins(0, dp(22), 0, 0);
        content.addView(hero, hp);
        hero.setOnClickListener(v -> pickPdf());

        content.addView(section("BUAT PDF"));
        LinearLayout image = actionCard("🖼", "Gambar", "Foto → PDF", Color.rgb(16, 185, 129));
        LinearLayout text = actionCard("✍", "Teks", "Teks → PDF", Color.rgb(168, 85, 247));
        cardRow(image, text);
        image.setOnClickListener(v -> pickImg());
        text.setOnClickListener(v -> textPdf());

        content.addView(section("SCAN & KONVERSI"));
        LinearLayout scan=actionCard("📷","Scan Dokumen","Kamera + auto-crop + enhancement",Color.rgb(14,165,233));
        LinearLayout pdfjpg=actionCard("🖼","PDF → JPG","Ekspor halaman sebagai gambar",Color.rgb(168,85,247));
        cardRow(scan,pdfjpg);
        scan.setOnClickListener(v->startScan());
        pdfjpg.setOnClickListener(v->pickPdfToJpg());

        content.addView(section("KELOLA"));
        LinearLayout tools = actionCard("🧰", "PDF Tools", "Gabung & split", Color.rgb(245, 158, 11));
        LinearLayout files = actionCard("🕘", "Terakhir", "Dokumen terbaru", Color.rgb(236, 72, 153));
        cardRow(tools, files);
        tools.setOnClickListener(v -> tools());
        files.setOnClickListener(v -> recent());

        TextView hint = tx("🔒  Offline-first • File tetap di perangkat", 12);
        hint.setTextColor(Color.rgb(100, 116, 139));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(12), 0, dp(10));
        content.addView(hint);
    }

    void pickPdf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("application/pdf");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PDF);
    }

    void pickImg() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), 0, dp(20), 0);

        Button camera = bt("📷  Kamera");
        Button gallery = bt("🖼  Galeri");
        camera.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        gallery.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        camera.setBackground(bg(Color.rgb(37, 99, 235), 16));
        gallery.setBackground(bg(Color.rgb(30, 41, 59), 16));

        box.addView(camera, new LinearLayout.LayoutParams(-1, dp(58)));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, dp(58));
        gp.setMargins(0, dp(10), 0, 0);
        box.addView(gallery, gp);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Tambahkan gambar")
                .setMessage("Pilih foto dari kamera atau gambar yang sudah tersimpan.")
                .setView(box)
                .setNegativeButton("Batal", null)
                .create();

        camera.setOnClickListener(v -> {
            dialog.dismiss();
            captureImage();
        });
        gallery.setOnClickListener(v -> {
            dialog.dismiss();
            pickImageFromGallery();
        });
        dialog.show();
    }

    void pickImageFromGallery() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_IMG);
    }

    void captureImage() {
        try {
            File photo = File.createTempFile("pdfku-camera-", ".jpg", getCacheDir());
            cameraOutputFile = photo;
            cameraOutputUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    photo
            );

            Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraOutputUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);

            List<android.content.pm.ResolveInfo> cameras = getPackageManager().queryIntentActivities(
                    i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            );
            if (cameras.isEmpty()) {
                cameraOutputUri = null;
                cameraOutputFile = null;
                photo.delete();
                toast("Aplikasi kamera tidak tersedia");
                return;
            }

            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            if (cameraOutputFile != null) cameraOutputFile.delete();
            cameraOutputUri = null;
            cameraOutputFile = null;
            toast("Tidak bisa membuka kamera");
        }
    }

    @Override
    protected void onActivityResult(int r, int c, Intent d) {
        super.onActivityResult(r, c, d);
        if (c != RESULT_OK) {
            if (r == REQ_CAMERA || r == REQ_SCAN || r == REQ_CAMERA_ADD) {
                if (cameraOutputFile != null) cameraOutputFile.delete();
                cameraOutputUri = null;
                cameraOutputFile = null;
            } else if (r == REQ_SAVE_AS && pendingSaveFile != null) {
                clearPendingSave(true);
            }
            return;
        }

        if (r == REQ_SCAN) {
            if (cameraOutputUri != null) {
                scanUris.add(cameraOutputUri);
                cameraOutputUri = null;
                cameraOutputFile = null;
                askNextScanPage();
            }
            return;
        }

        if (r == REQ_CAMERA_ADD) {
            if (cameraOutputUri != null) {
                Uri added = cameraOutputUri;
                if (editingImageUris != null) {
                    editingImageUris.add(added);
                    if (editingRefresh != null) editingRefresh.run();
                }
            }
            cameraOutputUri = null;
            cameraOutputFile = null;
            return;
        }

        if (r == REQ_CAMERA) {
            if (cameraOutputUri != null) {
                Intent imageIntent = new Intent();
                imageIntent.setData(cameraOutputUri);
                imagePdf(imageIntent);
            }
            cameraOutputUri = null;
            cameraOutputFile = null;
            return;
        }

        if (d == null) return;

        if (r == REQ_IMG_ADD) {
            persistReadPermissions(d);
            ArrayList<Uri> added = selectedUris(d);
            if (editingImageUris != null && !added.isEmpty()) {
                editingImageUris.addAll(added);
                if (editingRefresh != null) editingRefresh.run();
            } else if (added.isEmpty()) {
                toast("Tidak ada gambar dipilih");
            }
            return;
        }

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
        } else if (r >= REQ_SPLIT + 20 && r <= REQ_SPLIT + 24 && d.getData() != null) {
            persistReadPermission(d, d.getData());
            handleAdvancedResult(r, d.getData());
        } else if (r == REQ_SAVE_AS && pendingSaveFile != null && d.getData() != null) {
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

    void pickMoreImage() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), 0, dp(20), 0);

        Button camera = bt("📷  Kamera");
        Button gallery = bt("🖼  Galeri");
        camera.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        gallery.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        camera.setBackground(bg(Color.rgb(37, 99, 235), 16));
        gallery.setBackground(bg(Color.rgb(30, 41, 59), 16));

        box.addView(camera, new LinearLayout.LayoutParams(-1, dp(58)));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, dp(58));
        gp.setMargins(0, dp(10), 0, 0);
        box.addView(gallery, gp);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editingScanMode ? "Tambah halaman scan" : "Tambah gambar")
                .setMessage("Tambahkan foto ke daftar yang sedang Anda atur.")
                .setView(box)
                .setNegativeButton("Batal", null)
                .create();

        camera.setOnClickListener(v -> {
            dialog.dismiss();
            captureImageForEditing();
        });
        gallery.setOnClickListener(v -> {
            dialog.dismiss();
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_IMG_ADD);
        });
        dialog.show();
    }

    void captureImageForEditing() {
        try {
            File photo = File.createTempFile("pdfku-add-", ".jpg", getCacheDir());
            cameraOutputFile = photo;
            cameraOutputUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", photo);

            Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraOutputUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (getPackageManager().queryIntentActivities(
                    i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
                photo.delete();
                cameraOutputFile = null;
                cameraOutputUri = null;
                toast("Kamera tidak tersedia");
                return;
            }
            startActivityForResult(i, REQ_CAMERA_ADD);
        } catch (Exception e) {
            if (cameraOutputFile != null) cameraOutputFile.delete();
            cameraOutputFile = null;
            cameraOutputUri = null;
            toast("Tidak bisa membuka kamera");
        }
    }

    Bitmap thumbnail(Uri uri, int maxSide) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;

            int sample = 1;
            while (o.outWidth / sample > maxSide * 2 || o.outHeight / sample > maxSide * 2) {
                sample *= 2;
            }

            BitmapFactory.Options real = new BitmapFactory.Options();
            real.inSampleSize = sample;
            real.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                b = BitmapFactory.decodeStream(in, null, real);
            }
            if (b == null) return null;

            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd != null) {
                    ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                }
            }

            Matrix m = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: m.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: m.setRotate(90); m.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: m.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: m.setRotate(-90); m.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: m.setRotate(-90); break;
            }
            if (!m.isIdentity()) {
                Bitmap fixed = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (fixed != b) b.recycle();
                b = fixed;
            }

            float scale = Math.min(1f, Math.min((float) maxSide / b.getWidth(), (float) maxSide / b.getHeight()));
            if (scale < 1f) {
                int w = Math.max(1, Math.round(b.getWidth() * scale));
                int h = Math.max(1, Math.round(b.getHeight() * scale));
                Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
                if (small != b) b.recycle();
                b = small;
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    void showImagePreview(ImageView target, Uri uri) {
        target.setImageResource(android.R.drawable.ic_menu_gallery);
        worker.execute(() -> {
            Bitmap b = thumbnail(uri, dp(58));
            runOnUiThread(() -> {
                if (b != null && target.getWindowToken() != null) target.setImageBitmap(b);
            });
        });
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
        shell("");

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, dp(10));

        TextView title = tx("Dokumen", 21);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        top.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));

        Button share = bt("↗");
        share.setTextSize(18);
        share.setGravity(Gravity.CENTER);
        share.setBackground(bg(Color.rgb(30, 41, 59), 14));
        top.addView(share, new LinearLayout.LayoutParams(dp(46), dp(46)));
        content.addView(top);

        LinearLayout canvas = new LinearLayout(this);
        canvas.setGravity(Gravity.CENTER);
        canvas.setPadding(dp(8), dp(8), dp(8), dp(8));
        canvas.setBackground(bg(Color.rgb(226, 232, 240), 18));

        zoomImage = new ZoomImageView(this);
        image = zoomImage;
        image.setBackgroundColor(Color.WHITE);
        image.setContentDescription("Halaman PDF");
        canvas.addView(image, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams canvasParams = new LinearLayout.LayoutParams(-1, 0, 1);
        canvasParams.setMargins(0, 0, 0, dp(12));
        content.addView(canvas, canvasParams);

        LinearLayout pager = new LinearLayout(this);
        pager.setGravity(Gravity.CENTER_VERTICAL);
        pager.setPadding(dp(6), dp(6), dp(6), dp(6));
        pager.setBackground(bg(Color.rgb(17, 28, 49), 18));

        Button p = bt("‹");
        Button q = bt("›");
        Button save = bt("Simpan");
        TextView pageLabel = tx("Halaman " + (page + 1), 13);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setTextColor(Color.rgb(203, 213, 225));

        for (Button b : new Button[]{p, q}) {
            b.setTextSize(24);
            b.setGravity(Gravity.CENTER);
            b.setBackground(bg(Color.rgb(30, 41, 59), 14));
        }
        save.setBackground(bg(Color.rgb(37, 99, 235), 14));

        pager.addView(p, new LinearLayout.LayoutParams(dp(50), dp(50)));
        pager.addView(pageLabel, new LinearLayout.LayoutParams(0, dp(50), 1));
        pager.addView(save, new LinearLayout.LayoutParams(dp(88), dp(50)));
        pager.addView(q, new LinearLayout.LayoutParams(dp(50), dp(50)));
        content.addView(pager);

        Button homeButton = bt("←  Beranda");
        homeButton.setBackground(bg(Color.rgb(15, 23, 42), 14));
        LinearLayout.LayoutParams homeParams = new LinearLayout.LayoutParams(-1, dp(48));
        homeParams.setMargins(0, dp(10), 0, 0);
        content.addView(homeButton, homeParams);

        p.setOnClickListener(v -> {
            if (page > 0) { page--; render(); }
        });
        q.setOnClickListener(v -> {
            if (renderer != null && page < renderer.getPageCount() - 1) { page++; render(); }
        });
        save.setOnClickListener(v -> saveCurrentPageAs());
        share.setOnClickListener(v -> shareUri(openedUri));
        homeButton.setOnClickListener(v -> home());
        render();
    }

    void render() {
        if (renderer == null || zoomImage == null) return;
        zoomImage.post(() -> renderPageForViewer()); 
    }

    void renderPageForViewer() {
        if (renderer == null || zoomImage == null || zoomImage.getWidth() <= 0 || zoomImage.getHeight() <= 0) {
            return;
        }
        try {
            PdfRenderer.Page p = renderer.openPage(page);
            float fit = Math.min(
                    (float) zoomImage.getWidth() / p.getWidth(),
                    (float) zoomImage.getHeight() / p.getHeight()
            );
            fit = Math.max(0.1f, fit);
            int maxW = 2400;
            int maxH = 3200;
            int w = Math.max(1, Math.min(maxW, Math.round(p.getWidth() * fit * 2f)));
            int h = Math.max(1, Math.min(maxH, Math.round(p.getHeight() * fit * 2f)));
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.WHITE);
            Rect dest = new Rect(0, 0, w, h);
            p.render(b, dest, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            p.close();
            if (darkViewer) {
                Paint inv = new Paint(Paint.ANTI_ALIAS_FLAG);
                ColorMatrix cm = new ColorMatrix(new float[]{-1,0,0,0,255, 0,-1,0,0,255, 0,0,-1,0,255, 0,0,0,1,0});
                inv.setColorFilter(new ColorMatrixColorFilter(cm));
                Bitmap dark=Bitmap.createBitmap(b.getWidth(),b.getHeight(),Bitmap.Config.ARGB_8888);
                new Canvas(dark).drawBitmap(b,0,0,inv); b.recycle(); b=dark;
            }
            image.setImageBitmap(b);
            zoomImage.post(zoomImage::fitToView);
            status.setText("Halaman " + (page + 1) + " / " + renderer.getPageCount() + (darkViewer ? "  •  Dark" : ""));
        } catch (Exception e) { toast("Gagal menampilkan halaman: " + e.getMessage()); }
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

                final int pageWidth = 595;   // A4 points
                final int pageHeight = 842;  // A4 points
                final float left = 50f;
                final float right = 50f;
                final float top = 55f;
                final float bottom = 55f;
                final float contentWidth = pageWidth - left - right;

                PdfDocument d = new PdfDocument();
                Paint x = new Paint(Paint.ANTI_ALIAS_FLAG);
                x.setColor(Color.BLACK);
                x.setTextSize(16f);
                x.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

                float lineHeight = 25f;
                float y = top;
                int pageNo = 1;
                PdfDocument.Page p = d.startPage(
                        new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create()
                );

                String[] paragraphs = s.replace("\r", "").split("\n", -1);

                for (String paragraph : paragraphs) {
                    // Baris kosong tetap dipertahankan sebagai jarak antarparagraf.
                    if (paragraph.trim().isEmpty()) {
                        if (y + lineHeight > pageHeight - bottom) {
                            d.finishPage(p);
                            pageNo++;
                            p = d.startPage(new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create());
                            y = top;
                        }
                        y += lineHeight;
                        continue;
                    }

                    String remaining = paragraph.trim();

                    while (!remaining.isEmpty()) {
                        if (y + lineHeight > pageHeight - bottom) {
                            d.finishPage(p);
                            pageNo++;
                            p = d.startPage(new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create());
                            y = top;
                        }

                        int cut = remaining.length();

                        // Cari batas kata yang masih muat di dalam lebar halaman.
                        while (cut > 1 && x.measureText(remaining, 0, cut) > contentWidth) {
                            int space = remaining.lastIndexOf(' ', cut - 1);
                            if (space > 0) {
                                cut = space;
                            } else {
                                cut--;
                            }
                        }

                        String line = remaining.substring(0, cut).trim();

                        // Untuk kata yang sangat panjang, pecah berdasarkan ukuran karakter.
                        if (line.isEmpty()) {
                            cut = Math.max(1, cut);
                            line = remaining.substring(0, cut);
                        }

                        x.setTextAlign(Paint.Align.LEFT);
                        p.getCanvas().drawText(line, left, y, x);
                        y += lineHeight;

                        remaining = remaining.substring(Math.min(cut, remaining.length())).trim();
                    }
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
        ArrayList<Uri> a = selectedUris(d);
        if (a.isEmpty()) { toast("Tidak ada gambar dipilih"); return; }
        showImageOrderDialog(a, false);
    }

    void showImageOrderDialog(ArrayList<Uri> items, boolean scanMode) {
        editingImageUris = new ArrayList<>(items);
        editingScanMode = scanMode;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(4), dp(16), dp(8));

        TextView info = new TextView(this);
        info.setText("Atur foto sesuai keinginan Anda. Tahan urutan dengan ↑ ↓, hapus dengan ×, atau tambahkan foto baru.");
        info.setTextSize(13);
        info.setTextColor(Color.rgb(100, 116, 139));
        info.setPadding(0, 0, 0, dp(8));
        box.addView(info);

        ScrollView listScroll = new ScrollView(this);
        listScroll.setFillViewport(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(list);
        box.addView(listScroll, new LinearLayout.LayoutParams(-1, dp(360)));

        Button add = bt("＋  Tambah " + (scanMode ? "Foto / Halaman" : "Gambar"));
        add.setTextSize(15);
        add.setBackground(bg(Color.rgb(15, 118, 110), 14));
        add.setContentDescription("Tambah gambar");
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, dp(54));
        addLp.setMargins(0, dp(8), 0, dp(4));
        box.addView(add, addLp);

        CheckBox gray = new CheckBox(this);
        gray.setText("Scan hitam-putih / grayscale");
        gray.setTextSize(14);
        gray.setTextColor(Color.rgb(15, 23, 42));

        CheckBox crop = new CheckBox(this);
        crop.setText("Auto-crop tepi dokumen");
        crop.setTextSize(14);
        crop.setTextColor(Color.rgb(15, 23, 42));

        box.addView(gray, new LinearLayout.LayoutParams(-1, dp(46)));
        box.addView(crop, new LinearLayout.LayoutParams(-1, dp(46)));

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            list.removeAllViews();

            if (editingImageUris.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("Belum ada gambar. Tekan “Tambah " + (scanMode ? "Foto / Halaman" : "Gambar") + "”.");
                empty.setTextSize(14);
                empty.setTextColor(Color.rgb(100, 116, 139));
                empty.setGravity(Gravity.CENTER);
                list.addView(empty, new LinearLayout.LayoutParams(-1, dp(90)));
                return;
            }

            for (int i = 0; i < editingImageUris.size(); i++) {
                final int idx = i;
                Uri uri = editingImageUris.get(i);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(5), dp(6), dp(5));
                row.setBackground(bg(Color.rgb(241, 245, 249), 12));

                ImageView thumb = new ImageView(this);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackground(bg(Color.WHITE, 8));
                row.addView(thumb, new LinearLayout.LayoutParams(dp(58), dp(58)));
                showImagePreview(thumb, uri);

                TextView number = new TextView(this);
                number.setText(String.valueOf(i + 1));
                number.setTextSize(13);
                number.setTextColor(Color.WHITE);
                number.setGravity(Gravity.CENTER);
                number.setBackground(bg(Color.rgb(37, 99, 235), 18));
                LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(dp(36), dp(36));
                np.setMargins(dp(8), 0, 0, 0);
                row.addView(number, np);

                LinearLayout copy = new LinearLayout(this);
                copy.setOrientation(LinearLayout.VERTICAL);
                copy.setGravity(Gravity.CENTER_VERTICAL);
                copy.setPadding(dp(8), 0, dp(5), 0);

                String display = displayName(uri);
                if (display == null || display.trim().isEmpty()) display = "Gambar " + (i + 1);

                TextView name = new TextView(this);
                name.setText(display);
                name.setTextColor(Color.rgb(15, 23, 42));
                name.setTextSize(12);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);

                TextView type = new TextView(this);
                type.setText(scanMode ? "Hasil scan" : "Gambar");
                type.setTextColor(Color.rgb(100, 116, 139));
                type.setTextSize(11);

                copy.addView(name, new LinearLayout.LayoutParams(-1, dp(22)));
                copy.addView(type, new LinearLayout.LayoutParams(-1, dp(18)));
                row.addView(copy, new LinearLayout.LayoutParams(0, dp(50), 1f));

                Button up = bt("↑");
                Button down = bt("↓");
                Button del = bt("×");
                up.setContentDescription("Naikkan halaman " + (i + 1));
                down.setContentDescription("Turunkan halaman " + (i + 1));
                del.setContentDescription("Hapus halaman " + (i + 1));
                up.setEnabled(i > 0);
                down.setEnabled(i < editingImageUris.size() - 1);

                row.addView(up, new LinearLayout.LayoutParams(dp(42), dp(42)));
                LinearLayout.LayoutParams downLp = new LinearLayout.LayoutParams(dp(42), dp(42));
                downLp.setMargins(dp(4), 0, 0, 0);
                row.addView(down, downLp);
                LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(dp(42), dp(42));
                delLp.setMargins(dp(4), 0, 0, 0);
                row.addView(del, delLp);

                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(70));
                rowLp.setMargins(0, dp(4), 0, 0);
                list.addView(row, rowLp);

                up.setOnClickListener(v -> {
                    Collections.swap(editingImageUris, idx, idx - 1);
                    refresh[0].run();
                });
                down.setOnClickListener(v -> {
                    Collections.swap(editingImageUris, idx, idx + 1);
                    refresh[0].run();
                });
                del.setOnClickListener(v -> {
                    editingImageUris.remove(idx);
                    refresh[0].run();
                });
            }
        };
        editingRefresh = refresh[0];

        add.setOnClickListener(v -> pickMoreImage());
        refresh[0].run();

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(scanMode ? "Scan Dokumen" : "JPG / Gambar → PDF")
                .setView(box)
                .setNegativeButton("Batal", (di, w) -> {
                    editingRefresh = null;
                    editingImageUris = null;
                })
                .setPositiveButton("Buat PDF", (di, w) -> {
                    ArrayList<Uri> result = new ArrayList<>(editingImageUris);
                    editingRefresh = null;
                    editingImageUris = null;
                    if (result.isEmpty()) {
                        toast("Tambahkan minimal 1 gambar");
                        return;
                    }
                    makeImagePdf(result, gray.isChecked(), crop.isChecked(), scanMode);
                })
                .create();

        dlg.setOnDismissListener(di -> {
            if (editingImageUris != null) {
                // Jangan menghapus daftar ketika dialog hanya tertutup sementara oleh pemilih gambar/kamera.
                // State akan dibersihkan oleh tombol Batal/Buat PDF.
            }
        });
        dlg.show();

        if (dlg.getWindow() != null) {
            int screen = getResources().getDisplayMetrics().widthPixels;
            dlg.getWindow().setLayout(Math.min(dp(620), screen - dp(24)),
                    Math.min(dp(760), getResources().getDisplayMetrics().heightPixels - dp(100)));
        }
    }

    void makeImagePdf(ArrayList<Uri> items, boolean gray, boolean crop, boolean scanMode) {
        worker.execute(()->{
            File f=null;
            try{
                f=tempFile(scanMode?"scan":"images");
                AdvancedPdfTools.imagesToPdf(this,items,f,gray,crop);
                File result=f;
                runOnUiThread(()->requestSaveAs(result,scanMode?"PDF-ku-scan.pdf":"PDF-ku-gambar.pdf"));
            }catch(Exception e){
                if(f!=null)f.delete();
                runOnUiThread(()->toast("Gagal membuat PDF: "+e.getMessage()));
            }
        });
    }

    void tools() {
        shell("PDF Tools");
        TextView intro=tx("Toolkit PDF v1.3 • semua proses utama dilakukan di perangkat.",14);
        intro.setTextColor(Color.rgb(148,163,184)); content.addView(intro);

        addTool("🔗","Gabung PDF","Satukan beberapa file",v->pickMerge(),Color.rgb(37,99,235));
        addTool("✂","Split PDF","Pisahkan rentang halaman",v->pickSplit(),Color.rgb(245,158,11));
        addTool("↕","Atur Halaman","Urutkan, putar, hapus halaman",v->pickPageManager(),Color.rgb(14,165,233));
        addTool("🗜","Kompres PDF","Kecilkan ukuran file",v->pickCompress(),Color.rgb(16,185,129));
        addTool("🖼","PDF → JPG","Ekspor halaman sebagai gambar",v->pickPdfToJpg(),Color.rgb(168,85,247));
        addTool("🔐","Password PDF","Enkripsi PDF dengan password",v->pickEncrypt(),Color.rgb(239,68,68));
        addTool("💧","Watermark","Tambahkan watermark teks",v->pickWatermark(),Color.rgb(236,72,153));
        addTool("🔎","Cari / Ekstrak Teks","Cari teks pada PDF",v->searchPdf(),Color.rgb(59,130,246));
        addTool("✍","Tanda Tangan","Gambar dan tempel signature",v->signatureDialog(),Color.rgb(124,58,237));
        addTool("📝","Anotasi","Tambahkan catatan pada halaman",v->annotationDialog(),Color.rgb(234,179,8));
        addTool("🌙","Dark Mode Viewer",darkViewer?"Aktif • ketuk untuk matikan":"Nonaktif • ketuk untuk aktifkan",v->toggleDarkViewer(),Color.rgb(71,85,105));

        LinearLayout about=actionCard("ⓘ","Tentang PDF-ku","Gratis • Lara Studio • v1.3.0",Color.rgb(100,116,139));
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(76));ap.setMargins(0,dp(16),0,0);content.addView(about,ap);
        about.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("PDF-ku 1.3.0")
                .setMessage("PDF-ku gratis dari Lara Studio. Fitur utama bekerja offline dan file tetap di perangkat.")
                .setPositiveButton("OK",null).show());
        add("←  Kembali",v->home());
    }

    void addTool(String icon,String title,String sub,View.OnClickListener click,int accent){
        LinearLayout c=actionCard(icon,title,sub,accent);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(72));p.setMargins(0,dp(6),0,0);
        content.addView(c,p);c.setOnClickListener(click);
    }

    void startScan(){ scanUris.clear(); captureScanPage(); }
    void captureScanPage(){
        try{
            File photo=File.createTempFile("pdfku-scan-",".jpg",getCacheDir());
            cameraOutputFile=photo;
            cameraOutputUri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",photo);
            Intent i=new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT,cameraOutputUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if(getPackageManager().queryIntentActivities(i,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).isEmpty()){
                if(cameraOutputFile!=null) cameraOutputFile.delete();
                cameraOutputUri=null;
                cameraOutputFile=null;
                toast("Kamera tidak tersedia");
                return;
            }
            startActivityForResult(i,REQ_SCAN);
        }catch(Exception e){
            if(cameraOutputFile!=null) cameraOutputFile.delete();
            cameraOutputUri=null;
            cameraOutputFile=null;
            toast("Tidak bisa membuka kamera scan");
        }
    }
    void askNextScanPage(){
        new AlertDialog.Builder(this).setTitle("Scan berikutnya?")
            .setMessage("Halaman tersimpan: "+scanUris.size())
            .setNegativeButton("Selesai",(d,w)->showImageOrderDialog(new ArrayList<>(scanUris),true))
            .setPositiveButton("Tambah halaman",(d,w)->captureScanPage()).show();
    }

    void pickPageManager(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/pdf");i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,REQ_SPLIT+20);
    }

    void pickCompress(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/pdf");i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,REQ_SPLIT+21);
    }
    void pickPdfToJpg(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/pdf");i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,REQ_SPLIT+22);
    }
    void pickEncrypt(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/pdf");i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,REQ_SPLIT+23);
    }
    void pickWatermark(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/pdf");i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,REQ_SPLIT+24);
    }

    void handleAdvancedResult(int r, Uri u){
        if(u==null)return;
        if(r==REQ_SPLIT+20) showPageManager(u);
        else if(r==REQ_SPLIT+21) showCompressDialog(u);
        else if(r==REQ_SPLIT+22) showPdfToJpgDialog(u);
        else if(r==REQ_SPLIT+23) showEncryptDialog(u);
        else if(r==REQ_SPLIT+24) showWatermarkDialog(u);
    }

    void showCompressDialog(Uri u){
        final String[] q={"Kecil • 55%","Seimbang • 70%","Kualitas • 82%"};
        final int[] selected={1};
        new AlertDialog.Builder(this).setTitle("Kompres PDF").setSingleChoiceItems(q,1,(d,w)->selected[0]=w)
          .setNegativeButton("Batal",null).setPositiveButton("Kompres",(d,w)->compressPdf(u,selected[0])).show();
    }
    void compressPdf(Uri u,int preset){
        worker.execute(()->{
            File f=null;
            try{
                f=tempFile("compressed");
                int quality=preset==0?55:preset==1?70:82;
                int scale=preset==0?70:preset==1?85:100;
                AdvancedPdfTools.compressPdf(this,u,f,quality,scale);
                File r=f;
                runOnUiThread(()->requestSaveAs(r,"PDF-ku-compressed.pdf"));
            }catch(Exception e){
                if(f!=null)f.delete();
                runOnUiThread(()->toast("Gagal kompres: "+e.getMessage()));
            }
        });
    }

    void showPdfToJpgDialog(Uri u){
        EditText pageInput=new EditText(this);pageInput.setHint("Nomor halaman, kosong = semua");pageInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this).setTitle("PDF → JPG").setMessage("Kosongkan untuk semua halaman. Kualitas 85%.")
          .setView(pageInput).setNegativeButton("Batal",null).setPositiveButton("Ekspor",(d,w)->{
              String x=pageInput.getText().toString().trim();
              int p=-1;
              try {
                  if(!x.isEmpty()) {
                      p=Integer.parseInt(x)-1;
                      if(p<0) throw new NumberFormatException();
                  }
                  exportPdfJpg(u,p);
              } catch(NumberFormatException e) {
                  toast("Nomor halaman tidak valid");
              }
          }).show();
    }
    void exportPdfJpg(Uri u,int selected){
        worker.execute(()->{
            File dir=new File(getCacheDir(),"pdfku-jpg-"+System.nanoTime());
            File zip=null;
            try{
                AdvancedPdfTools.pdfToJpg(this,u,dir,85,selected<0,Math.max(0,selected));
                zip=zipDirectory(dir);
                File result=zip;
                runOnUiThread(()->requestShareFile(result,"application/zip","PDF-ku-JPG.zip"));
            }catch(Exception e){
                if(zip!=null) zip.delete();
                runOnUiThread(()->toast("Gagal ekspor JPG: "+e.getMessage()));
            }finally{
                deleteTree(dir);
            }
        });
    }
    File zipDirectory(File dir) throws Exception{
        File zip=new File(getCacheDir(),"PDF-ku-JPG-"+System.nanoTime()+".zip");
        try(java.util.zip.ZipOutputStream z=new java.util.zip.ZipOutputStream(new FileOutputStream(zip))){
            File[] fs=dir.listFiles();
            if(fs==null || fs.length==0) throw new IOException("Tidak ada JPG yang berhasil dibuat");
            for(File f:fs){
                java.util.zip.ZipEntry e=new java.util.zip.ZipEntry(f.getName());
                z.putNextEntry(e);
                try(InputStream in=new FileInputStream(f)){copy(in,z);}
                z.closeEntry();
            }
        }
        return zip;
    }
    void deleteTree(File f){
        if(f==null)return;
        if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File x:a)deleteTree(x);}
        f.delete();
    }
    void requestShareFile(File f,String mime,String name){
        Uri u=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",f);Intent i=new Intent(Intent.ACTION_SEND);i.setType(mime);i.putExtra(Intent.EXTRA_STREAM,u);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"Bagikan hasil"));
    }

    void showEncryptDialog(Uri u){
        EditText p=new EditText(this);p.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);p.setHint("Password");
        new AlertDialog.Builder(this).setTitle("Password PDF").setMessage("Password akan diterapkan pada salinan baru.")
          .setView(p).setNegativeButton("Batal",null).setPositiveButton("Enkripsi",(d,w)->encryptPdf(u,p.getText().toString())).show();
    }
    void encryptPdf(Uri u,String password){
        if(password.length()<4){toast("Password minimal 4 karakter");return;}
        worker.execute(()->{File f=null;try{f=tempFile("encrypted");AdvancedPdfTools.encrypt(this,u,f,password);File r=f;runOnUiThread(()->requestSaveAs(r,"PDF-ku-protected.pdf"));}catch(Exception e){if(f!=null)f.delete();runOnUiThread(()->toast("Gagal mengenkripsi: "+e.getMessage()));}});
    }
    void showWatermarkDialog(Uri u){
        EditText t=new EditText(this);t.setHint("Contoh: LARA STUDIO");
        new AlertDialog.Builder(this).setTitle("Watermark PDF").setView(t).setNegativeButton("Batal",null).setPositiveButton("Tambahkan",(d,w)->watermarkPdf(u,t.getText().toString())).show();
    }
    void watermarkPdf(Uri u,String text){
        if(text.trim().isEmpty()){toast("Teks watermark kosong");return;}
        worker.execute(()->{File f=null;try{f=tempFile("watermark");AdvancedPdfTools.watermark(this,u,f,text,0.25f,28);File r=f;runOnUiThread(()->requestSaveAs(r,"PDF-ku-watermark.pdf"));}catch(Exception e){if(f!=null)f.delete();runOnUiThread(()->toast("Gagal watermark: "+e.getMessage()));}});
    }

    void searchPdf(){
        if(openedUri==null){pickPdf();return;}
        EditText q=new EditText(this);q.setHint("Kata yang dicari");
        new AlertDialog.Builder(this).setTitle("Cari teks PDF").setView(q).setNegativeButton("Batal",null).setPositiveButton("Cari",(d,w)->{
            String needle=q.getText().toString().trim();
            if(needle.isEmpty()){toast("Masukkan kata yang ingin dicari");return;}
            worker.execute(()->{
                try{
                    String text=AdvancedPdfTools.extractText(this,openedUri);
                    int count=0;
                    for(String line:text.split("\\n")){
                        if(line.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) count++;
                    }
                    String msg="Ditemukan pada "+count+" baris.";
                    String preview=text.substring(0,Math.min(text.length(),5000));
                    runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Hasil pencarian").setMessage(msg+"\n\n"+preview).setPositiveButton("OK",null).show());
                }catch(Exception e){
                    runOnUiThread(()->toast("PDF tidak memiliki teks yang bisa dicari."));
                }
            });
        }).show();
    }

    void showPageManager(Uri u){
        worker.execute(()->{
            try{
                int n; try(InputStream in=getContentResolver().openInputStream(u); PDDocument d=PDDocument.load(in)){n=d.getNumberOfPages();}
                ArrayList<Integer> order=new ArrayList<>(); for(int i=0;i<n;i++) order.add(i);
                Map<Integer,Integer> rotations=new HashMap<>();
                LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16),dp(4),dp(16),0);
                LinearLayout list=new LinearLayout(this);
                list.setOrientation(LinearLayout.VERTICAL);
                list.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
                box.addView(list,new LinearLayout.LayoutParams(-1,-2));
                final Runnable[] refresh=new Runnable[1]; refresh[0]=()->{
                    list.removeAllViews();
                    for(int pos=0;pos<order.size();pos++){
                        final int p=pos, original=order.get(pos);
                        LinearLayout row=new LinearLayout(this);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(56)));
                        TextView name=tx("Halaman "+(original+1)+"  →  posisi "+(pos+1),14); name.setTextColor(Color.WHITE);
                        row.addView(name,new LinearLayout.LayoutParams(0,dp(52),1));
                        Button up=bt("↑"),down=bt("↓"),rot=bt("↻"),del=bt("×");
                        up.setEnabled(pos>0);down.setEnabled(pos<order.size()-1);
                        row.addView(up,new LinearLayout.LayoutParams(dp(42),dp(42)));
                        row.addView(down,new LinearLayout.LayoutParams(dp(42),dp(42)));
                        row.addView(rot,new LinearLayout.LayoutParams(dp(42),dp(42)));
                        row.addView(del,new LinearLayout.LayoutParams(dp(42),dp(42)));
                        list.addView(row);
                        up.setOnClickListener(v->{Collections.swap(order,p,p-1);refresh[0].run();});
                        down.setOnClickListener(v->{Collections.swap(order,p,p+1);refresh[0].run();});
                        rot.setOnClickListener(v->{int next=((rotations.containsKey(original)?rotations.get(original):0)+90)%360;
                                rotations.put(original,next);
                                refresh[0].run();});
                        del.setOnClickListener(v->{order.remove(p);refresh[0].run();});
                    }
                };
                runOnUiThread(()->{
                    refresh[0].run();
                    new AlertDialog.Builder(this).setTitle("Atur Halaman").setMessage("Geser ↑↓, putar ↻, atau hapus ×.")
                        .setView(box).setNegativeButton("Batal",null).setPositiveButton("Simpan",(d,w)->{
                            if(order.isEmpty()){toast("PDF tidak boleh kosong");return;}
                            worker.execute(()->{File f=null;try{f=tempFile("pages");AdvancedPdfTools.reorderPages(this,u,f,order,new HashSet<>(),rotations);File r=f;runOnUiThread(()->requestSaveAs(r,"PDF-ku-pages.pdf"));}catch(Exception e){if(f!=null)f.delete();runOnUiThread(()->toast("Gagal mengatur halaman: "+e.getMessage()));}});
                        }).show();
                });
            }catch(Exception e){runOnUiThread(()->toast("Gagal membaca PDF: "+e.getMessage()));}
        });
    }

    void toggleDarkViewer(){darkViewer=!darkViewer;getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("dark_viewer",darkViewer).apply();toast(darkViewer?"Dark viewer aktif":"Dark viewer nonaktif");if(renderer!=null)viewer();else tools();}
    void signatureDialog(){
        if(openedUri==null){toast("Buka PDF terlebih dahulu");return;}
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        ImageView pad=new ImageView(this);pad.setBackgroundColor(Color.WHITE);Bitmap sig=Bitmap.createBitmap(900,300,Bitmap.Config.ARGB_8888);sig.eraseColor(Color.WHITE);pad.setImageBitmap(sig);
        final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);pen.setColor(Color.BLACK);pen.setStrokeWidth(8);final float[] last={-1,-1};
        pad.setOnTouchListener((v,e)->{float x=e.getX()*900f/Math.max(1,pad.getWidth()),y=e.getY()*300f/Math.max(1,pad.getHeight());Canvas c=new Canvas(sig);if(e.getAction()==MotionEvent.ACTION_DOWN){last[0]=x;last[1]=y;}else if(e.getAction()==MotionEvent.ACTION_MOVE){c.drawLine(last[0],last[1],x,y,pen);last[0]=x;last[1]=y;pad.invalidate();}return true;});
        box.addView(pad,new LinearLayout.LayoutParams(-1,dp(180)));
        new AlertDialog.Builder(this).setTitle("Tanda Tangan").setView(box).setNegativeButton("Batal",null).setPositiveButton("Tempel",(d,w)->stampSignature(sig)).show();
    }
    void stampSignature(Bitmap sig){
        final Uri sourceUri=openedUri;
        final int pageIndex=page;
        worker.execute(()->{File f=null;try{f=tempFile("signature");AdvancedPdfTools.addSignature(this,sourceUri,f,pageIndex,sig,72,72,180,60);File r=f;runOnUiThread(()->requestSaveAs(r,"PDF-ku-signature.pdf"));}catch(Exception e){if(f!=null)f.delete();runOnUiThread(()->toast("Gagal menambahkan tanda tangan: "+e.getMessage()));}});
    }
    void annotationDialog(){
        if(openedUri==null){toast("Buka PDF terlebih dahulu");return;}
        EditText t=new EditText(this);t.setHint("Catatan");
        final Uri sourceUri=openedUri;
        final int pageIndex=page;
        new AlertDialog.Builder(this).setTitle("Anotasi halaman "+(pageIndex+1)).setView(t).setNegativeButton("Batal",null).setPositiveButton("Tambah",(d,w)->{
            String note=t.getText().toString().trim();
            if(note.isEmpty()){toast("Catatan kosong");return;}
            worker.execute(()->{File f=null;try{f=tempFile("annotation");AdvancedPdfTools.addTextAnnotation(this,sourceUri,f,pageIndex,note);File r=f;runOnUiThread(()->requestSaveAs(r,"PDF-ku-annotation.pdf"));}catch(Exception e){if(f!=null)f.delete();runOnUiThread(()->toast("Gagal menambah anotasi: "+e.getMessage()));}});
        }).show();
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
                source.delete();
                runOnUiThread(() -> toast("Gagal menyimpan PDF: " + e.getMessage()));
            }
        });
    }

    void clearPendingSave(boolean deleteFile) {
        File f = pendingSaveFile;
        pendingSaveFile = null;
        pendingSaveName = null;
        if (deleteFile && f != null) f.delete();
    }

    void shareUri(Uri u) {
        if (u == null) { toast("Tidak ada PDF yang dipilih"); return; }

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/pdf");

        List<android.content.pm.ResolveInfo> apps = getPackageManager().queryIntentActivities(
                send, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        );

        if (apps == null || apps.isEmpty()) {
            toast("Tidak ada aplikasi yang bisa membagikan PDF");
            return;
        }

        Collections.sort(apps, (a, b) -> {
            String la = String.valueOf(a.loadLabel(getPackageManager()));
            String lb = String.valueOf(b.loadLabel(getPackageManager()));
            return la.compareToIgnoreCase(lb);
        });

        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = String.valueOf(apps.get(i).loadLabel(getPackageManager()));
        }

        new AlertDialog.Builder(this)
                .setTitle("Kirim PDF dengan")
                .setItems(labels, (dialog, which) -> {
                    String packageName = apps.get(which).activityInfo.packageName;
                    showShareNameDialog(u, packageName);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    void showShareNameDialog(Uri u, String packageName) {
        String currentName = displayName(u);
        if (currentName == null || currentName.trim().isEmpty()) currentName = "Dokumen.pdf";
        if (!currentName.toLowerCase(Locale.ROOT).endsWith(".pdf")) currentName += ".pdf";

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(currentName.substring(0, currentName.length() - 4));
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint("Nama PDF");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), 0, dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));

        new AlertDialog.Builder(this)
                .setTitle("Nama PDF")
                .setMessage("Pilih atau ubah nama file yang akan dikirim.")
                .setView(box)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Kirim", (dialog, which) ->
                        shareRenamedPdf(u, input.getText().toString(), packageName))
                .show();

        input.requestFocus();
    }

    String safePdfName(String name) {
        if (name == null) return "Dokumen.pdf";
        name = name.trim();
        if (name.isEmpty()) name = "Dokumen";

        if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            name = name.substring(0, name.length() - 4);
        }

        name = name.replaceAll("[\\/:*?\"<>|\\x00-\\x1F]", "_");
        name = name.replaceAll("\\s+", " ").trim();
        if (name.isEmpty()) name = "Dokumen";
        if (name.length() > 120) name = name.substring(0, 120).trim();

        return name + ".pdf";
    }

    void shareRenamedPdf(Uri source, String requestedName, String packageName) {
        String fileName = safePdfName(requestedName);
        File shareDir = new File(getCacheDir(), "share-" + System.nanoTime());
        File shareFile = new File(shareDir, fileName);

        worker.execute(() -> {
            try {
                if (!shareDir.mkdirs()) throw new IOException("Cache share tidak bisa dibuat");
                try (InputStream in = getContentResolver().openInputStream(source);
                     OutputStream out = new FileOutputStream(shareFile, false)) {
                    if (in == null) throw new IOException("Tidak bisa membaca PDF");
                    copy(in, out);
                }

                runOnUiThread(() -> {
                    try {
                        Uri shareUri = FileProvider.getUriForFile(
                                this,
                                getPackageName() + ".fileprovider",
                                shareFile
                        );

                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("application/pdf");
                        i.setPackage(packageName);
                        i.putExtra(Intent.EXTRA_STREAM, shareUri);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(i);
                    } catch (Exception e) {
                        deleteTree(shareDir);
                        toast("Gagal menyiapkan PDF untuk dibagikan");
                    }
                });
            } catch (Exception e) {
                deleteTree(shareDir);
                runOnUiThread(() -> toast("Gagal menyiapkan PDF: " + e.getMessage()));
            }
        });
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
            TextView empty = tx("Belum ada PDF yang dibuka.\n\nFile yang kamu buka nanti akan muncul di sini.", 15);
            empty.setTextColor(Color.rgb(148, 163, 184));
            content.addView(empty);
        } else {
            TextView label = tx("TERBARU", 11);
            label.setTextColor(Color.rgb(96, 165, 250));
            label.setTypeface(Typeface.DEFAULT_BOLD);
            content.addView(label);
            for (Uri u : new ArrayList<>(recent)) {
                Button b = bt("📄  " + displayName(u));
                b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                b.setBackground(bg(Color.rgb(15, 23, 42), 16));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(62));
                p.setMargins(0, dp(7), 0, 0);
                content.addView(b, p);
                b.setOnClickListener(v -> open(u));
            }
        }
        add("←  Kembali", v -> home());
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
