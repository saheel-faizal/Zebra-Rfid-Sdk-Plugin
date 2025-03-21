package com.example.zebra_rfid_sdk_plugin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.zebra.rfid.api3.ACCESS_OPERATION_CODE;
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
                TagData singleTag = rfidReadEvents.getReadEventData().tagData;

                // 🔹 Send singleTag log to Telegram
                StringBuilder singleTagLog = new StringBuilder();
                singleTagLog.append("SingleTag Read:\n");
                singleTagLog.append("TagID: ").append(singleTag.getTagID()).append("\n");
                singleTagLog.append("AntennaID: ").append(singleTag.getAntennaID()).append("\n");
                singleTagLog.append("RSSI: ").append(singleTag.getPeakRSSI()).append("\n");
                singleTagLog.append("OpCode: ").append(singleTag.getOpCode()).append("\n");
                singleTagLog.append("OpStatus: ").append(singleTag.getOpStatus()).append("\n");

                if (singleTag.isContainsLocationInfo()) {
                    singleTagLog.append("RelativeDistance: ")
                            .append(singleTag.LocationInfo.getRelativeDistance()).append("\n");
                }

                TelegramLogger.sendLog(singleTagLog.toString());

                TagData[] myTags = reader.Actions.getReadTags(100);
                if (myTags != null) {
                    ArrayList<HashMap<String, Object>> datas = new ArrayList<>();
                    for (TagData tagData : myTags) {
                        Log.d(TAG, "Tag ID: " + tagData.getTagID());
                        Log.d(TAG, "Tag OpCode: " + tagData.getOpCode());
                        Log.d(TAG, "Tag OpStatus: " + tagData.getOpStatus());

                        StringBuilder tagLog = new StringBuilder();
                        tagLog.append("Tag Read:\n");
                        tagLog.append("TagID: ").append(tagData.getTagID()).append("\n");
                        tagLog.append("AntennaID: ").append(tagData.getAntennaID()).append("\n");
                        tagLog.append("RSSI: ").append(tagData.getPeakRSSI()).append("\n");
                        tagLog.append("OpCode: ").append(tagData.getOpCode()).append("\n");
                        tagLog.append("OpStatus: ").append(tagData.getOpStatus()).append("\n");

                        if (tagData.getOpCode() == null || tagData.getOpCode() == ACCESS_OPERATION_CODE.ACCESS_OPERATION_READ) {
                            Base.RfidData data = new Base.RfidData();
                            data.tagID = tagData.getTagID();
                            data.antennaID = tagData.getAntennaID();
                            data.peakRSSI = tagData.getPeakRSSI();
                            data.opStatus = tagData.getOpStatus();
                            data.allocatedSize = tagData.getTagIDAllocatedSize();
                            data.lockData = tagData.getPermaLockData();
                            if (tagData.isContainsLocationInfo()) {
                                data.relativeDistance = tagData.LocationInfo.getRelativeDistance();
                                tagLog.append("RelativeDistance: ").append(data.relativeDistance).append("\n");
                            }
                            data.memoryBankData = tagData.getMemoryBankData();
                            datas.add(transitionEntity(data));
                        }

                        // 🔹 Send each tag to Telegram
                        TelegramLogger.sendLog(tagLog.toString());
                    }

                    if (datas.size() > 0) {
                        new AsyncDataNotify().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, datas);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in eventReadNotify: " + e.getMessage());
                TelegramLogger.sendLog("Error in eventReadNotify: " + e.getMessage());
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