-- 99-tests.sql
-- Suite de testes mínima do schema:
-- - Trigger que cria mywallet ao inserir em users
-- - Criação de eventos + agregado eventcap isolado
-- - Reserva via UPDATE condicional atômico (CAS) e limite de vagas
-- - CHECK da invariante 0 <= quant <= capacity
-- - eventpresenter (lista de apresentadores, PK composta)
-- - Máquina de estados de walletevent (status)
-- - Read model desnormalizado event_availability (refresh inline + cascata no DELETE)
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

    -- 6b) Read model event_availability: UPSERT do refresh inline + cascata no DELETE do evento
    INSERT INTO public.event (creator_id, event_name, event_date, description)
    VALUES (v_user1, 'Evento Read Model', now() + interval '40 days', 'rm')
    RETURNING event_id INTO v_event_rm;
    INSERT INTO public.eventcap (event_id, capacity, quant) VALUES (v_event_rm, 4, 4);
    -- 1 reserva ativa (pending) -> available deve ser capacity - ativos = 3
    INSERT INTO public.walletevent (user_id, event_id, status, expires_at)
    VALUES (v_user2, v_event_rm, 'pending', now() + interval '120 seconds');

    INSERT INTO public.event_availability (event_id, available, refreshed_at)
    SELECT ec.event_id,
           CASE WHEN ec.capacity = -1 THEN -1
                ELSE GREATEST(ec.capacity - COALESCE(a.ativos, 0), 0) END,
           now()
    FROM public.eventcap ec
    LEFT JOIN (
        SELECT event_id, count(*) AS ativos
        FROM public.walletevent
        WHERE status IN ('pending', 'confirmed')
        GROUP BY event_id
    ) a ON a.event_id = ec.event_id
    ON CONFLICT (event_id) DO UPDATE
      SET available = EXCLUDED.available, refreshed_at = EXCLUDED.refreshed_at;

    SELECT available INTO v_count FROM public.event_availability WHERE event_id = v_event_rm;
    IF v_count <> 3 THEN
      RAISE EXCEPTION 'Falha: available do read model deveria ser 3 (capacity 4 - 1 ativa), e %', v_count;
    END IF;

    -- cascata: DELETE do evento remove a linha de event_availability (FK ON DELETE CASCADE)
    DELETE FROM public.walletevent WHERE event_id = v_event_rm;
    DELETE FROM public.event WHERE event_id = v_event_rm;
    SELECT count(*) INTO v_count FROM public.event_availability WHERE event_id = v_event_rm;
    IF v_count <> 0 THEN RAISE EXCEPTION 'Falha: event_availability nao foi removido em cascata'; END IF;

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

    -- 9) ON DELETE CASCADE de event -> eventcap / eventpresenter
    DELETE FROM public.walletevent WHERE event_id = v_event1;
    DELETE FROM public.event WHERE event_id = v_event1;
    SELECT count(*) INTO v_count FROM public.eventcap WHERE event_id = v_event1;
    IF v_count <> 0 THEN RAISE EXCEPTION 'Falha: eventcap nao foi removido em cascata'; END IF;
    SELECT count(*) INTO v_count FROM public.eventpresenter WHERE event_id = v_event1;
    IF v_count <> 0 THEN RAISE EXCEPTION 'Falha: eventpresenter nao foi removido em cascata'; END IF;

    -- 10) TRUNCATE e revalidacao do trigger
    TRUNCATE TABLE public.eventpresenter, public.eventcap, public.event_availability, public.event,
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
                   public.walletevent, public.mywallet, public.users
      RESTART IDENTITY CASCADE;
  EXCEPTION WHEN OTHERS THEN
    v_err := SQLERRM;
  END;

  -- Cleanup incondicional
  TRUNCATE TABLE public.eventpresenter, public.eventcap, public.event_availability, public.event,
                 public.walletevent, public.mywallet, public.users
    RESTART IDENTITY CASCADE;

  IF v_err IS NOT NULL THEN
    RAISE EXCEPTION 'Falha nos testes: %', v_err;
  ELSE
    RAISE NOTICE 'Testes minimos concluidos com sucesso';
  END IF;
END$$;

\echo Testes concluidos.
