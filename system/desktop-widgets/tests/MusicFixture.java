package org.taku.review.music;
import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.*;
import android.os.*;
public class MusicFixture extends Activity {
 static MediaSession session;
 static void playback(int state) { session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE).setState(state,0,1).build()); }
 @Override public void onCreate(Bundle b){super.onCreate(b);android.widget.TextView v=new android.widget.TextView(this);v.setText("Temporary widget routing fixture");setContentView(v);
  if(getIntent().getBooleanExtra("active",false)) {
   if(session!=null)session.release(); session=new MediaSession(this,"widget-review");
   session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"Widget routing test").build());
   session.setSessionActivity(PendingIntent.getActivity(this,0,new Intent(this,Player.class),PendingIntent.FLAG_IMMUTABLE));
   session.setCallback(new MediaSession.Callback(){public void onPlay(){playback(PlaybackState.STATE_PLAYING);} public void onPause(){playback(PlaybackState.STATE_PAUSED);}});
   playback(PlaybackState.STATE_PAUSED);session.setActive(true);
  }
 }
 public static class Player extends Activity { @Override public void onCreate(Bundle b){super.onCreate(b);android.widget.TextView v=new android.widget.TextView(this);v.setText("Active session player");setContentView(v);} }
}
