package com.limelight.binding.input.overlay;

import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.FrameLayout;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Editable, persisted state shared by every on-screen overlay element (gamepad and keyboard).
 *
 * Geometry is persisted in a resolution independent form (see {@link #toJson}), while the
 * element's live geometry is kept in its {@link FrameLayout.LayoutParams}.
 */
public class ElementEditState {
    /** Element is drawn while playing. Hidden elements stay visible (faded) inside the editor. */
    public boolean shown = true;
    /** Element was removed from the layout (keyboard overlay only). Can be re-added from the editor. */
    public boolean removed = false;
    /** Per element opacity multiplier, 0.1 .. 1.0, applied on top of the global overlay opacity. */
    public float opacity = 1f;
    /** Keep width/height ratio when resizing. */
    public boolean lockAspect = true;

    /** Geometry produced by the default layout, used by "reset element". */
    public final Rect defaultBounds = new Rect();
    public boolean defaultLockAspect = true;

    /** True while the overlay is opened inside the layout editor. */
    public boolean editing = false;

    public static final float HIDDEN_PREVIEW_ALPHA = 0.3f;
    public static final float MIN_OPACITY = 0.1f;

    public void applyVisibility(View view) {
        if (removed) {
            view.setVisibility(View.GONE);
            return;
        }
        if (editing) {
            view.setVisibility(View.VISIBLE);
            view.setAlpha(shown ? opacity : Math.min(opacity, HIDDEN_PREVIEW_ALPHA));
        } else {
            view.setVisibility(shown ? View.VISIBLE : View.GONE);
            view.setAlpha(opacity);
        }
    }

    public void resetToDefault(View view) {
        shown = true;
        removed = false;
        opacity = 1f;
        lockAspect = defaultLockAspect;
        setBounds(view, defaultBounds.left, defaultBounds.top, defaultBounds.width(), defaultBounds.height());
        applyVisibility(view);
    }

    public static void setBounds(View view, int left, int top, int width, int height) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        lp.leftMargin = left;
        lp.topMargin = top;
        lp.rightMargin = 0;
        lp.bottomMargin = 0;
        lp.width = width;
        lp.height = height;
        view.setLayoutParams(lp);
    }

    public static Rect getBounds(View view) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        return new Rect(lp.leftMargin, lp.topMargin, lp.leftMargin + lp.width, lp.topMargin + lp.height);
    }

    // ---------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------

    static final int ANCHOR_LEFT = 0;
    static final int ANCHOR_CENTER = 1;
    static final int ANCHOR_RIGHT = 2;

    /**
     * Serializes the element.
     *
     * Format v2 stores geometry in "screen height" units so that a layout keeps its proportions
     * on any resolution. Horizontally, elements are anchored to the closest screen edge (or the
     * center) so that controls stay under the thumbs on wider or narrower screens. The legacy
     * pixel keys are still written so that older builds can read the layout.
     */
    public JSONObject toJson(View view, DisplayMetrics screen) throws JSONException {
        Rect b = getBounds(view);
        float unit = screen.heightPixels;
        int screenW = screen.widthPixels;

        float centerX = b.exactCenterX();
        int anchor;
        float offsetX;
        if (centerX < screenW / 3f) {
            anchor = ANCHOR_LEFT;
            offsetX = b.left / unit;
        } else if (centerX > screenW * 2f / 3f) {
            anchor = ANCHOR_RIGHT;
            offsetX = (screenW - b.right) / unit;
        } else {
            anchor = ANCHOR_CENTER;
            offsetX = (centerX - screenW / 2f) / unit;
        }

        JSONObject o = new JSONObject();
        o.put("v", 2);
        o.put("anchor", anchor);
        o.put("x", offsetX);
        o.put("y", b.top / unit);
        o.put("w", b.width() / unit);
        o.put("h", b.height() / unit);
        o.put("opacity", opacity);
        o.put("lock", lockAspect);

        // Legacy keys
        o.put("LEFT", b.left);
        o.put("TOP", b.top);
        o.put("WIDTH", b.width());
        o.put("HEIGHT", b.height());
        o.put("ENABLED", shown);
        o.put("HIDDEN", removed);
        return o;
    }

    public void fromJson(View view, JSONObject o, DisplayMetrics screen) throws JSONException {
        int left, top, width, height;
        if (o.optInt("v", 1) >= 2) {
            float unit = screen.heightPixels;
            int screenW = screen.widthPixels;
            width = Math.round((float) o.getDouble("w") * unit);
            height = Math.round((float) o.getDouble("h") * unit);
            float offsetX = (float) o.getDouble("x") * unit;
            switch (o.optInt("anchor", ANCHOR_LEFT)) {
                case ANCHOR_RIGHT:
                    left = Math.round(screenW - offsetX - width);
                    break;
                case ANCHOR_CENTER:
                    left = Math.round(screenW / 2f + offsetX - width / 2f);
                    break;
                default:
                    left = Math.round(offsetX);
                    break;
            }
            top = Math.round((float) o.getDouble("y") * unit);
            opacity = clampOpacity((float) o.optDouble("opacity", 1.0));
            lockAspect = o.optBoolean("lock", defaultLockAspect);
        } else {
            left = o.getInt("LEFT");
            top = o.getInt("TOP");
            width = o.getInt("WIDTH");
            height = o.getInt("HEIGHT");
        }
        shown = o.optBoolean("ENABLED", true);
        removed = o.optBoolean("HIDDEN", false);

        // Keep the element reachable whatever screen the layout was made on
        int minSize = minElementSize(screen);
        width = Math.max(minSize, Math.min(width, screen.widthPixels));
        height = Math.max(minSize, Math.min(height, screen.heightPixels));
        left = Math.max(0, Math.min(left, screen.widthPixels - width));
        top = Math.max(0, Math.min(top, screen.heightPixels - height));

        setBounds(view, left, top, width, height);
        applyVisibility(view);
    }

    public static float clampOpacity(float value) {
        return Math.max(MIN_OPACITY, Math.min(1f, value));
    }

    /** Smallest size an element may have: big enough to stay grabbable (~32dp). */
    public static int minElementSize(DisplayMetrics screen) {
        return Math.round(32 * screen.density);
    }
}
