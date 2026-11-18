package com.limelight;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.AppGridAdapter;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.DragEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageView;
import android.widget.Gallery;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import org.xmlpull.v1.XmlPullParserException;

public class AppView extends AppCompatActivity implements AdapterFragmentCallbacks {
    private AppGridAdapter appGridAdapter;
    private String uuidString;
    private ShortcutHelper shortcutHelper;

    private ComputerDetails computer;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;
    private String lastRawApplist;
    private int lastRunningAppId;
    private boolean suspendGridUpdates;
    private boolean inForeground;
    private boolean showHiddenApps;
    private HashSet<Integer> hiddenAppIds = new HashSet<>();

    private PreferenceConfiguration prefConfig;
    private ImageView gameHeroBackground;
    private ImageView gameLogo;
    private TextView gameTitle;
    private TextView gameMeta;
    private ExtendedFloatingActionButton backgroundButton;
    private AppObject selectedApp;
    private View selectedItemView;
    private String steamGridDbApiKey = "";
    private int lastArtworkAppId = -1;
    private static final int REQUEST_CODE_BACKGROUND_IMAGE = 901;

    private final static int START_OR_RESUME_ID = 1;
    private final static int QUIT_ID = 2;
    private final static int START_WITH_QUIT = 4;
    private final static int VIEW_DETAILS_ID = 5;
    private final static int CREATE_SHORTCUT_ID = 6;
    private final static int EXPORT_LAUNCHER_FILE_ID = 7;
    private final static int HIDE_APP_ID = 8;
    private final static int START_WITH_VDISPLAY = 20;
    private final static int START_WITH_QUIT_VDISPLAY = 21;
    private final static int TOGGLE_APP_LOCK_ID = 22;

    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Get the computer object
                    computer = localBinder.getComputer(uuidString);
                    if (computer == null) {
                        finish();
                        return;
                    }

                    // Add a launcher shortcut for this PC (forced, since this is user interaction)
                    shortcutHelper.createAppViewShortcut(computer, true, getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
                    shortcutHelper.reportComputerShortcutUsed(computer);

                    try {
                        appGridAdapter = new AppGridAdapter(AppView.this,
                                PreferenceConfiguration.readPreferences(AppView.this),
                                computer, localBinder.getUniqueId(),
                                showHiddenApps);
                    } catch (Exception e) {
                        e.printStackTrace();
                        finish();
                        return;
                    }

                    appGridAdapter.updateHiddenApps(hiddenAppIds, true);

                    // Now make the binder visible. We must do this after appGridAdapter
                    // is set to prevent us from reaching updateUiWithServerinfo() and
                    // touching the appGridAdapter prior to initialization.
                    managerBinder = localBinder;

                    // Load the app grid with cached data (if possible).
                    // This must be done _before_ startComputerUpdates()
                    // so the initial serverinfo response can update the running
                    // icon.
                    populateAppGridWithCache();

                    // Start updates
                    startComputerUpdates();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isChangingConfigurations()) {
                                return;
                            }

                            // Despite my best efforts to catch all conditions that could
                            // cause the activity to be destroyed when we try to commit
                            // I haven't been able to, so we have this try-catch block.
                            try {
                                getFragmentManager().beginTransaction()
                                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                                        .commitAllowingStateLoss();
                            } catch (IllegalStateException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        this.prefConfig = PreferenceConfiguration.readPreferences(this);

        // If appGridAdapter is initialized, let it know about the configuration change.
        // If not, it will pick it up when it initializes.
        if (appGridAdapter != null) {
            // Update the app grid adapter to create grid items with the correct layout
            appGridAdapter.updateLayoutWithPreferences(this, this.prefConfig);

            try {
                // Reinflate the app grid itself to pick up the layout change
                getFragmentManager().beginTransaction()
                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                        .commitAllowingStateLoss();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }
    }

    private void startComputerUpdates() {
        // Don't start polling if we're not bound or in the foreground
        if (managerBinder == null || !inForeground) {
            return;
        }

        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                // Do nothing if updates are suspended
                if (suspendGridUpdates) {
                    return;
                }

                // Don't care about other computers
                if (!details.uuid.equalsIgnoreCase(uuidString)) {
                    return;
                }

                if (details.state == ComputerDetails.State.OFFLINE) {
                    // The PC is unreachable now
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, R.string.lost_connection, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // Close immediately if the PC is no longer paired
                if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Disable shortcuts referencing this PC for now
                            shortcutHelper.disableComputerShortcut(details,
                                    getResources().getString(R.string.scut_not_paired));

                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, R.string.scut_not_paired, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // App list is the same or empty
                if (details.rawAppList == null || details.rawAppList.equals(lastRawApplist)) {

                    // Let's check if the running app ID changed
                    if (details.runningGameId != lastRunningAppId) {
                        // Update the currently running game using the app ID
                        lastRunningAppId = details.runningGameId;
                        updateUiWithServerinfo(details);
                    }

                    return;
                }

                lastRunningAppId = details.runningGameId;
                lastRawApplist = details.rawAppList;

                try {
                    updateUiWithAppList(NvHTTP.getAppListByReader(new StringReader(details.rawAppList)));
                    updateUiWithServerinfo(details);

                    if (blockingLoadSpinner != null) {
                        blockingLoadSpinner.dismiss();
                        blockingLoadSpinner = null;
                    }
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                }
            }
        });

