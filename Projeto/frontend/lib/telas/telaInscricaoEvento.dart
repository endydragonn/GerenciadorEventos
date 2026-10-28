// ==============================================================================
// TELA DE INSCRIÇÃO EM EVENTO
// ==============================================================================
// Função: Realizar inscrição do usuário em um evento específico
// 
// Funcionalidades:
// - Exibição de detalhes do evento selecionado
// - Busca de detalhes do evento via API se necessário
// - Verificação de autenticação do usuário
// - Criação de inscrição via API (/bff/event-wallets)
// - Associação automática usuário-evento (userId + eventId)
// - SnackBars para feedback de sucesso/erro
// - Tratamento de erros (já inscrito, evento lotado, erro de conexão)
// - Navegação para tela principal após inscrição bem-sucedida
// ==============================================================================

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'auth.dart';
import 'package:intl/intl.dart';
import 'telaHomePage.dart'; // HomePageData
import 'telaPrincipal.dart'; // MainScreen
import 'package:http/http.dart' as http;
import 'modals.dart'; // SnackBars
import 'dart:convert';
import '../config/app_config.dart';

class EventRegistrationScreen extends StatefulWidget {
  final int? eventId;
  final Map<String, dynamic>? event;
  
  const EventRegistrationScreen({
    super.key,
    this.eventId,
    this.event,
  }) : assert(eventId != null || event != null, 'eventId ou event deve ser fornecido');

  @override
  State<EventRegistrationScreen> createState() => _EventRegistrationScreenState();
}

