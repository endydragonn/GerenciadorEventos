// Lógica pura da reserva em duas fases (issue #44): sem widget, sem rede, testável em
// test/painel_reserva_test.dart. O painel (widgets/painel_reserva.dart) só orquestra.

/// Limite de consultas ao estado da fila. Com o intervalo padrão de 1 s (`Retry-After: 1`) são 60 s,
/// acima do TTL da fila no admission (`ADMISSION_QUEUE_TTL_MS`, default 30 s): o servidor dá `timed_out`
/// antes de o cliente desistir.
const int kLimiteConsultas = 60;

/// Intervalo padrão entre consultas, e o teto aceito do `Retry-After` (evita um valor absurdo parar a tela).
const int kIntervaloPadraoSeg = 1;
const int kIntervaloMaximoSeg = 5;

/// Interpreta o `expiresAt` do backend. O servidor serializa um `LocalDateTime` (ISO SEM offset, ex.
/// `2026-10-28T18:30:00.123456`) gerado no relógio do container, que roda em UTC (sem `TZ` no compose).
/// `DateTime.parse` de string sem offset daria hora LOCAL do navegador (UTC-3 = contagem 3 h errada),
/// então, sem `Z`/offset, a string é lida como UTC. Se o backend passar a mandar offset, ele é respeitado.
/// Devolve `null` se não der para interpretar.
DateTime? parseExpiresAtUtc(String? s) {
  if (s == null || s.isEmpty) return null;
  // Só a parte depois do `T` pode ter fuso: o `-28` de uma data pura (`2026-10-28`) não é offset.
  final hora = s.contains('T') ? s.split('T').last : '';
  final temFuso = hora.endsWith('Z') || RegExp(r'[+-]\d{2}(:?\d{2})?$').hasMatch(hora);
  return DateTime.tryParse(temFuso ? s : '${s}Z');
}

/// Tempo que falta até [expiraUtc] (nunca negativo).
Duration tempoRestante(DateTime expiraUtc, DateTime agoraUtc) {
  final d = expiraUtc.difference(agoraUtc);
  return d.isNegative ? Duration.zero : d;
}

/// `mm:ss`, arredondando para cima (0,2 s ainda mostra 00:01). Minutos não dão a volta em 59.
String formatarMmSs(Duration d) {
  final total = (d.inMilliseconds + 999) ~/ 1000;
  final m = (total ~/ 60).toString().padLeft(2, '0');
  final s = (total % 60).toString().padLeft(2, '0');
  return '$m:$s';
}

/// Intervalo da consulta: o `Retry-After` do 202 (segundos), entre 1 e [kIntervaloMaximoSeg]; padrão 1 s.
Duration intervaloDaConsulta(String? retryAfter) {
  final n = int.tryParse(retryAfter ?? '') ?? kIntervaloPadraoSeg;
  return Duration(seconds: n.clamp(1, kIntervaloMaximoSeg));
}

/// O que fazer com a resposta do `POST /bff/event-wallets`.
enum ResultadoReserva { pendente, enfileirada, sessaoExpirada, recusada }

ResultadoReserva classificarReserva(int statusCode) {
  switch (statusCode) {
    case 201:
    case 200:
      return ResultadoReserva.pendente;
    case 202:
      return ResultadoReserva.enfileirada;
    case 401:
      return ResultadoReserva.sessaoExpirada;
    default: // 400 (lotado/vínculo), 422, 429, 503...: o corpo é o texto a mostrar
      return ResultadoReserva.recusada;
  }
}

/// O que fazer com a resposta do `GET /bff/event-wallets/admission`.
enum ResultadoConsulta { aguardando, pendente, recusada, naoChegouATempo, perdida, sessaoExpirada, instavel }

/// [status] é o campo `status` do corpo do 200. 404 = estado perdido (chave inexistente); 401 = sessão
/// expirada; qualquer outro código (503 com o admission fora, 5xx, 400) e erro de rede = [instavel]: o
/// painel segue consultando até [kLimiteConsultas], porque o admission pode voltar dentro do TTL.
ResultadoConsulta classificarConsulta(int statusCode, String? status) {
  if (statusCode == 404) return ResultadoConsulta.perdida;
  if (statusCode == 401) return ResultadoConsulta.sessaoExpirada;
  if (statusCode != 200) return ResultadoConsulta.instavel;
  switch (status) {
    case 'pending':
      return ResultadoConsulta.pendente;
    case 'rejected':
      return ResultadoConsulta.recusada;
    case 'timed_out':
      return ResultadoConsulta.naoChegouATempo;
    default: // 'queued' (ou um estado novo que este cliente não conhece: segue esperando)
      return ResultadoConsulta.aguardando;
  }
}

/// O `/confirm` falhou porque a reserva já venceu (ou não existe mais)? Textos do `EventWalletService`
/// do reservation: "Reserva expirada — refaça a inscrição" e "Nenhuma reserva pendente para confirmar".
bool confirmacaoVenceu(int statusCode, String corpo) {
  if (statusCode != 400) return false;
  final c = corpo.toLowerCase();
  return c.contains('expirada') || c.contains('nenhuma reserva pendente');
}

/// O `/confirm` deu 400 porque a reserva JÁ estava confirmada ("Reserva já confirmada", `EventWalletService`)?
/// Acontece num 2º clique ou numa resposta 200 perdida: o resultado é o que o usuário queria, trata como sucesso.
bool confirmacaoJaFeita(int statusCode, String corpo) {
  return statusCode == 400 && corpo.toLowerCase().contains('já confirmada');
}

/// O POST de reserva voltou 400 "Vínculo já existente"? Logo depois de a contagem zerar, o `pending` vencido
/// ainda existe até o job de expiração do reservation varrê-lo (a cada ~30 s): não é inscrição de verdade.
bool vinculoJaExiste(int statusCode, String corpo) {
  return statusCode == 400 && corpo.toLowerCase().contains('vínculo já existente');
}
