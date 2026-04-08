// ==============================================================================
// TELA DE CADASTRO DE USUÁRIO
// ==============================================================================
// Função: Registro de novos usuários no sistema
// 
// Funcionalidades:
// - Formulário de cadastro com nome, email, senha e confirmação
// - Validações de campos (nome não vazio, email válido, senha mínima 6 caracteres)
// - Verificação de senha igual à confirmação
// - Criação de usuário via API (/api/bff/users)
// - Hash de senha com BCrypt no backend
// - SnackBars para feedback de sucesso/erro
// - Tratamento de erros (email duplicado, dados inválidos, erro de conexão)
// - Navegação para tela de login após cadastro bem-sucedido
// - Link para voltar à tela de login
// ==============================================================================

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'dart:convert';
import 'package:flutter/services.dart';
import 'telaLogin.dart'; // Para LoginScreen
import 'telaVerificacaoEmail.dart'; // Para TelaVerificacaoEmail
import 'modals.dart'; // Modais

// Classe para formatar telefone no padrão (XX)XXXXX-XXXX
class PhoneInputFormatter extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) {
    final text = newValue.text;
    
    if (text.isEmpty) {
      return newValue;
    }
    
    // Remove tudo que não é número
    final digitsOnly = text.replaceAll(RegExp(r'\D'), '');
    
    // Limita a 11 dígitos
    if (digitsOnly.length > 11) {
      return oldValue;
    }
    
    // Formata de acordo com quantidade de dígitos
    String formatted = '';
    
    if (digitsOnly.length <= 2) {
      formatted = digitsOnly;
    } else if (digitsOnly.length <= 7) {
      formatted = '(${digitsOnly.substring(0, 2)})${digitsOnly.substring(2)}';
    } else {
      formatted = '(${digitsOnly.substring(0, 2)})${digitsOnly.substring(2, 7)}-${digitsOnly.substring(7)}';
    }
    
    return TextEditingValue(
      text: formatted,
      selection: TextSelection.collapsed(offset: formatted.length),
    );
  }
}

class RegisterScreen extends StatefulWidget {
  const RegisterScreen({super.key});

