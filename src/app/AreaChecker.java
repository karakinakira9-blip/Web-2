package app;

import java.math.BigDecimal;
import java.math.BigInteger;

public final class AreaChecker {
    private AreaChecker() {
    }

    public static boolean isHit(int x, BigDecimal y, BigDecimal r) {
        int scale = Math.max(0, Math.max(y.scale(), r.scale()));
        BigInteger xs = BigInteger.valueOf(x).multiply(BigInteger.TEN.pow(scale));
        BigInteger ys = y.setScale(scale).unscaledValue();
        BigInteger rs = r.setScale(scale).unscaledValue();

        return inRectangle(xs, ys, rs)
                || inTriangle(xs, ys, rs)
                || inQuarterCircle(xs, ys, rs);
    }

    private static boolean inRectangle(BigInteger x, BigInteger y, BigInteger r) {
        return x.compareTo(r.negate()) >= 0
                && x.signum() <= 0
                && y.shiftLeft(1).compareTo(r.negate()) >= 0
                && y.signum() <= 0;
    }

    private static boolean inTriangle(BigInteger x, BigInteger y, BigInteger r) {
        // 2(y - x) <= R при x <= 0 и y >= 0.
        return x.signum() <= 0
                && y.signum() >= 0
                && y.subtract(x).shiftLeft(1).compareTo(r) <= 0;
    }

    private static boolean inQuarterCircle(BigInteger x, BigInteger y, BigInteger r) {
        return x.signum() >= 0
                && y.signum() >= 0
                && x.multiply(x).add(y.multiply(y)).compareTo(r.multiply(r)) <= 0;
    }
}
