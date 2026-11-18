package com.limelight.grid;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.limelight.AppView;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.MemoryAssetLoader;
import com.limelight.grid.assets.NetworkAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

@SuppressWarnings("unchecked")
public class AppGridAdapter extends GenericGridAdapter<AppView.AppObject> {
    private static final int ART_WIDTH_PX = 300;
    private static final int SMALL_WIDTH_DP = 110;
    private static final int LARGE_WIDTH_DP = 170;

    private final ComputerDetails computer;
    private final String uniqueId;
    private final boolean showHiddenApps;

    private CachedAppAssetLoader loader;
    private Set<Integer> hiddenAppIds = new HashSet<>();
    private ArrayList<AppView.AppObject> allApps = new ArrayList<>();
    private final Map<Integer, Integer> savedOrder = new HashMap<>();
    private final SharedPreferences orderPreferences;
    private final Set<Integer> lockedAppIds = new HashSet<>();
    private final SharedPreferences lockPreferences;

    public AppGridAdapter(Context context, PreferenceConfiguration prefs, ComputerDetails computer, String uniqueId, boolean showHiddenApps) {
        super(context, getLayoutIdForPreferences(prefs));

        this.computer = computer;
        this.uniqueId = uniqueId;
        this.showHiddenApps = showHiddenApps;
        this.orderPreferences = context.getSharedPreferences("app_order", Context.MODE_PRIVATE);
        this.lockPreferences = context.getSharedPreferences("app_grid_locks", Context.MODE_PRIVATE);
        for (String id : lockPreferences.getStringSet(computer.uuid, new HashSet<String>())) {
            try { lockedAppIds.add(Integer.parseInt(id)); } catch (NumberFormatException ignored) { }
        }

        String storedOrder = this.orderPreferences.getString(computer.uuid, "");
        if (!storedOrder.isEmpty()) {
            String[] appIds = storedOrder.split(",");
            for (String appId : appIds) {
                try {
                    savedOrder.put(Integer.parseInt(appId), savedOrder.size());
                }
                catch (NumberFormatException ignored) {
                    // Ignore invalid entries from an older or damaged preference value.
                }
            }
        }

        updateLayoutWithPreferences(context, prefs);
    }

