package com.mojud.support;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/** Generated test images. Quadrants: top-left red, top-right green, bottom-left blue, bottom-right white. */
public final class TestImages {

    /** Text placed inside the fake EXIF block; it must never survive processing. */
    public static final String GPS_MARKER = "GPS-SECRET-23.8103N-90.4125E";

    private TestImages() {
    }

    public static BufferedImage quadrants(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width / 2, height / 2);
        g.setColor(Color.GREEN);
        g.fillRect(width / 2, 0, width - width / 2, height / 2);
        g.setColor(Color.BLUE);
        g.fillRect(0, height / 2, width / 2, height - height / 2);
        g.setColor(Color.WHITE);
        g.fillRect(width / 2, height / 2, width - width / 2, height - height / 2);
        g.dispose();
        return image;
    }

    public static byte[] jpeg(int width, int height) {
        return encode(quadrants(width, height), "jpeg");
    }

    public static byte[] png(int width, int height) {
        return encode(quadrants(width, height), "png");
    }

    /** A JPEG with an EXIF block holding the orientation tag and a fake GPS position. */
    public static byte[] jpegWithExif(int width, int height, int orientation) {
        byte[] jpeg = jpeg(width, height);
        byte[] marker = GPS_MARKER.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.writeBytes(new byte[] {'I', 'I', 0x2A, 0, 8, 0, 0, 0});          // little-endian, IFD0 at 8
        tiff.writeBytes(new byte[] {1, 0});                                    // one entry
        tiff.writeBytes(new byte[] {0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0});
        tiff.writeBytes(new byte[] {0, 0, 0, 0});                              // no next IFD
        tiff.writeBytes(marker);
        byte[] payload = concat("Exif\0\0".getBytes(StandardCharsets.US_ASCII), tiff.toByteArray());
        int length = payload.length + 2;
        byte[] app1 = concat(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length}, payload);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                                                 // SOI
        out.writeBytes(app1);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** A PNG whose left half is fully transparent. */
    public static byte[] transparentPng(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.dispose();
        return encode(image, "png");
    }

    public static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }

    private static byte[] chunk(String type, byte[] data) {
        byte[] padded = data.length % 2 == 0 ? data : concat(data, new byte[] {0});
        return concat(concat(type.getBytes(StandardCharsets.US_ASCII), le32(data.length)), padded);
    }

    private static byte[] le32(int value) {
        return new byte[] {(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
