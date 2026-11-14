package com.yili.podcast;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final String TAG = "MyPodcast";
    private static final int REQ_FILE = 1001;
    private static final int REQ_PERM = 1002;

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private MediaRecorder recorder;
    private File recFile;
    private boolean recording = false;

    /** 从微信等外部应用「用其他应用打开 / 分享」进来的音频，拷到这里等 JS 取 */
    private File importsDir;
    private final List<String> pendingImports = Collections.synchronizedList(new ArrayList<String>());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        web.addJavascriptInterface(new Bridge(), "Native");

        importsDir = new File(getFilesDir(), "imports");
        if (!importsDir.exists()) importsDir.mkdirs();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                WebResourceResponse r = intercept(req.getUrl());
                return r != null ? r : super.shouldInterceptRequest(view, req);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        request.grant(request.getResources());
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                filePathCallback = cb;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择音频文件"), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, cm.message() + " @" + cm.lineNumber());
                return true;
            }
        });

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /* ---------- 外部应用打开/分享进来的音频 ---------- */

    private void handleIntent(Intent it) {
        if (it == null) return;
        Uri uri = it.getData();
        if (uri == null && Intent.ACTION_SEND.equals(it.getAction())) {
            Object o = it.getParcelableExtra(Intent.EXTRA_STREAM);
            if (o instanceof Uri) uri = (Uri) o;
        }
        if (uri == null) return;
        importUri(uri);
    }

    private void importUri(final Uri uri) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String name = null;
                try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (i >= 0) name = c.getString(i);
                    }
                } catch (Exception ignored) {
                }
                String mime = null;
                try { mime = getContentResolver().getType(uri); } catch (Exception ignored) {}

                if (name == null || name.trim().isEmpty()) {
                    name = "import_" + System.currentTimeMillis() + extFor(mime);
                } else {
                    name = sanitizeName(name);
                    if (!name.matches("(?i).*\\.[a-z0-9]{2,5}$")) name = name + extFor(mime);
                }

                File out = new File(importsDir, name);
                if (out.exists()) out = new File(importsDir, System.currentTimeMillis() + "_" + name);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream fo = new FileOutputStream(out)) {
                    if (in == null) throw new Exception("无法读取文件");
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                } catch (Exception e) {
                    final String msg = "导入失败：" + (e.getMessage() == null ? "读不到文件" : e.getMessage());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
                    });
                    return;
                }
                pendingImports.add(out.getName());
                notifyJs();
            }
        }).start();
    }

    private void notifyJs() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) {
                    web.evaluateJavascript("window.__nativeImportReady&&window.__nativeImportReady()", null);
                }
            }
        });
    }

    /** 只放行 https://local.podcast/imports/<文件名>，从私有目录把音频喂给 WebView */
    private WebResourceResponse intercept(Uri u) {
        if (u == null || !"local.podcast".equals(u.getHost())) return null;
        String p = u.getPath();
        if (p == null || !p.startsWith("/imports/")) return null;
        String seg = u.getLastPathSegment();
        if (seg == null || seg.contains("..") || seg.contains("/") || seg.contains("\\")) return null;
        File f = new File(importsDir, seg);
        if (!f.exists() || !f.isFile()) return null;
        Map<String, String> h = new HashMap<String, String>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");
        try {
            return new WebResourceResponse(mimeOf(seg), null, 200, "OK", h, new FileInputStream(f));
        } catch (Exception e) {
            return null;
        }
    }

    private static String sanitizeName(String name) {
        String n = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (n.length() > 100) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 ? n.substring(dot) : "";
            n = n.substring(0, Math.min(90, dot > 0 ? dot : n.length())) + ext;
        }
        return n.isEmpty() ? "import" : n;
    }

    private static String extFor(String mime) {
        if (mime == null) return ".mp3";
        if (mime.contains("mpeg") || mime.contains("mp3")) return ".mp3";
        if (mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac")) return ".m4a";
        if (mime.contains("wav")) return ".wav";
        if (mime.contains("ogg")) return ".ogg";
        if (mime.contains("flac")) return ".flac";
        return ".mp3";
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a") || n.endsWith(".mp4") || n.endsWith(".aac")) return "audio/mp4";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".ogg") || n.endsWith(".oga")) return "audio/ogg";
        if (n.endsWith(".flac")) return "audio/flac";
        return "application/octet-stream";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (recorder != null) {
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }

    /** 在 UI 线程执行并把结果带回来（MediaRecorder 在主线程用最稳） */
    private String onMain(Callable<String> task) {
        final String[] out = new String[]{"err:timeout"};
        final CountDownLatch latch = new CountDownLatch(1);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    out[0] = task.call();
                } catch (Throwable t) {
                    out[0] = "err:" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return out[0];
    }

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    public class Bridge {

        @JavascriptInterface
        public String startRecord() {
            if (Build.VERSION.SDK_INT >= 23 && !hasAudioPermission()) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM);
                    }
                });
                return "err:需要麦克风权限，请在弹窗里点允许，然后再点一次录音";
            }
            if (recording) return "err:正在录音";

            return onMain(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    File dir = getCacheDir();
                    recFile = new File(dir, "rec_" + System.currentTimeMillis() + ".m4a");
                    MediaRecorder r = new MediaRecorder();
                    r.setAudioSource(MediaRecorder.AudioSource.MIC);
                    r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    r.setAudioSamplingRate(44100);
                    r.setAudioChannels(1);
                    r.setAudioEncodingBitRate(64000);
                    r.setOutputFile(recFile.getAbsolutePath());
                    r.prepare();
                    r.start();
                    recorder = r;
                    recording = true;
                    return "ok";
                }
            });
        }

        @JavascriptInterface
        public String stopRecord() {
            if (!recording || recorder == null) return "err:当前没有在录音";

            String r = onMain(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    MediaRecorder r = recorder;
                    recorder = null;
                    recording = false;
                    boolean ok = true;
                    try {
                        r.stop();
                    } catch (RuntimeException e) {
                        // 录得太短（<1s）会抛这个，文件是坏的
                        ok = false;
                    }
                    try { r.release(); } catch (Exception ignored) {}
                    if (!ok || recFile == null || !recFile.exists() || recFile.length() < 1000) {
                        return "err:录音太短，请至少说 2 秒";
                    }
                    byte[] bytes = readFile(recFile);
                    String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    return "data:audio/mp4;base64," + b64;
                }
            });
            if (recFile != null && recFile.exists()) {
                // 保留文件给转写用，30 分钟后再清
                final File f = recFile;
                new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try { f.delete(); } catch (Exception ignored) {}
                    }
                }, 30 * 60 * 1000);
            }
            return r;
        }

        /** 把最后一次录音的原始字节交回去（转写上传用，避免超大 base64 走 JS 桥） */
        @JavascriptInterface
        public String lastRecordBase64() {
            if (recFile == null || !recFile.exists()) return "err:没有录音文件";
            try {
                return "data:audio/mp4;base64," + Base64.encodeToString(readFile(recFile), Base64.NO_WRAP);
            } catch (Exception e) {
                return "err:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public boolean isRecording() {
            return recording;
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** 待导入文件名，一行一个（从微信「用其他应用打开」进来的） */
        @JavascriptInterface
        public String getPendingImports() {
            synchronized (pendingImports) {
                StringBuilder sb = new StringBuilder();
                for (String n : pendingImports) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(n);
                }
                return sb.toString();
            }
        }

        @JavascriptInterface
        public String importBaseUrl() {
            return "https://local.podcast/imports/";
        }

        @JavascriptInterface
        public void deleteImport(String name) {
            if (name == null) return;
            pendingImports.remove(name);
            try {
                File f = new File(importsDir, name);
                if (f.getParentFile().getCanonicalPath().equals(importsDir.getCanonicalPath())) f.delete();
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public String version() {
            return "1.2";
        }
    }

    private static byte[] readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toByteArray();
    }
}
