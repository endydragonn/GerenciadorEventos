// ==============================================================================
// PAINEL DE RESERVA (issue #44)
// ==============================================================================
// Substitui os botões da tela de inscrição. Reserva e confirmação são dois passos:
// - POST /bff/event-wallets -> 201 (pending, com expiresAt) ou 202 (modo fila, ainda sem reserva)
// - 202: consulta GET /bff/event-wallets/admission até pending / rejected / timed_out
// - pending: contagem regressiva até expiresAt + botão "Confirmar" (POST /bff/event-wallets/confirm)
// A lógica pura (parse do expiresAt, mm:ss, classificação de status) fica em helpers/reserva_logica.dart.
// ==============================================================================

import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

import '../config/app_config.dart';
import '../helpers/reserva_logica.dart';
import '../telas/auth.dart';
import '../telas/modals.dart';

enum _Fase { ocioso, reservando, aguardando, pendente, confirmando, expirada, recusada, naoChegou }

class PainelReserva extends StatefulWidget {
  final Object? userId;
  final Object? eventId;
  final bool temVagas;

  /// Chamado quando o `/confirm` responde 200 (a tela mostra o sucesso e volta para a principal).
  final VoidCallback onConfirmada;

  const PainelReserva({
    super.key,
    required this.userId,
    required this.eventId,
    required this.temVagas,
    required this.onConfirmada,
  });

  @override
  State<PainelReserva> createState() => _PainelReservaState();
}

class _PainelReservaState extends State<PainelReserva> {
  _Fase _fase = _Fase.ocioso;
  Timer? _timerConsulta;
  Timer? _timerContagem;

  /// Sobe a cada nova reserva/cancelamento: respostas de uma rodada antiga (fechada ou refeita) são descartadas.
  int _geracao = 0;
  int _tentativas = 0;
  Duration _intervalo = const Duration(seconds: kIntervaloPadraoSeg);

  /// Chave de idempotência do 202. Só é reenviada em "Tentar de novo" (timed_out); depois de um pending
  /// expirado ou de um rejected vai um POST NOVO, sem a chave (a antiga repetiria o resultado guardado).
  String? _chave;
  DateTime? _expiraUtc;
  Duration? _restante;
  String? _motivo;
  bool _instavel = false;

  /// Há um POST (reservar ou confirmar) em voo: um 2º clique antes do rebuild não dispara outro.
  bool _ocupado = false;

  @override
  void dispose() {
    _geracao++;
    _cancelarTimers();
    super.dispose();
  }

  void _cancelarTimers() {
    _timerConsulta?.cancel();
    _timerContagem?.cancel();
    _timerConsulta = null;
    _timerContagem = null;
  }

  Uri _uri(String caminho) => Uri.parse('${AppConfig.apiBaseUrl}/bff/event-wallets$caminho');

  Map<String, dynamic> _json(http.Response r) {
    try {
      final d = jsonDecode(utf8.decode(r.bodyBytes));
      return d is Map<String, dynamic> ? d : {};
    } catch (_) {
      return {};
    }
  }

  String _texto(http.Response r, String padrao) {
    try {
      final t = utf8.decode(r.bodyBytes).trim();
      return t.isEmpty ? padrao : t;
    } catch (_) {
      return padrao;
    }
  }

  // ---------------------------------------------------------------- reservar

