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

    // Limita o tamanho do diff em caracteres para evitar estouro desnecessário de contexto
    const truncatedDiff = fullDiff.slice(0, 30000);

    // 2. Inicializar o cliente do Gemini
    const ai = new GoogleGenAI({ apiKey: geminiKey });

    const systemInstruction = `
Você é um Engenheiro de Software Sênior atuando como revisor automatizado de Pull Requests.
Sua missão é analisar o diff fornecido e gerar um feedback técnico de alto nível, objetivo e acionável.

Pontos de Atenção:
- Erros de lógica, bugs potenciais ou falhas de segurança (ex: vazamento de dados, injeção, tratamento inadequado de exceções).
- Legibilidade, manutenibilidade e aderência a boas práticas da linguagem utilizada.
- Gargalos de performance ou trechos que podem ser simplificados.

Diretrizes de Resposta:
- Responda estritamente em Português do Brasil.
- Utilize formatação Markdown estruturada (títulos, listas com marcadores e blocos de código para sugestões).
- Seja direto e conciso. Caso o código esteja excelente, faça um breve elogio e aprove a alteração.
- Ignore arquivos de lock/build caso apareçam no diff.
`;

    const prompt = `Analise o seguinte diff de Pull Request e forneça seu code review:\n\n\`\`\`diff\n${truncatedDiff}\n\`\`\``;

    core.info('Enviando diff para análise do Gemini 2.5 Flash...');

    // 3. Chamada ao modelo Gemini 2.5 Flash
    const response = await ai.models.generateContent({
      model: 'gemini-2.5-flash',
      contents: prompt,
      config: {
        systemInstruction,
        temperature: 0.2, // Baixa temperatura para manter o feedback consistente e focado
      }
    });

    const reviewText = response.text;

    // 4. Comentar o resultado no PR
    const commentBody = `### 🤖 Gemini Code Review\n\n${reviewText}`;

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
