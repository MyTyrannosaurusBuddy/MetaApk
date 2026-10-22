package com.meta.app.bridge;

import android.content.Context;
import android.util.Log;

import java.io.*;

/**
 * File bridge - only the app-private directory is accessible.
 */
public class FileBridge {
    private static final String TAG = "FileBridge";
    private final Context context;

    public FileBridge(Context context) {
        this.context = context;
    }

    public String readFile(String relativePath) throws IOException {
        File file = new File(context.getFilesDir(), relativePath);
        BufferedReader reader = new BufferedReader(new FileReader(file));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        reader.close();
        return sb.toString();
    }

    public void writeFile(String relativePath, String content) throws IOException {
        File file = new File(context.getFilesDir(), relativePath);
        file.getParentFile().mkdirs();
        FileWriter writer = new FileWriter(file);
        writer.write(content);
        writer.close();
    }

    public String[] listFiles(String relativePath) {
        File dir = new File(context.getFilesDir(), relativePath);
        return dir.list();
    }

    public File getFilesDir() { return context.getFilesDir(); }
}
