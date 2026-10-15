import 'package:flutter/material.dart';

import '../api/chat.dart';
import '../api/models.dart';

/// 审批问答卡片（对齐 WebAskCard：单题 select/confirm/input + 批量信封 + 取消）。
///
/// 应答 envelope 照抄 handleRespondUi：{value} / {confirmed} / {cancelled}。
class AskCard extends StatefulWidget {
  const AskCard({
    super.key,
    required this.request,
    required this.busy,
    required this.onRespond,
  });

  final WebPendingUiRequest request;
  final bool busy;
  final ValueChanged<Map<String, dynamic>> onRespond;

  @override
  State<AskCard> createState() => _AskCardState();
}

class _AskCardState extends State<AskCard> {
  // 单题输入框 + 单题 select 选中项（选中后点提交才应答）。
  late final TextEditingController _draft;
  String? _selected;
  // 单题 select 的手输口（allowOther=true 时显示，与批量行为一致）。
  final _singleCustom = TextEditingController();
  bool _singleCustomActive = false;
  // 批量：当前题下标 + 每题答案 + 自定义输入框。
  int _tab = 0;
  final _answers = <String, dynamic>{};
  final _custom = TextEditingController();
  // 最后一次动的是自定义输入（true）还是选项（false）。
  bool _customActive = false;
  // prefill 只初始化一次（request 切换时 AskCard 整卡重建）。
  bool _prefilled = false;

  @override
  void initState() {
    super.initState();
    final pre = widget.request.prefill ?? '';
    _draft = TextEditingController(text: pre);
    if (pre.isNotEmpty) _singleCustomActive = true;
  }

  @override
  void didUpdateWidget(AskCard old) {
    super.didUpdateWidget(old);
    if (old.request.requestId != widget.request.requestId) {
      _selected = null;
      _tab = 0;
      _answers.clear();
      _custom.clear();
      _customActive = false;
      _singleCustom.clear();
      _singleCustomActive = false;
      _prefilled = false;
    }
    final pre = widget.request.prefill ?? '';
    if (!_prefilled && pre.isNotEmpty && _draft.text.isEmpty) {
      _draft.text = pre;
      _prefilled = true;
    }
    final tab = widget.request.batchQuestions.isEmpty
        ? null
        : widget.request.batchQuestions[_tab.clamp(
            0, widget.request.batchQuestions.length - 1)];
    final tabPre = tab?.prefill ?? '';
    if (tab != null && tabPre.isNotEmpty && _custom.text.isEmpty) {
      _custom.text = tabPre;
      _customActive = true;
    }
  }

