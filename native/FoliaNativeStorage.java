package top.izuna.foliamajor;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 車機用的「連結模式」原生層。
 *
 * 為什麼要有這一層：Folia 是網頁應用包進 WebView 的，`<input type=file>` 拿回來的只有檔案副本，
 * 想留著重開再播就得把整份 bytes 抄進 IndexedDB —— 2216 首要抄 3~6 GB，車機 eMMC 寫入慢，
 * 匯入就得幾十分鐘。這裡改成：
 *
 *   1. 拿「所有檔案存取」權限，用 java.io.File 直接讀車機（含 USB）上的原始檔案，不抄。
 *   2. 在 127.0.0.1 起一個支援 HTTP Range 的小服務，把檔案與封面用 http:// 串給 WebView，
 *      於是 <audio src> 直接邊播邊讀，音樂本體一份都不用複製。
 *   3. 標籤／時長／封面用 MediaMetadataRetriever 在原生層取（比在 JS 裡解快，還不必把檔案搬進 JS 記憶體）。
 *
 * 全部走 addJavascriptInterface（同步字串回傳）而不是 Capacitor 插件，因為插件要註冊、要改
 * 產生的工程；這支類別只要 MainActivity 在 onCreate 裡 attach 一次就好。
 */
public class FoliaNativeStorage {

    private static final String JS_NAME = "FoliaNativeStorage";
    private static final int REQUEST_CODE_PERMISSION = 4211;
    private static final String[] AUDIO_EXTENSIONS = {
            ".mp3", ".flac", ".m4a", ".wav", ".ogg", ".opus", ".aac", ".alac",
            ".ape", ".wv", ".tta", ".tak", ".wma", ".aif", ".aiff", ".caf", ".dsf", ".dff", ".mp4", ".mka"
    };
    private static final int MAX_SCAN_FILES = 20000;
    private static final int MAX_SCAN_DEPTH = 12;
    // 觸發 CI 重跑（上一輪 Maven 429 是暫時性的）。

    private final Activity activity;
    private final Context context;
    private ServerSocket serverSocket;
    private int port;
    private WebView webView;
    private MediaSession mediaSession;
    private boolean playing = false;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus = false;
    /** 同一顆鍵會被 MediaSession 與 Activity 各投遞一次，350 ms 內只認第一次，否則一按就會連跳兩下。 */
    private long lastKeyAtMs = 0;
    private String lastKeyAction = "";
    /** 播放位置由 JS 回報，原生每秒往前走一格，車機的媒體面板才會一直有「在播」的樣子。 */
    private long reportedPositionMs = 0;
    private long reportedDurationMs = 0;
    private long reportedAtMs = 0;
    private Handler tickerHandler;
    private Runnable ticker;
    /**
     * v0.9.11：被別的 App 搶走音訊焦點時要不要自動暫停。
     * 預設「不要」——很多車機的電台／DSP 服務會一直搶焦點，一搶我們就暫停，使用者看到的就是
     * 「點播放立刻變暫停，怎麼點怎麼暫停」。Aurora 的做法是不理焦點，只在硬體鍵上反應。
     */
    private boolean pauseOnFocusLoss = false;
    /** 最近一次由頁面回報「開始播放」的時刻：用來吞掉車機回送來的假 onPause。 */
    private long lastReportedPlayingAtMs = 0;
    /** 最近的媒體事件（焦點、按鍵、被吞掉的回音），診斷面板直接吐出來。 */
    private final java.util.Deque<String> mediaLog = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private void logMediaEvent(String text) {
        try {
            mediaLog.addFirst((System.currentTimeMillis() / 1000 % 100000) + "s " + text);
            while (mediaLog.size() > 40) mediaLog.removeLast();
        } catch (Throwable ignored) {
        }
    }
    private final Map<String, String> transcodeJobs = new ConcurrentHashMap<>();
    /** 方控的按鍵走 Activity 的 dispatchKeyEvent，MainActivity 靠這個把手把按鍵丟回來。 */
    private static FoliaNativeStorage instance;

