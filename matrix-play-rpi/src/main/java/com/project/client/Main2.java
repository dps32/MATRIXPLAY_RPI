package com.project2.client;

import com.piomatter.PioMatter;
import org.json.JSONObject;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import com.piomatter.UtilsFPS;
import javax.imageio.ImageIO;
import java.io.File;

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
    private enum Mode { NONE, TEXT, IMAGE, COUNTDOWN, BALL, GAME, WAITING_FOR_GAME }
    private volatile Mode mode = Mode.NONE;
    private volatile String text = null;
    private volatile BufferedImage image = null;
    private volatile long expireAtMs = 0L;
    
    // Imagen de fondo para el juego
    private BufferedImage imgFile = null;

    // Countdown
    private volatile int countdownValue = 0;
    private volatile long countdownLastUpdateMs = 0L;
    
    // Estado del juego
    private volatile float ballX = 0.5f;
    private volatile float ballY = 0.5f;
    private volatile float paddle1Y = 0.5f;
    private volatile float paddle2Y = 0.5f;
    private volatile int score1 = 0;
    private volatile int score2 = 0;
    
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

    public Main2(String serverUri) {
        System.out.println("[client] Initializing connection to: " + serverUri);
        ws = UtilsWS2.getSharedInstance(serverUri);
        ws.onMessage(this::onWsMessage);
        ws.onOpen(this::onWsOpen);
        ws.onClose(this::onWsClose);
        System.out.println("[client] WebSocket handlers configured");
    }

    private void onWsOpen(String msg) {
        System.out.println("[client] Connected: " + msg);
        String savedUrl = loadUrlFromJson();
        if (!savedUrl.isEmpty()) {
            previousMode = Mode.TEXT;
            previousText = savedUrl;
            previousImage = null;
            System.out.println("[client] Loaded saved URL: " + savedUrl);
        }

        requestGroupNameFromServer();
        requestUrlFromServer();
    }

    private void onWsClose(String msg) {
        System.out.println("[client] Disconnected: " + msg);
    }

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
                    String groupName = o.optString("value", "");
                    System.out.println("[client] Group name: " + groupName);
                    if (!groupName.isEmpty()) {
                        if (previousMode == Mode.NONE && text != null) {
                            previousMode = mode;
                            previousText = text;
                            previousImage = image;
                        }

                        text = "Group: " + groupName;
                        mode = Mode.TEXT;
                        scrollX = WIDTH;
                        expireAtMs = System.currentTimeMillis() + 40_000L;
                        System.out.println("[client] Showing group name for 40 seconds");
                    }
                }
                case "url" -> {
                    String url = o.optString("value", "");
                    if (!url.isEmpty()) {
                        saveUrlToJson(url);

                        if (mode == Mode.TEXT && text != null && text.startsWith("Group: ")) {
                            previousMode = Mode.TEXT;
                            previousText = url;
                            previousImage = null;
                            System.out.println("[client] URL saved to show after group name");
                        } else {
                            text = url;
                            image = null;
                            mode = Mode.TEXT;
                            scrollX = WIDTH;
                            expireAtMs = System.currentTimeMillis() + 3_600_000L;
                            System.out.println("[client] URL displayed immediately");
                        }
                    }
                }
                case "countdown" -> {
                    int count = o.optInt("number", 3);
                    if (count >= 0) {
                        if (mode != Mode.NONE && previousMode == Mode.NONE) {
                            previousMode = mode;
                            previousText = text;
                            previousImage = image;
                        }

                        countdownValue = count;
                        countdownLastUpdateMs = System.currentTimeMillis();
                        mode = Mode.COUNTDOWN;
                        expireAtMs = System.currentTimeMillis() + ((count + 1) * 1000L) + 4000L; // +4 segundos extra
                        System.out.println("[client] Countdown started from: " + count);
                    }
                }
                case "gameState" -> {
                    // Actualizar estado del juego
                    JSONObject ball = o.optJSONObject("ball");
                    if (ball != null) {
                        ballX = (float) ball.optDouble("x", 0.5);
                        ballY = (float) ball.optDouble("y", 0.5);
                    }

                    JSONObject paddle1 = o.optJSONObject("paddle1");
                    if (paddle1 != null) {
                        paddle1Y = (float) paddle1.optDouble("y", 0.5);
                    }

                    JSONObject paddle2 = o.optJSONObject("paddle2");
                    if (paddle2 != null) {
                        paddle2Y = (float) paddle2.optDouble("y", 0.5);
                    }

                    JSONObject score = o.optJSONObject("score");
                    if (score != null) {
                        score1 = score.optInt("player1", 0);
                        score2 = score.optInt("player2", 0);
                    }

                    // Cambiar al modo de juego solo si countdown terminó o no hay countdown
                    if (mode == Mode.COUNTDOWN) {
                        // Guardar que hay un juego esperando
                        mode = Mode.WAITING_FOR_GAME;
                        System.out.println("[client] Game waiting for countdown to finish");
                    } else if (mode != Mode.GAME) {
                        mode = Mode.GAME;
                        expireAtMs = Long.MAX_VALUE; // No expira
                        System.out.println("[client] Game mode activated");
                    }
                }
                default -> { /* ignore */ }
            }
        } catch (Exception ignored) {}
    }
    
    private void saveUrlToJson(String url) {
        try {
            JSONObject json = new JSONObject();
            json.put("url", url);
            json.put("timestamp", System.currentTimeMillis());

            Files.writeString(
                Paths.get(URL_FILE),
                json.toString(2),
                StandardCharsets.UTF_8
            );
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
            System.out.println("[client] Setting thread priority...");
            try { Thread.currentThread().setPriority(Math.min(Thread.MAX_PRIORITY - 1, Thread.NORM_PRIORITY + 2)); } catch (Exception ignored) {}

            // Cargar imagen de fondo para el juego
            System.out.println("[client] Loading background image...");
            try {
                // Intentar múltiples rutas posibles
                File imageFile = new File("resources/backgroundPacMan.jpg");
                if (!imageFile.exists()) {
                    imageFile = new File("backgroundPacMan.jpg");
                }
                if (!imageFile.exists()) {
                    imageFile = new File("src/main/resources/backgroundPacMan.jpg");
                }
                
                if (imageFile.exists()) {
                    this.imgFile = ImageIO.read(imageFile);
                    System.out.println("[client] Background image loaded successfully from: " + imageFile.getAbsolutePath());
                } else {
                    System.err.println("[client] Could not find image in any of the expected locations");
                }
            } catch (Exception e) {
                System.err.println("[client] Error loading image: " + e.getMessage());
                e.printStackTrace();
            }

            System.out.println("[client] Initializing PioMatter...");
            pm = new PioMatter(WIDTH, HEIGHT, ADDR, LANES, BRIGHTNESS, 0);
            fb = pm.mapFramebuffer();
            System.out.println("[client] PioMatter initialized successfully");

            back = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            g = back.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

            final Font font = new Font("SansSerif", Font.BOLD, 12);
            final Font countdownFont = new Font("SansSerif", Font.BOLD, 48);
            final Font scoreFont = new Font("SansSerif", Font.BOLD, 10);
            PioMatter.flushBlack(pm, fb, 2, 10);

            while (true) {
                fps.beginFrame();
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, WIDTH, HEIGHT);

                int startY = Math.max(0, RESERVED_TOP);
                boolean alive = System.currentTimeMillis() < expireAtMs;

                if (alive || mode == Mode.GAME) {
                    if (mode == Mode.COUNTDOWN) {
                        long elapsed = System.currentTimeMillis() - countdownLastUpdateMs;
                        int currentCount = countdownValue - (int)(elapsed / 1000L);
                        
                        if (currentCount >= 0) {
                            g.setFont(countdownFont);
                            FontMetrics fm = g.getFontMetrics();
                            String countStr = String.valueOf(currentCount);
                            int textWidth = fm.stringWidth(countStr);
                            int textHeight = fm.getAscent();
                            
                            int x = (WIDTH - textWidth) / 2;
                            int y = (HEIGHT + textHeight) / 2;
                            
                            float hue = currentCount / 3.0f * 0.33f;
                            g.setColor(Color.getHSBColor(hue, 1f, 1f));
                            g.drawString(countStr, x, y);
                        }
                        
                    } else if (mode == Mode.WAITING_FOR_GAME) {
                        // Mostrar pantalla negra mientras esperamos
                        g.setColor(Color.BLACK);
                        g.fillRect(0, 0, WIDTH, HEIGHT);
                        
                    } else if (mode == Mode.BALL) {
                        int ballSize = 20;
                        int ballX = (WIDTH - ballSize) / 2;
                        int ballY = (HEIGHT - ballSize) / 2;
                        
                        g.setColor(Color.WHITE);
                        g.fillOval(ballX, ballY, ballSize, ballSize);
                        
                    } else if (mode == Mode.GAME) {
                        // DIBUJAR JUEGO DE PONG
                        drawPongGame(g, scoreFont);
                        
                    } else if (mode == Mode.TEXT && text != null) {
                        g.setFont(font);
                        FontMetrics fm = g.getFontMetrics();

                        int xCursor = scrollX;
                        for (int i = 0; i < text.length(); i++) {
                            char c = text.charAt(i);
                            int cw = fm.charWidth(c);

                            if (xCursor + cw < 0) {
                                xCursor += cw;
                                continue;
                            }
                            if (xCursor >= WIDTH) {
                                break;
                            }

                            float ratio = (float) i / Math.max(1, text.length());
                            g.setColor(Color.getHSBColor(ratio, 1f, 1f));
                            g.drawString(String.valueOf(c), xCursor, startY + fm.getAscent());

                            xCursor += cw;
                        }

                        scrollX -= 1;
                        if (xCursor < 0) scrollX = WIDTH;
                    }
                } else {
                    // Expirado
                    if (mode == Mode.COUNTDOWN || mode == Mode.WAITING_FOR_GAME) {
                        // Countdown terminó
                        if (mode == Mode.WAITING_FOR_GAME) {
                            // Hay un juego esperando, activarlo
                            mode = Mode.GAME;
                            expireAtMs = Long.MAX_VALUE;
                            System.out.println("[client] Countdown finished, starting game");
                        } else {
                            // No hay juego, restaurar modo anterior
                            mode = previousMode;
                            text = previousText;
                            image = previousImage;
                            expireAtMs = System.currentTimeMillis() + 3_600_000L;
                            scrollX = WIDTH;
                            previousMode = Mode.NONE;
                            previousText = null;
                            previousImage = null;
                        }
                    } else if ((mode == Mode.BALL || mode == Mode.TEXT) && previousMode != Mode.NONE) {
                        mode = previousMode;
                        text = previousText;
                        image = previousImage;
                        expireAtMs = System.currentTimeMillis() + 3_600_000L;
                        scrollX = WIDTH;
                        previousMode = Mode.NONE;
                        previousText = null;
                        previousImage = null;
                    } else {
                        mode = Mode.NONE;
                        text = null;
                        image = null;
                    }
                }

                // Overlay FPS (no mostrar durante el juego)
                if (mode != Mode.GAME) {
                    fps.drawOverlay(g, 1, 9);
                }

                // Indicador de conexión WS
                int iconSize = 3;
                int iconX = WIDTH - iconSize - 1;
                int iconY = 1;
                g.setColor(ws.isOpen() ? Color.GREEN : Color.RED);
                g.fillRect(iconX, iconY, iconSize, iconSize);

                PioMatter.copyBufferedImageToRGB888(back, fb.data, fb.strideBytes, WIDTH, HEIGHT, BRIGHTNESS);
                pm.swap();
                fps.endFrameAndCap(FPS_CAP);
            }

        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (g != null) g.dispose();
            try { if (pm != null && fb != null) PioMatter.flushBlack(pm, fb, 2, 10); } catch (InterruptedException ignored) {}
            if (pm != null) pm.close();
            ws.forceExit();
        }
    }

    private void drawPongGame(Graphics2D g, Font scoreFont) {
        // Dimensiones de elementos del juego
        final int paddleWidth = 2;
        final int paddleHeight = 15;
        final int ballSize = 3;
        final int playAreaTop = 0;  // Toda la pantalla es el campo
        final int playAreaHeight = HEIGHT;

        // Dibujar imagen de fondo si está disponible
        if (imgFile != null) {
            // Escalar y dibujar la imagen para que cubra toda la pantalla
            g.drawImage(imgFile, 0, 0, WIDTH, HEIGHT, null);
            // Añadir una capa semi-transparente para mejorar la visibilidad de los elementos
            g.setColor(new Color(0, 0, 0, 100));
            g.fillRect(0, 0, WIDTH, HEIGHT);
        } else {
            // Si no hay imagen, dibujar fondo negro
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, WIDTH, HEIGHT);
        }

        // Dibujar línea central DOBLE
        g.setColor(new Color(80, 80, 80));
        int centerX = WIDTH / 2;
        for (int y = playAreaTop; y < HEIGHT; y += 4) {
            g.drawLine(centerX, y, centerX, y + 2);
        }

        // Dibujar pala izquierda (Jugador 1) - en el borde izquierdo
        int paddle1YPixel = (int) (playAreaTop + paddle1Y * playAreaHeight);
        g.setColor(Color.WHITE);
        g.fillRect(0, paddle1YPixel - paddleHeight / 2, paddleWidth, paddleHeight);

        // Dibujar pala derecha (Jugador 2) - en el borde derecho
        int paddle2YPixel = (int) (playAreaTop + paddle2Y * playAreaHeight);
        g.fillRect(WIDTH - paddleWidth, paddle2YPixel - paddleHeight / 2, paddleWidth, paddleHeight);

        // Dibujar pelota
        int ballXPixel = (int) (ballX * WIDTH);
        int ballYPixel = (int) (playAreaTop + ballY * playAreaHeight);
        g.fillOval(ballXPixel - ballSize / 2, ballYPixel - ballSize / 2, ballSize, ballSize);

        // Dibujar puntuaciones en la parte superior con fondo semi-transparente
        g.setFont(scoreFont);
        FontMetrics fm = g.getFontMetrics();
        String scoreText = score1 + " - " + score2;
        int textWidth = fm.stringWidth(scoreText);
        int textX = (WIDTH - textWidth) / 2;
        int textY = 8;
        
        // Fondo semi-transparente para la puntuación
        g.setColor(new Color(0, 0, 0, 200));
        g.fillRect(textX - 2, textY - fm.getAscent(), textWidth + 4, fm.getHeight());
        
        // Texto de puntuación
        g.setColor(Color.WHITE);
        g.drawString(scoreText, textX, textY);
    }

    public static void main(String[] args) {
        System.out.println("[client] Starting Main2...");
        System.out.println("[client] Java version: " + System.getProperty("java.version"));
        System.out.println("[client] Working directory: " + System.getProperty("user.dir"));
        
        String serverURI = (args.length > 0) ? args[0] : "wss://matrixplay1.ieti.site:443";
        System.out.println("[client] Server URI: " + serverURI);
        
        Main2 app = new Main2(serverURI);
        System.out.println("[client] Main2 instance created, starting run loop...");
        app.run();
    }
}