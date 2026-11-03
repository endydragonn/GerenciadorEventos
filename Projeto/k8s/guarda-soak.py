# Guarda do overlay `soak` (issue #232), chamada pelo cluster.sh (`guardar_soak`) com o RENDER do kustomize em stdin: nenhum e-mail real pode sair.
# Confere, no `env` do Deployment `notification`, os seis valores do Mailpit (cada um UMA vez, `value` literal; MAIL_PASSWORD="" tem de ser o literal vazio, nunca ausente nem valueFrom:
# sem a entrada, a senha REAL do Secret env-notification vence pelo envFrom) e que o render tem exatamente 1 Deployment `mailpit` com MP_UI_AUTH (basic auth).
# Também barra, no env do notification, qualquer SPRING_MAIL_*, SPRING_APPLICATION_JSON / JAVA_TOOL_OPTIONS / JDK_JAVA_OPTIONS com spring.mail (valueFrom nelas também reprova: não dá para provar)
# e, com o arquivo env-notification (o Secret que vai ao cluster; argv[1], só as CHAVES são lidas), a chave spring.mail.* / SPRING_MAIL_* / as três de opções (envFrom: o relaxed binding venceria o MAIL_HOST).
# Sai com 1 e imprime o que faltou/errou (só nomes e valores esperados, que não são segredo); 0 se está tudo certo.
import re
import sys

ESPERADO = [("MAIL_HOST", "mailpit"), ("MAIL_PORT", "1025"), ("MAIL_SMTP_AUTH", "false"), ("MAIL_STARTTLS", "false"),
            ("MAIL_USERNAME", "soak@example.com"), ("MAIL_PASSWORD", "")]


def kind(d):
    m = re.search(r"^kind: (\S+)$", d, re.M)
    return m.group(1) if m else ""


def nome(d):
    m = re.search(r"^metadata:\n(?:  .*\n)*?  name: (\S+)$", d, re.M)
    return m.group(1) if m else ""


def entradas_do_env(doc):
    """nome da variável -> lista de blocos (as linhas mais indentadas que o `- name: X`, sem espaços nas pontas)."""
    linhas = doc.split("\n")
    entradas = {}
    i = 0
    while i < len(linhas):
        m = re.match(r"^(\s*)- name: (\S+)\s*$", linhas[i])
        if not m:
            i += 1
            continue
        ind = len(m.group(1))
        j = i + 1
        bloco = []
        while j < len(linhas) and linhas[j].strip() and len(linhas[j]) - len(linhas[j].lstrip()) > ind:
            bloco.append(linhas[j].strip())
            j += 1
        entradas.setdefault(m.group(2), []).append(bloco)
        i = j
    return entradas


OPCOES = ("SPRING_APPLICATION_JSON", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")


def spring_mail_no_render(entradas):
    erros = []
    for nome in sorted(entradas):
        if re.match(r"(?i)^spring[._]mail", nome):
            erros.append("notification: %s no env do render (o relaxed binding venceria o MAIL_HOST e reabriria o SMTP real)" % nome)
        elif nome in OPCOES:
            for bloco in entradas[nome]:
                if any(l.startswith("valueFrom") for l in bloco):
                    erros.append("notification: %s vem de valueFrom (nao da para provar que nao leva spring.mail)" % nome)
                elif re.search(r"(?i)spring[._]mail", " ".join(bloco)):
                    erros.append("notification: %s contem spring.mail (venceria o MAIL_HOST e reabriria o SMTP real)" % nome)
    return erros


def spring_mail_no_secret(chaves):
    return ["Secret env-notification traz a chave %s (envFrom: venceria o MAIL_HOST e reabriria o SMTP real)" % k
            for k in chaves if re.match(r"(?i)^spring[._]mail", k) or k in OPCOES]


def conferir(render, chaves_secret=()):
    docs = render.replace("\r", "").split("\n---\n")
    dep = [d for d in docs if kind(d) == "Deployment"]
    erros = []
    mp = [d for d in dep if nome(d) == "mailpit"]
    if len(mp) != 1:
        erros.append("o render tem %d Deployment mailpit (esperado exatamente 1)" % len(mp))
    elif "name: MP_UI_AUTH" not in mp[0]:
        erros.append("o Deployment mailpit do render nao tem MP_UI_AUTH (basic auth)")
    nt = [d for d in dep if nome(d) == "notification"]
    if len(nt) != 1:
        erros.append("o render tem %d Deployment notification (esperado 1)" % len(nt))
        return erros
    entradas = entradas_do_env(nt[0])
    erros += spring_mail_no_render(entradas)
    erros += spring_mail_no_secret(chaves_secret)
    for var, esperado in ESPERADO:
        blocos = entradas.get(var, [])
        if len(blocos) != 1:
            erros.append("notification: %s aparece %d vez(es) no env do render (esperado 1)" % (var, len(blocos)))
            continue
        if any(l.startswith("valueFrom") for l in blocos[0]):
            erros.append("notification: %s vem de valueFrom (esperado o literal)" % var)
            continue
        vals = [l[len("value:"):].strip() for l in blocos[0] if l.startswith("value:")]
        if len(vals) != 1:
            erros.append("notification: %s sem `value` literal no render (esperado %r)" % (var, esperado))
            continue
        v = vals[0]
        if len(v) >= 2 and v[0] == v[-1] and v[0] in (chr(34), chr(39)):
            v = v[1:-1]
        if v != esperado:
            erros.append("notification: %s tem o valor errado no render (esperado %r)" % (var, esperado))
    return erros


if __name__ == "__main__":
    chaves = []
    if len(sys.argv) > 1:
        with open(sys.argv[1], encoding="utf-8") as f:
            chaves = [l.split("=", 1)[0].strip() for l in f if "=" in l]
    erros = conferir(sys.stdin.read(), chaves)
    for e in erros:
        print("   " + e)
    sys.exit(1 if erros else 0)
