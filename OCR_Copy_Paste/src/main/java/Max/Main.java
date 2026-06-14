package Max;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Super Copy Tools V6.0 —— AI Agent 智能调度版
 * <p>
 * 架构升级：
 * <ol>
 *   <li>保留 Swing UI + SnippingWindow + 双三次插值图像增强</li>
 *   <li>引入 {@link SmartCopyAgent} 声明式 Agent 接口</li>
 *   <li>OCR 和打字能力已封装为 {@code @Tool} 注解的工具方法：
 *       {@link OCRTool#extractTextFromImage(String)} /
 *       {@link TypingTool#typeText(String)}</li>
 *   <li>大模型自主调度完整链路：截图 → OCR 提取 → 文本清洗 → 智能录入</li>
 *   <li>所有 LLM 调用均为异步，不阻塞 Swing EDT</li>
 * </ol>
 * <p>
 * ========================================
 * 【Agent 调度流程】
 * <pre>
 * 用户点击"截图识别" → 选区完成
 *   → 截图保存为临时 PNG 文件
 *   → 启动后台线程，调用 SmartCopyAgent.processScreenshotAndType()
 *   → Agent (LLM) 推理，决策调用：
 *       1. OCRTool.extractTextFromImage(filePath)  → 获取原始文字
 *       2. LLM 自主分析并清洗文本（修复乱码/错别字/广告残留）
 *       3. TypingTool.typeText(cleanText) → 纯按键模拟录入
 *   → 返回任务摘要，更新 UI 日志区
 * </pre>
 * ========================================
 *
 * @author Max
 * @version 6.0 (AI Agent 智能调度版)
 */
public class Main extends JFrame {

    // ==========================================
    // 所有可配置项已移至 src/main/resources/config.properties
    // 通过 Config 类统一读取，无需在此硬编码任何路径或 Key。
    // 首次使用前请编辑 config.properties，填入你的 API Key 和 Tesseract 路径。
    // ==========================================

    // ==========================================
    // 工具类 & Agent 实例
    // ==========================================

    /** OCR 工具 —— 无状态，由 Agent 通过 @Tool 注解调用 */
    private final OCRTool ocrTool;

    /** 打字工具 —— 持有 Robot 引用，由 Agent 通过 @Tool 注解调用 */
    private final TypingTool typingTool;

    /** DeepSeek 大模型客户端（可为 null，表示 Agent 不可用） */
    private OpenAiChatModel chatModel;

    /** LangChain4j 动态代理的 Agent 实例（可为 null） */
    private SmartCopyAgent agent;

    /** Agent 模式是否可用 */
    private boolean agentAvailable = false;

    // ==========================================
    // UI 组件
    // ==========================================

    private JTextArea logArea;
    private Robot robot;

    // ==========================================
    // 构造器 & 初始化
    // ==========================================

    public Main() {
        // --- 0a. 初始化 Robot（必须在工具类之前） ---
        try {
            robot = new Robot();
            robot.setAutoDelay(10);
        } catch (AWTException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null,
                    "无法初始化 java.awt.Robot，程序无法运行。\n" + e.getMessage(),
                    "严重错误", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            throw new RuntimeException(e); // unreachable, 但编译器需要
        }

        // --- 0b. 初始化工具类 ---
        this.ocrTool = new OCRTool(Config.getTesseractPath(), Config.getTesseractLanguage());
        this.typingTool = new TypingTool(robot);

        // --- 0c. 环境检查 ---
        File tesseractExe = new File(Config.getTesseractPath());
        if (!tesseractExe.exists()) {
            JOptionPane.showMessageDialog(null,
                    "未找到 Tesseract OCR 引擎！\n请确认 config.properties 中的路径: " +
                            Config.getTesseractPath() +
                            "\n\nOCR 功能将不可用。",
                    "环境警告", JOptionPane.WARNING_MESSAGE);
        }

        // --- 0d. 初始化 LLM Agent ---
        initAgent();

        // --- 1. 窗口初始化 ---
        setTitle("Super Copy Tools V6.0 — DeepSeek AI Agent 智能调度");
        setSize(600, 520);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        initUI();
    }

    /**
     * 初始化 DeepSeek 大模型和 AI Agent。
     * <p>
     * 通过 langchain4j-open-ai 模块连接 DeepSeek API（OpenAI 兼容接口）。
     * 配置从 config.properties 中读取，若未配置 API Key 则降级为手动模式。
     */
    private void initAgent() {
        if (!Config.isAgentAvailable()) {
            System.out.println("[Agent] 未配置有效的 DeepSeek API Key，Agent 模式不可用。");
            System.out.println("[Agent] 请在 src/main/resources/config.properties 中填写 deepseek.api.key。");
            System.out.println("[Agent] 获取 Key: https://platform.deepseek.com/api_keys");
            return;
        }

        try {
            chatModel = OpenAiChatModel.builder()
                    .baseUrl(Config.getDeepseekBaseUrl())
                    .apiKey(Config.getDeepseekApiKey())
                    .modelName(Config.getDeepseekModel())
                    .temperature(Config.getAgentTemperature())
                    .maxTokens(Config.getAgentMaxTokens())
                    .logRequests(true)
                    .logResponses(true)
                    .build();

            agent = AiServices.builder(SmartCopyAgent.class)
                    .chatLanguageModel(chatModel)
                    .tools(ocrTool, typingTool)
                    .build();

            agentAvailable = true;
            System.out.println("[Agent] 初始化成功，模型: " + Config.getDeepseekModel());
        } catch (Exception e) {
            System.err.println("[Agent] 初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==========================================
    // UI 构建
    // ==========================================

    private void initUI() {
        setLayout(new BorderLayout());

        // --- 日志区 ---
        logArea = new JTextArea();
        logArea.setLineWrap(true);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 13));

        StringBuilder welcome = new StringBuilder();
        welcome.append("╔══════════════════════════════════════╗\n");
        welcome.append("║   Super Copy Tools V6.0 —— AI Agent  ║\n");
        welcome.append("╚══════════════════════════════════════╝\n\n");

        if (agentAvailable) {
            welcome.append("✅ AI Agent 模式已启用 (模型: ").append(Config.getDeepseekModel()).append(")\n");
            welcome.append("   流程: 截图 → OCR提取 → AI文本清洗 → 智能录入\n\n");
        } else {
            welcome.append("⚠️  AI Agent 模式未启用\n");
            welcome.append("   请在 config.properties 中填写 deepseek.api.key\n");
            welcome.append("   获取 Key: https://platform.deepseek.com/api_keys\n");
            welcome.append("   当前降级为手动模式：OCR 和打字需分别手动操作\n\n");
        }

        welcome.append("【操作说明】\n");
        welcome.append("  ■ 截图识别：点击后拖拽选择屏幕区域\n");
        welcome.append("    - Agent 模式：AI 自动 OCR → 清洗 → 录入\n");
        welcome.append("    - 手动模式：直接 OCR，结果写入下方候选框\n");
        welcome.append("  ■ 手动输入：将候选框中的文字逐键模拟打出\n");
        welcome.append("    - 按 Ctrl+A 全选，或鼠标拖拽选择部分文字\n");
        welcome.append("    - ASCII → 直接按键 | 中文 → Alt+小键盘 Unicode\n");
        welcome.append("    - ★ 全程不使用系统剪贴板，无 Ctrl+V\n\n");

        logArea.setText(welcome.toString());
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        // --- 底部按钮区 ---
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 0));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 状态标签
        JLabel statusLabel = new JLabel();
        if (agentAvailable) {
            statusLabel.setText("🤖 Agent 就绪");
            statusLabel.setForeground(new Color(0, 128, 0));
        } else {
            statusLabel.setText("🔧 手动模式");
            statusLabel.setForeground(Color.GRAY);
        }
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        bottomPanel.add(statusLabel, BorderLayout.WEST);

        // 按钮面板
        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 10, 0));

        JButton btnOCR = new JButton(agentAvailable
                ? "截图识别 (🤖 AI Agent)"
                : "截图识别 (📷 手动 OCR)");
        styleButton(btnOCR);
        btnOCR.addActionListener(e -> startSnippingProcess());

        JButton btnType = new JButton("手动输入 (⌨️ Smart Type)");
        styleButton(btnType);
        btnType.addActionListener(e -> startManualTypingProcess());

        buttonPanel.add(btnOCR);
        buttonPanel.add(btnType);
        bottomPanel.add(buttonPanel, BorderLayout.CENTER);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void styleButton(JButton button) {
        button.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        button.setFocusPainted(false);
        button.setPreferredSize(new Dimension(0, 50));
    }

    /**
     * 线程安全的日志追加 —— 始终在 EDT 上更新 UI。
     */
    private void appendLog(String text) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(text + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void deleteTempFile(File file) {
        if (file != null && file.exists()) {
            try {
                file.delete();
            } catch (Exception ignored) {
            }
        }
    }

    // ============================================================
    //  截图 → Agent 智能处理（核心新流程）
    //  - Agent 可用：保存截图 → LLM 调度 OCR → 清洗 → 录入
    //  - Agent 不可用：降级为直接 OCR（原有行为）
    // ============================================================

    /**
     * 处理截图：根据 Agent 是否可用，选择 Agent 智能流程或直接 OCR。
     * <p>
     * <b>Agent 流程（异步）</b>：
     * <ol>
     *   <li>将 BufferedImage 保存为临时 PNG</li>
     *   <li>在后台线程中调用 {@link SmartCopyAgent#processScreenshotAndType(String)}</li>
     *   <li>Agent 自主调用工具链完成 OCR → 清洗 → 录入</li>
     *   <li>任务结束后清理临时文件</li>
     * </ol>
     * <p>
     * <b>手动流程（同步）</b>：直接调用本地 OCR，结果写入日志区。
     */
    private void handleScreenshot(BufferedImage screenshot) {
        if (agentAvailable) {
            processScreenshotWithAgent(screenshot);
        } else {
            processScreenshotManual(screenshot);
        }
    }

    /**
     * Agent 智能流程 —— 异步执行，不阻塞 EDT。
     */
    private void processScreenshotWithAgent(BufferedImage screenshot) {
        // 1. 保存截图到临时文件
        File tempFile;
        try {
            tempFile = File.createTempFile("screenshot_agent_", ".png");
            ImageIO.write(screenshot, "png", tempFile);
        } catch (IOException e) {
            appendLog("【错误】无法保存截图临时文件: " + e.getMessage());
            return;
        }

        appendLog("══════════════════════════════════");
        appendLog("【Agent】📸 截图已保存，启动 AI 智能处理...");
        appendLog("【Agent】调度链路: OCR提取 → 文本清洗 → 智能录入");
        appendLog("【Agent】⏳ 正在连接大模型，请稍候...");

        final File finalTempFile = tempFile;
        final String filePath = tempFile.getAbsolutePath();

        // 2. 异步调用 Agent —— 绝不阻塞 EDT
        CompletableFuture.supplyAsync(() -> {
            try {
                String prompt = "请处理截图文件: " + filePath + "\n" +
                        "请严格按照标准工作流程操作：\n" +
                        "1. 调用 extractTextFromImage 提取图片中的文字\n" +
                        "2. 对提取的文字进行智能清洗（修复错别字/乱序/广告残留）\n" +
                        "3. 展示清洗前后对比\n" +
                        "4. 告知用户即将开始录入，请用户切换到目标窗口\n" +
                        "5. 调用 typeText 逐键录入清洗后的文本";

                return agent.processScreenshotAndType(prompt);
            } catch (Exception e) {
                return "[Agent 异常] " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }).thenAccept(agentReport -> {
            // 3. Agent 完成 —— 更新 UI
            appendLog("");
            appendLog("══════════════════════════════════");
            appendLog("【Agent 任务报告】");
            appendLog("──────────────────────────────");
            // 使用 SwingUtilities.invokeLater 确保文本安全追加
            SwingUtilities.invokeLater(() -> {
                logArea.append(agentReport + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
            appendLog("══════════════════════════════════");
            appendLog("【系统】临时截图已清理。");

            // 4. 清理临时文件
            deleteTempFile(finalTempFile);
        }).exceptionally(ex -> {
            // 5. 异常处理
            appendLog("【Agent 严重错误】任务执行失败: " + ex.getMessage());
            ex.printStackTrace();
            deleteTempFile(finalTempFile);
            return null;
        });
    }

    /**
     * 手动 OCR 流程 —— Agent 不可用时的降级方案。
     * 直接在后台线程中调用本地 Tesseract，结果写入日志区。
     */
    private void processScreenshotManual(BufferedImage screenshot) {
        new Thread(() -> {
            try {
                int w = screenshot.getWidth();
                int h = screenshot.getHeight();
                appendLog("----------------------------------");
                appendLog(String.format("【OCR 手动模式】原始尺寸: %dx%d", w, h));
                appendLog("【OCR 手动模式】正在增强至 " + (w * 2) + "x" + (h * 2)
                        + " (灰度+双三次插值)...");

                String resultText = ocrTool.performOCR(screenshot);

                if (resultText != null && !resultText.isEmpty()) {
                    appendLog("【OCR 成功】识别结果（未清洗）：");
                    SwingUtilities.invokeLater(() -> {
                        logArea.append("\n" + resultText + "\n");
                        logArea.setCaretPosition(logArea.getDocument().getLength());
                    });
                } else {
                    appendLog("【OCR 失败】无结果或引擎报错");
                }
            } catch (Exception e) {
                appendLog("【异常】OCR 处理失败: " + e.getMessage());
                e.printStackTrace();
            }
            appendLog("【系统】处理完成。");
        }, "ocr-manual-thread").start();
    }

    // ==========================================
    //  手动打字（保留原有交互）
    //  用户可手动选择/编辑日志区中的文字后点击打字。
    // ==========================================

    /**
     * 手动触发模拟打字 —— 读取日志区中用户选中（或全部）的文字。
     * <p>
     * 保留此功能以便用户在 Agent 清洗后复查文字，或手动输入自定义内容。
     * 内置 8 秒倒计时供用户切换到目标窗口。
     */
    private void startManualTypingProcess() {
        String content = logArea.getSelectedText();
        if (content == null || content.trim().isEmpty()) {
            content = logArea.getText();
        }

        if (content == null || content.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "无输入内容！\n请先在候选框中输入或粘贴要打印的文字。",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        final String text = content.trim();
        final int delaySeconds = Config.getTypingDelaySeconds();
        appendLog("----------------------------------");
        appendLog("【手动输入】" + delaySeconds + " 秒后开始模拟输入...");
        appendLog("【手动输入】请立即点击目标输入框！");

        new Thread(() -> {
            try {
                // 倒计时
                for (int i = delaySeconds; i >= 1; i--) {
                    appendLog("  ⏳ " + i + " 秒...");
                    Thread.sleep(1000);
                }
                typingTool.typeViaKeystrokes(text);
                appendLog("【完成】手动输入结束 (" + text.length() + " 字符)。");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                appendLog("【中断】手动输入被取消。");
            } catch (Exception e) {
                appendLog("【异常】手动输入失败: " + e.getMessage());
                e.printStackTrace();
            }
        }, "manual-type-thread").start();
    }

    // ==========================================
    //  截图模块 (SnippingWindow) —— 保持不变
    // ==========================================

    /**
     * 触发全屏截图流程：隐藏主窗口，截取全屏，打开截选窗口。
     * 用户完成选区后自动调用 {@link #handleScreenshot(BufferedImage)}。
     */
    private void startSnippingProcess() {
        this.setVisible(false);
        new Thread(() -> {
            try {
                Thread.sleep(200); // 等待窗口隐藏
                Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
                BufferedImage capture = robot.createScreenCapture(screenRect);
                SwingUtilities.invokeLater(() -> new SnippingWindow(capture, this));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> this.setVisible(true));
            }
        }, "snipping-thread").start();
    }

    /**
     * 截图选区窗口 —— 全屏遮罩 + 鼠标拖拽选择区域。
     * 核心逻辑不变，仅将回调改为 {@link #handleScreenshot(BufferedImage)}。
     */
    class SnippingWindow extends JWindow {
        private BufferedImage backgroundImage;
        private Main parentFrame;
        private Point startPoint, endPoint;
        private BackgroundPanel drawingPanel;

        public SnippingWindow(BufferedImage image, Main parent) {
            this.backgroundImage = image;
            this.parentFrame = parent;
            this.setSize(Toolkit.getDefaultToolkit().getScreenSize());
            this.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

            drawingPanel = new BackgroundPanel();
            this.add(drawingPanel);

            SnippingMouseAdapter adapter = new SnippingMouseAdapter();
            this.addMouseListener(adapter);
            this.addMouseMotionListener(adapter);

            this.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) finish(null);
                }
            });
            this.setFocusable(true);
            this.setVisible(true);
            this.requestFocus();
        }

        private void finish(Rectangle rect) {
            this.dispose();
            parentFrame.setVisible(true);

            if (rect != null && rect.width > 0 && rect.height > 0) {
                BufferedImage subImage = backgroundImage.getSubimage(
                        rect.x, rect.y, rect.width, rect.height);
                // ★ 核心变更：调用 Agent 调度入口而非直接 OCR
                parentFrame.handleScreenshot(subImage);
            }
        }

        private class BackgroundPanel extends JPanel {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g;

                if (backgroundImage != null) {
                    g2d.drawImage(backgroundImage, 0, 0, this);
                }

                // 半透明遮罩
                g2d.setColor(new Color(0, 0, 0, 100));
                g2d.fillRect(0, 0, getWidth(), getHeight());

                // 绘制选区
                if (startPoint != null && endPoint != null) {
                    int x = Math.min(startPoint.x, endPoint.x);
                    int y = Math.min(startPoint.y, endPoint.y);
                    int w = Math.abs(startPoint.x - endPoint.x);
                    int h = Math.abs(startPoint.y - endPoint.y);

                    if (w > 0 && h > 0) {
                        g2d.drawImage(backgroundImage.getSubimage(x, y, w, h), x, y, this);
                        g2d.setColor(Color.RED);
                        g2d.setStroke(new BasicStroke(2));
                        g2d.drawRect(x, y, w, h);
                    }
                }
            }
        }

        private class SnippingMouseAdapter extends MouseAdapter {
            @Override
            public void mousePressed(MouseEvent e) {
                startPoint = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                endPoint = e.getPoint();
                drawingPanel.repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                endPoint = e.getPoint();
                if (startPoint != null) {
                    int x = Math.min(startPoint.x, endPoint.x);
                    int y = Math.min(startPoint.y, endPoint.y);
                    int w = Math.abs(startPoint.x - endPoint.x);
                    int h = Math.abs(startPoint.y - endPoint.y);
                    if (w > 0 && h > 0) {
                        finish(new Rectangle(x, y, w, h));
                    } else {
                        startPoint = null;
                        endPoint = null;
                        drawingPanel.repaint();
                    }
                }
            }
        }
    }

    // ==========================================
    //  入口
    // ==========================================

    public static void main(String[] args) {
        // 设置系统属性以优化 macOS 体验
        System.setProperty("apple.awt.application.name", "Super Copy Tools");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        SwingUtilities.invokeLater(() -> new Main().setVisible(true));
    }
}
