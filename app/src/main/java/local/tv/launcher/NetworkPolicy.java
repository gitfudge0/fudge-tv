package local.tv.launcher;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
/** Pure boundary checks; never include request URLs or credentials in error messages. */
public final class NetworkPolicy {
 private static final Set<String> HOSTS=new HashSet<>(Arrays.asList("api.themoviedb.org","image.tmdb.org","api.open-meteo.com","geocoding-api.open-meteo.com"));
 private NetworkPolicy(){}
 public static boolean allowedUrl(String value){try{URI u=new URI(value);return "https".equals(u.getScheme())&&HOSTS.contains(u.getHost())&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443)&&u.getFragment()==null;}catch(Exception e){return false;}}
 public static boolean imageUrl(String value){try{URI u=new URI(value);return allowedUrl(value)&&"image.tmdb.org".equals(u.getHost())&&u.getQuery()==null&&u.getPath().matches("/t/p/(w1280|w500|original)/[A-Za-z0-9_-]+\\.(jpg|png|webp)");}catch(Exception e){return false;}}
 public static boolean validType(String type){return "movie".equals(type)||"tv".equals(type);}
 public static boolean validId(int id){return id>0;}
 public static String query(String value){if(value==null)return "";String s=value.trim();if(s.length()>120)s=s.substring(0,120);return s.replaceAll("[\\p{Cntrl}]","");}
 public static String credential(String value){if(value==null)return "";String s=value.trim().replaceFirst("(?i)^Bearer\\s+","");if(s.isEmpty())return "";if(s.length()>2048||!s.matches("[A-Za-z0-9._-]+"))throw new IllegalArgumentException("Invalid credential format");return s;}
 public static String region(String value){if(value==null||!value.matches("[A-Z]{2}"))throw new IllegalArgumentException("Use a two-letter uppercase region");return value;}
 public static boolean providerLink(String value){try{URI u=new URI(value);return "https".equals(u.getScheme())&&"www.themoviedb.org".equals(u.getHost())&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443)&&u.getFragment()==null&&u.getPath().matches("/(movie|tv)/[0-9]+/watch");}catch(Exception e){return false;}}
 private static String genreKey(String value){return value==null?"":value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]","");}
 public static boolean genreMatches(String query,String actual){String q=genreKey(query),a=genreKey(actual);return !q.isEmpty()&&(q.equals(a)||((q.equals("scifi")||q.equals("sciencefiction"))&&(a.equals("sciencefiction")||a.equals("scififantasy"))));}
 public static boolean apiKey(String value){return value!=null&&value.matches("[a-fA-F0-9]{32}");}
}
