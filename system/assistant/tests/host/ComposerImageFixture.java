import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import javax.imageio.ImageIO;

/** Host-only fixture: use the build JDK's fonts, with no Android font initialization or extra packages. */
public final class ComposerImageFixture {
    public static void main(String[] args) throws Exception {
        SecureRandom random = new SecureRandom();
        StringBuilder token = new StringBuilder("PIC-");
        for (int i = 0; i < 8; i++) token.append(random.nextInt(10));
        BufferedImage image = new BufferedImage(1100, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 32));
            graphics.drawString("Image verification token", 60, 85);
            graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 96));
            if (graphics.getFontMetrics().stringWidth(token.toString()) > 980)
                throw new IllegalStateException("Fixture font exceeds image bounds");
            graphics.drawString(token.toString(), 60, 220);
        } finally { graphics.dispose(); }
        if (!ImageIO.write(image, "png", Path.of(args[0]).toFile()))
            throw new IllegalStateException("PNG encoder unavailable");
        Files.writeString(Path.of(args[1]), token.toString());
    }
}
