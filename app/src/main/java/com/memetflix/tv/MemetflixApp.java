package com.memetflix.tv;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Guarda el último error fatal en un archivo para mostrarlo (y poder compartirlo)
 * la próxima vez que se abra la app, sin necesidad de adb ni Android Studio.
 */
public class MemetflixApp extends Application {

    private static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                saveCrash(MemetflixApp.this, thread, error);
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    private static void saveCrash(Context context, Thread thread, Throwable error) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("Memetflix " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
            pw.println("Fecha: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()));
            pw.println("Dispositivo: " + Build.MANUFACTURER + " " + Build.MODEL);
            pw.println("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            pw.println("ABI: " + (Build.VERSION.SDK_INT >= 21 ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI));
            pw.println("Hilo: " + thread.getName());
            pw.println();
            error.printStackTrace(pw);
            pw.flush();

            FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), CRASH_FILE));
            out.write(sw.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    /** Devuelve el reporte del último cierre inesperado y lo borra, o null si no hubo. */
    static String consumeLastCrash(Context context) {
        File file = new File(context.getFilesDir(), CRASH_FILE);
        if (!file.exists()) return null;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            in.close();
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
