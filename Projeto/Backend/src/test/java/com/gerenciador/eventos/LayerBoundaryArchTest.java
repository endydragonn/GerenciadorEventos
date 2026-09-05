package com.gerenciador.eventos;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import com.tngtech.archunit.core.domain.JavaClass.Predicates;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Fronteira de camada (issue #83): o controller/apresentação (pacote {@code bff}) não pode
 * pular a camada {@code Service} e acessar dado diretamente, seja via {@code Repository}
 * (DAO do projeto) ou via JDBC cru (java.sql.*). Roda no {@code mvn test} padrão, sem Docker/
 * Postgres — ArchUnit só analisa o bytecode compilado, não bate no banco.
 *
 * Medido na issue #83: zero violações hoje (o único uso de java.sql.* em bff/ é
 * {@code catch (SQLException e)} em BffUserController, que não é "acesso a dado" e por isso
 * fica fora da lista abaixo) — a regra já nasce travando o build, sem baseline/supressão.
 */
@AnalyzeClasses(packages = "com.gerenciador.eventos", importOptions = ImportOption.DoNotIncludeTests.class)
public class LayerBoundaryArchTest {

    @ArchTest
    static final ArchRule bff_nao_acessa_repository_diretamente =
        noClasses()
            .that().resideInAPackage("..bff..")
            .should().dependOnClassesThat().resideInAPackage("..Repository..")
            .because("controllers de bff/ devem falar com o banco através de Service/ — "
                + "acessar Repository/ direto pula a camada de regra de negócio (issue #83)");

    @ArchTest
    static final ArchRule bff_nao_usa_jdbc_cru =
        noClasses()
            .that().resideInAPackage("..bff..")
            .should().dependOnClassesThat(
                Predicates.belongToAnyOf(
                    Connection.class, PreparedStatement.class, Statement.class,
                    ResultSet.class, DriverManager.class))
            .because("controllers de bff/ não podem abrir Connection/Statement JDBC direto — "
                + "isso é responsabilidade de Repository/, chamado a partir de Service/ "
                + "(issue #83)");
}
