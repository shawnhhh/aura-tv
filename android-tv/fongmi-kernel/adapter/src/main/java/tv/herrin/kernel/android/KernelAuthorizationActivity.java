/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.kernel.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import androidx.fragment.app.FragmentActivity;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.ui.dialog.SpiderDialog;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Hosts the original dialog without changing HERRIN's ComponentActivity UI.
 * Only a random, non-secret ticket crosses the Intent. Source options and
 * callbacks remain in memory, are never persisted and expire on destruction. */
public final class KernelAuthorizationActivity extends FragmentActivity {
    private static final String TICKET = "kernel.authorization.ticket";
    private static final ConcurrentHashMap<String, Request> REQUESTS = new ConcurrentHashMap<>();
    private static Object sessionOwner;
    private static java.util.function.Consumer<Boolean> pendingObserver = value -> {};
    private record Request(String options, Function<String, CompletableFuture<String>> callback,
                           CompletableFuture<String> result) {}
    private Request request;
    private CompletableFuture<String> dialog;

    static void bindSession(Object owner, java.util.function.Consumer<Boolean> observer) {
        sessionOwner = owner;
        pendingObserver = observer;
    }
    static void cancelRequests() {
        for (Request request : REQUESTS.values()) request.result().cancel(false);
    }
    static void endSession(Object owner) {
        if (sessionOwner != owner) return;
        sessionOwner = null;
        cancelRequests();
        pendingObserver = value -> {};
    }
    private static void publishPending() {
        App.post(() -> pendingObserver.accept(!REQUESTS.isEmpty()));
    }

    static CompletableFuture<String> show(String options, Function<String, CompletableFuture<String>> callback) {
        var result = new CompletableFuture<String>();
        String ticket = UUID.randomUUID().toString();
        var request = new Request(options, callback, result);
        REQUESTS.put(ticket, request);
        result.whenComplete((value, failure) -> { REQUESTS.remove(ticket, request); publishPending(); });
        publishPending();
        App.post(() -> {
            if (result.isDone()) return;
            if (sessionOwner == null) { result.cancel(false); return; }
            Activity owner = App.activity();
            if (owner == null || owner.isFinishing() || owner.isDestroyed()) {
                result.completeExceptionally(new IllegalStateException("Authorization requires a foreground host"));
                return;
            }
            try {
                owner.startActivity(new Intent(owner, KernelAuthorizationActivity.class).putExtra(TICKET, ticket));
            } catch (RuntimeException failure) {
                result.completeExceptionally(new IllegalStateException("Authorization host could not start"));
            }
        });
        return result;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String ticket = getIntent().getStringExtra(TICKET);
        request = ticket == null ? null : REQUESTS.get(ticket);
        if (request == null || request.result().isDone()) finish();
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        if (request == null || dialog != null || isFinishing()) return;
        if (request.result().isDone()) { finish(); return; }
        // App's ActivityLifecycleCallbacks have now installed this real
        // FragmentActivity as Runtime.activity(). No QR or credentials are
        // synthesized by HERRIN: the original SpiderDialog renders everything.
        dialog = SpiderDialog.show(request.options(), request.callback());
        dialog.whenComplete((value, failure) -> App.post(() -> {
            if (failure == null) request.result().complete(value);
            else request.result().completeExceptionally(failure);
            finish();
        }));
        request.result().whenComplete((value, failure) -> {
            if (request.result().isCancelled()) App.post(this::finish);
        });
    }

    @Override protected void onDestroy() {
        if (dialog != null && !dialog.isDone()) dialog.cancel(false);
        if (request != null && !request.result().isDone()) request.result().cancel(false);
        super.onDestroy();
    }
}
