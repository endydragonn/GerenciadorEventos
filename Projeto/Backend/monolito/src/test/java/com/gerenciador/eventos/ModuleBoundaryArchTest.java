package com.gerenciador.eventos;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import org.springframework.stereotype.Controller;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Fronteira de módulo (issue #23, Fase 5). O monólito é hoje só o {@code gateway} (a borda HTTP, issue #25: roteia e agrega, sem
 * domínio próprio, e valida o JWT em {@code gateway.security}) mais {@code shared} (infra sem domínio). {@code reservation} (#33),
 * {@code catalog} (#35) e {@code identity} (#36) NÃO existem aqui (viraram serviços, outros processos): o monólito só tem os CLIENTES
 * HTTP deles, os pacotes {@code reservationclient}, {@code catalogclient} e {@code identityclient} (libs {@code reservation-client}/
 * {@code catalog-client}/{@code identity-client}).
 *
 * <p>Regra: um módulo só enxerga outro módulo pelo pacote {@code <modulo>.api} (interfaces e
 * DTOs públicos); nunca por {@code Repository}/{@code Service}/config internos. {@code shared}
 * não depende de módulo de domínio. Só analisa bytecode (sem Spring/banco).</p>
 */
@AnalyzeClasses(packages = "com.gerenciador.eventos", importOptions = ImportOption.DoNotIncludeTests.class)
public class ModuleBoundaryArchTest {

    private static final String RAIZ = "com.gerenciador.eventos.";

    /** Todo código de produção mora num módulo (nada sobra nos pacotes antigos POJO/Repository/Service). */
    @ArchTest
    static final ArchRule todo_codigo_mora_em_um_modulo =
        classes()
            .should().resideInAnyPackage(
                "com.gerenciador.eventos",
                RAIZ + "catalogclient..", RAIZ + "reservationclient..",
                RAIZ + "identityclient..", RAIZ + "admissionclient..", RAIZ + "stockclient..", RAIZ + "clientlb..", RAIZ + "shared..", RAIZ + "gateway..")
            .because("o layout por camadas (POJO/Repository/Service/config/security) foi trocado "
                + "por pacotes-por-serviço (issue #23)");

    /** O gateway só chama o catalog pelo contrato ({@code catalogclient.api}), nunca pelo {@code CatalogClient} concreto. */
    @ArchTest
    static final ArchRule gateway_so_usa_modulos_pela_api =
        soPelaApi("gateway", "catalogclient", "identityclient", "admissionclient");

    /**
     * O que SAIU do monólito na #33 não volta: nada aqui depende do pacote do serviço reservation (o código dele mora no
     * módulo Maven {@code reservation}, que o monólito nem tem no classpath). O monólito só fala com ele por HTTP, pelo
     * cliente {@code reservationclient}.
     */
    @ArchTest
    static final ArchRule nada_depende_do_codigo_do_servico_reservation =
        noClasses()
            .should().dependOnClassesThat().resideInAPackage(RAIZ + "reservation..")
            .because("o reservation é outro serviço (issue #33): o monólito só usa o cliente HTTP reservationclient");

    /** O mesmo para o catalog (#35): o código do serviço mora no módulo Maven {@code catalog}, fora do classpath do monólito. */
    @ArchTest
    static final ArchRule nada_depende_do_codigo_do_servico_catalog =
        noClasses()
            .should().dependOnClassesThat().resideInAPackage(RAIZ + "catalog..")
            .because("o catalog é outro serviço (issue #35): o monólito só usa o cliente HTTP catalogclient");

    /** O mesmo para o identity (#36): o código do serviço mora no módulo Maven {@code identity}, fora do classpath do monólito. */
    @ArchTest
    static final ArchRule nada_depende_do_codigo_do_servico_identity =
        noClasses()
            .should().dependOnClassesThat().resideInAPackage(RAIZ + "identity..")
            .because("o identity é outro serviço (issue #36): o monólito só usa o cliente HTTP identityclient");

    /** A lib do cliente é contrato e transporte: não pode depender de módulo do monólito (nem do contrário de dados). */
    @ArchTest
    static final ArchRule reservationclient_nao_depende_de_modulo_do_monolito =
        noClasses()
            .that().resideInAPackage(RAIZ + "reservationclient..")
            .should().dependOnClassesThat().resideInAnyPackage(
                RAIZ + "identity..", RAIZ + "catalogclient..", RAIZ + "identityclient..", RAIZ + "admissionclient..", RAIZ + "stockclient..",
                RAIZ + "gateway..")
            .because("reservation-client é uma lib de contrato HTTP, usada também pelo serviço reservation");

    @ArchTest
    static final ArchRule catalogclient_nao_depende_de_modulo_do_monolito =
        noClasses()
            .that().resideInAPackage(RAIZ + "catalogclient..")
            .should().dependOnClassesThat().resideInAnyPackage(
                RAIZ + "identity..", RAIZ + "reservationclient..", RAIZ + "identityclient..", RAIZ + "admissionclient..", RAIZ + "stockclient..",
                RAIZ + "gateway..")
            .because("catalog-client é uma lib de contrato HTTP, usada também pelo serviço catalog");

    /** A lib do identity (#36) também é só contrato e transporte: nada do monólito nem das outras libs. */
    @ArchTest
    static final ArchRule identityclient_nao_depende_de_modulo_do_monolito =
        noClasses()
            .that().resideInAPackage(RAIZ + "identityclient..")
            .should().dependOnClassesThat().resideInAnyPackage(
                RAIZ + "identity..", RAIZ + "catalogclient..", RAIZ + "reservationclient..", RAIZ + "admissionclient..", RAIZ + "stockclient..",
                RAIZ + "gateway..")
            .because("identity-client é uma lib de contrato HTTP, usada também pelo serviço identity");

    /** A lib do admission (#41) também é só contrato e transporte: nada do monólito nem das outras libs. */
    @ArchTest
    static final ArchRule admissionclient_nao_depende_de_modulo_do_monolito =
        noClasses()
            .that().resideInAPackage(RAIZ + "admissionclient..")
            .should().dependOnClassesThat().resideInAnyPackage(
                RAIZ + "identity..", RAIZ + "catalogclient..", RAIZ + "reservationclient..", RAIZ + "identityclient..",
                RAIZ + "stockclient..", RAIZ + "gateway..")
            .because("admission-client é uma lib de contrato HTTP, usada também pelo serviço admission");

    /** O código do serviço stock (#47) mora no módulo Maven {@code stock}, fora do classpath do monólito. */
    @ArchTest
    static final ArchRule nada_depende_do_codigo_do_servico_stock =
        noClasses()
            .should().dependOnClassesThat().resideInAPackage(RAIZ + "stock..")
            .because("o stock é outro serviço (issue #47): o monólito só usaria o cliente HTTP stockclient");

    /** O código do serviço admission (#41) mora no módulo Maven {@code admission}, fora do classpath do monólito. */
    @ArchTest
    static final ArchRule nada_depende_do_codigo_do_servico_admission =
        noClasses()
            .should().dependOnClassesThat().resideInAPackage(RAIZ + "admission..")
            .because("o admission é outro serviço (issue #41): o monólito só usa o cliente HTTP admissionclient");

    /**
     * O envio de e-mail saiu do monólito na #32 (virou o serviço {@code notification}): nada aqui pode depender do
     * {@code spring-mail}/{@code jakarta.mail}. O monólito só appenda evento no backbone.
     */
    @ArchTest
    static final ArchRule monolito_nao_envia_email =
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.mail..", "jakarta.mail..")
            .because("o e-mail é do serviço notification (issue #32); identity publica um evento no backbone, "
                + "não fala com SMTP");

    /** Só o {@code gateway} expõe HTTP: nenhum outro módulo tem {@code @RestController}/{@code @Controller}. */
    @ArchTest
    static final ArchRule controller_so_no_gateway =
        classes()
            .that().areMetaAnnotatedWith(Controller.class)
            .should().resideInAPackage(RAIZ + "gateway..")
            .because("o gateway é a superfície HTTP única /bff/**; os demais módulos "
                + "não expõem HTTP direto (issue #25)");

    @ArchTest
    static final ArchRule shared_nao_depende_de_modulo_de_dominio =
        noClasses()
            .that().resideInAPackage(RAIZ + "shared..")
            .should().dependOnClassesThat().resideInAnyPackage(
                RAIZ + "identity..", RAIZ + "catalogclient..", RAIZ + "reservationclient..",
                RAIZ + "identityclient..", RAIZ + "admissionclient..", RAIZ + "stockclient..", RAIZ + "gateway..")
            .because("shared é infra sem domínio: módulos dependem dele, nunca o contrário");

    /** O contrato público não pode vazar classe interna (de nenhum módulo): só {@code *.api}, shared e libs. */
    @ArchTest
    static final ArchRule api_nao_depende_de_classe_interna =
        noClasses()
            .that().resideInAnyPackage(
                RAIZ + "catalogclient.api..", RAIZ + "identityclient.api..", RAIZ + "admissionclient.api..")
            .should().dependOnClassesThat(internoDe("catalogclient", "identityclient", "admissionclient", "gateway"))
            .because("o pacote api é o contrato público: só pode depender de outros *.api, de shared e "
                + "de libs, nunca de Service/Repository/config interno (issue #23)");

    /** Nenhuma classe de {@code origem} depende de classe NÃO-api de um dos {@code destinos}. */
    private static ArchRule soPelaApi(String origem, String... destinos) {
        return noClasses()
            .that().resideInAPackage(RAIZ + origem + "..")
            .should().dependOnClassesThat(internoDe(destinos))
            .because(origem + " só pode chamar outro módulo pelo pacote <modulo>.api, "
                + "nunca por Repository/Service internos (issue #23)");
    }

    /** Classe de um dos {@code modulos} que NÃO está no respectivo pacote {@code api} ({@code gateway} não tem api). */
    private static DescribedPredicate<JavaClass> internoDe(String... modulos) {
        DescribedPredicate<JavaClass> interno = null;
        for (String m : modulos) {
            DescribedPredicate<JavaClass> deste = resideInAPackage(RAIZ + m + "..").and(
                DescribedPredicate.not(resideInAPackage(RAIZ + m + ".api..")));
            interno = (interno == null) ? deste : interno.or(deste);
        }
        return interno;
    }
}
