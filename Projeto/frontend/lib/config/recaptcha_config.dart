// Configuração do Google reCAPTCHA v3
// Site Key (pública) - usada no frontend
// Carregada do .env via const String.fromEnvironment()
const String recaptchaSiteKey = String.fromEnvironment(
  'RECAPTCHA_SITE_KEY',
  defaultValue: '', // Será carregado do .env
);

// Path (relativo ao AppConfig.apiBaseUrl) para validar reCAPTCHA no backend
const String recaptchaVerifyEndpoint = '/bff/users/verify-recaptcha';

