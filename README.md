# Documentação Gerenciamento de Eventos

Esta documentação unificada descreve o software web completo para gerenciamento de eventos, como palestras, shows ou workshops. O sistema é composto por duas partes principais: o **back-end** (o "cérebro" que lida com lógica, dados e segurança, construído em Java com Spring Boot) e o **front-end** (a "fachada" visível no navegador ou app, feita com Flutter, que permite interações como login, criação de eventos e inscrições).

O software permite que usuários se cadastrem, loguem, criem e gerenciem eventos, busquem e se inscrevam neles. O back-end cuida do armazenamento seguro de dados em um banco PostgreSQL e usa Docker para fácil implantação. O front-end oferece telas intuitivas, conectando-se ao back-end via API para trocar informações.

Pense no sistema como um clube de eventos: o back-end é a administração nos bastidores (guarda listas, verifica identidades), enquanto o front-end é a recepção e salão (onde você interage e vê tudo).

## Sumário

- [Back-end](#back-end)
  - [Introdução ao Sistema](#introdução-ao-sistema)
  - [Classes de Dados (POJOs)](#classes-de-dados-pojos)
  - [Camada de Acesso a Dados (Repositories)](#camada-de-acesso-a-dados-repositories)
  - [Camada de Lógica de Negócio (Services)](#camada-de-lógica-de-negócio-services)
  - [Camada de Interface (Controllers e BFF)](#camada-de-interface-controllers-e-bff)
  - [Como Tudo Funciona (Passo a Passo Simples)](#como-tudo-funciona-passo-a-passo-simples)
  - [Regras Importantes que o Sistema Segue (Para Não Dar Bagunça)](#regras-importantes-que-o-sistema-segue-para-não-dar-bagunça)
  - [Resumo em Uma Frase (Para Quem Quiser Entender Rápido)](#resumo-em-uma-frase-para-quem-quiser-entender-rápido)
  - [As Partes do Sistema (Explicadas Como Pessoas)](#as-partes-do-sistema-explicadas-como-pessoas)
  - [Configuração do Banco de Dados](#configuração-do-banco-de-dados)
  - [Funções Auxiliares no Banco de Dados](#funções-auxiliares-no-banco-de-dados)
  - [Tabelas do Banco de Dados](#tabelas-do-banco-de-dados)
  - [Segurança e Autenticação](#segurança-e-autenticação)
  - [Inicialização do Aplicativo](#inicialização-do-aplicativo)
  - [Popular o Banco de Dados (Seeding)](#popular-o-banco-de-dados-seeding)
  - [Scripts para Gerenciar Dados](#scripts-para-gerenciar-dados)
  - [Scripts para Testes](#scripts-para-testes)
  - [Configuração com Docker](#configuração-com-docker)
  - [Configuração do Projeto Java (pom.xml)](#configuração-do-projeto-java-pomxml)
  - [Guia de Comandos](#guia-de-comandos)
  - [Considerações Finais](#considerações-finais)
- [Front-end](#front-end)
  - [Introdução ao Projeto](#introdução-ao-projeto-1)
  - [Configuração de Implantação (Dockerfile e nginx.conf)](#configuração-de-implantação-dockerfile-e-nginxconf)
  - [Dependências e Configuração (pubspec.yaml)](#dependências-e-configuração-pubspecyaml)
  - [Página de Entrada (index.html)](#página-de-entrada-indexhtml)
  - [Código Principal do App (blocoInteiro.dart)](#código-principal-do-app-blocointeirodart)
  - [Considerações Finais](#considerações-finais-1)

## Back-end

# Documentação Back-end

Esta documentação completa reúne todas as partes do back-end do sistema de gerenciamento de eventos, incluindo gerenciamento de usuários, eventos, carteiras de inscrições, segurança, configuração do banco de dados, inicialização, seeding de dados e setup com Docker. O sistema é construído em Java com Spring Boot, focando em uma API REST para gerenciar eventos como palestras, shows ou workshops.

Imagine o sistema como uma casa organizada ou um clube de eventos:
- As **classes de dados** são como móveis ou cartões que guardam informações.
- Os **repositórios** são como o porão ou arquivo onde as informações são armazenadas permanentemente.
- Os **serviços** são como regras da casa ou gerente que verificam se tudo está certo.
- Os **controladores** são como portas de entrada que recebem pedidos.
- **Segurança**: Porteiros que verificam identidades.
- **Banco de dados**: Um armário digital ou agenda onde guardam listas de membros e eventos.
- **Inicialização**: A chave que abre a porta.
- **Docker**: Caixas mágicas que isolam partes do sistema para fácil instalação e teste.

O sistema permite criar contas de usuários, criar eventos (presenciais ou online), atualizar detalhes, deletar itens e gerenciar inscrições via carteiras digitais. Verifica regras como nomes únicos e datas válidas. Não é completo (sem interface gráfica), mas foca na lógica principal.

Usuários podem se cadastrar, logar, criar eventos, e o sistema responde a perguntas como “Quais eventos eu já comprei ou estou inscrito?”. Cada usuário tem uma carteira digital automática para guardar "ingressos" (inscrições).

O banco é PostgreSQL, configurado para UTF-8 (suporte a acentos). O app roda em containers Docker: um para o banco e outro para o backend Java.

Se algo parecer confuso, imagine processos do dia a dia, como gerenciar uma festa. Se precisar de exemplos de uso ou mais detalhes, é só pedir!

## Introdução ao Sistema

Este é um aplicativo para gerenciar eventos e usuários. Usuários podem se cadastrar, logar, criar eventos (presenciais ou online), atualizar detalhes e deletar itens. O sistema verifica regras, como não permitir nomes repetidos ou datas inválidas, para evitar erros.

- **Usuários**: Pessoas que usam o sistema, como organizadores de eventos.
- **Eventos**: Atividades como festas ou seminários, com detalhes como data, local e capacidade.
- **Banco de dados**: Um lugar para guardar informações permanentemente, como uma agenda digital.
- **API**: Uma forma de o sistema se comunicar com o mundo exterior, como um site ou app móvel, usando endereços web (chamados endpoints).

O sistema não é completo (por exemplo, não tem interface gráfica), mas foca na lógica principal.

Este novo pedaço do sistema serve para responder uma pergunta muito comum em aplicativos de eventos:

> “Quais eventos eu já comprei ou estou inscrito?”

É como se cada usuário tivesse uma **carteira digital** (um tipo de “mochila”) onde ficam guardados todos os eventos que ele adquiriu ou se inscreveu.  
Pense na carteira física que você leva no bolso: dentro dela tem ingressos de cinema, shows ou palestras. Aqui funciona igual, só que tudo é digital.

Se você já usou apps como Sympla, Eventbrite ou Ticketmaster, a parte “Meus ingressos” funciona exatamente assim por trás dos panos.  
Agora você sabe como ela é feita!

## Classes de Dados (POJOs)

Essas são como "caixas" que guardam informações. Elas não fazem nada sozinhas, só armazenam dados. São chamadas POJOs (Plain Old Java Objects), que significa "objetos simples em Java".

### User (Usuário)
Representa uma pessoa no sistema. É como um cartão de identificação com detalhes pessoais.

- **Campos principais**:
  - `id`: Um número único para identificar o usuário (gerado automaticamente).
  - `name`: Nome da pessoa.
  - `email`: Endereço de email (obrigatório e único).
  - `fone`: Telefone (opcional, mas deve ser único se informado).
  - `password`: Senha (guardada de forma segura, "codificada" para ninguém ler).
  - `birthDate`: Data de nascimento (no formato "ano-mês-dia").
  - `isAdmin`: Indica se o usuário é administrador (true/false, padrão false).
  - `isActive`: Indica se a conta está ativa (true/false, padrão true).
  - `createdAt` e `updatedAt`: Datas automáticas de quando a conta foi criada ou atualizada.

Exemplo: Um usuário chamado "João" com email "joao@email.com" e senha "123".

### Event (Evento)
Representa um evento. É como um convite com todos os detalhes.

- **Campos principais**:
  - `event_id`: Número único do evento (gerado automaticamente).
  - `creator_id`: ID do usuário que criou o evento.
  - `event_name`: Nome do evento (obrigatório e único).
  - `is_EAD`: Indica se é online (true) ou presencial (false).
  - `address`: Endereço (obrigatório se presencial).
  - `event_date`: Data e hora do evento (obrigatória).
  - `buy_time_limit`: Data limite para comprar ingressos (padrão: mesma do evento).
  - `lot_quantity`: Capacidade máxima (opcional, não pode ser negativa).
  - `quantity`: Quantidade de ingressos disponíveis (não pode ser negativa).
  - `description`: Descrição do evento.
  - `presenters`: Lista de nomes de apresentadores (pode ser vazia).
  - `image_data`: Imagem do evento (em formato bytes, como uma foto JPG).
  - `createdAt` e `updatedAt`: Datas automáticas de criação e atualização.

Exemplo: Um evento "Palestra sobre Java" online, criado por João, em 2025-12-01.

### MyWallet (Carteira)
- Toda pessoa que se cadastra no sistema ganha automaticamente **uma carteira** (chamada `MyWallet`).
- Essa carteira é criada sozinha (por um “gatilho” no banco de dados) no momento em que o usuário é criado — você não precisa fazer nada.
- A carteira tem apenas um número: o ID do usuário. Ela é como uma pasta com o nome da pessoa.

Exemplo real:  
Quando o João (ID = 5) se cadastra, o sistema cria automaticamente uma pasta chamada “Carteira do João (ID 5)”.

### EventWallet (Ingresso na Carteira)
- Cada vez que o usuário “compra” ou “se inscreve” em um evento, o sistema coloca um **ingresso digital** dentro da carteira dele.
- Esse ingresso é representado pela classe `EventWallet`.
- Ele é apenas uma ligação: “Usuário 5 tem o Evento 23”.

Exemplo:  
João se inscreveu na “Palestra de Java” (ID do evento = 23).  
O sistema cria um papelzinho que diz:  
“Usuário 5 tem Evento 23” → esse papelzinho fica dentro da carteira do João.

## Camada de Acesso a Dados (Repositories)

Essas partes lidam com o banco de dados: salvam, buscam, atualizam ou deletam informações. É como um bibliotecário que organiza livros em prateleiras.

### UserRepository
Cuida dos usuários no banco de dados.

- **Funções principais**:
  - `save`: Salva um novo usuário, gerando ID e datas automáticas. Adiciona valores padrão se algo faltar (como data de nascimento atual).
  - `update`: Atualiza um usuário existente, mantendo senha antiga se não for mudada.
  - `findById` e `findByEmail`: Busca um usuário por ID ou email.
  - `emailExists`, `nameExists`, `foneExists`: Verifica se email, nome ou telefone já estão em uso.
  - `softDelete`: Desativa uma conta (não apaga de verdade, só marca como inativa).

Usa conexões ao banco para executar comandos SQL (linguagem para bancos de dados).

### EventRepository
Cuida dos eventos no banco de dados.

- **Funções principais**:
  - `save`: Salva um novo evento, gerando ID e datas. Usa data do evento como limite de compra se não informado.
  - `update`: Atualiza um evento existente, sem mudar campos não informados.
  - `findById` e `findByName`: Busca por ID ou nome.
  - `nameExists`: Verifica se o nome do evento já existe.
  - `findAll`: Lista todos os eventos.
  - `delete`: Apaga um evento de verdade.

Lida com imagens e datas, convertendo formatos para o banco.

### MyWalletRepository
O funcionário que guarda e busca carteiras no armário do banco | Guarda-volumes das carteiras

### EventWalletRepository
O funcionário que coloca e tira ingressos das carteiras | Organizador de ingressos

## Camada de Lógica de Negócio (Services)

Aqui estão as regras do sistema. Elas verificam se algo é válido antes de salvar, como checar se um nome já existe ou se datas fazem sentido. É como um gerente que aprova ações.

### UserService
Gerencia regras para usuários.

- **Funções principais**:
  - `createUser`: Cria usuário, verifica campos obrigatórios (nome, email), checa duplicatas e codifica a senha.
  - `updateUser`: Atualiza, mas exige ID.
  - `findById` e `findByEmail`: Busca usuários.
  - `deactivateUser`: Desativa conta.
  - `getValidationErrors`: Lista erros de validação (ex: "Email já existe").
  - `login`: Verifica email e senha.
  - `changePassword`: Muda senha, verificando a antiga e o tamanho da nova.

Usa o repositório para acessar dados e adiciona lógica extra, como codificação de senha.

### EventService
Gerencia regras para eventos.

- **Funções principais**:
  - `createEvent`: Cria evento, aplica padrões (como limite de compra), verifica obrigatórios, regras (datas lógicas) e duplicatas.
  - `updateEvent`: Atualiza, sem aplicar padrões automáticos.
  - `findById` e `findByName`: Busca eventos.
  - `delete`: Apaga evento.
  - `getValidationErrors`: Lista erros (ex: "Nome já existe" ou "Endereço obrigatório").
  - `searchEvents`: Busca eventos por termo (simples, por nome).
  - `findByCreatorId`: Lista eventos de um criador.

Inclui buscas simples em memória (não otimizadas para muitos dados).

### MyWalletService
O atendente que responde: “Você tem carteira?” | Atendente da carteira

### EventWalletService
O atendente principal que faz tudo: verifica regras, evita duplicatas, etc. | Gerente da inscrição

## Camada de Interface (Controllers e BFF)

Essas partes recebem pedidos da internet (como de um navegador ou app) e respondem. São endpoints de uma API REST, que usa HTTP (protocolo web).

### UserController
Endpoints para usuários.

- **POST /api/users**: Cria usuário novo.
- **GET /api/users/{id}**: Busca por ID.
- **GET /api/users/email/{email}**: Busca por email.
- **PUT /api/users/{id}**: Atualiza.
- **DELETE /api/users/{id}**: Desativa.
- **POST /api/users/validate**: Verifica sem criar.

Retorna respostas como "OK" (200) ou erros (400, 500).

### EventController
Endpoints para eventos.

- **POST /api/events**: Cria evento.
- **POST /api/events/validate**: Verifica sem criar.
- **GET /api/events/{id}**: Busca por ID.
- **PUT /api/events/{id}**: Atualiza.
- **DELETE /api/events/{id}**: Apaga.

Faz validações básicas antes de chamar o serviço.

### MyWalletController
A porta de entrada: quem pergunta “Me mostra a carteira do usuário 5?” | Recepção da carteira

- **Endereço**: `GET /api/wallets/5` | Mostra a carteira do usuário número 5 | “Quero ver a carteira do João”
- **POST /api/wallets/validate**: Só verifica se os dados da carteira estão certos | Testar antes de usar

### EventWalletController
A porta para adicionar ingressos: “Coloca o evento 23 na carteira do usuário 5” | Balcão de inscrição

- **POST /api/event-wallets**: Adiciona um evento à carteira de alguém | “João se inscreveu na palestra”
- **POST /api/event-wallets/validate**: Só verifica se pode adicionar (sem fazer nada) | Testar antes de inscrever

### Aplicativo BFF (Backend for Frontend)
BFF significa "Backend para Frontend" — é como um "garçom" que une tudo para apps ou sites usarem facilmente. Inclui controladores para usuários, eventos, carteiras, etc.

- **O que faz**: É o app principal que junta todos os controladores em um só lugar. Inicia o sistema e escaneia pacotes para encontrar peças.
- **Como funciona**: Contém classes internas (como mini-apps) para:
  - **Usuários**: Criar, logar, atualizar, deletar. Inclui login com JWT (gera bilhete ao logar certo).
  - **Eventos**: Criar, atualizar, deletar, buscar (por nome, ID ou criador). Validações básicas antes de salvar.
  - **Carteiras (MyWallet)**: Buscar ou garantir que exista para um usuário.
  - **Inscrições (EventWallet)**: Adicionar/remover inscrição em evento, listar por usuário.
- **Por quê?**: Facilita a integração com interfaces (como um site). Une rotas em "/bff/" para serem chamadas por apps.

Exemplo: Em vez de ir a várias lojas, o BFF é uma loja única que vende tudo — usuários pedem por "/bff/users/login" e recebem um bilhete.

## Como Tudo Funciona (Passo a Passo Simples)

### Para Usuários e Eventos (Geral)
O sistema separa as partes para ficar organizado e fácil de manter.

### Para Carteiras e Inscrições
1. Quando alguém se cadastra  
→ O banco de dados cria automaticamente uma carteira vazia para essa pessoa.

2. Quando a pessoa se inscreve em um evento  
→ O sistema verifica:  
- A carteira da pessoa existe? → Sim  
- O evento existe? → Sim  
- Ela já tem esse evento na carteira? → Não (não pode ter duplicado)  

→ Se tudo estiver certo, coloca o “ingresso” (EventWallet) dentro da carteira.

3. Quando a pessoa quer ver seus eventos  
→ O sistema abre a carteira dela e lista todos os ingressos que estão lá.

## Regras Importantes que o Sistema Segue (Para Não Dar Bagunça)

1. Ninguém pode ter o mesmo evento duas vezes na carteira.
2. Só pode adicionar evento se o usuário e o evento realmente existirem.
3. A carteira é criada automaticamente — ninguém precisa criar na mão.
4. Se o usuário for apagado, a carteira e todos os ingressos somem juntos (limpeza automática).

## Resumo em Uma Frase (Para Quem Quiser Entender Rápido)

> Cada usuário tem uma carteira digital automática.  
> Quando ele se inscreve em um evento, o sistema coloca um “ingresso” nessa carteira.  
> Depois, é só abrir a carteira para ver todos os eventos que a pessoa tem.

## As Partes do Sistema (Explicadas Como Pessoas)

| Nome da classe | O que ela faz na vida real | Nome fácil |
|----------------|----------------------------|----------|
| `MyWallet` | A carteira em si (a pasta vazia que cada usuário tem) | A carteira |
| `EventWallet` | O ingresso que fica dentro da carteira | O ingresso |
| `MyWalletRepository` | O funcionário que guarda e busca carteiras no armário do banco | Guarda-volumes das carteiras |
| `EventWalletRepository` | O funcionário que coloca e tira ingressos das carteiras | Organizador de ingressos |
| `MyWalletService` | O atendente que responde: “Você tem carteira?” | Atendente da carteira |
| `EventWalletService` | O atendente principal que faz tudo: verifica regras, evita duplicatas, etc. | Gerente da inscrição |
| `MyWalletController` | A porta de entrada: quem pergunta “Me mostra a carteira do usuário 5?” | Recepção da carteira |
| `EventWalletController` | A porta para adicionar ingressos: “Coloca o evento 23 na carteira do usuário 5” | Balcão de inscrição |

## Configuração do Banco de Dados

Essa parte cuida de conectar o sistema a um "armário digital" (banco de dados) onde as informações são guardadas de forma permanente.

### DatabaseConnection
- **O que faz**: É como uma chave que abre a porta do armário. Usa um endereço (URL), nome de usuário e senha para conectar ao banco.
- **Como funciona**: Quando o sistema precisa salvar ou ler algo (como um novo usuário), ele "liga" para o banco usando essas credenciais. Se der errado, avisa com um erro.
- **Por quê?**: Sem isso, o sistema não consegue guardar nada para sempre — tudo sumiria ao desligar o computador.

Exemplo: Imagine ligar para um amigo: você precisa do número (URL), nome (usuário) e talvez uma senha secreta.

Esta documentação explica a parte do "armário digital" (banco de dados) do sistema de gerenciamento de eventos. O banco é como uma grande agenda organizada onde guardamos informações sobre usuários, eventos e inscrições. Usamos um programa chamado PostgreSQL, que é gratuito e seguro.

Pense no banco de dados como uma biblioteca:
- **Tabelas**: São como prateleiras ou gavetas, cada uma guardando um tipo de informação (ex: uma gaveta para usuários, outra para eventos).
- **Linhas**: Cada item na gaveta é um cartão com detalhes específicos (ex: nome, email de um usuário).
- **Regras (constraints)**: Impedem bagunça, como não permitir dois cartões com o mesmo email.
- **Funções e triggers**: São "robôs" automáticos que fazem tarefas sozinhos, como criar uma carteira quando um usuário é adicionado.

O banco é configurado para rodar em uma "caixa mágica" (container Docker), facilitando a instalação. Tudo é em português (UTF-8) para suportar acentos.

## Funções Auxiliares no Banco de Dados

Essas são "receitas automáticas" (funções) que o banco usa para tarefas repetitivas.

### create_wallet_for_user
- **O que faz**: Cria automaticamente uma carteira (entrada na tabela MyWallet) quando um novo usuário é adicionado.
- **Como funciona**: É chamada por um "gatilho" (trigger) na tabela de usuários. Pega o ID do novo usuário e insere na tabela de carteiras.
- **Por quê?**: Para que todo usuário tenha uma carteira sem precisar lembrar de criar manualmente. É como um robô que, ao cadastrar um cliente em uma loja, já cria uma conta fidelidade.
- **Exemplo**: Se você adiciona "João", o robô cria "Carteira de João" imediatamente.

## Tabelas do Banco de Dados

Essas são as "gavetas" principais. Cada uma tem colunas (campos) para guardar detalhes específicos. Elas se conectam via chaves (IDs) para evitar repetições.

### Tabela Users - Usuários
- **O que faz**: Guarda informações de pessoas, como nome, email e senha.
- **Campos principais**:
  - `user_id`: Número único automático (como um CPF gerado).
  - `user_name`, `email`, `fone`: Nome, email e telefone (únicos, não repetem).
  - `password`: Senha (guardada de forma segura).
  - `birthdate`: Data de nascimento.
  - `admin`: Se é administrador (sim/não, padrão não).
  - `isActive`: Se a conta está ativa (padrão sim).
  - `created_at` e `updated_at`: Datas automáticas de criação e atualização.
- **Regras**: Email, nome e telefone não repetem. Se deletar um usuário, carteiras ligadas somem (cascade).
- **Gatilho (trigger_create_wallet)**: Chama a função para criar carteira automática ao inserir usuário.
- **Exemplo**: Como uma ficha de cadastro: "João, joao@email.com, senha123, nascido em 1990".

### Tabela Event - Eventos
- **O que faz**: Guarda detalhes de eventos, como nome, data e local.
- **Campos principais**:
  - `event_id`: Número único automático.
  - `creator_id`: ID do usuário que criou (liga à tabela users).
  - `event_name`: Nome único.
  - `ead`: Se é online (sim/não, padrão não).
  - `address`: Endereço (se presencial).
  - `event_date` e `buy_time_limit`: Data do evento e limite para comprar ingressos.
  - `capacity` e `quant`: Capacidade máxima e quantidade disponível.
  - `description`: Texto explicando o evento.
  - `image_data`: Foto do evento (em bytes).
  - `created_at` e `updated_at`: Datas automáticas.
- **Regras**: Nome não repete. Liga ao criador (se deletar usuário, eventos ficam? Não especificado para delete).
- **Exemplo**: "Workshop de Java, criado por João, em São Paulo, dia 15/12/2025".

### Tabela MyWallet - Carteiras
- **O que faz**: Representa a carteira digital de cada usuário (guarda inscrições).
- **Campos principais**:
  - `user_id`: ID do usuário (chave principal, liga à users).
  - `created_at` e `updated_at`: Datas automáticas.
- **Regras**: Uma carteira por usuário. Se deletar usuário, carteira some (cascade).
- **Exemplo**: "Carteira do usuário 1 (João), criada em 23/11/2025".

### Tabela WalletEvent - Inscrições
- **O que faz**: Liga usuários a eventos (quem se inscreveu onde).
- **Campos principais**:
  - `user_id` e `event_id`: IDs do usuário e evento (chave composta).
  - `created_at` e `updated_at`: Datas automáticas.
- **Regras**: Liga às tabelas users e event. Não deleta se tentar remover usuário ou evento (restrict).
- **Exemplo**: "Usuário 1 se inscreveu no evento 1, em 23/11/2025".

## Segurança e Autenticação

Essas partes protegem o sistema contra intrusos, como um porteiro que verifica identidades. Usam algo chamado JWT (um "bilhete digital" que prova quem você é).

### JwtUtil
- **O que faz**: Cria e verifica bilhetes (tokens JWT). Cada bilhete tem um "nome" (subject), data de validade e uma assinatura secreta para evitar falsificações.
- **Como funciona**: 
  - Gera um bilhete novo quando você loga (com uma senha secreta e tempo de expiração, como 1 hora).
  - Verifica se o bilhete é válido (não expirou e assinatura certa).
- **Por quê?**: Para que só pessoas autorizadas acessem partes privadas, como editar eventos.

Exemplo: Como um ingresso de show com holograma anti-falsificação e data de validade.

### JwtAuthenticationFilter
- **O que faz**: É o "porteiro" que checa o bilhete em cada pedido ao sistema (como acessar uma página).
- **Como funciona**: Olha no cabeçalho da mensagem (como um envelope) por um bilhete "Bearer". Se válido, deixa passar; senão, bloqueia.
- **Por quê?**: Para proteger rotas, como só permitir que donos de eventos os editem.

### SecurityConfig
- **O que faz**: Define as regras de segurança gerais, como quais partes são públicas ou privadas.
- **Como funciona**:
  - Desliga proteções desnecessárias (como CSRF, para apps web simples).
  - Permite acesso livre a coisas como login ou busca de eventos.
  - Exige bilhete JWT para o resto.
  - Configura CORS (permite que sites de outros domínios acessem o sistema, como um app móvel).
  - Usa uma ferramenta para codificar senhas (BCrypt).
- **Por quê?**: Para o sistema ser seguro, mas acessível. Sem isso, qualquer um poderia bagunçar os dados.

Exemplo: Regras do clube: Entrada livre para ver a agenda, mas só membros com cartão entram na sala VIP.

## Inicialização do Aplicativo

Aqui está o "botão de ligar" do sistema inteiro.

### Main
- **O que faz**: É o ponto de partida. Inicia o aplicativo usando uma ferramenta chamada Spring Boot, que configura tudo automaticamente.
- **Como funciona**: Quando você roda o programa, ele "acorda" todos os outros pedaços (como serviços e controladores) e os coloca para trabalhar.
- **Por quê?**: Sem isso, o sistema não começa. É como apertar o play em um vídeo.

## Popular o Banco de Dados (Seeding)

Essas partes servem para encher o banco com dados de teste de uma vez, como adicionar vários convidados a uma lista.

### SeedData
- **O que faz**: É uma "caixa" que guarda listas de usuários, eventos e inscrições (quem se inscreveu onde).
- **Como funciona**: Recebe dados em formato JSON (como uma lista de compras) e os organiza.
- **Por quê?**: Para testar o sistema rapidamente, sem criar tudo manualmente.

Exemplo: Uma lista: "Adicionar João, Maria; Eventos: Festa1, Festa2; Inscrições: João na Festa1".

### SeedController
- **O que faz**: Recebe a caixa de dados e os salva no banco, usando os serviços normais (para aplicar regras, como codificar senhas).
- **Como funciona**:
  - Tenta criar cada usuário, evento e inscrição.
  - Registra sucessos, avisos (como "Já existe") e erros.
  - Retorna um resumo: "X criados, com Y avisos".
- **Por quê?**: Útil para desenvolvedores testarem ou inicializarem o sistema com dados reais.

Exemplo: Como importar uma planilha de convidados para uma festa — o sistema checa duplicatas e avisa problemas.

### Dados de Exemplo e Inicialização
Esse script enche as tabelas com dados fictícios para testar.

- **O que faz**: Adiciona usuários, eventos e inscrições sem apagar o que já existe (idempotente).
- **Como funciona**:
  - Insere 5 usuários (como João, Maria) com senhas simples (na vida real, codifique!).
  - Insere 5 eventos (workshops, conferências) ligados aos usuários.
  - Insere inscrições (ex: João no Workshop de Java).
  - Mostra um resumo: quantos usuários, eventos, etc.
  - Lista todas as tabelas no final para verificar.
- **Por quê?**: Para testar o sistema sem começar do zero. É como colocar livros de amostra na biblioteca.
- **Exemplo de saída**: "✅ Dados populados! Usuários: 5, Eventos: 5".

## Scripts para Gerenciar Dados

Esses são "receitas automáticas" (scripts) para lidar com dados no banco.

### populate-data.ps1 (PowerShell para Windows)
- **O que faz**: Enche o banco com dados de teste (usuários, eventos, inscrições) via API do servidor. Senhas são codificadas para segurança.
- **Como funciona**: Cria uma lista de usuários e eventos (como João, Maria, Workshops), converte em formato JSON (uma lista organizada) e envia para o servidor. Mostra resumo de sucessos, avisos e erros.
- **Exemplo**: Como adicionar convidados a uma festa: envia a lista e o servidor cuida do resto.
- **Uso**: Rode no PowerShell. Certifique-se de que o servidor está ligado.

### export-data.sh (Bash para Linux/Mac)
- **O que faz**: Exporta dados do banco para um arquivo texto bonito (com tabelas e estatísticas).
- **Como funciona**: Conecta ao banco via Docker, executa comandos SQL para listar usuários, eventos, inscrições, estatísticas (como total de usuários) e eventos populares. Salva em um arquivo como "database-export-20251123-120000.txt".
- **Opções**:
  - `-p`: Enche o banco com dados de teste antes.
  - `-o arquivo.txt`: Muda o nome do arquivo.
- **Exemplo**: Como imprimir um relatório de uma agenda: lista tudo formatado com caixas e emojis.
- **Uso**: Rode o script com opções para encher e exportar.

## Scripts para Testes

Esses scripts verificam se o sistema funciona direito, como um checklist de qualidade.

### run-tests.sh (Bash)
- **O que faz**: Roda testes automáticos dentro de uma caixa Docker temporária, sem sujar seu computador.
- **Como funciona**: Copia o código para dentro da caixa, executa Maven para testar, coleta relatórios e mostra resumo colorido (sucessos, falhas, tempo). Salva relatórios em "target/surefire-reports".
- **Exemplo**: Como testar um carro: roda vários cenários (acelerar, frear) e diz se passou ou falhou.
- **Uso**: Rode o script. Mostra algo como "✅ 23 testes passaram em 5s".

### export-tests.sh (Bash)
- **O que faz**: Gera um relatório texto dos testes, com ou sem rodar eles de novo.
- **Como funciona**: Lê relatórios existentes (ou roda testes se não usar -n), formata com classes de teste, estatísticas e resumo. Salva em arquivo como "tests-report-20251123-120000.txt".
- **Opções**:
  - `-n`: Não roda testes, usa os últimos.
  - `-o arquivo.txt`: Muda o nome.
- **Exemplo**: Como um boletim escolar: lista notas por matéria e total.
- **Uso**: Rode o script com opções para exportar sem rodar.

## Configuração com Docker

Esta é a parte final da documentação do sistema de gerenciamento de eventos. Aqui, explicamos como o sistema é "embalado" e executado usando uma ferramenta chamada Docker. Pense no Docker como caixas mágicas (chamadas containers) que guardam partes do sistema (como o banco de dados e o servidor) de forma isolada, facilitando a instalação e o teste em qualquer computador.

Se você não entende de programação ou computadores, imagine o Docker como um kit de montagem de uma casa de bonecas: cada caixa tem uma peça pronta (banco de dados em uma, servidor em outra), e um manual (docker-compose) diz como juntar tudo. Os scripts são como receitas que automatizam tarefas, como encher a casa com móveis (dados) ou verificar se está tudo certo (testes).

O sistema roda em dois containers principais: um para o banco de dados (PostgreSQL) e outro para o aplicativo Java (backend). Tudo é configurado para ser fácil de ligar e desligar.

### Introdução ao Docker no Projeto
- **O que é Docker?**: Uma ferramenta que cria "caixas virtuais" para rodar programas sem bagunçar seu computador. Cada caixa tem tudo o que precisa (como Java ou banco de dados) e não interfere nas outras.
- **Por quê usar?**: Facilita testar o sistema em qualquer máquina, sem instalar nada extra. É como um jogo que roda direto de um pen drive.
- **Requisitos básicos**: Instale o Docker no seu computador (procure "instalar Docker" no Google). O projeto usa arquivos como `Dockerfile` (receita para construir a caixa do backend) e `docker-compose.yml` (manual para montar tudo).

### Construção da Imagem do Backend (Dockerfile)
Esse arquivo é a "receita" para criar a caixa do servidor Java.

- **Como funciona**:
  - **Primeira parte (build)**: Usa uma caixa grande com Java 21 e Maven (ferramenta para construir apps Java). Copia o código, constrói um arquivo executável (JAR, como um ZIP com o programa pronto).
  - **Segunda parte (runtime)**: Usa uma caixa pequena só com Java 25 para rodar o JAR. Expõe a porta 8081 (como uma janela para acessar o servidor).
- **Exemplo simples**: É como assar um bolo: primeiro misture ingredientes (build), depois sirva o bolo pronto (runtime).
- **Uso**: O docker-compose faz isso automaticamente.

### Configuração com Docker para o Banco de Dados (Dockerfile)
Essa parte configura a caixa (container) onde o banco roda, isolada do resto do computador.

- **O que faz**: Usa uma caixa pronta do PostgreSQL versão 16 (leve, chamada "alpine"). Define o idioma como português (UTF-8) para nomes com acentos não darem problema.
- **Como funciona**:
  - Copia scripts de inicialização (como criar tabelas) para uma pasta especial. Esses scripts rodam automaticamente na primeira vez que a caixa liga.
  - Expõe a porta 5432 (como uma janela para conectar ao banco).
  - Guarda dados em um "armário persistente" para não perder ao desligar.
- **Variáveis**: Senha e usuário vêm de um arquivo separado (.env) para segurança.
- **Exemplo**: Imagine montar uma estante: a receita diz "pegue a estante básica, adicione gavetas (scripts), e defina o idioma".
- **Comando de teste**: Há um exemplo para rodar testes no banco via Docker.

### Configuração Geral (docker-compose.yml)
Esse arquivo é o "manual de montagem" que define as caixas e como elas se conectam.

- **Serviços (caixas)**:
  - **db (banco de dados)**: Usa PostgreSQL 16. Porta 5433 no seu computador (dentro da caixa é 5432). Guarda dados em um "armário persistente" (volume db-data) para não perder ao desligar. Verifica se está "saudável" antes de prosseguir.
  - **backend (servidor)**: Constrói a partir do Dockerfile. Porta 8081. Espera o banco estar pronto (depends_on). Reinicia sozinho se cair.
- **Outros itens**:
  - **Volumes**: Armário para dados do banco (não some ao parar).
  - **Networks**: Uma "rede privada" (gerenciador-net) para as caixas conversarem.
  - **Env_file**: Pega configurações de um arquivo `.env` (como URL do banco, usuário e senha).
- **Comandos básicos**:
  - Ligar: Rode o comando up em modo detached (fundo, sem parar o terminal).
  - Desligar: Rode o comando down.
- **Exemplo**: Como montar um brinquedo LEGO: o arquivo diz "conecte a peça DB à rede, espere ela ligar, então conecte o backend".

## Configuração do Projeto Java (pom.xml)

Esse é o "cardápio" de ingredientes para construir o app Java.

- **O que faz**: Define o que o Maven (ferramenta de construção) precisa: versão Java 21, dependências (pacotes prontos como Spring Boot para web, segurança, banco PostgreSQL, JWT para autenticação).
- **Partes principais**:
  - **Dependências**: Como ingredientes: Spring para o app, segurança para proteger, PostgreSQL para o banco.
  - **Build**: Configura como compilar (usar Java 21) e empacotar em JAR.
- **Exemplo**: Como uma receita de bolo: lista farinha (Spring), ovos (segurança) e como assar (build).
- **Uso**: Maven usa isso automaticamente no Dockerfile ou scripts de teste.

## Guia de Comandos

Esse arquivo é um manual pronto com comandos comuns.

- **Início rápido**: 4 passos para ligar, esperar, popular e exportar.
- **Comandos principais**: Ligar/desligar, reconstruir.
- **Testes**: Rode o script de testes.
- **Problemas comuns**: Ver logs, checar portas, resetar.
- **Workflows**: Rotinas diárias, após mudanças, antes de salvar código.
- **Notas**: Dados persistem, use .env para configurações (ex: senha do banco).

Exemplo de .env: Inclui URL do banco, usuário e senha.

## Considerações Finais

- **Segurança**: Senhas são codificadas, mas o sistema não tem autenticação completa (ex: tokens). O sistema protege dados com senhas codificadas e bilhetes JWT. Sempre verifique o bilhete para ações privadas. Senhas no .env ficam escondidas; não compartilhe.
- **Banco de dados**: Assume uma tabela "users" e "event" com colunas específicas. Ordem de criação: Users primeiro (pois outros dependem dela), depois Event, MyWallet e WalletEvent. Automação: Triggers e funções evitam erros humanos, como esquecer carteiras. Senhas devem ser codificadas no app (não no SQL). Use UTF-8 para acentos.
- **Melhorias possíveis**: Adicionar mais validações, interface gráfica ou buscas avançadas. Adicione mais roles (como admin) no JWT para controles finos. Adicione mais regras, como checar datas lógicas.
- **Como usar**: Para testar, use ferramentas como Postman para enviar pedidos aos endpoints.
- **Testes**: Use o seeding para encher o banco e testar fluxos, como inscrição em eventos. Sempre rode testes após mudanças; use populate/export para verificar dados.
- **Integração**: O BFF é o "ponto único" para apps front-end se conectarem.
- **Portas**: 5433 (banco), 8081 (API) — mude se conflitar.
- **Dicas para iniciantes**: Comece com o comando para ligar o Docker, acesse a porta 8081 em um navegador para testar a API.

Se o sistema for como uma festa, o Docker é o salão pronto: monte, encha de convidados (dados) e verifique se todos estão felizes (testes)! Se precisar de ajuda, rode os scripts e veja os logs.

Se o banco for como uma casa, as tabelas são cômodos, funções são empregados automáticos, e o seed é mobiliar para a inauguração! Se precisar rodar, use Docker para ligar tudo.

## Front-end

# Documentação  Front-end

Esta documentação explica o funcionamento da parte visível do aplicativo (chamada front-end), que é o que você vê e toca na tela do celular, tablet ou navegador. O app é feito com uma ferramenta chamada Flutter, que cria telas bonitas e fáceis de usar para gerenciar eventos como festas, palestras ou shows.

Pense no front-end como a loja que você visita: as vitrines (telas), botões para clicar, campos para digitar e mensagens que aparecem. Ele se conecta a um "depósito" no fundo (back-end) para guardar e buscar informações.

O app permite ver eventos, se cadastrar, logar, criar eventos, editar perfil, buscar e se inscrever. Aqui, explicamos os arquivos principais de forma simples, como se fossem peças de um quebra-cabeça.

## Introdução ao Projeto

Este é um aplicativo web (roda no navegador) feito com Flutter. Ele começa carregando uma página simples e vai crescendo para mostrar telas interativas.

- **Como funciona geral**: Quando você abre o site, ele carrega arquivos como imagens e códigos. Se demorar, mostra "Carregando...". Tudo é seguro e rápido graças a configurações especiais.
- **Por quê?**: Para que qualquer um acesse pelo navegador, sem baixar nada.
- **Exemplo**: Como entrar em um site de ingressos: clica, vê eventos e interage.

## Configuração de Implantação (Dockerfile e nginx.conf)

Essas partes são como "caixas" e "regras da casa" para colocar o app online, para que funcione em um servidor (como um computador na nuvem).

### Dockerfile
- **O que faz**: É uma receita para construir a "caixa" do app. Divide em dois passos: primeiro, prepara o código Flutter; depois, coloca em um servidor leve.
- **Como funciona**: Usa uma base simples (Ubuntu), instala ferramentas, copia o código, constrói o app e coloca em um servidor chamado Nginx. Define uma variável para o endereço do back-end.
- **Por quê?**: Para rodar o app em qualquer computador sem problemas.
- **Exemplo**: Como fazer um bolo: receita para misturar ingredientes e assar.

### nginx.conf
- **O que faz**: São regras para o servidor Nginx, que "entrega" as páginas para você.
- **Como funciona**: Define onde estão os arquivos do app, redireciona pedidos para o back-end (ex: /api/ vai para outro lugar) e cuida de erros.
- **Por quê?**: Para o app carregar rápido e seguro.
- **Exemplo**: Como um garçom: sabe onde pegar a comida (arquivos) e entrega no balcão certo.

## Dependências e Configuração (pubspec.yaml)

Este arquivo é como uma "lista de compras" do app: diz o que precisa para funcionar.

- **O que faz**: Lista o nome do app, versão e ferramentas extras (como botões bonitos ou envio de dados).
- **Como funciona**: Inclui Flutter para as telas, Provider para compartilhar dados entre páginas, HTTP para falar com o back-end, e mais.
- **Por quê?**: Para o app saber o que baixar e usar.
- **Exemplo**: Como uma receita: lista farinha, ovos e forno.

## Página de Entrada (index.html)

Esta é a "porta de entrada" no navegador: a primeira página que carrega.

- **O que faz**: Prepara o terreno para o app Flutter rodar.
- **Como funciona**: Mostra "Carregando..." enquanto baixa o código. Se der erro, avisa. Usa um script para iniciar o app.
- **Por quê?**: Para evitar tela branca e guiar o usuário.
- **Exemplo**: Como uma recepção: diz "Aguarde" enquanto prepara a sala.

## Código Principal do App (blocoInteiro.dart)

Este é o "coração" do app: o código que cria todas as telas e botões. Explicamos por partes, como cômodos de uma casa.

### Início do App (main e MyApp)
- **O que faz**: Liga o app e verifica se você está logado.
- **Como funciona**: Carrega o crachá (token), tenta pegar dados do usuário. Se ok, vai para painel principal; senão, para página inicial.
- **Por quê?**: Para entrar rápido se já logado.
- **Exemplo**: Como ligar a TV: verifica canal favorito.

### Dados Compartilhados (HomePageData)
- **O que faz**: Guarda coisas como nome do site, links e dados do usuário.
- **Como funciona**: Compartilha entre telas, atualiza quando muda (ex: após login).
- **Por quê?**: Para não repetir informações.
- **Exemplo**: Como uma prateleira: todos pegam o que precisam.

### Tela Inicial (HomeScreen)
- **O que faz**: Mostra eventos públicos.
- **Como funciona**: Barra superior com nome, perfil falso, imagem grande e botões para explorar ou criar.
- **Por quê?**: Para visitantes verem sem logar.
- **Exemplo**: Como vitrine de loja: atrai para entrar.

### Barra de Navegação (_TopNavigationBar)
- **O que faz**: Menu no topo com nome do site e botões.
- **Como funciona**: Links para buscar ou gerenciar eventos.
- **Por quê?**: Para navegar fácil.
- **Exemplo**: Como menu de restaurante.

### Seção de Perfil (_UserProfileSection)
- **O que faz**: Mostra nome e foto do usuário (ou genérico).
- **Como funciona**: Após login, atualiza com dados reais.
- **Por quê?**: Para personalizar.
- **Exemplo**: Como crachá: identifica você.

### Conteúdo Principal (_MainContentCard)
- **O que faz**: Mostra imagem com texto sobreposto.
- **Como funciona**: Carrega imagem e coloca texto em cima.
- **Por quê?**: Para chamar atenção.
- **Exemplo**: Como pôster: destaca o principal.

### Botões de Ação (_ActionButtonsSection)
- **O que faz**: Botões para explorar ou criar eventos.
- **Como funciona**: Clica e vai para outra tela.
- **Por quê?**: Para incentivar ações.
- **Exemplo**: Como "Compre agora" em loja.

### Outras Telas e Componentes
- **Telas de Eventos**: Criar, editar, inscrever — preenche formulários e envia dados.
- **Gerenciamento**: Abas para ver inscritos ou criados.
- **Login/Cadastro**: Campos para digitar, verifica e salva.
- **Pesquisa**: Barra para buscar, filtra resultados.
- **Edição de Perfil**: Muda dados pessoais.

## Considerações Finais

- **Segurança**: Verifica login antes de ações privadas.
- **Carregamento**: Mostra avisos enquanto espera.
- **Melhorias**: Pode adicionar mais botões ou imagens.

O app é como um organizador de eventos: fácil de usar e conectado ao back-end para guardar tudo!