  @override
  void dispose() {
    _draft.dispose();
    _singleCustom.dispose();
    _custom.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.fromLTRB(12, 8, 12, 4),
      color: Theme.of(context).colorScheme.tertiaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: widget.request.isBatch ? _buildBatch() : _buildSingle(),
      ),
    );
  }

  // ── 单题 ──────────────────────────────────────────────

  Widget _buildSingle() {
    final req = widget.request;
    // ✎ 开头的选项是桌面端手势项，过滤（与网页端一致）。
    final options =
        (req.options ?? []).where((o) => !o.startsWith('✎')).toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('需要你确认', style: TextStyle(fontWeight: FontWeight.bold)),
        const SizedBox(height: 6),
        SelectableText(req.title.isEmpty ? '请作答' : req.title),
        const SizedBox(height: 10),
        if (req.method == 'select' && options.isNotEmpty) ...[
          for (final option in options)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: _OptionButton(
                label: splitAskOption(option).label,
                description: splitAskOption(option).description,
                selected: _selected == option,
                busy: widget.busy,
                onTap: () => setState(() {
                  _selected = option;
                  _singleCustomActive = false;
                }),
              ),
            ),
          // allowOther=true 时给手输口（与批量 select 一致）。
          if (req.allowOther != false)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: TextField(
                controller: _singleCustom,
                minLines: 1,
                maxLines: 3,
                enabled: !widget.busy,
                onTap: () => setState(() => _singleCustomActive = true),
                onChanged: (_) =>
                    setState(() => _singleCustomActive = true),
                decoration: InputDecoration(
                  hintText: req.placeholder ?? '自定义回答',
                  border: const OutlineInputBorder(),
                  isDense: true,
                ),
              ),
            ),
          Row(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              TextButton(
                onPressed: widget.busy
                    ? null
                    : () => widget.onRespond({'cancelled': true}),
                child: const Text('取消'),
              ),
              const SizedBox(width: 8),
              FilledButton(
                onPressed: _singleSubmit(req),
                child: const Text('提交'),
              ),
            ],
          ),
        ] else if (req.method == 'confirm')
          Row(
            children: [
              FilledButton(
                onPressed: widget.busy
                    ? null
                    : () => widget.onRespond({'confirmed': true}),
                child: const Text('是'),
              ),
              const SizedBox(width: 8),
              OutlinedButton(
                onPressed: widget.busy
                    ? null
                    : () => widget.onRespond({'confirmed': false}),
                child: const Text('否'),
              ),
            ],
          )
        else
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _draft,
                  minLines: 1,
                  maxLines: 4,
                  enabled: !widget.busy,
                  onChanged: (_) => setState(() {}),
                  decoration: InputDecoration(
                    hintText: req.placeholder ?? '输入回答',
                    border: const OutlineInputBorder(),
                    isDense: true,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              FilledButton(
                onPressed: widget.busy || _draft.text.trim().isEmpty
                    ? null
                    : () {
                        widget.onRespond({'value': _draft.text.trim()});
                      },
                child: const Text('提交'),
              ),
            ],
          ),
        // select 自带取消+提交行，尾部不再重复加取消。
        if (req.method != 'select' || options.isEmpty)
          Align(
            alignment: Alignment.centerRight,
            child: TextButton(
              onPressed: widget.busy
                  ? null
                  : () => widget.onRespond({'cancelled': true}),
              child: const Text('取消'),
            ),
          ),
      ],
    );
  }

  // ── 批量 ──────────────────────────────────────────────

  Widget _buildBatch() {
    final questions = widget.request.batchQuestions;
    final current = questions[_tab.clamp(0, questions.length - 1)];
    final isLast = _tab >= questions.length - 1;
    final currentAns = _answers[current.id];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Expanded(
              child: Text('需要你确认', style: TextStyle(fontWeight: FontWeight.bold)),
            ),
            Text('${_tab + 1}/${questions.length}',
                style: Theme.of(context).textTheme.bodySmall),
          ],
        ),
        const SizedBox(height: 6),
        SelectableText(
          current.question,
          style: const TextStyle(fontWeight: FontWeight.w500),
        ),
        const SizedBox(height: 10),
        if (current.type == 'select' && current.options.isNotEmpty) ...[
          for (final opt in current.options)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: _OptionButton(
                label: opt.label,
                description: opt.description,
                selected: currentAns == (opt.value ?? opt.label),
                busy: widget.busy,
                // 只选中不推进；点选项后提交只发选项（手输的不发）。
                onTap: () => setState(() {
                  _answers[current.id] = opt.value ?? opt.label;
                  _customActive = false;
                }),
              ),
            ),
          // 提交收拢到右下与取消同行，见卡片底部动作行。
          // select 自定义输入恒定显示（ask_question 统一走批量信封，缺了它就只能选预设）。
          if (current.allowOther != false)
            TextField(
              controller: _custom,
              minLines: 1,
              maxLines: 3,
              enabled: !widget.busy,
              onTap: () => setState(() => _customActive = true),
              onChanged: (_) => setState(() => _customActive = true),
              decoration: InputDecoration(
                hintText: current.placeholder ?? '自定义回答',
                border: const OutlineInputBorder(),
                isDense: true,
              ),
            ),
        ] else if (current.type == 'multi_select' &&
            current.options.isNotEmpty) ...[
          for (final opt in current.options)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: _OptionButton(
                label: opt.label,
                description: opt.description,
                selected: currentAns is List &&
                    currentAns.contains(opt.value ?? opt.label),
                busy: widget.busy,
                onTap: () => _toggleMulti(current, opt.value ?? opt.label),
              ),
            ),
          // 提交收拢到右下与取消同行，见卡片底部动作行。
        ] else if (current.type == 'confirm')
          Row(
            children: [
              FilledButton(
                onPressed: widget.busy
                    ? null
                    : () => _answerOne(current, true),
                child: const Text('是'),
              ),
              const SizedBox(width: 8),
              OutlinedButton(
                onPressed: widget.busy
                    ? null
                    : () => _answerOne(current, false),
                child: const Text('否'),
              ),
            ],
          )
        else
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _custom,
                  minLines: 1,
                  maxLines: 4,
                  enabled: !widget.busy,
                  onChanged: (_) => setState(() {}),
                  decoration: InputDecoration(
                    hintText: current.placeholder ?? '输入回答',
                    border: const OutlineInputBorder(),
                    isDense: true,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              FilledButton(
                onPressed: widget.busy || _custom.text.trim().isEmpty
                    ? null
                    : () => _answerOne(current, _custom.text.trim()),
                child: Text(isLast ? '提交' : '下一题'),
              ),
            ],
          ),
        Row(
          mainAxisAlignment: MainAxisAlignment.end,
          children: [
            TextButton(
              onPressed: widget.busy
                  ? null
                  : () => widget.onRespond({'cancelled': true}),
              child: const Text('取消'),
            ),
            // select / multi 的提交收拢到右下，与取消同行。
            if (current.type == 'select' && current.options.isNotEmpty) ...[
              const SizedBox(width: 8),
              FilledButton(
                onPressed: _selectSubmit(current, currentAns),
                child: Text(isLast ? '提交' : '下一题'),
              ),
            ] else if (current.type == 'multi_select' &&
                current.options.isNotEmpty) ...[
              const SizedBox(width: 8),
              FilledButton(
                onPressed: widget.busy ||
                        currentAns is! List ||
                        currentAns.isEmpty
                    ? null
                    : () => _answerOne(current, currentAns),
                child: Text(isLast ? '提交' : '下一题'),
              ),
            ],
          ],
        ),
      ],
    );
  }

  /// 单题 select 提交：最后动了哪里就提交哪里（点选项只发选项，
  /// 动了手输框发手输；都没有则禁用）。
  VoidCallback? _singleSubmit(WebPendingUiRequest req) {
    if (widget.busy) return null;
    final text = _singleCustom.text.trim();
    // 手输优先（非空才算动过），否则取选中项；全空禁用。
    if (_singleCustomActive && text.isNotEmpty) {
      return () => widget.onRespond({'value': text});
    }
    final sel = _selected;
    if (sel == null || sel.trim().isEmpty) return null;
    return () => widget.onRespond({'value': sel});
  }

  /// 批量单选提交：最后动了哪里就提交哪里——点了选项只发选项，
  /// 动了自定义输入框（有点选记录且有内容）发手输；都没有返回 null。
  VoidCallback? _selectSubmit(WebBatchQuestion q, dynamic currentAns) {
    if (widget.busy) return null;
    final text = _custom.text.trim();
    final val =
        (_customActive && text.isNotEmpty) ? text : currentAns;
    if (val == null) return null;
    return () => _answerOne(q, val);
  }

  void _toggleMulti(WebBatchQuestion q, String val) {
    final arr = [...?_answers[q.id]];
    if (arr.contains(val)) {
      arr.remove(val);
    } else {
      arr.add(val);
    }
    setState(() => _answers[q.id] = arr);
  }

  void _answerOne(WebBatchQuestion q, dynamic val) {
    _answers[q.id] = val;
    if (_tab >= widget.request.batchQuestions.length - 1) {
      widget.onRespond({
        'value': serializeBatchAnswers(
            widget.request.batchQuestions, _answers),
      });
    } else {
      setState(() {
        _tab++;
        _custom.clear();
        _customActive = false;
      });
    }
  }
}

class _OptionButton extends StatelessWidget {
  const _OptionButton({
    required this.label,
    this.description,
    this.selected = false,
    required this.busy,
    required this.onTap,
  });

  final String label;
  final String? description;
  final bool selected;
  final bool busy;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final content = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(label),
        if (description != null)
          Text(
            description!,
            style: Theme.of(context).textTheme.bodySmall,
          ),
      ],
    );
    return SizedBox(
      width: double.infinity,
      child: selected
          ? FilledButton.tonal(
              onPressed: busy ? null : onTap,
              style: const ButtonStyle(alignment: Alignment.centerLeft),
              child: content,
            )
          : OutlinedButton(
              onPressed: busy ? null : onTap,
              style: const ButtonStyle(alignment: Alignment.centerLeft),
              child: content,
            ),
    );
  }
}
