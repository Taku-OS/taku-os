package org.agentos.agent.bridge.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.json.JSONObject;

/** Same-process native bridge regression; run only on an isolated, signed-out test AVD. */
public final class ConversationHostProbe extends Instrumentation {
  private static final String CHAT = "org.agentos.agent.MainActivity";
  private static final String HOME = "org.agentos.agent.home.HomeActivity";
  private Object module, appContext;

  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

  private static Object call(Object obj, String name, Object... args) throws Exception {
    for (Method method : obj.getClass().getMethods()) {
      if (method.getName().equals(name) && method.getParameterCount() == args.length) {
        method.setAccessible(true);
        return method.invoke(obj, args);
      }
    }
    throw new NoSuchMethodException(name);
  }

  private static Object field(Object obj, String name) throws Exception {
    Field field = obj.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(obj);
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  private Activity launch(String name) {
    return startActivitySync(new Intent().setClassName("org.agentos.agent", name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }

  private void onUi(Checked action) throws Exception {
    Throwable[] error = {null};
    runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error[0] = e; } });
    if (error[0] != null) throw new Exception("UI regression failed", error[0]);
  }
  interface Checked { void run() throws Exception; }

  private void findModule() throws Exception {
    Class<?> proxyType = Class.forName("expo.modules.adapters.react.NativeModulesProxy");
    String state = "no React context";
    long deadline = SystemClock.uptimeMillis() + 20000;
    do {
      Object host = call(getTargetContext().getApplicationContext(), "getReactHost");
      Object context = call(host, "getCurrentReactContext");
      if (context != null) {
        Object proxy = context.getClass().getMethod("getNativeModule", Class.class).invoke(context, proxyType);
        if (proxy == null) {
          Object bridge = context.getClass().getMethod("getNativeModule", Class.class).invoke(context,
              Class.forName("expo.modules.kotlin.ExpoBridgeModule"));
          if (bridge != null) proxy = ((java.lang.ref.WeakReference<?>) field(bridge, "nativeModulesProxy")).get();
        }
        if (proxy == null) {
          // Bridgeless React may omit legacy modules from getNativeModule(). Read
          // the existing package adapter, never instantiate another module registry.
          Object delegate = field(field(host, "host"), "reactHostDelegate");
          for (Object pkg : (Iterable<?>) call(delegate, "getReactPackages")) {
            if (pkg.getClass().getName().equals("expo.modules.ExpoModulesPackage")) {
              proxy = field(call(pkg, "getModuleRegistryAdapter"), "mModulesProxy");
            }
          }
        }
        state = context.getClass().getName() + " proxy=" + (proxy != null);
        if (proxy != null) {
          appContext = call(call(proxy, "getKotlinInteropModuleRegistry"), "getAppContext");
          module = call(call(appContext, "getRegistry"), "getModule", "TakuAgent");
          if (module != null) return;
        }
      }
      SystemClock.sleep(100);
    } while (SystemClock.uptimeMillis() < deadline);
    throw new AssertionError("Actual TakuAgent module was not initialized: " + state);
  }

  private Object sync(String name, Object... args) throws Exception {
    Map<?, ?> functions = (Map<?, ?>) call(call(module, "definition"), "getSyncFunctions");
    return call(functions.get(name), "callUserImplementation", args, appContext);
  }

  @Override public void onStart() {
    JSONObject report = new JSONObject();
    int result = Activity.RESULT_CANCELED;
    try {
      Activity chat = launch(CHAT);
      findModule();
      SystemClock.sleep(1500);
      require(field(module, "chatActivity") == chat, "late-created module must retain its already-resumed chat");
      Activity home = launch(HOME);
      SystemClock.sleep(1500);
      require(call(appContext, "getCurrentActivity") == home, "ReactHost must actually point at Home");
      String homeIntent = home.getIntent().toUri(0);
      onUi(() -> {
        chat.setIntent(new Intent(chat.getIntent()).putExtra("threadId", "cr-probe-before")
            .putExtra("panelId", "cr-probe-panel").putExtra("originThreadId", "cr-probe-before")
            .putExtra("panelLaunchId", "cr-probe-panel-launch"));
        Method open = module.getClass().getDeclaredMethod("openRoute");
        open.setAccessible(true);
        require("cr-probe-before".equals(call(open.invoke(module), "getThreadId")), "read must use chat route");
        sync("selectConversation", "cr-probe-after", null);
        require("cr-probe-after".equals(chat.getIntent().getStringExtra("threadId")), "chat route not updated");
        require(!chat.getIntent().hasExtra("panelId") && !chat.getIntent().hasExtra("originThreadId")
            && !chat.getIntent().hasExtra("panelLaunchId"), "stale chat Panel scope not cleared");
        require(homeIntent.equals(home.getIntent().toUri(0)), "Home intent was polluted");

        Object creating = sync("selectConversation", null, null);
        require(creating instanceof String, "clearing the route must return its generation");
        chat.setIntent(new Intent(chat.getIntent()).putExtra("threadId", "cr-probe-native"));
        require(sync("selectConversation", "cr-probe-created", creating) == null,
            "stale creation must not commit over a newer intent");
        require("cr-probe-native".equals(chat.getIntent().getStringExtra("threadId")),
            "stale creation overwrote the live chat intent");
        sync("selectConversation", "cr-probe-after", null);
        require(sync("selectConversation", "cr-probe-created", creating) == null,
            "stale creation must not commit over a newer route generation");
      });
      report.put("backgroundSelectAndRouteRead", true);
      onUi(() -> {
        Field observing = module.getClass().getDeclaredField("observingIme");
        observing.setAccessible(true); observing.setBoolean(module, true);
        Method attach = module.getClass().getDeclaredMethod("attachImeObserver");
        attach.setAccessible(true); attach.invoke(module);
        View observer = (View) field(module, "imeObserver");
        require(observer != null && observer.getContext() == chat, "IME observer attached to Home");
        observing.setBoolean(module, false);
        Method detach = module.getClass().getDeclaredMethod("detachImeObserver");
        detach.setAccessible(true); detach.invoke(module);
        sync("selectConversation", null, null);
      });
      report.put("imeBelongsToChat", true).put("passed", true);
      result = Activity.RESULT_OK;
    } catch (Throwable error) {
      try { report.put("passed", false).put("error", android.util.Log.getStackTraceString(error)); }
      catch (Exception ignored) { }
    } finally {
      Bundle data = new Bundle(); data.putString("report", report.toString()); finish(result, data);
    }
  }
}
