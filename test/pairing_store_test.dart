import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'dart:math' as math;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:pideck/main.dart';
import 'package:pideck/pairing_store.dart';

void main() {
  test('深色开关默认浅色，存取往返', () async {
    SharedPreferences.setMockInitialValues({});
    expect(await PairingStore.loadDarkTheme(), isFalse);
    await PairingStore.saveDarkTheme(true);
    expect(await PairingStore.loadDarkTheme(), isTrue);
    await PairingStore.saveDarkTheme(false);
    expect(await PairingStore.loadDarkTheme(), isFalse);
  });

  test('深色主题为 One Dark 配色', () {
    final dark = buildDarkTheme();
    expect(dark.brightness, Brightness.dark);
    expect(dark.scaffoldBackgroundColor, const Color(0xFF282C34));
    expect(dark.colorScheme.primary, const Color(0xFF61AFEF));
    expect(dark.colorScheme.onSurface, const Color(0xFFABB2BF));
    expect(dark.colorScheme.primaryContainer, const Color(0xFF3E4451));
    expect(dark.colorScheme.tertiaryContainer, const Color(0xFF2C313C));
    expect(dark.colorScheme.error, const Color(0xFFE57A7D));
  });

  test('浅色主题为 Catppuccin Latte 配色', () {
    final light = buildLightTheme();
    expect(light.brightness, Brightness.light);
    expect(light.scaffoldBackgroundColor, const Color(0xFFEFF1F5));
    expect(light.colorScheme.primary, const Color(0xFF0B57D0));
    expect(light.colorScheme.onPrimary, Colors.white);
    expect(light.colorScheme.secondary, const Color(0xFF0E7377));
    expect(light.colorScheme.onSurface, const Color(0xFF4C4F69));
    expect(light.colorScheme.primaryContainer, const Color(0xFFCCD0DA));
    expect(light.colorScheme.tertiaryContainer, const Color(0xFFE6E9EF));
  });

  double luminance(Color c) {
    double ch(double v) {
      return v <= 0.03928 ? v / 12.92 : math.pow((v + 0.055) / 1.055, 2.4).toDouble();
    }
    return 0.2126 * ch(c.r) + 0.7152 * ch(c.g) + 0.0722 * ch(c.b);
  }

  double ratio(Color a, Color b) {
    final la = luminance(a);
    final lb = luminance(b);
    return (la > lb ? (la + 0.05) / (lb + 0.05) : (lb + 0.05) / (la + 0.05));
  }

  test('深色卡片底字对比度达标（WCAG AA 4.5）', () {
    final cs = buildDarkTheme().colorScheme;
    // 审批卡底 + 卡上字。
    expect(ratio(cs.tertiaryContainer, cs.onTertiaryContainer),
        greaterThanOrEqualTo(4.5));
    // 用户气泡底 + 气泡字。
    expect(ratio(cs.primaryContainer, cs.onPrimaryContainer),
        greaterThanOrEqualTo(4.5));
  });

  test('功能色对比度达标（WCAG AA 4.5）', () {
    final light = buildLightTheme().colorScheme;
    // 浅色主色当正文链路色（配浅底）。
    expect(ratio(light.primary, light.surface),
        greaterThanOrEqualTo(4.5));
    // 浅色按钮白字（配主色底）。
    expect(ratio(light.onPrimary, light.primary),
        greaterThanOrEqualTo(4.5));
    // 浅色 secondary（新增徽标色，配浅底）。
    expect(ratio(light.secondary, light.surface),
        greaterThanOrEqualTo(4.5));
    // 浅色 tertiary（用量环警告态，配浅底）。
    expect(ratio(light.tertiary, light.surface),
        greaterThanOrEqualTo(4.5));
    // 浅色按钮白字（配警告底）。
    expect(ratio(light.onTertiary, light.tertiary),
        greaterThanOrEqualTo(4.5));
    final dark = buildDarkTheme().colorScheme;
    // 深色报错字（配深底）。
    expect(ratio(dark.error, dark.surface),
        greaterThanOrEqualTo(4.5));
    // 深色 tertiary（用量环警告态，配深底）。
    expect(ratio(dark.tertiary, dark.surface),
        greaterThanOrEqualTo(4.5));
  });
}
