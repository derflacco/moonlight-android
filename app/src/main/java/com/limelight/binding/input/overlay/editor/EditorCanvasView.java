package com.limelight.binding.input.overlay.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

import com.limelight.binding.input.overlay.EditableElement;
import com.limelight.binding.input.overlay.ElementEditState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Transparent surface laid over the overlay elements while editing. It owns every touch:
 * tap to select, drag to move (with alignment guides / grid snapping), drag a corner handle
 * or pinch to resize.
 */
public class EditorCanvasView extends View {

    public interface Callbacks {
        void onSelectionChanged(EditableElement element);

        /** A move / resize gesture is about to modify the selected element. */
        void onGestureStarted();

        /** The selected element changed during a gesture. */
        void onGestureUpdated();

        void onGestureFinished();
    }

    private static final int ACCENT = 0xFF3D7EFF;
    private static final int GUIDE = 0xFFFF4FC3;
    private static final int HIDDEN_OUTLINE = 0xCCFFB74D;
    private static final int OUTLINE = 0x66FFFFFF;
    private static final int GRID = 0x1FFFFFFF;
    private static final int GRID_CENTER = 0x40FFFFFF;

    private enum Mode {NONE, PENDING, MOVE, RESIZE, PINCH}

    private List<? extends EditableElement> elements = Collections.emptyList();
    private EditableElement selected;
    private Callbacks callbacks;
    private boolean snapEnabled = true;
    private boolean gridEnabled = false;
    private String hint;
    private float hintY = -1;

    private final float dp;
    private final float handleRadius;
    private final float handleTouchRadius;
    private final float snapThreshold;
    private final float minTouchTarget;
    private final int touchSlop;

    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint();
    private final Paint tagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tagBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRectF = new RectF();

    // Gesture state
    private Mode mode = Mode.NONE;
    private float downX, downY;
    private int activeCorner = -1;
    private Rect startBounds;
    private EditableElement downHit;
    private boolean gestureStarted;
    private float pinchScale = 1f;
    private final List<Float> guidesX = new ArrayList<>();
    private final List<Float> guidesY = new ArrayList<>();
    private boolean wasSnappedX, wasSnappedY;

    private final ScaleGestureDetector scaleDetector;

    public EditorCanvasView(Context context) {
        this(context, null);
    }

    public EditorCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        handleRadius = 7 * dp;
        handleTouchRadius = 26 * dp;
        snapThreshold = 8 * dp;
        minTouchTarget = 44 * dp;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(1.2f * dp);
        outlinePaint.setPathEffect(new DashPathEffect(new float[]{5 * dp, 4 * dp}, 0));

        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(2 * dp);
        selectionPaint.setColor(ACCENT);

        handleFill.setStyle(Paint.Style.FILL);
        handleFill.setColor(0xFFFFFFFF);

        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(1 * dp);
        guidePaint.setColor(GUIDE);

        gridPaint.setStrokeWidth(1);

