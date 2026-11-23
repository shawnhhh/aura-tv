/* SPDX-License-Identifier: GPL-3.0-only */
package tv.herrin.kernel.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import androidx.fragment.app.FragmentActivity;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.ui.dialog.SpiderDialog;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Consumer;

/** Original dialog host. Only an opaque ticket crosses the Intent.
 * 未测试，待后续统一验证。 Credentials and provider callbacks stay in memory. */
public final class KernelAuthorizationActivity extends FragmentActivity {
    private static final String TICKET = "kernel.authorization.ticket";
    private static final ConcurrentHashMap<String, Request> REQUESTS = new ConcurrentHashMap<>();
    private static volatile Session session;
    private record Session(Object owner, Consumer<Boolean> pending, Runnable cancelled) {}
    private record Request(Session owner, String options,
                           Function<String, CompletableFuture<String>> callback,
                           CompletableFuture<String> result) {}
    private Request request;
    private CompletableFuture<String> dialog;

    static void bindSession(Object owner, Consumer<Boolean> observer, Runnable cancelled) {
        cancelRequests();
        session = new Session(owner, observer, cancelled);
    }
    static void cancelRequests() {
        // Rotate the capability before completing old futures. Queued dismiss
        // callbacks from a previous episode cannot cancel the new selection.
        Session previous = session;
        if (previous != null) session = new Session(previous.owner(), previous.pending(), previous.cancelled());
        for (Request request : REQUESTS.values()) request.result().cancel(false);
        if (session != null) publishPending(session);
    }
    static void endSession(Object owner) {
        Session current = session;
        if (current == null || current.owner() != owner) return;
        session = null;
        cancelRequests();
    }
    private static void publishPending(Session owner) {
        App.post(() -> {
            if (session == owner) owner.pending().accept(REQUESTS.values().stream()
                .anyMatch(request -> request.owner() == owner));
        });
    }

    static CompletableFuture<String> show(String options, Function<String, CompletableFuture<String>> callback) {
        var result = new CompletableFuture<String>();
        Session current = session;
        if (current == null) { result.cancel(false); return result; }
        String ticket = UUID.randomUUID().toString();
        var request = new Request(current, options, callback, result);
        REQUESTS.put(ticket, request);
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable timeout = () -> result.complete(null);
        main.postDelayed(timeout, 120_000L);
        result.whenComplete((value, failure) -> {
            main.removeCallbacks(timeout);
            REQUESTS.remove(ticket, request);
            // Null means dismissed, never authorized. Notify the player before
            // the upstream worker can publish a protected playback result.
            if (failure == null && value == null) App.post(() -> {
                if (session == current) current.cancelled().run();
            });
            publishPending(current);
        });
        publishPending(current);
        App.post(() -> {
            if (result.isDone()) return;
            if (session != current) { result.cancel(false); return; }
            Activity owner = App.activity();
            if (owner == null || owner.isFinishing() || owner.isDestroyed()) {
                result.completeExceptionally(new IllegalStateException("Authorization requires a foreground host"));
                current.cancelled().run();
                return;
            }
            try {
                owner.startActivity(new Intent(owner, KernelAuthorizationActivity.class).putExtra(TICKET, ticket));
            } catch (RuntimeException failure) {
                result.completeExceptionally(new IllegalStateException("Authorization host could not start"));
                current.cancelled().run();
            }
        });
        return result;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        String ticket = getIntent().getStringExtra(TICKET);
        request = ticket == null ? null : REQUESTS.get(ticket);
        if (request == null || request.result().isDone() || request.owner() != session) finish();
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        if (request == null || dialog != null || isFinishing()) return;
        if (request.result().isDone() || request.owner() != session) { finish(); return; }
        dialog = SpiderDialog.show(request.options(), request.callback() == null ? null : event -> {
            if (request.result().isDone() || request.owner() != session) {
                var retired = new CompletableFuture<String>();
                retired.cancel(false);
                return retired;
            }
            return request.callback().apply(event);
        });
        dialog.whenComplete((value, failure) -> App.post(() -> {
            if (failure == null) request.result().complete(value);
            else {
                boolean completed = request.result().completeExceptionally(failure);
                if (completed && request.owner() == session) request.owner().cancelled().run();
            }
            finish();
        }));
        request.result().whenComplete((value, failure) -> App.post(() -> {
            if (dialog != null && !dialog.isDone()) dialog.cancel(true);
            finish();
        }));
    }

    @Override protected void onDestroy() {
        // The original dialog cancels on Activity destruction. Do not replay
        // a possibly consumed provider action after recreation. Resume through
        // an explicit playback retry, keeping the provider's valid login cache.
        if (request != null && !request.result().isDone()) request.result().complete(null);
        if (dialog != null && !dialog.isDone()) dialog.cancel(true);
        super.onDestroy();
    }
}
