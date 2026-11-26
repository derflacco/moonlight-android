package com.limelight.binding.input.overlay;

import android.content.Context;

import java.util.List;

/**
 * An overlay (gamepad or keyboard) whose elements can be arranged by the overlay editor.
 */
public interface EditableOverlay {
    /** Identifies the overlay, also used to pick its layout slots. */
    LayoutSlots.Kind getKind();

    String getTitle(Context context);

    /** Live list of elements (it is rebuilt by {@link #reloadLayout()}). */
    List<? extends EditableElement> getEditableElements();

    /** Puts the overlay in editor preview mode: no input, all non removed elements visible. */
    void setEditing(boolean editing);

    boolean isEditing();

    /** Persists the current layout to the active slot. */
    void saveLayout();

    /** Rebuilds the overlay from the defaults and the active slot. */
    void reloadLayout();

    /** Sets the overlay opacity (0..100 %), applied on top of each element's own opacity. */
    void setOpacity(int opacity);
}
