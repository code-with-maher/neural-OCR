package com.a.labs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends Activity {

    // بيانات الاعتماد
    private final String ELEVEN_API_KEY = "sk_5f9e74d63df84ec66189b43d7a67f6f98c0bab5aa822cada";
    private final String ELEVEN_VOICE_ID = "GHszn56Ads7pHU1bODA2";
    private final String GEMINI_API_KEY = "AIzaSyBT8g0vnV673C5sCAvWWnRroHS3eQN_9iQ";
    
    // القائمة المفلترة كما طلبت يا ماهر
    private final String[] GEMINI_MODELS = {
            "gemini-3.1-flash-lite-preview", 
            "gemini-3-flash-preview", 
            "gemini-2.5-flash"
    };

    private LinearLayout mainLayout, readingLayout, rawLogContainer;
    private Button btnPickFile, btnGenJson, btnPrev, btnNext, btnPlay, btnToggleLogs, btnCopyPage, btnCopyBook;
    private TextView statusText, rawResponseDisplay, pageIndicator;
    private ProgressBar progressBar;
    private Spinner modelSpinner, historySpinner;
    private ArrayAdapter<String> historyAdapter;

    private OkHttpClient client;
    private MediaPlayer mediaPlayer;
    private List<PageData> bookPages = new ArrayList<>();
    private int currentPageIndex = 0;
    private SharedPreferences prefs;
    private List<String> historyNames = new ArrayList<>();
    private List<String> historyUris = new ArrayList<>();

    private static class PageData { int pageNumber; String content; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("OCR_PERMANENT_STORAGE_V1", Context.MODE_PRIVATE);
        client = new OkHttpClient.Builder()
                .connectTimeout(180, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS).build();

        buildUI();
        setContentView(mainLayout);
        loadHistory();
    }

    private void buildUI() {
        mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setBackgroundColor(Color.parseColor("#F4F7F9"));

        // لوحة التحكم العلوية
        LinearLayout controlPanel = new LinearLayout(this);
        controlPanel.setOrientation(LinearLayout.VERTICAL);
        controlPanel.setPadding(30, 30, 30, 30);
        controlPanel.setBackground(getCardDrawable("#FFFFFF"));
        LinearLayout.LayoutParams cpParams = new LinearLayout.LayoutParams(-1, -2);
        cpParams.setMargins(20, 20, 20, 20);
        controlPanel.setLayoutParams(cpParams);

        addLabel(controlPanel, "🤖 اختر الموديل:");
        modelSpinner = new Spinner(this);
        modelSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, GEMINI_MODELS));
        controlPanel.addView(modelSpinner);

        addLabel(controlPanel, "📚 المستندات المحفوظة:");
        historySpinner = new Spinner(this);
        historyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, historyNames);
        historySpinner.setAdapter(historyAdapter);
        controlPanel.addView(historySpinner);

        LinearLayout actionRow = new LinearLayout(this);
        btnPickFile = createStyledButton("رفع ملف 📤", "#3498DB");
        btnGenJson = createStyledButton("استخراج وترجمة 🚀", "#E91E63");
        actionRow.addView(btnPickFile); actionRow.addView(btnGenJson);
        controlPanel.addView(actionRow);
        mainLayout.addView(controlPanel);

        // الحالة واللوق
        statusText = new TextView(this);
        statusText.setText("الحالة: جاهز للتمرد يا ماهر");
        statusText.setPadding(40, 10, 40, 10);
        statusText.setTypeface(null, Typeface.BOLD);
        mainLayout.addView(statusText);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setVisibility(View.GONE);
        mainLayout.addView(progressBar);

        // زر سجل التواصل (الظرف الزاوي)
        btnToggleLogs = createStyledButton("إظهار السجل الخام 🛠️", "#34495E");
        btnToggleLogs.setTextSize(9f);
        mainLayout.addView(btnToggleLogs);

        rawLogContainer = new LinearLayout(this);
        rawLogContainer.setOrientation(LinearLayout.VERTICAL);
        rawLogContainer.setVisibility(View.GONE);
        rawResponseDisplay = new TextView(this);
        rawResponseDisplay.setBackgroundColor(Color.parseColor("#1C1C1C"));
        rawResponseDisplay.setTextColor(Color.parseColor("#00FF41"));
        rawResponseDisplay.setTextSize(10f);
        rawResponseDisplay.setPadding(20, 20, 20, 20);
        ScrollView rawScroll = new ScrollView(this);
        rawScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, 250));
        rawScroll.addView(rawResponseDisplay);
        rawLogContainer.addView(rawScroll);
        mainLayout.addView(rawLogContainer);

        // منطقة القراءة
        pageIndicator = new TextView(this);
        pageIndicator.setGravity(Gravity.CENTER);
        pageIndicator.setPadding(0, 15, 0, 15);
        pageIndicator.setTypeface(null, Typeface.BOLD);
        mainLayout.addView(pageIndicator);

        ScrollView readScroll = new ScrollView(this);
        readScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        readingLayout = new LinearLayout(this);
        readingLayout.setOrientation(LinearLayout.VERTICAL);
        readingLayout.setPadding(45, 25, 45, 25);
        readScroll.addView(readingLayout);
        mainLayout.addView(readScroll);

        // شريط النسخ
        LinearLayout copyBar = new LinearLayout(this);
        copyBar.setGravity(Gravity.CENTER);
        copyBar.setBackgroundColor(Color.parseColor("#ECF0F1"));
        btnCopyPage = createStyledButton("نسخ الصفحة 📄", "#9B59B6");
        btnCopyBook = createStyledButton("نسخ الكتاب 📖", "#8E44AD");
        copyBar.addView(btnCopyPage); copyBar.addView(btnCopyBook);
        mainLayout.addView(copyBar);

        // شريط التنقل
        LinearLayout navBar = new LinearLayout(this);
        navBar.setGravity(Gravity.CENTER);
        navBar.setBackgroundColor(Color.WHITE);
        btnPrev = createStyledButton("السابق", "#7F8C8D");
        btnPlay = createStyledButton("▶ استماع", "#27AE60");
        btnNext = createStyledButton("التالي", "#7F8C8D");
        navBar.addView(btnPrev); navBar.addView(btnPlay); navBar.addView(btnNext);
        mainLayout.addView(navBar);

        // المستمعات
        btnPickFile.setOnClickListener(v -> openFilePicker());
        btnGenJson.setOnClickListener(v -> executeGeminiRequest());
        btnPrev.setOnClickListener(v -> navigatePage(-1));
        btnNext.setOnClickListener(v -> navigatePage(1));
        btnPlay.setOnClickListener(v -> streamVoice());
        btnCopyPage.setOnClickListener(v -> copyToClipboard("Page", bookPages.get(currentPageIndex).content));
        btnCopyBook.setOnClickListener(v -> copyFullBook());
        btnToggleLogs.setOnClickListener(v -> {
            boolean visible = rawLogContainer.getVisibility() == View.VISIBLE;
            rawLogContainer.setVisibility(visible ? View.GONE : View.VISIBLE);
            btnToggleLogs.setText(visible ? "إظهار السجل الخام 🛠️" : "إخفاء السجل ❌");
        });
    }

    // --- العمليات الأساسية ---

    private void openFilePicker() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("application/pdf");
        startActivityForResult(i, 101);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 101 && resultCode == Activity.RESULT_OK && data != null) {
            uploadAction(data.getData());
        }
    }

    private void uploadAction(Uri uri) {
        updateStatus("جاري رفع الملف...", true);
        new Thread(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] b = new byte[8192]; int len;
                while((len = is.read(b)) != -1) bos.write(b, 0, len);
                byte[] bytes = bos.toByteArray(); is.close();

                Request r1 = new Request.Builder()
                    .url("https://generativelanguage.googleapis.com/upload/v1beta/files?key=" + GEMINI_API_KEY)
                    .post(RequestBody.create(MediaType.parse("application/json"), "{\"file\":{\"display_name\":\"Doc_" + System.currentTimeMillis() + "\"}}"))
                    .addHeader("X-Goog-Upload-Protocol", "resumable").addHeader("X-Goog-Upload-Command", "start")
                    .addHeader("X-Goog-Upload-Header-Content-Length", String.valueOf(bytes.length))
                    .addHeader("X-Goog-Upload-Header-Content-Type", "application/pdf").build();
                
                String upUrl = client.newCall(r1).execute().header("X-Goog-Upload-Url");
                Request r2 = new Request.Builder().url(upUrl)
                    .post(RequestBody.create(MediaType.parse("application/pdf"), bytes))
                    .addHeader("X-Goog-Upload-Command", "upload, finalize")
                    .addHeader("X-Goog-Upload-Offset", "0").build();
                
                String res = client.newCall(r2).execute().body().string();
                JSONObject jo = new JSONObject(res);
                saveToHistory("مستند: " + System.currentTimeMillis(), jo.getJSONObject("file").getString("uri"));
                updateStatus("تم الرفع والحفظ بنجاح!", false);
            } catch (Exception e) { showErrorDialog("فشل الرفع", e.getMessage()); }
        }).start();
    }

    private void executeGeminiRequest() {
        int idx = historySpinner.getSelectedItemPosition();
        if(idx <= 0) return;
        String model = modelSpinner.getSelectedItem().toString();
        String uri = historyUris.get(idx);

        updateStatus("جاري الاستخراج والترجمة للعربية...", true);
        new Thread(() -> {
            try {
                JSONObject root = new JSONObject();
                JSONObject sys = new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", 
                    "أنت خبير OCR. استخرج النص من الـ PDF. " +
                    "إذا كان النص بغير العربية، ترجمه للعربية فوراً. " +
                    "المخرجات يجب أن تكون عربية فقط بصيغة Markdown.")));
                root.put("system_instruction", sys);

                JSONArray conts = new JSONArray();
                JSONObject p1 = new JSONObject().put("file_data", new JSONObject().put("mime_type", "application/pdf").put("file_uri", uri));
                JSONObject p2 = new JSONObject().put("text", "استخرج المحتوى بناءً على السكيما المرفقة.");
                conts.put(new JSONObject().put("parts", new JSONArray().put(p1).put(p2)));
                root.put("contents", conts);

                JSONObject config = new JSONObject();
                config.put("response_mime_type", "application/json");
                config.put("response_schema", new JSONObject("{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"page_number\":{\"type\":\"integer\"},\"content\":{\"type\":\"string\"}},\"required\":[\"page_number\",\"content\"]}}"));
                root.put("generationConfig", config);

                Request req = new Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + GEMINI_API_KEY)
                    .post(RequestBody.create(MediaType.parse("application/json"), root.toString())).build();

                String resBody = client.newCall(req).execute().body().string();
                showRaw(resBody);
                parseGeminiResponse(resBody);
            } catch (Exception e) { showErrorDialog("خطأ جيميناي", e.getMessage()); }
        }).start();
    }

    private void parseGeminiResponse(String raw) {
        try {
            JSONObject j = new JSONObject(raw);
            String text = j.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
            text = text.trim().replace("```json", "").replace("```", "").trim();

            bookPages.clear();
            JSONArray arr = new JSONArray(text);
            
            File docDir = new File(getExternalFilesDir(null), "MaherDocs/Doc_" + System.currentTimeMillis());
            if(!docDir.exists()) docDir.mkdirs();

            for(int i=0; i<arr.length(); i++) {
                PageData p = new PageData();
                p.pageNumber = arr.getJSONObject(i).optInt("page_number", i+1);
                p.content = arr.getJSONObject(i).optString("content", "");
                bookPages.add(p);
                
                // حفظ كملف مارك داون
                File f = new File(docDir, "Page_" + p.pageNumber + ".md");
                try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(p.content.getBytes("UTF-8")); }
            }

            new Handler(Looper.getMainLooper()).post(() -> {
                updateStatus("✅ تم الحفظ والترجمة!", false);
                currentPageIndex = 0; renderPage();
            });
        } catch (Exception e) { showErrorDialog("خطأ في التحليل", e.getMessage()); }
    }

    private void renderPage() {
        readingLayout.removeAllViews();
        if(bookPages.isEmpty()) return;
        PageData p = bookPages.get(currentPageIndex);
        pageIndicator.setText("📖 " + p.pageNumber + " / " + bookPages.size());
        TextView tv = new TextView(this);
        tv.setText(p.content); tv.setTextSize(17f);
        tv.setTextColor(Color.parseColor("#2C3E50"));
        readingLayout.addView(tv);
        btnPrev.setEnabled(currentPageIndex > 0);
        btnNext.setEnabled(currentPageIndex < bookPages.size() - 1);
    }

    private void navigatePage(int d) {
        if(mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.stop();
        currentPageIndex += d; renderPage();
    }

    private void copyFullBook() {
        StringBuilder sb = new StringBuilder();
        for(PageData p : bookPages) sb.append(p.content).append("\n\n---\n\n");
        copyToClipboard("FullBook", sb.toString());
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, "تم النسخ!", Toast.LENGTH_SHORT).show();
    }

    private void streamVoice() {
        if(bookPages.isEmpty()) return;
        updateStatus("جاري توليد الصوت...", true);
        new Thread(() -> {
            try {
                JSONObject b = new JSONObject().put("text", bookPages.get(currentPageIndex).content).put("model_id", "eleven_multilingual_v2");
                Request req = new Request.Builder().url("https://api.elevenlabs.io/v1/text-to-speech/" + ELEVEN_VOICE_ID)
                        .post(RequestBody.create(MediaType.parse("application/json"), b.toString()))
                        .addHeader("xi-api-key", ELEVEN_API_KEY).build();
                Response res = client.newCall(req).execute();
                if(res.isSuccessful()) {
                    File f = new File(getCacheDir(), "v.mp3");
                    try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(res.body().bytes()); }
                    new Handler(Looper.getMainLooper()).post(() -> { updateStatus("تشغيل الصفحة " + (currentPageIndex+1), false); playMp3(f.getAbsolutePath()); });
                } else { showErrorDialog("خطأ صوتي", res.body().string()); }
            } catch (Exception e) { showErrorDialog("خطأ", e.getMessage()); }
        }).start();
    }

    private void playMp3(String path) {
        try { if(mediaPlayer != null) mediaPlayer.release(); mediaPlayer = new MediaPlayer(); mediaPlayer.setDataSource(path); mediaPlayer.prepare(); mediaPlayer.start(); } catch (Exception ignored) {}
    }

    private void saveToHistory(String n, String u) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("list", "[]"));
            arr.put(new JSONObject().put("n", n).put("u", u));
            prefs.edit().putString("list", arr.toString()).apply();
            new Handler(Looper.getMainLooper()).post(this::loadHistory);
        } catch (Exception ignored) {}
    }

    private void loadHistory() {
        historyNames.clear(); historyUris.clear();
        historyNames.add("--- المحفوظات ---"); historyUris.add("");
        try {
            JSONArray arr = new JSONArray(prefs.getString("list", "[]"));
            for(int i=0; i<arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                historyNames.add(o.getString("n")); historyUris.add(o.getString("u"));
            }
        } catch (Exception ignored) {}
        historyAdapter.notifyDataSetChanged();
        if(historyNames.size() > 1) historySpinner.setSelection(historyNames.size()-1);
    }

    private void updateStatus(String m, boolean p) { new Handler(Looper.getMainLooper()).post(() -> { statusText.setText("💡 " + m); progressBar.setVisibility(p ? View.VISIBLE : View.GONE); }); }
    private void showRaw(String raw) { new Handler(Looper.getMainLooper()).post(() -> rawResponseDisplay.setText(raw)); }
    private void showErrorDialog(String t, String m) { new Handler(Looper.getMainLooper()).post(() -> { updateStatus("فشل!", false); new AlertDialog.Builder(this).setTitle("⚠️ " + t).setMessage(m).setPositiveButton("موافق", null).show(); }); }

    private Button createStyledButton(String t, String c) {
        Button b = new Button(this); b.setText(t); b.setTextColor(Color.WHITE); b.setTextSize(10f);
        GradientDrawable gd = new GradientDrawable(); gd.setColor(Color.parseColor(c)); gd.setCornerRadius(15f);
        b.setBackground(gd); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(10, 10, 10, 10); b.setLayoutParams(p); return b;
    }

    private GradientDrawable getCardDrawable(String color) {
        GradientDrawable gd = new GradientDrawable(); gd.setColor(Color.parseColor(color));
        gd.setCornerRadius(25f); gd.setStroke(2, Color.parseColor("#E0E0E0")); return gd;
    }

    private void addLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(this); tv.setText(text); tv.setTextSize(12f);
        tv.setPadding(10, 15, 0, 5); tv.setTypeface(null, Typeface.BOLD); parent.addView(tv);
    }

    @Override
    protected void onDestroy() { super.onDestroy(); if(mediaPlayer != null) mediaPlayer.release(); }
}
