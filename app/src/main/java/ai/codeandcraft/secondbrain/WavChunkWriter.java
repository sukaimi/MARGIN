package ai.codeandcraft.secondbrain;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class WavChunkWriter implements Closeable {
    private static final int HEADER_SIZE = 44;
    private static final int SAMPLE_SIZE = 2; // PCM16
    private final File partFile;
    private final int sampleRate;
    private final RandomAccessFile raf;
    private long payloadBytes;
    private boolean closed;

    public WavChunkWriter(File directory, int index, int sampleRate) throws IOException {
        if (sampleRate <= 0 || index < 0 || directory == null || !directory.exists() || !directory.isDirectory()) {
            throw new IllegalArgumentException("Invalid constructor arguments");
        }
        this.sampleRate = sampleRate;
        this.partFile = new File(directory, String.format(Locale.US, "chunk-%06d.wav.part", index));
        File finalFile = new File(directory, String.format(Locale.US, "chunk-%06d.wav", index));
        if (this.partFile.exists() || finalFile.exists()) {
            throw new IOException("File conflict: " + partFile.getName());
        }
        
        if (!this.partFile.createNewFile()) {
            throw new IOException("File creation failed: " + partFile.getAbsolutePath());
        }
        
        this.raf = new RandomAccessFile(this.partFile, "rw");
        this.payloadBytes = 0;
        this.closed = false;
        
        writeHeader(0);
    }

    public void write(byte[] data, int length) throws IOException {
        if (closed) throw new IOException("Writer closed");
        if (data == null || length <= 0 || (length % SAMPLE_SIZE) != 0 || length > data.length) {
            throw new IllegalArgumentException("Invalid length: must be positive, multiple of 2, and within data bounds");
        }
        
        if (payloadBytes + length > 0xFFFF_FFFFL - 36) {
            throw new IOException("WAV payload exceeds 4GiB limit");
        }

        raf.seek(HEADER_SIZE + payloadBytes);
        raf.write(data, 0, length);
        payloadBytes += length;
    }

    public void checkpoint() throws IOException {
        if (closed) throw new IOException("Writer closed");
        rewriteHeader();
        raf.getFD().sync();
    }

    private void failWrite(String message, Throwable cause) throws IOException {
        if (!closed) {
            try {
                raf.close();
            } catch (IOException ignored) {}
            closed = true;
        }
        throw new IOException(message, cause);
    }

    public long getPayloadBytes() {
        return payloadBytes;
    }

    public long getDurationMillis() {
        return payloadBytes * 1000L / (sampleRate * 2L);
    }

    public File finish() throws IOException {
        if (closed) throw new IOException("Writer closed");
        rewriteHeader();
        raf.getFD().sync();
        raf.close();
        closed = true;
        
        File finalFile = new File(partFile.getAbsolutePath().replace(".wav.part", ".wav"));
        if (finalFile.exists()) throw new IOException("Final file exists: " + finalFile.getName());
        if (!partFile.renameTo(finalFile)) throw new IOException("Rename failed: " + partFile.getName());
        return finalFile;
    }

    private void writeHeader(long dataSize) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt((int) (36 + dataSize));
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) 1);
        buf.putInt(sampleRate);
        buf.putInt(sampleRate * SAMPLE_SIZE);
        buf.putShort((short) SAMPLE_SIZE);
        buf.putShort((short) 16);
        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt((int) dataSize);
        buf.flip();
        raf.seek(0);
        raf.write(buf.array());
    }

    private void rewriteHeader() throws IOException {
        long currentPos = raf.getFilePointer();
        writeHeader(payloadBytes);
        raf.seek(currentPos);
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            try {
                raf.close();
            } finally {
                closed = true;
            }
        }
    }

    public static File recover(File part, int sampleRate) throws IOException {
        if (part == null || !part.getName().endsWith(".wav.part")) throw new IllegalArgumentException("Invalid file");
        if (part.length() < HEADER_SIZE) throw new IOException("File too small");
        
        File finalFile = new File(part.getAbsolutePath().replace(".wav.part", ".wav"));
        if (finalFile.exists()) throw new IOException("Conflict: Final file already exists: " + finalFile.getName());

        try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
            byte[] header = new byte[HEADER_SIZE];
            raf.readFully(header);
            ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            
            if (!"RIFF".equals(new String(header, 0, 4, StandardCharsets.US_ASCII)) ||
                !"WAVE".equals(new String(header, 8, 4, StandardCharsets.US_ASCII)) ||
                !"fmt ".equals(new String(header, 12, 4, StandardCharsets.US_ASCII)) ||
                buf.getInt(16) != 16 ||
                buf.getShort(20) != 1 ||
                buf.getShort(22) != 1 ||
                buf.getShort(32) != 2 ||
                buf.getShort(34) != 16 ||
                buf.getInt(24) != sampleRate ||
                buf.getInt(28) != sampleRate * 2 ||
                !"data".equals(new String(header, 36, 4, StandardCharsets.US_ASCII))) {
                throw new IOException("Malformed header");
            }
            
            long actualPayload = raf.length() - HEADER_SIZE;
            if ((actualPayload % SAMPLE_SIZE) != 0) {
                throw new IOException("Incomplete PCM sample: payload " + actualPayload
                        + " bytes is not a multiple of " + SAMPLE_SIZE + "; partial retained unchanged");
            }
            long validPayload = (actualPayload / SAMPLE_SIZE) * SAMPLE_SIZE;
            
            if (validPayload > 0xFFFF_FFFFL - 36) throw new IOException("Payload too large");
            
            // Rewrite header
            buf.putInt(4, (int) (36 + validPayload));
            buf.putInt(40, (int) validPayload);
            raf.seek(0);
            raf.write(header);
            raf.setLength(HEADER_SIZE + validPayload);
            raf.getFD().sync();
            raf.close();
            
            if (!part.renameTo(finalFile)) throw new IOException("Rename failed");
            return finalFile;
        }
    }
}
