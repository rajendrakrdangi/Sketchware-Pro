package pro.sketchware.activities.main.activities;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.provider.Settings;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.besome.sketch.design.DesignActivity;
import com.besome.sketch.lib.base.BasePermissionAppCompatActivity;
import com.besome.sketch.projects.MyProjectSettingActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.FirebaseApp;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.messaging.FirebaseMessaging;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

import a.a.a.DB;
import a.a.a.GB;
import mod.hey.studios.project.backup.BackupFactory;
import mod.hey.studios.project.ProjectTracker;
import mod.hey.studios.project.backup.BackupRestoreManager;
import mod.hey.studios.util.Helper;
import mod.hilal.saif.activities.tools.ConfigActivity;
import mod.tyron.backup.SingleCopyTask;
import pro.sketchware.R;
import pro.sketchware.activities.about.AboutActivity;
import pro.sketchware.activities.main.fragments.ai.AiChatViewModel;
import pro.sketchware.activities.main.fragments.ai.AiFragment;
import pro.sketchware.activities.main.fragments.projects.ProjectsFragment;
import pro.sketchware.activities.main.fragments.projects_store.ProjectsStoreFragment;
import pro.sketchware.ai.history.AiChatSession;
import pro.sketchware.ai.history.AiHistoryAdapter;
import pro.sketchware.databinding.MainBinding;
import pro.sketchware.lib.base.BaseTextWatcher;
import pro.sketchware.lib.base.BottomSheetDialogView;
import pro.sketchware.utility.DataResetter;
import pro.sketchware.utility.FileUtil;
import pro.sketchware.utility.SketchwareUtil;
import a.a.a.lC;
import a.a.a.yB;
import java.util.HashMap;
import pro.sketchware.ai.AiCrashHandoff;
import pro.sketchware.ai.config.AiConfiguration;
import pro.sketchware.ai.config.AiConfigurationRepository;
import pro.sketchware.utility.UI;

