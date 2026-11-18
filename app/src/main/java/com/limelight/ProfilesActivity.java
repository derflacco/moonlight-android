package com.limelight;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.widget.ImageButton;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.limelight.profiles.ProfilesAdapter;
import com.limelight.profiles.ProfilesManager;
import com.limelight.utils.UiHelper;

public class ProfilesActivity extends AppCompatActivity implements ProfilesManager.ProfileChangeListener {
    private ProfilesAdapter adapter;
    private RecyclerView recyclerView;
    private View emptyState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profiles);

        Toolbar toolbar = findViewById(R.id.profilesToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // Setup RecyclerView
        recyclerView = findViewById(R.id.profilesRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new ProfilesAdapter(this);
        recyclerView.setAdapter(adapter);

        // Setup empty state
        emptyState = findViewById(R.id.emptyState);

        // Setup FloatingActionButton
        FloatingActionButton fab = findViewById(R.id.addProfileFab);
        fab.setOnClickListener(v -> {
            Intent intent = new Intent(this, EditProfileActivity.class);
            startActivity(intent);
        });

        // Register for profile changes
        ProfilesManager.getInstance().addListener(this);

        updateUI();

        UiHelper.notifyNewRootView(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ProfilesManager.getInstance().removeListener(this);
    }

    @Override
    public void onProfilesChanged() {
        runOnUiThread(this::updateUI);
    }

    private void updateUI() {
        int profileCount = ProfilesManager.getInstance().getProfiles().size();
        if (profileCount == 0) {
            recyclerView.setVisibility(View.GONE);
            emptyState.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
        }
        adapter.notifyDataSetChanged();
    }
}