package com.rectanglescanner.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;

import com.rectanglescanner.views.RectangleDetectionController;
import com.rectanglescanner.helpers.ImageProcessorMessage;
import com.rectanglescanner.helpers.Quadrilateral;
import com.rectanglescanner.helpers.CapturedImage;

import android.view.Surface;


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
import org.opencv.core.Scalar;

import org.opencv.photo.Photo;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.Semaphore;

import java.lang.System;

import com.facebook.react.bridge.Arguments;


import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
// import com.google.mlkit.vision.text.TextRecognizerOptions;

/**
  Created by Jake on Jan 6, 2020.

  Async processes either the image preview frame to detect rectangles, or
  the captured image to crop and apply filters.
 */
public class ImageProcessor extends Handler {

    private static final String TAG = "ImageProcessor";
    private final Semaphore available = new Semaphore(1);
    private final RectangleDetectionController mMainActivity;
    private Quadrilateral lastDetectedRectangle = null;
    private TextRecognizer recognizer;
    private boolean processingImage = false;

    public ImageProcessor(Looper looper, RectangleDetectionController mainActivity, Context context) {
        super(looper);
        this.mMainActivity = mainActivity;
        this.recognizer = TextRecognition.getClient();
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
    }

    /**
    Receives an event message to handle async
    */
    public void handleMessage(Message msg) {
        if (msg.obj.getClass() == ImageProcessorMessage.class) {

            ImageProcessorMessage obj = (ImageProcessorMessage) msg.obj;

            String command = obj.getCommand();

            Log.d(TAG, "Message Received: " + command + " - " + obj.getObj().toString());
            if (command.equals("previewFrame")) {
                processPreviewFrame((Mat) obj.getObj());
            } else if (command.equals("pictureTaken")) {
                processCapturedImage((Mat) obj.getObj());
            }
        }
    }

    /**
    Detect a rectangle in the current frame from the camera video
    */
    private void processPreviewFrame(Mat frame) {
    if (available.tryAcquire()){
      rotateImageForScreen(frame);
      detectRectangleInFrame(frame);
    }
      frame.release();
      mMainActivity.setImageProcessorBusy(false);
    }

    /**
    Process a single frame from the camera video
    */
    private void processCapturedImage(Mat picture) {
        Mat capturedImage = Imgcodecs.imdecode(picture, Imgcodecs.CV_LOAD_IMAGE_UNCHANGED);
        picture.release();

        Log.d(TAG, "processCapturedImage - imported image " + capturedImage.size().width + "x" + capturedImage.size().height);

        rotateImageForScreen(capturedImage);

        CapturedImage doc = cropImageToLatestQuadrilateral(capturedImage);

        mMainActivity.onProcessedCapturedImage(doc);
        doc.release();
        picture.release();

        mMainActivity.setImageProcessorBusy(false);
    }

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

  // private Bundle bundleTextBlock(Text result) {
  //       // [START mlkit_process_text_block]
  //       Bundle resultBundle = new Bundle();

  //       String resultText = result.getText();
  //       // put resultText
  //       resultBundle.putString("resultText", resultText);

  //       // TODO put this stuff later
  //       // for (Text.TextBlock block : result.getTextBlocks()) {
  //       //     String blockText = block.getText();
  //       //     android.graphics.Point[] blockCornerPoints = block.getCornerPoints();
  //       //     for (android.graphics.Point p : blockCornerPoints){
  //       //       points.add(new Point(p.x, p.y));
  //       //     }
  //       //     android.graphics.Rect blockFrame = block.getBoundingBox();
  //       //     // Log.d(TAG, "processTextBlock " + blockFrame);    
  //       // }

  //     return resultBundle;
  //       // [END mlkit_process_text_block]
  //   }

