package com.limelight.binding.input.overlay.editor;

import android.graphics.Rect;

import com.limelight.binding.input.overlay.EditableElement;
import com.limelight.binding.input.overlay.EditableOverlay;
import com.limelight.binding.input.overlay.ElementEditState;

import java.util.HashMap;
import java.util.Map;

/**
 * Immutable copy of the editable state of every element of an overlay (undo/redo, cancel).
 */
final class LayoutSnapshot {
    private static final class Item {
        final Rect bounds;
        final boolean shown;
        final boolean removed;
        final boolean lockAspect;
        final float opacity;

        Item(EditableElement element) {
            ElementEditState s = element.getEditState();
            bounds = ElementEditState.getBounds(element.getView());
            shown = s.shown;
            removed = s.removed;
            lockAspect = s.lockAspect;
            opacity = s.opacity;
        }

        boolean sameAs(Item o) {
            return bounds.equals(o.bounds) && shown == o.shown && removed == o.removed
                    && lockAspect == o.lockAspect && opacity == o.opacity;
        }
    }

    private final Map<String, Item> items = new HashMap<>();

    private LayoutSnapshot() {
    }

    static LayoutSnapshot capture(EditableOverlay overlay) {
        LayoutSnapshot snapshot = new LayoutSnapshot();
        for (EditableElement element : overlay.getEditableElements()) {
            snapshot.items.put(element.getEditorKey(), new Item(element));
        }
        return snapshot;
    }

    /** Restores the captured state. Elements unknown to the snapshot are left untouched. */
    void apply(EditableOverlay overlay) {
        for (EditableElement element : overlay.getEditableElements()) {
            Item item = items.get(element.getEditorKey());
            if (item == null) {
                continue;
            }
            ElementEditState s = element.getEditState();
            s.shown = item.shown;
            s.removed = item.removed;
            s.lockAspect = item.lockAspect;
            s.opacity = item.opacity;
            ElementEditState.setBounds(element.getView(), item.bounds.left, item.bounds.top,
                    item.bounds.width(), item.bounds.height());
            s.applyVisibility(element.getView());
        }
    }

    boolean sameAs(LayoutSnapshot other) {
        if (other == null || other.items.size() != items.size()) {
            return false;
        }
        for (Map.Entry<String, Item> entry : items.entrySet()) {
            Item o = other.items.get(entry.getKey());
            if (o == null || !entry.getValue().sameAs(o)) {
                return false;
            }
        }
        return true;
    }
}
