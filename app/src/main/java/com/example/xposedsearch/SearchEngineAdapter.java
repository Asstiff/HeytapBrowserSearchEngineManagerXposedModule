package com.example.xposedsearch;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;

import java.util.List;

public class SearchEngineAdapter extends BaseAdapter {

    private final Context context;
    private final List<SearchEngineConfig> data;

    public SearchEngineAdapter(Context context, List<SearchEngineConfig> data) {
        this.context = context;
        this.data = data;
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Object getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    static class ViewHolder {
        EditText etName;
        EditText etKey;
        EditText etUrl;
        CheckBox cbEnabled;

        TextWatcher nameWatcher;
        TextWatcher urlWatcher;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder vh;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_engine, parent, false);
            vh = new ViewHolder();
            vh.etName = convertView.findViewById(R.id.etName);
            vh.etUrl = convertView.findViewById(R.id.etUrl);
            vh.cbEnabled = convertView.findViewById(R.id.cbEnabled);
            convertView.setTag(vh);
        } else {
            vh = (ViewHolder) convertView.getTag();
        }

        final SearchEngineConfig cfg = data.get(position);

        if (vh.nameWatcher != null) {
            vh.etName.removeTextChangedListener(vh.nameWatcher);
        }
        if (vh.urlWatcher != null) {
            vh.etUrl.removeTextChangedListener(vh.urlWatcher);
        }

        vh.etName.setText(cfg.name);
        vh.etUrl.setText(cfg.searchUrl);

        vh.nameWatcher = new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                cfg.name = s.toString();
            }
        };
        vh.urlWatcher = new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                cfg.searchUrl = s.toString();
            }
        };

        vh.etName.addTextChangedListener(vh.nameWatcher);
        vh.etUrl.addTextChangedListener(vh.urlWatcher);

        vh.cbEnabled.setOnCheckedChangeListener(null);
        vh.cbEnabled.setChecked(cfg.enabled);
        vh.cbEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> cfg.enabled = isChecked);

        return convertView;
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) { }

        @Override
        public abstract void afterTextChanged(Editable s);
    }
}