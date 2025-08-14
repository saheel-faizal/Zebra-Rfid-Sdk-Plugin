package com.example.zebra_rfid_sdk_plugin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.zebra.rfid.api3.ACCESS_OPERATION_CODE;
import com.zebra.rfid.api3.ACCESS_OPERATION_STATUS;
import com.zebra.rfid.api3.Antennas;
import com.zebra.rfid.api3.BATCH_MODE;
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
import com.zebra.rfid.api3.USB_BATCH_MODE;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel.Result;

public class RFIDHandler implements Readers.RFIDReaderEventHandler {
    private String TAG = "RFIDHandler";
    private Context context;
    private Handler mEventHandler = new Handler(Looper.getMainLooper());
    private AsyncTask<Void, Void, String> autoConnectDeviceTask;
    private static Readers readers;
    private static ReaderDevice readerDevice;
    private static RFIDReader reader;
    private int MAX_POWER = 300;
    private IEventHandler eventHandler = new IEventHandler();
    private EventChannel.EventSink sink = null;
    private AtomicBoolean isInventoryRunning = new AtomicBoolean(false);
    private AtomicBoolean isBatchMode = new AtomicBoolean(false);

    private void emit(final String eventName, final HashMap<String, Object> map) {
        map.put("eventName", eventName);
        mEventHandler.post(() -> {
            if (sink != null) {
                sink.success(map);
            }
        });
    }

    RFIDHandler(Context context) {
        this.context = context;
    }

    public void setEventSink(EventChannel.EventSink sink) {
        this.sink = sink;
    }

    @SuppressLint("StaticFieldLeak")
    public void connect(final Result result) {
        try {
            TelegramLogger.sendLog("RFIDHandler: Starting connection");
            if (readers == null) {
                readers = new Readers(context, ENUM_TRANSPORT.ALL);
                readers.attach(this);
            }
            autoConnectDevice(result);
        } catch (Exception e) {
            TelegramLogger.sendLog("RFIDHandler: Error initializing RFID reader", e);
            HashMap<String, Object> errorMap = new HashMap<>();
            errorMap.put("error", e.getMessage());
            emit(Base.RfidEngineEvents.Error, errorMap);
            result.error("RFID_ERROR", e.getMessage(), null);
        }
    }

    public void disconnect() {
        try {
            TelegramLogger.sendLog("RFIDHandler: Disconnecting reader");
            stopInventory();
            if (reader != null && reader.isConnected()) {
                reader.Events.removeEventsListener(eventHandler);
                reader.disconnect();
                TelegramLogger.sendLog("RFIDHandler: Reader disconnected successfully");
            }
        } catch (Exception e) {
            TelegramLogger.sendLog("RFIDHandler: Error disconnecting reader", e);
        }
    }

    public void dispose() {
        try {
            TelegramLogger.sendLog("RFIDHandler: Disposing resources");
            if (autoConnectDeviceTask != null && !autoConnectDeviceTask.isCancelled()) {
                autoConnectDeviceTask.cancel(true);
            }
            stopInventory();

            if (reader != null) {
                if (reader.isConnected()) {
                    reader.disconnect();
                }
                reader = null;
            }
            if (readers != null) {
                readers.detach(this);
                readers.Dispose();
                readers = null;
            }
            readerDevice = null;

            HashMap<String, Object> map = new HashMap<>();
            map.put("status", Base.ConnectionStatus.UnConnection.ordinal());
            emit(Base.RfidEngineEvents.ConnectionStatus, map);
        } catch (Exception e) {
            TelegramLogger.sendLog("RFIDHandler: Error disposing resources", e);
        }
    }

    @SuppressLint("StaticFieldLeak")
    private void autoConnectDevice(final Result result) {
        autoConnectDeviceTask = new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                TelegramLogger.sendLog("RFIDHandler: AutoConnectDeviceTask started");
                try {
                    if (readerDevice == null) {
                        ArrayList<ReaderDevice> availableReaders = readers.GetAvailableRFIDReaderList();
                        if (availableReaders == null || availableReaders.isEmpty()) {
                            String error = "No RFID readers available";
                            TelegramLogger.sendLog("RFIDHandler: " + error);
                            return error;
                        }
                        readerDevice = availableReaders.get(0);
                        reader = readerDevice.getRFIDReader();
                        TelegramLogger.sendLog("RFIDHandler: Found reader: " + readerDevice.getName());
                    }

                    if (reader != null && !reader.isConnected() && !isCancelled()) {
                        TelegramLogger.sendLog("RFIDHandler: Attempting to connect to reader");
                        reader.connect();
                        configureReader();
                        TelegramLogger.sendLog("RFIDHandler: Reader connected and configured");
                    }
                } catch (InvalidUsageException e) {
                    TelegramLogger.sendLog("RFIDHandler: InvalidUsageException in autoConnectDevice", e);
                    return e.getMessage();
                } catch (OperationFailureException e) {
                    TelegramLogger.sendLog("RFIDHandler: OperationFailureException in autoConnectDevice", e);
                    return e.getStatusDescription();
                } catch (Exception e) {
                    TelegramLogger.sendLog("RFIDHandler: Exception in autoConnectDevice", e);
                    return e.getMessage();
                }
                return null;
            }

