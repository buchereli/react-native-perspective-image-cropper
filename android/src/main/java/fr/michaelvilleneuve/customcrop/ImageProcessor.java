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


import org.opencv.android.Utils;
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


import android.os.Bundle;

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
    TextRecognizer recognizer = TextRecognition.getClient();

    Log.d(TAG, "processPicture - imported image " + img.size().width + "x" + img.size().height);


    final ScannedDocument sd = new ScannedDocument(img);
    android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(img.cols(), img.rows(), android.graphics.Bitmap.Config.ARGB_8888);
    Utils.matToBitmap(img, bmp);
    final InputImage image = InputImage.fromBitmap(bmp, 0);
    final Size srcSize = img.size();
    final Callback finalCallback = callback;
    final Mat finalImg = img;

      OnSuccessListener<Text> onTextSuccess = new OnSuccessListener<Text>() {
          @Override
          public void onSuccess(Text visionText) {
            Point[] pts = processTextBlock(visionText);
                Bundle data = new Bundle();

            Mat doc;
                if (pts[0] != null){
                    Quadrilateral quad = new Quadrilateral(sortPoints(pts));
                    Log.d(TAG, "quad " + quad);
                    sd.originalPoints = new Point[4];

              sd.originalPoints[0] = new Point(quad.points[3].x, quad.points[3].y); // TopLeft
              sd.originalPoints[1] = new Point(quad.points[0].x, quad.points[0].y); // TopRight
              sd.originalPoints[2] = new Point(quad.points[1].x, quad.points[1].y); // BottomRight
              sd.originalPoints[3] = new Point(quad.points[2].x, quad.points[2].y); // BottomLeft

              sd.previewPoints = mPreviewPoints;
              sd.previewSize = mPreviewSize;

              doc = fourPointTransform(finalImg, quad.points);
                } else {
                   doc = new Mat(finalImg.size(), CvType.CV_8UC4);
              finalImg.copyTo(doc); 
                }

            ScannedDocument sdoc = sd.setProcessed(doc);
            doc.release();
            finalImg.release();

            finalCallback.invoke(null, sdoc.pointsAsHash());
          }
      };


      Task<Text> result =
      recognizer.process(image)
              .addOnSuccessListener(onTextSuccess)
              .addOnFailureListener(
                      new OnFailureListener() {
                          @Override
                          public void onFailure( Exception e) {
                            finalImg.release();
                            finalCallback.invoke(null, new WritableNativeMap());
    
                              // Task failed with an exception
                              // ...
                          }
                      });
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

  private Mat fourPointTransform(Mat src, Point[] pts) {

    Point tl = pts[0];
    Point tr = pts[1];
    Point br = pts[2];
    Point bl = pts[3];

    double widthA = Math.sqrt(Math.pow(br.x - bl.x, 2) + Math.pow(br.y - bl.y, 2));
    double widthB = Math.sqrt(Math.pow(tr.x - tl.x, 2) + Math.pow(tr.y - tl.y, 2));

    double dw = Math.max(widthA, widthB);
    int maxWidth = Double.valueOf(dw).intValue();

    double heightA = Math.sqrt(Math.pow(tr.x - br.x, 2) + Math.pow(tr.y - br.y, 2));
    double heightB = Math.sqrt(Math.pow(tl.x - bl.x, 2) + Math.pow(tl.y - bl.y, 2));

    double dh = Math.max(heightA, heightB);
    int maxHeight = Double.valueOf(dh).intValue();

    Mat doc = new Mat(maxHeight, maxWidth, CvType.CV_8UC4);

    Mat src_mat = new Mat(4, 1, CvType.CV_32FC2);
    Mat dst_mat = new Mat(4, 1, CvType.CV_32FC2);

    src_mat.put(0, 0, tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x,
        bl.y);
    dst_mat.put(0, 0, 0.0, 0.0, dw, 0.0, dw, dh, 0.0, dh);

    Mat m = Imgproc.getPerspectiveTransform(src_mat, dst_mat);

    Imgproc.warpPerspective(src, doc, m, doc.size());

    return doc;
  }
}