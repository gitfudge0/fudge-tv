package local.tv.launcher;

import java.util.Collection;

/** Explicit hero identity; a previously visited Continue row must not override a later app selection. */
final class HeroSelection {
    enum Kind { APP, PROGRAM }
    final Kind kind;
    final String id;
    HeroSelection(Kind kind,String id){this.kind=kind;this.id=id==null?"":id;}
    HeroSelection restore(Collection<String> appIds,Collection<String> programIds,String fallbackApp){
        if(kind==Kind.PROGRAM && programIds.contains(id))return this;
        if(kind==Kind.APP && appIds.contains(id))return this;
        return new HeroSelection(Kind.APP,fallbackApp);
    }
}