  Future<void> _reservar({String? chave}) async {
    if (widget.userId == null) {
      AppModals.showError(context, 'Autenticação Necessária', 'Você precisa estar logado para se inscrever em um evento');
      return;
    }
    if (_ocupado) return;
    _ocupado = true;
    _cancelarTimers();
    final anterior = _fase;
    final g = ++_geracao;
    setState(() {
      _fase = _Fase.reservando;
      _motivo = null;
      _chave = chave;
    });
    try {
      final resp = await http.post(
        _uri(''),
        headers: ApiAuth.jsonHeaders(extra: chave == null ? null : {'Idempotency-Key': chave}),
        body: jsonEncode({'userId': widget.userId, 'eventId': widget.eventId}),
      );
      if (!mounted || g != _geracao) return;
      switch (classificarReserva(resp.statusCode)) {
        case ResultadoReserva.pendente:
          _chave = null;
          _iniciarContagem(_json(resp)['expiresAt']?.toString());
        case ResultadoReserva.enfileirada:
          // 202: nenhuma reserva ainda. A mesma chave (corpo, ou header) é a da consulta e de um "Tentar de novo".
          final corpo = _json(resp);
          _chave = (corpo['idempotencyKey'] ?? resp.headers['idempotency-key'] ?? chave)?.toString();
          _intervalo = intervaloDaConsulta(resp.headers['retry-after']);
          _tentativas = 0;
          _instavel = false;
          setState(() => _fase = _Fase.aguardando);
          _agendarConsulta(g);
        case ResultadoReserva.sessaoExpirada:
          setState(() => _fase = anterior);
          AppModals.showError(context, 'Sessão Expirada', 'Entre novamente na sua conta para se inscrever.');
        case ResultadoReserva.recusada:
          // 400 (lotado, vínculo já existente), 422, 429, 503: o corpo é o texto; segue na fase anterior.
          final texto = _texto(resp, 'Você já está inscrito neste evento ou o evento está lotado.');
          if (anterior == _Fase.expirada && vinculoJaExiste(resp.statusCode, texto)) {
            // O pending vencido ainda não foi varrido pelo job de expiração (~30 s): fica na "expirada", com aviso.
            setState(() {
              _fase = anterior;
              _motivo = 'A reserva anterior ainda está sendo liberada. Tente de novo em alguns segundos.';
            });
            return;
          }
          setState(() => _fase = anterior);
          AppModals.showError(context, resp.statusCode == 400 ? 'Inscrição Inválida' : 'Erro na Inscrição', texto);
      }
    } catch (_) {
      if (!mounted || g != _geracao) return;
      setState(() => _fase = anterior);
      AppModals.showError(context, 'Erro de Conexão', 'Não foi possível conectar ao servidor. Verifique sua conexão.');
    } finally {
      _ocupado = false;
    }
  }

  // ---------------------------------------------------------------- consulta da fila

  void _agendarConsulta(int g) {
    _timerConsulta = Timer(_intervalo, () => _consultar(g));
  }

  Future<void> _consultar(int g) async {
    if (!mounted || g != _geracao || _fase != _Fase.aguardando) return;
    _tentativas++;
    ResultadoConsulta r;
    Map<String, dynamic> corpo = {};
    try {
      final resp = await http.get(
        _uri('/admission?idempotencyKey=${Uri.encodeQueryComponent(_chave ?? '')}'),
        headers: ApiAuth.jsonHeaders(),
      ).timeout(const Duration(seconds: 5)); // sem resposta conta como falha transitória (catch) e a consulta segue até o limite
      if (!mounted || g != _geracao) return;
      corpo = _json(resp);
      r = classificarConsulta(resp.statusCode, corpo['status']?.toString());
    } catch (_) {
      if (!mounted || g != _geracao) return;
      r = ResultadoConsulta.instavel; // erro de rede: igual a 503, segue tentando dentro do limite
    }
    switch (r) {
      case ResultadoConsulta.pendente:
        _chave = null;
        _iniciarContagem(corpo['expiresAt']?.toString());
      case ResultadoConsulta.recusada:
        _chave = null; // refazer = POST novo, sem a chave
        setState(() {
          _fase = _Fase.recusada;
          _motivo = corpo['reason']?.toString() ?? 'Não foi possível reservar a vaga.';
        });
      case ResultadoConsulta.naoChegouATempo:
        setState(() => _fase = _Fase.naoChegou); // mantém a chave: "Tentar de novo" repete o POST com ela
      case ResultadoConsulta.perdida:
        _chave = null; // 404: o servidor não conhece a chave (perdeu o estado); oferece refazer
        setState(() {
          _fase = _Fase.recusada;
          _motivo = 'Perdemos o acompanhamento do seu pedido. Tente reservar de novo.';
        });
      case ResultadoConsulta.sessaoExpirada:
        setState(() => _fase = _Fase.ocioso);
        AppModals.showError(context, 'Sessão Expirada', 'Entre novamente na sua conta para se inscrever.');
      case ResultadoConsulta.aguardando:
      case ResultadoConsulta.instavel:
        // 503 (admission fora) e erros transitórios: continua consultando até o limite, que passa do TTL da fila.
        if (_tentativas >= kLimiteConsultas) {
          setState(() => _fase = _Fase.naoChegou);
        } else {
          setState(() => _instavel = r == ResultadoConsulta.instavel);
          _agendarConsulta(g);
        }
    }
  }

