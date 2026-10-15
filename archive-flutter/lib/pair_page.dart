import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import 'pairing_store.dart';

/// Scan the QR code shown on the PiDeck settings page (its content is just
/// the service address, e.g. `http://192.168.50.210:8765`).
///
/// A manual input field is kept as a fallback for emulators / desktops
/// without a camera.
class PairPage extends StatefulWidget {
  const PairPage({super.key, required this.onPaired});

  final ValueChanged<Pairing> onPaired;

  @override
  State<PairPage> createState() => _PairPageState();
}

class _PairPageState extends State<PairPage> {
  late final MobileScannerController _controller;
  final TextEditingController _manual = TextEditingController();
  bool _done = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _controller = MobileScannerController(
      formats: const [BarcodeFormat.qrCode],
      detectionSpeed: DetectionSpeed.normal,
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    _manual.dispose();
    super.dispose();
  }

  Future<void> _accept(String raw) async {
    if (_done) return;
    final baseUrl = PairingStore.parseBaseUrl(raw);
    if (baseUrl == null) {
      setState(() => _error = '二维码内容不是有效的服务地址：$raw');
      return;
    }
    _done = true;
    await _controller.stop();
    final pairing = Pairing(baseUrl: baseUrl);
    await PairingStore.save(pairing);
    if (mounted) widget.onPaired(pairing);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('配对 PiDeck')),
      body: Column(
        children: [
          Expanded(
            flex: 3,
            child: Stack(
              children: [
                MobileScanner(
                  controller: _controller,
                  onDetect: (capture) {
                    final raw = capture.barcodes.firstOrNull?.rawValue;
                    if (raw != null) _accept(raw);
                  },
                ),
                if (_error != null)
                  Positioned(
                    left: 16,
                    right: 16,
                    bottom: 16,
                    child: Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: Theme.of(context).colorScheme.error,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(
                        _error!,
                        style: TextStyle(
                            color: Theme.of(context).colorScheme.onError),
                      ),
                    ),
                  ),
              ],
            ),
          ),
          Expanded(
            flex: 2,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text('将取景框对准 PiDeck 设置页的二维码，或手动输入地址'),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: _manual,
                          keyboardType: TextInputType.url,
                          decoration: const InputDecoration(
                            hintText: 'http://192.168.50.210:8765',
                            border: OutlineInputBorder(),
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      ElevatedButton(
                        onPressed: () => _accept(_manual.text),
                        child: const Text('连接'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
