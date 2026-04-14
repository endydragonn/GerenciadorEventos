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
import 'telaVerificacaoEmail.dart'; // Para VerificacaoEmailModal
import 'modals.dart'; // Modais
import '../config/app_config.dart'; // Configuração da API
import '../widgets/recaptcha_widget.dart';

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

// Classe para formatar data no padrão DD/MM/YYYY
class DateInputFormatter extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) {
    final text = newValue.text;
    
    if (text.isEmpty) {
      return newValue;
    }
    
    // Remove tudo que não é número
    final digitsOnly = text.replaceAll(RegExp(r'\D'), '');
    
    // Limita a 8 dígitos (DDMMYYYY)
    if (digitsOnly.length > 8) {
      return oldValue;
    }
    
    // Formata de acordo com quantidade de dígitos
    String formatted = '';
    
    if (digitsOnly.length <= 2) {
      formatted = digitsOnly;
    } else if (digitsOnly.length <= 4) {
      formatted = '${digitsOnly.substring(0, 2)}/${digitsOnly.substring(2)}';
    } else {
      formatted = '${digitsOnly.substring(0, 2)}/${digitsOnly.substring(2, 4)}/${digitsOnly.substring(4)}';
    }
    
    return TextEditingValue(
      text: formatted,
      selection: TextSelection.collapsed(offset: formatted.length),
    );
  }
}

// Classe para limitar apenas números (para date picker)
class NumericFormatter extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) {
    final digitsOnly = newValue.text.replaceAll(RegExp(r'\D'), '');
    
    // Limita a 8 dígitos
    if (digitsOnly.length > 8) {
      return oldValue;
    }
    
    return TextEditingValue(
      text: digitsOnly,
      selection: TextSelection.collapsed(offset: digitsOnly.length),
    );
  }
}

// Widget para mostrar requisitos de senha e comparação
class PasswordRequirements extends StatelessWidget {
  final String password;
  final String confirmPassword;
  
  const PasswordRequirements({super.key, required this.password, this.confirmPassword = ''});
  
  bool _hasMinLength() => password.length >= 6;
  bool _hasUppercase() => password.contains(RegExp(r'[A-Z]'));
  bool _hasNumber() => password.contains(RegExp(r'[0-9]'));
  bool _hasSpecialChar() => password.contains(RegExp(r'[!@#$%^&*()_+=\[\]{};:,.<>?/\\|`~-]'));
  bool _passwordsMatch() => password.isNotEmpty && confirmPassword.isNotEmpty && password == confirmPassword;
  
