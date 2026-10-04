# CLAUDE.md — Frontend (Flutter Web)

App web em Flutter, servido como estáticos por Nginx em produção. Leia também o CLAUDE.md da
raiz (regras gerais) e o de `Backend/` (contrato da API).

## Estrutura

```
lib/
├── main.dart                app + MyApp + Provider HomePageData; checa token no boot
├── config/
│   ├── app_config.dart      AppConfig.apiBaseUrl (--dart-define API_BASE_URL, default vazio)
│   └── recaptcha_config.dart RECAPTCHA_SITE_KEY (--dart-define) + path de verify-recaptcha
├── telas/
│   ├── auth.dart            ApiAuth: token JWT em memória + SharedPreferences
│   ├── modals.dart          SnackBars padronizados (showError/showSuccess/...)
│   ├── telaHomePage.dart    landing pública (pré-login)
│   ├── telaLogin / telaCadastro / telaVerificacaoEmail
│   ├── telaPrincipal.dart   dashboard pós-login
│   ├── telaCriacaoEvento / telaEdicaoEvento / telaInscricaoEvento
│   ├── telaGerenciarEventos.dart  abas "Inscritos" / "Criados"
│   ├── telaPesquisarEvento.dart
│   └── telaEdicaoPerfil.dart
├── helpers/date_input_formatter.dart
└── widgets/recaptcha_widget.dart
```

`blocoInteiro.dart` (na raiz de `frontend/`, fora de `lib/`) é um **monólito legado de
referência** — não é compilado, não edite.

## Rodar

- Via Docker: `docker compose -f Projeto/docker-compose.yml up -d --build frontend`
  — Nginx na porta `8088` (container `:80`), container `gerenciador-frontend`.
- Build local: `flutter build web --release --dart-define=API_BASE_URL=<url> --dart-define=RECAPTCHA_SITE_KEY=<key>`
- Dev: `flutter run -d chrome --dart-define=API_BASE_URL=http://localhost:8081` (aponta
  direto pro backend, sem o proxy do Nginx — precisa do `backend` rodando em `:8081` com
  CORS liberado pra origem do `flutter run`, ver `Backend/CLAUDE.md`).
- Sem lockfile versionado; `flutter pub get` resolve pelo `pubspec.yaml`.
  Flutter 3.19.6 / Dart SDK `>=3.3.0 <4.0.0` (ver `Dockerfile`).

## Como fala com o backend

- Superfície HTTP única sob `/bff/**` (issue #78) — todas as chamadas seguem o mesmo padrão:
  `Uri.parse('${AppConfig.apiBaseUrl}/bff/...')`. Não existe mais divisão entre chamada
  relativa e absoluta: é sempre esse padrão, o que muda é só o valor de `apiBaseUrl`.
- `AppConfig.apiBaseUrl` (`API_BASE_URL`, `--dart-define`, `String.fromEnvironment`) tem
  **default vazio** (`''`). Vazio ⇒ path fica relativo (`/bff/...`), resolvido contra a
  origem da página — em produção (`docker-compose.yml` não passa mais `args.API_BASE_URL`,
  usa o default do `Dockerfile`) isso cai atrás do proxy do Nginx em `:8088`. Setado (ex.:
  `flutter run --dart-define=API_BASE_URL=http://localhost:8081`) ⇒ aponta direto pro
  backend, sem Nginx (uso em dev). **Mudar exige rebuild da imagem** — não é runtime.
- **Nginx faz proxy de `/bff/` → `backend:8081/bff/` SEM rewrite/strip** (`nginx.conf`): o
  path chega igual no backend, ex. `/bff/users/login` → `/bff/users/login`.
- JWT: `ApiAuth.jsonHeaders()` injeta `Authorization: Bearer <token>`. O token fica em
  memória (`ApiAuth.token`) e, se persistido, em `SharedPreferences` (`jwt_token`).
  `main.dart` tenta `GET ${AppConfig.apiBaseUrl}/bff/users/me` no boot pra restaurar a
  sessão; token inválido é limpo. "Lembrar de mim" também cacheia `email`/`password` em
  `SharedPreferences`.

## Estado e UI

- Estado global via `provider` — `HomePageData` (`ChangeNotifier`) guarda dados do usuário,
  nome do site e listas de evento. Atualize via `notifyListeners()` após login/edição.
- Feedback ao usuário sempre pelos helpers de `telas/modals.dart`, não `ScaffoldMessenger`
  cru — mantém cor/estilo consistentes.
- Material Design (`uses-material-design: true`). Textos em PT-BR.
- Datas: `intl` + `helpers/date_input_formatter.dart` para máscara de input.

## reCAPTCHA v3

`widgets/recaptcha_widget.dart` + `config/recaptcha_config.dart`. A **site key** entra por
`--dart-define=RECAPTCHA_SITE_KEY`; o backend valida o token contra a Google. Site key é
pública, mas mesmo assim não hardcode — mantenha via dart-define.

## Ao mexer aqui

- Novo endpoint consumido → confira o path real no `Backend/CLAUDE.md` (sempre `/bff/...`) e
  chame como `Uri.parse('${AppConfig.apiBaseUrl}/bff/...')`, igual ao resto do código.
- Mudou `API_BASE_URL`/dart-define → rebuild da imagem (`up -d --build frontend`).
- Não introduza pacote de state management novo (fica com `provider`).
