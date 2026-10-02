package com.allmusic.client.audio;

import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * B站 DASH 音频（.m4s）播放器：流式解析 fragmented MP4，取出 AAC 帧交给 JAAD 解码成 PCM。
 *
 * 为什么不能直接丢给 JLayer：B站独立音轨是 fMP4 容器里的 AAC（不是 MP3），
 * JLayer 只认 MP3；JDK 的 javax.sound 也不带 AAC 解码器。
 *
 * 实测 B站 m4s 的结构（BV1zmYP6aEdH / 192K 音轨）：
 *   ftyp | moov(trak→edts/elst、mdia→minf→stbl→stsd(mp4a→esds)) | mvex | sidx | (moof(mfhd+traf(tfhd,tfdt,trun)) + mdat) × N
 *   - tfhd: default-base-is-moof，无 base_data_offset → 样本数据基址 = moof 起始
 *   - trun: data_offset + 每样本 sample_size
 *   - 于是「样本起点 = moof 起点 + trun.data_offset」，实测正好等于紧邻 mdat 的负载起点
 *
 * 本类按盒子顺序流式读取，不把整个文件读进内存（长视频也不怕），
 * 只在 moov/moof 这两个小盒子上做内存解析；mdat 的数据按 trun 描述的样本逐个消费。
 */
public class BilibiliM4sSource implements PcmFrameSource {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-BiliAudio");

    /** AAC 编码器预热样本数：首帧输出的是预热数据，不丢会和歌词差约 23ms（elst 未给出时用这个） */
    private static final int DEFAULT_PRIMING = 1024;
    private static final long MAX_MEMORY_BOX = 8L * 1024 * 1024;
    /** 单个 AAC 帧不可能这么大（192K 音频每帧也就几百字节），用于挡住损坏的样本表 */
    private static final int MAX_SAMPLE_SIZE = 1024 * 1024;

    private final InputStream in;

    /** 下一个待读字节在文件中的绝对偏移 */
    private long pos;
    /** 当前 mdat 结束的绝对偏移（样本读完后跳到这） */
    private long mdatEnd;
    private FragmentSpec fragment;

    /** 允许连续解码失败的帧数，超过就认为整条流解不了，直接报错 */
    private static final int MAX_DECODE_ERRORS = 32;

    private Decoder decoder;
    private final SampleBuffer buffer = new SampleBuffer();
    private int primingRemaining = DEFAULT_PRIMING;
    private int decodeErrors;

    public BilibiliM4sSource(InputStream in) {
        this.in = in;
        buffer.setBigEndian(false); // 让 JAAD 直接输出小端 16bit PCM，省一次逐样本翻转
    }

    @Override
    public PcmFrame next() throws Exception {
        while (true) {
            if (fragment != null) {
                if (fragment.index < fragment.sizes.size()) {
                    int size = fragment.sizes.get(fragment.index++);
                    if (size <= 0 || size > MAX_SAMPLE_SIZE) {
                        throw new IOException("异常的音频样本大小: " + size);
                    }
                    byte[] sample = readFully(size);
                    if (sample == null) throw new EOFException("B站音频分片数据被截断");
                    if (fragment.index >= fragment.sizes.size()) {
                        skipTo(mdatEnd);
                        fragment = null;
                    }
                    PcmFrame frame;
                    try {
                        frame = decodeSample(sample);
                        decodeErrors = 0;
                    } catch (Exception e) {
                        // 个别帧损坏时跳过继续放（不因一帧毁了整首歌），但连续坏太多说明格式根本不支持
                        if (++decodeErrors > MAX_DECODE_ERRORS) {
                            throw new IOException("音频解码连续失败 " + decodeErrors + " 帧，放弃播放: " + e.getMessage(), e);
                        }
                        logger.warn("音频帧解码失败(第{}次)，跳过该帧: {}", decodeErrors, e.getMessage());
                        continue;
                    }
                    if (frame != null) return frame;
                    continue; // 该帧无输出（预热帧），继续下一帧
                }
                fragment = null;
            }
            if (!advance()) return null; // 文件读完
        }
    }

    // ── 盒子遍历 ──────────────────────────────────────────

