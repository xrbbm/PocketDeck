import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:webview_flutter/webview_flutter.dart';

import 'pairing_store.dart';

/// Loads the paired PiDeck service URL. All messaging, approval cards and
/// interrupt controls run inside the existing web page; this shell carries
/// zero business logic.
class ServiceView extends StatefulWidget {
  const ServiceView({super.key, required this.pairing, this.onRepair});

  final Pairing pairing;
  final VoidCallback? onRepair;

  @override
  State<ServiceView> createState() => _ServiceViewState();
}

class _ServiceViewState extends State<ServiceView> {
  late final WebViewController _controller;
  bool _failed = false;
  String _errorText = '';
  DateTime? _lastBack;

  @override
  void initState() {
    super.initState();
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageStarted: (_) {
            if (_failed) setState(() => _failed = false);
          },
          onWebResourceError: (error) {
            if (error.isForMainFrame == true && mounted) {
              setState(() {
                _failed = true;
                _errorText = error.description;
              });
            }
          },
        ),
      )
      ..loadRequest(Uri.parse(widget.pairing.baseUrl));
  }

  /// Back key: go back in web history if possible, otherwise require a
  /// second press within 2s to exit the app.
  Future<void> _onBack() async {
    if (await _controller.canGoBack()) {
      await _controller.goBack();
      return;
    }
    final now = DateTime.now();
    if (_lastBack == null ||
        now.difference(_lastBack!) > const Duration(seconds: 2)) {
      _lastBack = now;
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('再按一次退出'),
            duration: Duration(seconds: 2),
          ),
        );
      }
    } else if (mounted) {
      SystemNavigator.pop();
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _onBack();
      },
      child: Scaffold(
        appBar: AppBar(
          title: const Text('PiDeck'),
          actions: [
            IconButton(
              icon: const Icon(Icons.refresh),
              tooltip: '刷新',
              onPressed: () => _controller.reload(),
            ),
            IconButton(
              icon: const Icon(Icons.qr_code),
              tooltip: '重新配对',
              onPressed: widget.onRepair,
            ),
          ],
        ),
        body: _failed
            ? _ReconnectView(
                baseUrl: widget.pairing.baseUrl,
                errorText: _errorText,
                onRetry: () => _controller.reload(),
              )
            : LayoutBuilder(
                builder: (context, constraints) => RefreshIndicator(
                  onRefresh: () => _controller.reload(),
                  child: SingleChildScrollView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    child: SizedBox(
                      height: constraints.maxHeight,
                      child: WebViewWidget(controller: _controller),
                    ),
                  ),
                ),
              ),
      ),
    );
  }
}

/// Offline / timeout fallback: retry button instead of a white screen.
class _ReconnectView extends StatelessWidget {
  const _ReconnectView({
    required this.baseUrl,
    required this.errorText,
    required this.onRetry,
  });

  final String baseUrl;
  final String errorText;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.wifi_off, size: 64),
            const SizedBox(height: 16),
            const Text('无法连接服务', style: TextStyle(fontSize: 18)),
            const SizedBox(height: 8),
            Text(baseUrl,
                style: TextStyle(
                    color: Theme.of(context).colorScheme.onSurfaceVariant)),
            if (errorText.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text(errorText,
                  style: TextStyle(
                      color:
                          Theme.of(context).colorScheme.onSurfaceVariant)),
            ],
            const SizedBox(height: 8),
            const Text('电脑 IP 变化或不在同一 Wi-Fi 时，请用右上角按钮重新扫码配对'),
            const SizedBox(height: 16),
            ElevatedButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh),
              label: const Text('重试'),
            ),
          ],
        ),
      ),
    );
  }
}
