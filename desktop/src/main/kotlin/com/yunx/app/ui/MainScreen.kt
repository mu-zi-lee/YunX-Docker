package com.yunx.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunx.app.AppContext
import com.yunx.app.data.announcement.AnnouncementReadStore
import com.yunx.app.data.backup.AuthBackupManager
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.download.ChunkDownloader
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.C139Api
import com.yunx.app.data.network.GitHubApi
import com.yunx.app.data.network.GitHubTokenStore
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.Pan115Api
import com.yunx.app.data.network.Pan123Api
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.TokenCheck
import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.components.CloseChoice
import com.yunx.app.ui.components.CloseConfirmDialog
import com.yunx.app.util.TrayManager
import com.yunx.app.data.repository.BaiduAccountRepository
import com.yunx.app.data.repository.BaiduResolveRepository
import com.yunx.app.data.repository.C139AccountRepository
import com.yunx.app.data.repository.C139ResolveRepository
import com.yunx.app.data.repository.Pan115AccountRepository
import com.yunx.app.data.repository.Pan115ResolveRepository
import com.yunx.app.data.repository.Pan123AccountRepository
import com.yunx.app.data.repository.Pan123ResolveRepository
import com.yunx.app.data.repository.QuarkAccountRepository
import com.yunx.app.data.repository.QuarkResolveRepository
import com.yunx.app.data.repository.UCAccountRepository
import com.yunx.app.data.repository.UCResolveRepository
import com.yunx.app.data.repository.XunleiAccountRepository
import com.yunx.app.data.repository.XunleiResolveRepository
import com.yunx.app.ui.clipboard.ClipboardLinkController
import com.yunx.app.ui.clipboard.ClipboardLinkDetector
import com.yunx.app.ui.clipboard.ClipboardLinkPopup
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.ui.components.OverlayDialogHost
import com.yunx.app.ui.login.BaiduLoginScreen
import com.yunx.app.ui.login.C139LoginScreen
import com.yunx.app.ui.login.Pan115LoginScreen
import com.yunx.app.ui.login.Pan123LoginScreen
import com.yunx.app.ui.login.QuarkLoginScreen
import com.yunx.app.ui.login.UCLoginScreen
import com.yunx.app.ui.login.XunleiLoginScreen
import com.yunx.app.ui.login.XunleiVerifyWebViewScreen
import com.yunx.app.ui.navigation.MainTab
import com.yunx.app.ui.screens.AboutScreen
import com.yunx.app.ui.screens.AnnouncementPopupDialog
import com.yunx.app.ui.screens.AnnouncementScreen
import com.yunx.app.ui.screens.AnnouncementUnreadBadge
import com.yunx.app.ui.screens.BookmarkScreen
import com.yunx.app.ui.screens.DownloadScreen
import com.yunx.app.ui.screens.DriveScreen
import com.yunx.app.ui.screens.ExperimentalFeaturesScreen
import com.yunx.app.ui.screens.OnboardingScreen
import com.yunx.app.ui.screens.ResolveScreen
import com.yunx.app.ui.screens.SettingsScreen
import com.yunx.app.ui.screens.SupportScreen
import com.yunx.app.ui.screens.ThemeScreen
import com.yunx.app.ui.viewmodel.AnnouncementViewModel
import com.yunx.app.ui.viewmodel.BaiduAccountViewModel
import com.yunx.app.ui.viewmodel.BaiduCloudViewModel
import com.yunx.app.ui.viewmodel.BookmarkViewModel
import com.yunx.app.ui.viewmodel.C139AccountViewModel
import com.yunx.app.ui.viewmodel.C139CloudViewModel
import com.yunx.app.ui.viewmodel.DownloadViewModel
import com.yunx.app.ui.viewmodel.DriveQuotaViewModel
import com.yunx.app.ui.viewmodel.Pan115AccountViewModel
import com.yunx.app.ui.viewmodel.Pan115CloudViewModel
import com.yunx.app.ui.viewmodel.Pan123AccountViewModel
import com.yunx.app.ui.viewmodel.Pan123CloudViewModel
import com.yunx.app.ui.viewmodel.QuarkAccountViewModel
import com.yunx.app.ui.viewmodel.QuarkCloudViewModel
import com.yunx.app.ui.viewmodel.ResolveViewModel
import com.yunx.app.ui.viewmodel.UCAccountViewModel
import com.yunx.app.ui.viewmodel.UCCoudViewModel
import com.yunx.app.ui.viewmodel.XunleiAccountViewModel
import com.yunx.app.ui.viewmodel.XunleiCloudViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 桌面版主页框架：
 * - 左侧 NavigationRail（桌面窗口始终横向布局）+ 顶部可折叠大标题；
 * - 4 个主 Tab（解析 / 网盘 / 下载 / 设置），SaveableStateHolder 保存各页状态；
 * - 全屏覆盖层：首次引导 / 各平台登录 / 关于 / 支持 / 主题 / 收藏。
 * 相比 Android 版移除：通知权限、电池优化引导、存储权限、更新检测、横竖屏切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    /** 主窗口 X 按钮点击信号（由 Main.kt 置 true） */
    closeRequested: Boolean = false,
    /** 关闭逻辑处理完毕后回调（重置信号） */
    onCloseHandled: () -> Unit = {},
    /** 执行退出应用（淡出窗口 → exitApplication） */
    onExitApplication: () -> Unit = {},
    /** 最小化到系统托盘（由 Main.kt 实现，含关闭请求抑制） */
    onMinimizeToTray: () -> Unit = {}
) {
    var currentTab by rememberSaveable { mutableStateOf(MainTab.Resolve) }
    var showQuarkLogin by rememberSaveable { mutableStateOf(false) }
    var showUCLogin by rememberSaveable { mutableStateOf(false) }
    var showXunleiLogin by rememberSaveable { mutableStateOf(false) }
    var showXunleiVerify by rememberSaveable { mutableStateOf(false) }
    var xunleiVerifyUrl by rememberSaveable { mutableStateOf("") }
    var xunleiVerifyDeviceId by rememberSaveable { mutableStateOf("") }
    var showBaiduLogin by rememberSaveable { mutableStateOf(false) }
    var showC139Login by rememberSaveable { mutableStateOf(false) }
    var showPan123Login by rememberSaveable { mutableStateOf(false) }
    var showPan115Login by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showSupport by rememberSaveable { mutableStateOf(false) }
    var showTheme by rememberSaveable { mutableStateOf(false) }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    var showExperimental by rememberSaveable { mutableStateOf(false) }
    var showAnnouncements by rememberSaveable { mutableStateOf(false) }
    /** 启动公告弹窗点「查看详情」时带进去的公告 id（null = 从图标进来先看列表） */
    var announcementDetailId by rememberSaveable { mutableStateOf<String?>(null) }
    val saveableStateHolder = rememberSaveableStateHolder()

    val scope = rememberCoroutineScope()

    // 首次启动引导
    var showOnboarding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showOnboarding = !AppContext.miscPrefs.getBoolean("onboarding_shown", false)
    }

    // 依赖装配（与 Android 版相同的对象图，去掉 Android 专属注入）
    val api = remember { QuarkApi() }
    val ucApi = remember { UCApi() }
    val xunleiApi = remember { XunleiApi() }
    val baiduApi = remember { BaiduApi() }
    val c139Api = remember { C139Api() }
    val pan123Api = remember { Pan123Api() }
    val pan115Api = remember { Pan115Api() }
    val db = remember { AppDatabase.get() }
    val settings = remember { SettingsRepository() }
    // 主页快捷方式开关（实验性功能，默认关闭）：由实验性功能页回调同步，控制解析页是否显示收藏快捷方式
    var homeShortcutsEnabled by remember { mutableStateOf(settings.homeShortcutEnabled) }
    // 网盘页 GitHub 卡片登录态：保存/清除 Token 后即时刷新卡片文案
    var githubHasTokenState by remember { mutableStateOf(GitHubTokenStore.hasToken()) }
    // GitHub API 封装：Token 从 GitHubTokenStore 动态读取（AES-GCM 加密），仅用于提升 API 限额
    val githubApi = remember {
        GitHubApi(tokenProvider = { GitHubTokenStore.getToken() }).also { api ->
            // Token 被 GitHub 拒绝（HTTP 401）：清除本地 Token 并提示；否则后续所有请求都会失败
            api.onUnauthorized = {
                GitHubTokenStore.setToken(null)
                githubHasTokenState = false
                SnackbarController.show("GitHub Token 已失效，已自动清除")
            }
        }
    }
    // GitHub Token 配置弹窗 / 清除二次确认（网盘页入口）
    var showGitHubTokenDialog by remember { mutableStateOf(false) }
    var showGitHubClearConfirm by remember { mutableStateOf(false) }
    val repository = remember {
        QuarkAccountRepository(db.quarkAccountDao(), api)
    }
    val ucRepository = remember {
        UCAccountRepository(db.ucAccountDao(), ucApi)
    }
    val xunleiRepository = remember {
        XunleiAccountRepository(db.xunleiAccountDao(), xunleiApi)
    }
    val baiduRepository = remember {
        BaiduAccountRepository(db.baiduAccountDao(), baiduApi)
    }
    val c139Repository = remember {
        C139AccountRepository(db.c139AccountDao())
    }
    val pan123Repository = remember {
        Pan123AccountRepository(db.pan123AccountDao(), pan123Api)
    }
    val pan115Repository = remember {
        Pan115AccountRepository(db.pan115AccountDao(), pan115Api)
    }
    val backupManager = remember {
        AuthBackupManager(
            db.quarkAccountDao(),
            db.ucAccountDao(),
            db.xunleiAccountDao(),
            db.baiduAccountDao(),
            db.c139AccountDao(),
            db.pan123AccountDao(),
            db.pan115AccountDao()
        )
    }
    val downloadManager = remember {
        DownloadManager(
            dao = db.downloadTaskDao(),
            downloader = ChunkDownloader({ HttpClients.downloadClient() }),
            threadProvider = { platform -> settings.downloadThreadsFor(platform) },
            saveDirProvider = { settings.downloadDirUri },
            concurrencyProvider = { settings.maxConcurrentDownloads },
            speedLimitProvider = { settings.downloadSpeedLimit },
            retryCountProvider = { settings.downloadRetryCount },
            keepAwakeProvider = { settings.keepAwakeWhileDownloading },
            showSpeedProvider = { settings.notificationShowSpeed }
        )
    }

    // ===== 关闭行为处理 =====
    val tasks by downloadManager.tasks.collectAsState(initial = emptyList())
    // 关闭确认弹窗状态
    var showCloseDialog by remember { mutableStateOf(false) }
    var closeDialogHasDownloads by remember { mutableStateOf(false) }

    // 隐藏主窗口到系统托盘
    val minimizeToTray: () -> Unit = {
        // 委托给 Main.kt 的实现（含关闭请求抑制，避免 hide 触发 onCloseRequest 重跑）
        onMinimizeToTray()
        TrayManager.showNotification("云析", "已最小化到系统托盘，下载仍在后台继续")
    }

    // 监听关闭请求信号
    LaunchedEffect(closeRequested) {
        if (!closeRequested) return@LaunchedEffect
        val behavior = settings.closeBehavior
        val hasActiveDownloads = tasks.any {
            it.status == DownloadTaskEntity.STATUS_DOWNLOADING || it.status == DownloadTaskEntity.STATUS_PENDING
        }
        when (behavior) {
            SettingsRepository.CLOSE_BEHAVIOR_TRAY -> {
                minimizeToTray()
            }
            SettingsRepository.CLOSE_BEHAVIOR_EXIT -> {
                if (hasActiveDownloads) {
                    closeDialogHasDownloads = true
                    showCloseDialog = true
                } else {
                    onExitApplication()
                }
            }
            else -> { // CLOSE_BEHAVIOR_ASK
                closeDialogHasDownloads = hasActiveDownloads
                showCloseDialog = true
            }
        }
        onCloseHandled()
    }

    val viewModel: QuarkAccountViewModel = viewModel(
        factory = QuarkAccountViewModel.Factory(repository)
    )
    val ucViewModel: UCAccountViewModel = viewModel(
        factory = UCAccountViewModel.Factory(ucRepository)
    )
    val xunleiViewModel: XunleiAccountViewModel = viewModel(
        factory = XunleiAccountViewModel.Factory(xunleiRepository)
    )
    val baiduViewModel: BaiduAccountViewModel = viewModel(
        factory = BaiduAccountViewModel.Factory(baiduRepository)
    )
    val c139ViewModel: C139AccountViewModel = viewModel(
        factory = C139AccountViewModel.Factory(c139Repository)
    )
    val pan123ViewModel: Pan123AccountViewModel = viewModel(
        factory = Pan123AccountViewModel.Factory(pan123Repository)
    )
    val pan115ViewModel: Pan115AccountViewModel = viewModel(
        factory = Pan115AccountViewModel.Factory(pan115Repository)
    )
    val quarkCloudViewModel: QuarkCloudViewModel = viewModel(
        factory = QuarkCloudViewModel.Factory(
            api,
            { repository.getFreshCookie() },
            downloadManager
        )
    )
    val ucCloudViewModel: UCCoudViewModel = viewModel(
        factory = UCCoudViewModel.Factory(
            ucApi,
            { ucRepository.getFreshCookie() },
            downloadManager
        )
    )
    // 迅雷 access_token 过期自动刷新
    xunleiApi.refreshTokenProvider = { deviceId ->
        val acc = xunleiRepository.getAccount()
        if (acc == null || acc.refreshToken.isBlank()) null
        else xunleiApi.refreshToken(acc.refreshToken, deviceId)?.also { (at, nrt) ->
            xunleiRepository.updateTokens(at, nrt)
        }
    }
    val xunleiCloudViewModel: XunleiCloudViewModel = viewModel(
        factory = XunleiCloudViewModel.Factory(
            xunleiApi,
            { xunleiRepository.getAccount()?.accessToken },
            { xunleiRepository.getAccount()?.deviceId },
            { xunleiRepository.getAccount()?.captchaToken },
            downloadManager
        )
    )
    val baiduCloudViewModel: BaiduCloudViewModel = viewModel(
        factory = BaiduCloudViewModel.Factory(
            baiduApi,
            { baiduRepository.getAccount()?.cookie },
            downloadManager
        )
    )
    val c139CloudViewModel: C139CloudViewModel = viewModel(
        factory = C139CloudViewModel.Factory(
            c139Api,
            { c139Repository.getAccount()?.cookie },
            downloadManager
        )
    )
    val pan123CloudViewModel: Pan123CloudViewModel = viewModel(
        factory = Pan123CloudViewModel.Factory(
            pan123Api,
            { pan123Repository.getAccount()?.accessToken },
            downloadManager
        )
    )
    val pan115CloudViewModel: Pan115CloudViewModel = viewModel(
        factory = Pan115CloudViewModel.Factory(
            pan115Api,
            { pan115Repository.getAccount()?.cookie },
            downloadManager,
            // 登录态从无到有后自动重载根目录（115 登录走内嵌浏览器，登录完成不重启 VM）
            pan115ViewModel.pan115Account.map { it != null }
        )
    )
    val driveQuotaViewModel: DriveQuotaViewModel = viewModel(
        factory = DriveQuotaViewModel.Factory(
            api, { repository.getAccount()?.cookie },
            ucApi, { ucRepository.getAccount()?.cookie },
            xunleiApi,
            { xunleiRepository.getAccount()?.accessToken },
            { xunleiRepository.getAccount()?.deviceId },
            { xunleiRepository.getAccount()?.captchaToken },
            baiduApi, { baiduRepository.getAccount()?.cookie },
            c139Api, { c139Repository.getAccount()?.cookie },
            pan123Api, { pan123Repository.getAccount()?.accessToken },
            pan115Api, { pan115Repository.getAccount()?.cookie }
        )
    )
    val xunleiResolveRepository = remember {
        XunleiResolveRepository(
            api = xunleiApi,
            accountProvider = { xunleiRepository.getAccount()?.accessToken },
            deviceIdProvider = { xunleiRepository.getAccount()?.deviceId },
            captchaProvider = { xunleiRepository.getAccount()?.captchaToken },
            refreshProvider = {
                val acc = xunleiRepository.getAccount()
                if (acc == null || acc.refreshToken.isBlank()) null
                else xunleiApi.refreshToken(acc.refreshToken, acc.deviceId)?.also { (at, nrt) ->
                    xunleiRepository.updateTokens(at, nrt)
                }
            }
        )
    }
    val baiduResolveRepository = remember {
        BaiduResolveRepository(baiduApi)
    }
    val c139ResolveRepository = remember {
        C139ResolveRepository(c139Api)
    }
    val pan123ResolveRepository = remember {
        Pan123ResolveRepository(
            api = pan123Api,
            tokenProvider = { pan123Repository.getAccount()?.accessToken }
        )
    }
    val pan115ResolveRepository = remember {
        Pan115ResolveRepository(pan115Api)
    }
    val resolveViewModel: ResolveViewModel = viewModel(
        factory = ResolveViewModel.Factory(
            repository,
            QuarkResolveRepository(api),
            ucRepository,
            UCResolveRepository(ucApi),
            xunleiRepository,
            xunleiResolveRepository,
            baiduRepository,
            baiduResolveRepository,
            c139Repository,
            c139ResolveRepository,
            pan123Repository,
            pan123ResolveRepository,
            pan115Repository,
            pan115ResolveRepository,
            downloadManager,
            db.bookmarkDao(),
            db.linkHistoryDao(),
            githubApi,
            // GitHub 镜像前缀：用户自定义优先，未配置时用内置默认镜像
            { settings.githubMirrorPrefix?.ifBlank { null } ?: UpdateChecker.MIRROR_PREFIX },
            // 取链方式开关：设置页「免转存下载」实时生效
            { settings.quarkNoSaveDownload }
        )
    )
    val downloadViewModel: DownloadViewModel = viewModel(
        factory = DownloadViewModel.Factory(downloadManager)
    )
    val bookmarkViewModel: BookmarkViewModel = viewModel(
        factory = BookmarkViewModel.Factory(db.bookmarkDao())
    )
    // 收藏列表：主页快捷方式的数据源（复用既有收藏仓库，不新造存储）
    val bookmarks by bookmarkViewModel.bookmarks.collectAsState()
    val quarkAccount by viewModel.quarkAccount.collectAsState()
    val ucAccount by ucViewModel.ucAccount.collectAsState()
    val xunleiAccount by xunleiViewModel.xunleiAccount.collectAsState()
    val baiduAccount by baiduViewModel.baiduAccount.collectAsState()
    val c139Account by c139ViewModel.c139Account.collectAsState()
    val pan123Account by pan123ViewModel.pan123Account.collectAsState()
    val pan115Account by pan115ViewModel.pan115Account.collectAsState()

    // 解析页发起下载后，自动切换到「下载」Tab
    LaunchedEffect(resolveViewModel.downloadStarted) {
        if (resolveViewModel.downloadStarted) {
            currentTab = MainTab.Download
            resolveViewModel.consumeDownloadStarted()
        }
    }

    // 首次启动引导页：全屏覆盖（优先级最高）
    if (showOnboarding) {
        OnboardingScreen(
            onFinish = {
                AppContext.miscPrefs.putBoolean("onboarding_shown", true)
                showOnboarding = false
            }
        )
        return
    }

    // 夸克登录页：全屏覆盖
    if (showQuarkLogin) {
        QuarkLoginScreen(
            viewModel = viewModel,
            onBack = { showQuarkLogin = false },
            onSaved = { showQuarkLogin = false }
        )
        return
    }

    // UC 登录页：全屏覆盖
    if (showUCLogin) {
        UCLoginScreen(
            viewModel = ucViewModel,
            onBack = { showUCLogin = false },
            onSaved = { showUCLogin = false }
        )
        return
    }

    // 迅雷登录页：全屏覆盖（账号+密码，可能触发短信验证）
    if (showXunleiLogin) {
        XunleiLoginScreen(
            viewModel = xunleiViewModel,
            onBack = { showXunleiLogin = false },
            onSaved = { showXunleiLogin = false },
            onVerify = { url, deviceId ->
                xunleiVerifyUrl = url
                xunleiVerifyDeviceId = deviceId
                showXunleiLogin = false
                showXunleiVerify = true
            }
        )
        return
    }

    // 迅雷验证页（桌面版：系统浏览器承载验证）
    if (showXunleiVerify) {
        XunleiVerifyWebViewScreen(
            verifyUrl = xunleiVerifyUrl,
            deviceId = xunleiVerifyDeviceId,
            onResult = { success, _ ->
                showXunleiVerify = false
                showXunleiLogin = true // 回到登录页
                if (success) {
                    SnackbarController.show("验证完成，正在自动登录…")
                    xunleiViewModel.retryLoginAfterVerify()
                } else {
                    SnackbarController.show("验证未完成，请重试")
                }
            },
            onBack = {
                showXunleiVerify = false
                showXunleiLogin = true
            }
        )
        return
    }

    // 百度登录页：全屏覆盖（粘贴 Cookie 登录）
    if (showBaiduLogin) {
        BaiduLoginScreen(
            viewModel = baiduViewModel,
            onBack = { showBaiduLogin = false },
            onSaved = { showBaiduLogin = false }
        )
        return
    }

    // 139 登录页：全屏覆盖（粘贴 Cookie 登录）
    if (showC139Login) {
        C139LoginScreen(
            viewModel = c139ViewModel,
            onBack = { showC139Login = false },
            onSaved = { showC139Login = false }
        )
        return
    }

    // 123 登录页：全屏覆盖（账号+密码表单登录换 JWT）
    if (showPan123Login) {
        Pan123LoginScreen(
            viewModel = pan123ViewModel,
            onBack = { showPan123Login = false },
            onSaved = { showPan123Login = false }
        )
        return
    }

    // 115 登录页：全屏覆盖（内嵌浏览器登录抓 Cookie）
    if (showPan115Login) {
        Pan115LoginScreen(
            viewModel = pan115ViewModel,
            onBack = { showPan115Login = false },
            onSaved = { showPan115Login = false }
        )
        return
    }

    // 折叠标题状态提升到本层：跨页面共享
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topAppBarState)

    // 全局 Snackbar 宿主
    val snackbarHostState = rememberGlobalSnackbarHostState()

    /**
     * 应用内公告：顶栏红点角标 + 启动弹窗。
     *
     * 启动检查（[AnnouncementViewModel.checkStartup]）整个会话只跑一次：一次列表请求同时决定
     * 「角标数字」与「弹窗展示哪一条」—— 有未读的置顶公告就弹它，否则弹最新的一条未读，全读完则不弹。
     * 失败静默（与更新检查同一口径），用户点进公告页时会再拉一次并把错误显示出来。
     */
    val announcementReadStore = remember { AnnouncementReadStore() }
    val announcementViewModel: AnnouncementViewModel = viewModel(
        factory = AnnouncementViewModel.Factory(announcementReadStore)
    )
    val unreadAnnouncementCount by announcementViewModel.unreadCount.collectAsState()
    val popupAnnouncement by announcementViewModel.popup.collectAsState()
    LaunchedEffect(Unit) { announcementViewModel.checkStartup() }

    // 主框架与全屏覆盖层（关于页等）放在同一 Box：覆盖层带过渡动画
    Box(modifier = Modifier.fillMaxSize()) {
        // 根部提供主题内容色：M3 的 LocalContentColor 默认是 Color.Black（不随主题翻转），
        // 不提供的话所有未显式指定颜色的 Text 在深色模式下会保持黑色（如下载页空状态标题）
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground
        ) {
        val topBarContent: @Composable () -> Unit = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = currentTab.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    // 公告入口：所有 Tab 都显示（收藏只在解析页出现，公告是全局入口）。
                    // 角标画在 IconButton 外面（外层再套一个 48dp 的 Box）：material3 的 IconButton
                    // 内部带 .clip(CircleShape)，角标超出那颗圆会被切掉；外层 Box 同为 48dp 且不裁剪，
                    // 点击事件照旧落到 IconButton。
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = {
                                // 从图标进 = 先看列表（清掉上次「查看详情」直接进详情的请求）
                                announcementDetailId = null
                                showAnnouncements = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Campaign,
                                contentDescription = if (unreadAnnouncementCount > 0) {
                                    "公告（$unreadAnnouncementCount 条未读）"
                                } else {
                                    "公告"
                                }
                            )
                        }
                        // 未读红点角标：只有主界面显示（公告页自己开着的时候不显示）
                        if (unreadAnnouncementCount > 0 && !showAnnouncements) {
                            AnnouncementUnreadBadge(
                                count = unreadAnnouncementCount,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-6).dp, y = 2.dp)
                            )
                        }
                    }
                    // 解析页标题右上角：收藏网盘链接入口
                    if (currentTab == MainTab.Resolve) {
                        IconButton(onClick = { showBookmarks = true }) {
                            Icon(Icons.Outlined.Bookmarks, contentDescription = "收藏网盘链接")
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
        val tabContent: @Composable () -> Unit = {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    if (forward) {
                        (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 4 })
                            .togetherWith(fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { -it / 4 })
                    } else {
                        (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { -it / 4 })
                            .togetherWith(fadeOut(tween(160)) + slideOutHorizontally(tween(160)) { it / 4 })
                    }
                },
                label = "mainTab"
            ) { tab ->
                saveableStateHolder.SaveableStateProvider(tab) {
                    when (tab) {
                        MainTab.Resolve -> ResolveScreen(
                            scrollBehavior,
                            resolveViewModel,
                            quarkCloudViewModel,
                            xunleiCloudViewModel,
                            baiduCloudViewModel,
                            c139CloudViewModel,
                            ucCloudViewModel,
                            pan123CloudViewModel,
                            pan115CloudViewModel,
                            // 主页快捷方式（实验性功能，默认关闭）：复用收藏仓库数据源
                            homeShortcutsEnabled = homeShortcutsEnabled,
                            homeShortcuts = bookmarks,
                            onManageShortcuts = { showBookmarks = true }
                        )
                        MainTab.Drive -> DriveScreen(
                            scrollBehavior = scrollBehavior,
                            quarkAccount = quarkAccount,
                            ucAccount = ucAccount,
                            xunleiAccount = xunleiAccount,
                            baiduAccount = baiduAccount,
                            c139Account = c139Account,
                            pan123Account = pan123Account,
                            pan115Account = pan115Account,
                            quarkCloudViewModel = quarkCloudViewModel,
                            ucCloudViewModel = ucCloudViewModel,
                            xunleiCloudViewModel = xunleiCloudViewModel,
                            baiduCloudViewModel = baiduCloudViewModel,
                            c139CloudViewModel = c139CloudViewModel,
                            pan123CloudViewModel = pan123CloudViewModel,
                            pan115CloudViewModel = pan115CloudViewModel,
                            driveQuotaViewModel = driveQuotaViewModel,
                            onQuarkLogin = { showQuarkLogin = true },
                            onQuarkLogout = { viewModel.logout() },
                            onDownloadStarted = { currentTab = MainTab.Download },
                            onUCLogin = { showUCLogin = true },
                            onUCLogout = { ucViewModel.logout() },
                            onXunleiLogin = { showXunleiLogin = true },
                            onXunleiLogout = { xunleiViewModel.logout() },
                            onBaiduLogin = { showBaiduLogin = true },
                            onBaiduLogout = { baiduViewModel.logout() },
                            onC139Login = { showC139Login = true },
                            onC139Logout = { c139ViewModel.logout() },
                            onPan123Login = { showPan123Login = true },
                            onPan123Logout = { pan123ViewModel.logout() },
                            onPan115Login = { showPan115Login = true },
                            onPan115Logout = { pan115ViewModel.logout() },
                            githubHasToken = githubHasTokenState,
                            onGitHubTokenClick = { showGitHubTokenDialog = true },
                            // 已配置 Token 点卡片主体：用 GET /user 取 login，经统一解析入口进入该账号仓库列表
                            onGitHubBrowseHome = {
                                scope.launch {
                                    val login = githubApi.getUserLogin()
                                    if (!login.isNullOrBlank()) {
                                        resolveViewModel.startResolve("https://github.com/$login", "")
                                        currentTab = MainTab.Resolve
                                    }
                                }
                            },
                            // 更多菜单「清除 Token」：先二次确认再清除
                            onGitHubClearToken = { showGitHubClearConfirm = true }
                        )
                        MainTab.Download -> DownloadScreen(scrollBehavior, downloadViewModel)
                        MainTab.Settings -> SettingsScreen(
                            scrollBehavior = scrollBehavior,
                            onThemeClick = { showTheme = true },
                            onAboutClick = { showAbout = true },
                            onSupportClick = { showSupport = true },
                            onExperimentalClick = { showExperimental = true },
                            backupManager = backupManager,
                            onDownloadUpdateApk = { url, name, fallbackUrl ->
                                scope.launch {
                                    // 镜像下载传入直连作为回退：镜像主 URL 探测失败时整任务切到原始直连
                                    downloadManager.enqueue(
                                        url = url,
                                        fileName = name,
                                        fallbackUrl = fallbackUrl ?: ""
                                    )
                                    currentTab = MainTab.Download
                                }
                            }
                        )
                    }
                }
            }
        }

        // 桌面固定横向布局：左侧导航栏 + 右侧顶栏与内容
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                MainNavigationRail(
                    currentTab = currentTab,
                    onTabSelected = { currentTab = it }
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                ) {
                    topBarContent()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        tabContent()
                    }
                }
            }
            // 全局 Snackbar（悬浮底部居中）
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // 关于云析：叠加覆盖层（淡入 + 轻微缩放过渡）
        AnimatedVisibility(
            visible = showAbout,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
            modifier = Modifier.fillMaxSize()
        ) {
            AboutScreen(
                onBack = { showAbout = false },
                onPreviewOnboarding = {
                    AppContext.miscPrefs.putBoolean("onboarding_shown", false)
                    showAbout = false
                    showOnboarding = true
                }
            )
        }

        // 支持开发：叠加覆盖层
        AnimatedVisibility(
            visible = showSupport,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
            modifier = Modifier.fillMaxSize()
        ) {
            SupportScreen(
                onBack = { showSupport = false }
            )
        }

        // 主题与外观：叠加覆盖层
        AnimatedVisibility(
            visible = showTheme,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
            modifier = Modifier.fillMaxSize()
        ) {
            ThemeScreen(
                onBack = { showTheme = false }
            )
        }

        // 实验性功能：叠加覆盖层（二级页）
        AnimatedVisibility(
            visible = showExperimental,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
            modifier = Modifier.fillMaxSize()
        ) {
            ExperimentalFeaturesScreen(
                onBack = { showExperimental = false },
                onHomeShortcutChanged = { homeShortcutsEnabled = it }
            )
        }

        // 收藏网盘链接：叠加覆盖层
        AnimatedVisibility(
            visible = showBookmarks,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
            modifier = Modifier.fillMaxSize()
        ) {
            BookmarkScreen(
                viewModel = bookmarkViewModel,
                onBack = { showBookmarks = false },
                onResolve = { link, pwd ->
                    showBookmarks = false
                    currentTab = MainTab.Resolve
                    resolveViewModel.startResolve(link, pwd)
                }
            )
        }

        // 应用内公告：叠加覆盖层（列表 ↔ 详情在页面内部切换）
        AnimatedVisibility(
            visible = showAnnouncements,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
            modifier = Modifier.fillMaxSize()
        ) {
            AnnouncementScreen(
                viewModel = announcementViewModel,
                onBack = { showAnnouncements = false },
                // 启动弹窗点了「查看详情」就直接落在详情页
                initialDetailId = announcementDetailId
            )
        }

        // 全局弹窗覆盖层（FadeAlertDialog）：窗口内直接绘制，零原生窗口开销。
        // 放在根部最后 → 绘制在所有内容（含全屏覆盖层）之上。
        OverlayDialogHost()

        // 关闭确认弹窗（退出 / 最小化到托盘 / 取消）
        CloseConfirmDialog(
            visible = showCloseDialog,
            hasActiveDownloads = closeDialogHasDownloads,
            onDismiss = { choice, remember ->
                showCloseDialog = false
                if (remember && choice != CloseChoice.CANCEL) {
                    settings.closeBehavior = when (choice) {
                        CloseChoice.TRAY -> SettingsRepository.CLOSE_BEHAVIOR_TRAY
                        CloseChoice.EXIT -> SettingsRepository.CLOSE_BEHAVIOR_EXIT
                        CloseChoice.CANCEL -> SettingsRepository.CLOSE_BEHAVIOR_ASK
                    }
                }
                when (choice) {
                    CloseChoice.TRAY -> {
                        // 等待弹窗淡出完成（140ms）后再隐藏窗口，
                        // 否则窗口恢复时弹窗残留导致闪烁
                        scope.launch {
                            kotlinx.coroutines.delay(150)
                            minimizeToTray()
                        }
                    }
                    CloseChoice.EXIT -> onExitApplication()
                    CloseChoice.CANCEL -> {}
                }
            }
        )

        // GitHub Token 配置弹窗（网盘页入口）：AES-GCM 加密存储，输入用密码可见性切换
        if (showGitHubTokenDialog) {
            var tokenInput by rememberSaveable { mutableStateOf(GitHubTokenStore.getToken() ?: "") }
            var passwordVisible by remember { mutableStateOf(false) }
            // 校验失败提示（显示在输入框下方），修改输入即清除
            var tokenError by remember { mutableStateOf<String?>(null) }
            FadeAlertDialog(
                visible = true,
                onDismissRequest = { showGitHubTokenDialog = false },
                title = { Text("GitHub Token") },
                text = {
                    Column {
                        Text(
                            text = "Token 仅用于提升 API 限额（匿名 60/小时，认证后 5000/小时）。经 AES-GCM 加密存储，不会明文保存。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "如何获取 Token：",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "1. 浏览器打开 GitHub，右上角头像 → Settings\n" +
                                "2. 左侧 Developer settings → Personal access tokens → Tokens (classic) → Generate new token\n" +
                                "3. 勾选 public_repo 即可浏览公开仓库；如需在主页看到自己的私有仓库，再勾选 repo\n" +
                                "4. 有效期建议选 90 天或 No expiration，生成后复制粘贴到上方输入框",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "安全提示：仅给最小权限，勿勾选删除/管理类权限；Token 不清空保存即可清除。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = {
                                tokenInput = it
                                tokenError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = tokenError != null,
                            label = { Text("Personal Access Token") },
                            supportingText = { tokenError?.let { Text(it) } },
                            visualTransformation = if (passwordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Outlined.Visibility
                                        else Icons.Outlined.VisibilityOff,
                                        contentDescription = if (passwordVisible) "隐藏" else "显示"
                                    )
                                }
                            }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val input = tokenInput.trim()
                        if (input.isBlank()) {
                            // 清空即清除 Token（无需联网校验）
                            GitHubTokenStore.setToken(null)
                            githubHasTokenState = false
                            showGitHubTokenDialog = false
                            SnackbarController.show("已清除 GitHub Token")
                        } else {
                            // 保存前先校验：无效 Token 会让 GitHub 拒绝之后的所有请求（含公开仓库解析）
                            scope.launch {
                                when (val check = githubApi.validateToken(input)) {
                                    is TokenCheck.Valid -> {
                                        GitHubTokenStore.setToken(input)
                                        githubHasTokenState = true
                                        showGitHubTokenDialog = false
                                        SnackbarController.show("GitHub Token 已保存（@${check.login}）")
                                    }
                                    TokenCheck.Invalid -> tokenError = "Token 无效或已过期，请重新生成后再保存"
                                    TokenCheck.Unknown -> tokenError = "无法校验 Token（网络异常），请联网后重试"
                                }
                            }
                        }
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { showGitHubTokenDialog = false }) { Text("取消") }
                }
            )
        }

        // 清除 GitHub Token 二次确认（网盘页更多菜单）
        if (showGitHubClearConfirm) {
            FadeAlertDialog(
                visible = true,
                onDismissRequest = { showGitHubClearConfirm = false },
                title = { Text("清除 GitHub Token？") },
                text = { Text("清除后 GitHub API 回退匿名限额（60 次/小时/IP）。") },
                confirmButton = {
                    TextButton(onClick = {
                        GitHubTokenStore.setToken(null)
                        githubHasTokenState = false
                        showGitHubClearConfirm = false
                        SnackbarController.show("已清除 GitHub Token")
                    }) { Text("清除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showGitHubClearConfirm = false }) { Text("取消") }
                }
            )
        }

        // 启动公告弹窗：展示未读的置顶公告（没有则最新未读）。两个出口都算已读，见 AnnouncementViewModel.consumePopup
        popupAnnouncement?.let { announcement ->
            AnnouncementPopupDialog(
                announcement = announcement,
                onDetail = {
                    announcementDetailId = announcement.id
                    showAnnouncements = true
                    announcementViewModel.consumePopup()
                },
                onDismiss = { announcementViewModel.consumePopup() }
            )
        }

        // 剪贴板分享链接检测器：主窗口失焦时若剪贴板有分享链接，触发右下角弹窗。
        // Detector 在 Box 内部以绑定 MainScreen 生命周期；Popup 是独立顶层 Window。
        // 可在「设置 → 用户体验」中关闭；切换 Tab 返回时重新读取设置值。
        ClipboardLinkDetector(settings = settings)
        ClipboardLinkPopup()

        // 托盘菜单由 Main.kt 的 Tray 组件处理（原生 Win32 菜单）

        // 消费 ClipboardLinkController.openRequest：用户在弹窗点「打开」时切到解析页 + 启动解析 + 主窗口前台。
        // ClipboardLinkController.openRequest 是 mutableStateOf（Compose State），但用轮询消费更可靠。
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(200)
                ClipboardLinkController.consumeOpen()?.let { d ->
                    currentTab = MainTab.Resolve
                    resolveViewModel.startResolve(d.text, d.parsed.pwd)
                    // Windows 上 toFront() 单独调用不一定能夺取焦点，用 alwaysOnTop 瞬时切换确保置顶
                    ClipboardLinkController.mainWindow?.let { w ->
                        val wasOnTop = w.isAlwaysOnTop
                        w.isAlwaysOnTop = true
                        w.toFront()
                        w.requestFocus()
                        w.isAlwaysOnTop = wasOnTop
                    }
                }
            }
        }
        }
    }
}

/**
 * 侧边导航栏（桌面固定）：4 个主 Tab，未选中项只显示图标。
 */
@Composable
private fun MainNavigationRail(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit
) {
    NavigationRail {
        MainTab.values().forEach { tab ->
            NavigationRailItem(
                selected = currentTab == tab,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = if (currentTab == tab) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = tab.title
                    )
                },
                label = { Text(tab.title) },
                alwaysShowLabel = currentTab == tab
            )
        }
    }
}