    private FoliaNativeStorage(Activity activity) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
    }

    /** MainActivity 的 onCreate 最後呼叫；WebView 這時已經由 Bridge 建好。 */
    public static void attach(BridgeActivity host) {
        try {
            FoliaNativeStorage storage = new FoliaNativeStorage(host);
            storage.webView = host.getBridge().getWebView();
            storage.startServer();
            storage.startMediaSession();
            instance = storage;
            host.getBridge().getWebView().addJavascriptInterface(storage, JS_NAME);
        } catch (Throwable error) {
            android.util.Log.e("FoliaNativeStorage", "attach failed", error);
        }
    }

    // ---------------------------------------------------------------- JS 介面

    @JavascriptInterface
    public String ping() {
        JSONObject json = new JSONObject();
        try {
            json.put("ok", true);
            json.put("port", port);
            json.put("sdk", Build.VERSION.SDK_INT);
        } catch (Exception ignored) {
            // JSONObject 的 put 只在極端情況失敗，失敗也要回一個能解析的字串。
        }
        return json.toString();
    }
    /**
     * 方控（方向盤按鍵）與系統媒體鍵的接收點。
     *
     * 為什麼要原生做：WebView 不會向系統註冊 MediaSession，方控的 PLAY/PAUSE 根本傳不到頁面裡的
     * navigator.mediaSession。這裡建一個真正的 MediaSession 當作「目前在播的 App」，再把按鍵轉給 JS。
     * 老車機有些不走 MediaSession、直接把 KeyEvent 丟給前景 Activity，所以 MainActivity 也接了
     * dispatchKeyEvent 一起轉。
     *
     * 跟 Aurora 對齊的地方（v0.9.8）：
     *   1. 播放時向系統要 audio focus、暫停時放掉。車機只把媒體鍵交給「有焦點、而且有在播」的
     *      session；沒有焦點時按鍵會被丟回車機內建的播放器，看起來就是「只有第一次能用」。
     *   2. 播放中每秒更新 PlaybackState 的 position / speed，面板才一直認為這個 session 還活著。
     *   3. MediaSession callback 與 Activity dispatchKeyEvent 兩個投遞點去重，一次按鍵只算一次。
     *   4. setSessionActivity：從車機的媒體面板點回 App 時真的能把 Activity 叫起來。
     */
    private void startMediaSession() {
        if (Build.VERSION.SDK_INT < 21 || mediaSession != null) return;
        try {
            audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            mediaSession = new MediaSession(context, "FoliaPlayback");
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    logMediaEvent("session onPlay");
                    dispatchMediaKey("play");
                }

                @Override
                public void onPause() {
                    // 有些車機會在我們剛回報「開始播放」後，立刻把 session 的 PAUSED 回送一次；
                    // 那不是使用者按的鍵，吞掉，否則一點播放就被自己停掉。
                    long sincePlay = SystemClock.elapsedRealtime() - lastReportedPlayingAtMs;
                    if (sincePlay >= 0 && sincePlay < 900) {
                        logMediaEvent("session echo pause ignored (" + sincePlay + "ms)");
                        return;
                    }
                    logMediaEvent("session onPause");
                    dispatchMediaKey("pause");
                }

                @Override
                public void onStop() {
                    long sincePlay = SystemClock.elapsedRealtime() - lastReportedPlayingAtMs;
                    if (sincePlay >= 0 && sincePlay < 900) {
                        logMediaEvent("session echo stop->pause ignored (" + sincePlay + "ms)");
                        return;
                    }
                    logMediaEvent("session onStop");
                    dispatchMediaKey("pause");
                }

                @Override
                public void onSkipToNext() {
                    dispatchMediaKey("next");
                }

                @Override
                public void onSkipToPrevious() {
                    dispatchMediaKey("prev");
                }

                /**
                 * 有些車機不走 callback，直接把 MEDIA_BUTTON intent 丟進來；不覆寫這個方法的話
                 * PLAY_PAUSE 會落到預設實作而被吃掉。
                 */
                @Override
                public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                    if (mediaButtonIntent != null) {
                        KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                                && handleMediaKeyCode(event.getKeyCode())) {
                            return true;
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent);
                }
            });
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            try {
                mediaSession.setPlaybackToLocal(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
            } catch (Throwable ignored) {
                // API 21 以前的建構子差異；拿不到也不影響按鍵轉發。
            }
            try {
                Intent launch = new Intent(context, MainActivity.class);
                launch.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pending = PendingIntent.getActivity(
                        context, 0, launch,
                        Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);
                mediaSession.setSessionActivity(pending);
            } catch (Throwable ignored) {
                // 沒有 sessionActivity 只是面板點不回 App，按鍵照樣能用。
            }
            mediaSession.setActive(true);
            applyPlaybackState(false);
        } catch (Throwable error) {
            android.util.Log.e("FoliaNativeStorage", "MediaSession unavailable", error);
        }
    }

    /** 舊介面（boolean）；位置由 reportPlaybackState 提供。 */
    /** 設定頁的「被其他聲音搶走時暫停」開關；預設關。 */
    @JavascriptInterface
    public void setPauseOnFocusLoss(boolean enabled) {
        pauseOnFocusLoss = enabled;
        logMediaEvent("pauseOnFocusLoss=" + enabled);
    }

    /** 最近的媒體事件（焦點／按鍵／回音），給診斷面板看。 */
    @JavascriptInterface
    public String mediaEventLog() {
        return String.join("\n", mediaLog);
    }

    @JavascriptInterface
    public void setPlaybackState(boolean isPlaying) {
        reportPlaybackState(isPlaying, currentPositionMs(), reportedDurationMs);
    }

    /**
     * 新的上報點：頁面每次開始／暫停／換歌都回報一次播放狀態與位置，
     * 原生拿它更新 MediaSession 的 PlaybackState（車機面板的進度條就是讀這個）。
     */
    @JavascriptInterface
    public void reportPlaybackState(boolean isPlaying, double positionMs, double durationMs) {
        reportedPositionMs = Math.max(0, Math.round(positionMs));
        reportedDurationMs = Math.max(0, Math.round(durationMs));
        reportedAtMs = SystemClock.elapsedRealtime();
        if (isPlaying && !playing) lastReportedPlayingAtMs = reportedAtMs;
        playing = isPlaying;
        updateAudioFocus(isPlaying);
        startTicker(isPlaying);
        applyPlaybackState(isPlaying);
    }

    private long currentPositionMs() {
        if (playing && reportedAtMs > 0) {
            long drift = SystemClock.elapsedRealtime() - reportedAtMs;
            long estimate = reportedPositionMs + Math.min(drift, 5000);
            if (reportedDurationMs > 0) return Math.min(estimate, reportedDurationMs);
            return estimate;
        }
        return reportedPositionMs;
    }

    private void applyPlaybackState(boolean isPlaying) {
        if (mediaSession == null) return;
        try {
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                    | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_SEEK_TO;
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            currentPositionMs(), isPlaying ? 1f : 0f)
                    .build());
        } catch (Throwable error) {
            android.util.Log.w("FoliaNativeStorage", "setPlaybackState failed: " + error);
        }
    }

    /** 播放中每秒把 position 往前推一格：車機面板看到 session 還在動，就不會把按鍵路由走。 */
    private void startTicker(final boolean isPlaying) {
        if (tickerHandler == null) tickerHandler = new Handler(Looper.getMainLooper());
        if (ticker != null) {
            tickerHandler.removeCallbacks(ticker);
            ticker = null;
        }
        if (!isPlaying) return;
        ticker = new Runnable() {
            @Override
            public void run() {
                if (!playing) return;
                applyPlaybackState(true);
                if (tickerHandler != null) tickerHandler.postDelayed(this, 1000);
            }
        };
        tickerHandler.postDelayed(ticker, 1000);
    }

    private void updateAudioFocus(boolean wantFocus) {
        if (audioManager == null) return;
        try {
            if (wantFocus && !hasAudioFocus) {
                AudioManager.OnAudioFocusChangeListener listener = new AudioManager.OnAudioFocusChangeListener() {
                    @Override
                    public void onAudioFocusChange(int change) {
                        // 預設只記錄不動作：車機的電台／DSP 服務常常反覆搶焦點，一搶就暫停等於
                        // 播放鍵壞掉。設定裡把「被搶焦點時暫停」打開才真的暫停。
                        logMediaEvent("focus change " + change);
                        if (!pauseOnFocusLoss) return;
                        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            dispatchMediaKey("pause");
                        }
                    }
                };
                if (Build.VERSION.SDK_INT >= 26) {
                    if (audioFocusRequest == null) {
                        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                                .setAudioAttributes(new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .build())
                                .setOnAudioFocusChangeListener(listener)
                                .setWillPauseWhenDucked(false)
                                .build();
                    }
                    hasAudioFocus = audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                } else {
                    hasAudioFocus = audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
                            AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
                }
            } else if (!wantFocus && hasAudioFocus) {
                if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest != null) {
                    audioManager.abandonAudioFocusRequest(audioFocusRequest);
                } else if (Build.VERSION.SDK_INT < 26) {
                    audioManager.abandonAudioFocus(null);
                }
                hasAudioFocus = false;
            }
        } catch (Throwable error) {
            android.util.Log.w("FoliaNativeStorage", "audio focus failed: " + error);
        }
    }

    /** 同一顆鍵去重後再轉給頁面；回 true 代表這是一次有效的新按鍵。 */
    private boolean dispatchMediaKey(String action) {
        long now = SystemClock.elapsedRealtime();
        if (action.equals(lastKeyAction) && now - lastKeyAtMs < 350) {
            return false;
        }
        lastKeyAction = action;
        lastKeyAtMs = now;
        forwardMediaKey(action);
        return true;
    }

    /** MainActivity 的 dispatchKeyEvent 轉過來；認得的鍵回 true。 */
    public static boolean handleHardwareKey(int keyCode) {
        FoliaNativeStorage storage = instance;
        if (storage == null) return false;
        return storage.handleMediaKeyCode(keyCode);
    }

    private boolean handleMediaKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                dispatchMediaKey("playPause");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                dispatchMediaKey("play");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                dispatchMediaKey("pause");
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                dispatchMediaKey("next");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                dispatchMediaKey("prev");
                return true;
            default:
                return false;
        }
    }

    private void forwardMediaKey(String key) {
        final String js = "window.__foliaNativeMediaKey && window.__foliaNativeMediaKey('" + key + "')";
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (webView != null) webView.evaluateJavascript(js, null);
                } catch (Throwable error) {
                    android.util.Log.w("FoliaNativeStorage", "forwardMediaKey failed: " + error);
                }
            }
        });
    }

    @JavascriptInterface
    public int serverPort() {
        return port;
    }

    /** 有沒有讀整個儲存空間的權限（Android 11+ 用 MANAGE，以下用 READ_EXTERNAL_STORAGE）。 */
    @JavascriptInterface
    public boolean hasPermission() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return Environment.isExternalStorageManager();
            }
            return context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable error) {
            return false;
        }
    }

    /** 跳出系統的授權頁；使用者按回來後由 JS 自己輪詢 hasPermission()。 */
    @JavascriptInterface
    public void requestPermission() {
        activity.runOnUiThread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + context.getPackageName()));
                    activity.startActivity(intent);
                } else {
                    activity.requestPermissions(
                            new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                            REQUEST_CODE_PERMISSION);
                }
            } catch (Throwable error) {
                // 有些車機把這個設定頁拿掉了，退回「所有應用」的清單頁。
                try {
                    Intent fallback = new Intent(Build.VERSION.SDK_INT >= 30
                            ? Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                            : Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    if (Build.VERSION.SDK_INT < 30) {
                        fallback.setData(Uri.parse("package:" + context.getPackageName()));
                    }
                    activity.startActivity(fallback);
                } catch (Throwable ignored) {
                    android.util.Log.e("FoliaNativeStorage", "permission page unavailable", error);
                }
            }
        });
    }

    /**
     * 可讀的儲存空間：內建 + 外接（USB／SD）。
     *
     * 為什麼不只列 /storage：車機上 USB 常被掛在別的點（/mnt/media_rw/<UUID>），而且
     * /storage/<UUID> 不一定 canRead()。最可靠的是問系統「這個 App 在各個 volume 上的私有目錄」，
     * 那是 /storage/<UUID>/Android/data/<pkg>/files 這種路徑，把 "/Android/" 之後切掉就是 volume 根。
     * 這條路不需要任何權限就會回，剛好可以拿來找出「有哪些 volume 存在」。
     */
    @JavascriptInterface
    public String roots() {
        JSONArray roots = new JSONArray();
        List<String> seen = new ArrayList<>();
        try {
            File internal = Environment.getExternalStorageDirectory();
            if (internal != null && internal.isDirectory()) {
                seen.add(internal.getAbsolutePath());
                roots.put(volume(internal.getAbsolutePath(), "內建儲存", internal.canRead()));
            }

            // 1) 系統回報的每個 volume（含外接），從 App 私有目錄推回 volume 根。
            try {
                File[] appDirs = context.getExternalFilesDirs(null);
                if (appDirs != null) {
                    for (File appDir : appDirs) {
                        if (appDir == null) continue;
                        String path = appDir.getAbsolutePath();
                        int cut = path.indexOf("/Android/");
                        if (cut <= 0) continue;
                        String rootPath = path.substring(0, cut);
                        if (seen.contains(rootPath)) continue;
                        File root = new File(rootPath);
                        if (!root.isDirectory()) continue;
                        seen.add(rootPath);
                        roots.put(volume(rootPath, labelFor(rootPath), root.canRead()));
                    }
                }
            } catch (Throwable error) {
                android.util.Log.w("FoliaNativeStorage", "getExternalFilesDirs failed: " + error);
            }

            // 2) /storage 底下沒被上面抓到的（某些車機會直接掛在那裡）。
            collectVolumeRoots(new File("/storage"), roots, seen, 0);
            // 3) USB 實際的掛載點。
            collectVolumeRoots(new File("/mnt/media_rw"), roots, seen, 0);
            // 4) 有些 ROM 用 /udisk、/usb。
            collectVolumeRoots(new File("/mnt/usb"), roots, seen, 0);
            collectVolumeRoots(new File("/udisk"), roots, seen, 0);
        } catch (Throwable error) {
            android.util.Log.e("FoliaNativeStorage", "roots failed", error);
        }
        return roots.toString();
    }

    /** 只往下挖兩層找目錄，用來撿 /storage、/mnt/media_rw 這種容器底下的 volume。 */
    private void collectVolumeRoots(File container, JSONArray roots, List<String> seen, int depth) {
        if (depth > 1 || !container.isDirectory()) return;
        File[] children = container.listFiles();
        if (children == null) return;
        Arrays.sort(children);
        for (File child : children) {
            if (!child.isDirectory()) continue;
            String name = child.getName();
            if (name.equals("emulated") || name.equals("self")) {
                // emulated 是內建儲存（上面已經放過），但 /storage/emulated/0 也是合法路徑。
                continue;
            }
            String path = child.getAbsolutePath();
            File[] grandchildren = child.listFiles();
            if (grandchildren == null || grandchildren.length == 0) {
                // 空目錄多半是沒掛載的殘骸，但還是留著讓使用者自己點進去看看。
                if (!seen.contains(path)) {
                    seen.add(path);
                    try {
                        roots.put(volume(path, labelFor(path), child.canRead()));
                    } catch (Exception ignored) {
                    }
                }
                continue;
            }
            if (!seen.contains(path)) {
                seen.add(path);
                try {
                    roots.put(volume(path, labelFor(path), child.canRead()));
                } catch (Exception ignored) {
                }
            }
            collectVolumeRoots(child, roots, seen, depth + 1);
        }
    }

    private static String labelFor(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.isEmpty()) name = path;
        // UUID 樣式的卷宗（1234-ABCD）直接標成外接裝置，對使用者比較好認。
        if (name.matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) return "外接儲存（" + name + "）";
        if (name.equals("0") || name.equals("sdcard0")) return "內建儲存";
        return name;
    }

    /**
     * 給「找不到外接裝置」用的診斷：把這台車機實際的掛載狀況吐成 JSON。
     *
     * 車機 ROM 對 USB 的處理五花八門：有的掛在 /storage/<UUID>，有的只掛在 /mnt/media_rw，
     * 有的乾脆只讓自家的播放器看得到。與其猜，不如把 getExternalFilesDirs、/proc/mounts
     * 跟幾個常見掛載點的實際內容一起回報，貼回來就知道該往哪裡找。
     */
    @JavascriptInterface
    public String diagnose() {
        JSONObject json = new JSONObject();
        try {
            json.put("sdk", Build.VERSION.SDK_INT);
            json.put("manageExternalStorage", hasPermission());
            json.put("readPermission", context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
            json.put("externalStorageState", Environment.getExternalStorageState());
            json.put("port", port);
            json.put("pauseOnFocusLoss", pauseOnFocusLoss);
            json.put("mediaLog", mediaEventLog());

            JSONArray appDirs = new JSONArray();
            try {
                File[] dirs = context.getExternalFilesDirs(null);
                if (dirs != null) {
                    for (File dir : dirs) {
                        if (dir == null) continue;
                        JSONObject entry = new JSONObject();
                        entry.put("path", dir.getAbsolutePath());
                        entry.put("exists", dir.exists());
                        appDirs.put(entry);
                    }
                }
            } catch (Throwable error) {
                json.put("appDirsError", String.valueOf(error));
            }
            json.put("appDirs", appDirs);

            json.put("dirStorage", dirEntries(new File("/storage")));
            json.put("dirMediaRw", dirEntries(new File("/mnt/media_rw")));
            json.put("dirMntUser", dirEntries(new File("/mnt/user/0")));
            json.put("dirUdisk", dirEntries(new File("/udisk")));
            json.put("dirUsb", dirEntries(new File("/mnt/usb")));

            JSONArray mounts = new JSONArray();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/mounts"), "UTF-8"))) {
                String line;
                int guard = 0;
                while ((line = reader.readLine()) != null && guard++ < 4000) {
                    String[] parts = line.split(" ");
                    if (parts.length < 3) continue;
                    String mountPath = parts[1];
                    String fs = parts[2];
                    boolean interesting = mountPath.startsWith("/mnt/") || mountPath.startsWith("/storage/")
                            || fs.equals("vfat") || fs.equals("exfat") || fs.equals("ntfs") || fs.equals("fuse")
                            || fs.equals("fuseblk") || fs.equals("sdcardfs") || fs.equals("esdfs");
                    if (!interesting) continue;
                    JSONObject entry = new JSONObject();
                    entry.put("mount", mountPath);
                    entry.put("fs", fs);
                    entry.put("dev", parts[0]);
                    entry.put("readable", new File(mountPath).canRead());
                    mounts.put(entry);
                }
            } catch (Throwable error) {
                json.put("mountsError", String.valueOf(error));
            }
            json.put("mounts", mounts);
        } catch (Throwable error) {
            android.util.Log.e("FoliaNativeStorage", "diagnose failed", error);
        }
        return json.toString();
    }

    /** 列一個目錄底下的項目（名稱／canRead／canExecute／是否目錄），拿來看出權限卡在哪。 */
    private JSONArray dirEntries(File dir) {
        JSONArray array = new JSONArray();
        if (!dir.isDirectory()) return array;
        File[] children = dir.listFiles();
        if (children == null) {
            try {
                JSONObject error = new JSONObject();
                error.put("error", "listFiles returned null");
                error.put("canRead", dir.canRead());
                error.put("canExecute", dir.canExecute());
                array.put(error);
            } catch (Exception ignored) {
            }
            return array;
        }
        Arrays.sort(children);
        for (File child : children) {
            try {
                JSONObject entry = new JSONObject();
                entry.put("name", child.getName());
                entry.put("dir", child.isDirectory());
                entry.put("canRead", child.canRead());
                entry.put("canExecute", child.canExecute());
                array.put(entry);
            } catch (Exception ignored) {
            }
        }
        return array;
    }

    /** 單層列目錄：只回子目錄與音檔，夠 JS 做一個檔案瀏覽器。 */
    @JavascriptInterface
    public String list(String dirPath) {
        JSONObject result = new JSONObject();
        JSONArray dirs = new JSONArray();
        JSONArray files = new JSONArray();
        try {
            File dir = new File(dirPath);
            if (!dir.isDirectory() || !dir.canRead()) {
                result.put("error", "unreadable");
                result.put("dirs", dirs);
                result.put("files", files);
                return result.toString();
            }
            File[] children = dir.listFiles();
            if (children != null) {
                Arrays.sort(children);
                for (File child : children) {
                    String name = child.getName();
                    if (name.startsWith(".")) continue;
                    if (child.isDirectory()) {
                        JSONObject entry = new JSONObject();
                        entry.put("name", name);
                        entry.put("path", child.getAbsolutePath());
                        dirs.put(entry);
                    } else if (isAudio(name)) {
                        files.put(fileEntry(child));
                    }
                }
            }
            result.put("dirs", dirs);
            result.put("files", files);
        } catch (Throwable error) {
            android.util.Log.e("FoliaNativeStorage", "list failed", error);
            try {
                result.put("error", String.valueOf(error));
            } catch (Exception ignored) {
            }
        }
        return result.toString();
    }

    /** 同一個資料夾裡的字幕／歌詞檔（.lrc / .vtt）：JS 之後用 /f/ 網址自己抓內容、自己解碼。 */
    @JavascriptInterface
    public String listLyrics(String dirPath) {
        JSONArray files = new JSONArray();
        try {
            File dir = new File(dirPath);
            if (!dir.isDirectory() || !dir.canRead()) return files.toString();
            File[] children = dir.listFiles();
            if (children == null) return files.toString();
            Arrays.sort(children);
            for (File child : children) {
                String name = child.getName().toLowerCase(Locale.ROOT);
                if (!child.isFile()) continue;
                if (name.endsWith(".lrc") || name.endsWith(".vtt")) {
                    JSONObject entry = new JSONObject();
                    entry.put("name", child.getName());
                    entry.put("path", child.getAbsolutePath());
                    entry.put("size", child.length());
                    files.put(entry);
                }
            }
        } catch (Throwable error) {
            android.util.Log.w("FoliaNativeStorage", "listLyrics failed: " + error);
        }
        return files.toString();
    }

    /** 遞迴掃一個資料夾的音檔（原生端很快，2200 首通常幾秒）。 */
    @JavascriptInterface
    public String scan(String dirPath) {
        JSONArray files = new JSONArray();
        try {
            collect(new File(dirPath), files, 0);
        } catch (Throwable error) {
            android.util.Log.e("FoliaNativeStorage", "scan failed", error);
        }
        return files.toString();
    }

    /** 一首的標籤／時長／封面。封面會落到快取目錄，回傳給 JS 的是 http 可取的相對路徑。 */
    @JavascriptInterface
    public String probe(String filePath) {
        JSONObject json = new JSONObject();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            File file = new File(filePath);
            json.put("path", filePath);
            json.put("size", file.length());
            json.put("lastModified", file.lastModified());
            String name = file.getName();
            json.put("name", name);
            json.put("mime", mimeFor(name));

            retriever.setDataSource(filePath);
            putIfPresent(json, "title", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            putIfPresent(json, "artist", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            putIfPresent(json, "album", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            putIfPresent(json, "genre", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE));

            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration != null && !duration.isEmpty()) {
                try {
                    json.put("duration", Long.parseLong(duration));
                } catch (NumberFormatException ignored) {
                }
            }
            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (bitrate != null && !bitrate.isEmpty()) {
                try {
                    json.put("bitrate", Long.parseLong(bitrate));
                } catch (NumberFormatException ignored) {
                }
            }
            if (Build.VERSION.SDK_INT >= 21) {
                try {
                    String sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
                    if (sampleRate != null && !sampleRate.isEmpty()) {
                        json.put("sampleRate", Long.parseLong(sampleRate));
                    }
                } catch (Throwable ignored) {
                }
                try {
                    String bits = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE);
                    if (bits != null && !bits.isEmpty()) {
                        json.put("bitsPerSample", Long.parseLong(bits));
                    }
                } catch (Throwable ignored) {
                }
            }

            byte[] picture = retriever.getEmbeddedPicture();
            if (picture != null && picture.length > 0) {
                String coverPath = writeCover(filePath, picture);
                if (coverPath != null) {
                    json.put("coverPath", coverPath);
                    json.put("coverUrl", coverHttpPath(coverPath));
                }
            }
        } catch (Throwable error) {
            android.util.Log.e("FoliaNativeStorage", "probe failed for " + filePath, error);
            try {
                json.put("error", String.valueOf(error));
            } catch (Exception ignored) {
            }
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
        return json.toString();
    }

    // ---------------------------------------------------------------- 檔案與 JSON 小工具

    private void collect(File dir, JSONArray out, int depth) throws Exception {
        if (depth > MAX_SCAN_DEPTH || out.length() >= MAX_SCAN_FILES) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children);
        for (File child : children) {
            if (out.length() >= MAX_SCAN_FILES) return;
            String name = child.getName();
            if (name.startsWith(".")) continue;
            if (child.isDirectory()) {
                // Android/data、Android/obb 這些是別的 App 的私有資料，掃了只會被拒。
                if (name.equals("Android") && depth == 0) continue;
                collect(child, out, depth + 1);
            } else if (isAudio(name)) {
                out.put(fileEntry(child));
            }
        }
    }

    private JSONObject fileEntry(File file) throws Exception {
        JSONObject entry = new JSONObject();
        entry.put("name", file.getName());
        entry.put("path", file.getAbsolutePath());
        entry.put("size", file.length());
        entry.put("lastModified", file.lastModified());
        return entry;
    }

    private JSONObject volume(String path, String label, boolean readable) throws Exception {
        JSONObject object = new JSONObject();
        object.put("name", label);
        object.put("path", path);
        object.put("readable", readable);
        return object;
    }

    private static boolean isAudio(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : AUDIO_EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    private static String mimeFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".m4a") || lower.endsWith(".alac") || lower.endsWith(".mp4")) return "audio/mp4";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".opus")) return "audio/opus";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".wma")) return "audio/x-ms-wma";
        if (lower.endsWith(".aif") || lower.endsWith(".aiff")) return "audio/aiff";
        if (lower.endsWith(".dsf")) return "application/octet-stream";
        if (lower.endsWith(".dff")) return "application/octet-stream";
        if (lower.endsWith(".ape")) return "application/octet-stream";
        if (lower.endsWith(".wv")) return "application/octet-stream";
        if (lower.endsWith(".tta")) return "application/octet-stream";
        if (lower.endsWith(".tak")) return "application/octet-stream";
        if (lower.endsWith(".mka")) return "audio/x-matroska";
        // 其餘未知格式不要冒充 audio/mpeg：Chromium 會先當成 MP3 慢慢試，延遲才報錯；
        // 給 octet-stream 它會立刻回「不支援」，v0.9.8 的原生轉碼才來得及接手。
        return "application/octet-stream";
    }

    private static void putIfPresent(JSONObject json, String key, String value) throws Exception {
        if (value != null && !value.trim().isEmpty()) json.put(key, value.trim());
    }

    private String writeCover(String filePath, byte[] picture) {
        try {
            File dir = new File(context.getCacheDir(), "linked-covers");
            if (!dir.exists() && !dir.mkdirs()) return null;
            boolean png = picture.length > 8
                    && (picture[0] & 0xFF) == 0x89
                    && (picture[1] & 0xFF) == 0x50
                    && (picture[2] & 0xFF) == 0x4E
                    && (picture[3] & 0xFF) == 0x47;
            File target = new File(dir, sha1(filePath) + (png ? ".png" : ".jpg"));
            if (target.exists() && target.length() == picture.length) return target.getAbsolutePath();
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(picture);
            }
            return target.getAbsolutePath();
        } catch (Throwable error) {
            android.util.Log.e("FoliaNativeStorage", "writeCover failed", error);
            return null;
        }
    }

    private static String sha1(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hash = digest.digest(value.getBytes("UTF-8"));
        StringBuilder builder = new StringBuilder();
        for (byte b : hash) builder.append(String.format("%02x", b));
        return builder.toString();
    }

    private static String coverHttpPath(String coverPath) {
        return "/c/" + Base64Url.encode(coverPath);
    }

    // ---------------------------------------------------------------- 不支援的格式：原生解成 WAV

    /**
     * 為什麼要有這段：WebView 的解碼器只認 mp3 / aac / flac / vorbis / opus / wav 這一組，
     * ALAC 的 m4a、APE、WMA、DSF／DFF、TTA 一律 decode error（有些甚至因為我們把未知格式的
     * Content-Type 當成 audio/mpeg，還沒進解碼器就失敗）。車機的系統解碼器（Aurora 用的那顆）
     * 反而解得開，於是：
     *
     *   1. 頁面的 <audio> 報 decode / unsupported 時，請原生用 MediaExtractor + MediaCodec 解碼，
     *      寫成 16-bit PCM 立體聲 WAV 放進 App 快取；
     *   2. 回一個 http://127.0.0.1:<port>/f/<base64> 的網址，頁面把 src 換過去就好。
     *
     * 這樣播放器、歌詞、頻譜、方控全部照舊，只有「這一首的音檔來源」換成了 WAV。
     */
    private File wavCacheDir() {
        File dir = new File(context.getCacheDir(), "folia-wav");
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.w("FoliaNativeStorage", "cannot create wav cache dir");
        }
        return dir;
    }

    @JavascriptInterface
    public boolean canTranscode() {
        return Build.VERSION.SDK_INT >= 16;
    }

    /** 請原生把這個檔案解成 WAV；立刻回傳 job 狀態，解碼在背景執行緒跑。 */
    @JavascriptInterface
    public String startWavTranscode(String filePath) {
        JSONObject result = new JSONObject();
        try {
            File source = new File(filePath);
            if (!source.isFile() || !source.canRead()) {
                result.put("state", "failed");
                result.put("error", "file not readable");
                return result.toString();
            }
            String key = sha1(filePath + "|" + source.length() + "|" + source.lastModified());
            File target = new File(wavCacheDir(), key + ".wav");
            String url = "http://127.0.0.1:" + port + "/f/" + Base64Url.encode(target.getAbsolutePath());
            if (target.isFile() && target.length() > 44) {
                result.put("state", "done");
                result.put("url", url);
                return result.toString();
            }
            if (transcodeJobs.containsKey(key)) {
                String running = transcodeJobs.get(key);
                if (running != null) return running;
            }
            result.put("state", "running");
            result.put("jobId", key);
            result.put("progress", 0);
            transcodeJobs.put(key, result.toString());
            final File src = source;
            final File out = target;
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    runWavTranscode(key, src, out);
                }
            });
            worker.setDaemon(true);
            worker.start();
            return result.toString();
        } catch (Throwable error) {
            android.util.Log.w("FoliaNativeStorage", "startWavTranscode failed: " + error);
            try {
                result.put("state", "failed");
                result.put("error", String.valueOf(error));
            } catch (Exception ignored) {
                // 到這裡已經是錯誤路徑，回甚麼都不影響呼叫端。
            }
            return result.toString();
        }
    }

    /** 輪詢：{"state":"running|done|failed","progress":0-100,"url":...,"error":...} */
    @JavascriptInterface
    public String wavTranscodeStatus(String jobId) {
        String raw = transcodeJobs.get(jobId);
        if (raw != null) return raw;
        JSONObject missing = new JSONObject();
        try {
            missing.put("state", "missing");
        } catch (Exception ignored) {
            // 不會發生。
        }
        return missing.toString();
    }

    private void publishJob(String jobId, String state, int progress, String url, String error) {
        JSONObject json = new JSONObject();
        try {
            json.put("state", state);
            json.put("progress", progress);
            if (url != null) json.put("url", url);
            if (error != null) json.put("error", error);
        } catch (Exception ignored) {
            // 同上，JSONObject 的 put 不會失敗。
        }
        transcodeJobs.put(jobId, json.toString());
    }

    /**
     * 實際解碼：MediaExtractor 抽音軌 → MediaCodec 解 → 一律寫成 16-bit 立體聲 WAV。
     * 單聲道複製成左右兩軌，5.1 取前兩軌（FL/FR），這樣 Chromium 一定吃得下。
     */
    private void runWavTranscode(String jobId, File source, File target) {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        RandomAccessFile raf = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(source.getAbsolutePath());
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = candidate;
                    break;
                }
            }
            if (trackIndex < 0 || format == null) throw new IOException("no audio track");
            extractor.selectTrack(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int sourceChannels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            boolean pcmFloat = false;

            raf = new RandomAccessFile(target, "rw");
            raf.setLength(0);
            raf.write(new byte[44]);
            long dataBytes = 0;
            int lastProgress = -1;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        java.nio.ByteBuffer input = codec.getInputBuffer(inIndex);
                        if (input == null) throw new IOException("no input buffer");
                        input.clear();
                        int sampleSize = extractor.readSampleData(input, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        sourceChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4) {
                        pcmFloat = true;
                    }
                } else if (outIndex >= 0) {
                    java.nio.ByteBuffer output = codec.getOutputBuffer(outIndex);
                    if (output != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        byte[] stereo = pcmFloat
                                ? toStereo16Float(output, sourceChannels)
                                : toStereo16(output, sourceChannels);
                        raf.write(stereo);
                        dataBytes += stereo.length;
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // 舊 API 的一次性通知，緩衝區照 getOutputBuffer 拿，不必處理。
                }

                int progress = durationUs > 0
                        ? (int) Math.min(99, Math.max(0, info.presentationTimeUs * 100 / durationUs))
                        : (dataBytes > 0 ? Math.min(99, (int) (dataBytes / (1024 * 1024))) : 0);
                if (progress != lastProgress) {
                    lastProgress = progress;
                    publishJob(jobId, "running", progress, null, null);
                }
            }

            // 收尾：把 44 bytes 的 WAV 表頭補回去（長度要在解完才知道）。
            byte[] header = wavHeader(sampleRate, 2, dataBytes);
            raf.seek(0);
            raf.write(header);
            raf.close();
            raf = null;

            publishJob(jobId, "done", 100,
                    "http://127.0.0.1:" + port + "/f/" + Base64Url.encode(target.getAbsolutePath()), null);
            cleanupWavCache(target);
        } catch (Throwable error) {
            android.util.Log.w("FoliaNativeStorage", "wav transcode failed: " + error);
            try {
                if (raf != null) raf.close();
            } catch (Throwable ignored) {
                // 已經在錯誤路徑。
            }
            // 失敗的殘檔要刪掉，否則下次 startWavTranscode 會看到「檔案存在」直接回 done。
            try {
                if (target.isFile()) target.delete();
            } catch (Throwable ignored) {
                // 刪不掉就算了，下一次會用新的 key。
            }
            publishJob(jobId, "failed", 0, null, String.valueOf(error));
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Throwable ignored) {
                // 收尾失敗不影響結果。
            }
            try {
                if (extractor != null) extractor.release();
            } catch (Throwable ignored) {
                // 同上。
            }
        }
    }

    /** 16-bit PCM：取第 0／1 軌當左右（單聲道複製一份）。 */
    private static byte[] toStereo16(java.nio.ByteBuffer buffer, int channels) {
        java.nio.ShortBuffer shorts = buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int frames = Math.max(0, shorts.remaining() / Math.max(1, channels));
        byte[] out = new byte[frames * 4];
        int write = 0;
        for (int frame = 0; frame < frames; frame++) {
            short left = shorts.get(frame * channels);
            short right = channels >= 2 ? shorts.get(frame * channels + 1) : left;
            out[write++] = (byte) (left & 0xFF);
            out[write++] = (byte) ((left >> 8) & 0xFF);
            out[write++] = (byte) (right & 0xFF);
            out[write++] = (byte) ((right >> 8) & 0xFF);
        }
        return out;
    }

    /** 少數解碼器輸出 float PCM（-1..1），一樣轉成 16-bit。 */
    private static byte[] toStereo16Float(java.nio.ByteBuffer buffer, int channels) {
        java.nio.FloatBuffer floats = buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        int frames = Math.max(0, floats.remaining() / Math.max(1, channels));
        byte[] out = new byte[frames * 4];
        int write = 0;
        for (int frame = 0; frame < frames; frame++) {
            float left = floats.get(frame * channels);
            float right = channels >= 2 ? floats.get(frame * channels + 1) : left;
            out[write++] = (byte) (Math.round(clamp16(left)) & 0xFF);
            out[write++] = (byte) ((Math.round(clamp16(left)) >> 8) & 0xFF);
            out[write++] = (byte) (Math.round(clamp16(right)) & 0xFF);
            out[write++] = (byte) ((Math.round(clamp16(right)) >> 8) & 0xFF);
        }
        return out;
    }

    private static float clamp16(float value) {
        float scaled = value * 32767f;
        if (scaled > 32767f) return 32767f;
        if (scaled < -32768f) return -32768f;
        return scaled;
    }

    private static byte[] wavHeader(int sampleRate, int channels, long dataBytes) {
        long byteRate = (long) sampleRate * channels * 2L;
        byte[] header = new byte[44];
        putAscii(header, 0, "RIFF");
        putIntLe(header, 4, 36 + dataBytes);
        putAscii(header, 8, "WAVEfmt ");
        putIntLe(header, 16, 16);
        putShortLe(header, 20, 1);
        putShortLe(header, 22, channels);
        putIntLe(header, 24, sampleRate);
        putIntLe(header, 28, byteRate);
        putShortLe(header, 32, channels * 2);
        putShortLe(header, 34, 16);
        putAscii(header, 36, "data");
        putIntLe(header, 40, dataBytes);
        return header;
    }

    private static void putAscii(byte[] target, int offset, String text) {
        for (int i = 0; i < text.length(); i++) target[offset + i] = (byte) text.charAt(i);
    }

    private static void putIntLe(byte[] target, int offset, long value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void putShortLe(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    /** 快取只留最近兩週的 WAV；一首五分鐘的歌大約 50 MB，不清會把車機的可用空間吃光。 */
    private void cleanupWavCache(File keep) {
        try {
            File dir = wavCacheDir();
            long cutoff = System.currentTimeMillis() - 14L * 24 * 3600 * 1000;
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File file : files) {
                if (file.equals(keep)) continue;
                if (file.getName().endsWith(".wav") && file.lastModified() < cutoff) file.delete();
            }
        } catch (Throwable error) {
            android.util.Log.w("FoliaNativeStorage", "cleanupWavCache failed: " + error);
        }
    }

    // ---------------------------------------------------------------- 本機 HTTP（Range 串流）

    private void startServer() throws IOException {
        if (serverSocket != null) return;
        serverSocket = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
        port = serverSocket.getLocalPort();
        Thread accept = new Thread(() -> {
            while (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    final Socket socket = serverSocket.accept();
                    Thread worker = new Thread(() -> handle(socket));
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException closed) {
                    break;
                }
            }
        });
        accept.setDaemon(true);
        accept.start();
    }

    private void handle(Socket socket) {
        try (Socket open = socket) {
            open.setSoTimeout(15000);
            InputStream rawIn = new BufferedInputStream(open.getInputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(rawIn, "UTF-8"));

            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String rangeHeader = null;
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon > 0) {
                    String key = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    if (key.equals("range")) rangeHeader = header.substring(colon + 1).trim();
                }
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];
            OutputStream out = open.getOutputStream();

            if ("OPTIONS".equalsIgnoreCase(method)) {
                writeCors(out);
                out.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".getBytes("UTF-8"));
                return;
            }

            String decodeTarget = target;
            int question = decodeTarget.indexOf('?');
            if (question >= 0) decodeTarget = decodeTarget.substring(0, question);

            boolean isCover = decodeTarget.startsWith("/c/");
            boolean isFile = decodeTarget.startsWith("/f/");
            if (!isCover && !isFile) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n".getBytes("UTF-8"));
                return;
            }

            String encoded = decodeTarget.substring(3);
            String decoded;
            try {
                decoded = Base64Url.decode(encoded);
            } catch (Throwable error) {
                out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes("UTF-8"));
                return;
            }

            File file = new File(decoded);
            if (!file.isFile() || !file.canRead()) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes("UTF-8"));
                return;
            }

            long total = file.length();
            String contentType = isCover
                    ? (decoded.toLowerCase(Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg")
                    : mimeFor(decoded);

            long start = 0;
            long end = total - 1;
            boolean partial = false;
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String spec = rangeHeader.substring(6).trim();
                int dash = spec.indexOf('-');
                if (dash >= 0) {
                    String from = spec.substring(0, dash).trim();
                    String to = spec.substring(dash + 1).trim();
                    try {
                        if (from.isEmpty()) {
                            long suffix = Long.parseLong(to);
                            start = Math.max(0, total - suffix);
                        } else {
                            start = Long.parseLong(from);
                            if (!to.isEmpty()) end = Math.min(total - 1, Long.parseLong(to));
                        }
                        partial = start >= 0 && start < total && end >= start;
                    } catch (NumberFormatException ignored) {
                        partial = false;
                    }
                }
            }

            if (!partial) {
                start = 0;
                end = total - 1;
            }
            long length = end - start + 1;

            StringBuilder head = new StringBuilder();
            head.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            head.append("Content-Type: ").append(contentType).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            head.append("Access-Control-Allow-Origin: *\r\n");
            head.append("Access-Control-Allow-Headers: Range\r\n");
            head.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n");
            head.append("Cache-Control: no-store\r\n");
            if (partial) {
                head.append("Content-Range: bytes ").append(start).append('-').append(end)
                        .append('/').append(total).append("\r\n");
            }
            head.append("Content-Length: ").append(length).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes("UTF-8"));
            out.flush();

            if ("HEAD".equalsIgnoreCase(method) || length <= 0) {
                return;
            }

            try (RandomAccessFile access = new RandomAccessFile(file, "r")) {
                access.seek(start);
                byte[] buffer = new byte[64 * 1024];
                long remaining = length;
                while (remaining > 0) {
                    int read = access.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read <= 0) break;
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
                out.flush();
            }
        } catch (Throwable error) {
            android.util.Log.w("FoliaNativeStorage", "serve failed: " + error);
        }
    }

    private static void writeCors(OutputStream out) throws IOException {
        out.write(("Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Range\r\n"
                + "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n").getBytes("UTF-8"));
    }

    /** URL-safe base64（無 padding）：路徑用一般 base64 遇到 '/' 會跟網址路徑打架。 */
    private static class Base64Url {
        private static final String ALPHABET =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

        static String encode(String value) {
            try {
                byte[] bytes = value.getBytes("UTF-8");
                StringBuilder builder = new StringBuilder();
                for (int index = 0; index < bytes.length; index += 3) {
                    int b0 = bytes[index] & 0xFF;
                    int b1 = index + 1 < bytes.length ? bytes[index + 1] & 0xFF : 0;
                    int b2 = index + 2 < bytes.length ? bytes[index + 2] & 0xFF : 0;
                    int triple = (b0 << 16) | (b1 << 8) | b2;
                    builder.append(ALPHABET.charAt((triple >> 18) & 0x3F));
                    builder.append(ALPHABET.charAt((triple >> 12) & 0x3F));
                    if (index + 1 < bytes.length) builder.append(ALPHABET.charAt((triple >> 6) & 0x3F));
                    if (index + 2 < bytes.length) builder.append(ALPHABET.charAt(triple & 0x3F));
                }
                return builder.toString();
            } catch (Throwable error) {
                return "";
            }
        }

        static String decode(String value) throws Exception {
            byte[] out = new byte[value.length() * 2];
            int position = 0;
            int bits = 0;
            int accumulator = 0;
            for (char character : value.toCharArray()) {
                int index = ALPHABET.indexOf(character);
                if (index < 0) throw new IllegalArgumentException("bad base64");
                accumulator = (accumulator << 6) | index;
                bits += 6;
                if (bits >= 8) {
                    bits -= 8;
                    out[position++] = (byte) ((accumulator >> bits) & 0xFF);
                }
            }
            byte[] trimmed = new byte[position];
            System.arraycopy(out, 0, trimmed, 0, position);
            return new String(trimmed, "UTF-8");
        }
    }
}
