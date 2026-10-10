package com.singingbetter.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class AudioSession {
    interface Listener {
        void onPitch(float hz);
        void onMessage(String message);
        void onSaved(File file, int voicedFrames, int inTuneFrames, float averageHz, long durationMillis);
    }

    private static final int SAMPLE_RATE = 44100;
    private static final int FRAME_SIZE = 4096;
    private final Listener listener;
    private final File recordingsDirectory;
    private volatile boolean recording;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private ByteArrayOutputStream pcmBytes;
    private float targetHz;
    private String pendingLabel = RecordingStore.DEFAULT_LABEL;
    private int voicedFrames;
    private int inTuneFrames;
    private double frequencyTotal;
    private File lastFile;
    private byte[] lastPcm;
    private long recordingStartedAtMs;
    private AudioTrack playbackTrack;

    AudioSession(File filesDirectory, Listener listener) {
        this.recordingsDirectory = new File(filesDirectory, "recordings");
        this.listener = listener;
        restoreLastRecording();
    }

    private synchronized void restoreLastRecording() {
        File[] files = recordingsDirectory.listFiles((dir, name) -> name.endsWith(".wav"));
        if (files == null || files.length == 0) return;
        File latest = files[0];
        for (File candidate : files) {
            if (candidate.lastModified() > latest.lastModified()) latest = candidate;
        }
        if (latest.length() <= 44) return;
        try {
            lastFile = latest;
            lastPcm = RecordingStore.readPcm(latest);
        } catch (IOException ignored) {
            lastFile = null;
            lastPcm = null;
        }
    }

    synchronized void start(float expectedHz, String label) throws Exception {
        if (recording) return;
        if (!recordingsDirectory.exists() && !recordingsDirectory.mkdirs()) {
            throw new IOException("无法创建录音目录");
        }
        targetHz = expectedHz;
        pendingLabel = label == null || label.trim().isEmpty() ? RecordingStore.DEFAULT_LABEL : label;
        voicedFrames = 0;
        inTuneFrames = 0;
        frequencyTotal = 0;
        pcmBytes = new ByteArrayOutputStream();
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) throw new IOException("设备不支持当前录音格式");
        int bufferBytes = Math.max(minBuffer, FRAME_SIZE * 2 * 2);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            audioRecord = null;
            throw new IOException("麦克风初始化失败");
        }
        audioRecord.startRecording();
        recordingStartedAtMs = System.currentTimeMillis();
        recording = true;
        recordingThread = new Thread(this::captureLoop, "SingingBetter-AudioCapture");
        recordingThread.start();
    }

    private void captureLoop() {
        short[] frame = new short[FRAME_SIZE];
        byte[] littleEndian = new byte[FRAME_SIZE * 2];
        try {
            while (recording) {
                AudioRecord active = audioRecord;
                if (active == null) break;
                int count = active.read(frame, 0, frame.length, AudioRecord.READ_BLOCKING);
                if (count < 0) {
                    listener.onMessage("录音读取异常（" + count + "）");
                    break;
                }
                if (count == 0) continue;
                for (int i = 0; i < count; i++) {
                    int value = frame[i];
                    littleEndian[i * 2] = (byte) (value & 0xff);
                    littleEndian[i * 2 + 1] = (byte) ((value >> 8) & 0xff);
                }
                synchronized (this) {
                    if (pcmBytes != null) pcmBytes.write(littleEndian, 0, count * 2);
                }
                float hz = PitchDetector.estimateHz(frame, count, SAMPLE_RATE);
                if (hz > 0) {
                    synchronized (this) {
                        voicedFrames++;
                        frequencyTotal += hz;
                        if (targetHz > 0 && Math.abs(PitchDetector.centsFrom(hz, targetHz)) <= 50) inTuneFrames++;
                    }
                }
                listener.onPitch(hz);
            }
        } catch (Exception error) {
            listener.onMessage("录音中断：" + (error.getMessage() == null ? "未知原因" : error.getMessage()));
        }
    }

    void stop() {
        AudioRecord active;
        Thread worker;
        synchronized (this) {
            if (!recording && audioRecord == null) return;
            recording = false;
            active = audioRecord;
            worker = recordingThread;
        }
        if (active != null) {
            try { active.stop(); } catch (IllegalStateException ignored) { }
        }
        if (worker != null && worker != Thread.currentThread()) {
            try { worker.join(800); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }

        byte[] data;
        int savedVoicedFrames;
        int savedInTuneFrames;
        float averageHz;
        long durationMillis;
        synchronized (this) {
            if (active != null) {
                active.release();
                if (audioRecord == active) audioRecord = null;
            }
            recordingThread = null;
            data = pcmBytes == null ? new byte[0] : pcmBytes.toByteArray();
            pcmBytes = null;
            lastPcm = data;
            savedVoicedFrames = voicedFrames;
            savedInTuneFrames = inTuneFrames;
            averageHz = voicedFrames == 0 ? 0 : (float) (frequencyTotal / voicedFrames);
            durationMillis = Math.max(0, System.currentTimeMillis() - recordingStartedAtMs);
        }
        try {
            File savedFile = RecordingStore.newFile(recordingsDirectory, pendingLabel, System.currentTimeMillis());
            writeWav(savedFile, data);
            synchronized (this) { lastFile = savedFile; }
            listener.onSaved(savedFile, savedVoicedFrames, savedInTuneFrames, averageHz, durationMillis);
        } catch (IOException error) {
            listener.onMessage("录音已结束，但保存失败：" + error.getMessage());
        }
    }

    void playReferenceTone(float frequencyHz) throws Exception {
        if (frequencyHz < 60 || frequencyHz > 1200) throw new IOException("参考音频率超出支持范围");
        short[] tone = new short[SAMPLE_RATE];
        for (int i = 0; i < tone.length; i++) {
            double envelope = Math.min(1.0, Math.min(i / 220.0, (tone.length - i) / 220.0));
            tone[i] = (short) (Math.sin(2.0 * Math.PI * frequencyHz * i / SAMPLE_RATE) * 5200 * envelope);
        }
        byte[] data = new byte[tone.length * 2];
        for (int i = 0; i < tone.length; i++) {
            data[i * 2] = (byte) (tone[i] & 0xff);
            data[i * 2 + 1] = (byte) ((tone[i] >> 8) & 0xff);
        }
        int minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) throw new IOException("设备不支持音频播放");
        AudioTrack track = new AudioTrack(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(), new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(), Math.max(minBuffer, data.length), AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
        track.write(data, 0, data.length, AudioTrack.WRITE_BLOCKING);
        track.play();
        Thread playback = new Thread(() -> {
            try {
                while (track.getPlaybackHeadPosition() < tone.length && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    Thread.sleep(20);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                if (track.getState() == AudioTrack.STATE_INITIALIZED) {
                    try { track.stop(); } catch (IllegalStateException ignored) { }
                    track.release();
                }
            }
        }, "SingingBetter-ReferenceTone");
        playback.start();
    }

    synchronized void playLast() throws Exception {
        if (lastPcm == null || lastPcm.length == 0) throw new IOException("还没有可回放的录音");
        playPcm(lastPcm);
    }

    /** 回放磁盘上的任意一段录音（录音管理页用）。 */
    synchronized void playFile(File file) throws Exception {
        if (file == null || !file.exists()) throw new IOException("录音文件已不存在");
        playPcm(RecordingStore.readPcm(file));
    }

    private void playPcm(byte[] data) throws Exception {
        stopPlayback();
        int minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) throw new IOException("设备不支持音频回放");
        AudioTrack track = new AudioTrack(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(), new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(), Math.max(minBuffer, data.length), AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
        track.write(data, 0, data.length, AudioTrack.WRITE_BLOCKING);
        track.play();
        playbackTrack = track;
        final int frames = data.length / 2;
        Thread playback = new Thread(() -> {
            try {
                while (track.getPlaybackHeadPosition() < frames && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    Thread.sleep(30);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                try { track.stop(); } catch (IllegalStateException ignored) { }
                track.release();
                synchronized (AudioSession.this) {
                    if (playbackTrack == track) playbackTrack = null;
                }
            }
        }, "SingingBetter-AudioPlayback");
        playback.start();
    }

    /** 离开页面或开始新回放前调用，避免多段录音同时出声。 */
    synchronized void stopPlayback() {
        AudioTrack track = playbackTrack;
        playbackTrack = null;
        if (track == null) return;
        try { track.stop(); } catch (IllegalStateException ignored) { }
        track.release();
    }

    /** 删除录音后调用：若删掉的正是缓存里的那一条，就清掉缓存，避免继续回放到已删除的音频。 */
    synchronized void forget(File file) {
        if (file == null) return;
        if (file.equals(lastFile)) {
            lastFile = null;
            lastPcm = null;
        }
    }

    synchronized File getLastFile() { return lastFile; }
    synchronized boolean isRecording() { return recording; }
    File recordingsDirectory() { return recordingsDirectory; }

    private static void writeWav(File file, byte[] pcm) throws IOException {
        int byteRate = SAMPLE_RATE * 2;
        try (FileOutputStream out = new FileOutputStream(file)) {
            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put(new byte[]{'R','I','F','F'});
            header.putInt(36 + pcm.length);
            header.put(new byte[]{'W','A','V','E','f','m','t',' '});
            header.putInt(16);
            header.putShort((short) 1);
            header.putShort((short) 1);
            header.putInt(SAMPLE_RATE);
            header.putInt(byteRate);
            header.putShort((short) 2);
            header.putShort((short) 16);
            header.put(new byte[]{'d','a','t','a'});
            header.putInt(pcm.length);
            out.write(header.array());
            out.write(pcm);
        }
    }

    synchronized void release() {
        stopPlayback();
        if (recording || audioRecord != null) stop();
    }
}
