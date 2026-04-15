// ==============================================================================
// UTILITÁRIO DE AUTENTICAÇÃO JWT
// ==============================================================================
// Função: Gerenciar token JWT e cabeçalhos de autenticação
// 
// Funcionalidades:
// - Armazenamento de token JWT (memória + localStorage via SharedPreferences)
// - initialize(): Carrega token salvo ao iniciar app
// - setToken(token, persist): Salva token (persistente se persist=true)
// - clearToken(): Remove token (logout)
// - jsonHeaders(): Retorna headers HTTP com Authorization Bearer
// 
// Comportamento:
// - Se "Lembrar de mim" = true: token persiste após refresh (localStorage)
// - Se "Lembrar de mim" = false: token apenas na sessão atual (memória)
// ==============================================================================

// Utilitário para JWT no frontend com persistência
import 'package:shared_preferences/shared_preferences.dart';

class ApiAuth {
  static String? _token;
  static const String _tokenKey = 'jwt_token';
  static const String _emailKey = 'cached_email';
  static const String _passwordKey = 'cached_password';

  // Inicializar: carregar token salvo
  static Future<void> initialize() async {
    final prefs = await SharedPreferences.getInstance();
    
    _token = prefs.getString(_tokenKey);
  }

  // Salvar token SEMPRE (independente do checkbox)
  static Future<void> setToken(String? token) async {
    _token = token;
    final prefs = await SharedPreferences.getInstance();
    
    if (token != null && token.isNotEmpty) {
      await prefs.setString(_tokenKey, token);
    } else {
      await prefs.remove(_tokenKey);
    }
  }

  // Salvar credenciais para "Lembrar de mim"
  static Future<void> setCachedCredentials(String email, String password, {bool persist = true}) async {
    final prefs = await SharedPreferences.getInstance();
    
    if (persist) {
      await prefs.setString(_emailKey, email);
      await prefs.setString(_passwordKey, password);

    } else {
      await prefs.remove(_emailKey);
      await prefs.remove(_passwordKey);
    }
  }

  // Obter credenciais cacheadas
  static Future<Map<String, String>?> getCachedCredentials() async {
    final prefs = await SharedPreferences.getInstance();
    final email = prefs.getString(_emailKey);
    final password = prefs.getString(_passwordKey);
    
    if (email != null && password != null) {
  
      return {'email': email, 'password': password};
    }
    
    return null;
  }

  // Limpar credenciais cacheadas
  static Future<void> clearCachedCredentials() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_emailKey);
    await prefs.remove(_passwordKey);
  }

  // Limpar token (logout)
  static Future<void> clearToken() async {
    _token = null;
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_tokenKey);
  }

  static String? get token => _token;

  static Map<String, String> jsonHeaders({Map<String, String>? extra}) {
    final headers = <String, String>{'Content-Type': 'application/json'};
    if (_token != null && _token!.isNotEmpty) {
      headers['Authorization'] = 'Bearer $_token';
    }
    if (extra != null) headers.addAll(extra);
    return headers;
  }
}
