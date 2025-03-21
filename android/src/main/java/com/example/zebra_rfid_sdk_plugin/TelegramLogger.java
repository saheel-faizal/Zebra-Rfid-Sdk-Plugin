package com.example.zebra_rfid_sdk_plugin;

import android.util.Log;

import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class TelegramLogger {

    private static final String BOT_TOKEN = "7691552261:AAHoAR22XME33YEPJjN1faTDCyo2MVK-ljQ";
    private static final String CHAT_ID = "457782794";

    public static void sendLog(final String message) {
        new Thread(() -> {
            try {
                String urlString = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
                String data = "chat_id=" + CHAT_ID + "&text=" + URLEncoder.encode(message, "UTF-8");

                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());
                writer.write(data);
                writer.flush();

                int responseCode = conn.getResponseCode();
                Log.d("TelegramLogger", "Telegram response code: " + responseCode);

                writer.close();
                conn.disconnect();
            } catch (Exception e) {
                Log.e("TelegramLogger", "Failed to send log: " + e.getMessage());
            }
        }).start();
    }
}