    /**
    Detects a rectangle from the image and sets the last detected rectangle
    */
    private OnSuccessListener<Text> onTextSuccess;
    private void detectRectangleInFrame(Mat inputRgba) {


      android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(inputRgba.cols(), inputRgba.rows(), android.graphics.Bitmap.Config.ARGB_8888);
      Utils.matToBitmap(inputRgba, bmp);
      InputImage image = InputImage.fromBitmap(bmp, 0);
      final Size srcSize = inputRgba.size();

        this.onTextSuccess = new OnSuccessListener<Text>() {
            @Override
            public void onSuccess(Text visionText) {
                Point[] pts = processTextBlock(visionText);
                Bundle data = new Bundle();
                if (pts[0] != null){
                    Quadrilateral quad = new Quadrilateral(sortPoints(pts), srcSize);
                    Log.d(TAG, "quad " + quad);
                    Bundle quadMap = quad.toBundle();
                    Bundle visionBundle = new Bundle();

                    String resultText = visionText.getText();
                    // put resultText
                    visionBundle.putString("resultText", resultText);

                    data.putBundle("detectedRectangle", quadMap);
                    data.putBundle("visionText", visionBundle);
                } else {
                    data.putBoolean("detectedRectangle", false);
                    data.putBoolean("visionText", false);
                }

                mMainActivity.rectangleWasDetected(Arguments.fromBundle(data));
                available.release();
      
                // Task completed successfully
                // ...minAreaRect
            }
        };


        Task<Text> result =
        recognizer.process(image)
                .addOnSuccessListener(this.onTextSuccess)
                .addOnFailureListener(
                        new OnFailureListener() {
                            @Override
                            public void onFailure( Exception e) {
                              available.release();
      
                                // Task failed with an exception
                                // ...
                            }
                        });
    }

    /**
    Crops the image to the latest detected rectangle and fixes perspective
    */
    private CapturedImage cropImageToLatestQuadrilateral(Mat capturedImage) {
        // applyFilters(capturedImage);

        Mat doc;
        if (this.lastDetectedRectangle != null) {
            Mat croppedCapturedImage = this.lastDetectedRectangle.cropImageToRectangleSize(capturedImage);
            doc = fourPointTransform(croppedCapturedImage, this.lastDetectedRectangle.getPointsForSize(croppedCapturedImage.size()));
            croppedCapturedImage.release();
        } else {
            doc = new Mat(capturedImage.size(), CvType.CV_8UC4);
            capturedImage.copyTo(doc);
        }

        Core.flip(doc.t(), doc, 0);
        Core.flip(capturedImage.t(), capturedImage, 0);
        CapturedImage sd = new CapturedImage(capturedImage);

        sd.originalSize = capturedImage.size();
        sd.heightWithRatio = Double.valueOf(sd.originalSize.width).intValue();
        sd.widthWithRatio = Double.valueOf(sd.originalSize.height).intValue();
        return sd.setProcessed(doc);
    }

