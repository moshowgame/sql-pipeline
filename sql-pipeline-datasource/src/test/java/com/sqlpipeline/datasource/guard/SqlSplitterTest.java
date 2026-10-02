package com.sqlpipeline.datasource.guard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlSplitterTest {

    @Test
    void splitSimpleStatements() {
        List<String> result = SqlSplitter.split("SELECT 1; SELECT 2;");
        assertThat(result).containsExactly("SELECT 1", "SELECT 2");
    }

    @Test
    void ignoreSemicolonInsideString() {
        List<String> result = SqlSplitter.split("SELECT 'a;b' FROM t;");
        assertThat(result).containsExactly("SELECT 'a;b' FROM t");
    }

    @Test
    void ignoreSemicolonInsideDollarQuote() {
        String sql = "INSERT INTO t VALUES (1);"
                + " CREATE FUNCTION f() RETURNS int AS $$ BEGIN RETURN 1; END $$ LANGUAGE plpgsql;"
                + " SELECT 2;";
        List<String> result = SqlSplitter.split(sql);
        assertThat(result).hasSize(3);
        assertThat(result.get(1)).contains("BEGIN RETURN 1; END");
    }

    @Test
    void supportTaggedDollarQuote() {
        String sql = "SELECT $fn$ body ; with ; $fn$ FROM t;";
        assertThat(SqlSplitter.split(sql)).containsExactly("SELECT $fn$ body ; with ; $fn$ FROM t");
    }

    @Test
    void dollarSignAsPositionalArgIsNotDollarQuote() {
        List<String> result = SqlSplitter.split("SELECT $1 + $2 FROM t;");
        assertThat(result).containsExactly("SELECT $1 + $2 FROM t");
    }

    @Test
    void stripLineComments() {
        List<String> result = SqlSplitter.split("SELECT 1 -- comment ; here\n; SELECT 2");
        assertThat(result).containsExactly("SELECT 1", "SELECT 2");
    }

    @Test
    void stripBlockCommentsWithNesting() {
        List<String> result = SqlSplitter.split("SELECT /* outer /* inner ; */ still comment */ 1");
        assertThat(result).containsExactly("SELECT   1");
    }

    @Test
    void ignoreSemicolonInsideQuotedIdentifier() {
        List<String> result = SqlSplitter.split("SELECT \"col;a\" FROM t;");
        assertThat(result).containsExactly("SELECT \"col;a\" FROM t");
    }

    @Test
    void escapedQuoteDoubling() {
        List<String> result = SqlSplitter.split("SELECT 'it''s; ok' FROM t;");
        assertThat(result).containsExactly("SELECT 'it''s; ok' FROM t");
    }

    @Test
    void emptyAndCommentOnlyInput() {
        assertThat(SqlSplitter.split(null)).isEmpty();
        assertThat(SqlSplitter.split("   ")).isEmpty();
        assertThat(SqlSplitter.split("-- just comment\n/* another */")).isEmpty();
    }
}
