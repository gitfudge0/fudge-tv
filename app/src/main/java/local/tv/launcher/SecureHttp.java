package local.tv.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** HTTPS transport with bounded response size and one verified Cinemeta catalog handoff. */
public final class SecureHttp {
  private static final int SOCKET_TIMEOUT_MS = 5000;
  private static final long REQUEST_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(10);

  private SecureHttp() {}

  public static byte[] get(String url, Map<String, String> headers, int maxBytes)
      throws IOException {
    return get(url, headers, maxBytes, 0, System.nanoTime() + REQUEST_TIMEOUT_NS);
  }

  private static byte[] get(
      String url, Map<String, String> headers, int maxBytes, int redirects, long deadline)
      throws IOException {
    remainingTimeout(deadline);
    if (!NetworkPolicy.allowedUrl(url) || maxBytes < 1 || maxBytes > 5 * 1024 * 1024) {
      throw new IOException("Request rejected");
    }
    HttpsURLConnection connection = null;
    try {
      connection = (HttpsURLConnection) new URL(url).openConnection();
      connection.setConnectTimeout(remainingTimeout(deadline));
      connection.setReadTimeout(remainingTimeout(deadline));
      connection.setInstanceFollowRedirects(false);
      connection.setUseCaches(false);
      connection.setRequestProperty("Accept-Encoding", "identity");
      connection.setRequestProperty("User-Agent", "FudgeTV/2.0");
      if (headers != null) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
          if (!"Accept".equals(header.getKey())) throw new IOException("Header rejected");
          connection.setRequestProperty(header.getKey(), header.getValue());
        }
      }
      int status = connection.getResponseCode();
      remainingTimeout(deadline);
      if (status == 307 && redirects == 0) {
        String next = connection.getHeaderField("Location");
        if (NetworkPolicy.catalogRedirect(url, next)) {
          connection.disconnect();
          connection = null;
          return get(next, headers, maxBytes, redirects + 1, deadline);
        }
      }
      if (status != 200) throw new IOException("Provider request failed");
      if (connection.getContentLengthLong() > maxBytes) throw new IOException("Response too large");
      try (InputStream in = connection.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        while (true) {
          // Recompute the remaining budget: a slow trickle must not reset the deadline.
          connection.setReadTimeout(remainingTimeout(deadline));
          int size = in.read(buffer);
          remainingTimeout(deadline);
          if (size == -1) return out.toByteArray();
          if (out.size() + size > maxBytes) throw new IOException("Response too large");
          out.write(buffer, 0, size);
        }
      }
    } catch (InterruptedIOException interrupted) {
      throw interrupted;
    } catch (IOException | RuntimeException unavailable) {
      // Keep provider URLs and user queries out of surfaced errors.
      throw new IOException("Provider unavailable");
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  static int remainingTimeout(long deadline) throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) throw new SocketTimeoutException("Provider timed out");
    return (int) Math.max(1, Math.min(SOCKET_TIMEOUT_MS, TimeUnit.NANOSECONDS.toMillis(remaining)));
  }
}
