package io.floci.aws.services.athena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DuckDB DESCRIBE reports types such as BIGINT and DOUBLE, while Athena
 * reports the same columns as bigint and double. The mapper translates
 * between the two vocabularies, and anything it cannot translate falls
 * back to varchar, which is what every column reported before.
 */
class DuckDbTypeMapperTest {

    @Test
    void mapsTheIssueQueryTypes() {
        assertEquals("bigint", DuckDbTypeMapper.toAthenaType("BIGINT"));
        assertEquals("double", DuckDbTypeMapper.toAthenaType("DOUBLE"));
        assertEquals("boolean", DuckDbTypeMapper.toAthenaType("BOOLEAN"));
        assertEquals("date", DuckDbTypeMapper.toAthenaType("DATE"));
    }

    @Test
    void mapsIntegerTypes() {
        assertEquals("tinyint", DuckDbTypeMapper.toAthenaType("TINYINT"));
        assertEquals("smallint", DuckDbTypeMapper.toAthenaType("SMALLINT"));
        assertEquals("integer", DuckDbTypeMapper.toAthenaType("INTEGER"));
        assertEquals("integer", DuckDbTypeMapper.toAthenaType("INT"));
        assertEquals("bigint", DuckDbTypeMapper.toAthenaType("INT8"));
        assertEquals("integer", DuckDbTypeMapper.toAthenaType("INT4"));
        assertEquals("smallint", DuckDbTypeMapper.toAthenaType("UTINYINT"));
        assertEquals("integer", DuckDbTypeMapper.toAthenaType("USMALLINT"));
        assertEquals("bigint", DuckDbTypeMapper.toAthenaType("UINTEGER"));
        assertEquals("decimal(20,0)", DuckDbTypeMapper.toAthenaType("UBIGINT"));
    }

    @Test
    void mapsFloatingPointAndDecimalTypes() {
        assertEquals("real", DuckDbTypeMapper.toAthenaType("FLOAT"));
        assertEquals("real", DuckDbTypeMapper.toAthenaType("REAL"));
        assertEquals("real", DuckDbTypeMapper.toAthenaType("FLOAT4"));
        assertEquals("double", DuckDbTypeMapper.toAthenaType("FLOAT8"));
        assertEquals("double", DuckDbTypeMapper.toAthenaType("DOUBLE PRECISION"));
        assertEquals("decimal", DuckDbTypeMapper.toAthenaType("DECIMAL"));
        assertEquals("decimal(5,1)", DuckDbTypeMapper.toAthenaType("NUMERIC(5,1)"));
        assertEquals("decimal(10,2)", DuckDbTypeMapper.toAthenaType("DECIMAL(10,2)"));
        assertEquals("decimal(10,2)", DuckDbTypeMapper.toAthenaType("DECIMAL( 10 , 2 )"));
    }

    @Test
    void mapsStringAndBinaryTypes() {
        assertEquals("varchar", DuckDbTypeMapper.toAthenaType("VARCHAR"));
        assertEquals("varchar", DuckDbTypeMapper.toAthenaType("TEXT"));
        assertEquals("char", DuckDbTypeMapper.toAthenaType("CHAR(3)"));
        assertEquals("varbinary", DuckDbTypeMapper.toAthenaType("BLOB"));
        assertEquals("varbinary", DuckDbTypeMapper.toAthenaType("BYTEA"));
    }

    @Test
    void mapsTemporalTypes() {
        assertEquals("time", DuckDbTypeMapper.toAthenaType("TIME"));
        assertEquals("time with time zone", DuckDbTypeMapper.toAthenaType("TIME WITH TIME ZONE"));
        assertEquals("time with time zone", DuckDbTypeMapper.toAthenaType("TIMETZ"));
        assertEquals("timestamp", DuckDbTypeMapper.toAthenaType("TIMESTAMP"));
        assertEquals("timestamp", DuckDbTypeMapper.toAthenaType("TIMESTAMP_S"));
        assertEquals("timestamp", DuckDbTypeMapper.toAthenaType("TIMESTAMP_MS"));
        assertEquals("timestamp", DuckDbTypeMapper.toAthenaType("TIMESTAMP_NS"));
        assertEquals("timestamp with time zone", DuckDbTypeMapper.toAthenaType("TIMESTAMP WITH TIME ZONE"));
        assertEquals("timestamp with time zone", DuckDbTypeMapper.toAthenaType("TIMESTAMPTZ"));
    }

    @Test
    void mapsJson() {
        assertEquals("json", DuckDbTypeMapper.toAthenaType("JSON"));
    }

    @Test
    void mapsNestedTypesRecursively() {
        assertEquals("struct<a:bigint,b:varchar>",
                DuckDbTypeMapper.toAthenaType("STRUCT(a BIGINT, b VARCHAR)"));
        assertEquals("struct<x:struct<y:double>>",
                DuckDbTypeMapper.toAthenaType("STRUCT(x STRUCT(y DOUBLE))"));
        assertEquals("array<integer>", DuckDbTypeMapper.toAthenaType("INTEGER[]"));
        assertEquals("array<decimal(10,2)>", DuckDbTypeMapper.toAthenaType("DECIMAL(10,2)[]"));
        assertEquals("array<varchar>", DuckDbTypeMapper.toAthenaType("VARCHAR[3]"));
        assertEquals("map<varchar,bigint>", DuckDbTypeMapper.toAthenaType("MAP(VARCHAR, BIGINT)"));
        assertEquals("struct<items:array<struct<id:bigint>>>",
                DuckDbTypeMapper.toAthenaType("STRUCT(items STRUCT(id BIGINT)[])"));
    }

    @Test
    void toleratesCaseAndSurroundingWhitespace() {
        assertEquals("bigint", DuckDbTypeMapper.toAthenaType("bigint"));
        assertEquals("double", DuckDbTypeMapper.toAthenaType("  Double  "));
        assertEquals("varchar", DuckDbTypeMapper.toAthenaType("varchar"));
    }

    @Test
    void preservesStructFieldNameCase() {
        assertEquals("struct<MyField:bigint>",
                DuckDbTypeMapper.toAthenaType("STRUCT(MyField BIGINT)"));
    }

    @Test
    void fallsBackToVarcharForUnrepresentableTypes() {
        assertEquals("varchar", DuckDbTypeMapper.toAthenaType("HUGEINT"));
        assertEquals("varchar", DuckDbTypeMapper.toAthenaType("UUID"));
        assertEquals("varchar", DuckDbTypeMapper.toAthenaType("INTERVAL"));
        assertEquals("varchar", DuckDbTypeMapper.toAthenaType("ENUM('a','b')"));
        assertEquals("varchar", DuckDbTypeMapper.toAthenaType("UNION(a INT)"));
        assertEquals("varchar", DuckDbTypeMapper.toAthenaType("NOTATYPE"));
    }

    @Test
    void fallsBackToVarcharForNullBlankAndMalformedInput() {
        assertEquals("varchar", DuckDbTypeMapper.toAthenaType(null));
        assertEquals("varchar", DuckDbTypeMapper.toAthenaType(""));
        assertEquals("varchar", DuckDbTypeMapper.toAthenaType("   "));
        assertEquals("varchar", DuckDbTypeMapper.toAthenaType("BIGINT("));
        assertEquals("varchar", DuckDbTypeMapper.toAthenaType("STRUCT()"));
        assertEquals("varchar", DuckDbTypeMapper.toAthenaType("INTEGER[2"));
        assertEquals("varchar", DuckDbTypeMapper.toAthenaType("BIGINT extra"));
    }
}
