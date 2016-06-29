/**
 * MediaSourceManager.java
 *
 * Copyright (c) 2015 Amazon Technologies, Inc. All rights reserved.
 *
 * PROPRIETARY/CONFIDENTIAL
 *
 * Use is subject to license terms.
 */

package com.amazon.whisperplay.example.flingsample;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MediaSourceManager {

    private static final String TAG = MediaSourceManager.class.getName();
    private static final String FILE_NAME = "FlingSample.json";
    private Context mContext;

    public MediaSourceManager(Context context) {
        mContext = context;
    }

    public static class MediaSource {
        // Avoid a lot of boiler plate code
        public String presentableTitle;
        public String url;
        public String iconUrl;
        public Map<String, Object> metadata;

        public String toString() {
            return presentableTitle;
        }
    }

    private static void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while((read = in.read(buffer)) != -1){
            out.write(buffer, 0, read);
        }
    }

    public static String convertStreamToString(InputStream is) {
        java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    private boolean ensureDatabaseWritable() {
        boolean success = false;
        try {
            File out = new File(Environment.getExternalStorageDirectory(), FILE_NAME);
            if (out.exists()) {
                Log.d(TAG, "Database is available on external storage.");
                success = true;
            } else {
                AssetManager manager = mContext.getAssets();
                InputStream in = manager.open(FILE_NAME);
                out.createNewFile();
                OutputStream outStream = new FileOutputStream(out);
                copyFile(in, outStream);

                success = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Database is not available. " + e.toString());
        }

        return success;
    }

    /*
     Returns all the sources present in the JSON database, if it is accessible.
     If the database is not present in the sdcard (external dir) then an attempt
     is made to copy the default database to the sdcard. If the user has mounted
     the sdcard, this attempt will still fail and an empty list will be returned.
     In such case, the user can be presented with a notice to make sdcard available.
     */
    public List<MediaSource> getAllSources() {
        List<MediaSource> allSources = new ArrayList<>();

        // Copy JSON database to external storage if not there
        if (ensureDatabaseWritable()) {
            try {
                File jsonFile = new File(Environment.getExternalStorageDirectory(), FILE_NAME);
                InputStream jsonStream = new FileInputStream(jsonFile);
                JSONTokener jsonTokener = new JSONTokener(convertStreamToString(jsonStream));
                JSONArray jsonArray=new JSONArray(jsonTokener);
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject object = jsonArray.getJSONObject(i);
                    String title = object.getString("title");
                    String url = object.getString("url");
                    String iconUrl = object.optString("iconUrl");
                    JSONObject metadataJson = object.getJSONObject("metadata");
                    Iterator<String> nameItr = metadataJson.keys();
                    Map<String, Object> metadata = new HashMap<>();
                    while(nameItr.hasNext()) {
                        String name = nameItr.next();
                        metadata.put(name, metadataJson.get(name));
                    }
                    // Add the source if the source if it was read without any exception
                    MediaSource source = new MediaSource();
                    source.presentableTitle = title;
                    source.url = url;
                    source.iconUrl = iconUrl;
                    source.metadata = metadata;
                    allSources.add(source);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading database. " + e.toString());
            }
        }

        return allSources;
    }
}
