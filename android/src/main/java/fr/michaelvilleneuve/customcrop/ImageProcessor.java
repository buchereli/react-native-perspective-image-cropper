package fr.michaelvilleneuve.customcrop;

import com.facebook.react.bridge.WritableMap;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.shapes.PathShape;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;

import com.facebook.react.bridge.WritableNativeMap;
import android.util.Log;


import com.facebook.react.bridge.Callback;


import fr.michaelvilleneuve.helpers.Quadrilateral;
import fr.michaelvilleneuve.helpers.ScannedDocument;
import fr.michaelvilleneuve.helpers.Utils;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.RotatedRect;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;

public class ImageProcessor {

  private static final String TAG = "l33t";
  private Size mPreviewSize;
  private Point[] mPreviewPoints;

  // public WritableMap processPicture(Mat img) {
  //   // Mat img = Imgcodecs.imdecode(picture, Imgcodecs.CV_LOAD_IMAGE_UNCHANGED);
  //   // picture.release();

  //   Log.d(TAG, "processPicture - imported image " + img.size().width + "x" + img.size().height);

  //   ScannedDocument doc = detectDocument(img);
  //   doc.release();
  //   img.release();

  //   return doc.pointsAsHash();
  // }

private Point[] processTextBlock(Text result) {
        // [START mlkit_process_text_block]
        ArrayList<Point> points = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            android.graphics.Point[] blockCornerPoints = block.getCornerPoints();
            for (android.graphics.Point p : blockCornerPoints){
              points.add(new Point(p.x, p.y));
            }
        }
        MatOfPoint2f temp = new MatOfPoint2f();
        temp.fromList(points);
        Point[] vertices = { null, null, null, null };
        if (points.size() != 0){
          RotatedRect rrect = Imgproc.minAreaRect(temp);
          rrect.points(vertices);
        }

