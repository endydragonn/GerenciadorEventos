package com.gerenciador.eventos.notification;

/**
 * Centraliza todos os templates de email da aplicação
 * Facilita manutenção e atualização de conteúdos de email
 */
public class EmailTemplates {

    private static final String COMPANY_NAME = "Equipe Organizador de Eventos";
    private static final String APP_NAME = "Organizador de Eventos";

    /**
     * Template: Código de Verificação de Email (Cadastro)
     */
    public static String getVerificationCodeTemplate(String userName, String verificationCode, int expirationMinutes) {
        return "Olá " + userName + ",\n\n" +
               "Bem-vindo ao " + APP_NAME + "!\n\n" +
               "Para completar seu cadastro, use o código de verificação abaixo:\n\n" +
               "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
               "📝 Código de Verificação: " + verificationCode + "\n" +
               "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
               "⏰ Este código é válido por " + expirationMinutes + " minutos.\n\n" +
               "🔒 Se você não solicitou este cadastro, ignore este email e sua conta permanecerá segura.\n\n" +
               "Atenciosamente,\n" +
               COMPANY_NAME;
    }

    /**
     * Template: Código MFA para Reset de Senha
     */
    public static String getPasswordResetMfaTemplate(String userName, String mfaCode) {
        return "Olá " + userName + ",\n\n" +
               "Você solicitou uma alteração de senha.\n" +
               "Use o código abaixo para validar sua identidade:\n\n" +
               "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
               "🔐 Código MFA: " + mfaCode + "\n" +
               "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
               "⏰ Este código expira em 15 minutos.\n\n" +
               "⚠️  Se você não solicitou esta alteração, ignore este email e sua senha permanecerá segura.\n" +
               "Nenhuma mudança será feita em sua conta sem este código.\n\n" +
               "Atenciosamente,\n" +
               COMPANY_NAME;
    }

    /**
     * Template: Confirmação de Cadastro Bem-Sucedido
     */
    public static String getSignupConfirmationTemplate(String userName) {
        return "Olá " + userName + ",\n\n" +
               "Bem-vindo ao " + APP_NAME + "!\n\n" +
               "✅ Sua conta foi criada com sucesso!\n\n" +
               "Você agora pode:\n" +
               "• Procurar por eventos na plataforma\n" +
               "• Criar seus próprios eventos\n" +
               "• Gerenciar inscrições\n" +
               "• Acessar sua carteira virtual\n\n" +
               "Para começar, faça login em nossa plataforma:\n" +
               "🔗 http://localhost:8088\n\n" +
               "Se tiver dúvidas, entre em contato conosco.\n\n" +
               "Atenciosamente,\n" +
               COMPANY_NAME;
    }

    /**
     * Template: Confirmação de Alteração de Senha
     */
    public static String getPasswordChangedTemplate(String userName) {
        return "Olá " + userName + ",\n\n" +
               "✅ Sua senha foi alterada com sucesso!\n\n" +
               "A alteração foi concluída em: " + java.time.LocalDateTime.now() + "\n\n" +
               "🔒 Dicas de Segurança:\n" +
               "• Não compartilhe sua senha com ninguém\n" +
               "• Use uma senha forte e única\n" +
               "• Sempre faça logout ao terminar\n\n" +
               "Se você não solicitou esta alteração, mude sua senha imediatamente!\n\n" +
               "Atenciosamente,\n" +
               COMPANY_NAME;
    }

    /**
     * Template: Código de Reativação de Conta
     */
    public static String getAccountReactivationTemplate(String userName, String reactivationCode) {
        return "Olá " + userName + ",\n\n" +
               "Recebemos uma solicitação para reativar sua conta.\n\n" +
               "Use o código abaixo para reativar:\n\n" +
               "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
               "🔓 Código de Reativação: " + reactivationCode + "\n" +
               "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
               "⏰ Este código é válido por 24 horas.\n\n" +
               "Se você não solicitou isto, ignore este email.\n\n" +
               "Atenciosamente,\n" +
               COMPANY_NAME;
    }

    /**
     * Template: Notificação de Nova Inscrição em Evento
     */
    public static String getEventRegistrationTemplate(String userName, String eventName, String eventDate) {
        return "Olá " + userName + ",\n\n" +
               "✅ Você se inscreveu com sucesso no evento:\n\n" +
               "📅 Evento: " + eventName + "\n" +
               "📆 Data: " + eventDate + "\n\n" +
               "Você receberá atualizações sobre este evento por email.\n" +
               "Verifique os detalhes em sua conta.\n\n" +
               "Atenciosamente,\n" +
               COMPANY_NAME;
    }

    /**
     * Template: Lembrete de Evento
     */
    public static String getEventReminderTemplate(String userName, String eventName, String eventDate) {
        return "Olá " + userName + ",\n\n" +
               "📢 Lembrete de Evento!\n\n" +
               "Você tem uma inscrição ativa no evento:\n\n" +
               "📅 Evento: " + eventName + "\n" +
               "📆 Data: " + eventDate + "\n\n" +
               "Não esqueça de comparecer!\n\n" +
               "Atenciosamente,\n" +
               COMPANY_NAME;
    }

    /**
     * Sujeito: Código de Verificação de Email
     */
    public static String getVerificationCodeSubject() {
        return "📧 Código de Verificação de Email - " + APP_NAME;
    }

    /**
     * Sujeito: MFA para Alteração de Senha
     */
    public static String getPasswordResetMfaSubject() {
        return "🔐 Código MFA para Alteração de Senha - " + APP_NAME;
    }

    /**
     * Sujeito: Confirmação de Cadastro
     */
    public static String getSignupConfirmationSubject() {
        return "✅ Bem-vindo ao " + APP_NAME + "!";
    }

    /**
     * Sujeito: Alteração de Senha
     */
    public static String getPasswordChangedSubject() {
        return "🔒 Sua Senha Foi Alterada - " + APP_NAME;
    }

    /**
     * Sujeito: Reativação de Conta
     */
    public static String getAccountReactivationSubject() {
        return "🔓 Reative sua Conta - " + APP_NAME;
    }

    /**
     * Sujeito: Nova Inscrição em Evento
     */
    public static String getEventRegistrationSubject() {
        return "✅ Inscrição Confirmada em Evento - " + APP_NAME;
    }

    /**
     * Sujeito: Lembrete de Evento
     */
    public static String getEventReminderSubject() {
        return "📢 Lembrete de Evento - " + APP_NAME;
    }
}
