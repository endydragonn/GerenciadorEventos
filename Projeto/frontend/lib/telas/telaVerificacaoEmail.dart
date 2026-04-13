import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'dart:async';
import 'dart:convert';
import 'telaLogin.dart';
import 'auth.dart';
import '../config/app_config.dart';

class TelaVerificacaoEmail extends StatefulWidget {
  final String email;

  const TelaVerificacaoEmail({Key? key, required this.email}) : super(key: key);

  @override
  State<TelaVerificacaoEmail> createState() => _TelaVerificacaoEmailState();
}

class _TelaVerificacaoEmailState extends State<TelaVerificacaoEmail> {
  final TextEditingController _codeController = TextEditingController();
  bool _isLoading = false;
  String? _errorMessage;
  int _remainingSeconds = 300; // 5 minutos
  late Timer _timer;
  bool _canResend = false;
  int _resendCooldown = 0;

  @override
  void initState() {
    super.initState();
    _startTimer();
  }

  void _startTimer() {
    _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
      setState(() {
        _remainingSeconds--;
        if (_remainingSeconds <= 0) {
          _timer.cancel();
          _errorMessage = "Código expirado. Solicite um novo.";
          _canResend = true;
        }
      });
    });
  }

  String _formatTime(int seconds) {
    int minutes = seconds ~/ 60;
    int secs = seconds % 60;
    return "${minutes.toString().padLeft(2, '0')}:${secs.toString().padLeft(2, '0')}";
  }

  Future<void> _verifyCode() async {
    final code = _codeController.text.trim();

    if (code.isEmpty) {
      setState(() {
        _errorMessage = "Digite o código de verificação";
      });
      return;
    }

    if (code.length != 6) {
      setState(() {
        _errorMessage = "Código deve ter 6 dígitos";
      });
      return;
    }

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final response = await http.post(
        Uri.parse('${AppConfig.apiBaseUrl}/api/users/verify-email'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({
          'email': widget.email,
          'verificationCode': code,
        }),
      ).timeout(
        const Duration(seconds: 10),
        onTimeout: () {
          throw Exception('Timeout na requisição');
        },
      );

      if (response.statusCode == 201) {
        _timer.cancel();
        
        // Extrai token da resposta e salva
        String? token;
        try {
          final responseData = jsonDecode(response.body);
          token = responseData['token'];
          
          if (token != null && token.isNotEmpty) {
            // Salva token com persistência (vai estar disponível ao reiniciar)
            await ApiAuth.setToken(token, persist: true);
          }
        } catch (e) {
          // Ignora erro de parse
        }

        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Email verificado com sucesso!'),
              backgroundColor: Colors.green,
            ),
          );
          
          // Aguarda 1 segundo e navega (será redirecionado para home automaticamente se houver token)
          Future.delayed(const Duration(seconds: 1), () {
            if (mounted) {
              Navigator.of(context).pushAndRemoveUntil(
                MaterialPageRoute(builder: (context) => const LoginScreen()),
                (route) => false,
              );
            }
          });
        }
      } else {
        String errorMsg = 'Erro ao verificar código';
        try {
          final responseData = jsonDecode(response.body);
          errorMsg = responseData['message'] ?? errorMsg;
        } catch (parseError) {
          errorMsg = response.body.isNotEmpty ? response.body : errorMsg;
        }
        setState(() {
          _errorMessage = errorMsg;
        });
      }
    } catch (e) {
      setState(() {
        _errorMessage = 'Erro de conexão: ${e.toString()}';
      });
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  Future<void> _resendCode() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final response = await http.post(
        Uri.parse('${AppConfig.apiBaseUrl}/api/users/resend-verification-code'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({'email': widget.email}),
      ).timeout(
        const Duration(seconds: 10),
        onTimeout: () {
          throw Exception('Timeout na requisição');
        },
      );

      if (response.statusCode == 200) {
        setState(() {
          _remainingSeconds = 300;
          _canResend = false;
          _codeController.clear();
          _errorMessage = null;
        });
        _timer.cancel();
        _startTimer();

        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Novo código enviado!'),
              backgroundColor: Colors.green,
            ),
          );
        }
      } else {
        String errorMsg = 'Erro ao reenviar código';
        try {
          final responseData = jsonDecode(response.body);
          errorMsg = responseData['message'] ?? errorMsg;
        } catch (parseError) {
          errorMsg = response.body.isNotEmpty ? response.body : errorMsg;
        }
        setState(() {
          _errorMessage = errorMsg;
        });
      }
    } catch (e) {
      setState(() {
        _errorMessage = 'Erro de conexão: ${e.toString()}';
      });
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  @override
  void dispose() {
    _timer.cancel();
    _codeController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.grey[100],
      appBar: AppBar(
        title: const Text('Verificação de Email'),
        centerTitle: true,
        elevation: 0,
        backgroundColor: Colors.blue,
      ),
      body: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              const SizedBox(height: 40),
              
              // Ícone
              Container(
                width: 80,
                height: 80,
                decoration: BoxDecoration(
                  color: Colors.blue.withOpacity(0.1),
                  shape: BoxShape.circle,
                ),
                child: const Icon(
                  Icons.email_outlined,
                  size: 40,
                  color: Colors.blue,
                ),
              ),

              const SizedBox(height: 24),

              // Título
              const Text(
                'Verifique seu Email',
                style: TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.bold,
                ),
                textAlign: TextAlign.center,
              ),

              const SizedBox(height: 12),

              // Descrição
              Text(
                'Enviamos um código de 6 dígitos para:\n${widget.email}',
                style: TextStyle(
                  fontSize: 14,
                  color: Colors.grey[600],
                ),
                textAlign: TextAlign.center,
              ),

              const SizedBox(height: 32),

              // Campo de código
              TextField(
                controller: _codeController,
                enabled: !_isLoading && _remainingSeconds > 0,
                maxLength: 6,
                textAlign: TextAlign.center,
                keyboardType: TextInputType.number,
                style: const TextStyle(
                  fontSize: 32,
                  fontWeight: FontWeight.bold,
                  letterSpacing: 8,
                ),
                decoration: InputDecoration(
                  hintText: '000000',
                  hintStyle: TextStyle(
                    color: Colors.grey[300],
                  ),
                  counterText: '',
                  contentPadding: const EdgeInsets.symmetric(vertical: 20),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                  enabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: BorderSide(
                      color: Colors.grey[300]!,
                      width: 2,
                    ),
                  ),
                  focusedBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: const BorderSide(
                      color: Colors.blue,
                      width: 2,
                    ),
                  ),
                  disabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: BorderSide(
                      color: Colors.grey[200]!,
                      width: 2,
                    ),
                  ),
                ),
              ),

              const SizedBox(height: 16),

              // Timer
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                decoration: BoxDecoration(
                  color: _remainingSeconds > 60
                      ? Colors.blue.withOpacity(0.1)
                      : Colors.red.withOpacity(0.1),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  'Código expira em: ${_formatTime(_remainingSeconds)}',
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: _remainingSeconds > 60 ? Colors.blue : Colors.red,
                  ),
                ),
              ),

              const SizedBox(height: 24),

              // Mensagem de erro
              if (_errorMessage != null)
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: Colors.red.withOpacity(0.1),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: Colors.red.withOpacity(0.3)),
                  ),
                  child: Row(
                    children: [
                      const Icon(Icons.error_outline, color: Colors.red, size: 20),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Text(
                          _errorMessage!,
                          style: const TextStyle(
                            color: Colors.red,
                            fontSize: 13,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),

              const SizedBox(height: 24),

              // Botão Verificar
              SizedBox(
                width: double.infinity,
                height: 48,
                child: ElevatedButton(
                  onPressed: _isLoading || _remainingSeconds <= 0
                      ? null
                      : _verifyCode,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.blue,
                    disabledBackgroundColor: Colors.grey[300],
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  child: _isLoading
                      ? const SizedBox(
                          height: 24,
                          width: 24,
                          child: CircularProgressIndicator(
                            valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                            strokeWidth: 2,
                          ),
                        )
                      : const Text(
                          'Verificar Código',
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                            color: Colors.white,
                          ),
                        ),
                ),
              ),

              const SizedBox(height: 16),

              // Botão Reenviar
              TextButton(
                onPressed: _isLoading ? null : _resendCode,
                child: Text(
                  'Reenviar Código',
                  style: TextStyle(
                    color: _isLoading ? Colors.grey : Colors.blue,
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),

              const SizedBox(height: 24),

              // Link voltar
              TextButton(
                onPressed: () {
                  _timer.cancel();
                  Navigator.of(context).pop();
                },
                child: const Text(
                  'Voltar para Cadastro',
                  style: TextStyle(
                    color: Colors.grey,
                    fontSize: 14,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
