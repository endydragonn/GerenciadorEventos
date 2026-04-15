// ==============================================================================
// TELA DE CRIAÇÃO DE EVENTO
// ==============================================================================
// Função: Criar novos eventos no sistema
// 
// Funcionalidades:
// - Formulário para criar evento com validação
// - Campos: nome, data, localização, descrição, tipo (Público/Privado)
// - Verificação de autenticação do usuário
// - Criação via API (/api/bff/events) com JWT
// - Associação automática do evento ao criador (creator_id)
// - SnackBars para feedback de sucesso/erro
// - Opção de cancelar com confirmação se houver alterações
// - Navegação para tela principal após criação bem-sucedida
// ==============================================================================

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'telaHomePage.dart'; // HomePageData
import 'telaPrincipal.dart'; // MainScreen
import 'package:http/http.dart' as http;
import 'auth.dart';
import 'modals.dart'; // SnackBars
import 'dart:convert';
import '../helpers/date_input_formatter.dart';
import 'package:flutter/services.dart';

class CreateEventScreen extends StatefulWidget {
  const CreateEventScreen({super.key});

  @override
  State<CreateEventScreen> createState() => _CreateEventScreenState();
}

class _CreateEventScreenState extends State<CreateEventScreen> {
  final _formKey = GlobalKey<FormState>();
  final _titleController = TextEditingController();
  final _dateController = TextEditingController();
  final _locationController = TextEditingController();
  final _descriptionController = TextEditingController();
  final _imageUrlController = TextEditingController();
  final _capacityController = TextEditingController();
  final _buyLimitController = TextEditingController();
  final _priceController = TextEditingController();
  String? _eventType;
  bool _hasChanges = false;
  bool _isEAD = false;
  String _paymentType = 'Gratuito';

