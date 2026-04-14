// ==============================================================================
// CONFIGURAÇÃO GLOBAL DA APLICAÇÃO
// ==============================================================================
// Variáveis de ambiente definidas em tempo de build via Dockerfile
// Uso: flutter build web --release --dart-define=API_BASE_URL=http://localhost:8088

const String API_BASE_URL = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: 'http://localhost:8088',
);

class AppConfig {
  static const String apiBaseUrl = API_BASE_URL;
  
  // Endpoints da API
  static const String userVerificationEndpoint = '$apiBaseUrl/api/users/request-verification';
  static const String emailVerifyEndpoint = '$apiBaseUrl/api/users/verify-email';
  static const String registerEndpoint = '$apiBaseUrl/api/users';
  static const String loginEndpoint = '$apiBaseUrl/api/users/login';
  static const String meEndpoint = '$apiBaseUrl/api/bff/users/me';
}
