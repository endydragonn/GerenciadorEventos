#!/bin/bash

# Script para exportar dados do banco para arquivo texto formatado
# Opções:
#   -p | --populate  Executa db/seed-data.sql antes de exportar (idempotente)
#   -o <arquivo>     Define nome do arquivo de saída (opcional)
#   -h | --help      Mostra ajuda

set -euo pipefail

OUTPUT_FILE="database-export-$(date +%Y%m%d-%H%M%S).txt"
RUN_POPULATE=false

print_help() {
    cat <<EOF
Uso: $0 [opções]

Gera um snapshot textual do banco de dados.

Opções:
    -p, --populate    Executa seed (db/seed-data.sql) antes de exportar
    -o, --output ARQ  Define nome do arquivo de saída (default: database-export-<timestamp>.txt)
    -h, --help        Exibe esta ajuda

Exemplos:
    $0                Apenas exporta
    $0 -p             Popula e exporta
    $0 -p -o relatorio.txt  Popula e exporta para relatorio.txt
EOF
}

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        -p|--populate)
            RUN_POPULATE=true
            shift
            ;;
        -o|--output)
            OUTPUT_FILE="${2:-}"
            if [[ -z "$OUTPUT_FILE" ]]; then
                echo "Erro: --output requer um nome de arquivo" >&2
                exit 1
            fi
            shift 2
            ;;
        -h|--help)
            print_help
            exit 0
            ;;
        *)
            echo "Opção desconhecida: $1" >&2
            echo "Use --help para ajuda." >&2
            exit 1
            ;;
    esac
done

if [[ "$RUN_POPULATE" == true ]]; then
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    SEED_FILE="$SCRIPT_DIR/db/seed-data.sql"
    if [[ ! -f "$SEED_FILE" ]]; then
        echo "Arquivo $SEED_FILE não encontrado" >&2
        exit 1
    fi
    echo "🌱 Populando banco (seed-data.sql)..."
    docker compose -f "$SCRIPT_DIR/../docker-compose.yml" exec -T db psql -U admin -d meu_banco < "$SEED_FILE" >/dev/null 2>&1 || {
        echo "Falha ao executar seed" >&2
        exit 1
    }
    echo "✔ Seed executado (idempotente)."
fi

echo "📊 Exportando dados do banco para: $OUTPUT_FILE"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/../docker-compose.yml"

