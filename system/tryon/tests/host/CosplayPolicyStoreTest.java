// SPDX-License-Identifier: LicenseRef-Taku-OS-Internal
package org.taku.tryon.realtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.taku.cosplay.api.CosplayContract;

public final class CosplayPolicyStoreTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        commands(); identities(); metadata(); targets(); ownership(); expiryAndReplay(); capacity(); concurrentClaim();
        System.out.println("Cosplay policy/store passed: " + checks + " checks");
    }
    private static Map<String, Object> fields(String id) {
        Map<String, Object> value = new HashMap<>();
        value.put("schema", CosplayContract.SCHEMA); value.put("jpeg", new byte[]{-1, -40, -1, 7, -1, -39});
        value.put("capturedAtElapsedMs", 99_000L); value.put("invokedAtElapsedMs", 98_000L);
        value.put("command", CosplayContract.COMMAND); value.put("sourcePackage", "com.zhiliaoapp.musically");
        value.put("requestId", id); return value;
    }
    private static CosplayPolicy.Request request(int suffix) {
        return CosplayPolicy.validate(fields(String.format("%032x", suffix)), 100_000L);
    }
    private static void commands() {
        check(CosplayPolicy.validate(fields("f".repeat(32)), 100_000L).command().equals(CosplayContract.COMMAND), "canonical action only");
        for (String value : new String[]{"不要帮我穿上他这一套", "先别帮我穿上她这一身", "帮我穿上他这一套吗我只是举例",
                "穿上红色衣服", "我不知道要不要换装", "他说帮我穿上他这一套", "‘帮我穿上他这一套’",
                "don't help me try this look", "do not help me try this look", "never help me try this look",
                "help me try this look but don't start", "help me try this look and send a message",
                "he said help me try this look", "help me try this look\u202e", "help me\ntry this look", "", " ", "x".repeat(161),
                "帮我穿上他这一套", "Help me try this look.", CosplayContract.COMMAND + " ", " " + CosplayContract.COMMAND})
            badField("command", value);
    }
    private static void identities() {
        String pkg = CosplayContract.ASSISTANT_PACKAGE, cert = CosplayContract.ASSISTANT_CERT_SHA256;
        check(CosplayPolicy.trustedAssistant(10123,pkg,10123,cert), "approved development Home signer is allowed with ordinary app UID");
        check(CosplayPolicy.trustedAssistant(10123,pkg,10123,"other-platform-signer",true), "installed Android platform signer is accepted independently of a development certificate");
        check(!CosplayPolicy.trustedAssistant(1000,pkg,1000,cert), "system UID is not substituted for ordinary Home identity");
        check(!CosplayPolicy.trustedAssistant(10123,"org.agentos.assistant",10123,cert), "old system assistant package cannot impersonate Home");
        check(!CosplayPolicy.trustedAssistant(10123,"android",10123,cert), "platform identity alone is insufficient");
        check(!CosplayPolicy.trustedAssistant(2000,pkg,2000,cert), "shell does not mint real invocation tickets");
        check(!CosplayPolicy.trustedAssistant(0,pkg,0,cert), "root uses the standard API rather than fabricated voice invocation");
        check(!CosplayPolicy.trustedAssistant(10123,pkg,10124,cert), "package must actually belong to Binder caller UID");
        check(!CosplayPolicy.trustedAssistant(10123,pkg,10123,"0".repeat(64)), "unknown development signer is denied");
        check(!CosplayPolicy.trustedAssistant(10123,null,10123,cert), "missing calling package is denied");
    }
    private static void metadata() {
        check(CosplayPolicy.validate(fields("a".repeat(32)), 100_000L).capturedAtElapsedMs() == 99_000L, "valid fresh metadata");
        badField("schema", "wrong"); badField("requestId", "A".repeat(32)); badField("requestId", "a".repeat(31));
        badField("sourcePackage", "org.agentos.shopping"); badField("command", "不要帮我穿上他这一套");
        badField("capturedAtElapsedMs", 99_000); badField("invokedAtElapsedMs", "98000");
        badField("capturedAtElapsedMs", 100_001L); badField("invokedAtElapsedMs", 99_001L);
        badField("capturedAtElapsedMs", -1L); badField("invokedAtElapsedMs", -1L);
        badField("jpeg", new byte[CosplayContract.MAX_JPEG_BYTES + 1]); badField("jpeg", "not bytes");
        badField("jpeg", new byte[]{-1, -40, -1, 0});
        long deadlineNow = 1_000_000L;
        Map<String, Object> staleCapture = fields("a".repeat(32));
        staleCapture.put("capturedAtElapsedMs", deadlineNow-CosplayContract.MAX_CAPTURE_AGE_MS);
        staleCapture.put("invokedAtElapsedMs", deadlineNow-CosplayContract.MAX_CAPTURE_AGE_MS);
        reject(() -> CosplayPolicy.validate(staleCapture, deadlineNow), "original capture expires exactly at bounded reference deadline");
        Map<String, Object> staleInvocation = fields("a".repeat(32));
        staleInvocation.put("invokedAtElapsedMs", deadlineNow-CosplayContract.MAX_INVOCATION_AGE_MS);
        staleInvocation.put("capturedAtElapsedMs", deadlineNow-1_000L);
        reject(() -> CosplayPolicy.validate(staleInvocation, deadlineNow), "original invocation expires exactly at deadline");
        Map<String, Object> retained = fields("a".repeat(32));
        retained.put("invokedAtElapsedMs", deadlineNow-CosplayContract.MAX_INVOCATION_AGE_MS+1);
        retained.put("capturedAtElapsedMs", deadlineNow-CosplayContract.MAX_CAPTURE_AGE_MS+1);
        CosplayPolicy.Request reference=CosplayPolicy.validate(retained, deadlineNow);
        check(reference.capturedAtElapsedMs()==(Long)retained.get("capturedAtElapsedMs")
            &&reference.invokedAtElapsedMs()==(Long)retained.get("invokedAtElapsedMs"), "clarification keeps original timestamps just before expiry");
        Map<String, Object> extra = fields("a".repeat(32)); extra.put("uid", 1000);
        reject(() -> CosplayPolicy.validate(extra, 100_000L), "self-reported authority rejected");
        Map<String, Object> missing = fields("a".repeat(32)); missing.remove("command");
        reject(() -> CosplayPolicy.validate(missing, 100_000L), "missing command rejected");
        check(CosplayPolicy.imageDimensionsAllowed(1920, 1080), "bounded landscape JPEG");
        check(CosplayPolicy.imageDimensionsAllowed(1080, 1920), "bounded portrait JPEG");
        check(!CosplayPolicy.imageDimensionsAllowed(1921, 1080), "oversized JPEG denied");
        check(!CosplayPolicy.imageDimensionsAllowed(1920, 0), "undecodable image denied");
    }
    private static void targets() {
        Map<String,Object> input=fields("b".repeat(32));
        CosplayPolicy.Request old=CosplayPolicy.validate(input,100_000L);
        check(CosplayPolicy.TARGET_CHARACTER.equals(old.target()), "legacy validated request remains character");
        CosplayPolicy.Request selected=CosplayPolicy.validateForTarget(input,100_000L,CosplayPolicy.TARGET_OUTFIT_SELECTION);
        CosplayRequestStore.Slots slots=new CosplayRequestStore.Slots();
        CosplayRequestStore.Request claimed=slots.take(slots.put(selected,100_000L).ticket(),100_001L);
        check(CosplayPolicy.TARGET_OUTFIT_SELECTION.equals(claimed.target()), "outfit selection stays bound to one-shot ticket");
        check(claimed.invokedAtElapsedMs()==98_000L&&claimed.capturedAtElapsedMs()==99_000L,
            "ticket never refreshes selected reference timestamps");
        check(CosplayPolicy.TARGET_CHARACTER.equals(new CosplayRequestStore.Request(new byte[0],"id","source",1,2).target()),
            "old five-argument store constructor defaults to character");
        reject(()->CosplayPolicy.validateForTarget(input,100_000L,"prompt:anything"), "unsupported internal target rejected");
        reject(()->new CosplayRequestStore.Request(new byte[0],"id","source",1,2,"other"), "malformed store target rejected");
        input.put("target",CosplayPolicy.TARGET_OUTFIT_SELECTION);
        reject(()->CosplayPolicy.validate(input,100_000L), "caller cannot inject target into legacy payload");
        reject(()->CosplayPolicy.validateForTarget(input,100_000L,CosplayPolicy.TARGET_OUTFIT_SELECTION),
            "outfit method cannot accept client-supplied target or prompt");
    }
    private static void badField(String key, Object value) {
        Map<String, Object> modified = fields("a".repeat(32)); modified.put(key, value);
        reject(() -> CosplayPolicy.validate(modified, 100_000L), "bad metadata: " + key);
    }
    private static void ownership() throws Exception {
        CosplayRequestStore.Slots slots = new CosplayRequestStore.Slots(); CosplayPolicy.Request source = request(1);
        byte[] expected = source.jpeg().clone();
        CosplayRequestStore.Prepared prepared = slots.put(source, 100_000L);
        check(prepared.ticket().matches("[a-f0-9]{32}"), "128-bit opaque ticket");
        check(!prepared.toString().contains(prepared.ticket()) && !source.toString().contains(source.requestId()), "incidental strings redact ticket and metadata");
        check(prepared.expiresAtElapsedMs() == 115_000L, "15-second lease");
        Arrays.fill(source.jpeg(), (byte) 0);
        check(slots.take("invalid", 100_001L) == null, "invalid ticket cannot claim");
        CosplayRequestStore.Request claimed = slots.take(prepared.ticket(), 100_002L);
        check(claimed != null && Arrays.equals(claimed.jpeg(), expected), "prepare copies independent bytes");
        check(claimed.requestId().equals(source.requestId()) && claimed.sourcePackage().equals(source.sourcePackage()), "claim retains bound metadata");
        check(slots.take(prepared.ticket(), 100_003L) == null, "claim is one shot");
        slots.discard(prepared.ticket());
        check(Arrays.equals(claimed.jpeg(), expected), "cancel after claim cannot wipe active owner's pixels");
        reject(() -> slots.put(request(1), 100_004L), "same request cannot prepare after claim");
        Arrays.fill(claimed.jpeg(), (byte) 0);
        CosplayRequestStore.Prepared cancelled = slots.put(request(2), 100_005L);
        byte[] pending = pendingBytes(slots, cancelled.ticket()); slots.discard(cancelled.ticket()); slots.discard(cancelled.ticket());
        check(allZero(pending), "cancel erases pending bytes and is idempotent");
        check(slots.take(cancelled.ticket(), 100_006L) == null, "cancelled ticket cannot claim");
        reject(() -> slots.put(request(2), 100_007L), "cancellation does not permit replay");
    }
    private static void expiryAndReplay() throws Exception {
        CosplayRequestStore.Slots slots = new CosplayRequestStore.Slots();
        CosplayRequestStore.Prepared first = slots.put(request(3), 100_000L); byte[] expired = pendingBytes(slots, first.ticket());
        check(slots.take(first.ticket(), 115_000L) == null, "lease expires at deadline");
        check(allZero(expired), "expiry erases pending bytes");
        reject(() -> slots.put(request(3), 115_001L), "expired ticket does not clear request replay guard");
        CosplayRequestStore.Prepared second = slots.put(request(4), 116_000L); byte[] rolledBack = pendingBytes(slots, second.ticket());
        check(slots.take(second.ticket(), 115_999L) == null, "clock rollback fails closed");
        check(allZero(rolledBack), "rollback erases pending bytes");
        reject(() -> slots.put(request(4), 116_001L), "rollback retains replay guard");
        reject(() -> slots.put(request(5), Long.MAX_VALUE), "deadline overflow rejected");
    }
    private static void capacity() {
        CosplayRequestStore.Slots slots = new CosplayRequestStore.Slots();
        CosplayRequestStore.Prepared one = slots.put(request(5), 100_000L), two = slots.put(request(6), 100_001L);
        reject(() -> slots.put(request(7), 100_002L), "bounded to two pending without evicting live request");
        check(slots.take(one.ticket(), 100_003L) != null, "capacity rejection preserves first request");
        check(slots.take(two.ticket(), 100_004L) != null, "capacity rejection preserves second request");
    }
    private static void concurrentClaim() throws Exception {
        CosplayRequestStore.Slots slots = new CosplayRequestStore.Slots();
        String ticket = slots.put(request(8), 100_000L).ticket(); CountDownLatch start = new CountDownLatch(1);
        AtomicInteger claims = new AtomicInteger(); Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> { try { start.await(); if (slots.take(ticket, 100_001L) != null) claims.incrementAndGet(); }
                catch (InterruptedException e) { throw new AssertionError(e); } }); threads[i].start();
        }
        start.countDown(); for (Thread thread : threads) thread.join();
        check(claims.get() == 1, "racing claimants receive exactly one owner");
    }
    private static byte[] pendingBytes(CosplayRequestStore.Slots slots, String ticket) throws Exception {
        Field entries = slots.getClass().getDeclaredField("entries"); entries.setAccessible(true);
        Object entry = ((Map<?, ?>) entries.get(slots)).get(ticket);
        Method accessor = entry.getClass().getDeclaredMethod("request"); accessor.setAccessible(true);
        return ((CosplayRequestStore.Request) accessor.invoke(entry)).jpeg();
    }
    private static boolean allZero(byte[] bytes) { for (byte value : bytes) if (value != 0) return false; return true; }
    private static void reject(Runnable action, String message) {
        boolean rejected = false; try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        check(rejected, message);
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
