package com.singingbetter.app;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 录音文件的命名、解析与读写。
 *
 * 命名规则：<标签>_<yyyyMMdd-HHmm>.wav，例如「长音稳定_20261010-1145.wav」。
 * 旧版本保存的 take_<时间戳>.wav 仍然可读、可回放、可删除，只是显示成统一的默认标签。
 */
final class RecordingStore {
    static final int SAMPLE_RATE = 44100;
    static final String DEFAULT_LABEL = "练习录音";
    private static final String EXTENSION = ".wav";
    private static final String LEGACY_PREFIX = "take_";
    private static final Pattern TIMESTAMP_SUFFIX = Pattern.compile("_\\d{8}-\\d{4}(?:-\\d+)?$");
    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|\\r\\n\\t]");

    static final class Item {
        final File file;
        final String label;
        final long timeMillis;
        final long durationMillis;
        final long sizeBytes;

        Item(File file, String label, long timeMillis, long durationMillis, long sizeBytes) {
            this.file = file;
            this.label = label;
            this.timeMillis = timeMillis;
            this.durationMillis = durationMillis;
            this.sizeBytes = sizeBytes;
        }
    }

    private RecordingStore() { }

    static List<Item> list(File directory) {
        File[] files = directory == null ? null : directory.listFiles();
        List<Item> items = new ArrayList<>();
        if (files == null) return items;
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(EXTENSION)) continue;
            long pcmBytes = pcmLength(file);
            items.add(new Item(file, labelOf(file), file.lastModified(),
                    durationMillis(pcmBytes), file.length()));
        }
        Collections.sort(items, new Comparator<Item>() {
            @Override public int compare(Item left, Item right) {
                if (left.timeMillis == right.timeMillis) return left.file.getName().compareTo(right.file.getName());
                return left.timeMillis > right.timeMillis ? -1 : 1;
            }
        });
        return items;
    }

    static long totalSize(List<Item> items) {
        long total = 0;
        for (Item item : items) total += item.sizeBytes;
        return total;
    }

    /** 生成新录音文件：标签_日期时间.wav，重名时追加 -2、-3。 */
    static File newFile(File directory, String label, long timeMillis) {
        String suffix = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date(timeMillis));
        String base = sanitize(label) + "_" + suffix;
        File target = new File(directory, base + EXTENSION);
        int index = 2;
        while (target.exists()) {
            target = new File(directory, base + "-" + index + EXTENSION);
            index++;
        }
        return target;
    }

    /** 重命名：保留原时间戳后缀，返回新文件；失败返回 null。 */
    static File rename(File file, String newLabel) {
        File parent = file.getParentFile();
        if (parent == null) return null;
        String name = file.getName();
        String stem = name.substring(0, name.length() - EXTENSION.length());
        String suffix = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date(file.lastModified()));
        java.util.regex.Matcher matcher = TIMESTAMP_SUFFIX.matcher(stem);
        if (matcher.find()) suffix = stem.substring(matcher.start() + 1);
        String base = sanitize(newLabel) + "_" + suffix;
        File target = new File(parent, base + EXTENSION);
        if (target.equals(file)) return file;
        int index = 2;
        while (target.exists()) {
            target = new File(parent, base + "-" + index + EXTENSION);
            index++;
        }
        return file.renameTo(target) ? target : null;
    }

    static boolean delete(File file) {
        return file != null && file.delete();
    }

    static boolean deleteAll(File directory) {
        List<Item> items = list(directory);
        boolean all = true;
        for (Item item : items) all &= delete(item.file);
        return all;
    }

    /** 从文件名还原标签；旧 take_ 文件统一显示默认标签。 */
    static String labelOf(File file) {
        String name = file.getName();
        if (!name.endsWith(EXTENSION)) return DEFAULT_LABEL;
        String stem = name.substring(0, name.length() - EXTENSION.length());
        if (stem.startsWith(LEGACY_PREFIX)) return DEFAULT_LABEL;
        String label = TIMESTAMP_SUFFIX.matcher(stem).replaceFirst("");
        return label.isEmpty() ? DEFAULT_LABEL : label;
    }

    static String sanitize(String label) {
        if (label == null) return DEFAULT_LABEL;
        String cleaned = ILLEGAL.matcher(label.trim()).replaceAll("_").replaceAll("\\s+", " ");
        if (cleaned.length() > 40) cleaned = cleaned.substring(0, 40).trim();
        return cleaned.isEmpty() ? DEFAULT_LABEL : cleaned;
    }

    static long durationMillis(long pcmBytes) {
        if (pcmBytes <= 0) return 0;
        return Math.round(1000.0 * (pcmBytes / 2.0) / SAMPLE_RATE);
    }

    /** 只扫描头部，拿到 data 块长度（字节），用于列表里算时长，不读完整音频。 */
    static long pcmLength(File file) {
        try (RandomAccessFile access = new RandomAccessFile(file, "r")) {
            long[] data = locateDataChunk(access);
            return data == null ? 0 : data[1];
        } catch (IOException ignored) {
            return 0;
        }
    }

    /** 读出 16-bit PCM 数据（不含 44 字节头），用于回放。 */
    static byte[] readPcm(File file) throws IOException {
        try (RandomAccessFile access = new RandomAccessFile(file, "r")) {
            long[] data = locateDataChunk(access);
            if (data == null) throw new IOException("WAV 文件没有音频数据");
            int length = (int) Math.min(data[1], Integer.MAX_VALUE);
            if (length <= 0) throw new IOException("WAV 文件是空的");
            byte[] pcm = new byte[length];
            access.seek(data[0]);
            access.readFully(pcm);
            return pcm;
        }
    }

    /** 返回 {dataOffset, dataLength}；解析失败返回 null。 */
    private static long[] locateDataChunk(RandomAccessFile access) throws IOException {
        byte[] header = new byte[12];
        if (access.read(header) < 12) return null;
        if (!"RIFF".equals(ascii(header, 0, 4)) || !"WAVE".equals(ascii(header, 8, 4))) return null;
        while (true) {
            byte[] chunk = new byte[8];
            if (access.read(chunk) < 8) return null;
            long size = readLittleEndianInt(chunk, 4) & 0xffffffffL;
            if ("data".equals(ascii(chunk, 0, 4))) {
                long available = access.length() - access.getFilePointer();
                long length = Math.max(0, Math.min(size, available));
                return new long[]{access.getFilePointer(), length};
            }
            if (size <= 0) continue;
            long skipped = access.skipBytes((int) Math.min(size, Integer.MAX_VALUE));
            if (skipped <= 0) return null;
        }
    }

    private static long readLittleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
                | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16)
                | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }
}
