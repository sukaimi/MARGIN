package ai.codeandcraft.secondbrain;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class PcmWavReader {
    private static final int MAX_DATA_BYTES = 800_000;
    private static final int SR = 16000, CH = 1, BITS = 16, BR = 32_000, BA = 2;

    public static final class Audio {
        public final float[] samples;
        public final long durationMs;
        public final String sha256;
        Audio(float[] samples, long durationMs, String sha256) {
            this.samples = samples;
            this.durationMs = durationMs;
            this.sha256 = sha256;
        }
    }

    public static Audio read(File file) throws IOException {
        if (file == null) throw new IOException("File must not be null");
        long actualLength = file.length();
        if (actualLength < 12) throw new IOException("File too small for WAV header");
        if (actualLength > 1_048_576) throw new IOException("File exceeds 1 MiB bound");
        String sha256;
        try (FileInputStream in = new FileInputStream(file)) { sha256 = digest(in); }
        byte[] fmt = null, data = null;
        boolean haveFmt = false, haveData = false;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(0);
            byte[] header = new byte[12];
            raf.readFully(header);
            if (!"RIFF".equals(new String(header, 0, 4, StandardCharsets.US_ASCII))
                    || !"WAVE".equals(new String(header, 8, 4, StandardCharsets.US_ASCII)))
                throw new IOException("Not a RIFF/WAVE file");
            long declaredSize = Integer.toUnsignedLong(
                    ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            if (declaredSize > actualLength - 8)
                throw new IOException("RIFF declared size exceeds actual file length");
            long riffEnd = 8 + declaredSize, pos = 12;
            while (pos + 8 <= riffEnd) {
                raf.seek(pos);
                byte[] idBuf = new byte[4], sizeBuf = new byte[4];
                raf.readFully(idBuf);
                raf.readFully(sizeBuf);
                long chunkSize = Integer.toUnsignedLong(
                        ByteBuffer.wrap(sizeBuf).order(ByteOrder.LITTLE_ENDIAN).getInt());
                long chunkDataStart = pos + 8;
                if (chunkSize < 0 || chunkDataStart + chunkSize > riffEnd)
                    throw new IOException("Chunk out of bounds");
                String id = new String(idBuf, StandardCharsets.US_ASCII);
                if (id.equals("fmt ")) {
                    if (haveFmt) throw new IOException("Duplicate fmt chunk");
                    if (chunkSize < 16) throw new IOException("fmt chunk too small");
                    raf.seek(chunkDataStart);
                    fmt = new byte[16];
                    raf.readFully(fmt);
                    haveFmt = true;
                } else if (id.equals("data")) {
                    if (haveData) throw new IOException("Duplicate data chunk");
                    if (chunkSize <= 0 || chunkSize % 2 != 0 || chunkSize > MAX_DATA_BYTES)
                        throw new IOException("Invalid data chunk size");
                    raf.seek(chunkDataStart);
                    data = new byte[(int) chunkSize];
                    raf.readFully(data);
                    haveData = true;
                }
                pos = chunkDataStart + chunkSize + (chunkSize % 2);
                if (pos > riffEnd) throw new IOException("Chunk extends past RIFF end");
            }
            if (pos != riffEnd) throw new IOException("Dangling bytes after last chunk");
        }
        if (!haveFmt) throw new IOException("Missing fmt chunk");
        if (!haveData) throw new IOException("Missing data chunk");
        ByteBuffer fb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
        if ((fb.getShort(0) & 0xFFFF) != 1 || (fb.getShort(2) & 0xFFFF) != CH
                || fb.getInt(4) != SR || fb.getInt(8) != BR
                || (fb.getShort(12) & 0xFFFF) != BA || (fb.getShort(14) & 0xFFFF) != BITS)
            throw new IOException("Unsupported WAV format: expected PCM16 mono 16000Hz");
        float[] samples = new float[data.length / 2];
        ByteBuffer db = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples.length; i++) samples[i] = db.getShort() / 32768.0f;
        return new Audio(samples, data.length / 32L, sha256);
    }

    private static String digest(FileInputStream in) throws IOException {
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }
}