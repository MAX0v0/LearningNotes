package Max;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.Objects;
import java.util.Random;
import dev.langchain4j.agent.tool.Tool;

/**
 * 打字工具类 —— 纯模拟键盘按键输入，绝不使用系统剪贴板。
 * <p>
 * 输入策略（全部基于 Robot 逐键模拟）：
 * <ol>
 *   <li><b>ASCII 字符</b>：直接映射 KeyEvent 键码，逐键按下/释放</li>
 *   <li><b>非 ASCII 字符</b>（中文等）：通过 Alt + 小键盘十进制 Unicode 码点输入
 *       （Windows 原生 Alt+Numpad 机制，无需剪贴板）</li>
 * </ol>
 * <p>
 * 核心原则：本程序的设计初衷是应对禁止复制粘贴的受限环境，
 * 因此全程不使用 java.awt.datatransfer.Clipboard，不使用 Ctrl+V。
 * <p>
 * ========================================
 * 【Agent 集成指南】
 * 本类已内置 {@code @Tool} 注解。在构建 AI Service 时，
 * 将本类实例作为 tool 注册即可：
 * <pre>{@code
 * AiServices.builder(SmartCopyAgent.class)
 *     .chatLanguageModel(model)
 *     .tools(typingTool)  // ← 直接注入
 *     .build();
 * }</pre>
 * ========================================
 *
 * @author Max
 * @version 4.0 (纯按键模拟版，零剪贴板)
 */
public class TypingTool {

    private final Robot robot;
    private final Random random = new Random();

    /**
     * 构造打字工具实例。
     *
     * @param robot AWT Robot 实例，用于模拟键盘事件（不可为 null）
     * @throws NullPointerException 如果 robot 为 null
     */
    public TypingTool(Robot robot) {
        this.robot = Objects.requireNonNull(robot, "Robot must not be null");
    }

    // ============================================================
    //  【Agent Tool - 纯按键文本输入】
    //  大模型 Agent 通过 @Tool 注解感知并调用此方法。
    //  所有字符均通过 Robot 逐键打出，绝不使用剪贴板。
    // ============================================================

    /**
     * 将文本通过纯模拟按键逐字输入到当前光标位置。
     * <p>
     * 全程基于 Robot 模拟击键，不使用系统剪贴板：
     * <ul>
     *   <li>ASCII 字符 → 直接按键打出</li>
     *   <li>中文/Unicode → Windows Alt + 小键盘 Unicode 码点输入</li>
     * </ul>
     * 内置倒计时，给用户留出切换到目标窗口的时间。
     *
     * @param textContent 待输入的文本内容（支持中英文、Unicode）
     * @return 操作结果描述字符串
     */
    @Tool("将纯文本通过模拟按键逐字输入到当前光标所在位置。全部使用 Robot 击键模拟，不使用剪贴板。参数 textContent 是待输入的完整文本。")
    public String typeText(String textContent) {
        if (textContent == null || textContent.trim().isEmpty()) {
            return "[输入跳过] 文本内容为空，未执行任何操作。";
        }

        final String text = textContent.trim();
        final int delaySeconds = Config.getTypingDelaySeconds();

        // --- 倒计时，给用户留出切换到目标窗口的时间 ---
        try {
            for (int i = delaySeconds; i >= 1; i--) {
                Thread.sleep(1000);
                System.out.println("[输入工具] " + i + " 秒后开始逐键录入...");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[输入中断] 等待期间线程被中断。";
        }

        // --- 纯模拟按键录入 ---
        typeViaKeystrokes(text);

        String preview = text.length() > 50 ? text.substring(0, 47) + "..." : text;
        return "[输入成功] 已通过逐键模拟录入文本 (" + text.length() + " 字符): " + preview;
    }

    // ==========================================
    //      公开方法：逐键模拟输入（核心）
    // ==========================================

    /**
     * 纯模拟按键逐字输入文本（全 Unicode 支持，不使用剪贴板）。
     * <ul>
     *   <li>ASCII（&lt; 128）→ 映射 KeyEvent 直接打出</li>
     *   <li>非 ASCII（中文等）→ Windows Alt + 小键盘十进制码点</li>
     * </ul>
     *
     * @param text 待输入的文本
     */
    public void typeViaKeystrokes(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        int asciiCount = 0;
        int unicodeCount = 0;

        for (char c : text.toCharArray()) {
            if (c == '\n') {
                // 换行：直接按 Enter
                robot.keyPress(KeyEvent.VK_ENTER);
                robot.keyRelease(KeyEvent.VK_ENTER);
                robot.delay(30 + random.nextInt(40));
            } else if (c == '\r') {
                // 回车符跳过
            } else if (c < 128) {
                // ASCII 可直接映射键码
                typeAsciiChar(c);
                asciiCount++;
                int delay = (c == ' ') ? 50 : 20;
                robot.delay(delay + random.nextInt(60));
            } else {
                // 非 ASCII（中文等）：Alt + 小键盘十进制码点
                typeUnicodeChar(c);
                unicodeCount++;
                robot.delay(80 + random.nextInt(100)); // Unicode 输入较慢，多等一会
            }
        }

        System.out.printf("[输入工具] 录入完成: ASCII %d 个, Unicode %d 个%n",
                asciiCount, unicodeCount);
    }

    // ---------- ASCII 按键映射（与原版一致） ----------

    /**
     * 模拟按下单个 ASCII 字符。
     */
    private void typeAsciiChar(char c) {
        boolean useShift = false;
        int keyCode = -1;

        if (Character.isLetter(c)) {
            useShift = Character.isUpperCase(c);
            keyCode = Character.toUpperCase(c);
        } else if (Character.isDigit(c)) {
            keyCode = c;
        } else {
            switch (c) {
                case ' ':  keyCode = KeyEvent.VK_SPACE;          break;
                case '-':  keyCode = KeyEvent.VK_MINUS;           break;
                case '=':  keyCode = KeyEvent.VK_EQUALS;          break;
                case '[':  keyCode = KeyEvent.VK_OPEN_BRACKET;    break;
                case ']':  keyCode = KeyEvent.VK_CLOSE_BRACKET;   break;
                case ';':  keyCode = KeyEvent.VK_SEMICOLON;       break;
                case '\'': keyCode = KeyEvent.VK_QUOTE;           break;
                case ',':  keyCode = KeyEvent.VK_COMMA;           break;
                case '.':  keyCode = KeyEvent.VK_PERIOD;          break;
                case '/':  keyCode = KeyEvent.VK_SLASH;           break;
                case '`':  keyCode = KeyEvent.VK_BACK_QUOTE;      break;
                case '\\': keyCode = KeyEvent.VK_BACK_SLASH;      break;
                case '\t': keyCode = KeyEvent.VK_TAB;             break;
                // Shift 组合符号
                case '!':  useShift = true; keyCode = KeyEvent.VK_1; break;
                case '@':  useShift = true; keyCode = KeyEvent.VK_2; break;
                case '#':  useShift = true; keyCode = KeyEvent.VK_3; break;
                case '$':  useShift = true; keyCode = KeyEvent.VK_4; break;
                case '%':  useShift = true; keyCode = KeyEvent.VK_5; break;
                case '^':  useShift = true; keyCode = KeyEvent.VK_6; break;
                case '&':  useShift = true; keyCode = KeyEvent.VK_7; break;
                case '*':  useShift = true; keyCode = KeyEvent.VK_8; break;
                case '(':  useShift = true; keyCode = KeyEvent.VK_9; break;
                case ')':  useShift = true; keyCode = KeyEvent.VK_0; break;
                case '_':  useShift = true; keyCode = KeyEvent.VK_MINUS; break;
                case '+':  useShift = true; keyCode = KeyEvent.VK_EQUALS; break;
                case '{':  useShift = true; keyCode = KeyEvent.VK_OPEN_BRACKET; break;
                case '}':  useShift = true; keyCode = KeyEvent.VK_CLOSE_BRACKET; break;
                case ':':  useShift = true; keyCode = KeyEvent.VK_SEMICOLON; break;
                case '"':  useShift = true; keyCode = KeyEvent.VK_QUOTE; break;
                case '<':  useShift = true; keyCode = KeyEvent.VK_COMMA; break;
                case '>':  useShift = true; keyCode = KeyEvent.VK_PERIOD; break;
                case '?':  useShift = true; keyCode = KeyEvent.VK_SLASH; break;
                case '~':  useShift = true; keyCode = KeyEvent.VK_BACK_QUOTE; break;
                case '|':  useShift = true; keyCode = KeyEvent.VK_BACK_SLASH; break;
                default:   return; // 未知 ASCII 字符安全跳过
            }
        }

        if (keyCode != -1) {
            if (useShift) robot.keyPress(KeyEvent.VK_SHIFT);
            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);
            if (useShift) robot.keyRelease(KeyEvent.VK_SHIFT);
        }
    }

