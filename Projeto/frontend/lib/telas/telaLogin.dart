// ==============================================================================
// TELA DE LOGIN
// ==============================================================================
// Função: Autenticação de usuários no sistema
// 
// Funcionalidades:
// - Formulário de login com email e senha
// - Validação de credenciais via API (/bff/users/login)
// - Checkbox "Lembrar de mim" para persistência de sessão
// - Geração e armazenamento de token JWT (persistente se "Lembrar" marcado)
// - Recuperação de senha (TODO - não implementado)
// - Link para tela de cadastro
// - SnackBars para feedback de sucesso/erro
// - Navegação automática para tela principal após login bem-sucedido
// ==============================================================================

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:http/http.dart' as http;
import 'dart:convert';

import 'telaHomePage.dart'; // HomePageData está aqui
import 'telaPrincipal.dart'; // MainScreen
import 'telaCadastro.dart'; // RegisterScreen
import 'auth.dart';
import 'modals.dart';
import '../config/app_config.dart';
import '../widgets/recaptcha_widget.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  bool _rememberMe = false;
  bool _showPassword = false;
  final _usernameController = TextEditingController();
  final _passwordController = TextEditingController();
  bool _isLoading = false;
  bool _recaptchaValidated = false;
  
  // Mensagem de erro inline
  String? _loginError;

  @override
  void initState() {
    super.initState();
    // Carregar credenciais cacheadas se existirem
    _loadCachedCredentials();
  }

  Future<void> _loadCachedCredentials() async {
    final cached = await ApiAuth.getCachedCredentials();
    if (cached != null && mounted) {
      setState(() {
        _usernameController.text = cached['email'] ?? '';
        _passwordController.text = cached['password'] ?? '';
        _rememberMe = true;
      });

    }
  }

  Future<void> _login() async {
    // Validar reCAPTCHA primeiro
    if (!_recaptchaValidated) {
      setState(() {
        _loginError = 'Por favor, complete a verificação de segurança';
      });
      return;
    }

    // Limpar erro anterior
    setState(() {
      _loginError = null;
      _isLoading = true;
    });

    try {
      final response = await http.post(
        Uri.parse('${AppConfig.apiBaseUrl}/bff/users/login'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({
          'email': _usernameController.text,
          'password': _passwordController.text,
        }),
      );

      setState(() {
        _isLoading = false;
      });

      if (response.statusCode == 200) {
        // Backend retorna: {user: {...}, token: "..."}
        final data = jsonDecode(response.body);
        final user = data['user'];
        final token = data['token'];

        // SEMPRE salvar token JWT (independente do checkbox)
        await ApiAuth.setToken(token);

        // Salvar credenciais APENAS se "Lembrar de mim" estiver marcado
        await ApiAuth.setCachedCredentials(
          _usernameController.text,
          _passwordController.text,
          persist: _rememberMe
        );

        // Salvar dados do usuário no Provider
        if (!mounted) return;
        Provider.of<HomePageData>(context, listen: false).setUser(user);

        // Mostrar modal de sucesso
        AppModals.showLoginSuccess(
          context,
          user['user_name'] ?? user['email'] ?? 'Usuário',
          onOk: () {
            Navigator.pushReplacement(
              context,
              MaterialPageRoute(builder: (context) => const MainScreen()),
            );
          },
        );
      } else if (response.statusCode == 400 || response.statusCode == 401) {
        // Erro de autenticação - mostrar inline abaixo do email
        if (!mounted) return;
        setState(() {
          _loginError = 'Email ou senha incorretos.';
        });
      } else {
        // Erro genérico do servidor
        if (!mounted) return;
        AppModals.showError(
          context,
          'Erro no Servidor',
          'Ocorreu um erro inesperado (código ${response.statusCode}). Tente novamente mais tarde.',
        );
      }
    } catch (e) {
      setState(() {
        _isLoading = false;
      });
      
      if (!mounted) return;
      AppModals.showConnectionError(context);
    }
  }

  void _showForgotPasswordModal(BuildContext context) {
    showDialog(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          title: Row(
            children: [
              const Icon(Icons.lock_reset, color: Colors.purple),
              const SizedBox(width: 10),
              const Text('Recuperação de Senha'),
            ],
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Funcionalidade em Desenvolvimento',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 16),
              const Text(
                'A recuperação de senha ainda não foi implementada.',
                style: TextStyle(fontSize: 14),
              ),
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.blue.shade50,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.blue.shade200),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.info_outline, size: 20, color: Colors.blue.shade700),
                        const SizedBox(width: 8),
                        Text(
                          'Próximas Funcionalidades:',
                          style: TextStyle(
                            fontWeight: FontWeight.bold,
                            color: Colors.blue.shade700,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    const Text('• Envio de email com token de recuperação'),
                    const Text('• Link temporário para redefinir senha'),
                    const Text('• Validação de token com expiração'),
                  ],
                ),
              ),
              const SizedBox(height: 12),
              const Text(
                'Por enquanto, entre em contato com o administrador do sistema para recuperar sua senha.',
                style: TextStyle(
                  fontSize: 12,
                  fontStyle: FontStyle.italic,
                  color: Colors.grey,
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Entendi'),
            ),
          ],
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final HomePageData homePageData = Provider.of<HomePageData>(context);

    return Scaffold(
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            _TopNavigationBarLogin(homePageData: homePageData),
            const SizedBox(height: 20.0),
            const Text(
              'Login',
              style: TextStyle(fontSize: 32.0, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 20.0),
            // Mensagem de erro acima do campo de email
            if (_loginError != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 12.0),
                child: Text(
                  _loginError!,
                  style: const TextStyle(
                    color: Colors.red,
                    fontSize: 14.0,
                  ),
                ),
              ),
            const Text('Email'),
            TextField(
              controller: _usernameController,
              decoration: const InputDecoration(
                hintText: 'Digite seu email',
                border: OutlineInputBorder(),
              ),
              onChanged: (value) {
                if (_loginError != null) {
                  setState(() => _loginError = null);
                }
              },
            ),
            const SizedBox(height: 20.0),
            const Text('Senha'),
            TextField(
              controller: _passwordController,
              obscureText: !_showPassword,
              decoration: InputDecoration(
                hintText: 'Senha',
                border: const OutlineInputBorder(),
                suffixIcon: IconButton(
                  icon: Icon(
                    _showPassword ? Icons.visibility : Icons.visibility_off,
                    color: Colors.grey,
                  ),
                  onPressed: () {
                    setState(() {
                      _showPassword = !_showPassword;
                    });
                  },
                ),
              ),
            ),
            const SizedBox(height: 20.0),
            RecaptchaWidget(
              action: 'login',
              onRecaptchaChanged: (isValid) {
                setState(() {
                  _recaptchaValidated = isValid;
                });
              },
            ),
            const SizedBox(height: 20.0),

            SizedBox(
              width: 150.0,
              child: ElevatedButton(
                onPressed: _isLoading ? null : _login,
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.black,
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(vertical: 12.0),
                ),
                child: _isLoading
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(
                          color: Colors.white,
                          strokeWidth: 2.0,
                        ),
                      )
                    : const Text('Logar'),
              ),
            ),
            const SizedBox(height: 10.0),
            Row(
              children: <Widget>[
                Checkbox(
                  value: _rememberMe,
                  onChanged: (bool? value) {
                    setState(() {
                      _rememberMe = value ?? false;
                    });
                  },
                ),
                const Text('Lembrar de mim'),
              ],
            ),
            TextButton(
              onPressed: () {
                _showForgotPasswordModal(context);
              },
              child: const Text(
                'Esqueci minha senha',
                style: TextStyle(color: Colors.purple),
              ),
            ),
            TextButton(
              onPressed: () {
                Navigator.push(
                  context,
                  MaterialPageRoute(builder: (context) => const RegisterScreen()),
                );
              },
              child: const Text(
                'Não tem conta? Cadastre-se',
                style: TextStyle(color: Colors.purple),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _TopNavigationBarLogin extends StatelessWidget {
  final HomePageData homePageData;
  const _TopNavigationBarLogin({required this.homePageData});

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 8.0),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.start,
          children: <Widget>[
            Text(
              homePageData.siteName,
              style: const TextStyle(
                fontWeight: FontWeight.bold,
                fontSize: 18.0,
              ),
            ),
          ],
        ),
      ),
    );
  }
}