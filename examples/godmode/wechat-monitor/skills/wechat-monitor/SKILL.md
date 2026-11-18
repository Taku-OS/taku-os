# WeChat notification monitor

This provider observes **new notifications** posted through `NotificationManager.notify(String,int,Notification)` in the running `com.tencent.mm` main process, not the WeChat message database. Notifications can be suppressed, redacted, posted by another process or use other APIs; an empty event list does not prove no messages arrived. WCDB schema and hooks have not been established.

Install this directory with `taku-plugin godmode install /absolute/path/to/wechat-monitor`, then `taku-plugin godmode enable org.taku.wechat.monitor`. Discover with `taku-plugin godmode tools` and invoke:

```
taku-plugin godmode call-tool org.taku.wechat.monitor start_monitor '{}'
taku-plugin godmode call-tool org.taku.wechat.monitor read_events '{"limit":100}'
taku-plugin godmode call-tool org.taku.wechat.monitor stop_monitor '{}'
```

`limit` is optional and must be an integer from 1 to 1000; events are oldest-first within the selected tail. Start requires the REA runtime pack, Frida server and a running WeChat main process; installation does not start monitoring. `start_monitor` reports an error if attach or hook installation fails or times out. `read_events` reports `lastCaptureError` if writing or extracting a notification fails. Detach or provider shutdown stops capture; restart it after a WeChat or God Mode restart. `stop_monitor` leaves previous events intact.

The latest 1000 events persist in `/data/local/agentos/wechat-monitor/events.jsonl` (directory 0700, file 0600), until removed locally with `rm /data/local/agentos/wechat-monitor/events.jsonl`. The God Mode host also audits `call-tool` results in its local evidence ledger; reading events can therefore create additional local copies there. Protect and remove those records according to local retention needs. Nothing in this provider uploads events; other software or tools consuming its output may do so. Never send captured content externally without authorization.
