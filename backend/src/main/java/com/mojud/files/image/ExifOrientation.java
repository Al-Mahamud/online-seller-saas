package com.mojud.files.image;

/**
 * Reads the EXIF orientation tag (0x0112) from a JPEG, so a phone photo can be turned upright
 * before its metadata is thrown away. Returns 1 (upright) for anything it cannot read.
 */
final class ExifOrientation {

    private static final int ORIENTATION_TAG = 0x0112;

    private ExifOrientation() {
    }

    static int read(byte[] jpeg) {
        try {
            int offset = 2;
            while (offset + 4 <= jpeg.length && u8(jpeg, offset) == 0xFF) {
                int marker = u8(jpeg, offset + 1);
                if (marker == 0xDA || marker == 0xD9) {
                    return 1; // start of image data or end of image: no EXIF before it
                }
                int length = u16(jpeg, offset + 2, false);
                int data = offset + 4;
                if (marker == 0xE1 && length >= 8 && FileType.ascii(jpeg, data, 4).equals("Exif")) {
                    return fromTiff(jpeg, data + 6);
                }
                offset += 2 + length;
            }
        } catch (IndexOutOfBoundsException e) {
            // Truncated or malformed EXIF: treat as upright.
        }
        return 1;
    }

    private static int fromTiff(byte[] b, int tiff) {
        boolean littleEndian = b[tiff] == 'I' && b[tiff + 1] == 'I';
        int ifd = tiff + (int) u32(b, tiff + 4, littleEndian);
        int entries = u16(b, ifd, littleEndian);
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (u16(b, entry, littleEndian) == ORIENTATION_TAG) {
                int value = u16(b, entry + 8, littleEndian);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    private static int u16(byte[] b, int i, boolean littleEndian) {
        return littleEndian ? u8(b, i) | u8(b, i + 1) << 8 : u8(b, i) << 8 | u8(b, i + 1);
    }

    private static long u32(byte[] b, int i, boolean littleEndian) {
        return littleEndian
                ? (long) u16(b, i, true) | (long) u16(b, i + 2, true) << 16
                : (long) u16(b, i, false) << 16 | u16(b, i + 2, false);
    }
}
