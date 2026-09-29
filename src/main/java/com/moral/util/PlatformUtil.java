package com.moral.util;

import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import java.awt.Font;
import java.io.File;
import java.util.Enumeration;

/**
 * 跨平台适配：字体、默认目录、系统判断。
 * Windows 与 macOS/Linux 均可运行。
 */
public final class PlatformUtil {

    /** 默认 CSV 目录候选（按当前系统挑选第一个存在的目录） */
    private static final String[] MAC_CANDIDATES = {
            "/Users/liuxiaocheng/PDMS/projects/pdms-data-unity-backend/E:/oracleToCSV/kbe"
    };
    private static final String[] WIN_CANDIDATES = {
            "E:\\oracleToCSV\\kbe",
            "D:\\oracleToCSV\\kbe"
    };

    private PlatformUtil() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /**
     * 初始化全局 UI 环境：中文字体、全局异常兜底。
     * 必须在设置 LookAndFeel 之后调用（字体替换会覆盖外观自带的字体）。
     */
    public static void init() {
        setupFont();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            throwable.printStackTrace();
            SwingUtil.showError(null, "程序发生未捕获异常：" + throwable.getMessage());
        });
    }

    /** 按系统选择可用的中文字体，避免 Windows 上中文显示为方块 */
    public static String resolveFontFamily() {
        String[] candidates;
        if (isWindows()) {
            candidates = new String[]{"Microsoft YaHei UI", "Microsoft YaHei", "SimSun", "Segoe UI"};
        } else if (isMacOs()) {
            candidates = new String[]{"PingFang SC", "Heiti SC", "Lucida Grande"};
        } else {
            candidates = new String[]{"Noto Sans CJK SC", "WenQuanYi Zen Hei", "Dialog"};
        }
        String[] available = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        for (String candidate : candidates) {
            for (String family : available) {
                if (family.equalsIgnoreCase(candidate)) {
                    return family;
                }
            }
        }
        return Font.DIALOG;
    }

    /** 全局替换 Swing 默认字体（切换主题后需重新调用） */
    public static void setupFont() {
        String family = resolveFontFamily();
        Enumeration<Object> keys = UIManager.getDefaults().keys();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            Object value = UIManager.get(key);
            if (value instanceof FontUIResource) {
                FontUIResource old = (FontUIResource) value;
                UIManager.put(key, new FontUIResource(family, old.getStyle(), old.getSize()));
            } else if (value instanceof Font) {
                Font old = (Font) value;
                UIManager.put(key, new FontUIResource(family, old.getStyle(), old.getSize()));
            }
        }
    }

    /** 默认 CSV 目录：存在则返回，否则返回用户主目录 */
    public static String defaultCsvDir() {
        String[] candidates = isWindows() ? WIN_CANDIDATES : MAC_CANDIDATES;
        for (String path : candidates) {
            File dir = new File(path);
            if (dir.isDirectory()) {
                return dir.getAbsolutePath();
            }
        }
        return System.getProperty("user.home");
    }

    /** 打开系统文件管理器（Windows 资源管理器 / macOS Finder） */
    public static void revealInExplorer(File file) {
        try {
            if (isWindows()) {
                Runtime.getRuntime().exec(new String[]{"explorer.exe", file.getAbsolutePath()});
            } else if (isMacOs()) {
                Runtime.getRuntime().exec(new String[]{"open", file.getAbsolutePath()});
            }
        } catch (Exception ignore) {
            // 打开失败不影响主流程
        }
    }

    /** 提示 Windows 上常见的文件被占用问题 */
    public static String describeFileLockHint(Throwable error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (isWindows() && (message.contains("另一个程序") || message.toLowerCase().contains("being used by another process"))) {
            return "（Windows 提示：文件可能被 Excel 或其他程序占用，请关闭后重试）";
        }
        return "";
    }

    /** 弹窗兜底使用前确保 Swing 可用 */
    public static void warnIfNoGui() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, "当前环境无图形界面，无法启动桌面程序");
        }
    }
}
