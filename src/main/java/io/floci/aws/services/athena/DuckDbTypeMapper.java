package io.floci.aws.services.athena;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps DuckDB type names, as reported by DESCRIBE, to the Athena type names
 * GetQueryResults reports in ColumnInfo. DuckDB and Athena share most type
 * names, but differ on a few (FLOAT is real, BLOB is varbinary) and Athena
 * has no equivalent for some DuckDB types. Anything unknown or
 * unrepresentable falls back to varchar, which is what the result CSV parser
 * reported for every column before this mapping existed.
 */
public final class DuckDbTypeMapper {

    private DuckDbTypeMapper() {
    }

    public static String toAthenaType(String duckDbType) {
        if (duckDbType == null || duckDbType.isBlank()) {
            return "varchar";
        }
        try {
            Parser parser = new Parser(duckDbType.strip());
            String mapped = parser.parseType();
            parser.requireEnd();
            return mapped;
        } catch (IllegalArgumentException e) {
            return "varchar";
        }
    }

    private static String mapPrimitive(String base, String params) {
        switch (base) {
            case "BOOLEAN":
                return "boolean";
            case "TINYINT":
                return "tinyint";
            case "SMALLINT":
                return "smallint";
            case "INTEGER":
            case "INT":
            case "INT4":
                return "integer";
            case "BIGINT":
            case "INT8":
                return "bigint";
            case "UTINYINT":
                return "smallint";
            case "USMALLINT":
                return "integer";
            case "UINTEGER":
                return "bigint";
            case "UBIGINT":
                return "decimal(20,0)";
            case "HUGEINT":
                return "varchar";
            case "FLOAT":
            case "REAL":
            case "FLOAT4":
                return "real";
            case "DOUBLE":
            case "FLOAT8":
            case "DOUBLE PRECISION":
                return "double";
            case "DECIMAL":
            case "NUMERIC":
                if (params == null) {
                    return "decimal";
                }
                String compact = params.replace(" ", "");
                return compact.matches("\\d+,\\d+") ? "decimal(" + compact + ")" : "decimal";
            case "VARCHAR":
            case "TEXT":
            case "STRING":
                return "varchar";
            case "CHAR":
            case "BPCHAR":
                return "char";
            case "BLOB":
            case "BYTEA":
                return "varbinary";
            case "DATE":
                return "date";
            case "TIME":
                return "time";
            case "TIME WITH TIME ZONE":
            case "TIMETZ":
                return "time with time zone";
            case "TIMESTAMP":
            case "DATETIME":
            case "TIMESTAMP_S":
            case "TIMESTAMP_MS":
            case "TIMESTAMP_NS":
                return "timestamp";
            case "TIMESTAMP WITH TIME ZONE":
            case "TIMESTAMPTZ":
                return "timestamp with time zone";
            case "JSON":
                return "json";
            case "UUID":
            case "INTERVAL":
            case "BIT":
            case "BITSTRING":
            default:
                return "varchar";
        }
    }

    private static final class Parser {
        private final String input;
        private int pos;

        Parser(String input) {
            this.input = input;
            this.pos = 0;
        }

        String parseType() {
            skipSpaces();
            String base = readBase();
            if (base.isEmpty()) {
                throw new IllegalArgumentException("empty type at offset " + pos);
            }
            String upperBase = base.toUpperCase(Locale.ROOT);
            String mapped;
            if (upperBase.equals("STRUCT")) {
                mapped = parseStruct();
            } else if (upperBase.equals("MAP")) {
                mapped = parseMap();
            } else if (upperBase.equals("UNION") || upperBase.equals("ENUM")) {
                skipBalancedParens();
                mapped = "varchar";
            } else {
                mapped = mapPrimitive(upperBase, readParams());
            }
            while (readArraySuffix()) {
                mapped = "array<" + mapped + ">";
            }
            return mapped;
        }

