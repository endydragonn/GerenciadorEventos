"""Auto-teste do criterios.py e do amostra.py (issue #232): pre-checks do cluster vivo, criterios 1 a 3 e a amostra do kubectl. Sem cluster, sem rede.
Uso:  python criterios_selftest.py"""
import copy
import os
import sys
import tempfile
import unittest

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
import amostra as AM  # noqa: E402
import criterios as C  # noqa: E402


def deploy(nome, replicas=1, prontas=1, env=None, container=None):
    return {"kind": "Deployment", "metadata": {"name": nome}, "spec": {"replicas": replicas, "template": {"spec": {"containers": [
        {"name": container or nome, "env": env or []}]}}}, "status": {"readyReplicas": prontas}}


def so(nome, alvo=None, target=None):
    spec = {"scaleTargetRef": {"name": alvo or nome}}
    if target is not None:
        spec["triggers"] = [{"type": "metrics-api", "metadata": {"targetValue": target}}]
    return {"kind": "ScaledObject", "metadata": {"name": nome}, "spec": spec}


def env_soak():
    # a API do Kubernetes omite `value` vazio: MAIL_PASSWORD="" chega SEM value
    return [{"name": "MAIL_HOST", "value": "mailpit"}, {"name": "MAIL_PORT", "value": "1025"}, {"name": "MAIL_SMTP_AUTH", "value": "false"},
            {"name": "MAIL_STARTTLS", "value": "false"}, {"name": "MAIL_USERNAME", "value": "soak@example.com"}, {"name": "MAIL_PASSWORD"}]


