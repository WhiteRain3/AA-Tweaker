package sksa.aa.tweaker;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decoder for the flag lists Play Services stores in phenotype.db (param_partitions.flags_content).
 *
 * The layout is: raw-deflate( count, then per flag: varint descriptor, [name], [value] ).
 * descriptor & 7 is the type; descriptor >>> 3 is 0 when a name string follows, otherwise a
 * delta to the previous numeric flag id. The format was worked out by the GMS Flags Reborn
 * project (Apache-2.0); this is an independent implementation of the same layout.
 */
final class PhenotypeFlags {

    static final class Flag {
        /** B boolean, L integer, D floating point, S string, X binary. */
        final char type;
        final String name;
        final String value;

        Flag(char type, String name, String value) {
            this.type = type;
            this.name = name;
            this.value = value;
        }
    }

    private final byte[] data;
    private int pos;

    private PhenotypeFlags(byte[] data) {
        this.data = data;
    }

    static List<Flag> decode(byte[] compressed) throws DataFormatException {
        PhenotypeFlags in = new PhenotypeFlags(inflate(compressed));
        long count = in.varint();
        if (count < 0 || count > 1_000_000) throw new DataFormatException("implausible flag count " + count);
        List<Flag> flags = new ArrayList<>((int) count);
        long previousId = 0;
        for (long i = 0; i < count; i++) {
            long descriptor = in.varint();
            long delta = descriptor >>> 3;
            String name;
            if (delta == 0) {
                name = in.string();
            } else {
                previousId += delta;
                name = Long.toString(previousId);
            }
            int type = (int) (descriptor & 7);
            switch (type) {
                case 0:
                case 1:
                    flags.add(new Flag('B', name, type == 1 ? "true" : "false"));
                    break;
                case 2:
                    flags.add(new Flag('L', name, Long.toString(in.varint())));
                    break;
                case 3:
                    flags.add(new Flag('D', name, Double.toString(Double.longBitsToDouble(in.fixed64()))));
                    break;
                case 4:
                    flags.add(new Flag('S', name, in.string()));
                    break;
                case 5:
                    flags.add(new Flag('X', name, "<" + in.bytes().length + " bytes>"));
                    break;
                default:
                    throw new DataFormatException("unknown flag type " + type);
            }
        }
        return flags;
    }

    private static byte[] inflate(byte[] compressed) throws DataFormatException {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new DataFormatException("truncated flag data");
                    }
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private long varint() throws DataFormatException {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos >= data.length) throw new DataFormatException("unexpected end of flag data");
            byte b = data[pos++];
            result |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new DataFormatException("malformed varint");
    }

    private long fixed64() throws DataFormatException {
        if (pos + 8 > data.length) throw new DataFormatException("unexpected end of flag data");
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= (long) (data[pos++] & 0xff) << (8 * i);
        }
        return result;
    }

    private byte[] bytes() throws DataFormatException {
        long len = varint();
        if (len < 0 || pos + len > data.length) throw new DataFormatException("bad length " + len);
        byte[] out = new byte[(int) len];
        System.arraycopy(data, pos, out, 0, out.length);
        pos += out.length;
        return out;
    }

    private String string() throws DataFormatException {
        return new String(bytes(), StandardCharsets.UTF_8);
    }
}
