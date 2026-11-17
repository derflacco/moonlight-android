package com.limelight.ui;

import android.widget.AdapterView;

public interface AdapterFragmentCallbacks {
    int getAdapterFragmentLayoutId();
    void receiveAdapterView(AdapterView<?> adapterView);
}
