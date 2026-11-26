package com.limelight.binding.input.overlay;

import android.view.View;

/**
 * An on-screen control that can be arranged by the overlay editor.
 */
public interface EditableElement {
    /** The element's view, attached to the overlay's frame layout. */
    View getView();

    /** Stable key, unique within its overlay, used for persistence and undo history. */
    String getEditorKey();

    /** Human readable name shown in the editor. */
    String getDisplayName();

    ElementEditState getEditState();

    /** Whether the element can be removed from the layout (and added back later). */
    boolean isRemovable();
}