    /** 前进到下一段样本数据；返回 false 表示流结束 */
    private boolean advance() throws IOException {
        while (true) {
            Box box = readBox();
            if (box == null) return false;

            switch (box.type) {
                case "moov":
                    parseMoov(readPayload(box));
                    skipTo(box.end);
                    break;

                case "moof":
                    fragment = parseFragment(readPayload(box), box.start);
                    if (fragment.sizes.isEmpty()) throw new IOException("B站音频分片中没有样本");
                    // 样本数据紧跟在 mdat 里
                    Box data = readBox();
                    if (data == null || !"mdat".equals(data.type)) {
                        throw new IOException("音频分片后没有 mdat 数据盒（可能不是 B站 的 DASH 音频流）");
                    }
                    long sampleStart = fragment.dataOffset >= 0 ? fragment.dataOffset : data.payloadStart;
                    if (sampleStart < pos || sampleStart > data.end) {
                        throw new IOException("样本数据偏移越界: " + sampleStart);
                    }
                    skipTo(sampleStart);
                    mdatEnd = data.end;
                    return true;

                default:
                    skipTo(box.end);
                    break;
            }
        }
    }

    private static final class Box {
        final String type;
        final long start, payloadStart, end;

        Box(String type, long start, long payloadStart, long end) {
            this.type = type;
            this.start = start;
            this.payloadStart = payloadStart;
            this.end = end;
        }
    }

    private Box readBox() throws IOException {
        long start = pos;
        byte[] header = readFully(8);
        if (header == null) return null; // 干净结束

        long size = u32(header, 0);
        String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
        long payloadStart = start + 8;

        if (size == 1) {
            byte[] ext = readFully(8);
            if (ext == null) throw new EOFException("盒子 largesize 不完整: " + type);
            size = u64(ext, 0);
            payloadStart = start + 16;
        } else if (size == 0) {
            size = -1; // 一直到文件末尾
        }

        long end = size < 0 ? Long.MAX_VALUE : start + size;
        if (end < payloadStart) throw new IOException("非法盒子大小: " + type + " size=" + size);
        return new Box(type, start, payloadStart, end);
    }

    private byte[] readPayload(Box box) throws IOException {
        long len = box.end - box.payloadStart;
        if (len > MAX_MEMORY_BOX) throw new IOException("盒子过大，无法解析: " + box.type + " (" + len + ")");
        byte[] data = readFully((int) len);
        if (data == null) throw new EOFException("盒子数据不完整: " + box.type);
        return data;
    }

