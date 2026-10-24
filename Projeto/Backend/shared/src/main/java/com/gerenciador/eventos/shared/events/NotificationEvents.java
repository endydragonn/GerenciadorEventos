package com.gerenciador.eventos.shared.events;

import java.time.Duration;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Contrato do stream {@code identity.notification} (issue #32, Fase 7): o que {@code identity} (monólito) publica e o
 * serviço {@code notification} consome para mandar e-mail. Fica em {@code shared} porque é a única coisa que os dois
 * lados combinam; cada notificação é UM stream de uma versão só ({@code streamId} novo, {@code version} 1).
 *
 * <p>Payload (schemaVersion 1), nos dois tipos: {@code email}, {@code userName}, {@code code}, {@code expirationMinutes} e
 * {@code expiresAt} (ISO-8601, instante em que o código vence). <b>Exceção deliberada à regra "sem dado pessoal no
 * payload" da ADR do backbone (decisão do usuário, 2026-10-21):</b> o e-mail só se monta com destinatário e código. O dado
 * pessoal vive só enquanto o código vale, mais {@link #CARENCIA}: o monólito apaga a linha do {@code event_store}
 * ({@code NotificationEventRetentionService}), o tópico e o {@code .DLT} têm {@link #RETENCAO_TOPICO} e o consumer descarta
 * evento vencido. A senha e o hash NUNCA entram.</p>
 */
public final class NotificationEvents {

    /** Tipo do stream = nome do tópico. */
    public static final String STREAM_TYPE = "identity.notification";

    /** Tópico das mensagens que o consumer não conseguiu processar (política da {@code EventsKafkaConfig}). */
    public static final String DEAD_LETTER_TOPIC = STREAM_TYPE + ".DLT";

    /** Código de verificação do cadastro em duas fases (pedido e reenvio). */
    public static final String VERIFICATION_CODE_REQUESTED = "VerificationCodeRequested";

    /** Código MFA do reset de senha. */
    public static final String PASSWORD_RESET_MFA_REQUESTED = "PasswordResetMfaRequested";

    public static final String EMAIL = "email";
    public static final String USER_NAME = "userName";
    public static final String CODE = "code";
    public static final String EXPIRATION_MINUTES = "expirationMinutes";
    public static final String EXPIRES_AT = "expiresAt";

    /** Validade do código MFA do reset de senha (a maior validade de código do sistema; a do cadastro é menor). */
    public static final Duration MFA_VALIDADE = Duration.ofMinutes(15);

    /** Quanto o dado pessoal sobrevive depois que o código vence (decisão do usuário: validade + 30 s). */
    public static final Duration CARENCIA = Duration.ofSeconds(30);

    /** Retenção do tópico e do .DLT: a maior validade + a carência. A validade do cadastro tem de ser {@code <= MFA_VALIDADE}. */
    public static final Duration RETENCAO_TOPICO = MFA_VALIDADE.plus(CARENCIA);

    /**
     * O broker só apaga segmentos FECHADOS: sem um {@code segment.ms} curto o segmento ativo ficaria aberto por dias e o
     * {@code retention.ms} não teria efeito. Com 5 min o dado some do tópico em até {@code RETENCAO_TOPICO + 5 min}.
     */
    private static final Duration SEGMENTO = Duration.ofMinutes(5);

    private NotificationEvents() {
    }

    /** O tópico do stream (1 partição, retenção curta). Os dois lados declaram o mesmo, idempotente no KafkaAdmin. */
    public static NewTopic topic() {
        return comRetencao(STREAM_TYPE);
    }

    /** O .DLT do tópico: guarda a mensagem original (e-mail inclusive), então tem a mesma retenção curta. */
    public static NewTopic deadLetterTopic() {
        return comRetencao(DEAD_LETTER_TOPIC);
    }

    private static NewTopic comRetencao(String nome) {
        return TopicBuilder.name(nome).partitions(1).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(RETENCAO_TOPICO.toMillis()))
                .config(TopicConfig.SEGMENT_MS_CONFIG, String.valueOf(SEGMENTO.toMillis()))
                .build();
    }
}
