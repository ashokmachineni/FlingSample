/**
 * MediaListAdapter.java
 *
 * Copyright (c) 2015 Amazon Technologies, Inc. All rights reserved.
 *
 * PROPRIETARY/CONFIDENTIAL
 *
 * Use is subject to license terms.
 */

package com.amazon.whisperplay.example.flingsample;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.androidquery.AQuery;

import org.json.JSONObject;

import java.util.List;

public class MediaListAdapter extends BaseAdapter {

    private LayoutInflater mInflater;
    private List<MediaSourceManager.MediaSource> mData;
    private AQuery mAQuery;

    public MediaListAdapter(Context context, List<MediaSourceManager.MediaSource> data) {
        this.mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        this.mData = data;
        this.mAQuery = new AQuery(context);
    }
    @Override
    public int getCount() {
        return mData.size();
    }

    @Override
    public Object getItem(int position) {
        return mData.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = mInflater.inflate(R.layout.item, parent, false);
        }
        MediaSourceManager.MediaSource mediaSource = mData.get(position);
        JSONObject metadata = new JSONObject(mediaSource.metadata);

        ImageView iconUrlView = (ImageView)convertView.findViewById(R.id.mediaimage);
        String imageUrl = mediaSource.iconUrl;

        if (!imageUrl.isEmpty()) {
            mAQuery.id(iconUrlView).image(imageUrl, true, true, 0, R.drawable.ic_whisperplay_default_light_24dp);
        } else {
            iconUrlView.setImageResource(R.drawable.ic_whisperplay_default_light_24dp);
        }

        TextView titleTextView = (TextView)convertView.findViewById(R.id.mediatitle);
        titleTextView.setText(mediaSource.toString());

        TextView descriptionTextView = (TextView)convertView.findViewById(R.id.mediadescription);
        String description = metadata.optString("description");
        if (description != null) {
            descriptionTextView.setText(description);
        } else {
            descriptionTextView.setText("Description:");
        }
        return convertView;
    }
}
