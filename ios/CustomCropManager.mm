#import "CustomCropManager.h"
#import <React/RCTLog.h>

#import <GoogleMLKit/MLKit.h>

@implementation CustomCropManager
{
  CGFloat _imageDedectionConfidence;
  NSTimer *_borderDetectTimeKeeper;
  BOOL _borderDetectFrame;
  CIRectangleFeature *_borderDetectLastRectangleFeature;
  CGRect _borderDetectLastRectangleBounds;
}

static NSString *const detectionNoResultsMessage = @"Something went wrong";


RCT_EXPORT_MODULE();

RCT_EXPORT_METHOD(crop:(NSDictionary *)points imageUri:(NSString *)imageUri callback:(RCTResponseSenderBlock)callback)
{
    NSString *parsedImageUri = [[imageUri stringByReplacingOccurrencesOfString:@"file://" withString:@""] stringByRemovingPercentEncoding];
    NSURL *fileURL = [NSURL fileURLWithPath:parsedImageUri];
    CIImage *ciImage = [CIImage imageWithContentsOfURL:fileURL];
    ciImage = [ciImage imageByApplyingOrientation:kCGImagePropertyOrientationDownMirrored];
    
    CGPoint newLeft = CGPointMake([points[@"topLeft"][@"x"] floatValue], [points[@"topLeft"][@"y"] floatValue]);
    CGPoint newRight = CGPointMake([points[@"topRight"][@"x"] floatValue], [points[@"topRight"][@"y"] floatValue]);
    CGPoint newBottomLeft = CGPointMake([points[@"bottomLeft"][@"x"] floatValue], [points[@"bottomLeft"][@"y"] floatValue]);
    CGPoint newBottomRight = CGPointMake([points[@"bottomRight"][@"x"] floatValue], [points[@"bottomRight"][@"y"] floatValue]);
    
    NSMutableDictionary *rectangleCoordinates = [[NSMutableDictionary alloc] init];
    
    rectangleCoordinates[@"inputTopLeft"] = [CIVector vectorWithCGPoint:newLeft];
    rectangleCoordinates[@"inputTopRight"] = [CIVector vectorWithCGPoint:newRight];
    rectangleCoordinates[@"inputBottomLeft"] = [CIVector vectorWithCGPoint:newBottomLeft];
    rectangleCoordinates[@"inputBottomRight"] = [CIVector vectorWithCGPoint:newBottomRight];
    
    ciImage = [ciImage imageByApplyingFilter:@"CIPerspectiveCorrection" withInputParameters:rectangleCoordinates];
    
    CIContext *context = [CIContext contextWithOptions:nil];
    CGImageRef cgimage = [context createCGImage:ciImage fromRect:[ciImage extent]];
    UIImage *image = [UIImage imageWithCGImage:cgimage];
    NSData *imageToEncode = UIImageJPEGRepresentation(image, 0.8);
    callback(@[[NSNull null], @{@"image": [imageToEncode base64EncodedStringWithOptions:NSDataBase64Encoding64CharacterLineLength]}]);
}

- (CGPoint)cartesianForPoint:(CGPoint)point {
    return CGPointMake(point.x, point.y);
}


RCT_EXPORT_METHOD(findDocument:(NSString *)imagePath callback:(RCTResponseSenderBlock)callback)
{
    NSData *imageData = [NSData dataWithContentsOfFile:imagePath];
    UIImage *image = [UIImage imageWithData:imageData];

    if (!image) {
        NSLog(@"No image found %@", imagePath);
        return;
    }
    
    MLKTextRecognizer *textRecognizer = [MLKTextRecognizer textRecognizer];
    MLKVisionImage *handler = [[MLKVisionImage alloc] initWithImage:image];
    
    [textRecognizer processImage:handler completion:^(MLKText *_Nullable result, NSError *_Nullable error) {
        @try {
            if (error != nil || result == nil) {
                NSString *errorString = error ? error.localizedDescription : detectionNoResultsMessage;
                @throw [NSException exceptionWithName:@"failure" reason:errorString userInfo:nil];
                return;
            }
        
            NSMutableArray *output = prepareOutput2(result);
            callback(@[[NSNull null], output]);
        }
        @catch (NSException *e) {
            NSString *errorString = e ? e.reason : detectionNoResultsMessage;
            NSLog(errorString);
            callback(@[@{@"error": @"No rectangle found"}, [NSNull null]]);
        }
    }];
}


// MARK: Rectangle Detection

/*!
 Gets a rectangle detector that can be used to plug an image into and find the rectangles from
 */
- (CIDetector *)highAccuracyRectangleDetector
{
    static CIDetector *detector = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^
    {
        detector = [CIDetector detectorOfType:CIDetectorTypeRectangle context:nil options:@{CIDetectorAccuracy : CIDetectorAccuracyHigh, CIDetectorReturnSubFeatures: @(YES) }];
    });
    return detector;
}

