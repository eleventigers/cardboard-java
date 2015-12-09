package com.google.vrtoolkit.cardboard.sensors;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.util.ArrayList;

public class DeviceSensorLooper implements SensorEventProvider {
    public static final String SENSOR_THREAD_ID = "sensor";
    private boolean mIsRunning;
    private SensorManager mSensorManager;
    private Looper mSensorLooper;
    private SensorEventListener mSensorEventListener;
    private final ArrayList<SensorEventListener> mRegisteredListeners;
    private static final int[] INPUT_SENSORS = new int[] { Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE };

    public DeviceSensorLooper(final SensorManager sensorManager) {
        super();
        this.mRegisteredListeners = new ArrayList<SensorEventListener>();
        this.mSensorManager = sensorManager;
    }

    @Override
    public void start() {
        if (this.mIsRunning) {
            return;
        }
        this.mSensorEventListener = new SensorEventListener() {
            public void onSensorChanged(final SensorEvent event) {
                for (final SensorEventListener listener : mRegisteredListeners) {
                    synchronized (listener) {
                        listener.onSensorChanged(event);
                    }
                }
            }

            public void onAccuracyChanged(final Sensor sensor, final int accuracy) {
                for (final SensorEventListener listener : mRegisteredListeners) {
                    synchronized (listener) {
                        listener.onAccuracyChanged(sensor, accuracy);
                    }
                }
            }
        };
        final HandlerThread sensorThread = buildHandlerThread();
        sensorThread.start();
        mSensorLooper = sensorThread.getLooper();
        mIsRunning = true;
    }

    protected HandlerThread buildHandlerThread() {
        return new HandlerThread(SENSOR_THREAD_ID) {
            protected void onLooperPrepared() {
                final Handler handler = new Handler(Looper.myLooper());
                for (final int sensorType : DeviceSensorLooper.INPUT_SENSORS) {
                    final Sensor sensor = mSensorManager.getDefaultSensor(sensorType);
                    mSensorManager.registerListener(mSensorEventListener, sensor, 0, handler);
                }
            }
        };
    }

    @Override
    public void stop() {
        if (!mIsRunning) {
            return;
        }
        mSensorManager.unregisterListener(this.mSensorEventListener);
        mSensorEventListener = null;
        mSensorLooper.quit();
        mSensorLooper = null;
        mIsRunning = false;
    }

    @Override
    public void registerListener(final SensorEventListener listener) {
        synchronized (mRegisteredListeners) {
            mRegisteredListeners.add(listener);
        }
    }

    @Override
    public void unregisterListener(final SensorEventListener listener) {
        synchronized (mRegisteredListeners) {
            mRegisteredListeners.remove(listener);
        }
    }

}