def cluster_soak_valido():
    return {"kind": "List", "items": [
        deploy("notification", env=env_soak()),
        deploy("stock", env=[{"name": "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "value": "245"}]),
        deploy("mailpit", env=[{"name": "MP_MAX_MESSAGES", "value": "250000"}]),
        deploy("backend"), deploy("reservation"),
        so("backend"), so("reservation"), so("notification")]}


class Pre(unittest.TestCase):
    def test_cluster_soak_valido(self):
        self.assertEqual(C.pre_checks(cluster_soak_valido(), 60000), [])

    def test_cluster_base_sem_mailpit_no_notification_aborta(self):
        c = cluster_soak_valido()
        c["items"][0] = deploy("notification", env=[{"name": "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "value": "3"}])
        erros = C.pre_checks(c, 60000)
        self.assertTrue(any("MAIL_HOST" in e for e in erros))
        self.assertTrue(any("MAIL_PASSWORD" in e and "ausente" in e for e in erros))

    def test_mail_password_ausente_nao_e_vazio(self):
        # o env sem a entrada MAIL_PASSWORD (a senha real do Secret vale) reprova; com a entrada sem `value` (vazia) passa
        c = cluster_soak_valido()
        c["items"][0] = deploy("notification", env=env_soak()[:-1])
        self.assertTrue(any("MAIL_PASSWORD" in e for e in C.pre_checks(c, 1)))

    def test_mail_password_de_valuefrom_aborta(self):
        c = cluster_soak_valido()
        env = env_soak()[:-1] + [{"name": "MAIL_PASSWORD", "valueFrom": {"secretKeyRef": {"name": "env-notification", "key": "MAIL_PASSWORD"}}}]
        c["items"][0] = deploy("notification", env=env)
        self.assertTrue(any("MAIL_PASSWORD" in e and "valueFrom" in e for e in C.pre_checks(c, 1)))

    def test_mail_password_com_valor_aborta(self):
        c = cluster_soak_valido()
        c["items"][0] = deploy("notification", env=env_soak()[:-1] + [{"name": "MAIL_PASSWORD", "value": "x"}])
        self.assertTrue(any("MAIL_PASSWORD" in e for e in C.pre_checks(c, 1)))

    def test_sem_231_scaledobject_do_stock(self):
        c = cluster_soak_valido()
        c["items"].append(so("stock"))
        self.assertTrue(any("ScaledObject do stock" in e for e in C.pre_checks(c, 1)))

    def test_sem_231_hikari_60(self):
        c = cluster_soak_valido()
        c["items"][1] = deploy("stock", env=[{"name": "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "value": "60"}])
        self.assertTrue(any("Hikari" in e and "245" in e for e in C.pre_checks(c, 1)))

    def test_replica_diferente_de_1(self):
        c = cluster_soak_valido()
        c["items"][3] = deploy("backend", replicas=2, prontas=2)
        self.assertTrue(any("backend" in e and "replicas=2" in e for e in C.pre_checks(c, 1)))
        c = cluster_soak_valido()
        c["items"][4] = deploy("reservation", replicas=1, prontas=0)
        self.assertTrue(any("reservation" in e and "prontas=0" in e for e in C.pre_checks(c, 1)))

    def test_total_de_cadastros_passa_do_mp_max_messages(self):
        c = cluster_soak_valido()
        self.assertTrue(any("MP_MAX_MESSAGES" in e for e in C.pre_checks(c, 125001)))  # x2 > 250000
        self.assertEqual(C.pre_checks(c, 125000), [])

    def test_sem_mp_max_messages(self):
        c = cluster_soak_valido()
        c["items"][2] = deploy("mailpit", env=[])
        self.assertTrue(any("MP_MAX_MESSAGES" in e for e in C.pre_checks(c, 1)))


class PreGatilho(unittest.TestCase):
    """Decisao C: ADMISSION_X_RPM por bloco (S1 56000, S2 4000); o alvo do KEDA do reservation e 0,5 x X."""

    def cluster(self, alvo):
        c = cluster_soak_valido()
        c["items"] = [i for i in c["items"] if i["metadata"]["name"] != "reservation" or i["kind"] != "ScaledObject"] + [so("reservation", target=alvo)]
        return c

    def test_x_por_bloco(self):
        self.assertEqual(C.X_DO_BLOCO, {"S1": 56000, "S2": 4000})
        self.assertEqual((C.alvo_keda(56000), C.alvo_keda(4000)), ("28000", "2000"))

    def test_s1_e_s2_validos(self):
        self.assertEqual(C.pre_checks(self.cluster("28000"), 1, x_esperado=56000, x_vivo=56000), [])
        self.assertEqual(C.pre_checks(self.cluster("2000"), 1, x_esperado=4000, x_vivo=4000), [])

    def test_x_vivo_diferente_do_bloco_aborta(self):
        erros = C.pre_checks(self.cluster("28000"), 1, x_esperado=4000, x_vivo=56000)
        self.assertTrue(any("ADMISSION_X_RPM=56000" in e and "4000" in e for e in erros))

    def test_alvo_do_keda_diferente_de_meio_x_aborta(self):
        erros = C.pre_checks(self.cluster("28000"), 1, x_esperado=4000, x_vivo=4000)
        self.assertTrue(any("targetValue" in e and "2000" in e for e in erros))

    def test_x_vivo_nao_lido_aborta(self):
        self.assertTrue(any("nao lido" in e for e in C.pre_checks(self.cluster("2000"), 1, x_esperado=4000, x_vivo=None)))

    def test_pod_do_admission_anterior_ao_secret_aborta(self):
        erros = C.pre_checks(self.cluster("2000"), 1, x_esperado=4000, x_vivo=4000, admission_antigo=True)
        self.assertTrue(any("rollout restart" in e for e in erros))

    def test_sem_bloco_nao_confere_o_gatilho(self):
        self.assertEqual(C.pre_checks(cluster_soak_valido(), 60000), [])


class PreSpringMail(unittest.TestCase):
    def com_env(self, extra):
        c = cluster_soak_valido()
        c["items"][0] = deploy("notification", env=env_soak() + extra)
        return C.pre_checks(c, 1)

    def test_spring_mail_host_no_env_aborta(self):
        self.assertTrue(any("SPRING_MAIL_HOST" in e for e in self.com_env([{"name": "SPRING_MAIL_HOST", "value": "smtp.gmail.com"}])))

    def test_spring_application_json_com_spring_mail_aborta(self):
        e = self.com_env([{"name": "SPRING_APPLICATION_JSON", "value": '{"spring.mail.host":"smtp.gmail.com"}'}])
        self.assertTrue(any("SPRING_APPLICATION_JSON" in x and "spring.mail" in x for x in e))

    def test_java_tool_options_com_spring_mail_aborta(self):
        for nome in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"):
            e = self.com_env([{"name": nome, "value": "-Dspring.mail.host=smtp.gmail.com"}])
            self.assertTrue(any(nome in x for x in e), nome)

    def test_java_tool_options_da_trilha_c_passa(self):
        self.assertEqual(self.com_env([{"name": "JAVA_TOOL_OPTIONS", "value": "-Djava.security.properties=/etc/java-security/java.security"}]), [])

    def test_valuefrom_em_variavel_de_opcoes_aborta(self):
        e = self.com_env([{"name": "SPRING_APPLICATION_JSON", "valueFrom": {"secretKeyRef": {"name": "x", "key": "y"}}}])
        self.assertTrue(any("valueFrom" in x for x in e))

    def test_chave_spring_mail_no_secret_aborta(self):
        c = cluster_soak_valido()
        for chaves in (["MAIL_HOST", "spring.mail.host"], ["SPRING_MAIL_HOST"], ["JAVA_TOOL_OPTIONS"]):
            self.assertTrue(C.pre_checks(c, 1, chaves_secret=chaves), chaves)
        self.assertEqual(C.pre_checks(c, 1, chaves_secret=["MAIL_HOST", "MAIL_PASSWORD"]), [])


class Criterio3(unittest.TestCase):
    def linhas(self):
        return [{"minuto": "0", "tipo": "leitura", "c5xx": "0", "c0": "0", "inicio_s": "1000"}, {"minuto": "1", "tipo": "reserva", "c5xx": "2", "c0": "0", "inicio_s": "1060"},
                {"minuto": "1", "tipo": "mailpit", "c5xx": "9", "c0": "9", "inicio_s": "1060"}, {"minuto": "2", "tipo": "login", "c5xx": "1", "c0": "3", "inicio_s": "1120"}]

    def test_5xx_e_status_0_fora_do_mailpit(self):
        c3 = C.criterio3(self.linhas())
        self.assertEqual((c3["reprova"], c3["c5xx"], c3["c0"]), (6, 3, 3))
        self.assertEqual(c3["por_tipo"], {"leitura": {"c5xx": 0, "c0": 0}, "reserva": {"c5xx": 2, "c0": 0}, "login": {"c5xx": 1, "c0": 3}})
        self.assertEqual([(m["minuto"], m["tipo"]) for m in c3["minutos"]], [(1, "reserva"), (2, "login")])
        self.assertEqual(C.criterio3([{"minuto": "0", "tipo": "mailpit", "c5xx": "5", "c0": "5"}])["reprova"], 0)

    def test_so_status_0_reprova_por_padrao(self):
        self.assertTrue(C.C0_REPROVA)
        self.assertEqual(C.criterio3([{"minuto": "0", "tipo": "leitura", "c5xx": "0", "c0": "4"}])["reprova"], 4)

    def test_trocar_para_so_registrar_e_uma_constante(self):
        l = [{"minuto": "0", "tipo": "leitura", "c5xx": "0", "c0": "4"}]
        self.assertEqual(C.criterio3(l, c0_reprova=False)["reprova"], 0)
        antes = C.C0_REPROVA
        try:
            C.C0_REPROVA = False
            self.assertEqual(C.criterio3(l)["reprova"], 0)
            self.assertEqual(C.criterio3(l)["c0"], 4)   # continua contado, so nao reprova
        finally:
            C.C0_REPROVA = antes

    def test_csv_antigo_sem_c0_nem_inicio(self):
        c3 = C.criterio3([{"minuto": "0", "tipo": "leitura", "c5xx": "1"}])
        self.assertEqual((c3["reprova"], c3["minutos"][0]["inicio_s"]), (1, None))

    def pods(self):
        def p(t, pod, app, r=0, term=0):
            return {"epoch_s": str(t), "pod": pod, "app": app, "pronto": "1", "restarts": str(r), "terminando": str(term)}
        return [p(1050, "res-a", "reservation"), p(1050, "res-b", "reservation"), p(1050, "bk-1", "backend"),
                p(1065, "res-a", "reservation"), p(1065, "res-b", "reservation", term=1), p(1065, "bk-1", "backend"),
                p(1070, "res-a", "reservation"), p(1070, "bk-1", "backend"),
                p(1130, "res-a", "reservation"), p(1130, "bk-1", "backend", r=1)]

    def test_saidas_de_pods(self):
        self.assertEqual(C.saidas_de_pods(self.pods()), [(1065.0, "reservation", "res-b", "terminando"), (1070.0, "reservation", "res-b", "sumiu"), (1130.0, "backend", "bk-1", "reiniciou")])

    def test_relatorio_liga_minuto_a_pod_que_saiu_com_horario_servico_e_nome(self):
        c3 = C.criterio3(self.linhas())
        txt = "\n".join(C.relatorio_erros(c3, self.pods()))
        self.assertIn("minuto 1", txt)
        self.assertIn("pod saiu as %s: servico reservation, pod res-b (terminando)" % C.utc(1065), txt)
        self.assertIn("pod res-b (sumiu)", txt)
        # o minuto 2 (login, inicio 1120): o backend reiniciou as 1130 -> aparece nele, nao no minuto 1
        i2 = txt.index("minuto 2")
        self.assertIn("servico backend, pod bk-1 (reiniciou)", txt[i2:])
        self.assertNotIn("bk-1", txt[:i2])

    def test_relatorio_sem_pod_na_janela_diz_que_nao_e_troca_de_replica(self):
        c3 = C.criterio3([{"minuto": "5", "tipo": "leitura", "c5xx": "0", "c0": "2", "inicio_s": "5000"}])
        txt = "\n".join(C.relatorio_erros(c3, self.pods()))
        self.assertIn("nenhum pod saiu", txt)

    def test_relatorio_sem_arquivo_de_pods(self):
        txt = "\n".join(C.relatorio_erros(C.criterio3(self.linhas()), None))
        self.assertIn("sem soak-ID-pods.csv", txt)

    def test_cli_reprova_por_status_0_e_imprime_o_relatorio(self):
        import contextlib
        import csv
        import io
        with tempfile.TemporaryDirectory() as d:
            pm, pd = os.path.join(d, "pm.csv"), os.path.join(d, "pods.csv")
            with open(pm, "w", encoding="utf-8", newline="") as f:
                w = csv.DictWriter(f, ["minuto", "tipo", "c5xx", "c0", "inicio_s"])
                w.writeheader()
                w.writerows(self.linhas())
            with open(pd, "w", encoding="utf-8", newline="") as f:
                w = csv.DictWriter(f, ["epoch_s", "pod", "app", "pronto", "restarts", "terminando"])
                w.writeheader()
                w.writerows(self.pods())
            with contextlib.redirect_stdout(io.StringIO()) as out:
                rc = C.main(["crit3", "--por-minuto", pm, "--pods", pd])
        self.assertEqual(rc, 1)
        self.assertIn("pod res-b (terminando)", out.getvalue())
        self.assertIn("status 0", out.getvalue())
        with tempfile.TemporaryDirectory() as d:
            pm = os.path.join(d, "pm.csv")
            with open(pm, "w", encoding="utf-8", newline="") as f:
                f.write("minuto,tipo,c5xx,c0,inicio_s\n0,leitura,0,0,1000\n")
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(C.main(["crit3", "--por-minuto", pm]), 0)


class Fila(unittest.TestCase):
    def linhas(self, c202, c429):
        return [{"tipo": "reserva", "c202": str(c202), "c429": str(c429)}, {"tipo": "leitura", "c202": "0", "c429": "7"}]

    def test_s2_exige_202_e_so_registra_429(self):
        self.assertEqual(C.criterio_fila(self.linhas(10, 0), "S2"), (True, 10, 0))
        self.assertEqual(C.criterio_fila(self.linhas(10, 5), "S2"), (True, 10, 5))
        self.assertEqual(C.criterio_fila(self.linhas(0, 5), "S2")[0], False)

    def test_s1_so_registra(self):
        self.assertEqual(C.criterio_fila(self.linhas(0, 0), "S1"), (True, 0, 0))
        self.assertEqual(C.criterio_fila(self.linhas(3, 0), "S1")[0], True)


def serie(servico, pontos, passo=5):
    """pontos: lista de (metrica, alvo, replicas) a cada `passo` s."""
    return [{"epoch_s": str(1000 + i * passo), "servico": servico, "metrica": repr(m) if m is not None else "", "alvo": repr(a) if a is not None else "", "replicas": str(r), "prontas": str(r)}
            for i, (m, a, r) in enumerate(pontos)]


class Criterio2(unittest.TestCase):
    def test_sobe_em_ate_60s(self):
        # metrica 80 > alvo 50 x 1,1 por 2 minutos; a replica nova aparece no 4o ponto (15 s)
        pts = [(30, 50, 1)] * 3 + [(80, 50, 1)] * 3 + [(80, 50, 2)] * 20
        r = C.criterio2(serie("catalog", pts))
        self.assertEqual((r["catalog"]["subidas"], r["catalog"]["violacoes"]), (1, []))

    def test_nao_sobe_em_60s_viola(self):
        pts = [(30, 50, 1)] * 3 + [(80, 50, 1)] * 30
        r = C.criterio2(serie("catalog", pts))
        self.assertEqual(len(r["catalog"]["violacoes"]), 1)
        self.assertIn("nenhuma replica nova", r["catalog"]["violacoes"][0])

    def test_acima_por_menos_de_60s_nao_e_avaliado(self):
        pts = [(30, 50, 1)] * 3 + [(80, 50, 1)] * 5 + [(30, 50, 1)] * 20
        r = C.criterio2(serie("catalog", pts))
        self.assertEqual((r["catalog"]["subidas"], r["catalog"]["violacoes"]), (0, []))

    def test_dentro_da_tolerancia_do_hpa_nao_conta(self):
        pts = [(54, 50, 1)] * 40  # 8% acima: o HPA nao escala (tolerancia 10%)
        self.assertEqual(C.criterio2(serie("catalog", pts))["catalog"]["violacoes"], [])

    def test_no_maximo_nao_exige_subida(self):
        pts = [(90, 50, 3)] * 40
        r = C.criterio2(serie("catalog", pts))
        self.assertEqual((r["catalog"]["subidas"], r["catalog"]["violacoes"]), (0, []))

    def test_desce_para_1_em_ate_5min(self):
        # 3 replicas, carga total 3 x 10 = 30 <= alvo 50: ao longo de 6 min cai 3 -> 2 -> 1 (em 240 s)
        pts = [(10, 50, 3)] * 24 + [(15, 50, 2)] * 12 + [(30, 50, 1)] * 36
        r = C.criterio2(serie("identity", pts))
        self.assertEqual((r["identity"]["descidas"], r["identity"]["violacoes"]), (1, []))

    def test_nao_desce_em_5min_viola(self):
        pts = [(10, 50, 3)] * 80  # 400 s abaixo, sempre 3 replicas
        r = C.criterio2(serie("identity", pts))
        self.assertEqual(len(r["identity"]["violacoes"]), 1)
        self.assertIn("sem voltar a 1", r["identity"]["violacoes"][0])

    def test_metrica_por_pod_baixa_mas_carga_total_nao_cabe_em_1_nao_exige_descida(self):
        # 2 replicas a 30 (alvo 50): 30% abaixo por pod, mas a carga total 60 > 50 -> o HPA fica em 2; nao e violacao
        pts = [(30, 50, 2)] * 80
        r = C.criterio2(serie("identity", pts))
        self.assertEqual((r["identity"]["descidas"], r["identity"]["violacoes"]), (0, []))

    def test_abaixo_por_menos_de_5min_nao_e_avaliado(self):
        pts = [(10, 50, 3)] * 30 + [(90, 50, 3)] * 5
        self.assertEqual(C.criterio2(serie("identity", pts))["identity"]["descidas"], 0)

    def test_stock_fica_em_1(self):
        ok = C.criterio2(serie("stock", [(None, None, 1)] * 10))
        self.assertEqual(ok["stock"]["violacoes"], [])
        ruim = C.criterio2(serie("stock", [(None, None, 1)] * 3 + [(None, None, 2)]))
        self.assertEqual(len(ruim["stock"]["violacoes"]), 1)

    def test_reservation_fica_fora_do_criterio_no_s1(self):
        pts = [(30, 50, 1)] * 3 + [(80, 50, 1)] * 30   # violaria (sem replica nova em 60 s)...
        self.assertEqual(len(C.criterio2(serie("reservation", pts))["reservation"]["violacoes"]), 1)
        self.assertNotIn("reservation", C.criterio2(serie("reservation", pts), fora=("reservation",)))   # ...mas no S1 fica fora

    def test_servico_sem_hpa_fica_fora(self):
        self.assertNotIn("frontend", C.criterio2(serie("frontend", [(None, None, 1)] * 10)))


def msg(to, quando, de="soak@example.com", id_="x"):
    return {"id": id_, "to": to, "de": de, "quando": quando}


class Emails(unittest.TestCase):
    def test_tudo_certo(self):
        ms = [msg("soak-b1-%d@example.com" % i, 1000 + i) for i in range(5)]
        r = C.criterio_emails(ms, 5, "b1", [])
        self.assertEqual((r["perdidos"], r["duplicadas_nao_toleradas"], r["from_ruins"], r["unicos"]), (0, [], [], 5))

    def test_email_perdido_sempre_reprova(self):
        ms = [msg("soak-b1-%d@example.com" % i, 1000 + i) for i in range(4)]
        self.assertEqual(C.criterio_emails(ms, 5, "b1", [1002])["perdidos"], 1)

    def test_duplicado_so_perto_de_um_pod_do_notification_saindo(self):
        ms = [msg("soak-b1-1@example.com", 1000), msg("soak-b1-1@example.com", 1030)]
        self.assertEqual(C.criterio_emails(ms, 1, "b1", [1070])["duplicadas_toleradas"], 1)  # 40 s do pod saindo
        r = C.criterio_emails(ms, 1, "b1", [1200])
        self.assertEqual(r["duplicadas_nao_toleradas"], ["soak-b1-1@example.com"])
        self.assertEqual(len(C.criterio_emails(ms, 1, "b1", [])["duplicadas_nao_toleradas"]), 1)

    def test_from_de_qualquer_mensagem_fora_de_example_com(self):
        ms = [msg("soak-b1-1@example.com", 1000), msg("outro@gmail.com", 1001, de="conta.real@gmail.com")]
        self.assertEqual(C.criterio_emails(ms, 1, "b1", [])["from_ruins"], ["conta.real@gmail.com"])

    def test_filtra_pelo_sufixo_do_bloco(self):
        ms = [msg("soak-b1-1@example.com", 1000), msg("soak-b2-1@example.com", 1001)]
        r = C.criterio_emails(ms, 1, "b1", [])
        self.assertEqual((r["total"], r["unicos"]), (1, 1))

    def test_sufixo_com_maiuscula_casa_com_o_endereco_em_minusculas(self):
        ms = [msg("soak-t1-1@example.com", 1000)]
        self.assertEqual(C.criterio_emails(ms, 1, "T1", [])["unicos"], 1)

    def test_extras_viram_aviso(self):
        ms = [msg("soak-b1-%d@example.com" % i, 1000 + i) for i in range(3)]
        self.assertEqual(C.criterio_emails(ms, 2, "b1", [])["extras"], 1)

    def test_parse_do_created_do_mailpit(self):
        self.assertEqual(C.parse_quando("2026-11-01T12:00:00Z"), 1793534400.0)
        self.assertEqual(C.parse_quando("2026-11-01T12:00:00.123456789Z"), 1793534400.123456)
        self.assertEqual(C.parse_quando("2026-11-01T09:00:00-03:00"), 1793534400.0)

    def test_saidas_do_notification(self):
        pods = [{"epoch_s": "100", "pod": "n1", "app": "notification", "pronto": "1", "restarts": "0", "terminando": "0"},
                {"epoch_s": "105", "pod": "n1", "app": "notification", "pronto": "1", "restarts": "0", "terminando": "1"},
                {"epoch_s": "110", "pod": "n1", "app": "notification", "pronto": "1", "restarts": "0", "terminando": "1"},
                {"epoch_s": "100", "pod": "n2", "app": "notification", "pronto": "1", "restarts": "0", "terminando": "0"},
                {"epoch_s": "120", "pod": "n2", "app": "notification", "pronto": "1", "restarts": "1", "terminando": "0"},
                {"epoch_s": "100", "pod": "m", "app": "mailpit", "pronto": "1", "restarts": "0", "terminando": "1"}]
        self.assertEqual(C.saidas_do_notification(pods), [105.0, 120.0])

    def test_mensagens_das_paginas(self):
        pg = {"messages": [{"ID": "a", "Created": "2026-11-01T12:00:00Z", "From": {"Address": "SOAK@example.com"}, "To": [{"Address": "Soak-B1-1@Example.com"}]}]}
        self.assertEqual(C.mensagens_das_paginas([pg])[0]["to"], "soak-b1-1@example.com")


class Amostra(unittest.TestCase):
    def lista(self):
        return {"kind": "List", "items": [
            {"kind": "HorizontalPodAutoscaler", "metadata": {"name": "keda-hpa-catalog"}, "spec": {"scaleTargetRef": {"name": "catalog"}, "metrics": [
                {"type": "Resource", "resource": {"name": "cpu", "target": {"type": "Utilization", "averageUtilization": 50}}}]},
             "status": {"currentMetrics": [{"type": "Resource", "resource": {"name": "cpu", "current": {"averageUtilization": 73}}}]}},
            {"kind": "HorizontalPodAutoscaler", "metadata": {"name": "keda-hpa-reservation"}, "spec": {"scaleTargetRef": {"name": "reservation"}, "metrics": [
                {"type": "External", "external": {"metric": {"name": "s0-redis"}, "target": {"type": "AverageValue", "averageValue": "28k"}}}]},
             "status": {"currentMetrics": [{"type": "External", "external": {"current": {"averageValue": "31500m"}}}]}},
            {"kind": "Deployment", "metadata": {"name": "catalog"}, "spec": {"replicas": 2}, "status": {"readyReplicas": 1}},
            {"kind": "Deployment", "metadata": {"name": "reservation"}, "spec": {"replicas": 1}, "status": {}},
            {"kind": "Deployment", "metadata": {"name": "stock"}, "spec": {"replicas": 1}, "status": {"readyReplicas": 1}},
            {"kind": "Pod", "metadata": {"name": "mailpit-abc", "labels": {"app": "mailpit"}},
             "status": {"conditions": [{"type": "Ready", "status": "True"}], "containerStatuses": [{"restartCount": 2}]}},
            {"kind": "Pod", "metadata": {"name": "notification-x", "labels": {"app": "notification"}, "deletionTimestamp": "2026-11-01T12:00:00Z"}, "status": {}}]}

    def test_quantidade(self):
        for q, v in (("23", 23.0), ("500m", 0.5), ("28k", 28000.0), ("2M", 2e6), ("", None), (None, None), ("x", None)):
            self.assertEqual(AM.quantidade(q), v, q)

    def test_amostrar(self):
        rep, pods = AM.amostrar(self.lista(), 1000)
        por = {l[1]: l for l in rep}
        self.assertEqual(por["catalog"], [1000, "catalog", "73.0", "50.0", 2, 1])
        self.assertEqual(por["reservation"], [1000, "reservation", "31.5", "28000.0", 1, 0])
        self.assertEqual(por["stock"], [1000, "stock", "", "", 1, 1])
        self.assertEqual(pods, [[1000, "mailpit-abc", "mailpit", 1, 2, 0], [1000, "notification-x", "notification", 0, 0, 1]])

    def test_metrica_do_hpa_container_resource(self):
        # KEDA com sidecar (Istio) gera o HPA de CPU como ContainerResource (chave `containerResource`)
        hpa = {"spec": {"metrics": [{"containerResource": {"container": "identity", "name": "cpu", "target": {"averageUtilization": 50, "type": "Utilization"}}, "type": "ContainerResource"}]},
               "status": {"currentMetrics": [{"containerResource": {"container": "identity", "current": {"averageUtilization": 1, "averageValue": "9m"}, "name": "cpu"}, "type": "ContainerResource"}]}}
        self.assertEqual(AM.metrica_do_hpa(hpa), (1.0, 50.0))
        del hpa["status"]["currentMetrics"]
        self.assertEqual(AM.metrica_do_hpa(hpa), (None, None))

    def test_main_acrescenta_com_cabecalho_uma_vez(self):
        import io
        with tempfile.TemporaryDirectory() as d:
            prefixo = os.path.join(d, "soak-T")
            import json
            for ep in (1000, 1005):
                sys.stdin = io.StringIO(json.dumps(self.lista()))
                self.assertEqual(AM.main(["--prefixo", prefixo, "--epoch", str(ep)]), 0)
            sys.stdin = sys.__stdin__
            with open(prefixo + "-replicas.csv", encoding="utf-8") as f:
                linhas = f.read().splitlines()
        self.assertEqual(linhas[0], "epoch_s,servico,metrica,alvo,replicas,prontas")
        self.assertEqual(len(linhas), 1 + 2 * 3)

    def test_o_csv_da_amostra_alimenta_o_criterio_2(self):
        rep, _ = AM.amostrar(self.lista(), 1000)
        linhas = [dict(zip(["epoch_s", "servico", "metrica", "alvo", "replicas", "prontas"], [str(x) for x in l])) for l in rep]
        self.assertIn("catalog", C.criterio2(linhas))


if __name__ == "__main__":
    unittest.main(verbosity=2)
