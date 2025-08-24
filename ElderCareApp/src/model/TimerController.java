package model;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;

/**
 * Canvas-based circular timer view with integrated clock-like background.
 *
 * update(progress, remainingSeconds, alertMode)
 *   - progress: 0.0 .. 1.0
 *   - remainingSeconds: seconds to show as HH:mm:ss
 *   - alertMode: when true, draw red progress ring and red glow
 */
public class TimerController extends StackPane {
    private final Canvas canvas;
    private final GraphicsContext gc;

    private double width = 340;
    private double height = 340;

    public TimerController() {
        canvas = new Canvas(width, height);
        gc = canvas.getGraphicsContext2D();
        this.getChildren().add(canvas);

        draw(0.0, 0, false);
    }

    public void setCanvasSize(double w, double h) {
        this.width = w;
        this.height = h;
        canvas.setWidth(w);
        canvas.setHeight(h);
        draw(0.0, 0, false);
    }

    /**
     * Update drawing.
     * @param progress 0..1
     * @param remainingSeconds seconds left
     * @param alertMode true => red (intake window); false => blue (pre-dose)
     */
    public void update(double progress, long remainingSeconds, boolean alertMode) {
        draw(progress, remainingSeconds, alertMode);
    }

    private void draw(double progress, long remainingSeconds, boolean alertMode) {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            w = width;
            h = height;
            canvas.setWidth(w);
            canvas.setHeight(h);
        }
        double centerX = w / 2.0;
        double centerY = h / 2.0;
        double radius = Math.min(w, h) * 0.38;

        // clear
        gc.clearRect(0, 0, w, h);

        // ===== Clock Face Background =====
        RadialGradient gradient = new RadialGradient(
                0, 0, centerX, centerY, radius * 1.2, false,
                CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#141424")),
                new Stop(1, Color.web("#0d0d1a"))
        );
        gc.setFill(gradient);
        gc.fillOval(centerX - radius * 1.2, centerY - radius * 1.2,
                radius * 2.4, radius * 2.4);

        // tick marks (like a clock)
        gc.setStroke(Color.web("#2a2a40"));
        gc.setLineWidth(2);
        for (int i = 0; i < 60; i++) {
            double angle = Math.toRadians(i * 6);
            double inner = radius * 0.82;
            double outer = radius * 0.9;
            if (i % 5 == 0) inner = radius * 0.75;
            double x1 = centerX + inner * Math.cos(angle);
            double y1 = centerY - inner * Math.sin(angle);
            double x2 = centerX + outer * Math.cos(angle);
            double y2 = centerY - outer * Math.sin(angle);
            gc.strokeLine(x1, y1, x2, y2);
        }

        // ===== Background Ring =====
        gc.setStroke(Color.web("#1c1c2b"));
        gc.setLineWidth(Math.max(8, radius * 0.12));
        gc.strokeOval(centerX - radius, centerY - radius, radius * 2, radius * 2);

        // ===== Progress Arc =====
        double angle = 360.0 * Math.max(0.0, Math.min(1.0, progress));
        Color strokeColor = alertMode ? Color.web("#ff3b30") : Color.web("#1e90ff");
        gc.setStroke(strokeColor);
        gc.setLineWidth(Math.max(8, radius * 0.12));
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setEffect(new DropShadow(15, strokeColor.deriveColor(0,1,1,0.5))); // subtle glow
        gc.strokeArc(centerX - radius, centerY - radius, radius * 2, radius * 2,
                90, -angle, ArcType.OPEN);
        gc.setEffect(null);

        // ===== Time Text =====
        String time = formatTime(remainingSeconds);
        gc.setFill(Color.web("#dfe6f1"));
        double fontSize = Math.max(20, radius * 0.36);
        gc.setFont(Font.font("Consolas", fontSize));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(time, centerX, centerY + fontSize * 0.28);
    }

    private String formatTime(long seconds) {
        if (seconds <= 0) return "00:00:00";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
