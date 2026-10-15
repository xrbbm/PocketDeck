import 'package:flutter/material.dart';

import 'pages/shell_page.dart';
import 'pair_page.dart';
import 'pairing_store.dart';

void main() {
  runApp(const PiDeckApp());
}

/// Catppuccin Latte 浅色配色（https://catppuccin.com/palette）。
/// 槽位与深色主题一一对应：base/mantle/crust 管底，surface0 管气泡
/// （配 text #4C4F69，对比度 5.2，WCAG AA 可读），primary #0B57D0
/// 管主色（配 base 底 5.65；onPrimary 取纯白 6.9）。
ThemeData buildLightTheme() {
  const base = Color(0xFFEFF1F5);
  const mantle = Color(0xFFE6E9EF);
  const crust = Color(0xFFDCE0E8);
  const text = Color(0xFF4C4F69);
  const subtext = Color(0xFF5C5F77);
  const bubble = Color(0xFFCCD0DA);
  const blue = Color(0xFF0B57D0);
  const teal = Color(0xFF0E7377);
  const warn = Color(0xFFB54708);
  const red = Color(0xFFD20F39);
  return ThemeData(
    brightness: Brightness.light,
    colorScheme: const ColorScheme.light(
      primary: blue,
      onPrimary: Colors.white,
      secondary: teal,
      tertiary: warn,
      onTertiary: Colors.white,
      tertiaryContainer: mantle,
      onTertiaryContainer: text,
      primaryContainer: bubble,
      onPrimaryContainer: text,
      surface: base,
      onSurface: text,
      onSurfaceVariant: subtext,
      surfaceContainerLowest: crust,
      error: red,
    ),
    scaffoldBackgroundColor: base,
    cardColor: mantle,
    useMaterial3: true,
  );
}

/// One Dark Pro 深色配色（浅色主题保持默认不动）。
ThemeData buildDarkTheme() {
  const bg = Color(0xFF282C34);
  const bgDeep = Color(0xFF21252B);
  const fg = Color(0xFFABB2BF);
  const panel = Color(0xFF2C313C);
  const bubble = Color(0xFF3E4451);
  const onBubble = Colors.white;
  const blue = Color(0xFF61AFEF);
  const cyan = Color(0xFF56B6C2);
  const warn = Color(0xFFF5B841);
  const red = Color(0xFFE57A7D);
  return ThemeData(
    brightness: Brightness.dark,
    colorScheme: const ColorScheme.dark(
      primary: blue,
      onPrimary: bg,
      secondary: cyan,
      tertiary: warn,
      onTertiary: bg,
      tertiaryContainer: panel,
      onTertiaryContainer: fg,
      primaryContainer: bubble,
      onPrimaryContainer: onBubble,
      surface: bg,
      onSurface: fg,
      surfaceContainerLowest: bgDeep,
      error: red,
    ),
    scaffoldBackgroundColor: bg,
    cardColor: bgDeep,
    useMaterial3: true,
  );
}

class PiDeckApp extends StatefulWidget {
  const PiDeckApp({super.key});

  @override
  State<PiDeckApp> createState() => _PiDeckAppState();
}

class _PiDeckAppState extends State<PiDeckApp> {
  bool _dark = false;
  bool _themeLoaded = false;

  @override
  void initState() {
    super.initState();
    PairingStore.loadDarkTheme().then((v) {
      if (mounted) {
        setState(() {
          _dark = v;
          _themeLoaded = true;
        });
      }
    });
  }

  Future<void> _toggleTheme() async {
    final next = !_dark;
    setState(() => _dark = next);
    await PairingStore.saveDarkTheme(next);
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'PiDeck',
      theme: buildLightTheme(),
      darkTheme: buildDarkTheme(),
      themeMode: _dark ? ThemeMode.dark : ThemeMode.light,
      // 主题偏好读回前不渲染 Gate：深色用户冷启动不闪白。
      home: _themeLoaded
          ? Gate(isDark: _dark, onToggleTheme: _toggleTheme)
          : const Scaffold(body: Center(child: CircularProgressIndicator())),
      routes: {
        '/pair': (_) => const _RePair(),
      },
    );
  }
}

/// Startup gate: no pairing -> scan page; paired -> drawer shell
/// (cold start jumps straight into the last chat).
class Gate extends StatefulWidget {
  const Gate({super.key, required this.isDark, required this.onToggleTheme});

  final bool isDark;
  final VoidCallback onToggleTheme;

  @override
  State<Gate> createState() => _GateState();
}

class _GateState extends State<Gate> {
  Future<Pairing?>? _future;

  @override
  void initState() {
    super.initState();
    _future = PairingStore.load();
  }

  void _refresh(Pairing _) {
    setState(() => _future = PairingStore.load());
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Pairing?>(
      future: _future,
      builder: (context, snapshot) {
        // 存储损坏抛异常时给错误态，不静默回扫码页（用户会误以为没配对）。
        if (snapshot.hasError) {
          return Scaffold(
            body: Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text('配对信息读取失败'),
                    const SizedBox(height: 8),
                    Text('${snapshot.error}',
                        style: Theme.of(context).textTheme.bodySmall,
                        textAlign: TextAlign.center),
                    const SizedBox(height: 16),
                    FilledButton(
                      onPressed: () => setState(
                          () => _future = PairingStore.load()),
                      child: const Text('重试'),
                    ),
                  ],
                ),
              ),
            ),
          );
        }
        if (!snapshot.hasData) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Scaffold(
              body: Center(child: CircularProgressIndicator()),
            );
          }
          return PairPage(onPaired: _refresh);
        }
        final pairing = snapshot.data!;
        if (pairing.baseUrl.isEmpty) {
          return PairPage(onPaired: _refresh);
        }
        return ShellPage(
          key: ValueKey(pairing.baseUrl),
          pairing: pairing,
          isDark: widget.isDark,
          onToggleTheme: widget.onToggleTheme,
        );
      },
    );
  }
}

/// 断开配对后回到扫码页（抽屉壳用命名路由跳回此处）。
class _RePair extends StatefulWidget {
  const _RePair();

  @override
  State<_RePair> createState() => _RePairState();
}

class _RePairState extends State<_RePair> {
  @override
  Widget build(BuildContext context) {
    return PairPage(
      onPaired: (_) => Navigator.of(context).pushNamedAndRemoveUntil('/', (_) => false),
    );
  }
}
