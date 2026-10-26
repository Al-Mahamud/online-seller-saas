package com.mojud.files.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mojud.shared.error.ApiException;
import com.mojud.support.TestImages;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor();

    @Test
    void photosAreStoredLargeAndSmallAsJpeg() throws IOException {
        ImageProcessor.Processed image = processor.process(TestImages.jpeg(2400, 1200));

        assertThat(image.contentType()).isEqualTo("image/jpeg");
        assertThat(read(image.large())).extracting(BufferedImage::getWidth, BufferedImage::getHeight).containsExactly(800, 400);
        assertThat(read(image.small())).extracting(BufferedImage::getWidth, BufferedImage::getHeight).containsExactly(200, 100);
        assertThat(image.width()).isEqualTo(800);
    }

    @Test
    void smallImagesAreNotEnlarged() throws IOException {
        assertThat(read(processor.process(TestImages.png(300, 150)).large()).getWidth()).isEqualTo(300);
    }

    @Test
    void transparencyIsKeptAsPng() throws IOException {
        ImageProcessor.Processed image = processor.process(TestImages.transparentPng(400, 400));

        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(new Color(read(image.large()).getRGB(10, 10), true).getAlpha()).isZero();
    }

    @Test
    void phonePhotosAreTurnedUprightAndLoseTheirLocation() throws IOException {
        // Orientation 6: the camera was turned; the picture must be rotated 90° clockwise.
        byte[] upload = TestImages.jpegWithExif(1000, 600, 6);
        assertThat(TestImages.contains(upload, TestImages.GPS_MARKER)).isTrue();

        ImageProcessor.Processed image = processor.process(upload);

        BufferedImage large = read(image.large());
        assertThat(large.getWidth()).isEqualTo(480);
        assertThat(large.getHeight()).isEqualTo(800);
        // Top-left was red; after turning clockwise it is the top-right corner.
        assertThat(isRoughly(large.getRGB(large.getWidth() - 20, 20), Color.RED)).isTrue();
        assertThat(TestImages.contains(image.large(), TestImages.GPS_MARKER)).isFalse();
        assertThat(TestImages.contains(image.small(), TestImages.GPS_MARKER)).isFalse();
    }

    @Test
    void theContentDecidesNotTheName() {
        byte[] program = "MZ\u0090\u0000 this is a Windows program".getBytes(StandardCharsets.ISO_8859_1);
        byte[] fakeJpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5};

        assertThatThrownBy(() -> processor.process(program)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code().code()).isEqualTo("file.unsupported"));
        assertThatThrownBy(() -> processor.process(fakeJpeg)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code().code()).isEqualTo("file.unsupported"));
    }

    @Test
    void tinyImagesAreRefused() {
        assertThatThrownBy(() -> processor.process(TestImages.png(50, 300))).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code().code()).isEqualTo("file.image_too_small"));
    }

    @Test
    void webpCanBeRead() {
        assertThat(ImageIO.getImageReadersByFormatName("webp").hasNext()).isTrue();
    }

    private static BufferedImage read(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static boolean isRoughly(int rgb, Color expected) {
        Color actual = new Color(rgb);
        return Math.abs(actual.getRed() - expected.getRed()) < 40 && Math.abs(actual.getGreen() - expected.getGreen()) < 40
                && Math.abs(actual.getBlue() - expected.getBlue()) < 40;
    }
}
