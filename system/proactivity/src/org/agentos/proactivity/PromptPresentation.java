package org.agentos.proactivity;

import java.util.List;
import org.agentos.proactivity.api.ObserverSpec;
import org.agentos.surfaces.SurfaceSpec;

/** Maps an approved declaration to the existing renderer without granting execution capability. */
final class PromptPresentation {
    private PromptPresentation() {}
    static String type(ObserverSpec spec){return spec.prompt()==null?"hint":"choice";}
    static boolean accepts(ObserverSpec spec,String actionId) {
        return "accept".equals(actionId) || (spec.prompt()!=null && "cancel".equals(actionId));
    }
    static SurfaceSpec surface(String id,long revision,ObserverSpec spec) {
        if(spec.prompt()==null)return SurfaceSpec.hint(id,revision,spec.title(),spec.summary(),
            new SurfaceSpec.Action("accept","Viewed",true)).withTtlMs(120000);
        return SurfaceSpec.choice(id,revision,spec.title(),spec.summary(),List.of(
            new SurfaceSpec.Action("cancel",spec.prompt().declineLabel(),false),
            new SurfaceSpec.Action("accept",spec.prompt().acceptLabel(),true)))
            .withPlacement("top").withTtlMs(120000);
    }
}
