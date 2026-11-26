package org.agentos.assistant;

import java.io.IOException;

/** Bounded page-transition reads. No input operation is accepted by this interface. */
final class DiningPollSendWait {
    interface Read<T> {
        T get() throws Exception;
    }

    interface Guard {
        void check() throws Exception;
    }

    interface Now {
        long get();
    }

    interface Pause {
        void sleep(long millis) throws Exception;
    }

    static <T> T read(Read<T> read, Guard guard, Now now, Pause pause, long deadline, String stage)
            throws Exception {
        while (true) {
            guard.check();
            try {
                return read.get();
            } catch (IOException unavailable) {
                if (!"POLL_SOURCE_NOT_VISIBLE".equals(unavailable.getMessage())) throw unavailable;
                if (now.get() >= deadline)
                    throw new IOException("POLL_SOURCE_NOT_VISIBLE__STAGE_" + stage);
                pause.sleep(80);
            }
        }
    }
}