  Widget _buildRequirement(String label, bool met) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4.0),
      child: Row(
        children: [
          Icon(
            met ? Icons.check_circle : Icons.cancel,
            color: met ? Colors.green : Colors.red,
            size: 18,
          ),
          const SizedBox(width: 8),
          Text(
            label,
            style: TextStyle(
              color: met ? Colors.green : Colors.red,
              fontSize: 12,
            ),
          ),
        ],
      ),
    );
  }
  
  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _buildRequirement('Mínimo 6 caracteres', _hasMinLength()),
        _buildRequirement('Ao menos 1 letra maiúscula', _hasUppercase()),
        _buildRequirement('Ao menos 1 dígito', _hasNumber()),
        _buildRequirement('Ao menos 1 caractere especial', _hasSpecialChar()),
        _buildRequirement('Senhas são iguais', _passwordsMatch()),
      ],
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
  bool _showPassword = false;
  bool _showConfirmPassword = false;
  bool _recaptchaValidated = false;
  
  // Mensagens de erro para cada campo
  String? _nameError;
  String? _emailError;
  String? _phoneError;
  String? _birthdateError;
  String? _passwordError;
  String? _confirmPasswordError;

  Future<void> _register() async {
    // Validar reCAPTCHA primeiro
    if (!_recaptchaValidated) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Por favor, complete a verificação de segurança'),
          backgroundColor: Colors.red,
        ),
      );
      return;
    }

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
    } else if (!_passwordController.text.contains(RegExp(r'[A-Z]'))) {
      setState(() => _passwordError = 'A senha deve conter pelo menos 1 letra maiúscula.');
      hasError = true;
    } else if (!_passwordController.text.contains(RegExp(r'[0-9]'))) {
      setState(() => _passwordError = 'A senha deve conter pelo menos 1 número.');
      hasError = true;
    } else if (!_passwordController.text.contains(RegExp(r'[!@#$%^&*()_+=\[\]{};:,.<>?/\\|`~-]'))) {
      setState(() => _passwordError = 'A senha deve conter pelo menos 1 caractere especial.');
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
        Uri.parse('${AppConfig.apiBaseUrl}/api/users/request-verification'),
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
        
        // Mostra modal de verificação de email (centralizado na mesma tela)
        showDialog(
          context: context,
          barrierDismissible: false,
          builder: (context) => VerificacaoEmailModal(
            email: _emailController.text,
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
                suffixIcon: _nameError != null
                    ? const Icon(Icons.error, color: Colors.red)
                    : (_nameController.text.isNotEmpty
                        ? const Icon(Icons.check_circle, color: Colors.green)
                        : null),
              ),
              onChanged: (value) {
                if (_nameError != null && value.isNotEmpty) {
                  setState(() => _nameError = null);
                } else {
                  setState(() {});
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
                suffixIcon: _phoneError != null
                    ? const Icon(Icons.error, color: Colors.red)
                    : (_phoneController.text.replaceAll(RegExp(r'\D'), '').length >= 10 && _phoneController.text.replaceAll(RegExp(r'\D'), '').length <= 11
                        ? const Icon(Icons.check_circle, color: Colors.green)
                        : null),
              ),
              keyboardType: TextInputType.phone,
              inputFormatters: [PhoneInputFormatter()],
              onChanged: (value) {
                if (_phoneError != null && value.replaceAll(RegExp(r'\D'), '').length >= 10) {
                  setState(() => _phoneError = null);
                } else {
                  setState(() {});
                }
              },
            ),
            const SizedBox(height: 20.0),

            const Text('Data de Nascimento'),
            TextField(
              controller: _birthdateController,
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
                    : (_birthdateController.text.length == 10
                        ? const Icon(Icons.check_circle, color: Colors.green)
                        : IconButton(
                            icon: const Icon(Icons.calendar_today),
                            onPressed: _selectBirthdate,
                          )),
              ),
              keyboardType: TextInputType.number,
              inputFormatters: [DateInputFormatter()],
              onTap: _selectBirthdate,
              onChanged: (value) {
                if (_birthdateError != null && value.length == 10) {
                  setState(() => _birthdateError = null);
                } else {
                  setState(() {});
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
                errorText: _passwordError,
                suffixIcon: _passwordError != null
                    ? const Icon(Icons.error, color: Colors.red)
                    : Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          IconButton(
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
                          if (_passwordController.text.length >= 6 &&
                              _passwordController.text.contains(RegExp(r'[A-Z]')) &&
                              _passwordController.text.contains(RegExp(r'[0-9]')) &&
                              _passwordController.text.contains(RegExp(r'[!@#$%^&*()_+=\[\]{};:,.<>?/\\|`~-]')))
                            const Icon(Icons.check_circle, color: Colors.green)
                        ],
                      ),
              ),
              onChanged: (value) {
                if (_passwordError != null) {
                  setState(() {
                    if (value.length >= 6 &&
                        value.contains(RegExp(r'[A-Z]')) &&
                        value.contains(RegExp(r'[0-9]')) &&
                        value.contains(RegExp(r'[!@#$%^&*()_+=\[\]{};:,.<>?/\\|`~-]'))) {
                      _passwordError = null;
                    } else {
                      setState(() {});
                    }
                  });
                } else {
                  setState(() {});
                }
              },
            ),
            const SizedBox(height: 20.0),

            const Text('Confirmar senha'),
            TextField(
              controller: _confirmPasswordController,
              obscureText: !_showConfirmPassword,
              decoration: InputDecoration(
                hintText: 'Confirme sua senha',
                border: const OutlineInputBorder(),
                errorText: _confirmPasswordError,
                suffixIcon: _confirmPasswordError != null
                    ? const Icon(Icons.error, color: Colors.red)
                    : Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          IconButton(
                            icon: Icon(
                              _showConfirmPassword ? Icons.visibility : Icons.visibility_off,
                              color: Colors.grey,
                            ),
                            onPressed: () {
                              setState(() {
                                _showConfirmPassword = !_showConfirmPassword;
                              });
                            },
                          ),
                          if (_confirmPasswordController.text.isNotEmpty && _confirmPasswordController.text == _passwordController.text)
                            const Icon(Icons.check_circle, color: Colors.green)
                        ],
                      ),
              ),
              onChanged: (value) {
                if (_confirmPasswordError != null && value == _passwordController.text) {
                  setState(() => _confirmPasswordError = null);
                } else {
                  setState(() {});
                }
              },
            ),
            const SizedBox(height: 12.0),
            const Text(
              'Requisitos da senha:',
              style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 8.0),
            // Requisitos de senha + Comparação
            PasswordRequirements(
              password: _passwordController.text,
              confirmPassword: _confirmPasswordController.text,
            ),
            const SizedBox(height: 20.0),
            RecaptchaWidget(
              action: 'register',
              onRecaptchaChanged: (isValid) {
                setState(() {
                  _recaptchaValidated = isValid;
                });
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