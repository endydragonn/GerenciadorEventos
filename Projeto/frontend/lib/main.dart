import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:http/http.dart' as http;
import 'dart:convert';
import 'telas/telaHomePage.dart';
import 'telas/telaPrincipal.dart'; // MainScreen
import 'telas/auth.dart';
import 'config/app_config.dart';

void main() async {
  debugPrint('🏁 main() - Iniciando aplicação');
  WidgetsFlutterBinding.ensureInitialized();
  debugPrint('⚙️ WidgetsFlutterBinding inicializado');
  
  // Carregar token salvo antes de iniciar o app
  await ApiAuth.initialize();
  
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  // Recuperar dados do usuário se houver token
  Future<Map<String, dynamic>?> _loadUserFromToken() async {
    if (ApiAuth.token == null || ApiAuth.token!.isEmpty) {
      return null;
    }

    try {
      final headers = ApiAuth.jsonHeaders();
      
      // Tentar fazer uma requisição para obter os dados do usuário atual
      final response = await http.get(
        Uri.parse('${AppConfig.apiBaseUrl}/bff/users/me'),
        headers: headers,
      );

      if (response.statusCode == 200) {
        final userData = jsonDecode(response.body) as Map<String, dynamic>;
        return userData;
      }
    } catch (e) {
      // Se falhar, limpar o token inválido
      await ApiAuth.clearToken();
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider<HomePageData>(
      create: (_) => HomePageData(),
      child: MaterialApp(
        title: 'Organizador de Eventos',
        theme: ThemeData(
          colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
          useMaterial3: true,
        ),
        home: FutureBuilder<Map<String, dynamic>?>(
          future: _loadUserFromToken(),
          builder: (context, snapshot) {
            // Enquanto carrega, mostrar splash
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Scaffold(
                body: Center(child: CircularProgressIndicator()),
              );
            }

            // Se carregou usuário, restaurar no Provider e ir para MainScreen
            if (snapshot.hasData && snapshot.data != null) {
              WidgetsBinding.instance.addPostFrameCallback((_) {
                Provider.of<HomePageData>(context, listen: false)
                    .setUser(snapshot.data!);
              });
              // Retornar MainScreen se usuário foi restaurado
              return const MainScreen();
            }

            // Sem sessão válida, mostrar HomeScreen (que tem botão de login)
            return const HomeScreen();
          },
        ),
      ),
    );
  }
}

// A Home real está em telas/telaHomePage.dart (HomeScreen)
