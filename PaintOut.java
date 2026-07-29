import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class PaintOut {
    public static void main(String[] a) throws Exception {
        File f = new File(a[0]);
        BufferedImage img = ImageIO.read(f);
        int bg = img.getRGB(6, 6);   // 좌상단 배경색 샘플
        // 상단 중앙 배터리+43% 영역 덮기 (480 기준 x[170,310] y[0,80])
        int x0 = 170 * img.getWidth() / 480, x1 = 310 * img.getWidth() / 480;
        int y0 = 0, y1 = 82 * img.getHeight() / 480;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++)
                img.setRGB(x, y, bg);
        ImageIO.write(img, "png", f);
        System.out.println("done bg=" + Integer.toHexString(bg));
    }
}
