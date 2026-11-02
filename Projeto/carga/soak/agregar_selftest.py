"""Auto-teste do agregar.py (issue #232) com um CSV pequeno no formato real do `k6 --out csv` (fixtures/k6-referencia.csv). Uso:  python agregar_selftest.py

Os percentis esperados foram calculados a mao pelo metodo nearest-rank (o k-esimo menor valor, k = ceil(p x n / 100)):
  leitura min 0: 11 duracoes 10,20,...,100,200 -> p50 = 6o = 60, p95 = ceil(10,45) = 11o = 200, p99 = 11o = 200
  cadastro min 0: 50,70,100,300 -> p50 = 2o = 70, p95 = 4o = 300, p99 = 300
  mailpit min 0: 5,7 -> p50 = 1o = 5, p95 = 2o = 7, p99 = 7
  leitura min 1: 20,40,60 -> p50 = 2o = 40, p95 = 3o = 60, p99 = 60
  reserva min 1: 30,40,50,60,70,80,90 -> p50 = ceil(3,5) = 4o = 60, p95 = ceil(6,65) = 7o = 90, p99 = 90   (o 90 e o status 0 = c0)
  inicio_s: t0 = 1000 (1000 - 60 x 0 = 1060 - 60 x 1), entao o minuto m comeca em 1000 + 60 m
"""
import contextlib
import csv
import gzip
import io
import os
import shutil
import sys
import tempfile
import unittest

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
import agregar as A  # noqa: E402

FIXTURE = os.path.join(AQUI, "fixtures", "k6-referencia.csv")

ESPERADO = [
    # minuto,tipo,taxa,reqs,p50,p95,p99,c2xx,c202,c400,c429,c5xx,c0,outros,descartadas,verificacoes_2xx,inicio_s,atrasada
    ["0", "cadastro", "0", "4", "70.000", "300.000", "300.000", "3", "0", "1", "0", "0", "0", "0", "0", "2", "1000", "0"],
    ["0", "leitura", "3", "11", "60.000", "200.000", "200.000", "10", "0", "0", "0", "1", "0", "0", "0", "0", "1000", "0"],
    ["0", "mailpit", "0", "2", "5.000", "7.000", "7.000", "2", "0", "0", "0", "0", "0", "0", "0", "0", "1000", "0"],
    ["1", "leitura", "2", "3", "40.000", "60.000", "60.000", "3", "0", "0", "0", "0", "0", "0", "4", "0", "1060", "0"],
    ["1", "reserva", "0", "7", "60.000", "90.000", "90.000", "1", "1", "1", "1", "1", "1", "1", "0", "0", "1060", "0"],
]


def rodar(entrada):
    buf = io.StringIO()
    n, avisos, atrasadas = A.agregar(entrada, buf)
    return n, avisos, atrasadas, list(csv.reader(io.StringIO(buf.getvalue())))


