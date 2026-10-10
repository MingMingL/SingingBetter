package com.singingbetter.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_AUDIO = 1001;
    private static final int REQ_EXPORT = 1002;
    private static final int BG = Color.rgb(247, 246, 242);
    private static final int INK = Color.rgb(35, 49, 43);
    private static final int MUTED = Color.rgb(112, 124, 116);
    private static final int GREEN = Color.rgb(38, 105, 78);
    private static final int GREEN_LIGHT = Color.rgb(231, 241, 234);
    private static final int CORAL = Color.rgb(201, 84, 61);
    private static final int BORDER = Color.rgb(229, 230, 222);
    private static final int WHITE = Color.WHITE;
    private static final long EXIT_CONFIRM_WINDOW_MS = 2000L;
    private static final float[] COMMON_PITCHES = {130.81f, 146.83f, 164.81f, 174.61f, 196.00f, 220.00f, 246.94f,
            261.63f, 293.66f, 329.63f, 349.23f, 392.00f, 440.00f};
    private static final String[] COMMON_PITCH_NAMES = {"C3", "D3", "E3", "F3", "G3", "A3", "B3",
            "C4", "D4", "E4", "F4", "G4", "A4"};

    private FrameLayout contentHost;
    private LinearLayout bottomBar;
    private AudioSession audioSession;
    private PitchGraphView pitchGraph;
    private TextView noteValue;
    private TextView centsValue;
    private TextView sessionHint;
    private TextView savedSummary;
    private TextView recordButton;
    private TextView playbackButton;
    private int selectedTab = 0;
    private float targetHz = 220f;
    private float referenceHz = 220f;
    private String sessionMode = "练习测评";
    private boolean awaitingTargetHz = false;
    private Runnable pendingRecorderAction;
    private boolean recordingPageVisible;
    private boolean skipHistoryForNextPage;
    private boolean handlingSwipe;
    private float touchDownX;
    private float touchDownY;
    private long lastHomeSwipeAt;
    private String currentPageTitle = "";
    private int currentPageTab;
    private boolean currentPageRecordingVisible;
    private TextView referenceSummary;
    private final Deque<NavigationState> navigationHistory = new ArrayDeque<>();
    private String lastResult = "还没有练习记录，完成一次练习后这里会出现你的进步。";
    private File pendingExportFile;

    private static final class NavigationState {
        final View page;
        final int selectedTab;
        final boolean recordingPageVisible;
        final String title;

        NavigationState(View page, int selectedTab, boolean recordingPageVisible, String title) {
            this.page = page;
            this.selectedTab = selectedTab;
            this.recordingPageVisible = recordingPageVisible;
            this.title = title;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        lastResult = getPreferences(0).getString("lastResult", lastResult);
        referenceHz = getPreferences(0).getFloat("referenceHz", 220f);
        audioSession = new AudioSession(getFilesDir(), new AudioSession.Listener() {
            @Override public void onPitch(float hz) {
                runOnUiThread(() -> updatePitch(hz));
            }
            @Override public void onMessage(String message) {
                runOnUiThread(() -> {
                    if (sessionHint != null) sessionHint.setText(message);
                    toast(message);
                });
            }
            @Override public void onSaved(File file, int voicedFrames, int inTuneFrames, float averageHz, long durationMillis) {
                runOnUiThread(() -> onRecordingSaved(file, voicedFrames, inTuneFrames, averageHz, durationMillis));
            }
        });
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        contentHost = new FrameLayout(this);
        root.addView(contentHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setBackgroundColor(WHITE);
        bottomBar.setElevation(dp(8));
        root.addView(bottomBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)));
        setContentView(root);
        showTab(0);
    }

    private void showTab(int tab) {
        if (audioSession != null) {
            if (audioSession.isRecording()) audioSession.stop();
            audioSession.stopPlayback();
        }
        if (contentHost.getChildCount() > 0) {
            if (tab != selectedTab) {
                navigationHistory.push(new NavigationState(contentHost.getChildAt(0), currentPageTab,
                        currentPageRecordingVisible, currentPageTitle));
            } else {
                navigationHistory.clear();
            }
        }
        lastHomeSwipeAt = 0;
        skipHistoryForNextPage = true;
        selectedTab = tab;
        recordingPageVisible = false;
        refreshBottomBar();
        switch (tab) {
            case 1: showLearn(); break;
            case 2: showPractice(); break;
            case 3: showPerform(); break;
            case 4: showProfile(); break;
            default: showHome(); break;
        }
    }

    private void refreshBottomBar() {
        bottomBar.removeAllViews();
        String[] icons = {"⌂", "▤", "♪", "◉", "○"};
        String[] labels = {"首页", "课程", "练习", "演唱", "我的"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            int color = selectedTab == i ? GREEN : MUTED;
            TextView icon = text(icons[i], 19, color, true);
            icon.setGravity(Gravity.CENTER);
            TextView label = text(labels[i], 11, color, selectedTab == i);
            label.setGravity(Gravity.CENTER);
            item.addView(icon);
            item.addView(label);
            item.setOnClickListener(v -> showTab(index));
            bottomBar.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
    }

    private void showHome() {
        LinearLayout page = screen("SingingBetter", "把每一次练习，变成听得见的进步");
        LinearLayout greeting = row();
        LinearLayout greetText = column();
        greetText.addView(text("早上好，准备开嗓了吗？", 19, INK, true));
        greetText.addView(text("今天给自己 8 分钟", 13, MUTED, false));
        greeting.addView(greetText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView avatar = text("BS", 13, GREEN, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(shape(GREEN_LIGHT, 28, 0, 0));
        greeting.addView(avatar, new LinearLayout.LayoutParams(dp(44), dp(44)));
        page.addView(greeting, margin(0, 4, 0, 18));

        LinearLayout hero = card(CORAL, 20);
        hero.addView(text("今天的声音练习", 14, 0xFFFFE6DC, true));
        hero.addView(text("找到你的稳定音", 24, WHITE, true), margin(0, 7, 0, 2));
        hero.addView(text("从舒适音区开始，听见音高的变化。", 13, 0xFFFFF6F0, false));
        TextView start = button("开始 3 分钟练习", WHITE, CORAL, true);
        start.setOnClickListener(v -> showTargetPitchPicker(() -> openRecorder("音准练习", targetHz)));
        hero.addView(start, margin(0, 18, 0, 0));
        page.addView(hero, margin(0, 0, 0, 18));

        LinearLayout stats = row();
        stats.addView(statCard("连续练习", getPreferences(0).getInt("streak", 0) + " 天", "每天一点点"), weightedMargin(0, 8, 8, 0));
        stats.addView(statCard("累计时长", getPreferences(0).getInt("minutes", 0) + " 分钟", "只算你的练习"), weightedMargin(8, 8, 0, 0));
        page.addView(stats);

        page.addView(sectionTitle("继续学习", "全部课程", () -> showTab(1)), margin(0, 24, 0, 10));
        page.addView(courseCard("01", "发声先从呼吸开始", "图文 · 4 分钟", GREEN_LIGHT, () -> showLesson("breath")), margin(0, 0, 0, 10));
        page.addView(courseCard("02", "音准不是天生的", "示范课 · 6 分钟", 0xFFF8EBDD, () -> showVideoLesson()), margin(0, 0, 0, 10));
        page.addView(sectionTitle("今日推荐", "去练习", () -> showTab(2)), margin(0, 18, 0, 10));
        page.addView(infoCard("轻声哼鸣", "用轻松的“嗯”寻找舒适共鸣，不要用力顶高音。", "热身 · 2 分钟", () -> showTargetPitchPicker(() -> openRecorder("轻声哼鸣", targetHz))), margin(0, 0, 0, 10));
        page.addView(infoCard("唱一段", "用原创练声旋律录一段，回听音高的稳定程度。", "演唱 · 自由练习", () -> showTab(3)), margin(0, 0, 0, 18));
        showPage(page);
    }

    private void showLearn() {
        LinearLayout page = screen("课程", "短课讲清一个要点，学完就能马上练");
        page.addView(chipRow(new String[]{"全部", "发声", "音准", "节奏"}), margin(0, 10, 0, 16));
        page.addView(sectionTitle("图文教程", "4 节入门", null), margin(0, 0, 0, 10));
        page.addView(courseCard("01", "发声先从呼吸开始", "呼吸 · 支持 · 放松", GREEN_LIGHT, () -> showLesson("breath")), margin(0, 0, 0, 10));
        page.addView(courseCard("02", "找到轻松的起音", "发声 · 不挤不喊", 0xFFF8EBDD, () -> showLesson("onset")), margin(0, 0, 0, 10));
        page.addView(courseCard("03", "听见音高的方向", "音准 · 高低与稳定", 0xFFE9EFF5, () -> showLesson("pitch")), margin(0, 0, 0, 10));
        page.addView(courseCard("04", "把节拍唱稳", "节奏 · 慢练与分句", 0xFFF2EAF0, () -> showLesson("rhythm")), margin(0, 0, 0, 18));
        page.addView(sectionTitle("视频教程", "原创示范课", null), margin(0, 0, 0, 10));
        page.addView(videoCard("音准不是天生的", "看、听、模仿：用 3 步找到一个舒服的音", () -> showVideoLesson()), margin(0, 0, 0, 12));
        page.addView(noteBox("首版示范课使用原创图文脚本和播放器占位；准备好原创或可商用授权的视频后，可在这里接入正式视频。"), margin(0, 0, 0, 20));
        showPage(page);
    }

    private void showPractice() {
        LinearLayout page = screen("练习", "先热身，再挑战；每次只专注一件事");
        page.addView(cardHeader("今天推荐", "先选一个舒服的目标音，再做长音稳定练习。"), margin(0, 10, 0, 14));
        List<RecordingStore.Item> saved = RecordingStore.list(recordingsDirectory());
        page.addView(infoCard("我的录音", RecordingStore.totalSize(saved) == 0
                        ? "还没有录音，完成一次练习后就能在这里回放和管理。"
                        : "共 " + saved.size() + " 段 · 占用 " + formatSize(RecordingStore.totalSize(saved)) + "，可回放、重命名、导出或删除。",
                saved.isEmpty() ? "暂无" : saved.size() + " 段", () -> showRecordings()), margin(0, 0, 0, 18));
        page.addView(sectionTitle("练习工具", "打开麦克风", null), margin(0, 0, 0, 10));
        page.addView(exerciseCard("01", "长音稳定", "看音高是否平稳", "2 分钟", () -> showTargetPitchPicker(() -> openRecorder("长音稳定", targetHz))), margin(0, 0, 0, 10));
        page.addView(exerciseCard("02", "音准跟唱", "先听参考音，再轻声模唱", "3 分钟", () -> showTargetPitchPicker(() -> openRecorder("音准跟唱", targetHz))), margin(0, 0, 0, 10));
        page.addView(exerciseCard("03", "自由试唱", "唱你熟悉的一句，观察音高轨迹", "自由", () -> openRecorder("自由试唱", 0f)), margin(0, 0, 0, 18));
        page.addView(sectionTitle("练习小贴士", null, null), margin(0, 0, 0, 8));
        page.addView(infoCard("舒适比高音重要", "如果喉咙紧、疼或声音发哑，请停下来休息。练习不追求音量，也不要硬顶音域。", "安全练习", null), margin(0, 0, 0, 10));
        page.addView(infoCard("录音环境", "尽量戴耳机并在安静环境练习，避免扬声器伴奏进入麦克风，影响基础音高识别。", "识别更稳定", null), margin(0, 0, 0, 18));
        showPage(page);
    }

    private File recordingsDirectory() {
        return audioSession == null ? new File(getFilesDir(), "recordings") : audioSession.recordingsDirectory();
    }

    private void showRecordings() {
        List<RecordingStore.Item> items = RecordingStore.list(recordingsDirectory());
        long totalBytes = RecordingStore.totalSize(items);
        LinearLayout page = screen("录音管理", "回放、重命名、导出或删除本机录音");
        page.addView(cardHeader("共 " + items.size() + " 段录音", items.isEmpty()
                        ? "完成一次练习后，录音会出现在这里。"
                        : "占用 " + formatSize(totalBytes) + "，录音只保存在本机，卸载应用会一并删除。"),
                margin(0, 10, 0, 14));
        if (items.isEmpty()) {
            page.addView(noteBox("还没有录音。回到练习页挑一项练习，录完保存后就能在这里回放、重命名或导出。"), margin(0, 0, 0, 14));
        } else {
            for (RecordingStore.Item item : items) page.addView(recordingCard(item), margin(0, 0, 0, 10));
            TextView clearAll = button("清空全部录音", WHITE, CORAL, true);
            clearAll.setOnClickListener(v -> confirmClearRecordings(items.size(), totalBytes));
            page.addView(clearAll, margin(0, 8, 0, 14));
        }
        page.addView(noteBox("导出会打开系统的「保存到…」，把 WAV 写到你自己选的位置（通常是「下载」目录）。录音始终留在本机，不会上传。"), margin(0, 0, 0, 18));
        showPage(page);
    }

    private LinearLayout recordingCard(final RecordingStore.Item item) {
        LinearLayout card = card(WHITE, 16);
        LinearLayout top = row();
        LinearLayout copy = column();
        copy.addView(text(item.label, 15, INK, true));
        copy.addView(text(recordingMeta(item), 12, MUTED, false), margin(0, 4, 0, 0));
        top.addView(copy, weightedMargin(0, 0, 0, 0));
        top.addView(text(formatSize(item.sizeBytes), 12, GREEN, true));
        card.addView(top);
        LinearLayout firstRow = row();
        firstRow.addView(actionButton("回放", GREEN_LIGHT, GREEN, v -> playRecordingFile(item)), weightedMargin(0, 12, 5, 0));
        firstRow.addView(actionButton("重命名", WHITE, INK, v -> showRenameDialog(item)), weightedMargin(5, 12, 0, 0));
        card.addView(firstRow);
        LinearLayout secondRow = row();
        secondRow.addView(actionButton("导出", WHITE, INK, v -> exportRecording(item)), weightedMargin(0, 8, 5, 0));
        secondRow.addView(actionButton("删除", 0xFFF8E9E4, CORAL, v -> confirmDeleteRecording(item)), weightedMargin(5, 8, 0, 0));
        card.addView(secondRow);
        return card;
    }

    private String recordingMeta(RecordingStore.Item item) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(item.timeMillis));
        long seconds = Math.round(item.durationMillis / 1000.0);
        String length = seconds < 60 ? seconds + " 秒" : (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
        return time + " · 时长 " + length;
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void playRecordingFile(RecordingStore.Item item) {
        try {
            audioSession.playFile(item.file);
            toast("正在回放：" + item.label);
        } catch (Exception error) {
            toast(error.getMessage() == null ? "暂时无法回放" : error.getMessage());
        }
    }

    private void showRenameDialog(final RecordingStore.Item item) {
        LinearLayout wrapper = column();
        wrapper.setPadding(dp(22), dp(18), dp(22), dp(6));
        wrapper.addView(text("给这段录音起个好认的名字，例如「长音稳定 · 第 3 次」。", 13, MUTED, false), margin(0, 0, 0, 10));
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(item.label);
        input.setSelection(input.getText().length());
        wrapper.addView(input);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("重命名录音")
                .setView(wrapper)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = input.getText() == null ? "" : input.getText().toString().trim();
            if (label.isEmpty()) {
                toast("名字不能为空");
                return;
            }
            File renamed = RecordingStore.rename(item.file, label);
            dialog.dismiss();
            if (renamed == null) {
                toast("重命名失败，请换一个名字再试");
                return;
            }
            if (audioSession != null) audioSession.forget(item.file);
            toast("已重命名为「" + RecordingStore.labelOf(renamed) + "」");
            refreshRecordings();
        }));
        dialog.show();
    }

    private void confirmDeleteRecording(final RecordingStore.Item item) {
        new AlertDialog.Builder(this)
                .setTitle("删除这段录音？")
                .setMessage(item.label + "\n" + recordingMeta(item) + "\n\n删除后无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    if (RecordingStore.delete(item.file)) {
                        if (audioSession != null) audioSession.forget(item.file);
                        toast("已删除「" + item.label + "」");
                        refreshRecordings();
                    } else {
                        toast("删除失败，请重试");
                    }
                })
                .show();
    }

    private void confirmClearRecordings(int count, long totalBytes) {
        new AlertDialog.Builder(this)
                .setTitle("清空全部录音？")
                .setMessage("将删除 " + count + " 段录音，释放约 " + formatSize(totalBytes) + "。删除后无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("全部删除", (dialog, which) -> {
                    RecordingStore.deleteAll(recordingsDirectory());
                    if (audioSession != null) audioSession.stopPlayback();
                    toast("已清空录音");
                    refreshRecordings();
                })
                .show();
    }

    private void refreshRecordings() {
        skipHistoryForNextPage = true;
        showRecordings();
    }

    private void exportRecording(RecordingStore.Item item) {
        pendingExportFile = item.file;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/wav");
        intent.putExtra(Intent.EXTRA_TITLE, item.file.getName());
        try {
            startActivityForResult(intent, REQ_EXPORT);
        } catch (ActivityNotFoundException error) {
            pendingExportFile = null;
            toast("这台设备没有可用的文件管理器，无法导出");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT) return;
        File source = pendingExportFile;
        pendingExportFile = null;
        if (source == null) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            toast("已取消导出");
            return;
        }
        boolean copied = false;
        try (InputStream input = new FileInputStream(source);
             OutputStream output = getContentResolver().openOutputStream(data.getData())) {
            if (output != null) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
                copied = true;
            }
        } catch (IOException | SecurityException error) {
            toast("导出失败：" + (error.getMessage() == null ? "无法写入所选位置" : error.getMessage()));
        }
        if (copied) toast("已导出 " + source.getName());
    }

    private void showPerform() {
        LinearLayout page = screen("演唱", "选一段、唱一遍、回听自己的声音");
        LinearLayout original = card(0xFFE9EEE6, 20);
        original.addView(text("原创练习曲 · 无版权歌曲素材", 12, GREEN, true));
        original.addView(text("晨光练声", 24, INK, true), margin(0, 6, 0, 2));
        original.addView(text("用“ma—ma—ma”轻唱五声音阶。先在舒适音区试唱，再按自己的音域调整。", 14, MUTED, false));
        LinearLayout noteRow = row();
        noteRow.addView(notePill("哼鸣热身"), wrapMargin(0, 12, 6, 0));
        noteRow.addView(notePill("音域可调"), wrapMargin(0, 12, 6, 0));
        noteRow.addView(notePill("自由录音"), wrapMargin(0, 12, 0, 0));
        original.addView(noteRow);
        page.addView(original, margin(0, 10, 0, 14));
        page.addView(infoCard("跟唱提示", "吸气时保持肩膀放松；第一遍小声模唱，第二遍再唱完整句。点击录音后，完成即可回放。", "练习步骤", null), margin(0, 0, 0, 14));
        TextView reference = button("先听参考音 · " + PitchDetector.noteName(referenceHz), GREEN_LIGHT, GREEN, true);
        reference.setOnClickListener(v -> playReferenceTone(referenceHz));
        page.addView(reference, margin(0, 0, 0, 8));
        TextView sing = button("开始演唱并录音", GREEN, WHITE, true);
        sing.setOnClickListener(v -> openRecorder("晨光练声 · 演唱", 0f));
        page.addView(sing, margin(0, 0, 0, 14));
        page.addView(sectionTitle("最近一遍", null, null), margin(0, 6, 0, 8));
        page.addView(noteBox(lastResult));
        showPage(page);
    }

    private void showProfile() {
        LinearLayout page = screen("我的", "你的练习记录只保存在这台设备上");
        LinearLayout profile = card(WHITE, 18);
        TextView avatar = text("BS", 16, WHITE, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(shape(GREEN, 28, 0, 0));
        LinearLayout profileText = column();
        profileText.addView(text("声音练习者", 18, INK, true));
        profileText.addView(text("从今天开始，慢慢听见变化。", 13, MUTED, false));
        LinearLayout profileRow = row();
        profileRow.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
        profileRow.addView(profileText, weightedMargin(12, 0, 0, 0));
        profile.addView(profileRow);
        page.addView(profile, margin(0, 10, 0, 14));
        LinearLayout referenceCard = card(WHITE, 16);
        referenceCard.addView(text("演唱参考音", 15, INK, true));
        referenceSummary = text(referenceLabel(), 13, GREEN, true);
        referenceCard.addView(referenceSummary, margin(0, 6, 0, 0));
        referenceCard.addView(text("点击选择常用音符", 12, MUTED, false), margin(0, 4, 0, 0));
        referenceCard.setOnClickListener(v -> showReferencePitchPicker());
        page.addView(referenceCard, margin(0, 0, 0, 14));
        page.addView(sectionTitle("学习档案", null, null), margin(0, 0, 0, 8));
        int sessions = getPreferences(0).getInt("sessions", 0);
        int minutes = getPreferences(0).getInt("minutes", 0);
        page.addView(infoCard("练习次数", sessions + " 次", "持续积累", null), margin(0, 0, 0, 8));
        page.addView(infoCard("练习时长", minutes + " 分钟", "本机统计", null), margin(0, 0, 0, 8));
        page.addView(infoCard("最近一次测评", lastResult, "音高反馈仅为基础估算", null), margin(0, 0, 0, 16));
        page.addView(noteBox("录音文件保存在应用私有目录，卸载应用或清除数据后会被删除。首版没有上传录音或账号云同步。"), margin(0, 0, 0, 18));
        showPage(page);
    }

    private void showLesson(String lessonId) {
        String title;
        String intro;
        String[] steps;
        switch (lessonId) {
            case "onset":
                title = "找到轻松的起音";
                intro = "舒服的发声不需要喉咙用力挤压。把声音想成轻轻接上气息，而不是突然“撞”出一个音。";
                steps = new String[]{"先轻轻呼气，感受气息自然流动。", "在呼气上加一个柔和的“呜”或“嗯”，音量保持像说话。", "如果声音发紧或刺耳，停一下，回到更轻、更舒服的音量。"};
                break;
            case "pitch":
                title = "听见音高的方向";
                intro = "音高是声音听起来高或低的感觉。练习时先辨认方向，再追求准确；不要用更大的音量来硬追目标音。";
                steps = new String[]{"听参考音，心里默唱一次。", "用轻声哼鸣模仿，留意声音是偏高还是偏低。", "分两三次靠近目标；保持喉咙放松，记录变化。"};
                break;
            case "rhythm":
                title = "把节拍唱稳";
                intro = "把一句旋律拆成小节拍，比一口气唱完更容易找到稳定感。先拍手或轻点，再加入声音。";
                steps = new String[]{"用舒适速度数 1、2、3、4。", "先按节拍轻轻说出歌词或音节。", "把句子拆短，慢速重复两遍，再回到自然速度。"};
                break;
            default:
                title = "发声先从呼吸开始";
                intro = "唱歌不需要一次吸满一大口气。保持身体自然、肩颈放松，让吸气轻轻发生；唱的时候均匀送气，不要一开始就把气推完。";
                steps = new String[]{"站稳或坐直，放松下巴和肩膀。", "安静吸气，感受下侧肋骨轻轻向外展开，不要耸肩。", "用轻柔的“sss”慢慢呼气，保持气流平稳；舒服即可，不比谁憋得久。"};
        }
        LinearLayout page = screen(title, "原创入门讲义 · 约 3 分钟");
        page.addView(breathDiagram(), margin(0, 10, 0, 16));
        page.addView(sectionTitle("先记住", null, null), margin(0, 0, 0, 6));
        page.addView(noteBox(intro), margin(0, 0, 0, 14));
        page.addView(sectionTitle("练习步骤", null, null), margin(0, 0, 0, 8));
        for (int i = 0; i < steps.length; i++) page.addView(stepCard(i + 1, steps[i]), margin(0, 0, 0, 8));
        page.addView(infoCard("开始练习", "把刚学到的感觉用一次短录音检查；反馈只做基础音高估算，不代替声乐老师指导。", "录音与回听", () -> showTargetPitchPicker(() -> openRecorder("课程练习 · " + title, targetHz))), margin(0, 14, 0, 18));
        showPage(page);
    }

    private void showVideoLesson() {
        LinearLayout page = screen("音准示范课", "看、听、模仿：用三步找到一个舒服的音");
        LinearLayout player = card(0xFF263C35, 20);
        TextView playSymbol = text("▶", 32, WHITE, true);
        playSymbol.setGravity(Gravity.CENTER);
        LinearLayout playerText = column();
        playerText.setGravity(Gravity.CENTER);
        playerText.addView(playSymbol);
        playerText.addView(text("原创示范课视频接入位", 15, WHITE, true), margin(0, 10, 0, 4));
        playerText.addView(text("当前为交互原型，尚无视频文件", 12, 0xFFD4E1D9, false));
        player.addView(playerText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(196)));
        page.addView(player, margin(0, 10, 0, 16));
        page.addView(noteBox("正式版接入视频前，使用原创拍摄或已获得商业使用许可的素材。首版先用本页原创讲义展示课程结构，不下载或盗用网页视频。"), margin(0, 0, 0, 14));
        page.addView(stepCard(1, "找一个舒服的音，不要从最高音开始。"), margin(0, 0, 0, 8));
        page.addView(stepCard(2, "先听示范，再小声模唱；必要时降低音量和音高。"), margin(0, 0, 0, 8));
        page.addView(stepCard(3, "录下短短一段，回听音高是否稳定、喉咙是否放松。"), margin(0, 0, 0, 14));
        TextView practice = button("试试音准练习", GREEN, WHITE, true);
        practice.setOnClickListener(v -> showTargetPitchPicker(() -> openRecorder("音准跟唱", targetHz)));
        page.addView(practice, margin(0, 0, 0, 18));
        showPage(page);
    }

    private void openRecorder(String mode, float expectedHz) {
        sessionMode = mode;
        targetHz = expectedHz;
        selectedTab = mode.contains("演唱") || mode.contains("练声") ? 3 : 2;
        refreshBottomBar();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        showRecorder();
    }

    /** 目标音选择面板：常用 13 个音（C3~A4），点击后回调 pendingRecorderAction */
    private void showTargetPitchPicker(Runnable onPicked) {
        pendingRecorderAction = onPicked;
        awaitingTargetHz = true;

        LinearLayout page = screen("选择目标音", "点击音名确认，再进入练习");
        page.addView(noteBox("当前练习需要固定目标音。请在下方选择你要练习的音高；也可先用「自由试唱」探索舒适音区。"), margin(0, 10, 0, 14));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout row = null;
        for (int i = 0; i < COMMON_PITCHES.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                grid.addView(row, margin(0, 6, 0, 0));
            }
            final float hz = COMMON_PITCHES[i];
            final String name = COMMON_PITCH_NAMES[i];
            TextView btn = text(name, 14, INK, false);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(18), dp(12), dp(18), dp(12));
            int background = targetHz > 0 && Math.abs(targetHz - hz) < 0.5f ? GREEN_LIGHT : WHITE;
            btn.setBackground(shape(background, 14, BORDER, 1));
            btn.setOnClickListener(v -> {
                targetHz = hz;
                awaitingTargetHz = false;
                pendingRecorderAction.run();
            });
            row.addView(btn, wrapMargin(0, 0, 8, 0));
        }
        page.addView(grid);
        showPage(page);
    }

    private void showReferencePitchPicker() {
        LinearLayout page = screen("参考音设置", "选择演唱页和自由试唱时使用的参考音");
        page.addView(noteBox("当前参考音：" + referenceLabel() + "。练习目标音不受此设置影响。"), margin(0, 10, 0, 14));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout row = null;
        for (int i = 0; i < COMMON_PITCHES.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                grid.addView(row, margin(0, 6, 0, 0));
            }
            final float hz = COMMON_PITCHES[i];
            final String name = COMMON_PITCH_NAMES[i];
            TextView btn = text(name, 14, INK, false);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(18), dp(12), dp(18), dp(12));
            int background = Math.abs(referenceHz - hz) < 0.5f ? GREEN_LIGHT : WHITE;
            btn.setBackground(shape(background, 14, BORDER, 1));
            btn.setOnClickListener(v -> {
                referenceHz = hz;
                getPreferences(0).edit().putFloat("referenceHz", referenceHz).apply();
                if (referenceSummary != null) referenceSummary.setText(referenceLabel());
                toast("参考音已设为 " + name);
                navigateBackOrExit();
            });
            row.addView(btn, wrapMargin(0, 0, 8, 0));
        }
        page.addView(grid);
        showPage(page);
    }

    private void showRecorder() {
        recordingPageVisible = true;
        LinearLayout page = screen("正在练习", sessionMode);
        page.addView(noteBox(targetHz > 0 ? "目标音：" + PitchDetector.noteName(targetHz) + "（约 " + Math.round(targetHz) + " Hz）。请先用轻声哼鸣，保持舒适，不要硬推音量。" : "自由演唱模式：没有固定目标音，只显示检测到的音高轨迹与回放。"), margin(0, 10, 0, 10));
        float referenceToneHz = targetHz > 0 ? targetHz : referenceHz;
        String referenceToneName = targetHz > 0 ? "目标音" : "参考音 · " + PitchDetector.noteName(referenceHz);
        TextView reference = button("听" + referenceToneName, GREEN_LIGHT, GREEN, true);
        reference.setOnClickListener(v -> playReferenceTone(referenceToneHz));
        page.addView(reference, margin(0, 0, 0, 12));
        LinearLayout pitchCard = card(WHITE, 18);
        pitchCard.addView(text("当前音高", 13, MUTED, true));
        noteValue = text("—", 36, INK, true);
        pitchCard.addView(noteValue, margin(0, 2, 0, 2));
        centsValue = text("安静等待你的声音", 14, MUTED, false);
        pitchCard.addView(centsValue);
        page.addView(pitchCard, margin(0, 0, 0, 12));
        page.addView(text("音高轨迹 · 仅作练习参考", 13, MUTED, true), margin(2, 2, 0, 7));
        pitchGraph = new PitchGraphView(this);
        page.addView(pitchGraph, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(164)));
        sessionHint = text("准备好后点击开始，请允许麦克风权限。", 13, MUTED, false);
        page.addView(sessionHint, margin(2, 12, 0, 12));
        recordButton = button("开始录音", CORAL, WHITE, true);
        recordButton.setOnClickListener(v -> {
            if (audioSession.isRecording()) stopRecording();
            else startRecording();
        });
        page.addView(recordButton, margin(0, 0, 0, 10));
        playbackButton = button("回放本次录音", GREEN_LIGHT, GREEN, true);
        playbackButton.setOnClickListener(v -> playRecording());
        page.addView(playbackButton, margin(0, 0, 0, 14));
        savedSummary = text(lastResult, 13, MUTED, false);
        page.addView(savedSummary, margin(2, 0, 0, 20));
        showPage(page);
    }

    private void startRecording() {
        if (!recordingPageVisible) return;
        try {
            pitchGraph.clear();
            savedSummary.setText("录音中…唱完后点击“结束并保存”。");
            audioSession.start(targetHz, sessionMode);
            recordButton.setText("结束并保存");
            sessionHint.setText("正在听你的声音，保持自然呼吸。录音仅保存在本机。" );
        } catch (Exception error) {
            sessionHint.setText(error.getMessage() == null ? "无法启动录音，请检查麦克风权限。" : error.getMessage());
        }
    }

    private void stopRecording() {
        audioSession.stop();
        if (recordButton != null) recordButton.setText("再录一遍");
        if (sessionHint != null) sessionHint.setText("已保存。可以回放，或在练习页「我的录音」里管理。" );
    }

    private void playRecording() {
        try {
            audioSession.playLast();
            sessionHint.setText("正在回放你的录音…" );
        } catch (Exception error) {
            toast(error.getMessage() == null ? "暂时无法回放" : error.getMessage());
        }
    }

    private String referenceLabel() {
        return PitchDetector.noteName(referenceHz) + "（约 " + Math.round(referenceHz) + " Hz）";
    }

    private void playReferenceTone(float hz) {
        try {
            audioSession.playReferenceTone(hz);
            if (sessionHint != null) sessionHint.setText("正在播放 " + PitchDetector.noteName(hz) + " 参考音，听完后轻声跟唱。" );
        } catch (Exception error) {
            toast(error.getMessage() == null ? "暂时无法播放参考音" : error.getMessage());
        }
    }

    private void updatePitch(float hz) {
        if (!recordingPageVisible || pitchGraph == null) return;
        pitchGraph.addFrequency(hz);
        if (hz <= 0) {
            centsValue.setText("没有稳定音高，试试更安静、更轻声地哼鸣");
            return;
        }
        noteValue.setText(PitchDetector.noteName(hz));
        if (targetHz > 0) {
            float cents = PitchDetector.centsFrom(hz, targetHz);
            String direction = cents > 0 ? "偏高 " : "偏低 ";
            centsValue.setText(Math.abs(cents) <= 50 ? "接近目标音 · " + Math.round(cents) + " 音分" : direction + Math.round(Math.abs(cents)) + " 音分");
        } else {
            centsValue.setText(String.format(Locale.getDefault(), "约 %.1f Hz · 放松喉咙，保持气息", hz));
        }
    }

    private void onRecordingSaved(File file, int voicedFrames, int inTuneFrames, float averageHz, long durationMillis) {
        int sessions = getPreferences(0).getInt("sessions", 0) + 1;
        int elapsedMinutes = Math.max(1, (int) Math.ceil(durationMillis / 60000.0));
        int minutes = getPreferences(0).getInt("minutes", 0) + elapsedMinutes;
        updatePracticeStreak();
        getPreferences(0).edit().putInt("sessions", sessions).putInt("minutes", minutes).apply();
        String averageNote = averageHz > 0 ? PitchDetector.noteName(averageHz) : "未检测到稳定音高";
        String scoreText;
        if (targetHz > 0 && voicedFrames > 0) {
            int score = Math.round(100f * inTuneFrames / voicedFrames);
            String targetNote = PitchDetector.noteName(targetHz);
            scoreText = "基础目标音匹配：" + score + "%（" + targetNote + " 附近 ±50 音分）";
        } else {
            scoreText = "基础音高稳定度：有效音高片段 " + voicedFrames + " 个";
        }
        lastResult = "最近一遍：" + scoreText + "；平均音高约 " + averageNote + "。此结果受麦克风、环境噪声和泛音影响，只供练习参考。";
        getPreferences(0).edit().putString("lastResult", lastResult).apply();
        if (savedSummary != null) savedSummary.setText(lastResult + "\n录音已保存在本机，可立即回放。");
        if (sessionHint != null) sessionHint.setText("已保存 WAV 录音，可以回放或再录。" );
        if (recordButton != null) recordButton.setText("再录一遍");
    }

    private void updatePracticeStreak() {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String today = formatter.format(new Date());
        android.content.SharedPreferences preferences = getPreferences(0);
        String previous = preferences.getString("lastPracticeDate", "");
        int streak = preferences.getInt("streak", 0);
        if (today.equals(previous)) return;
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        String yesterdayText = formatter.format(yesterday.getTime());
        streak = yesterdayText.equals(previous) ? streak + 1 : 1;
        preferences.edit().putString("lastPracticeDate", today).putInt("streak", streak).apply();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showRecorder();
            } else {
                toast("没有麦克风权限，仍可阅读教程；开启练习需要允许录音。" );
            }
        }
    }

    private LinearLayout screen(String title, String subtitle) {
        LinearLayout page = new LinearLayout(this);
        page.setTag(title);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(12), dp(20), dp(24));
        TextView brand = text("SingingBetter  /  声音成长计划", 11, GREEN, true);
        page.addView(brand, margin(0, 2, 0, 7));
        page.addView(text(title, 27, INK, true));
        page.addView(text(subtitle, 13, MUTED, false), margin(0, 3, 0, 12));
        return page;
    }

    private void showPage(LinearLayout page) {
        View scroll = page.getParent() instanceof ScrollView ? (View) page.getParent() : null;
        if (scroll == null) {
            ScrollView container = new ScrollView(this);
            container.setFillViewport(false);
            container.setClipToPadding(false);
            container.setVerticalScrollBarEnabled(false);
            container.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scroll = container;
        }
        if (contentHost.getChildCount() > 0 && !skipHistoryForNextPage) {
            navigationHistory.push(new NavigationState(contentHost.getChildAt(0), currentPageTab,
                    currentPageRecordingVisible, currentPageTitle));
        }
        skipHistoryForNextPage = false;
        contentHost.removeAllViews();
        contentHost.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        currentPageTitle = page.getTag() instanceof String ? (String) page.getTag() : "";
        currentPageTab = selectedTab;
        currentPageRecordingVisible = recordingPageVisible;
    }

    private LinearLayout courseCard(String number, String title, String detail, int tint, Runnable click) {
        LinearLayout card = card(WHITE, 16);
        LinearLayout line = row();
        TextView badge = text(number, 13, GREEN, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(shape(tint, 12, 0, 0));
        LinearLayout copy = column();
        copy.addView(text(title, 16, INK, true));
        copy.addView(text(detail, 12, MUTED, false), margin(0, 4, 0, 0));
        line.addView(badge, new LinearLayout.LayoutParams(dp(42), dp(42)));
        line.addView(copy, weightedMargin(12, 0, 0, 0));
        TextView arrow = text("›", 24, MUTED, false);
        arrow.setGravity(Gravity.CENTER);
        line.addView(arrow, new LinearLayout.LayoutParams(dp(26), dp(42)));
        card.addView(line);
        if (click != null) card.setOnClickListener(v -> click.run());
        return card;
    }

    private LinearLayout exerciseCard(String number, String title, String detail, String duration, Runnable click) {
        LinearLayout card = card(WHITE, 16);
        LinearLayout top = row();
        TextView badge = text(number, 13, CORAL, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(shape(0xFFF8E9E4, 12, 0, 0));
        LinearLayout copy = column();
        copy.addView(text(title, 16, INK, true));
        copy.addView(text(detail, 12, MUTED, false), margin(0, 4, 0, 0));
        top.addView(badge, new LinearLayout.LayoutParams(dp(40), dp(40)));
        top.addView(copy, weightedMargin(12, 0, 0, 0));
        top.addView(text(duration, 11, GREEN, true));
        card.addView(top);
        card.setOnClickListener(v -> click.run());
        return card;
    }

    private LinearLayout infoCard(String title, String description, String tag, Runnable click) {
        LinearLayout card = card(WHITE, 16);
        LinearLayout top = row();
        top.addView(text(title, 15, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (tag != null) top.addView(notePill(tag));
        card.addView(top);
        card.addView(text(description, 13, MUTED, false), margin(0, 6, 0, 0));
        if (click != null) card.setOnClickListener(v -> click.run());
        return card;
    }

    private LinearLayout videoCard(String title, String desc, Runnable click) {
        LinearLayout card = card(WHITE, 16);
        LinearLayout row = row();
        TextView thumb = text("▶", 18, WHITE, true);
        thumb.setGravity(Gravity.CENTER);
        thumb.setBackground(shape(0xFF263C35, 12, 0, 0));
        LinearLayout copy = column();
        copy.addView(text(title, 15, INK, true));
        copy.addView(text(desc, 12, MUTED, false), margin(0, 3, 0, 0));
        row.addView(thumb, new LinearLayout.LayoutParams(dp(52), dp(52)));
        row.addView(copy, weightedMargin(12, 0, 0, 0));
        card.addView(row);
        card.setOnClickListener(v -> click.run());
        return card;
    }

    private LinearLayout statCard(String label, String value, String hint) {
        LinearLayout card = card(WHITE, 16);
        card.addView(text(label, 12, MUTED, true));
        card.addView(text(value, 22, INK, true), margin(0, 5, 0, 1));
        card.addView(text(hint, 11, MUTED, false));
        return card;
    }

    private LinearLayout cardHeader(String title, String description) {
        LinearLayout card = card(GREEN_LIGHT, 16);
        card.addView(text(title, 15, GREEN, true));
        card.addView(text(description, 13, INK, false), margin(0, 6, 0, 0));
        return card;
    }

    private LinearLayout stepCard(int number, String copy) {
        LinearLayout card = card(WHITE, 14);
        LinearLayout line = row();
        TextView badge = text(String.valueOf(number), 13, WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(shape(GREEN, 18, 0, 0));
        line.addView(badge, new LinearLayout.LayoutParams(dp(28), dp(28)));
        line.addView(text(copy, 13, INK, false), weightedMargin(10, 0, 0, 0));
        card.addView(line);
        return card;
    }

    private LinearLayout breathDiagram() {
        LinearLayout card = card(0xFFE8EFE8, 18);
        card.addView(text("呼吸练习小图示", 12, GREEN, true));
        LinearLayout flow = row();
        String[] words = {"放松肩颈", "轻轻吸气", "均匀呼气"};
        for (int i = 0; i < words.length; i++) {
            TextView node = text(words[i], 12, INK, true);
            node.setGravity(Gravity.CENTER);
            node.setBackground(shape(WHITE, 12, 0, 0));
            flow.addView(node, new LinearLayout.LayoutParams(0, dp(42), 1));
            if (i < words.length - 1) {
                TextView arrow = text("→", 16, GREEN, true);
                arrow.setGravity(Gravity.CENTER);
                flow.addView(arrow, new LinearLayout.LayoutParams(dp(26), dp(42)));
            }
        }
        card.addView(flow, margin(0, 10, 0, 0));
        return card;
    }

    private LinearLayout chipRow(String[] labels) {
        LinearLayout row = row();
        for (int i = 0; i < labels.length; i++) {
            TextView chip = text(labels[i], 12, i == 0 ? WHITE : MUTED, i == 0);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            chip.setBackground(shape(i == 0 ? GREEN : WHITE, 20, BORDER, 1));
            row.addView(chip, wrapMargin(0, 0, 8, 0));
        }
        return row;
    }

    private LinearLayout sectionTitle(String title, String action, Runnable click) {
        LinearLayout row = row();
        row.addView(text(title, 16, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (action != null) {
            TextView link = text(action + "  ›", 12, GREEN, true);
            row.addView(link);
            if (click != null) link.setOnClickListener(v -> click.run());
        }
        return row;
    }

    private TextView notePill(String text) {
        TextView pill = text(text, 11, GREEN, true);
        pill.setPadding(dp(9), dp(5), dp(9), dp(5));
        pill.setBackground(shape(GREEN_LIGHT, 14, 0, 0));
        return pill;
    }

    private TextView noteBox(String text) {
        TextView box = text(text, 13, INK, false);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(shape(0xFFF0F0E8, 14, 0, 0));
        return box;
    }

    private LinearLayout card(int color, int radiusDp) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(shape(color, radiusDp, color == WHITE ? BORDER : 0, color == WHITE ? 1 : 0));
        return card;
    }

    private GradientDrawable shape(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private TextView button(String label, int background, int foreground, boolean bold) {
        TextView button = text(label, 14, foreground, bold);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), dp(13), dp(14), dp(13));
        button.setBackground(shape(background, 14, background == WHITE ? 0xFFEAE0D9 : background, background == WHITE ? 1 : 0));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private TextView actionButton(String label, int background, int foreground, View.OnClickListener click) {
        TextView button = text(label, 12, foreground, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackground(shape(background, 12, background == WHITE ? BORDER : background, background == WHITE ? 1 : 0));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(click);
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout.LayoutParams margin(int left, int top, int right, int bottom) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            setMargins(dp(left), dp(top), dp(right), dp(bottom));
        }};
    }

    private LinearLayout.LayoutParams weightedMargin(int left, int top, int right, int bottom) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1) {{
            setMargins(dp(left), dp(top), dp(right), dp(bottom));
        }};
    }

    private LinearLayout.LayoutParams wrapMargin(int left, int top, int right, int bottom) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            setMargins(dp(left), dp(top), dp(right), dp(bottom));
        }};
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void navigateBackOrExit() {
        if (audioSession != null) {
            if (audioSession.isRecording()) audioSession.stop();
            audioSession.stopPlayback();
        }
        if (!navigationHistory.isEmpty()) {
            NavigationState previous = navigationHistory.pop();
            contentHost.removeAllViews();
            contentHost.addView(previous.page, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            selectedTab = previous.selectedTab;
            recordingPageVisible = previous.recordingPageVisible;
            currentPageTitle = previous.title;
            currentPageTab = previous.selectedTab;
            currentPageRecordingVisible = previous.recordingPageVisible;
            refreshBottomBar();
            lastHomeSwipeAt = 0;
            return;
        }
        if (selectedTab != 0 || !"SingingBetter".equals(currentPageTitle)) {
            navigationHistory.clear();
            contentHost.removeAllViews();
            showTab(0);
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (lastHomeSwipeAt != 0 && now - lastHomeSwipeAt <= EXIT_CONFIRM_WINDOW_MS) {
            finish();
        } else {
            lastHomeSwipeAt = now;
            toast("再向左滑一次退出应用");
        }
    }

    private boolean isLeftSwipe(float x, float y) {
        float deltaX = x - touchDownX;
        float deltaY = y - touchDownY;
        return deltaX < -dp(88) && Math.abs(deltaX) > Math.abs(deltaY) * 1.2f;
    }

    private void cancelChildTouch(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchDownX = event.getX();
            touchDownY = event.getY();
            handlingSwipe = false;
        } else if (action == MotionEvent.ACTION_MOVE && !handlingSwipe && isLeftSwipe(event.getX(), event.getY())) {
            handlingSwipe = true;
            cancelChildTouch(event);
            return true;
        } else if (handlingSwipe) {
            if (action == MotionEvent.ACTION_UP) {
                handlingSwipe = false;
                navigateBackOrExit();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                handlingSwipe = false;
            }
            return true;
        } else if (action == MotionEvent.ACTION_UP && isLeftSwipe(event.getX(), event.getY())) {
            cancelChildTouch(event);
            navigateBackOrExit();
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onBackPressed() {
        navigateBackOrExit();
    }

    @Override
    protected void onDestroy() {
        if (audioSession != null) audioSession.release();
        super.onDestroy();
    }
}
