package com.limelight.preferences;

import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.ListAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.limelight.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * ListPreference dialog for frame pacing with non-selectable section headers.
 *
 * Groups (as requested):
 *  - Latency: warp2, warp, latency, gpu-raw
 *  - Vsync: balanced
 *  - Quality: smoothness, cap-fps
 *
 * Keeps STOCK entry labels (it uses ListPreference entries[]).
 */
public final class FramePacingGroupedDialogFragment extends DialogFragment {

    private static final String ARG_KEY = "key";

    public static FramePacingGroupedDialogFragment newInstance(@NonNull String key) {
        FramePacingGroupedDialogFragment f = new FramePacingGroupedDialogFragment();
        Bundle b = new Bundle(1);
        b.putString(ARG_KEY, key);
        f.setArguments(b);
        return f;
    }

    private static final class Row {
        final boolean isHeader;
        final int entryIndex; // >=0 for selectable items, -1 for headers
        final CharSequence text;

        Row(boolean isHeader, int entryIndex, CharSequence text) {
            this.isHeader = isHeader;
            this.entryIndex = entryIndex;
            this.text = text;
        }
    }

    private static final class RowsAdapter extends BaseAdapter implements ListAdapter {

        private final LayoutInflater inflater;
        private final List<Row> rows;
        private final float density;

        RowsAdapter(@NonNull android.content.Context ctx, @NonNull List<Row> rows) {
            this.inflater = LayoutInflater.from(ctx);
            this.rows = rows;
            this.density = ctx.getResources().getDisplayMetrics().density;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return !rows.get(position).isHeader;
        }

        int getEntryIndex(int position) {
            return rows.get(position).entryIndex;
        }

        boolean isHeader(int position) {
            return rows.get(position).isHeader;
        }

        @Override
        public int getViewTypeCount() {
            return 2; // header, item
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).isHeader ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row r = rows.get(position);

            if (r.isHeader) {
                TextView tv;
                if (convertView instanceof TextView) {
                    tv = (TextView) convertView;
                } else {
                    tv = (TextView) inflater.inflate(android.R.layout.simple_list_item_1, parent, false);
                }

                tv.setText(r.text);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setEnabled(false);

                // Compact header styling
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                int padH = dp(16);
                int padTop = dp(14);
                int padBottom = dp(6);
                tv.setPadding(padH, padTop, padH, padBottom);

                return tv;
            } else {
                CheckedTextView ctv;
                if (convertView instanceof CheckedTextView) {
                    ctv = (CheckedTextView) convertView;
                } else {
                    ctv = (CheckedTextView) inflater.inflate(
                            android.R.layout.simple_list_item_single_choice, parent, false);
                }

                ctv.setText(r.text);
                return ctv;
            }
        }

        private int dp(int dp) {
            return (int) (dp * density + 0.5f);
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final String key = (getArguments() != null) ? getArguments().getString(ARG_KEY) : null;
        if (key == null) {
            throw new IllegalStateException("Missing preference key");
        }

        final PreferenceFragmentCompat host = (PreferenceFragmentCompat) getTargetFragment();
        if (host == null) {
            throw new IllegalStateException("Target fragment is null");
        }

        final Preference p = host.findPreference(key);
        if (!(p instanceof ListPreference)) {
            throw new IllegalStateException("Preference is not a ListPreference: " + key);
        }

        final ListPreference lp = (ListPreference) p;

        final CharSequence[] entries = lp.getEntries();
        final CharSequence[] entryValues = lp.getEntryValues();
        if (entries == null || entryValues == null || entries.length != entryValues.length) {
            throw new IllegalStateException("ListPreference entries/values invalid for key: " + key);
        }

        final HashSet<Integer> usedIndices = new HashSet<>(16);
        final List<Row> rows = new ArrayList<>(16);

        // --- Latency group ---
        addHeader(rows, R.string.pacing_group_latency);
        addValueRow(rows, entries, entryValues, usedIndices, "warp2");
        addValueRow(rows, entries, entryValues, usedIndices, "warp");
        addValueRow(rows, entries, entryValues, usedIndices, "latency");
        addValueRow(rows, entries, entryValues, usedIndices, "gpu-raw");

        // --- Vsync group ---
        addHeader(rows, R.string.pacing_group_vsync);
        addValueRow(rows, entries, entryValues, usedIndices, "balanced");

        // --- Quality group ---
        addHeader(rows, R.string.pacing_group_quality);
        addValueRow(rows, entries, entryValues, usedIndices, "smoothness");
        addValueRow(rows, entries, entryValues, usedIndices, "cap-fps");

        // Optional: keep any remaining modes visible (stock labels), under "Other"
        final List<Row> leftovers = new ArrayList<>();
        for (int i = 0; i < entryValues.length; i++) {
            if (!usedIndices.contains(i)) {
                leftovers.add(new Row(false, i, entries[i]));
            }
        }
        if (!leftovers.isEmpty()) {
            addHeader(rows, R.string.pacing_group_other);
            rows.addAll(leftovers);
        }

        final RowsAdapter adapter = new RowsAdapter(requireContext(), rows);

        final int currentEntryIndex = lp.findIndexOfValue(lp.getValue());
        final int checkedAdapterPos = findAdapterPosForEntryIndex(rows, currentEntryIndex);

        AlertDialog.Builder b = new AlertDialog.Builder(requireContext());
        b.setTitle(lp.getTitle());
        b.setSingleChoiceItems(adapter, checkedAdapterPos, (dialog, which) -> {
            if (adapter.isHeader(which)) return;

            int entryIndex = adapter.getEntryIndex(which);
            if (entryIndex < 0 || entryIndex >= entryValues.length) return;

            final String newValue = entryValues[entryIndex].toString();
            if (lp.callChangeListener(newValue)) {
                lp.setValue(newValue);
            }
            dialog.dismiss();
        });
        b.setNegativeButton(android.R.string.cancel, null);

        return b.create();
    }

    private void addHeader(List<Row> rows, int resId) {
        rows.add(new Row(true, -1, requireContext().getString(resId)));
    }

    private static void addValueRow(List<Row> outRows,
                                    CharSequence[] entries,
                                    CharSequence[] entryValues,
                                    HashSet<Integer> usedIndices,
                                    String value) {
        int idx = findIndexOf(entryValues, value);
        if (idx >= 0) {
            usedIndices.add(idx);
            outRows.add(new Row(false, idx, entries[idx])); // STOCK label from entries[]
        }
    }

    private static int findIndexOf(CharSequence[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (value.equals(String.valueOf(values[i]))) return i;
        }
        return -1;
    }

    private static int findAdapterPosForEntryIndex(List<Row> rows, int entryIndex) {
        if (entryIndex < 0) return -1;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (!r.isHeader && r.entryIndex == entryIndex) return i;
        }
        return -1;
    }
}
