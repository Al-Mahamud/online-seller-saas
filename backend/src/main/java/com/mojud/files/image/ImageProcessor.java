package com.mojud.files.image;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.stereotype.Component;

/**
 * Turns an uploaded photo into the two images the app serves:
 * <ul>
 * <li>accepts JPEG, PNG and WebP (via TwelveMonkeys), recognised by content, not by name;</li>
 * <li>turns phone photos upright using their EXIF orientation, then re-encodes, which drops all
 * metadata, including the GPS position phones record;</li>
 * <li>makes a large ({@value #LARGE} px long side) and a small ({@value #SMALL} px) copy, as JPEG,
 * or PNG when the image has transparency (the JDK cannot write WebP);</li>
 * <li>reads huge images subsampled and refuses absurd pixel counts, so a crafted file cannot exhaust
 * memory.</li>
 * </ul>
 */
@Component
public class ImageProcessor {

    public record Processed(byte[] large, byte[] small, String contentType, String extension, int width, int height) {
    }

    static final int LARGE = 800;
    static final int SMALL = 200;
    static final int MIN_SIDE = 100;
    static final long MAX_SOURCE_PIXELS = 100_000_000L;
    private static final float JPEG_QUALITY = 0.85f;

    public Processed process(byte[] upload) {
        FileType type = FileType.detect(upload).orElseThrow(ImageProcessor::unsupported);
        BufferedImage image = decode(upload);
        // Shrink first, so turning never handles more than LARGE² pixels.
        boolean transparent = image.getColorModel().hasAlpha();
        BufferedImage large = fit(image, LARGE, transparent);
        large = orient(large, type == FileType.JPEG ? ExifOrientation.read(upload) : 1);
        BufferedImage small = fit(large, SMALL, transparent);
        String format = transparent ? "png" : "jpeg";
        return new Processed(encode(large, format), encode(small, format), transparent ? "image/png" : "image/jpeg",
                transparent ? "png" : "jpg", large.getWidth(), large.getHeight());
    }

    private static BufferedImage decode(byte[] upload) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(upload))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw unsupported();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < MIN_SIDE || height < MIN_SIDE) {
                    throw new ApiException(ErrorCode.IMAGE_TOO_SMALL, MIN_SIDE);
                }
                if ((long) width * height > MAX_SOURCE_PIXELS) {
                    throw unsupported();
                }
                ImageReadParam param = reader.getDefaultReadParam();
                // Read at no less than twice the large size, then scale smoothly.
                int subsampling = Math.max(1, Math.max(width, height) / (LARGE * 2));
                param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Corrupt files, CMYK JPEGs and the like.
            throw unsupported();
        }
    }

    /**
     * Turns the image upright according to its EXIF orientation (1-8, EXIF 2.3 §4.6.4). Each case
     * maps a source pixel (x, y) of a w x h image to its upright position.
     */
    static BufferedImage orient(BufferedImage image, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swaps = orientation >= 5;
        int targetWidth = swaps ? h : w;
        int[] source = image.getRGB(0, 0, w, h, null, 0, w);
        int[] target = new int[source.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int tx;
                int ty;
                switch (orientation) {
                    case 2 -> { tx = w - 1 - x; ty = y; }             // mirror horizontally
                    case 3 -> { tx = w - 1 - x; ty = h - 1 - y; }     // rotate 180°
                    case 4 -> { tx = x; ty = h - 1 - y; }             // mirror vertically
                    case 5 -> { tx = y; ty = x; }                     // transpose
                    case 6 -> { tx = h - 1 - y; ty = x; }             // rotate 90° clockwise
                    case 7 -> { tx = h - 1 - y; ty = w - 1 - x; }     // transverse
                    default -> { tx = y; ty = w - 1 - x; }            // 8: rotate 90° anticlockwise
                }
                target[ty * targetWidth + tx] = source[y * w + x];
            }
        }
        BufferedImage turned = new BufferedImage(targetWidth, swaps ? w : h, image.getType());
        turned.setRGB(0, 0, targetWidth, swaps ? w : h, target, 0, targetWidth);
        return turned;
    }

    /** Scales down so the long side is at most {@code longSide}; never scales up. */
    private static BufferedImage fit(BufferedImage image, int longSide, boolean transparent) {
        double scale = Math.min(1.0, (double) longSide / Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        int type = transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(width, height, type);
        Graphics2D g = target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        if (!transparent) {
            // A JPEG has no transparency: what was see-through becomes white, not black.
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, width, height);
        }
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();
        return target;
    }

    private static byte[] encode(BufferedImage image, String format) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (format.equals("jpeg")) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode image", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static ApiException unsupported() {
        return new ApiException(ErrorCode.UNSUPPORTED_FILE);
    }
}