            @Override
            protected void onPostExecute(String error) {
                Base.ConnectionStatus status = Base.ConnectionStatus.ConnectionReady;
                if (error != null) {
                    status = Base.ConnectionStatus.ConnectionError;
                    HashMap<String, Object> errorMap = new HashMap<>();
                    errorMap.put("error", error);
                    emit(Base.RfidEngineEvents.Error, errorMap);
                }
                HashMap<String, Object> statusMap = new HashMap<>();
                statusMap.put("status", status.ordinal());
                emit(Base.RfidEngineEvents.ConnectionStatus, statusMap);

                if (result != null) {
                    if (error == null) {
                        result.success(null);
                    } else {
                        result.error("CONNECTION_ERROR", error, null);
                    }
                }
            }
        }.execute();
    }

    private boolean isReaderConnected() {
        boolean connected = reader != null && reader.isConnected();
        if (!connected) {
            TelegramLogger.sendLog("RFIDHandler: Reader not connected");
        }
        return connected;
    }

    private synchronized void configureReader() {
        if (!isReaderConnected()) {
            TelegramLogger.sendLog("RFIDHandler: Reader not connected in configureReader");
            return;
        }

        try {
            TelegramLogger.sendLog("RFIDHandler: Configuring reader");
            reader.Events.addEventsListener(eventHandler);
            reader.Events.setHandheldEvent(true);
            reader.Events.setTagReadEvent(true);
            reader.Events.setAttachTagDataWithReadEvent(false);

            TriggerInfo triggerInfo = new TriggerInfo();
            triggerInfo.StartTrigger.setTriggerType(START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE);
            triggerInfo.StopTrigger.setTriggerType(STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE);
            reader.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true);
            reader.Config.setStartTrigger(triggerInfo.StartTrigger);
            reader.Config.setStopTrigger(triggerInfo.StopTrigger);

            MAX_POWER = reader.ReaderCapabilities.getTransmitPowerLevelValues().length - 1;
            Antennas.AntennaRfConfig config = reader.Config.Antennas.getAntennaRfConfig(1);
            config.setTransmitPowerIndex(MAX_POWER);
            config.setrfModeTableIndex(0);
            config.setTari(0);
            reader.Config.Antennas.setAntennaRfConfig(1, config);

            Antennas.SingulationControl singulationControl = reader.Config.Antennas.getSingulationControl(1);
            singulationControl.setSession(SESSION.SESSION_S0);
            singulationControl.Action.setInventoryState(INVENTORY_STATE.INVENTORY_STATE_A);
            singulationControl.Action.setSLFlag(SL_FLAG.SL_ALL);
            reader.Config.Antennas.setSingulationControl(1, singulationControl);

            reader.Actions.PreFilters.deleteAll();

            isBatchMode.set(reader.Config.getBatchModeConfig().getValue() == BATCH_MODE.ENABLE.getValue() ||
                    reader.Config.getUsbBatchModeConfig().getValue() == USB_BATCH_MODE.ENABLE.getValue());

            TelegramLogger.sendLog("RFIDHandler: Reader configured successfully. Batch mode: " + isBatchMode.get());
        } catch (Exception e) {
            TelegramLogger.sendLog("RFIDHandler: Error configuring reader", e);
            throw e;
        }
    }

    public void getReadersList(Result result) {
        try {
            TelegramLogger.sendLog("RFIDHandler: Getting readers list");
            ArrayList<ReaderDevice> readersList = readers != null ? readers.GetAvailableRFIDReaderList() : new ArrayList<>();
            ArrayList<String> readerNames = new ArrayList<>();
            for (ReaderDevice device : readersList) {
                readerNames.add(device.getName());
            }
            result.success(readerNames);
        } catch (Exception e) {
            TelegramLogger.sendLog("RFIDHandler: Error getting readers list", e);
            result.error("RFID_ERROR", e.getMessage(), null);
        }
    }

    public class IEventHandler implements RfidEventsListener {
        @Override
        public void eventReadNotify(RfidReadEvents rfidReadEvents) {
            try {
                TagData[] tags = reader.Actions.getReadTags(100);
                if (tags != null && tags.length > 0) {
                    ArrayList<HashMap<String, Object>> tagList = new ArrayList<>();
                    for (TagData tag : tags) {
                        if (tag.getOpCode() == null || tag.getOpCode() == ACCESS_OPERATION_CODE.ACCESS_OPERATION_READ) {
                            Base.RfidData data = new Base.RfidData();
                            data.tagID = tag.getTagID();
                            data.antennaID = tag.getAntennaID();
                            data.peakRSSI = tag.getPeakRSSI();
                            data.opStatus = tag.getOpStatus();
                            data.allocatedSize = tag.getTagIDAllocatedSize();
                            data.lockData = tag.getPermaLockData();
                            if (tag.isContainsLocationInfo()) {
                                data.relativeDistance = tag.LocationInfo.getRelativeDistance();
                            }
                            data.memoryBankData = tag.getMemoryBankData();
                            tagList.add(transitionEntity(data));
                        }
                    }
                    if (!tagList.isEmpty()) {
                        HashMap<String, Object> dataMap = new HashMap<>();
                        dataMap.put("datas", tagList);
                        emit(Base.RfidEngineEvents.ReadRfid, dataMap);
                    }
                }
            } catch (Exception e) {
                TelegramLogger.sendLog("RFIDHandler: Error processing tag data", e);
            }
        }

        @Override
        public void eventStatusNotify(RfidStatusEvents rfidStatusEvents) {
            try {
                STATUS_EVENT_TYPE eventType = rfidStatusEvents.StatusEventData.getStatusEventType();
                TelegramLogger.sendLog("RFIDHandler: Status event: " + eventType);

                if (eventType == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
                    HANDHELD_TRIGGER_EVENT_TYPE triggerEvent = rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent();
                    if (triggerEvent == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                        TelegramLogger.sendLog("RFIDHandler: Trigger pressed - starting inventory");
                        performInventory();
                    } else if (triggerEvent == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
                        TelegramLogger.sendLog("RFIDHandler: Trigger released - stopping inventory");
                        stopInventory();
                    }
                } else if (eventType == STATUS_EVENT_TYPE.BATCH_MODE_EVENT) {
                    isBatchMode.set(true);
                    TelegramLogger.sendLog("RFIDHandler: Batch mode activated");
                }
            } catch (Exception e) {
                TelegramLogger.sendLog("RFIDHandler: Error processing status event", e);
            }
        }
    }

    public synchronized void performInventory() {
        if (!isReaderConnected() || isInventoryRunning.get()) {
            TelegramLogger.sendLog("RFIDHandler: Cannot perform inventory - reader not connected or already running");
            return;
        }

        try {
            if (isBatchMode.get()) {
                TelegramLogger.sendLog("RFIDHandler: Stopping batch mode before inventory");
                reader.Actions.Inventory.stop();
                isBatchMode.set(false);
                Thread.sleep(200);
            }

            TelegramLogger.sendLog("RFIDHandler: Starting inventory");
            reader.Actions.Inventory.perform();
            isInventoryRunning.set(true);
            TelegramLogger.sendLog("RFIDHandler: Inventory started successfully");
        } catch (Exception e) {
            TelegramLogger.sendLog("RFIDHandler: Error starting inventory", e);
            HashMap<String, Object> errorMap = new HashMap<>();
            errorMap.put("error", "Failed to start inventory: " + e.getMessage());
            emit(Base.RfidEngineEvents.Error, errorMap);
        }
    }

    public synchronized void stopInventory() {
        if (!isReaderConnected() || !isInventoryRunning.get()) {
            TelegramLogger.sendLog("RFIDHandler: Cannot stop inventory - reader not connected or not running");
            return;
        }

        try {
            TelegramLogger.sendLog("RFIDHandler: Stopping inventory");
            reader.Actions.Inventory.stop();

            if (isBatchMode.get()) {
                TelegramLogger.sendLog("RFIDHandler: Purging tags in batch mode");
                reader.Actions.purgeTags();
                isBatchMode.set(false);
            }

            isInventoryRunning.set(false);
            TelegramLogger.sendLog("RFIDHandler: Inventory stopped successfully");
        } catch (Exception e) {
            TelegramLogger.sendLog("RFIDHandler: Error stopping inventory", e);
            HashMap<String, Object> errorMap = new HashMap<>();
            errorMap.put("error", "Failed to stop inventory: " + e.getMessage());
            emit(Base.RfidEngineEvents.Error, errorMap);
        }
    }

    @Override
    public void RFIDReaderAppeared(ReaderDevice readerDevice) {
        TelegramLogger.sendLog("RFIDHandler: Reader appeared: " + readerDevice.getName());
        HashMap<String, Object> map = new HashMap<>();
        map.put("readerName", readerDevice.getName());
        emit("ReaderAppeared", map);
    }

    @Override
    public void RFIDReaderDisappeared(ReaderDevice readerDevice) {
        TelegramLogger.sendLog("RFIDHandler: Reader disappeared: " + readerDevice.getName());
        dispose();
        HashMap<String, Object> map = new HashMap<>();
        map.put("readerName", readerDevice.getName());
        emit("ReaderDisappeared", map);
    }

    private static HashMap<String, Object> transitionEntity(Object object) {
        HashMap<String, Object> hashMap = new HashMap<>();
        Field[] fields = object.getClass().getDeclaredFields();
        for (Field field : fields) {
            field.setAccessible(true);
            try {
                hashMap.put(field.getName(), field.get(object));
            } catch (IllegalAccessException e) {
                Log.e("RFIDHandler", "Error converting field to map: " + field.getName(), e);
            }
        }
        return hashMap;
    }
}