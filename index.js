import React, { Component } from 'react';
import {
  NativeModules,
  PanResponder,
  Dimensions,
  Image,
  View,
  Animated,
  ActivityIndicator,
} from 'react-native';
import Svg, { Polygon } from 'react-native-svg';


const ConvexHull = require('hull.js');
const {
  matrix,
  transpose,
  multiply,
  min,
  max
} = require('mathjs');

export const findMinBoundingRect = points => {
  // Convex hull for the points
  const hullPoints = ConvexHull(points, Infinity);

  const edges = [];
  for (let i=0; i < hullPoints.length-1; i++) {
    edges.push([
      hullPoints[i+1][0] - hullPoints[i][0],
      hullPoints[i+1][1] - hullPoints[i][1]
    ]);
  }
  const angles = [...new Set(
    edges.map(edge =>
      Math.abs(
        Math.atan2(edge[1], edge[0]) % (Math.PI / 2)
      )
    )
  )];
  const rotations = angles.map(angle => {
    return [
      [ Math.cos(angle), Math.cos(angle - Math.PI/2) ],
      [ Math.cos(angle + Math.PI/2), Math.cos(angle) ]
    ]
  });


  // Apply rotations to the hull
  const rotPoints = rotations.map(rotation =>
    multiply(
      matrix(rotation),
      transpose(matrix(hullPoints))
    ).toArray()
  );

  const minXY = rotPoints.map(pMat => {
    const minValues = min(pMat, 1);
    return [minValues[0], minValues[1]];
  });
  const maxXY = rotPoints.map(pMat => {
    const minValues = max(pMat, 1);
    return [minValues[0], minValues[1]];
  });
  const minX = minXY.map(m => m[0]);
  const minY = minXY.map(m => m[1]);
  const maxX = maxXY.map(m => m[0]);
  const maxY = maxXY.map(m => m[1]);

  // Find the box with the best area
  const areas = minX.map((_, i) =>
    (maxX[i] - minX[i]) * (maxY[i] - minY[i])
  );
  const bestIdx = areas.reduce(
    (iMax, x, i, arr) => x < arr[iMax] ? i : iMax,
    0
  );

  // Return the best box
  const x1 = maxX[bestIdx];
  const x2 = minX[bestIdx];
  const y1 = maxY[bestIdx];
  const y2 = minY[bestIdx];
  const r = rotations[bestIdx];
  const minRect = [];
  minRect.push(multiply([x1, y2], r));
  minRect.push(multiply([x2, y2], r));
  minRect.push(multiply([x2, y1], r));
  minRect.push(multiply([x1, y1], r));
  minRect.push(r);

  return minRect;
}

function findCornersFromFoundDoc(res, corners, zoom) {
    if (res.topLeft) {
      corners[2].position.setValue({ x: res.topLeft.x * zoom, y: res.topLeft.y * zoom });
      corners[1].position.setValue({ x: res.topRight.x * zoom, y: res.topRight.y * zoom });
      corners[0].position.setValue({ x: res.bottomLeft.x * zoom, y: res.bottomLeft.y * zoom });
      corners[3].position.setValue({ x: res.bottomRight.x * zoom, y: res.bottomRight.y * zoom });
    } else {
      const points = [];
      res.forEach((box) => {
        points.push(...box.cornerPoints.map(({x,y}) => [x,y]))
      });
      if (!points.length){
        throw new Error('No points detected')
      }
      const boundingBox = findMinBoundingRect(points);
      corners[2].position.setValue({ x: boundingBox[0][0]*zoom, y: boundingBox[0][1]*zoom });
      corners[1].position.setValue({ x: boundingBox[1][0]*zoom, y: boundingBox[1][1]*zoom });
      corners[0].position.setValue({ x: boundingBox[2][0]*zoom, y: boundingBox[2][1]*zoom });
      corners[3].position.setValue({ x: boundingBox[3][0]*zoom, y: boundingBox[3][1]*zoom });
    }
}

