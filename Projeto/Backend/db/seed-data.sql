-- ==========================================
-- SCRIPT PARA POPULAR TABELAS COM DADOS
-- Executa INSERTs sem limpar dados existentes
-- Encoding: UTF-8
--
-- ATENÇÃO (issue #28): os eventos inseridos aqui entram em `event` direto, com version = 0 e SEM stream no
-- event_store. O catalog RECUSA alterar/cancelar um evento assim (erro "é pré-ES"). Depois de carregar este seed,
-- rode o backfill (idempotente: cria o EventCreated v1 de cada evento com version = 0):
--   docker exec -i gerenciador-db psql -h localhost -U supabase_admin -d meu_banco \
--     < Projeto/Backend/db/migrations/20261022-catalog-event-version.sql
-- ==========================================

SET client_encoding = 'UTF8';

-- Inserir usuários de exemplo
INSERT INTO users (user_name, email, fone, password, birthdate, admin) VALUES
    ('João Silva', 'joao.silva@email.com', '11987654321', 'senha123', '1990-05-15', false),
    ('Maria Santos', 'maria.santos@email.com', '11987654322', 'senha456', '1985-08-22', false),
    ('Pedro Oliveira', 'pedro.oliveira@email.com', '11987654323', 'senha789', '1992-03-10', false),
    ('Ana Costa', 'ana.costa@email.com', '11987654324', 'senha321', '1988-11-30', false),
    ('Carlos Souza', 'carlos.souza@email.com', '11987654325', 'senha654', '1995-07-18', true)
ON CONFLICT (email) DO NOTHING;

-- Inserir eventos de exemplo (metadados apenas; capacidade vai em eventcap)
INSERT INTO event (creator_id, event_name, ead, address, event_date, description) VALUES
    (
        (SELECT user_id FROM users WHERE email = 'joao.silva@email.com'),
        'Workshop de Java',
        false,
        'Av. Paulista, 1000 - São Paulo, SP',
        '2026-12-15 09:00:00',
        'Aprenda Java do zero ao avançado com experts da área'
    ),
    (
        (SELECT user_id FROM users WHERE email = 'maria.santos@email.com'),
        'Conferência de DevOps',
        false,
        'Centro de Convenções - Rio de Janeiro, RJ',
        '2026-12-20 14:00:00',
        'As melhores práticas de DevOps e Cloud Computing'
    ),
    (
        (SELECT user_id FROM users WHERE email = 'pedro.oliveira@email.com'),
        'Hackathon 2026',
        false,
        'Campus UFMG - Belo Horizonte, MG',
        '2027-01-10 08:00:00',
        'Competição de programação com prêmios incríveis'
    ),
    (
        (SELECT user_id FROM users WHERE email = 'joao.silva@email.com'),
        'Meetup de Spring Boot',
        true,
        NULL,
        '2026-12-01 19:00:00',
        'Compartilhe experiências e aprenda sobre Spring Boot'
    ),
    (
        (SELECT user_id FROM users WHERE email = 'carlos.souza@email.com'),
        'Curso de Docker',
        false,
        'Rua XV de Novembro, 500 - Curitiba, PR',
        '2027-02-05 10:00:00',
        'Containerização e orquestração com Docker e Kubernetes'
    )
ON CONFLICT (event_name) DO NOTHING;

-- Capacidade/vagas isoladas em eventcap (quant = vagas ainda disponíveis)
INSERT INTO eventcap (event_id, capacity, quant, buy_time_limit)
SELECT e.event_id, v.capacity, v.quant, v.buy_time_limit
FROM (VALUES
    ('Workshop de Java',      100, 98,  TIMESTAMP '2026-12-14 23:59:59'),
    ('Conferência de DevOps', 200, 197, TIMESTAMP '2026-12-19 23:59:59'),
    ('Hackathon 2026',        50,  49,  TIMESTAMP '2027-01-09 23:59:59'),
    ('Meetup de Spring Boot', 80,  79,  TIMESTAMP '2026-12-01 18:00:00'),
    ('Curso de Docker',       30,  29,  TIMESTAMP '2027-02-04 23:59:59')
) AS v(event_name, capacity, quant, buy_time_limit)
JOIN event e ON e.event_name = v.event_name
ON CONFLICT (event_id) DO NOTHING;

-- Apresentadores de exemplo
INSERT INTO eventpresenter (event_id, presenter_name)
SELECT e.event_id, v.presenter_name
FROM (VALUES
    ('Workshop de Java',      'Fulano de Tal'),
    ('Workshop de Java',      'Ciclana Souza'),
    ('Conferência de DevOps', 'Beltrano Lima')
) AS v(event_name, presenter_name)
JOIN event e ON e.event_name = v.event_name
ON CONFLICT (event_id, presenter_name) DO NOTHING;

-- Nota: MyWallet é criada automaticamente pelo trigger create_wallet_for_user
-- quando um usuário é inserido, então não precisamos inserir aqui

-- Inserir inscrições confirmadas (walletevent relaciona user_id com event_id)
INSERT INTO walletevent (user_id, event_id, status) VALUES
    (
        (SELECT user_id FROM users WHERE email = 'maria.santos@email.com'),
        (SELECT event_id FROM event WHERE event_name = 'Workshop de Java'),
        'confirmed'
    ),
    (
        (SELECT user_id FROM users WHERE email = 'pedro.oliveira@email.com'),
        (SELECT event_id FROM event WHERE event_name = 'Conferência de DevOps'),
        'confirmed'
    ),
    (
        (SELECT user_id FROM users WHERE email = 'ana.costa@email.com'),
        (SELECT event_id FROM event WHERE event_name = 'Meetup de Spring Boot'),
        'confirmed'
    ),
    (
        (SELECT user_id FROM users WHERE email = 'carlos.souza@email.com'),
        (SELECT event_id FROM event WHERE event_name = 'Hackathon 2026'),
        'confirmed'
    ),
    (
        (SELECT user_id FROM users WHERE email = 'maria.santos@email.com'),
        (SELECT event_id FROM event WHERE event_name = 'Curso de Docker'),
        'confirmed'
    ),
    (
        (SELECT user_id FROM users WHERE email = 'joao.silva@email.com'),
        (SELECT event_id FROM event WHERE event_name = 'Conferência de DevOps'),
        'confirmed'
    )
ON CONFLICT (user_id, event_id) DO NOTHING;

-- Exibir resumo dos dados inseridos
DO $$
DECLARE
    v_users_count INTEGER;
    v_events_count INTEGER;
    v_wallets_count INTEGER;
    v_transactions_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_users_count FROM users;
    SELECT COUNT(*) INTO v_events_count FROM event;
    SELECT COUNT(*) INTO v_wallets_count FROM mywallet;
    SELECT COUNT(*) INTO v_transactions_count FROM walletevent;

    RAISE NOTICE '==========================================';
    RAISE NOTICE 'DADOS POPULADOS COM SUCESSO!';
    RAISE NOTICE '==========================================';
    RAISE NOTICE 'Resumo:';
    RAISE NOTICE '  - Usuarios: %', v_users_count;
    RAISE NOTICE '  - Eventos: %', v_events_count;
    RAISE NOTICE '  - Carteiras: %', v_wallets_count;
    RAISE NOTICE '  - Inscricoes: %', v_transactions_count;
    RAISE NOTICE '==========================================';
END $$;

-- Mostrar dados das tabelas
\echo ''
\echo '========== USUARIOS =========='
SELECT user_id, user_name, email, fone, TO_CHAR(birthdate, 'DD/MM/YYYY') as nascimento, admin
FROM users
ORDER BY user_id;

\echo ''
\echo '========== EVENTOS =========='
SELECT e.event_id, e.event_name,
       CASE WHEN e.ead THEN 'EAD' ELSE 'Presencial' END as tipo,
       TO_CHAR(e.event_date, 'DD/MM/YYYY HH24:MI') as data_evento,
       c.capacity as capacidade,
       c.quant as vagas_disponiveis
FROM event e
LEFT JOIN eventcap c ON c.event_id = e.event_id
ORDER BY e.event_date;

\echo ''
\echo '========== CARTEIRAS =========='
SELECT w.user_id, u.user_name as usuario
FROM mywallet w
JOIN users u ON w.user_id = u.user_id
ORDER BY w.user_id;

\echo ''
\echo '========== INSCRICOES =========='
SELECT u.user_name as usuario, e.event_name as evento, we.status
FROM walletevent we
JOIN users u ON we.user_id = u.user_id
JOIN event e ON we.event_id = e.event_id
ORDER BY u.user_name, e.event_name;
