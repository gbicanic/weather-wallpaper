package com.gbicanic.tvoverlay;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;

/**
 * Plays the Otvoreni radio live stream (ADTS AAC over Icecast).
 *
 * Decodes the stream itself instead of using MediaPlayer: the Sony's MediaTek stream detector
 * can't recognise this AAC stream and gives up after ~20 s. Here the ADTS frames are read straight
 * from HTTP, fed to the AAC decoder and the PCM written to an AudioTrack.
 *
 * start()/stop() are cheap to call repeatedly. stop() drops the connection, so returning to the
 * home screen always joins the live broadcast.
 */
class Radio {
    private static final String TAG = "TvOverlay";
    private static final String[] STREAMS = {
            "http://stream.otvoreni.hr/otvoreni",
            "http://stream2.otvoreni.hr/otvoreni",
    };
    private static final long RETRY_MS = 10 * 1000;
    private static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350};

    private Worker worker;

    synchronized void start() {
        if (worker == null) {
            worker = new Worker();
            worker.start();
        }
    }

    synchronized void stop() {
        if (worker != null) {
            worker.cancel();
            worker = null;
        }
    }

    private static final class Worker extends Thread {
        private volatile boolean running = true;
        private volatile HttpURLConnection connection;
        private int streamIndex;

        Worker() {
            super("radio");
        }

        void cancel() {
            running = false;
            interrupt();
            HttpURLConnection c = connection;
            if (c != null) c.disconnect(); // unblocks a pending read
        }

        @Override
        public void run() {
            while (running) {
                String url = STREAMS[streamIndex];
                try {
                    play(url);
                } catch (Exception e) {
                    if (running) Log.w(TAG, "Radio stream " + url + " stopped: " + e);
                }
                if (!running) break;
                streamIndex = (streamIndex + 1) % STREAMS.length;
                try {
                    Thread.sleep(RETRY_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        private void play(String url) throws IOException {
            Log.i(TAG, "Radio connecting to " + url);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "TvOverlay/3");
            connection = c;
            MediaCodec codec = null;
            AudioTrack track = null;
            try {
                InputStream in = new BufferedInputStream(c.getInputStream(), 16384);
                byte[] header = new byte[9];
                byte[] frame = new byte[8192];
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                long pts = 0;
                int sampleRate = 48000;
                while (running) {
                    syncToFrame(in, header);
                    readFully(in, header, 2, 5);
                    boolean crc = (header[1] & 1) == 0;
                    int length = ((header[3] & 3) << 11) | ((header[4] & 0xff) << 3) | ((header[5] & 0xe0) >> 5);
                    int headerLen = crc ? 9 : 7;
                    if (crc) readFully(in, header, 7, 2);
                    int payload = length - headerLen;
                    if (payload <= 0 || payload > frame.length) continue; // false sync, keep scanning
                    readFully(in, frame, 0, payload);

                    if (codec == null) {
                        int profile = ((header[2] >> 6) & 3) + 1;
                        int rateIndex = (header[2] >> 2) & 0xf;
                        int channels = ((header[2] & 1) << 2) | ((header[3] >> 6) & 3);
                        if (rateIndex >= RATES.length || channels == 0) continue;
                        sampleRate = RATES[rateIndex];
                        MediaFormat format = MediaFormat.createAudioFormat(
                                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[]{
                                (byte) ((profile << 3) | (rateIndex >> 1)),
                                (byte) (((rateIndex & 1) << 7) | (channels << 3))}));
                        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                        codec.configure(format, null, null, 0);
                        codec.start();
                        Log.i(TAG, "Radio playing " + url + " (" + sampleRate + " Hz, " + channels + " ch)");
                    }

                    int inIndex;
                    while ((inIndex = codec.dequeueInputBuffer(10000)) < 0) {
                        if (!running) return;
                        track = drain(codec, info, track);
                    }
                    ByteBuffer buf = codec.getInputBuffer(inIndex);
                    buf.clear();
                    buf.put(frame, 0, payload);
                    codec.queueInputBuffer(inIndex, 0, payload, pts, 0);
                    pts += 1024L * 1000000L / sampleRate;
                    track = drain(codec, info, track);
                }
            } finally {
                connection = null;
                c.disconnect();
                if (codec != null) {
                    try {
                        codec.stop();
                    } catch (RuntimeException ignored) {
                    }
                    codec.release();
                }
                if (track != null) {
                    try {
                        track.stop();
                    } catch (RuntimeException ignored) {
                    }
                    track.release();
                }
            }
        }

        /** Moves decoded PCM to the speaker; creates the AudioTrack once the output format is known. */
        private AudioTrack drain(MediaCodec codec, MediaCodec.BufferInfo info, AudioTrack track) {
            while (running) {
                int out = codec.dequeueOutputBuffer(info, 0);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    if (track != null) track.release();
                    track = newTrack(f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            f.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                } else if (out >= 0) {
                    if (info.size > 0) {
                        if (track == null) {
                            MediaFormat f = codec.getOutputFormat();
                            track = newTrack(f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                    f.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                        }
                        ByteBuffer pcm = codec.getOutputBuffer(out);
                        pcm.position(info.offset);
                        pcm.limit(info.offset + info.size);
                        track.write(pcm, info.size, AudioTrack.WRITE_BLOCKING);
                    }
                    codec.releaseOutputBuffer(out, false);
                } else {
                    break;
                }
            }
            return track;
        }

        private static AudioTrack newTrack(int rate, int channels) {
            int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setChannelMask(mask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(Math.max(min * 4, rate * channels * 2 / 2)) // ~0.5 s
                    .build();
            t.play();
            return t;
        }

        /** Reads until the 12-bit ADTS sync word; leaves its two bytes in header[0..1]. */
        private static void syncToFrame(InputStream in, byte[] header) throws IOException {
            int prev = read(in);
            while (true) {
                int b = read(in);
                if (prev == 0xff && (b & 0xf6) == 0xf0) {
                    header[0] = (byte) prev;
                    header[1] = (byte) b;
                    return;
                }
                prev = b;
            }
        }

        private static int read(InputStream in) throws IOException {
            int b = in.read();
            if (b < 0) throw new EOFException("stream ended");
            return b;
        }

        private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
            while (len > 0) {
                int n = in.read(buf, off, len);
                if (n < 0) throw new EOFException("stream ended");
                off += n;
                len -= n;
            }
        }
    }
}
