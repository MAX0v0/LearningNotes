package Max;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import dev.langchain4j.agent.tool.Tool;

/**
 * OCR 工具类 —— 图片文字识别 (无状态)
 * <p>
 * 本工具类封装了基于 Tesseract 的 OCR 识别流程，包含双三次插值（Bicubic）
 * 图像增强算法。该类设计为无状态（stateless），所有配置通过构造器注入，
 * 方法仅依赖参数，无副作用，便于后续对接 LangChain4j 框架。
 * <p>
 * ========================================
 * 【Agent 集成指南】
 * 若需对接 LangChain4j，为本类的核心方法添加 {@code @Tool} 注解即可：
 * <pre>{@code
 * // LangChain4j 示例
 * @Tool("识别图片中的文字并返回纯文本")
 * public String recognizeText(String imagePath) {
 *     BufferedImage img = ImageIO.read(new File(imagePath));
 *     return ocrTool.performOCR(img);
 * }
 * }</pre>
 * ========================================
 *
 * @author Max
 * @version 2.0 (AI Agent 重构版)
 */
public class OCRTool {

    /** Tesseract OCR 引擎可执行文件路径 */
    private final String tesseractPath;

    /** OCR 语言包参数，如 "chi_sim+eng" 表示中英文混合识别 */
    private final String language;

    /**
     * 构造一个 OCR 工具实例。
     *
     * @param tesseractPath Tesseract 可执行文件路径，例如 "D:\\Program Files\\Tesseract-OCR\\tesseract.exe"
     * @param language      OCR 语言包，例如 "chi_sim+eng"
     */
    public OCRTool(String tesseractPath, String language) {
        this.tesseractPath = tesseractPath;
        this.language = language;
    }

    // ==========================================
    //  【Agent Tool 候选方法 A】
    //  方法签名: String performOCR(BufferedImage)
    //  描述: 接收图片，返回 Tesseract 识别的原始字符串。
    //  可加 @Tool("对图片执行OCR文字识别") 暴露给 Agent。
    // ==========================================

    /**
     * 对给定的图片执行 OCR 识别。
     * <p>
     * 内部流程：
     * <ol>
     *   <li>图像增强：2.0x 双三次插值放大 + 灰度化</li>
     *   <li>写入临时 PNG 文件</li>
     *   <li>调用 Tesseract 命令行引擎</li>
     *   <li>读取识别结果文本</li>
     * </ol>
     *
     * @param originalImage 待识别的原始 BufferedImage（通常来自截图）
     * @return 识别出的纯文本字符串；失败时返回空字符串 ""
     * @throws IOException 如果 Tesseract 执行过程中发生 I/O 异常
     */
    public String performOCR(BufferedImage originalImage) throws IOException, InterruptedException {
        File imageFile = null;
        File outputFileBase = null;
        File finalResultFile = null;

        try {
            int w = originalImage.getWidth();
            int h = originalImage.getHeight();

            // --- 图像增强：2.0x 双三次插值 + 灰度化 ---
            int newW = w * 2;
            int newH = h * 2;
            BufferedImage enhancedImage = new BufferedImage(newW, newH, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g2d = enhancedImage.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.drawImage(originalImage, 0, 0, newW, newH, null);
            g2d.dispose();

            // --- 写入临时 PNG ---
            imageFile = File.createTempFile("ocr_enhanced_", ".png");
            ImageIO.write(enhancedImage, "png", imageFile);

            // --- 构造输出文件名 ---
            outputFileBase = File.createTempFile("ocr_result_", "");
            String outputBasePath = outputFileBase.getAbsolutePath();
            finalResultFile = new File(outputBasePath + ".txt");

            // --- 调用 Tesseract ---
            ProcessBuilder pb = new ProcessBuilder(
                    tesseractPath,
                    imageFile.getAbsolutePath(),
                    outputBasePath,
                    "-l", language
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 清空流防止阻塞
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) { /* drain */ }
            }

            int exitCode = process.waitFor();

            if (exitCode == 0 && finalResultFile.exists()) {
                return new String(Files.readAllBytes(finalResultFile.toPath()), StandardCharsets.UTF_8).trim();
            } else {
                return "";
            }

        } finally {
            // 清理临时文件
            deleteQuietly(imageFile);
            deleteQuietly(outputFileBase);
            deleteQuietly(finalResultFile);
        }
    }

    // ==========================================
    //  【Agent Tool - OCR 文字提取】
    //  此方法由大模型 Agent 通过 @Tool 注解感知并调用。
    //  接收图片文件路径字符串（LLM 可传递），内部读取文件
    //  后委托给 performOCR(BufferedImage) 执行 Tesseract 识别。
    // ==========================================

    /**
     * 从指定路径的图片文件中提取文字（OCR）。
     * <p>
     * 此方法标记了 {@link Tool @Tool} 注解，LangChain4j 框架会自动
     * 将其注册为 AI Agent 的可用工具。大模型在需要 OCR 能力时会
     * 自动调用此方法，传入图片文件的绝对路径。
     *
     * @param imageFilePath 待识别图片文件的绝对路径（如 /tmp/screenshot_xxx.png）
     * @return 识别出的纯文本字符串；失败时返回错误描述
     */
    @Tool("从图片文件中提取文字内容。参数 imageFilePath 是截图文件的完整绝对路径。返回识别出的纯文本字符串。")
    public String extractTextFromImage(String imageFilePath) {
        File imageFile = new File(imageFilePath);
        if (!imageFile.exists()) {
            return "[OCR 错误] 图片文件不存在: " + imageFilePath;
        }
        if (!imageFile.canRead()) {
            return "[OCR 错误] 图片文件不可读: " + imageFilePath;
        }
        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) {
                return "[OCR 错误] 无法解码图片文件（可能格式损坏）: " + imageFilePath;
            }
            String result = performOCR(image);
            if (result == null || result.isEmpty()) {
                return "[OCR 结果] 未识别到任何文字（图片可能为纯图或文字区域过小）";
            }
            return result;
        } catch (IOException e) {
            return "[OCR 异常] 读取图片失败: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[OCR 异常] OCR 处理被中断";
        } catch (Exception e) {
            return "[OCR 异常] 未知错误: " + e.getMessage();
        }
    }

    /** 安静地删除临时文件，不抛异常 */
    private void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            try {
                file.delete();
            } catch (Exception ignored) {
            }
        }
    }
}
