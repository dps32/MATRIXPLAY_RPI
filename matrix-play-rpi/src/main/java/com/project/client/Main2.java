package com.project.client;

import com.piomatter.PioMatter;

import org.json.JSONObject;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import com.piomatter.UtilsFPS;

public class Main2 {

    // Matriz LED
    private static final int WIDTH = 64, HEIGHT = 64;
    private static final int ADDR = 5;
    private static final int LANES = 2;
    private static final int BRIGHTNESS = 200;
    private static final int FPS_CAP = 60;

    // Reservado para texto superior
    private static final int RESERVED_TOP = 12;

    // Estados de visualización
    private enum Mode {
        NONE, TEXT, IMAGE, COUNTDOWN, BALL
    }

    private volatile Mode mode = Mode.NONE;
    private volatile String text = null;
    private volatile BufferedImage image = null;
    private volatile long expireAtMs = 0L;

    // Countdown
    private volatile int countdownValue = 0;
    private volatile long countdownLastUpdateMs = 0L;

    // Guardar modo previo para restaurar después del countdown
    private volatile Mode previousMode = Mode.NONE;
    private volatile String previousText = null;
    private volatile BufferedImage previousImage = null;

    // Scroll continuo
    private int scrollX = WIDTH;

    // WebSocket
    private final UtilsWS2 ws;

    // Archivo JSON para guardar la URL
    private static final String URL_FILE = "url.json";

    // Constructor
    public Main2(String serverUri) {
        ws = UtilsWS2.getSharedInstance(serverUri);
        ws.onMessage(this::onWsMessage);
        ws.onOpen(this::onWsOpen);
        ws.onClose(this::onWsClose);
    }

    // Callbacks WS
    private void onWsOpen(String msg) {
        System.out.println("[client] Connected: " + msg);
        // Cuando se conecta, cargar y preservar la URL guardada para mostrarla después
        // del groupname
        String savedUrl = loadUrlFromJson();
        if (!savedUrl.isEmpty()) {
            previousMode = Mode.TEXT;
            previousText = savedUrl;
            previousImage = null;
            System.out.println("[client] Loaded saved URL: " + savedUrl);
        }

        // Solicitar el nombre del grupo al servidor
        requestGroupNameFromServer();

        // Solicitar la URL al servidor
        requestUrlFromServer();
    }

    private void onWsClose(String msg) {
        System.out.println("[client] Disconnected: " + msg);
    }

    // Enviar solicitud de URL al servidor
    private void requestUrlFromServer() {
        try {
            JSONObject request = new JSONObject();
            request.put("type", "url");
            ws.safeSend(request.toString());
            System.out.println("[client] Requesting URL from server...");
        } catch (Exception e) {
            System.err.println("[client] Error requesting URL: " + e.getMessage());
        }
    }

    // Enviar solicitud de groupname al servidor
    private void requestGroupNameFromServer() {
        try {
            JSONObject request = new JSONObject();
            request.put("type", "groupname");
            ws.safeSend(request.toString());
            System.out.println("[client] Requesting group name from server...");
        } catch (Exception e) {
            System.err.println("[client] Error requesting group name: " + e.getMessage());
        }
    }

