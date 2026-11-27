package org.agentos.assistant;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Handles the taku-account://auth/callback OAuth PKCE redirect inside org.agentos.assistant. */
public final class TakuAccountCallbackActivity extends Activity {
    private static final ExecutorService IO = Executors.newCachedThreadPool();
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        String callbackUrl = data == null ? null : data.toString();
        TakuAccountTokenServer.ensureStarted(this);
        if (callbackUrl == null || callbackUrl.isEmpty()) {
            finish();
            return;
        }
        IO.execute(() -> {
            String toastMessage;
            try {
                TakuAuthManager.get(getApplicationContext()).completeGoogleOAuthCallback(callbackUrl);
                toastMessage = "Taku 账号已登录";
            } catch (Exception e) {
                toastMessage = describeError(e.getMessage());
            }
            String finalMessage = toastMessage;
            runOnUiThread(() -> {
                Toast.makeText(getApplicationContext(), finalMessage, Toast.LENGTH_LONG).show();
                if (!isFinishing() && !isDestroyed()) {
                    startActivity(
                            new Intent(this, TakuAccountSettingsActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                    finish();
                }
            });
        });
    }

    private static String describeError(String code) {
        if ("ERR_IMPLICIT_FLOW_REJECTED".equals(code)) return "拒绝隐式令牌回调，请使用授权码重试";
        if ("ERR_OAUTH_EXPIRED".equals(code)) return "Google 登录授权已超时，请重试";
        if ("ERR_OAUTH_CANCELLED".equals(code)) return "已取消 Google 登录";
        if ("ERR_OAUTH_STATE_INVALID".equals(code)) return "登录校验态无效，请重新发起登录";
        if ("ERR_TRANSIENT_OFFLINE".equals(code)) return "网络异常，请检查网络后重试";
        return "Taku 登录未完成，请重试";
    }
}
