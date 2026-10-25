package local.tv.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded asynchronous artwork. Never runs networking or decoding on the UI thread. */
final class Artwork implements AutoCloseable {
    interface Callback { void ready(Bitmap image); }
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final long DISK_CAP = 16L * 1024 * 1024;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final File directory;
    private volatile boolean closed;
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    Artwork(Context context) { this.context=context.getApplicationContext(); directory=new File(context.getCacheDir(), "art"); directory.mkdirs(); }
    void load(String raw, Callback callback) {
        if (closed || raw == null || raw.isEmpty()) return;
        Bitmap cached=memory.get(raw);
        if(cached!=null) { callback.ready(cached); return; }
        executor.execute(() -> {
            Bitmap result=null;
            try {
                Uri uri=Uri.parse(raw); String scheme=uri.getScheme();
                if (!"https".equals(scheme) && !"http".equals(scheme) && !"content".equals(scheme) && !"android.resource".equals(scheme)) return;
                byte[] bytes;
                File file=new File(directory, hash(raw));
                if(file.isFile() && file.length()<=MAX_BYTES) { try(InputStream in=new FileInputStream(file)) {bytes=readBounded(in);} file.setLastModified(System.currentTimeMillis()); }
                else {
                    if("http".equals(scheme) || "https".equals(scheme)) bytes=download(raw);
                    else try(InputStream in=context.getContentResolver().openInputStream(uri)) { bytes=readBounded(in); }
                    if (bytes!=null) {
                        synchronized(directory) { try(FileOutputStream out=new FileOutputStream(file)) {out.write(bytes);} trimDisk(); }
                    }
                }
                if(bytes!=null) {
                    BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
                    BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
                    if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth>16384 || bounds.outHeight>16384) return;
                    BitmapFactory.Options options=new BitmapFactory.Options(); options.inSampleSize=1;
                    while(bounds.outWidth/options.inSampleSize>1600 || bounds.outHeight/options.inSampleSize>1000) options.inSampleSize*=2;
                    options.inPreferredConfig=Bitmap.Config.RGB_565;
                    result=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                    if(result!=null) memory.put(raw,result);
                }
            } catch(Exception | OutOfMemoryError ignored) { /* Caller keeps scenic fallback. */ }
            Bitmap delivered=result;
            if(!closed) main.post(() -> {if(!closed && delivered!=null) callback.ready(delivered);});
        });
    }
    private byte[] download(String raw) throws Exception {
        String current=raw;
        for(int redirect=0;redirect<4;redirect++) {
            URL url=new URL(current);
            if(!"https".equals(url.getProtocol()) && !"http".equals(url.getProtocol())) throw new IOException("Unsupported redirect");
            if(url.getUserInfo()!=null||NetworkPolicy.obsoleteMetadataHost(url.getHost())) throw new IOException("Credentials forbidden");
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();
            connection.setConnectTimeout(3500); connection.setReadTimeout(3500); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "image/*");connection.setRequestProperty("User-Agent","FudgeTV/2.0");
            try {
                int code=connection.getResponseCode();
                if(code>=300 && code<400) {
                    String location=connection.getHeaderField("Location");
                    if(location==null) throw new IOException("Missing redirect");
                    current=new URL(url, location).toExternalForm(); continue;
                }
                if(code!=200 || connection.getContentLengthLong()>MAX_BYTES) throw new IOException("Artwork unavailable or too large");
                String type=connection.getContentType();
                if(type!=null && !type.startsWith("image/")) throw new IOException("Not an image");
                try(InputStream in=connection.getInputStream()) {return readBounded(in);}
            } finally {connection.disconnect();}
        }
        throw new IOException("Too many redirects");
    }
    private static byte[] readBounded(InputStream input) throws IOException {
        if(input==null) throw new IOException("No stream");
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] block=new byte[8192]; int n,total=0;
        while((n=input.read(block))!=-1) { total+=n; if(total>MAX_BYTES) throw new IOException("Artwork too large"); out.write(block,0,n); }
        return out.toByteArray();
    }
    private void trimDisk() {
        File[] files=directory.listFiles(); if(files==null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long size=0;for(File f:files) size+=f.length();
        for(File f:files) {if(size<=DISK_CAP) break;long length=f.length();if(f.delete())size-=length;}
    }
    private static String hash(String s) throws Exception {byte[] digest=MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format("%02x",b));return out.toString();}
    @Override public void close(){closed=true;executor.shutdownNow();main.removeCallbacksAndMessages(null);memory.evictAll();}
}
