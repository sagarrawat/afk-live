package com.afklive.streamer.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Counts bytes read and fails once a limit is passed. Used for zip entries, whose declared
 * size can't be trusted (a small upload can expand to far more than the user's storage).
 * Does not close the underlying stream, so it can wrap one entry of a ZipInputStream.
 */
public class LimitedInputStream extends FilterInputStream {

    private final long limit;
    private long count;

    public LimitedInputStream(InputStream in, long limit) {
        super(in);
        this.limit = limit;
    }

    public long getCount() {
        return count;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            add(1);
        }
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int n = super.read(buf, off, len);
        if (n > 0) {
            add(n);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        add(skipped);
        return skipped;
    }

    @Override
    public void close() {
        // Leave the underlying stream open (it may be a ZipInputStream with more entries).
    }

    private void add(long n) throws IOException {
        count += n;
        if (count > limit) {
            throw new LimitExceededException(limit);
        }
    }

    /** Thrown when more than the allowed number of bytes is read. */
    public static class LimitExceededException extends IOException {
        public LimitExceededException(long limit) {
            super("Upload exceeds the remaining storage (" + (limit / 1024 / 1024) + " MB).");
        }
    }
}