{
        echo "╔════════════════════════════════════════════════════════════════╗"
        echo "║          EXPORTAÇÃO DO BANCO DE DADOS - GERENCIADOR           ║"
        echo "║                  Data: $(date '+%d/%m/%Y %H:%M:%S')                  ║"
        if [[ "$RUN_POPULATE" == true ]]; then
            echo "║              (Seed executado antes da exportação)              ║"
        fi
        echo "╚════════════════════════════════════════════════════════════════╝"
        echo ""
    
        echo "👥 ═══════════════════ USUÁRIOS ═══════════════════"
        docker compose -f "$COMPOSE_FILE" exec -T db psql -U admin -d meu_banco -c "
                SELECT 
                        user_id as ID,
                        user_name as Nome,
                        email as Email,
                        fone as Telefone,
                        TO_CHAR(birthdate, 'DD/MM/YYYY') as Nascimento,
                        CASE WHEN admin THEN 'Sim' ELSE 'Não' END as Admin,
                        CASE WHEN isactive THEN 'Ativo' ELSE 'Inativo' END as Status,
                        SUBSTRING(password, 1, 60) as \"Senha (hash BCrypt)\"
                FROM users 
                ORDER BY user_id;
        "
    
        echo ""
        echo "🔐 ═══════════════ SENHAS DE EXEMPLO (seed) ═══════════════"
        echo "AVISO: Senhas BCrypt são unidirecionais (não podem ser revertidas)"
        echo "Senhas usadas no populate-data.ps1:"
        echo ""
        echo "  • joao.silva@email.com     → senha123"
        echo "  • maria.santos@email.com   → senha456"
        echo "  • pedro.oliveira@email.com → senha789"
        echo "  • ana.costa@email.com      → senha321"
        echo "  • carlos.souza@email.com   → admin123"
        echo ""
        echo "Nota: Estas senhas só são válidas se os dados foram populados"
        echo "      via script populate-data.ps1"
    
        echo ""
        echo "🎫 ═══════════════════ EVENTOS ═══════════════════"
        docker compose -f "$COMPOSE_FILE" exec -T db psql -U admin -d meu_banco -c "
                SELECT 
                        event_id as ID,
                        event_name as Evento,
                        CASE WHEN ead THEN 'EAD' ELSE 'Presencial' END as Tipo,
                        TO_CHAR(event_date, 'DD/MM/YYYY HH24:MI') as Data,
                        COALESCE(capacity::text, 'Ilimitado') as Capacidade,
                        quant as Vagas,
                        description as Descrição
                FROM event 
                ORDER BY event_date;
        "
    
        echo ""
        echo "💰 ═══════════════════ CARTEIRAS ═══════════════════"
        docker compose -f "$COMPOSE_FILE" exec -T db psql -U admin -d meu_banco -c "
                SELECT 
                        w.user_id as \"ID Usuario\",
                        u.user_name as Usuario,
                        TO_CHAR(w.created_at, 'DD/MM/YYYY HH24:MI') as \"Criada em\"
                FROM mywallet w
                JOIN users u ON w.user_id = u.user_id
                ORDER BY w.user_id;
        "
    
        echo ""
        echo "📝 ═══════════════════ INSCRIÇÕES ═══════════════════"
        docker compose -f "$COMPOSE_FILE" exec -T db psql -U admin -d meu_banco -c "
                SELECT 
                        u.user_name as Usuario,
                        e.event_name as Evento,
                        TO_CHAR(e.event_date, 'DD/MM/YYYY') as \"Data Evento\",
                        TO_CHAR(we.created_at, 'DD/MM/YYYY HH24:MI') as \"Inscrito em\"
                FROM walletevent we
                JOIN users u ON we.user_id = u.user_id
                JOIN event e ON we.event_id = e.event_id
                ORDER BY u.user_name, e.event_name;
        "
    
        echo ""
        echo "📊 ═══════════════════ ESTATÍSTICAS ═══════════════════"
        docker compose -f "$COMPOSE_FILE" exec -T db psql -U admin -d meu_banco -c "
                SELECT 
                        (SELECT COUNT(*) FROM users) as \"Total Usuários\",
                        (SELECT COUNT(*) FROM users WHERE admin = true) as Admins,
                        (SELECT COUNT(*) FROM event) as \"Total Eventos\",
                        (SELECT COUNT(*) FROM event WHERE ead = true) as \"Eventos EAD\",
                        (SELECT COUNT(*) FROM walletevent) as \"Total Inscrições\";
        "
    
        echo ""
        echo "🏆 ═══════════════ EVENTO MAIS POPULAR ═══════════════"
        docker compose -f "$COMPOSE_FILE" exec -T db psql -U admin -d meu_banco -c "
                SELECT 
                        e.event_name as Evento,
                        COUNT(we.user_id) as Inscrições
                FROM event e
                LEFT JOIN walletevent we ON e.event_id = we.event_id
                GROUP BY e.event_id, e.event_name
                ORDER BY COUNT(we.user_id) DESC
                LIMIT 5;
        "
    
        echo ""
        echo "╔════════════════════════════════════════════════════════════════╗"
        echo "║                    FIM DA EXPORTAÇÃO                           ║"
        echo "╚════════════════════════════════════════════════════════════════╝"
    
} > "$OUTPUT_FILE"

echo "✅ Exportação concluída!"
echo "📁 Arquivo: $OUTPUT_FILE"
echo ""
echo "Para visualizar:"
echo "  cat $OUTPUT_FILE"
echo "  less $OUTPUT_FILE"
echo "  code $OUTPUT_FILE"