    public void updateHiddenApps(Set<Integer> newHiddenAppIds, boolean hideImmediately) {
        this.hiddenAppIds.clear();
        this.hiddenAppIds.addAll(newHiddenAppIds);

        if (hideImmediately) {
            // Reconstruct the itemList with the new hidden app set
            itemList.clear();
            for (AppView.AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());

                if (!app.isHidden || showHiddenApps) {
                    itemList.add(app);
                }
            }
        }
        else {
            // Just update the isHidden state to show the correct UI indication
            for (AppView.AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());
            }
        }

        notifyDataSetChanged();
    }

    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        if (prefs.smallIconMode) {
            return R.layout.app_grid_item_small;
        }
        else {
            return R.layout.app_grid_item;
        }
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        int dpi = context.getResources().getDisplayMetrics().densityDpi;
        int dp;

        if (prefs.smallIconMode) {
            dp = SMALL_WIDTH_DP;
        }
        else {
            dp = LARGE_WIDTH_DP;
        }

        double scalingDivisor = ART_WIDTH_PX / (dp * (dpi / 160.0));
        if (scalingDivisor < 1.0) {
            // We don't want to make them bigger before draw-time
            scalingDivisor = 1.0;
        }
        LimeLog.info("Art scaling divisor: " + scalingDivisor);

        if (loader != null) {
            // Cancel operations on the old loader
            cancelQueuedOperations();
        }

        this.loader = new CachedAppAssetLoader(computer, scalingDivisor,
                new NetworkAssetLoader(context, uniqueId),
                new MemoryAssetLoader(),
                new DiskAssetLoader(context),
                BitmapFactory.decodeResource(context.getResources(), R.drawable.no_app_image));

        // This will trigger the view to reload with the new layout
        setLayoutId(getLayoutIdForPreferences(prefs));
    }

    public void cancelQueuedOperations() {
        loader.cancelForegroundLoads();
        loader.cancelBackgroundLoads();
        loader.freeCacheMemory();
    }

    private static void sortList(List<AppView.AppObject> list) {
        Collections.sort(list, new Comparator<AppView.AppObject>() {
            @Override
            public int compare(AppView.AppObject lhs, AppView.AppObject rhs) {
                int lIndex = lhs.app.getAppIndex();
                int rIndex = rhs.app.getAppIndex();
                if (lIndex == rIndex) {
                    return lhs.app.getAppName().toLowerCase().compareTo(rhs.app.getAppName().toLowerCase());
                } else {
                    return lIndex - rIndex;
                }
            }
        });
    }

    private void applySavedOrder() {
        if (savedOrder.isEmpty()) {
            sortList(itemList);
            sortList(allApps);
            return;
        }
        Comparator<AppView.AppObject> comparator = (lhs, rhs) -> {
            Integer left = savedOrder.get(lhs.app.getAppId());
            Integer right = savedOrder.get(rhs.app.getAppId());
            if (left != null && right != null) return left - right;
            if (left != null) return -1;
            if (right != null) return 1;
            int index = lhs.app.getAppIndex() - rhs.app.getAppIndex();
            return index != 0 ? index : lhs.app.getAppName().compareToIgnoreCase(rhs.app.getAppName());
        };
        Collections.sort(itemList, comparator);
        Collections.sort(allApps, comparator);
    }

    public boolean isAppLocked(AppView.AppObject app) {
        return app != null && lockedAppIds.contains(app.app.getAppId());
    }

    public void setAppLocked(AppView.AppObject app, boolean locked) {
        if (app == null) return;
        int appId = app.app.getAppId();
        if (locked) lockedAppIds.add(appId);
        else lockedAppIds.remove(appId);
        Set<String> stored = new HashSet<>();
        for (Integer id : lockedAppIds) stored.add(Integer.toString(id));
        lockPreferences.edit().putStringSet(computer.uuid, stored).apply();
    }

    public void moveApp(int from, int to) {
        if (from < 0 || to < 0 || from >= itemList.size() || to >= itemList.size() || from == to) return;
        AppView.AppObject moved = itemList.remove(from);
        itemList.add(to, moved);
        savedOrder.clear();
        StringBuilder value = new StringBuilder();
        for (AppView.AppObject app : itemList) {
            int id = app.app.getAppId();
            savedOrder.put(id, savedOrder.size());
            if (value.length() > 0) value.append(',');
            value.append(id);
        }
        for (AppView.AppObject app : allApps) {
            int id = app.app.getAppId();
            if (!savedOrder.containsKey(id)) {
                savedOrder.put(id, savedOrder.size());
                if (value.length() > 0) value.append(',');
                value.append(id);
            }
        }
        orderPreferences.edit().putString(computer.uuid, value.toString()).apply();
        applySavedOrder();
        notifyDataSetChanged();
    }

    public void addApp(AppView.AppObject app) {
        // Update hidden state
        app.isHidden = hiddenAppIds.contains(app.app.getAppId());

        // Always add the app to the all apps list
        allApps.add(app);

        // Add the app to the adapter data if it's not hidden
        if (showHiddenApps || !app.isHidden) {
            // Queue a request to fetch this bitmap into cache
            loader.queueCacheLoad(app.app);

            itemList.add(app);
        }

        applySavedOrder();
    }

    public void removeApp(AppView.AppObject app) {
        itemList.remove(app);
        allApps.remove(app);
    }

    @Override
    public void clear() {
        super.clear();
        allApps.clear();
    }

    @Override
    public void populateView(View parentView, ImageView imgView, RelativeLayout gridMask, ProgressBar prgView, TextView txtView, ImageView overlayView, AppView.AppObject obj) {
        // Let the cached asset loader handle it
        loader.populateImageView(obj.app, imgView, txtView);

        if (obj.isRunning) {
            // Show the play button overlay
            overlayView.setImageResource(R.drawable.ic_play);
            overlayView.setVisibility(View.VISIBLE);
            gridMask.setBackgroundColor(0x66000000);
        }
        else {
            overlayView.setVisibility(View.GONE);
            gridMask.setBackgroundColor(0x00000000);
        }

        if (obj.isHidden) {
            parentView.setAlpha(0.40f);
        }
        else {
            parentView.setAlpha(1.0f);
        }
    }
}
