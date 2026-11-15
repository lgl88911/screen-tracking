package com.screentime.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器。
 *
 * 不依赖 android.* 也不依赖 org.json，这样快照导入的解析逻辑能在桌面 JDK 上直接单测
 * —— android.jar 里没有 org.json，桌面 JDK 也没有，两边都跑不了就等于没测。
 *
 * 只做解析，不做序列化：写 JSON 的那一侧在 Exporter 里手拼字符串。
 * 类型映射：object → LinkedHashMap（保持键序）、array → ArrayList、
 * string → String、整数 → Long、小数 → Double、true/false → Boolean、null → null。
 */
public final class Json {

    public static class ParseException extends Exception {
        public ParseException(String msg) {
            super(msg);
        }
    }

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) throws ParseException {
        if (text == null) {
            throw new ParseException("输入为 null");
        }
        // 导出文件本身不带 BOM，但用户手工转存过的副本可能带
        int start = text.length() > 0 && text.charAt(0) == '\uFEFF' ? 1 : 0;
        Json j = new Json(text);
        j.i = start;
        j.skipWs();
        Object v = j.value();
        j.skipWs();
        if (j.i < text.length()) {
            throw new ParseException("第 " + j.i + " 字符后有多余内容");
        }
        return v;
    }

    // ------------------------------------------------------------ 结构

    private Object value() throws ParseException {
        if (i >= s.length()) {
            throw new ParseException("内容意外结束");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return null;
            default:
                return number();
        }
    }

    private Map<String, Object> object() throws ParseException {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++;                       // '{'
        skipWs();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw new ParseException("第 " + i + " 字符处缺少键名");
            }
            String k = string();
            skipWs();
            expect(':');
            skipWs();
            m.put(k, value());
            skipWs();
            char c = peek();
            i++;
            if (c == ',') {
                continue;
            }
            if (c == '}') {
                return m;
            }
            throw new ParseException("第 " + (i - 1) + " 字符处应为 , 或 }");
        }
    }

    private List<Object> array() throws ParseException {
        List<Object> l = new ArrayList<Object>();
        i++;                       // '['
        skipWs();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            skipWs();
            l.add(value());
            skipWs();
            char c = peek();
            i++;
            if (c == ',') {
                continue;
            }
            if (c == ']') {
                return l;
            }
            throw new ParseException("第 " + (i - 1) + " 字符处应为 , 或 ]");
        }
    }

    private String string() throws ParseException {
        i++;                       // 开引号
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw new ParseException("字符串未闭合");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= s.length()) {
                throw new ParseException("转义符后内容结束");
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) {
                        throw new ParseException("\\u 转义不完整");
                    }
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default:
                    throw new ParseException("未知转义 \\" + e);
            }
        }
    }

    private Object number() throws ParseException {
        int start = i;
        if (peek() == '-' || peek() == '+') {
            i++;
        }
        boolean frac = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                frac = true;
                i++;
            } else {
                break;
            }
        }
        String t = s.substring(start, i);
        if (t.length() == 0) {
            throw new ParseException("第 " + start + " 字符处不是合法值");
        }
        try {
            return frac ? (Object) Double.valueOf(t) : (Object) Long.valueOf(t);
        } catch (NumberFormatException e) {
            throw new ParseException("不是合法数字: " + t);
        }
    }

    // ------------------------------------------------------------ 词法

    private char peek() throws ParseException {
        if (i >= s.length()) {
            throw new ParseException("内容意外结束");
        }
        return s.charAt(i);
    }

    private void expect(char c) throws ParseException {
        if (peek() != c) {
            throw new ParseException("第 " + i + " 字符处应为 " + c);
        }
        i++;
    }

    private void literal(String word) throws ParseException {
        if (!s.startsWith(word, i)) {
            throw new ParseException("第 " + i + " 字符处应为 " + word);
        }
        i += word.length();
    }

    private void skipWs() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                i++;
            } else {
                return;
            }
        }
    }
}