public class MainActivity extends BasePermissionAppCompatActivity {
    private static final String PROJECTS_FRAGMENT_TAG = "projects_fragment";
    private static final String PROJECTS_STORE_FRAGMENT_TAG = "projects_store_fragment";
    private static final String AI_FRAGMENT_TAG = "ai_fragment";
    private ActionBarDrawerToggle drawerToggle;
    private DB u;
    private Snackbar storageAccessDenied;
    private MainBinding binding;
    private final OnBackPressedCallback closeDrawer = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            setEnabled(false);
            binding.drawerLayout.closeDrawers();
        }
    };
    private ProjectsFragment projectsFragment;
    private ProjectsStoreFragment projectsStoreFragment;
    private AiFragment aiFragment;
    private Fragment activeFragment;
    @IdRes
    private int currentNavItemId = R.id.item_projects;
    private AiHistoryAdapter aiHistoryAdapter;
    private AiChatViewModel aiChatViewModel;

    public final ActivityResultLauncher<Intent> programInfoLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent data = result.getData();
                if (result.getResultCode() == RESULT_OK && data != null) {
                    DataResetter.a(this, data.getBooleanExtra("onlyConfig", true));
                }
            });

    private static boolean isFirebaseInitialized(Context context) {
        try {
            return FirebaseApp.getApps(context) != null && !FirebaseApp.getApps(context).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    // onRequestPermissionsResult but for Storage access only, and only when granted
    public void g(int i) {
        if (i == 9501) {
            allFilesAccessCheck();

            if (activeFragment instanceof ProjectsFragment) {
                projectsFragment.refreshProjectsList();
            }
        }
    }

    @Override
    public void h(int i) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getApplicationContext().getPackageName()));
        startActivity(intent);
    }

    @Override
    public void l() {
    }

    @Override
    public void m() {
    }

    public void n() {
        if (activeFragment instanceof ProjectsFragment) {
            projectsFragment.refreshProjectsList();
        }
    }



    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        drawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        enableEdgeToEdgeNoContrast();

        binding = MainBinding.inflate(getLayoutInflater());

        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        binding.statusBarOverlapper.setMinimumHeight(UI.getStatusBarHeight(this));
        UI.addSystemWindowInsetToPadding(binding.appbar, true, false, true, false);

        u = new DB(getApplicationContext(), "U1");
        int u1I0 = u.a("U1I0", -1);
        long u1I1 = u.e("U1I1");
        if (u1I1 <= 0) {
            u.a("U1I1", System.currentTimeMillis());
        }
        if (System.currentTimeMillis() - u1I1 > /* (a day) */ 1000 * 60 * 60 * 24) {
            u.a("U1I0", Integer.valueOf(u1I0 + 1));
        }

        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(null);

        drawerToggle = new ActionBarDrawerToggle(this, binding.drawerLayout, R.string.app_name, R.string.app_name);
        binding.drawerLayout.addDrawerListener(drawerToggle);
        binding.drawerLayout.addDrawerListener(new DrawerLayout.DrawerListener() {
            @Override
            public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {
            }

            @Override
            public void onDrawerOpened(@NonNull View drawerView) {
                // Also when swiped open, which doesn't go through openDrawer()
                syncDrawerContent();
                closeDrawer.setEnabled(true);
                getOnBackPressedDispatcher().addCallback(closeDrawer);
            }

            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
            }

            @Override
            public void onDrawerStateChanged(int newState) {
            }
        });

        boolean hasStorageAccess = isStoragePermissionGranted();
        if (!hasStorageAccess) {
            showNoticeNeedStorageAccess();
        }
        if (hasStorageAccess) {
            allFilesAccessCheck();
        }

        if (Intent.ACTION_VIEW.equals(getIntent().getAction())) {
            Uri data = getIntent().getData();
            if (data != null) {
                new SingleCopyTask(this, new SingleCopyTask.CallBackTask() {
                    @Override
                    public void onCopyPreExecute() {
                    }

                    @Override
                    public void onCopyProgressUpdate(int progress) {
                    }

                    @Override
                    public void onCopyPostExecute(@NonNull String path, boolean wasSuccessful, @NonNull String reason) {
                        if (wasSuccessful) {
                            BackupRestoreManager manager = new BackupRestoreManager(MainActivity.this, projectsFragment);

                            if (BackupFactory.zipContainsFile(path, "local_libs")) {
                                new MaterialAlertDialogBuilder(MainActivity.this)
                                        .setTitle(R.string.common_word_warning)
                                        .setMessage(BackupRestoreManager.getRestoreIntegratedLocalLibrariesMessage(false, -1, -1, null))
                                        .setPositiveButton("Copy", (dialog, which) -> manager.doRestore(path, true))
                                        .setNegativeButton("Don't copy", (dialog, which) -> manager.doRestore(path, false))
                                        .setNeutralButton(R.string.common_word_cancel, null)
                                        .show();
                            } else {
                                manager.doRestore(path, true);
                            }

                            // Clear intent so it doesn't duplicate
                            getIntent().setData(null);
                        } else {
                            SketchwareUtil.toastError("Failed to copy backup file to temporary location: " + reason, Toast.LENGTH_LONG);
                        }
                    }
                }).copyFile(data);
            }
        }

        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.item_projects) {
                navigateToProjectsFragment();
                return true;
            } else if (id == R.id.item_ai) {
                navigateToAiFragment();
                return true;
            } else if (id == R.id.item_sketchub) {
                navigateToSketchubFragment();
                return true;
            }
            return false;
        });

        setupAiDrawer();

        if (savedInstanceState != null) {
            projectsFragment = (ProjectsFragment) getSupportFragmentManager().findFragmentByTag(PROJECTS_FRAGMENT_TAG);
            projectsStoreFragment = (ProjectsStoreFragment) getSupportFragmentManager().findFragmentByTag(PROJECTS_STORE_FRAGMENT_TAG);
            aiFragment = (AiFragment) getSupportFragmentManager().findFragmentByTag(AI_FRAGMENT_TAG);
            currentNavItemId = savedInstanceState.getInt("selected_tab_id");
            Fragment current = getFragmentForNavId(currentNavItemId);
            if (current instanceof ProjectsFragment) {
                navigateToProjectsFragment();
            } else if (current instanceof ProjectsStoreFragment) {
                navigateToSketchubFragment();
            } else if (current instanceof AiFragment) {
                navigateToAiFragment();
            }

            return;
        }

        navigateToProjectsFragment();
    }

    private Fragment getFragmentForNavId(int navItemId) {
        if (navItemId == R.id.item_projects) {
            return projectsFragment;
        } else if (navItemId == R.id.item_sketchub) {
            return projectsStoreFragment;
        } else if (navItemId == R.id.item_ai) {
            return aiFragment;
        }
        throw new IllegalArgumentException();
    }

    /**
     * Opens a project in the editor, e.g. one the AI tab has just generated.
     */
    public void openProject(String sc_id) {
        Intent intent = new Intent(this, DesignActivity.class);
        ProjectTracker.setScId(sc_id);
        intent.putExtra("sc_id", sc_id);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    /**
     * Opens the settings of an existing project, e.g. to rename one the AI tab has generated.
     */
    public void editProject(String sc_id) {
        Intent intent = new Intent(this, MyProjectSettingActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("sc_id", sc_id);
        intent.putExtra("is_update", true);
        if (projectsFragment != null) {
            // Refreshes the project's entry in the list when the user saves
            projectsFragment.openProjectSettings.launch(intent);
        } else {
            startActivity(intent);
        }
    }

    public void openDrawer() {
        syncDrawerContent();
        binding.drawerLayout.openDrawer(GravityCompat.START);
    }

    /**
     * Makes the drawer show what belongs to the current tab: the chat history on the AI tab, the
     * app menu elsewhere. Done on every opening, so the drawer can't keep showing a stale or
     * empty panel whatever happened while it was closed.
     */
    private void syncDrawerContent() {
        boolean ai = currentNavItemId == R.id.item_ai;
        View history = binding.aiDrawer.getRoot();
        binding.leftDrawer.setVisibility(ai ? View.GONE : View.VISIBLE);
        history.setVisibility(ai ? View.VISIBLE : View.GONE);
        if (ai) {
            history.bringToFront();
            // Draw what's known right away, then reload it from storage
            showAiSessions();
            refreshAiDrawerHistory();
            history.requestLayout();
            history.invalidate();
        }
    }

    /**
     * Reloads the project list, no matter which tab is showing.
     */
    public void refreshProjects() {
        if (projectsFragment != null) {
            projectsFragment.refreshProjectsList();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("selected_tab_id", currentNavItemId);
    }

    private void navigateToProjectsFragment() {
        if (projectsFragment == null) {
            projectsFragment = new ProjectsFragment();
        }

        binding.leftDrawer.setVisibility(View.VISIBLE);
        binding.aiDrawer.aiDrawerRoot.setVisibility(View.GONE);

        boolean shouldShow = true;
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction transaction = fm.beginTransaction();

        binding.createNewProject.show();
        // The AI tab collapses the search bar to show its own top bar
        binding.appbar.setExpanded(true, false);
        if (activeFragment != null) transaction.hide(activeFragment);
        if (fm.findFragmentByTag(PROJECTS_FRAGMENT_TAG) == null) {
            shouldShow = false;
            transaction.add(binding.container.getId(), projectsFragment, PROJECTS_FRAGMENT_TAG);
        }
        if (shouldShow) transaction.show(projectsFragment);
        transaction.commit();

        activeFragment = projectsFragment;
        currentNavItemId = R.id.item_projects;
    }

    private void navigateToSketchubFragment() {
        if (projectsStoreFragment == null) {
            projectsStoreFragment = new ProjectsStoreFragment();
        }

        binding.leftDrawer.setVisibility(View.VISIBLE);
        binding.aiDrawer.aiDrawerRoot.setVisibility(View.GONE);

        boolean shouldShow = true;
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction transaction = fm.beginTransaction();

        binding.createNewProject.hide();
        binding.appbar.setExpanded(true, false);
        if (activeFragment != null) transaction.hide(activeFragment);
        if (fm.findFragmentByTag(PROJECTS_STORE_FRAGMENT_TAG) == null) {
            shouldShow = false;
            transaction.add(binding.container.getId(), projectsStoreFragment, PROJECTS_STORE_FRAGMENT_TAG);
        }
        if (shouldShow) transaction.show(projectsStoreFragment);
        transaction.commit();

        activeFragment = projectsStoreFragment;
        currentNavItemId = R.id.item_sketchub;
    }

    private void navigateToAiFragment() {
        if (aiFragment == null) {
            aiFragment = new AiFragment();
        }

        binding.leftDrawer.setVisibility(View.GONE);
        binding.aiDrawer.aiDrawerRoot.setVisibility(View.VISIBLE);
        refreshAiDrawerHistory();

        boolean shouldShow = true;
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction transaction = fm.beginTransaction();

        binding.createNewProject.hide();
        if (activeFragment != null) transaction.hide(activeFragment);
        if (fm.findFragmentByTag(AI_FRAGMENT_TAG) == null) {
            shouldShow = false;
            transaction.add(binding.container.getId(), aiFragment, AI_FRAGMENT_TAG);
        }
        if (shouldShow) transaction.show(aiFragment);
        transaction.commit();

        activeFragment = aiFragment;
        currentNavItemId = R.id.item_ai;
    }

    private void setupAiDrawer() {
        aiChatViewModel = new ViewModelProvider(this).get(AiChatViewModel.class);

        // A plain View with wrap_content takes all the height it's offered, which pushed the whole
        // history below the screen; it gets exactly the status bar's height instead
        View statusBarSpace = binding.aiDrawer.statusBarOverlapper;
        android.view.ViewGroup.LayoutParams spaceParams = statusBarSpace.getLayoutParams();
        spaceParams.height = UI.getStatusBarHeight(this);
        statusBarSpace.setLayoutParams(spaceParams);
        // Keeps "Clear all history" above the gesture bar
        UI.addSystemWindowInsetToPadding(binding.aiDrawer.getRoot(), false, false, false, true);

        aiHistoryAdapter = new AiHistoryAdapter(new AiHistoryAdapter.Listener() {
            @Override
            public void onSessionClick(@NonNull AiChatSession session) {
                if (isAiBusy()) {
                    return;
                }
                aiChatViewModel.loadSession(session.getId());
                binding.drawerLayout.closeDrawer(GravityCompat.START);
            }

            @Override
            public void onSessionRename(@NonNull AiChatSession session) {
                showRenameAiSessionDialog(session);
            }

            @Override
            public void onSessionDelete(@NonNull AiChatSession session) {
                boolean current = session.getId().equals(aiChatViewModel.getCurrentSessionId());
                if (current && isAiBusy()) {
                    return;
                }
                new MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle(R.string.ai_history_delete_session_title)
                        .setMessage(getString(R.string.ai_history_delete_session_named, session.getTitle()))
                        .setPositiveButton(R.string.common_word_delete, (dialog, which) -> {
                            aiChatViewModel.deleteSession(session.getId());
                            SketchwareUtil.toast(getString(R.string.ai_history_session_deleted));
                        })
                        .setNegativeButton(R.string.common_word_cancel, null)
                        .show();
            }
        });

        binding.aiDrawer.recyclerSessions.setAdapter(aiHistoryAdapter);

        binding.aiDrawer.btnNewChat.setOnClickListener(v -> {
            if (isAiBusy()) {
                return;
            }
            aiChatViewModel.newChat();
            binding.drawerLayout.closeDrawer(GravityCompat.START);
        });

        binding.aiDrawer.btnClearAll.setOnClickListener(v -> {
            if (isAiBusy()) {
                return;
            }
            new MaterialAlertDialogBuilder(MainActivity.this)
                    .setTitle(R.string.ai_history_clear_confirm_title)
                    .setMessage(R.string.ai_history_clear_confirm_message)
                    .setPositiveButton(R.string.ai_history_clear_all, (dialog, which) -> {
                        aiChatViewModel.clearHistory();
                        SketchwareUtil.toast(getString(R.string.ai_history_cleared));
                    })
                    .setNegativeButton(R.string.common_word_cancel, null)
                    .show();
        });

        binding.aiDrawer.search.addTextChangedListener(new BaseTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                aiChatViewModel.searchSessions(s.toString());
            }
        });

        // The list follows every save, rename and delete, wherever it happens
        aiChatViewModel.getSessions().observe(this, sessions -> showAiSessions());
        aiChatViewModel.getActiveSessionId().observe(this, id -> showAiSessions());
        aiChatViewModel.getHistoryError().observe(this, message -> {
            if (message != null) {
                SketchwareUtil.toastError(message, Toast.LENGTH_LONG);
                aiChatViewModel.onHistoryErrorShown();
            }
        });
        aiChatViewModel.isSessionsLoading().observe(this, loading ->
                binding.aiDrawer.loading.setVisibility(Boolean.TRUE.equals(loading) ? View.VISIBLE : View.INVISIBLE));
    }

    /**
     * @return Whether the AI is working, in which case the chat can't be switched; tells the user.
     */
    private boolean isAiBusy() {
        if (Boolean.TRUE.equals(aiChatViewModel.isBusy().getValue())) {
            SketchwareUtil.toast(getString(R.string.ai_history_busy));
            return true;
        }
        return false;
    }

    private void showAiSessions() {
        if (aiHistoryAdapter == null || aiChatViewModel == null) {
            return;
        }
        List<AiChatSession> sessions = aiChatViewModel.getSessions().getValue();
        if (sessions == null) {
            sessions = List.of();
        }
        aiHistoryAdapter.submitList(sessions, aiChatViewModel.getActiveSessionId().getValue());
        boolean searching = !Helper.getText(binding.aiDrawer.search).trim().isEmpty();
        boolean empty = sessions.isEmpty();
        binding.aiDrawer.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.aiDrawer.emptyTitle.setText(searching ? R.string.ai_history_no_matches_title : R.string.ai_history_empty_title);
        binding.aiDrawer.emptyMessage.setText(searching ? R.string.ai_history_no_matches_message : R.string.ai_history_empty_message);
        binding.aiDrawer.recyclerSessions.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.aiDrawer.btnClearAll.setVisibility(empty && !searching ? View.GONE : View.VISIBLE);
    }

    private void showRenameAiSessionDialog(AiChatSession session) {
        TextInputLayout layout = new TextInputLayout(this);
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setSingleLine(true);
        input.setText(session.getTitle());
        input.setSelectAllOnFocus(true);
        layout.addView(input);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding / 2, padding, 0);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ai_history_rename_title)
                .setView(layout)
                .setPositiveButton(R.string.ai_history_rename, null)
                .setNegativeButton(R.string.common_word_cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String title = Helper.getText(input).trim();
                if (title.isEmpty()) {
                    layout.setError(getString(R.string.ai_history_rename_empty));
                    return;
                }
                aiChatViewModel.renameSession(session.getId(), title);
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void refreshAiDrawerHistory() {
        if (aiChatViewModel != null) {
            aiChatViewModel.refreshSessions();
        }
    }

    @NonNull
    private BottomSheetDialogView getBottomSheetDialogView() {
        BottomSheetDialogView bottomSheetDialog = new BottomSheetDialogView(this);
        bottomSheetDialog.setTitle("Major changes in v7.0.0");
        bottomSheetDialog.setDescription("""
                There have been major changes since v6.3.0 fix1, \
                and it's very important to know them all if you want your projects to still work.
                
                You can view all changes whenever you want at the About Sketchware Pro screen.""");

        bottomSheetDialog.setPositiveButton("View changes", (dialog, which) -> {
            ConfigActivity.setSetting(ConfigActivity.SETTING_CRITICAL_UPDATE_REMINDER, true);
            Intent launcher = new Intent(this, AboutActivity.class);
            launcher.putExtra("select", "changelog");
            startActivity(launcher);
        });
        bottomSheetDialog.setCancelable(false);
        return bottomSheetDialog;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        drawerToggle.syncState();
        if (isFirebaseInitialized(this)) {
            FirebaseMessaging.getInstance().subscribeToTopic("all");
        }
    }

    /**
     * Fixes a crash the user sent from the logcat reader, once the project editor has saved and
     * closed the project: the AI tab opens with a new chat that has the project attached.
     */
    private void startCrashFix() {
        AiCrashHandoff.Request request = AiCrashHandoff.take();
        if (request == null) {
            return;
        }
        String name = null;
        for (HashMap<String, Object> project : lC.a()) {
            if (request.sc_id().equals(yB.c(project, "sc_id"))) {
                name = yB.c(project, "my_app_name");
            }
        }
        if (name == null) {
            SketchwareUtil.toastError(getString(R.string.ai_crash_project_missing));
            return;
        }
        binding.bottomNav.setSelectedItemId(R.id.item_ai);
        AiConfiguration configuration = new AiConfigurationRepository(this).getSelected();
        if (configuration == null) {
            SketchwareUtil.toast(getString(R.string.ai_crash_no_model));
            return;
        }
        AiChatViewModel viewModel = new ViewModelProvider(this).get(AiChatViewModel.class);
        if (!viewModel.fixCrash(new AiChatViewModel.AttachedProject(request.sc_id(), name), request.crash(), configuration)) {
            SketchwareUtil.toast(getString(R.string.ai_crash_busy));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        /* Check if the device is running low on storage space */
        long freeMegabytes = GB.c();
        if (freeMegabytes < 100 && freeMegabytes > 0) {
            showNoticeNotEnoughFreeStorageSpace();
        }
        if (isStoragePermissionGranted() && storageAccessDenied != null && storageAccessDenied.isShown()) {
            storageAccessDenied.dismiss();
        }
        startCrashFix();
        Bundle bundle = new Bundle();
        bundle.putString(FirebaseAnalytics.Param.SCREEN_NAME, "MainActivity");
        bundle.putString(FirebaseAnalytics.Param.SCREEN_CLASS, "MainActivity");
        mAnalytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, bundle);
    }

    private void allFilesAccessCheck() {
        if (Build.VERSION.SDK_INT > 29) {
            File optOutFile = new File(getFilesDir(), ".skip_all_files_access_notice");
            boolean granted = Environment.isExternalStorageManager();

            if (!optOutFile.exists() && !granted) {
                MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this);
                dialog.setIcon(R.drawable.ic_expire_48dp);
                dialog.setTitle(R.string.title_android_11_storage_access);
                dialog.setMessage("Starting with Android 11, Sketchware Pro needs a new permission to avoid " + "taking ages to build projects. Don't worry, we can't do more to storage than " + "with current granted permissions.");
                dialog.setPositiveButton(R.string.common_word_settings, (v, which) -> {
                    FileUtil.requestAllFilesAccessPermission(this);
                    v.dismiss();
                });
                dialog.setNegativeButton(R.string.common_word_skip, null);
                dialog.setNeutralButton(R.string.common_word_don_t_show_anymore, (v, which) -> {
                    try {
                        if (!optOutFile.createNewFile())
                            throw new IOException("Failed to create file " + optOutFile);
                    } catch (IOException e) {
                        Log.e("MainActivity", "Error while trying to create " + "\"Don't show Android 11 hint\" dialog file: " + e.getMessage(), e);
                    }
                    v.dismiss();
                });
                dialog.show();
            }
        }
    }

    private void showNoticeNeedStorageAccess() {
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this);
        dialog.setTitle(R.string.common_message_permission_title_storage);
        dialog.setIcon(R.drawable.color_about_96);
        dialog.setMessage(R.string.common_message_permission_need_load_project);
        dialog.setPositiveButton(R.string.common_word_ok, (v, which) -> {
            v.dismiss();
            requestStoragePermission(9501);
        });
        dialog.show();
    }

    private void showNoticeNotEnoughFreeStorageSpace() {
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this);
        dialog.setTitle(R.string.common_message_insufficient_storage_space_title);
        dialog.setIcon(R.drawable.high_priority_96_red);
        dialog.setMessage(R.string.common_message_insufficient_storage_space);
        dialog.setPositiveButton(R.string.common_word_ok, null);
        dialog.show();
    }

    public void s() {
        if (storageAccessDenied == null || !storageAccessDenied.isShown()) {
            storageAccessDenied = Snackbar.make(binding.layoutCoordinator, Helper.getResString(R.string.common_message_permission_denied), Snackbar.LENGTH_INDEFINITE);
            storageAccessDenied.setAction(Helper.getResString(R.string.common_word_settings), v -> {
                storageAccessDenied.dismiss();
                requestStoragePermission(9501);
            });
            storageAccessDenied.setActionTextColor(Color.YELLOW);
            storageAccessDenied.show();
        }
    }

}
