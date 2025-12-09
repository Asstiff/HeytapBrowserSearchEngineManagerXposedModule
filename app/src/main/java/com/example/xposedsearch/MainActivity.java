package com.example.xposedsearch;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ListView listView;
    private Button btnSave;
    private TextView tvHint;
    private SearchEngineAdapter adapter;
    private final List<SearchEngineConfig> data = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView = findViewById(R.id.listViewEngines);
        Button btnAdd = findViewById(R.id.btnAdd);
        btnSave = findViewById(R.id.btnSave);
        tvHint = findViewById(R.id.tvHint);

        // 更新提示文字
        tvHint.setText("勾选要显示的搜索引擎，保存后重启浏览器生效。\n首次使用请先打开浏览器一次以发现内置引擎。");

        // 不再支持"新增"，直接隐藏按钮
        if (btnAdd != null) {
            btnAdd.setVisibility(View.GONE);
        }

        loadData();
        adapter = new SearchEngineAdapter(this, data);
        listView.setAdapter(adapter);

        btnSave.setOnClickListener(v -> {
            ConfigManager.saveEngines(MainActivity.this, data);
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage("已保存，请强制停止浏览器后重新打开。")
                    .setPositiveButton("确定", null)
                    .show();
        });

        // 点击 item 编辑
        listView.setOnItemClickListener((adapterView, view, position, id) -> {
            SearchEngineConfig cfg = data.get(position);
            showEditDialog(cfg);
        });

        // 首次运行时自动保存一次，确保配置文件被创建
        if (savedInstanceState == null) {
            ConfigManager.saveEngines(this, data);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 重新加载数据，以便显示新发现的引擎
        loadData();
        adapter.notifyDataSetChanged();
    }

    private void loadData() {
        data.clear();
        data.addAll(ConfigManager.loadEngines(this));
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

        // 如果是内置引擎（URL 为空），禁用 URL 编辑
        if (working.searchUrl == null || working.searchUrl.isEmpty()) {
            etUrl.setEnabled(false);
            etUrl.setHint("内置引擎，无需 URL");
        }

        new AlertDialog.Builder(this)
                .setTitle("编辑搜索引擎: " + working.key)
                .setView(dialogView)
                .setPositiveButton("确定", (dialog, which) -> {
                    working.name = etName.getText().toString().trim();
                    if (etUrl.isEnabled()) {
                        working.searchUrl = etUrl.getText().toString().trim();
                    }
                    working.enabled = cbEnabled.isChecked();
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}