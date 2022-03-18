package fr.michaelvilleneuve.helpers;

import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;

/**
 * Created by allgood on 05/03/16.
 */
public class Quadrilateral {
    public Point[] points;

    public Quadrilateral(Point[] points) {
        this.points = points;
    }
}
