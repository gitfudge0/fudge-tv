package local.tv.launcher;
import java.io.*;
import java.net.*;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
/** Small HTTPS transport. Only the verified official Cinemeta catalog handoff may redirect once. */
public final class SecureHttp {
 private SecureHttp(){}
 public static byte[] get(String url,Map<String,String> headers,int maxBytes)throws IOException{return get(url,headers,maxBytes,0);}
 private static byte[] get(String url,Map<String,String> headers,int maxBytes,int redirects)throws IOException{
  if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
  if(!NetworkPolicy.allowedUrl(url)||maxBytes<1||maxBytes>5*1024*1024)throw new IOException("Request rejected");
  HttpsURLConnection c=null;
  try{c=(HttpsURLConnection)new URL(url).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestProperty("Accept-Encoding","identity");c.setRequestProperty("User-Agent","FudgeTV/2.0");
   if(headers!=null)for(Map.Entry<String,String> h:headers.entrySet()){if(!"Accept".equals(h.getKey()))throw new IOException("Header rejected");c.setRequestProperty(h.getKey(),h.getValue());}
   int status=c.getResponseCode();if(status==307&&redirects==0){String next=c.getHeaderField("Location");if(NetworkPolicy.catalogRedirect(url,next)){c.disconnect();c=null;return get(next,headers,maxBytes,redirects+1);}}if(status!=200)throw new IOException("Provider request failed");
   if(c.getContentLengthLong()>maxBytes)throw new IOException("Response too large");
   try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();if(out.size()+n>maxBytes)throw new IOException("Response too large");out.write(buf,0,n);}return out.toByteArray();}
  }catch(IOException|RuntimeException e){throw new IOException("Provider unavailable");}finally{if(c!=null)c.disconnect();}
 }
}