    private Quadrilateral getQuadrilateral(ArrayList<MatOfPoint> contours, Size srcSize) {

        int height = Double.valueOf(srcSize.height).intValue();
        int width = Double.valueOf(srcSize.width).intValue();
        Size size = new Size(width, height);

        Log.i(TAG, "Size----->" + size);
        Log.i(TAG, "Size----->" + contours.size());
        for (MatOfPoint c : contours) {
            MatOfPoint2f c2f = new MatOfPoint2f(c.toArray());
//            MatOfPoint2f c2f2 = new MatOfPoint2f(c.toArray()*4);
            double peri = Imgproc.arcLength(c2f, true);
            MatOfPoint2f approx = new MatOfPoint2f();
            // 0.02 -> 0.1
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true);
            // Imgproc.approxPolyDP(c2f, approx, 0.1 * peri, true);
            // Imgproc.minAreaRect(c2f, approx);

            Point[] points = approx.toArray();

            // select biggest 4 angles polygon
            // if (points.length == 4) {
            Point[] foundPoints = sortPoints(points);
//            Log.i(TAG, "points----->" + foundPoints);
            if (insideArea(foundPoints, size)) {

                return new Quadrilateral(c, foundPoints, new Size(srcSize.width, srcSize.height));
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

        src_mat.put(0, 0, tl.x, tl.y, tr.x, tr.y, br.x, br.y,
                bl.x, bl.y);
        dst_mat.put(0, 0, 0.0, 0.0, dw, 0.0, dw, dh, 0.0, dh);

        Mat m = Imgproc.getPerspectiveTransform(src_mat, dst_mat);

        Imgproc.warpPerspective(src, doc, m, doc.size());

        return doc;
    }

    private Quadrilateral findContours2(Mat src) {

//      applyFilters(src);

        Mat grayImage;
        Mat binaryImage;
        Mat cannedImage;
        Mat resizedImage;

        int height = Double.valueOf(src.size().height).intValue();
        int width = Double.valueOf(src.size().width).intValue();
        Size size = new Size(width, height);

        resizedImage = new Mat(size, CvType.CV_8UC4);
        grayImage = new Mat(size, CvType.CV_8UC1);
        binaryImage = new Mat(size, CvType.CV_8UC1);
        cannedImage = new Mat(size, CvType.CV_8UC1);

        Imgproc.resize(src, resizedImage, size);
        Imgproc.cvtColor(resizedImage, grayImage, Imgproc.COLOR_RGBA2GRAY, 4);
        Imgproc.GaussianBlur(grayImage, grayImage, new Size(5, 5), 0);
        Imgproc.threshold(grayImage, binaryImage, 0, 255, Imgproc.THRESH_TOZERO + Imgproc.THRESH_OTSU);



        Mat rectComponents = Mat.zeros(new Size(0, 0), 0);
        Mat centComponents = Mat.zeros(new Size(0, 0), 0);
        Imgproc.connectedComponentsWithStats(binaryImage, cannedImage, rectComponents, centComponents);
      
        int[] bestRectangleInfo = new int[5];
        // double[] bestCentroidInfo = new double[2];
        int[] rectangleInfo = new int[5];
        double[] centroidInfo = new double[2];

        for(int i = 1; i < rectComponents.rows(); i++) {

            // Extract bounding box
            rectComponents.row(i).get(0, 0, rectangleInfo);

            // Extract centroids
            centComponents.row(i).get(0, 0, centroidInfo);

            if (rectangleInfo[4] > bestRectangleInfo[4]
              && rectangleInfo[4] > (binaryImage.size().height*binaryImage.size().width /8)){
            Log.i("saveToDirectory2", "better size----->"  +' '+rectangleInfo[4]+' '+rectangleInfo[2] +' '+rectangleInfo[3] +' '+(rectangleInfo[4]*10/(rectangleInfo[2] * rectangleInfo[3])));
            if ((rectangleInfo[4]*10/(rectangleInfo[2] * rectangleInfo[3])) >= 8
              && Math.abs((binaryImage.size().width/2)-centroidInfo[0]) < (binaryImage.size().width/4)
              && Math.abs((binaryImage.size().height/2)-centroidInfo[1]) < (binaryImage.size().height/4)
              ){
              bestRectangleInfo = Arrays.copyOf(rectangleInfo, 5);
            }
              }
        }

        Point[] foundPoints = { null, null, null, null };

        // top-left corner = minimal sum
        foundPoints[0] = new Point(bestRectangleInfo[0], bestRectangleInfo[1]);

        // bottom-right corner = maximal sum
        foundPoints[1] = new Point(bestRectangleInfo[0], bestRectangleInfo[1] + bestRectangleInfo[3]);

        // top-right corner = minimal difference
        foundPoints[2] = new Point(bestRectangleInfo[0] + bestRectangleInfo[2], bestRectangleInfo[1]);

        // bottom-left corner = maximal difference
        foundPoints[3] = new Point(bestRectangleInfo[0] + bestRectangleInfo[2], bestRectangleInfo[1] + bestRectangleInfo[3]);

        // return result;

        // if (insideArea(foundPoints, size)) {

        //         return new Quadrilateral(c, foundPoints, new Size(srcSize.width, srcSize.height));
        //     }

        // Free memory
        rectComponents.release();
        centComponents.release();
        
        cannedImage.release();
        resizedImage.release();
        grayImage.release();
        binaryImage.release();

        // return new Quadrilateral(foundPoints, src.size());
        return new Quadrilateral(sortPoints(foundPoints), src.size());

    }
    private Quadrilateral findContours3(Mat src) {

//      applyFilters(src);

        Mat grayImage;
        Mat binaryImage;
        Mat cannedImage;
        Mat resizedImage;

        int height = Double.valueOf(src.size().height).intValue();
        int width = Double.valueOf(src.size().width).intValue();
        Size size = new Size(width, height);

        resizedImage = new Mat(size, CvType.CV_8UC4);
        grayImage = new Mat(size, CvType.CV_8UC1);
        binaryImage = new Mat(size, CvType.CV_8UC1);
        cannedImage = new Mat(size, CvType.CV_8UC1);

        Imgproc.resize(src, resizedImage, size);

        Imgproc.medianBlur(resizedImage, resizedImage, 5);

        Scalar lower = new Scalar(155,155,155);
        Scalar higher = new Scalar(255,255,255);

        Core.inRange(resizedImage, lower, higher, binaryImage);


        Mat rectComponents = Mat.zeros(new Size(0, 0), 0);
        Mat centComponents = Mat.zeros(new Size(0, 0), 0);
        Imgproc.connectedComponentsWithStats(binaryImage, cannedImage, rectComponents, centComponents);
      
        int[] bestRectangleInfo = new int[5];
        // double[] bestCentroidInfo = new double[2];
        int[] rectangleInfo = new int[5];
        double[] centroidInfo = new double[2];

        for(int i = 1; i < rectComponents.rows(); i++) {

            // Extract bounding box
            rectComponents.row(i).get(0, 0, rectangleInfo);

            // Extract centroids
            centComponents.row(i).get(0, 0, centroidInfo);

            if (rectangleInfo[4] > bestRectangleInfo[4]
              && rectangleInfo[4] > (binaryImage.size().height*binaryImage.size().width /8)){
            Log.i("saveToDirectory2", "better size----->"  +' '+rectangleInfo[4]+' '+rectangleInfo[2] +' '+rectangleInfo[3] +' '+(rectangleInfo[4]*10/(rectangleInfo[2] * rectangleInfo[3])));
            if ((rectangleInfo[4]*10/(rectangleInfo[2] * rectangleInfo[3])) >= 8
              && Math.abs((binaryImage.size().width/2)-centroidInfo[0]) < (binaryImage.size().width/4)
              && Math.abs((binaryImage.size().height/2)-centroidInfo[1]) < (binaryImage.size().height/4)
              ){
              
              bestRectangleInfo = Arrays.copyOf(rectangleInfo, 5);
            }
              }
        }

        Point[] foundPoints = { null, null, null, null };

        // top-left corner = minimal sum
        foundPoints[0] = new Point(bestRectangleInfo[0], bestRectangleInfo[1]);

        // bottom-right corner = maximal sum
        foundPoints[1] = new Point(bestRectangleInfo[0], bestRectangleInfo[1] + bestRectangleInfo[3]);

        // top-right corner = minimal difference
        foundPoints[2] = new Point(bestRectangleInfo[0] + bestRectangleInfo[2], bestRectangleInfo[1]);

        // bottom-left corner = maximal difference
        foundPoints[3] = new Point(bestRectangleInfo[0] + bestRectangleInfo[2], bestRectangleInfo[1] + bestRectangleInfo[3]);

        // return result;

        // if (insideArea(foundPoints, size)) {

        //         return new Quadrilateral(c, foundPoints, new Size(srcSize.width, srcSize.height));
        //     }

        // Free memory
        rectComponents.release();
        centComponents.release();
        
        cannedImage.release();
        resizedImage.release();
        grayImage.release();
        binaryImage.release();

        // return new Quadrilateral(foundPoints, src.size());
        return new Quadrilateral(sortPoints(foundPoints), src.size());

    }
    private ArrayList<MatOfPoint> findContours(Mat src) {

//      applyFilters(src);

        Mat grayImage;
        Mat binaryImage;
        Mat cannedImage;
        Mat resizedImage;

        int height = Double.valueOf(src.size().height).intValue();
        int width = Double.valueOf(src.size().width).intValue();
        Size size = new Size(width, height);

        resizedImage = new Mat(size, CvType.CV_8UC4);
        grayImage = new Mat(size, CvType.CV_8UC1);
        binaryImage = new Mat(size, CvType.CV_8UC1);
        cannedImage = new Mat(size, CvType.CV_8UC1);

        Imgproc.resize(src, resizedImage, size);
        Imgproc.cvtColor(resizedImage, grayImage, Imgproc.COLOR_RGBA2GRAY, 4);
        Imgproc.GaussianBlur(grayImage, grayImage, new Size(5, 5), 0);
        Imgproc.threshold(grayImage, binaryImage, 0, 255, Imgproc.THRESH_TOZERO + Imgproc.THRESH_OTSU);
        Imgproc.Canny(binaryImage, cannedImage, 80, 100, 3, false);


        ArrayList<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();

        Imgproc.findContours(binaryImage, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);

        hierarchy.release();

        Collections.sort(contours, new Comparator<MatOfPoint>() {

            @Override
            public int compare(MatOfPoint lhs, MatOfPoint rhs) {
                return Double.compare(Imgproc.contourArea(rhs), Imgproc.contourArea(lhs));
            }
        });

        cannedImage.release();
        resizedImage.release();
        grayImage.release();
        binaryImage.release();

        return contours;
    }

    /*!
     Applies filters to the image based on the set filter
     */
    public void applyFilters(Mat image) {
          applyBlackAndWhiteFilterToImage(image);
          // Imgproc.threshold(image, image, 0, 255, 3);
      // int filterId = this.mMainActivity.getFilterId();
      // switch (filterId) {
      //   case 1: {
      //     // original image
      //     break;
      //   }
      //   case 2: {
      //     applyGreyscaleFilterToImage(image);
      //     break;
      //   }
      //   case 3: {
      //     applyColorFilterToImage(image);
      //     break;
      //   }
      //   case 4: {
      //     break;
      //   }
      //   default:
      //     // original image
      // }
    }

    /*!
     Slightly enhances the black and white image
     */
    public Mat applyGreyscaleFilterToImage(Mat image)
    {
      Imgproc.cvtColor(image, image, Imgproc.COLOR_RGBA2GRAY);
      return image;
    }

    /*!
     Slightly enhances the black and white image
     */
    public Mat applyBlackAndWhiteFilterToImage(Mat image)
    {
      Imgproc.cvtColor(image, image, Imgproc.COLOR_RGBA2GRAY);
      image.convertTo(image, -1, 1, 10);
      return image;
    }

    /*!
     Slightly enhances the color on the image
     */
    public Mat applyColorFilterToImage(Mat image)
    {
      image.convertTo(image, -1, 1.2, 0);
      return image;
    }


    public void rotateImageForScreen(Mat image) {
        // Core.copyMakeBorder(image, image, 10, 10, 10, 10, Core.BORDER_CONSTANT,  new Scalar(0,0,0));
      switch (this.mMainActivity.lastDetectedRotation) {
        case Surface.ROTATION_90: {
          // Do nothing
          break;
        }
        case Surface.ROTATION_180: {
          Core.flip(image.t(), image, 0);
          break;
        }
        case Surface.ROTATION_270: {
          Core.flip(image, image, 0);
          Core.flip(image, image, 1);
          break;
        }
        case Surface.ROTATION_0:
        default: {
          Core.flip(image.t(), image, 1);
          break;
        }
      }
    }


  private Bundle getCoordinates(boundingBox Rect) {
    Bundle resultBundle = new Bundle();
    if (boundingBox == null) {
      resultBundle.putNull("top");
      resultBundle.putNull("left");
      resultBundle.putNull("width");
      resultBundle.putNull("height");
    } else {
      resultBundle.putInt("top", boundingBox.top);
      resultBundle.putInt("left", boundingBox.left);
      resultBundle.putInt("width", boundingBox.width());
      resultBundle.putInt("height", boundingBox.height());
    }
    return resultBundle;
  }

  private Bundle getCornerPoints(pointsList Point[]) {
    Bundle resultBundle = new Bundle();
    if (pointsList == null) {
      return resultBundle;
    }

    for (Point point : pointsList) {
    Bundle i = new Bundle();
      i.putInt("x", point.x);
      i.putInt("y", point.y);
      resultBundle.pushBundle(i);
    }

    return resultBundle;
  }


  private Bundle getDataAsArray(visionText Text) {
    Bundle resultBundle = new Bundle();
    for (Text.TextBlock block : visionText.getTextBlocks()) {
      Bundle blockElements = new Bundle();
      for (Text.Line line : block.getLines()) {
        Bundle lineElements = new Bundle();
        for (Text.Element element : line.getElements()) {
          Bundle e = new Bundle();
          e.putString("text", element.getText());
          e.putBundle("bounding", getCoordinates(element.getBoundingBox()));
          e.putArray("cornerPoints", getCornerPoints(element.getCornerPoints()));
          lineElements.pushBundle(e);
        }
        Bundle l = new Bundle();
        Bundle lCoordinates = getCoordinates(line.getBoundingBox());
        l.putString("text", line.getText());
        l.putBundle("bounding", lCoordinates);
        l.putArray("elements", lineElements);
        l.putArray("cornerPoints", getCornerPoints(line.getCornerPoints()));

        blockElements.pushBundle(l);
      }

      Bundle info = new Bundle();


      info.putBundle("bounding", getCoordinates(block.getBoundingBox()));
      info.putString("text", block.getText());
      info.putArray("lines", blockElements);
      info.putArray("cornerPoints", getCornerPoints(block.getCornerPoints()));
      resultBundle.pushBundle(info);
    }
    return resultBundle;
  }
}
