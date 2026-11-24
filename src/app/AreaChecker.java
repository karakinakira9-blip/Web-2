package app;

public final class AreaChecker {
    private static final double EPSILON = 1e-9;

    private AreaChecker() {
    }

    public static boolean isHit(double x, double y, double r) {
        return inRectangle(x, y, r)
                || inTriangle(x, y, r)
                || inQuarterCircle(x, y, r);
    }

    private static boolean inRectangle(double x, double y, double r) {
        return x >= -r - EPSILON
                && x <= EPSILON
                && y >= -r / 2.0 - EPSILON
                && y <= EPSILON;
    }

    private static boolean inTriangle(double x, double y, double r) {
        return x >= -r / 2.0 - EPSILON
                && x <= EPSILON
                && y >= -EPSILON
                && y <= x + r / 2.0 + EPSILON;
    }

    private static boolean inQuarterCircle(double x, double y, double r) {
        return x >= -EPSILON
                && y >= -EPSILON
                && x * x + y * y <= r * r + EPSILON;
    }
}
