package ez.backend.turbo.utils;

import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.CoordinateTransformation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SpatialUtils {

    private SpatialUtils() {}

    public static List<double[][]> projectRings(List<List<double[]>> rings,
                                                CoordinateTransformation transform) {
        List<double[][]> projected = new ArrayList<>(rings.size());
        for (List<double[]> ring : rings) {
            double[][] coords = new double[ring.size()][2];
            for (int i = 0; i < ring.size(); i++) {
                double[] wgs = ring.get(i);
                Coord proj = transform.transform(new Coord(wgs[0], wgs[1]));
                coords[i][0] = proj.getX();
                coords[i][1] = proj.getY();
            }
            projected.add(coords);
        }
        return projected;
    }

    public static String toPolygonWkt(List<double[][]> rings) {
        StringBuilder sb = new StringBuilder("POLYGON(");
        for (int r = 0; r < rings.size(); r++) {
            if (r > 0) sb.append(", ");
            sb.append('(');
            double[][] ring = rings.get(r);
            for (int i = 0; i < ring.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(String.format(Locale.US, "%.6f %.6f", ring[i][0], ring[i][1]));
            }
            sb.append(')');
        }
        sb.append(')');
        return sb.toString();
    }
}
