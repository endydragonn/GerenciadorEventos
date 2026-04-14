// ==============================================================================
// TELA DE EDIÇÃO DE PERFIL
// ==============================================================================
// Função: Editar informações do perfil do usuário logado
// 
// Funcionalidades:
// - Formulário pré-preenchido com dados atuais do usuário
// - Campos editáveis: nome, email, telefone, foto de perfil
// - Atualização via API (/api/bff/users/{userId}) com método PUT
// - Validações de campos (email válido, telefone, etc.)
// - Confirmação antes de salvar alterações
// - Opção de cancelar edição
// - Atualização do Provider (HomePageData) após edição bem-sucedida
// - SnackBars para feedback de sucesso/erro
// - Navegação de volta para tela principal após salvar
// ==============================================================================

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'telaHomePage.dart'; // HomePageData
import 'telaPrincipal.dart'; // MainScreen
import 'package:http/http.dart' as http;
import 'auth.dart';
import 'dart:convert';
import 'modals.dart';
import '../config/app_config.dart';

class EditProfileScreen extends StatefulWidget {
  const EditProfileScreen({super.key});

  @override
  State<EditProfileScreen> createState() => _EditProfileScreenState();
}

class _EditProfileScreenState extends State<EditProfileScreen> {
  final _formKey = GlobalKey<FormState>();
  final _nameController = TextEditingController();
  final _emailController = TextEditingController();
  final _phoneController = TextEditingController();
  final _mfaCodeController = TextEditingController();
  final _newPasswordController = TextEditingController();
  final _confirmPasswordController = TextEditingController();
  bool _hasChanges = false;
  bool _changePassword = false;
  bool _mfaRequested = false;
  bool _mfaValidated = false;
  bool _showNewPassword = false;
  bool _showConfirmPassword = false;
  bool _requestingMfa = false;
  bool _isRequestingMfa = false;

  // Método para validar força da senha
  bool _isPasswordStrong(String password) {
    return password.length >= 6 &&
           password.contains(RegExp(r'[A-Z]')) &&
           password.contains(RegExp(r'[0-9]')) &&
           password.contains(RegExp(r'[!@#$%^&*()_+=\[\]{};:,.<>?/\\|`~-]'));
  }

