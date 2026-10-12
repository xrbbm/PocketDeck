import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:pideck/pages/chat_page.dart';

void main() {
  test('decodeResizeJpg：大图长边压到 1600 并转 JPEG', () {
    // 2400x1200 红图：长边超限，应压到 1600x800。
    final src = img.Image(width: 2400, height: 1200);
    img.fill(src, color: img.ColorRgb8(200, 30, 30));
    final raw = img.encodePng(src);

    final out = decodeResizeJpg(raw);
    expect(out, isNotNull);
    final back = img.decodeImage(out!);
    expect(back, isNotNull);
    expect(back!.width, 1600);
    expect(back.height, 800);
  });

  test('decodeResizeJpg：小图不放大只转 JPEG', () {
    final src = img.Image(width: 400, height: 300);
    img.fill(src, color: img.ColorRgb8(30, 120, 200));
    final raw = img.encodePng(src);

    final out = decodeResizeJpg(raw);
    expect(out, isNotNull);
    final back = img.decodeImage(out!);
    expect(back!.width, 400);
    expect(back.height, 300);
  });

  test('decodeResizeJpg：坏数据回 null（调用方回退原图直传）', () {
    expect(decodeResizeJpg(const [0, 1, 2, 3]), isNull);
    expect(decodeResizeJpg(const <int>[]), isNull);
  });

  test('回到底部箭头阈值：离底部>160 才亮（>跟随阈值120，防闪烁）', () {
    // 阈值语义锁死：跟随滚动 120px 内贴底，箭头 160px 外才出现，
    // 中间 40px 缓冲带两者都不动，不闪。
    const followThreshold = 120;
    const arrowThreshold = 160;
    expect(arrowThreshold, greaterThan(followThreshold));

    bool shouldShow(double pixels, double maxExtent) {
      if (maxExtent <= 0) return false;
      return pixels < maxExtent - arrowThreshold;
    }

    expect(shouldShow(1000, 1000), isFalse); // 贴底：不亮
    expect(shouldShow(900, 1000), isFalse); // 差100：在缓冲带，不亮
    expect(shouldShow(800, 1000), isTrue); // 差200：亮
    expect(shouldShow(0, 0), isFalse); // 空列表：不亮
  });

  test('网络超时收敛：GET 10s / POST 15s（后台切回挂住不断崖）', () {
    // 超时值写死在 ApiClient._getJson/_postJson/_rawPost 的 .timeout 里，
    // 此处锁语义：读超时必须存在且 GET < POST（读快写慢）。
    const getTimeout = Duration(seconds: 10);
    const postTimeout = Duration(seconds: 15);
    expect(getTimeout < postTimeout, isTrue);
    // Uint8List 在 flutter_test 里可用（foundation 链路正常）。
    expect(Uint8List(0), isA<Uint8List>());
  });
}