  @override
  void initState() {
    super.initState();
    // Verificar autenticação ao carregar a tela
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _checkAuthentication();
    });
  }

  void _checkAuthentication() {
    final homePageData = Provider.of<HomePageData>(context, listen: false);
    final user = homePageData.user;

    // Se não estiver logado, redirecionar para tela principal
    if (user == null || user.isEmpty || user['user_id'] == null) {
      AppModals.showError(
        context,
        'Autenticação Necessária',
        'Você precisa estar logado para criar eventos.',
        onOk: () {
          Navigator.pushReplacement(
            context,
            MaterialPageRoute(builder: (context) => const MainScreen()),
          );
        },
      );
      return;
    }

    // Verificar se o usuário é um host
    final isHost = user['host'] ?? user['isHost'] ?? false;
    if (!isHost) {
      AppModals.showError(
        context,
        'Acesso Negado',
        'Apenas usuários com status de host podem criar eventos.',
        onOk: () {
          Navigator.pushReplacement(
            context,
            MaterialPageRoute(builder: (context) => const MainScreen()),
          );
        },
      );
    }
  }

  @override
  void dispose() {
    _titleController.dispose();
    _dateController.dispose();
    _locationController.dispose();
    _descriptionController.dispose();
    _imageUrlController.dispose();
    _capacityController.dispose();
    _buyLimitController.dispose();
    _priceController.dispose();
    super.dispose();
  }

  Future<void> _confirmCreate(BuildContext context) async {
    if (_formKey.currentState!.validate()) {
      final homePageData = Provider.of<HomePageData>(context, listen: false);
      final user = homePageData.user;

      // Verificar se o usuário está logado
      if (user == null || user.isEmpty) {
        AppModals.showError(
          context,
          'Autenticação Necessária',
          'Você precisa estar logado para criar um evento',
        );
        return;
      }

      // Verificar se user_id existe
      if (user['user_id'] == null) {
        AppModals.showError(
          context,
          'Erro de Autenticação',
          'ID do usuário não encontrado. Faça login novamente.',
        );
        return;
      }

      // Verificar se o usuário é um host
      final isHost = user['host'] ?? user['isHost'] ?? false;
      if (!isHost) {
        AppModals.showError(
          context,
          'Acesso Negado',
          'Apenas usuários com status de host podem criar eventos.',
        );
        return;
      }


      // Converter data do formato DD/MM/AAAA para ISO 8601
      String formattedDate;
      String buyTimeLimit;
      try {
        final dateParts = _dateController.text.split('/');
        if (dateParts.length != 3) {
          throw FormatException('Data inválida');
        }
        final day = dateParts[0].padLeft(2, '0');
        final month = dateParts[1].padLeft(2, '0');
        final year = dateParts[2];
        // Formato: YYYY-MM-DDTHH:MM:SS
        formattedDate = '$year-$month-${day}T09:00:00';
        
        // Converter buy_time_limit se fornecido, senão usar 1 dia antes do evento
        if (_buyLimitController.text.isNotEmpty) {
          final buyParts = _buyLimitController.text.split('/');
          if (buyParts.length != 3) {
            throw FormatException('Data limite inválida');
          }
          final buyDay = buyParts[0].padLeft(2, '0');
          final buyMonth = buyParts[1].padLeft(2, '0');
          final buyYear = buyParts[2];
          buyTimeLimit = '$buyYear-$buyMonth-${buyDay}T23:59:59';
        } else {
          // Padrão: 1 dia antes do evento às 23:59:59
          final dayBefore = int.parse(day) - 1;
          buyTimeLimit = '$year-$month-${dayBefore.toString().padLeft(2, '0')}T23:59:59';
        }
      } catch (e) {
        AppModals.showError(
          context,
          'Data Inválida',
          'Por favor, insira as datas no formato DD/MM/AAAA',
        );
        return;
      }

      final eventData = {
        'event_name': _titleController.text,
        'event_date': formattedDate,
        'buy_time_limit': buyTimeLimit,
        'address': _isEAD ? null : _locationController.text,
        'description': _descriptionController.text,
        'is_EAD': _isEAD,
        'creator_id': user['user_id'],
        'quantity': 0,
        'lot_quantity': _isEAD ? null : (_capacityController.text.isNotEmpty ? int.tryParse(_capacityController.text) : null),
        // Preço: 0.0 para eventos gratuitos, valor digitado para eventos pagos
        'price': _paymentType == 'Gratuito' ? 0.0 : (double.tryParse(_priceController.text) ?? 0.0),
        'presenters': [], // Lista vazia de apresentadores (obrigatório no backend)
      };


      try {
        final response = await http.post(
          Uri.parse('/api/bff/events'),
          headers: ApiAuth.jsonHeaders(),
          body: jsonEncode(eventData),
        );

        if (response.statusCode >= 400) {
          print('❌ Erro: ${response.body}');
        }

        if (!mounted) return;

        if (response.statusCode == 201) {
          AppModals.showSuccess(
            context,
            'Evento Criado',
            'Seu evento foi criado com sucesso!',
            onOk: () {
              Navigator.pushReplacement(
                context,
                MaterialPageRoute(builder: (context) => const MainScreen()),
              );
            },
          );
        } else if (response.statusCode == 400) {
          // Tentar mostrar a mensagem de erro do backend
          String errorMsg = 'Verifique os dados do evento e tente novamente.';
          try {
            errorMsg = response.body;
          } catch (e) {
            // Se não conseguir parsear, usar mensagem padrão
          }
          AppModals.showError(
            context,
            'Dados Inválidos',
            errorMsg,
          );
        } else {
          AppModals.showError(
            context,
            'Erro ao Criar Evento',
            'Ocorreu um erro (${response.statusCode}). Tente novamente.',
          );
        }
      } catch (e) {
        if (!mounted) return;
        AppModals.showConnectionError(context);
      }
    }
  }

  void _cancelCreate(BuildContext context) {
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
        title: const Text('Criar Evento'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => _cancelCreate(context),
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
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: <Widget>[
                    Column(
                      children: [
                        GestureDetector(
                          onTap: () {
                            showDialog(
                              context: context,
                              builder: (BuildContext context) {
                                final tempController = TextEditingController(text: _imageUrlController.text);
                                return AlertDialog(
                                  title: const Row(
                                    children: [
                                      Icon(Icons.add_photo_alternate, color: Colors.blue),
                                      SizedBox(width: 8),
                                      Text('Adicionar Imagem'),
                                    ],
                                  ),
                                  content: Column(
                                    mainAxisSize: MainAxisSize.min,
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      const Text(
                                        'Cole o link público da imagem do evento:',
                                        style: TextStyle(fontSize: 14),
                                      ),
                                      const SizedBox(height: 16),
                                      TextField(
                                        controller: tempController,
                                        decoration: const InputDecoration(
                                          hintText: 'https://exemplo.com/imagem.jpg',
                                          border: OutlineInputBorder(),
                                          prefixIcon: Icon(Icons.link),
                                        ),
                                        keyboardType: TextInputType.url,
                                      ),
                                    ],
                                  ),
                                  actions: [
                                    TextButton(
                                      onPressed: () => Navigator.of(context).pop(),
                                      child: const Text('Cancelar'),
                                    ),
                                    ElevatedButton(
                                      onPressed: () {
                                        setState(() {
                                          _imageUrlController.text = tempController.text;
                                          _hasChanges = true;
                                        });
                                        Navigator.of(context).pop();
                                      },
                                      style: ElevatedButton.styleFrom(
                                        backgroundColor: Colors.blue,
                                        foregroundColor: Colors.white,
                                      ),
                                      child: const Text('Salvar'),
                                    ),
                                  ],
                                );
                              },
                            );
                          },
                          child: Icon(
                            Icons.add_photo_alternate,
                            size: 60.0,
                            color: _imageUrlController.text.isNotEmpty
                                ? Colors.green
                                : Colors.grey,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              _imageUrlController.text.isNotEmpty
                                  ? 'Imagem Adicionada ✓'
                                  : 'Imagem do Evento',
                              style: TextStyle(
                                fontSize: 12,
                                color: _imageUrlController.text.isNotEmpty
                                    ? Colors.green
                                    : Colors.grey,
                              ),
                            ),
                            const SizedBox(width: 4),
                            GestureDetector(
                              onTap: () {
                                showDialog(
                                  context: context,
                                  builder: (BuildContext context) {
                                    return AlertDialog(
                                      title: const Row(
                                        children: [
                                          Icon(Icons.info_outline, color: Colors.blue),
                                          SizedBox(width: 8),
                                          Text('Como adicionar imagem'),
                                        ],
                                      ),
                                      content: const Text(
                                        'Para adicionar uma imagem ao evento:\n\n'
                                        '1. Faça upload da imagem em um serviço de hospedagem (ex: Imgur, Google Drive, Dropbox)\n\n'
                                        '2. Obtenha o link público da imagem\n\n'
                                        '3. Clique no ícone de imagem acima\n\n'
                                        '4. Cole o link no campo que aparecerá\n\n'
                                        'Nota: A funcionalidade de upload direto estará disponível em breve!',
                                        style: TextStyle(fontSize: 14),
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
                              },
                              child: const Icon(
                                Icons.help_outline,
                                size: 16,
                                color: Colors.blue,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ],
                ),
                const SizedBox(height: 20.0),
                const Text(
                  'Título do Evento',
                  style: TextStyle(fontSize: 16.0, fontWeight: FontWeight.w500),
                ),
                TextFormField(
                  controller: _titleController,
                  decoration: const InputDecoration(
                    hintText: 'Digite o título',
                    border: OutlineInputBorder(),
                  ),
                  validator: (value) {
                    if (value == null || value.isEmpty) {
                      return 'Por favor, insira um título';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 20.0),
                const Text(
                  'Data do Evento',
                  style: TextStyle(fontSize: 16.0, fontWeight: FontWeight.w500),
                ),
                TextFormField(
                  controller: _dateController,
                  decoration: const InputDecoration(
                    hintText: 'DD/MM/AAAA',
                    border: OutlineInputBorder(),
                  ),
                  keyboardType: TextInputType.number,
                  inputFormatters: [
                    FilteringTextInputFormatter.digitsOnly,
                    LengthLimitingTextInputFormatter(10),
                    // DateInputFormatter formats as DD/MM/AAAA
                    // imported from helpers/date_input_formatter.dart
                    // We'll import the file at top if missing
                    DateInputFormatter(),
                  ],
                  validator: (value) {
                    if (value == null || value.isEmpty) {
                      return 'Por favor, insira uma data';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 20.0),
                // Checkbox EAD
                CheckboxListTile(
                  title: const Text('Evento EAD (Online)'),
                  subtitle: const Text('Vagas ilimitadas, insira o link da reunião no campo de local'),
                  value: _isEAD,
                  onChanged: (bool? value) {
                    setState(() {
                      _isEAD = value ?? false;
                      _hasChanges = true;
                    });
                  },
                  controlAffinity: ListTileControlAffinity.leading,
                ),
                const SizedBox(height: 20.0),
                // Campo de Local/Link
                Text(
                  _isEAD ? 'Link da Reunião (Opcional)' : 'Local do Evento',
                  style: const TextStyle(fontSize: 16.0, fontWeight: FontWeight.w500),
                ),
                TextFormField(
                  controller: _locationController,
                  decoration: InputDecoration(
                    hintText: _isEAD 
                        ? 'Ex: https://meet.google.com/abc-defg-hij' 
                        : 'Digite o local',
                    border: const OutlineInputBorder(),
                    helperText: _isEAD 
                        ? 'Insira o link do Google Meet, Zoom, Teams, etc.' 
                        : null,
                  ),
                  validator: (value) {
                    if (!_isEAD && (value == null || value.isEmpty)) {
                      return 'Por favor, insira um local';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 20.0),
                
                // Campo de Capacidade (apenas se não for EAD)
                if (!_isEAD) ...[
                  const Text(
                    'Capacidade do Evento',
                    style: TextStyle(fontSize: 16.0, fontWeight: FontWeight.w500),
                  ),
                  TextFormField(
                    controller: _capacityController,
                    decoration: const InputDecoration(
                      hintText: 'Número máximo de participantes (deixe vazio para ilimitado)',
                      border: OutlineInputBorder(),
                    ),
                    keyboardType: TextInputType.number,
                  ),
                  const SizedBox(height: 20.0),
                ],
                // Data limite de inscrição
                const Text(
                  'Data Limite de Inscrição (opcional)',
                  style: TextStyle(fontSize: 16.0, fontWeight: FontWeight.w500),
                ),
                TextFormField(
                  controller: _buyLimitController,
                  decoration: const InputDecoration(
                    hintText: 'DD/MM/AAAA (padrão: 1 dia antes do evento)',
                    border: OutlineInputBorder(),
                  ),
                  keyboardType: TextInputType.number,
                  inputFormatters: [
                    FilteringTextInputFormatter.digitsOnly,
                    LengthLimitingTextInputFormatter(10),
                    DateInputFormatter(),
                  ],
                ),
                const SizedBox(height: 20.0),
                const Text(
                  'Descrição',
                  style: TextStyle(fontSize: 16.0, fontWeight: FontWeight.w500),
                ),
                TextFormField(
                  controller: _descriptionController,
                  decoration: const InputDecoration(
                    hintText: 'Detalhes do evento',
                    border: OutlineInputBorder(),
                  ),
                  maxLines: 3,
                  validator: (value) {
                    if (value != null && value.length > 200) {
                      return 'A descrição não pode exceder 200 caracteres';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 20.0),
                // Tipo de Pagamento
                const Text(
                  'Tipo de Pagamento',
                  style: TextStyle(fontSize: 16.0, fontWeight: FontWeight.w500),
                ),
                DropdownButtonFormField<String>(
                  value: _paymentType,
                  items: <String>['Gratuito', 'Pago']
                      .map<DropdownMenuItem<String>>((String value) {
                    return DropdownMenuItem<String>(
                      value: value,
                      child: Text(value),
                    );
                  }).toList(),
                  onChanged: (String? newValue) {
                    setState(() {
                      _paymentType = newValue ?? 'Gratuito';
                      _hasChanges = true;
                      // Limpar preço se mudar para gratuito
                      if (_paymentType == 'Gratuito') {
                        _priceController.clear();
                      }
                    });
                  },
                  decoration: const InputDecoration(
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 20.0),
                // Campo de Preço (apenas se for pago)
                if (_paymentType == 'Pago') ...[
                  const Text(
                    'Preço do Ingresso (R\$)',
                    style: TextStyle(fontSize: 16.0, fontWeight: FontWeight.w500),
                  ),
                  TextFormField(
                    controller: _priceController,
                    decoration: const InputDecoration(
                      hintText: 'Ex: 50.00',
                      border: OutlineInputBorder(),
                      prefixText: 'R\$ ',
                    ),
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    validator: (value) {
                      if (_paymentType == 'Pago' && (value == null || value.isEmpty)) {
                        return 'Por favor, insira o preço';
                      }
                      if (_paymentType == 'Pago' && double.tryParse(value!) == null) {
                        return 'Por favor, insira um valor válido';
                      }
                      return null;
                    },
                  ),
                  const SizedBox(height: 20.0),
                ],
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: <Widget>[
                    ElevatedButton(
                      onPressed: () => _confirmCreate(context),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.black,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(horizontal: 20.0, vertical: 10.0),
                      ),
                      child: const Text('Criar Evento'),
                    ),
                    ElevatedButton(
                      onPressed: () => _cancelCreate(context),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.grey,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(horizontal: 20.0, vertical: 10.0),
                      ),
                      child: const Text('Cancelar'),
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
