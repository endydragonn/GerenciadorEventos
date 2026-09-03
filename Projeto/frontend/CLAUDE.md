# CLAUDE.md — Frontend (Flutter Web)

App web em Flutter, servido como estáticos por Nginx em produção. Leia também o CLAUDE.md da
raiz (regras gerais) e o de `Backend/` (contrato da API).

## Estrutura

```
lib/
├── main.dart                app + MyApp + Provider HomePageData; checa token no boot
├── config/
│   ├── app_config.dart      API_BASE_URL (--dart-define) + endpoints montados
│   └── recaptcha_config.dart RECAPTCHA_SITE_KEY (--dart-define)
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
  direto pro backend, sem o proxy do Nginx).
- Sem lockfile versionado; `flutter pub get` resolve pelo `pubspec.yaml`.
  Flutter 3.19.6 / Dart SDK `>=3.3.0 <4.0.0` (ver `Dockerfile`).

## Como fala com o backend

- `API_BASE_URL` entra **em tempo de build** via `--dart-define` (`String.fromEnvironment`).
  No `docker-compose.yml` é passado como `args.API_BASE_URL` pro Dockerfile. **Mudar exige
  rebuild da imagem** — não é runtime.
- **Nginx faz proxy de `/api/*` → `backend:8081` removendo o prefixo `/api/`**
  (`nginx.conf`). Ex.: o app chama `/api/bff/users/login`, o backend recebe `/bff/users/login`.
- Há inconsistência herdada: parte do código usa URL **relativa** (`/api/...`, funciona só
  atrás do Nginx) e parte usa `AppConfig.*Endpoint` (base absoluta + path). Ao criar chamada
  nova, siga o padrão do arquivo vizinho e prefira passar por `/api/...`.
- JWT: `ApiAuth.jsonHeaders()` injeta `Authorization: Bearer <token>`. O token fica em
  memória (`ApiAuth.token`) e, se persistido, em `SharedPreferences` (`jwt_token`).
  `main.dart` tenta `GET /api/bff/users/me` no boot pra restaurar a sessão; token inválido
  é limpo. "Lembrar de mim" também cacheia `email`/`password` em `SharedPreferences`.

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

- Novo endpoint consumido → confira o path real no `Backend/CLAUDE.md` (lembre do strip do
  `/api/` pelo Nginx) e adicione em `config/app_config.dart` se seguir esse padrão.
- Mudou `API_BASE_URL`/dart-define → rebuild da imagem (`up -d --build frontend`).
- Não introduza pacote de state management novo (fica com `provider`).
