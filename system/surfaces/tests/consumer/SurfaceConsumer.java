import android.content.Context;
import android.os.RemoteException;
import java.util.List;
import org.agentos.surfaces.SurfaceSpec;
import org.agentos.surfaces.client.SurfaceClient;

/** Compile against the exported SDK JAR only, without host sources or resources. */
public final class SurfaceConsumer implements SurfaceClient.Listener {
    private final SurfaceClient client;
    public SurfaceConsumer(Context context) { client=new SurfaceClient(context,this); client.connect(); }
    @Override public void onConnected() {
        try {
            client.showHint("hint",1,"标题","说明",new SurfaceSpec.Action("accept","确认",true));
            client.showChoice("choice",1,"标题","说明",List.of(new SurfaceSpec.Action("a","选项",true)));
            client.showResult("result",1,"标题","说明",new SurfaceSpec.ArtifactRef("fixture:one","text/plain","结果"));
            client.publish(SurfaceSpec.choice("custom",1,"标题","",List.of(new SurfaceSpec.Action("a","A",true))).withPlacement("top").withTtlMs(60000));
            client.dismiss("custom");
        } catch (RemoteException failure) { throw new IllegalStateException(failure); }
    }
    @Override public void onEvent(String type,String id,long revision,String value) {}
    @Override public void onAction(SurfaceClient.ActionEvent event) {
        SurfaceSpec.ArtifactRef artifact=event.artifact();
        if (artifact!=null) artifact.ref();
    }
    public void close() { client.close(); }
}
