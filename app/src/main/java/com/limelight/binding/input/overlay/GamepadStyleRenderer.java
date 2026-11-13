package com.limelight.binding.input.overlay;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * Draws gamepad controls in the {@link ButtonStyle#XBOX}, {@link ButtonStyle#PLAYSTATION} and
 * {@link ButtonStyle#SWITCH} styles. All drawing is vector based so it scales to any size.
 */
public class GamepadStyleRenderer {
    // Shared palette
    private static final int BODY = 0x1C1C20;
    private static final int OUTLINE = 0xE8E8EC;
    private static final int GLYPH = 0xF4F4F6;
    private static final int PRESSED_GLYPH = 0x16161A;

    // Xbox face colors
    private static final int XBOX_A = 0x6CC24A;
    private static final int XBOX_B = 0xE5483D;
    private static final int XBOX_X = 0x3D8FE0;
    private static final int XBOX_Y = 0xF5C518;
    private static final int XBOX_ACCENT = 0x52B043;

    // PlayStation face colors
    private static final int PS_CROSS = 0x82A8EC;
    private static final int PS_CIRCLE = 0xEF6F75;
    private static final int PS_SQUARE = 0xE184C4;
    private static final int PS_TRIANGLE = 0x42C6A2;
    private static final int PS_ACCENT = 0x5B86F2;

    // Switch
    private static final int SWITCH_ACCENT = 0xE8394A;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public GamepadStyleRenderer() {
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    private static int argb(int alpha, int rgb) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
    }

    private static int accent(ButtonStyle style) {
        switch (style) {
            case PLAYSTATION:
                return PS_ACCENT;
            case SWITCH:
                return SWITCH_ACCENT;
            default:
                return XBOX_ACCENT;
        }
    }

    // -------------------------------------------------------------------------------------
    // Buttons
    // -------------------------------------------------------------------------------------

    /**
     * @param alpha global overlay opacity, 0..255
     */
    public void drawButton(Canvas canvas, int width, int height, ButtonStyle style,
                           GamepadButton button, boolean pressed, int alpha) {
        float strokeWidth = Math.max(2f, Math.min(width, height) * 0.05f);
        stroke.setStrokeWidth(strokeWidth);
        float inset = strokeWidth / 2f + 1f;
        rect.set(inset, inset, width - inset, height - inset);

        switch (button) {
            case FACE_A:
            case FACE_B:
            case FACE_X:
            case FACE_Y:
                drawFaceButton(canvas, style, button, pressed, alpha);
                return;
            default:
                break;
        }

        int glyphColor = pressed ? PRESSED_GLYPH : GLYPH;
        fill.setColor(pressed ? argb(alpha * 9 / 10, accentFor(style, button)) : argb(alpha * 55 / 100, BODY));
        stroke.setColor(argb(alpha * 9 / 10, OUTLINE));

        float h = rect.height();
        switch (button) {
            case LB:
            case RB:
                drawRoundRect(canvas, style == ButtonStyle.SWITCH ? h / 2f : h * 0.3f);
                drawLabel(canvas, shoulderLabel(style, button), glyphColor, alpha, 0.42f);
                break;
            case LT:
            case RT:
                drawTriggerShape(canvas);
                drawLabel(canvas, shoulderLabel(style, button), glyphColor, alpha, 0.42f);
                break;
            case GUIDE:
                drawPill(canvas);
                drawHomeGlyph(canvas, glyphColor, alpha);
                break;
            case BACK:
                drawPill(canvas);
                if (style == ButtonStyle.SWITCH) {
                    drawMinusGlyph(canvas, glyphColor, alpha);
                } else if (style == ButtonStyle.XBOX) {
                    drawViewGlyph(canvas, glyphColor, alpha);
                } else {
                    drawLabel(canvas, "CREATE", glyphColor, alpha, 0.34f);
                }
                break;
            case START:
                drawPill(canvas);
                if (style == ButtonStyle.SWITCH) {
                    drawPlusGlyph(canvas, glyphColor, alpha);
                } else if (style == ButtonStyle.XBOX) {
                    drawMenuGlyph(canvas, glyphColor, alpha);
                } else {
                    drawLabel(canvas, "OPTIONS", glyphColor, alpha, 0.34f);
                }
                break;
            case L3:
            case R3:
                drawPill(canvas);
                drawLabel(canvas, stickClickLabel(style, button), glyphColor, alpha, 0.42f);
                break;
            case TOUCHPAD:
                drawRoundRect(canvas, h * 0.2f);
                drawLabel(canvas, "TOUCHPAD", glyphColor, alpha, 0.28f);
                break;
            default:
                break;
        }
    }

    private int accentFor(ButtonStyle style, GamepadButton button) {
        return accent(style);
    }

    /** Name of a button as printed on a controller of the given style. */
    public static String buttonName(ButtonStyle style, GamepadButton button) {
        switch (button) {
            case FACE_A:
                return style == ButtonStyle.PLAYSTATION ? "Cross" : style == ButtonStyle.SWITCH ? "B" : "A";
            case FACE_B:
                return style == ButtonStyle.PLAYSTATION ? "Circle" : style == ButtonStyle.SWITCH ? "A" : "B";
            case FACE_X:
                return style == ButtonStyle.PLAYSTATION ? "Square" : style == ButtonStyle.SWITCH ? "Y" : "X";
            case FACE_Y:
                return style == ButtonStyle.PLAYSTATION ? "Triangle" : style == ButtonStyle.SWITCH ? "X" : "Y";
            case LB:
            case RB:
            case LT:
            case RT:
                return shoulderLabel(style, button);
            case BACK:
                return style == ButtonStyle.PLAYSTATION ? "Create" : style == ButtonStyle.SWITCH ? "Minus" : "View";
            case START:
                return style == ButtonStyle.PLAYSTATION ? "Options" : style == ButtonStyle.SWITCH ? "Plus" : "Menu";
            case GUIDE:
                return "Home";
            case L3:
            case R3:
                return stickClickLabel(style, button);
            default:
                return "Touchpad";
        }
    }

    private static String shoulderLabel(ButtonStyle style, GamepadButton button) {
        switch (style) {
            case PLAYSTATION:
                switch (button) {
                    case LB: return "L1";
                    case RB: return "R1";
                    case LT: return "L2";
                    default: return "R2";
                }
            case SWITCH:
                switch (button) {
                    case LB: return "L";
                    case RB: return "R";
                    case LT: return "ZL";
                    default: return "ZR";
                }
            default:
                switch (button) {
                    case LB: return "LB";
                    case RB: return "RB";
                    case LT: return "LT";
                    default: return "RT";
                }
        }
    }

    private static String stickClickLabel(ButtonStyle style, GamepadButton button) {
        boolean left = button == GamepadButton.L3;
        if (style == ButtonStyle.PLAYSTATION) {
            return left ? "L3" : "R3";
        }
        return left ? "LS" : "RS";
    }

    private void drawFaceButton(Canvas canvas, ButtonStyle style, GamepadButton button,
                                boolean pressed, int alpha) {
        float cx = rect.centerX();
        float cy = rect.centerY();
        float r = Math.min(rect.width(), rect.height()) / 2f;

        int faceColor;
        switch (style) {
            case PLAYSTATION:
                faceColor = button == GamepadButton.FACE_A ? PS_CROSS
                        : button == GamepadButton.FACE_B ? PS_CIRCLE
                        : button == GamepadButton.FACE_X ? PS_SQUARE : PS_TRIANGLE;
                break;
            case SWITCH:
                faceColor = GLYPH;
                break;
            default:
                faceColor = button == GamepadButton.FACE_A ? XBOX_A
                        : button == GamepadButton.FACE_B ? XBOX_B
                        : button == GamepadButton.FACE_X ? XBOX_X : XBOX_Y;
                break;
        }

        // Body
        fill.setColor(pressed ? argb(alpha * 9 / 10, style == ButtonStyle.SWITCH ? SWITCH_ACCENT : faceColor)
                : argb(alpha * 55 / 100, BODY));
        canvas.drawCircle(cx, cy, r, fill);
        stroke.setColor(argb(alpha * 9 / 10, style == ButtonStyle.SWITCH ? OUTLINE : faceColor));
        canvas.drawCircle(cx, cy, r, stroke);

        int glyphColor = pressed ? PRESSED_GLYPH : faceColor;
        if (style == ButtonStyle.PLAYSTATION) {
            drawPlayStationSymbol(canvas, button, cx, cy, r, glyphColor, alpha);
        } else {
            String label;
            if (style == ButtonStyle.SWITCH) {
                // Nintendo labels by position: bottom B, right A, left Y, top X
                label = button == GamepadButton.FACE_A ? "B"
                        : button == GamepadButton.FACE_B ? "A"
                        : button == GamepadButton.FACE_X ? "Y" : "X";
                if (pressed) {
                    glyphColor = GLYPH;
                }
            } else {
                label = button == GamepadButton.FACE_A ? "A"
                        : button == GamepadButton.FACE_B ? "B"
                        : button == GamepadButton.FACE_X ? "X" : "Y";
            }
            text.setColor(argb(alpha, glyphColor));
            text.setTextSize(r * 1.05f);
            drawCenteredText(canvas, label, cx, cy);
        }
    }

    private void drawPlayStationSymbol(Canvas canvas, GamepadButton button, float cx, float cy,
                                       float r, int color, int alpha) {
        float s = r * 0.52f;
        Paint p = new Paint(stroke);
        p.setColor(argb(alpha, color));
        p.setStrokeWidth(Math.max(2f, r * 0.13f));
        switch (button) {
            case FACE_A: // cross
                canvas.drawLine(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, p);
                canvas.drawLine(cx - s / 2, cy + s / 2, cx + s / 2, cy - s / 2, p);
                break;
            case FACE_B: // circle
                canvas.drawCircle(cx, cy, s / 2f, p);
                break;
            case FACE_X: // square
                canvas.drawRect(cx - s * 0.45f, cy - s * 0.45f, cx + s * 0.45f, cy + s * 0.45f, p);
                break;
            default: // triangle
                path.reset();
                float th = s * 0.95f;
                path.moveTo(cx, cy - th * 0.62f);
                path.lineTo(cx + th * 0.58f, cy + th * 0.4f);
                path.lineTo(cx - th * 0.58f, cy + th * 0.4f);
                path.close();
                canvas.drawPath(path, p);
                break;
        }
    }

    private void drawRoundRect(Canvas canvas, float radius) {
        canvas.drawRoundRect(rect, radius, radius, fill);
        canvas.drawRoundRect(rect, radius, radius, stroke);
    }

    private void drawPill(Canvas canvas) {
        float radius = Math.min(rect.width(), rect.height()) / 2f;
        drawRoundRect(canvas, radius);
    }

    private void drawTriggerShape(Canvas canvas) {
        float big = Math.min(rect.height() * 0.55f, rect.width() / 2f);
        float small = rect.height() * 0.15f;
        float[] radii = {big, big, big, big, small, small, small, small};
        path.reset();
        path.addRoundRect(rect, radii, Path.Direction.CW);
        canvas.drawPath(path, fill);
        canvas.drawPath(path, stroke);
    }

    private void drawLabel(Canvas canvas, String label, int color, int alpha, float sizeOfHeight) {
        text.setColor(argb(alpha, color));
        text.setTextSize(rect.height() * sizeOfHeight);
        // Shrink long labels so they fit horizontally
        float maxWidth = rect.width() * 0.8f;
        float measured = text.measureText(label);
        if (measured > maxWidth) {
            text.setTextSize(text.getTextSize() * maxWidth / measured);
        }
        drawCenteredText(canvas, label, rect.centerX(), rect.centerY());
    }

    private void drawCenteredText(Canvas canvas, String label, float cx, float cy) {
        Paint.FontMetrics fm = text.getFontMetrics();
        canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, text);
    }

    private Paint glyphPaint(int color, int alpha) {
        Paint p = new Paint(stroke);
        p.setColor(argb(alpha, color));
        p.setStrokeWidth(Math.max(2f, rect.height() * 0.08f));
        return p;
    }

    private float glyphSize() {
        return Math.min(rect.width(), rect.height()) * 0.5f;
    }

    private void drawMinusGlyph(Canvas canvas, int color, int alpha) {
        Paint p = glyphPaint(color, alpha);
        float s = glyphSize() / 2f;
        canvas.drawLine(rect.centerX() - s, rect.centerY(), rect.centerX() + s, rect.centerY(), p);
    }

    private void drawPlusGlyph(Canvas canvas, int color, int alpha) {
        Paint p = glyphPaint(color, alpha);
        float s = glyphSize() / 2f;
        canvas.drawLine(rect.centerX() - s, rect.centerY(), rect.centerX() + s, rect.centerY(), p);
        canvas.drawLine(rect.centerX(), rect.centerY() - s, rect.centerX(), rect.centerY() + s, p);
    }

    private void drawMenuGlyph(Canvas canvas, int color, int alpha) {
        Paint p = glyphPaint(color, alpha);
        float s = glyphSize() / 2f;
        float cx = rect.centerX();
        float cy = rect.centerY();
        for (int i = -1; i <= 1; i++) {
            float y = cy + i * s * 0.6f;
            canvas.drawLine(cx - s, y, cx + s, y, p);
        }
    }

    private void drawViewGlyph(Canvas canvas, int color, int alpha) {
        Paint p = glyphPaint(color, alpha);
        p.setStrokeWidth(p.getStrokeWidth() * 0.8f);
        float s = glyphSize() * 0.62f;
        float cx = rect.centerX();
        float cy = rect.centerY();
        float off = s * 0.28f;
        RectF back = new RectF(cx - s / 2 - off, cy - s / 2 - off, cx + s / 2 - off, cy + s / 2 - off);
        RectF front = new RectF(cx - s / 2 + off, cy - s / 2 + off, cx + s / 2 + off, cy + s / 2 + off);
        float radius = s * 0.18f;
        canvas.drawRoundRect(back, radius, radius, p);
        Paint f = new Paint(fill);
        canvas.drawRoundRect(front, radius, radius, f);
        canvas.drawRoundRect(front, radius, radius, p);
    }

    private void drawHomeGlyph(Canvas canvas, int color, int alpha) {
        Paint p = glyphPaint(color, alpha);
        p.setStrokeWidth(p.getStrokeWidth() * 0.8f);
        float s = glyphSize() * 0.75f;
        float cx = rect.centerX();
        float cy = rect.centerY();
        path.reset();
        path.moveTo(cx - s / 2, cy - s * 0.02f);
        path.lineTo(cx, cy - s / 2);
        path.lineTo(cx + s / 2, cy - s * 0.02f);
        path.moveTo(cx - s * 0.36f, cy - s * 0.14f);
        path.lineTo(cx - s * 0.36f, cy + s / 2);
        path.lineTo(cx + s * 0.36f, cy + s / 2);
        path.lineTo(cx + s * 0.36f, cy - s * 0.14f);
        canvas.drawPath(path, p);
    }

    // -------------------------------------------------------------------------------------
    // D-pad
    // -------------------------------------------------------------------------------------

    public static final int DIR_LEFT = 1;
    public static final int DIR_UP = 2;
    public static final int DIR_RIGHT = 4;
    public static final int DIR_DOWN = 8;

    public void drawDpad(Canvas canvas, int width, int height, ButtonStyle style, int direction, int alpha) {
        float size = Math.min(width, height);
        float strokeWidth = Math.max(2f, size * 0.025f);
        stroke.setStrokeWidth(strokeWidth);
        float cx = width / 2f;
        float cy = height / 2f;
        float half = size / 2f - strokeWidth;
        float arm = size * 0.17f; // half width of an arm
        float radius = arm * 0.35f;

        // Cross body
        path.reset();
        rect.set(cx - arm, cy - half, cx + arm, cy + half);
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        rect.set(cx - half, cy - arm, cx + half, cy + arm);
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        path.setFillType(Path.FillType.WINDING);
        fill.setColor(argb(alpha * 55 / 100, BODY));
        canvas.drawPath(path, fill);

        // Pressed arms
        fill.setColor(argb(alpha * 9 / 10, accent(style)));
        if ((direction & DIR_UP) != 0) {
            rect.set(cx - arm, cy - half, cx + arm, cy);
            canvas.drawRoundRect(rect, radius, radius, fill);
        }
        if ((direction & DIR_DOWN) != 0) {
            rect.set(cx - arm, cy, cx + arm, cy + half);
            canvas.drawRoundRect(rect, radius, radius, fill);
        }
        if ((direction & DIR_LEFT) != 0) {
            rect.set(cx - half, cy - arm, cx, cy + arm);
            canvas.drawRoundRect(rect, radius, radius, fill);
        }
        if ((direction & DIR_RIGHT) != 0) {
            rect.set(cx, cy - arm, cx + half, cy + arm);
            canvas.drawRoundRect(rect, radius, radius, fill);
        }

        // Outline (union of both bars)
        Path outline = new Path();
        outline.moveTo(cx - arm, cy - half);
        outline.lineTo(cx + arm, cy - half);
        outline.lineTo(cx + arm, cy - arm);
        outline.lineTo(cx + half, cy - arm);
        outline.lineTo(cx + half, cy + arm);
        outline.lineTo(cx + arm, cy + arm);
        outline.lineTo(cx + arm, cy + half);
        outline.lineTo(cx - arm, cy + half);
        outline.lineTo(cx - arm, cy + arm);
        outline.lineTo(cx - half, cy + arm);
        outline.lineTo(cx - half, cy - arm);
        outline.lineTo(cx - arm, cy - arm);
        outline.close();
        stroke.setColor(argb(alpha * 9 / 10, OUTLINE));
        canvas.drawPath(outline, stroke);

        // Arrows
        float a = arm * 0.55f;
        float d = half - arm * 0.9f;
        drawArrow(canvas, cx, cy - d, 0, a, (direction & DIR_UP) != 0, alpha);
        drawArrow(canvas, cx + d, cy, 90, a, (direction & DIR_RIGHT) != 0, alpha);
        drawArrow(canvas, cx, cy + d, 180, a, (direction & DIR_DOWN) != 0, alpha);
        drawArrow(canvas, cx - d, cy, 270, a, (direction & DIR_LEFT) != 0, alpha);
    }

    private void drawArrow(Canvas canvas, float x, float y, float degrees, float size, boolean pressed, int alpha) {
        canvas.save();
        canvas.rotate(degrees, x, y);
        path.reset();
        path.moveTo(x, y - size * 0.6f);
        path.lineTo(x + size * 0.6f, y + size * 0.35f);
        path.lineTo(x - size * 0.6f, y + size * 0.35f);
        path.close();
        fill.setColor(argb(alpha, pressed ? PRESSED_GLYPH : GLYPH));
        canvas.drawPath(path, fill);
        canvas.restore();
    }

    // -------------------------------------------------------------------------------------
    // Analog stick
    // -------------------------------------------------------------------------------------

    public void drawStick(Canvas canvas, ButtonStyle style, float centerX, float centerY, float outerRadius,
                          float knobX, float knobY, float knobRadius, boolean active, int alpha) {
        float strokeWidth = Math.max(2f, outerRadius * 0.05f);
        stroke.setStrokeWidth(strokeWidth);

        // Base
        fill.setColor(argb(alpha * 45 / 100, BODY));
        canvas.drawCircle(centerX, centerY, outerRadius, fill);
        stroke.setColor(argb(alpha * 9 / 10, OUTLINE));
        canvas.drawCircle(centerX, centerY, outerRadius, stroke);

        // Knob
        float knob = knobRadius * 1.6f;
        fill.setColor(active ? argb(alpha * 9 / 10, accent(style)) : argb(alpha * 75 / 100, 0x3A3A40));
        canvas.drawCircle(knobX, knobY, knob, fill);
        canvas.drawCircle(knobX, knobY, knob, stroke);

        // Grip ring (concave PlayStation stick, ring on the others)
        Paint ring = new Paint(stroke);
        ring.setStrokeWidth(strokeWidth * 0.7f);
        ring.setColor(argb(alpha * 6 / 10, active ? PRESSED_GLYPH : OUTLINE));
        canvas.drawCircle(knobX, knobY, knob * (style == ButtonStyle.PLAYSTATION ? 0.45f : 0.68f), ring);
    }
}
