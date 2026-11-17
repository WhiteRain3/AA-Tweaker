package sksa.aa.tweaker;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;

public class PhenotypeFlagsTest {

    private final ByteArrayOutputStream raw = new ByteArrayOutputStream();

    private void varint(long v) {
        while ((v & ~0x7fL) != 0) {
            raw.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        raw.write((int) v);
    }

    private void string(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        varint(b.length);
        raw.write(b, 0, b.length);
    }

    private byte[] deflated() {
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        d.setInput(raw.toByteArray());
        d.finish();
        byte[] buf = new byte[4096];
        int n = d.deflate(buf);
        d.end();
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    @Test
    public void decodesEveryType() throws Exception {
        varint(6);
        varint(1); string("Group__on");            // named boolean true
        varint(0); string("Group__off");           // named boolean false
        varint(2); string("Group__size"); varint(1240);
        varint(3); string("Group__ratio");
        long bits = Double.doubleToRawLongBits(1.67);
        for (int i = 0; i < 8; i++) raw.write((int) (bits >>> (8 * i)) & 0xff);
        varint(4); string("Group__text"); string("hello|world");
        varint((45L << 3) | 1);                    // numeric id 45, boolean true

        List<PhenotypeFlags.Flag> flags = PhenotypeFlags.decode(deflated());
        assertEquals(6, flags.size());
        assertEquals("Group__on", flags.get(0).name);
        assertEquals("true", flags.get(0).value);
        assertEquals("false", flags.get(1).value);
        assertEquals('L', flags.get(2).type);
        assertEquals("1240", flags.get(2).value);
        assertEquals("1.67", flags.get(3).value);
        assertEquals("hello|world", flags.get(4).value);
        assertEquals("45", flags.get(5).name);
        assertEquals('B', flags.get(5).type);
    }
}
