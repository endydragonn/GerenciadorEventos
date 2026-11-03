// Semeia o dry-run (issue #197, etapa 4b) pelo POST /bff/seed, que SO existe no overlay `carga` (SEED_ENABLED=true): 1 usuario host e 1 usuario base (o dry-run clona o hash da
// senha do base por SQL para o pool de usuarios da carga) e, depois do login do host (para ler o user_id), o evento de capacidade limitada que a carga disputa.
// Variaveis (-e): BASE_URL, SUFIXO, CAPACIDADE. Imprime `HOST_ID <n>`; qualquer resposta inesperada aborta (exit != 0).
import http from 'k6/http';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://backend:8081';
const SUFIXO = __ENV.SUFIXO;
const CAPACIDADE = parseInt(__ENV.CAPACIDADE || '30', 10);
const H = { headers: { 'Content-Type': 'application/json' } };
export const options = { vus: 1, iterations: 1 };

function exigir(res, esperado, o) {
  if (!esperado.includes(res.status)) exec.test.abort(`${o}: HTTP ${res.status} ${String(res.body).slice(0, 200)}`);
}

export default function () {
  const usuario = (nome, fim, admin) => ({ name: `${nome} ${SUFIXO}`, email: `${nome.toLowerCase()}-${SUFIXO}@example.com`, password: 'senha123',
    fone: `${fim}${SUFIXO}`.slice(0, 11), birthDate: '1990-01-15', isAdmin: admin });
  let r = http.post(`${BASE}/bff/seed`, JSON.stringify({ users: [usuario('Host', '11', true), usuario('Base', '12', false)] }), H);
  exigir(r, [200, 201], 'seed de usuarios');
  r = http.post(`${BASE}/bff/users/login`, JSON.stringify({ email: `host-${SUFIXO}@example.com`, password: 'senha123' }), H);
  exigir(r, [200], 'login do host');
  const hostId = r.json().user.user_id;
  console.log(`HOST_ID ${hostId}`);
  r = http.post(`${BASE}/bff/seed`, JSON.stringify({ events: [{ creator_id: hostId, event_name: `Dry quente ${SUFIXO}`, is_EAD: true, event_date: '2035-12-01T14:00:00',
    lot_quantity: CAPACIDADE, quantity: CAPACIDADE, description: 'dry-run #197', presenters: [] }] }), H);
  exigir(r, [200, 201], 'seed do evento');
}
