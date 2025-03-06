package com.example.zebra_rfid_sdk_plugin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;

import com.zebra.rfid.api3.Antennas;
import com.zebra.rfid.api3.ENUM_TRANSPORT;
import com.zebra.rfid.api3.ENUM_TRIGGER_MODE;
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE;
import com.zebra.rfid.api3.INVENTORY_STATE;
import com.zebra.rfid.api3.InvalidUsageException;
import com.zebra.rfid.api3.OperationFailureException;
import com.zebra.rfid.api3.RFIDReader;
import com.zebra.rfid.api3.ReaderDevice;
import com.zebra.rfid.api3.Readers;
import com.zebra.rfid.api3.RfidEventsListener;
import com.zebra.rfid.api3.RfidReadEvents;
import com.zebra.rfid.api3.RfidStatusEvents;
import com.zebra.rfid.api3.SESSION;
import com.zebra.rfid.api3.SL_FLAG;
import com.zebra.rfid.api3.START_TRIGGER_TYPE;
import com.zebra.rfid.api3.STATUS_EVENT_TYPE;
import com.zebra.rfid.api3.STOP_TRIGGER_TYPE;
import com.zebra.rfid.api3.TagData;
import com.zebra.rfid.api3.TriggerInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel.Result;

public class RFIDHandler implements Readers.RFIDReaderEventHandler {
    private String TAG = "RFIDHandler";
    Context context;

    public Handler mEventHandler = new Handler(Looper.getMainLooper());
    private AsyncTask<Void, Void, String> AutoConnectDeviceTask;
    private static Readers readers;
    private static ReaderDevice readerDevice;
    public static volatile boolean mIsMultiTagLocatingRunning;

    private static RFIDReader reader;
    private int MAX_POWER = 270;
    private IEventHandler eventHandler = new IEventHandler();
    private Function<String, Map<String, Object>> _emit;
    private EventChannel.EventSink sink = null;

    private void emit(final String eventName, final HashMap map) {
        map.put("eventName", eventName);
        mEventHandler.post(new Runnable() {
            @Override
            public void run() {
                if (sink != null) {
                    sink.success(map);
                }
            }
        });
    }

    RFIDHandler(Context _context) {
        context = _context;
    }

    public void setEventSink(EventChannel.EventSink _sink) {
        sink = _sink;
    }

    @SuppressLint("StaticFieldLeak")
    public void connect(final Result result) {
        Readers.attach(this);
        if (readers == null) {
            readers = new Readers(context, ENUM_TRANSPORT.ALL);
        }
        AutoConnectDevice(result);
    }

    public void disconnect() {
        try {
            stopInventory(); // Stop any ongoing inventory operation
            if (reader != null && reader.isConnected()) {
                reader.Events.removeEventsListener(eventHandler); // Remove event listener

                Log.d(TAG, "Reader disconnected successfully.");
            }
        } catch (InvalidUsageException | OperationFailureException e) {
            e.printStackTrace();
            Log.e(TAG, "Error disconnecting reader: " + e.getMessage());
        }
    }

