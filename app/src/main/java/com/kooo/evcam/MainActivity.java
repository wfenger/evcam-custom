package com.kooo.evcam;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.navigation.NavigationView;
import com.kooo.evcam.camera.ImageAdjustManager;
import com.kooo.evcam.camera.MultiCameraManager;

import java.util.concurrent.CountDownLatch;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS = 100;
    
    // 静态实例引用（用于悬浮窗等外部组件访问）
    private static MainActivity instance;

    // 根据Android版本动态获取需要的权限
    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            return new String[]{
                    Manifest.permission.CAMERA
            };
        } else {
            // Android 12及以下
            return new String[]{
                    Manifest.permission.CAMERA
            };
        }
    }

    private AutoFitTextureView textureFront, textureBack, textureLeft, textureRight;
    private final java.util.Map<String, android.graphics.Matrix> previewBaseTransforms = new java.util.HashMap<>();
    private PreviewCorrectionFloatingWindow previewCorrectionFloatingWindow;
    private FisheyeCorrectionFloatingWindow fisheyeCorrectionFloatingWindow;

    // 调试信息覆盖层（连点5下空白处显示）
    private TextView tvDebugOverlay;
    private boolean debugOverlayVisible = false;
    private final android.os.Handler debugUpdateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable debugUpdateRunnable;
    private int debugTapCount = 0;
    private long debugLastTapTime = 0;
    private static final int DEBUG_TAP_COUNT = 5;
    private static final long DEBUG_TAP_INTERVAL_MS = 800;  // 连续点击的最大间隔

    private Button btnExit;
    private MultiCameraManager cameraManager;

    public MultiCameraManager getCameraManager() {
        if (cameraManager == null) {
            cameraManager = com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
        }
        return cameraManager;
    }
    private ImageAdjustManager imageAdjustManager;  // 亮度/降噪调节管理器
    private ImageAdjustFloatingWindow imageAdjustFloatingWindow;  // 亮度/降噪调节悬浮窗
    private int textureReadyCount = 0;  // 记录准备好的TextureView数量
    private int requiredTextureCount = 4;  // 需要准备好的TextureView数量（根据摄像头数量）
    private boolean isInBackground = false;  // 是否在后台
    private boolean hasBeenResumedOnce = false;  // Activity 是否已经完全恢复过一次（用于区分新创建和已存在）
    
    // 车型配置相关
    private AppConfig appConfig;
    private int configuredCameraCount = 4;  // 配置的摄像头数量
    private CustomLayoutManager customLayoutManager;  // 自定义车型布局管理器

    // 导航相关
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private View recordingLayout;  // 录制界面布局
    private View fragmentContainer;  // Fragment容器

    // 存储清理管理器
    private StorageCleanupManager storageCleanupManager;
    
    // 远程命令分发器（重构后的统一入口）
    private RemoteCommandDispatcher remoteCommandDispatcher;
    
    // 心跳推图管理器
    private com.kooo.evcam.heartbeat.HeartbeatManager heartbeatManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;  // 设置静态实例引用
        AppLog.init(this);

        // 设置字体缩放比例（1.3倍）
        adjustFontScale(1.2f);

        // 初始化应用配置
        appConfig = new AppConfig(this);
        
        // 重置U盘回退提示标志（每次冷启动重置）
        AppConfig.resetSdFallbackFlag();
        
        // 根据车型配置设置布局和摄像头数量
        setupLayoutByCarModel();

        // 设置状态栏沉浸式
        setupStatusBar();

        initViews();
        setupNavigationDrawer();

        // 检查是否首次启动
        checkFirstLaunch();

        // 初始化远程命令分发器
        initRemoteCommandDispatcher();

        // 权限检查，但不立即初始化摄像头
        // 等待TextureView准备好后再初始化
        if (!checkPermissions()) {
            requestPermissions();
        }

        // 注册状态信息提供者，让远程服务能获取完整的状态信息
        // 注意：必须保持强引用（statusInfoProvider 成员变量），否则 WeakReference 会导致对象被 GC
        RemoteServiceManager.StatusInfoProvider statusInfoProvider = new RemoteServiceManager.StatusInfoProvider() {
            @Override
            public String getFullStatusInfo() {
                // 直接使用外部类引用，因为 statusInfoProvider 的生命周期与 MainActivity 绑定
                if (!isDestroyed()) {
                    return buildStatusInfo();
                }
                return null; // 返回 null 会触发使用基本状态信息
            }
        };
        RemoteServiceManager.getInstance().setStatusInfoProvider(statusInfoProvider);
        AppLog.d(TAG, "StatusInfoProvider 已注册");

        // 启动定时保活任务（车机必需，始终开启）
        KeepAliveManager.startKeepAliveWork(this);
        AppLog.d(TAG, "定时保活任务已启动");
        
        // 防止休眠（仅当开启"开机自启动"时）
        // WakeLock 主要在 CameraForegroundService 中维护
        // 这里作为备份，确保 Activity 存在时也有 WakeLock
        if (appConfig.isAutoStartOnBoot()) {
            WakeUpHelper.acquirePersistentWakeLock(this);
            AppLog.d(TAG, "WakeLock 已获取（开机自启动已开启）");
        } else {
            AppLog.d(TAG, "WakeLock 未获取（开机自启动未开启）");
        }
        
        // 启动存储清理任务（如果用户设置了限制）
        storageCleanupManager = new StorageCleanupManager(this);
        storageCleanupManager.start();
        
        // 启动文件传输服务（用于U盘中转写入模式）
        FileTransferManager.getInstance(this).start();

        // 检查是否是开机自启动
        boolean autoStartFromBoot = getIntent().getBooleanExtra("auto_start_from_boot", false);
        if (autoStartFromBoot) {
            // 清除标志，避免后续重复检测
            getIntent().removeExtra("auto_start_from_boot");
            
            // 移到后台
            AppLog.d(TAG, "开机自启动模式：未开启自动录制，等待窗口准备好后移到后台");
            // 设置标志，等待 onWindowFocusChanged 时再移到后台
            // 这确保 Activity 完全初始化后再执行，避免中断初始化过程
            new android.os.Handler().postDelayed(() -> {
                moveTaskToBack(true);  // 将应用移到后台
                AppLog.d(TAG, "应用已移到后台，开机自启动完成");
            }, 500);  // 延迟 500ms
        }

        // 检查是否有启动时传入的远程命令（冷启动）
        handleRemoteCommandFromIntent(getIntent());

        // 启动补盲选项服务 (副屏显示+主屏悬浮窗)
        // 定制键唤醒独立于补盲全局开关，单独判断
        if ((appConfig.isBlindSpotGlobalEnabled()
                && (appConfig.isSecondaryDisplayEnabled() || appConfig.isMainFloatingEnabled()
                    || appConfig.isTurnSignalLinkageEnabled() || appConfig.isMockTurnSignalFloatingEnabled()
                    || appConfig.isAvmAvoidanceEnabled()))
                || appConfig.isCustomKeyWakeupEnabled()) {
            BlindSpotService.update(this);
            AppLog.d(TAG, "补盲选项服务已启动");
        }
        
        // 初始化息屏录制检测
        initScreenStateReceiver();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        AppLog.d(TAG, "onNewIntent called");
        
        // 处理远程命令
        handleRemoteCommandFromIntent(intent);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
    }

    /**
     * 处理来自 Intent 的远程命令
     * 由 WakeUpHelper 启动时传入
     */
    private void handleRemoteCommandFromIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getStringExtra("remote_action");
        if (action == null || action.isEmpty()) {
            return;
        }

        AppLog.d(TAG, "Received remote command from intent: " + action);

        // 处理前台切换指令（不需要等待摄像头）
        if ("foreground".equals(action)) {
            intent.removeExtra("remote_action");
            AppLog.d(TAG, "Foreground command executed - app brought to front");
            // Activity 已经被启动到前台，不需要额外操作
            return;
        }
        
        // 注意：后台指令现在通过广播处理（WakeUpHelper.ACTION_MOVE_TO_BACKGROUND）
        // 不再通过 startActivity 方式，避免闪屏问题

        // 先切换到主界面（录制界面），确保显示正确的界面
        showRecordingInterface();
        AppLog.d(TAG, "Switched to recording interface");

        // 检查是否是 Telegram 命令
        String remoteSource = intent.getStringExtra("remote_source");
        if ("telegram".equals(remoteSource)) {
            long chatId = intent.getLongExtra("telegram_chat_id", 0);
            int duration = intent.getIntExtra("remote_duration", 60);
            
            // 清除 Intent 中的命令，避免重复执行
            intent.removeExtra("remote_action");
            intent.removeExtra("remote_source");
            
            AppLog.d(TAG, "Telegram command: action=" + action + ", chatId=" + chatId + ", duration=" + duration);
            
            // 标记有待处理的远程命令
            boolean pendingRemoteCommand = true;
            
            // 判断是否应该在完成后返回后台
            boolean isRemoteWakeUpIntent = intent.getBooleanExtra("remote_wake_up", false);
            boolean wasAlreadyInForeground = hasBeenResumedOnce && !isInBackground;
            boolean shouldReturnToBackground = isRemoteWakeUpIntent && !wasAlreadyInForeground;
            
            boolean isRemoteWakeUp = false;
            if (shouldReturnToBackground) {
                isRemoteWakeUp = true;
                AppLog.d(TAG, "Telegram: Remote wake-up flag set, will return to background after completion");
            } else if (wasAlreadyInForeground) {
                isRemoteWakeUp = false;
                AppLog.d(TAG, "Telegram: App was in foreground, will stay in foreground after completion");
            } else {
                isRemoteWakeUp = false;
                AppLog.d(TAG, "Telegram: No wake-up flag, staying in foreground");
            }
            
            // 延迟执行命令，等待摄像头准备好
            int delay = wasAlreadyInForeground ? 1500 : 3000;
            final String finalAction = action;
            
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                pendingRemoteCommand = false;
                
                // 检查摄像头是否准备好
                if (cameraManager == null) {
                    AppLog.e(TAG, "Telegram: CameraManager is null");
                    return;
                }
                
                int connectedCount = cameraManager.getConnectedCameraCount();
                AppLog.d(TAG, "Telegram: Connected cameras: " + connectedCount);
                
                // 如果连接的摄像头不足，继续等待
                if (!cameraManager.hasConnectedCameras()) {
                    AppLog.w(TAG, "Telegram: No cameras connected yet, waiting 1.5s more...");
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        boolean hasCamera = cameraManager != null && cameraManager.hasConnectedCameras();
                        AppLog.d(TAG, "Telegram: After waiting, hasConnectedCameras: " + hasCamera);
                    }, 1500);
                } else {
                    AppLog.d(TAG, "Telegram: Cameras ready, executing command");
                }
            }, delay);
            return;
        }
    }

    /**
     * 初始化远程命令分发器
     * 设置 CameraController 和 RecordingStateListener
     */
    private void initRemoteCommandDispatcher() {
        remoteCommandDispatcher = new RemoteCommandDispatcher(this);
        
        // 设置摄像头控制器
        remoteCommandDispatcher.setCameraController(new RemoteCommandHandler.CameraController() {
            @Override
            public boolean isRecording() {
                return false;
            }
            
            @Override
            public boolean hasConnectedCameras() {
                return cameraManager != null && cameraManager.hasConnectedCameras();
            }
            
            @Override
            public boolean startRecording(String timestamp) {
                return false;
            }
            
            @Override
            public void stopRecording(boolean skipTransfer) {
            }
            
            @Override
            public void takePicture(String timestamp) {
            }
            
            @Override
            public void stopRecordingTimer() {
            }
            
            @Override
            public void stopBlinkAnimation() {
            }
            
            @Override
            public void startRecording() {
            }
            
            @Override
            public void setSegmentDurationOverride(long durationMs) {
            }
            
            @Override
            public void clearSegmentDurationOverride() {
            }
        });
        
        // 设置录制状态监听器
        remoteCommandDispatcher.setRecordingStateListener(new RemoteCommandHandler.RecordingStateListener() {
            @Override
            public void onRemoteRecordingStart() {
            }
            
            @Override
            public void onRemoteRecordingStop() {
            }
            
            @Override
            public void onPreparing() {
            }
            
            @Override
            public void onPreparingComplete() {
            }
            
            @Override
            public void returnToBackgroundIfRemoteWakeUp() {
            }
            
            @Override
            public boolean isRemoteWakeUp() {
                return false;
            }
        });
        
        AppLog.d(TAG, "RemoteCommandDispatcher 初始化完成");
        
        // 从 RemoteServiceManager 同步已运行服务的 API 客户端
        // 这确保 Activity 重建后，远程命令处理器能正确使用已有的 API 客户端
        syncApiClientsFromRemoteServiceManager();
    }
    
    /**
     * 从 RemoteServiceManager 同步已运行服务的 API 客户端
     * 在 Activity 重建时，远程服务可能已在运行，需要同步到新的 remoteCommandDispatcher
     */
    private void syncApiClientsFromRemoteServiceManager() {
        if (remoteCommandDispatcher == null) {
            return;
        }
        
        RemoteServiceManager serviceManager = RemoteServiceManager.getInstance();
    }
    
    /**
     * 更新远程命令分发器的 API 客户端
     * 在服务启动后调用
     */
    private void updateRemoteDispatcherApiClients() {
    }

    /**
     * 切换侧边栏的打开/关闭状态
     */
    public void toggleDrawer() {
        if (drawerLayout != null) {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        }
    }

    /**
     * 设置导航抽屉
     */
    private void setupNavigationDrawer() {
        // 设置导航菜单点击监听
        navigationView.setNavigationItemSelectedListener(item -> {
            int itemId = item.getItemId();
            // 先清除所有菜单项的选中状态（处理跨组选中）
            clearAllNavigationChecks();
            
            if (itemId == R.id.nav_recording) {
                // 显示录制界面
                showRecordingInterface();
            } else if (itemId == R.id.nav_secondary_display) {
                // 显示补盲选项界面
                showBlindSpotInterface();
            } else if (itemId == R.id.nav_settings) {
                showSettingsInterface();
            }
            // 设置当前项为选中
            navigationView.setCheckedItem(itemId);
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // 默认选中录制界面
        navigationView.setCheckedItem(R.id.nav_recording);
    }
    
    /**
     * 清除所有导航菜单项的选中状态
     * 用于处理跨组选中时的状态同步
     */
    private void clearAllNavigationChecks() {
        for (int i = 0; i < navigationView.getMenu().size(); i++) {
            navigationView.getMenu().getItem(i).setChecked(false);
        }
    }

    /**
     * 检查并处理首次启动
     * 首次启动时自动进入设置界面并显示引导弹窗
     */
    private void checkFirstLaunch() {
        if (appConfig == null || !appConfig.isFirstLaunch()) {
            return;
        }

        AppLog.d(TAG, "检测到首次启动，进入设置界面");

        // 标记首次启动已完成（在显示弹窗前标记，避免重复触发）
        appConfig.setFirstLaunchCompleted();

        // 延迟执行，确保 UI 完全初始化
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            // 进入设置界面
            showSettingsInterface();
            clearAllNavigationChecks();
            navigationView.setCheckedItem(R.id.nav_settings);

            // 显示引导弹窗
            showFirstLaunchGuideDialog();
        }, 300);
    }

    /**
     * 显示首次启动引导弹窗（美化版）
     */
    private void showFirstLaunchGuideDialog() {
        // 创建自定义对话框
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_first_launch_guide);
        dialog.setCancelable(false);

        // 设置对话框窗口属性
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            // 设置背景透明（让圆角生效）
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            // 设置对话框宽度
            android.view.WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.85);
            window.setAttributes(params);
        }

        // 加载二维码图片
        android.widget.ImageView ivQrcode = dialog.findViewById(R.id.iv_qrcode);
        loadQrcodeImage(ivQrcode);

        // 设置确认按钮点击事件
        dialog.findViewById(R.id.btn_confirm).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    /**
     * 加载打赏二维码图片（URL经过混淆处理）
     */
    private void loadQrcodeImage(android.widget.ImageView imageView) {
        // 根据屏幕密度动态设置二维码尺寸
        // 低DPI大屏设备使用更大尺寸，高DPI设备使用适中尺寸
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;
        int screenWidthPx = dm.widthPixels;
        
        // 计算二维码尺寸（像素）
        // density: mdpi=1.0, hdpi=1.5, xhdpi=2.0, xxhdpi=3.0, xxxhdpi=4.0
        int qrcodeSizePx;
        if (density <= 1.0f) {
            // mdpi 或更低密度（大屏低DPI设备）：使用屏幕宽度的25%
            qrcodeSizePx = (int) (screenWidthPx * 0.25f);
        } else if (density <= 1.5f) {
            // hdpi：使用屏幕宽度的22%
            qrcodeSizePx = (int) (screenWidthPx * 0.22f);
        } else if (density <= 2.0f) {
            // xhdpi：使用屏幕宽度的20%
            qrcodeSizePx = (int) (screenWidthPx * 0.20f);
        } else {
            // xxhdpi 及以上（高密度设备）：使用屏幕宽度的18%
            qrcodeSizePx = (int) (screenWidthPx * 0.18f);
        }
        
        // 设置ImageView尺寸
        android.view.ViewGroup.LayoutParams params = imageView.getLayoutParams();
        params.width = qrcodeSizePx;
        params.height = qrcodeSizePx;
        imageView.setLayoutParams(params);
        
        // URL混淆存储，防止被轻易修改
        // 原始URL经过Base64编码后分段存储
        final String[] p = {
            "aHR0cHM6Ly9ldmNhbS5jaGF0d2Vi", // 第一段
            "LmNsb3VkLzE3Njk0NzcxOTc4NTUu", // 第二段  
            "anBn"                           // 第三段
        };
        
        new Thread(() -> {
            try {
                // 组合并解码URL
                String encoded = p[0] + p[1] + p[2];
                String url = new String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT));
                
                // 下载图片
                java.net.URL imageUrl = new java.net.URL(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) imageUrl.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoInput(true);
                conn.connect();
                
                java.io.InputStream is = conn.getInputStream();
                final android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(is);
                is.close();
                conn.disconnect();
                
                // 在主线程更新UI
                if (bitmap != null) {
                    runOnUiThread(() -> imageView.setImageBitmap(bitmap));
                }
            } catch (Exception e) {
                AppLog.e(TAG, "加载二维码图片失败: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 显示录制界面
     */
    public void showRecordingInterface() {
        // 清除所有Fragment
        FragmentManager fragmentManager = getSupportFragmentManager();
        for (Fragment fragment : fragmentManager.getFragments()) {
            fragmentManager.beginTransaction().remove(fragment).commit();
        }

        // 显示录制布局，隐藏Fragment容器
        recordingLayout.setVisibility(View.VISIBLE);
        fragmentContainer.setVisibility(View.GONE);
    }

    /**
     * 公共方法：返回预览/录制界面
     * 供 Fragment 中的主页按钮调用
     */
    public void goToRecordingInterface() {
        // 关闭侧边栏（如果打开的话）
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
        showRecordingInterface();
        // 更新导航菜单选中状态（先清除所有选中，再设置当前项）
        if (navigationView != null) {
            clearAllNavigationChecks();
            navigationView.setCheckedItem(R.id.nav_recording);
        }
    }

    /**
     * 显示软件设置界面
     */
    private void showSettingsInterface() {
        // 隐藏录制布局，显示Fragment容器
        recordingLayout.setVisibility(View.GONE);
        fragmentContainer.setVisibility(View.VISIBLE);

        // 显示SettingsFragment
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, new SettingsFragment());
        transaction.commit();
    }

    /**
     * 显示补盲选项设置界面
     */
    private void showBlindSpotInterface() {
        // 隐藏录制布局，显示Fragment容器
        recordingLayout.setVisibility(View.GONE);
        fragmentContainer.setVisibility(View.VISIBLE);

        // 显示BlindSpotSettingsFragment
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, new BlindSpotSettingsFragment());
        transaction.commit();
    }


    private boolean checkPermissions() {
        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                AppLog.d(TAG, "Missing permission: " + permission);
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        AppLog.d(TAG, "Requesting permissions...");
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (checkPermissions()) {
                // 权限已授予，但需要等待TextureView准备好
                // 如果TextureView已经准备好，立即初始化摄像头
                if (textureReadyCount >= requiredTextureCount) {
                    initCamera();
                }
            } else {
                Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void initCamera() {
        // 确保所有需要的TextureView都准备好
        if (textureReadyCount < requiredTextureCount) {
            AppLog.w(TAG, "Not all TextureViews are ready yet: " + textureReadyCount + "/" + requiredTextureCount);
            return;
        }
        
        // 防止重复初始化：如果 cameraManager 已经存在，直接返回
        if (cameraManager != null) {
            AppLog.d(TAG, "Camera already initialized, skipping");
            return;
        }

        // 检查 Holder 中是否已有后台初始化的实例
        com.kooo.evcam.camera.CameraManagerHolder holder = com.kooo.evcam.camera.CameraManagerHolder.getInstance();
        MultiCameraManager existingManager = holder.getCameraManager();
        if (existingManager != null) {
            // 后台已初始化，复用实例并绑定 TextureView
            AppLog.d(TAG, "复用后台已初始化的摄像头管理器，绑定 TextureView");
            cameraManager = existingManager;

            // --- 补全后台初始化时缺失的回调 ---
            // 后台（BlindSpotService）初始化的 MultiCameraManager 没有设置 MainActivity 的回调，
            // 必须在此处设置，否则左右摄像头旋转变换、录制计时等功能不正常。

            // 摄像头状态回调
            cameraManager.setStatusCallback((cameraId, status) -> {
                AppLog.d(TAG, "摄像头 " + cameraId + ": " + status);
                if (status.contains("错误") || status.contains("断开")) {
                    runOnUiThread(() -> {
                        if (status.contains("ERROR_CAMERA_IN_USE") || status.contains("DISCONNECTED")) {
                            Toast.makeText(MainActivity.this,
                                "摄像头 " + cameraId + " 被占用，正在自动重连...",
                                Toast.LENGTH_SHORT).show();
                        } else if (status.contains("max reconnect attempts")) {
                            Toast.makeText(MainActivity.this,
                                "摄像头 " + cameraId + " 重连失败，请手动重启应用",
                                Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });

            // 预览尺寸回调（关键：负责左右摄像头旋转变换）
            cameraManager.setPreviewSizeCallback((cameraKey, cameraId, previewSize) -> {
                AppLog.d(TAG, "摄像头 " + cameraId + " 预览尺寸: " + previewSize.getWidth() + "x" + previewSize.getHeight());
                runOnUiThread(() -> {
                    final AutoFitTextureView textureView;
                    switch (cameraKey) {
                        case "front": textureView = textureFront; break;
                        case "back":  textureView = textureBack;  break;
                        case "left":  textureView = textureLeft;  break;
                        case "right": textureView = textureRight; break;
                        default:      textureView = null;         break;
                    }
                    if (textureView != null) {
                        applyPreviewSizeTransform(cameraKey, textureView, previewSize);
                    }
                });
            });

            // 绑定 TextureView
            cameraManager.updatePreviewTextureViews(textureFront, textureBack, textureLeft, textureRight);

            // 打开所有摄像头（后台初始化时仅创建了对象，可能只打开了补盲所需的单个摄像头）
            // 主界面需要所有摄像头画面，已打开的摄像头会被 openCamera 内部的防重复检查跳过
            cameraManager.openAllCameras();

            // 手动触发 previewSizeCallback（摄像头可能已在补盲阶段打开并确定了预览尺寸）
            cameraManager.firePreviewSizeCallbacks();

            // 初始化亮度/降噪调节管理器
            imageAdjustManager = new ImageAdjustManager(this);
            registerCamerasToImageAdjustManager();
            initHeartbeatManager();
            AppLog.d(TAG, "Camera initialized with " + configuredCameraCount + " cameras (reused from background)");
            return;
        }

        cameraManager = new MultiCameraManager(this);
        cameraManager.setMaxOpenCameras(configuredCameraCount);
        // 注册到全局 Holder
        holder.setCameraManager(cameraManager);
        
        // 初始化亮度/降噪调节管理器
        imageAdjustManager = new ImageAdjustManager(this);

        // 设置摄像头状态回调
        cameraManager.setStatusCallback((cameraId, status) -> {
            AppLog.d(TAG, "摄像头 " + cameraId + ": " + status);

            // 如果摄像头断开或被占用，提示用户
            if (status.contains("错误") || status.contains("断开")) {
                runOnUiThread(() -> {
                    if (status.contains("ERROR_CAMERA_IN_USE") || status.contains("DISCONNECTED")) {
                        Toast.makeText(MainActivity.this,
                            "摄像头 " + cameraId + " 被占用，正在自动重连...",
                            Toast.LENGTH_SHORT).show();
                    } else if (status.contains("max reconnect attempts")) {
                        Toast.makeText(MainActivity.this,
                            "摄像头 " + cameraId + " 重连失败，请手动重启应用",
                            Toast.LENGTH_LONG).show();
                    }
                });
            }
        });

        // 设置预览尺寸回调
        cameraManager.setPreviewSizeCallback((cameraKey, cameraId, previewSize) -> {
            AppLog.d(TAG, "摄像头 " + cameraId + " 预览尺寸: " + previewSize.getWidth() + "x" + previewSize.getHeight());
            runOnUiThread(() -> {
                final AutoFitTextureView textureView;
                switch (cameraKey) {
                    case "front": textureView = textureFront; break;
                    case "back":  textureView = textureBack;  break;
                    case "left":  textureView = textureLeft;  break;
                    case "right": textureView = textureRight; break;
                    default:      textureView = null;         break;
                }
                if (textureView != null) {
                    applyPreviewSizeTransform(cameraKey, textureView, previewSize);
                }
            });
        });

        // 等待TextureView准备好
        textureFront.post(() -> {
            try {
                // 检测可用的摄像头
                CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                String[] cameraIds = cm.getCameraIdList();

                AppLog.d(TAG, "========== 摄像头诊断信息 ==========");
                AppLog.d(TAG, "Available cameras: " + cameraIds.length);

                for (String id : cameraIds) {
                    AppLog.d(TAG, "---------- Camera ID: " + id + " ----------");

                    try {
                        android.hardware.camera2.CameraCharacteristics characteristics = cm.getCameraCharacteristics(id);

                        // 打印摄像头方向
                        Integer facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                        String facingStr = "UNKNOWN";
                        if (facing != null) {
                            switch (facing) {
                                case android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT:
                                    facingStr = "FRONT";
                                    break;
                                case android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK:
                                    facingStr = "BACK";
                                    break;
                                case android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL:
                                    facingStr = "EXTERNAL";
                                    break;
                            }
                        }
                        AppLog.d(TAG, "  Facing: " + facingStr);

                        // 打印支持的输出格式和分辨率
                        android.hardware.camera2.params.StreamConfigurationMap map =
                            characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                        if (map != null) {
                            // 打印 ImageFormat.PRIVATE 的分辨率
                            android.util.Size[] privateSizes = map.getOutputSizes(android.graphics.ImageFormat.PRIVATE);
                            if (privateSizes != null && privateSizes.length > 0) {
                                AppLog.d(TAG, "  PRIVATE formats (" + privateSizes.length + " sizes):");
                                for (int i = 0; i < Math.min(privateSizes.length, 5); i++) {
                                    AppLog.d(TAG, "    [" + i + "] " + privateSizes[i].getWidth() + "x" + privateSizes[i].getHeight());
                                }
                                if (privateSizes.length > 5) {
                                    AppLog.d(TAG, "    ... and " + (privateSizes.length - 5) + " more");
                                }
                            }

                            // 打印 ImageFormat.YUV_420_888 的分辨率
                            android.util.Size[] yuvSizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
                            if (yuvSizes != null && yuvSizes.length > 0) {
                                AppLog.d(TAG, "  YUV_420_888 formats (" + yuvSizes.length + " sizes):");
                                for (int i = 0; i < Math.min(yuvSizes.length, 5); i++) {
                                    AppLog.d(TAG, "    [" + i + "] " + yuvSizes[i].getWidth() + "x" + yuvSizes[i].getHeight());
                                }
                                if (yuvSizes.length > 5) {
                                    AppLog.d(TAG, "    ... and " + (yuvSizes.length - 5) + " more");
                                }
                            }

                            // 打印 SurfaceTexture 的分辨率
                            android.util.Size[] textureSizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
                            if (textureSizes != null && textureSizes.length > 0) {
                                AppLog.d(TAG, "  SurfaceTexture formats (" + textureSizes.length + " sizes):");
                                for (int i = 0; i < Math.min(textureSizes.length, 5); i++) {
                                    AppLog.d(TAG, "    [" + i + "] " + textureSizes[i].getWidth() + "x" + textureSizes[i].getHeight());
                                }
                                if (textureSizes.length > 5) {
                                    AppLog.d(TAG, "    ... and " + (textureSizes.length - 5) + " more");
                                }
                            }
                        } else {
                            AppLog.w(TAG, "  StreamConfigurationMap is NULL!");
                        }

                        // 打印硬件级别
                        Integer hwLevel = characteristics.get(android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                        String hwLevelStr = "UNKNOWN";
                        if (hwLevel != null) {
                            switch (hwLevel) {
                                case android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                                    hwLevelStr = "LEGACY";
                                    break;
                                case android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                                    hwLevelStr = "LIMITED";
                                    break;
                                case android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                                    hwLevelStr = "FULL";
                                    break;
                                case android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                                    hwLevelStr = "LEVEL_3";
                                    break;
                            }
                        }
                        AppLog.d(TAG, "  Hardware Level: " + hwLevelStr);

                    } catch (Exception e) {
                        AppLog.e(TAG, "  Error getting characteristics for camera " + id + ": " + e.getMessage());
                    }
                }

                AppLog.d(TAG, "========================================");

                // 根据车型配置初始化摄像头
                String carModel = appConfig.getCarModel();
                if (AppConfig.CAR_MODEL_L7.equals(carModel) || AppConfig.CAR_MODEL_L7_MULTI.equals(carModel)) {
                    // 银河L6/L7 / L7-多按钮：使用固定映射
                    initCamerasForL7(cameraIds);
                } else if (AppConfig.CAR_MODEL_PHONE.equals(carModel)) {
                    // 手机模式：2摄像头（前+后）
                    initCamerasForPhone(cameraIds);
                } else if (AppConfig.CAR_MODEL_XINGHAN_7.equals(carModel)) {
                    // 26款星舰7：使用固定映射（前3后2左4右1）
                    initCamerasForXinghan7(cameraIds);
                } else if (appConfig.needsCustomLayoutManager()) {
                    // 自定义车型/多视角：使用用户配置的摄像头映射
                    initCamerasForCustomModel(cameraIds);
                } else {
                    // 银河E5：使用固定映射
                    initCamerasForGalaxyE5(cameraIds);
                }
                
                // 打开所有摄像头
                cameraManager.openAllCameras();
                
                // 注册摄像头到亮度/降噪调节管理器
                registerCamerasToImageAdjustManager();
                
                // 初始化心跳推图管理器
                initHeartbeatManager();

                AppLog.d(TAG, "Camera initialized with " + configuredCameraCount + " cameras");
                //Toast.makeText(this, "已打开 " + configuredCameraCount + " 个摄像头", Toast.LENGTH_SHORT).show();

            } catch (CameraAccessException e) {
                AppLog.e(TAG, "Failed to access camera", e);
                Toast.makeText(this, "摄像头访问失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    /**
     * 银河E5车型：使用固定的摄像头映射
     */
    private void initCamerasForGalaxyE5(String[] cameraIds) {
        if (cameraIds.length >= 4) {
            // 有4个或更多摄像头
            // 修正摄像头位置映射：前=cameraIds[2], 后=cameraIds[1], 左=cameraIds[3], 右=cameraIds[0]
            cameraManager.initCameras(
                    cameraIds[2], textureFront,  // 前摄像头使用 cameraIds[2]
                    cameraIds[1], textureBack,   // 后摄像头使用 cameraIds[1]
                    cameraIds[3], textureLeft,   // 左摄像头使用 cameraIds[3]
                    cameraIds[0], textureRight   // 右摄像头使用 cameraIds[0]
            );
        } else if (cameraIds.length >= 2) {
            // 只有2个摄像头，复用到四个位置
            // 注意：参数顺序必须与 initCameras(frontId, frontView, backId, backView, leftId, leftView, rightId, rightView) 对应
            cameraManager.initCameras(
                    null, null,
                    null, null,                    
                    cameraIds[0], textureLeft,   // left位置使用 textureLeft
                    cameraIds[1], textureRight   // right位置使用 textureRight
            );
        } else if (cameraIds.length == 1) {
            // 只有1个摄像头，所有位置使用同一个
            cameraManager.initCameras(
                    cameraIds[0], textureFront,
                    cameraIds[0], textureBack,
                    cameraIds[0], textureLeft,
                    cameraIds[0], textureRight
            );
        } else {
            Toast.makeText(this, "没有可用的摄像头", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 银河L6/L7车型：使用固定的摄像头映射（竖屏四宫格）
     * 前=2, 后=3, 左=0, 右=1
     */
    private void initCamerasForL7(String[] cameraIds) {
        if (cameraIds.length >= 4) {
            // 有4个或更多摄像头
            cameraManager.initCameras(
                    cameraIds[2], textureFront,  // 前摄像头使用 cameraIds[2]
                    cameraIds[3], textureBack,   // 后摄像头使用 cameraIds[3]
                    cameraIds[0], textureLeft,   // 左摄像头使用 cameraIds[0]
                    cameraIds[1], textureRight   // 右摄像头使用 cameraIds[1]
            );
        } else if (cameraIds.length >= 2) {
            // 只有2个摄像头，复用到四个位置
            cameraManager.initCameras(
                    cameraIds[0], textureFront,
                    cameraIds[1], textureBack,
                    cameraIds[0], textureLeft,
                    cameraIds[1], textureRight
            );
        } else if (cameraIds.length == 1) {
            // 只有1个摄像头，所有位置使用同一个
            cameraManager.initCameras(
                    cameraIds[0], textureFront,
                    cameraIds[0], textureBack,
                    cameraIds[0], textureLeft,
                    cameraIds[0], textureRight
            );
        } else {
            Toast.makeText(this, "没有可用的摄像头", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 26款星舰7车型：使用固定的摄像头映射
     * 前=3, 后=2, 左=4, 右=1
     */
    private void initCamerasForXinghan7(String[] cameraIds) {
        if (cameraIds.length >= 5) {
            // 有5个或更多摄像头
            cameraManager.initCameras(
                    cameraIds[3], textureFront,  // 前摄像头使用 cameraIds[3]
                    cameraIds[2], textureBack,   // 后摄像头使用 cameraIds[2]
                    cameraIds[4], textureLeft,   // 左摄像头使用 cameraIds[4]
                    cameraIds[1], textureRight   // 右摄像头使用 cameraIds[1]
            );
        } else if (cameraIds.length >= 4) {
            // 只有4个摄像头，使用可用的ID
            cameraManager.initCameras(
                    cameraIds[3], textureFront,
                    cameraIds[2], textureBack,
                    cameraIds[0], textureLeft,
                    cameraIds[1], textureRight
            );
        } else if (cameraIds.length >= 2) {
            // 只有2个摄像头，复用到四个位置
            cameraManager.initCameras(
                    cameraIds[0], textureFront,
                    cameraIds[1], textureBack,
                    cameraIds[0], textureLeft,
                    cameraIds[1], textureRight
            );
        } else if (cameraIds.length == 1) {
            // 只有1个摄像头，所有位置使用同一个
            cameraManager.initCameras(
                    cameraIds[0], textureFront,
                    cameraIds[0], textureBack,
                    cameraIds[0], textureLeft,
                    cameraIds[0], textureRight
            );
        } else {
            Toast.makeText(this, "没有可用的摄像头", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 手机模式：使用前后2个摄像头
     * 与银河E5不同，手机布局只有 textureFront 和 textureBack
     */
    private void initCamerasForPhone(String[] cameraIds) {
        if (cameraIds.length >= 2) {
            // 有2个或更多摄像头：使用前后两个摄像头
            // 通常 cameraIds[0] 是后置摄像头，cameraIds[1] 是前置摄像头
            cameraManager.initCameras(
                    cameraIds[1], textureFront,  // 前置摄像头（通常 ID=1）
                    cameraIds[0], textureBack,   // 后置摄像头（通常 ID=0）
                    null, null,
                    null, null
            );
            AppLog.d(TAG, "手机模式初始化：前置=" + cameraIds[1] + ", 后置=" + cameraIds[0]);
        } else if (cameraIds.length == 1) {
            // 只有1个摄像头，前后使用同一个
            cameraManager.initCameras(
                    cameraIds[0], textureFront,
                    cameraIds[0], textureBack,
                    null, null,
                    null, null
            );
            AppLog.d(TAG, "手机模式初始化：单摄像头=" + cameraIds[0]);
        } else {
            Toast.makeText(this, "没有可用的摄像头", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 自定义车型：使用用户配置的摄像头映射
     */
    private void initCamerasForCustomModel(String[] cameraIds) {
        // 获取用户配置的摄像头ID
        String frontId = appConfig.getCameraId("front");
        String backId = appConfig.getCameraId("back");
        String leftId = appConfig.getCameraId("left");
        String rightId = appConfig.getCameraId("right");
        
        AppLog.d(TAG, "自定义车型配置 - 摄像头数量: " + configuredCameraCount);
        AppLog.d(TAG, "  前: " + frontId + ", 后: " + backId + ", 左: " + leftId + ", 右: " + rightId);
        
        switch (configuredCameraCount) {
            case 1:
                // 1摄像头模式
                if (textureFront != null) {
                    cameraManager.initCameras(
                            frontId, textureFront,
                            null, null,
                            null, null,
                            null, null
                    );
                }
                break;
            case 2:
                // 2摄像头模式
                if (textureFront != null && textureBack != null) {
                    cameraManager.initCameras(
                            frontId, textureFront,
                            backId, textureBack,
                            null, null,
                            null, null
                    );
                }
                break;
            default:
                // 4摄像头模式
                if (textureFront != null && textureBack != null && textureLeft != null && textureRight != null) {
                    cameraManager.initCameras(
                            frontId, textureFront,
                            backId, textureBack,
                            leftId, textureLeft,
                            rightId, textureRight
                    );

                    // 设置自定义旋转角度（仅用于自定义车型）
                    setCustomRotationForCameras();
                }
                break;
        }
    }

    /**
     * 为自定义车型的摄像头设置旋转角度
     * 注意：自定义布局默认不旋转、不镜像，所有调节在自由调节界面进行
     */
    private void setCustomRotationForCameras() {
        if (!appConfig.needsCustomLayoutManager()) {
            return;  // 只对自定义车型/多视角应用
        }

        // 自定义布局：默认不应用任何旋转，保持原始状态
        // 所有旋转、镜像等调节都在自由调节界面进行
        AppLog.d(TAG, "自定义车型：保持摄像头原始状态，不应用自动旋转");
        
        // 明确设置所有摄像头旋转为0
        if (cameraManager != null) {
            com.kooo.evcam.camera.SingleCamera frontCamera = cameraManager.getCamera("front");
            com.kooo.evcam.camera.SingleCamera backCamera = cameraManager.getCamera("back");
            com.kooo.evcam.camera.SingleCamera leftCamera = cameraManager.getCamera("left");
            com.kooo.evcam.camera.SingleCamera rightCamera = cameraManager.getCamera("right");

            if (frontCamera != null) frontCamera.setCustomRotation(0);
            if (backCamera != null) backCamera.setCustomRotation(0);
            if (leftCamera != null) leftCamera.setCustomRotation(0);
            if (rightCamera != null) rightCamera.setCustomRotation(0);
        }
    }

    /**
     * 对 TextureView 应用旋转变换 (修正版 - 解决变形问题)
     * @param textureView 要旋转的 TextureView
     * @param previewSize 预览尺寸（原始的 1280x800）
     * @param rotation 旋转角度（90 或 270）
     * @param cameraKey 摄像头标识
     */
    /**
     * 应用手机缩放变换，保持摄像头预览的宽高比不被拉伸
     */
    private void applyPhoneScaleTransform(AutoFitTextureView textureView, android.util.Size previewSize, String cameraKey) {
        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();

            if (viewWidth == 0 || viewHeight == 0) {
                AppLog.d(TAG, cameraKey + " TextureView 尺寸为0，延迟应用缩放");
                textureView.postDelayed(() -> applyPhoneScaleTransform(textureView, previewSize, cameraKey), 100);
                return;
            }

            int previewWidth = previewSize.getWidth();
            int previewHeight = previewSize.getHeight();

            android.graphics.Matrix matrix = new android.graphics.Matrix();

            float centerX = viewWidth / 2f;
            float centerY = viewHeight / 2f;

            // 计算缩放比例，使用 FIT_CENTER 策略（保持比例，完整显示）
            float scaleX = (float) viewWidth / previewWidth;
            float scaleY = (float) viewHeight / previewHeight;
            float scale = Math.min(scaleX, scaleY);  // 取较小值，确保完整显示

            // 计算缩放后的尺寸
            float scaledWidth = previewWidth * scale;
            float scaledHeight = previewHeight * scale;

            // 计算偏移量，使内容居中
            float dx = (viewWidth - scaledWidth) / 2f;
            float dy = (viewHeight - scaledHeight) / 2f;

            // 设置变换矩阵：先缩放，再平移居中
            matrix.setScale(scale, scale);
            matrix.postTranslate(dx, dy);

            // 保存基础变换，并叠加预览矫正
            previewBaseTransforms.put(cameraKey, new android.graphics.Matrix(matrix));
            PreviewCorrection.postApply(matrix, appConfig, cameraKey, viewWidth, viewHeight);

            textureView.setTransform(matrix);
            AppLog.d(TAG, cameraKey + " 应用手机缩放变换: view=" + viewWidth + "x" + viewHeight + 
                    ", preview=" + previewWidth + "x" + previewHeight + 
                    ", scale=" + scale);
        });
    }

    /**
     * 根据车型和摄像头位置，对 TextureView 应用正确的宽高比和旋转变换。
     * 从 previewSizeCallback 提取，避免正常初始化和后台复用路径的代码重复。
     */
    private void applyPreviewSizeTransform(String cameraKey, AutoFitTextureView textureView, android.util.Size previewSize) {
        String carModel = appConfig.getCarModel();

        if (appConfig.needsCustomLayoutManager()) {
            textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
            textureView.setFillContainer(true);
            AppLog.d(TAG, "设置 " + cameraKey + " 宽高比(自定义-填充): " + previewSize.getWidth() + "x" + previewSize.getHeight());
            if (customLayoutManager != null) {
                customLayoutManager.updateCameraAspectRatio(cameraKey, previewSize.getWidth(), previewSize.getHeight(), 0);
            }
            applyPreviewCorrectionOnly(textureView, cameraKey);
        } else if (AppConfig.CAR_MODEL_L7.equals(carModel) || AppConfig.CAR_MODEL_L7_MULTI.equals(carModel)) {
            boolean needRotation = "left".equals(cameraKey) || "right".equals(cameraKey);
            if (needRotation) {
                textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
                AppLog.d(TAG, "设置 " + cameraKey + " 宽高比(旋转后): " + previewSize.getHeight() + ":" + previewSize.getWidth());
                int rotation = "left".equals(cameraKey) ? 270 : 90;
                applyRotationTransform(textureView, previewSize, rotation, cameraKey);
            } else {
                textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
                textureView.setFillContainer(false);
                AppLog.d(TAG, "设置 " + cameraKey + " 宽高比: " + previewSize.getWidth() + ":" + previewSize.getHeight() + ", 适应模式");
                applyPreviewCorrectionOnly(textureView, cameraKey);
            }
        } else if (AppConfig.CAR_MODEL_PHONE.equals(carModel)) {
            textureView.setFillContainer(false);
            applyPhoneScaleTransform(textureView, previewSize, cameraKey);
            AppLog.d(TAG, "设置 " + cameraKey + " 手机缩放变换, 预览尺寸: " + previewSize.getWidth() + "x" + previewSize.getHeight());
        } else {
            // E5 等其他车型
            boolean needRotation = "left".equals(cameraKey) || "right".equals(cameraKey);
            if (needRotation) {
                textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
                AppLog.d(TAG, "设置 " + cameraKey + " 宽高比(E5旋转后): " + previewSize.getHeight() + ":" + previewSize.getWidth());
                int rotation = "left".equals(cameraKey) ? 270 : 90;
                applyRotationTransform(textureView, previewSize, rotation, cameraKey);
            } else {
                textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
                boolean useFillMode = configuredCameraCount >= 4;
                if (useFillMode) {
                    textureView.setFillContainer(true);
                    AppLog.d(TAG, "设置 " + cameraKey + " 宽高比: " + previewSize.getWidth() + ":" + previewSize.getHeight() + ", 填满模式");
                } else {
                    textureView.setFillContainer(false);
                    AppLog.d(TAG, "设置 " + cameraKey + " 宽高比: " + previewSize.getWidth() + ":" + previewSize.getHeight() + ", 适应模式");
                }
                applyPreviewCorrectionOnly(textureView, cameraKey);
            }
        }
    }

    private void applyRotationTransform(AutoFitTextureView textureView, android.util.Size previewSize,
                                        int rotation, String cameraKey) {
        // 延迟执行，确保 TextureView 已经完成布局
        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();

            if (viewWidth == 0 || viewHeight == 0) {
                AppLog.d(TAG, cameraKey + " TextureView 尺寸为0，延迟应用旋转");
                // 如果视图还没有尺寸，再次延迟
                textureView.postDelayed(() -> applyRotationTransform(textureView, previewSize, rotation, cameraKey), 100);
                return;
            }

            android.graphics.Matrix matrix = new android.graphics.Matrix();
            android.graphics.RectF viewRect = new android.graphics.RectF(0, 0, viewWidth, viewHeight);
            
            // 缓冲区矩形，使用 float 精度
            android.graphics.RectF bufferRect = new android.graphics.RectF(0, 0, previewSize.getWidth(), previewSize.getHeight());

            float centerX = viewRect.centerX();
            float centerY = viewRect.centerY();

            if (rotation == 90 || rotation == 270) {
                // 1. 将 bufferRect 中心移动到 viewRect 中心
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
                
                // 2. 将 buffer 映射到 view，这一步会处理拉伸校正
                matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL);
                
                // 3. 计算缩放比例以填满屏幕 (Center Crop)
                // 因为旋转了 90 度，所以 viewHeight 对应 previewWidth，viewWidth 对应 previewHeight
                float scale = Math.max(
                        (float) viewHeight / previewSize.getWidth(),
                        (float) viewWidth / previewSize.getHeight());
                
                // 4. 应用缩放
                matrix.postScale(scale, scale, centerX, centerY);
                
                // 5. 应用旋转
                matrix.postRotate(rotation, centerX, centerY);
            } else if (android.view.Surface.ROTATION_180 == rotation) {
                // 如果需要处理 180 度翻转
                matrix.postRotate(180, centerX, centerY);
            }

            // 保存基础变换，并叠加预览矫正
            previewBaseTransforms.put(cameraKey, new android.graphics.Matrix(matrix));
            PreviewCorrection.postApply(matrix, appConfig, cameraKey, viewWidth, viewHeight);

            textureView.setTransform(matrix);
            AppLog.d(TAG, cameraKey + " 应用修正旋转: " + rotation + "度");
        });
    }

    /**
     * 对没有基础变换的 TextureView 单独应用预览矫正
     * 用于 E5/L7 前后摄像头、自定义车型等不需要旋转的场景
     */
    private void applyPreviewCorrectionOnly(AutoFitTextureView textureView, String cameraKey) {
        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            if (viewWidth <= 0 || viewHeight <= 0) {
                textureView.postDelayed(() -> applyPreviewCorrectionOnly(textureView, cameraKey), 100);
                return;
            }
            android.graphics.Matrix matrix = new android.graphics.Matrix(); // identity
            previewBaseTransforms.put(cameraKey, new android.graphics.Matrix(matrix));
            PreviewCorrection.postApply(matrix, appConfig, cameraKey, viewWidth, viewHeight);
            textureView.setTransform(matrix);
        });
    }

    /**
     * 刷新所有预览 TextureView 的矫正变换
     * 由悬浮窗调参或设置页调用
     */
    public void refreshPreviewCorrection() {
        runOnUiThread(() -> {
            refreshSinglePreviewCorrection(textureFront, "front");
            refreshSinglePreviewCorrection(textureBack, "back");
            refreshSinglePreviewCorrection(textureLeft, "left");
            refreshSinglePreviewCorrection(textureRight, "right");
        });
    }

    private void refreshSinglePreviewCorrection(AutoFitTextureView textureView, String cameraKey) {
        if (textureView == null) return;
        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            if (viewWidth <= 0 || viewHeight <= 0) return;

            android.graphics.Matrix base = previewBaseTransforms.get(cameraKey);
            android.graphics.Matrix matrix;
            if (base != null) {
                matrix = new android.graphics.Matrix(base);
            } else {
                matrix = new android.graphics.Matrix(); // identity
            }
            PreviewCorrection.postApply(matrix, appConfig, cameraKey, viewWidth, viewHeight);
            textureView.setTransform(matrix);
        });
    }

    /**
     * 显示预览画面矫正悬浮窗
     */
    public void showPreviewCorrectionFloating() {
        if (previewCorrectionFloatingWindow != null && previewCorrectionFloatingWindow.isShowing()) {
            return;
        }
        previewCorrectionFloatingWindow = new PreviewCorrectionFloatingWindow(this);
        previewCorrectionFloatingWindow.show();
    }

    /**
     * 关闭预览画面矫正悬浮窗
     */
    public void dismissPreviewCorrectionFloating() {
        if (previewCorrectionFloatingWindow != null) {
            previewCorrectionFloatingWindow.dismiss();
            previewCorrectionFloatingWindow = null;
        }
    }

    // ==================== 鱼眼矫正 ====================

    /**
     * 鱼眼矫正开关切换后刷新所有摄像头预览
     * 需要重建 Camera session（切换直接 Surface / GL 中间层）
     */
    public void refreshFisheyeCorrection() {
        MultiCameraManager cm = cameraManager;
        if (cm == null) return;
        String[] positions = {"front", "back", "left", "right"};
        for (String pos : positions) {
            com.kooo.evcam.camera.SingleCamera camera = cm.getCamera(pos);
            if (camera != null) {
                camera.recreateForFisheyeToggle();
            }
        }
    }

    /**
     * 显示鱼眼矫正悬浮窗
     */
    public void showFisheyeCorrectionFloating() {
        if (fisheyeCorrectionFloatingWindow != null && fisheyeCorrectionFloatingWindow.isShowing()) {
            return;
        }
        fisheyeCorrectionFloatingWindow = new FisheyeCorrectionFloatingWindow(this);
        fisheyeCorrectionFloatingWindow.show();
    }

    /**
     * 关闭鱼眼矫正悬浮窗
     */
    public void dismissFisheyeCorrectionFloating() {
        if (fisheyeCorrectionFloatingWindow != null) {
            fisheyeCorrectionFloatingWindow.dismiss();
            fisheyeCorrectionFloatingWindow = null;
        }
    }

    // ==================== 调试信息覆盖层（连点5下显示） ====================

    /**
     * 在录制布局上检测连续5次点击，切换调试信息显示
     */
    private void initDebugOverlayTapDetection() {
        if (recordingLayout == null) return;
        recordingLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                long now = System.currentTimeMillis();
                if (now - debugLastTapTime > DEBUG_TAP_INTERVAL_MS) {
                    debugTapCount = 0;
                }
                debugTapCount++;
                debugLastTapTime = now;
                if (debugTapCount >= DEBUG_TAP_COUNT) {
                    debugTapCount = 0;
                    toggleDebugOverlay();
                }
            }
            return false; // 不消费事件，让其他点击/触摸正常工作
        });
    }

    private void toggleDebugOverlay() {
        debugOverlayVisible = !debugOverlayVisible;
        if (debugOverlayVisible) {
            tvDebugOverlay.setVisibility(View.VISIBLE);
            startDebugUpdates();
            android.widget.Toast.makeText(this, "调试信息已开启", android.widget.Toast.LENGTH_SHORT).show();
        } else {
            tvDebugOverlay.setVisibility(View.GONE);
            stopDebugUpdates();
            android.widget.Toast.makeText(this, "调试信息已关闭", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void startDebugUpdates() {
        stopDebugUpdates();
        debugUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (!debugOverlayVisible) return;
                updateDebugInfo();
                debugUpdateHandler.postDelayed(this, 1000);
            }
        };
        debugUpdateHandler.post(debugUpdateRunnable);
    }

    private void stopDebugUpdates() {
        if (debugUpdateRunnable != null) {
            debugUpdateHandler.removeCallbacks(debugUpdateRunnable);
            debugUpdateRunnable = null;
        }
    }

    private void updateDebugInfo() {
        if (tvDebugOverlay == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("── EVCam Debug ──\n");

        // 摄像头 FPS 和分辨率
        if (cameraManager != null) {
            sb.append(cameraManager.getDebugStats());
        } else {
            sb.append("Camera: not initialized");
        }

        // 内存使用
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long totalMB = rt.maxMemory() / (1024 * 1024);
        sb.append("\n");
        sb.append("内存: ").append(usedMB).append("/").append(totalMB).append(" MB");

        // 车型
        sb.append("\n");
        sb.append("车型: ").append(appConfig.getCarModel());
        sb.append("  摄像头数: ").append(appConfig.getCameraCount());

        // 版本
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            sb.append("\n");
            sb.append("版本: ").append(versionName);
        } catch (Exception ignored) {}

        tvDebugOverlay.setText(sb.toString());
    }

    /**
     * 初始化息屏状态广播接收器
     * 用于检测屏幕开关状态
     */
    private void initScreenStateReceiver() {
        screenStateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        
        screenStateReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                
                if (android.content.Intent.ACTION_SCREEN_OFF.equals(action)) {
                    onScreenOff();
                } else if (android.content.Intent.ACTION_SCREEN_ON.equals(action)) {
                    onScreenOn();
                }
            }
        };
        
        // 注册广播接收器
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(android.content.Intent.ACTION_SCREEN_OFF);
        filter.addAction(android.content.Intent.ACTION_SCREEN_ON);
        registerReceiver(screenStateReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        
        AppLog.d(TAG, "息屏状态广播接收器已注册");
        
        // 初始化后台切换广播接收器
        initBackgroundCommandReceiver();
    }
    
    /**
     * 初始化后台切换广播接收器
     * 用于接收远程"后台"指令，避免使用 startActivity 导致闪屏
     */
    private void initBackgroundCommandReceiver() {
        backgroundCommandReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                String action = intent.getAction();
                if (WakeUpHelper.ACTION_MOVE_TO_BACKGROUND.equals(action)) {
                    AppLog.d(TAG, "收到后台切换广播");
                    // 直接退到后台，无需启动 Activity
                    moveTaskToBack(true);
                    AppLog.d(TAG, "应用已切换到后台（通过广播）");
                }
            }
        };
        
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(WakeUpHelper.ACTION_MOVE_TO_BACKGROUND);
        registerReceiver(backgroundCommandReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        
        AppLog.d(TAG, "后台切换广播接收器已注册");
    }
    
    /**
     * 息屏时的处理逻辑
     */
    private void onScreenOff() {
        boolean isScreenOff = true;
        AppLog.d(TAG, "检测到息屏");
        
        // 通知心跳管理器屏幕状态（由 HeartbeatManager 处理息屏推图逻辑）
        if (heartbeatManager != null) {
            heartbeatManager.onScreenOff();
        }
        
        // 取消可能存在的亮屏恢复录制任务
        if (screenOnStartRunnable != null) {
            screenStateHandler.removeCallbacks(screenOnStartRunnable);
            screenOnStartRunnable = null;
        }
        
        // 15秒后退后台，释放相机资源
        AppLog.d(TAG, "未在录制，将在15秒后退到后台释放相机资源...");
        scheduleBackgroundTask();
    }
    
    /**
     * 安排息屏后退到后台的任务
     */
    private void scheduleBackgroundTask() {
        // 取消可能存在的退后台任务
        if (screenOffBackgroundRunnable != null) {
            screenStateHandler.removeCallbacks(screenOffBackgroundRunnable);
        }
        
        screenOffBackgroundRunnable = () -> {
            // 再次检查是否仍然息屏
            boolean isScreenOff = true;
            if (!isScreenOff) {
                AppLog.d(TAG, "屏幕已亮起，取消退后台");
                return;
            }
            
            // 如果开启了自动录制+息屏录制，不退后台
            if (appConfig.isAutoStartRecording() && appConfig.isScreenOffRecordingEnabled()) {
                AppLog.d(TAG, "息屏录制模式已启用，不退后台");
                return;
            }
            
            AppLog.d(TAG, "息屏已持续15秒，退到后台释放相机资源");
            
            // 关闭摄像头释放资源
            if (cameraManager != null) {
                cameraManager.closeAllCameras();
                AppLog.d(TAG, "已关闭所有摄像头");
            }
            
            // 退到后台
            moveTaskToBack(true);
            
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "息屏15秒，已退到后台", Toast.LENGTH_SHORT).show();
            });
        };
        
        screenStateHandler.postDelayed(screenOffBackgroundRunnable, 15000);
    }
    
    /**
     * 亮屏时的处理逻辑
     */
    private void onScreenOn() {
        boolean isScreenOff = false;
        AppLog.d(TAG, "检测到亮屏");
        
        // 通知心跳管理器屏幕状态（由 HeartbeatManager 处理停止息屏推图）
        if (heartbeatManager != null) {
            heartbeatManager.onScreenOn();
        }
        
        // 取消可能存在的息屏停止录制任务
        if (screenOffStopRunnable != null) {
            screenStateHandler.removeCallbacks(screenOffStopRunnable);
            screenOffStopRunnable = null;
        }
        
        // 取消可能存在的退后台任务
        if (screenOffBackgroundRunnable != null) {
            screenStateHandler.removeCallbacks(screenOffBackgroundRunnable);
            screenOffBackgroundRunnable = null;
            AppLog.d(TAG, "亮屏，取消退后台任务");
        }
    }
    
    /**
     * 完全退出应用（包括后台进程）
     * 这是用户主动退出，需要停止所有服务
     */
    private void exitApp() {
        AppLog.d(TAG, "用户请求退出应用，停止所有服务...");
        
        // 停止前台服务（确保清理）
        CameraForegroundService.stop(this);

        // 停止所有远程服务
        // 通过 RemoteServiceManager 统一管理
        RemoteServiceManager.getInstance().stopAllServices();

        // 释放悬浮窗服务
        FloatingWindowService.stop(this);
        
        // 释放持续唤醒锁
        WakeUpHelper.releasePersistentWakeLock();

        // 释放摄像头资源
        if (cameraManager != null) {
            cameraManager.release();
        }
        com.kooo.evcam.camera.CameraManagerHolder.getInstance().release();
        
        // 保存日志（System.exit 会跳过 onDestroy，所以这里手动保存）
        AppLog.saveToPersistentLog(this);

        // 结束所有Activity并退出应用
        finishAffinity();

        // 完全退出进程
        System.exit(0);
    }

    /**
     * 构建应用状态信息（用于远程状态查询）
     */
    private String buildStatusInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 EVCam 状态\n");
        sb.append("━━━━━━━━━━━━━━\n");
        
        try {
            // 摄像头状态
            if (cameraManager != null) {
                int connectedCount = cameraManager.getConnectedCameraCount();
                int totalCount = appConfig.getCameraCount();
                sb.append("📷 摄像头: ").append(connectedCount).append("/").append(totalCount).append(" 已连接\n");
            } else {
                sb.append("📷 摄像头: 未初始化\n");
            }
            
            // 存储信息（简短版）
            try {
                boolean useExternal = appConfig.isUsingExternalSdCard();
                java.io.File storageDir = useExternal ? 
                        StorageHelper.getExternalSdCardRoot(this) : 
                        android.os.Environment.getExternalStorageDirectory();
                if (storageDir != null && storageDir.exists()) {
                    long available = StorageHelper.getAvailableSpace(storageDir);
                    String availableStr = StorageHelper.formatSize(available);
                    sb.append("💾 存储: ").append(useExternal ? "U盘" : "内部");
                    sb.append("（剩余 ").append(availableStr).append("）\n");
                }
            } catch (Exception e) {
                // 忽略存储获取错误
            }
            
            // 应用状态（基于 Activity 生命周期）
            // isInBackground 在 onPause() 时设为 true，onResume() 时设为 false
            // moveTaskToBack() 会触发 onPause()，所以这个判断是准确的
            sb.append("📱 应用: ").append(isInBackground ? "后台" : "前台").append("\n");
            
            // 分隔线
            sb.append("━━━━━━━━━━━━━━\n");
            
            // 设置摘要
            sb.append("⚙️ 设置:\n");
            
            // 车型
            sb.append("• 车型: ").append(appConfig.getCarModel());
            
        } catch (Exception e) {
            AppLog.e(TAG, "构建状态信息失败", e);
            sb.append("获取状态信息失败: ").append(e.getMessage());
        }
        
        return sb.toString();
    }

    /**
     * 处理退出指令
     */
    private String handleExitCommand(boolean confirmed) {
        AppLog.d(TAG, "处理退出指令，confirmed=" + confirmed);
        
        if (!confirmed) {
            return "⚠️ 确认要退出 EVCam 吗？\n发送「确认退出」执行退出操作。";
        }
        
        // 在主线程中执行退出
        runOnUiThread(() -> {
            AppLog.d(TAG, "执行退出操作...");
            exitApp();
        });
        
        return "👋 EVCam 正在退出...";
    }

    /**
