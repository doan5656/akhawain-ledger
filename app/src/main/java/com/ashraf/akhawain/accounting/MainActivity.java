package com.ashraf.akhawain.accounting;

import android.app.Activity;
import android.app.PrintManager;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 4102;
    private static final int CREATE_DOCUMENT_REQUEST = 4103;
    private static final int BAR_COLOR = Color.rgb(163, 47, 39);

    private WebView webView;
    private WebView printView; // نحتفظ بمرجع حتى لا يجمعه Garbage Collector قبل انتهاء التحميل
    private ValueCallback<Uri[]> uploadCallback;
    private byte[] pendingFileBytes;
    private String pendingFileName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BAR_COLOR);
        getWindow().setNavigationBarColor(BAR_COLOR);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarDividerColor(BAR_COLOR);
        }

        // الحاوية تلوّن مناطق شريط الحالة والتنقل، وتعوّض الحواف في أندرويد 15+ حيث الشاشة الكاملة إلزامية
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BAR_COLOR);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 35) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            }
            return insets;
        });

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(255, 248, 245));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // التطبيق يعمل من assets فقط؛ منع الوصول لملفات الجهاز (لا يؤثر على file:///android_asset)
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        webView.addJavascriptInterface(new AppBridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // التطبيق يعمل دون إنترنت؛ لا ننتقل خارج الصفحة المضمّنة
                String scheme = request.getUrl().getScheme();
                return !"file".equals(scheme) && !"about".equals(scheme);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // إذا انهارت عملية العرض نعيد تشغيل الشاشة بدل إغلاق التطبيق (البيانات محفوظة في التخزين)
                runOnUiThread(() -> recreate());
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (uploadCallback != null) uploadCallback.onReceiveValue(null);
                uploadCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    uploadCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private static String safeName(String filename) {
        String name = filename == null ? "" : filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        return name.isEmpty() ? "file" : name;
    }

    /** يُبلغ الواجهة بنتيجة الحفظ: ok / cancel / fail */
    private void notifySaved(String status, String name) {
        if (webView == null) return;
        webView.evaluateJavascript("window.__fileSaved&&window.__fileSaved('" + status + "','" + name + "')", null);
    }

    /** حل احتياطي إذا لم يتوفر منتقي الملفات: الحفظ في مجلد التنزيلات */
    private boolean saveToDownloads(String name, String mime, byte[] data) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) return false;
                    out.write(data);
                }
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) return false;
                try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                    out.write(data);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private class AppBridge {
        /** يحفظ ملفاً (نسخة احتياطية JSON أو تصدير CSV) في مكان يختاره المستخدم */
        @JavascriptInterface
        public void saveFile(String filename, String mime, String base64) {
            final String name = safeName(filename);
            final String type = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
            byte[] decoded = null;
            try {
                decoded = Base64.decode(base64, Base64.DEFAULT);
            } catch (Exception e) {
                decoded = null;
            }
            final byte[] data = decoded;
            runOnUiThread(() -> {
                if (data == null) {
                    notifySaved("fail", name);
                    return;
                }
                pendingFileBytes = data;
                pendingFileName = name;
                try {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(type);
                    intent.putExtra(Intent.EXTRA_TITLE, name);
                    startActivityForResult(intent, CREATE_DOCUMENT_REQUEST);
                } catch (Exception e) {
                    pendingFileBytes = null;
                    pendingFileName = null;
                    notifySaved(saveToDownloads(name, type, data) ? "ok" : "fail", name);
                }
            });
        }

        /** للتوافق مع الإصدارات السابقة من الواجهة */
        @JavascriptInterface
        public void saveBackup(String filename, String base64) {
            saveFile(filename, "application/json", base64);
        }

        @JavascriptInterface
        public void shareText(String title, String text) {
            runOnUiThread(() -> {
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, title);
                    send.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(send, title));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تعذرت المشاركة", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void dial(String number) {
            final String n = number == null ? "" : number.replaceAll("[^0-9+]", "");
            if (n.isEmpty()) return;
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + n)));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تعذر فتح الاتصال", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void printHtml(String html) {
            runOnUiThread(() -> {
                try {
                    if (printView != null) {
                        printView.destroy();
                    }
                    printView = new WebView(MainActivity.this);
                    final boolean[] started = {false};
                    printView.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            if (started[0]) return;
                            started[0] = true;
                            PrintManager manager = (PrintManager) getSystemService(PRINT_SERVICE);
                            PrintDocumentAdapter adapter = view.createPrintDocumentAdapter("Statement-Bofiya-AlAkhawain");
                            PrintAttributes attrs = new PrintAttributes.Builder()
                                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                    .build();
                            manager.print("كشف حساب - بوفية الأخوين", adapter, attrs);
                        }
                    });
                    printView.loadDataWithBaseURL(null,
                            "<html dir='rtl'><head><meta charset='utf-8'><style>"
                                    + "body{font-family:sans-serif;padding:18px;color:#30201d}"
                                    + "h2,h3{margin:6px 0}"
                                    + "table{width:100%;border-collapse:collapse;margin:10px 0}"
                                    + "td,th{text-align:right;border-bottom:1px solid #ddd;padding:6px;font-size:11px}"
                                    + "th{background:#f6ece8}"
                                    + "</style></head><body>" + html + "</body></html>",
                            "text/html", "UTF-8", null);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تعذرت تهيئة الطباعة", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (uploadCallback != null) {
                uploadCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                uploadCallback = null;
            }
        } else if (requestCode == CREATE_DOCUMENT_REQUEST) {
            final byte[] bytes = pendingFileBytes;
            final String name = pendingFileName == null ? "file" : pendingFileName;
            pendingFileBytes = null;
            pendingFileName = null;
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                notifySaved("cancel", name);
                return;
            }
            boolean saved = false;
            if (bytes != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    if (out != null) {
                        out.write(bytes);
                        out.flush();
                        saved = true;
                    }
                } catch (Exception e) {
                    saved = false;
                }
            }
            notifySaved(saved ? "ok" : "fail", name);
        }
    }

    /** زر الرجوع: تتولاه الواجهة أولاً (إغلاق نافذة ← رجوع صفحة)، ثم نخرج من التطبيق فقط عند الصفحة الرئيسية */
    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        webView.evaluateJavascript(
                "(function(){try{return window.__handleBack?window.__handleBack():false}catch(e){return false}})()",
                value -> {
                    if (!"true".equals(value)) {
                        exitApp();
                    }
                });
    }

    private void exitApp() {
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (printView != null) {
            printView.destroy();
            printView = null;
        }
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