        tagPaint.setColor(0xFFFFFFFF);
        tagPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics()));
        tagPaint.setTextAlign(Paint.Align.CENTER);
        tagBgPaint.setColor(0xE61C1D22);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (mode == Mode.PINCH && selected != null && startBounds != null) {
                    pinchScale *= detector.getScaleFactor();
                    applyPinch();
                    return true;
                }
                return false;
            }
        });
    }

    // ------------------------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------------------------

    public void setCallbacks(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public void setElements(List<? extends EditableElement> elements) {
        this.elements = elements;
        if (selected != null && !elements.contains(selected)) {
            selected = null;
        }
        invalidate();
    }

    public EditableElement getSelected() {
        return selected;
    }

    /** Changes the selection without notifying the callbacks. */
    public void setSelected(EditableElement element) {
        selected = element;
        invalidate();
    }

    public void setSnapEnabled(boolean enabled) {
        snapEnabled = enabled;
    }

    public void setGridEnabled(boolean enabled) {
        gridEnabled = enabled;
        invalidate();
    }

    public void setHint(String hint) {
        this.hint = hint;
        invalidate();
    }

    /** Vertical position of the help text (center line), negative for the default. */
    public void setHintY(float y) {
        hintY = y;
        invalidate();
    }

    public int getGridStep() {
        return Math.max(8, Math.round(getHeight() / 36f));
    }

    public boolean isGestureInProgress() {
        return mode == Mode.MOVE || mode == Mode.RESIZE || mode == Mode.PINCH;
    }

    // ------------------------------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = x;
                downY = y;
                gestureStarted = false;
                guidesX.clear();
                guidesY.clear();
                wasSnappedX = wasSnappedY = false;

                int corner = selected != null ? hitCorner(x, y) : -1;
                if (corner >= 0) {
                    activeCorner = corner;
                    startBounds = ElementEditState.getBounds(selected.getView());
                    mode = Mode.RESIZE;
                    beginGesture();
                } else {
                    downHit = hitTest(x, y);
                    mode = Mode.PENDING;
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (mode == Mode.PENDING && downHit != null && downHit != selected) {
                    select(downHit);
                }
                if (selected != null) {
                    startBounds = ElementEditState.getBounds(selected.getView());
                    pinchScale = 1f;
                    guidesX.clear();
                    guidesY.clear();
                    if (!gestureStarted) {
                        beginGesture();
                    }
                    mode = Mode.PINCH;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == Mode.PENDING) {
                    if (Math.hypot(x - downX, y - downY) > touchSlop) {
                        if (downHit != null) {
                            if (downHit != selected) {
                                select(downHit);
                            }
                            startBounds = ElementEditState.getBounds(selected.getView());
                            mode = Mode.MOVE;
                            beginGesture();
                        } else {
                            mode = Mode.NONE;
                        }
                    }
                }
                if (mode == Mode.MOVE) {
                    applyMove(x - downX, y - downY);
                } else if (mode == Mode.RESIZE) {
                    applyResize(x - downX, y - downY);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (mode == Mode.PENDING) {
                    // Simple tap: select what is under the finger (or clear the selection)
                    select(downHit);
                } else if (gestureStarted) {
                    endGesture();
                }
                resetGesture();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (gestureStarted) {
                    endGesture();
                }
                resetGesture();
                return true;
            }
            default:
                return true;
        }
    }

    private void resetGesture() {
        mode = Mode.NONE;
        downHit = null;
        activeCorner = -1;
        guidesX.clear();
        guidesY.clear();
        invalidate();
    }

    private void select(EditableElement element) {
        if (selected == element) {
            return;
        }
        selected = element;
        if (callbacks != null) {
            callbacks.onSelectionChanged(element);
        }
        invalidate();
    }

    private void beginGesture() {
        gestureStarted = true;
        if (callbacks != null) {
            callbacks.onGestureStarted();
        }
    }

    private void endGesture() {
        gestureStarted = false;
        if (callbacks != null) {
            callbacks.onGestureFinished();
        }
    }

    private void notifyUpdated() {
        invalidate();
        if (callbacks != null) {
            callbacks.onGestureUpdated();
        }
    }

    // ------------------------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------------------------

    private int minSize() {
        return ElementEditState.minElementSize(getResources().getDisplayMetrics());
    }

    private boolean isActive(EditableElement element) {
        return !element.getEditState().removed && element.getView().getVisibility() == VISIBLE;
    }

    private EditableElement hitTest(float x, float y) {
        EditableElement best = null;
        long bestArea = Long.MAX_VALUE;
        for (EditableElement element : elements) {
            if (!isActive(element)) {
                continue;
            }
            Rect b = ElementEditState.getBounds(element.getView());
            RectF r = new RectF(b);
            // Make tiny controls easier to grab
            if (r.width() < minTouchTarget) {
                r.inset(-(minTouchTarget - r.width()) / 2f, 0);
            }
            if (r.height() < minTouchTarget) {
                r.inset(0, -(minTouchTarget - r.height()) / 2f);
            }
            if (r.contains(x, y)) {
                long area = (long) b.width() * b.height();
                if (area < bestArea) {
                    bestArea = area;
                    best = element;
                }
            }
        }
        return best;
    }

    /** Corners: 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left. */
    private float[] cornerPoint(Rect b, int corner) {
        switch (corner) {
            case 0: return new float[]{b.left, b.top};
            case 1: return new float[]{b.right, b.top};
            case 2: return new float[]{b.right, b.bottom};
            default: return new float[]{b.left, b.bottom};
        }
    }

    private int hitCorner(float x, float y) {
        Rect b = ElementEditState.getBounds(selected.getView());
        int best = -1;
        double bestDist = handleTouchRadius;
        for (int i = 0; i < 4; i++) {
            float[] p = cornerPoint(b, i);
            double d = Math.hypot(x - p[0], y - p[1]);
            if (d <= bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private void applyMove(float dx, float dy) {
        int w = startBounds.width();
        int h = startBounds.height();
        float left = startBounds.left + dx;
        float top = startBounds.top + dy;

        guidesX.clear();
        guidesY.clear();
        boolean snappedX = false, snappedY = false;

        if (snapEnabled) {
            List<Float> targetsX = new ArrayList<>();
            List<Float> targetsY = new ArrayList<>();
            targetsX.add(0f);
            targetsX.add(getWidth() / 2f);
            targetsX.add((float) getWidth());
            targetsY.add(0f);
            targetsY.add(getHeight() / 2f);
            targetsY.add((float) getHeight());
            for (EditableElement other : elements) {
                if (other == selected || !isActive(other)) {
                    continue;
                }
                Rect o = ElementEditState.getBounds(other.getView());
                targetsX.add((float) o.left);
                targetsX.add(o.exactCenterX());
                targetsX.add((float) o.right);
                targetsY.add((float) o.top);
                targetsY.add(o.exactCenterY());
                targetsY.add((float) o.bottom);
            }

            float[] sx = snap(left, w, targetsX);
            if (sx != null) {
                left = sx[0];
                guidesX.add(sx[1]);
                snappedX = true;
            }
            float[] sy = snap(top, h, targetsY);
            if (sy != null) {
                top = sy[0];
                guidesY.add(sy[1]);
                snappedY = true;
            }
        }

        if (gridEnabled) {
            int step = getGridStep();
            if (!snappedX) {
                left = Math.round(left / step) * step;
            }
            if (!snappedY) {
                top = Math.round(top / step) * step;
            }
        }

        // Light haptic tick when a guide engages
        if ((snappedX && !wasSnappedX) || (snappedY && !wasSnappedY)) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        wasSnappedX = snappedX;
        wasSnappedY = snappedY;

        left = Math.max(0, Math.min(left, getWidth() - w));
        top = Math.max(0, Math.min(top, getHeight() - h));
        ElementEditState.setBounds(selected.getView(), Math.round(left), Math.round(top), w, h);
        notifyUpdated();
    }

    /**
     * Tries to align the start, center or end of a span [start, start + size] with a target.
     *
     * @return {newStart, guidePosition} or null
     */
    private float[] snap(float start, float size, List<Float> targets) {
        float[] edges = {start, start + size / 2f, start + size};
        float bestDistance = snapThreshold;
        float[] result = null;
        for (float target : targets) {
            for (float edge : edges) {
                float delta = target - edge;
                if (Math.abs(delta) < bestDistance) {
                    bestDistance = Math.abs(delta);
                    result = new float[]{start + delta, target};
                }
            }
        }
        return result;
    }

    private void applyResize(float dx, float dy) {
        int corner = activeCorner;
        boolean movingLeft = corner == 0 || corner == 3;
        boolean movingTop = corner == 0 || corner == 1;

        float anchorX = movingLeft ? startBounds.right : startBounds.left;
        float anchorY = movingTop ? startBounds.bottom : startBounds.top;
        float px = (movingLeft ? startBounds.left : startBounds.right) + dx;
        float py = (movingTop ? startBounds.top : startBounds.bottom) + dy;

        if (gridEnabled) {
            int step = getGridStep();
            px = Math.round(px / step) * step;
            py = Math.round(py / step) * step;
        }

        float maxW = movingLeft ? anchorX : getWidth() - anchorX;
        float maxH = movingTop ? anchorY : getHeight() - anchorY;
        int min = minSize();

        float newW = movingLeft ? anchorX - px : px - anchorX;
        float newH = movingTop ? anchorY - py : py - anchorY;

        if (selected.getEditState().lockAspect) {
            float sw = startBounds.width();
            float sh = startBounds.height();
            float scale = Math.max(newW / sw, newH / sh);
            float minScale = Math.max(min / sw, min / sh);
            float maxScale = Math.min(maxW / sw, maxH / sh);
            scale = Math.max(minScale, Math.min(scale, maxScale));
            newW = sw * scale;
            newH = sh * scale;
        } else {
            newW = Math.max(min, Math.min(newW, maxW));
            newH = Math.max(min, Math.min(newH, maxH));
        }

        float left = movingLeft ? anchorX - newW : anchorX;
        float top = movingTop ? anchorY - newH : anchorY;
        ElementEditState.setBounds(selected.getView(), Math.round(left), Math.round(top),
                Math.round(newW), Math.round(newH));
        notifyUpdated();
    }

    private void applyPinch() {
        float sw = startBounds.width();
        float sh = startBounds.height();
        int min = minSize();
        float minScale = Math.max(min / sw, min / sh);
        float maxScale = Math.min(getWidth() / sw, getHeight() / sh);
        float scale = Math.max(minScale, Math.min(pinchScale, maxScale));
        float newW = sw * scale;
        float newH = sh * scale;
        float left = startBounds.exactCenterX() - newW / 2f;
        float top = startBounds.exactCenterY() - newH / 2f;
        left = Math.max(0, Math.min(left, getWidth() - newW));
        top = Math.max(0, Math.min(top, getHeight() - newH));
        ElementEditState.setBounds(selected.getView(), Math.round(left), Math.round(top),
                Math.round(newW), Math.round(newH));
        notifyUpdated();
    }

    // ------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();

        if (gridEnabled) {
            int step = getGridStep();
            gridPaint.setColor(GRID);
            for (int x = step; x < width; x += step) {
                canvas.drawLine(x, 0, x, height, gridPaint);
            }
            for (int y = step; y < height; y += step) {
                canvas.drawLine(0, y, width, y, gridPaint);
            }
            gridPaint.setColor(GRID_CENTER);
            canvas.drawLine(width / 2f, 0, width / 2f, height, gridPaint);
            canvas.drawLine(0, height / 2f, width, height / 2f, gridPaint);
        }

        // Outline every control so that small / transparent ones are easy to find
        for (EditableElement element : elements) {
            if (element == selected || !isActive(element)) {
                continue;
            }
            Rect b = ElementEditState.getBounds(element.getView());
            outlinePaint.setColor(element.getEditState().shown ? OUTLINE : HIDDEN_OUTLINE);
            canvas.drawRect(b, outlinePaint);
        }

        for (float gx : guidesX) {
            canvas.drawLine(gx, 0, gx, height, guidePaint);
        }
        for (float gy : guidesY) {
            canvas.drawLine(0, gy, width, gy, guidePaint);
        }

        if (selected != null && isActive(selected)) {
            Rect b = ElementEditState.getBounds(selected.getView());
            canvas.drawRect(b, selectionPaint);
            for (int i = 0; i < 4; i++) {
                float[] p = cornerPoint(b, i);
                canvas.drawCircle(p[0], p[1], handleRadius, handleFill);
                canvas.drawCircle(p[0], p[1], handleRadius, selectionPaint);
            }
            drawTag(canvas, selected.getDisplayName(), b);
        } else if (hint != null && mode == Mode.NONE) {
            drawPill(canvas, hint, width / 2f, hintY >= 0 ? hintY : height - 28 * dp);
        }
    }

    private void drawTag(Canvas canvas, String label, Rect b) {
        if (label == null || label.isEmpty()) {
            return;
        }
        float tagHeight = tagPaint.getTextSize() + 10 * dp;
        float y = b.top - tagHeight / 2f - 6 * dp;
        if (y - tagHeight / 2f < 0) {
            y = b.bottom + tagHeight / 2f + 6 * dp;
        }
        float x = Math.max(0, Math.min(b.exactCenterX(), getWidth()));
        drawPill(canvas, label, x, y);
    }

    private void drawPill(Canvas canvas, String label, float cx, float cy) {
        float textWidth = tagPaint.measureText(label);
        float padH = 10 * dp;
        float h = tagPaint.getTextSize() + 10 * dp;
        float left = Math.max(2 * dp, Math.min(cx - textWidth / 2f - padH, getWidth() - textWidth - 2 * padH - 2 * dp));
        tmpRectF.set(left, cy - h / 2f, left + textWidth + 2 * padH, cy + h / 2f);
        canvas.drawRoundRect(tmpRectF, h / 2f, h / 2f, tagBgPaint);
        Paint.FontMetrics fm = tagPaint.getFontMetrics();
        canvas.drawText(label, tmpRectF.centerX(), cy - (fm.ascent + fm.descent) / 2f, tagPaint);
    }
}
