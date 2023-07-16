package com.drdisagree.iconify.ui.activities;

import static com.drdisagree.iconify.common.Preferences.FIRST_INSTALL;
import static com.drdisagree.iconify.common.Preferences.ON_HOME_PAGE;
import static com.drdisagree.iconify.common.Preferences.UPDATE_DETECTED;
import static com.drdisagree.iconify.common.Preferences.VER_CODE;
import static com.drdisagree.iconify.utils.SystemUtil.isDarkMode;
import static com.drdisagree.iconify.utils.helpers.Logger.writeLog;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.TransitionDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.activity.OnBackPressedCallback;
import androidx.core.content.res.ResourcesCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieCompositionFactory;
import com.airbnb.lottie.RenderMode;
import com.drdisagree.iconify.BuildConfig;
import com.drdisagree.iconify.R;
import com.drdisagree.iconify.common.Resources;
import com.drdisagree.iconify.config.Prefs;
import com.drdisagree.iconify.config.RPrefs;
import com.drdisagree.iconify.databinding.ActivityOnboardingBinding;
import com.drdisagree.iconify.ui.adapters.OnboardingAdapter;
import com.drdisagree.iconify.ui.utils.Animatoo;
import com.drdisagree.iconify.ui.utils.TaskExecutor;
import com.drdisagree.iconify.ui.views.InstallationDialog;
import com.drdisagree.iconify.utils.FileUtil;
import com.drdisagree.iconify.utils.ModuleUtil;
import com.drdisagree.iconify.utils.OverlayUtil;
import com.drdisagree.iconify.utils.RootUtil;
import com.drdisagree.iconify.utils.SystemUtil;
import com.drdisagree.iconify.utils.compiler.OnBoardingCompiler;
import com.drdisagree.iconify.utils.helpers.BackupRestore;
import com.topjohnwu.superuser.Shell;

