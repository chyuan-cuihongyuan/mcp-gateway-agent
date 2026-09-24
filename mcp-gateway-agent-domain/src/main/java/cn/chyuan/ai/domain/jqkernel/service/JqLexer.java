package cn.chyuan.ai.domain.jqkernel.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * jq 词法（工单 0756 CL1，jq 思想）。
 * 记号（. | , [ ] { } ( ) : ; ?）与数字字符串字面量（转义与 \( ) 插值标记）/
 * 变量 $x/关键字标识符/非法字符报错定位。
 */
public final class JqLexer {

    public enum Kind {
        DOT, PIPE, COMMA, LBRACKET, RBRACKET, LBRACE, RBRACE, LPAREN, RPAREN, COLON, SEMI, QUESTION,
        NUMBER, STRING, IDENT, VARIABLE, PLUS, MINUS, STAR, SLASH, PERCENT,
        EQ, NEQ, LT, LE, GT, GE, AND, OR, NOT_KW, REDUCE, FOREACH, AS, DEF, IF, THEN, ELIF, ELSE, END, TRY, CATCH,
        EOF
    }

    public record Token(Kind kind, String text, int position) {
    }

    private static final Map<String, Kind> KEYWORDS = Map.ofEntries(
            Map.entry("and", Kind.AND), Map.entry("or", Kind.OR), Map.entry("not", Kind.NOT_KW),
            Map.entry("reduce", Kind.REDUCE), Map.entry("foreach", Kind.FOREACH), Map.entry("as", Kind.AS),
            Map.entry("def", Kind.DEF), Map.entry("if", Kind.IF), Map.entry("then", Kind.THEN),
            Map.entry("elif", Kind.ELIF), Map.entry("else", Kind.ELSE), Map.entry("end", Kind.END),
            Map.entry("try", Kind.TRY), Map.entry("catch", Kind.CATCH));

    private final String source;
    private int at;
    private final List<Token> tokens = new ArrayList<>();
    private boolean inString;
    private int interpDepth;
    private final StringBuilder strBuf = new StringBuilder();

    public JqLexer(String source) {
        this.source = source;
    }

    public List<Token> lex() {
        while (at < source.length()) {
            if (inString) {
                scanStringChar();
                continue;
            }
            char c = source.charAt(at);
            if (Character.isWhitespace(c)) {
                at++;
                continue;
            }
            switch (c) {
                case '.' -> emit(Kind.DOT);
                case '|' -> emit(Kind.PIPE);
                case ',' -> emit(Kind.COMMA);
                case '[' -> emit(Kind.LBRACKET);
                case ']' -> emit(Kind.RBRACKET);
                case '{' -> emit(Kind.LBRACE);
                case '}' -> emit(Kind.RBRACE);
                case '(' -> emit(Kind.LPAREN);
                case ')' -> {
                    if (interpDepth > 0) {
                        interpDepth--;
                        at++;
                        tokens.add(new Token(Kind.RPAREN, "\\)", at - 1));
                        if (interpDepth == 0) {
                            inString = true;
                        }
                    } else {
                        emit(Kind.RPAREN);
                    }
                }
                case ':' -> emit(Kind.COLON);
                case ';' -> emit(Kind.SEMI);
                case '?' -> emit(Kind.QUESTION);
                case '+' -> emit(Kind.PLUS);
                case '*' -> emit(Kind.STAR);
                case '/' -> emit(Kind.SLASH);
                case '%' -> emit(Kind.PERCENT);
                case '-' -> emit(Kind.MINUS);
                case '=' -> {
                    if (peekIs('=')) {
                        at++;
                        tokens.add(new Token(Kind.EQ, "==", at - 1));
                        at++;
                    } else {
                        throw new IllegalArgumentException("非法单 =（位 " + at + "）");
                    }
                }
                case '!' -> {
                    if (peekIs('=')) {
                        at++;
                        tokens.add(new Token(Kind.NEQ, "!=", at - 1));
                        at++;
                    } else {
                        throw new IllegalArgumentException("非法单 !（位 " + at + "）");
                    }
                }
                case '<' -> twoChar(Kind.LE, Kind.LT);
                case '>' -> twoChar(Kind.GE, Kind.GT);
                case '"' -> {
                    inString = true;
                    strBuf.setLength(0);
                    at++;
                }
                case '$' -> lexVariable();
                default -> {
                    if (Character.isDigit(c)) {
                        lexNumber();
                    } else if (Character.isLetter(c) || c == '_') {
                        lexIdent();
                    } else {
                        throw new IllegalArgumentException("非法字符 '" + c + "'（位 " + at + "）");
                    }
                }
            }
        }
        if (inString || interpDepth > 0) {
            throw new IllegalArgumentException("字符串未闭合（位 " + at + "）");
        }
        tokens.add(new Token(Kind.EOF, "", at));
        return tokens;
    }

    /** 字符串态扫描：转义、\( ) 插值入口、闭引号 */
    private void scanStringChar() {
        char c = source.charAt(at);
        if (c == '"') {
            tokens.add(new Token(Kind.STRING, strBuf.toString(), at - strBuf.length()));
            strBuf.setLength(0);
            inString = false;
            at++;
            return;
        }
        if (c == '\\' && peekIs('(')) {
            tokens.add(new Token(Kind.STRING, strBuf.toString(), at - strBuf.length()));
            strBuf.setLength(0);
            tokens.add(new Token(Kind.LPAREN, "\\(", at));
            at += 2;
            inString = false;
            interpDepth = 1;
            return;
        }
        if (c == '\\') {
            at++;
            if (at >= source.length()) {
                throw new IllegalArgumentException("转义截断（字符串未闭合）");
            }
            char esc = source.charAt(at);
            strBuf.append(switch (esc) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case '"' -> '"';
                case '\\' -> '\\';
                default -> throw new IllegalArgumentException("非法转义 \\" + esc + "（位 " + at + "）");
            });
            at++;
            return;
        }
        strBuf.append(c);
        at++;
    }

    private boolean peekIs(char expected) {
        return at + 1 < source.length() && source.charAt(at + 1) == expected;
    }

    private void twoChar(Kind two, Kind one) {
        if (peekIs('=')) {
            at++;
            tokens.add(new Token(two, one == Kind.LT ? "<=" : ">=", at - 1));
            at++;
        } else {
            emit(one);
        }
    }

    private void emit(Kind kind) {
        tokens.add(new Token(kind, String.valueOf(source.charAt(at)), at));
        at++;
    }

    private void lexNumber() {
        int start = at;
        while (at < source.length() && (Character.isDigit(source.charAt(at)) || source.charAt(at) == '.'
                || source.charAt(at) == 'e' || source.charAt(at) == 'E')) {
            at++;
        }
        tokens.add(new Token(Kind.NUMBER, source.substring(start, at), start));
    }

    private void lexIdent() {
        int start = at;
        while (at < source.length() && (Character.isLetterOrDigit(source.charAt(at)) || source.charAt(at) == '_')) {
            at++;
        }
        String word = source.substring(start, at);
        tokens.add(new Token(KEYWORDS.getOrDefault(word, Kind.IDENT), word, start));
    }

    private void lexVariable() {
        int start = at;
        at++;
        if (at >= source.length() || (!Character.isLetter(source.charAt(at)) && source.charAt(at) != '_')) {
            throw new IllegalArgumentException("变量名缺失（位 " + start + "）");
        }
        while (at < source.length() && (Character.isLetterOrDigit(source.charAt(at)) || source.charAt(at) == '_')) {
            at++;
        }
        tokens.add(new Token(Kind.VARIABLE, source.substring(start, at), start));
    }
}
