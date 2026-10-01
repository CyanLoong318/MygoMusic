package com.allmusic.client.audio;

/**
 * 一帧解码后的 PCM（16bit，多声道交错）
 */
public record PcmFrame(short[] samples, int channels, int sampleRate) {
}