        if (poller == null) {
            poller = managerBinder.createAppListPoller(computer);
        }
        poller.start();
    }

    private void stopComputerUpdates() {
        if (poller != null) {
            poller.stop();
        }

        if (managerBinder != null) {
            managerBinder.stopPolling();
        }

        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_app_view);

        // Allow floating expanded PiP overlays while browsing apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        UiHelper.notifyNewRootView(this);

        // Setup the profiles button
        findViewById(R.id.profilesButton)
            .setOnClickListener(v -> startActivity(new Intent(this, ProfilesActivity.class)));

        showHiddenApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);
        uuidString = getIntent().getStringExtra(UUID_EXTRA);

        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String hiddenAppIdStr : hiddenAppsPrefs.getStringSet(uuidString, new HashSet<String>())) {
            hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr));
        }

        String computerName = getIntent().getStringExtra(NAME_EXTRA);

        TextView label = findViewById(R.id.appListText);
        setTitle(computerName);
        label.setText(computerName);

        gameHeroBackground = findViewById(R.id.gameHeroBackground);
        gameLogo = findViewById(R.id.gameLogo);
        gameTitle = findViewById(R.id.gameTitle);
        gameMeta = findViewById(R.id.gameMeta);
        backgroundButton = findViewById(R.id.backgroundButton);
        backgroundButton.setOnClickListener(v -> showBackgroundOptions());
        backgroundButton.shrink();
        applyBackgroundScaleMode();
        findViewById(R.id.gamePlayButton).setOnClickListener(v -> {
            if (selectedApp != null && selectedItemView != null) {
                handleAppClick(selectedApp, selectedItemView);
            }
        });

        this.prefConfig = PreferenceConfiguration.readPreferences(this);

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
    }

    private void updateHiddenApps(boolean hideImmediately) {
        HashSet<String> hiddenAppIdStringSet = new HashSet<>();

        for (Integer hiddenAppId : hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString());
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putStringSet(uuidString, hiddenAppIdStringSet)
                .apply();

        appGridAdapter.updateHiddenApps(hiddenAppIds, hideImmediately);
    }

    private void populateAppGridWithCache() {
        try {
            // Try to load from cache
            lastRawApplist = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(lastRawApplist));
            updateUiWithAppList(applist);
            LimeLog.info("Loaded applist from cache");
        } catch (IOException | XmlPullParserException e) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: "+lastRawApplist);
                e.printStackTrace();
            }
            LimeLog.info("Loading applist from the network");
            // We'll need to load from the network
            loadAppsBlocking();
        }
    }

    private void loadAppsBlocking() {
        blockingLoadSpinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.applist_refresh_title),
                getResources().getString(R.string.applist_refresh_msg), true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        String configuredApiKey = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("steamgriddb_api_key", "");
        if (!configuredApiKey.equals(steamGridDbApiKey)) {
            steamGridDbApiKey = configuredApiKey.trim();
            lastArtworkAppId = -1;
            if (selectedApp != null) {
                updateSelectedApp(selectedApp, selectedItemView);
            }
        }
        // The selected image remains in place; only reapply its display mode after settings return.
        applyBackgroundScaleMode();
        startComputerUpdates();

        ExtendedFloatingActionButton profilesButton = findViewById(R.id.profilesButton);
        // User report Samsung and Xiaomi devices have this problem
        // Why just these two brands have the most problems?
        if (profilesButton == null) {
            return;
        }
        String activeProfileName = ProfilesManager.getInstance().getActiveName();
        if (activeProfileName.isEmpty()) {
            profilesButton.shrink();
        } else {
            profilesButton.setText(activeProfileName);
            profilesButton.extend();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_BACKGROUND_IMAGE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null &&
                    selectedApp != null) {
                Uri uri = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // Some document providers don't offer persistable permissions.
                }
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putString(getLocalBackgroundKey(selectedApp.app.getAppId()), uri.toString())
                        .remove(getSteamGridDbBackgroundKey(selectedApp.app.getAppId()))
                        .apply();
                lastArtworkAppId = -1;
                applyBackgroundForSelectedApp(selectedApp, selectedItemView);
            }
            return;
        }

        if (requestCode == ShortcutHelper.REQUEST_CODE_EXPORT_ART_FILE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                ShortcutHelper.writeArtFileToUri(this, uri);
            } else {
                // Clear the content if the user cancelled or if there was an error before this point
                ShortcutHelper.artFileContentToExport = null;
                // Show "File export cancelled." toast only if the user explicitly cancelled.
                if (resultCode == Activity.RESULT_CANCELED) {
                    Toast.makeText(this, R.string.file_export_cancelled, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        AppObject selectedApp = (AppObject) appGridAdapter.getItem(info.position);

        menu.setHeaderTitle(selectedApp.app.getAppName());
        menu.add(Menu.NONE, TOGGLE_APP_LOCK_ID, 0,
                appGridAdapter.isAppLocked(selectedApp) ? "Unlock position" : "Lock position");

        if (lastRunningAppId == 0) {
            if (prefConfig.useVirtualDisplay) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_start_primarydisplay));
            } else {
                menu.add(Menu.NONE, START_WITH_VDISPLAY, 1, getResources().getString(R.string.applist_menu_start_vdisplay));
            }
        } else {
            if (lastRunningAppId == selectedApp.app.getAppId()) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }
            else {
                if (prefConfig.useVirtualDisplay) {
                    menu.add(Menu.NONE, START_WITH_QUIT_VDISPLAY, 1, getResources().getString(R.string.applist_menu_quit_and_start));
                    menu.add(Menu.NONE, START_WITH_QUIT, 2, getResources().getString(R.string.applist_menu_quit_and_start_primarydisplay));
                } else{
                    menu.add(Menu.NONE, START_WITH_QUIT, 1, getResources().getString(R.string.applist_menu_quit_and_start));
                    menu.add(Menu.NONE, START_WITH_QUIT_VDISPLAY, 2, getResources().getString(R.string.applist_menu_quit_and_start_vdisplay));
                }
            }
        }

        // Only show the hide checkbox if this is not the currently running app or it's already hidden
        if (lastRunningAppId != selectedApp.app.getAppId() || selectedApp.isHidden) {
            MenuItem hideAppItem = menu.add(Menu.NONE, HIDE_APP_ID, 3, getResources().getString(R.string.applist_menu_hide_app));
            hideAppItem.setCheckable(true);
            hideAppItem.setChecked(selectedApp.isHidden);
        }

        menu.add(Menu.NONE, VIEW_DETAILS_ID, 4, getResources().getString(R.string.applist_menu_details));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Only add an option to create shortcut if box art is loaded
            // and when we're in grid-mode (not list-mode).
            ImageView appImageView = info.targetView.findViewById(R.id.grid_image);
            if (appImageView != null) {
                // We have a grid ImageView, so we must be in grid-mode
                BitmapDrawable drawable = (BitmapDrawable)appImageView.getDrawable();
                if (drawable != null && drawable.getBitmap() != null) {
                    // We have a bitmap loaded too
                    menu.add(Menu.NONE, CREATE_SHORTCUT_ID, 5, getResources().getString(R.string.applist_menu_scut));
                }
            }
        }

        menu.add(Menu.NONE, EXPORT_LAUNCHER_FILE_ID, 6, getResources().getString(R.string.applist_menu_export_launcher));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final AppObject app = (AppObject) appGridAdapter.getItem(info.position);
        int itemId = item.getItemId();
        switch (itemId) {
            case TOGGLE_APP_LOCK_ID: {
                boolean locked = !appGridAdapter.isAppLocked(app);
                appGridAdapter.setAppLocked(app, locked);
                Toast.makeText(this, locked ? "App position locked" : "App position unlocked", Toast.LENGTH_SHORT).show();
                return true;
            }
            case START_WITH_QUIT:
            case START_WITH_QUIT_VDISPLAY: {
                boolean withVDiaplay = itemId == START_WITH_QUIT_VDISPLAY;
                if (withVDiaplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                    UiHelper.displayVdisplayConfirmationDialog(
                        AppView.this,
                        computer,
                        () -> UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                            @Override
                            public void run() {
                                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true);
                            }
                        }, null),
                        null
                    );
                } else {
                    // Display a confirmation dialog first
                    UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                        @Override
                        public void run() {
                            ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, withVDiaplay);
                        }
                    }, null);
                }
                return true;
            }

            case START_OR_RESUME_ID:
            case START_WITH_VDISPLAY: {
                boolean withVDiaplay = itemId == START_WITH_VDISPLAY;
                if (withVDiaplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                    UiHelper.displayVdisplayConfirmationDialog(
                            AppView.this,
                            computer,
                            () -> ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true),
                            null
                    );
                } else {
                    // Resume is the same as start for us
                    ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, withVDiaplay);
                }
                return true;
            }

            case QUIT_ID: {
                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        suspendGridUpdates = true;
                        ServerHelper.doQuit(AppView.this, computer,
                                app.app, managerBinder, new Runnable() {
                                    @Override
                                    public void run() {
                                        // Trigger a poll immediately
                                        suspendGridUpdates = false;
                                        if (poller != null) {
                                            poller.pollNow();
                                        }
                                    }
                                });
                    }
                }, null);
                return true;
            }

            case VIEW_DETAILS_ID: {
                Dialog.displayDialog(AppView.this, getResources().getString(R.string.title_details), app.app.toString(), false);
                return true;
            }

            case HIDE_APP_ID: {
                if (item.isChecked()) {
                    // Transitioning hidden to shown
                    hiddenAppIds.remove(app.app.getAppId());
                } else {
                    // Transitioning shown to hidden
                    hiddenAppIds.add(app.app.getAppId());
                }
                updateHiddenApps(false);
                return true;
            }

            case CREATE_SHORTCUT_ID: {
                ImageView appImageView = info.targetView.findViewById(R.id.grid_image);
                Bitmap appBits = ((BitmapDrawable) appImageView.getDrawable()).getBitmap();
                if (!shortcutHelper.createPinnedGameShortcut(computer, app.app, appBits)) {
                    Toast.makeText(AppView.this, getResources().getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
                }
                return true;
            }

            case EXPORT_LAUNCHER_FILE_ID: {
                if (app.app.getAppUUID() == null || (app.app.getAppUUID() != null && app.app.getAppUUID().isEmpty())) {
                    UiHelper.displayConfirmationDialog(
                            AppView.this,
                            getResources().getString(R.string.title_export_sunshine_launcher_file),
                            getResources().getString(R.string.message_export_sunshine_launcher_file),
                            getResources().getString(R.string.proceed),
                            getResources().getString(R.string.cancel),
                            () -> shortcutHelper.exportLauncherFile(computer, app.app),
                            null
                    );
                } else {
                    shortcutHelper.exportLauncherFile(computer, app.app);
                }
                return true;
            }

            default: {
                return super.onContextItemSelected(item);
            }
        }
    }

    private String getLocalBackgroundKey(int appId) {
        return "deck_background_uri_" + (uuidString == null ? "default" : uuidString) + "_" + appId;
    }

    private String getSteamGridDbBackgroundKey(int appId) {
        return "deck_background_url_" + (uuidString == null ? "default" : uuidString) + "_" + appId;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void applyBackgroundScaleMode() {
        String mode = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("deck_background_scale_mode", "cover");
        ImageView.ScaleType scaleType;
        switch (mode) {
            case "fit":
                scaleType = ImageView.ScaleType.FIT_CENTER;
                break;
            case "stretch":
                scaleType = ImageView.ScaleType.FIT_XY;
                break;
            case "center":
                scaleType = ImageView.ScaleType.CENTER;
                break;
            case "cover":
            default:
                scaleType = ImageView.ScaleType.CENTER_CROP;
                break;
        }
        gameHeroBackground.setScaleType(scaleType);
    }

    private void showBackgroundOptions() {
        if (selectedApp == null) {
            Toast.makeText(this, R.string.background_select_game_first, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] options = {
                getString(R.string.background_choose_steamgriddb),
                getString(R.string.background_choose_device),
                getString(R.string.background_reset)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.background_options_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showSteamGridDbHeroOptions();
                    } else if (which == 1) {
                        openBackgroundImagePicker();
                    } else {
                        resetSelectedBackground();
                    }
                })
                .show();
    }

    private void openBackgroundImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_CODE_BACKGROUND_IMAGE);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.background_picker_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void resetSelectedBackground() {
        if (selectedApp == null) return;
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .remove(getLocalBackgroundKey(selectedApp.app.getAppId()))
                .remove(getSteamGridDbBackgroundKey(selectedApp.app.getAppId()))
                .apply();
        lastArtworkAppId = -1;
        applyBackgroundForSelectedApp(selectedApp, selectedItemView);
    }

    private void showSteamGridDbHeroOptions() {
        if (steamGridDbApiKey.isEmpty()) {
            Toast.makeText(this, R.string.background_steamgriddb_key_required, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.background_loading_options, Toast.LENGTH_SHORT).show();
        SteamGridDbClient.loadHeroOptions(getApplicationContext(), selectedApp.app.getAppName(),
                steamGridDbApiKey, urls -> {
                    if (isFinishing() || selectedApp == null) return;
                    if (urls == null || urls.isEmpty()) {
                        Toast.makeText(this, R.string.background_no_steamgriddb_options, Toast.LENGTH_LONG).show();
                        return;
                    }
                    showSteamGridDbHeroGrid(urls);
                });
    }

    private void showSteamGridDbHeroGrid(List<String> urls) {
        GridView grid = new GridView(this);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(10));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(dp(12), dp(12), dp(12), dp(12));
        grid.setClipToPadding(false);

        grid.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return urls.size();
            }

            @Override
            public Object getItem(int position) {
                return urls.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                FrameLayout cell;
                ImageView image;
                if (convertView instanceof FrameLayout) {
                    cell = (FrameLayout) convertView;
                    image = (ImageView) cell.getChildAt(0);
                } else {
                    cell = new FrameLayout(AppView.this);
                    cell.setBackgroundResource(R.drawable.deck_input_background);
                    image = new ImageView(AppView.this);
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    cell.addView(image, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    cell.setLayoutParams(new AbsListView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(116)));
                }
                String url = urls.get(position);
                image.setTag(url);
                image.setImageDrawable(null);
                SteamGridDbClient.loadImage(getApplicationContext(), url, bitmap -> {
                    if (bitmap != null && url.equals(image.getTag())) {
                        image.setImageBitmap(bitmap);
                    }
                });
                return cell;
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.background_choose_steamgriddb)
                .setView(grid)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            String url = urls.get(position);
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putString(getSteamGridDbBackgroundKey(selectedApp.app.getAppId()), url)
                    .remove(getLocalBackgroundKey(selectedApp.app.getAppId()))
                    .apply();
            lastArtworkAppId = -1;
            applyBackgroundForSelectedApp(selectedApp, selectedItemView);
            dialog.dismiss();
        });
        dialog.show();
    }

    private void applyBackgroundForSelectedApp(AppObject app, View itemView) {
        if (app == null || gameHeroBackground == null) return;
        applyBackgroundScaleMode();

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String localUri = preferences.getString(getLocalBackgroundKey(app.app.getAppId()), "");
        if (!localUri.isEmpty()) {
            try {
                gameHeroBackground.setImageURI(Uri.parse(localUri));
                gameHeroBackground.setAlpha(0.58f);
                lastArtworkAppId = app.app.getAppId();
                return;
            } catch (RuntimeException e) {
                LimeLog.warning("Unable to load selected background image: " + e.getMessage());
                preferences.edit().remove(getLocalBackgroundKey(app.app.getAppId())).apply();
            }
        }

        ImageView tileImage = itemView != null ? itemView.findViewById(R.id.grid_image) : null;
        if (tileImage != null && tileImage.getDrawable() != null) {
            gameHeroBackground.setImageDrawable(tileImage.getDrawable());
            gameHeroBackground.setAlpha(0.24f);
        } else {
            gameHeroBackground.setImageDrawable(null);
        }
        loadSteamGridDbArtwork(app);
    }

    private void updateSelectedApp(AppObject app, View itemView) {
        if (app == null) return;
        boolean selectionChanged = selectedApp == null ||
                selectedApp.app.getAppId() != app.app.getAppId();
        selectedApp = app;
        selectedItemView = itemView;
        gameTitle.setText(app.app.getAppName());

        String metadata = getString(R.string.meta_sunshine_game, app.app.getAppId());
        if (app.app.isHdrSupported()) metadata += getString(R.string.meta_hdr_suffix);
        if (app.isRunning) metadata += getString(R.string.meta_running_suffix);
        if (steamGridDbApiKey.isEmpty()) metadata += getString(R.string.meta_steamgriddb_setup);
        gameMeta.setText(metadata);

        gameLogo.setImageDrawable(null);
        gameLogo.setVisibility(View.GONE);
        if (selectionChanged) lastArtworkAppId = -1;

        applyBackgroundForSelectedApp(app, itemView);
    }

    private void loadSteamGridDbArtwork(AppObject app) {
        if (app == null || lastArtworkAppId == app.app.getAppId()) return;
        final int requestedAppId = app.app.getAppId();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String selectedUrl = preferences.getString(getSteamGridDbBackgroundKey(requestedAppId), "");
        lastArtworkAppId = requestedAppId;

        if (!selectedUrl.isEmpty()) {
            SteamGridDbClient.loadImage(getApplicationContext(), selectedUrl, bitmap -> {
                if (isFinishing() || selectedApp == null ||
                        selectedApp.app.getAppId() != requestedAppId) return;
                if (bitmap != null) {
                    gameHeroBackground.setImageBitmap(bitmap);
                    gameHeroBackground.setAlpha(0.58f);
                }
            });
            return;
        }

        if (steamGridDbApiKey.isEmpty()) return;
        SteamGridDbClient.loadArtwork(getApplicationContext(), app.app.getAppName(),
                steamGridDbApiKey, artwork -> {
                    if (isFinishing() || selectedApp == null ||
                            selectedApp.app.getAppId() != requestedAppId) return;
                    if (artwork.hero != null) {
                        gameHeroBackground.setImageBitmap(artwork.hero);
                        gameHeroBackground.setAlpha(0.58f);
                    }
                    if (artwork.logo != null) {
                        gameLogo.setImageBitmap(artwork.logo);
                        gameLogo.setVisibility(View.VISIBLE);
                    }
                });
    }

    private void handleAppClick(AppObject app, View itemView) {
        if (app == null) return;
        selectedApp = app;
        selectedItemView = itemView;
        if (lastRunningAppId != 0) {
            if (prefConfig.resumeWithoutConfirm && lastRunningAppId == app.app.getAppId()) {
                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder,
                        prefConfig.useVirtualDisplay);
            } else if (itemView != null) {
                openContextMenu(itemView);
            }
        } else if (prefConfig.useVirtualDisplay &&
                !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
            UiHelper.displayVdisplayConfirmationDialog(AppView.this, computer,
                    () -> ServerHelper.doStart(AppView.this, app.app, computer,
                            managerBinder, true), null);
        } else {
            ServerHelper.doStart(AppView.this, app.app, computer, managerBinder,
                    prefConfig.useVirtualDisplay);
        }
    }

    private void updateUiWithServerinfo(final ComputerDetails details) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                    // Look through our current app list to tag the running app
                for (int i = 0; i < appGridAdapter.getCount(); i++) {
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // There can only be one or zero apps running.
                    if (existingApp.isRunning &&
                            existingApp.app.getAppId() == details.runningGameId) {
                        // This app was running and still is, so we're done now
                        return;
                    }
                    else if (existingApp.app.getAppId() == details.runningGameId) {
                        // This app wasn't running but now is
                        existingApp.isRunning = true;
                        updated = true;
                    }
                    else if (existingApp.isRunning) {
                        // This app was running but now isn't
                        existingApp.isRunning = false;
                        updated = true;
                    }
                    else {
                        // This app wasn't running and still isn't
                    }
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    private void updateUiWithAppList(final List<NvApp> appList) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                // First handle app updates and additions
                for (NvApp app : appList) {
                    boolean foundExistingApp = false;

                    // Try to update an existing app in the list first
                    for (int i = 0; i < appGridAdapter.getCount(); i++) {
                        AppObject existingApp = (AppObject) appGridAdapter.getItem(i);
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            // Found the app; update its properties
                            if (!existingApp.app.getAppName().equals(app.getAppName())) {
                                existingApp.app.setAppName(app.getAppName());
                                updated = true;
                            }

                            foundExistingApp = true;
                            break;
                        }
                    }

                    if (!foundExistingApp) {
                        // This app must be new
                        appGridAdapter.addApp(new AppObject(app));

                        // We could have a leftover shortcut from last time this PC was paired
                        // or if this app was removed then added again. Enable those shortcuts
                        // again if present.
                        shortcutHelper.enableAppShortcut(computer, app);

                        updated = true;
                    }
                }

                // Next handle app removals
                int i = 0;
                while (i < appGridAdapter.getCount()) {
                    boolean foundExistingApp = false;
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // Check if this app is in the latest list
                    for (NvApp app : appList) {
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            foundExistingApp = true;
                            break;
                        }
                    }

                    // This app was removed in the latest app list
                    if (!foundExistingApp) {
                        shortcutHelper.disableAppShortcut(computer, existingApp.app, getString(R.string.app_removed_from_pc));
                        appGridAdapter.removeApp(existingApp);
                        updated = true;

                        // Check this same index again because the item at i+1 is now at i after
                        // the removal
                        continue;
                    }

                    // Move on to the next item
                    i++;
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return PreferenceConfiguration.readPreferences(AppView.this).smallIconMode ?
                    R.layout.app_grid_view_small : R.layout.app_grid_view;
    }

    @Override
    public void receiveAdapterView(AdapterView<?> adapterView) {
        Gallery gallery = (Gallery) adapterView;
        gallery.setAdapter(appGridAdapter);
        gallery.setOnItemClickListener((parent, view, position, id) ->
                handleAppClick((AppObject) appGridAdapter.getItem(position), view));
        gallery.setOnItemLongClickListener((parent, view, position, id) -> {
            AppObject draggedApp = (AppObject) appGridAdapter.getItem(position);
            if (appGridAdapter.isAppLocked(draggedApp)) {
                Toast.makeText(AppView.this, "This app position is locked", Toast.LENGTH_SHORT).show();
                return true;
            }
            ClipData data = ClipData.newPlainText("app-position", Integer.toString(position));
            View.DragShadowBuilder shadow = new View.DragShadowBuilder(view);
            view.startDrag(data, shadow, Integer.valueOf(position), 0);
            return true;
        });
        gallery.setOnDragListener((target, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return event.getLocalState() instanceof Integer;
                case DragEvent.ACTION_DRAG_ENTERED:
                case DragEvent.ACTION_DRAG_LOCATION:
                case DragEvent.ACTION_DRAG_EXITED:
                    return true;
                case DragEvent.ACTION_DROP:
                    if (!(event.getLocalState() instanceof Integer)) return false;
                    int from = (Integer) event.getLocalState();
                    int to = -1;
                    float nearest = Float.MAX_VALUE;
                    for (int i = 0; i < gallery.getChildCount(); i++) {
                        View child = gallery.getChildAt(i);
                        float distance = Math.abs(event.getX() - (child.getLeft() + child.getRight()) / 2.0f);
                        if (distance < nearest) {
                            nearest = distance;
                            to = gallery.getPositionForView(child);
                        }
                    }
                    if (to >= 0) appGridAdapter.moveApp(from, to);
                    return to >= 0;
                case DragEvent.ACTION_DRAG_ENDED:
                    return true;
                default:
                    return true;
            }
        });
        gallery.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < appGridAdapter.getCount()) {
                    updateSelectedApp((AppObject) appGridAdapter.getItem(position), view);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedApp = null;
                selectedItemView = null;
            }
        });
        gallery.setUnselectedAlpha(0.72f);
        UiHelper.applyStatusBarPadding(gallery);
        registerForContextMenu(gallery);
        gallery.requestFocus();
        if (gallery.getCount() > 0) gallery.setSelection(0);
    }

    public static class AppObject {
        public final NvApp app;
        public boolean isRunning;
        public boolean isHidden;

        public AppObject(NvApp app) {
            if (app == null) {
                throw new IllegalArgumentException("app must not be null");
            }
            this.app = app;
        }

        @Override
        public String toString() {
            return app.getAppName();
        }
    }
}
