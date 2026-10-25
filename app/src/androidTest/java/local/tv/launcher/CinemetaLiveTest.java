package local.tv.launcher;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.net.URL;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import javax.net.ssl.HttpsURLConnection;
import static org.junit.Assert.*;
/** Opt-in device provider contract check: -e liveCinemeta true. Never launches playback. */
@RunWith(AndroidJUnit4.class)
public class CinemetaLiveTest {
 @Test public void officialCatalogAndNativeNormalization()throws Exception{
  Assume.assumeTrue("Explicit live-provider opt-in required",Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("liveCinemeta","false")));
  String url="https://v3-cinemeta.strem.io/catalog/movie/top.json";
  HttpsURLConnection connection=(HttpsURLConnection)new URL(url).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(5000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent","FudgeTV/2.0");connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Accept-Encoding","identity");
  try{int status=connection.getResponseCode();System.out.println("Cinemeta raw HTTPS status="+status+" contentType="+connection.getContentType()+" length="+connection.getContentLengthLong());assertTrue("Provider status="+status+" location="+connection.getHeaderField("Location"),status==200||(status==307&&NetworkPolicy.catalogRedirect(url,connection.getHeaderField("Location"))));}finally{connection.disconnect();}
  byte[] body=SecureHttp.get(url,Collections.singletonMap("Accept","application/json"),1024*1024);JSONObject raw=new JSONObject(new String(body,StandardCharsets.UTF_8));assertTrue(raw.getJSONArray("metas").length()>0);assertTrue(CatalogClient.normalizeList(raw.getJSONArray("metas"),"movie").length()>0);
  JSONObject home=new CatalogClient(InstrumentationRegistry.getInstrumentation().getTargetContext()).home();System.out.println("Cinemeta native status="+home.optString("status")+" items="+home.optJSONArray("items").length());assertNotEquals("error",home.getString("status"));assertTrue(home.getJSONArray("items").length()>0);
 }
}
