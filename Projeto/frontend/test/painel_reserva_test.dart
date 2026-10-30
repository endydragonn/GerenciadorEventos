import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_web_app/helpers/reserva_logica.dart';

void main() {
  group('parseExpiresAtUtc', () {
    test('string sem fuso é lida como UTC', () {
      final d = parseExpiresAtUtc('2026-10-28T18:30:00.123456')!;
      expect(d.isUtc, isTrue);
      expect(d.hour, 18);
      expect(d.minute, 30);
    });

    test('com Z ou offset respeita o fuso informado', () {
      expect(parseExpiresAtUtc('2026-10-28T18:30:00Z')!.hour, 18);
      expect(parseExpiresAtUtc('2026-10-28T18:30:00-03:00')!.toUtc().hour, 21);
      expect(parseExpiresAtUtc('2026-10-28T18:30:00+0100')!.toUtc().hour, 17);
    });

    test('nulo, vazio e lixo viram null', () {
      expect(parseExpiresAtUtc(null), isNull);
      expect(parseExpiresAtUtc(''), isNull);
      expect(parseExpiresAtUtc('amanha'), isNull);
    });

    test('data pura (sem T) não confunde o dia com offset', () {
      // "-28" não é fuso: cai no ramo sem fuso (+Z), que o Dart rejeita para data sem hora.
      expect(parseExpiresAtUtc('2026-10-28'), isNull);
    });
  });

  group('tempo restante e mm:ss', () {
    final expira = DateTime.utc(2026, 10, 28, 18, 30);

    test('diferença até expiresAt, sem ficar negativa', () {
      expect(tempoRestante(expira, DateTime.utc(2026, 10, 28, 18, 28, 30)), const Duration(seconds: 90));
      expect(tempoRestante(expira, DateTime.utc(2026, 10, 28, 18, 31)), Duration.zero);
    });

    test('formata mm:ss arredondando para cima', () {
      expect(formatarMmSs(const Duration(seconds: 90)), '01:30');
      expect(formatarMmSs(const Duration(milliseconds: 200)), '00:01');
      expect(formatarMmSs(Duration.zero), '00:00');
      expect(formatarMmSs(const Duration(minutes: 75)), '75:00');
    });
  });

  group('intervaloDaConsulta', () {
    test('usa o Retry-After, com padrão e limites', () {
      expect(intervaloDaConsulta('2'), const Duration(seconds: 2));
      expect(intervaloDaConsulta(null), const Duration(seconds: 1));
      expect(intervaloDaConsulta('abc'), const Duration(seconds: 1));
      expect(intervaloDaConsulta('0'), const Duration(seconds: 1));
      expect(intervaloDaConsulta('999'), const Duration(seconds: kIntervaloMaximoSeg));
    });

    test('o limite de consultas cobre o TTL da fila (30 s) com o intervalo padrão', () {
      expect(kLimiteConsultas * kIntervaloPadraoSeg, greaterThan(30));
    });
  });

  group('classificarReserva', () {
    test('por código HTTP', () {
      expect(classificarReserva(201), ResultadoReserva.pendente);
      expect(classificarReserva(202), ResultadoReserva.enfileirada);
      expect(classificarReserva(401), ResultadoReserva.sessaoExpirada);
      for (final c in [400, 422, 429, 503]) {
        expect(classificarReserva(c), ResultadoReserva.recusada);
      }
    });
  });

  group('classificarConsulta', () {
    test('estados do 200', () {
      expect(classificarConsulta(200, 'queued'), ResultadoConsulta.aguardando);
      expect(classificarConsulta(200, 'pending'), ResultadoConsulta.pendente);
      expect(classificarConsulta(200, 'rejected'), ResultadoConsulta.recusada);
      expect(classificarConsulta(200, 'timed_out'), ResultadoConsulta.naoChegouATempo);
      expect(classificarConsulta(200, 'novo'), ResultadoConsulta.aguardando);
    });

    test('404 perdida, 401 sessão, 503 e demais instável', () {
      expect(classificarConsulta(404, null), ResultadoConsulta.perdida);
      expect(classificarConsulta(401, null), ResultadoConsulta.sessaoExpirada);
      expect(classificarConsulta(503, null), ResultadoConsulta.instavel);
      expect(classificarConsulta(400, null), ResultadoConsulta.instavel);
    });
  });

  group('confirmacaoVenceu', () {
    test('reconhece as mensagens do reservation', () {
      expect(confirmacaoVenceu(400, 'Reserva expirada — refaça a inscrição'), isTrue);
      expect(confirmacaoVenceu(400, 'Nenhuma reserva pendente para confirmar'), isTrue);
      expect(confirmacaoVenceu(400, 'Reserva já confirmada'), isFalse);
      expect(confirmacaoVenceu(500, 'Reserva expirada'), isFalse);
    });

    test('"Reserva já confirmada" (400) conta como sucesso', () {
      expect(confirmacaoJaFeita(400, 'Reserva já confirmada'), isTrue);
      expect(confirmacaoJaFeita(400, 'Reserva expirada — refaça a inscrição'), isFalse);
      expect(confirmacaoJaFeita(500, 'Reserva já confirmada'), isFalse);
    });
  });

  group('vinculoJaExiste', () {
    test('reconhece o 400 do POST de reserva', () {
      expect(vinculoJaExiste(400, 'Vínculo já existente'), isTrue);
      expect(vinculoJaExiste(400, 'Evento lotado'), isFalse);
      expect(vinculoJaExiste(429, 'Vínculo já existente'), isFalse);
    });
  });
}
