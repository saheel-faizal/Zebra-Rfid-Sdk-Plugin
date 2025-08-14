package com.example.zebra_rfid_sdk_plugin;

import android.util.Log;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TelegramLogger {
    private static final String TAG = "TelegramLogger";
    private static final String TELEGRAM_BOT_TOKEN = "7691552261:AAHoAR22XME33YEPJjN1faTDCyo2MVK-ljQ";
    private static final String TELEGRAM_CHAT_ID = "457782794";
    private static final String TELEGRAM_API_URL = "https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN + "/sendMessage";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    public static void sendLog(String message) {
        sendLog(message, null);
    }

    public static void sendLog(String message, Exception exception) {
        String fullMessage = message;
        if (exception != null) {
            fullMessage += "\n\nException:\n" + getStackTraceAsString(exception);
        }

        RequestBody formBody = new FormBody.Builder()
                .add("chat_id", TELEGRAM_CHAT_ID)
                .add("text", fullMessage)
                .build();

        Request request = new Request.Builder()
                .url(TELEGRAM_API_URL)
                .post(formBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to send log to Telegram", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Telegram API error: " + response.body().string());
                } else {
                    Log.d(TAG, "Log sent to Telegram successfully");
                }
                response.close();
            }
        });
    }

    private static String getStackTraceAsString(Exception e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.toString()).append("\n");
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append("\tat ").append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}