package com.moral.util;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;

/**
 * 界面主题：集中定义配色、字体、圆角与按钮语义样式。
 * <p>
 * 必须在 LookAndFeel 安装之后、任何组件创建之前调用 {@link #install()}；
 * 之后各面板只从本类取色，避免颜色硬编码散落各处。
 * 整个类只影响视觉，不涉及任何布局与业务逻辑。
 */
public final class UiTheme {

    // ---- 主色 ----
    /** 主色：主按钮底、焦点环、进度条 */
    public static final Color ACCENT = new Color(0x2563EB);
    /** 主色悬停 */
    public static final Color ACCENT_HOVER = new Color(0x1D4ED8);
    /** 主色按下 */
    public static final Color ACCENT_PRESSED = new Color(0x1E40AF);
    /** 主色浅底：表格选中行 */
    public static final Color ACCENT_SOFT = new Color(0xE8F0FE);
    /** 焦点环 */
    public static final Color ACCENT_RING = new Color(0x93C5FD);

    // ---- 背景与描边 ----
    /** 窗口底色 */
    public static final Color APP_BG = new Color(0xF4F7FB);
    /** 卡片 / 表格 / 输入控件底色 */
    public static final Color CARD_BG = new Color(0xFFFFFF);
    /** 次级底：表头、斑马纹、滚动条槽 */
    public static final Color SURFACE_ALT = new Color(0xF7F9FC);
    /** 常规描边：卡片线、网格线、进度条槽 */
    public static final Color BORDER = new Color(0xE3E8EF);
    /** 强调描边：表头分隔线、输入框边框 */
    public static final Color BORDER_STRONG = new Color(0xCDD7E3);

    // ---- 文字 ----
    public static final Color TEXT_PRIMARY = new Color(0x0F172A);
    public static final Color TEXT_SECONDARY = new Color(0x64748B);
    public static final Color TEXT_DISABLED = new Color(0x94A3B8);

    // ---- 状态色（表清单状态列、进度条、日志级别共用） ----
    public static final Color STATUS_PENDING = new Color(0x94A3B8);
    public static final Color STATUS_RUNNING = new Color(0x3B82F6);
    public static final Color STATUS_SUCCESS = new Color(0x16A34A);
    public static final Color STATUS_WARN = new Color(0xD97706);
    public static final Color STATUS_ERROR = new Color(0xDC2626);

    private static final String STYLE_PRIMARY = "pdmsPrimary";
    private static final String STYLE_DANGER = "pdmsDanger";

    private static volatile boolean installed;
    private static volatile Font bodyFont;
    private static volatile Font boldFont;
    private static volatile Font monoFont;

    private UiTheme() {
    }

