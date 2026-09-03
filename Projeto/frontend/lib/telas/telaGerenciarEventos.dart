// ==============================================================================
// TELA DE GERENCIAMENTO DE EVENTOS
// ==============================================================================
// Função: Gerenciar eventos criados e inscrições do usuário
// 
// Funcionalidades:
// - Duas abas (Tabs):
//   1. "Inscritos": Eventos nos quais o usuário está inscrito
//      • Lista via API /api/bff/event-wallets/user/{userId}
//      • Opção de cancelar inscrição
//   2. "Criados": Eventos criados pelo usuário
//      • Lista via API /api/bff/events?creator_id={userId}
//      • Visualização de participantes inscritos
// - Carregamento assíncrono de dados
// - Tratamento de estados (loading, erro, vazio)
// - Cards com informações de cada evento
// - Navegação de volta para tela principal
// ==============================================================================

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'telaHomePage.dart'; // HomePageData
import 'package:http/http.dart' as http;
import 'auth.dart';
import 'dart:convert';
import 'telaEdicaoEvento.dart';
import 'telaPrincipal.dart';
import 'modals.dart';

class ManageEventsScreen extends StatefulWidget {
  const ManageEventsScreen({super.key});

  @override
  State<ManageEventsScreen> createState() => _ManageEventsScreenState();
}

class _ManageEventsScreenState extends State<ManageEventsScreen> {
  List<Map<String, dynamic>> enrolledEvents = [];
  List<Map<String, dynamic>> createdEvents = [];

