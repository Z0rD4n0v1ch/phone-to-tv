package com.example.phonetotv;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Небольшой генератор QR-кодов без сторонних библиотек: байтовый режим, уровень коррекции M, версии 1–10
 * (до 213 байт). Для ссылки вида https://…/tv/?h=192.168.1.60&p=1234 этого с запасом хватает.
 * Алгоритм по стандарту ISO/IEC 18004 (по мотивам открытой реализации Project Nayuki).
 */
public final class QrCode {

    private static final int[] ECC_PER_BLOCK_M = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS_M = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};

    public final int size;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    /** true — тёмный модуль. Координаты: x — столбец, y — строка. */
    public boolean get(int x, int y) {
        return x >= 0 && x < size && y >= 0 && y < size && modules[y][x];
    }

    public static QrCode encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int ver = 1; ver <= 10; ver++) {
            int capacityBits = numDataCodewords(ver) * 8;
            int ccBits = ver <= 9 ? 8 : 16;
            int used = 4 + ccBits + data.length * 8;
            if (used <= capacityBits) return new QrCode(ver, data);
        }
        throw new IllegalArgumentException("Слишком длинный текст для QR-кода");
    }

    private QrCode(int ver, byte[] data) {
        size = ver * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];
        drawFunctionPatterns(ver);
        byte[] all = addEccAndInterleave(ver, encodeData(ver, data));
        drawCodewords(all);

        int bestMask = 0;
        int minPenalty = Integer.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(m);
            drawFormatBits(m);
            int p = penalty();
            if (p < minPenalty) { minPenalty = p; bestMask = m; }
            applyMask(m); // XOR — отменяем
        }
        applyMask(bestMask);
        drawFormatBits(bestMask);
    }

    // ---------- данные ----------

    private static byte[] encodeData(int ver, byte[] data) {
        int capacityBits = numDataCodewords(ver) * 8;
        BitBuffer bb = new BitBuffer();
        bb.append(0x4, 4); // байтовый режим
        bb.append(data.length, ver <= 9 ? 8 : 16);
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capacityBits - bb.size()));
        bb.append(0, (8 - bb.size() % 8) % 8);
        for (int pad = 0xEC; bb.size() < capacityBits; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        byte[] out = new byte[bb.size() / 8];
        for (int i = 0; i < bb.size(); i++) if (bb.get(i)) out[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
        return out;
    }

    private static int numRawDataModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    private static int numDataCodewords(int ver) {
        return numRawDataModules(ver) / 8 - ECC_PER_BLOCK_M[ver] * BLOCKS_M[ver];
    }

    private static byte[] addEccAndInterleave(int ver, byte[] data) {
        int numBlocks = BLOCKS_M[ver];
        int blockEccLen = ECC_PER_BLOCK_M[ver];
        int rawCodewords = numRawDataModules(ver) / 8;
        int numShortBlocks = numBlocks - rawCodewords % numBlocks;
        int shortBlockLen = rawCodewords / numBlocks;

        byte[] divisor = rsDivisor(blockEccLen);
        List<byte[]> blocks = new ArrayList<>();
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int datLen = shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1);
            byte[] dat = new byte[datLen];
            System.arraycopy(data, k, dat, 0, datLen);
            k += datLen;
            byte[] ecc = rsRemainder(dat, divisor);
            byte[] block = new byte[shortBlockLen + 1];
            System.arraycopy(dat, 0, block, 0, datLen);
            // в коротких блоках позиция datLen — заглушка, она пропускается при перемешивании
            System.arraycopy(ecc, 0, block, block.length - blockEccLen, blockEccLen);
            blocks.add(block);
        }
        byte[] result = new byte[rawCodewords];
        int n = 0;
        for (int i = 0; i < blocks.get(0).length; i++) {
            for (int j = 0; j < blocks.size(); j++) {
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) result[n++] = blocks.get(j)[i];
            }
        }
        return result;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < result.length; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < result.length) result[j] ^= result[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) gfMul(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ---------- служебные узоры ----------

    private void drawFunctionPatterns(int ver) {
        for (int i = 0; i < size; i++) {
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        int[] align = alignmentPositions(ver);
        int n = align.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        setFunction(align[i] + dx, align[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        drawFormatBits(0); // место под формат, перезапишется
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                setFunction(a, b, bit);
                setFunction(b, a, bit);
            }
        }
    }

    private static int[] alignmentPositions(int ver) {
        if (ver == 1) return new int[0];
        int numAlign = ver / 7 + 2;
        int step = (ver * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2;
        int[] result = new int[numAlign];
        result[0] = 6;
        for (int i = numAlign - 1, pos = ver * 4 + 10; i >= 1; i--, pos -= step) result[i] = pos;
        return result;
    }

    private void drawFinder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) setFunction(xx, yy, dist != 2 && dist != 4);
            }
        }
    }

    private void drawFormatBits(int mask) {
        int data = (0 << 3) | mask; // уровень M = 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setFunction(8, i, bit(bits, i));
        setFunction(8, 7, bit(bits, 6));
        setFunction(8, 8, bit(bits, 7));
        setFunction(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setFunction(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(8, size - 15 + i, bit(bits, i));
        setFunction(8, size - 8, true);
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private void setFunction(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x];
            }
        }
    }

    /** Упрощённая оценка маски: длинные ряды, квадраты 2×2 и баланс тёмного/светлого. */
    private int penalty() {
        int result = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean cur = pass == 0 ? modules[a][b] : modules[b][a];
                    boolean prev = pass == 0 ? modules[a][b - 1] : modules[b - 1][a];
                    if (cur == prev) {
                        run++;
                        if (run == 5) result += 3;
                        else if (run > 5) result++;
                    } else {
                        run = 1;
                    }
                }
            }
        }
        for (int y = 0; y < size - 1; y++)
            for (int x = 0; x < size - 1; x++) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3;
            }
        int dark = 0;
        for (boolean[] row : modules) for (boolean m : row) if (m) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        result += Math.max(0, k) * 10;
        return result;
    }

    private static final class BitBuffer {
        private final List<Boolean> bits = new ArrayList<>();
        void append(int val, int len) {
            for (int i = len - 1; i >= 0; i--) bits.add(((val >>> i) & 1) != 0);
        }
        int size() { return bits.size(); }
        boolean get(int i) { return bits.get(i); }
    }
}
