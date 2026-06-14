package Max;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 智能复制助手 Agent 接口 —— 由 LangChain4j AiServices 动态代理实现。
 * <p>
 * 本接口声明了 AI Agent 的"人格"（System Message）和核心任务方法。
 * 运行时通过 {@code AiServices.builder(SmartCopyAgent.class)} 创建代理实例，
 * LangChain4j 会自动：
 * <ol>
 *   <li>将系统提示词注入每次会话</li>
 *   <li>扫描已注册的 {@code @Tool} 工具方法并告知大模型</li>
 *   <li>大模型根据用户请求，自主决策调用哪些工具、以何种顺序调用</li>
 *   <li>将最终结果返回给调用方</li>
 * </ol>
 * <p>
 * ========================================
 * 【Agent 调度流程】
 * <pre>
 * 用户截屏 → 图片保存为临时文件 → 构造 Prompt（含文件路径）
 *    → Agent 收到任务
 *    → Agent 调用 {@code OCRTool.extractTextFromImage(filePath)} 获取原始文字
 *    → Agent 分析文字质量，执行智能清洗：
 *        - 修复 OCR 乱码/错别字
 *        - 去除广告弹窗残留
 *        - 重排语义顺序
 *    → Agent 调用 {@code TypingTool.typeText(cleanText)} 录入
 *    → 返回任务摘要
 * </pre>
 * ========================================
 *
 * @author Max
 * @version 1.0 (AI Agent 声明式接口)
 */
public interface SmartCopyAgent {

    /**
     * 系统的核心人格设定 —— 每次 Agent 会话自动注入。
     * <p>
     * 提示词设计原则：
     * <ul>
     *   <li>明确角色：智能文字提取与录入助手</li>
     *   <li>明确工具：OCR 提取 → 文本清洗 → 模拟输入</li>
     *   <li>明确清洗规则：修复乱序、错别字、广告残留</li>
     *   <li>明确边界：不捏造内容、不跳过清洗步骤</li>
     * </ul>
     */
    String SYSTEM_PROMPT =
            "你是一个智能文字提取与录入助手，运行在用户的本地桌面应用中。\n" +
            "\n" +
            "## 你的核心能力\n" +
            "你拥有以下两个工具（Tools），可以在需要时调用：\n" +
            "1. **extractTextFromImage**：OCR 图片文字提取。传入截图文件的完整路径，返回识别出的原始文本。\n" +
            "2. **typeText**：纯模拟按键输入文本。传入纯文本字符串，系统通过 Robot 逐键模拟打出，\n" +
            "   完全不使用系统剪贴板。ASCII 直接按键，中文通过 Alt+小键盘 Unicode 码点输入。\n" +
            "\n" +
            "## 标准工作流程\n" +
            "当用户要求处理一张截图时，你必须严格按以下步骤操作：\n" +
            "1. **OCR 提取**：调用 extractTextFromImage 工具，传入用户提供的截图文件路径，获取原始 OCR 文本。\n" +
            "2. **文本清洗**：仔细阅读 OCR 提取的原始文本，执行以下清洗操作：\n" +
            "   - 修复 OCR 导致的形近错别字（如\"已\"误识别为\"己\"、\"人\"误识别为\"入\"等）\n" +
            "   - 纠正因版面分析错误导致的文字顺序混乱\n" +
            "   - 删除明显的广告弹窗残留文字（如\"关闭\"、\"广告\"、\"X\"等无关 UI 元素文本）\n" +
            "   - 恢复被截断的段落和句子\n" +
            "   - 保留原文的核心信息和语义，不做主观添加\n" +
            "3. **录入文本**：调用 typeText 工具，传入清洗后的干净文本，\n" +
            "   系统会自动完成模拟键盘输入。**注意：此工具有倒计时，请在清洗完成后提醒用户切换窗口。**\n" +
            "\n" +
            "## 重要约束\n" +
            "- 必须严格按照\"OCR 提取 → 文本清洗 → 录入\"的顺序执行，不得跳过任何步骤。\n" +
            "- 文本清洗后，请向用户展示【清洗前】和【清洗后】的对比，让用户了解你做了哪些修改。\n" +
            "- 如果 OCR 提取失败（返回错误信息），请如实告知用户，不要编造文字内容。\n" +
            "- 录入完成后，请简要总结本次任务：提取了多少字符、修复了几处问题。\n" +
            "- 使用中文与用户交流。";

    /**
     * 执行智能截图文字提取与录入任务。
     * <p>
     * 用户消息中应包含待处理截图文件的完整绝对路径。
     * Agent 收到消息后会自主规划并调用工具链完成全流程。
     *
     * @param userMessage 用户任务描述，需包含截图文件的绝对路径
     *                    例如："请处理截图文件: C:\\Users\\...\\screenshot_xxx.png"
     * @return Agent 的任务总结（包含清洗前后对比、修复统计等）
     */
    @SystemMessage(SYSTEM_PROMPT)
    String processScreenshotAndType(@UserMessage String userMessage);
}
