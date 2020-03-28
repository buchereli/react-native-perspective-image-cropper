package fr.michaelvilleneuve.customcrop;

import fr.michaelvilleneuve.customcrop.ImageProcessor;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.provider.MediaStore;
import android.util.Base64;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.WritableMap;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.imgcodecs.*;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import org.opencv.calib3d.Calib3d;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import android.util.Log;

public class RNCustomCropModule extends ReactContextBaseJavaModule {

  private final ReactApplicationContext reactContext;
  private final String TAG = ":(";

  public RNCustomCropModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.reactContext = reactContext;
  }

  @Override
  public String getName() {
    return "CustomCropManager";
  }

  @ReactMethod
  public void crop(ReadableMap points, String imageUri, Callback callback) {
    BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this.reactContext) {
      @Override
      public void onManagerConnected(int status) {
        if (status == LoaderCallbackInterface.SUCCESS) {
          Log.d(TAG, "SUCCESS init OpenCV: " + status);
        } else {
          Log.d(TAG, "ERROR init OpenCV: " + status);
        }
      }
    };

    if (!OpenCVLoader.initDebug()) {
      OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_1_0, this.reactContext, mLoaderCallback);
    } else {
      mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
    }

    Point tl = new Point(points.getMap("topLeft").getDouble("x"), points.getMap("topLeft").getDouble("y"));
    Point tr = new Point(points.getMap("topRight").getDouble("x"), points.getMap("topRight").getDouble("y"));
    Point bl = new Point(points.getMap("bottomLeft").getDouble("x"), points.getMap("bottomLeft").getDouble("y"));
    Point br = new Point(points.getMap("bottomRight").getDouble("x"), points.getMap("bottomRight").getDouble("y"));

    Mat src = Imgcodecs.imread(imageUri.replace("file://", ""));
    Imgproc.cvtColor(src, src, Imgproc.COLOR_BGR2RGB);

    System.out.println("EASYEXPENSE - Source: " + src.size().width + " - " + src.size().height);

    double bottom = Math.sqrt(Math.pow(br.x - bl.x, 2) + Math.pow(br.y - bl.y, 2));
    double top = Math.sqrt(Math.pow(tr.x - tl.x, 2) + Math.pow(tr.y - tl.y, 2));

    System.out.println("EASYEXPENSE - Width: " + bottom + " - " + top);
    double maxWidth = Math.max(bottom, top);

    double right = Math.sqrt(Math.pow(tr.x - br.x, 2) + Math.pow(tr.y - br.y, 2));
    double left = Math.sqrt(Math.pow(tl.x - bl.x, 2) + Math.pow(tl.y - bl.y, 2));

    System.out.println("EASYEXPENSE - Hieght: " + left + " - " + right);
    double maxHeight = Math.max(right, left);

    Mat doc = new Mat((int) maxHeight, (int) maxWidth, CvType.CV_8UC4);

    MatOfPoint2f startMat = new MatOfPoint2f(tl, tr, bl, br);
    MatOfPoint2f endMat = new MatOfPoint2f(new Point(0, 0), new Point((int) maxWidth, 0), new Point(0, (int) maxHeight),
        new Point((int) maxWidth, (int) maxHeight));

    Mat warpMat = Imgproc.getPerspectiveTransform(startMat, endMat);
    Imgproc.warpPerspective(src, doc, warpMat, doc.size());

    Bitmap bitmap = Bitmap.createBitmap(doc.cols(), doc.rows(), Bitmap.Config.ARGB_8888);
    Utils.matToBitmap(doc, bitmap);

    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream);
    byte[] byteArray = byteArrayOutputStream.toByteArray();

    WritableMap map = Arguments.createMap();
    map.putString("image", Base64.encodeToString(byteArray, Base64.DEFAULT));
    callback.invoke(null, map);

    warpMat.release();
  }

  @ReactMethod
  public void findDocument(String imageUri, Callback callback) {
    BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this.reactContext) {
      @Override
      public void onManagerConnected(int status) {
        if (status == LoaderCallbackInterface.SUCCESS) {
          Log.d(TAG, "SUCCESS init OpenCV: " + status);
        } else {
          Log.d(TAG, "ERROR init OpenCV: " + status);
        }
      }
    };

    if (!OpenCVLoader.initDebug()) {
      OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_1_0, this.reactContext, mLoaderCallback);
    } else {
      mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
    }

    System.out.println("l33t uri - " + imageUri);
    if (!imageUri.isEmpty()) {
      Mat src = Imgcodecs.imread(imageUri.replace("file://", ""));
      Imgproc.cvtColor(src, src, Imgproc.COLOR_BGR2RGB);

      System.out.println("l33t - Source: " + src.size().width + " - " + src.size().height);

      ImageProcessor ip = new ImageProcessor();

      // WritableMap map = Arguments.createMap();
      // map.putString("crop", ip.processPicture(src));
      callback.invoke(null, ip.processPicture(src));
    }
  }

}