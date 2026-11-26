package com.limelight.binding.input.overlay.editor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Undo / redo stacks of {@link LayoutSnapshot}s for one overlay.
 */
final class EditorHistory {
    private static final int MAX_STEPS = 60;

    private final Deque<LayoutSnapshot> undo = new ArrayDeque<>();
    private final Deque<LayoutSnapshot> redo = new ArrayDeque<>();

    /** Records the state that existed before an edit. */
    void push(LayoutSnapshot before) {
        undo.push(before);
        while (undo.size() > MAX_STEPS) {
            undo.removeLast();
        }
        redo.clear();
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    /** @return the state to restore, or null */
    LayoutSnapshot undo(LayoutSnapshot current) {
        if (undo.isEmpty()) {
            return null;
        }
        redo.push(current);
        return undo.pop();
    }

    /** @return the state to restore, or null */
    LayoutSnapshot redo(LayoutSnapshot current) {
        if (redo.isEmpty()) {
            return null;
        }
        undo.push(current);
        return redo.pop();
    }

    void clear() {
        undo.clear();
        redo.clear();
    }
}