  @override
  void initState() {
    super.initState();
    // Verificar autenticação ao carregar a tela
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _checkAuthentication();
      // Carregar dados mais recentes do usuário após verificar autenticação
      _loadUserData();
    });
    // Inicializar com dados atuais (serão atualizados por _loadUserData)
    final user = Provider.of<HomePageData>(context, listen: false).user;
    if (user != null) {
      _nameController.text = user['name'] ?? '';
      _emailController.text = user['email'] ?? '';
      _phoneController.text = user['fone'] ?? '';
    }
  }

  void _checkAuthentication() {
    final user = Provider.of<HomePageData>(context, listen: false).user;

    // Se não estiver logado, redirecionar para tela principal
    if (user == null || user.isEmpty || user['user_id'] == null) {
      AppModals.showError(
        context,
        'Autenticação Necessária',
        'Você precisa estar logado para editar seu perfil.',
        onOk: () {
          Navigator.pushReplacement(
            context,
            MaterialPageRoute(builder: (context) => const MainScreen()),
          );
        },
      );
    }
  }

  Future<void> _loadUserData() async {
    final user = Provider.of<HomePageData>(context, listen: false).user;
    if (user == null || user['user_id'] == null) return;

    try {
      final response = await http.get(
        Uri.parse('/api/bff/users/${user['user_id']}'),
        headers: ApiAuth.jsonHeaders(),
      );

      if (!mounted) return;

      if (response.statusCode == 200) {
        final userData = jsonDecode(response.body);
        // Atualizar dados no Provider
        Provider.of<HomePageData>(context, listen: false).setUser(userData);
        // Atualizar controladores com dados mais recentes
        setState(() {
          _nameController.text = userData['name'] ?? '';
          _emailController.text = userData['email'] ?? '';
          _phoneController.text = userData['fone'] ?? '';
        });
      } else {
        // Opcional: Mostrar erro se falhar ao carregar
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Erro ao carregar dados do perfil: ${response.statusCode}'),
            backgroundColor: Colors.red,
          ),
        );
      }
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Erro de conexão ao carregar dados do perfil'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  @override
  void dispose() {
    _nameController.dispose();
    _emailController.dispose();
    _phoneController.dispose();
    _mfaCodeController.dispose();
    _newPasswordController.dispose();
    _confirmPasswordController.dispose();
    super.dispose();
  }

  Future<void> _confirmEdit(BuildContext context) async {
    if (_formKey.currentState!.validate()) {
      final user = Provider.of<HomePageData>(context, listen: false).user;
      
      // Se alterando senha, primeiro validar MFA antes de continuar
      if (_changePassword) {
        if (!_mfaRequested) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Clique em "Solicitar Código MFA" para alterar sua senha'),
              backgroundColor: Colors.orange,
            ),
          );
          return;
        }

        if (!_mfaValidated) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Código MFA não validado. Verifique o código enviado no seu email.'),
              backgroundColor: Colors.red,
            ),
          );
          return;
        }
      }
      
      // Atualizar dados do perfil
      final updatedUser = {
        'name': _nameController.text,
        'email': _emailController.text,
        'fone': _phoneController.text,
      };

      try {
        final response = await http.put(
          Uri.parse('${AppConfig.apiBaseUrl}/bff/users/${user['user_id']}'),
          headers: ApiAuth.jsonHeaders(),
          body: jsonEncode(updatedUser),
        );

        if (!mounted) return;

        if (response.statusCode == 200) {
          // Se alterando senha, fazer a alteração agora (após validação de MFA)
          if (_changePassword) {
            final passwordResponse = await http.put(
              Uri.parse('${AppConfig.apiBaseUrl}/bff/users/${user['user_id']}/password-with-mfa'),
              headers: ApiAuth.jsonHeaders(),
              body: jsonEncode({
                'mfaCode': _mfaCodeController.text,
                'newPassword': _newPasswordController.text,
              }),
            );

            if (!mounted) return;

            if (passwordResponse.statusCode != 200) {
              String errorMsg = 'Erro ao alterar senha';
              try {
                errorMsg = jsonDecode(passwordResponse.body);
              } catch (e) {}
              
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(
                  content: Text(errorMsg),
                  backgroundColor: Colors.red,
                ),
              );
              return;
            }
          }

          // Atualizar dados no Provider
          final updatedUserData = jsonDecode(response.body);
          Provider.of<HomePageData>(context, listen: false).setUser(updatedUserData);
          
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(_changePassword 
                ? 'Perfil e senha atualizados com sucesso!' 
                : 'Perfil atualizado com sucesso!'),
              backgroundColor: Colors.green,
            ),
          );
          Navigator.pushReplacement(
            context,
            MaterialPageRoute(builder: (context) => const MainScreen()),
          );
        } else {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('Falha ao atualizar perfil: ${response.statusCode}'),
              backgroundColor: Colors.red,
            ),
          );
        }
      } catch (e) {
        if (!mounted) return;
        AppModals.showConnectionError(context);
      }
    }
  }

  // Widget para mostrar requisito de senha
  Widget _buildPasswordRequirement(String text, bool isMet, {bool withStrikethrough = true}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4.0),
      child: Row(
        children: [
          Icon(
            isMet ? Icons.check_circle : Icons.cancel,
            size: 16,
            color: isMet ? Colors.green : Colors.red,
          ),
          const SizedBox(width: 8),
          Text(
            text,
            style: TextStyle(
              fontSize: 12,
              color: isMet ? Colors.green : Colors.red,
              decoration: isMet && withStrikethrough ? TextDecoration.lineThrough : null,
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _requestPasswordResetMfa() async {
    final user = Provider.of<HomePageData>(context, listen: false).user;
    
    setState(() {
      _isRequestingMfa = true;
    });

    try {
      final response = await http.post(
        Uri.parse('${AppConfig.apiBaseUrl}/bff/users/${user['user_id']}/request-password-reset-mfa'),
        headers: ApiAuth.jsonHeaders(),
      );

      if (!mounted) return;

      setState(() {
        _isRequestingMfa = false;
      });

      if (response.statusCode == 200) {
        setState(() {
          _mfaRequested = true;
          _mfaValidated = false;
        });

        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('📧 Código MFA enviado para seu email! Verifique sua caixa de entrada.'),
            backgroundColor: Colors.green,
          ),
        );
      } else {
        String errorMsg = 'Erro ao solicitar código MFA';
        try {
          errorMsg = jsonDecode(response.body);
        } catch (e) {}
        
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(errorMsg),
            backgroundColor: Colors.red,
          ),
        );
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isRequestingMfa = false;
      });
      AppModals.showConnectionError(context);
    }
  }

  Future<void> _verifyMfaCode() async {
    if (_mfaCodeController.text.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Digite o código MFA'),
          backgroundColor: Colors.red,
        ),
      );
      return;
    }

    final user = Provider.of<HomePageData>(context, listen: false).user;
    
    try {
      final response = await http.post(
        Uri.parse('${AppConfig.apiBaseUrl}/bff/users/${user['user_id']}/verify-password-reset-mfa'),
        headers: ApiAuth.jsonHeaders(),
        body: jsonEncode({'mfaCode': _mfaCodeController.text}),
      );

      if (!mounted) return;

      if (response.statusCode == 200) {
        setState(() {
          _mfaValidated = true;
        });

        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('✅ Código MFA validado com sucesso!'),
            backgroundColor: Colors.green,
          ),
        );
      } else {
        String errorMsg = 'Código MFA inválido ou expirado';
        try {
          errorMsg = jsonDecode(response.body);
        } catch (e) {}
        
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(errorMsg),
            backgroundColor: Colors.red,
          ),
        );
      }
    } catch (e) {
      if (!mounted) return;
      AppModals.showConnectionError(context);
    }
  }

  void _cancelEdit(BuildContext context) {
    if (_hasChanges) {
      showDialog(
        context: context,
        builder: (BuildContext context) {
          return AlertDialog(
            title: const Text('Alterações não salvas'),
            content: const Text('Você tem alterações não salvas. Deseja sair mesmo assim?'),
            actions: <Widget>[
              TextButton(
                onPressed: () {
                  Navigator.of(context).pop();
                },
                child: const Text('Voltar a editar'),
              ),
              TextButton(
                onPressed: () {
                  Navigator.of(context).pop();
                  Navigator.pushReplacement(
                    context,
                    MaterialPageRoute(builder: (context) => const MainScreen()),
                  );
                },
                child: const Text('Sair'),
              ),
            ],
          );
        },
      );
    } else {
      Navigator.pushReplacement(
        context,
        MaterialPageRoute(builder: (context) => const MainScreen()),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Editar Perfil'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => _cancelEdit(context),
        ),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Form(
          key: _formKey,
          onChanged: () => setState(() => _hasChanges = true),
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                const Text(
                  'Editar Informações do Perfil',
                  style: TextStyle(fontSize: 24.0, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 24.0),
                
                // Nome
                const Text('Nome Completo', style: TextStyle(fontWeight: FontWeight.w500)),
                const SizedBox(height: 8.0),
                TextFormField(
                  controller: _nameController,
                  decoration: const InputDecoration(
                    hintText: 'Seu nome completo',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.person),
                  ),
                  validator: (value) {
                    if (value == null || value.isEmpty) {
                      return 'Nome é obrigatório';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 20.0),
                
                // Email
                const Text('Email', style: TextStyle(fontWeight: FontWeight.w500)),
                const SizedBox(height: 8.0),
                TextFormField(
                  controller: _emailController,
                  decoration: const InputDecoration(
                    hintText: 'seuemail@example.com',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.email),
                  ),
                  keyboardType: TextInputType.emailAddress,
                  validator: (value) {
                    if (value == null || value.isEmpty || !value.contains('@')) {
                      return 'Email válido é obrigatório';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 20.0),
                
                // Telefone
                const Text('Telefone', style: TextStyle(fontWeight: FontWeight.w500)),
                const SizedBox(height: 8.0),
                TextFormField(
                  controller: _phoneController,
                  decoration: const InputDecoration(
                    hintText: '(00) 00000-0000',
                    border: OutlineInputBorder(),
                    prefixIcon: Icon(Icons.phone),
                  ),
                  keyboardType: TextInputType.phone,
                  validator: (value) {
                    if (value == null || value.isEmpty) {
                      return 'Telefone é obrigatório';
                    }
                    if (value.length < 10) {
                      return 'Telefone deve ter pelo menos 10 dígitos';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 32.0),
                
                // Informações Não Editáveis
                const Divider(),
                const SizedBox(height: 16.0),
                const Text(
                  'Informações da Conta',
                  style: TextStyle(fontSize: 18.0, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 16.0),
                
                // Data de Nascimento (Não Editável)
                const Text('Data de Nascimento', style: TextStyle(fontWeight: FontWeight.w500, color: Colors.grey)),
                const SizedBox(height: 8.0),
                Consumer<HomePageData>(
                  builder: (context, homePageData, child) {
                    final user = homePageData.user;
                    String birthdate = 'Não informado';
                    if (user != null && user['birthdate'] != null) {
                      try {
                        final date = DateTime.parse(user['birthdate']);
                        birthdate = '${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year}';
                      } catch (e) {
                        birthdate = user['birthdate'];
                      }
                    }
                    return TextFormField(
                      initialValue: birthdate,
                      enabled: false,
                      decoration: const InputDecoration(
                        border: OutlineInputBorder(),
                        prefixIcon: Icon(Icons.cake),
                        filled: true,
                        fillColor: Color(0xFFF5F5F5),
                      ),
                      style: const TextStyle(color: Colors.black54),
                    );
                  },
                ),
                const SizedBox(height: 16.0),
                
                // Data de Criação da Conta (Não Editável)
                const Text('Membro desde', style: TextStyle(fontWeight: FontWeight.w500, color: Colors.grey)),
                const SizedBox(height: 8.0),
                Consumer<HomePageData>(
                  builder: (context, homePageData, child) {
                    final user = homePageData.user;
                    String createdAt = 'Não disponível';
                    if (user != null && user['created_at'] != null) {
                      try {
                        final date = DateTime.parse(user['created_at']);
                        createdAt = '${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year} às ${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
                      } catch (e) {
                        createdAt = user['created_at'];
                      }
                    }
                    return TextFormField(
                      initialValue: createdAt,
                      enabled: false,
                      decoration: const InputDecoration(
                        border: OutlineInputBorder(),
                        prefixIcon: Icon(Icons.calendar_month),
                        filled: true,
                        fillColor: Color(0xFFF5F5F5),
                      ),
                      style: const TextStyle(color: Colors.black54),
                    );
                  },
                ),
                const SizedBox(height: 32.0),
                
                // Seção de Alteração de Senha
                const Divider(),
                const SizedBox(height: 16.0),
                
                CheckboxListTile(
                  title: const Text(
                    'Alterar Senha',
                    style: TextStyle(fontWeight: FontWeight.w500, fontSize: 16),
                  ),
                  subtitle: const Text('Marque esta opção se deseja alterar sua senha'),
                  value: _changePassword,
                  onChanged: (bool? value) async {
                    setState(() {
                      _changePassword = value ?? false;
                      if (!_changePassword) {
                        // Limpar campos de senha ao desmarcar
                        _mfaCodeController.clear();
                        _newPasswordController.clear();
                        _confirmPasswordController.clear();
                        _mfaRequested = false;
                        _mfaValidated = false;
                      }
                    });
                    
                    // Se marcou, enviar MFA automaticamente
                    if (value == true && !_mfaRequested) {
                      await Future.delayed(const Duration(milliseconds: 300));
                      if (mounted) {
                        await _requestPasswordResetMfa();
                      }
                    }
                  },
                  controlAffinity: ListTileControlAffinity.leading,
                ),
                
                if (_changePassword) ...[
                  const SizedBox(height: 16.0),
                  
                  // Aviso de MFA (só mostra antes de solicitar o código)
                  if (!_mfaRequested)
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: Colors.blue.shade50,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: Colors.blue.shade200),
                      ),
                      child: Row(
                        children: [
                          Icon(Icons.security, color: Colors.blue.shade700),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              'Enviaremos um código MFA para seu email para validar a alteração de senha',
                              style: TextStyle(
                                fontSize: 12,
                                color: Colors.blue.shade700,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  if (!_mfaRequested) const SizedBox(height: 16.0),
                  
                  if (_mfaRequested && !_mfaValidated) ...[
                    // Campo de código MFA
                    const Text('Código MFA', style: TextStyle(fontWeight: FontWeight.w500)),
                    const SizedBox(height: 8.0),
                    TextFormField(
                      autofocus: true,
                      controller: _mfaCodeController,
                      decoration: InputDecoration(
                        hintText: 'Digite o código MFA (8 caracteres)',
                        border: const OutlineInputBorder(),
                        prefixIcon: const Icon(Icons.key),
                        suffixIcon: _mfaValidated
                            ? const Icon(Icons.check_circle, color: Colors.green)
                            : null,
                      ),
                      maxLength: 8,
                      textCapitalization: TextCapitalization.characters,
                      onChanged: (value) async {
                        setState(() {});
                        if (value.length == 8 && !_mfaValidated) {
                          await _verifyMfaCode();
                        }
                      },
                      validator: (value) {
                        if (_changePassword && _mfaRequested && (value == null || value.isEmpty)) {
                          return 'Código MFA é obrigatório';
                        }
                        if (_changePassword && _mfaRequested && value!.length < 8) {
                          return 'Código MFA deve ter 8 caracteres';
                        }
                        return null;
                      },
                    ),
                    const SizedBox(height: 16.0),
                  ],
                  
                  if (_mfaValidated) ...[
                    // Nova Senha
                    const Text('Nova Senha', style: TextStyle(fontWeight: FontWeight.w500)),
                    const SizedBox(height: 8.0),
                    TextFormField(
                      controller: _newPasswordController,
                      obscureText: !_showNewPassword,
                      onChanged: (value) => setState(() {}),
                      decoration: InputDecoration(
                        hintText: 'Digite a nova senha',
                        border: const OutlineInputBorder(),
                        prefixIcon: const Icon(Icons.lock),
                        suffixIcon: IconButton(
                          icon: Icon(
                            _showNewPassword ? Icons.visibility : Icons.visibility_off,
                            color: Colors.grey,
                          ),
                          onPressed: () {
                            setState(() {
                              _showNewPassword = !_showNewPassword;
                            });
                          },
                        ),
                      ),
                      validator: (value) {
                        if (_changePassword && (value == null || value.isEmpty)) {
                          return 'Nova senha é obrigatória';
                        }
                        if (_changePassword && !_isPasswordStrong(value!)) {
                          return 'Senha não atende aos requisitos';
                        }
                        return null;
                      },
                    ),
                    const SizedBox(height: 16.0),
                    
                    // Confirmar Nova Senha
                    const Text('Confirmar Nova Senha', style: TextStyle(fontWeight: FontWeight.w500)),
                    const SizedBox(height: 8.0),
                    TextFormField(
                      controller: _confirmPasswordController,
                      obscureText: !_showConfirmPassword,
                      onChanged: (value) => setState(() {}),
                      decoration: InputDecoration(
                        hintText: 'Digite a nova senha novamente',
                        border: const OutlineInputBorder(),
                        prefixIcon: const Icon(Icons.lock),
                        suffixIcon: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (_confirmPasswordController.text.isNotEmpty && 
                                _newPasswordController.text.isNotEmpty &&
                                _confirmPasswordController.text == _newPasswordController.text)
                              const Padding(
                                padding: EdgeInsets.only(right: 8.0),
                                child: Icon(Icons.check_circle, color: Colors.green),
                              ),
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
                          ],
                        ),
                      ),
                      validator: (value) {
                        if (_changePassword && (value == null || value.isEmpty)) {
                          return 'Confirmação de senha é obrigatória';
                        }
                        if (_changePassword && value != _newPasswordController.text) {
                          return 'As senhas não coincidem';
                        }
                        return null;
                      },
                    ),
                    const SizedBox(height: 16.0),
                    
                    // Requisitos de senha (mostra sempre)
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
                          Text(
                            'Requisitos da Senha:',
                            style: TextStyle(
                              fontWeight: FontWeight.bold,
                              color: Colors.blue.shade700,
                              fontSize: 12,
                              ),
                            ),
                            const SizedBox(height: 8),
                            _buildPasswordRequirement(
                              'Mínimo 6 caracteres',
                              _newPasswordController.text.length >= 6,
                            ),
                            _buildPasswordRequirement(
                              'Ao menos 1 letra maiúscula (A-Z)',
                              _newPasswordController.text.contains(RegExp(r'[A-Z]')),
                            ),
                            _buildPasswordRequirement(
                              'Ao menos 1 dígito (0-9)',
                              _newPasswordController.text.contains(RegExp(r'[0-9]')),
                            ),
                            _buildPasswordRequirement(
                              'Ao menos 1 caractere especial (!@#\$%^&*...)',
                              _newPasswordController.text.contains(RegExp(r'[!@#$%^&*()_+=\[\]{};:,.<>?/\\|`~-]')),
                            ),
                            _buildPasswordRequirement(
                              'Senhas são iguais',
                              _newPasswordController.text.isNotEmpty &&
                              _newPasswordController.text == _confirmPasswordController.text,
                              withStrikethrough: false,
                            ),

                          ],
                        ),
                      ),
                  ],
                  ],
                  
                  const SizedBox(height: 32.0),
                
                // Botões
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: <Widget>[
                    Expanded(
                      child: ElevatedButton.icon(
                        onPressed: () => _confirmEdit(context),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.blue,
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 14.0),
                        ),
                        icon: const Icon(Icons.check),
                        label: const Text('Salvar Alterações'),
                      ),
                    ),
                    const SizedBox(width: 16.0),
                    Expanded(
                      child: ElevatedButton.icon(
                        onPressed: () => _cancelEdit(context),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.grey,
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 14.0),
                        ),
                        icon: const Icon(Icons.close),
                        label: const Text('Cancelar'),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
