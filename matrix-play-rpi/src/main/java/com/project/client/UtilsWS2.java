package com.project.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.handshake.ServerHandshake;

public class UtilsWS2 {

    private static UtilsWS2 sharedInstance = null;
    private WebSocketClient client;
    private Consumer<String> onOpenCallBack = null;
    private Consumer<String> onMessageCallBack = null;
    private Consumer<String> onCloseCallBack = null;
    private Consumer<String> onErrorCallBack = null;
    private String location = "";
    private static AtomicBoolean exitRequested = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> reconnectFuture = null;

    private UtilsWS2(String location) {
        this.location = location;
        createNewWebSocketClient();
    }

    private void createNewWebSocketClient() {
        try {
            this.client = new WebSocketClient(new URI(location), new Draft_6455()) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    // Mantener vivo con ping/pong
                    try {
                        setConnectionLostTimeout(30);
                    } catch (Exception ignored) {
                    }

                    String message = "WS connected to: " + getURI();
                    System.out.println(message);

                    // Cancelar cualquier reconexión pendiente al abrir correctamente
                    synchronized (UtilsWS2.this) {
                        if (reconnectFuture != null && !reconnectFuture.isDone()) {
                            reconnectFuture.cancel(true);
                            reconnectFuture = null;
                        }
                    }

                    if (onOpenCallBack != null) {
                        onOpenCallBack.accept(message);
                    }
                }

                @Override
                public void onMessage(String message) {
                    if (onMessageCallBack != null) {
                        onMessageCallBack.accept(message);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    String message = "WS closed connection from: " + getURI() + " with reason: " + reason;
                    System.out.println(message);
                    if (onCloseCallBack != null) {
                        onCloseCallBack.accept(message);
                    }
                    // Proponer reconexión si no estamos saliendo
                    scheduleReconnect();
                }

                @Override
                public void onError(Exception e) {
                    String msg = "WS connection error: "
                            + (e != null && e.getMessage() != null ? e.getMessage() : "unknown");
                    System.out.println(msg);
                    if (onErrorCallBack != null) {
                        onErrorCallBack.accept(msg);
                    }
                    // Si no está abierto, programar reconexión (evitar duplicados)
                    if (!(client != null && client.getReadyState() == ReadyState.OPEN)) {
                        scheduleReconnect();
                    }
                }
            };

            // Iniciar conexión (no bloqueante)
            this.client.connect();

        } catch (URISyntaxException e) {
            e.printStackTrace();
            System.out.println("WS Error, " + location + " is not a valid URI");
        }
    }

    private synchronized void scheduleReconnect() {
        if (exitRequested.get())
            return;

        // No programar si ya hay una reconexión pendiente
        if (reconnectFuture != null && !reconnectFuture.isDone()) {
            return;
        }

        // Si ya está abierto o intentando conectar, no reagendar
        if (client != null) {
            ReadyState st = client.getReadyState();
            if (st == ReadyState.OPEN || st == ReadyState.NOT_YET_CONNECTED) {
                return;
            }
        }

        reconnectFuture = scheduler.schedule(this::reconnect, 5, TimeUnit.SECONDS);
    }

    private void reconnect() {
        if (exitRequested.get())
            return;

        synchronized (this) {
            if (client != null && client.isOpen()) {
                // Ya está abierto; no tocar
                reconnectFuture = null;
                return;
            }

            System.out.println("WS reconnecting to: " + this.location);

            try {
                if (client != null && !client.isClosed()) {
                    client.closeBlocking();
                }
            } catch (Exception e) {
                System.out.println("WS error while closing before reconnect: " + e.getMessage());
            }

            createNewWebSocketClient();
            reconnectFuture = null;
        }
    }

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
                } else if (st == ReadyState.NOT_YET_CONNECTED || st == ReadyState.CLOSING) {
                    System.out.println("WS Info: Client is connecting/closing. Not sending yet.");
                    return;
                }
            }
            System.out.println("WS Error: Client is not connected. Attempting to reconnect...");
            scheduleReconnect();
        } catch (Exception e) {
            System.out.println("WS Error sending message: " + e.getMessage());
        }
    }

    public void forceExit() {
        System.out.println("WS Closing ...");
        exitRequested.set(true);
        try {
            if (client != null && !client.isClosed()) {
                client.closeBlocking();
            }
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