Java.perform(function () {
  try {
    const manager = Java.use('android.app.NotificationManager');
    const notify = manager.notify.overload('java.lang.String', 'int', 'android.app.Notification');
    notify.implementation = function (tag, id, notification) {
      try {
        const extras = notification ? notification.extras.value : null;
        const title = extras ? extras.getCharSequence('android.title') : null;
        const text = extras ? extras.getCharSequence('android.text') : null;
        send({kind: 'event', title: title === null ? '' : String(title), text: text === null ? '' : String(text), time: new Date().toISOString()});
      } catch (error) {
        send({kind: 'capture_error', error: String(error)});
      }
      return notify.call(this, tag, id, notification);
    };
    send({kind: 'ready', hook: 'NotificationManager.notify(String,int,Notification)'});
  } catch (error) {
    send({kind: 'hook_error', error: String(error)});
  }
});
