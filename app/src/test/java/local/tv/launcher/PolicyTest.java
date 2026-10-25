package local.tv.launcher;
import org.junit.Test;
import static org.junit.Assert.*;
public class PolicyTest {
    @Test public void rejectsForeignDeepLinks(){
        String owner="com.netflix.ninja";
        assertTrue(Policy.ownerAllowed(owner,null,null));
        assertTrue(Policy.ownerAllowed(owner,owner,owner));
        assertFalse(Policy.ownerAllowed(owner,"com.other",null));
        assertFalse(Policy.ownerAllowed(owner,null,"com.other"));
        assertFalse(Policy.ownerAllowed("com.unretained",null,null));
        assertFalse(Policy.ownerAllowed(null,null,null));
    }
    @Test public void rejectsLocalAndExecutableSchemes(){
        for(String blocked:new String[]{null,"","file","content","javascript","data","android.resource","intent","JAVASCRIPT"})assertFalse(Policy.linkSchemeAllowed(blocked));
        assertTrue(Policy.linkSchemeAllowed("https"));assertTrue(Policy.linkSchemeAllowed("netflix"));
    }
    @Test public void remoteClampsAtRowBoundaries(){
        assertEquals(0,Policy.move(0,-1,7));assertEquals(6,Policy.move(6,1,7));assertEquals(4,Policy.move(3,1,7));assertEquals(-1,Policy.move(0,1,0));assertEquals(0,Policy.move(-3,0,7));
    }
    @Test public void newestOpenedComesFirstAndTiesAreDeterministic(){
        assertTrue(Policy.compareRecent(200,100,"Z","A")<0);assertTrue(Policy.compareRecent(0,0,"Apple TV","Netflix")<0);assertEquals(0,Policy.compareRecent(1,1,"Netflix","Netflix"));
    }
    @Test public void resolvedResumeMustStayWithExportedOwner(){
        assertTrue(Policy.resolvedOwnerAllowed("com.netflix.ninja","com.netflix.ninja",true));
        assertFalse(Policy.resolvedOwnerAllowed("com.netflix.ninja","com.other",true));
        assertFalse(Policy.resolvedOwnerAllowed("com.netflix.ninja","com.netflix.ninja",false));
    }
    @Test public void hiddenFactoryAppsAreNeverListed(){
        assertFalse(Policy.safeUserApp("com.sony.dtv.fac.menu","Main","Menu"));
        assertFalse(Policy.safeUserApp("com.vendor.productiontest","Main","Tools"));
        assertFalse(Policy.safeUserApp("com.sony.tools","FactoryActivity","Tools"));
        assertTrue(Policy.safeUserApp("com.netflix.ninja","Main","Netflix"));
    }
    @Test public void gridDoesNotWrapOrJumpToPartialLastRow(){
        assertEquals(0,Policy.gridMove(0,-1,0,12,5));assertEquals(4,Policy.gridMove(4,1,0,12,5));
        assertEquals(9,Policy.gridMove(9,0,1,12,5));assertEquals(11,Policy.gridMove(6,0,1,12,5));
        assertEquals(1,Policy.gridMove(6,0,-1,12,5));assertEquals(10,Policy.gridMove(10,-1,0,12,5));
    }
    @Test public void startupSelectsAppUnlessUserHasAlreadyChosenFocus(){
        assertTrue(Policy.shouldDefaultFocus(true,false));
        assertFalse(Policy.shouldDefaultFocus(true,true));
        assertFalse(Policy.shouldDefaultFocus(false,false));
        assertFalse(Policy.shouldDefaultFocus(false,true));
    }
    @Test public void portraitAndSquareArtCannotBecomeCinematicBackdrop(){
        assertTrue(Policy.cinematicArtwork(1920,1080));assertTrue(Policy.cinematicArtwork(1300,1000));
        assertFalse(Policy.cinematicArtwork(1000,1000));assertFalse(Policy.cinematicArtwork(600,900));assertFalse(Policy.cinematicArtwork(0,900));
    }
    @Test public void focusIdentitySurvivesPositionChanges(){
        assertEquals(Policy.stableId("com.netflix.ninja"),Policy.stableId("com.netflix.ninja"));assertNotEquals(Policy.stableId("com.netflix.ninja"),Policy.stableId("com.stremio.one"));
    }
}