  @override
  State<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends State<RegisterScreen> {
  final _nameController = TextEditingController();
  final _emailController = TextEditingController();
  final _phoneController = TextEditingController();
  final _birthdateController = TextEditingController();
  final _passwordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();
  bool _isLoading = false;
  
  // Mensagens de erro para cada campo
  String? _nameError;
  String? _emailError;
  String? _phoneError;
  String? _birthdateError;
  String? _passwordError;
  String? _confirmPasswordError;

  Future<void> _register() async {
    // Limpar erros anteriores
    setState(() {
      _nameError = null;
      _emailError = null;
      _phoneError = null;
      _birthdateError = null;
      _passwordError = null;
      _confirmPasswordError = null;
    });

    // Validações com mensagens inline
    bool hasError = false;

    if (_nameController.text.isEmpty) {
      setState(() => _nameError = 'Por favor, preencha seu nome completo.');
      hasError = true;
    }

    if (_emailController.text.isEmpty) {
      setState(() => _emailError = 'Por favor, insira seu e-mail.');
      hasError = true;
    } else if (!_emailController.text.contains('@') || !_emailController.text.contains('.')) {
      setState(() => _emailError = 'Insira um e-mail válido (exemplo: user@domain.com).');
      hasError = true;
    }

    if (_phoneController.text.isEmpty) {
      setState(() => _phoneError = 'Por favor, insira seu telefone.');
      hasError = true;
    } else if (_phoneController.text.replaceAll(RegExp(r'\D'), '').length < 10) {
      setState(() => _phoneError = 'Telefone deve ter pelo menos 10 dígitos.');
      hasError = true;
    } else if (_phoneController.text.replaceAll(RegExp(r'\D'), '').length > 11) {
      setState(() => _phoneError = 'Telefone não pode exceder 11 dígitos.');
      hasError = true;
    }

    if (_birthdateController.text.isEmpty || _birthdateController.text.length < 10) {
      setState(() => _birthdateError = 'Por favor, selecione sua data de nascimento (DD/MM/YYYY).');
      hasError = true;
    }

    if (_passwordController.text.isEmpty || _passwordController.text.length < 6) {
      setState(() => _passwordError = 'A senha deve ter pelo menos 6 caracteres.');
      hasError = true;
    }

    if (_passwordController.text != _confirmPasswordController.text) {
      setState(() => _confirmPasswordError = 'As senhas digitadas não coincidem.');
      hasError = true;
    }

    if (hasError) return;

    setState(() {
      _isLoading = true;
    });

    try {
      // Converte DD/MM/YYYY para YYYY-MM-DD
      final dateStr = _birthdateController.text.trim(); // DD/MM/YYYY
      
      if (dateStr.length != 10 || !dateStr.contains('/')) {
        throw Exception('Formato de data inválido: $dateStr');
      }
      
      final dateParts = dateStr.split('/');
      if (dateParts.length != 3) {
        throw Exception('Data deveria ter 3 partes, mas tem ${dateParts.length}');
      }
      
      final birthDateFormatted = '${dateParts[2]}-${dateParts[1]}-${dateParts[0]}'; // YYYY-MM-DD
      
      final userData = {
        'name': _nameController.text,
        'email': _emailController.text,
        'fone': _phoneController.text.replaceAll(RegExp(r'\D'), ''), // Remove máscara
        'birthdate': birthDateFormatted, // Formato YYYY-MM-DD (padrão banco)
        'password': _passwordController.text,
      };

      // Chama endpoint de request-verification (Etapa 1)
      final response = await http.post(
        Uri.parse('http://localhost:8081/api/users/request-verification'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode(userData),
      ).timeout(
        const Duration(seconds: 10),
        onTimeout: () {
          throw Exception('Timeout na requisição');
        },
      );

      setState(() {
        _isLoading = false;
      });

      if (response.statusCode == 201) {
        // Requisição de verificação bem-sucedida
        if (!mounted) return;
        
        // Navega para tela de verificação de email
        Navigator.push(
          context,
          MaterialPageRoute(
            builder: (context) => TelaVerificacaoEmail(email: _emailController.text),
          ),
        );
      } else if (response.statusCode == 400) {
        // Erro de validação do backend
        if (!mounted) return;
        String errorMsg = 'Verifique os dados inseridos.';
        try {
          errorMsg = response.body;
        } catch (_) {}
        
        // Mostra erro em modal ao invés de tentar adivinhar qual campo
        AppModals.showRegisterError(
          context,
          'Erro na validação: $errorMsg',
        );
      } else {
        // Erro genérico do servidor
        if (!mounted) return;
        AppModals.showRegisterError(
          context,
          'Erro no servidor (código ${response.statusCode}). Tente novamente mais tarde.',
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

  Future<void> _selectBirthdate() async {
    final DateTime? picked = await showDatePicker(
      context: context,
      initialDate: DateTime(2000),
      firstDate: DateTime(1900),
      lastDate: DateTime.now(),
      builder: (context, child) {
        return Theme(
          data: ThemeData.light().copyWith(
            colorScheme: const ColorScheme.light(
              primary: Colors.purple,
              onPrimary: Colors.white,
            ),
          ),
          child: child ?? const SizedBox(),
        );
      },
    );
    
    if (picked != null && mounted) {
      setState(() {
        _birthdateController.text = '${picked.day.toString().padLeft(2, '0')}/${picked.month.toString().padLeft(2, '0')}/${picked.year}';
        _birthdateError = null;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () {
            Navigator.pop(context);
          },
        ),
        title: const Text('Cadastro'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            const SizedBox(height: 20.0),
            const Text(
              'Cadastro',
              style: TextStyle(fontSize: 32.0, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 20.0),

            const Text('Nome completo'),
            TextField(
              controller: _nameController,
              decoration: InputDecoration(
                hintText: 'Seu nome completo',
                border: const OutlineInputBorder(),
                errorText: _nameError,
              ),
              onChanged: (value) {
                if (_nameError != null && value.isNotEmpty) {
                  setState(() => _nameError = null);
                }
              },
            ),
            const SizedBox(height: 20.0),

            const Text('Email'),
            TextField(
              controller: _emailController,
              decoration: InputDecoration(
                hintText: 'seuemail@example.com',
                border: OutlineInputBorder(
                  borderSide: BorderSide(
                    color: _emailError != null ? Colors.red : Colors.grey,
                    width: _emailError != null ? 2 : 1,
                  ),
                ),
                errorText: _emailError,
                errorStyle: const TextStyle(color: Colors.red, fontSize: 12),
                suffixIcon: _emailError != null
                    ? const Icon(Icons.error, color: Colors.red)
                    : (_emailController.text.isNotEmpty && _emailController.text.contains('@') && _emailController.text.contains('.')
                        ? const Icon(Icons.check_circle, color: Colors.green)
                        : null),
              ),
              keyboardType: TextInputType.emailAddress,
              onChanged: (value) {
                if (_emailError != null && value.contains('@') && value.contains('.')) {
                  setState(() => _emailError = null);
                } else {
                  setState(() {});
                }
              },
            ),
            const SizedBox(height: 20.0),

            const Text('Telefone'),
            TextField(
              controller: _phoneController,
              decoration: InputDecoration(
                hintText: '(00) 00000-0000',
                border: const OutlineInputBorder(),
                errorText: _phoneError,
              ),
              keyboardType: TextInputType.phone,
              inputFormatters: [PhoneInputFormatter()],
              onChanged: (value) {
                if (_phoneError != null && value.replaceAll(RegExp(r'\D'), '').length >= 10) {
                  setState(() => _phoneError = null);
                }
              },
            ),
            const SizedBox(height: 20.0),

            const Text('Data de Nascimento'),
            TextField(
              controller: _birthdateController,
              readOnly: true,
              decoration: InputDecoration(
                hintText: 'DD/MM/YYYY',
                border: OutlineInputBorder(
                  borderSide: BorderSide(
                    color: _birthdateError != null ? Colors.red : Colors.grey,
                    width: _birthdateError != null ? 2 : 1,
                  ),
                ),
                errorText: _birthdateError,
                errorStyle: const TextStyle(color: Colors.red, fontSize: 12),
                suffixIcon: _birthdateError != null
                    ? const Icon(Icons.error, color: Colors.red)
                    : IconButton(
                        icon: const Icon(Icons.calendar_today),
                        onPressed: _selectBirthdate,
                      ),
              ),
              onTap: _selectBirthdate,
            ),
            const SizedBox(height: 20.0),

            const Text('Senha'),
            TextField(
              controller: _passwordController,
              obscureText: true,
              decoration: InputDecoration(
                hintText: 'Senha',
                border: const OutlineInputBorder(),
                errorText: _passwordError,
              ),
              onChanged: (value) {
                if (_passwordError != null && value.length >= 6) {
                  setState(() => _passwordError = null);
                }
              },
            ),
            const SizedBox(height: 20.0),

            const Text('Confirmar senha'),
            TextField(
              controller: _confirmPasswordController,
              obscureText: true,
              decoration: InputDecoration(
                hintText: 'Confirme sua senha',
                border: const OutlineInputBorder(),
                errorText: _confirmPasswordError,
              ),
              onChanged: (value) {
                if (_confirmPasswordError != null && value == _passwordController.text) {
                  setState(() => _confirmPasswordError = null);
                }
              },
            ),
            const SizedBox(height: 20.0),

            SizedBox(
              width: double.infinity,
              child: ElevatedButton(
                onPressed: _isLoading ? null : _register,
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.black,
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(vertical: 12.0),
                ),
                child: _isLoading
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(
                          color: Colors.white,
                          strokeWidth: 2.0,
                        ),
                      )
                    : const Text('Completar cadastro'),
              ),
            ),
            const SizedBox(height: 10.0),

            TextButton(
              onPressed: () {
                Navigator.push(
                  context,
                  MaterialPageRoute(builder: (context) => const LoginScreen()),
                );
              },
              child: const Text(
                'Já tem uma conta? Faça login',
                style: TextStyle(color: Colors.purple),
              ),
            ),
          ],
        ),
      ),
    );
  }
}