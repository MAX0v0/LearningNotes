package Max;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 统一配置管理类 —— 从 classpath 下的 config.properties 加载所有配置。
 * <p>
 * 使用方式：
 * <pre>{@code
 * String apiKey = Config.getDeepseekApiKey();
 * String tesseractPath = Config.getTesseractPath();
 * }</pre>
 * <p>
 * 配置文件位置：{@code src/main/resources/config.properties}
 * 首次启动前，请编辑该文件填入你的 API Key 和 Tesseract 路径。
 * <p>
 * 若配置文件缺失或某配置项未填，所有 getter 会返回内置默认值，
 * 确保程序不会因配置缺失而崩溃。
 *
 * @author Max
 * @version 1.0
 */
public final class Config {

    private static final Properties props = new Properties();

    static {
        // 1. 尝试加载用户配置文件
        try (InputStream rawIn = Config.class.getClassLoader()
                .getResourceAsStream("config.properties")) {
            if (rawIn != null) {
                // --- 处理 UTF-8 BOM（Windows 编辑器常用）---
                // Java UTF-8 Reader 不会自动跳过 BOM，需要手动检测并剥离，
                // 否则 BOM 会变成第一个 key 的前缀字符 ﻿，导致解析失败。
                PushbackInputStream pin = new PushbackInputStream(rawIn, 3);
                byte[] bom = new byte[3];
                int bomRead = pin.read(bom);
                if (bomRead == 3 && bom[0] == (byte) 0xEF
                                 && bom[1] == (byte) 0xBB
                                 && bom[2] == (byte) 0xBF) {
                    // 检测到 BOM，已跳过（不 push back）
                    System.out.println("[Config] 检测到 UTF-8 BOM，已自动跳过。");
                } else if (bomRead > 0) {
                    // 不是 BOM，回退已读字节
                    pin.unread(bom, 0, bomRead);
                }

                props.load(new InputStreamReader(pin, StandardCharsets.UTF_8));
                System.out.println("[Config] 已加载配置文件 config.properties");
            } else {
                System.out.println("[Config] 未找到 config.properties，使用默认配置。");
                System.out.println("[Config] 请在 src/main/resources/ 下创建 config.properties 文件。");
            }
        } catch (IOException e) {
            System.err.println("[Config] 加载配置文件失败: " + e.getMessage());
        }
    }

    private Config() { /* 工具类，禁止实例化 */ }

    // ==========================================
    // DeepSeek 大模型配置
    // ==========================================

    /**
     * DeepSeek API Key。
     * 对应 config.properties 中的 {@code deepseek.api.key}。
     * 未配置时返回空字符串，Agent 模式自动降级为手动模式。
     */
    public static String getDeepseekApiKey() {
        return props.getProperty("deepseek.api.key", "").trim();
    }

    /**
     * DeepSeek API 端点地址。
     * 默认：{@code https://api.deepseek.com/v1}
     */
    public static String getDeepseekBaseUrl() {
        return props.getProperty("deepseek.api.base-url",
                "https://api.deepseek.com/v1").trim();
    }

    /**
     * DeepSeek 模型名称。
     * 默认：{@code deepseek-chat}（DeepSeek-V3）
     */
    public static String getDeepseekModel() {
        return props.getProperty("deepseek.model.name",
                "deepseek-chat").trim();
    }

    // ==========================================
    // Tesseract OCR 引擎配置
    // ==========================================

    /**
     * Tesseract 可执行文件路径。
     * 对应 config.properties 中的 {@code tesseract.path}。
     * 默认：{@code D:\Program Files\Tesseract-OCR\tesseract.exe}
     */
    public static String getTesseractPath() {
        return props.getProperty("tesseract.path",
                "D:\\Program Files\\Tesseract-OCR\\tesseract.exe").trim();
    }

    /**
     * OCR 语言包。
     * 对应 config.properties 中的 {@code tesseract.language}。
     * 默认：{@code chi_sim+eng}（简体中文 + 英文）
     */
    public static String getTesseractLanguage() {
        return props.getProperty("tesseract.language",
                "chi_sim+eng").trim();
    }

    // ==========================================
    // Agent 行为参数
    // ==========================================

    /**
     * 大模型温度参数（0.0 ~ 2.0）。
     * 默认：{@code 0.2}（精确模式，适合工具调用）
     */
    public static double getAgentTemperature() {
        try {
            return Double.parseDouble(props.getProperty("agent.temperature", "0.2"));
        } catch (NumberFormatException e) {
            return 0.2;
        }
    }

    /**
     * 大模型最大输出 Token 数。
     * 默认：{@code 4096}
     */
    public static int getAgentMaxTokens() {
        try {
            return Integer.parseInt(props.getProperty("agent.max-tokens", "4096"));
        } catch (NumberFormatException e) {
            return 4096;
        }
    }

    // ==========================================
    // 模拟输入设置
    // ==========================================

    /**
     * 打字前倒计时秒数（3 ~ 30）。
     * 默认：{@code 8}
     */
    public static int getTypingDelaySeconds() {
        try {
            int s = Integer.parseInt(props.getProperty("typing.delay.seconds", "8"));
            return Math.max(3, Math.min(30, s)); // 钳位到 [3, 30]
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    // ==========================================
    // 便捷判断
    // ==========================================

    /**
     * Agent 模式是否可用 —— 即是否已配置有效的 DeepSeek API Key。
     */
    public static boolean isAgentAvailable() {
        String key = getDeepseekApiKey();
        // 检查是否已填写真实 Key（而非 <<< ... >>> 占位符）
        return !key.isEmpty() && !key.contains("<<<");
    }
}