    // ---------- 非 ASCII 字符：Alt + 小键盘十进制 Unicode 码点 ----------

    /**
     * 通过 Windows Alt + 小键盘数字输入一个非 ASCII 字符（如中文）。
     * <p>
     * 原理：Windows 原生 Alt + 小键盘数字序列会输出对应十进制码点的字符。
     * 例如：'中' 的 Unicode 码点为 U+4E2D = 十进制 20013，
     * 则模拟按下 Alt 不放 → 小键盘依次按 2, 0, 0, 1, 3 → 松开 Alt → 输出 '中'。
     * <p>
     * 限制：
     * <ul>
     *   <li>依赖小键盘（NumPad），NumLock 必须开启</li>
     *   <li>仅 Windows 平台有效（macOS/Linux 会降级跳过）</li>
     *   <li>码点超过 65535（Supplementary Characters）暂不支持</li>
     * </ul>
     */
    private void typeUnicodeChar(char c) {
        int codePoint = (int) c;

        // 超出 BMP 的字符（如部分 Emoji）跳过
        if (codePoint > 65535) {
            System.out.println("[输入工具] 跳过超出 BMP 的字符: U+" + Integer.toHexString(codePoint));
            return;
        }

        String digits = String.valueOf(codePoint);

        // 按下 Alt
        robot.keyPress(KeyEvent.VK_ALT);
        robot.delay(20);

        // 逐个按下小键盘数字键
        for (char digit : digits.toCharArray()) {
            int numpadKey = switch (digit) {
                case '0' -> KeyEvent.VK_NUMPAD0;
                case '1' -> KeyEvent.VK_NUMPAD1;
                case '2' -> KeyEvent.VK_NUMPAD2;
                case '3' -> KeyEvent.VK_NUMPAD3;
                case '4' -> KeyEvent.VK_NUMPAD4;
                case '5' -> KeyEvent.VK_NUMPAD5;
                case '6' -> KeyEvent.VK_NUMPAD6;
                case '7' -> KeyEvent.VK_NUMPAD7;
                case '8' -> KeyEvent.VK_NUMPAD8;
                case '9' -> KeyEvent.VK_NUMPAD9;
                default -> -1;
            };
            if (numpadKey != -1) {
                robot.keyPress(numpadKey);
                robot.delay(15);
                robot.keyRelease(numpadKey);
                robot.delay(15);
            }
        }

        // 松开 Alt —— 此时字符被 Windows 输出
        robot.delay(30);
        robot.keyRelease(KeyEvent.VK_ALT);
        robot.delay(50);
    }
}
