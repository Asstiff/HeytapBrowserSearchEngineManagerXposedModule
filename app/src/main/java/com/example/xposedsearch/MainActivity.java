package com.example.xposedsearch;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TARGET_PACKAGE = "com.heytap.browser";

    private RecyclerView recyclerView;
    private TextView emptyView;
    private TextView rootStatusView;
    private Button btnForceStop;
    private Button btnRequestRoot;
    private SwitchMaterial switchHideIcon;
    private EngineAdapter adapter;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private boolean hasRootAccess = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupListeners();
        checkRootStatus();
        refreshList();
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);
        rootStatusView = findViewById(R.id.rootStatus);
        btnForceStop = findViewById(R.id.btnForceStop);
        btnRequestRoot = findViewById(R.id.btnRequestRoot);
        switchHideIcon = findViewById(R.id.switchHideIcon);
        FloatingActionButton fab = findViewById(R.id.fab);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new EngineAdapter();
        recyclerView.setAdapter(adapter);

        fab.setOnClickListener(v -> showAddDialog());

        // 初始化隐藏图标开关状态
        switchHideIcon.setChecked(AppUtils.isIconHidden(this));
    }

    private void setupListeners() {
        btnForceStop.setOnClickListener(v -> forceStopBrowser());

        btnRequestRoot.setOnClickListener(v -> requestRootAccess());

        switchHideIcon.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // 显示警告对话框
                new AlertDialog.Builder(this)
                        .setTitle("隐藏桌面图标")
                        .setMessage("隐藏后，只能通过 LSPosed 模块管理界面打开本应用。\n\n确定要隐藏图标吗？")
                        .setPositiveButton("确定", (dialog, which) -> {
                            AppUtils.setIconHidden(this, true);
                            Toast.makeText(this, "桌面图标已隐藏\n可通过 LSPosed 打开本应用", Toast.LENGTH_LONG).show();
                        })
                        .setNegativeButton("取消", (dialog, which) -> {
                            switchHideIcon.setChecked(false);
                        })
                        .setOnCancelListener(dialog -> {
                            switchHideIcon.setChecked(false);
                        })
                        .show();
            } else {
                AppUtils.setIconHidden(this, false);
                Toast.makeText(this, "桌面图标已恢复显示", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void checkRootStatus() {
        executor.execute(() -> {
            hasRootAccess = RootUtils.checkRootAccess();
            mainHandler.post(() -> updateRootUI());
        });
    }

    private void updateRootUI() {
        if (hasRootAccess) {
            rootStatusView.setText("Root 状态: ✓ 已授权");
            rootStatusView.setTextColor(0xFF4CAF50);
            btnRequestRoot.setVisibility(View.GONE);
            btnForceStop.setEnabled(true);
        } else {
            rootStatusView.setText("Root 状态: ✗ 未授权");
            rootStatusView.setTextColor(0xFFF44336);
            btnRequestRoot.setVisibility(View.VISIBLE);
            btnForceStop.setEnabled(false);
        }
    }

    private void requestRootAccess() {
        btnRequestRoot.setEnabled(false);
        btnRequestRoot.setText("请求中...");

        executor.execute(() -> {
            boolean granted = RootUtils.requestRootAccess();
            hasRootAccess = granted;

            mainHandler.post(() -> {
                btnRequestRoot.setEnabled(true);
                btnRequestRoot.setText("请求 Root 权限");
                updateRootUI();

                if (granted) {
                    Toast.makeText(this, "Root 权限已获取", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Root 权限获取失败", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void forceStopBrowser() {
        if (!hasRootAccess) {
            Toast.makeText(this, "需要 Root 权限", Toast.LENGTH_SHORT).show();
            return;
        }

        btnForceStop.setEnabled(false);
        btnForceStop.setText("正在停止...");

        executor.execute(() -> {
            boolean success = RootUtils.forceStopApp(TARGET_PACKAGE);

            mainHandler.post(() -> {
                btnForceStop.setEnabled(true);
                btnForceStop.setText("强制停止浏览器");

                if (success) {
                    Toast.makeText(this, "浏览器已强制停止", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "强制停止失败", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
        // 同步开关状态
        switchHideIcon.setOnCheckedChangeListener(null);
        switchHideIcon.setChecked(AppUtils.isIconHidden(this));
        switchHideIcon.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                new AlertDialog.Builder(this)
                        .setTitle("隐藏桌面图标")
                        .setMessage("隐藏后，只能通过 LSPosed 模块管理界面打开本应用。\n\n确定要隐藏图标吗？")
                        .setPositiveButton("确定", (dialog, which) -> {
                            AppUtils.setIconHidden(this, true);
                            Toast.makeText(this, "桌面图标已隐藏", Toast.LENGTH_LONG).show();
                        })
                        .setNegativeButton("取消", (dialog, which) -> switchHideIcon.setChecked(false))
                        .setOnCancelListener(dialog -> switchHideIcon.setChecked(false))
                        .show();
            } else {
                AppUtils.setIconHidden(this, false);
                Toast.makeText(this, "桌面图标已恢复显示", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void refreshList() {
        List<SearchEngineConfig> engines = ConfigManager.loadEngines(this);
        adapter.setData(engines);

        if (engines.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_open_browser) {
            openBrowser();
            return true;
        } else if (id == R.id.action_browser_settings) {
            openBrowserSettings();
            return true;
        } else if (id == R.id.action_about) {
            showAboutDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openBrowser() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
            if (intent != null) {
                startActivity(intent);
            } else {
                Toast.makeText(this, "未找到浏览器应用", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private void openBrowserSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + TARGET_PACKAGE));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开应用设置", Toast.LENGTH_SHORT).show();
        }
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setMessage("HeytapEngineManager\n\n" +
                        "用于管理 Heytap 浏览器的搜索引擎列表。\n\n" +
                        "使用方法：\n" +
                        "1. 在 LSPosed 中启用本模块\n" +
                        "2. 打开浏览器的搜索引擎设置页面\n" +
                        "3. 模块会自动获取引擎列表\n" +
                        "4. 在本应用中管理引擎\n" +
                        "5. 强制停止浏览器后重新打开生效")
                .setPositiveButton("确定", null)
                .show();
    }

    private void showEditDialog(SearchEngineConfig engine) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_engine, null);
        EditText editKey = dialogView.findViewById(R.id.editKey);
        EditText editName = dialogView.findViewById(R.id.editName);
        EditText editUrl = dialogView.findViewById(R.id.editUrl);
        TextView originalInfo = dialogView.findViewById(R.id.originalInfo);

        editKey.setText(engine.key);
        editKey.setEnabled(false);
        editName.setText(engine.name);
        editUrl.setText(engine.searchUrl);

        if (engine.isBuiltin && engine.originalSearchUrl != null) {
            originalInfo.setVisibility(View.VISIBLE);
            originalInfo.setText("原始名称: " + engine.originalName + "\n原始URL: " + engine.originalSearchUrl);
        } else {
            originalInfo.setVisibility(View.GONE);
        }

        new AlertDialog.Builder(this)
                .setTitle("编辑搜索引擎")
                .setView(dialogView)
                .setPositiveButton("保存", (dialog, which) -> {
                    String newName = editName.getText().toString().trim();
                    String newUrl = editUrl.getText().toString().trim();

                    if (newName.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    ConfigManager.updateEngineByUser(this, engine.key, newName, newUrl, engine.enabled);
                    refreshList();
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAddDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_engine, null);
        EditText editKey = dialogView.findViewById(R.id.editKey);
        EditText editName = dialogView.findViewById(R.id.editName);
        EditText editUrl = dialogView.findViewById(R.id.editUrl);
        TextView originalInfo = dialogView.findViewById(R.id.originalInfo);

        editKey.setEnabled(true);
        editKey.setHint("唯一标识符 (如: google)");
        editName.setHint("显示名称 (如: Google)");
        editUrl.setHint("搜索URL (如: https://google.com/search?q=%s)");
        originalInfo.setVisibility(View.GONE);

        new AlertDialog.Builder(this)
                .setTitle("添加自定义搜索引擎")
                .setView(dialogView)
                .setPositiveButton("添加", (dialog, which) -> {
                    String key = editKey.getText().toString().trim();
                    String name = editName.getText().toString().trim();
                    String url = editUrl.getText().toString().trim();

                    if (key.isEmpty()) {
                        Toast.makeText(this, "标识符不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (name.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (url.isEmpty()) {
                        Toast.makeText(this, "URL不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!url.contains("%s") && !url.contains("{searchTerms}")) {
                        Toast.makeText(this, "URL必须包含 %s 或 {searchTerms} 作为搜索词占位符", Toast.LENGTH_LONG).show();
                        return;
                    }

                    boolean success = ConfigManager.addCustomEngine(this, key, name, url);
                    if (success) {
                        refreshList();
                        Toast.makeText(this, "已添加", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "标识符已存在", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeleteConfirmDialog(SearchEngineConfig engine) {
        new AlertDialog.Builder(this)
                .setTitle("删除搜索引擎")
                .setMessage("确定要删除 \"" + engine.name + "\" 吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    ConfigManager.deleteEngine(this, engine.key);
                    refreshList();
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== Adapter ====================

    private class EngineAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_HEADER = 0;
        private static final int TYPE_ITEM = 1;

        private List<Object> items = new ArrayList<>();

        public void setData(List<SearchEngineConfig> engines) {
            items.clear();

            List<SearchEngineConfig> builtinEngines = new ArrayList<>();
            List<SearchEngineConfig> customEngines = new ArrayList<>();

            for (SearchEngineConfig e : engines) {
                if (e.isBuiltin) {
                    builtinEngines.add(e);
                } else {
                    customEngines.add(e);
                }
            }

            if (!builtinEngines.isEmpty()) {
                items.add("浏览器内置引擎");
                items.addAll(builtinEngines);
            }

            if (!customEngines.isEmpty()) {
                items.add("自定义引擎");
                items.addAll(customEngines);
            }

            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position) instanceof String ? TYPE_HEADER : TYPE_ITEM;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_HEADER) {
                View view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_header, parent, false);
                return new HeaderViewHolder(view);
            } else {
                View view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_engine, parent, false);
                return new EngineViewHolder(view);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderViewHolder) {
                ((HeaderViewHolder) holder).title.setText((String) items.get(position));
            } else if (holder instanceof EngineViewHolder) {
                ((EngineViewHolder) holder).bind((SearchEngineConfig) items.get(position));
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class HeaderViewHolder extends RecyclerView.ViewHolder {
            TextView title;

            HeaderViewHolder(View view) {
                super(view);
                title = view.findViewById(R.id.headerTitle);
            }
        }

        class EngineViewHolder extends RecyclerView.ViewHolder {
            TextView name, key, url, modifiedBadge;
            SwitchMaterial toggle;
            ImageButton btnEdit, btnDelete;
            Button btnReset;

            EngineViewHolder(View view) {
                super(view);
                name = view.findViewById(R.id.engineName);
                key = view.findViewById(R.id.engineKey);
                url = view.findViewById(R.id.engineUrl);
                modifiedBadge = view.findViewById(R.id.modifiedBadge);
                toggle = view.findViewById(R.id.engineToggle);
                btnEdit = view.findViewById(R.id.btnEdit);
                btnDelete = view.findViewById(R.id.btnDelete);
                btnReset = view.findViewById(R.id.btnReset);
            }

            void bind(SearchEngineConfig engine) {
                name.setText(engine.name);
                key.setText(engine.key);

                if (engine.searchUrl != null && !engine.searchUrl.isEmpty()) {
                    String urlText = engine.searchUrl;
                    if (urlText.length() > 50) {
                        urlText = urlText.substring(0, 50) + "...";
                    }
                    url.setText(urlText);
                    url.setVisibility(View.VISIBLE);
                } else {
                    url.setVisibility(View.GONE);
                }

                toggle.setOnCheckedChangeListener(null);
                toggle.setChecked(engine.enabled);
                toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    ConfigManager.updateEngineEnabled(MainActivity.this, engine.key, isChecked);
                });

                btnEdit.setOnClickListener(v -> showEditDialog(engine));

                if (engine.isBuiltin) {
                    btnReset.setVisibility(engine.canReset() ? View.VISIBLE : View.GONE);
                    btnDelete.setVisibility(View.GONE);
                    btnReset.setOnClickListener(v -> {
                        ConfigManager.resetEngine(MainActivity.this, engine.key);
                        refreshList();
                        Toast.makeText(MainActivity.this, "已恢复默认", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    btnReset.setVisibility(View.GONE);
                    btnDelete.setVisibility(View.VISIBLE);
                    btnDelete.setOnClickListener(v -> showDeleteConfirmDialog(engine));
                }

                modifiedBadge.setVisibility(engine.isModified ? View.VISIBLE : View.GONE);
            }
        }
    }
}