class Agregar(unittest.TestCase):
    def test_reproduz_os_percentis_e_contagens_do_csv_de_referencia(self):
        n, avisos, atrasadas, linhas = rodar(FIXTURE)
        self.assertEqual(linhas[0], A.COLUNAS)
        self.assertEqual(linhas[1:], ESPERADO)
        self.assertEqual(n, 5)
        self.assertEqual(avisos, [])

    def test_classes_sao_mutuamente_exclusivas_e_somam_as_requisicoes(self):
        _, _, _, linhas = rodar(FIXTURE)
        for l in linhas[1:]:
            self.assertEqual(sum(int(x) for x in l[7:14]), int(l[3]), l)   # vale tambem nas linhas atrasadas

    def test_gzip_da_o_mesmo_resultado(self):
        with tempfile.TemporaryDirectory() as d:
            gz = os.path.join(d, "k6.csv.gz")
            with open(FIXTURE, "rb") as f, gzip.open(gz, "wb") as g:
                shutil.copyfileobj(f, g)
            _, _, _, linhas = rodar(gz)
        self.assertEqual(linhas[1:], ESPERADO)

    def test_percentil_nearest_rank(self):
        self.assertEqual(A.percentil([1, 2, 3, 4], 50), 2)
        self.assertEqual(A.percentil([1, 2, 3, 4], 95), 4)
        self.assertEqual(A.percentil([7], 99), 7)
        self.assertEqual(A.percentil(list(range(1, 101)), 95), 95)
        self.assertEqual(A.percentil(list(range(1, 101)), 99), 99)

    def test_classe(self):
        for status, esperado in ((200, "c2xx"), (201, "c2xx"), (202, "c202"), (204, "c2xx"), (400, "c400"), (401, "outros"), (429, "c429"), (500, "c5xx"),
                                 (503, "c5xx"), (0, "c0"), (404, "outros"), (422, "outros")):
            self.assertEqual(A.classe(status), esperado, status)

    def test_cabecalho_do_k6_em_outra_ordem_e_lido_pelo_nome(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "k6.csv")
            with open(p, "w", encoding="utf-8", newline="") as f:
                f.write("extra_tags,status,name,metric_value,timestamp,metric_name\n")
                f.write("tipo=login&fase=vale&min=3,200,/bff/users/login,12.5,5000,http_req_duration\n")
            _, _, _, linhas = rodar(p)
        self.assertEqual(linhas[1][:8], ["3", "login", "0", "1", "12.500", "12.500", "12.500", "1"])

    def test_minutos_fechados_sao_descartados_da_memoria_e_a_saida_sai_em_ordem(self):
        # 10 minutos, 2 tipos, entregues em ordem: a saida e ordenada por (minuto, tipo) e nada fica pendente
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "k6.csv")
            with open(p, "w", encoding="utf-8", newline="") as f:
                f.write("metric_name,timestamp,metric_value,status,name,extra_tags\n")
                for m in range(10):
                    for tipo in ("reserva", "leitura"):
                        f.write("http_req_duration,%d,%d,200,/x,tipo=%s&min=%d\n" % (100 + 60 * m, m + 1, tipo, m))
            _, avisos, _, linhas = rodar(p)
        self.assertEqual([(int(l[0]), l[1]) for l in linhas[1:]], [(m, t) for m in range(10) for t in ("leitura", "reserva")])
        self.assertEqual(avisos, [])

    def _com_atrasada(self, d, status):
        p = os.path.join(d, "k6.csv")
        with open(p, "w", encoding="utf-8", newline="") as f:
            f.write("metric_name,timestamp,metric_value,status,name,extra_tags\n")
            for m in (0, 1, 2, 3, 4, 5):
                f.write("http_req_duration,%d,5,200,/x,tipo=leitura&min=%d\n" % (100 + 60 * m, m))
            f.write("http_req_duration,100,5,%s,/x,tipo=leitura&min=0\n" % status)  # minuto 0 de novo, depois de fechado
        return p

    def test_atrasada_2xx_vira_linha_atrasada_no_proprio_por_minuto(self):
        with tempfile.TemporaryDirectory() as d:
            n, avisos, atrasadas, linhas = rodar(self._com_atrasada(d, 200))
        normais = [l for l in linhas[1:] if l[0] == "0" and l[-1] == "0"]
        tardias = [l for l in linhas[1:] if l[-1] == "1"]
        self.assertEqual(len(normais), 1)
        self.assertEqual(normais[0][3], "1")                       # a linha normal do minuto 0 nao ganhou a amostra tardia
        self.assertEqual(len(tardias), 1)
        self.assertEqual(tardias[0][:2] + tardias[0][3:7], ["0", "leitura", "1", "", "", ""])   # so contagens, sem percentis
        self.assertEqual(tardias[0][7], "1")                       # c2xx
        self.assertEqual(len(set(avisos)), 1)
        self.assertEqual(atrasadas, {"leitura": {"amostras": 1, "reqs": 1, "c5xx": 0, "c0": 0}})

    def test_atrasada_com_5xx_ou_status_0_e_contada_por_tipo_e_o_cli_sai_com_3(self):
        for status, campo in ((503, "c5xx"), (0, "c0")):
            with tempfile.TemporaryDirectory() as d:
                p = self._com_atrasada(d, status)
                _, _, atrasadas, _ = rodar(p)
                self.assertEqual(atrasadas["leitura"][campo], 1, status)
                saida = os.path.join(d, "pm.csv")
                with contextlib.redirect_stderr(io.StringIO()) as err:
                    self.assertEqual(A.main([p, "--id", "T", "--saida", saida]), 3, status)
                self.assertIn("ERRO", err.getvalue())
                self.assertIn("leitura", err.getvalue())

    def test_atrasada_com_5xx_ou_status_0_reprova_o_criterio_3_lendo_so_o_por_minuto(self):
        # de ponta a ponta: agregar.main -> soak-T-por-minuto.csv -> criterios.crit3 (o verificar-soak.sh pos so ve esse arquivo, nunca o codigo de saida do agregar)
        import criterios as C
        for status, campo in ((503, "c5xx"), (0, "c0")):
            with tempfile.TemporaryDirectory() as d:
                p = self._com_atrasada(d, status)
                saida = os.path.join(d, "soak-T-por-minuto.csv")
                with contextlib.redirect_stderr(io.StringIO()):
                    A.main([p, "--id", "T", "--saida", saida])
                c3 = C.criterio3(C.ler_csv(saida))
                self.assertEqual(c3["reprova"], 1, status)
                self.assertEqual(c3["por_tipo"]["leitura"][campo], 1, status)
                self.assertEqual(C.main(["crit3", "--por-minuto", saida]), 1, status)

    def test_atrasada_2xx_e_so_aviso_exit_0(self):
        with tempfile.TemporaryDirectory() as d:
            p = self._com_atrasada(d, 200)
            with contextlib.redirect_stderr(io.StringIO()) as err:
                self.assertEqual(A.main([p, "--id", "T", "--saida", os.path.join(d, "pm.csv")]), 0)
        self.assertIn("atrasadas leitura", err.getvalue())

    def test_tipo_cai_no_cenario_quando_a_tag_nao_veio(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "k6.csv")
            with open(p, "w", encoding="utf-8", newline="") as f:
                f.write("metric_name,timestamp,metric_value,status,name,scenario,extra_tags" + chr(10))
                f.write("http_req_duration,100,5,200,/x,leitura,tipo=leitura&min=0" + chr(10))
                f.write("dropped_iterations,101,1,,,leitura," + chr(10))
                f.write("dropped_iterations,101,1,,,setup," + chr(10))
            _, _, _, linhas = rodar(p)
        self.assertEqual(len(linhas), 2)
        self.assertEqual(linhas[1][:3] + [linhas[1][14]], ["0", "leitura", "0", "1"])

    def _com_reserva_400(self, d):
        p = os.path.join(d, "k6.csv")
        with open(p, "w", encoding="utf-8", newline="") as f:
            f.write("metric_name,timestamp,metric_value,status,name,extra_tags\n")
            f.write("http_req_duration,100,5,400,/bff/event-wallets,tipo=reserva&fase=pico&min=0\n")
            for m, motivo, v in ((0, "lotado", 1), (0, "lotado", 1), (1, "lotado", 1), (1, "vinculo", 1), (3, "outro", 1), (3, "outro", 1)):
                f.write("reserva_400,%d,%d,,/bff/event-wallets,tipo=reserva&fase=pico&min=%d&motivo=%s\n" % (100 + 60 * m, v, m, motivo))
        return p

    def test_reserva_400_soma_por_motivo_e_nao_vira_requisicao_nem_altera_o_por_minuto(self):
        with tempfile.TemporaryDirectory() as d:
            p = self._com_reserva_400(d)
            _, _, _, linhas = rodar(p)
            sem = os.path.join(d, "sem.csv")
            with open(p, encoding="utf-8") as f, open(sem, "w", encoding="utf-8", newline="") as g:
                g.writelines(l for l in f if not l.startswith("reserva_400"))
            _, _, _, base = rodar(sem)
            motivos = dict.fromkeys(A.MOTIVOS_400, 0)
            A.agregar(p, io.StringIO(), motivos)
            self.assertEqual(motivos, {"lotado": 3, "vinculo": 1, "outro": 2})
            saida = os.path.join(d, "pm.csv")
            with contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(A.main([p, "--id", "T", "--saida", saida]), 0)
        self.assertEqual(linhas, base)                              # o por-minuto e identico sem as linhas reserva_400
        self.assertEqual(linhas[0], A.COLUNAS)
        self.assertEqual([l for l in linhas[1:] if l[1] == "reserva"], [["0", "reserva", "0", "1", "5.000", "5.000", "5.000", "0", "0", "1", "0", "0", "0", "0", "0", "0", "100", "0"]])
        self.assertIn("reserva 400 por motivo: lotado=3 vinculo=1 outro=2", out.getvalue().splitlines())

    def test_sem_reserva_400_o_resumo_sai_zerado(self):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            A.main([FIXTURE, "--id", "T", "--saida", os.devnull])
        self.assertIn("reserva 400 por motivo: lotado=0 vinculo=0 outro=0", out.getvalue().splitlines())

    def test_cli_grava_o_arquivo(self):
        with tempfile.TemporaryDirectory() as d:
            saida = os.path.join(d, "soak-T-por-minuto.csv")
            self.assertEqual(A.main([FIXTURE, "--id", "T", "--saida", saida]), 0)
            with open(saida, encoding="utf-8", newline="") as f:
                conteudo = f.read()
        self.assertNotIn("\r", conteudo)
        self.assertEqual(conteudo.count("\n"), 6)


if __name__ == "__main__":
    unittest.main(verbosity=2)
