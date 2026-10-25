package local.tv.launcher;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;
public class HeroSelectionTest {
    @Test public void laterAppFocusSurvivesEarlierContinueVisitAndResume(){
        HeroSelection selection=new HeroSelection(HeroSelection.Kind.PROGRAM,"netflix:42");
        // Focus then moves to Stremio, then its hero; lifecycle restoration must keep Stremio.
        selection=new HeroSelection(HeroSelection.Kind.APP,"com.stremio.one");
        HeroSelection restored=selection.restore(Arrays.asList("com.stremio.one","com.netflix.ninja"),Collections.singleton("netflix:42"),"com.netflix.ninja");
        assertEquals(HeroSelection.Kind.APP,restored.kind);assertEquals("com.stremio.one",restored.id);
    }
    @Test public void existingProgramRetainsItsStableIdentity(){
        HeroSelection selection=new HeroSelection(HeroSelection.Kind.PROGRAM,"netflix:42");
        HeroSelection restored=selection.restore(Collections.singleton("com.netflix.ninja"),Arrays.asList("netflix:99","netflix:42"),"com.netflix.ninja");
        assertEquals(HeroSelection.Kind.PROGRAM,restored.kind);assertEquals("netflix:42",restored.id);
    }
    @Test public void missingProgramFallsBackToAppWithoutStaleIndex(){
        HeroSelection selection=new HeroSelection(HeroSelection.Kind.PROGRAM,"netflix:42");
        HeroSelection restored=selection.restore(Collections.singleton("com.stremio.one"),Collections.emptyList(),"com.stremio.one");
        assertEquals(HeroSelection.Kind.APP,restored.kind);assertEquals("com.stremio.one",restored.id);
    }
}
