package com.example.zebra_rfid_sdk_plugin;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

public class ZebraRfidSdkPlugin implements FlutterPlugin, MethodCallHandler, StreamHandler {
  private MethodChannel channel;
  private EventChannel eventChannel;
  private RFIDHandler rfidHandler;
  private Context context;
  private EventChannel.EventSink sink = null;
  private final String TAG = "ZebraRfidSdkPlugin";

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    context = flutterPluginBinding.getApplicationContext();
    rfidHandler = new RFIDHandler(context);

    channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "com.example.zebra_rfid_sdk_plugin/plugin");
    channel.setMethodCallHandler(this);

    eventChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "com.example.zebra_rfid_sdk_plugin/event_channel");
    eventChannel.setStreamHandler(this);
  }

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
    switch (call.method) {
      case "getPlatformVersion":
        result.success("Android " + android.os.Build.VERSION.RELEASE);
        break;
      case "toast":
        String txt = call.argument("text");
        Toast.makeText(context, txt, Toast.LENGTH_LONG).show();
        break;
      case "connect":
        rfidHandler.connect(result);
        break;
      case "getReadersList":
        rfidHandler.getReadersList(result);
        break;
      case "disconnect":
        rfidHandler.dispose();
        result.success(null);
        break;
      case "startInventory":
        rfidHandler.performInventory();
        result.success(null);
        break;
      case "stopInventory":
        rfidHandler.stopInventory();
        result.success(null);
        break;
      default:
        result.notImplemented();
    }
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    channel.setMethodCallHandler(null);
    eventChannel.setStreamHandler(null);
    rfidHandler.dispose();
  }

  @Override
  public void onListen(Object arguments, EventChannel.EventSink events) {
    Log.w(TAG, "adding listener");
    sink = events;
    rfidHandler.setEventSink(sink);
  }

  @Override
  public void onCancel(Object arguments) {
    Log.w(TAG, "cancelling listener");
    sink = null;
  }
}