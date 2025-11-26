package com.project2.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.concurrent.ThreadLocalRandom;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.handshake.ServerHandshake;

public class UtilsWS2 {

    private static UtilsWS2 sharedInstance = null;

    // --- Config reconexión ---
    private static final long RECONNECT_BASE_MS = 2_000L; // 2s
    private static final long RECONNECT_MAX_MS = 60_000L; // 60s
    private static final long CONNECT_ATTEMPT_TIMEOUT_MS = 15_000L; // watchdog del intento

    private final String location;
    private volatile WebSocketClient client;

    private Consumer<String> onOpenCallBack = null;
    private Consumer<String> onMessageCallBack = null;
    private Consumer<String> onCloseCallBack = null;
    private Consumer<String> onErrorCallBack = null;

    private static final AtomicBoolean exitRequested = new AtomicBoolean(false);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ws-reconnector");
            t.setDaemon(true);
            return t;
        }
    });

    private ScheduledFuture<?> reconnectFuture = null;
    private ScheduledFuture<?> connectWatchdog = null;
    private volatile int reconnectAttempts = 0;

    private UtilsWS2(String location) {
        this.location = location;
        createNewWebSocketClient();
    }

    // ---------- Creación y callbacks ----------
    private void createNewWebSocketClient() {
        try {
            this.client = new WebSocketClient(new URI(location), new Draft_6455()) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    try {
                        setConnectionLostTimeout(30);
                    } catch (Exception ignored) {
                    }
                    String message = "WS connected to: " + getURI();
                    System.out.println(message);

                    // reset estado reconexión
                    cancelReconnectTimer();
                    cancelConnectWatchdog();
                    reconnectAttempts = 0;

                    if (onOpenCallBack != null)
                        onOpenCallBack.accept(message);
                }

                @Override
                public void onMessage(String message) {
                    if (onMessageCallBack != null)
                        onMessageCallBack.accept(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    String message = "WS closed from: " + getURI() + " reason: " + reason;
                    System.out.println(message);
                    cancelConnectWatchdog(); // este intento terminó
                    if (onCloseCallBack != null)
                        onCloseCallBack.accept(message);
                    scheduleReconnect(); // y programamos el siguiente
                }

                @Override
                public void onError(Exception e) {
                    String msg = "WS error: " + (e != null && e.getMessage() != null ? e.getMessage() : "unknown");
                    System.out.println(msg);
                    cancelConnectWatchdog(); // este intento ya falló
                    if (onErrorCallBack != null)
                        onErrorCallBack.accept(msg);
                    scheduleReconnect();
                }
            };

            // Iniciar conexión (no bloqueante) y arrancar watchdog
            this.client.connect();
            startConnectWatchdog();

        } catch (URISyntaxException e) {
            e.printStackTrace();
            System.out.println("WS Error, " + location + " is not a valid URI");
        }
    }

    // ---------- Reconexión infinita con backoff + jitter ----------
    private synchronized void scheduleReconnect() {
        if (exitRequested.get())
            return;

        // Si ya hay un timer de reconexión activo, no dupliques
        if (reconnectFuture != null && !reconnectFuture.isDone())
            return;

        // calcular próximo delay (exponencial con tope + jitter)
        long delay = nextBackoffMs();
        int nextAttempt = reconnectAttempts + 1;

        System.out.println("WS will retry in " + delay + " ms (attempt #" + nextAttempt + ")");
        reconnectFuture = scheduler.schedule(this::reconnectNow, delay, TimeUnit.MILLISECONDS);
    }

    private long nextBackoffMs() {
        int n = Math.min(reconnectAttempts, 16); // evita overflow
        long base = RECONNECT_BASE_MS * (1L << n); // 2s, 4s, 8s...
        long capped = Math.min(base, RECONNECT_MAX_MS);
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1L, capped / 3)); // 0..~capped/3
        return capped + jitter;
    }

    private void reconnectNow() {
        if (exitRequested.get())
            return;

        synchronized (this) {
            reconnectAttempts++;
            System.out.println("WS reconnecting (attempt #" + reconnectAttempts + ") to: " + this.location);

            // cerrar cliente previo sin bloquear el hilo del scheduler
            try {
                if (client != null)
                    client.close();
            } catch (Exception ignored) {
            }

            // crear cliente nuevo e iniciar watchdog del intento
            createNewWebSocketClient();

            // liberar el handle del timer actual
            reconnectFuture = null;
        }
    }

    // ---------- Watchdog del intento de conexión ----------
    private synchronized void startConnectWatchdog() {
        cancelConnectWatchdog();
        connectWatchdog = scheduler.schedule(() -> {
            if (exitRequested.get())
                return;
            ReadyState st = (client != null) ? client.getReadyState() : null;
            if (st != ReadyState.OPEN) {
                System.out.println("WS connect attempt timed out (state=" + st + "). Forcing retry...");
                try {
                    if (client != null)
                        client.close();
                } catch (Exception ignored) {
                }
                scheduleReconnect();
            }
        }, CONNECT_ATTEMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelConnectWatchdog() {
        if (connectWatchdog != null && !connectWatchdog.isDone()) {
            connectWatchdog.cancel(true);
        }
        connectWatchdog = null;
    }

    private synchronized void cancelReconnectTimer() {
        if (reconnectFuture != null && !reconnectFuture.isDone()) {
            reconnectFuture.cancel(true);
        }
        reconnectFuture = null;
    }

    // ---------- API pública ----------
    public static UtilsWS2 getSharedInstance(String location) {
        if (sharedInstance == null) {
            sharedInstance = new UtilsWS2(location);
        }
        return sharedInstance;
    }

    public void onOpen(Consumer<String> callBack) {
        this.onOpenCallBack = callBack;
    }

    public void onMessage(Consumer<String> callBack) {
        this.onMessageCallBack = callBack;
    }

    public void onClose(Consumer<String> callBack) {
        this.onCloseCallBack = callBack;
    }

    public void onError(Consumer<String> callBack) {
        this.onErrorCallBack = callBack;
    }

    public void safeSend(String text) {
        try {
            if (client != null) {
                ReadyState st = client.getReadyState();
                if (st == ReadyState.OPEN) {
                    client.send(text);
                    return;
                }
                // si no está abierto, garantizamos que haya reconexión en curso
                System.out.println("WS Info: Not open (state=" + st + "). Will ensure reconnect.");
                scheduleReconnect();
            } else {
                System.out.println("WS Info: Client is null. Will ensure reconnect.");
                scheduleReconnect();
            }
        } catch (Exception e) {
            System.out.println("WS Error sending message: " + e.getMessage());
        }
    }

    public void forceExit() {
        System.out.println("WS Closing ...");
        exitRequested.set(true);
        cancelConnectWatchdog();
        cancelReconnectTimer();
        try {
            if (client != null)
                client.close();
        } catch (Exception e) {
            System.out.println("WS Interrupted while closing WebSocket connection: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            scheduler.shutdownNow();
        }
    }

    public boolean isOpen() {
        return client != null && client.isOpen();
    }
}