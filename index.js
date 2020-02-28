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

    this.state = {
      imageWidth: props.width,
      imageHeight: props.height,
      image: props.initialImage,
      topLeft: new Animated.ValueXY(),
      topRight: new Animated.ValueXY(),
      bottomLeft: new Animated.ValueXY(),
      bottomRight: new Animated.ValueXY(),
    };
    this.state = {
      ...this.state,
      overlayPositions: this.getOverlayString(),
    };

    this.panResponderTopLeft = this.createPanResponser(this.state.topLeft);
    this.panResponderTopRight = this.createPanResponser(this.state.topRight);
    this.panResponderBottomLeft = this.createPanResponser(this.state.bottomLeft);
    this.panResponderBottomRight = this.createPanResponser(this.state.bottomRight);
  }

  createPanResponser(corner) {
    return PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onPanResponderMove: (evt, gestureState) => {
        Animated.event([
          null,
          {
            dx: corner.x,
            dy: corner.y,
          },
        ])(evt, gestureState);
        this.setState({ overlayPositions: this.getOverlayString() });
      },
      onPanResponderRelease: () => {
        corner.flattenOffset();
        this.setState({ overlayPositions: this.getOverlayString() });
      },
      onPanResponderGrant: () => {
        corner.setOffset({ x: corner.x._value, y: corner.y._value });
        corner.setValue({ x: 0, y: 0 });
      },
    });
  }

  crop() {
    const { topLeft, topRight, bottomLeft, bottomRight } = this.state;
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

  getOverlayString() {
    const topLeft = this.offset(this.state.topLeft);
    const topRight = this.offset(this.state.topRight);
    const bottomLeft = this.offset(this.state.bottomLeft);
    const bottomRight = this.offset(this.state.bottomRight);

    return `${topLeft.x},${topLeft.y} ${topRight.x},${topRight.y} ${
      bottomRight.x},${bottomRight.y} ${bottomLeft.x},${bottomLeft.y}`;
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
    const { topLeft, topRight, bottomLeft, bottomRight } = this.state;
    topLeft.setValue({x: padding, y: padding});
    topRight.setValue({x: imageLayoutWidth - padding, y: padding});
    bottomLeft.setValue({x: padding, y: imageLayoutHeight - padding});
    bottomRight.setValue({x: imageLayoutWidth - padding, y: imageLayoutHeight - padding});

    this.setState({
      viewWidth: layout.width,
      viewHeight: layout.height,
      offsetVerticle,
      offsetHorizontal,
      overlayPositions: this.getOverlayString(),
      zoom,
    });
  }

  render() {
    const { offsetVerticle, offsetHorizontal } = this.state;

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
              points={this.state.overlayPositions}
              strokeWidth={this.props.overlayStrokeWidth || 3}
            />
          </Svg>
          <Animated.View
            {...this.panResponderTopLeft.panHandlers}
            style={[
              this.state.topLeft.getLayout(),
              s(this.props).handler,
            ]}
          >
            <View
              style={[
                s(this.props).handlerI,
                { left: -10, top: -10 },
              ]}
            />
            <View
              style={[
                s(this.props).handlerRound,
                { left: 31, top: 31 },
              ]}
            />
          </Animated.View>
          <Animated.View
            {...this.panResponderTopRight.panHandlers}
            style={[
              this.state.topRight.getLayout(),
              s(this.props).handler,
            ]}
          >
            <View
              style={[
                s(this.props).handlerI,
                { left: 10, top: -10 },
              ]}
            />
            <View
              style={[
                s(this.props).handlerRound,
                { right: 31, top: 31 },
              ]}
            />
          </Animated.View>
          <Animated.View
            {...this.panResponderBottomLeft.panHandlers}
            style={[
              this.state.bottomLeft.getLayout(),
              s(this.props).handler,
            ]}
          >
            <View
              style={[
                s(this.props).handlerI,
                { left: -10, top: 10 },
              ]}
            />
            <View
              style={[
                s(this.props).handlerRound,
                { left: 31, bottom: 31 },
              ]}
            />
          </Animated.View>
          <Animated.View
            {...this.panResponderBottomRight.panHandlers}
            style={[
              this.state.bottomRight.getLayout(),
              // {top: 50, right: 50},
              s(this.props).handler,
            ]}
          >
            <View
              style={[
                s(this.props).handlerI,
                { left: 10, top: 10 },
              ]}
            />
            <View
              style={[
                s(this.props).handlerRound,
                { right: 31, bottom: 31 },
              ]}
            />
          </Animated.View>
        </View>
      </View>
    );
  }
}

const s = (props) => ({
  handlerI: {
    borderRadius: 0,
    height: 20,
    width: 20,
    backgroundColor: props.handlerColor || 'blue',
  },
  handlerRound: {
    width: 39,
    position: 'absolute',
    height: 39,
    borderRadius: 100,
    backgroundColor: props.handlerColor || 'blue',
  },
  handler: {
    height: 140,
    width: 140,
    overflow: 'visible',
    marginLeft: -70,
    marginTop: -70,
    alignItems: 'center',
    justifyContent: 'center',
    position: 'absolute',
  },
});

export default CustomCrop;
