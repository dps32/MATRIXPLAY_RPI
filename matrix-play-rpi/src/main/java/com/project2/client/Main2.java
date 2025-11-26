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
import java.util.Date;
import java.util.Random;

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
        NONE, TEXT, IMAGE, COUNTDOWN, BALL, GAME, WAITING_FOR_GAME, START, RESULT, POSTGROUP
    }

    private volatile Mode mode = Mode.NONE;
    private volatile String text = null;
    private volatile BufferedImage image = null;
    private volatile long expireAtMs = 0L;

    // Imágenes para animaciones
    private BufferedImage imgFile = null;
    private BufferedImage sunImage = null;
    private BufferedImage earthImage = null;
    private BufferedImage moonImage = null;

    // Imágenes para el carrusel 3D
    private BufferedImage[] carouselImages = new BufferedImage[4];
    private int currentImageIndex = 0;
    private long lastImageChangeMs = 0L;
    private static final long IMAGE_DISPLAY_TIME = 3000L;
    private float transitionProgress = 0f;
    private boolean isTransitioning = false;
    private static final float TRANSITION_SPEED = 0.05f;

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

    // Nombres de jugadores
    private volatile String player1Name = "Player1";
    private volatile String player2Name = "Player2";

    // Guardar modo previo
    private volatile Mode previousMode = Mode.NONE;
    private volatile String previousText = null;
    private volatile BufferedImage previousImage = null;

    // Scroll continuo
    private int scrollX = WIDTH;

    // WebSocket
    private final UtilsWS2 ws;

    // Archivo JSON para guardar la URL
    private static final String URL_FILE = "url.json";

    // Control post-group
    private long postGroupLastSwitchMs = 0L;
    private boolean postGroupShowUrl = true;
    private static final long POSTGROUP_SWITCH_MS = 5000L;
    private boolean postGroupTransitioning = false;
    private long postGroupTransitionStartMs = 0L;
    private static final long POSTGROUP_TRANSITION_MS = 600L;

    // Tiempo para animación del sistema solar
    private long animationStartTime = System.currentTimeMillis();

    // Control de desvanecimiento del marcador
    private float scoreAlpha = 1.0f;
    private long scoreAlphaLastUpdateMs = 0L;
    private static final long SCORE_FADE_DURATION_MS = 300L; // 300 ms

    // Explosiones de fondo para el juego
    private static final int MAX_EXPLOSIONS = 10;
    private final Explosion[] explosions = new Explosion[MAX_EXPLOSIONS];
    private final Random random = new Random();

    public Main2(String serverUri) {
        System.out.println("[client] Initializing connection to: " + serverUri);
        ws = UtilsWS2.getSharedInstance(serverUri);
        ws.onMessage(this::onWsMessage);
        ws.onOpen(this::onWsOpen);
        ws.onClose(this::onWsClose);
        System.out.println("[client] WebSocket handlers configured");

        // Inicializar explosiones
        for (int i = 0; i < MAX_EXPLOSIONS; i++) {
            explosions[i] = new Explosion();
        }

        // Inicializar modo POSTGROUP al inicio si hay URL guardada
        String savedUrl = loadUrlFromJson();
        if (!savedUrl.isEmpty()) {
            mode = Mode.POSTGROUP;
            previousText = savedUrl;
            postGroupLastSwitchMs = System.currentTimeMillis();
            postGroupShowUrl = true;
            expireAtMs = Long.MAX_VALUE; // Tiempo indefinido
            System.out.println("[client] Starting in POSTGROUP mode with saved URL");
        }

    }

    private void onWsOpen(String msg) {
        System.out.println("[client] Connected: " + msg);
        // Si no estamos en POSTGROUP, solicitar datos
        if (mode != Mode.POSTGROUP) {
            requestGroupNameFromServer();
            requestUrlFromServer();
        }
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
                        if (mode == Mode.POSTGROUP) {
                            // Guardar el estado actual de POSTGROUP
                            previousMode = Mode.POSTGROUP;
                            previousText = text;
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
                            // Después del group name, volver a POSTGROUP
                            mode = Mode.POSTGROUP;
                            previousText = url;
                            postGroupLastSwitchMs = System.currentTimeMillis();
                            postGroupShowUrl = true;
                            postGroupTransitioning = false;
                            expireAtMs = Long.MAX_VALUE;
                            System.out.println("[client] Returning to POSTGROUP after group name");
                        } else if (mode == Mode.NONE) {
                            // Si no hay modo activo, ir directamente a POSTGROUP
                            mode = Mode.POSTGROUP;
                            previousText = url;
                            postGroupLastSwitchMs = System.currentTimeMillis();
                            postGroupShowUrl = true;
                            postGroupTransitioning = false;
                            expireAtMs = Long.MAX_VALUE;
                            System.out.println("[client] Starting POSTGROUP with new URL");
                        } else if (mode == Mode.POSTGROUP) {
                            // Actualizar URL mientras estamos en POSTGROUP
                            previousText = url;
                            System.out.println("[client] Updated POSTGROUP URL");
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
                        expireAtMs = System.currentTimeMillis() + ((count + 1) * 1000L) + 4000L;
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

                    // Verificar si la partida ha terminado
                    if (score1 >= 10 || score2 >= 10) {
                        mode = Mode.RESULT;
                        expireAtMs = System.currentTimeMillis() + 10_000L;
                        System.out.println("[client] Game finished - showing RESULT for 10s");
                        return;
                    }

                    if (mode == Mode.COUNTDOWN) {
                        mode = Mode.WAITING_FOR_GAME;
                        System.out.println("[client] Game waiting for countdown to finish");
                    } else if (mode != Mode.GAME) {
                        mode = Mode.GAME;
                        expireAtMs = Long.MAX_VALUE;
                        System.out.println("[client] Game mode activated");
                    }
                }
                case "players" -> {
                    String p1 = o.optString("player1", "");
                    String p2 = o.optString("player2", "");
                    if (!p1.isEmpty())
                        player1Name = p1;
                    if (!p2.isEmpty())
                        player2Name = p2;
                    System.out.println("[client] Received player names: " + player1Name + ", " + player2Name);
                }
                case "playerNames" -> {
                    String p1 = o.optString("player1", "");
                    String p2 = o.optString("player2", "");
                    if (!p1.isEmpty())
                        player1Name = p1;
                    if (!p2.isEmpty())
                        player2Name = p2;
                    System.out.println(
                            "[client] Received player names (playerNames): " + player1Name + ", " + player2Name);
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

    private BufferedImage loadImage(String[] paths) {
        for (String path : paths) {
            try {
                File file = new File(path);
                if (file.exists()) {
                    BufferedImage img = ImageIO.read(file);
                    System.out.println("[client] Image loaded successfully from: " + file.getAbsolutePath());
                    return img;
                }
            } catch (Exception e) {
                System.err.println("[client] Error loading image from " + path + ": " + e.getMessage());
            }
        }
        System.err.println("[client] Could not find image in any of the expected locations");
        return null;
    }

    public void run() {
        PioMatter pm = null;
        PioMatter.FB fb = null;
        BufferedImage back = null;
        Graphics2D g = null;
        final UtilsFPS fps = new UtilsFPS();

        try {
            System.out.println("[client] Setting thread priority...");
            try {
                Thread.currentThread().setPriority(Math.min(Thread.MAX_PRIORITY - 1, Thread.NORM_PRIORITY + 2));
            } catch (Exception ignored) {
            }

            // Cargar imágenes para el sistema solar
            System.out.println("[client] Loading solar system images...");
            sunImage = loadImage(new String[] {
                    "resources/center.png", "center.png", "src/main/resources/center.png"
            });
            earthImage = loadImage(new String[] {
                    "resources/pastilla.png", "pastilla.png", "src/main/resources/pastilla.png"
            });
            moonImage = loadImage(new String[] {
                    "resources/medicina2.png", "medicina2.png", "src/main/resources/medicina2.png"
            });

            // Cargar imágenes del carrusel
            carouselImages[0] = loadImage(new String[] { "resources/qr.png", "qr.png", "src/main/resources/qr.png" });

            System.out.println("[client] Initializing PioMatter...");
            PioMatter pmLocal = new PioMatter(WIDTH, HEIGHT, ADDR, LANES, BRIGHTNESS, 0);
            pm = pmLocal;
            fb = pm.mapFramebuffer();
            System.out.println("[client] PioMatter initialized successfully");

            back = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            g = back.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            final Font font = new Font("SansSerif", Font.BOLD, 12);
            final Font countdownFont = new Font("SansSerif", Font.BOLD, 48);
            final Font scoreFont = new Font("SansSerif", Font.BOLD, 10);
            final Font resultNameFont = new Font("SansSerif", Font.BOLD, 10);
            final Font resultScoreFont = new Font("SansSerif", Font.BOLD, 12);
            PioMatter.flushBlack(pm, fb, 2, 10);

            while (true) {
                // fps.beginFrame();
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, WIDTH, HEIGHT);

                int startY = Math.max(0, RESERVED_TOP);
                boolean alive = System.currentTimeMillis() < expireAtMs;

                if (alive || mode == Mode.GAME || mode == Mode.POSTGROUP) {
                    if (mode == Mode.COUNTDOWN) {
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

                            float hue = currentCount / 3.0f * 0.33f;
                            g.setColor(Color.getHSBColor(hue, 1f, 1f));
                            g.drawString(countStr, x, y);
                        }

                    } else if (mode == Mode.WAITING_FOR_GAME) {
                        g.setColor(Color.BLACK);
                        g.fillRect(0, 0, WIDTH, HEIGHT);

                    } else if (mode == Mode.BALL) {
                        int ballSize = 20;
                        int ballX = (WIDTH - ballSize) / 2;
                        int ballY = (HEIGHT - ballSize) / 2;

                        g.setColor(Color.WHITE);
                        g.fillOval(ballX, ballY, ballSize, ballSize);

                    } else if (mode == Mode.GAME) {
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
                        if (xCursor < 0)
                            scrollX = WIDTH;

                    } else if (mode == Mode.START) {
                        // Carrusel 3D de imágenes
                        long currentTime = System.currentTimeMillis();

                        if (!isTransitioning && (currentTime - lastImageChangeMs) >= IMAGE_DISPLAY_TIME) {
                            isTransitioning = true;
                        }

                        if (isTransitioning) {
                            transitionProgress += TRANSITION_SPEED;
                            if (transitionProgress >= 1.0f) {
                                transitionProgress = 0f;
                                isTransitioning = false;
                                currentImageIndex = (currentImageIndex + 1) % carouselImages.length;
                                lastImageChangeMs = currentTime;
                            }
                        }

                        drawCarousel3D(g);
                    } else if (mode == Mode.RESULT) {
                        drawResultScreen(g, resultNameFont, resultScoreFont);
                    } else if (mode == Mode.POSTGROUP) {
                        long now = System.currentTimeMillis();

                        // Iniciar transición cuando toca cambiar entre URL y QR
                        if (!postGroupTransitioning && (now - postGroupLastSwitchMs) >= POSTGROUP_SWITCH_MS) {
                            postGroupTransitioning = true;
                            postGroupTransitionStartMs = now;
                        }

                        if (postGroupTransitioning) {
                            float progress = (now - postGroupTransitionStartMs) / (float) POSTGROUP_TRANSITION_MS;
                            if (progress >= 1.0f) {
                                progress = 1.0f;
                                postGroupTransitioning = false;
                                postGroupShowUrl = !postGroupShowUrl;
                                postGroupLastSwitchMs = now;
                                if (postGroupShowUrl) {
                                    scrollX = WIDTH;
                                }
                            }

                            float fromAlpha = 1.0f - progress;
                            float toAlpha = progress;

                            Composite oldComposite = g.getComposite();

                            if (postGroupShowUrl) {
                                // De URL -> QR
                                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fromAlpha));
                                drawPostGroupUrl(g, font, startY, now);
                                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, toAlpha));
                                drawPostGroupQr(g, font);
                            } else {
                                // De QR -> URL
                                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fromAlpha));
                                drawPostGroupQr(g, font);
                                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, toAlpha));
                                drawPostGroupUrl(g, font, startY, now);
                            }

                            g.setComposite(oldComposite);
                        } else {
                            if (postGroupShowUrl && previousText != null) {
                                drawPostGroupUrl(g, font, startY, now);
                            } else {
                                drawPostGroupQr(g, font);
                            }
                        }
                    }
                } else {
                    // Expirado - manejar transiciones
                    if (mode == Mode.COUNTDOWN || mode == Mode.WAITING_FOR_GAME) {
                        if (mode == Mode.WAITING_FOR_GAME) {
                            mode = Mode.GAME;
                            expireAtMs = Long.MAX_VALUE;
                            System.out.println("[client] Countdown finished, starting game");
                        } else {
                            mode = previousMode;
                            text = previousText;
                            image = previousImage;
                            expireAtMs = System.currentTimeMillis() + 3_600_000L;
                            scrollX = WIDTH;
                            previousMode = Mode.NONE;
                            previousText = null;
                            previousImage = null;
                        }
                    } else if (mode == Mode.RESULT) {
                        // Después del resultado, volver a POSTGROUP
                        String savedUrl = loadUrlFromJson();
                        if (!savedUrl.isEmpty()) {
                            mode = Mode.POSTGROUP;
                            previousText = savedUrl;
                            postGroupLastSwitchMs = System.currentTimeMillis();
                            postGroupShowUrl = true;
                            postGroupTransitioning = false;
                            expireAtMs = Long.MAX_VALUE;
                            System.out.println("[client] After RESULT -> POSTGROUP indefinitely");
                        } else {
                            mode = Mode.NONE;
                        }
                    } else {
                        mode = Mode.NONE;
                        text = null;
                        image = null;
                    }
                }

                // Overlay FPS
                // if (mode != Mode.GAME && mode != Mode.START && !(mode == Mode.POSTGROUP &&
                // !postGroupShowUrl)) {
                // fps.drawOverlay(g, 1, 9);

                // }

                // Indicador de conexión WS
                if (mode != Mode.START && !(mode == Mode.POSTGROUP && !postGroupShowUrl)) {
                    int iconSize = 3;
                    int iconX = WIDTH - iconSize - 1;
                    int iconY = 1;
                    g.setColor(ws.isOpen() ? Color.GREEN : Color.RED);
                    g.fillRect(iconX, iconY, iconSize, iconSize);
                }

                PioMatter.copyBufferedImageToRGB888(back, fb.data, fb.strideBytes, WIDTH, HEIGHT, BRIGHTNESS);
                pm.swap();
                // fps.endFrameAndCap(FPS_CAP);
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

    private void drawSolarSystem(Graphics2D g) {
        // Configurar composición
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f));

        // Centro del sistema solar
        int centerX = WIDTH / 2;
        int centerY = HEIGHT / 2;

        // Tiempo para animación
        long currentTime = System.currentTimeMillis() - animationStartTime;
        double seconds = currentTime / 1000.0;

        // Dibujar órbita
        g.setColor(new Color(0, 153, 255, 100));
        g.drawOval(centerX - 25, centerY - 25, 50, 50);

        // Rotación de la Tierra
        double earthAngle = (2 * Math.PI / 10) * seconds; // 10 segundos para órbita completa
        int earthX = centerX + (int) (25 * Math.cos(earthAngle));
        int earthY = centerY + (int) (25 * Math.sin(earthAngle));

        // Dibujar sombra de la Tierra
        g.setColor(new Color(0, 0, 0, 100));
        g.fillRect(earthX, earthY - 6, 12, 12);

        // Dibujar Tierra
        if (earthImage != null) {
            g.drawImage(earthImage, earthX - 6, earthY - 6, 12, 12, null);
        } else {
            g.setColor(Color.BLUE);
            g.fillOval(earthX - 6, earthY - 6, 12, 12);
        }

        // Rotación de la Luna alrededor de la Tierra
        double moonAngle = (2 * Math.PI / 3) * seconds; // 3 segundos para órbita lunar
        int moonX = earthX + (int) (15 * Math.cos(moonAngle));
        int moonY = earthY + (int) (15 * Math.sin(moonAngle));

        // Dibujar Luna
        if (moonImage != null) {
            g.drawImage(moonImage, moonX - 3, moonY - 3, 6, 6, null);
        } else {
            g.setColor(Color.LIGHT_GRAY);
            g.fillOval(moonX - 3, moonY - 3, 6, 6);
        }

        // Dibujar Sol en el centro
        if (sunImage != null) {
            g.drawImage(sunImage, centerX - 15, centerY - 15, 30, 30, null);
        } else {
            g.setColor(Color.YELLOW);
            g.fillOval(centerX - 15, centerY - 15, 30, 30);
        }

        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
    }

    // ----------------------------------------------------
    // Dibujado de explosiones
    // ----------------------------------------------------
    private void drawExplosions(Graphics2D g, long now) {
        for (Explosion e : explosions) {
            if (e == null || !e.active) {
                continue;
            }

            float t = (now - e.startTimeMs) / (float) e.durationMs;
            if (t >= 1.0f) {
                e.active = false;
                continue;
            }

            // Suavizamos la curva de crecimiento (ease-out)
            float eased = (float) Math.pow(t, 0.6);
            float radius = e.maxRadius * eased;

            // La explosión se va difuminando
            float alpha = 1.0f - t;

            Composite oldComposite = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

            int rOuter = Math.max(1, Math.round(radius));
            int rMiddle = Math.max(1, Math.round(radius * 0.7f));
            int rInner = Math.max(1, Math.round(radius * 0.4f));

            // Círculo exterior
            g.setColor(e.outerColor);
            g.fillOval(Math.round(e.x - rOuter), Math.round(e.y - rOuter), rOuter * 2, rOuter * 2);

            // Círculo medio
            g.setColor(e.middleColor);
            g.fillOval(Math.round(e.x - rMiddle), Math.round(e.y - rMiddle), rMiddle * 2, rMiddle * 2);

            // Núcleo interior
            g.setColor(e.innerColor);
            g.fillOval(Math.round(e.x - rInner), Math.round(e.y - rInner), rInner * 2, rInner * 2);

            // Chispas
            g.setColor(new Color(255, 255, 255, (int) (160 * alpha)));
            for (int i = 0; i < e.sparkCount; i++) {
                float angle = e.sparkAngle[i];
                float len = e.sparkLength[i] * eased;

                float sx = e.x + (float) Math.cos(angle) * (radius + 0.5f);
                float sy = e.y + (float) Math.sin(angle) * (radius + 0.5f);

                float ex = e.x + (float) Math.cos(angle) * (radius + len);
                float ey = e.y + (float) Math.sin(angle) * (radius + len);

                g.drawLine(Math.round(sx), Math.round(sy), Math.round(ex), Math.round(ey));
            }

            g.setComposite(oldComposite);
        }

        maybeSpawnExplosion(now);
    }

    // ----------------------------------------------------
    // Creación de nuevas explosiones
    // ----------------------------------------------------
    private void maybeSpawnExplosion(long now) {
        // Probabilidad baja por frame
        if (random.nextFloat() > 0.06f) {
            return;
        }

        for (Explosion e : explosions) {
            if (e != null && !e.active) {
                e.active = true;
                // Posición aleatoria en toda la matriz
                e.x = random.nextInt(WIDTH);
                e.y = random.nextInt(HEIGHT);

                // Tamaño pequeño, adaptado a 64x64
                e.maxRadius = 2.5f + random.nextFloat() * 3.5f; // ~2.5 a 6 px

                e.startTimeMs = now;
                e.durationMs = 350 + random.nextInt(450); // 350–800 ms

                // Paleta cálida
                e.innerColor = new Color(255, 255, 220); // casi blanco
                e.middleColor = new Color(255, 200, 80); // amarillo/naranja
                e.outerColor = new Color(255, 80 + random.nextInt(80), 20); // rojo/naranja

                // Chispas
                e.sparkCount = 4 + random.nextInt(4); // 4–7 chispas
                e.sparkAngle = new float[e.sparkCount];
                e.sparkLength = new float[e.sparkCount];
                for (int i = 0; i < e.sparkCount; i++) {
                    e.sparkAngle[i] = (float) (random.nextFloat() * Math.PI * 2.0);
                    e.sparkLength[i] = 1.0f + random.nextFloat() * 2.0f; // longitud extra
                }

                break;
            }
        }
    }

    // ----------------------------------------------------
    // Clase interna Explosion
    // ----------------------------------------------------
    private static class Explosion {
        float x;
        float y;
        float maxRadius; // radio máximo en píxeles
        long startTimeMs;
        long durationMs; // duración total de la explosión
        boolean active;

        // Colores base
        Color innerColor;
        Color middleColor;
        Color outerColor;

        // Pequeñas chispas
        int sparkCount;
        float[] sparkAngle;
        float[] sparkLength;
    }

    private void updateScoreAlpha(long now) {
        if (scoreAlphaLastUpdateMs == 0L) {
            scoreAlphaLastUpdateMs = now;
        }

        float clampedBallY = Math.max(0f, Math.min(1f, ballY));
        float targetAlpha = (clampedBallY <= 0.3f) ? 0f : 1f;

        long dt = now - scoreAlphaLastUpdateMs;
        if (dt <= 0L) {
            return;
        }

        float step = dt / (float) SCORE_FADE_DURATION_MS;
        if (step > 1.0f) {
            step = 1.0f;
        }

        if (scoreAlpha < targetAlpha) {
            scoreAlpha = Math.min(targetAlpha, scoreAlpha + step);
        } else if (scoreAlpha > targetAlpha) {
            scoreAlpha = Math.max(targetAlpha, scoreAlpha - step);
        }

        scoreAlphaLastUpdateMs = now;
    }

    private void drawPongGame(Graphics2D g, Font scoreFont) {
        final int paddleWidth = 2;
        final int paddleHeight = 13; // reducido 2 píxeles
        final int ballSize = 3;
        final int playAreaTop = 0;
        final int playAreaHeight = HEIGHT;

        long now = System.currentTimeMillis();

        // Fondo con animación del sistema solar
        drawSolarSystem(g);

        // Explosiones de fondo entre la órbita y la pelota/palas
        drawExplosions(g, now);

        // Superposición oscura para mejor contraste
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // Línea central de la pista
        g.setColor(new Color(80, 80, 80));
        int centerX = WIDTH / 2;
        for (int y = playAreaTop; y < HEIGHT; y += 4) {
            g.drawLine(centerX, y, centerX, y + 2);
        }

        // Palas
        int paddle1YPixel = (int) (playAreaTop + paddle1Y * playAreaHeight);
        g.setColor(Color.WHITE);
        g.fillRect(0, paddle1YPixel - paddleHeight / 2, paddleWidth, paddleHeight);

        int paddle2YPixel = (int) (playAreaTop + paddle2Y * playAreaHeight);
        g.fillRect(WIDTH - paddleWidth, paddle2YPixel - paddleHeight / 2, paddleWidth, paddleHeight);

        // Pelota
        int ballXPixel = (int) (ballX * WIDTH);
        int ballYPixel = (int) (playAreaTop + ballY * playAreaHeight);

        // Actualizar desvanecimiento del marcador (30% superior de la pantalla)
        updateScoreAlpha(now);

        // Marcador
        g.setFont(scoreFont);
        FontMetrics fm = g.getFontMetrics();
        String scoreText = score1 + " - " + score2;
        int textWidth = fm.stringWidth(scoreText);
        int textX = (WIDTH - textWidth) / 2;
        int textY = 8;

        if (scoreAlpha > 0f) {
            Composite oldComposite = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, scoreAlpha));

            // Fondo semitransparente para el marcador
            g.setColor(new Color(0, 0, 0, 150));
            g.fillRect(textX - 2, textY - fm.getAscent() - 2, textWidth + 4, fm.getHeight() + 4);

            // Texto del marcador
            g.setColor(Color.WHITE);
            g.drawString(scoreText, textX, textY);

            g.setComposite(oldComposite);
        }

        // Pelota
        g.setColor(Color.WHITE);
        g.fillOval(ballXPixel - ballSize / 2, ballYPixel - ballSize / 2, ballSize, ballSize);
    }

    private void drawCarousel3D(Graphics2D g) {
        BufferedImage currentImg = carouselImages[currentImageIndex];
        BufferedImage nextImg = carouselImages[(currentImageIndex + 1) % carouselImages.length];

        if (currentImg == null && nextImg == null) {
            return;
        }

        if (!isTransitioning) {
            if (currentImg != null) {
                g.drawImage(currentImg, 0, 0, WIDTH, HEIGHT, null);
            }
        } else {
            float angle = transitionProgress * (float) Math.PI / 2;
            float currentWidth = WIDTH * (float) Math.cos(angle);
            float nextWidth = WIDTH * (float) Math.sin(angle);
            float currentBrightness = (float) Math.cos(angle);
            float nextBrightness = (float) Math.sin(angle);

            if (currentImg != null && currentWidth > 0) {
                int drawWidth = (int) Math.abs(currentWidth);
                int xOffset = (WIDTH - drawWidth) / 2;

                BufferedImage scaledCurrent = new BufferedImage(drawWidth, HEIGHT, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2 = scaledCurrent.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(currentImg, 0, 0, drawWidth, HEIGHT, null);

                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f - currentBrightness * 0.5f));
                g2.setColor(Color.BLACK);
                g2.fillRect(0, 0, drawWidth, HEIGHT);
                g2.dispose();

                g.drawImage(scaledCurrent, xOffset, 0, null);
            }

            if (nextImg != null && nextWidth > 0) {
                int drawWidth = (int) Math.abs(nextWidth);
                int xOffset = WIDTH - drawWidth;

                BufferedImage scaledNext = new BufferedImage(drawWidth, HEIGHT, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2 = scaledNext.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

                int srcWidth = currentImg != null ? currentImg.getWidth() : nextImg.getWidth();
                int srcHeight = currentImg != null ? currentImg.getHeight() : nextImg.getHeight();
                g2.drawImage(nextImg, 0, 0, drawWidth, HEIGHT,
                        srcWidth - (int) (srcWidth * nextBrightness), 0,
                        srcWidth, srcHeight, null);

                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f - nextBrightness * 0.5f));
                g2.setColor(Color.BLACK);
                g2.fillRect(0, 0, drawWidth, HEIGHT);
                g2.dispose();

                g.drawImage(scaledNext, xOffset, 0, null);
            }
        }
    }

    private void drawResultScreen(Graphics2D g, Font nameFont, Font scoreFont) {
        if (sunImage != null) {
            g.drawImage(sunImage, 0, 0, WIDTH, HEIGHT, null);
            g.setColor(new Color(0, 0, 0, 120));
            g.fillRect(0, 0, WIDTH, HEIGHT);
        } else {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, WIDTH, HEIGHT);
        }

        g.setFont(nameFont);
        FontMetrics fmName = g.getFontMetrics();
        g.setFont(scoreFont);
        FontMetrics fmScore = g.getFontMetrics();

        int leftX = 4;
        int rightX = WIDTH / 2 + 2;

        // Primer jugador arriba
        int firstNameY = 15;
        int firstScoreY = 30;

        // Segundo jugador abajo (pero dentro de los 64px)
        int secondNameY = 40;
        int secondScoreY = 55;

        String firstName = player1Name;
        int firstScore = score1;

        String secondName = player2Name;
        int secondScore = score2;

        Color firstColor, secondColor;
        if (score1 > score2) {
            firstColor = new Color(0, 255, 0);
            secondColor = new Color(255, 64, 64);
        } else if (score2 > score1) {
            firstColor = new Color(255, 64, 64);
            secondColor = new Color(0, 255, 0);
        } else {
            // Empate: ambos en blanco
            firstColor = Color.WHITE;
            secondColor = Color.WHITE;
        }

        // Dibujar primer jugador (arriba)
        g.setFont(nameFont);
        g.setColor(firstColor);
        g.drawString(firstName, leftX, firstNameY);
        g.setFont(scoreFont);
        g.drawString(String.valueOf(firstScore), leftX, firstScoreY);

        // Dibujar segundo jugador (abajo)
        g.setFont(nameFont);
        g.setColor(secondColor);
        g.drawString(secondName, leftX, secondNameY);
        g.setFont(scoreFont);
        g.drawString(String.valueOf(secondScore), leftX, secondScoreY);
    }

    private void drawPostGroupUrl(Graphics2D g, Font font, int startY, long now) {
        if (previousText == null) {
            return;
        }

        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();

        int xCursor = scrollX;
        String urlTxt = previousText;
        for (int i = 0; i < urlTxt.length(); i++) {
            char c = urlTxt.charAt(i);
            int cw = fm.charWidth(c);

            if (xCursor + cw < 0) {
                xCursor += cw;
                continue;
            }
            if (xCursor >= WIDTH) {
                break;
            }

            float hue = (float) (now % 5000L) / 5000f + (float) i / urlTxt.length() * 0.3f;
            g.setColor(Color.getHSBColor(hue, 1f, 1f));
            g.drawString(String.valueOf(c), xCursor, startY + fm.getAscent());
            xCursor += cw;
        }

        scrollX -= 1;
        if (xCursor < 0) {
            scrollX = WIDTH;
        }
    }

    private void drawPostGroupQr(Graphics2D g, Font font) {
        BufferedImage qr = carouselImages[0];
        if (qr != null) {
            g.drawImage(qr, 0, 0, WIDTH, HEIGHT, null);
        } else {
            g.setFont(font);
            g.setColor(Color.WHITE);
            g.drawString("QR", WIDTH / 2 - 6, HEIGHT / 2);
        }
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