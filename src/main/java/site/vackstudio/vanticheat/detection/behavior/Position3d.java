package site.vackstudio.vanticheat.detection.behavior;

public record Position3d(double x, double y, double z) {
    public double distance(Position3d other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double horizontalDistance(Position3d other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