  // ---------------------------------------------------------------- contagem regressiva

  void _iniciarContagem(String? expiresAt) {
    _cancelarTimers();
    _expiraUtc = parseExpiresAtUtc(expiresAt);
    setState(() => _fase = _Fase.pendente);
    _correrContagem();
  }

  void _correrContagem() {
    final expira = _expiraUtc;
    if (expira == null) {
      setState(() => _restante = null); // sem prazo legível: só o botão "Confirmar"; o servidor decide ao confirmar
      return;
    }
    _timerContagem?.cancel();
    void tique() {
      final r = tempoRestante(expira, DateTime.now().toUtc());
      if (r == Duration.zero) {
        _timerContagem?.cancel();
        if (mounted) setState(() => _fase = _Fase.expirada);
      } else if (mounted) {
        setState(() => _restante = r);
      }
    }

    tique();
    if (_fase == _Fase.pendente) {
      _timerContagem = Timer.periodic(const Duration(seconds: 1), (_) => tique());
    }
  }

  // ---------------------------------------------------------------- confirmar

  Future<void> _confirmar() async {
    if (_ocupado) return;
    _ocupado = true;
    _timerContagem?.cancel();
    setState(() => _fase = _Fase.confirmando);
    final g = _geracao;
    try {
      final resp = await http.post(
        _uri('/confirm'),
        headers: ApiAuth.jsonHeaders(),
        body: jsonEncode({'userId': widget.userId, 'eventId': widget.eventId}),
      ).timeout(const Duration(seconds: 10)); // sem resposta vai ao catch: volta à contagem e libera o botão
      if (!mounted || g != _geracao) return;
      final texto = _texto(resp, 'Não foi possível confirmar a reserva. Tente novamente.');
      // "Reserva já confirmada" (400) é o resultado desejado: mesmo caminho do 200.
      if (resp.statusCode == 200 || confirmacaoJaFeita(resp.statusCode, texto)) {
        widget.onConfirmada();
        return;
      }
      if (confirmacaoVenceu(resp.statusCode, texto)) {
        setState(() => _fase = _Fase.expirada);
        return;
      }
      setState(() => _fase = _Fase.pendente);
      _correrContagem();
      AppModals.showError(context, 'Confirmação Pendente', texto);
    } catch (_) {
      if (!mounted || g != _geracao) return;
      setState(() => _fase = _Fase.pendente);
      _correrContagem();
      AppModals.showError(context, 'Erro de Conexão', 'Não foi possível conectar ao servidor. Verifique sua conexão.');
    } finally {
      _ocupado = false;
    }
  }

