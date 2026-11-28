package tv.herrin.app.spiderdialog;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** Presents an instruction without interpreting or modifying the provider's authorization data. */
public final class AuthorizationPrompt {
    private static final Pattern ASIDE = Pattern.compile("\\([^()\\r\\n]*\\)|（[^（）\\r\\n]*）");
    private static final Pattern PRIVATE = Pattern.compile("(?i)://|\\b(?:dns|doh|token|cookie|ticket|authorization|secret|password|bearer)\\b|[?&=]");
    private static final Pattern ACTION = Pattern.compile("扫码|扫描|登录|授权|验证码|访问码|提取码|密码|确认|继续|(?i:\\bscan\\b|\\bsign in\\b|\\blog in\\b|\\bverification code\\b)");

    private AuthorizationPrompt() {}

    public static String instruction(String qr, String original) {
        String provider = provider(qr);
        if (provider != null) return "请使用" + provider + " APP 扫码。";
        if (original != null) {
            String text = ASIDE.matcher(original).replaceAll("");
            for (String clause : text.split("[\\r\\n。；;，,]+")) {
                String clean = clause.replaceAll("[\\p{Cc}\\p{Cf}]", "").trim();
                if (clean.length() <= 96 && ACTION.matcher(clean).find() && !PRIVATE.matcher(clean).find())
                    return clean + "。";
            }
        }
        return qr == null || qr.isBlank() ? "" : "请按来源要求扫描二维码。";
    }

    private static String provider(String qr) {
        if (qr == null || qr.length() > 4096) return null;
        try {
            URI uri = URI.create(qr);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getHost() == null)
                return null;
            return switch (uri.getHost().toLowerCase(Locale.ROOT)) {
                case "quark.cn", "pan.quark.cn", "su.quark.cn" -> "夸克";
                case "pan.baidu.com", "passport.baidu.com" -> "百度网盘";
                case "aliyundrive.com", "www.aliyundrive.com", "alipan.com", "www.alipan.com", "auth.alipan.com" -> "阿里云盘";
                default -> null;
            };
        } catch (IllegalArgumentException invalidQr) {
            return null;
        }
    }
}
