package com.limelight;

import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.limelight.binding.input.overlay.EditableOverlay;
import com.limelight.binding.input.overlay.editor.OverlayEditorView;
import com.limelight.binding.input.virtual_controller.VirtualController;
import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardController;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone input overlay editor, opened from the settings. The overlays are displayed
 * in preview mode (they don't send any input) over an empty background.
 */
public class InputOverlayEditorActivity extends AppCompatActivity {
    public static final String EXTRA_INITIAL_OVERLAY = "initial_overlay";
    public static final String OVERLAY_KEYBOARD = "keyboard";

    private FrameLayout root;
    private VirtualController virtualController;
    private KeyBoardController keyBoardController;
    private OverlayEditorView editor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF15161A);
        setContentView(root);
        hideSystemUi();

        virtualController = new VirtualController(null, root, this);
        virtualController.refreshLayout();
        virtualController.show();

        keyBoardController = new KeyBoardController(null, root, this);
        keyBoardController.refreshLayout();
        keyBoardController.show();

        List<EditableOverlay> overlays = new ArrayList<>();
        overlays.add(virtualController);
        overlays.add(keyBoardController);

        EditableOverlay initial = OVERLAY_KEYBOARD.equals(getIntent().getStringExtra(EXTRA_INITIAL_OVERLAY))
                ? keyBoardController : virtualController;

        editor = OverlayEditorView.open(root, overlays, initial, saved -> finish());
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        virtualController.refreshLayout();
        keyBoardController.refreshLayout();
        if (editor != null && editor.getParent() != null) {
            editor.onHostLayoutRebuilt();
        }
    }

    @Override
    public void onBackPressed() {
        if (editor != null && editor.getParent() != null) {
            editor.onBackPressed();
            return;
        }
        super.onBackPressed();
    }
}