    /** 读满 n 字节；文件在盒子边界干净结束时返回 null */
    private byte[] readFully(int n) throws IOException {
        if (n == 0) return new byte[0];
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r < 0) {
                if (read == 0) return null;
                throw new EOFException("音频流意外结束（需要 " + n + " 字节，只读到 " + read + "）");
            }
            read += r;
        }
        pos += n;
        return buf;
    }

    private void skipTo(long target) throws IOException {
        while (pos < target) {
            long n = in.skip(target - pos);
            if (n > 0) {
                pos += n;
                continue;
            }
            if (in.read() < 0) return; // 流结束，后续 readBox 自然会返回 null
            pos++;
        }
    }

    // ── moov：取 AudioSpecificConfig 与预热样本数 ─────────────

    private void parseMoov(byte[] d) throws IOException {
        MemBox root = new MemBox("", 0, d.length);

        for (MemBox trak : children(d, root)) {
            if (!"trak".equals(trak.type)) continue;

            MemBox stsd = find(d, trak, "mdia", "minf", "stbl", "stsd");
            if (stsd == null) continue;

            MemBox entry = firstChild(d, stsd, 8); // stsd 是 FullBox：跳过 version/flags + entry_count
            if (entry == null || !"mp4a".equals(entry.type)) continue;

            // AudioSampleEntry 头部有 28 字节固定字段（reserved/data_ref_index/声道数/采样率等），子盒子在它之后
            if (entry.payloadOff + 28 > entry.end) continue;
            MemBox entryBody = new MemBox("mp4a-body", entry.payloadOff + 28, entry.end);
            MemBox esds = find(d, entryBody, "esds");
            if (esds == null) continue;

            byte[] asc = findDecoderSpecificInfo(d, esds);
            if (asc == null) {
                logger.warn("esds 中没有找到 AudioSpecificConfig");
                continue;
            }

            // JAAD 只支持 AAC-LC（Main/LTP 也别指望）。HE-AAC(SBR)/HE-AACv2(PS) 会一路解码报错，
            // 不如在这里就说清楚原因和解决办法（服务端把 sources.bilibili.play-mode 改成 transcode）。
            int aot = (asc[0] >> 3) & 0x1F;
            if (aot != 1 && aot != 2) {
                throw new IOException("该B站音轨不是 AAC-LC（audioObjectType=" + aot
                        + (aot == 5 || aot == 29 ? "，HE-AAC/SBR" : "") + "），客户端无法解码；"
                        + "请在服务端 config.yml 里把 sources.bilibili.play-mode 设为 transcode 让服务端转码");
            }

            try {
                decoder = new Decoder(asc);
            } catch (Exception e) {
                throw new IOException("AAC 解码器初始化失败: " + e.getMessage(), e);
            }
            primingRemaining = readPriming(d, trak);

            logger.info("B站音频音轨已就绪: profile={}, 采样率={}Hz, 声道={}, 丢弃预热样本={}",
                    decoder.getConfig().getProfile(),
                    decoder.getConfig().getSampleFrequency().getFrequency(),
                    decoder.getConfig().getChannelConfiguration().getChannelCount(),
                    primingRemaining);
            return;
        }
        throw new IOException("moov 中没有找到可解码的 AAC(mp4a) 音轨");
    }

    /**
     * 读取编辑列表里的 media_time 作为「预热样本数」。
     * B站实测 elst 的 media_time 为 0（没有声明），此时用 AAC 标准预热值 1024。
     */
    private static int readPriming(byte[] d, MemBox trak) {
        MemBox elst = find(d, trak, "edts", "elst");
        if (elst == null) return DEFAULT_PRIMING;

        int p = elst.payloadOff;
        if (p + 8 > elst.end) return DEFAULT_PRIMING;

        int version = d[p] & 0xFF;
        p += 4;
        long count = u32(d, p);
        p += 4;
        if (count <= 0) return DEFAULT_PRIMING;

        try {
            long mediaTime;
            if (version == 1) {
                p += 8; // segment_duration(8)
                mediaTime = u64(d, p);
            } else {
                p += 4; // segment_duration(4)
                mediaTime = u32(d, p);
            }
            if (mediaTime > 0 && mediaTime <= Integer.MAX_VALUE) return (int) mediaTime;
        } catch (Exception ignored) {
        }
        return DEFAULT_PRIMING;
    }

    /**
     * 从 esds 里取出 DecoderSpecificInfo（= AudioSpecificConfig）。
     * 描述符长度用的是「可变长编码」（每字节最高位表示还有后续字节）。
     */
    private static byte[] findDecoderSpecificInfo(byte[] d, MemBox esds) {
        int p = esds.payloadOff + 4; // FullBox version/flags
        int end = esds.end;

        while (p < end) {
            int tag = d[p++] & 0xFF;
            long len = 0;
            int b;
            do {
                if (p >= end) return null;
                b = d[p++] & 0xFF;
                len = (len << 7) | (b & 0x7F);
            } while ((b & 0x80) != 0);

            long content = p;
            long contentEnd = p + len;
            if (contentEnd > end) return null;

            if (tag == 0x03) { // ES_Descriptor：跳过 ES_ID/flags 后继续找内层描述符
                if (content + 3 > contentEnd) return null;
                int flags = d[(int) content + 2] & 0xFF;
                long q = content + 3;
                if ((flags & 0x80) != 0) q += 2;                                  // dependsOn_ES_ID
                if ((flags & 0x40) != 0) {                                        // URL
                    if (q >= contentEnd) return null;
                    q += 1 + (d[(int) q] & 0xFF);
                }
                if ((flags & 0x20) != 0) q += 2;                                  // OCR_ES_Id
                p = (int) Math.min(q, contentEnd);
                continue;
            }
            if (tag == 0x04) { // DecoderConfigDescriptor：13 字节固定字段后是内层描述符
                p = (int) Math.min(content + 13, contentEnd);
                continue;
            }
            if (tag == 0x05) { // DecoderSpecificInfo
                byte[] asc = new byte[(int) len];
                System.arraycopy(d, (int) content, asc, 0, (int) len);
                return asc;
            }
            p = (int) contentEnd;
        }
        return null;
    }

    // ── moof：取这一段的样本大小与数据起点 ────────────────────

    private static final class FragmentSpec {
        final List<Integer> sizes = new ArrayList<>();
        long dataOffset = -1;
        int index;
    }

    private static FragmentSpec parseFragment(byte[] d, long moofStart) throws IOException {
        FragmentSpec spec = new FragmentSpec();
        MemBox root = new MemBox("", 0, d.length);
        long cursor = -1;

        for (MemBox traf : children(d, root)) {
            if (!"traf".equals(traf.type)) continue;

            long base = moofStart; // 没有 base_data_offset 时，基址 = 本 moof 起点（default-base-is-moof）
            int defaultSize = 0;

            MemBox tfhd = firstChildOfType(d, traf, "tfhd");
            if (tfhd != null && tfhd.payloadOff + 12 <= tfhd.end) {
                int p = tfhd.payloadOff;
                int flags = readFullBoxFlags(d, p); // payload 头是 1 字节 version + 3 字节 flags
                p += 4;
                p += 4; // track_ID
                if ((flags & 0x000001) != 0) {
                    base = u64(d, p);
                    p += 8;
                }
                if ((flags & 0x000002) != 0) p += 4;          // sample_description_index
                if ((flags & 0x000008) != 0) p += 4;          // default_sample_duration
                if ((flags & 0x000010) != 0) {
                    defaultSize = (int) u32(d, p);
                    p += 4;
                }
            }

            for (MemBox trun : children(d, traf)) {
                if (!"trun".equals(trun.type)) continue;
                if (trun.payloadOff + 12 > trun.end) throw new IOException("trun 盒子不完整");

                int p = trun.payloadOff;
                int flags = readFullBoxFlags(d, p);
                p += 4;
                long count = u32(d, p);
                p += 4;

                boolean hasDataOffset = (flags & 0x000001) != 0;
                boolean firstSampleFlags = (flags & 0x000004) != 0;
                boolean hasDuration = (flags & 0x000100) != 0;
                boolean hasSize = (flags & 0x000200) != 0;
                boolean hasFlags = (flags & 0x000400) != 0;
                boolean hasCto = (flags & 0x000800) != 0;

                long runStart;
                if (hasDataOffset) {
                    runStart = base + u32(d, p);
                    p += 4;
                } else {
                    runStart = cursor >= 0 ? cursor : base;
                }
                if (firstSampleFlags) p += 4;

                long runBytes = 0;
                for (long i = 0; i < count; i++) {
                    // 先算出本样本要占几个字节、检查过界再读：
                    // 旧代码是先 u32(d,p) 读完再检查 p > trun.end，越界那一次读到的其实是
                    // 紧邻盒子的字节（结果不可知），运气差就解析出一个荒谬的样本大小。
                    int needed = (hasDuration ? 4 : 0) + (hasSize ? 4 : 0)
                            + (hasFlags ? 4 : 0) + (hasCto ? 4 : 0);
                    if (p + needed > trun.end) throw new IOException("trun 样本表越界");

                    if (hasDuration) p += 4;
                    int size;
                    if (hasSize) {
                        size = (int) u32(d, p);
                        p += 4;
                    } else {
                        size = defaultSize;
                    }
                    if (hasFlags) p += 4;
                    if (hasCto) p += 4;
                    if (size <= 0) throw new IOException("样本大小为 0（无法定位音频数据）");
                    spec.sizes.add(size);
                    runBytes += size;
                }

                if (spec.dataOffset < 0) spec.dataOffset = runStart;
                cursor = runStart + runBytes;
            }
        }
        return spec;
    }

    // ── 解码 ─────────────────────────────────────────────

    private PcmFrame decodeSample(byte[] sample) throws Exception {
        if (decoder == null) throw new IOException("音轨信息缺失（moov 尚未解析）");

        int adts = adtsHeaderLength(sample);
        byte[] frame = sample;
        if (adts > 0 && adts < sample.length) {
            frame = new byte[sample.length - adts];
            System.arraycopy(sample, adts, frame, 0, frame.length);
        }

        decoder.decodeFrame(frame, buffer);

        byte[] pcm = buffer.getData();
        if (pcm == null || pcm.length < 2) return null;

        int channels = Math.max(1, buffer.getChannels());
        int sampleRate = buffer.getSampleRate();
        if (sampleRate <= 0) return null;

        int total = pcm.length / 2;         // 16bit 小端，多声道交错
        int perChannel = total / channels;

        int dropFrames = 0;
        if (primingRemaining > 0) {
            dropFrames = Math.min(primingRemaining, perChannel);
            primingRemaining -= dropFrames;
        }
        int drop = dropFrames * channels;
        int count = total - drop;
        if (count <= 0) return null;

        short[] out = new short[count];
        for (int i = 0; i < count; i++) {
            int idx = (drop + i) * 2;
            out[i] = (short) ((pcm[idx] & 0xFF) | (pcm[idx + 1] << 8));
        }
        return new PcmFrame(out, channels, sampleRate);
    }

    /** 有些源在 m4s 里塞了 ADTS 头，这里做兼容；返回需要跳过的头长度 */
    private static int adtsHeaderLength(byte[] d) {
        if (d.length < 7) return 0;
        if ((d[0] & 0xFF) != 0xFF || (d[1] & 0xF0) != 0xF0) return 0;
        boolean noCrc = (d[1] & 0x01) != 0;
        return noCrc ? 7 : 9;
    }

    @Override
    public void close() {
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }

    // ── 内存内盒子工具 ────────────────────────────────────

    private static final class MemBox {
        final String type;
        final int payloadOff, end;

        MemBox(String type, int payloadOff, int end) {
            this.type = type;
            this.payloadOff = payloadOff;
            this.end = end;
        }
    }

    private static List<MemBox> children(byte[] d, MemBox parent) {
        List<MemBox> out = new ArrayList<>();
        int off = parent.payloadOff;
        while (off + 8 <= parent.end) {
            MemBox box = boxAt(d, off, parent.end);
            if (box == null) break;
            out.add(box);
            off = box.end;
        }
        return out;
    }

    private static MemBox boxAt(byte[] d, int off, int limit) {
        if (off + 8 > limit) return null;
        long size = u32(d, off);
        String type = new String(d, off + 4, 4, StandardCharsets.ISO_8859_1);
        int payloadOff = off + 8;
        if (size == 1) {
            if (off + 16 > limit) return null;
            size = u64(d, off + 8);
            payloadOff = off + 16;
        } else if (size == 0) {
            size = limit - off;
        }
        long end = off + size;
        if (end > limit || end < payloadOff) return null;
        return new MemBox(type, payloadOff, (int) end);
    }

    /** 沿路径逐层查找子盒子 */
    private static MemBox find(byte[] d, MemBox parent, String... path) {
        MemBox cur = parent;
        for (String type : path) {
            MemBox next = firstChildOfType(d, cur, type);
            if (next == null) return null;
            cur = next;
        }
        return cur;
    }

    private static MemBox firstChildOfType(byte[] d, MemBox parent, String type) {
        for (MemBox box : children(d, parent)) {
            if (box.type.equals(type)) return box;
        }
        return null;
    }

    /** 跳过前 skipBytes 个字节后的第一个子盒子（stsd 这类带计数字段的 FullBox 用） */
    private static MemBox firstChild(byte[] d, MemBox parent, int skipBytes) {
        int off = parent.payloadOff + skipBytes;
        if (off + 8 > parent.end) return null;
        return boxAt(d, off, parent.end);
    }

    /** FullBox 的 24 位 flags：version(1 字节) 之后才是 flags(3 字节) */
    private static int readFullBoxFlags(byte[] d, int payloadOff) {
        return ((d[payloadOff + 1] & 0xFF) << 16)
                | ((d[payloadOff + 2] & 0xFF) << 8)
                | (d[payloadOff + 3] & 0xFF);
    }

    private static long u32(byte[] d, int off) {
        return ((long) (d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }

    private static long u64(byte[] d, int off) {
        return (u32(d, off) << 32) | u32(d, off + 4);
    }
}
