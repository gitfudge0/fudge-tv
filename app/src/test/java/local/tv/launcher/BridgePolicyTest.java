package local.tv.launcher;
import org.junit.Test;
import static org.junit.Assert.*;
public class BridgePolicyTest {
 @Test public void onlyExactPackagedOriginIsTrusted(){assertTrue(BridgePolicy.trustedOrigin("https://appassets.androidplatform.net"));assertTrue(BridgePolicy.trustedOrigin("https://appassets.androidplatform.net/"));assertFalse(BridgePolicy.trustedOrigin("https://appassets.androidplatform.net.evil.example"));assertFalse(BridgePolicy.trustedOrigin("http://appassets.androidplatform.net"));assertFalse(BridgePolicy.trustedOrigin("https://user@appassets.androidplatform.net"));assertFalse(BridgePolicy.trustedOrigin("https://appassets.androidplatform.net:444"));}
 @Test public void bridgeHasNoSecretImportOrArbitraryIntentOperation(){assertFalse(BridgePolicy.operation("setCredential"));assertFalse(BridgePolicy.operation("intent"));assertFalse(BridgePolicy.operation("exec"));assertFalse(BridgePolicy.operation("credential"));assertTrue(BridgePolicy.operation("resume"));}
 @Test public void externalInputsAreBounded(){assertFalse(BridgePolicy.payloadLength(new String(new char[8193])));assertTrue(BridgePolicy.titleKey("movie:tt1160419"));assertFalse(BridgePolicy.titleKey("https://evil/"));assertFalse(BridgePolicy.titleKey("movie:-1"));assertEquals("",BridgePolicy.requestId("x');alert(1)//"));}
}
