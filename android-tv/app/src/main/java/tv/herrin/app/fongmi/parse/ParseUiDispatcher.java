/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.app.fongmi.parse;

import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;

/** Android Handler.post reports queue rejection with false rather than throwing. */
public final class ParseUiDispatcher {
    private ParseUiDispatcher() {}

    public static void post(Predicate<Runnable> enqueue, Runnable task) {
        if (!enqueue.test(task)) {
            throw new RejectedExecutionException("Parser UI dispatcher unavailable");
        }
    }
}
