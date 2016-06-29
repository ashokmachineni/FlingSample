/**
 * FlingActivity.java
 *
 * Copyright (c) 2015 Amazon Technologies, Inc. All rights reserved.
 *
 * PROPRIETARY/CONFIDENTIAL
 *
 * Use is subject to license terms.
 */

package com.amazon.whisperplay.example.flingsample;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.amazon.whisperplay.fling.media.controller.RemoteMediaPlayer;
import com.amazon.whisperplay.fling.media.controller.DiscoveryController;
import com.amazon.whisperplay.fling.media.controller.RemoteMediaPlayer.FutureListener;
import com.amazon.whisperplay.fling.media.service.MediaPlayerInfo;
import com.amazon.whisperplay.fling.media.service.MediaPlayerStatus;
import com.amazon.whisperplay.fling.media.service.MediaPlayerStatus.MediaState;
import com.amazon.whisperplay.fling.media.service.CustomMediaPlayer.PlayerSeekMode;
import com.amazon.whisperplay.fling.media.service.CustomMediaPlayer.StatusListener;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class FlingActivity extends Activity implements View.OnClickListener {

    // Debugging TAG
    private static final String TAG = FlingActivity.class.getName();

    // Set selected player from device picker
    private RemoteMediaPlayer mCurrentDevice;

    // Default value to set updating status interval from player
    private static final long MONITOR_INTERVAL = 1000L;
    // Callback from player to listen media status.(onStatusChange)
    private StatusListener mListener;
    // Store status information from player
    private Status mStatus = new Status();
    private final Object mStatusLock = new Object();

    // Discovery controller that triggers start/stop discovery
    private DiscoveryController mController;

    // Lock object for mDeviceList synchronization
    private final Object mDeviceListAvailableLock = new Object();
    // Set the discovered devices from Discovery controller
    private List<RemoteMediaPlayer> mDeviceList = new LinkedList<>();
    private List<RemoteMediaPlayer> mPickerDeviceList = new LinkedList<>();
    // Comparator to sort device list with alphabet device name order
    private RemoteMediaPlayerComp mComparator = new RemoteMediaPlayerComp();

    // Application menu
    private Menu mMenu;
    // Device picker adapter
    private ArrayAdapter<String> mPickerAdapter;
    // List of device picker items.
    private List<String> mPickerList = new ArrayList<>();

    // MediaSource manager to load media information from external storage
    private MediaSourceManager mManager;
    // ListView for Media Source list
    private ListView mMediaListView;

    // Progress(SeekBar) of media duration
    private SeekBar mSeekBar;
    private Long mMediaDuration = Long.valueOf(0);
    private boolean mDurationSet = false;
    // TextView to show total and current duration as number
    private TextView mTotalDuration;
    private TextView mCurrentDuration;
    // Current Title and Media Status to show
    private TextView mCurrentStatusView;
    private TextView mMediaTitleView;
    private boolean mMediaTitleSet = false;

    // Playback buttons as ImageView
    private ImageView mBackwardButton;
    private ImageView mPlayButton;
    private ImageView mPauseButton;
    private ImageView mStopButton;
    private ImageView mForwardButton;

    // Shared preference name
    private static final String APP_SHARED_PREF_NAME = "com.amazon.whisperplay.example.fling";
    // Last stored player uuid from shared preference
    private String mLastPlayerId;
    // Error count for failing remote call
    private static final int MAX_ERRORS = 5;
    private int mErrorCount = 0;

    private DiscoveryController.IDiscoveryListener mDiscovery =
            new DiscoveryController.IDiscoveryListener() {

        @Override
        public void playerDiscovered(final RemoteMediaPlayer device) {
            synchronized (mDeviceListAvailableLock) {
                int threadId = android.os.Process.myTid();
                if (mDeviceList.contains(device)) {
                    mDeviceList.remove(device);
                    Log.i(TAG, "["+threadId+"]"+"playerDiscovered(updating): " + device.getName());
                } else {
                    Log.i(TAG, "["+threadId+"]"+"playerDiscovered(adding): " + device.getName());
                }
                mDeviceList.add(device);
                // start rejoining with discovered device
                if (mLastPlayerId != null && mCurrentDevice == null) {
                    if (device.getUniqueIdentifier().equalsIgnoreCase(mLastPlayerId)) {
                        new UpdateSessionTask().execute(device);
                    }
                }
                triggerUpdate();
            }
        }

        @Override
        public void playerLost(final RemoteMediaPlayer device) {
            synchronized (mDeviceListAvailableLock) {
                if (mDeviceList.contains(device)) {
                    int threadId = android.os.Process.myTid();
                    Log.i(TAG, "["+threadId+"]"+"playerLost(removing): " + device.getName());
                    if (device.equals(mCurrentDevice) && mListener != null) {
                        Log.i(TAG, "["+threadId+"]"+"playerLost(removing): " + mListener.toString());
                        device.removeStatusListener(mListener);
                        mCurrentDevice = null;
                    }
                    mDeviceList.remove(device);
                    triggerUpdate();
                }
            }
        }

        @Override
        public void discoveryFailure() {
            Log.e(TAG, "Discovery Failure");
        }

        private void triggerUpdate() {
            // It should be run in main thread since it is updating Adapter.
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mPickerDeviceList = mDeviceList;
                    Collections.sort(mPickerDeviceList, mComparator);
                    mPickerList.clear();
                    for (RemoteMediaPlayer device : mPickerDeviceList) {
                        mPickerList.add(device.getName());
                    }
                    mPickerAdapter.notifyDataSetChanged();
                    // Calling onPrepareOptionsMenu() to update picker icon
                    invalidateOptionsMenu();
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Hide Home icon
        getActionBar().setDisplayShowHomeEnabled(false);
        // Initialize UI resources
        mMediaTitleView = (TextView) findViewById(R.id.currentmediatitle);
        mCurrentStatusView = (TextView) findViewById(R.id.currentstatus);
        mCurrentDuration = (TextView) findViewById(R.id.currentDuration);
        mTotalDuration = (TextView) findViewById(R.id.totalDuration);
        mMediaListView = (ListView) findViewById(R.id.mediaList);
        mSeekBar = (SeekBar) findViewById(R.id.seekBar);
        mBackwardButton = (ImageView) findViewById(R.id.backward);
        mPlayButton = (ImageView) findViewById(R.id.play);
        mPauseButton = (ImageView) findViewById(R.id.pause);
        mStopButton = (ImageView) findViewById(R.id.stop);
        mForwardButton = (ImageView) findViewById(R.id.forward);
        // Create MediaSourceManager
        mManager = new MediaSourceManager(this);
        // Create DiscoveryController
        mController = new DiscoveryController(this);
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "onResume");
        super.onResume();
        // Playback controller will be enabled when connectionUpdate succeed.
        setPlaybackControllWorking(false);
        mListener = new Monitor();
        // Set if last player was saved
        retrieveLastPlayerIfExist();
        // Start Discovery Controller
        Log.i(TAG, "onResume - start Discovery");
        mController.start("amzn.thin.pl", mDiscovery);
        // Set Adapter with media sources
        mMediaListView.setAdapter(new MediaListAdapter(this, mManager.getAllSources()));
        // Create device picker adapter
        mPickerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_activated_1, mPickerList);
        // When user selects media from listView, start fling directly.
        mMediaListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                synchronized (mDeviceListAvailableLock) {
                    if (mCurrentDevice != null) {
                        Log.i(TAG, "setOnItemClickListener - Start fling.");
                        final ListView lv = (ListView) findViewById(R.id.mediaList);
                        MediaListAdapter ad = (MediaListAdapter)lv.getAdapter();
                        int selectedPosition = lv.getCheckedItemPosition();
                        if (selectedPosition >= 0) {
                            MediaSourceManager.MediaSource source =
                                    (MediaSourceManager.MediaSource)ad.getItem(selectedPosition);
                            Log.i(TAG, "setOnItemClickListener - Source =" + source);
                            Log.i(TAG, "setOnItemClickListener - Start fling:target:"
                                    + mCurrentDevice.toString());
                            JSONObject metadata = new JSONObject(source.metadata);
                            fling(mCurrentDevice, source.url, metadata.toString());
                        } else {
                            Log.i(TAG, "setOnItemClickListener - Select item first");
                        }
                    } else {
                        Log.i(TAG, "setOnItemClickListener - Target device is null");
                    }
                }
            }
        });
        // When user moves progress bar, seek absolute position from current player.
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {}
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (mCurrentDevice != null) {
                    Log.i(TAG, "SeekBar(Absolute seek) - " + convertTime(seekBar.getProgress()));
                    mCurrentDevice.seek(PlayerSeekMode.Absolute, seekBar.getProgress())
                            .getAsync(new ErrorResultHandler("Seek...","Error Seeking"));
                }
            }
        });
        // initialize error count
        mErrorCount = 0;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        this.mMenu = menu;
        getMenuInflater().inflate(R.menu.options, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem flingButton = menu.findItem(R.id.menu_fling);
        if (mPickerDeviceList.size() > 0) {
            if (mCurrentDevice != null) {
                flingButton.setIcon(R.drawable.ic_whisperplay_default_blue_light_24dp);
                setPlaybackControllWorking(true);
            } else {
                flingButton.setIcon(R.drawable.ic_whisperplay_default_light_24dp);
                setPlaybackControllWorking(false);
            }
            setPickerIconVisibility(true);
        } else {
            flingButton.setIcon(R.drawable.ic_whisperplay_default_light_24dp);
            setPickerIconVisibility(false);
            setPlaybackControllWorking(false);
        }
        return true;
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "onPause");
        if (mCurrentDevice != null) {
            Log.i(TAG, "onPause - removeStatusListener:mListener=" + mListener.toString());
            try {
                storeLastPlayer(true);
                Log.i(TAG, "onPause - removeStatusListener: start t=" + System.currentTimeMillis());
                mCurrentDevice.removeStatusListener(mListener).get(3000, TimeUnit.MILLISECONDS);
                Log.i(TAG, "onPause - removeStatusListener: finish t=" + System.currentTimeMillis());
            } catch (InterruptedException e) {
                Log.e(TAG, "InterruptedException. msg =" + e.getMessage());
            } catch (ExecutionException e) {
                Log.e(TAG, "ExecutionException. msg =" + e.getMessage());
            } catch (TimeoutException e) {
                Log.e(TAG, "TimeoutException. msg =" + e.getMessage());
            } finally {
                clean();
            }
        } else {
            storeLastPlayer(false);
            clean();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        Log.i(TAG, "onStop");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy");
        super.onDestroy();
    }

    private void setStatusAndTitleVisibility(boolean enable) {
        Log.i(TAG, "setStatusAndTitleVisibility:" + (enable ? "enable" : "disable"));
        if (enable) {
            mCurrentStatusView.setVisibility(View.VISIBLE);
            mMediaTitleView.setVisibility(View.VISIBLE);
        } else {
            mCurrentStatusView.setVisibility(View.INVISIBLE);
            mMediaTitleView.setVisibility(View.INVISIBLE);
            mCurrentStatusView.setText(getString(R.string.empty_text));
            mMediaTitleView.setText(getString(R.string.empty_text));
        }
    }

    private void resetMediaTitle() {
        mMediaTitleSet = false;
        mMediaTitleView.setText(getString(R.string.empty_text));
    }

    private void resetDuration() {
        Log.i(TAG, "resetDuration");
        mMediaDuration = Long.valueOf(0);
        mSeekBar.setProgress(0);
        mSeekBar.setMax(0);
        mDurationSet = false;
        mCurrentDuration.setText(convertTime(0));
        mTotalDuration.setText(convertTime(0));
    }

    private void setPlaybackControllWorking(boolean enable) {
        Log.i(TAG, "setPlaybackControllWorking:" + (enable ? "enable" : "disable"));
        mPlayButton.setEnabled(enable);
        mPauseButton.setEnabled(enable);
        mStopButton.setEnabled(enable);
        mForwardButton.setEnabled(enable);
        mBackwardButton.setEnabled(enable);
    }
    private void setProgressVisibility(boolean enable) {
        Log.i(TAG, "setProgressVisibility:" + (enable ? "enable" : "disable"));
        if (enable) {
            mSeekBar.setVisibility(View.VISIBLE);
            mCurrentDuration.setVisibility(View.VISIBLE);
            mTotalDuration.setVisibility(View.VISIBLE);

        } else {
            mCurrentDuration.setText(convertTime(0));
            mTotalDuration.setText(convertTime(0));
            mSeekBar.setMax(0);
            mSeekBar.setProgress(0);
            mSeekBar.setVisibility(View.INVISIBLE);
            mCurrentDuration.setVisibility(View.INVISIBLE);
            mTotalDuration.setVisibility(View.INVISIBLE);
        }
    }

    private void setPickerIconVisibility(boolean enable) {
        Log.i(TAG, "setPickerIconVisibility: " + (enable ? "enable" : "disable"));
        MenuItem flingButton = mMenu.findItem(R.id.menu_fling);
        flingButton.setVisible(enable);
    }

    @Override
    public void onClick(View view) {
        MediaState state;
        synchronized (mStatusLock) {
            state = mStatus.mState;
        }
        switch (view.getId()) {

            case R.id.play:
                Log.i(TAG, "onClick - PlayButton");
                if (state == MediaState.Paused || state == MediaState.ReadyToPlay) {
                    Log.i(TAG, "onClick - Start doPlay");
                    doPlay();
                } else {
                    synchronized (mDeviceListAvailableLock) {
                        if (mCurrentDevice != null) {
                            Log.i(TAG, "onClick - Enter");
                            final ListView lv = (ListView) findViewById(R.id.mediaList);
                            MediaListAdapter ad = (MediaListAdapter)lv.getAdapter();
                            int position = lv.getCheckedItemPosition();
                            if (position >= 0) {
                                MediaSourceManager.MediaSource source =
                                        (MediaSourceManager.MediaSource) ad.getItem(position);
                                Log.i(TAG, "onClick - Source =" + source);
                                JSONObject metadata = new JSONObject(source.metadata);
                                Log.i(TAG, "onClick - fling");
                                fling(mCurrentDevice, source.url, metadata.toString());
                            } else {
                                Log.i(TAG, "onClick - Media must be selected first.");
                            }
                            Log.i(TAG, "onClick - Exit");
                        } else {
                            Log.i(TAG, "onClick - Target device is null");
                        }
                    }
                }
                break;
            case R.id.pause:
                Log.i(TAG, "onClick - PauseButton");
                doPause();
                break;
            case R.id.stop:
                Log.i(TAG, "onClick - StopButton");
                doStop();
                break;
            case R.id.forward:
                Log.i(TAG, "onClick - ForwardButton");
                doFore();
                break;
            case R.id.backward:
                Log.i(TAG, "onClick - BackwardButton");
                doBack();
                break;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.menu_fling) {
            if (mCurrentDevice == null) {
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.menu_fling))
                        .setAdapter(mPickerAdapter, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int index) {
                                connectionUpdate(mPickerDeviceList.get(index));
                            }
                        })
                        .show();
                return true;
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(mCurrentDevice.getName())
                        .setNeutralButton(getString(R.string.btn_disconnect),
                                new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                connectionUpdate(null);
                                dialogInterface.dismiss();
                            }
                        })
                        .show();
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    private void connectionUpdate(final RemoteMediaPlayer target) {
        new ConnectionUpdateTask().execute(target);
    }

    private void setStatusText() {
        // This method deals with UI on main thread.
        runOnUiThread(new Runnable() {
            public void run() {
                synchronized (mStatusLock) {
                    switch (mStatus.mState) {
                        case NoSource:
                            break;
                        case PreparingMedia:
                            Log.i(TAG, "setStatusText - PreparingMedia");
                            mCurrentStatusView.setText(getString(R.string.media_preping));
                            break;
                        case ReadyToPlay:
                            Log.i(TAG, "setStatusText - ReadyToPlay");
                            mCurrentStatusView.setText(getString(R.string.media_readytoplay));
                            break;
                        case Playing:
                            Log.i(TAG, "setStatusText - Playing");
                            if (!mDurationSet) {
                                Log.i(TAG, "setStatusText - Playing: ReadyToPlay was missed." +
                                        " duration needs to be set.");
                                new TotalDurationUpdateTask().execute();

                            }
                            if (!mMediaTitleSet) {
                                Log.i(TAG, "setStatusText - Playing: ReadyToPlay was missed." +
                                        " media title needs to be set.");
                                new MediaTitleUpdateTask().execute();
                            }
                            //Update progress session
                            if (mMediaDuration > 0 && mDurationSet) {
                                Log.i(TAG, "setStatusText - Playing: Set Progress");
                                mTotalDuration.setText(String.valueOf(convertTime(mMediaDuration)));
                                mCurrentDuration.setText(String.valueOf(
                                        convertTime(mStatus.mPosition)));
                                mSeekBar.setProgress((int) mStatus.mPosition);
                            }
                            mCurrentStatusView.setText(getString(R.string.media_playing));
                            setProgressVisibility(true);
                            setStatusAndTitleVisibility(true);
                            break;
                        case Paused:
                            Log.i(TAG, "setStatusText - Paused");
                            if (!mDurationSet) {
                                new TotalDurationUpdateTask().execute();
                                new CurrentPositionUpdateTask().execute();
                            }
                            if (!mMediaTitleSet) {
                                new MediaTitleUpdateTask().execute();
                            }
                            mCurrentStatusView.setText(getString(R.string.media_paused));
                            setProgressVisibility(true);
                            setStatusAndTitleVisibility(true);
                            break;
                        case Finished:
                            Log.i(TAG, "setStatusText - Finished");
                            mCurrentStatusView.setText(getString(R.string.media_done));
                            resetDuration();
                            break;
                        case Seeking:
                            Log.i(TAG, "setStatusText - Seeking");
                            mCurrentStatusView.setText(getString(R.string.media_seeking));
                            break;
                        case Error:
                            Log.i(TAG, "setStatusText - Error");
                            mCurrentStatusView.setText(getString(R.string.media_error));
                            break;
                        default:
                            break;
                    }
                }
            }
        });
    }

    private void handleFailure( Throwable throwable, final String msg, final boolean extend ) {
        Log.e(TAG, msg, throwable);
        final String exceptionMessage = throwable.getMessage();

        mErrorCount = mErrorCount+1;
        if (mErrorCount > MAX_ERRORS) {
            errorMessagePopup(msg + (extend ? exceptionMessage : ""));
        }
    }

    private void errorMessagePopup(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.e(TAG, "errorMessagePopup: Showing the error message.");
                new AlertDialog.Builder(FlingActivity.this)
                        .setTitle(getString(R.string.communication_error))
                        .setMessage(message)
                        .setNeutralButton(getString(R.string.btn_close),
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialogInterface, int i) {
                                        dialogInterface.dismiss();
                                    }
                                })
                        .show();
                if (mCurrentDevice != null) {
                    Log.e(TAG, "errorMessagePopup: removeStatusListener. set current device to null");
                    mCurrentDevice.removeStatusListener(mListener);
                    mCurrentDevice = null;
                }
                resetDuration();
                setStatusAndTitleVisibility(false);
                setPlaybackControllWorking(false);
                setProgressVisibility(false);
                invalidateOptionsMenu();
                resetMediaTitle();
            }
        });
    }

    private void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast toast = Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT);
                toast.show();
            }
        });
    }

    private void initializeFling(final RemoteMediaPlayer target) {
        Log.i(TAG, "initializeFling - target: " + target.toString());
        mCurrentDevice = target;
        mStatus.clear();
        resetDuration();
        resetMediaTitle();
    }

    private void fling(final RemoteMediaPlayer target, final String name, final String title) {
        initializeFling(target);
        Log.i(TAG, "try setPositionUpdateInterval: " + MONITOR_INTERVAL);
        mCurrentDevice.setPositionUpdateInterval(MONITOR_INTERVAL).getAsync(
                new ErrorResultHandler("setPositionUpdateInterval",
                        "Error attempting set update interval, ignoring", true));
        Log.i(TAG, "try setMediaSource: url - " + name + " title - " + title);
        mCurrentDevice.setMediaSource(name, title, true, false).getAsync(
                new ErrorResultHandler("setMediaSource", "Error attempting to Play:", true));
        showToast("try Flinging...");
    }

    private void doPlay() {
        if (mCurrentDevice != null) {
            Log.i(TAG, "try doPlay...");
            mCurrentDevice.play().getAsync(new ErrorResultHandler("doPlay", "Error Playing"));
        }
    }

    private void doPause() {
        if (mCurrentDevice != null) {
            Log.i(TAG, "try doPause...");
            mCurrentDevice.pause().getAsync(new ErrorResultHandler("doPause", "Error Pausing"));
        }
    }

    private void doStop() {
        if (mCurrentDevice != null) {
            Log.i(TAG, "try doStop...");
            mCurrentDevice.stop().getAsync(new ErrorResultHandler("doStop", "Error Stopping"));
            mStatus.clear();
            resetDuration();
        }
    }

    private void doFore() {
        if (mCurrentDevice != null) {
            Log.i(TAG, "try doFore - seek");
            mCurrentDevice.seek(PlayerSeekMode.Relative, 10000).getAsync(
                    new ErrorResultHandler("doFore", "Error Seeking"));
        }
    }

    private void doBack() {
        if (mCurrentDevice != null) {
            Log.i(TAG, "try doBack - seek");
            mCurrentDevice.seek(PlayerSeekMode.Relative, -10000).getAsync(
                    new ErrorResultHandler("doBack", "Error Seeking"));
        }
    }

    private void retrieveLastPlayerIfExist(){
        SharedPreferences preferences = getApplicationContext().getSharedPreferences(
                APP_SHARED_PREF_NAME, Context.MODE_PRIVATE);
        mLastPlayerId = preferences.getString("lastPlayerId", null);
        Log.i(TAG, "retrieveLastPlayerIfExist - lastPlayerId=" + mLastPlayerId);
    }

    private void storeLastPlayer(boolean value) {
        SharedPreferences preferences = getApplicationContext().getSharedPreferences(
                APP_SHARED_PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        if (value) {
            if (mCurrentDevice != null) {
                editor.putString("lastPlayerId", mCurrentDevice.getUniqueIdentifier());
                editor.apply();
                Log.i(TAG, "storeLastPlayer - id:" + mCurrentDevice.getUniqueIdentifier());
            }
        } else {
            Log.i(TAG, "storeLastPlayer - remove id and clear");
            editor.clear();
            editor.apply();
        }
    }

    private static String convertTime(long time) {
        long totalSecs = time / 1000;
        long hours = totalSecs / 3600;
        long minutes = (totalSecs / 60) % 60;
        long seconds = totalSecs % 60;
        String hourString = (hours == 0) ? "00" : ((hours < 10) ? "0" + hours : "" + hours);
        String minString = (minutes == 0) ? "00" : ((minutes < 10) ? "0" + minutes : "" + minutes);
        String secString = (seconds == 0) ? "00" : ((seconds < 10) ? "0" + seconds : "" + seconds);

        return hourString + ":" + minString + ":" + secString;
    }

    private void clean() {
        Log.i(TAG, "clean - calling mController.stop()");
        mController.stop();
        mCurrentDevice = null;
        mDeviceList.clear();
        mPickerDeviceList.clear();
        resetDuration();
        resetMediaTitle();
        setStatusAndTitleVisibility(false);
        setProgressVisibility(false);
        setPickerIconVisibility(false);
        setPlaybackControllWorking(false);
    }

    private static class Status {
        public long mPosition;
        public MediaState mState;
        public MediaPlayerStatus.MediaCondition mCond;

        public synchronized void clear() {
            mPosition = -1L;
            mState = MediaState.NoSource;
        }
    }

    private class Monitor implements StatusListener {

        @Override
        public void onStatusChange(MediaPlayerStatus status, long position) {
            if (mCurrentDevice != null) {
                synchronized (mStatusLock) {
                    mStatus.mState = status.getState();
                    mStatus.mCond = status.getCondition();
                    mStatus.mPosition = position;
                    Log.i(TAG, "State Change state=" + mStatus.mState
                            + " Position=" + convertTime(position));
                    if (mStatus.mState == MediaState.ReadyToPlay) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                resetDuration();
                            }
                        });
                        new TotalDurationUpdateTask().execute();
                        new MediaTitleUpdateTask().execute();
                    }
                }
                setStatusText();
            }
        }
    }

    private class ConnectionUpdateTask extends AsyncTask<RemoteMediaPlayer, Void, Integer> {
        @Override
        protected Integer doInBackground(RemoteMediaPlayer... remoteMediaPlayers) {
            int threadId = android.os.Process.myTid();
            if (remoteMediaPlayers[0] != null) { // Connect
                RemoteMediaPlayer target = remoteMediaPlayers[0];
                try {
                    Log.i(TAG, "[" + threadId + "]" + "ConnectionUpdateTask:addStatusListener"
                            + ":target=" + target);
                    target.addStatusListener(mListener).get();
                    // Set current device after remote call succeed.
                    mCurrentDevice = target;
                    Log.i(TAG, "["+threadId+"]"+"ConnectionUpdateTask:set current device"
                            +":currentDevice="+target);
                    return 0;
                } catch (InterruptedException e) {
                    Log.e(TAG, "["+threadId+"]"+"InterruptedException msg=" + e);
                    return 2;
                } catch (ExecutionException e) {
                    Log.e(TAG, "["+threadId+"]"+"ExecutionException msg=" + e);
                    return 2;
                }
            } else { // Disconnect
                try {
                    if (mCurrentDevice != null) {
                        Log.i(TAG, "["+threadId+"]"+"ConnectionUpdateTask:removeStatusListener" +
                                ":mCurrentDevice="+mCurrentDevice+ "mListener="+mListener);
                        mCurrentDevice.removeStatusListener(mListener).get();
                    }
                } catch (InterruptedException e) {
                    Log.e(TAG, "["+threadId+"]"+"InterruptedException msg=" + e);
                } catch (ExecutionException e) {
                    Log.e(TAG, "["+threadId+"]"+"ExecutionException msg=" + e);
                } finally {
                    return 1;
                }
            }
        }

        @Override
        protected void onPostExecute(Integer result) {
            MenuItem item = mMenu.findItem(R.id.menu_fling);
            switch (result) {
                case 0:
                    // after connection
                    mErrorCount = 0;
                    Log.i(TAG, "[main]" + "ConnectionUpdateTask:onPostExecute: connection");
                    item.setIcon(R.drawable.ic_whisperplay_default_blue_light_24dp);
                    invalidateOptionsMenu();
                    new UpdateSessionTask().execute(mCurrentDevice);
                    break;
                case 1:
                    // after disconnection
                    Log.i(TAG, "[main]" + "ConnectionUpdateTask:onPostExecute: disconnection");
                    item.setIcon(R.drawable.ic_whisperplay_default_light_24dp);
                    mCurrentDevice = null;
                    invalidateOptionsMenu();
                    setProgressVisibility(false);
                    setStatusAndTitleVisibility(false);
                    resetDuration();
                    resetMediaTitle();
                    break;
                case 2:
                    // error handle
                    Log.i(TAG, "[main]" + "ConnectionUpdateTask:onPostExecute: error handle");
                    errorMessagePopup("Problem with connection. " +
                            "Try again and check the target player.");
                    break;
            }
        }
    }

    private class UpdateSessionTask extends AsyncTask<RemoteMediaPlayer, Void, MediaPlayerStatus> {
        RemoteMediaPlayer target = null;
        @Override
        protected MediaPlayerStatus doInBackground(RemoteMediaPlayer... remoteMediaPlayers) {
            target = remoteMediaPlayers[0];
            int threadId = android.os.Process.myTid();
            Log.i(TAG, "["+threadId+"]"+"UpdateSessionTask:found match: " + target.getName());
            try {
                Log.i(TAG, "["+threadId+"]"+"UpdateSessionTask:getStatus");
                return target.getStatus().get();
            } catch (InterruptedException e) {
                Log.e(TAG, "["+threadId+"]"+"InterruptedException msg=" + e);
                target = null;
                return null;
            } catch (ExecutionException e) {
                Log.e(TAG, "["+threadId+"]"+"ExecutionException msg=" + e);
                target = null;
                return null;
            }
        }

        @Override
        protected void onPostExecute(MediaPlayerStatus mediaPlayerStatus) {
            if (mediaPlayerStatus != null) {
                mCurrentDevice = target;
                Log.i(TAG, "[main]" + "UpdateSessionTask:onPostExecute:set current device:"
                        +mCurrentDevice.toString());
                mCurrentDevice.addStatusListener(mListener);
                synchronized (mStatusLock) {
                    mStatus.mState = mediaPlayerStatus.getState();
                    mStatus.mCond = mediaPlayerStatus.getCondition();
                }
                setStatusText();
                MenuItem item = mMenu.findItem(R.id.menu_fling);
                item.setIcon(R.drawable.ic_whisperplay_default_blue_light_24dp);
                setProgressVisibility(true);
                setStatusAndTitleVisibility(true);
                invalidateOptionsMenu();
            } else {
                Log.i(TAG, "[main]" + "UpdateSessionTask:onPostExecute:skip rejoin");
            }
        }
    }

    private class MediaTitleUpdateTask extends AsyncTask<Void, Void, MediaPlayerInfo> {
        @Override
        protected MediaPlayerInfo doInBackground(Void... voids) {
            int threadId = android.os.Process.myTid();
            if (mCurrentDevice != null) {
                try {
                    Log.i(TAG, "["+threadId+"]"+"MediaTitleUpdateTask:getMediaInfo");
                    return mCurrentDevice.getMediaInfo().get();
                } catch (InterruptedException e) {
                    Log.e(TAG, "["+threadId+"]"+"InterruptedException msg=" + e);
                    return null;
                } catch (ExecutionException e) {
                    Log.e(TAG, "["+threadId+"]"+"ExecutionException msg=" + e);
                    return null;
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(MediaPlayerInfo result) {
            if (result != null) {
                try {
                    JSONObject jobj = (JSONObject) new JSONTokener(result.getMetadata()).nextValue();
                    Log.i(TAG, "[main]" + "MediaTitleUpdateTask:onPostExecute:set mediaTitleView");
                    mMediaTitleView.setText((String)jobj.get("title"));
                    setStatusAndTitleVisibility(true);
                    mMediaTitleSet = true;
                } catch (JSONException e) {
                    Log.e(TAG, "Cannot parse Metadata", e);
                }
            }
        }
    }

    private class CurrentPositionUpdateTask extends AsyncTask<Void, Void, Long> {
        @Override
        protected Long doInBackground(Void... voids) {
            if (mCurrentDevice != null) {
                int threadId = android.os.Process.myTid();
                try {
                    Log.i(TAG, "["+threadId+"]"+"CurrentPositionUpdateTask:getPosition");
                    return mCurrentDevice.getPosition().get();
                } catch (InterruptedException e) {
                    Log.e(TAG, "["+threadId+"]"+"InterruptedException msg=" + e);
                    return null;
                } catch (ExecutionException e) {
                    Log.e(TAG, "["+threadId+"]"+"ExecutionException msg=" + e);
                    return null;
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Long result) {
            if (result != null) {
                Log.i(TAG, "[main]"+"CurrentPositionUpdateTask:onPostExecute:");
                mSeekBar.setProgress(result.intValue());
                mCurrentDuration.setText(convertTime(result.intValue()));
            } else {
                Log.i(TAG, "[main]" +"CurrentPositionUpdateTask:onPostExecute:result is null");
            }
        }
    }
    private class TotalDurationUpdateTask extends AsyncTask<Void, Void, Long> {
        @Override
        protected Long doInBackground(Void... voids) {
            if (mCurrentDevice != null) {
                int threadId = android.os.Process.myTid();
                try {
                    Log.i(TAG, "["+threadId+"]"+"TotalDurationUpdateTask:getDuration");
                    return mCurrentDevice.getDuration().get();
                } catch (InterruptedException e) {
                    Log.e(TAG, "["+threadId+"]"+"InterruptedException msg=" + e);
                    return null;
                } catch (ExecutionException e) {
                    Log.e(TAG, "["+threadId+"]"+"ExecutionException msg=" + e);
                    return null;
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Long result) {
            if (result != null) {
                Log.i(TAG, "[main]" + "TotalDurationUpdateTask:onPostExecute");
                mMediaDuration = result;
                mSeekBar.setMax(result.intValue());
                if (mMediaDuration > 0) {
                    Log.i(TAG, "[main]" + "TotalDurationUpdateTask:onPostExecute:setTotalDuration");
                    mTotalDuration.setText(String.valueOf(convertTime(mMediaDuration)));
                }
                mDurationSet = true;
                setProgressVisibility(true);
            } else {
                Log.i(TAG, "[main]" +"TotalDurationUpdateTask:onPostExecute:result is null");
            }
        }
    }

    private class ErrorResultHandler implements FutureListener<Void> {
        private String mCommand;
        private String mMsg;
        private boolean mExtend;

        ErrorResultHandler(String command, String msg) {
            this(command, msg, false);
        }

        ErrorResultHandler(String command, String msg, boolean extend) {
            mCommand = command;
            mMsg = msg;
            mExtend = extend;
        }

        @Override
        public void futureIsNow(Future<Void> result) {
            try {
                result.get();
                showToast(mCommand);
                mErrorCount = 0;
                Log.i(TAG, mCommand + ": successful");
            } catch(ExecutionException e) {
                handleFailure(e.getCause(), mMsg, mExtend);
            } catch(Exception e) {
                handleFailure(e, mMsg, mExtend);
            }
        }
    }

    private static class RemoteMediaPlayerComp implements Comparator<RemoteMediaPlayer> {
        @Override
        public int compare(RemoteMediaPlayer player1, RemoteMediaPlayer player2) {
            return player1.getName().compareTo(player2.getName());
        }
    }

}
