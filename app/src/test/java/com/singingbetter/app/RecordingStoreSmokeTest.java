package com.singingbetter.app;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** RecordingStore 的冒烟测试：纯 Java，不依赖 Android，用 javac/java 直接跑。 */
public final class RecordingStoreSmokeTest {

    public static void main(String[] args) throws Exception {
        File directory = new File(System.getProperty("java.io.tmpdir"), "singingbetter_store_" + System.currentTimeMillis());
        if (!directory.exists() && !directory.mkdirs()) throw new AssertionError("无法创建临时目录");

        File legacy = writeWav(directory, "take_1700000000000.wav", 3);
        File modern = writeWav(directory, "长音稳定_20261010-1145.wav", 2);
        modern.setLastModified(System.currentTimeMillis());
        Thread.sleep(20);
        File newest = writeWav(directory, "自由试唱_20261010-1200.wav", 1);
        newest.setLastModified(System.currentTimeMillis() + 5000);

        List<RecordingStore.Item> items = RecordingStore.list(directory);
        expect("列出 3 段录音", items.size() == 3, items.size());
        expect("按时间倒序", "自由试唱".equals(items.get(0).label), items.get(0).label);

        boolean legacySeen = false;
        long modernDuration = -1;
        for (RecordingStore.Item item : items) {
            if (item.file.getName().equals(legacy.getName())) {
                legacySeen = true;
                expect("旧 take_ 文件名回退为默认标签", RecordingStore.DEFAULT_LABEL.equals(item.label), item.label);
                expect("旧文件时长仍可解析", item.durationMillis == 3000L, item.durationMillis);
            }
            if (item.file.getName().equals(modern.getName())) modernDuration = item.durationMillis;
        }
        expect("旧文件出现在列表中", legacySeen, legacySeen);
        expect("新文件时长（2 秒）", Math.abs(modernDuration - 2000L) <= 20, modernDuration);
        expect("总占用大于 0", RecordingStore.totalSize(items) > 500000, RecordingStore.totalSize(items));

        File created = RecordingStore.newFile(directory, "音准跟唱", System.currentTimeMillis());
        expect("新建文件名带标签", RecordingStore.labelOf(created).equals("音准跟唱"), created.getName());

        File renamed = RecordingStore.rename(modern, "我的第一次长音");
        expect("重命名成功", renamed != null && renamed.exists(), renamed == null ? "null" : renamed.getName());
        expect("重命名保留时间戳后缀", renamed != null && renamed.getName().contains("20261010-1145"),
                renamed == null ? "null" : renamed.getName());
        expect("重命名后标签正确", renamed != null && "我的第一次长音".equals(RecordingStore.labelOf(renamed)),
                renamed == null ? "null" : RecordingStore.labelOf(renamed));

        expect("非法字符被清洗", "a_b_c__".equals(RecordingStore.sanitize("a/b:c*?")), RecordingStore.sanitize("a/b:c*?"));
        expect("空标签回退", RecordingStore.DEFAULT_LABEL.equals(RecordingStore.sanitize("  ")), RecordingStore.sanitize("  "));
        expect("读回 PCM 长度", RecordingStore.readPcm(newest).length == 44100 * 2, RecordingStore.readPcm(newest).length);

        File same = RecordingStore.rename(newest, "自由试唱");
        expect("改成同名不报错", same != null && same.exists(), same == null ? "null" : same.getName());

        expect("删除成功", RecordingStore.delete(legacy) && !legacy.exists(), !legacy.exists());
        RecordingStore.deleteAll(directory);
        expect("清空后列表为空", RecordingStore.list(directory).isEmpty(), RecordingStore.list(directory).size());

        System.out.println("RecordingStore 冒烟测试全部通过");
    }

    private static File writeWav(File directory, String name, int seconds) throws Exception {
        byte[] pcm = new byte[44100 * 2 * seconds];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i % 251);
        File file = new File(directory, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put(new byte[]{'R','I','F','F'});
            header.putInt(36 + pcm.length);
            header.put(new byte[]{'W','A','V','E','f','m','t',' '});
            header.putInt(16);
            header.putShort((short) 1);
            header.putShort((short) 1);
            header.putInt(44100);
            header.putInt(44100 * 2);
            header.putShort((short) 2);
            header.putShort((short) 16);
            header.put(new byte[]{'d','a','t','a'});
            header.putInt(pcm.length);
            out.write(header.array());
            out.write(pcm);
        }
        return file;
    }

    private static void expect(String name, boolean ok, Object actual) {
        if (!ok) throw new AssertionError(name + " 失败，实际：" + actual);
        System.out.println("ok  " + name + " -> " + actual);
    }
}
