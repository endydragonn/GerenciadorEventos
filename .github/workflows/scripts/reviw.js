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
    const { owner, repo, number: pull_number } = context.issue;

    // 1. Obter as alterações (diff) do PR
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

    // Limita o tamanho do diff em caracteres para otimizar o tempo e cota
    const truncatedDiff = fullDiff.slice(0, 30000);

    // 2. Inicializar o cliente do Gemini
    const ai = new GoogleGenAI({ apiKey: geminiKey });

    const systemInstruction = `
Você é um Arquiteto de Software e Especialista em Segurança Sênior com foco em Java, Spring Boot, Spring Security, SQL e Arquiteturas Orientadas a Eventos com Apache Kafka.

Sua missão é analisar o diff fornecido e apontar melhorias críticas, riscos de segurança e falhas arquiteturais no backend.

Diretrizes de Revisão Técnica:

1. **Segurança (Spring Security & OWASP Top 10):**
   - Verifique regras de autorização/autenticação, exposição indevida de endpoints e manipulação de JWT / OAuth2.
   - Identifique riscos de vazamento de credenciais/segredos no código, SQL Injection ou desativação acidental de proteções (como CORS/CSRF sem justificativa).
   - Valide se dados sensíveis estão sendo sanitizados ou mascarados antes de ir para logs.

2. **Event-Driven Architecture & Apache Kafka:**
   - Verifique o manuseio de consumidores (@KafkaListener), estratégias de Retry, Dead Letter Topics (DLT) e idempotent consumers (evitar duplo processamento de mensagens).
   - Analise serialização/deserialização de payload, gerenciamento de exceções na escuta de tópicos e configurações de commits/offsets.

3. **Spring Boot & SQL / JPA:**
   - Avalie uso correto de injeção de dependência via construtor, transações (@Transactional) e propagação.
   - Identifique problemas de performance em banco de dados, como consulta N+1 no JPA/Hibernate, falta de índices explícitos em queries nativas e mapeamentos de relacionamentos incorretos.

Diretrizes de Formatação:
- Responda estritamente em Português do Brasil.
- Use Markdown limpo (subtítulos, listas com marcadores e blocos de código Java/YAML para correções sugeridas).
- Seja objetivo e acionável. Se o código estiver excelente ou não apresentar falhas técnicas, faça um breve elogio e aprove a alteração.
- Ignore arquivos de lock ou diretórios compilados (target/build).
`;

    const prompt = `Analise o seguinte diff de Pull Request (Java / Spring Boot / Security / Kafka / SQL) e forneça seu code review:\n\n\`\`\`diff\n${truncatedDiff}\n\`\`\``;

    core.info('Enviando diff para análise do Gemini 2.5 Flash...');

    // 3. Chamada ao modelo Gemini 2.5 Flash
    const response = await ai.models.generateContent({
      model: 'gemini-2.5-flash',
      contents: prompt,
      config: {
        systemInstruction,
        temperature: 0.2, // Baixa temperatura para manter o feedback técnico e consistente
      }
    });

    const reviewText = response.text;

    // 4. Comentar o resultado no PR
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
