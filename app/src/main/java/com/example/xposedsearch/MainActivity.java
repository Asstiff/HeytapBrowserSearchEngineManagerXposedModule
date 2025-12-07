package com.example.xposedsearch;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ListView listView;
    private Button btnSave;
    private SearchEngineAdapter adapter;
    private final List<SearchEngineConfig> data = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView = findViewById(R.id.listViewEngines);
        Button btnAdd = findViewById(R.id.btnAdd);
        btnSave = findViewById(R.id.btnSave);

        // 不再支持“新增”，直接隐藏按钮，避免误导
        if (btnAdd != null) {
            btnAdd.setVisibility(View.GONE);
        }

        data.addAll(ConfigManager.loadEngines(this));
        adapter = new SearchEngineAdapter(this, data);
        listView.setAdapter(adapter);

        btnSave.setOnClickListener(v -> {
            ConfigManager.saveEngines(MainActivity.this, data);
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage("已保存，重启浏览器生效。")
                    .setPositiveButton("确定", null)
                    .show();
        });

        // 点击 item 编辑（只能改名称、URL、启用状态，不能改 key）
        listView.setOnItemClickListener((adapterView, view, position, id) -> {
            SearchEngineConfig cfg = data.get(position);
            showEditDialog(cfg);
        });

        // 不再支持长按删除，直接不设置 OnItemLongClickListener
    }
    private void showEditDialog(SearchEngineConfig target) {
        if (target == null) return;
        SearchEngineConfig working = target;

        View dialogView = LayoutInflater.from(this).inflate(R.layout.item_engine, null, false);
        EditText etName = dialogView.findViewById(R.id.etName);
        EditText etUrl = dialogView.findViewById(R.id.etUrl);
        CheckBox cbEnabled = dialogView.findViewById(R.id.cbEnabled);

        etName.setText(working.name);
        etUrl.setText(working.searchUrl);
        cbEnabled.setChecked(working.enabled);

        new AlertDialog.Builder(this)
                .setTitle("编辑搜索引擎")
                .setView(dialogView)
                .setPositiveButton("确定", (dialog, which) -> {
                    // 只更新 name / url / enabled
                    working.name = etName.getText().toString().trim();
                    working.searchUrl = etUrl.getText().toString().trim();
                    working.enabled = cbEnabled.isChecked();

                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}