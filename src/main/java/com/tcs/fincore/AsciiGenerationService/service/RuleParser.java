package com.tcs.fincore.AsciiGenerationService.service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RuleParser {

    // Regex to capture: ["Content"] [Col] [Logic]
    private static final Pattern RULE_PATTERN = Pattern.compile("\\[\"(.*?)\"\\]\\[(.*?)\\]\\[(.*?)\\]");

    // CPU OPTIMIZATION: Cache compiled regex patterns to avoid re-compiling millions of times
    private static final Map<String, Pattern> REGEX_CACHE = new ConcurrentHashMap<>();

    // Helper to get cached pattern
    private static Pattern getCachedPattern(String regex) {
        return REGEX_CACHE.computeIfAbsent(regex, Pattern::compile);
    }

    // --- 1. MATH LOGIC (CHAINED) ---
    public static BigDecimal applyMathLogic(BigDecimal amount, String head, int colIdx, String logicConfig) {
        if (logicConfig == null) return amount;

        Matcher m = RULE_PATTERN.matcher(logicConfig);
        while (m.find()) {
            String headRegex = m.group(1);
            String colReq = m.group(2);
            String logic = m.group(3);

            boolean colMatch = colReq.isEmpty() || colReq.equals(String.valueOf(colIdx));
            // OPTIMIZED: Use Cache
            boolean headMatch = getCachedPattern(headRegex).matcher(head).lookingAt();

            if (colMatch && headMatch) {
                if (logic.contains("/")) {
                    BigDecimal divisor = new BigDecimal(logic.replaceAll("[^0-9.]", ""));
                    if (divisor.compareTo(BigDecimal.ZERO) != 0) {
                        amount = amount.divide(divisor, 6, RoundingMode.HALF_UP);
                    }
                } else if (logic.contains("*")) {
                    BigDecimal multiplier = new BigDecimal(logic.replace("*", "").replace("(", "").replace(")", ""));
                    amount = amount.multiply(multiplier);
                } else if (logic.contains("+")) {
                    BigDecimal add = new BigDecimal(logic.replace("+", "").replace("(", "").replace(")", ""));
                    amount = amount.add(add);
                } else if (logic.contains("-")) {
                    BigDecimal sub = new BigDecimal(logic.replace("-", "").replace("(", "").replace(")", ""));
                    amount = amount.subtract(sub);
                }
            }
        }
        return amount;
    }

    // --- 2. DECIMAL LOGIC (CHAINED) ---
    public static BigDecimal applyDecimalLogic(BigDecimal amount, String head, int colIdx, String decimalConfig) {
        if (decimalConfig == null) return amount;

        Matcher m = RULE_PATTERN.matcher(decimalConfig);
        while (m.find()) {
            String headRegex = m.group(1);
            String colReq = m.group(2);
            String rule = m.group(3);

            boolean colMatch = colReq.isEmpty() || colReq.equals(String.valueOf(colIdx));
            boolean headMatch = getCachedPattern(headRegex).matcher(head).lookingAt();

            if (colMatch && headMatch) {
                if (rule.equalsIgnoreCase("floor")) {
                    amount = amount.setScale(0, RoundingMode.FLOOR);
                } else if (rule.equalsIgnoreCase("ceil")) {
                    amount = amount.setScale(0, RoundingMode.CEILING);
                } else if (rule.equalsIgnoreCase("round")) {
                    amount = amount.setScale(0, RoundingMode.HALF_UP);
                } else if (rule.equalsIgnoreCase("down")) {
                    amount = amount.setScale(0, RoundingMode.DOWN);
                }
                // Logic: Remove Decimal (123.29 -> 12329)
                else if (rule.equalsIgnoreCase("remove_decimal") || rule.equalsIgnoreCase("remove")) {
                    amount = amount.movePointRight(amount.scale());
                }
                else {
                    try {
                        int scale = Integer.parseInt(rule);
                        amount = amount.setScale(scale, RoundingMode.HALF_UP);
                    } catch (NumberFormatException e) {}
                }
            }
        }
        return amount;
    }

    // --- 3. PADDING LOGIC (CHAINED) ---
    public static String applyAmountPadding(BigDecimal amount, String head, int colIdx, String padConfig) {
        BigDecimal absAmount = amount.abs();
        String currentStr = absAmount.toPlainString();

        if (padConfig == null) return currentStr;

        Matcher m = RULE_PATTERN.matcher(padConfig);
        while (m.find()) {
            String headRegex = m.group(1);
            String colReq = m.group(2);
            String rule = m.group(3);

            boolean colMatch = colReq.isEmpty() || colReq.equals(String.valueOf(colIdx));
            boolean headMatch = getCachedPattern(headRegex).matcher(head).lookingAt();

            if (colMatch && headMatch) {
                if (rule.contains(",")) {
                    String[] parts = rule.split(",");
                    int totalLen = Integer.parseInt(parts[0]);
                    int decimalLen = Integer.parseInt(parts[1]);

                    BigDecimal scaled = absAmount.setScale(decimalLen, RoundingMode.HALF_UP);
                    currentStr = padLeft(scaled.toPlainString(), totalLen, '0');
                } else {
                    int totalLen = Integer.parseInt(rule);
                    currentStr = padLeft(currentStr, totalLen, '0');
                }
            }
        }
        return currentStr;
    }

    // --- 4. SIGN LOGIC (CHAINED & TRIMMED) ---
    public static String applySign(String fmtAmt, BigDecimal origAmt, String head, int colIdx, String signConfig) {
        String currentStr = fmtAmt;

        if (signConfig == null || signConfig.trim().isEmpty()) {
            currentStr = currentStr.replace("+", "").replace("-", "");
            return (origAmt.compareTo(BigDecimal.ZERO) < 0) ? currentStr + "-" : currentStr;
        }

        Matcher m = RULE_PATTERN.matcher(signConfig);
        boolean anyRuleApplied = false;

        while (m.find()) {
            String headRegex = m.group(1);
            String colReq = m.group(2);
            String rule = m.group(3) != null ? m.group(3).trim() : "";

            boolean colMatch = colReq.isEmpty() || colReq.equals(String.valueOf(colIdx));
            boolean headMatch = getCachedPattern(headRegex).matcher(head).lookingAt();

            if (colMatch && headMatch) {
                anyRuleApplied = true;
                currentStr = currentStr.replace("+", "").replace("-", "");

                if ("NO_SIGN".equalsIgnoreCase(rule)) {
                    // Do nothing
                } else if ("SHOW_SIGN".equalsIgnoreCase(rule)) {
                    if (origAmt.compareTo(BigDecimal.ZERO) >= 0) {
                        currentStr = currentStr + "+";
                    } else {
                        currentStr = currentStr + "-";
                    }
                } else if ("POS_0".equalsIgnoreCase(rule)) {
                    if (origAmt.compareTo(BigDecimal.ZERO) >= 0) {
                        currentStr = currentStr + "0";
                    } else {
                        currentStr = currentStr + "-";
                    }
                }
            }
        }

        if (!anyRuleApplied) {
            currentStr = currentStr.replace("+", "").replace("-", "");
            return (origAmt.compareTo(BigDecimal.ZERO) < 0) ? currentStr + "-" : currentStr;
        }

        return currentStr;
    }

    // --- 5. INCLUSION LOGIC (CHAINED) ---
    public static boolean shouldIncludeValue(BigDecimal amount, String head, int colIdx, String includeConfig) {
        if (includeConfig == null) return true;

        boolean include = true;
        Matcher m = RULE_PATTERN.matcher(includeConfig);

        while (m.find()) {
            String headRegex = m.group(1);
            String colReq = m.group(2);
            String rule = m.group(3);

            boolean colMatch = colReq.isEmpty() || colReq.equals(String.valueOf(colIdx));
            boolean headMatch = getCachedPattern(headRegex).matcher(head).lookingAt();

            if (colMatch && headMatch) {
                if ("ALWAYS".equalsIgnoreCase(rule)) { /* Pass */ }
                else if (rule.equals(">0") && amount.compareTo(BigDecimal.ZERO) <= 0) include = false;
                else if (rule.equals("<0") && amount.compareTo(BigDecimal.ZERO) >= 0) include = false;
                else if ((rule.equals("=0") || rule.equals("0")) && amount.compareTo(BigDecimal.ZERO) != 0) include = false;
                else if (rule.equals("!=0") && amount.compareTo(BigDecimal.ZERO) == 0) include = false;
            }
        }
        return include;
    }

    // --- HEAD PADDING ---
    public static String applyHeadPadding(String head, String padConfig) {
        if (padConfig == null || padConfig.length() < 3) return head;
        int len = Integer.parseInt(padConfig.substring(0, padConfig.length() - 2));
        char dir = padConfig.charAt(padConfig.length() - 2);
        char pad = padConfig.endsWith("\" \"") ? ' ' : padConfig.charAt(padConfig.length() - 1);
        return dir == 'L' ? padLeft(head, len, pad) : padRight(head, len, pad);
    }

    private static String padLeft(String str, int len, char pad) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() + str.length() < len) sb.append(pad);
        return sb.append(str).toString();
    }
    private static String padRight(String str, int len, char pad) {
        StringBuilder sb = new StringBuilder(str);
        while (sb.length() < len) sb.append(pad);
        return sb.toString();
    }
}