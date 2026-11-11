package fr.hotelvente;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formatage de texte : codes &amp; (&amp;c, &amp;l...), hex (&amp;#ff8800)
 * et balises MiniMessage (&lt;gradient:red:gold&gt;...).
 */
public final class Text {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern LEGACY = Pattern.compile("&([0-9a-fk-orA-FK-OR])");
    private static final Map<Character, String> CODES = new HashMap<>();

    static {
        String[] colors = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
                "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
        String hex = "0123456789abcdef";
        for (int i = 0; i < colors.length; i++) {
            CODES.put(hex.charAt(i), "<reset><" + colors[i] + ">");
        }
        CODES.put('k', "<obfuscated>");
        CODES.put('l', "<bold>");
        CODES.put('m', "<strikethrough>");
        CODES.put('n', "<underlined>");
        CODES.put('o', "<italic>");
        CODES.put('r', "<reset>");
    }

    private Text() {}

    public static Component parse(String input) {
        if (input == null) input = "";
        String s = HEX.matcher(input).replaceAll("<reset><#$1>");
        Matcher m = LEGACY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rep = CODES.get(Character.toLowerCase(m.group(1).charAt(0)));
            m.appendReplacement(sb, Matcher.quoteReplacement(rep == null ? m.group() : rep));
        }
        m.appendTail(sb);
        return MM.deserialize(sb.toString())
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> parseList(List<String> lines, Map<String, String> ph) {
        List<Component> out = new ArrayList<>();
        for (String line : lines) out.add(parse(replace(line, ph)));
        return out;
    }

    public static String replace(String s, Map<String, String> ph) {
        if (s == null) return "";
        if (ph == null) return s;
        for (Map.Entry<String, String> e : ph.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return s;
    }

    /** ph("a","1","b","2") -> {a=1, b=2} */
    public static Map<String, String> ph(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) map.put(kv[i], kv[i + 1]);
        return map;
    }

    public static String duration(long ms, String format, String less) {
        if (ms < 60_000L) return less;
        long totalMinutes = ms / 60_000L;
        long h = totalMinutes / 60;
        long m = totalMinutes % 60;
        return format.replace("{h}", String.valueOf(h)).replace("{m}", String.valueOf(m));
    }
}