  // ---------------------------------------------------------------- UI

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (_fase != _Fase.ocioso && _fase != _Fase.reservando) ...[
          _caixa(),
          const SizedBox(height: 16),
        ],
        Row(
          children: [
            Expanded(child: _botaoPrincipal()),
            const SizedBox(width: 16),
            Expanded(
              child: OutlinedButton(
                onPressed: () => Navigator.pop(context),
                style: OutlinedButton.styleFrom(
                  foregroundColor: Colors.red,
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                  side: const BorderSide(color: Colors.red),
                ),
                child: const Text('Cancelar', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _botaoPrincipal() {
    String rotulo;
    VoidCallback? acao;
    bool ocupado = false;
    switch (_fase) {
      case _Fase.ocioso:
        rotulo = 'Reservar vaga';
        acao = widget.temVagas ? () => _reservar() : null;
      case _Fase.reservando:
        rotulo = 'Reservando...';
        ocupado = true;
      case _Fase.aguardando:
        rotulo = 'Aguardando...';
        ocupado = true;
      case _Fase.pendente:
        rotulo = 'Confirmar inscrição';
        acao = _confirmar;
      case _Fase.confirmando:
        rotulo = 'Confirmando...';
        ocupado = true;
      case _Fase.expirada:
      case _Fase.recusada:
        rotulo = 'Reservar de novo';
        acao = () => _reservar();
      case _Fase.naoChegou:
        rotulo = 'Tentar de novo';
        acao = () => _reservar(chave: _chave);
    }
    return ElevatedButton(
      onPressed: acao,
      style: ElevatedButton.styleFrom(
        backgroundColor: Colors.green,
        foregroundColor: Colors.white,
        padding: const EdgeInsets.symmetric(vertical: 16),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        disabledBackgroundColor: Colors.grey,
        disabledForegroundColor: Colors.white,
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          if (ocupado) ...[
            const SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
            ),
            const SizedBox(width: 8),
          ],
          Flexible(child: Text(rotulo, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold))),
        ],
      ),
    );
  }

  Widget _caixa() {
    MaterialColor cor;
    IconData icone;
    String titulo;
    String texto;
    Widget? extra;
    switch (_fase) {
      case _Fase.aguardando:
        cor = Colors.blue;
        icone = Icons.hourglass_top;
        titulo = 'Aguardando confirmação';
        texto = _instavel
            ? 'Conexão instável, tentando de novo...'
            : 'Muita gente está reservando agora. Você está na fila e esta tela atualiza sozinha.';
        extra = const Padding(padding: EdgeInsets.only(top: 12), child: LinearProgressIndicator());
      case _Fase.pendente:
      case _Fase.confirmando:
        cor = Colors.green;
        icone = Icons.event_available;
        titulo = 'Vaga reservada';
        final r = _restante;
        texto = r == null
            ? 'Confirme sua inscrição. Se não confirmar a tempo, a vaga é liberada.'
            : 'Confirme sua inscrição antes do fim do prazo. Se sair ou o tempo acabar, a vaga é liberada.';
        if (r != null) {
          extra = Padding(
            padding: const EdgeInsets.only(top: 8),
            // Fora da liveRegion e com label estável: o mm:ss muda todo segundo e o leitor não deve anunciar cada tique.
            child: Semantics(
              label: 'Tempo restante para confirmar',
              value: formatarMmSs(r),
              excludeSemantics: true,
              child: Text(
                formatarMmSs(r),
                style: TextStyle(
                  fontSize: 32,
                  fontWeight: FontWeight.bold,
                  color: r < const Duration(minutes: 1) ? Colors.red : Colors.green.shade800,
                ),
              ),
            ),
          );
        }
      case _Fase.expirada:
        cor = Colors.orange;
        icone = Icons.timer_off;
        titulo = 'Reserva expirada';
        texto = _motivo ?? 'O prazo para confirmar acabou. A vaga é liberada em instantes; você pode reservar de novo.';
      case _Fase.recusada:
        cor = Colors.red;
        icone = Icons.cancel_outlined;
        titulo = 'Não foi possível reservar';
        texto = _motivo ?? 'Tente reservar de novo.';
      case _Fase.naoChegou:
        cor = Colors.orange;
        icone = Icons.hourglass_disabled;
        titulo = 'A fila não andou a tempo';
        texto = 'Não conseguimos reservar sua vaga a tempo. Tente de novo.';
      case _Fase.ocioso:
      case _Fase.reservando:
        return const SizedBox.shrink();
    }
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: cor[50],
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: cor),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icone, color: cor),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Só título e texto são liveRegion: mudança de estado é anunciada; a contagem (extra) não.
                Semantics(
                  liveRegion: true,
                  container: true,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(titulo, style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: cor.shade800)),
                      const SizedBox(height: 4),
                      Text(texto, style: TextStyle(color: cor.shade900)),
                    ],
                  ),
                ),
                if (extra != null) extra,
              ],
            ),
          ),
        ],
      ),
    );
  }
}