function getCornersHelper(corners) {
  const topSorted = [...corners].sort((a, b) => a.position.y._value - b.position.y._value)
  const topLeft = topSorted[0].position.x._value < topSorted[1].position.x._value ? topSorted[0] : topSorted[1];
  const topRight = topSorted[0].position.x._value >= topSorted[1].position.x._value ? topSorted[0] : topSorted[1];
  const bottomLeft = topSorted[2].position.x._value < topSorted[3].position.x._value ? topSorted[2] : topSorted[3];
  const bottomRight = topSorted[2].position.x._value >= topSorted[3].position.x._value ? topSorted[2] : topSorted[3];

  return { topLeft, topRight, bottomLeft, bottomRight };
  }

export function findAndCropImage(coordinates, imageURI, callback) {
  NativeModules.CustomCropManager.findDocument(
    imageURI,
    (err, res) => {
      if (res) {
      const corners = [];
      for (let i = 0; i < 4; i++) {
        corners[i] = { position: new Animated.ValueXY(), delta: { x: 0, y: 0 } }
      }
      try {
        findCornersFromFoundDoc(res, corners, 1);
      } catch (e) {
        callback(e, null);
      }
      const {bottomLeft, bottomRight, topLeft, topRight} = getCornersHelper(corners);
      const cornerCoords = {
        bottomLeft:viewCoordinatesToImageCoordinatesHelper(bottomLeft, 1), 
        bottomRight:viewCoordinatesToImageCoordinatesHelper(bottomRight, 1), 
        topLeft:viewCoordinatesToImageCoordinatesHelper(topLeft, 1), 
        topRight:viewCoordinatesToImageCoordinatesHelper(topRight, 1)
      };
      NativeModules.CustomCropManager.crop(
        cornerCoords.bottomLeft.x?cornerCoords:coordinates,
        imageURI,
        callback,
      );
    } else {
      callback(err, null);
    }
  });
}

function viewCoordinatesToImageCoordinatesHelper(corner, zoom) {
  return {
    x: corner.position.x._value * (1 / zoom),
    y: corner.position.y._value * (1 / zoom),
  }
}


const AnimatedPolygon = Animated.createAnimatedComponent(Polygon);

const TOP = 0;
const RIGHT = 1;
const BOTTOM = 2;
const LEFT = 3;

class CustomCrop extends Component {
  state = {};

  constructor(props) {
    super(props);

    const corners = [];
    for (let i = 0; i < 4; i++) {
      corners[i] = { position: new Animated.ValueXY(), delta: { x: 0, y: 0 } };
      corners[i].panResponder = this.cornerPanResponser(corners[i]);
    }

    const midPoints = [];
    for (let i = 0; i < 4; i++) {
      midPoints[i] = { position: new Animated.ValueXY(), delta: { x: 0, y: 0 } };
      midPoints[i].panResponder = this.midPointPanResponser(midPoints[i], i);
    }

    this.state = {
      imageWidth: props.width,
      imageHeight: props.height,
      image: props.initialImage,
      corners,
      midPoints,
      loading: true,
    };
  }

