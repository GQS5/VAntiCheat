package site.vackstudio.vanticheat.detection.behavior;

public record BoundingBox3d(double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ) {
    public double distanceTo(Position3d point) {
        double dx = axisDistance(point.x(), minX, maxX);
        double dy = axisDistance(point.y(), minY, maxY);
        double dz = axisDistance(point.z(), minZ, maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) return min - value;
        if (value > max) return value - max;
        return 0;
    }
}
