package local.tv.launcher;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Pure boundaries for the trusted packaged document; no remote page receives a bridge. */
public final class BridgePolicy {
    public static final String ORIGIN="https://appassets.androidplatform.net";
    public static final String INDEX=ORIGIN+"/assets/ui/index.html";
    private static final Set<String> OPERATIONS=new HashSet<>(Arrays.asList("ready","resume","launchApp","details","openDiscovery","search","weather","controls","listings","usage","allApps","imeOpen","imeHide","textFocus","refresh","region","metrics"));
    private BridgePolicy(){}
    public static boolean trustedOrigin(String value){try{URI u=new URI(value);return "https".equals(u.getScheme())&&"appassets.androidplatform.net".equals(u.getHost())&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443)&&u.getQuery()==null&&u.getFragment()==null&&(u.getPath().isEmpty()||u.getPath().equals("/"));}catch(Exception e){return false;}}
    public static boolean operation(String value){return OPERATIONS.contains(value);}
    public static boolean titleKey(String value){return value!=null&&value.matches("(movie|tv):[1-9][0-9]{0,8}");}
    public static String requestId(String value){return value!=null&&value.matches("[a-zA-Z0-9_-]{0,40}")?value:"";}
    public static boolean payloadLength(String value){return value!=null&&value.length()<=8192;}
}
