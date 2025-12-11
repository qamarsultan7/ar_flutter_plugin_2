import 'dart:math' show sqrt;
import 'dart:typed_data';

import 'package:ar_flutter_plugin_2/datatypes/config_planedetection.dart';
import 'package:ar_flutter_plugin_2/models/ar_hittest_result.dart';
import 'package:ar_flutter_plugin_2/utils/json_converters.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:vector_math/vector_math_64.dart';

typedef ARHitResultHandler = void Function(List<ARHitTestResult> hits);
typedef ErrorHandler = void Function(String error);
typedef PlaneDetectedHandler = void Function(int count);

/// Manages the session configuration, parameters and events of an ARView
class ARSessionManager {
  late MethodChannel _channel;

  final bool debug;
  final BuildContext buildContext;
  final PlaneDetectionConfig planeDetectionConfig;

  late ARHitResultHandler onPlaneOrPointTap;

  /// Plane detection callback
  PlaneDetectedHandler? onPlaneDetected;

  ErrorHandler? onError;

  ARSessionManager(int id, this.buildContext, this.planeDetectionConfig,
      {this.debug = false}) {
    _channel = MethodChannel('arsession_$id');
    _channel.setMethodCallHandler(_platformCallHandler);
  }

  /// Returns camera pose
  Future<Matrix4?> getCameraPose() async {
    try {
      final serialized =
          await _channel.invokeMethod<List<dynamic>>('getCameraPose', {});
      return MatrixConverter().fromJson(serialized!);
    } catch (e) {
      print("Error: $e");
      return null;
    }
  }

  /// Hit test screen plane
  Future<List<Matrix4>> hitTestScreenPosition(double x, double y) async {
    final List results = await _channel.invokeMethod("hitTest", {
      "x": x,
      "y": y,
    });

    if (results.isEmpty) {
      debugPrint("⚠️ No hit test results found");
      return [];
    }

    return results.map<Matrix4>((result) {
      final List<dynamic> matrixValues = result["worldTransform"];
      return Matrix4.fromList(matrixValues.cast<double>());
    }).toList();
  }

  // Disable camera
  void disableCamera() {
    _channel.invokeMethod<void>('disableCamera');
  }

  // Enable camera
  void enableCamera() {
    _channel.invokeMethod<void>('enableCamera');
  }

  // Show or hide planes
  void showPlanes(bool showPlanes) {
    _channel.invokeMethod<void>('showPlanes', {
      "showPlanes": showPlanes,
    });
  }

  Future<void> _platformCallHandler(MethodCall call) {
    try {
      switch (call.method) {
        case 'onError':
          if (onError != null) {
            onError!(call.arguments[0]);
          } else {
            ScaffoldMessenger.of(buildContext).showSnackBar(
              SnackBar(
                content: Text(call.arguments[0]),
                action: SnackBarAction(
                  label: 'HIDE',
                  onPressed: () => ScaffoldMessenger.of(buildContext)
                      .hideCurrentSnackBar(),
                ),
              ),
            );
          }
          break;

        case 'onPlaneOrPointTap':
          final raw = call.arguments as List<dynamic>;
          final converted = raw
              .map((e) => ARHitTestResult.fromJson(Map<String, dynamic>.from(e)))
              .toList();
          onPlaneOrPointTap(converted);
          break;

        case 'onPlaneDetected':
          if (onPlaneDetected != null) {
            onPlaneDetected!(call.arguments as int);
          }
          break;

        default:
          if (debug) print("Unhandled method ${call.method}");
      }
    } catch (e) {
      print("Platform handler error: $e");
    }

    return Future.value();
  }

  /// Initialize AR Session
  onInitialize({
    bool showAnimatedGuide = true,
    bool showFeaturePoints = false,
    bool showPlanes = true,
    String? customPlaneTexturePath,
    bool showWorldOrigin = false,
    bool handleTaps = true,
    bool handlePans = false,
    bool handleRotation = false,
  }) {
    _channel.invokeMethod("init", {
      'showAnimatedGuide': showAnimatedGuide,
      'showFeaturePoints': showFeaturePoints,
      'planeDetectionConfig': planeDetectionConfig.index,
      'showPlanes': showPlanes,
      'customPlaneTexturePath': customPlaneTexturePath,
      'showWorldOrigin': showWorldOrigin,
      'handleTaps': handleTaps,
      'handlePans': handlePans,
      'handleRotation': handleRotation,
    });
  }

  /// Dispose session
  dispose() async {
    try {
      await _channel.invokeMethod("dispose");
    } catch (e) {
      print("Dispose error: $e");
    }
  }

  /// Take snapshot
  Future<ImageProvider> snapshot() async {
    final bytes = await _channel.invokeMethod<Uint8List>('snapshot');
    return MemoryImage(bytes!);
  }
}