      return vertices;
        // [END mlkit_process_text_block]
    }


  public void processPicture(Mat img, Callback callback) {
    // Mat img = Imgcodecs.imdecode(picture, Imgcodecs.CV_LOAD_IMAGE_UNCHANGED);
    // picture.release();
    TextRecognizer recognizer = TextRecognition.getClient();

    Log.d(TAG, "processPicture - imported image " + img.size().width + "x" + img.size().height);


    ScannedDocument sd = new ScannedDocument(img);

    ArrayList<MatOfPoint> contours = findContours(img);
    sd.originalSize = img.size();
    Quadrilateral quad = getQuadrilateral(contours, sd.originalSize);

    android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(img.cols(), img.rows(), android.graphics.Bitmap.Config.ARGB_8888);
    Utils.matToBitmap(img, bmp);
    InputImage image = InputImage.fromBitmap(bmp, 0);
    final Size srcSize = img.size();

      OnSuccessListener<Text> onTextSuccess = new OnSuccessListener<Text>() {
          @Override
          public void onSuccess(Text visionText) {
            Point[] pts = processTextBlock(visionText);
                Bundle data = new Bundle();
              double ratio = sd.originalSize.height / 500;
            sd.heightWithRatio = Double.valueOf(sd.originalSize.width / ratio).intValue();
            sd.widthWithRatio = Double.valueOf(sd.originalSize.height / ratio).intValue();

            Mat doc;
                if (pts[0] != null){
                    Quadrilateral quad = new Quadrilateral(sortPoints(pts), srcSize);
                    Log.d(TAG, "quad " + quad);
                    sd.originalPoints = new Point[4];

              sd.originalPoints[0] = new Point(quad.points[3].x * ratio, quad.points[3].y * ratio); // TopLeft
              sd.originalPoints[1] = new Point(quad.points[0].x * ratio, quad.points[0].y * ratio); // TopRight
              sd.originalPoints[2] = new Point(quad.points[1].x * ratio, quad.points[1].y * ratio); // BottomRight
              sd.originalPoints[3] = new Point(quad.points[2].x * ratio, quad.points[2].y * ratio); // BottomLeft

              sd.quadrilateral = quad;
              sd.previewPoints = mPreviewPoints;
              sd.previewSize = mPreviewSize;

              doc = fourPointTransform(img, quad.points);
                } else {
                   doc = new Mat(img.size(), CvType.CV_8UC4);
              img.copyTo(doc); 
                }

            ScannedDocument sdoc = sd.setProcessed(doc);
            doc.release();
            img.release();

            callback.invoke(null, sdoc.pointsAsHash());
          }
      };


      Task<Text> result =
      recognizer.process(image)
              .addOnSuccessListener(onTextSuccess)
              .addOnFailureListener(
                      new OnFailureListener() {
                          @Override
                          public void onFailure( Exception e) {
                            img.release();
                            callback.invoke(null, new WritableNativeMap(););
    
                              // Task failed with an exception
                              // ...
                          }
                      });
  }

  

  private Quadrilateral getQuadrilateral(ArrayList<MatOfPoint> contours, Size srcSize) {
    double ratio = srcSize.height / 500;
    int height = Double.valueOf(srcSize.height / ratio).intValue();
    int width = Double.valueOf(srcSize.width / ratio).intValue();
    Size size = new Size(width, height);

    Log.i("COUCOU", "Size----->" + size);
    for (MatOfPoint c : contours) {
      MatOfPoint2f c2f = new MatOfPoint2f(c.toArray());
      double peri = Imgproc.arcLength(c2f, true);
      MatOfPoint2f approx = new MatOfPoint2f();
      Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true);

      Point[] points = approx.toArray();

      // select biggest 4 angles polygon
      // if (points.length == 4) {
      Point[] foundPoints = sortPoints(points);

      if (insideArea(foundPoints, size)) {
        return new Quadrilateral(c, foundPoints);
      }
      // }
    }

    return null;
  }

  private Point[] sortPoints(Point[] src) {

    ArrayList<Point> srcPoints = new ArrayList<>(Arrays.asList(src));

    Point[] result = { null, null, null, null };

    Comparator<Point> sumComparator = new Comparator<Point>() {
      @Override
      public int compare(Point lhs, Point rhs) {
        return Double.compare(lhs.y + lhs.x, rhs.y + rhs.x);
      }
    };

    Comparator<Point> diffComparator = new Comparator<Point>() {

      @Override
      public int compare(Point lhs, Point rhs) {
        return Double.compare(lhs.y - lhs.x, rhs.y - rhs.x);
      }
    };

    // top-left corner = minimal sum
    result[0] = Collections.min(srcPoints, sumComparator);

    // bottom-right corner = maximal sum
    result[2] = Collections.max(srcPoints, sumComparator);

    // top-right corner = minimal difference
    result[1] = Collections.min(srcPoints, diffComparator);

    // bottom-left corner = maximal difference
    result[3] = Collections.max(srcPoints, diffComparator);

    return result;
  }

  private boolean insideArea(Point[] rp, Size size) {
    int width = Double.valueOf(size.width).intValue();
    int height = Double.valueOf(size.height).intValue();

    int minimumSize = width / 10;

    boolean isANormalShape = rp[0].x != rp[1].x && rp[1].y != rp[0].y && rp[2].y != rp[3].y && rp[3].x != rp[2].x;
    boolean isBigEnough = ((rp[1].x - rp[0].x >= minimumSize) && (rp[2].x - rp[3].x >= minimumSize)
        && (rp[3].y - rp[0].y >= minimumSize) && (rp[2].y - rp[1].y >= minimumSize));

    double leftOffset = rp[0].x - rp[3].x;
    double rightOffset = rp[1].x - rp[2].x;
    double bottomOffset = rp[0].y - rp[1].y;
    double topOffset = rp[2].y - rp[3].y;

    boolean isAnActualRectangle = ((leftOffset <= minimumSize && leftOffset >= -minimumSize)
        && (rightOffset <= minimumSize && rightOffset >= -minimumSize)
        && (bottomOffset <= minimumSize && bottomOffset >= -minimumSize)
        && (topOffset <= minimumSize && topOffset >= -minimumSize));

    return isANormalShape && isAnActualRectangle && isBigEnough;
  }

  private Mat fourPointTransform(Mat src, Point[] pts) {
    double ratio = src.size().height / 500;

    Point tl = pts[0];
    Point tr = pts[1];
    Point br = pts[2];
    Point bl = pts[3];

    double widthA = Math.sqrt(Math.pow(br.x - bl.x, 2) + Math.pow(br.y - bl.y, 2));
    double widthB = Math.sqrt(Math.pow(tr.x - tl.x, 2) + Math.pow(tr.y - tl.y, 2));

    double dw = Math.max(widthA, widthB) * ratio;
    int maxWidth = Double.valueOf(dw).intValue();

    double heightA = Math.sqrt(Math.pow(tr.x - br.x, 2) + Math.pow(tr.y - br.y, 2));
    double heightB = Math.sqrt(Math.pow(tl.x - bl.x, 2) + Math.pow(tl.y - bl.y, 2));

    double dh = Math.max(heightA, heightB) * ratio;
    int maxHeight = Double.valueOf(dh).intValue();

    Mat doc = new Mat(maxHeight, maxWidth, CvType.CV_8UC4);

    Mat src_mat = new Mat(4, 1, CvType.CV_32FC2);
    Mat dst_mat = new Mat(4, 1, CvType.CV_32FC2);

    src_mat.put(0, 0, tl.x * ratio, tl.y * ratio, tr.x * ratio, tr.y * ratio, br.x * ratio, br.y * ratio, bl.x * ratio,
        bl.y * ratio);
    dst_mat.put(0, 0, 0.0, 0.0, dw, 0.0, dw, dh, 0.0, dh);

    Mat m = Imgproc.getPerspectiveTransform(src_mat, dst_mat);

    Imgproc.warpPerspective(src, doc, m, doc.size());

    return doc;
  }

  private ArrayList<MatOfPoint> findContours(Mat src) {
    Mat grayImage;
    Mat cannedImage;
    Mat resizedImage;
    Mat binaryImage;

    double ratio = src.size().height / 500;
    int height = Double.valueOf(src.size().height / ratio).intValue();
    int width = Double.valueOf(src.size().width / ratio).intValue();
    Size size = new Size(width, height);

    resizedImage = new Mat(size, CvType.CV_8UC4);
    grayImage = new Mat(size, CvType.CV_8UC4);
    binaryImage = new Mat(size, CvType.CV_8UC4);
    cannedImage = new Mat(size, CvType.CV_8UC1);

    Imgproc.resize(src, resizedImage, size);
    Imgproc.cvtColor(resizedImage, grayImage, Imgproc.COLOR_RGBA2GRAY, 4);
    Imgproc.GaussianBlur(grayImage, grayImage, new Size(5, 5), 0);
    Imgproc.threshold(grayImage, binaryImage, 0, 255, Imgproc.THRESH_TOZERO + Imgproc.THRESH_OTSU);
    Imgproc.Canny(binaryImage, cannedImage, 80, 100, 3, false);

    ArrayList<MatOfPoint> contours = new ArrayList<>();
    Mat hierarchy = new Mat();

    Imgproc.findContours(cannedImage, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);

    hierarchy.release();

    Collections.sort(contours, new Comparator<MatOfPoint>() {

      @Override
      public int compare(MatOfPoint lhs, MatOfPoint rhs) {
        return Double.compare(Imgproc.contourArea(rhs), Imgproc.contourArea(lhs));
      }
    });

    resizedImage.release();
    grayImage.release();
    cannedImage.release();

    return contours;
  }
}