    // Manejar mensajes entrantes del WS
    private void onWsMessage(String msg) {
        try {
            JSONObject o = new JSONObject(msg);
            String t = o.optString("type", "");
            switch (t) {
                case "welcome" -> {
                    String welcomeMsg = o.optString("message", "");
                    System.out.println("[client] Welcome message: " + welcomeMsg);
                }
                case "groupname" -> {
                    String groupName = o.optString("message", "");
                    System.out.println("[client] Group name: " + groupName);
                    if (!groupName.isEmpty()) {
                        // No machacar previous si ya está preparado (p.ej. con la URL cargada del JSON)
                        if (previousMode == Mode.NONE && text != null) {
                            previousMode = mode;
                            previousText = text;
                            previousImage = image;
                        }

                        // Mostrar el nombre del grupo temporalmente
                        text = "Group: " + groupName;
                        mode = Mode.TEXT;
                        scrollX = WIDTH; // Reiniciar scroll
                        expireAtMs = System.currentTimeMillis() + 40_000L; // 40 segundos reales
                        System.out.println("[client] Showing group name for 40 seconds");
                    }
                }
                case "url" -> {
                    String url = o.optString("message", "");
                    if (!url.isEmpty()) {
                        saveUrlToJson(url);

                        // Si estamos mostrando el groupname, guardar la URL para después
                        if (mode == Mode.TEXT && text != null && text.startsWith("Group: ")) {
                            previousMode = Mode.TEXT;
                            previousText = url;
                            previousImage = null;
                            System.out.println("[client] URL saved to show after group name");
                        } else {
                            // Si no hay groupname activo, mostrar URL directamente
                            text = url;
                            image = null;
                            mode = Mode.TEXT;
                            scrollX = WIDTH;
                            expireAtMs = System.currentTimeMillis() + 3_600_000L; // 1 hora
                            System.out.println("[client] URL displayed immediately");
                        }
                    }
                }
                case "countdown" -> {
                    int count = o.optInt("number", 3);
                    if (count >= 0) {
                        // Guardar el estado actual para restaurarlo después
                        if (mode != Mode.NONE && previousMode == Mode.NONE) {
                            previousMode = mode;
                            previousText = text;
                            previousImage = image;
                        }

                        countdownValue = count;
                        countdownLastUpdateMs = System.currentTimeMillis();
                        mode = Mode.COUNTDOWN;
                        expireAtMs = System.currentTimeMillis() + ((count + 1) * 1000L) + 500L;
                        System.out.println("[client] Countdown started from: " + count);
                    }
                }
                default -> {
                    /* ignore */ }
            }
        } catch (Exception ignored) {
        }
    }