    /**
     * 安装主题：向 UIManager 写入配色 / 字体 / 圆角 / 行高，并注册按钮样式类。
     * 必须在 {@code FlatLightLaf} 初始化之后、组件创建之前调用。
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        setupFonts();
        setupSurface();
        setupInputs();
        setupTable();
        setupProgressBar();
        setupScrollBar();
        setupStyles();
    }

    /** 统一字号与字重：沿用系统字体族（跨平台安全），只改字重与相对大小 */
    private static void setupFonts() {
        Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = new Font(Font.DIALOG, Font.PLAIN, 13);
        }
        bodyFont = base.deriveFont(Font.PLAIN);
        boldFont = base.deriveFont(Font.BOLD);
        monoFont = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(11, Math.round(base.getSize2D())));

        UIManager.put("defaultFont", bodyFont);
        String[] fontKeys = {
                "Label.font", "Button.font", "ToggleButton.font", "CheckBox.font", "RadioButton.font",
                "ComboBox.font", "Spinner.font", "TextField.font", "FormattedTextField.font",
                "PasswordField.font", "TextArea.font", "TextPane.font", "EditorPane.font",
                "Table.font", "List.font", "Tree.font", "ProgressBar.font", "ToolTip.font",
                "OptionPane.font", "OptionPane.messageFont", "OptionPane.buttonFont"
        };
        for (String key : fontKeys) {
            UIManager.put(key, bodyFont);
        }
        UIManager.put("TableHeader.font", boldFont);
        UIManager.put("TitledBorder.font", boldFont);
    }

    /** 窗口底色、卡片标题与面板配色 */
    private static void setupSurface() {
        // 面板统一白底，卡片靠 TitledBorder 区分；窗口底由 MainFrame 单独设置
        UIManager.put("Panel.background", CARD_BG);
        UIManager.put("ScrollPane.background", CARD_BG);
        UIManager.put("Viewport.background", CARD_BG);
        UIManager.put("SplitPane.background", APP_BG);
        UIManager.put("SplitPaneDivider.draggingColor", ACCENT);

        UIManager.put("TitledBorder.titleColor", TEXT_PRIMARY);
        UIManager.put("TitledBorder.border", BorderFactory.createLineBorder(BORDER, 1));

        UIManager.put("Component.focusColor", ACCENT);
        UIManager.put("Component.focusedBorderColor", ACCENT);
        UIManager.put("Component.borderColor", BORDER_STRONG);
        UIManager.put("Component.disabledBorderColor", BORDER);

        UIManager.put("ToolTip.background", new Color(0x1F2937));
        UIManager.put("ToolTip.foreground", new Color(0xF8FAFC));
    }

    /** 输入控件：白底、圆角、蓝色聚焦环 */
    private static void setupInputs() {
        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 8);

        String[] inputKeys = {
                "TextField.background", "FormattedTextField.background", "PasswordField.background",
                "TextArea.background", "TextPane.background", "EditorPane.background",
                "ComboBox.background", "Spinner.background"
        };
        for (String key : inputKeys) {
            UIManager.put(key, CARD_BG);
        }
        UIManager.put("CheckBox.icon.checkmarkColor", ACCENT);
        UIManager.put("CheckBox.icon[filled].selectedBackground", ACCENT);
        UIManager.put("ComboBox.buttonArrowColor", TEXT_SECONDARY);
        UIManager.put("Component.arrowType", "chevron");
    }

    /** 表清单：白底、斑马纹、主色选中、加粗表头 */
    private static void setupTable() {
        UIManager.put("Table.background", CARD_BG);
        UIManager.put("Table.alternateRowColor", SURFACE_ALT);
        UIManager.put("Table.selectionBackground", ACCENT_SOFT);
        UIManager.put("Table.selectionForeground", TEXT_PRIMARY);
        UIManager.put("Table.selectionInactiveBackground", ACCENT_SOFT);
        UIManager.put("Table.selectionInactiveForeground", TEXT_PRIMARY);
        UIManager.put("Table.gridColor", BORDER);
        UIManager.put("Table.rowHeight", 28);

        UIManager.put("TableHeader.background", SURFACE_ALT);
        UIManager.put("TableHeader.foreground", TEXT_SECONDARY);
        UIManager.put("TableHeader.separatorColor", BORDER);
        UIManager.put("TableHeader.bottomSeparatorColor", BORDER_STRONG);
        Object headerHeight = UIManager.get("TableHeader.height");
        if (headerHeight instanceof Integer height) {
            UIManager.put("TableHeader.height", Math.max(height, 32));
        }
    }

    /** 进度条：主色填充、浅色槽、圆角 */
    private static void setupProgressBar() {
        UIManager.put("ProgressBar.foreground", ACCENT);
        UIManager.put("ProgressBar.background", new Color(0xE6EBF3));
        UIManager.put("ProgressBar.arc", 6);
        Object horizontalSize = UIManager.get("ProgressBar.horizontalSize");
        if (horizontalSize instanceof Dimension size) {
            UIManager.put("ProgressBar.horizontalSize", new Dimension(size.width, Math.max(size.height, 6)));
        }
    }

    /** 滚动条：细滑块、浅槽 */
    private static void setupScrollBar() {
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollBar.track", SURFACE_ALT);
        UIManager.put("ScrollBar.thumb", BORDER_STRONG);
        UIManager.put("ScrollBar.hoverThumbColor", new Color(0x94A3B8));
        UIManager.put("ScrollBar.pressedThumbColor", TEXT_SECONDARY);
    }

    /** 注册主操作 / 危险操作的按钮样式类 */
    private static void setupStyles() {
        UIManager.put("[style]Button." + STYLE_PRIMARY,
                "background: #2563EB; foreground: #FFFFFF;"
                        + "hoverBackground: #1D4ED8; pressedBackground: #1E40AF;"
                        + "focusedBackground: #1D4ED8; selectedBackground: #1D4ED8;"
                        + "borderColor: #2563EB; hoverBorderColor: #1D4ED8; focusedBorderColor: #1D4ED8;"
                        + "arc: 8");
        UIManager.put("[style]Button." + STYLE_DANGER,
                "background: #FFFFFF; foreground: #DC2626;"
                        + "hoverBackground: #FEF2F2; pressedBackground: #FEE2E2;"
                        + "focusedBackground: #FEF2F2; selectedBackground: #FEE2E2;"
                        + "borderColor: #FCA5A5; hoverBorderColor: #DC2626; focusedBorderColor: #DC2626;"
                        + "arc: 8");
    }

    /** 主操作按钮（蓝底白字）；禁用时自动回落为常规灰底 */
    public static void primary(JButton button) {
        applySemantic(button, STYLE_PRIMARY);
    }

    /** 危险操作按钮（红字描边）；禁用时自动回落为常规灰底 */
    public static void danger(JButton button) {
        applySemantic(button, STYLE_DANGER);
    }

    /**
     * 挂载语义样式，并监听 enabled：FlatLaf 的样式类不会自动处理禁用态，
     * 若不摘除，"开始导入"在导入中置灰时仍会是蓝底。
     */
    private static void applySemantic(JButton button, String styleClass) {
        button.putClientProperty(FlatClientProperties.STYLE_CLASS, styleClass);
        button.addPropertyChangeListener("enabled", event -> {
            boolean enabled = Boolean.TRUE.equals(event.getNewValue());
            button.putClientProperty(FlatClientProperties.STYLE_CLASS, enabled ? styleClass : null);
            button.repaint();
        });
    }

    /** 卡片标题边框：统一标题字重与描边颜色 */
    public static Border cardBorder(String title) {
        return BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(BORDER, 1),
                title,
                TitledBorder.LEFT,
                TitledBorder.DEFAULT_POSITION,
                titleFont(),
                TEXT_PRIMARY);
    }

    /** 次要说明文字（副标题、统计、提示） */
    public static void muted(JLabel label) {
        label.setForeground(TEXT_SECONDARY);
    }

    /** 让面板跟随窗口底色（用于窗口根面板、标题栏、操作栏等卡片之外的区域） */
    public static void appBackground(JComponent component) {
        component.setOpaque(true);
        component.setBackground(APP_BG);
    }

    /** 常规正文字体 */
    public static Font bodyFont() {
        Font font = bodyFont;
        return font != null ? font : UIManager.getFont("Label.font");
    }

    /** 加粗字体（卡片标题、表头） */
    public static Font boldFont() {
        Font font = boldFont;
        return font != null ? font : UIManager.getFont("Label.font").deriveFont(Font.BOLD);
    }

    /** 卡片标题字体 */
    public static Font titleFont() {
        return boldFont();
    }

    /** 等宽字体（日志、DDL） */
    public static Font monoFont() {
        Font font = monoFont;
        return font != null ? font : new Font(Font.MONOSPACED, Font.PLAIN, 13);
    }
}
