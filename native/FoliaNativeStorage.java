package top.izuna.foliamajor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

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
            ".ape", ".wv", ".tta", ".wma", ".aif", ".aiff", ".caf", ".dsf", ".dff"
    };
    private static final int MAX_SCAN_FILES = 20000;
    private static final int MAX_SCAN_DEPTH = 12;

    private final Activity activity;
    private final Context context;
    private ServerSocket serverSocket;
    private int port;

    private FoliaNativeStorage(Activity activity) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
    }

    /** MainActivity 的 onCreate 最後呼叫；WebView 這時已經由 Bridge 建好。 */
    public static void attach(BridgeActivity host) {
        try {
            FoliaNativeStorage storage = new FoliaNativeStorage(host);
            storage.startServer();
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
        if (lower.endsWith(".m4a") || lower.endsWith(".alac")) return "audio/mp4";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".opus")) return "audio/opus";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".wma")) return "audio/x-ms-wma";
        if (lower.endsWith(".aif") || lower.endsWith(".aiff")) return "audio/aiff";
        return "audio/mpeg";
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
