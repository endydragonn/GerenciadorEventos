// ==============================================================================
// CONFIGURAÇÃO GLOBAL DA APLICAÇÃO
// ==============================================================================
// API_BASE_URL entra em tempo de build via --dart-define. Vazio (default) faz o app
// chamar a API por caminho relativo (/bff/...), atrás do proxy do Nginx em produção
// (docker-compose não define mais o arg -> usa este default). Para apontar direto pro
// backend em dev sem Nginx: --dart-define=API_BASE_URL=http://localhost:8081
// (ver frontend/CLAUDE.md).

const String API_BASE_URL = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: '',
);

class AppConfig {
  static const String apiBaseUrl = API_BASE_URL;
}
