// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import java.util.Arrays;
import java.util.Random;

/** Production arithmetic/output bounds only; no Android pixels, credentials or model calls. */
public final class LucyCharacterReferenceTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        equal(new int[]{512, 1152}, LucyCharacterReference.dimensions(456, 1024), "full TikTok preview uses an aligned canvas");
        equal(new int[]{1152, 512}, LucyCharacterReference.dimensions(1024, 456), "landscape orientation preserved");
        equal(new int[]{512, 576}, LucyCharacterReference.dimensions(820, 925), "reported shopping failure uses working canvas");
        equal(new int[]{512, 576}, LucyCharacterReference.dimensions(512, 578), "nearby rejected height uses working canvas");
        equal(new int[]{576, 512}, LucyCharacterReference.dimensions(578, 512), "landscape receives matching alignment");
        equal(new int[]{0, 0, 511, 576}, LucyCharacterReference.contentBounds(820, 925, 512, 576), "complete portrait fits without cropping");
        equal(new int[]{0, 15, 512, 624}, LucyCharacterReference.contentBounds(512, 609, 512, 640), "vertical padding keeps the source ratio");
        equal(new int[]{15, 0, 624, 512}, LucyCharacterReference.contentBounds(609, 512, 640, 512), "horizontal padding keeps the source ratio");
        equal(new int[]{512, 512}, LucyCharacterReference.dimensions(80, 80), "small square can satisfy Lucy minimum");
        equal(new int[]{512, 1920}, LucyCharacterReference.dimensions(512, 1920), "maximum aspect ratio supported");
        equal(new int[]{512, 1920}, LucyCharacterReference.dimensions(273, 1024), "legacy preview rounding does not reject valid edge");
        equal(new int[]{512, 512}, LucyCharacterReference.dimensions(Integer.MAX_VALUE, Integer.MAX_VALUE), "large arithmetic cannot overflow");
        reject(() -> LucyCharacterReference.dimensions(0, 100), "zero width");
        reject(() -> LucyCharacterReference.dimensions(100, -1), "negative height");
        reject(() -> LucyCharacterReference.dimensions(512, 1921), "long edge cannot force minimum below512");
        reject(() -> LucyCharacterReference.dimensions(1, Integer.MAX_VALUE), "extreme source rejected before allocation");
        Random random = new Random(261108);
        for (int i = 0; i < 400; i++) {
            int shorter = 1 + random.nextInt(4000);
            int longer = shorter + random.nextInt(Math.max(1, shorter * 2));
            int[] size = LucyCharacterReference.dimensions(shorter, longer);
            check(size[0] == 512 && size[1] >= 512 && size[1] <= 1920, "output edge contract");
            check(size[0] % 64 == 0 && size[1] % 64 == 0, "both canvas dimensions stay aligned");
            check(Math.abs(size[1] - (double) longer * 512 / shorter) <= 32.50000001, "nearest aligned canvas remains bounded");
            int[] content = LucyCharacterReference.contentBounds(shorter, longer, size[0], size[1]);
            int contentWidth = content[2] - content[0], contentHeight = content[3] - content[1];
            check(content[0] >= 0 && content[1] >= 0 && content[2] <= size[0] && content[3] <= size[1], "full source remains inside canvas");
            check(contentWidth == size[0] || contentHeight == size[1], "one dimension fills canvas");
            double scale = Math.min((double) size[0] / shorter, (double) size[1] / longer);
            check(Math.abs(contentWidth - shorter * scale) <= .50000001
                    && Math.abs(contentHeight - longer * scale) <= .50000001, "contain preserves source ratio within rounding");
        }
        check(!LucyCharacterReference.validJpegSize(0), "empty JPEG rejected");
        check(LucyCharacterReference.validJpegSize(320L * 1024), "exact payload bound accepted");
        check(!LucyCharacterReference.validJpegSize(320L * 1024 + 1), "oversize payload rejected");
        check("data:image/jpeg;base64,".length() + 4L * ((LucyCharacterReference.MAX_JPEG_BYTES + 2) / 3) < 500L * 1024,
                "Base64 remains below signaling reference bound");
        int[] qualities = LucyCharacterReference.jpegQualities();
        check(qualities.length > 1 && qualities.length <= 8, "bounded compression attempts");
        for (int i = 0; i < qualities.length; i++) {
            check(qualities[i] >= 1 && qualities[i] <= 95, "valid JPEG quality");
            if (i > 0) check(qualities[i] < qualities[i - 1], "quality descends instead of shrinking dimensions");
        }
        qualities[0] = 0;
        check(LucyCharacterReference.jpegQualities()[0] > 0, "quality plan cannot be mutated by caller");
        try (LucyCharacterReference.JpegBuffer buffer = new LucyCharacterReference.JpegBuffer()) {
            buffer.write(new byte[LucyCharacterReference.MAX_JPEG_BYTES]);
            check(!buffer.exceeded() && buffer.size() == LucyCharacterReference.MAX_JPEG_BYTES, "exact stream limit retained");
            buffer.write(1);
            check(buffer.exceeded() && buffer.size() == LucyCharacterReference.MAX_JPEG_BYTES, "single-byte overflow flagged without growth");
        }
        try (LucyCharacterReference.JpegBuffer buffer = new LucyCharacterReference.JpegBuffer()) {
            buffer.write(new byte[LucyCharacterReference.MAX_JPEG_BYTES + 100]);
            check(buffer.exceeded() && buffer.toByteArray().length == LucyCharacterReference.MAX_JPEG_BYTES,
                    "bulk overflow is bounded and cannot silently pass as complete JPEG");
        }
        System.out.println("LucyCharacterReferenceTest: " + checks + " checks passed; no device/model calls");
    }
    private static void reject(Runnable action, String message) {
        try { action.run(); throw new AssertionError(message + " not rejected"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void equal(int[] expected, int[] actual, String message) {
        check(Arrays.equals(expected, actual), message + ": " + Arrays.toString(actual));
    }
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
}
