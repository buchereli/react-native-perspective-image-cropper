import React, { Component } from 'react';
import {
  NativeModules,
  PanResponder,
  Dimensions,
  Image,
  View,
  Animated,
} from 'react-native';
import Svg, { Polygon } from 'react-native-svg';

const AnimatedPolygon = Animated.createAnimatedComponent(Polygon);

class CustomCrop extends Component {
  state = {};

  constructor(props) {
    super(props);

    const corners = [];
    for(let i = 0; i < 4; i++) {
      corners[i] = {position: new Animated.ValueXY(), delta: {x: 0, y: 0}};
    }

    this.state = {
      imageWidth: props.width,
      imageHeight: props.height,
      image: props.initialImage,
      corners,
    };
    this.state = {
      ...this.state,
      overlayPositions: this.getOverlayString(),
    };

    this.state.corners.forEach(corner => {
      corner.panResponder = this.createPanResponser(corner);
    })
  }

  createPanResponser(corner) {
    return PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onPanResponderMove: (e, gesture) => {
        const { position, delta }  = corner;
        corner.position.setValue({
          x: Math.min(Math.max(position.x._value + gesture.dx - delta.x, 0), this.state.imageLayoutWidth),
          y: Math.min(Math.max(position.y._value + gesture.dy - delta.y, 0), this.state.imageLayoutHeight),
        });
        corner.delta = { x: gesture.dx, y: gesture.dy };
        this.setState({ overlayPositions: this._getOverlayString() });
      },
      onPanResponderRelease: () => {
        corner.delta = { x: 0, y: 0 };
      },
      onPanResponderGrant: () => {
        corner.delta = { x: 0, y: 0 };
      },
    });
  }

  crop() {
    const { topLeft, topRight, bottomLeft, bottomRight } = this.getPositions();
    const coordinates = {
      topLeft: this.viewCoordinatesToImageCoordinates(topLeft),
      topRight: this.viewCoordinatesToImageCoordinates(topRight),
      bottomLeft: this.viewCoordinatesToImageCoordinates(bottomLeft),
      bottomRight: this.viewCoordinatesToImageCoordinates(bottomRight),
      height: this.state.height,
      width: this.state.width,
    };

    NativeModules.CustomCropManager.crop(
      coordinates,
      this.state.image,
      (err, res) => {
        this.props.updateImage(res.image, coordinates)
      },
    );
  }

  getPositions() {
    const { corners } = this.state;

    const topSorted = [...corners].sort((a, b) => a.position.y._value > b.position.y._value)
    const topLeft = topSorted[0].position.x._value < topSorted[1].position.x._value ? topSorted[0].position : topSorted[1].position;
    const topRight = topSorted[0].position.x._value > topSorted[1].position.x._value ? topSorted[0].position : topSorted[1].position;
    const bottomLeft = topSorted[2].position.x._value < topSorted[3].position.x._value ? topSorted[2].position : topSorted[3].position;
    const bottomRight = topSorted[2].position.x._value > topSorted[3].position.x._value ? topSorted[2].position : topSorted[3].position;

    return { topLeft, topRight, bottomLeft, bottomRight };
  }

  getOverlayString() {
    const { topLeft, topRight, bottomLeft, bottomRight } = this.getPositions();

    return `${topLeft.x._value},${topLeft.y._value} ${topRight.x._value},${topRight.y._value} ${
      bottomRight.x._value},${bottomRight.y._value} ${bottomLeft.x._value},${bottomLeft.y._value}`;
  }

  offset(position) {
    return { x: position.x._value + position.x._offset, y: position.y._value + position.y._offset };
  }

  viewCoordinatesToImageCoordinates(corner) {
    const {zoom} = this.state;
    return {
      x: corner.x._value * (1 / zoom),
      y: corner.y._value * (1 / zoom),
    };
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
    corners[0].position.setValue({x: padding, y: padding});
    corners[1].position.setValue({x: imageLayoutWidth - padding, y: padding});
    corners[2].position.setValue({x: padding, y: imageLayoutHeight - padding});
    corners[3].position.setValue({x: imageLayoutWidth - padding, y: imageLayoutHeight - padding});

    this.setState({
      viewWidth: layout.width,
      viewHeight: layout.height,
      imageLayoutWidth,
      imageLayoutHeight,
      offsetVerticle,
      offsetHorizontal,
      overlayPositions: this.getOverlayString(),
      zoom,
    });
  }

  render() {
    const { offsetVerticle, offsetHorizontal, corners, overlayPositions } = this.state;

    return (
      <View style={{ flex: 1, width: '100%' }} onLayout={this.onLayout}>
        <Image
          style={{ flex: 1, width: '100%' }}
          resizeMode="contain"
          source={{ uri: this.state.image }}
        />
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
    height: 100,
    width: 100,
    marginLeft: -50,
    marginTop: -50,
    alignItems: 'center',
    justifyContent: 'center',
    position: 'absolute',
    backgroundColor: 'transparent',
  },
});

export default CustomCrop;
