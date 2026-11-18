package org.agentos.surfaces;

import java.util.List;

/** Presentation boundary. Renderers emit identities, never invoke application work. */
interface SurfaceRenderer {
    interface Listener {
        void onAction(SurfaceStore.Key key,long revision,String action);
        void onDismiss(SurfaceStore.Key key);
    }
    void render(List<SurfaceStore.Entry> entries,SurfaceStore.Key preferred);
    void hide();
}
