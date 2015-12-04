package com.google.vrtoolkit.cardboard.sensors;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.TagLostException;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Handler;
import android.util.Log;

import com.google.vrtoolkit.cardboard.CardboardDeviceParams;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class NfcSensor {

    private static final String TAG = "NfcSensor";
    private static final int MAX_CONNECTION_FAILURES = 1;
    private static final long NFC_POLLING_INTERVAL_MS = 250L;

    private static NfcSensor sInstance;
    private final Context mContext;
    private final NfcAdapter mNfcAdapter;
    private final Object mTagLock;
    private final List<ListenerHelper> mListeners;
    private IntentFilter[] mNfcIntentFilters;
    private Ndef mCurrentNdef;
    private Tag mCurrentTag;
    private boolean mCurrentTagIsCardboard;
    private Timer mNfcDisconnectTimer;
    private int mTagConnectionFailures;
    
    public static NfcSensor getInstance(final Context context) {
        if (NfcSensor.sInstance == null) {
            NfcSensor.sInstance = new NfcSensor(context);
        }
        return NfcSensor.sInstance;
    }
    
    private NfcSensor(final Context context) {
        super();
        mContext = context.getApplicationContext();
        mNfcAdapter = NfcAdapter.getDefaultAdapter(this.mContext);
        mListeners = new ArrayList<>();
        mTagLock = new Object();
        if (mNfcAdapter == null) {
            return;
        }
        final IntentFilter ndefIntentFilter = new IntentFilter("android.nfc.action.NDEF_DISCOVERED");
        ndefIntentFilter.addAction("android.nfc.action.TECH_DISCOVERED");
        ndefIntentFilter.addAction("android.nfc.action.TAG_DISCOVERED");
        mNfcIntentFilters = new IntentFilter[] { ndefIntentFilter };
        mContext.registerReceiver(new BroadcastReceiver() {
            public void onReceive(final Context context, final Intent intent) {
                NfcSensor.this.onNfcIntent(intent);
            }
        }, ndefIntentFilter);
    }
    
    public void addOnCardboardNfcListener(final OnCardboardNfcListener listener) {
        if (listener == null) {
            return;
        }
        synchronized (mListeners) {
            for (final ListenerHelper helper : mListeners) {
                if (helper.getListener() == listener) {
                    return;
                }
            }
            mListeners.add(new ListenerHelper(listener, new Handler()));
        }
    }
    
    public void removeOnCardboardNfcListener(final OnCardboardNfcListener listener) {
        if (listener == null) {
            return;
        }
        synchronized (mListeners) {
            Iterator<ListenerHelper> iterator = mListeners.iterator();
            while (iterator.hasNext()) {
                ListenerHelper helper = iterator.next();
                if (helper.getListener() == listener) {
                    iterator.remove();
                }
            }
        }
    }
    
    public boolean isNfcSupported() {
        return mNfcAdapter != null;
    }
    
    public boolean isNfcEnabled() {
        return isNfcSupported() && mNfcAdapter.isEnabled();
    }
    
    public boolean isDeviceInCardboard() {
        synchronized (mTagLock) {
            return mCurrentTagIsCardboard;
        }
    }
    
    public NdefMessage getTagContents() {
        synchronized (mTagLock) {
            return (mCurrentNdef != null) ? mCurrentNdef.getCachedNdefMessage() : null;
        }
    }
    
    public NdefMessage getCurrentTagContents() throws TagLostException, IOException, FormatException {
        synchronized (mTagLock) {
            return (mCurrentNdef != null) ? mCurrentNdef.getNdefMessage() : null;
        }
    }
    
    public int getTagCapacity() {
        synchronized (mTagLock) {
            if (mCurrentNdef == null) {
                throw new IllegalStateException("No NFC tag");
            }
            return this.mCurrentNdef.getMaxSize();
        }
    }
    
    public void writeUri(final Uri uri) throws TagLostException, IOException, IllegalArgumentException {
        synchronized (mTagLock) {
            if (mCurrentTag == null) {
                throw new IllegalStateException("No NFC tag found");
            }
            NdefMessage currentMessage = null;
            NdefMessage newMessage = null;
            final NdefRecord newRecord = NdefRecord.createUri(uri);
            try {
                currentMessage = this.getCurrentTagContents();
            } catch (Exception e3) {
                currentMessage = this.getTagContents();
            }
            if (currentMessage != null) {
                final ArrayList<NdefRecord> newRecords = new ArrayList<NdefRecord>();
                boolean recordFound = false;
                for (final NdefRecord record : currentMessage.getRecords()) {
                    if (this.isCardboardNdefRecord(record)) {
                        if (!recordFound) {
                            newRecords.add(newRecord);
                            recordFound = true;
                        }
                    } else {
                        newRecords.add(record);
                    }
                }
                newMessage = new NdefMessage(newRecords.toArray(new NdefRecord[newRecords.size()]));
            }
            if (newMessage == null) {
                newMessage = new NdefMessage(new NdefRecord[] { newRecord });
            }
            Label_0432: {
                if (mCurrentNdef != null) {
                    if (!mCurrentNdef.isConnected()) {
                        mCurrentNdef.connect();
                    }
                    if (mCurrentNdef.getMaxSize() < newMessage.getByteArrayLength()) {
                        throw new IllegalArgumentException(new StringBuilder(82)
                                .append("Not enough capacity in NFC tag. Capacity: ")
                                .append(mCurrentNdef.getMaxSize()).append(" bytes, ")
                                .append(newMessage.getByteArrayLength()).append(" required.")
                                .toString());
                    }
                    try {
                        mCurrentNdef.writeNdefMessage(newMessage);
                        break Label_0432;
                    } catch (FormatException e) {
                        final String s = "Internal error when writing to NFC tag: ";
                        final String value = String.valueOf(e.toString());
                        throw new RuntimeException((value.length() != 0) ? s.concat(value) : new String(s));
                    }
                }
                final NdefFormatable ndef = NdefFormatable.get(mCurrentTag);
                if (ndef == null) {
                    throw new IOException("Could not find a writable technology for the NFC tag");
                }
                Log.w("NfcSensor", "Ndef technology not available. Falling back to NdefFormattable.");
                try {
                    ndef.connect();
                    ndef.format(newMessage);
                    ndef.close();
                } catch (FormatException e2) {
                    final String s2 = "Internal error when writing to NFC tag: ";
                    final String value2 = String.valueOf(e2.toString());
                    throw new RuntimeException((value2.length() != 0) ? s2.concat(value2) : new String(s2));
                }
            }
            this.onNewNfcTag(mCurrentTag);
        }
    }
    
    public void onResume(final Activity activity) {
        if (!isNfcEnabled()) {
            return;
        }
        final Intent intent = new Intent("android.nfc.action.NDEF_DISCOVERED");
        intent.setPackage(activity.getPackageName());
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);
        mNfcAdapter.enableForegroundDispatch(activity, pendingIntent, mNfcIntentFilters, null);
    }
    
    public void onPause(final Activity activity) {
        if (!isNfcEnabled()) {
            return;
        }
        mNfcAdapter.disableForegroundDispatch(activity);
    }
    
    public void onNfcIntent(final Intent intent) {
        if (!isNfcEnabled() || intent == null ||
                !mNfcIntentFilters[0].matchAction(intent.getAction())) {
            return;
        }
        onNewNfcTag((Tag) intent.getParcelableExtra("android.nfc.extra.TAG"));
    }
    
    private void onNewNfcTag(final Tag nfcTag) {
        if (nfcTag == null) {
            return;
        }
        synchronized (mTagLock) {
            final Tag previousTag = mCurrentTag;
            final Ndef previousNdef = mCurrentNdef;
            final boolean previousTagWasCardboard = mCurrentTagIsCardboard;
            closeCurrentNfcTag();
            mCurrentTag = nfcTag;
            mCurrentNdef = Ndef.get(nfcTag);
            if (mCurrentNdef == null) {
                if (previousTagWasCardboard) {
                    sendDisconnectionEvent();
                }
                return;
            }
            boolean isSameTag = false;
            if (previousNdef != null) {
                final byte[] tagId1 = mCurrentTag.getId();
                final byte[] tagId2 = previousTag.getId();
                isSameTag = (tagId1 != null && tagId2 != null && Arrays.equals(tagId1, tagId2));
                if (!isSameTag && previousTagWasCardboard) {
                    sendDisconnectionEvent();
                }
            }
            NdefMessage nfcTagContents;
            try {
                mCurrentNdef.connect();
                nfcTagContents = mCurrentNdef.getCachedNdefMessage();
            } catch (Exception e) {
                final String s = "NfcSensor";
                final String s2 = "Error reading NFC tag: ";
                final String value = String.valueOf(e.toString());
                Log.e(s, (value.length() != 0) ? s2.concat(value) : new String(s2));
                if (isSameTag && previousTagWasCardboard) {
                    sendDisconnectionEvent();
                }
                return;
            }
            mCurrentTagIsCardboard = isCardboardNdefMessage(nfcTagContents);
            if (!isSameTag && mCurrentTagIsCardboard) {
                synchronized (mListeners) {
                    for (final ListenerHelper listener : mListeners) {
                        listener.onInsertedIntoCardboard(
                                CardboardDeviceParams.createFromNfcContents(nfcTagContents));
                    }
                }
            }
            if (mCurrentTagIsCardboard) {
                mTagConnectionFailures = 0;
                (mNfcDisconnectTimer = new Timer("NFC disconnect timer")).schedule(new TimerTask() {
                    @Override
                    public void run() {
                        synchronized (NfcSensor.this.mTagLock) {
                            if (!NfcSensor.this.mCurrentNdef.isConnected()) {
                                ++NfcSensor.this.mTagConnectionFailures;
                                if (NfcSensor.this.mTagConnectionFailures > 1) {
                                    NfcSensor.this.closeCurrentNfcTag();
                                    NfcSensor.this.sendDisconnectionEvent();
                                }
                            }
                        }
                    }
                }, 250L, 250L);
            }
        }
    }
    
    private void closeCurrentNfcTag() {
        if (mNfcDisconnectTimer != null) {
            mNfcDisconnectTimer.cancel();
        }
        if (mCurrentNdef == null) {
            return;
        }
        try {
            mCurrentNdef.close();
        } catch (IOException e) {
            Log.w("NfcSensor", e.toString());
        }
        mCurrentTag = null;
        mCurrentNdef = null;
        mCurrentTagIsCardboard = false;
    }
    
    private void sendDisconnectionEvent() {
        synchronized (mListeners) {
            for (final ListenerHelper listener : mListeners) {
                listener.onRemovedFromCardboard();
            }
        }
    }
    
    private boolean isCardboardNdefMessage(final NdefMessage message) {
        if (message == null) {
            return false;
        }
        for (final NdefRecord record : message.getRecords()) {
            if (this.isCardboardNdefRecord(record)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isCardboardNdefRecord(final NdefRecord record) {
        if (record == null) {
            return false;
        }
        final Uri uri = record.toUri();
        return uri != null && CardboardDeviceParams.isCardboardUri(uri);
    }
    
    private static class ListenerHelper implements OnCardboardNfcListener {
        private OnCardboardNfcListener mListener;
        private Handler mHandler;
        
        public ListenerHelper(final OnCardboardNfcListener listener, final Handler handler) {
            super();
            mListener = listener;
            mHandler = handler;
        }
        
        public OnCardboardNfcListener getListener() {
            return this.mListener;
        }
        
        @Override
        public void onInsertedIntoCardboard(final CardboardDeviceParams deviceParams) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    ListenerHelper.this.mListener.onInsertedIntoCardboard(deviceParams);
                }
            });
        }
        
        @Override
        public void onRemovedFromCardboard() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    ListenerHelper.this.mListener.onRemovedFromCardboard();
                }
            });
        }
    }
    
    public interface OnCardboardNfcListener {
        void onInsertedIntoCardboard(CardboardDeviceParams p0);
        void onRemovedFromCardboard();
    }
}
