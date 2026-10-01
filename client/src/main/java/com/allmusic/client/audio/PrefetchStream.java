package com.allmusic.client.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 边下边播的预读流。
 *
 * 后台线程持续把网络数据读进内存（上限 maxBufferBytes），播放线程从这里顺序消费。这样：
 *  1. 开播不必等整首下完（首包到了就开始解码播放）；
 *  2. 暂停期间预读线程照常拉流，几 MB 的歌曲通常在整个暂停期间就整首进了内存，
 *     恢复播放完全不受 CDN 空闲断连影响（直链是公网 CDN，不像插件内置 HTTP 服务那样近）；
 *  3. 顺带把下载到的字节喂给缓存写入器，实现「边听边缓存」。
 *
 * 网络读失败时按已下载字节数发 Range 请求续传（B站CDN支持 206），避免中途抖动整首报废。
 */
public class PrefetchStream extends InputStream {
    private static final Logger logger = LoggerFactory.getLogger("MygoMusic-Prefetch");

    private static final int CHUNK = 64 * 1024;
    private static final int MAX_RETRIES = 3;
    private static final byte[] EOF_MARK = new byte[0];

    /** 断线续传：从 offset 字节处重新打开底层流（返回的流需已定位到 offset） */
    public interface Reopener {
        InputStream open(long offset) throws IOException;
    }

    private final AudioCache.CacheWriter cacheWriter;
    private final Reopener reopener;
    private final Semaphore space;
    private final int capacity;
    /** 该音频的总字节数（-1 表示未知）：用来识别「CDN 悄悄把连接掐了」这种情况下的假 EOF */
    private final long expectedLength;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread worker;

    private volatile IOException failure;
    private volatile long totalFetched;
    /** 当前底层流，close() 时直接关掉以尽快唤醒阻塞中的网络读 */
    private volatile InputStream netRef;

    private byte[] current;
    private int pos;

    public PrefetchStream(InputStream net, AudioCache.CacheWriter cacheWriter, Reopener reopener, int maxBufferBytes) {
        this(net, cacheWriter, reopener, maxBufferBytes, -1);
    }

    public PrefetchStream(InputStream net, AudioCache.CacheWriter cacheWriter, Reopener reopener,
                          int maxBufferBytes, long expectedLength) {
        this.cacheWriter = cacheWriter;
        this.reopener = reopener;
        this.capacity = Math.max(CHUNK, maxBufferBytes);
        this.expectedLength = expectedLength;
        this.space = new Semaphore(capacity);
        this.worker = new Thread(() -> pump(net), "MygoMusic-Prefetch");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** 已从网络取到的字节数（= 缓存文件应有的大小） */
    public long getTotalFetched() {
        return totalFetched;
    }

    /** 已知总长度、但还没下完 */
    private boolean isIncomplete() {
        return expectedLength >= 0 && totalFetched < expectedLength;
    }

    private void pump(InputStream net) {
        netRef = net;
        byte[] buf = new byte[CHUNK];
        int retries = 0;
        try {
            while (!closed.get()) {
                int n;
                try {
                    n = net.read(buf, 0, buf.length);
                } catch (IOException e) {
                    if (closed.get() || retries >= MAX_RETRIES || reopener == null || totalFetched <= 0) {
                        failure = e;
                        break;
                    }
                    retries++;
                    logger.warn("音频流读取中断，尝试续传({}/{}): {}", retries, MAX_RETRIES, e.getMessage());
                    try {
                        net = reopener.open(totalFetched);
                        netRef = net;
                    } catch (IOException reopenError) {
                        failure = e;
                        break;
                    }
                    continue; // 续传成功，继续读
                }

                if (n < 0) {
                    // 提前结束（CDN 悄悄掐连接时 read 会返回 -1 而不是抛异常）：
                    // 已下载的字节数对不上总长度就按断线处理，续传接着下，别当成播完了
                    if (isIncomplete() && !closed.get() && retries < MAX_RETRIES && reopener != null
                            && totalFetched > 0) {
                        retries++;
                        logger.warn("音频流提前结束({}/{} 字节)，尝试续传({}/{})",
                                totalFetched, expectedLength, retries, MAX_RETRIES);
                        try {
                            net = reopener.open(totalFetched);
                            netRef = net;
                            continue;
                        } catch (IOException reopenError) {
                            logger.warn("续传失败: {}", reopenError.getMessage());
                        }
                    }
                    break; // 正常结束
                }
                if (n == 0) continue;

                totalFetched += n;
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);

                if (cacheWriter != null) cacheWriter.write(chunk, 0, n);

                // 缓冲到上限就在这里等：播放线程消费后释放额度
                space.acquire(n);
                if (closed.get()) break;
                queue.put(chunk);
            }

            // 完整下完才让缓存生效（中途失败/被切歌的截断文件由 close() abort 掉）
            if (cacheWriter != null && !closed.get() && failure == null && !isIncomplete()) {
                cacheWriter.commit();
            }
        } catch (InterruptedException e) {
            // close() 唤醒，正常退出
        } finally {
            try {
                net.close();
            } catch (IOException ignored) {}
            queue.offer(EOF_MARK);
        }
    }

    @Override
    public int read() throws IOException {
        if (!fill()) return -1;
        return current[pos++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (!fill()) return -1;
        int n = Math.min(len, current.length - pos);
        System.arraycopy(current, pos, b, off, n);
        pos += n;
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        // 跳过一般发生在 mp4 盒子的填充字节上，直接消费即可
        long remaining = n;
        byte[] buf = new byte[(int) Math.min(8192, Math.max(1, remaining))];
        while (remaining > 0) {
            int read = read(buf, 0, (int) Math.min(buf.length, remaining));
            if (read < 0) break;
            remaining -= read;
        }
        return n - remaining;
    }

    @Override
    public int available() {
        int size = current == null ? 0 : current.length - pos;
        for (byte[] chunk : queue) {
            size += chunk.length;
        }
        return size;
    }

    /** 取下一块数据；返回 false = 正常结束 */
    private boolean fill() throws IOException {
        while (current == null || pos >= current.length) {
            current = null;
            pos = 0;

            byte[] chunk;
            try {
                chunk = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("预读等待被中断", e);
            }

            if (chunk == EOF_MARK) {
                if (failure != null) throw failure;
                return false;
            }

            space.release(chunk.length); // 腾出缓冲额度，唤醒预读线程
            current = chunk;
        }
        return true;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (cacheWriter != null) cacheWriter.abort(); // 半截文件不留缓存
        InputStream net = netRef;
        if (net != null) {
            try {
                net.close(); // 立刻掐断可能正阻塞的网络读
            } catch (IOException ignored) {}
        }
        worker.interrupt();
        space.release(capacity); // 解除预读线程在 acquire 上的阻塞
        queue.clear();
        queue.offer(EOF_MARK);
    }
}
