import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Small dependency-free preview renderer for the emerald golem model.
 *
 * It mirrors the cuboids and texture offsets from EmeraldGolemModel.java and
 * is intentionally kept outside the mod source set. This makes it useful for
 * checking a texture/model pairing without starting a Minecraft client.
 */
public final class RenderEmeraldGolem {
    private static final int FINAL_WIDTH = 900;
    private static final int FINAL_HEIGHT = 1100;
    private static final int SUPER_SAMPLE = 2;
    private static final int WIDTH = FINAL_WIDTH * SUPER_SAMPLE;
    private static final int HEIGHT = FINAL_HEIGHT * SUPER_SAMPLE;
    private static final double PIXELS_PER_MODEL_UNIT = 25.0 * SUPER_SAMPLE;

    private static final Vec3 CAMERA = new Vec3(8.0, 7.0, -13.0);
    private static final Vec3 TARGET = new Vec3(0.0, 16.0, 0.0);
    private static final Vec3 WORLD_UP = new Vec3(0.0, 1.0, 0.0);
    private static final Vec3 LIGHT = new Vec3(-0.45, 0.86, -0.92).normalize();

    private record Vec3(double x, double y, double z) {
        private Vec3 add(Vec3 other) {
            return new Vec3(x + other.x, y + other.y, z + other.z);
        }

        private Vec3 subtract(Vec3 other) {
            return new Vec3(x - other.x, y - other.y, z - other.z);
        }

        private Vec3 multiply(double amount) {
            return new Vec3(x * amount, y * amount, z * amount);
        }

        private double dot(Vec3 other) {
            return x * other.x + y * other.y + z * other.z;
        }

        private Vec3 cross(Vec3 other) {
            return new Vec3(y * other.z - z * other.y, z * other.x - x * other.z,
                    x * other.y - y * other.x);
        }

        private double length() {
            return Math.sqrt(dot(this));
        }

        private Vec3 normalize() {
            double length = length();
            return length == 0.0 ? this : multiply(1.0 / length);
        }
    }

    private record Projected(double x, double y, double depth) {
    }

    private static final class Face {
        private final Vec3[] vertices;
        private final double[][] uv;
        private final Vec3 normal;
        private final double depth;
        private final double shade;

        private Face(Vec3[] vertices, double[][] uv) {
            this.vertices = vertices;
            this.uv = uv;
            this.normal = vertices[1].subtract(vertices[0])
                    .cross(vertices[2].subtract(vertices[0])).normalize();
            this.depth = vertices[0].add(vertices[1]).add(vertices[2]).add(vertices[3])
                    .multiply(0.25).subtract(CAMERA).dot(forward());
            this.shade = 0.62 + 0.38 * Math.max(0.0, normal.dot(LIGHT));
        }
    }

    private static final class Box {
        private final float x;
        private final float y;
        private final float z;
        private final float width;
        private final float height;
        private final float depth;
        private final int u;
        private final int v;