/*!
 Finds the best fitting rectangle from the list of rectangles found in the image
 */
- (CIRectangleFeature *)biggestRectangleInRectangles:(NSArray *)rectangles image:(CIImage *)image
{
  if (![rectangles count]) return nil;

  float halfPerimiterValue = 0;

  CIRectangleFeature *biggestRectangle = [rectangles firstObject];

  for (CIRectangleFeature *rect in rectangles) {
    CGPoint p1 = rect.topLeft;
    CGPoint p2 = rect.topRight;
    CGFloat width = hypotf(p1.x - p2.x, p1.y - p2.y);

    CGPoint p3 = rect.topLeft;
    CGPoint p4 = rect.bottomLeft;
    CGFloat height = hypotf(p3.x - p4.x, p3.y - p4.y);

    CGFloat currentHalfPerimiterValue = height + width;

    if (halfPerimiterValue < currentHalfPerimiterValue) {
      halfPerimiterValue = currentHalfPerimiterValue;
      biggestRectangle = rect;
    }
  }

  return biggestRectangle;
}

/*!
 Maps the coordinates to the correct orientation.  This maybe can be cleaned up and removed if the orientation is set on the input image.
 */
- (NSDictionary *) computeRectangle: (CIRectangleFeature *) rectangle forImage: (CIImage *) image {
  CGRect imageBounds = image.extent;
  if (!rectangle) return nil;
  return @{
    @"bottomLeft": @{
        @"y": @(imageBounds.size.height-rectangle.topRight.y),
        @"x": @(rectangle.topRight.x)
    },
    @"bottomRight": @{
        @"y": @(imageBounds.size.height-rectangle.topLeft.y),
        @"x": @(rectangle.topLeft.x)
    },
    @"topLeft": @{
        @"y": @(imageBounds.size.height-rectangle.bottomRight.y),
        @"x": @(rectangle.bottomRight.x)
    },
    @"topRight": @{
        @"y": @(imageBounds.size.height-rectangle.bottomLeft.y),
        @"x": @(rectangle.bottomLeft.x)
    },
    @"dimensions": @{@"height": @(imageBounds.size.height), @"width": @(imageBounds.size.width)}
  };
}

/*!
 Checks if the confidence of the current rectangle is above a threshold. The higher, the more likely the rectangle is the desired object to be scanned.
 */
BOOL isRectangleDetectionConfidenceHighEnough(float confidence)
{
    return (confidence > 1.0);
}

NSMutableArray* getCornerPoints2(NSArray *cornerPoints) {
    NSMutableArray *result = [NSMutableArray array];
    
    if (cornerPoints == nil) {
        return result;
    }
    for (NSValue  *point in cornerPoints) {
        NSMutableDictionary *resultPoint = [NSMutableDictionary dictionary];
        [resultPoint setObject:[NSNumber numberWithFloat:point.CGPointValue.x] forKey:@"x"];
        [resultPoint setObject:[NSNumber numberWithFloat:point.CGPointValue.y] forKey:@"y"];
        [result addObject:resultPoint];
    }
    return result;
}


NSDictionary* getBounding2(CGRect frame) {
    return @{
       @"top": @(frame.origin.y),
       @"left": @(frame.origin.x),
       @"width": @(frame.size.width),
       @"height": @(frame.size.height)
   };
}


NSMutableArray* prepareOutput2(MLKText *result) {
    NSMutableArray *output = [NSMutableArray array];
    for (MLKTextBlock *block in result.blocks) {
        
        NSMutableArray *blockElements = [NSMutableArray array];
        for (MLKTextLine *line in block.lines) {
            NSMutableArray *lineElements = [NSMutableArray array];
            for (MLKTextElement *element in line.elements) {
                NSMutableDictionary *e = [NSMutableDictionary dictionary];
                e[@"text"] = element.text;
                e[@"cornerPoints"] = getCornerPoints2(element.cornerPoints);
                e[@"bounding"] = getBounding2(element.frame);
                [lineElements addObject:e];
            }
            
            NSMutableDictionary *l = [NSMutableDictionary dictionary];
            l[@"text"] = line.text;
            l[@"cornerPoints"] = getCornerPoints2(line.cornerPoints);
            l[@"elements"] = lineElements;
            l[@"bounding"] = getBounding2(line.frame);
            [blockElements addObject:l];
        }
        
        NSMutableDictionary *b = [NSMutableDictionary dictionary];
        b[@"text"] = block.text;
        b[@"cornerPoints"] = getCornerPoints2(block.cornerPoints);
        b[@"bounding"] = getBounding2(block.frame);
        b[@"lines"] = blockElements;
        [output addObject:b];
    }
    return output;
}


@end