import org.zeroturnaround.zip.ZipUtil;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class Onboarding extends BaseActivity {

    private static boolean hasErroredOut = false, rebootRequired = false;
    private static StartInstallationProcess installModule = null;
    private final String TAG = Onboarding.class.getSimpleName();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int previousPage = 0;
    private ViewPager2 mViewPager;
    private Button btnNextStep;
    private LottieAnimationView loading_anim;
    @SuppressLint("StaticFieldLeak")
    private InstallationDialog progressDialog;
    private OnboardingAdapter mAdapter = null;
    private String logger = null, prev_log = null;
    public static boolean skippedInstallation = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityOnboardingBinding binding = ActivityOnboardingBinding.inflate(getLayoutInflater());
        View mView = binding.getRoot();
        setContentView(mView);

        Prefs.putBoolean(ON_HOME_PAGE, false);

        mViewPager = binding.viewPager;
        mAdapter = new OnboardingAdapter(this, this);
        mViewPager.setAdapter(mAdapter);

        // Progress dialog while installing
        progressDialog = new InstallationDialog(this);

        AtomicBoolean clickedContinue = new AtomicBoolean(false);

        // Skip button
        TextView btnSkip = binding.btnSkip;
        btnSkip.setOnClickListener(v -> {
            int lastItemIndex = Objects.requireNonNull(mViewPager.getAdapter()).getItemCount() - 1;
            mViewPager.setCurrentItem(lastItemIndex, true);
            Animatoo.animateSlideLeft(this);
        });

        // Next button
        btnNextStep = findViewById(R.id.btn_next_step);
        mViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                final int duration = 200;

                if (position != previousPage) {
                    handler.removeCallbacksAndMessages(null);

                    if (position > previousPage) {
                        getWindow().getDecorView().setBackground(getWindowDrawables()[previousPage]);
                        btnNextStep.setBackground(getButtonDrawables()[previousPage]);

                        ((TransitionDrawable) getWindow().getDecorView().getBackground()).startTransition(duration);
                        ((TransitionDrawable) btnNextStep.getBackground()).startTransition(duration);

                        handler.postDelayed(() -> {
                            getWindow().getDecorView().setBackground(getWindowDrawables()[position]);
                            btnNextStep.setBackground(getButtonDrawables()[position]);
                        }, duration);
                    } else {
                        getWindow().getDecorView().setBackground(getWindowDrawables()[position]);
                        btnNextStep.setBackground(getButtonDrawables()[position]);

                        ((TransitionDrawable) getWindow().getDecorView().getBackground()).startTransition(0);
                        ((TransitionDrawable) btnNextStep.getBackground()).startTransition(0);
                        ((TransitionDrawable) getWindow().getDecorView().getBackground()).reverseTransition(duration);
                        ((TransitionDrawable) btnNextStep.getBackground()).reverseTransition(duration);

                        handler.postDelayed(() -> {
                            getWindow().getDecorView().setBackground(getWindowDrawables()[position]);
                            btnNextStep.setBackground(getButtonDrawables()[position]);
                        }, duration);
                    }

                    previousPage = position;
                } else {
                    getWindow().getDecorView().setBackground(getWindowDrawables()[position]);
                    btnNextStep.setBackground(getButtonDrawables()[position]);
                }

                if (position == 2) {
                    btnSkip.setVisibility(View.INVISIBLE);
                    btnNextStep.setText(R.string.btn_lets_go);
                } else {
                    btnSkip.setVisibility(View.VISIBLE);
                    btnNextStep.setText(R.string.btn_next);
                }

                if (position == 2) {
                    // Reboot button
                    if (rebootRequired) {
                        showInfoNow(R.string.need_reboot_title, R.string.need_reboot_desc);
                        btnNextStep.setText(R.string.btn_reboot);
                    }

                    // Skip installation on long click
                    btnNextStep.setOnLongClickListener(v -> {
                        skippedInstallation = true;
                        hasErroredOut = false;

                        if (RootUtil.isDeviceRooted()) {
                            if (RootUtil.isMagiskInstalled() || RootUtil.isKSUInstalled()) {
                                if (!Environment.isExternalStorageManager()) {
                                    showInfo(R.string.need_storage_perm_title, R.string.need_storage_perm_desc);
                                    Toast.makeText(Onboarding.this, R.string.toast_storage_access, Toast.LENGTH_SHORT).show();

                                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                        clickedContinue.set(true);
                                        SystemUtil.getStoragePermission(Onboarding.this);
                                    }, clickedContinue.get() ? 10 : 2000);
                                } else {
                                    if (!ModuleUtil.moduleExists()) {
                                        Prefs.clearAllPrefs();
                                        RPrefs.clearAllPrefs();

                                        handleInstallation();
                                    } else {
                                        Intent intent = new Intent(Onboarding.this, XposedMenu.class);
                                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                                        startActivity(intent);
                                        Animatoo.animateSlideLeft(Onboarding.this);
                                        Toast.makeText(Onboarding.this, R.string.toast_skipped_installation, Toast.LENGTH_LONG).show();
                                    }
                                }
                            } else {
                                showInfo(R.string.magisk_not_found_title, R.string.magisk_not_found_desc);
                            }
                        } else {
                            showInfo(R.string.root_not_found_title, R.string.root_not_found_desc);
                        }
                        return true;
                    });
                } else {
                    btnNextStep.setOnLongClickListener(null);
                }
            }
        });

        // Start installation on click
        btnNextStep.setOnClickListener(v -> {
            if (getItem() > mViewPager.getChildCount()) {
                skippedInstallation = false;
                hasErroredOut = false;

                if (RootUtil.isDeviceRooted()) {
                    if (RootUtil.isMagiskInstalled() || RootUtil.isKSUInstalled()) {
                        if (!Environment.isExternalStorageManager()) {
                            showInfo(R.string.need_storage_perm_title, R.string.need_storage_perm_desc);
                            Toast.makeText(Onboarding.this, R.string.toast_storage_access, Toast.LENGTH_SHORT).show();

                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                clickedContinue.set(true);
                                SystemUtil.getStoragePermission(this);
                            }, clickedContinue.get() ? 10 : 2000);
                        } else {
                            boolean moduleExists = ModuleUtil.moduleExists();
                            boolean overlayExists = OverlayUtil.overlayExists();

                            if ((Prefs.getInt(VER_CODE) != BuildConfig.VERSION_CODE) || !moduleExists || !overlayExists) {
                                if (!moduleExists || !overlayExists) {
                                    Prefs.clearAllPrefs();
                                    RPrefs.clearAllPrefs();
                                }

                                handleInstallation();
                            } else {
                                Intent intent = new Intent(Onboarding.this, HomePage.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                                Animatoo.animateSlideLeft(Onboarding.this);
                            }
                        }
                    } else {
                        showInfo(R.string.magisk_not_found_title, R.string.magisk_not_found_desc);
                    }
                } else {
                    showInfo(R.string.root_not_found_title, R.string.root_not_found_desc);
                }
            } else {
                mViewPager.setCurrentItem(getItem() + 1, true);
            }
        });

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        } else {
            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    onBackPressed();
                }
            });
        }
    }

    private void handleInstallation() {
        LottieCompositionFactory.fromRawRes(this, !isDarkMode() ? R.raw.loading_day : R.raw.loading_night).addListener(result -> {
            loading_anim = findViewById(R.id.loading_anim);
            loading_anim.setMaxWidth(btnNextStep.getHeight());
            loading_anim.setMaxHeight(btnNextStep.getHeight());
            btnNextStep.setTextColor(Color.TRANSPARENT);
            loading_anim.setAnimation(!isDarkMode() ? R.raw.loading_day : R.raw.loading_night);
            loading_anim.setRenderMode(RenderMode.HARDWARE);
            loading_anim.setVisibility(View.VISIBLE);

            installModule = new Onboarding.StartInstallationProcess();
            installModule.execute();
        });
    }

    private void showInfo(int title, int desc) {
        ((com.drdisagree.iconify.ui.fragments.Onboarding) mAdapter.getCurrentFragment()).animateUpdateTextView(title, desc);
    }

    private void showInfoNow(int title, int desc) {
        ((com.drdisagree.iconify.ui.fragments.Onboarding) mAdapter.getCurrentFragment()).updateTextView(title, desc);
    }

    private int getItem() {
        return mViewPager.getCurrentItem();
    }

    private TransitionDrawable[] getWindowDrawables() {
        TransitionDrawable[] transitionDrawable = new TransitionDrawable[3];
        transitionDrawable[0] = (TransitionDrawable) ResourcesCompat.getDrawable(getResources(), R.drawable.onboarding_background_1, getTheme());
        transitionDrawable[1] = (TransitionDrawable) ResourcesCompat.getDrawable(getResources(), R.drawable.onboarding_background_2, getTheme());
        transitionDrawable[2] = (TransitionDrawable) ResourcesCompat.getDrawable(getResources(), R.drawable.onboarding_background_3, getTheme());
        return transitionDrawable;
    }

    private TransitionDrawable[] getButtonDrawables() {
        TransitionDrawable[] transitionDrawable = new TransitionDrawable[3];
        transitionDrawable[0] = (TransitionDrawable) ResourcesCompat.getDrawable(getResources(), R.drawable.onboarding_button_transition_1, getTheme());
        transitionDrawable[1] = (TransitionDrawable) ResourcesCompat.getDrawable(getResources(), R.drawable.onboarding_button_transition_2, getTheme());
        transitionDrawable[2] = (TransitionDrawable) ResourcesCompat.getDrawable(getResources(), R.drawable.onboarding_button_transition_3, getTheme());
        return transitionDrawable;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(this::onBackPressed);
            } catch (Exception ignored) {
            }
        }

        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        if (installModule != null) {
            installModule.cancel(true);
        }
    }

    @Override
    public void onBackPressed() {
        if (mViewPager.getCurrentItem() == 0) {
            super.onBackPressed();
        } else {
            mViewPager.setCurrentItem(mViewPager.getCurrentItem() - 1, true);
        }
    }

    @SuppressLint("StaticFieldLeak")
    private class StartInstallationProcess extends TaskExecutor<Void, Integer, Integer> {
        @SuppressLint("SetTextI18n")
        @Override
        protected void onPreExecute() {
            showInfo(R.string.onboarding_title_3, R.string.onboarding_desc_3);
            btnNextStep.setText(R.string.btn_lets_go);

            progressDialog.show(getResources().getString(R.string.installing), getResources().getString(R.string.init_module_installation));
        }

        @SuppressLint("SetTextI18n")
        @Override
        protected void onProgressUpdate(Integer... values) {
            super.onProgressUpdate(values);

            String title = getResources().getString(R.string.step) + ' ' + values[0] + "/6";
            String desc = getResources().getString(R.string.loading_dialog_wait);

            switch (values[0]) {
                case 1:
                    desc = getResources().getString(R.string.module_installation_step1);
                    break;
                case 2:
                    desc = getResources().getString(R.string.module_installation_step2);
                    break;
                case 3:
                    desc = getResources().getString(R.string.module_installation_step3);
                    break;
                case 4:
                    desc = getResources().getString(R.string.module_installation_step4);
                    break;
                case 5:
                    desc = getResources().getString(R.string.module_installation_step5);
                    break;
                case 6:
                    desc = getResources().getString(R.string.module_installation_step6);
                    break;
            }

            progressDialog.setMessage(title, desc);

            if (logger != null && !Objects.equals(prev_log, logger)) {
                progressDialog.setLogs(logger);
                prev_log = logger;
            }
        }

        @Override
        protected Integer doInBackground(Void... voids) {
            int step = 0;

            logger = "Creating blank module template";
            publishProgress(++step);
            ModuleUtil.handleModule();

            logger = null;
            publishProgress(++step);
            try {
                logger = "Cleaning iconify data directory";
                publishProgress(step);
                // Clean data directory
                Shell.cmd("rm -rf " + Resources.TEMP_DIR).exec();
                Shell.cmd("rm -rf " + Resources.DATA_DIR + "/Overlays").exec();

                logger = "Extracting overlays from assets";
                publishProgress(step);
                if (skippedInstallation) {
                    logger = "Skipped...";
                    publishProgress(step);
                } else {
                    // Extract overlays from assets
                    FileUtil.copyAssets("Overlays");
                    ModuleUtil.extractPremadeOverlays();
                }

                logger = "Creating temporary directories";
                publishProgress(step);
                // Create temp directory
                Shell.cmd("rm -rf " + Resources.TEMP_DIR + "; mkdir -p " + Resources.TEMP_DIR).exec();
                Shell.cmd("mkdir -p " + Resources.TEMP_OVERLAY_DIR).exec();
                Shell.cmd("mkdir -p " + Resources.UNSIGNED_UNALIGNED_DIR).exec();
                Shell.cmd("mkdir -p " + Resources.UNSIGNED_DIR).exec();
                Shell.cmd("mkdir -p " + Resources.SIGNED_DIR).exec();
            } catch (IOException e) {
                hasErroredOut = true;
                Log.e(TAG, e.toString());
            }

            logger = null;
            publishProgress(++step);
            File dir;
            if (skippedInstallation) {
                logger = "Skipping overlay builder...";
                publishProgress(step);
            } else {
                // Create AndroidManifest.xml and build APK using AAPT
                dir = new File(Resources.DATA_DIR + "/Overlays");
                if (dir.listFiles() == null) hasErroredOut = true;

                if (!hasErroredOut) {
                    for (File pkg : Objects.requireNonNull(dir.listFiles())) {
                        if (pkg.isDirectory()) {
                            for (File overlay : Objects.requireNonNull(pkg.listFiles())) {
                                if (overlay.isDirectory()) {
                                    String overlay_name = overlay.toString().replace(pkg.toString() + '/', "");

                                    if (OnBoardingCompiler.createManifest(overlay_name, pkg.toString().replace(Resources.DATA_DIR + "/Overlays/", ""), overlay.getAbsolutePath())) {
                                        hasErroredOut = true;
                                    }

                                    logger = "Building APK for " + overlay_name;
                                    publishProgress(step);

                                    if (!hasErroredOut && OnBoardingCompiler.runAapt(overlay.getAbsolutePath(), overlay_name)) {
                                        hasErroredOut = true;
                                    }
                                }
                                if (hasErroredOut) break;
                            }
                        }
                        if (hasErroredOut) break;
                    }
                }
            }

            logger = null;
            publishProgress(++step);
            if (skippedInstallation) {
                logger = "Skipping zipaligning process...";
                publishProgress(step);
            } else {
                // ZipAlign the APK
                dir = new File(Resources.UNSIGNED_UNALIGNED_DIR);
                if (dir.listFiles() == null) hasErroredOut = true;

                if (!hasErroredOut) {
                    for (File overlay : Objects.requireNonNull(dir.listFiles())) {
                        if (!overlay.isDirectory()) {
                            String overlay_name = overlay.toString().replace(Resources.UNSIGNED_UNALIGNED_DIR + '/', "").replace("-unaligned", "");

                            logger = "Zip aligning APK " + overlay_name.replace("-unsigned.apk", "");
                            publishProgress(step);

                            if (OnBoardingCompiler.zipAlign(overlay.getAbsolutePath(), overlay_name)) {
                                hasErroredOut = true;
                            }
                        }
                        if (hasErroredOut) break;
                    }
                }
            }

            logger = null;
            publishProgress(++step);
            if (skippedInstallation) {
                logger = "Skipping signing process...";
                publishProgress(step);
            } else {
                // Sign the APK
                dir = new File(Resources.UNSIGNED_DIR);
                if (dir.listFiles() == null) hasErroredOut = true;

                if (!hasErroredOut) {
                    for (File overlay : Objects.requireNonNull(dir.listFiles())) {
                        if (!overlay.isDirectory()) {
                            String overlay_name = overlay.toString().replace(Resources.UNSIGNED_DIR + '/', "").replace("-unsigned", "");

                            logger = "Signing APK " + overlay_name.replace(".apk", "");
                            publishProgress(step);

                            int attempt = 3;
                            while (attempt-- != 0) {
                                hasErroredOut = OnBoardingCompiler.apkSigner(overlay.getAbsolutePath(), overlay_name);

                                if (!hasErroredOut) break;
                                else try {
                                    Thread.sleep(2000);
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        if (hasErroredOut) break;
                    }
                }
            }

            logger = "Moving overlays to system directory";
            publishProgress(++step);
            if (skippedInstallation) {
                logger = "Skipping...";
                publishProgress(step);
            }
            // Move all generated overlays to system dir and flash as module
            if (!hasErroredOut) {
                Shell.cmd("cp -a " + Resources.SIGNED_DIR + "/. " + Resources.OVERLAY_DIR).exec();
                BackupRestore.restoreFiles();
                RootUtil.setPermissionsRecursively(644, Resources.OVERLAY_DIR + '/');

                Shell.cmd("cp -r " + Resources.MODULE_DIR + ' ' + Resources.TEMP_DIR).exec();
                Shell.cmd("rm -rf " + Resources.MODULE_DIR).exec();
                try {
                    ZipUtil.pack(new File(Resources.TEMP_DIR + "/Iconify"), new File(Resources.TEMP_DIR + "/Iconify.zip"));
                } catch (Exception exception) {
                    hasErroredOut = true;
                    writeLog(TAG, "Error creating module zip", exception.toString());
                }

                ModuleUtil.flashModule();
            }

            logger = "Cleaning temporary directories";
            publishProgress(step);
            // Clean temp directory
            Shell.cmd("rm -rf " + Resources.TEMP_DIR).exec();
            Shell.cmd("rm -rf " + Resources.DATA_DIR + "/Overlays").exec();

            logger = "Installtion process finished";
            publishProgress(step);

            return null;
        }

        @SuppressLint("NotifyDataSetChanged")
        @Override
        protected void onPostExecute(Integer integer) {
            progressDialog.hide();

            if (!hasErroredOut) {
                if (!skippedInstallation) {
                    if (BuildConfig.VERSION_CODE != Prefs.getInt(VER_CODE, -1)) {
                        if (Prefs.getBoolean(FIRST_INSTALL, true)) {
                            Prefs.putBoolean(FIRST_INSTALL, true);
                            Prefs.putBoolean(UPDATE_DETECTED, false);
                        } else {
                            Prefs.putBoolean(FIRST_INSTALL, false);
                            Prefs.putBoolean(UPDATE_DETECTED, true);
                        }
                        Prefs.putInt(VER_CODE, BuildConfig.VERSION_CODE);
                    }

                    if (OverlayUtil.overlayExists()) {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            Intent intent = new Intent(Onboarding.this, HomePage.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            Animatoo.animateSlideLeft(Onboarding.this);
                        }, 10);
                    } else {
                        rebootRequired = true;
                        showInfo(R.string.need_reboot_title, R.string.need_reboot_desc);
                        btnNextStep.setText(R.string.btn_reboot);
                        btnNextStep.setOnClickListener(view -> SystemUtil.restartDevice());
                        btnNextStep.setOnLongClickListener(null);
                    }
                } else {
                    Intent intent = new Intent(Onboarding.this, XposedMenu.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    Animatoo.animateSlideLeft(Onboarding.this);
                    Toast.makeText(Onboarding.this, R.string.one_time_reboot_needed, Toast.LENGTH_LONG).show();
                }
            } else {
                Shell.cmd("rm -rf " + Resources.MODULE_DIR).exec();
                Shell.cmd("rm -rf " + Resources.BACKUP_DIR).exec();
                showInfo(R.string.installation_failed_title, R.string.installation_failed_desc);
                btnNextStep.setText(R.string.btn_lets_go);
            }

            loading_anim.setVisibility(View.GONE);
            btnNextStep.setTextColor(getResources().getColor(R.color.textColorPrimaryInverse, getTheme()));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(Onboarding.this::onBackPressed);
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        protected void onCancelled() {
            Shell.cmd("rm -rf " + Resources.DATA_DIR).exec();
            Shell.cmd("rm -rf " + Resources.TEMP_DIR).exec();
            Shell.cmd("rm -rf " + Resources.BACKUP_DIR).exec();
            Shell.cmd("rm -rf " + Resources.MODULE_DIR).exec();
        }
    }
}