        void requireEnd() {
            skipSpaces();
            if (pos != input.length()) {
                throw new IllegalArgumentException("trailing characters at offset " + pos);
            }
        }

        private String parseStruct() {
            expect('(');
            List<String> fields = new ArrayList<>();
            skipSpaces();
            if (peek() == ')') {
                throw new IllegalArgumentException("empty struct at offset " + pos);
            }
            while (true) {
                skipSpaces();
                String name = readIdentifier();
                skipSpaces();
                String fieldType = parseType();
                fields.add(name + ":" + fieldType);
                skipSpaces();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                if (peek() == ')') {
                    break;
                }
                throw new IllegalArgumentException("expected , or ) at offset " + pos);
            }
            expect(')');
            return "struct<" + String.join(",", fields) + ">";
        }

        private String parseMap() {
            expect('(');
            String keyType = parseType();
            skipSpaces();
            expect(',');
            String valueType = parseType();
            skipSpaces();
            expect(')');
            return "map<" + keyType + "," + valueType + ">";
        }

        private String readBase() {
            StringBuilder base = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (Character.isLetter(c)) {
                    base.append(c);
                    pos++;
                } else if (base.length() > 0 && (Character.isDigit(c) || c == '_')) {
                    // Type names such as INT8 and TIMESTAMP_S carry digits and underscores.
                    base.append(c);
                    pos++;
                } else if (c == ' ') {
                    if (base.length() > 0 && base.charAt(base.length() - 1) != ' ') {
                        base.append(' ');
                    }
                    pos++;
                } else {
                    break;
                }
            }
            return base.toString().strip();
        }

        private String readParams() {
            skipSpaces();
            if (peek() != '(') {
                return null;
            }
            pos++;
            int start = pos;
            int depth = 1;
            while (pos < input.length() && depth > 0) {
                char c = input.charAt(pos);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
                pos++;
            }
            if (depth != 0) {
                throw new IllegalArgumentException("unbalanced parens at offset " + pos);
            }
            return input.substring(start, pos - 1);
        }

        private void skipBalancedParens() {
            skipSpaces();
            if (peek() == '(') {
                readParams();
            }
        }

        private boolean readArraySuffix() {
            skipSpaces();
            if (peek() == '[') {
                pos++;
                skipSpaces();
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
                skipSpaces();
                expect(']');
                return true;
            }
            if (matchWord("ARRAY")) {
                return true;
            }
            return false;
        }

        private boolean matchWord(String word) {
            if (input.regionMatches(true, pos, word, 0, word.length())) {
                int after = pos + word.length();
                if (after >= input.length() || !Character.isLetterOrDigit(input.charAt(after))) {
                    pos = after;
                    return true;
                }
            }
            return false;
        }

        private String readIdentifier() {
            skipSpaces();
            if (peek() == '"') {
                pos++;
                StringBuilder name = new StringBuilder();
                while (pos < input.length()) {
                    char c = input.charAt(pos);
                    if (c == '"') {
                        if (pos + 1 < input.length() && input.charAt(pos + 1) == '"') {
                            name.append('"');
                            pos += 2;
                        } else {
                            pos++;
                            return name.toString();
                        }
                    } else {
                        name.append(c);
                        pos++;
                    }
                }
                throw new IllegalArgumentException("unterminated identifier at offset " + pos);
            }
            int start = pos;
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                    pos++;
                } else {
                    break;
                }
            }
            if (pos == start) {
                throw new IllegalArgumentException("expected identifier at offset " + pos);
            }
            return input.substring(start, pos);
        }

        private void skipSpaces() {
            while (pos < input.length() && input.charAt(pos) == ' ') {
                pos++;
            }
        }

        private char peek() {
            return pos < input.length() ? input.charAt(pos) : '\0';
        }

        private void expect(char expected) {
            skipSpaces();
            if (peek() != expected) {
                throw new IllegalArgumentException("expected " + expected + " at offset " + pos);
            }
            pos++;
        }
    }
}