        private Box(float x, float y, float z, float width, float height, float depth, int u, int v) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.u = u;
            this.v = v;
        }
    }

    private static Vec3 forward() {
        return TARGET.subtract(CAMERA).normalize();
    }

    private static Vec3 cameraRight() {
        return WORLD_UP.cross(forward()).normalize();
    }

    private static Vec3 cameraUp() {
        return forward().cross(cameraRight()).normalize();
    }

    private static Projected project(Vec3 vertex) {
        Vec3 relative = vertex.subtract(TARGET);
        double centerX = WIDTH * 0.5;
        double centerY = HEIGHT * 0.605;
        return new Projected(centerX + relative.dot(cameraRight()) * PIXELS_PER_MODEL_UNIT,
                centerY - relative.dot(cameraUp()) * PIXELS_PER_MODEL_UNIT,
                vertex.subtract(CAMERA).dot(forward()));
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: java RenderEmeraldGolem <texture.png> <output.png>");
        }

        BufferedImage texture = ImageIO.read(new File(args[0]));
        if (texture == null || texture.getWidth() != 128 || texture.getHeight() != 128) {
            throw new IllegalArgumentException("Expected a readable 128x128 texture atlas: " + args[0]);
        }

        BufferedImage rendered = createBackground();
        List<Face> faces = new ArrayList<>();
        addModel(faces);
        faces.sort(Comparator.comparingDouble(face -> -face.depth));

        double[] depthBuffer = new double[WIDTH * HEIGHT];
        java.util.Arrays.fill(depthBuffer, Double.POSITIVE_INFINITY);
        for (Face face : faces) {
            drawFace(rendered, texture, depthBuffer, face);
        }

        BufferedImage output = new BufferedImage(FINAL_WIDTH, FINAL_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D downsample = output.createGraphics();
        downsample.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        downsample.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        downsample.drawImage(rendered, 0, 0, FINAL_WIDTH, FINAL_HEIGHT, null);
        downsample.dispose();

        File outputFile = new File(args[1]);
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create output directory: " + parent);
        }
        ImageIO.write(output, "png", outputFile);
    }

    private static BufferedImage createBackground() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setPaint(new GradientPaint(0, 0, new Color(6, 24, 26), 0, HEIGHT,
                new Color(14, 55, 52)));
        graphics.fillRect(0, 0, WIDTH, HEIGHT);

        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.28f));
        graphics.setPaint(new RadialGradientPaint(WIDTH * 0.5f, HEIGHT * 0.47f, WIDTH * 0.5f,
                new float[]{0.0f, 1.0f}, new Color[]{new Color(45, 170, 125, 110), new Color(5, 22, 24, 0)}));
        graphics.fillRect(0, 0, WIDTH, HEIGHT);

        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.42f));
        graphics.setPaint(new Color(0, 0, 0, 180));
        graphics.fill(new Ellipse2D.Double(WIDTH * 0.18, HEIGHT * 0.855, WIDTH * 0.64, HEIGHT * 0.075));
        graphics.dispose();
        return image;
    }

    private static void addModel(List<Face> faces) {
        // Coordinates below are the final cuboid coordinates after each
        // PartPose.offset in EmeraldGolemModel.createBodyLayer(). Minecraft's
        // model Y axis points downward, so addBoxY converts it to world-up Y.
        addBox(faces, -4.0f, -33.0f, -7.5f, 8.0f, 10.0f, 8.0f, 0, 0);
        addBox(faces, -1.0f, -26.0f, -9.5f, 2.0f, 4.0f, 2.0f, 24, 0);

        addBox(faces, -5.0f, -23.0f, -5.0f, 10.0f, 8.0f, 9.0f, 10, 42);
        addBox(faces, -3.5f, -15.0f, -3.0f, 7.0f, 2.0f, 6.0f, 2, 70);

        addBox(faces, 5.0f, -22.5f, -3.0f, 3.0f, 21.0f, 5.0f, 62, 22);
        addBox(faces, 5.0f, -23.5f, -3.0f, 2.0f, 1.0f, 5.0f, 62, 59);

        addBox(faces, -8.0f, -22.5f, -3.0f, 3.0f, 21.0f, 5.0f, 62, 59);
        addBox(faces, -7.0f, -23.5f, -3.0f, 2.0f, 1.0f, 5.0f, 60, 59);

        addBox(faces, 0.5f, -13.0f, -3.0f, 3.0f, 13.0f, 5.0f, 40, 0);
        addBox(faces, -3.5f, -13.0f, -3.0f, 3.0f, 13.0f, 5.0f, 63, 0);
    }

    private static void addBox(List<Face> faces, float x, float modelY, float z,
                               float width, float height, float depth, int u, int v) {
        double x0 = x;
        double x1 = x + width;
        double yTop = -modelY;
        double yBottom = -(modelY + height);
        double z0 = z;
        double z1 = z + depth;

        // These six layouts match the standard CubeListBuilder.texOffs UV
        // packing used by Minecraft: top, bottom, right, front, left, back.
        faces.add(new Face(new Vec3[]{
                new Vec3(x0, yTop, z0), new Vec3(x1, yTop, z0),
                new Vec3(x1, yTop, z1), new Vec3(x0, yTop, z1)},
                uv(u + (int) depth, v, u + (int) depth + (int) width, v + (int) depth)));
        faces.add(new Face(new Vec3[]{
                new Vec3(x0, yBottom, z1), new Vec3(x1, yBottom, z1),
                new Vec3(x1, yBottom, z0), new Vec3(x0, yBottom, z0)},
                uv(u + (int) depth + (int) width, v, u + (int) depth + (int) width * 2, v + (int) depth)));
        faces.add(new Face(new Vec3[]{
                new Vec3(x1, yTop, z0), new Vec3(x1, yTop, z1),
                new Vec3(x1, yBottom, z1), new Vec3(x1, yBottom, z0)},
                uv(u, v + (int) depth, u + (int) depth, v + (int) depth + (int) height)));
        faces.add(new Face(new Vec3[]{
                new Vec3(x0, yTop, z0), new Vec3(x1, yTop, z0),
                new Vec3(x1, yBottom, z0), new Vec3(x0, yBottom, z0)},
                uv(u + (int) depth, v + (int) depth, u + (int) depth + (int) width,
                        v + (int) depth + (int) height)));
        faces.add(new Face(new Vec3[]{
                new Vec3(x0, yTop, z1), new Vec3(x0, yTop, z0),
                new Vec3(x0, yBottom, z0), new Vec3(x0, yBottom, z1)},
                uv(u + (int) depth + (int) width, v + (int) depth,
                        u + (int) depth + (int) width + (int) depth,
                        v + (int) depth + (int) height)));
        faces.add(new Face(new Vec3[]{
                new Vec3(x1, yTop, z1), new Vec3(x0, yTop, z1),
                new Vec3(x0, yBottom, z1), new Vec3(x1, yBottom, z1)},
                uv(u + (int) depth + (int) width + (int) depth, v + (int) depth,
                        u + (int) depth + (int) width + (int) depth + (int) width,
                        v + (int) depth + (int) height)));
    }

    private static double[][] uv(int left, int top, int right, int bottom) {
        return new double[][]{{left, top}, {right, top}, {right, bottom}, {left, bottom}};
    }

    private static void drawFace(BufferedImage output, BufferedImage texture, double[] depthBuffer, Face face) {
        Projected[] projected = new Projected[4];
        for (int i = 0; i < 4; i++) {
            projected[i] = project(face.vertices[i]);
        }
        drawTriangle(output, texture, depthBuffer, face, projected[0], projected[1], projected[2], 0, 1, 2);
        drawTriangle(output, texture, depthBuffer, face, projected[0], projected[2], projected[3], 0, 2, 3);
    }

    private static void drawTriangle(BufferedImage output, BufferedImage texture, double[] depthBuffer,
                                     Face face, Projected a, Projected b, Projected c,
                                     int ia, int ib, int ic) {
        double denominator = (b.y() - c.y()) * (a.x() - c.x()) + (c.x() - b.x()) * (a.y() - c.y());
        if (Math.abs(denominator) < 0.001) {
            return;
        }

        int minX = Math.max(0, (int) Math.floor(Math.min(a.x(), Math.min(b.x(), c.x()))));
        int maxX = Math.min(WIDTH - 1, (int) Math.ceil(Math.max(a.x(), Math.max(b.x(), c.x()))));
        int minY = Math.max(0, (int) Math.floor(Math.min(a.y(), Math.min(b.y(), c.y()))));
        int maxY = Math.min(HEIGHT - 1, (int) Math.ceil(Math.max(a.y(), Math.max(b.y(), c.y()))));

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5;
                double py = y + 0.5;
                double wa = ((b.y() - c.y()) * (px - c.x()) + (c.x() - b.x()) * (py - c.y())) / denominator;
                double wb = ((c.y() - a.y()) * (px - c.x()) + (a.x() - c.x()) * (py - c.y())) / denominator;
                double wc = 1.0 - wa - wb;
                if (wa < -0.001 || wb < -0.001 || wc < -0.001) {
                    continue;
                }

                double depth = wa * a.depth() + wb * b.depth() + wc * c.depth();
                int bufferIndex = y * WIDTH + x;
                if (depth >= depthBuffer[bufferIndex]) {
                    continue;
                }

                double u = wa * face.uv[ia][0] + wb * face.uv[ib][0] + wc * face.uv[ic][0];
                double v = wa * face.uv[ia][1] + wb * face.uv[ib][1] + wc * face.uv[ic][1];
                int textureX = clamp((int) Math.floor(u), 0, texture.getWidth() - 1);
                int textureY = clamp((int) Math.floor(v), 0, texture.getHeight() - 1);
                int argb = texture.getRGB(textureX, textureY);
                int alpha = (argb >>> 24) & 0xff;
                if (alpha == 0) {
                    continue;
                }

                int red = shade((argb >>> 16) & 0xff, face.shade);
                int green = shade((argb >>> 8) & 0xff, face.shade);
                int blue = shade(argb & 0xff, face.shade);
                output.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
                depthBuffer[bufferIndex] = depth;
            }
        }
    }

    private static int shade(int channel, double multiplier) {
        return clamp((int) Math.round(channel * multiplier), 0, 255);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
