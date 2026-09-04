import core from '@actions/core';
import github from '@actions/github';
import { GoogleGenAI } from '@google/genai';

async function run() {
  try {
    const token = process.env.GITHUB_TOKEN;
    const geminiKey = process.env.GEMINI_API_KEY;

    if (!token || !geminiKey) {
      throw new Error('Tokens GITHUB_TOKEN e GEMINI_API_KEY são obrigatórios.');
    }

    const octokit = github.getOctokit(token);
    const context = github.context;

    // Captura o número do Pull Request do evento
    const pull_number = context.payload.pull_request?.number;
    if (!pull_number) {
      core.setFailed('Este workflow só pode ser executado em eventos do tipo pull_request.');
      return;
    }

    const { owner, repo } = context.repo;

    // 1. Obter as alterações (diff) do PR via GitHub REST API
    const { data: diffData } = await octokit.rest.pulls.get({
      owner,
      repo,
      pull_number,
      mediaType: { format: 'diff' }
    });

    const fullDiff = String(diffData);

    if (!fullDiff.trim()) {
      core.info('Nenhuma alteração encontrada no diff.');
      return;
    }

    // Limita o tamanho do diff (máx 30.000 caracteres) para otimizar execução e cota
    const truncatedDiff = fullDiff.slice(0, 30000);

    // 2. Inicializar o cliente do Gemini SDK
    const ai = new GoogleGenAI({ apiKey: geminiKey });

    const systemInstruction = `
Você é um Arquiteto de Software e Especialista em Segurança Sênior especializado em Java, Spring Boot, Spring Security, SQL e Arquiteturas Orientadas a Eventos com Apache Kafka.

Sua missão é analisar o diff fornecido e apontar melhorias críticas, riscos de segurança e falhas arquiteturais no backend.

Diretrizes de Revisão Técnica:

1. **Segurança (Spring Security & OWASP Top 10):**
   - Verifique regras de autorização/autenticação, exposição indevida de endpoints e manipulação de JWT / OAuth2.
   - Identifique riscos de vazamento de credenciais/segredos no código, SQL Injection ou desativação acidental de proteções (CORS/CSRF sem justificativa).
   - Valide se dados sensíveis estão sendo sanitizados antes de ir para logs.

2. **Event-Driven Architecture & Apache Kafka:**
   - Verifique o manuseio de consumidores (@KafkaListener), estratégias de Retry, Dead Letter Topics (DLT) e consumidores idempotentes.
   - Analise serialização/deserialização de payload, gerenciamento de exceções e configurações de commits/offsets.

3. **Spring Boot & SQL / JPA:**
   - Avalie uso correto de injeção de dependência via construtor e transações (@Transactional).
   - Identifique problemas de performance em banco de dados, como consulta N+1 no JPA/Hibernate, falta de índices explícitos e mapeamentos incorretos.

Diretrizes de Formatação:
- Responda estritamente em Português do Brasil.
- Use Markdown limpo (subtítulos, listas com marcadores e blocos de código Java/YAML/SQL para correções sugeridas).
- Seja objetivo e acionável. Se o código estiver excelente, faça um breve elogio e aprove a alteração.
- Ignore arquivos de lock ou diretórios compilados (target/build).
`;

    const prompt = `Analise o seguinte diff de Pull Request (Java / Spring Boot / Security / Kafka / SQL) e forneça seu code review:\n\n\`\`\`diff\n${truncatedDiff}\n\`\`\``;

    core.info('Enviando diff para análise do Gemini 3.6 Flash...');

    // 3. Chamada ao modelo Gemini 3.6 Flash com limite de tokens para velocidade acelerada
    const response = await ai.models.generateContent({
      model: 'gemini-3.6-flash',
      contents: prompt,
      config: {
        systemInstruction,
        temperature: 0.2,
        maxOutputTokens: 1200 // Limita o tamanho da resposta para otimizar o tempo de resposta
      }
    });

    const reviewText = response.text;

    // 4. Comentar a análise diretamente no PR
    const commentBody = `### 🤖 Gemini Code Review (Java / Security / Kafka / SQL)\n\n${reviewText}`;

    await octokit.rest.issues.createComment({
      owner,
      repo,
      issue_number: pull_number,
      body: commentBody
    });

    core.info('Code Review publicado com sucesso!');
  } catch (error) {
    core.setFailed(`Falha na execução do Gemini PR Reviewer: ${error.message}`);
  }
}

run();
