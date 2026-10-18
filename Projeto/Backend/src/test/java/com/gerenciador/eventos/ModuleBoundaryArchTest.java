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
 * Fronteira de módulo (issue #23, Fase 5). O backend é um monólito dividido em módulos por serviço
 * ({@code identity}, {@code catalog}, {@code reservation}, {@code notification}) mais
 * {@code shared} (infra sem domínio); {@code gateway} é a borda HTTP (issue #25): roteia e agrega, sem domínio próprio.
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
                RAIZ + "identity..", RAIZ + "catalog..", RAIZ + "reservation..",
                RAIZ + "notification..", RAIZ + "shared..", RAIZ + "gateway..")
            .because("o layout por camadas (POJO/Repository/Service/config/security) foi trocado "
                + "por pacotes-por-serviço (issue #23)");

    @ArchTest
    static final ArchRule identity_so_usa_outros_modulos_pela_api =
        soPelaApi("identity", "catalog", "reservation", "notification", "gateway");

    @ArchTest
    static final ArchRule catalog_so_usa_outros_modulos_pela_api =
        soPelaApi("catalog", "identity", "reservation", "notification", "gateway");

    @ArchTest
    static final ArchRule reservation_so_usa_outros_modulos_pela_api =
        soPelaApi("reservation", "identity", "catalog", "notification", "gateway");

    @ArchTest
    static final ArchRule notification_so_usa_outros_modulos_pela_api =
        soPelaApi("notification", "identity", "catalog", "reservation", "gateway");

    @ArchTest
    static final ArchRule gateway_so_usa_modulos_pela_api =
        soPelaApi("gateway", "identity", "catalog", "reservation", "notification");

    /** Só o {@code gateway} expõe HTTP: nenhum outro módulo tem {@code @RestController}/{@code @Controller}. */
    @ArchTest
    static final ArchRule controller_so_no_gateway =
        classes()
            .that().areMetaAnnotatedWith(Controller.class)
            .should().resideInAPackage(RAIZ + "gateway..")
            .because("o gateway é a superfície HTTP única /bff/**; identity/catalog/reservation/notification "
                + "não expõem HTTP direto (issue #25)");

    @ArchTest
    static final ArchRule shared_nao_depende_de_modulo_de_dominio =
        noClasses()
            .that().resideInAPackage(RAIZ + "shared..")
            .should().dependOnClassesThat().resideInAnyPackage(
                RAIZ + "identity..", RAIZ + "catalog..", RAIZ + "reservation..",
                RAIZ + "notification..", RAIZ + "gateway..")
            .because("shared é infra sem domínio: módulos dependem dele, nunca o contrário");

    /** O contrato público não pode vazar classe interna (de nenhum módulo): só {@code *.api}, shared e libs. */
    @ArchTest
    static final ArchRule api_nao_depende_de_classe_interna =
        noClasses()
            .that().resideInAnyPackage(
                RAIZ + "identity.api..", RAIZ + "catalog.api..", RAIZ + "reservation.api..",
                RAIZ + "notification.api..")
            .should().dependOnClassesThat(internoDe("identity", "catalog", "reservation", "notification", "gateway"))
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
