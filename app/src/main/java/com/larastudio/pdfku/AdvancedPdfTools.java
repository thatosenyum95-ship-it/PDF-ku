package com.larastudio.pdfku;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.media.ExifInterface;
import android.net.Uri;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import android.graphics.pdf.PdfRenderer;

import java.io.*;
import java.util.*;

public final class AdvancedPdfTools {
    private AdvancedPdfTools() {}

    public static Bitmap orientedBitmap(Context c, Uri uri) throws IOException {
        Bitmap b;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Gambar tidak bisa dibaca");
            b = BitmapFactory.decodeStream(in);
        }
        if (b == null) throw new IOException("Gambar rusak atau format tidak didukung");

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream ex = c.getContentResolver().openInputStream(uri)) {
            if (ex != null) orientation = new ExifInterface(ex).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        }

        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.setRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.setRotate(270); m.postScale(-1, 1); break;
            default: return b;
        }
        Bitmap out = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (out != b) b.recycle();
        return out;
    }

    public static Bitmap enhanceScan(Bitmap src, boolean grayscale) {
        Bitmap b = src;
        if (grayscale) {
            Bitmap g = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(g);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0f);
            p.setColorFilter(new ColorMatrixColorFilter(cm));
            c.drawBitmap(b, 0, 0, p);
            b = g;
            if (g != src) src.recycle();
        }
        return b;
    }

    public static void imagesToPdf(Context c, List<Uri> uris, File out, boolean grayscale, boolean crop) throws Exception {
        PdfDocument pdf = new PdfDocument();
        int pageNo = 1;
        try {
            for (Uri uri : uris) {
                Bitmap b = orientedBitmap(c, uri);
                b = enhanceScan(b, grayscale);
                if (crop) b = cropBorder(b);
                float scale = Math.min(535f / b.getWidth(), 762f / b.getHeight());
                int w = Math.max(1, Math.round(b.getWidth() * scale));
                int h = Math.max(1, Math.round(b.getHeight() * scale));
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(595, 842, pageNo++).create());
                page.getCanvas().drawColor(Color.WHITE);
                page.getCanvas().drawBitmap(b, null,
                        new RectF((595-w)/2f, (842-h)/2f, (595+w)/2f, (842+h)/2f),
                        new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
                pdf.finishPage(page);
                b.recycle();
            }
            try (FileOutputStream os = new FileOutputStream(out)) { pdf.writeTo(os); }
        } finally { pdf.close(); }
    }

    private static Bitmap cropBorder(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int step = Math.max(1, Math.min(w, h) / 250);
        int bg = Color.WHITE;
        long r=0,g=0,bl=0,n=0;
        for (int y=0;y<h;y+=Math.max(1,h/20)) for(int x=0;x<w;x+=Math.max(1,w/20)) {
            int c=src.getPixel(x,y); r+=Color.red(c); g+=Color.green(c); bl+=Color.blue(c); n++;
        }
        if(n>0) bg=Color.rgb((int)(r/n),(int)(g/n),(int)(bl/n));
        int threshold=45, left=w, top=h, right=-1, bottom=-1;
        for(int y=0;y<h;y+=step) for(int x=0;x<w;x+=step) {
            int c=src.getPixel(x,y);
            int d=Math.abs(Color.red(c)-Color.red(bg))+Math.abs(Color.green(c)-Color.green(bg))+Math.abs(Color.blue(c)-Color.blue(bg));
            if(d>threshold){left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);}
        }
        if (right < 0 || bottom < 0 || left >= w || top >= h) return src;
        int pad=Math.max(8,Math.min(w,h)/40);
        left=Math.max(0,left-pad); top=Math.max(0,top-pad); right=Math.min(w-1,right+pad); bottom=Math.min(h-1,bottom+pad);
        if(right<=left || bottom<=top || (right-left)<w/3 || (bottom-top)<h/3) return src;
        Bitmap out=Bitmap.createBitmap(src,left,top,right-left+1,bottom-top+1);
        src.recycle();
        return out;
    }

    public static void pdfToJpg(Context c, Uri uri, File dir, int quality, boolean allPages, int selectedPage) throws Exception {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Folder output tidak tersedia");
        try (android.os.ParcelFileDescriptor fd=c.getContentResolver().openFileDescriptor(uri,"r")) {
            if(fd==null) throw new IOException("PDF tidak bisa dibuka");
            PdfRenderer r=new PdfRenderer(fd);
            try {
                int from=allPages?0:selectedPage, to=allPages?r.getPageCount()-1:selectedPage;
                for(int i=from;i<=to;i++){
                    PdfRenderer.Page p=r.openPage(i);
                    Bitmap b=Bitmap.createBitmap(p.getWidth(),p.getHeight(),Bitmap.Config.ARGB_8888);
                    b.eraseColor(Color.WHITE); p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT); p.close();
                    File f=new File(dir,"page-"+(i+1)+".jpg");
                    try(FileOutputStream os=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,quality,os);}
                    b.recycle();
                }
            } finally {r.close();}
        }
    }

    public static void compressPdf(Context c, Uri uri, File out, int quality, int scale) throws Exception {
        File dir=new File(c.getCacheDir(),"compress-"+System.nanoTime()); if(!dir.mkdirs()) throw new IOException("Cache gagal");
        try(android.os.ParcelFileDescriptor fd=c.getContentResolver().openFileDescriptor(uri,"r")){
            if(fd==null) throw new IOException("PDF tidak bisa dibuka");
            PdfRenderer r=new PdfRenderer(fd); PdfDocument pdf=new PdfDocument();
            try{
                for(int i=0;i<r.getPageCount();i++){
                    PdfRenderer.Page p=r.openPage(i);
                    int safeScale=Math.max(40,Math.min(100,scale));
                    int w=Math.max(1,p.getWidth()*safeScale/100), h=Math.max(1,p.getHeight()*safeScale/100);
                    Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.RGB_565); b.eraseColor(Color.WHITE);
                    p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT); p.close();
                    File jpg=new File(dir,"p"+i+".jpg"); try(FileOutputStream os=new FileOutputStream(jpg)){b.compress(Bitmap.CompressFormat.JPEG,quality,os);}
                    Bitmap small=BitmapFactory.decodeFile(jpg.getAbsolutePath());
                    PdfDocument.Page q=pdf.startPage(new PdfDocument.PageInfo.Builder(w,h,i+1).create());
                    q.getCanvas().drawBitmap(small,0,0,null); pdf.finishPage(q);
                    small.recycle(); b.recycle();
                }
                try(FileOutputStream os=new FileOutputStream(out)){pdf.writeTo(os);}
            } finally {pdf.close();r.close();}
        } finally {deleteTree(dir);}
    }

    public static void reorderPages(Context c, Uri uri, File out, List<Integer> order, Set<Integer> deleted, Map<Integer,Integer> rotations) throws Exception {
        try(InputStream in=c.getContentResolver().openInputStream(uri); PDDocument src=PDDocument.load(in); PDDocument dst=new PDDocument()){
            for(Integer idx:order){
                if(deleted.contains(idx)) continue;
                PDPage p=src.getPage(idx);
                PDPage copy=dst.importPage(p);
                Integer rot=rotations.get(idx);
                if(rot!=null) copy.setRotation((p.getRotation()+rot)%360);
            }
            dst.save(out);
        }
    }

    public static void watermark(Context c, Uri uri, File out, String text, float opacity, int size) throws Exception {
        float alpha=Math.max(0.05f,Math.min(1f,opacity));
        try(InputStream in=c.getContentResolver().openInputStream(uri); PDDocument doc=PDDocument.load(in)){
            for(PDPage page:doc.getPages()){
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream cs=new com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc,page,com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND,true,true);
                PDExtendedGraphicsState gs=new PDExtendedGraphicsState();
                gs.setNonStrokingAlphaConstant(alpha);
                cs.setGraphicsStateParameters(gs);
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD,size);
                cs.setNonStrokingColor(150,150,150);
                PDRectangle b=page.getMediaBox();
                cs.newLineAtOffset(b.getWidth()/2-size*text.length()/4f,b.getHeight()/2);
                cs.showText(text);
                cs.endText();
                cs.close();
            }
            doc.save(out);
        }
    }

    public static void encrypt(Context c, Uri uri, File out, String password) throws Exception {
        try(InputStream in=c.getContentResolver().openInputStream(uri); PDDocument doc=PDDocument.load(in)){
            AccessPermission ap=new AccessPermission();
            StandardProtectionPolicy policy=new StandardProtectionPolicy(password,password,ap);
            policy.setEncryptionKeyLength(128);
            doc.protect(policy); doc.save(out);
        }
    }

    public static String extractText(Context c, Uri uri) throws Exception {
        try(InputStream in=c.getContentResolver().openInputStream(uri); PDDocument doc=PDDocument.load(in)){
            PDFTextStripper stripper=new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    public static void addTextAnnotation(Context c, Uri uri, File out, int pageIndex, String contents) throws Exception {
        try(InputStream in=c.getContentResolver().openInputStream(uri); PDDocument doc=PDDocument.load(in)){
            PDPage page=doc.getPage(pageIndex);
            PDAnnotationText a=new PDAnnotationText();
            a.setContents(contents); a.setName("PDF-ku");
            a.setRectangle(new PDRectangle(72, page.getMediaBox().getHeight()-120, 28, 28));
            page.getAnnotations().add(a); doc.save(out);
        }
    }

    public static void addSignature(Context c, Uri uri, File out, int pageIndex, Bitmap signature, float x, float y, float w, float h) throws Exception {
        try(InputStream in=c.getContentResolver().openInputStream(uri); PDDocument doc=PDDocument.load(in)){
            PDPage page=doc.getPage(pageIndex);
            PDImageXObject img=LosslessFactory.createFromImage(doc,signature);
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream cs=new com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc,page,com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode.APPEND,true,true);
            cs.drawImage(img,x,y,w,h); cs.close(); doc.save(out);
        }
    }

    private static void deleteTree(File f){
        if(f==null)return;
        if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File x:a)deleteTree(x);}
        f.delete();
    }
}