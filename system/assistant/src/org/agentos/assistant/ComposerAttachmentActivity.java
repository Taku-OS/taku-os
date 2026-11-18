package org.agentos.assistant;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import org.json.*;
import java.io.*;
import java.util.UUID;

/** Android document picker: copies only user-selected content into private task attachments. */
public final class ComposerAttachmentActivity extends Activity {
    String thread;
    Uri cameraOutput;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        thread = getIntent().getStringExtra("conversationId");
        if (!AgentRepository.validId(thread)) { finish(); return; }
        if (state != null) {String value=state.getString("cameraOutput");if(value!=null)cameraOutput=Uri.parse(value);}
        if (state == null && "camera".equals(getIntent().getStringExtra("mime"))) {
            try {
                ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"Taku-"+System.currentTimeMillis()+".jpg");values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Taku");
                cameraOutput=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
                Intent camera=new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,cameraOutput).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
                camera.setClipData(ClipData.newRawUri("New photo",cameraOutput));startActivityForResult(camera,2);
            } catch(Exception error){if(cameraOutput!=null)try{getContentResolver().delete(cameraOutput,null,null);}catch(Exception ignored){}android.widget.Toast.makeText(this,"Camera temporarily unavailable",android.widget.Toast.LENGTH_SHORT).show();returnToChat();}
        } else if (state == null) {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(getIntent().getStringExtra("mime"));
            pick.addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(pick, 1);
        }
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(cameraOutput!=null)state.putString("cameraOutput",cameraOutput.toString());}
    void returnToChat(){startActivity(new Intent(this,PanelActivity.class).putExtra("conversationId",thread)
            .putExtra(AgentFrontendRoute.NATIVE_CONTROLS_ONLY,getIntent().getBooleanExtra(AgentFrontendRoute.NATIVE_CONTROLS_ONLY,false)));finish();}
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if(request==2 && result==RESULT_OK && cameraOutput!=null)data=new Intent().setData(cameraOutput);
        if(request==2 && result!=RESULT_OK && cameraOutput!=null)getContentResolver().delete(cameraOutput,null,null);
        try {
            if (result == RESULT_OK && data != null) {
                JSONArray items = ComposerPreferences.attachments(getSharedPreferences("agent-settings", 0), thread);
                int count = data.getClipData() == null ? 1 : data.getClipData().getItemCount();
                for (int i = 0; i < count && items.length() < 8; i++) {
                    Uri uri = data.getClipData() == null ? data.getData() : data.getClipData().getItemAt(i).getUri();
                    if (uri == null) continue;
                    String mime = getContentResolver().getType(uri), name = "Attachment";
                    try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                    }
                    File directory = new File(getFilesDir(), "composer-attachments"); directory.mkdirs();
                    File file = new File(directory, UUID.randomUUID().toString());
                    try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
                        byte[] buffer = new byte[8192]; int n, total = 0;
                        while ((n = in.read(buffer)) != -1) { total += n; if (total > 20_000_000) throw new IOException("Attachments must be at most 20 MB"); out.write(buffer, 0, n); }
                    } catch (Exception e) { file.delete(); throw e; }
                    items.put(new JSONObject().put("path", file.getAbsolutePath()).put("name", name).put("mime", mime == null ? "application/octet-stream" : mime));
                }
                getSharedPreferences("agent-settings", 0).edit().putString(ComposerPreferences.key(thread, "attachments"), items.toString()).apply();
            }
        } catch (Exception e) { android.widget.Toast.makeText(this, "Attachment not added: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show(); }
        returnToChat();
    }
}