    public void dispose() {
        try {
            if (AutoConnectDeviceTask != null && !AutoConnectDeviceTask.isCancelled()) {
                AutoConnectDeviceTask.cancel(true); // Cancel the task
            }
            handleTriggerPress(false); // stops the inventory


            if (readers != null) {
                readerDevice = null;
                reader = null;
                readers.Dispose();
                readers = null;
                HashMap<String, Object> map = new HashMap<>();
                map.put("status", Base.ConnectionStatus.UnConnection.ordinal());
                emit(Base.RfidEngineEvents.ConnectionStatus, map);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("StaticFieldLeak")
    public void AutoConnectDevice(final Result result) {
        AutoConnectDeviceTask = new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                Log.d(TAG, "CreateInstanceTask");
                try {
                    if (readerDevice == null) {
                        ArrayList<ReaderDevice> readersListArray = readers.GetAvailableRFIDReaderList();
                        if (readersListArray.size() > 0) {
                            readerDevice = readersListArray.get(0);
                            reader = readerDevice.getRFIDReader();
                        } else {
                            return "No connectable device detected";
                        }
                    }

                    if (reader != null && !reader.isConnected() && !this.isCancelled()) {
                        reader.connect();
                        ConfigureReader();
                    }

                } catch (InvalidUsageException ex) {
                    Log.d(TAG, "InvalidUsageException");
                    return ex.getMessage();
                } catch (OperationFailureException e) {
                    String details = e.getStatusDescription();
                    return details;
                }
                return null;
            }

            @Override
            protected void onPostExecute(String error) {
                Base.ConnectionStatus status = Base.ConnectionStatus.ConnectionRealy;
                super.onPostExecute(error);
                if (error != null) {
                    emit(Base.RfidEngineEvents.Error, transitionEntity(Base.ErrorResult.error(error)));
                    status = Base.ConnectionStatus.ConnectionError;
                }
                HashMap<String, Object> map = new HashMap<>();
                map.put("status", status.ordinal());
                emit(Base.RfidEngineEvents.ConnectionStatus, map);
            }

            @Override
            protected void onCancelled() {
                super.onCancelled();
                AutoConnectDeviceTask = null;
            }
        }.execute();
    }

    private boolean isReaderConnected() {
        if (reader != null && reader.isConnected())
            return true;
        else {
            Log.d(TAG, "reader is not connected");
            return false;
        }
    }

    private synchronized void ConfigureReader() {
        Log.d(TAG, "ConfigureReader " + reader.getHostName());
        if (reader.isConnected()) {
            TriggerInfo triggerInfo = new TriggerInfo();
            triggerInfo.StartTrigger.setTriggerType(START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE);
            triggerInfo.StopTrigger.setTriggerType(STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE);
            try {
                // Receive events from reader
                reader.Events.addEventsListener(eventHandler);
                // HH event
                reader.Events.setHandheldEvent(true);
                // Tag event with tag data
                reader.Events.setTagReadEvent(true);
                reader.Events.setAttachTagDataWithReadEvent(false);
                // Set trigger mode as RFID so scanner beam will not come
                reader.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true);
                // Set start and stop triggers
                reader.Config.setStartTrigger(triggerInfo.StartTrigger);
                reader.Config.setStopTrigger(triggerInfo.StopTrigger);
                // Power levels are index-based, so maximum power supported gets the last one
                MAX_POWER = reader.ReaderCapabilities.getTransmitPowerLevelValues().length - 1;
                // Set antenna configurations
                Antennas.AntennaRfConfig config = reader.Config.Antennas.getAntennaRfConfig(1);
                config.setTransmitPowerIndex(MAX_POWER);
                config.setrfModeTableIndex(0);
                config.setTari(0);
                reader.Config.Antennas.setAntennaRfConfig(1, config);
                // Set the singulation control
                Antennas.SingulationControl s1_singulationControl = reader.Config.Antennas.getSingulationControl(1);
                s1_singulationControl.setSession(SESSION.SESSION_S0);
                s1_singulationControl.Action.setInventoryState(INVENTORY_STATE.INVENTORY_STATE_A);
                s1_singulationControl.Action.setSLFlag(SL_FLAG.SL_ALL);
                reader.Config.Antennas.setSingulationControl(1, s1_singulationControl);
                // Delete any prefilters
                reader.Actions.PreFilters.deleteAll();
            } catch (InvalidUsageException | OperationFailureException e) {
                e.printStackTrace();
            }
        }
    }

    public ArrayList<ReaderDevice> getReadersList() {
        ArrayList<ReaderDevice> readersListArray = new ArrayList<>();
        try {
            if (readers != null) {
                readersListArray = readers.GetAvailableRFIDReaderList();
                return readersListArray;
            }
        } catch (InvalidUsageException e) {
            e.printStackTrace();
        }
        return readersListArray;
    }

