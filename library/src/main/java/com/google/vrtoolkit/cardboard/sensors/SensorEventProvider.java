package com.google.vrtoolkit.cardboard.sensors;

import android.hardware.SensorEventListener;

public interface SensorEventProvider {
    void start();

    void stop();

    void registerListener(SensorEventListener listener);

    void unregisterListener(SensorEventListener listener);
}