  @override
  void initState() {
    super.initState();
    // Verificar autenticação ao carregar a tela
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _checkAuthentication();
    });
    _fetchEvents();
  }

  void _checkAuthentication() {
    final user = Provider.of<HomePageData>(context, listen: false).user;

    // Se não estiver logado, redirecionar para tela principal
    if (user == null || user.isEmpty || user['user_id'] == null) {
      AppModals.showError(
        context,
        'Autenticação Necessária',
        'Você precisa estar logado para gerenciar eventos.',
        onOk: () {
          Navigator.pushReplacement(
            context,
            MaterialPageRoute(builder: (context) => const MainScreen()),
          );
        },
      );
    }
  }

  Future<void> _fetchEvents() async {
    final user = Provider.of<HomePageData>(context, listen: false).user;
    
    if (user == null || user['user_id'] == null) {
      print('❌ Usuário não encontrado ou sem ID');
      return;
    }
    
    final userId = user['user_id'];

    // Enrolled
    final enrolledResponse = await http.get(
      Uri.parse('/api/bff/event-wallets/user/$userId'),
      headers: ApiAuth.jsonHeaders(),
    );
    
    if (enrolledResponse.statusCode == 200) {
      final wallets = jsonDecode(enrolledResponse.body) as List;
      List<Map<String, dynamic>> events = [];
      for (var wallet in wallets) {
        final eventResponse = await http.get(
          Uri.parse('/api/bff/events/${wallet['eventId']}'),
          headers: ApiAuth.jsonHeaders(),
        );
        if (eventResponse.statusCode == 200) {
          final ev = jsonDecode(eventResponse.body) as Map<String, dynamic>;
          ev['_reservaStatus'] = wallet['status']; // confirmed | pending | expired | cancelled
          events.add(ev);
        }
      }
      setState(() {
        enrolledEvents = events;
      });
    }

    // Created
    final createdResponse = await http.get(
      Uri.parse('/api/bff/events?creator_id=$userId'),
      headers: ApiAuth.jsonHeaders(),
    );
    
    if (createdResponse.statusCode == 200) {
      final createdList = jsonDecode(createdResponse.body) as List;
      setState(() {
        createdEvents = createdList.cast<Map<String, dynamic>>();
      });
    }
  }

  String _statusLabel(dynamic status) {
    switch (status) {
      case 'confirmed':
        return 'confirmada';
      case 'pending':
        return 'pendente (aguardando confirmação)';
      case 'expired':
        return 'expirada';
      case 'cancelled':
        return 'cancelada';
      default:
        return status?.toString() ?? '-';
    }
  }

  void _showDetailsDialog(BuildContext context, Map<String, dynamic> event) {
    // Formatar data
    String formattedDate = 'Não informado';
    if (event['event_date'] != null) {
      try {
        final date = DateTime.parse(event['event_date'].toString());
        formattedDate = '${date.day.toString().padLeft(2, '0')}/${date.month.toString().padLeft(2, '0')}/${date.year} às ${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
      } catch (e) {
        formattedDate = event['event_date'].toString();
      }
    }
    
    // Determinar tipo de evento
    final isEAD = event['is_EAD'] ?? false;
    
    // Local (para eventos presenciais ou link para eventos online)
    String location = 'Não informado';
    IconData locationIcon = Icons.location_on;
    Color locationColor = Colors.red;
    
    if (isEAD) {
      // Evento online - mostrar link se disponível
      location = event['address'] != null && event['address'].toString().isNotEmpty 
          ? event['address'] 
          : 'Online';
      locationIcon = Icons.videocam;
      locationColor = Colors.green;
    } else {
      // Evento presencial - mostrar endereço
      location = event['address'] ?? 'Não informado';
      locationIcon = Icons.location_on;
      locationColor = Colors.red;
    }
    
    // Vagas
    final capacity = event['lot_quantity'];
    final enrolled = event['quantity'] ?? 0;
    String vacancyInfo = '';
    if (isEAD) {
      vacancyInfo = 'Vagas ilimitadas (Online)';
    } else if (capacity != null) {
      final available = capacity - enrolled;
      vacancyInfo = '$available vagas disponíveis de $capacity';
    } else {
      vacancyInfo = 'Capacidade não definida';
    }
    
    showDialog(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          title: Text(
            event['event_name'] ?? 'Evento',
            style: const TextStyle(fontWeight: FontWeight.bold),
          ),
          content: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                // Data
                Row(
                  children: [
                    const Icon(Icons.calendar_today, size: 20, color: Colors.blue),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'Data: $formattedDate',
                        style: const TextStyle(fontSize: 14),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                
                // Local (ou Link se for EAD)
                Row(
                  children: [
                    Icon(locationIcon, size: 20, color: locationColor),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '${isEAD ? "Link" : "Local"}: $location',
                        style: const TextStyle(fontSize: 14),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                
                // Vagas
                Row(
                  children: [
                    const Icon(Icons.people, size: 20, color: Colors.purple),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        vacancyInfo,
                        style: const TextStyle(fontSize: 14),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                
                // Descrição
                if (event['description'] != null && event['description'].toString().isNotEmpty) ...[
                  const Divider(),
                  const SizedBox(height: 8),
                  const Text(
                    'Descrição:',
                    style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    event['description'],
                    style: const TextStyle(fontSize: 13),
                  ),
                ],
              ],
            ),
          ),
          actions: <Widget>[
            TextButton(
              onPressed: () {
                Navigator.of(context).pop();
              },
              child: const Text('Fechar'),
            ),
          ],
        );
      },
    );
  }

  void _showCancelConfirmation(BuildContext context, Map<String, dynamic> event) {
    showDialog(
      context: context,
      builder: (BuildContext context) {
        return AlertDialog(
          title: const Text('Confirmar Cancelamento'),
          content: const Text('Tem certeza que deseja cancelar a inscrição neste evento?'),
          actions: <Widget>[
            TextButton(
              onPressed: () {
                Navigator.of(context).pop();
              },
              child: const Text('Não'),
            ),
            TextButton(
              onPressed: () async {
                Navigator.of(context).pop();
                final user = Provider.of<HomePageData>(context, listen: false).user;
                final userId = user['user_id'];
                final eventId = event['event_id'];
                final response = await http.delete(
                  Uri.parse('/api/bff/event-wallets?userId=$userId&eventId=$eventId'),
                  headers: ApiAuth.jsonHeaders(),
                );
                if (response.statusCode == 200) {
                  setState(() {
                    enrolledEvents.remove(event);
                  });
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('Inscrição cancelada!')),
                  );
                }
              },
              child: const Text('Sim'),
            ),
          ],
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Gerenciar Eventos'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            const Text(
              'Eventos Inscritos',
              style: TextStyle(fontSize: 20.0, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8.0),
            ...enrolledEvents.map((event) {
              return Card(
                elevation: 2.0,
                margin: const EdgeInsets.symmetric(vertical: 8.0),
                child: ListTile(
                  leading: Image.network(
                    event['imageUrl'] ?? 'https://i.imgur.com/error.png',
                    width: 60,
                    height: 60,
                    fit: BoxFit.cover,
                    errorBuilder: (context, error, stackTrace) => const Icon(Icons.error),
                  ),
                  title: Text(event['event_name']),
                  subtitle: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text('Data: ${event['event_date']}'),
                      Text('Local: ${event['address']}'),
                      Text('Tipo: ${event['type']}'),
                      if (event['_reservaStatus'] != null)
                        Text(
                          'Reserva: ${_statusLabel(event['_reservaStatus'])}',
                          style: TextStyle(
                            fontWeight: FontWeight.bold,
                            color: event['_reservaStatus'] == 'confirmed'
                                ? Colors.green
                                : (event['_reservaStatus'] == 'pending'
                                    ? Colors.orange
                                    : Colors.red),
                          ),
                        ),
                    ],
                  ),
                  trailing: PopupMenuButton<String>(
                    onSelected: (String value) {
                      if (value == 'Ver Detalhes') {
                        _showDetailsDialog(context, event);
                      } else if (value == 'Cancelar Inscrição') {
                        _showCancelConfirmation(context, event);
                      }
                    },
                    itemBuilder: (BuildContext context) => <PopupMenuEntry<String>>[
                      const PopupMenuItem<String>(
                        value: 'Ver Detalhes',
                        child: Text('Ver Detalhes'),
                      ),
                      const PopupMenuItem<String>(
                        value: 'Cancelar Inscrição',
                        child: Text('Cancelar Inscrição'),
                      ),
                    ],
                    icon: const Icon(Icons.more_vert),
                  ),
                ),
              );
            }).toList(),
            const SizedBox(height: 16.0),
            const Text(
              'Eventos Criados por Você',
              style: TextStyle(fontSize: 20.0, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8.0),
            ...createdEvents.map((event) {
              return Card(
                elevation: 2.0,
                margin: const EdgeInsets.symmetric(vertical: 8.0),
                child: ListTile(
                  leading: Image.network(
                    event['imageUrl'] ?? 'https://i.imgur.com/error.png',
                    width: 60,
                    height: 60,
                    fit: BoxFit.cover,
                    errorBuilder: (context, error, stackTrace) => const Icon(Icons.error),
                  ),
                  title: Text(event['event_name']),
                  subtitle: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text('Data: ${event['event_date']}'),
                      Text('Local: ${event['address']}'),
                      Text('Tipo: ${event['type']}'),
                    ],
                  ),
                  trailing: PopupMenuButton<String>(
                    onSelected: (String value) {
                      if (value == 'Ver Detalhes') {
                        _showDetailsDialog(context, event);
                      } else if (value == 'Gerenciar Evento') {
                        Navigator.push(
                          context,
                          MaterialPageRoute(
                            builder: (context) => EditEventScreen(event: event),
                          ),
                        );
                      }
                    },
                    itemBuilder: (BuildContext context) => <PopupMenuEntry<String>>[
                      const PopupMenuItem<String>(
                        value: 'Ver Detalhes',
                        child: Text('Ver Detalhes'),
                      ),
                      const PopupMenuItem<String>(
                        value: 'Gerenciar Evento',
                        child: Text('Gerenciar Evento'),
                      ),
                    ],
                    icon: const Icon(Icons.more_vert),
                  ),
                ),
              );
            }).toList(),
          ],
        ),
      ),
    );
  }
}
