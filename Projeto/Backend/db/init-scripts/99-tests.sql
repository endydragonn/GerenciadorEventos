-- 99-tests.sql
-- Suite de testes mínima do schema:
-- - Trigger que cria mywallet ao inserir em users
-- - Criação de eventos + agregado eventcap isolado
-- - Reserva via UPDATE condicional atômico (CAS) e limite de vagas
-- - CHECK da invariante 0 <= quant <= capacity
-- - eventpresenter (lista de apresentadores, PK composta)
-- - Máquina de estados de walletevent (status)
-- - Projecao assincrona event_availability (so ativos; disponibilidade calculada na leitura) e walletevent.version da guarda
-- - event_store/event_outbox/event_snapshot/projection_checkpoint (concorrência otimista, outbox, checkpoint atômico)
-- - TRUNCATE e revalidação pós-TRUNCATE

\echo Iniciando testes minimos do banco...

DO $$
DECLARE
  v_user1 integer;
  v_user2 integer;
  v_event1 integer;
  v_event2 integer;
  v_event_ilim integer;
  v_event_rm integer;
  v_count integer;
  v_rows  integer;
  v_err   text;
BEGIN
  v_err := NULL;
  BEGIN
    -- Baseline limpa
    TRUNCATE TABLE public.eventpresenter, public.eventcap, public.event_availability, public.event,
                   public.event_store, public.event_outbox, public.event_snapshot, public.projection_checkpoint,
                   public.walletevent, public.mywallet, public.users
      RESTART IDENTITY CASCADE;

    -- 1) Inserir usuario e validar trigger da carteira
    INSERT INTO public.users (user_name, email, fone, password, birthdate)
    VALUES ('teste_trigger', 'trigger@test.com', '5500000000', 'pw', '1990-01-01')
    RETURNING user_id INTO v_user1;

    SELECT count(*) INTO v_count FROM public.mywallet WHERE user_id = v_user1;
    IF v_count <> 1 THEN
      RAISE EXCEPTION 'Falha: trigger nao criou mywallet (esperado 1, obtido %)', v_count;
    END IF;

    INSERT INTO public.users (user_name, email, fone, password, birthdate)
    VALUES ('teste_trigger2', 'trigger2@test.com', '6600000000', 'pw', '1992-02-02')
    RETURNING user_id INTO v_user2;

    -- 2) Criar eventos + eventcap
    INSERT INTO public.event (creator_id, event_name, event_date, description)
    VALUES (v_user1, 'Evento Teste 1', now() + interval '30 days', 'desc1')
    RETURNING event_id INTO v_event1;

    INSERT INTO public.event (creator_id, event_name, event_date, description)
    VALUES (v_user1, 'Evento Teste 2', now() + interval '60 days', 'desc2')
    RETURNING event_id INTO v_event2;

    INSERT INTO public.eventcap (event_id, capacity, quant, buy_time_limit)
    VALUES (v_event1, 2, 2, now() + interval '20 days');
    INSERT INTO public.eventcap (event_id, capacity, quant, buy_time_limit)
    VALUES (v_event2, 1, 1, now() + interval '50 days');

    -- 3) eventpresenter: lista de apresentadores, PK composta
    INSERT INTO public.eventpresenter (event_id, presenter_name) VALUES (v_event1, 'Alice');
    INSERT INTO public.eventpresenter (event_id, presenter_name) VALUES (v_event1, 'Bob');
    SELECT count(*) INTO v_count FROM public.eventpresenter WHERE event_id = v_event1;
    IF v_count <> 2 THEN
      RAISE EXCEPTION 'Falha: esperado 2 apresentadores, obtido %', v_count;
    END IF;
    BEGIN
      INSERT INTO public.eventpresenter (event_id, presenter_name) VALUES (v_event1, 'Alice');
      RAISE EXCEPTION 'Falha: apresentador duplicado nao violou a PK composta';
    EXCEPTION WHEN unique_violation THEN NULL; END;

    -- 4) CHECK da invariante 0 <= quant <= capacity
    BEGIN
      INSERT INTO public.event (creator_id, event_name, event_date, description)
      VALUES (v_user1, 'Evento Invalido', now() + interval '10 days', 'x')
      RETURNING event_id INTO v_count;
      INSERT INTO public.eventcap (event_id, capacity, quant) VALUES (v_count, 5, 6);
      RAISE EXCEPTION 'Falha: CHECK permitiu quant > capacity';
    EXCEPTION WHEN check_violation THEN NULL; END;

    -- 5) Reserva via UPDATE condicional atomico (CAS) no evento 2 (capacity=1)
    UPDATE public.eventcap SET quant = quant - 1 WHERE event_id = v_event2 AND quant >= 1;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    IF v_rows <> 1 THEN RAISE EXCEPTION 'Falha: primeira reserva CAS nao afetou 1 linha (obtido %)', v_rows; END IF;

    INSERT INTO public.walletevent (user_id, event_id, status, expires_at)
    VALUES (v_user1, v_event2, 'pending', now() + interval '120 seconds');

    -- Segunda reserva no mesmo evento: sem vaga -> 0 linhas
    UPDATE public.eventcap SET quant = quant - 1 WHERE event_id = v_event2 AND quant >= 1;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    IF v_rows <> 0 THEN RAISE EXCEPTION 'Falha: CAS permitiu overselling (afetou % linhas)', v_rows; END IF;

    SELECT quant INTO v_count FROM public.eventcap WHERE event_id = v_event2;
    IF v_count <> 0 THEN RAISE EXCEPTION 'Falha: quant do evento 2 deveria ser 0, e %', v_count; END IF;

    -- 5b) Evento ILIMITADO (capacity = -1): CAS nao afeta linha; CHECK nao exige quant<=capacity
    INSERT INTO public.event (creator_id, event_name, event_date, description)
    VALUES (v_user1, 'Evento Ilimitado', now() + interval '90 days', 'sem limite')
    RETURNING event_id INTO v_event_ilim;
    INSERT INTO public.eventcap (event_id, capacity, quant) VALUES (v_event_ilim, -1, -1);

    UPDATE public.eventcap SET quant = quant - 1
      WHERE event_id = v_event_ilim AND capacity <> -1 AND quant >= 1;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    IF v_rows <> 0 THEN RAISE EXCEPTION 'Falha: CAS afetou evento ilimitado (%)', v_rows; END IF;

    INSERT INTO public.walletevent (user_id, event_id) VALUES (v_user1, v_event_ilim);
    INSERT INTO public.walletevent (user_id, event_id) VALUES (v_user2, v_event_ilim);
    SELECT quant INTO v_count FROM public.eventcap WHERE event_id = v_event_ilim;
    IF v_count <> -1 THEN RAISE EXCEPTION 'Falha: quant do ilimitado mudou (%)', v_count; END IF;

    -- 6) Confirmacao e liberacao de vaga
    UPDATE public.walletevent SET status = 'confirmed', expires_at = NULL
      WHERE user_id = v_user1 AND event_id = v_event2 AND status = 'pending';
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    IF v_rows <> 1 THEN RAISE EXCEPTION 'Falha: confirmacao pending->confirmed nao afetou 1 linha'; END IF;

    -- Cancelamento devolve a vaga
    UPDATE public.walletevent SET status = 'cancelled'
      WHERE user_id = v_user1 AND event_id = v_event2;
    UPDATE public.eventcap SET quant = quant + 1 WHERE event_id = v_event2 AND quant < capacity;
    SELECT quant INTO v_count FROM public.eventcap WHERE event_id = v_event2;
    IF v_count <> 1 THEN RAISE EXCEPTION 'Falha: cancelamento nao devolveu a vaga (quant=%)', v_count; END IF;

    -- 6b) Projecao assincrona event_availability (issue #29): so guarda "ativos"; a disponibilidade e calculada na
    --     leitura (eventcap LEFT JOIN projecao), nunca grava nem le eventcap.quant
    INSERT INTO public.event (creator_id, event_name, event_date, description)
    VALUES (v_user1, 'Evento Read Model', now() + interval '40 days', 'rm')
    RETURNING event_id INTO v_event_rm;
    INSERT INTO public.eventcap (event_id, capacity, quant) VALUES (v_event_rm, 4, 4);

    -- evento sem nenhuma linha na projecao ja tem disponibilidade = capacity
    SELECT CASE WHEN ec.capacity = -1 THEN -1 ELSE GREATEST(ec.capacity - GREATEST(COALESCE(ea.ativos, 0), 0), 0) END
      INTO v_count
      FROM public.eventcap ec LEFT JOIN public.event_availability ea ON ea.event_id = ec.event_id
     WHERE ec.event_id = v_event_rm;
    IF v_count <> 4 THEN RAISE EXCEPTION 'Falha: evento sem linha na projecao deveria ter disponibilidade 4, e %', v_count; END IF;

    -- SlotReserved (+1), depois ReservationConfirmed (0, so avanca applied_at/last_occurred_at)
    INSERT INTO public.event_availability (event_id, ativos, last_occurred_at, applied_at)
    VALUES (v_event_rm, 1, clock_timestamp(), clock_timestamp())
    ON CONFLICT (event_id) DO UPDATE
      SET ativos = event_availability.ativos + EXCLUDED.ativos,
          last_occurred_at = GREATEST(event_availability.last_occurred_at, EXCLUDED.last_occurred_at),
          applied_at = clock_timestamp();
    SELECT GREATEST(ec.capacity - GREATEST(COALESCE(ea.ativos, 0), 0), 0) INTO v_count
      FROM public.eventcap ec LEFT JOIN public.event_availability ea ON ea.event_id = ec.event_id
     WHERE ec.event_id = v_event_rm;
    IF v_count <> 3 THEN
      RAISE EXCEPTION 'Falha: disponibilidade deveria ser 3 (capacity 4 - 1 ativo), e %', v_count;
    END IF;

    -- ReservationCancelled (-1) devolve; um -1 a mais (linha anterior ao ES) nao quebra a leitura: piso 0 / sem CHECK
    INSERT INTO public.event_availability (event_id, ativos, last_occurred_at, applied_at)
    VALUES (v_event_rm, -2, clock_timestamp(), clock_timestamp())
    ON CONFLICT (event_id) DO UPDATE SET ativos = event_availability.ativos + EXCLUDED.ativos;
    SELECT ativos INTO v_count FROM public.event_availability WHERE event_id = v_event_rm;
    IF v_count <> -1 THEN RAISE EXCEPTION 'Falha: ativos deveria aceitar saldo negativo (sem CHECK), e %', v_count; END IF;
    -- com ativos = -1 a disponibilidade calculada NAO passa da capacidade (ativos negativo conta como 0)
    SELECT GREATEST(ec.capacity - GREATEST(COALESCE(ea.ativos, 0), 0), 0) INTO v_count
      FROM public.eventcap ec LEFT JOIN public.event_availability ea ON ea.event_id = ec.event_id
     WHERE ec.event_id = v_event_rm;
    IF v_count <> 4 THEN
      RAISE EXCEPTION 'Falha: com ativos = -1 a disponibilidade deveria ser a capacidade (4), e %', v_count;
    END IF;

    -- sem FK para event: apagar o evento nao mexe na projecao (e a projecao nao trava o DELETE)
    DELETE FROM public.event WHERE event_id = v_event_rm;
    SELECT count(*) INTO v_count FROM public.event_availability WHERE event_id = v_event_rm;
    IF v_count <> 1 THEN RAISE EXCEPTION 'Falha: event_availability nao deveria ter FK para event'; END IF;
    DELETE FROM public.event_availability WHERE event_id = v_event_rm;

    -- 6c) walletevent.version sai da propria guarda (UPDATE ... RETURNING version): a 1a reserva e a v1 e reviver
    --     uma cancelada continua o mesmo stream
    INSERT INTO public.event (creator_id, event_name, event_date, description)
    VALUES (v_user1, 'Evento Version', now() + interval '41 days', 'ver')
    RETURNING event_id INTO v_event_rm;
    INSERT INTO public.walletevent (user_id, event_id, status, expires_at, version)
    VALUES (v_user2, v_event_rm, 'pending', now() + interval '120 seconds', 1) RETURNING version INTO v_count;
    IF v_count <> 1 THEN RAISE EXCEPTION 'Falha: 1a reserva deveria ser a version 1, e %', v_count; END IF;
    UPDATE public.walletevent SET status = 'cancelled', expires_at = NULL, version = version + 1
     WHERE user_id = v_user2 AND event_id = v_event_rm AND status IN ('pending', 'confirmed') RETURNING version INTO v_count;
    IF v_count <> 2 THEN RAISE EXCEPTION 'Falha: cancelamento deveria dar a version 2, e %', v_count; END IF;
    INSERT INTO public.walletevent (user_id, event_id, status, expires_at, version)
    VALUES (v_user2, v_event_rm, 'pending', now() + interval '120 seconds', 1)
    ON CONFLICT (user_id, event_id) DO UPDATE
      SET status = 'pending', expires_at = EXCLUDED.expires_at, version = walletevent.version + 1
      WHERE walletevent.status IN ('expired', 'cancelled')
    RETURNING version INTO v_count;
    IF v_count <> 3 THEN RAISE EXCEPTION 'Falha: reviver reserva cancelada deveria dar a version 3, e %', v_count; END IF;
    -- a guarda que nao afeta linha (reserva ativa) nao devolve versao nenhuma
    v_count := NULL;
    INSERT INTO public.walletevent (user_id, event_id, status, expires_at, version)
    VALUES (v_user2, v_event_rm, 'pending', now() + interval '120 seconds', 1)
    ON CONFLICT (user_id, event_id) DO UPDATE
      SET status = 'pending', version = walletevent.version + 1
      WHERE walletevent.status IN ('expired', 'cancelled')
    RETURNING version INTO v_count;
    IF v_count IS NOT NULL THEN RAISE EXCEPTION 'Falha: guarda sobre reserva ativa nao deveria afetar linha (version %)', v_count; END IF;
    DELETE FROM public.walletevent WHERE event_id = v_event_rm;
    DELETE FROM public.event WHERE event_id = v_event_rm;

    -- 7) status invalido rejeitado pelo CHECK
    BEGIN
      INSERT INTO public.walletevent (user_id, event_id, status)
      VALUES (v_user2, v_event1, 'foo');
      RAISE EXCEPTION 'Falha: status invalido aceito';
    EXCEPTION WHEN check_violation THEN NULL; END;

    -- 8) N:N e PK composta em walletevent (status default = confirmed)
    INSERT INTO public.walletevent (user_id, event_id) VALUES (v_user1, v_event1);
    INSERT INTO public.walletevent (user_id, event_id) VALUES (v_user2, v_event1);
    SELECT count(*) INTO v_count FROM public.walletevent WHERE status = 'confirmed';
    IF v_count < 2 THEN RAISE EXCEPTION 'Falha: status default confirmed nao aplicado'; END IF;
    BEGIN
      INSERT INTO public.walletevent (user_id, event_id) VALUES (v_user1, v_event1);
      RAISE EXCEPTION 'Falha: insercao duplicada em walletevent nao falhou';
    EXCEPTION WHEN unique_violation THEN NULL; END;

    -- 8b) idempotency_key: a PK (user_id, idempotency_key) serializa a mesma chave DO MESMO usuario
    --     (ON CONFLICT DO NOTHING = 0 linhas na 2a vez); outro usuario pode usar a mesma chave
    INSERT INTO public.idempotency_key (idempotency_key, user_id, event_id, expires_at)
    VALUES ('chave-teste', v_user1, v_event1, now());
    INSERT INTO public.idempotency_key (idempotency_key, user_id, event_id, expires_at)
    VALUES ('chave-teste', v_user1, v_event1, now()) ON CONFLICT (user_id, idempotency_key) DO NOTHING;
    SELECT count(*) INTO v_count FROM public.idempotency_key WHERE idempotency_key = 'chave-teste';
    IF v_count <> 1 THEN RAISE EXCEPTION 'Falha: idempotency_key aceitou chave duplicada do mesmo usuario'; END IF;
    BEGIN
      INSERT INTO public.idempotency_key (idempotency_key, user_id, event_id, expires_at)
      VALUES ('chave-teste', v_user1, v_event1, now());
      RAISE EXCEPTION 'Falha: insercao duplicada em idempotency_key nao falhou';
    EXCEPTION WHEN unique_violation THEN NULL; END;
    INSERT INTO public.idempotency_key (idempotency_key, user_id, event_id, expires_at)
    VALUES ('chave-teste', v_user2, v_event1, now());
    SELECT count(*) INTO v_count FROM public.idempotency_key WHERE idempotency_key = 'chave-teste';
    IF v_count <> 2 THEN RAISE EXCEPTION 'Falha: idempotency_key nao escopou a chave por usuario'; END IF;
    DELETE FROM public.idempotency_key WHERE idempotency_key = 'chave-teste';

    -- 8c) event_store / event_outbox / event_snapshot / projection_checkpoint (issue #27):
    --     concorrência otimista por (stream_type, stream_id, version) com ON CONFLICT DO NOTHING (rowcount 0),
    --     outbox com PK, upsert do snapshot e avanço atômico do checkpoint (UPDATE ... WHERE version = v-1)
    INSERT INTO public.event_store (event_id, stream_type, stream_id, version, event_type, payload, partition_key)
    VALUES (gen_random_uuid(), 'teste.stream', 's1', 1, 'Teste', '{"a":1}', '1');
    INSERT INTO public.event_store (event_id, stream_type, stream_id, version, event_type, payload, partition_key)
    VALUES (gen_random_uuid(), 'teste.stream', 's1', 1, 'Teste', '{"a":2}', '1')
    ON CONFLICT (stream_type, stream_id, version) DO NOTHING;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    IF v_rows <> 0 THEN RAISE EXCEPTION 'Falha: event_store aceitou a mesma version duas vezes (rowcount %)', v_rows; END IF;
    BEGIN
      INSERT INTO public.event_store (event_id, stream_type, stream_id, version, event_type, payload, partition_key)
      VALUES (gen_random_uuid(), 'teste.stream', 's1', 1, 'Teste', '{}', '1');
      RAISE EXCEPTION 'Falha: UNIQUE (stream_type, stream_id, version) nao barrou version repetida';
    EXCEPTION WHEN unique_violation THEN NULL; END;
    BEGIN
      INSERT INTO public.event_store (event_id, stream_type, stream_id, version, event_type, payload, partition_key)
      VALUES (gen_random_uuid(), 'teste.stream', 's1', 0, 'Teste', '{}', '1');
      RAISE EXCEPTION 'Falha: CHECK version >= 1 nao barrou version 0';
    EXCEPTION WHEN check_violation THEN NULL; END;
    INSERT INTO public.event_store (event_id, stream_type, stream_id, version, event_type, payload, partition_key)
    VALUES (gen_random_uuid(), 'teste.stream', 's1', 2, 'Teste', '{}', '1');
    SELECT count(DISTINCT global_position) INTO v_count FROM public.event_store;
    IF v_count <> 2 THEN RAISE EXCEPTION 'Falha: global_position nao e unico por evento (%)', v_count; END IF;

    INSERT INTO public.event_outbox (global_position) SELECT global_position FROM public.event_store;
    BEGIN
      INSERT INTO public.event_outbox (global_position) SELECT min(global_position) FROM public.event_store;
      RAISE EXCEPTION 'Falha: event_outbox aceitou global_position duplicada';
    EXCEPTION WHEN unique_violation THEN NULL; END;

    INSERT INTO public.event_snapshot (stream_type, stream_id, version, state) VALUES ('teste.stream', 's1', 2, '{"n":2}')
    ON CONFLICT (stream_type, stream_id) DO UPDATE SET version = EXCLUDED.version, state = EXCLUDED.state;
    INSERT INTO public.event_snapshot (stream_type, stream_id, version, state) VALUES ('teste.stream', 's1', 4, '{"n":4}')
    ON CONFLICT (stream_type, stream_id) DO UPDATE SET version = EXCLUDED.version, state = EXCLUDED.state;
    SELECT version INTO v_count FROM public.event_snapshot WHERE stream_type = 'teste.stream' AND stream_id = 's1';
    IF v_count <> 4 THEN RAISE EXCEPTION 'Falha: event_snapshot deveria guardar so o ultimo snapshot (version %)', v_count; END IF;

    INSERT INTO public.projection_checkpoint (projecao, stream_type, stream_id, version)
    VALUES ('p', 'teste.stream', 's1', 1) ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    IF v_rows <> 1 THEN RAISE EXCEPTION 'Falha: checkpoint da v1 deveria inserir (rowcount %)', v_rows; END IF;
    INSERT INTO public.projection_checkpoint (projecao, stream_type, stream_id, version)
    VALUES ('p', 'teste.stream', 's1', 1) ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    IF v_rows <> 0 THEN RAISE EXCEPTION 'Falha: checkpoint da v1 reaplicada deveria dar rowcount 0 (%)', v_rows; END IF;
    UPDATE public.projection_checkpoint SET version = 2
     WHERE projecao = 'p' AND stream_type = 'teste.stream' AND stream_id = 's1' AND version = 1;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    IF v_rows <> 1 THEN RAISE EXCEPTION 'Falha: avanco v1 -> v2 do checkpoint deveria afetar 1 linha (%)', v_rows; END IF;
    UPDATE public.projection_checkpoint SET version = 2
     WHERE projecao = 'p' AND stream_type = 'teste.stream' AND stream_id = 's1' AND version = 1;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    IF v_rows <> 0 THEN RAISE EXCEPTION 'Falha: reavanco do checkpoint deveria afetar 0 linhas (%)', v_rows; END IF;

    -- 8d) event.version (issue #28): nasce 0 (= pre-ES, sem stream) e e a versao esperada do proximo append
    SELECT version INTO v_count FROM public.event WHERE event_id = v_event1;
    IF v_count <> 0 THEN RAISE EXCEPTION 'Falha: event.version deveria nascer 0 (%)', v_count; END IF;
    BEGIN
      UPDATE public.event SET version = NULL WHERE event_id = v_event1;
      RAISE EXCEPTION 'Falha: event.version aceitou NULL';
    EXCEPTION WHEN not_null_violation THEN NULL; END;

    -- 9) ON DELETE CASCADE de event -> eventcap / eventpresenter
    DELETE FROM public.walletevent WHERE event_id = v_event1;
    DELETE FROM public.event WHERE event_id = v_event1;
    SELECT count(*) INTO v_count FROM public.eventcap WHERE event_id = v_event1;
    IF v_count <> 0 THEN RAISE EXCEPTION 'Falha: eventcap nao foi removido em cascata'; END IF;
    SELECT count(*) INTO v_count FROM public.eventpresenter WHERE event_id = v_event1;
    IF v_count <> 0 THEN RAISE EXCEPTION 'Falha: eventpresenter nao foi removido em cascata'; END IF;

    -- 10) TRUNCATE e revalidacao do trigger
    TRUNCATE TABLE public.eventpresenter, public.eventcap, public.event_availability, public.event,
                   public.event_store, public.event_outbox, public.event_snapshot, public.projection_checkpoint,
                   public.walletevent, public.mywallet, public.users
      RESTART IDENTITY CASCADE;

    SELECT count(*) INTO v_count FROM public.users;      IF v_count <> 0 THEN RAISE EXCEPTION 'users nao ficou vazio'; END IF;
    SELECT count(*) INTO v_count FROM public.eventcap;   IF v_count <> 0 THEN RAISE EXCEPTION 'eventcap nao ficou vazio'; END IF;

    INSERT INTO public.users (user_name, email, fone, password, birthdate)
    VALUES ('teste_pos_truncate', 'postruncate@test.com', '7700000000', 'pw', '1995-05-05')
    RETURNING user_id INTO v_user1;
    SELECT count(*) INTO v_count FROM public.mywallet WHERE user_id = v_user1;
    IF v_count <> 1 THEN RAISE EXCEPTION 'trigger nao criou mywallet apos TRUNCATE'; END IF;

    TRUNCATE TABLE public.eventpresenter, public.eventcap, public.event_availability, public.event,
                   public.event_store, public.event_outbox, public.event_snapshot, public.projection_checkpoint,
                   public.walletevent, public.mywallet, public.users
      RESTART IDENTITY CASCADE;
  EXCEPTION WHEN OTHERS THEN
    v_err := SQLERRM;
  END;

  -- Cleanup incondicional
  TRUNCATE TABLE public.eventpresenter, public.eventcap, public.event_availability, public.event,
                   public.event_store, public.event_outbox, public.event_snapshot, public.projection_checkpoint,
                 public.walletevent, public.mywallet, public.users
    RESTART IDENTITY CASCADE;

  IF v_err IS NOT NULL THEN
    RAISE EXCEPTION 'Falha nos testes: %', v_err;
  ELSE
    RAISE NOTICE 'Testes minimos concluidos com sucesso';
  END IF;
END$$;

\echo Testes concluidos.