  cornerPanResponser(corner) {
    return PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onPanResponderMove: (e, gesture) => {
        this.moveCorner(corner, gesture.dx, gesture.dy);
        this.setState({ overlayPositions: this.getOverlayString() });
      },
      onPanResponderRelease: () => {
        corner.delta = { x: 0, y: 0 };
      },
      onPanResponderGrant: () => {
        corner.delta = { x: 0, y: 0 };
      },
    });
  }

  moveCorner(corner, dx, dy) {
    const { delta, position } = corner;
    position.setValue({
      x: Math.min(Math.max(position.x._value + dx - delta.x, 0), this.state.imageLayoutWidth),
      y: Math.min(Math.max(position.y._value + dy - delta.y, 0), this.state.imageLayoutHeight),
    });
    corner.delta = { x: dx, y: dy };
    this.updateMidPoints();
  }

  midPointPanResponser(midPoint, side) {
    return PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onPanResponderMove: (e, gesture) => {
        const { topLeft, topRight, bottomLeft, bottomRight } = this.getCorners();
        switch (side) {
          case TOP:
            this.moveCorner(topLeft, 0, gesture.dy);
            this.moveCorner(topRight, 0, gesture.dy);
            break;
          case RIGHT:
            this.moveCorner(bottomRight, gesture.dx, 0);
            this.moveCorner(topRight, gesture.dx, 0);
            break;
          case BOTTOM:
            this.moveCorner(bottomLeft, 0, gesture.dy);
            this.moveCorner(bottomRight, 0, gesture.dy);
            break;
          case LEFT:
            this.moveCorner(bottomLeft, gesture.dx, 0);
            this.moveCorner(topLeft, gesture.dx, 0);
            break;
        }
        this.setState({ overlayPositions: this.getOverlayString() });
      },
      onPanResponderRelease: () => {
        this.state.corners.forEach(corner => {
          corner.delta = { x: 0, y: 0 };
        })
      },
      onPanResponderGrant: () => { },
    });
  }

  crop() {
    if (!this.state.loading) {
      const { topLeft, topRight, bottomLeft, bottomRight } = this.getCorners();
      const coordinates = {
        topLeft: this.viewCoordinatesToImageCoordinates(topLeft),
        topRight: this.viewCoordinatesToImageCoordinates(topRight),
        bottomLeft: this.viewCoordinatesToImageCoordinates(bottomLeft),
        bottomRight: this.viewCoordinatesToImageCoordinates(bottomRight),
        height: this.state.imageHeight,
        width: this.state.imageWidth,
      };
      NativeModules.CustomCropManager.crop(
        coordinates,
        this.state.image,
        (err, res) => {
          this.props.updateImage(res.image, coordinates)
        },
      );
    }
  }

  findDocument() {
    NativeModules.CustomCropManager.findDocument(
      this.state.image,
      (err, res) => {
        let { corners, zoom } = this.state;
        if (res) {
          try{
            findCornersFromFoundDoc(res, corners, zoom)
          } catch (_) {
            // Noop
          }
          this.updateMidPoints();
        }
        this.setState({ loading: false, overlayPositions: this.getOverlayString() });
      },
    );
  }
  
  getCorners() {
    const { corners } = this.state;
    return getCornersHelper(corners);
  }

  setMidPoint(point, start, end) {
    point.position.setValue({
      x: (start.position.x._value + end.position.x._value) / 2,
      y: (start.position.y._value + end.position.y._value) / 2,
    })
  }

  updateMidPoints() {
    const { topLeft, topRight, bottomLeft, bottomRight } = this.getCorners();

    this.setMidPoint(this.state.midPoints[TOP], topLeft, topRight);
    this.setMidPoint(this.state.midPoints[RIGHT], bottomRight, topRight);
    this.setMidPoint(this.state.midPoints[BOTTOM], bottomRight, bottomLeft);
    this.setMidPoint(this.state.midPoints[LEFT], topLeft, bottomLeft);
  }

  getOverlayString() {
    const { topLeft, topRight, bottomLeft, bottomRight } = this.getCorners();

    return `${topLeft.position.x._value},${topLeft.position.y._value} ${topRight.position.x._value},${topRight.position.y._value} ${bottomRight.position.x._value},${bottomRight.position.y._value} ${bottomLeft.position.x._value},${bottomLeft.position.y._value}`;
  }

  offset(position) {
    return { x: position.x._value + position.x._offset, y: position.y._value + position.y._offset };
  }

  viewCoordinatesToImageCoordinates(corner) {
    let { zoom } = this.state;

    return viewCoordinatesToImageCoordinatesHelper(corner, zoom);
  }

  onLayout = (event) => {
    const layout = event.nativeEvent.layout;

    if (
      layout.width === this.state.viewWidth &&
      layout.height === this.state.viewHeight
    ) {
      return;
    }

    const { imageHeight, imageWidth } = this.state;
    const widthZoom = layout.width / imageWidth;
    const heightZoom = layout.height / imageHeight;
    const zoom = widthZoom < heightZoom ? widthZoom : heightZoom;
    const offsetHorizontal = Math.max(Math.round((layout.width - imageWidth * zoom) / 2), 0);
    const offsetVerticle = Math.max(Math.round((layout.height - imageHeight * zoom) / 2), 0);

    const imageLayoutWidth = layout.width - offsetHorizontal * 2;
    const imageLayoutHeight = layout.height - offsetVerticle * 2;

    const padding = 50;
    const { corners } = this.state;
    corners[0].position.setValue({ x: padding, y: padding });
    corners[1].position.setValue({ x: imageLayoutWidth - padding, y: padding });
    corners[2].position.setValue({ x: padding, y: imageLayoutHeight - padding });
    corners[3].position.setValue({ x: imageLayoutWidth - padding, y: imageLayoutHeight - padding });

    this.updateMidPoints();
    
    this.props.findDocumentOnLoad && this.findDocument();

    this.setState({
      viewWidth: layout.width,
      viewHeight: layout.height,
      imageLayoutWidth,
      imageLayoutHeight,
      offsetVerticle,
      offsetHorizontal,
      zoom,
    });
  }

  render() {
    const {
      offsetVerticle,
      offsetHorizontal,
      corners,
      midPoints,
      overlayPositions,
      loading,
    } = this.state;

    return (
      <View style={{ flex: 1, width: '100%' }} onLayout={this.onLayout}>
        <Image
          style={{ flex: 1, width: '100%' }}
          resizeMode="contain"
          source={{ uri: this.state.image }}
        />
        {loading && (
          <View style={{ position: "absolute", justifyContent: "center", alignItems: "center", width: "100%", height: "100%" }}>
            <ActivityIndicator color={this.props.overlayColor} size="large" />
          </View>
        )}
        {!loading && (
          <View style={{
            position: 'absolute',
            top: offsetVerticle,
            bottom: offsetVerticle,
            left: offsetHorizontal,
            right: offsetHorizontal,
          }}>
            <Svg
              height={this.state.viewHeight}
              width={Dimensions.get('window').width}
              style={{ position: 'absolute', left: 0, top: 0 }}
            >
              <AnimatedPolygon
                ref={(ref) => (this.polygon = ref)}
                fill={this.props.overlayColor || 'blue'}
                fillOpacity={this.props.overlayOpacity || 0.5}
                stroke={this.props.overlayStrokeColor || 'blue'}
                points={overlayPositions}
                strokeWidth={this.props.overlayStrokeWidth || 3}
              />
            </Svg>

            {
              midPoints.map(point => (
                <Animated.View
                  {...point.panResponder.panHandlers}
                  style={[
                    point.position.getLayout(),
                    s(this.props).handler,
                  ]}
                >
                  <View
                    style={[
                      s(this.props).handlerRound,
                    ]}
                  />
                </Animated.View>
              ))
            }

            {
              corners.map(corner => (
                <Animated.View
                  {...corner.panResponder.panHandlers}
                  style={[
                    corner.position.getLayout(),
                    s(this.props).handler,
                  ]}
                >
                  <View
                    style={[
                      s(this.props).handlerRound,
                    ]}
                  />
                </Animated.View>
              ))
            }
          </View>
        )}
      </View>
    );
  }
}

const s = (props) => ({
  handlerRound: {
    width: 20,
    position: 'absolute',
    height: 20,
    borderRadius: 10,
    backgroundColor: props.handlerColor || 'blue',
  },
  handler: {
    height: 60,
    width: 60,
    marginLeft: -30,
    marginTop: -30,
    alignItems: 'center',
    justifyContent: 'center',
    position: 'absolute',
    backgroundColor: 'transparent',
    borderRadius: 50,
  },
});

export default CustomCrop;
