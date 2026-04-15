import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'dart:convert';
import 'dart:html' as html;
import 'dart:js' as js;
import 'dart:async';
import '../config/app_config.dart';

class RecaptchaWidget extends StatefulWidget {
  final Function(bool) onRecaptchaChanged;
  final String action; // 'register', 'login', etc.

  const RecaptchaWidget({
    super.key,
    required this.onRecaptchaChanged,
    required this.action,
  });

  @override
  State<RecaptchaWidget> createState() => _RecaptchaWidgetState();
}

class _RecaptchaWidgetState extends State<RecaptchaWidget> {
  bool _isVerifying = false;
  bool _isVerified = false;
  String? _token;

  @override
  void initState() {
    super.initState();
    _executeRecaptcha();
  }

  Future<void> _executeRecaptcha() async {
    setState(() => _isVerifying = true);

    try {
      // Obter token real do reCAPTCHA v3
      final token = await _getRecaptchaToken();
      
      // Verificar token no backend
      final isValid = await _verifyTokenOnBackend(token);
      
      setState(() {
        _isVerifying = false;
        _isVerified = isValid;
        _token = token;
      });

      widget.onRecaptchaChanged(isValid);

      if (!isValid) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Falha na verificação do reCAPTCHA. Tente novamente.'),
            backgroundColor: Colors.red,
          ),
        );
      }
    } catch (e) {
      setState(() => _isVerifying = false);
      
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Erro na verificação do reCAPTCHA'),
          backgroundColor: Colors.red,
        ),
      );

      widget.onRecaptchaChanged(false);
    }
  }

  Future<String> _getRecaptchaToken() async {
    final completer = Completer<String>();
    
    try {
      // Acessar grecaptcha diretamente - já está carregado
      final dynamic window = js.context;
      final dynamic grecaptcha = window['grecaptcha'];
      
      if (grecaptcha == null) {
        throw Exception('grecaptcha não carregou no window');
      }
      
      
      // Criar objeto de parâmetros de forma correta para JavaScript
      final Map<String, String> params = {'action': widget.action};
      final dynamic jsParams = js.JsObject.jsify(params);
      
      // Chamar grecaptcha.execute com parâmetros corretos
      final dynamic promise = grecaptcha.callMethod('execute', [
        '6Lc7frUsAAAAAEAYk35OuvmHeJOIPY0MLOvaQfJ3',
        jsParams
      ]);
      
      // Conectar callbacks ao promise
      promise.callMethod('then', [
        js.allowInterop((token) {
  
          if (!completer.isCompleted) {
            completer.complete(token as String);
          }
        }),
        js.allowInterop((error) {
 
          if (!completer.isCompleted) {
            completer.completeError(error);
          }
        })
      ]);
      
      // Retornar token com timeout de 20 segundos
      return await completer.future.timeout(const Duration(seconds: 20));
      
    } catch (e) {
      // debugPrint('✗ Erro em _getRecaptchaToken: $e');
      rethrow;
    }
  }

  Future<bool> _verifyTokenOnBackend(String token) async {
    try {
      final response = await http.post(
        Uri.parse('${AppConfig.apiBaseUrl}/api/users/verify-recaptcha'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({
          'token': token,
          'action': widget.action,
        }),
      ).timeout(const Duration(seconds: 10));
      
      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        
        return data['success'] == true && (data['score'] ?? 0.0) > 0.7;
      }
      
      return false;
    } catch (e) {
      // print('❌ Erro ao verificar token no backend: $e');
      return false;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 8),
      child: Row(
        children: [
          if (_isVerifying)
            const SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(
                strokeWidth: 2.0,
                valueColor: AlwaysStoppedAnimation<Color>(Colors.purple),
              ),
            )
          else if (_isVerified)
            const Icon(Icons.check_circle, color: Colors.green, size: 20)
          else
            const Icon(Icons.error, color: Colors.red, size: 20),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              _isVerifying
                  ? 'Verificando reCAPTCHA...'
                  : _isVerified
                      ? 'reCAPTCHA verificado ✓'
                      : 'Erro na verificação do reCAPTCHA',
              style: TextStyle(
                fontSize: 12,
                color: _isVerified ? Colors.green : (_isVerifying ? Colors.grey : Colors.red),
              ),
            ),
          ),
          if (_isVerified)
            TextButton(
              onPressed: _executeRecaptcha,
              child: const Text('Reverificar', style: TextStyle(fontSize: 11)),
            ),
        ],
      ),
    );
  }
}