    private void saveUrlToJson(String url) {
        try {
            JSONObject json = new JSONObject();
            json.put("url", url);
            json.put("timestamp", System.currentTimeMillis());

            Files.writeString(
                    Paths.get(URL_FILE),
                    json.toString(2),
                    StandardCharsets.UTF_8);
            System.out.println("[client] URL saved to " + Paths.get(URL_FILE).toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[client] Error saving URL to JSON: " + e.getMessage());
        }
    }

    private String loadUrlFromJson() {
        try {
            if (Files.exists(Paths.get(URL_FILE))) {
                String content = Files.readString(Paths.get(URL_FILE), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(content);
                String url = json.optString("url", "");
                System.out.println("[client] URL loaded from " + Paths.get(URL_FILE).toAbsolutePath() + ": " + url);
                return url;
            } else {
                System.out.println("[client] URL file not found at " + Paths.get(URL_FILE).toAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("[client] Error loading URL from JSON: " + e.getMessage());
        }
        return "";
    }

    public void run() {
        PioMatter pm = null;
        PioMatter.FB fb = null;
        BufferedImage back = null;
        Graphics2D g = null;
        final UtilsFPS fps = new UtilsFPS();

        try {
            // Un pequeño empujón de prioridad ayuda a la regularidad sin introducir
            // limitadores nuevos
            try {
                Thread.currentThread().setPriority(Math.min(Thread.MAX_PRIORITY - 1, Thread.NORM_PRIORITY + 2));
            } catch (Exception ignored) {
            }

            pm = new PioMatter(WIDTH, HEIGHT, ADDR, LANES, BRIGHTNESS, 0);
            fb = pm.mapFramebuffer();

            back = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            g = back.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

            final Font font = new Font("SansSerif", Font.BOLD, 12);
            final Font countdownFont = new Font("SansSerif", Font.BOLD, 48);
            PioMatter.flushBlack(pm, fb, 2, 10);

            while (true) {
                fps.beginFrame();
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, WIDTH, HEIGHT);

                int startY = Math.max(0, RESERVED_TOP);
                boolean alive = System.currentTimeMillis() < expireAtMs;

                if (alive) {
                    if (mode == Mode.COUNTDOWN) {
                        // Actualizar countdown cada segundo
                        long elapsed = System.currentTimeMillis() - countdownLastUpdateMs;
                        int currentCount = countdownValue - (int) (elapsed / 1000L);

                        if (currentCount >= 0) {
                            g.setFont(countdownFont);
                            FontMetrics fm = g.getFontMetrics();
                            String countStr = String.valueOf(currentCount);
                            int textWidth = fm.stringWidth(countStr);
                            int textHeight = fm.getAscent();

                            int x = (WIDTH - textWidth) / 2;
                            int y = (HEIGHT + textHeight) / 2;

                            // Color que va cambiando según el número
                            float hue = currentCount / 3.0f * 0.33f; // De rojo a amarillo
                            g.setColor(Color.getHSBColor(hue, 1f, 1f));
                            g.drawString(countStr, x, y);
                        } else {
                            // Cuando termina el countdown, mostrar la bola blanca
                            mode = Mode.BALL;
                            expireAtMs = System.currentTimeMillis() + 2_000L; // Mostrar bola 2 segundos
                        }

                    } else if (mode == Mode.BALL) {
                        // Dibujar círculo blanco en el centro
                        int ballSize = 20;
                        int ballX = (WIDTH - ballSize) / 2;
                        int ballY = (HEIGHT - ballSize) / 2;

                        g.setColor(Color.WHITE);
                        g.fillOval(ballX, ballY, ballSize, ballSize);

                    } else if (mode == Mode.TEXT && text != null) {
                        g.setFont(font);
                        FontMetrics fm = g.getFontMetrics();

                        // ---- DIBUJADO O(n): sin stringWidth(substring(0,i)) ----
                        int xCursor = scrollX;
                        for (int i = 0; i < text.length(); i++) {
                            char c = text.charAt(i);
                            int cw = fm.charWidth(c);

                            if (xCursor + cw < 0) { // completamente a la izquierda
                                xCursor += cw;
                                continue;
                            }
                            if (xCursor >= WIDTH) { // ya no se ve nada más
                                break;
                            }

                            float ratio = (float) i / Math.max(1, text.length());
                            g.setColor(Color.getHSBColor(ratio, 1f, 1f));
                            g.drawString(String.valueOf(c), xCursor, startY + fm.getAscent());

                            xCursor += cw;
                        }

                        scrollX -= 1;
                        // Cuando todo el texto salió por la izquierda, reiniciar
                        if (xCursor < 0)
                            scrollX = WIDTH;
                    }
                } else {
                    // Expirado, volver a NONE o modo previo
                    if ((mode == Mode.BALL || mode == Mode.TEXT) && previousMode != Mode.NONE) {
                        mode = previousMode;
                        text = previousText;
                        image = previousImage;
                        expireAtMs = System.currentTimeMillis() + 3_600_000L; // 1 hora
                        scrollX = WIDTH; // Reiniciar scroll
                        previousMode = Mode.NONE;
                        previousText = null;
                        previousImage = null;
                    } else {
                        mode = Mode.NONE;
                        text = null;
                        image = null;
                    }
                }

                // Dibujar overlay FPS (de tu UtilsFPS)
                fps.drawOverlay(g, 1, 9);

                // Indicador de conexión WS
                int iconSize = 5;
                int iconX = WIDTH - iconSize - 2;
                int iconY = 2;
                if (ws.isOpen()) {
                    g.setColor(Color.GREEN); // conectado
                } else {
                    g.setColor(Color.RED); // desconectado
                }
                g.fillOval(iconX, iconY, iconSize, iconSize);

                PioMatter.copyBufferedImageToRGB888(back, fb.data, fb.strideBytes, WIDTH, HEIGHT, BRIGHTNESS);
                pm.swap();
                fps.endFrameAndCap(FPS_CAP);
            }

        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (g != null)
                g.dispose();
            try {
                if (pm != null && fb != null)
                    PioMatter.flushBlack(pm, fb, 2, 10);
            } catch (InterruptedException ignored) {
            }
            if (pm != null)
                pm.close();
            ws.forceExit();
        }
    }

    public static void main(String[] args) {
        String serverURI = (args.length > 0) ? args[0] : "wss://matrixplay1.ieti.site:443";
        Main2 app = new Main2(serverURI);
        app.run();
    }
}