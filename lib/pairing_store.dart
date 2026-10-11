import 'package:shared_preferences/shared_preferences.dart';

/// The single saved PiDeck service (v1 keeps exactly one pairing).
///
/// [token] is reserved for future server-side auth and stays empty
/// while the server runs without token checks.
class Pairing {
  const Pairing({required this.baseUrl, this.token = ''});

  final String baseUrl;
  final String token;
}

class PairingStore {
  static const _kBaseUrl = 'pair_base_url';
  static const _kToken = 'pair_token';
  static const _kLastSession = 'last_session_id';
  static const _kDarkTheme = 'app_dark_theme';

  /// Default port when the scanned text carries no explicit port.
  static const defaultPort = 8765;

  /// Parse scanned QR text into a normalized `scheme://host:port` base URL.
  ///
  /// Accepts `http://host:port`, `https://host:port`, or a bare
  /// `host:port` / `host` (defaults to http + [defaultPort]).
  /// Returns null for empty text or non-http(s) schemes.
  static String? parseBaseUrl(String raw) {
    final text = raw.trim();
    if (text.isEmpty) return null;
    final withScheme = text.contains('://') ? text : 'http://$text';
    // scheme 大小写归一（`HTTP://` 也认）；path/query 含 token 时直接拒掉，
    // 不静默吞（扫码串 token 进 path 是配错，宁可让用户重扫）。
    final lower = withScheme.toLowerCase();
    final isHttp = lower.startsWith('http://');
    final isHttps = lower.startsWith('https://');
    if (!isHttp && !isHttps) return null;
    final scheme = isHttps ? 'https' : 'http';
    final rest = withScheme.substring(scheme.length + 3);
    if (rest.contains('/') || rest.contains('?') || rest.contains('#')) {
      return null;
    }
    final uri = Uri.tryParse('$scheme://$rest');
    if (uri == null || uri.host.isEmpty) return null;
    final port = uri.hasPort ? uri.port : defaultPort;
    // IPv6 回填方括号（Uri.host 已去括号，直接拼会成非法 URL）。
    final host = uri.host.contains(':') ? '[${uri.host}]' : uri.host;
    return '$scheme://$host:$port';
  }

  static Future<Pairing?> load() async {
    final prefs = await SharedPreferences.getInstance();
    final baseUrl = prefs.getString(_kBaseUrl);
    if (baseUrl == null || baseUrl.isEmpty) return null;
    return Pairing(baseUrl: baseUrl, token: prefs.getString(_kToken) ?? '');
  }

  static Future<void> save(Pairing pairing) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kBaseUrl, pairing.baseUrl);
    await prefs.setString(_kToken, pairing.token);
  }

  static Future<void> clear() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_kBaseUrl);
    await prefs.remove(_kToken);
    await prefs.remove(_kLastSession);
  }

  /// 上次打开的会话 id（抽屉壳冷启动直开聊天用；无则进抽屉选）。
  static Future<String?> loadLastSessionId() async {
    final prefs = await SharedPreferences.getInstance();
    final id = prefs.getString(_kLastSession);
    return (id == null || id.isEmpty) ? null : id;
  }

  static Future<void> clearLastSessionId() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_kLastSession);
  }

  static Future<void> saveLastSessionId(String id) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kLastSession, id);
  }

  /// 深色主题开关（默认浅色；One Dark 配色见 main.dart）。
  static Future<bool> loadDarkTheme() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getBool(_kDarkTheme) ?? false;
  }

  static Future<void> saveDarkTheme(bool dark) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_kDarkTheme, dark);
  }
}
