package com.allmusic.client.audio;

import java.io.Closeable;

/**
 * PCM 帧来源：把「Mp3 解码」和「B站 m4s(AAC) 解码」统一成同一种取帧方式，
 * 播放/暂停/音量/进度/播完切歌等逻辑只写一份（见 AudioPlayer）。
 */
public interface PcmFrameSource extends Closeable {

    /**
     * 取下一帧 PCM；返回 null 表示自然播放结束。
     * 该方法会阻塞直到有数据（内部做网络预读），可能抛异常表示下载/解码失败。
     */
    PcmFrame next() throws Exception;
}