    public class IEventHandler implements RfidEventsListener {
        @Override
        public void eventReadNotify(RfidReadEvents rfidReadEvents) {
            try {
                // Step 1: Perform an inventory operation to detect tags
                TagData[] myTags = reader.Actions.getReadTags(100); // Fetch tags
                if (myTags != null) {
                    ArrayMap<String, String> multiTagLocateTagMap = new ArrayMap<>();

                    // Step 2: Build the dynamic tag list
                    for (TagData tagData : myTags) {
                        String epc = tagData.getTagID();
                        String rssi = String.valueOf(tagData.getPeakRSSI()); // Use peak RSSI as reference
                        multiTagLocateTagMap.put(epc, rssi);
                        Log.d(TAG, "Added EPC to Multi-Tag Locate list: " + epc + " with RSSI: " + rssi);
                    }

                    // Step 3: Import the dynamic tag list into the reader
                    reader.Actions.MultiTagLocate.purgeItemList(); // Clear existing list
                    reader.Actions.MultiTagLocate.importItemList(multiTagLocateTagMap); // Import new list

                    // Step 4: Start the Multi-Tag Locate operation
                    if (!mIsMultiTagLocatingRunning) {
                        reader.Actions.MultiTagLocate.perform();
                        mIsMultiTagLocatingRunning = true;
                        Log.d(TAG, "Multi-tag location started.");
                    }

                    // Step 5: Get the tags with Multi-Tag Locate information
                    TagData[] locatedTags = reader.Actions.getMultiTagLocateTagInfo(100); // Fetch tag data
                    if (locatedTags != null) {
                        ArrayList<HashMap<String, Object>> datas = new ArrayList<>();

                        for (TagData tagData : locatedTags) {
                            if (tagData.isContainsMultiTagLocateInfo()) {
                                Base.RfidData data = new Base.RfidData();
                                data.tagID = tagData.getTagID();
                                data.antennaID = tagData.getAntennaID();
                                data.peakRSSI = tagData.getPeakRSSI();
                                data.opStatus = tagData.getOpStatus();
                                data.allocatedSize = tagData.getTagIDAllocatedSize();
                                data.lockData = tagData.getPermaLockData();

                                // Extract Multi-Tag Locate information
                                data.relativeDistance = tagData.MultiTagLocateInfo.getRelativeDistance();
                                Log.d(TAG, "Relative Distance: " + data.relativeDistance);

                                data.memoryBankData = tagData.getMemoryBankData();
                                datas.add(transitionEntity(data));
                            }
                        }

                        // Step 6: Send the processed tag data to Flutter
                        if (datas.size() > 0) {
                            new AsyncDataNotify().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, datas);
                        }
                    }
                }
            } catch (InvalidUsageException | OperationFailureException e) {
                e.printStackTrace();
                Log.e(TAG, "Error in multi-tag location or tag read: " + e.getMessage());
            }
        }

        @Override
        public void eventStatusNotify(RfidStatusEvents rfidStatusEvents) {
            Log.d(TAG, "Status Notification: " + rfidStatusEvents.StatusEventData.getStatusEventType());
            if (rfidStatusEvents.StatusEventData.getStatusEventType() == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
                if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent() == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                    new AsyncTask<Void, Void, Void>() {
                        @SuppressLint("StaticFieldLeak")
                        @Override
                        protected Void doInBackground(Void... voids) {
                            handleTriggerPress(true);
                            return null;
                        }
                    }.execute();
                } else if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent() == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
                    new AsyncTask<Void, Void, Void>() {
                        @Override
                        protected Void doInBackground(Void... voids) {
                            handleTriggerPress(false);
                            return null;
                        }
                    }.execute();
                }
            }
        }
    }

    public void handleTriggerPress(boolean pressed) {
        if (pressed) {
            performInventory();
        } else {
            stopInventory();
        }
    }

    synchronized void performInventory() {
        if (!isReaderConnected())
            return;
        try {
            reader.Actions.Inventory.perform();
        } catch (InvalidUsageException | OperationFailureException e) {
            e.printStackTrace();
        }
    }

    synchronized void stopInventory() {
        if (!isReaderConnected())
            return;
        try {
            reader.Actions.Inventory.stop();
        } catch (InvalidUsageException | OperationFailureException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void RFIDReaderAppeared(ReaderDevice readerDevice) {
        Log.d(TAG, "RFIDReaderAppeared " + readerDevice.getName());
    }

    @Override
    public void RFIDReaderDisappeared(ReaderDevice readerDevice) {
        Log.d(TAG, "RFIDReaderDisappeared " + readerDevice.getName());
//        if (readerDevice.getName().equals(reader.getHostName())) {
//            disconnect();
            dispose();
        }
    }

    private class AsyncDataNotify extends AsyncTask<ArrayList<HashMap<String, Object>>, Void, Void> {
        @Override
        protected Void doInBackground(ArrayList<HashMap<String, Object>>... params) {
            HashMap<String, Object> hashMap = new HashMap<>();
            hashMap.put("datas", params[0]);
            emit(Base.RfidEngineEvents.ReadRfid, hashMap);
            return null;
        }
    }

    public static HashMap<String, Object> transitionEntity(Object onClass) {
        HashMap<String, Object> hashMap = new HashMap<>();
        Field[] fields = onClass.getClass().getDeclaredFields();
        for (Field field : fields) {
            field.setAccessible(true);
            try {
                hashMap.put(field.getName(), field.get(onClass));
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        return hashMap;
    }
}