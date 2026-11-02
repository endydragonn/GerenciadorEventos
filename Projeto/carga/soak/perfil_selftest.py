"""Auto-teste do perfil.py (issue #232). Sem k6, sem cluster, sem rede. Uso:  python perfil_selftest.py   (ou  python -m unittest perfil_selftest)"""
import hashlib
import os
import sys
import tempfile
import unittest
from fractions import Fraction

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import perfil as P  # noqa: E402


def integral_dos_stages(stages):
    """Integral da taxa pelos stages do k6 (trapezio, de 0 RPM), independente do `nos_da_taxa`: iteracoes totais."""
    total, prev = Fraction(0), 0
    for s in stages:
        d = int(s["duration"][:-1])
        total += Fraction(prev + s["target"], 2) * d / 60
        prev = s["target"]
    return total


class Perfil(unittest.TestCase):
    def test_mesma_semente_mesmos_bytes(self):
        a = P.serializar(P.gerar_perfil("S1", 42, 35000, 7200))
        b = P.serializar(P.gerar_perfil("S1", 42, 35000, 7200))
        self.assertEqual(a, b)

    def test_mesma_semente_mesmos_bytes_em_arquivo_e_sem_cr(self):
        with tempfile.TemporaryDirectory() as d1, tempfile.TemporaryDirectory() as d2:
            P.main(["--id", "S1", "--semente", "7", "--teto", "35000", "--saida", d1])
            P.main(["--id", "S1", "--semente", "7", "--teto", "35000", "--saida", d2])
            with open(os.path.join(d1, "perfil-S1.json"), "rb") as f:
                x = f.read()
            with open(os.path.join(d2, "perfil-S1.json"), "rb") as f:
                y = f.read()
        self.assertEqual(x, y)
        self.assertNotIn(b"\r", x)
        self.assertTrue(x.endswith(b"\n"))

    def test_semente_diferente_perfil_diferente(self):
        self.assertNotEqual(P.serializar(P.gerar_perfil("S1", 42, 35000, 7200)), P.serializar(P.gerar_perfil("S1", 43, 35000, 7200)))

    def test_sem_timestamp_nem_caminho(self):
        txt = P.serializar(P.gerar_perfil("S1", 42, 35000, 7200)).decode()
        self.assertNotRegex(txt, r"\d{4}-\d\d-\d\d")  # data
        for proibido in ("\\\\", "/home", "C:", "tmp"):
            self.assertNotIn(proibido, txt)

    def test_tetos_somam_o_teto_do_bloco(self):
        for teto in (35000, 50000, 3000, 1234, 777, 100):
            t = P.tetos_por_tipo(teto)
            self.assertEqual(sum(t.values()), teto, teto)
        self.assertEqual(P.tetos_por_tipo(35000), {"leitura": 21000, "reserva": 10500, "login": 2450, "cadastro": 700, "escrita": 350})

    def test_soma_dos_picos_coincidentes_nao_passa_do_teto_e_chega_nele(self):
        for semente in (1, 42, 99):
            p = P.gerar_perfil("S", semente, 50000, 7200)
            # se todos estivessem no pico maximo (100% do teto do tipo), a soma seria o teto do bloco
            self.assertEqual(sum(t["teto_rpm"] for t in p["tipos"].values()), 50000)
            for tipo, t in p["tipos"].items():
                for f in t["fases"]:
                    if f["pico"]:
                        self.assertGreaterEqual(f["rpm"], int(0.7 * t["teto_rpm"]))
                        self.assertLessEqual(f["rpm"], t["teto_rpm"])
                    else:
                        self.assertLessEqual(abs(f["rpm"] - max(1, round(0.1 * t["teto_rpm"]))), 1)
            # soma dos picos coincidentes (cada tipo no maximo do pico dele) <= teto
            self.assertLessEqual(sum(max(f["rpm"] for f in t["fases"]) for t in p["tipos"].values()), 50000)

    def test_fases_cobrem_a_duracao_sem_buraco_e_dentro_dos_limites(self):
        for dur in (7200, 600, 1234, 5400):
            p = P.gerar_perfil("S", 5, 35000, dur)
            for tipo, t in p["tipos"].items():
                fases = t["fases"]
                self.assertEqual(fases[0]["inicio_s"], 0)
                self.assertEqual(fases[-1]["fim_s"], dur, (tipo, dur))
                for a, b in zip(fases, fases[1:]):
                    self.assertEqual(a["fim_s"], b["inicio_s"])
                for f in fases:
                    d = f["fim_s"] - f["inicio_s"]
                    self.assertGreaterEqual(d, P.FASE_MIN_S, (tipo, dur))
                    self.assertLessEqual(d, P.FASE_MAX_S, (tipo, dur))
                    self.assertTrue(P.RAMPA_MIN_S <= f["rampa_s"] <= P.RAMPA_MAX_S)
                soma_stages = sum(int(s["duration"][:-1]) for s in t["stages"])
                self.assertEqual(soma_stages, dur)

    def test_pico_e_vale_meio_a_meio(self):
        picos = total = 0
        for semente in range(1, 21):
            p = P.gerar_perfil("S", semente, 35000, 7200)
            for t in p["tipos"].values():
                for f in t["fases"]:
                    total += 1
                    picos += f["pico"]
        self.assertTrue(0.4 < picos / total < 0.6, picos / total)

    def test_total_de_cadastros_bate_com_a_integral_da_taxa(self):
        for semente, teto, dur in ((42, 35000, 7200), (7, 50000, 7200), (3, 3000, 600), (11, 2000, 1234)):
            p = P.gerar_perfil("S", semente, teto, dur)
            integral = integral_dos_stages(p["tipos"]["cadastro"]["stages"])  # outro caminho de calculo (stages do k6)
            self.assertEqual(p["total_cadastros_esperado"], -((-integral.numerator) // integral.denominator))  # teto
            soma_minutos = sum(m["cadastro"]["rpm"] for m in p["por_minuto"][: dur // 60])
            soma_minutos += sum(m["cadastro"]["rpm"] * (dur % 60) / 60 for m in p["por_minuto"][dur // 60:])
            self.assertAlmostEqual(soma_minutos, float(integral), delta=0.001 * len(p["por_minuto"]) + 0.01)

    def test_por_minuto_tem_os_cinco_tipos_e_o_pico(self):
        p = P.gerar_perfil("S", 42, 35000, 7200)
        self.assertEqual(len(p["por_minuto"]), 120)
        for i, lin in enumerate(p["por_minuto"]):
            self.assertEqual(lin["minuto"], i)
            for tipo in P.TIPOS:
                self.assertIn("rpm", lin[tipo])
                self.assertIsInstance(lin[tipo]["pico"], bool)
        # a taxa media de um minuto no meio de um patamar e a taxa do patamar
        t = p["tipos"]["leitura"]["fases"][0]
        m = (t["fim_s"] - 60) // 60 - 1  # um minuto inteiro dentro do patamar da 1a fase
        self.assertEqual(p["por_minuto"][m]["leitura"]["rpm"], t["rpm"])
        self.assertEqual(p["por_minuto"][m]["leitura"]["pico"], t["pico"])

    def test_duracao_curta_do_dry_run(self):
        p = P.gerar_perfil("dry", 1, 1000, 600)
        self.assertEqual(len(p["por_minuto"]), 10)
        for t in p["tipos"].values():
            self.assertEqual(t["fases"][-1]["fim_s"], 600)

    def test_entradas_invalidas(self):
        with self.assertRaises(ValueError):
            P.gerar_perfil("S", 1, 50, 7200)
        with self.assertRaises(ValueError):
            P.gerar_perfil("S", 1, 35000, 30)


class PerfilFixo(unittest.TestCase):
    """Perfil fixo do dry-run do S2 (`--fixo dry-s2`): deterministico sem semente, com subidas bruscas vale -> pico na reserva."""

    def _perfil(self, teto=20000):
        return P.gerar_perfil_fixo("dryS2", "dry-s2", teto)

    def test_byte_a_byte_igual_e_sem_semente(self):
        self.assertEqual(P.serializar(self._perfil()), P.serializar(self._perfil()))
        with tempfile.TemporaryDirectory() as d1, tempfile.TemporaryDirectory() as d2:
            self.assertEqual(P.main(["--id", "dryS2", "--teto", "20000", "--fixo", "dry-s2", "--saida", d1]), 0)
            self.assertEqual(P.main(["--id", "dryS2", "--teto", "20000", "--fixo", "dry-s2", "--saida", d2]), 0)
            with open(os.path.join(d1, "perfil-dryS2.json"), "rb") as f:
                x = f.read()
            with open(os.path.join(d2, "perfil-dryS2.json"), "rb") as f:
                y = f.read()
        self.assertEqual(x, y)
        self.assertNotIn(b"\r", x)
        self.assertEqual(x, P.serializar(self._perfil()))
        # os bytes nao mudam por acidente (muda a tabela FIXOS = muda este hash, de proposito)
        self.assertEqual(hashlib.sha256(x).hexdigest(), "61f367d18fcd344227412c5dd76e8e7252d8b4a379de9f6f829ff9972e996e9c")

    def test_fixo_nao_aceita_semente_nem_outra_duracao(self):
        for extra in (["--semente", "3"], ["--duracao", "7200"]):
            with tempfile.TemporaryDirectory() as d, self.assertRaises(SystemExit):
                P.main(["--id", "x", "--teto", "20000", "--fixo", "dry-s2", "--saida", d] + extra)

    def test_sem_fixo_a_semente_continua_obrigatoria(self):
        with tempfile.TemporaryDirectory() as d, self.assertRaises(SystemExit):
            P.main(["--id", "x", "--teto", "20000", "--saida", d])

    def test_perfil_sorteado_nao_mudou_com_o_refactor(self):
        # hashes tirados do perfil.py ANTES do --fixo: o caminho da semente nao pode mudar um byte
        for args, esperado in ((("S1", 42, 35000, 7200), "acd4ac4578a26c1867ab7ffcac2596429ad1de2cc564494c9fcc752b0fa5bc5b"),
                                (("S", 7, 50000, 7200), "511e75a034ad0d778966c2cd09563d16109b647349eeb94942f52c80b3072ccf"),
                                (("dry", 1, 3000, 600), "1b0a70d2b75e1fc562591b619a4cf961f18ae8eb444a8259dfd820f8ade14479")):
            self.assertEqual(hashlib.sha256(P.serializar(P.gerar_perfil(*args))).hexdigest(), esperado, args)
        self.assertNotIn("fixo", P.gerar_perfil("S", 1, 35000, 7200))

    def test_duracao_e_tetos(self):
        p = self._perfil()
        self.assertEqual((p["duracao_s"], p["semente"], p["fixo"]), (600, 0, "dry-s2"))
        self.assertEqual(sum(t["teto_rpm"] for t in p["tipos"].values()), 20000)
        for tipo, t in p["tipos"].items():
            self.assertEqual(t["fases"][0]["inicio_s"], 0)
            self.assertEqual(t["fases"][-1]["fim_s"], 600)
            self.assertEqual(sum(int(s["duration"][:-1]) for s in t["stages"]), 600, tipo)

    def test_reserva_tem_pelo_menos_2_subidas_bruscas_de_vale_para_pico(self):
        t = self._perfil()["tipos"]["reserva"]
        self.assertEqual(t["teto_rpm"], 6000)
        fases = t["fases"]
        subidas = [(a, b) for a, b in zip(fases, fases[1:]) if not a["pico"] and b["pico"]]
        # a 1a subida parte do vale da 1a fase: o vale que abre o bloco tambem conta (de 0 ao vale, depois ao pico)
        self.assertGreaterEqual(len(subidas), 2)
        for a, b in subidas:
            self.assertEqual(a["rpm"], 600)    # vale = 10% do teto do tipo
            self.assertEqual(b["rpm"], 6000)   # pico = 100% do teto do tipo
            self.assertLessEqual(b["rampa_s"], 30)  # rampa curta: a taxa quase dobra a cada segundo
        # confirmado pelos stages do k6 (outro caminho): uma rampa de <= 30 s do vale ao pico, ao menos 2 vezes
        st, prev, n = t["stages"], 0, 0
        for s in st:
            if prev == 600 and s["target"] == 6000 and int(s["duration"][:-1]) <= 30:
                n += 1
            prev = s["target"]
        self.assertGreaterEqual(n, 2)

    def test_hot_da_reserva_no_pico_passa_do_limiar_da_fila_com_r1(self):
        # S2: X = 4000, 95% da reserva no quente; a fila liga com hot >= 0,8 * X * R(=1) = 3200 e o pico chega a 0,95 * 6000 = 5700
        pico = max(f["rpm"] for f in self._perfil()["tipos"]["reserva"]["fases"])
        self.assertGreater(0.95 * pico, 3200)
        vale = min(f["rpm"] for f in self._perfil()["tipos"]["reserva"]["fases"])
        self.assertLess(0.95 * vale, 0.7 * 4000)  # no vale a fila desliga (0,7 * X * R)


class PerfilUmTipoSo(unittest.TestCase):
    """`--so cadastro` (dry do Mailpit): so um tipo, com as mesmas fases/stages que ele tem no perfil completo da mesma semente/teto/duracao (o ritmo do S2)."""

    def test_cadastro_igual_ao_do_perfil_completo(self):
        cheio = P.gerar_perfil("S2", 257867, 50000, 7200)
        so = P.gerar_perfil("dryMailpit", 257867, 50000, 7200, so="cadastro")
        self.assertEqual(so["so"], "cadastro")
        self.assertEqual(list(so["tipos"]), ["cadastro"])
        self.assertEqual(so["tipos"]["cadastro"], cheio["tipos"]["cadastro"])
        self.assertEqual(so["total_cadastros_esperado"], cheio["total_cadastros_esperado"])
        self.assertEqual((so["semente"], so["teto_rpm"], so["duracao_s"]), (257867, 50000, 7200))
        self.assertEqual(len(so["por_minuto"]), 120)
        for s_, c_ in zip(so["por_minuto"], cheio["por_minuto"]):
            self.assertEqual(s_, {"minuto": c_["minuto"], "cadastro": c_["cadastro"]})

    def test_perfil_completo_nao_ganha_a_chave_so_e_o_s2_real_nao_muda_de_bytes(self):
        cheio = P.gerar_perfil("S2", 257867, 50000, 7200)
        self.assertNotIn("so", cheio)
        with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "results", "perfil-S2.json"), "rb") as f:
            self.assertEqual(P.serializar(cheio), f.read())

    def test_main_so_grava_bytes_iguais_e_o_resumo_diz_o_total(self):
        import contextlib
        import io
        with tempfile.TemporaryDirectory() as d1, tempfile.TemporaryDirectory() as d2:
            args = ["--id", "dryMailpit", "--semente", "257867", "--teto", "50000", "--duracao", "7200", "--so", "cadastro"]
            with contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(P.main(args + ["--saida", d1]), 0)
                self.assertEqual(P.main(args + ["--saida", d2]), 0)
            with open(os.path.join(d1, "perfil-dryMailpit.json"), "rb") as f:
                x = f.read()
            with open(os.path.join(d2, "perfil-dryMailpit.json"), "rb") as f:
                y = f.read()
        self.assertEqual(x, y)
        self.assertNotIn(b"\r", x)
        self.assertIn("so cadastro", out.getvalue())
        self.assertIn("cadastros esperados 54649", out.getvalue())

    def test_so_invalido_e_so_com_fixo_sao_recusados(self):
        with tempfile.TemporaryDirectory() as d:
            for extra in (["--so", "nada"], ["--fixo", "dry-s2", "--so", "cadastro"]):
                with self.assertRaises(SystemExit):
                    P.main(["--id", "x", "--semente", "1", "--teto", "50000", "--saida", d] + extra)


if __name__ == "__main__":
    unittest.main(verbosity=2)
