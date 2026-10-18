package ai.jackknife.killayue.peer;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** TLS transport scheduling around the C++ codecs and bounded native playback buffer. */
public final class HammerBuffer implements Closeable {
    public static final int AUTO = 0, ORIGINAL = 1, SAVER = 2;
    private final Hammer.Song song;
    private final List<Hammer.Card> peers;
    private final int mode, blockSize;
    private final long size, cache;
    private final byte[] header;
    private final ThreadPoolExecutor worker =
        new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(4));
    private final Map<Long, Task> pending = new HashMap<Long, Task>();
    private boolean closed;
    private long last = -1;
    private volatile int bitrate;
    public volatile String state = "Buffering";
    public volatile int bufferedBytes;
    public HammerBuffer(List<Hammer.Card> peers, Hammer.Song song, int mode, long frames) throws IOException {
        Hammer.require(!peers.isEmpty() && peers.size() <= 32 && mode >= 0 && mode <= 2,
                       "Invalid stream configuration");
        this.peers = new ArrayList<Hammer.Card>(peers);
        this.song = song;
        this.mode = mode;
        blockSize = mode == ORIGINAL ? Hammer.CHUNK : 96000 * 4;
        size = mode == ORIGINAL ? song.size : 44 + frames * 4;
        Hammer.require(size > 44 && size <= 0xffffffffL, "Stream length unsupported");
        bitrate = mode == SAVER ? 48000 : 96000;
        header = mode == ORIGINAL ? new byte[0] : waveHeader((int)(frames * 4));
        cache = HammerNative.cacheCreate();
    }
    private static byte[] waveHeader(int bytes) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'R', 'I', 'F', 'F'})
            .putInt(bytes + 36)
            .put(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '})
            .putInt(16)
            .putShort((short)1)
            .putShort((short)2)
            .putInt(48000)
            .putInt(192000)
            .putShort((short)4)
            .putShort((short)16)
            .put(new byte[] {'d', 'a', 't', 'a'})
            .putInt(bytes);
        return b.array();
    }
    private final class Task extends FutureTask<byte[]> {
        final Hammer.Job job;
        Task(final long position, final Hammer.Job job) {
            super(new Callable<byte[]>() {
                public byte[] call() throws Exception {
                    Exception failure = null;
                    for (Hammer.Card peer : HammerRoutes.order(peers)) {
                        job.check();
                        HammerRoutes.begin(peer);
                        long started = System.nanoTime();
                        boolean ok = false;
                        try {
                            byte[] bytes;
                            if (mode == ORIGINAL)
                                bytes = Hammer.chunk(peer, song, position, job);
                            else {
                                byte[] packet =
                                    Hammer.segment(peer, song.id, (int)(position / blockSize), bitrate, job);
                                bytes = HammerMedia.codec.decode(packet);
                                Hammer.require(bytes.length ==
                                                   Math.min(blockSize, size - header.length - position),
                                               "Decoded segment length differs");
                            }
                            long millis = (System.nanoTime() - started) / 1000000;
                            if (mode == AUTO)
                                bitrate = HammerNative.autoBitrate(millis, bitrate);
                            ok = true;
                            return bytes;
                        } catch (Exception e) {
                            failure = e;
                        } finally {
                            HammerRoutes.end(peer, (System.nanoTime() - started) / 1000000, ok);
                        }
                    }
                    throw new IOException("Hosting peers unavailable. Retry when a host reconnects.",
                                          failure);
                }
            });
            this.job = job;
        }
        void stop() {
            job.cancel();
            cancel(true);
        }
    }
    private synchronized Task request(long position) throws IOException {
        Hammer.require(!closed, "Stream closed");
        Task old = pending.get(Long.valueOf(position));
        if (old != null)
            return old;
        Task task = new Task(position, new Hammer.Job());
        pending.put(Long.valueOf(position), task);
        try {
            worker.execute(task);
        } catch (RejectedExecutionException e) {
            pending.remove(Long.valueOf(position));
            throw new IOException("Buffer is busy", e);
        }
        return task;
    }
    private synchronized void harvest() throws IOException {
        for (Iterator<Map.Entry<Long, Task>> it = pending.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Long, Task> e = it.next();
            if (e.getValue().isDone()) {
                try {
                    HammerNative.cachePut(cache, e.getKey().longValue(), e.getValue().get());
                } catch (ExecutionException ignored) {
                } catch (CancellationException ignored) {
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", ex);
                }
                it.remove();
            }
        }
        bufferedBytes = HammerNative.cacheBytes(cache);
    }
    public int readAt(long position, byte[] destination, int offset, int length) throws IOException {
        Hammer.require(position >= 0 && offset >= 0 && length >= 0 && offset <= destination.length - length,
                       "Invalid stream read");
        if (length == 0)
            return 0;
        if (position >= size)
            return -1;
        if (position < header.length) {
            int n = Math.min(length, header.length - (int)position);
            System.arraycopy(header, (int)position, destination, offset, n);
            return n;
        }
        long dataPosition = position - header.length, start = dataPosition / blockSize * blockSize;
        int got;
        synchronized (this) {
            Hammer.require(!closed, "Stream closed");
            harvest();
            if (last >= 0 && (start < last || start > last + blockSize)) {
                for (Task t : pending.values())
                    t.stop();
                pending.clear();
                worker.getQueue().clear();
            }
            last = start;
            got = HammerNative.cacheRead(cache, start, destination, offset, (int)(dataPosition - start),
                                         length);
        }
        if (got < 0) {
            state = "Buffering";
            Task task = request(start);
            byte[] bytes;
            try {
                bytes = task.get(45, TimeUnit.SECONDS);
            } catch (Exception e) {
                task.stop();
                synchronized (this) { pending.remove(Long.valueOf(start)); }
                state = "Connection interrupted — retry";
                throw new IOException("Stream interrupted", e);
            }
            synchronized (this) {
                Hammer.require(!closed, "Stream closed");
                pending.remove(Long.valueOf(start));
                HammerNative.cachePut(cache, start, bytes);
                got = HammerNative.cacheRead(cache, start, destination, offset, (int)(dataPosition - start),
                                             length);
            }
        }
        synchronized (this) {
            Hammer.require(!closed, "Stream closed");
            state = mode == ORIGINAL ? "Original source" : "Opus " + bitrate / 1000 + " kbps";
            for (int n = 1; n <= 3; n++) {
                long next = start + (long)n * blockSize;
                if (next >= size - header.length)
                    break;
                if (!HammerNative.cacheHas(cache, next) && !pending.containsKey(Long.valueOf(next)) &&
                    pending.size() < 4)
                    request(next);
            }
            bufferedBytes = HammerNative.cacheBytes(cache);
        }
        return got;
    }
    public long size() { return size; }
    public synchronized void close() {
        if (closed)
            return;
        closed = true;
        for (Task t : pending.values())
            t.stop();
        pending.clear();
        worker.shutdownNow();
        HammerNative.cacheClose(cache);
        bufferedBytes = 0;
        state = "Stopped";
    }
}