class _EventRegistrationScreenState extends State<EventRegistrationScreen> {
  Map<String, dynamic>? _eventData;
  bool _isLoading = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _checkAuthentication();
    if (widget.event != null) {
      _eventData = widget.event;
    } else if (widget.eventId != null) {
      _loadEventDetails();
    }
  }

  Future<void> _checkAuthentication() async {
    final prefs = await SharedPreferences.getInstance();
    final token = prefs.getString('auth_token');
    if (token == null || token.isEmpty) {
      if (mounted) {
        Navigator.pushReplacementNamed(context, '/login');
      }
    }
  }

  Future<void> _loadEventDetails() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      // Usar ApiAuth para obter headers (centraliza token JWT)
      final headers = ApiAuth.jsonHeaders();
      final response = await http.get(
        Uri.parse('${AppConfig.apiBaseUrl}/bff/events/${widget.eventId}'),
        headers: headers,
      );

      if (response.statusCode == 200) {
        setState(() {
          _eventData = jsonDecode(utf8.decode(response.bodyBytes));
          _isLoading = false;
        });
      } else {
        setState(() {
          _errorMessage = 'Erro ao carregar evento: ${response.statusCode}';
          _isLoading = false;
        });
      }
    } catch (e) {
      setState(() {
        _errorMessage = 'Erro de conexão: $e';
        _isLoading = false;
      });
    }
  }

  DateTime? _parseEventDate(dynamic dateStr) {
    if (dateStr == null) return null;
    try {
      return DateTime.parse(dateStr.toString());
    } catch (e) {
      return null;
    }
  }

  String _formatEventDate(dynamic dateStr) {
    DateTime? date = _parseEventDate(dateStr);
    if (date == null) return 'Data não disponível';
    return DateFormat('dd/MM/yyyy \'às\' HH:mm').format(date);
  }

  Future<void> _submitRegistration(BuildContext context) async {
    if (_eventData == null) return;
    
    final homePageData = Provider.of<HomePageData>(context, listen: false);
    final user = homePageData.user;

    // Verificar se o usuário está logado
    if (user.isEmpty) {
      AppModals.showError(
        context,
        'Autenticação Necessária',
        'Você precisa estar logado para se inscrever em um evento',
      );
      return;
    }

    final enrollment = {
      'userId': user['user_id'],
      'eventId': _eventData!['event_id'],
    };

    try {
      final headers = ApiAuth.jsonHeaders();

      // Passo 1: reservar a vaga (UPDATE condicional atômico no backend -> status 'pending')
      final reserveResp = await http.post(
        Uri.parse('${AppConfig.apiBaseUrl}/bff/event-wallets'),
        headers: headers,
        body: jsonEncode(enrollment),
      );

      if (!context.mounted) return;

      if (reserveResp.statusCode != 201 && reserveResp.statusCode != 200) {
        String errorMsg = 'Você já está inscrito neste evento ou o evento está lotado.';
        try {
          errorMsg = utf8.decode(reserveResp.bodyBytes);
        } catch (_) {}
        AppModals.showError(
          context,
          reserveResp.statusCode == 400 ? 'Inscrição Inválida' : 'Erro na Inscrição',
          errorMsg,
        );
        return;
      }

      // Passo 2: confirmar a reserva (pending -> confirmed).
      // Obs.: se o usuário fechar o app antes deste passo, o job de expiração do backend
      // devolve a vaga automaticamente após eventcap.max_time_in_line.
      final confirmResp = await http.post(
        Uri.parse('${AppConfig.apiBaseUrl}/bff/event-wallets/confirm'),
        headers: headers,
        body: jsonEncode(enrollment),
      );

      if (!context.mounted) return;

      if (confirmResp.statusCode == 200) {
        AppModals.showSuccess(
          context,
          'Inscrição Confirmada',
          'Você foi inscrito no evento com sucesso!',
          onOk: () {
            Navigator.pushReplacement(
              context,
              MaterialPageRoute(builder: (context) => const MainScreen()),
            );
          },
        );
      } else {
        String errorMsg = 'Não foi possível confirmar a reserva. Tente novamente.';
        try {
          errorMsg = utf8.decode(confirmResp.bodyBytes);
        } catch (_) {}
        AppModals.showError(context, 'Confirmação Pendente', errorMsg);
      }
    } catch (e) {
      if (!context.mounted) return;
      AppModals.showError(
        context,
        'Erro de Conexão',
        'Não foi possível conectar ao servidor. Verifique sua conexão.',
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return Scaffold(
        appBar: AppBar(
          title: const Text('Inscrição em Evento'),
          backgroundColor: Colors.blue,
        ),
        body: const Center(child: CircularProgressIndicator()),
      );
    }

    if (_errorMessage != null) {
      return Scaffold(
        appBar: AppBar(
          title: const Text('Inscrição em Evento'),
          backgroundColor: Colors.blue,
        ),
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.error_outline, size: 64, color: Colors.red),
              const SizedBox(height: 16),
              Text(_errorMessage!, textAlign: TextAlign.center),
              const SizedBox(height: 16),
              ElevatedButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Voltar'),
              ),
            ],
          ),
        ),
      );
    }

    if (_eventData == null) {
      return Scaffold(
        appBar: AppBar(
          title: const Text('Inscrição em Evento'),
          backgroundColor: Colors.blue,
        ),
        body: const Center(child: Text('Evento não encontrado')),
      );
    }

    final event = _eventData!;
    final eventId = event['event_id'];
    final isEAD = event['is_EAD'] == true || event['ead'] == true;
    // O backend serializa o POJO Event: lot_quantity = capacidade, quantity = vagas disponíveis.
    // -1 (ou null) = evento sem limite de vagas.
    final vagasRaw = event['quantity'];
    final ilimitado = vagasRaw == null || vagasRaw == -1;
    final vagasDisponiveis = ilimitado ? null : vagasRaw;
    final temVagas = ilimitado || vagasDisponiveis > 0;
    
    return Scaffold(
      appBar: AppBar(
        title: const Text('Inscrição em Evento'),
        backgroundColor: Colors.blue,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            // Card com detalhes do evento
            Card(
              elevation: 4,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Imagem do evento
                  if (eventId != null)
                    ClipRRect(
                      borderRadius: const BorderRadius.vertical(top: Radius.circular(12)),
                      child: Image.network(
                        '${AppConfig.apiBaseUrl}/bff/events/$eventId/image',
                        width: double.infinity,
                        height: 200,
                        fit: BoxFit.cover,
                        errorBuilder: (context, error, stackTrace) {
                          return Container(
                            width: double.infinity,
                            height: 200,
                            color: Colors.grey[300],
                            child: const Icon(
                              Icons.event,
                              size: 80,
                              color: Colors.grey,
                            ),
                          );
                        },
                      ),
                    ),
                  
                  Padding(
                    padding: const EdgeInsets.all(16.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // Nome do evento
                        Text(
                          event['event_name'] ?? 'Sem nome',
                          style: const TextStyle(
                            fontSize: 24,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 16),
                        
                        // Data
                        Row(
                          children: [
                            const Icon(Icons.calendar_today, size: 20, color: Colors.blue),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                _formatEventDate(event['event_date']),
                                style: const TextStyle(fontSize: 16),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 12),
                        
                        // Local ou Link
                        Row(
                          children: [
                            Icon(
                              isEAD ? Icons.computer : Icons.location_on,
                              size: 20,
                              color: Colors.blue,
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                isEAD
                                    ? 'Evento Online (EAD)'
                                    : event['address'] ?? 'Local não informado',
                                style: const TextStyle(fontSize: 16),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 12),
                        
                        // Vagas
                        Row(
                          children: [
                            Icon(
                              temVagas ? Icons.check_circle : Icons.cancel,
                              size: 20,
                              color: temVagas ? Colors.green : Colors.red,
                            ),
                            const SizedBox(width: 8),
                            Text(
                              vagasDisponiveis != null
                                  ? (temVagas
                                      ? 'Vagas disponíveis: $vagasDisponiveis'
                                      : 'Evento lotado')
                                  : 'Vagas ilimitadas',
                              style: TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.bold,
                                color: temVagas ? Colors.green : Colors.red,
                              ),
                            ),
                          ],
                        ),
                        
                        if (event['description'] != null && 
                            event['description'].toString().isNotEmpty) ...[
                          const SizedBox(height: 16),
                          const Divider(),
                          const SizedBox(height: 8),
                          const Text(
                            'Descrição:',
                            style: TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            event['description'],
                            style: const TextStyle(fontSize: 14),
                          ),
                        ],
                      ],
                    ),
                  ),
                ],
              ),
            ),
            
            const SizedBox(height: 24),
            
            // Botões
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                Expanded(
                  child: ElevatedButton(
                    onPressed: temVagas ? () => _submitRegistration(context) : null,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.green,
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(vertical: 16),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(8),
                      ),
                      disabledBackgroundColor: Colors.grey,
                    ),
                    child: const Text(
                      'Confirmar Inscrição',
                      style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                    ),
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: OutlinedButton(
                    onPressed: () => Navigator.pop(context),
                    style: OutlinedButton.styleFrom(
                      foregroundColor: Colors.red,
                      padding: const EdgeInsets.symmetric(vertical: 16),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(8),
                      ),
                      side: const BorderSide(color: Colors.red),
                    ),
                    child: const Text(
                      'Cancelar',
                      style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                    ),
                  ),
                ),
              ],
            ),
            
            if (!temVagas) ...[
              const SizedBox(height: 16),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.orange[50],
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: Colors.orange),
                ),
                child: const Row(
                  children: [
                    Icon(Icons.info_outline, color: Colors.orange),
                    SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        'Este evento não possui mais vagas disponíveis.',
                        style: TextStyle(color: Colors.orange),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
