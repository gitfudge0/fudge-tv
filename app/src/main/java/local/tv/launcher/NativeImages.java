package local.tv.launcher;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.LruCache;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Opaque local image URLs. Only native provider art and installed-app banners enter this registry. */
final class NativeImages {
    private static final int LIMIT=5*1024*1024;
    private final Context context;private final File directory;
    private final ConcurrentHashMap<String,Entry> registry=new ConcurrentHashMap<>();
    private final LruCache<String,byte[]> memory=new LruCache<String,byte[]>(8*1024*1024){@Override protected int sizeOf(String k,byte[] value){return value.length;}};
    private static final class Entry {final String uri;final Drawable drawable;Entry(String uri,Drawable d){this.uri=uri;drawable=d;}}
    NativeImages(Context c){context=c.getApplicationContext();directory=new File(c.getCacheDir(),"web_art");directory.mkdirs();}
    String artwork(String uri){if(uri==null||uri.isEmpty())return "";String key=hash("art:"+uri);registry.put(key,new Entry(uri,null));return BridgePolicy.ORIGIN+"/art/"+key;}
    String banner(String id,Drawable d){if(d==null)return "";String key=hash("banner:"+id);registry.put(key,new Entry(null,d));return BridgePolicy.ORIGIN+"/art/"+key;}
    byte[] get(String key)throws IOException{
        if(!key.matches("[a-f0-9]{64}"))throw new IOException("Image unavailable");
        byte[] saved=memory.get(key);if(saved!=null)return saved;Entry entry=registry.get(key);if(entry==null)throw new IOException("Image unavailable");
        File file=new File(directory,key);
        if(file.isFile()&&file.length()<=LIMIT){try(InputStream in=new FileInputStream(file)){saved=bounded(in);}memory.put(key,saved);return saved;}
        Bitmap bitmap=null;
        try{
            if(entry.drawable!=null){Drawable d=entry.drawable;bitmap=Bitmap.createBitmap(512,288,Bitmap.Config.RGB_565);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.rgb(14,16,19));int w=Math.max(1,d.getIntrinsicWidth()),h=Math.max(1,d.getIntrinsicHeight());float scale=Math.min(512f/w,288f/h);Rect previous=new Rect(d.getBounds());int dw=Math.round(w*scale),dh=Math.round(h*scale);d.setBounds((512-dw)/2,(288-dh)/2,(512+dw)/2,(288+dh)/2);d.draw(canvas);d.setBounds(previous);}
            else{
                Uri uri=Uri.parse(entry.uri);String scheme=uri.getScheme();byte[] raw;
                if("https".equals(scheme)||"http".equals(scheme))raw=download(entry.uri);
                else if("content".equals(scheme)||"android.resource".equals(scheme)){try(InputStream in=context.getContentResolver().openInputStream(uri)){raw=bounded(in);}}
                else throw new IOException("Image unavailable");
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(raw,0,raw.length,bounds);
                if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>16384||bounds.outHeight>16384)throw new IOException("Image unavailable");
                BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(bounds.outWidth/options.inSampleSize>1600||bounds.outHeight/options.inSampleSize>1000)options.inSampleSize*=2;options.inPreferredConfig=Bitmap.Config.RGB_565;bitmap=BitmapFactory.decodeByteArray(raw,0,raw.length,options);
            }
            if(bitmap==null)throw new IOException("Image unavailable");ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,88,out);saved=out.toByteArray();if(saved.length>LIMIT)throw new IOException("Image unavailable");memory.put(key,saved);
            synchronized(directory){try(FileOutputStream stream=new FileOutputStream(file)){stream.write(saved);}trim();}
            return saved;
        }catch(RuntimeException|OutOfMemoryError e){throw new IOException("Image unavailable");}finally{if(bitmap!=null)bitmap.recycle();}
    }
    private byte[] download(String raw)throws IOException{
        URL url=new URL(raw);
        for(int redirects=0;redirects<3;redirects++){
            if((!"https".equals(url.getProtocol())&&!"http".equals(url.getProtocol()))||url.getUserInfo()!=null)throw new IOException("Image unavailable");
            HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(4000);c.setReadTimeout(4000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","image/*");
            try{int code=c.getResponseCode();if(code>=300&&code<400){String next=c.getHeaderField("Location");if(next==null)throw new IOException("Image unavailable");url=new URL(url,next);continue;}if(code!=200||c.getContentLengthLong()>LIMIT)throw new IOException("Image unavailable");try(InputStream in=c.getInputStream()){return bounded(in);}}finally{c.disconnect();}
        }throw new IOException("Image unavailable");
    }
    private static byte[] bounded(InputStream in)throws IOException{if(in==null)throw new IOException("Image unavailable");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>LIMIT)throw new IOException("Image unavailable");out.write(buffer,0,n);}return out.toByteArray();}
    private void trim(){File[] files=directory.listFiles();if(files==null)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));long bytes=0;for(File f:files)bytes+=f.length();for(File f:files){if(bytes<=16L*1024*1024)break;long size=f.length();if(f.delete())bytes-=size;}}
    private static String hash(String value){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));StringBuilder s=new StringBuilder();for(byte b:d)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}catch(Exception impossible){throw new IllegalStateException("Image unavailable");}}
}
