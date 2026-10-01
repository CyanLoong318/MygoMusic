package com.allmusic.client.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.IOException;
import java.io.InputStream;

/**
 * MP3 帧来源（JLayer 解码）。酷狗/网易云以及服务端转码后的 B站音频都是 MP3。
 */
public class Mp3FrameSource implements PcmFrameSource {
    private final InputStream in;
    private final Bitstream bitstream;
    private final Decoder decoder = new Decoder();

    public Mp3FrameSource(InputStream in) {
        this.in = in;
        this.bitstream = new Bitstream(in);
    }

    @Override
    public PcmFrame next() throws Exception {
        Header header = bitstream.readFrame();
        if (header == null) return null; // 自然播放到末尾

        SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
        bitstream.closeFrame();

        short[] src = output.getBuffer();
        int length = Math.min(output.getBufferLength(), src == null ? 0 : src.length);
        if (length <= 0) return null;

        short[] samples = new short[length];
        System.arraycopy(src, 0, samples, 0, length);

        return new PcmFrame(samples,
                Math.max(1, output.getChannelCount()),
                Math.max(1, output.getSampleFrequency()));
    }

    @Override
    public void close() throws IOException {
        try {
            bitstream.close();
        } catch (Exception ignored) {
        }
        in.close();
    }
}
