// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic protocol controls only: no Android, sockets, tokens or model use. */
public final class FalModelUpdateTest {
    private static int assertions;
    private static void check(boolean value) {
        assertions++; if (!value) throw new AssertionError("check " + assertions);
    }
    private static FalModelUpdate ready() {
        FalModelUpdate state = new FalModelUpdate();
        check(state.acknowledge("prompt_ack", true) == FalModelUpdate.Ack.INITIAL_READY);
        check("ready".equals(state.availability()));
        return state;
    }
    public static void main(String[] args) throws Exception {
        Map<String, Object> clear = FalSignalPolicy.update("Turn the room into a beach", null, true);
        check(clear.containsKey("reference_image_url") && clear.get("reference_image_url") == null);
        check(((Map<?, ?>) FalSignalCodec.decode(FalSignalCodec.encode(clear))).containsKey("reference_image_url"));
        Map<String, Object> textOnly = FalSignalPolicy.update("Add a hat", null, false);
        check(textOnly.size() == 1 && !textOnly.containsKey("reference_image_url"));
        FalModelUpdate clearReference = ready();
        clearReference.begin(1, true); // Policy's explicit-null payload still requires an image acknowledgement.
        check(clearReference.acknowledge("prompt_ack", true) == FalModelUpdate.Ack.WAITING);
        check(clearReference.acknowledge("set_image_ack", true) == FalModelUpdate.Ack.APPLIED);

        FalModelUpdate initial = new FalModelUpdate();
        check(initial.begin(0, true) == 0);
        check(initial.acknowledge("set_image_ack", true) == FalModelUpdate.Ack.IGNORED);
        check(initial.begin(1, false) == 0);
        check(initial.acknowledge("prompt_ack", true) == FalModelUpdate.Ack.INITIAL_READY);
        check(initial.acknowledge("set_image_ack", true) == FalModelUpdate.Ack.IGNORED);
        check("ready".equals(initial.availability())); // Optional late initial image ACK does not lock updates.
        long first = initial.begin(5, false);
        check(first != 0 && initial.owns(first));
        check(initial.begin(6, true) == 0); // One in flight across text/image updates.
        check(initial.acknowledge("set_image_ack", true) == FalModelUpdate.Ack.IGNORED);
        check(initial.acknowledge("prompt_ack", true) == FalModelUpdate.Ack.APPLIED);
        check(!initial.expires(first, 100_000)); // Old timeout cannot fail a completed request.
        long second = initial.begin(100, true);
        check(second > first && !initial.expires(first, 200_000));
        check(initial.acknowledge("set_image_ack", true) == FalModelUpdate.Ack.WAITING);
        check(initial.acknowledge("prompt_ack", true) == FalModelUpdate.Ack.APPLIED);

        FalModelUpdate imageLast = ready();
        long imageId = imageLast.begin(1_000, true);
        check(imageLast.acknowledge("prompt_ack", true) == FalModelUpdate.Ack.WAITING);
        check("pending".equals(imageLast.availability()));
        check(!imageLast.expires(imageId, 10_999));
        check(imageLast.expires(imageId, 11_000)); // Combined update missing one ACK stays unconfirmed.
        check("unconfirmed".equals(imageLast.availability()));
        check(imageLast.acknowledge("set_image_ack", true) == FalModelUpdate.Ack.IGNORED);
        check(imageLast.begin(20_000, true) == 0); // Late ACK cannot be reused for a follow-up.

        for (String type : new String[]{"prompt_ack", "set_image_ack"}) {
            FalModelUpdate rejected = ready();
            long id = rejected.begin(1, true);
            check(rejected.acknowledge(type, false) == FalModelUpdate.Ack.REJECTED);
            check(!rejected.owns(id) && rejected.begin(2, false) == 0);
            check(rejected.acknowledge("prompt_ack", true) == FalModelUpdate.Ack.IGNORED);
            check(rejected.acknowledge("set_image_ack", true) == FalModelUpdate.Ack.IGNORED);
        }
        FalModelUpdate malformed = ready();
        malformed.begin(1, false);
        check(malformed.acknowledge("prompt_ack", null) == FalModelUpdate.Ack.REJECTED);
        FalModelUpdate badInitial = new FalModelUpdate();
        check(badInitial.acknowledge("set_image_ack", false) == FalModelUpdate.Ack.INITIAL_REJECTED);
        check(badInitial.begin(1, true) == 0);

        FalModelUpdate duplicate = ready();
        duplicate.begin(1, true);
        check(duplicate.acknowledge("prompt_ack", true) == FalModelUpdate.Ack.WAITING);
        check(duplicate.acknowledge("prompt_ack", true) == FalModelUpdate.Ack.UNCONFIRMED);
        check(duplicate.begin(2, false) == 0);
        FalModelUpdate unsolicited = ready();
        check(unsolicited.acknowledge("set_image_ack", true) == FalModelUpdate.Ack.IGNORED);
        check(unsolicited.begin(1, true) != 0);
        check(unsolicited.acknowledge("prompt_ack", true) == FalModelUpdate.Ack.WAITING);
        check(unsolicited.acknowledge("set_image_ack", true) == FalModelUpdate.Ack.APPLIED);
        FalModelUpdate unsolicitedPrompt = ready();
        check(unsolicitedPrompt.acknowledge("prompt_ack", true) == FalModelUpdate.Ack.IGNORED);
        check(unsolicitedPrompt.begin(1, true) == 0);

        FalModelUpdate stopped = ready();
        long stoppedId = stopped.begin(1, true);
        stopped.close();
        check(!stopped.owns(stoppedId) && !stopped.expires(stoppedId, 99_000));
        check(stopped.acknowledge("prompt_ack", true) == FalModelUpdate.Ack.IGNORED);
        check(stopped.acknowledge("set_image_ack", true) == FalModelUpdate.Ack.IGNORED);
        check("closed".equals(stopped.availability()) && stopped.begin(100_000, false) == 0);
        FalModelUpdate nextSegment = ready();
        check(nextSegment.begin(1, false) != 0); // A retired segment never shares ACK state with its replacement.
        check(stopped.acknowledge("prompt_ack", true) == FalModelUpdate.Ack.IGNORED);
        check("pending".equals(nextSegment.availability()));

        FalModelUpdate sendFailure = ready();
        long failedId = sendFailure.begin(1, false);
        check(sendFailure.fail(failedId) && !sendFailure.fail(failedId));
        check(sendFailure.begin(2, false) == 0);
        check(sendFailure.acknowledge("prompt_ack", true) == FalModelUpdate.Ack.IGNORED);

        FalModelUpdate concurrent = ready();
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(12);
        AtomicInteger accepted = new AtomicInteger();
        for (int n = 0; n < 12; n++) new Thread(() -> {
            try { start.await(); if (concurrent.begin(1, true) != 0) accepted.incrementAndGet(); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
            finally { done.countDown(); }
        }).start();
        start.countDown(); done.await(); check(accepted.get() == 1);
        System.out.println("fal model updates: " + assertions + " assertions passed; no network/device/model use");
    }
}
